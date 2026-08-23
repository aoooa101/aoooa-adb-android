package com.aoooa.webadb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.aoooa.webadb.adb.AdbConnection
import com.aoooa.webadb.bridge.Channel
import com.aoooa.webadb.bridge.TcpChannel
import com.aoooa.webadb.bridge.UsbChannel
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
                log("🎉 文件推送成功: $targetDir/$fileName")
            } else {
                log("❌ 文件推送失败")
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

    fun disconnect() {
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
}
