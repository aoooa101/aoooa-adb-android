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
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ADB 连接管理器（2.0 原生版）。
 * 管理传输层（USB/TCP）+ AdbConnection 协议层 + Compose 状态。
 */
object AdbManager {

    /** 连接状态 */
    val connected = mutableStateOf(false)
    val deviceName = mutableStateOf("")
    val model = mutableStateOf("")
    val os = mutableStateOf("")
    val battery = mutableStateOf("")
    val selinux = mutableStateOf("")

    /** 终端日志 */
    val logs = mutableStateListOf<String>()

    @Volatile
    private var channel: Channel? = null
    @Volatile
    private var connection: AdbConnection? = null

    private var logWriter: FileWriter? = null
    private var logFile: File? = null

    /** 初始化文件日志（在 Android/data/com.aoooa.webadb/files/logs/ 中生成免权限日志） */
    fun initFileLog(context: Context) {
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
    }

    /** 同时写入文件日志和内存日志 */
    fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$time] $msg"
        logs.add(line)
        if (logs.size > 300) logs.removeAt(0)
        fileLog(line)
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

    /** 用 USB 设备建立连接（在后台线程执行） */
    fun connectUsb(context: Context, device: UsbDevice) {
        if (connected.value) return
        Thread {
            try {
                val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                
                // 1. 预先创建连接对象（绑定持久化 RSA Key）
                var connHolder: AdbConnection? = null
                val ch = UsbChannel(
                    onData = { data -> connHolder?.onData(data) },
                    onStatus = { msg -> log(msg) }
                )
                
                val conn = AdbConnection(ch, context) { msg -> log(msg) }
                connHolder = conn
                connection = conn

                log("打开 USB 通道...")
                if (!ch.connect(usbManager, device)) {
                    log("USB 连接失败")
                    return@Thread
                }
                channel = ch

                log("开始 ADB 认证...")
                if (!conn.connect()) {
                    log("认证失败")
                    return@Thread
                }
                connected.value = true
                deviceName.value = device.productName ?: device.deviceName
                loadDeviceInfo(conn)
                log("已连接")
            } catch (e: Exception) {
                log("连接异常: ${e.stackTraceToString()}")
            }
        }.start()
    }

    /** 用 TCP 建立无线连接（在后台线程执行） */
    fun connectTcp(context: Context, host: String, port: Int) {
        if (connected.value) return
        Thread {
            try {
                var connHolder: AdbConnection? = null
                val ch = TcpChannel(
                    onData = { data -> connHolder?.onData(data) },
                    onStatus = { msg -> log(msg) }
                )
                
                val conn = AdbConnection(ch, context) { msg -> log(msg) }
                connHolder = conn
                connection = conn

                log("连接 $host:$port ...")
                if (!ch.connect(host, port)) {
                    log("TCP 连接失败")
                    return@Thread
                }
                channel = ch

                log("开始 ADB 认证...")
                if (!conn.connect()) {
                    log("认证失败")
                    return@Thread
                }
                connected.value = true
                deviceName.value = "$host:$port"
                loadDeviceInfo(conn)
                log("已连接")
            } catch (e: Exception) {
                log("连接异常: ${e.stackTraceToString()}")
            }
        }.start()
    }

    /** 开启 5555 无线调试（adbd 重启，连接会断开） */
    fun enableTcpip() {
        Thread {
            val result = connection?.enableTcpip(5555)
            if (result.isNullOrBlank()) log("(无输出)") else log(result)
            log("正在重启 adbd，连接即将断开...")
        }.start()
    }

    /** 关闭无线调试端口 */
    fun disableTcpip() {
        Thread {
            val result = connection?.disableTcpip()
            if (result.isNullOrBlank()) log("(无输出)") else log(result)
            log("正在重启 adbd，连接即将断开...")
        }.start()
    }

    fun pair(host: String, port: Int, code: String) {
        log("配对请求: $host:$port code=$code")
        if (port <= 0 || code.length != 6) {
            log("配对信息不完整（需要配对端口 + 6 位配对码）")
            return
        }
        Thread {
            log("SPAKE2 配对将在后续版本实现；当前请使用「IP:5555 直连」方式")
        }.start()
    }

    private fun loadDeviceInfo(conn: AdbConnection) {
        val manufacturer = conn.shell("getprop ro.product.manufacturer")
        val modelName = conn.shell("getprop ro.product.model")
        val release = conn.shell("getprop ro.build.version.release")
        val sdk = conn.shell("getprop ro.build.version.sdk")
        val sel = conn.shell("getenforce")
        val bat = conn.shell("dumpsys battery")

        model.value = "$manufacturer $modelName".trim()
        os.value = if (release.isNotBlank()) "Android $release (API $sdk)" else ""
        selinux.value = sel
        battery.value = Regex("level:\\s*(\\d+)").find(bat)?.groupValues?.get(1)?.let { "$it%" } ?: ""
    }

    fun exec(cmd: String) {
        val conn = connection ?: return
        if (cmd.isBlank()) return
        log("> $cmd")
        Thread {
            val result = conn.shell(cmd)
            if (result.isNotBlank()) log(result)
            else log("(无输出)")
        }.start()
    }

    fun disconnect() {
        connection?.disconnect()
        connection = null
        channel = null
        connected.value = false
        deviceName.value = ""
        model.value = ""
        os.value = ""
        battery.value = ""
        selinux.value = ""
        log("已断开")
    }
}
