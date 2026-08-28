package com.aoooa.webadb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.aoooa.webadb.adb.AdbConnection
import com.aoooa.webadb.bridge.Channel
import com.aoooa.webadb.bridge.TcpChannel
import com.aoooa.webadb.bridge.UsbChannel
import com.aoooa.webadb.model.TerminalLine
import com.aoooa.webadb.ui.i18n.I18n
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ADB 连接管理器（2.0 原生版）。
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

    /** 交互式控制台全局常驻输出流缓冲区（切换界面永不丢失、永不断开） */
    val terminalLines = mutableStateListOf<TerminalLine>()
    val isInteractiveActive = mutableStateOf(false)

    @Volatile
    private var lineIdCounter = 0L
    @Volatile
    private var activeLineText = ""

    /**
     * 终端流式字符处理器（工业级流式 ANSI / PTY 状态机）：
     * 1. 支持跨 TCP/USB 数据包的不完整 ANSI 转义序列自动续接；
     * 2. 无损保留 ANSI SGR 颜色代码 (\u001B[...m)，深度清洗光标/括号粘贴/清行等杂项控制码；
     * 3. 精准处理 \r\n 换行、\r 行首重绘与 \b / 0x7F 退格（避开 ANSI 序列防破坏）；
     * 4. 彻底消除文本重叠、字符吞噬与乱码残留。
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
                    // 单独的 \r 回车（行首重绘）
                    c == '\r' -> {
                        if (i + 1 >= len) {
                            pendingEscape = "\r"
                            break
                        }
                        buffer.clear()
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
    private var connection: AdbConnection? = null
    @Volatile
    private var fastbootClient: com.aoooa.webadb.fastboot.FastbootClient? = null
    @Volatile
    private var isConnecting = false

    private var logWriter: FileWriter? = null
    private var logFile: File? = null

    private var appContext: Context? = null

    /** 初始化文件日志（在 Android/data/com.aoooa.webadb/files/logs/ 中生成免权限日志，异步执行防主线程 I/O 阻塞） */
    fun initFileLog(context: Context) {
        appContext = context.applicationContext
        Thread {
            try {
                val logDir = context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs")
                logDir.mkdirs()
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                logFile = File(logDir, "webadb_$ts.log")
                logWriter = FileWriter(logFile, true)
                fileLog("=== WebADB 完整调试日志开始 (${logFile?.absolutePath}) ===")

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
                        fileLog(crashMsg)
                        logWriter?.flush()
                    } catch (_: Exception) {
                    } finally {
                        defaultHandler?.uncaughtException(thread, throwable)
                    }
                }
            } catch (e: Exception) {
                // 文件日志失败不影响主功能
            }
        }.start()
    }

    /** 写入用户界面终端基础日志（同时归档至文件日志） */
    fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$time] $msg"
        logs.add(line)
        if (logs.size > 300) logs.removeAt(0)
        fileLog(line)
    }

    /** 写入底层技术调试日志（仅记录于文件日志，保持界面终端清爽） */
    fun debugLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        fileLog("[$time] $msg")
    }

    private fun fileLog(line: String) {
        try {
            logWriter?.write(line + "\n")
            logWriter?.flush()
        } catch (_: Exception) {
        }
    }

    /** 获取日志文件路径（供用户查看） */
    fun getLogFile(): File? = logFile

    /** 用 USB 设备建立连接（在后台线程执行），防重入 */
    fun connectUsb(context: Context, device: UsbDevice) {
        if (connected.value || isConnecting) return
        synchronized(this) {
            if (connected.value || isConnecting) return
            isConnecting = true
        }
        Thread {
            try {
                val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                
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
                connection = conn

                log(I18n.current.logConnectingUsb)
                if (!ch.connect(usbManager, device)) {
                    log(I18n.current.logUsbFailed)
                    return@Thread
                }
                channel = ch

                log(I18n.current.logAuthStart)
                if (!conn.connect()) {
                    log(I18n.current.logAuthFailed)
                    return@Thread
                }
                connected.value = true
                deviceName.value = device.productName ?: device.deviceName
                loadDeviceInfo(conn)
                log(I18n.current.logConnected)
                ensureInteractiveShell()
            } catch (e: Exception) {
                log("${I18n.current.logUsbFailed}: ${e.message}")
                debugLog("USB 连接异常栈: ${e.stackTraceToString()}")
            } finally {
                synchronized(this) {
                    isConnecting = false
                }
            }
        }.start()
    }

    /** 用 TCP 建立无线连接（在后台线程执行），防重入：同一时刻只允许一个连接流程 */
    fun connectTcp(context: Context, host: String, port: Int) {
        if (connected.value || isConnecting) return
        synchronized(this) {
            if (connected.value || isConnecting) return
            isConnecting = true
        }
        Thread {
            try {
                var connHolder: AdbConnection? = null
                val ch = TcpChannel(
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
                connection = conn

                log(String.format(I18n.current.logConnectingTcp, host, port))
                if (!ch.connect(host, port)) {
                    log(I18n.current.logTcpFailed)
                    return@Thread
                }
                channel = ch

                log(I18n.current.logAuthStart)
                if (!conn.connect()) {
                    log(I18n.current.logAuthFailed)
                    return@Thread
                }
                connected.value = true
                deviceName.value = "$host:$port"
                loadDeviceInfo(conn)
                log(I18n.current.logConnected)
                ensureInteractiveShell()
            } catch (e: Exception) {
                log("${I18n.current.logTcpFailed}: ${e.message}")
                debugLog("TCP 连接异常栈: ${e.stackTraceToString()}")
            } finally {
                synchronized(this) {
                    isConnecting = false
                }
            }
        }.start()
    }

    /** 一键直连已发现的已配对无线调试主端口 */
    fun connectDiscovered(context: Context) {
        val port = discoveredDebugPort.value
        val host = discoveredDebugHost.value.ifBlank { "127.0.0.1" }
        if (port > 0) {
            log(String.format(I18n.current.logDiscoveredPort, host, port))
            connectTcp(context, host, port)
        } else {
            log(I18n.current.logSearchingMdns)
            com.aoooa.webadb.pairing.PairingService.start(context)
        }
    }

    /** 用 USB 设备建立 Fastboot 连接（在后台线程执行），防重入 */
    fun connectFastboot(context: Context, device: UsbDevice) {
        if (connected.value || isConnecting) return
        synchronized(this) {
            if (connected.value || isConnecting) return
            isConnecting = true
        }
        Thread {
            try {
                val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                val client = com.aoooa.webadb.fastboot.FastbootClient(
                    onLog = { msg -> log(msg) },
                    onDebugLog = { msg -> debugLog(msg) }
                )
                log(I18n.current.wiredHint)
                if (!client.connect(usbManager, device)) {
                    log("Fastboot 连接失败")
                    return@Thread
                }
                fastbootClient = client
                isFastbootMode.value = true
                connected.value = true
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
                log("Fastboot 连接异常: ${e.message}")
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
        }.start()
    }

    /** 关闭无线调试端口 */
    fun disableTcpip() {
        Thread {
            val result = connection?.disableTcpip()
            if (!result.isNullOrBlank()) log(result)
            log(I18n.current.logTcpipRestarting)
        }.start()
    }

    /** 开启或关闭原生 5555 无线调试（通过 ADB 官方内建 tcpip:5555 / usb: 服务，非 shell 命令） */
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
                } else {
                    log(I18n.current.logTcpip5555Disabling)
                    val result = conn.disableTcpip()
                    if (result.isNotBlank()) log(result)
                    log(I18n.current.logTcpipRestarting)
                    isTcpip5555Enabled.value = false
                }
            } catch (e: Exception) {
                log("5555: ${e.message}")
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
            com.aoooa.webadb.pairing.AdbPairing.pair(ctx, host, port, code) { success ->
                if (success) {
                    log(I18n.current.logPairingSuccess)
                } else {
                    log(I18n.current.logPairingFailed)
                }
            }
        }
    }

    private fun loadDeviceInfo(conn: AdbConnection) {
        val manufacturer = conn.shell("getprop ro.product.manufacturer")
        val modelName = conn.shell("getprop ro.product.model")
        val release = conn.shell("getprop ro.build.version.release")
        val sdk = conn.shell("getprop ro.build.version.sdk")
        val sel = conn.shell("getenforce")
        val bat = conn.shell("dumpsys battery")

        val tcpPort = conn.shell("getprop service.adb.tcp.port").trim()
        isTcpip5555Enabled.value = (tcpPort.toIntOrNull() ?: -1) > 0

        model.value = "$manufacturer $modelName".trim()
        os.value = if (release.isNotBlank()) "Android $release (API $sdk)" else ""
        selinux.value = sel
        battery.value = Regex("level:\\s*(\\d+)").find(bat)?.groupValues?.get(1)?.let { "$it%" } ?: ""
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

    /** 同步执行命令并捕获返回结果（供后台探测使用） */
    fun execCapture(cmd: String): String {
        val conn = connection ?: return ""
        if (cmd.isBlank()) return ""
        return conn.shell(cmd)
    }

    /** 推送文件到设备目标目录 (ADB Push) */
    fun pushFile(context: Context, uri: android.net.Uri, fileName: String, targetDir: String) {
        val conn = connection
        if (conn == null) {
            log("请先连接 ADB 设备")
            return
        }
        Thread {
            log("正在准备推送文件: $fileName -> $targetDir ...")
            var lastPct = -1
            val ok = conn.pushFile(context, uri, fileName, targetDir) { pct, sent, total ->
                val intPct = (pct * 100).toInt()
                if (intPct % 20 == 0 && intPct != lastPct) {
                    lastPct = intPct
                    log("[传输进度 $intPct%] ${sent / 1024}KB / ${total / 1024}KB")
                }
            }
            if (ok) {
                log("[成功] 文件推送完成: $targetDir/$fileName")
            } else {
                log("[失败] 文件推送失败")
            }
        }.start()
    }

    /** 流式安装 APK 文件 (无需被控端留存安装包) */
    fun installApk(context: Context, uri: android.net.Uri, fileName: String, useCompatibleMode: Boolean = false) {
        val conn = connection
        if (conn == null) {
            log("请先连接 ADB 设备")
            return
        }
        Thread {
            log("正在流式安装 APK: $fileName ...")
            var lastPct = -1
            val result = conn.installStream(context, uri, useCompatibleMode) { pct ->
                val intPct = (pct * 100).toInt()
                if (intPct % 25 == 0 && intPct != lastPct) {
                    lastPct = intPct
                    log("[写入进度 $intPct%]")
                }
            }
            log(result)
        }.start()
    }

    /** Fastboot 刷入单分区镜像 (Fastboot Flash) */
    fun flashPartition(context: Context, uri: android.net.Uri, fileName: String, partition: String) {
        val fb = fastbootClient
        if (fb == null || !isFastbootMode.value) {
            log("请先连接 Fastboot 设备")
            return
        }
        Thread {
            log("准备刷入镜像 [$fileName] -> 分区 [$partition] ...")
            var lastPct = -1
            val result = fb.flashPartitionImage(context, uri, partition) { pct ->
                val intPct = (pct * 100).toInt()
                if (intPct % 25 == 0 && intPct != lastPct) {
                    lastPct = intPct
                    log("[镜像上传进度 $intPct%]")
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
            mainHandler.post { terminalLines.add(TerminalLine(lineIdCounter++, "[未连接] 设备未连接或未授权")) }
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

    /** 可靠执行终端命令，支持 cd 路径上下文自动维持，并将命令与输出实时推送到控制台缓冲区 */
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
                                out.split("\n").forEach { line -> terminalLines.add(TerminalLine(lineIdCounter++, line)) }
                            }
                        }
                    } else {
                        mainHandler.post { terminalLines.add(TerminalLine(lineIdCounter++, "[错误] Fastboot 未就绪")) }
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
                                            lines.dropLast(1).forEach { line -> terminalLines.add(TerminalLine(lineIdCounter++, line)) }
                                        }
                                    }
                                } else {
                                    // cd 报错（如目录不存在）
                                    mainHandler.post {
                                        lines.forEach { line -> terminalLines.add(TerminalLine(lineIdCounter++, line)) }
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
                                    out.split("\n").forEach { line -> terminalLines.add(TerminalLine(lineIdCounter++, line)) }
                                }
                            }
                        }
                    } else {
                        mainHandler.post { terminalLines.add(TerminalLine(lineIdCounter++, "[未连接] 设备未连接或未授权")) }
                        debugLog("[Terminal Error] 设备未连接或未授权 (conn=${conn != null}, auth=${conn?.isAuthenticated})")
                    }
                }
            } catch (e: Exception) {
                mainHandler.post { terminalLines.add(TerminalLine(lineIdCounter++, "[错误] 执行异常: ${e.message}")) }
                debugLog("[Terminal Exception] 异常栈: ${e.stackTraceToString()}")
            } finally {
                mainHandler.post {
                    if (terminalLines.size > 3000) {
                        repeat(terminalLines.size - 3000) { terminalLines.removeAt(0) }
                    }
                    onDone()
                }
            }
        }.start()
    }

    @Volatile
    /**
     * 将 PTY 返回的原始文本流无损、增量式地拼合渲染到控制台缓冲区，
     * 彻底解决分包粘联、提示符缺失及行重叠 Bug。
     */
    fun appendTerminalContent(rawText: String) {
        val (completed, active) = TerminalStreamProcessor.process(rawText)
        mainHandler.post {
            synchronized(this) {
                // 1. 如果之前末尾存在未闭合的活动行，先安全移除（因为它要与新来的块进行拼合）
                if (activeLineText.isNotEmpty() && terminalLines.isNotEmpty()) {
                    terminalLines.removeAt(terminalLines.size - 1)
                }

                // 2. 处理已完成的换行行
                for (line in completed) {
                    val mergedLine = if (activeLineText.isNotEmpty()) {
                        val m = activeLineText + line
                        activeLineText = "" // 消费掉当前缓存的拼接头部
                        m
                    } else {
                        line
                    }
                    terminalLines.add(TerminalLine(lineIdCounter++, mergedLine))
                }

                // 3. 更新当前的未闭合活动行
                activeLineText = if (activeLineText.isNotEmpty()) {
                    activeLineText + active
                } else {
                    active
                }

                // 4. 如果当前活动行不为空，将其添加为列表的最后一项
                if (activeLineText.isNotEmpty()) {
                    terminalLines.add(TerminalLine(lineIdCounter++, activeLineText))
                }

                // 5. 限制缓冲区大小在 3000 行内，防止内存暴涨
                if (terminalLines.size > 3000) {
                    repeat(terminalLines.size - 3000) { terminalLines.removeAt(0) }
                }
            }
        }
    }

    /** 确保交互式 PTY 终端长连接持续处于活跃状态（全局单例调度，切 Tab 绝不断开） */
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
        activeLineText = ""
        lineIdCounter = 0L
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
        activeLineText = ""
    }

    fun disconnect() {
        closeInteractiveShell()
        TerminalStreamProcessor.clear()
        terminalLines.clear()
        activeLineText = ""
        lineIdCounter = 0L
        connection?.disconnect()
        connection = null
        channel = null
        fastbootClient?.disconnect()
        fastbootClient = null
        connected.value = false
        isFastbootMode.value = false
        isTcpip5555Enabled.value = false
        deviceName.value = ""
        model.value = ""
        os.value = ""
        battery.value = ""
        selinux.value = ""
        log(I18n.current.logDisconnected)
    }

    /** 获取本地日志目录总占用大小（字节） */
    fun getLogDirectorySize(context: Context): Long {
        var total = 0L
        val dirs = listOfNotNull(
            context.getExternalFilesDir("logs"),
            File(context.filesDir, "logs")
        )
        for (dir in dirs) {
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { if (it.isFile) total += it.length() }
            }
        }
        return total
    }

    /** 格式化文件大小展示 */
    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1.0 -> String.format(Locale.getDefault(), "%.2f MB", mb)
            kb >= 1.0 -> String.format(Locale.getDefault(), "%.1f KB", kb)
            else -> "$bytes B"
        }
    }

    /** 一键清理所有本地日志文件并释放存储（具备线程安全锁） */
    fun clearLocalLogs(context: Context) {
        synchronized(this) {
            logs.clear()
            try {
                logWriter?.flush()
                logWriter?.close()
                logWriter = null
            } catch (_: Exception) {}

            val dirs = listOfNotNull(
                context.getExternalFilesDir("logs"),
                File(context.filesDir, "logs")
            )
            for (dir in dirs) {
                if (dir.exists() && dir.isDirectory) {
                    dir.listFiles()?.forEach { file ->
                        if (file.isFile) runCatching { file.delete() }
                    }
                }
            }
            initFileLog(context)
        }
    }
}
