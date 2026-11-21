package com.aoooa.adb.log

import java.io.FileWriter
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import android.content.Context
import com.aoooa.adb.ui.i18n.I18n
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class AppFileLogger(private val debugLog: (String) -> Unit, private val appendUiLog: (String) -> Unit, private val timeStamp: () -> String) {
    private val LOG_TAG = "aoooa-adb"
    /** 单文件日志体积上限，达到后切换到新的日志文件。 */
    private val SINGLE_LOG_MAX_BYTES = 8L * 1024 * 1024
    /** 触发一次容量轮转的最小新增写入量。 */
    private val ROTATE_TRIGGER_BYTES = 2L * 1024 * 1024
    /** 两次容量轮转的最小间隔。 */
    private val ROTATE_MIN_INTERVAL_MS = 5L * 60 * 1000
    /** 日志文件未就绪时允许缓存的最大行数。 */
    private val PENDING_LOG_MAX_LINES = 2000
    /** 单次批量刷盘的最大日志条数，控制单次持锁时长。 */
    private val LOG_BATCH_MAX = 200
    @Volatile
    private var logWriter: FileWriter? = null
    private var logFile: File? = null
    /** 后台日志写线程：将写盘操作从调用线程（含主线程）剥离。 */
    private var logWriterThread: Thread? = null
    /** 待写日志队列，容量受限，写线程拥堵时丢弃最早内容并计数。 */
    private val logQueue = java.util.concurrent.LinkedBlockingQueue<String>(5000)
    private val queueDroppedLines = AtomicLong(0L)
    /** 日志写入锁：保证多线程写入、文件切换与句柄关闭之间的原子性。 */
    private val logLock = Any()
    /** 日志文件就绪前产生的日志行，初始化完成后按序回放。 */
    private val pendingLogLines = ArrayDeque<String>()
    private val pendingLock = Any()
    @Volatile
    private var pendingDroppedLines = 0
    /** 当前日志文件累计写入字节数。 */
    private val bytesInCurrentFile = AtomicLong(0L)
    /** 上次容量轮转后累计写入字节数。 */
    private val bytesSinceRotate = AtomicLong(0L)
    private val lastRotateAt = AtomicLong(0L)
    private val maintenanceBusy = AtomicBoolean(false)
    @Volatile
    private var fileLogFailureReported = false

    private var appContext: Context? = null

    /** 初始化文件日志（记录于 Android/data/com.aoooa.adb/files/logs/，异步执行避免主线程阻塞） */
    fun initFileLog(context: Context) {
        appContext = context.applicationContext
        Thread {
            try {
                val file = openLogFile(context)
                fileLog("=== aoooa-adb 完整调试日志开始 (${file.absolutePath}) ===")
                // 回放初始化期间产生的日志，避免启动阶段日志缺失
                flushPendingLogs()
                // 启动时执行一次历史日志淘汰（存活期 + 容量两个维度）
                com.aoooa.adb.util.LogRotator.rotate(context, file) { msg -> debugLog(msg) }
                lastRotateAt.set(System.currentTimeMillis())

                AppCrashRecorder.install(::flushLogsBlocking, ::fileLogSync)
            } catch (e: Exception) {
                // 文件日志不可用时降级到 Logcat，不阻断主功能
                android.util.Log.e(LOG_TAG, "文件日志初始化失败: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }.start()
    }

    /**
     * 打开新的日志文件并接管写入句柄。
     * 同一秒内重复初始化时追加序号，避免多个写入句柄指向同一文件。
     */
    @Synchronized
    private fun openLogFile(context: Context): File {
        val logDir = context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs")
        if (!logDir.exists() && !logDir.mkdirs()) {
            android.util.Log.w(LOG_TAG, "日志目录创建失败: ${logDir.absolutePath}")
        }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        var target = File(logDir, "aoooa-adb_$ts.log")
        var seq = 1
        while (target.exists()) {
            target = File(logDir, "aoooa-adb_${ts}_$seq.log")
            seq++
        }
        val versionHeader = com.aoooa.adb.util.AppVersionInfo.read(context).logHeader()
        val writer = FileWriter(target, true)
        try {
            writer.write(versionHeader)
            writer.flush()
        } catch (error: Exception) {
            runCatching { writer.close() }
            throw error
        }
        synchronized(logLock) {
            val previous = logWriter
            logWriter = writer
            logFile = target
            bytesInCurrentFile.set(target.length())
            bytesSinceRotate.set(0L)
            fileLogFailureReported = false
            if (previous != null) {
                try {
                    previous.flush()
                    previous.close()
                } catch (e: Exception) {
                    android.util.Log.w(LOG_TAG, "旧日志句柄关闭失败: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
        startLogWriterIfNeeded()
        return target
    }

    /**
     * 启动后台日志写线程。
     * 写线程按批取队列内容写入并统一次刷盘，调用线程不再承担磁盘 IO 开销。
     */
    private fun startLogWriterIfNeeded() {
        synchronized(logLock) {
            if (logWriterThread != null) return
            val worker = Thread {
                while (true) {
                    try {
                        val first = logQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                        synchronized(logLock) {
                            if (first != null) {
                                writeLineLocked(first)
                                var batch = 1
                                while (batch < LOG_BATCH_MAX) {
                                    val next = logQueue.poll() ?: break
                                    writeLineLocked(next)
                                    batch++
                                }
                            }
                            flushWriterLocked()
                            maybeStartMaintenanceLocked()
                        }
                    } catch (e: InterruptedException) {
                        return@Thread
                    } catch (e: Exception) {
                        android.util.Log.e(LOG_TAG, "日志写线程异常: ${e.javaClass.simpleName}: ${e.message}", e)
                    }
                }
            }
            worker.isDaemon = true
            worker.name = "adb-log-writer"
            logWriterThread = worker
            worker.start()
        }
    }

    /** 在持有 logLock 的前提下写入一行并累计体积。 */
    private fun writeLineLocked(line: String) {
        val writer = logWriter ?: return
        try {
            writer.write(line)
            writer.write("\n")
            val increment = line.length.toLong() + 1L
            bytesInCurrentFile.addAndGet(increment)
            bytesSinceRotate.addAndGet(increment)
        } catch (e: Exception) {
            reportFileLogFailure(e)
        }
    }

    /** 在持有 logLock 的前提下刷盘。 */
    private fun flushWriterLocked() {
        try {
            logWriter?.flush()
        } catch (e: Exception) {
            reportFileLogFailure(e)
        }
    }

    /** 在持有 logLock 的前提下判断是否需要切换文件或执行容量轮转。 */
    private fun maybeStartMaintenanceLocked() {
        if (maintenanceBusy.get()) return
        if (bytesInCurrentFile.get() >= SINGLE_LOG_MAX_BYTES) {
            scheduleLogMaintenance(rollover = true)
            return
        }
        val now = System.currentTimeMillis()
        if (bytesSinceRotate.get() >= ROTATE_TRIGGER_BYTES &&
            now - lastRotateAt.get() >= ROTATE_MIN_INTERVAL_MS
        ) {
            scheduleLogMaintenance(rollover = false)
        }
    }

    /**
     * 排空待写队列并将当前内容刷入磁盘。
     * 仅用于崩溃、退出等需要保证日志已持久化的场景。
     */
    private fun flushLogsBlocking() {
        if (logWriter == null) return
        val deadline = System.currentTimeMillis() + 2000
        while (!logQueue.isEmpty() && System.currentTimeMillis() < deadline) {
            val line = logQueue.poll() ?: break
            synchronized(logLock) { writeLineLocked(line) }
        }
        synchronized(logLock) { flushWriterLocked() }
    }

    /** 直接写入并立即刷盘，避免崩溃现场信息滞留在队列中。 */
    private fun fileLogSync(line: String) {
        synchronized(logLock) {
            writeLineLocked(line)
            flushWriterLocked()
        }
    }

    /** 将文件就绪前缓存的日志行按序写入。 */
    private fun flushPendingLogs() {
        val snapshot = synchronized(pendingLock) {
            val list = pendingLogLines.toList()
            val droppedCount = pendingDroppedLines
            pendingLogLines.clear()
            pendingDroppedLines = 0
            list to droppedCount
        }
        val pending = snapshot.first
        val dropped = snapshot.second
        if (pending.isEmpty() && dropped == 0) return
        fileLog("=== 回放初始化前缓存日志 ${pending.size} 行 ===")
        for (line in pending) fileLog(line)
        if (dropped > 0) {
            fileLog("=== 缓存日志超出上限，已丢弃最早的 $dropped 行 ===")
        }
    }

    /** 文件未就绪时缓存日志行，超出上限时丢弃最早的行并计数。 */
    private fun stashPendingLog(line: String) {
        synchronized(pendingLock) {
            if (pendingLogLines.size >= PENDING_LOG_MAX_LINES) {
                pendingLogLines.removeFirst()
                pendingDroppedLines++
            }
            pendingLogLines.addLast(line)
        }
    }

    /**
     * 写入一行到文件日志。
     * 文件未就绪时进入内存暂存队列；就绪后入待写队列由写线程落盘，调用线程不做磁盘 IO。
     */
    fun fileLog(line: String) {
        if (logWriter == null) {
            stashPendingLog(line)
            return
        }
        if (!logQueue.offer(line)) {
            val dropped = queueDroppedLines.incrementAndGet()
            // 队列溢出属于异常状态，每累计 1000 条通过 Logcat 告警一次，避免完全静默
            if (dropped % 1000L == 1L) {
                android.util.Log.w(LOG_TAG, "日志队列已满，累计丢弃 $dropped 行（写盘速度低于产生速度）")
            }
        }
    }

    /**
     * 上报文件日志写入失败。
     * 首次失败向 Logcat 与界面各输出一次，后续失败仅写 Logcat，避免刷屏。
     */
    private fun reportFileLogFailure(e: Exception) {
        val detail = String.format(I18n.current.logFileWriteFailed, "${e.javaClass.simpleName}: ${e.message}")
        android.util.Log.e(LOG_TAG, detail, e)
        if (fileLogFailureReported) return
        fileLogFailureReported = true
        appendUiLog("[${timeStamp()}] $detail")
    }

    /**
     * 在后台线程执行日志维护：先按需切换文件，再执行容量轮转。
     * 同一时刻仅允许一个维护任务，重复请求直接丢弃。
     */
    private fun scheduleLogMaintenance(rollover: Boolean) {
        if (!maintenanceBusy.compareAndSet(false, true)) return
        Thread {
            try {
                val context = appContext
                if (context == null) {
                    android.util.Log.w(LOG_TAG, "日志维护跳过：应用上下文未就绪")
                    return@Thread
                }
                if (rollover) rolloverLogFile(context)
                com.aoooa.adb.util.LogRotator.rotate(context, logFile) { msg -> debugLog(msg) }
                lastRotateAt.set(System.currentTimeMillis())
                bytesSinceRotate.set(0L)
            } catch (e: Exception) {
                debugLog("[日志维护] 执行异常: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                maintenanceBusy.set(false)
            }
        }.also { it.isDaemon = true }.start()
    }

    /** 当前日志文件达到单文件上限时切换到新文件，旧文件交由容量轮转淘汰。 */
    private fun rolloverLogFile(context: Context) {
        try {
            val file = openLogFile(context)
            fileLog("=== 单个日志文件达到上限，已切换至 ${file.name} ===")
        } catch (e: Exception) {
            debugLog("[日志维护] 日志文件切换失败: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 获取日志文件路径（供用户查看） */
    fun getLogFile(): File? = synchronized(logLock) { logFile }

    @Synchronized
    internal fun deleteHistoricalLog(file: File): Boolean = synchronized(logLock) {
        val context = appContext ?: return@synchronized false
        if (file == logFile || file !in com.aoooa.adb.util.LogRotator.logFiles(context)) return@synchronized false
        file.delete()
    }

    /** 清空所有本地日志文件 */
    fun clearLocalLogs(context: Context) {
        synchronized(this) {
            synchronized(logLock) {
                try {
                    logWriter?.flush()
                } catch (e: Exception) {
                    android.util.Log.w(LOG_TAG, "清空日志前刷新失败: ${e.javaClass.simpleName}: ${e.message}")
                }
                try {
                    logWriter?.close()
                } catch (e: Exception) {
                    android.util.Log.w(LOG_TAG, "清空日志前关闭句柄失败: ${e.javaClass.simpleName}: ${e.message}")
                }
                logWriter = null
            }

            val dirs = listOfNotNull(
                context.getExternalFilesDir("logs"),
                File(context.filesDir, "logs")
            )
            for (dir in dirs) {
                if (dir.exists() && dir.isDirectory) {
                    dir.listFiles()?.forEach { file ->
                        // 删除失败需要可见，否则界面显示已清空而磁盘仍有残留
                        if (file.isFile && !file.delete()) {
                            android.util.Log.w(LOG_TAG, "日志文件删除失败: ${file.name}")
                        }
                    }
                }
            }
            initFileLog(context)
        }
    }

}
