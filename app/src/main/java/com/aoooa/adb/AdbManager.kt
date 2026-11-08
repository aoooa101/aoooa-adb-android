package com.aoooa.adb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.aoooa.adb.adb.AdbConnection
import com.aoooa.adb.adb.disableTcpip
import com.aoooa.adb.adb.enableTcpip
import com.aoooa.adb.adb.installStream
import com.aoooa.adb.adb.pushFile
import com.aoooa.adb.adb.shell
import com.aoooa.adb.app.ApkExporter
import com.aoooa.adb.bridge.Channel
import com.aoooa.adb.bridge.TcpChannel
import com.aoooa.adb.bridge.UsbChannel
import com.aoooa.adb.model.TerminalLine
import com.aoooa.adb.ui.i18n.I18n
import com.aoooa.adb.util.RamInfo
import com.aoooa.adb.util.SizeFormat
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 控制台终端模式：
 * SHELL: 设备远程交互式 Shell 终端
 * LOG: 实时日志查看与筛选
 */
enum class TerminalMode {
    SHELL, LOG, CONTROL, APP_MANAGER, FILE_MANAGER
}

/**
 * ADB 连接管理器。
 * 管理传输层（USB/TCP）+ AdbConnection 协议层 + Compose 状态。
 * 界面终端仅显示核心状态日志与命令返回，所有底层技术细节全量记录于本地文件日志。
 */
object AdbManager {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 连接状态 */
    val connected = mutableStateOf(false)
    val isFastbootMode = mutableStateOf(false)
    val deviceName = mutableStateOf("")
    val model = mutableStateOf("")
    val os = mutableStateOf("")
    val battery = mutableStateOf("")
    val selinux = mutableStateOf("")

    /** 5555 无线调试开启状态 */
    val isTcpip5555Enabled = mutableStateOf(false)

    /** 动态捕获到的已配对无线调试主端口（Android 11+ _adb-tls-connect） */
    val discoveredDebugHost = mutableStateOf("")
    val discoveredDebugPort = mutableStateOf(0)

    /** 终端基础日志（供用户界面查看，支持多语言国际化） */
    val logs = mutableStateListOf<String>()

    /** 交互式控制台全局输出流缓冲区 */
    val terminalLines = mutableStateListOf<TerminalLine>()
    val isInteractiveActive = mutableStateOf(false)

    /** 控制台当前终端模式（SHELL 终端 / LOG 日志） */
    val currentTerminalMode = mutableStateOf(TerminalMode.SHELL)

    @Volatile
    private var hasPendingLine = false

    /**
     * 终端流式字符处理器：
     * 1. 支持跨 TCP/USB 数据包的不完整 ANSI 转义序列自动续接；
     * 2. 保留 ANSI SGR 颜色代码 (\u001B[...m)，过滤光标移动与控制码；
     * 3. 处理 \r\n 换行、\r 行首重绘与 \b / 0x7F 退格；
     * 4. 处理控制字符与乱码过滤。
     */
    object TerminalStreamProcessor {
        private val buffer = StringBuilder()
        private var pendingEscape = "" // 跨数据块暂存的未闭合转义序列

        @Synchronized
        fun process(raw: String): Pair<List<String>, String> {
            val completedLines = mutableListOf<String>()
            val input = if (pendingEscape.isNotEmpty()) {
                val combined = pendingEscape + raw
                pendingEscape = ""
                combined
            } else {
                raw
            }

            var i = 0
            val len = input.length

            while (i < len) {
                val c = input[i]
                when {
                    // 处理 ANSI 转义控制码 (\u001B 开头)
                    c == '\u001B' -> {
                        val start = i
                        i++
                        if (i >= len) {
                            // 序列在 \u001B 处被切断，暂存等待下一个数据块
                            pendingEscape = input.substring(start)
                            break
                        }

                        val next = input[i]
                        when (next) {
                            '[' -> {
                                // CSI 序列: \u001B[ ... <cmd>
                                i++
                                while (i < len && (input[i] in '0'..'9' || input[i] == ';' || input[i] == '?' || input[i] == '>' || input[i] == ' ' || input[i] == '!')) {
                                    i++
                                }
                                if (i >= len) {
                                    // CSI 序列不完整，暂存
                                    pendingEscape = input.substring(start)
                                    break
                                }
                                val cmd = input[i]
                                i++
                                val seq = input.substring(start, i)
                                if (cmd == 'm') {
                                    // SGR 颜色控制码无损保留供 UI 高亮
                                    buffer.append(seq)
                                } else if (cmd == 'K' || cmd == 'J') {
                                    // 清行 / 清屏序列处理：如果是在当前行，且是 2K (清除整行) 则清空 buffer
                                    if (seq.contains("2K")) {
                                        buffer.clear()
                                    }
                                }
                                // 其他光标控制 (H, f, A, B, C, D, h, l 等) 安全吸收过滤
                            }
                            ']' -> {
                                // OSC 序列: \u001B] ... (\u0007 | \u001B\)
                                i++
                                var oscClosed = false
                                while (i < len) {
                                    if (input[i] == '\u0007') {
                                        i++
                                        oscClosed = true
                                        break
                                    } else if (input[i] == '\u001B' && i + 1 < len && input[i + 1] == '\\') {
                                        i += 2
                                        oscClosed = true
                                        break
                                    }
                                    i++
                                }
                                if (!oscClosed) {
                                    pendingEscape = input.substring(start)
                                    break
                                }
                            }
                            '(', ')', '*', '+' -> {
                                // 指定字符集序列 (如 \u001B(B)
                                i++
                                if (i < len) {
                                    i++ // 吸收字符集标识
                                } else {
                                    pendingEscape = input.substring(start)
                                    break
                                }
                            }
                            else -> {
                                // 单字节转义 (如 \u001BM, \u001B7, \u001B8 等)，安全吸收
                                i++
                            }
                        }
                    }
                    // \r\n 换行
                    c == '\r' && i + 1 < len && input[i + 1] == '\n' -> {
                        completedLines.add(buffer.toString())
                        buffer.clear()
                        i += 2
                    }
                    // 单独的 \n 换行
                    c == '\n' -> {
                        completedLines.add(buffer.toString())
                        buffer.clear()
                        i++
                    }
                    // 单独的 \r 回车（行首光标重置）
                    c == '\r' -> {
                        if (i + 1 >= len) {
                            pendingEscape = "\r"
                            break
                        }
                        i++
                    }
                    // \b 退格 (Backspace) 与 0x7F (DEL)
                    c == '\b' || c.code == 0x7F -> {
                        deleteLastVisibleChar(buffer)
                        i++
                    }
                    // 忽略其他不可打印控制字节（保留制表符 \t）
                    c.code < 32 && c != '\t' -> {
                        i++
                    }
                    else -> {
                        buffer.append(c)
                        i++
                    }
                }
            }
            return completedLines to buffer.toString()
        }

        /** 安全删除最后一个可见字符，避免截断 ANSI 颜色序列 */
        private fun deleteLastVisibleChar(sb: StringBuilder) {
            if (sb.isEmpty()) return
            // 如果末尾正好是一个 ANSI 颜色序列，例如 \u001B[32m，先跳过该序列再去删正文字符
            if (sb.endsWith("m")) {
                val escIdx = sb.lastIndexOf("\u001B[")
                if (escIdx != -1) {
                    val escSeq = sb.substring(escIdx)
                    sb.delete(escIdx, sb.length)
                    deleteLastVisibleChar(sb)
                    sb.append(escSeq) // 恢复颜色
                    return
                }
            }
            sb.deleteCharAt(sb.length - 1)
        }

        @Synchronized
        fun clear() {
            buffer.clear()
            pendingEscape = ""
        }
    }

    @Volatile
    private var channel: Channel? = null
    @Volatile
    var connection: AdbConnection? = null
        private set
    @Volatile
    private var fastbootClient: com.aoooa.adb.fastboot.FastbootClient? = null
    @Volatile
    private var currentUsbDevice: UsbDevice? = null
    @Volatile
    private var isConnecting = false

    /** 连接流程代际号：发起连接或执行断开时递增，用于作废在途的握手流程。 */
    private val connectGeneration = AtomicInteger(0)

    /** 单文件日志体积上限，达到后切换到新的日志文件。 */
    private const val SINGLE_LOG_MAX_BYTES = 8L * 1024 * 1024
    /** 触发一次容量轮转的最小新增写入量。 */
    private const val ROTATE_TRIGGER_BYTES = 2L * 1024 * 1024
    /** 两次容量轮转的最小间隔。 */
    private const val ROTATE_MIN_INTERVAL_MS = 5L * 60 * 1000
    /** 日志文件未就绪时允许缓存的最大行数。 */
    private const val PENDING_LOG_MAX_LINES = 2000
    /** 单次批量刷盘的最大日志条数，控制单次持锁时长。 */
    private const val LOG_BATCH_MAX = 200
    /** 界面终端日志保留的最大行数。 */
    private const val UI_LOG_MAX_LINES = 300
    /** 终端输出保留的最大行数。 */
    private const val TERMINAL_MAX_LINES = 3000
    /** 列表裁剪时，超过该溢出量改用整体重建，避免逐条移除造成大量复制。 */
    private const val TRIM_REBUILD_THRESHOLD = 32
    private const val LOG_TAG = "aoooa-adb"

    /** 电池电量解析（预编译，避免每次连接重复构造正则）。 */
    private val BATTERY_LEVEL_PATTERN = Regex("level:\\s*(\\d+)")

    @Volatile
    private var logWriter: FileWriter? = null
    private var logFile: File? = null
    /** 后台日志写线程：将写盘操作从调用线程（含主线程）剥离。 */
    private var logWriterThread: Thread? = null
    /** 待写日志队列，容量受限，写线程拥堵时丢弃最早内容并计数。 */
    private val logQueue = java.util.concurrent.LinkedBlockingQueue<String>(5000)
    private val queueDroppedLines = AtomicLong(0L)
    /** 连接资源释放线程：底层端点注销属于阻塞操作，不在主线程执行。 */
    private val releaseExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "adb-release").apply { isDaemon = true }
    }

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
        com.aoooa.adb.adb.AdbServerProxy.start()
        Thread {
            try {
                val file = openLogFile(context)
                fileLog("=== aoooa-adb 完整调试日志开始 (${file.absolutePath}) ===")
                // 回放初始化期间产生的日志，避免启动阶段日志缺失
                flushPendingLogs()
                // 启动时执行一次历史日志淘汰（存活期 + 容量两个维度）
                com.aoooa.adb.util.LogRotator.rotate(context, file) { msg -> debugLog(msg) }
                lastRotateAt.set(System.currentTimeMillis())

                // 注册全局未捕获异常崩溃拦截器（记录所有线程崩溃堆栈与环境信息）
                val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
                Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                    try {
                        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
                        val crashMsg = buildString {
                            appendLine("\n==================== 崩溃异常捕获 (CRASH) ====================")
                            appendLine("[$time] 触发线程: ${thread.name} (ID: ${thread.id})")
                            appendLine("[$time] 设备型号: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (${android.os.Build.PRODUCT})")
                            appendLine("[$time] 系统版本: Android ${android.os.Build.VERSION.RELEASE} (SDK API ${android.os.Build.VERSION.SDK_INT})")
                            appendLine("[$time] 异常类型: ${throwable.javaClass.name}")
                            appendLine("[$time] 异常信息: ${throwable.message}")
                            appendLine("[$time] 完整调用栈:")
                            appendLine(throwable.stackTraceToString())
                            appendLine("=================================================================\n")
                        }
                        // 崩溃前先将队列中已产生的日志落盘，保证现场日志完整
                        flushLogsBlocking()
                        fileLogSync(crashMsg)
                    } catch (e: Exception) {
                        // 崩溃现场写入文件失败时降级到 Logcat，保留原始堆栈
                        android.util.Log.e(LOG_TAG, "崩溃日志写入失败: ${e.javaClass.simpleName}: ${e.message}", e)
                        android.util.Log.e(LOG_TAG, "原始崩溃: ${throwable.javaClass.name}: ${throwable.message}", throwable)
                    } finally {
                        defaultHandler?.uncaughtException(thread, throwable)
                    }
                }
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
        val writer = FileWriter(target, true)
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

    /** 日志时间戳格式器：SimpleDateFormat 非线程安全且构造昂贵，按线程缓存复用。 */
    private val logTimeFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    }

    /** 取当前时间的日志时间戳（线程安全复用格式器，避免每行新建）。 */
    private fun timeStamp(): String = logTimeFormat.get()!!.format(Date())

    /** 写入用户界面终端基础日志（同时归档至文件日志） */
    fun log(msg: String) {
        val line = "[${timeStamp()}] $msg"
        appendUiLog(line)
        fileLog(line)
    }

    /** 写入底层技术调试日志（仅记录于文件日志，保持界面终端清爽） */
    fun debugLog(msg: String) {
        fileLog("[${timeStamp()}] $msg")
    }

    /**
     * 追加一行到界面终端日志。
     * SnapshotStateList 必须在主线程修改，否则 LogPanel 遍历时会触发 ConcurrentModificationException。
     */
    private fun appendUiLog(line: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            appendUiLogInternal(line)
        } else {
            mainHandler.post { appendUiLogInternal(line) }
        }
    }

    private fun appendUiLogInternal(line: String) {
        logs.add(line)
        // 一次性裁剪到上限：逐条 removeAt(0) 会产生多次快照失效，整体重建只触发一次刷新
        trimLogList(logs, UI_LOG_MAX_LINES)
    }

    /**
     * 将快照列表裁剪到指定上限，保留最近的条目。
     *
     * 溢出少量时逐条移除（元素少、复制成本低），溢出较多时整体重建，
     * 避免大量溢出时出现 O(n²) 复制与逐条快照失效。
     */
    private fun <T> trimLogList(list: androidx.compose.runtime.snapshots.SnapshotStateList<T>, limit: Int) {
        val overflow = list.size - limit
        if (overflow <= 0) return
        if (overflow > TRIM_REBUILD_THRESHOLD) {
            val kept = list.takeLast(limit)
            list.clear()
            list.addAll(kept)
        } else {
            repeat(overflow) { list.removeAt(0) }
        }
    }

    /**
     * 写入一行到文件日志。
     * 文件未就绪时进入内存暂存队列；就绪后入待写队列由写线程落盘，调用线程不做磁盘 IO。
     */
    private fun fileLog(line: String) {
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
    fun getLogFile(): File? = logFile

    /** 用 USB 设备建立连接（在后台线程执行），防重入 */
    fun connectUsb(context: Context, device: UsbDevice) {
        val generation = synchronized(this) {
            if (connected.value || isConnecting) return
            isConnecting = true
            connectGeneration.incrementAndGet()
        }
        Thread {
            var connHolder: AdbConnection? = null
            // 底层 USB 端点通信日志改走 debugLog，避免刷屏界面
            val ch = UsbChannel(
                onData = { data -> connHolder?.onData(data) },
                onStatus = { msg -> debugLog(msg) }
            )
            val conn = AdbConnection(
                channel = ch,
                context = context,
                onLog = { msg -> log(msg) },
                onDebugLog = { msg -> debugLog(msg) }
            )
            connHolder = conn
            // 握手前登记信道与连接：disconnect() 才能在握手期间关闭底层端点
            channel = ch
            connection = conn

            try {
                // 线程启动前已被断开：直接回收，避免登记残留
                if (connectGeneration.get() != generation) {
                    debugLog("USB 连接流程在启动前已被作废")
                    releaseConnectionResources(ch, conn)
                    return@Thread
                }
                val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                log(I18n.current.logConnectingUsb)
                if (!ch.connect(usbManager, device)) {
                    log(I18n.current.logUsbFailed)
                    releaseConnectionResources(ch, conn)
                    return@Thread
                }

                log(I18n.current.logAuthStart)
                if (!conn.connect()) {
                    log(I18n.current.logAuthFailed)
                    releaseConnectionResources(ch, conn)
                    return@Thread
                }

                // 握手期间可能已执行断开或发起新连接，此时不得提交连接成功状态
                val committed = synchronized(this) {
                    if (connectGeneration.get() != generation) {
                        false
                    } else {
                        connected.value = true
                        true
                    }
                }
                if (!committed) {
                    debugLog("USB 连接流程已作废（握手期间执行了断开或新连接）")
                    releaseConnectionResources(ch, conn)
                    return@Thread
                }

                currentUsbDevice = device
                deviceName.value = device.productName ?: device.deviceName
                loadDeviceInfo(conn)
                log(I18n.current.logConnected)
                ensureInteractiveShell()
            } catch (e: Exception) {
                log("${I18n.current.logUsbFailed}: ${e.message}")
                debugLog("USB 连接异常栈: ${e.stackTraceToString()}")
                releaseConnectionResources(ch, conn)
            } finally {
                synchronized(this) {
                    isConnecting = false
                }
            }
        }.start()
    }

    /** 清除全局登记，仅当全局引用仍指向本次流程创建的对象时才置空。 */
    private fun releaseRegistration(ch: Channel?, conn: AdbConnection?) {
        if (channel === ch) channel = null
        if (connection === conn) connection = null
    }

    /** 释放一次连接流程占用的连接对象与信道，释放异常写入调试日志。 */
    private fun releaseConnectionResources(ch: Channel?, conn: AdbConnection?) {
        releaseRegistration(ch, conn)
        try {
            conn?.disconnect()
        } catch (e: Exception) {
            debugLog("连接释放异常: ${e.javaClass.simpleName}: ${e.message}")
        }
        try {
            ch?.close()
        } catch (e: Exception) {
            debugLog("信道关闭异常: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 用 TCP 建立无线连接（在后台线程执行），防重入：同一时刻只允许一个连接流程 */
    fun connectTcp(context: Context? = null, host: String, port: Int) {
        val actualContext = context ?: appContext
        if (actualContext == null) {
            log(I18n.current.logConnectNoContext)
            return
        }
        val generation = synchronized(this) {
            if (connected.value || isConnecting) return
            isConnecting = true
            connectGeneration.incrementAndGet()
        }
        Thread {
            var connHolder: AdbConnection? = null
            val ch = TcpChannel(
                onData = { data -> connHolder?.onData(data) },
                onStatus = { msg -> debugLog(msg) },
                onDisconnected = { reason ->
                    debugLog("TCP 通道断开: $reason")
                    mainHandler.post {
                        if (connected.value) disconnect()
                    }
                }
            )
            val conn = AdbConnection(
                channel = ch,
                context = actualContext,
                onLog = { msg -> log(msg) },
                onDebugLog = { msg -> debugLog(msg) }
            )
            connHolder = conn
            // 握手前登记信道与连接：disconnect() 才能在握手期间关闭底层套接字
            channel = ch
            connection = conn

            try {
                // 线程启动前已被断开：直接回收，避免登记残留
                if (connectGeneration.get() != generation) {
                    debugLog("TCP 连接流程在启动前已被作废")
                    releaseConnectionResources(ch, conn)
                    return@Thread
                }
                log(String.format(I18n.current.logConnectingTcp, host, port))
                if (!ch.connect(host, port)) {
                    log(I18n.current.logTcpFailed)
                    releaseConnectionResources(ch, conn)
                    return@Thread
                }

                log(I18n.current.logAuthStart)
                if (!conn.connect()) {
                    log(I18n.current.logAuthFailed)
                    releaseConnectionResources(ch, conn)
                    return@Thread
                }

                // 握手期间可能已执行断开或发起新连接，此时不得提交连接成功状态
                val committed = synchronized(this) {
                    if (connectGeneration.get() != generation) {
                        false
                    } else {
                        connected.value = true
                        // 无线会话与 USB 设备无关，清空记录避免拔出旧设备时误判断开
                        currentUsbDevice = null
                        true
                    }
                }
                if (!committed) {
                    debugLog("TCP 连接流程已作废（握手期间执行了断开或新连接）")
                    releaseConnectionResources(ch, conn)
                    return@Thread
                }

                deviceName.value = "$host:$port"
                loadDeviceInfo(conn)
                log(I18n.current.logConnected)
                ensureInteractiveShell()
            } catch (e: Exception) {
                log("${I18n.current.logTcpFailed}: ${e.message}")
                debugLog("TCP 连接异常栈: ${e.stackTraceToString()}")
                releaseConnectionResources(ch, conn)
            } finally {
                synchronized(this) {
                    isConnecting = false
                }
            }
        }.start()
    }


    /** 用 USB 设备建立 Fastboot 连接（在后台线程执行），防重入 */
    fun connectFastboot(context: Context, device: UsbDevice) {
        val generation = synchronized(this) {
            if (connected.value || isConnecting) return
            isConnecting = true
            connectGeneration.incrementAndGet()
        }
        Thread {
            val client = com.aoooa.adb.fastboot.FastbootClient(
                onLog = { msg -> log(msg) },
                onDebugLog = { msg -> debugLog(msg) }
            )
            try {
                val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                log(I18n.current.wiredHint)
                if (!client.connect(usbManager, device)) {
                    log(I18n.current.logFastbootFailed)
                    try {
                        client.disconnect()
                    } catch (e: Exception) {
                        debugLog("Fastboot 连接释放异常: ${e.javaClass.simpleName}: ${e.message}")
                    }
                    return@Thread
                }
                // 握手期间可能已执行断开或发起新连接，此时不得提交连接成功状态
                val committed = synchronized(this) {
                    if (connectGeneration.get() != generation) {
                        false
                    } else {
                        fastbootClient = client
                        isFastbootMode.value = true
                        connected.value = true
                        currentUsbDevice = device
                        true
                    }
                }
                if (!committed) {
                    debugLog("Fastboot 连接流程已作废（握手期间执行了断开或新连接）")
                    try {
                        client.disconnect()
                    } catch (e: Exception) {
                        debugLog("Fastboot 连接释放异常: ${e.javaClass.simpleName}: ${e.message}")
                    }
                    return@Thread
                }
                val devProd = device.productName ?: device.deviceName
                deviceName.value = "Fastboot: $devProd"
                model.value = "Fastboot Device"
                os.value = "Bootloader Mode"
                log(I18n.current.fastbootConnected)

                // 自动抓取 Fastboot 基础信息
                val product = client.execute("getvar:product")
                val unlocked = client.execute("getvar:unlocked")
                if (product.isNotBlank()) debugLog("Product: $product")
                if (unlocked.isNotBlank()) debugLog("Unlocked: $unlocked")
            } catch (e: Exception) {
                log(String.format(I18n.current.logFastbootError, e.message))
                debugLog("Fastboot 连接异常栈: ${e.stackTraceToString()}")
            } finally {
                synchronized(this) {
                    isConnecting = false
                }
            }
        }.start()
    }

    /** 开启 5555 无线调试（adbd 重启，连接会断开） */
    fun enableTcpip() {
        Thread {
            val result = connection?.enableTcpip(5555)
            if (!result.isNullOrBlank()) log(result)
            log(I18n.current.logTcpipRestarting)
            Thread.sleep(300)
            disconnect()
        }.start()
    }

    /** 关闭无线调试端口 */
    fun disableTcpip() {
        Thread {
            val result = connection?.disableTcpip()
            if (!result.isNullOrBlank()) log(result)
            log(I18n.current.logTcpipRestarting)
            Thread.sleep(300)
            disconnect()
        }.start()
    }

    /** 开启或关闭 5555 无线调试（通过 tcpip:5555 / usb: 协议服务） */
    fun setTcpip5555(enable: Boolean) {
        val conn = connection
        if (conn == null) {
            log(I18n.current.logNoDeviceFor5555)
            return
        }
        Thread {
            try {
                if (enable) {
                    log(I18n.current.logTcpip5555Enabling)
                    val result = conn.enableTcpip(5555)
                    if (result.isNotBlank()) log(result)
                    log(I18n.current.logTcpipRestarting)
                    isTcpip5555Enabled.value = true
                    Thread.sleep(300)
                    disconnect()
                } else {
                    log(I18n.current.logTcpip5555Disabling)
                    val result = conn.disableTcpip()
                    if (result.isNotBlank()) log(result)
                    log(I18n.current.logTcpipRestarting)
                    isTcpip5555Enabled.value = false
                    Thread.sleep(300)
                    disconnect()
                }
            } catch (e: Exception) {
                log(String.format(I18n.current.logTcpip5555Error, e.message))
                debugLog("切换 5555 异常栈: ${e.stackTraceToString()}")
            }
        }.start()
    }

    fun pair(host: String, port: Int, code: String) {
        log(String.format(I18n.current.logPairingStart, host, port, code))
        if (port <= 0 || code.length != 6) {
            log(I18n.current.logPairingFailed)
            return
        }
        val ctx = appContext
        if (ctx != null) {
            com.aoooa.adb.pairing.AdbPairing.pair(ctx, host, port, code) { success ->
                if (success) {
                    log(I18n.current.logPairingSuccess)
                } else {
                    log(I18n.current.logPairingFailed)
                }
            }
        }
    }

    /**
     * 读取设备基础信息并刷新界面状态。
     *
     * 全部属性合并到一次 shell 调用中读取：每次 conn.shell 都是一次完整的
     * ADB 流建立与等待退出，串行 7 次会显著拖慢连接完成时间。
     */
    private fun loadDeviceInfo(conn: AdbConnection) {
        val out = conn.shell(
            "getprop ro.product.manufacturer; getprop ro.product.model; " +
                "getprop ro.build.version.release; getprop ro.build.version.sdk; " +
                "getenforce; getprop service.adb.tcp.port; dumpsys battery | grep level | head -1"
        )
        val lines = out.split("\n")
        val manufacturer = lines.getOrNull(0)?.trim().orEmpty()
        val modelName = lines.getOrNull(1)?.trim().orEmpty()
        val release = lines.getOrNull(2)?.trim().orEmpty()
        val sdk = lines.getOrNull(3)?.trim().orEmpty()
        val sel = lines.getOrNull(4)?.trim().orEmpty()
        val tcpPort = lines.getOrNull(5)?.trim().orEmpty()
        val bat = lines.getOrNull(6)?.trim().orEmpty()

        isTcpip5555Enabled.value = (tcpPort.toIntOrNull() ?: -1) > 0
        model.value = "$manufacturer $modelName".trim()
        os.value = if (release.isNotBlank()) "Android $release (API $sdk)" else ""
        selinux.value = sel
        battery.value = BATTERY_LEVEL_PATTERN.find(bat)?.groupValues?.get(1)?.let { "$it%" } ?: ""
    }

    fun exec(cmd: String) {
        if (cmd.isBlank()) return
        if (isFastbootMode.value) {
            val fb = fastbootClient ?: return
            log("> $cmd")
            Thread {
                val result = fb.execute(cmd)
                if (result.isNotBlank()) log(result)
                else log(I18n.current.logNoOutput)
            }.start()
            return
        }
        val conn = connection ?: return
        Thread {
            val result = conn.shell(cmd)
            if (result.isNotBlank()) log(result)
            else log(I18n.current.logNoOutput)
        }.start()
    }

    /** 同步执行命令并捕获返回结果（支持 ADB 与 Fastboot 双模式） */
    fun execCapture(cmd: String): String {
        if (cmd.isBlank()) return ""
        if (isFastbootMode.value) {
            val fb = fastbootClient ?: return I18n.current.statusDisconnected
            return fb.execute(cmd)
        }
        val conn = connection ?: return ""
        return conn.shell(cmd)
    }

    /** 推送文件到设备目标目录 (ADB Push) */
    fun pushFile(context: Context, uri: android.net.Uri, fileName: String, targetDir: String) {
        val conn = connection
        if (conn == null) {
            log(I18n.current.logNeedAdbDevice)
            return
        }
        Thread {
            log(String.format(I18n.current.logPushPreparing, fileName, targetDir))
            var lastPct = -1
            val ok = conn.pushFile(context, uri, fileName, targetDir) { pct, sent, total ->
                val intPct = (pct * 100).toInt()
                if (intPct % 20 == 0 && intPct != lastPct) {
                    lastPct = intPct
                    log(String.format(I18n.current.logPushProgress, intPct, sent / 1024, total / 1024))
                }
            }
            if (ok) {
                log(String.format(I18n.current.logPushSuccess, "$targetDir/$fileName"))
            } else {
                log(I18n.current.logPushFailed)
            }
        }.start()
    }

    /** 流式安装 APK 文件 (无需被控端留存安装包) */
    fun installApk(context: Context, uri: android.net.Uri, fileName: String, useCompatibleMode: Boolean = false) {
        val conn = connection
        if (conn == null) {
            log(I18n.current.logNeedAdbDevice)
            return
        }
        Thread {
            log(String.format(I18n.current.logInstallStreaming, fileName))
            var lastPct = -1
            val result = conn.installStream(context, uri, useCompatibleMode) { pct ->
                val intPct = (pct * 100).toInt()
                if (intPct % 25 == 0 && intPct != lastPct) {
                    lastPct = intPct
                    log(String.format(I18n.current.logInstallWriteProgress, intPct))
                }
            }
            log(result)
        }.start()
    }

    /** Fastboot 刷入单分区镜像 (Fastboot Flash) */
    fun flashPartition(context: Context, uri: android.net.Uri, fileName: String, partition: String) {
        val fb = fastbootClient
        if (fb == null || !isFastbootMode.value) {
            log(I18n.current.logNeedFastbootDevice)
            return
        }
        Thread {
            log(String.format(I18n.current.logFlashPreparing, fileName, partition))
            var lastPct = -1
            val result = fb.flashPartitionImage(context, uri, partition) { pct ->
                val intPct = (pct * 100).toInt()
                if (intPct % 25 == 0 && intPct != lastPct) {
                    lastPct = intPct
                    log(String.format(I18n.current.logFlashProgress, intPct))
                }
            }
            log(result)
        }.start()
    }

    /** 交互式控制台当前工作路径状态（默认根目录，支持 cd 实时动态跟随） */
    val currentWorkingDir = mutableStateOf("/")

    /** 获取当前设备的标准 Shell 提示符（如 shell@OPPO:/ $ 或 root@OPPO:/sdcard #） */
    fun getShellPrompt(): String {
        val dev = deviceName.value.ifBlank { "android" }
        val isRoot = selinux.value.equals("Permissive", ignoreCase = true) || model.value.contains("root", ignoreCase = true)
        val user = if (isRoot) "root" else "shell"
        val symbol = if (isRoot) "#" else "$"
        val path = currentWorkingDir.value.ifBlank { "/" }
        return "$user@$dev:$path $symbol "
    }

    /**
     * 向终端发送用户输入的整行命令（自动补齐回车换行），自适应 ADB 交互 PTY / Fastboot 单次模式
     */
    fun sendTerminalInput(cmd: String) {
        val trimmed = cmd.trim()
        if (isFastbootMode.value) {
            execTerminal(trimmed)
            return
        }
        val conn = connection
        if (conn == null || !conn.isAuthenticated) {
            mainHandler.post { terminalLines.add(TerminalLine(text = I18n.current.termNotConnected)) }
            return
        }
        Thread {
            ensureInteractiveShell()
            // 发送命令文本 + \n (Linux 标准换行执行)，确保触发远程 Shell 换行回显与命令输出分行
            conn.writeInteractiveInput((cmd + "\n").toByteArray(Charsets.UTF_8))
        }.start()
    }

    /**
     * 向终端发送原生控制字节（如 0x03=Ctrl+C, 0x04=EOF/Ctrl+D, 0x1A=Ctrl+Z, 0x09=Tab, 0x1B=Esc）
     */
    fun sendTerminalControl(byte: Byte) {
        if (isFastbootMode.value) return
        val conn = connection ?: return
        Thread {
            ensureInteractiveShell()
            conn.writeInteractiveInput(byteArrayOf(byte))
        }.start()
    }

    /**
     * 向终端发送 ANSI 序列（如方向键: ↑=\u001B[A, ↓=\u001B[B, →=\u001B[C, ←=\u001B[D）
     */
    fun sendTerminalAnsi(seq: String) {
        if (isFastbootMode.value) return
        val conn = connection ?: return
        Thread {
            ensureInteractiveShell()
            conn.writeInteractiveInput(seq.toByteArray(Charsets.UTF_8))
        }.start()
    }

    /** 执行终端命令并维护 cd 路径上下文，将输出追加至控制台缓冲区 */
    fun execTerminal(cmd: String, onDone: () -> Unit = {}) {
        val trimmed = cmd.trim()
        if (trimmed.isEmpty()) {
            onDone()
            return
        }

        Thread {
            try {
                val curDir = currentWorkingDir.value.ifBlank { "/" }
                debugLog("[Terminal Exec] 开始执行命令: '$trimmed' (当前路径: '$curDir', Fastboot: ${isFastbootMode.value})")

                if (isFastbootMode.value) {
                    val fb = fastbootClient
                    if (fb != null) {
                        val out = fb.execute(trimmed)
                        debugLog("[Terminal Fastboot Out] 返回: $out")
                        if (out.isNotBlank()) {
                            mainHandler.post {
                                out.split("\n").forEach { line -> terminalLines.add(TerminalLine(text = line)) }
                            }
                        }
                    } else {
                        mainHandler.post { terminalLines.add(TerminalLine(text = I18n.current.termFastbootNotReady)) }
                        debugLog("[Terminal Error] Fastboot 未就绪")
                    }
                } else {
                    val conn = connection
                    if (conn != null && conn.isAuthenticated) {
                        val isCdCmd = trimmed == "cd" || trimmed.startsWith("cd ")

                        if (isCdCmd) {
                            // 执行 cd 并即时捕获手机系统真实的 pwd 绝对路径
                            val cdExec = "cd $curDir && $trimmed && pwd"
                            debugLog("[Terminal CD] 下发探测指令: '$cdExec'")
                            val out = conn.shell(cdExec).trim()
                            debugLog("[Terminal CD Out] 探测返回: '$out'")
                            if (out.isNotBlank()) {
                                val lines = out.split("\n")
                                val newPwd = lines.last().trim()
                                if (newPwd.startsWith("/")) {
                                    currentWorkingDir.value = newPwd
                                    debugLog("[Terminal CD] 路径成功更新为: '$newPwd'")
                                    if (lines.size > 1) {
                                        mainHandler.post {
                                            lines.dropLast(1).forEach { line -> terminalLines.add(TerminalLine(text = line)) }
                                        }
                                    }
                                } else {
                                    // cd 报错（如目录不存在）
                                    mainHandler.post {
                                        lines.forEach { line -> terminalLines.add(TerminalLine(text = line)) }
                                    }
                                }
                            }
                        } else {
                            // 携带当前工作路径上下文执行命令
                            val wrapCmd = if (curDir == "/") trimmed else "cd $curDir && $trimmed"
                            debugLog("[Terminal Shell] 下发命令: '$wrapCmd'")
                            val out = conn.shell(wrapCmd)
                            debugLog("[Terminal Shell Out] 返回长度: ${out.length} 字符")
                            if (out.isNotBlank()) {
                                mainHandler.post {
                                    out.split("\n").forEach { line -> terminalLines.add(TerminalLine(text = line)) }
                                }
                            }
                        }
                    } else {
                        mainHandler.post { terminalLines.add(TerminalLine(text = I18n.current.termNotConnected)) }
                        debugLog("[Terminal Error] 设备未连接或未授权 (conn=${conn != null}, auth=${conn?.isAuthenticated})")
                    }
                }
            } catch (e: Exception) {
                mainHandler.post { terminalLines.add(TerminalLine(text = String.format(I18n.current.termExecError, e.message ?: ""))) }
                debugLog("[Terminal Exception] 异常栈: ${e.stackTraceToString()}")
            } finally {
                mainHandler.post {
                    // 一次性裁剪到上限：避免逐条移除导致多次快照失效
                    trimLogList(terminalLines, TERMINAL_MAX_LINES)
                    onDone()
                }
            }
        }.start()
    }

    /**
     * 将 PTY 返回的原始文本流拼合渲染到控制台缓冲区，
     * 处理分包粘联与行状态更新。
     */
    fun appendTerminalContent(rawText: String) {
        val (completed, pending) = TerminalStreamProcessor.process(rawText)
        mainHandler.post {
            synchronized(this) {
                if (hasPendingLine && terminalLines.isNotEmpty()) {
                    terminalLines.removeAt(terminalLines.size - 1)
                    hasPendingLine = false
                }
                for (line in completed) {
                    terminalLines.add(TerminalLine(text = line))
                }
                if (pending.isNotEmpty()) {
                    terminalLines.add(TerminalLine(text = pending))
                    hasPendingLine = true
                }
                // 一次性裁剪到上限：避免逐条移除导致多次快照失效
                trimLogList(terminalLines, TERMINAL_MAX_LINES)
            }
        }
    }

    /** 保持交互式 PTY 终端长连接，在切换标签页时维持会话 */
    fun ensureInteractiveShell() {
        val conn = connection ?: return
        if (isFastbootMode.value || isInteractiveActive.value || !conn.isAuthenticated) return
        
        isInteractiveActive.value = true
        conn.openInteractiveShell { rawText ->
            if (rawText.isNotEmpty()) {
                appendTerminalContent(rawText)
            }
        }
    }

    /** 清空控制台输出 */
    fun clearTerminal() {
        TerminalStreamProcessor.clear()
        terminalLines.clear()
        hasPendingLine = false
    }

    /** 开启交互式终端会话 */
    fun openInteractiveShell(onOutput: (String) -> Unit): Boolean {
        val conn = connection ?: return false
        return conn.openInteractiveShell(onOutput)
    }

    /** 向交互式终端发送文本（自动 UTF-8 编码） */
    fun sendInteractiveInput(text: String): Boolean {
        val conn = connection ?: return false
        return conn.writeInteractiveInput(text.toByteArray(Charsets.UTF_8))
    }

    /** 向交互式终端发送原生控制字节（如 0x03 Ctrl+C, 0x09 Tab 等） */
    fun sendInteractiveBytes(bytes: ByteArray): Boolean {
        val conn = connection ?: return false
        return conn.writeInteractiveInput(bytes)
    }

    /** 关闭交互式终端会话 */
    fun closeInteractiveShell() {
        connection?.closeInteractiveShell()
        isInteractiveActive.value = false
        hasPendingLine = false
    }

    fun disconnect() {
        // 递增代际：作废在途的握手流程，避免其握手成功后重新提交已连接状态
        val hadSession = synchronized(this) {
            connectGeneration.incrementAndGet()
            connected.value || connection != null || channel != null || fastbootClient != null
        }
        com.aoooa.adb.log.LogManager.pauseCapture()
        // 断开 ADB 前尽量收掉控制模式会话（best-effort，不阻塞）
        try {
            val phase = com.aoooa.adb.control.ControlSessionManager.phase.value
            if (phase == com.aoooa.adb.control.ControlSessionPhase.RUNNING ||
                phase == com.aoooa.adb.control.ControlSessionPhase.PREPARING
            ) {
                Thread {
                    try {
                        kotlinx.coroutines.runBlocking {
                            com.aoooa.adb.control.ControlSessionManager.stop("adb_disconnect")
                        }
                    } catch (e: Exception) {
                        debugLog("控制模式会话停止异常: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }.start()
            }
        } catch (e: Exception) {
            debugLog("控制模式会话状态读取异常: ${e.javaClass.simpleName}: ${e.message}")
        }
        try {
            closeInteractiveShell()
        } catch (e: Exception) {
            debugLog("交互式终端注销异常: ${e.javaClass.simpleName}: ${e.message}")
        }
        TerminalStreamProcessor.clear()
        terminalLines.clear()
        hasPendingLine = false
        // 抓取引用后立即复位全局状态：底层端点注销属于阻塞操作，交由后台线程执行
        val connToRelease = connection
        val channelToRelease = channel
        val fastbootToRelease = fastbootClient
        connection = null
        channel = null
        fastbootClient = null
        connected.value = false
        isFastbootMode.value = false
        isTcpip5555Enabled.value = false
        deviceName.value = ""
        model.value = ""
        os.value = ""
        battery.value = ""
        selinux.value = ""
        currentUsbDevice = null
        // 无活动会话时重复调用不重复输出断开日志
        if (hadSession) log(I18n.current.logDisconnected)
        if (connToRelease != null || channelToRelease != null || fastbootToRelease != null) {
            releaseExecutor.execute {
                try {
                    connToRelease?.disconnect()
                } catch (e: Exception) {
                    debugLog("连接注销异常: ${e.javaClass.simpleName}: ${e.message}")
                }
                try {
                    channelToRelease?.close()
                } catch (e: Exception) {
                    debugLog("信道注销异常: ${e.javaClass.simpleName}: ${e.message}")
                }
                try {
                    fastbootToRelease?.disconnect()
                } catch (e: Exception) {
                    debugLog("Fastboot 注销异常: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    /**
     * USB 设备拔出通知处理。
     * 仅当拔出设备的 deviceId 与当前记录的 USB 设备一致时重置连接状态；
     * deviceId 缺失或无法匹配时不做处理，避免误断其他通道的会话。
     */
    fun onUsbDeviceDetached(device: UsbDevice?) {
        if (device == null) {
            debugLog("USB 拔出广播未携带设备信息，忽略")
            return
        }
        mainHandler.post {
            val current = currentUsbDevice ?: return@post
            if (device.deviceId != current.deviceId) return@post
            if (connected.value || fastbootClient != null) {
                debugLog("当前 USB 设备已拔出，重置连接状态")
                disconnect()
            }
        }
    }

    /** 获取本地日志目录总占用大小（字节） */
    fun getLogDirectorySize(context: Context): Long = SizeFormat.logDirectorySize(context)

    /** 格式化文件大小展示 */
    fun formatFileSize(bytes: Long): String = SizeFormat.fileSize(bytes)

    /** 清空所有本地日志文件 */
    fun clearLocalLogs(context: Context) {
        synchronized(this) {
            logs.clear()
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

    /**
     * 读取被控端当前物理运存信息：返回 Pair(已用字节, 总字节)
     * 使用 cat /proc/meminfo 解析 MemTotal 与 MemAvailable
     */
    fun getRamUsage(): Pair<Long, Long>? {
        val conn = connection ?: return null
        if (!conn.isAuthenticated) return null
        return try {
            RamInfo.parseMemInfo(conn.shell("cat /proc/meminfo"))
        } catch (e: Exception) {
            debugLog("读取运存信息失败: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** 格式化 RAM 运存字节展示 (仅显示已用大小，如 "4.2 GB") */
    fun formatRamSize(bytes: Long): String = SizeFormat.ramSize(bytes)

    /**
     * 默认安装方式：推包到被控端 /data/local/tmp 临时目录执行安装
     * @param autoDelete 是否在安装完毕后自动删除临时包（严格等待 pm install 返回 Success/Failure 后执行）
     */
    fun installApkDefault(
        context: Context,
        uri: android.net.Uri,
        fileName: String,
        autoDelete: Boolean = true,
        onProgress: (percent: Float, stage: String) -> Unit,
        onResult: (success: Boolean, message: String) -> Unit
    ) {
        val conn = connection
        if (conn == null || !conn.isAuthenticated) {
            onResult(false, I18n.current.installNotConnected)
            return
        }
        Thread {
            val tmpDir = "/data/local/tmp"
            val tmpName = "aoooa_inst_${System.currentTimeMillis()}.apk"
            val tmpPath = "$tmpDir/$tmpName"
            try {
                onProgress(0f, I18n.current.installPushProgress)
                val ok = conn.pushFile(context, uri, tmpName, tmpDir) { pct, _, _ ->
                    onProgress(pct * 0.7f, I18n.current.installPushProgress)
                }
                if (!ok) {
                    onResult(false, I18n.current.installPushFailed)
                    return@Thread
                }
                onProgress(0.8f, I18n.current.installCommitProgress)
                val installOut = conn.shell("pm install -r $tmpPath")
                val isSuccess = installOut.contains("Success", ignoreCase = true)
                onProgress(1f, if (isSuccess) I18n.current.installResultOk else I18n.current.installResultFail)
                onResult(isSuccess, installOut.trim())
            } catch (e: Exception) {
                onResult(false, String.format(I18n.current.installResultError, e.message ?: ""))
            } finally {
                if (autoDelete) {
                    try {
                        conn.shell("rm -f $tmpPath")
                    } catch (e: Exception) {
                        debugLog("清理被控端临时安装包失败: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            }
        }.start()
    }

    /**
     * 重载：支持本地直接从 File 执行推包安装
     */
    fun installApkDefault(
        file: java.io.File,
        fileName: String,
        autoDelete: Boolean = true,
        onProgress: (percent: Float, stage: String) -> Unit,
        onResult: (success: Boolean, message: String) -> Unit
    ) {
        val conn = connection
        if (conn == null || !conn.isAuthenticated) {
            onResult(false, I18n.current.installNotConnected)
            return
        }
        Thread {
            val tmpDir = "/data/local/tmp"
            val tmpName = "aoooa_inst_${System.currentTimeMillis()}.apk"
            val tmpPath = "$tmpDir/$tmpName"
            try {
                onProgress(0f, I18n.current.installPushProgress)
                val ok = conn.pushFile(file, tmpName, tmpDir) { pct, _, _ ->
                    onProgress(pct * 0.7f, I18n.current.installPushProgress)
                }
                if (!ok) {
                    onResult(false, I18n.current.installPushFailed)
                    return@Thread
                }
                onProgress(0.8f, I18n.current.installCommitProgress)
                val installOut = conn.shell("pm install -r $tmpPath")
                val isSuccess = installOut.contains("Success", ignoreCase = true)
                onProgress(1f, if (isSuccess) I18n.current.installResultOk else I18n.current.installResultFail)
                onResult(isSuccess, installOut.trim())
            } catch (e: Exception) {
                onResult(false, String.format(I18n.current.installResultError, e.message ?: ""))
            } finally {
                if (autoDelete) {
                    try {
                        conn.shell("rm -f $tmpPath")
                    } catch (e: Exception) {
                        debugLog("清理被控端临时安装包失败: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            }
        }.start()
    }

    /**
     * 通过 ADB Sync 协议从被控端提取指定应用 APK 并保存至本地已授权的 SAF 目录
     */
    fun extractAppApk(
        context: Context,
        packageName: String,
        appLabel: String,
        onProgress: (percent: Float) -> Unit,
        onResult: (success: Boolean, savedName: String, message: String) -> Unit
    ) {
        ApkExporter.extract(connection, context, packageName, appLabel, onProgress, onResult)
    }

    @Deprecated("Use extractAppApk instead", ReplaceWith("extractAppApk(context, packageName, appLabel, onProgress, onResult)"))
    fun downloadAppApk(
        context: Context,
        packageName: String,
        appLabel: String,
        onProgress: (percent: Float) -> Unit,
        onResult: (success: Boolean, savedName: String, message: String) -> Unit
    ) = extractAppApk(context, packageName, appLabel, onProgress, onResult)

    /**
     * 恢复已被卸载的系统预装应用
     */
    fun restoreUninstalledApp(packageName: String, onResult: (Boolean, String) -> Unit) {
        ApkExporter.restore(connection, packageName, onResult)
    }
}
