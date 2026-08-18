package com.aoooa.webadb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.aoooa.webadb.adb.AdbConnection
import com.aoooa.webadb.bridge.UsbChannel

/**
 * ADB 连接管理器（2.0 原生版）。
 * 管理 UsbChannel 传输层 + AdbConnection 协议层 + Compose 状态。
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

    private var channel: UsbChannel? = null
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
