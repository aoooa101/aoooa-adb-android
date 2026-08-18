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

    private var channel: Channel? = null
    private var connection: AdbConnection? = null

    fun log(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        logs.add(0, "[$time] $msg")
        if (logs.size > 300) logs.removeAt(logs.size - 1)
    }

    /** 用 USB 设备建立连接（在后台线程执行） */
    fun connectUsb(context: Context, device: UsbDevice) {
        if (connected.value) return
        Thread {
            try {
                val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                val ch = UsbChannel(
                    onData = { data -> connection?.onData(data) },
                    onStatus = { msg -> log(msg) }
                )
                log("打开 USB 通道...")
                if (!ch.connect(usbManager, device)) {
                    log("USB 连接失败")
                    return@Thread
                }
                channel = ch

                val conn = AdbConnection(ch) { msg -> log(msg) }
                connection = conn
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
                log("连接异常: ${e.message}")
            }
        }.start()
    }

    /** 用 TCP 建立无线连接（在后台线程执行） */
    fun connectTcp(host: String, port: Int) {
        if (connected.value) return
        Thread {
            try {
                val ch = TcpChannel(
                    onData = { data -> connection?.onData(data) },
                    onStatus = { msg -> log(msg) }
                )
                log("连接 $host:$port ...")
                if (!ch.connect(host, port)) {
                    log("TCP 连接失败")
                    return@Thread
                }
                channel = ch

                val conn = AdbConnection(ch) { msg -> log(msg) }
                connection = conn
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
                log("连接异常: ${e.message}")
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

    /**
     * Android 11+ 配对（SPAKE2）。
     * 框架版：先记录参数并提示；SPAKE2 握手在后续版本实现。
     */
    fun pair(host: String, port: Int, code: String) {
        log("配对请求: $host:$port code=$code")
        if (port <= 0 || code.length != 6) {
            log("配对信息不完整（需要配对端口 + 6 位配对码）")
            return
        }
        Thread {
            // TODO(2.1): SPAKE2 pairing handshake
            // 简化方案：配对成功后直接尝试连接 5555
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

    /** 执行 shell 命令 */
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
