package com.aoooa.webadb.bridge

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Base64
import android.webkit.JavascriptInterface
import com.aoooa.webadb.MainActivity

/**
 * JS <-> 原生桥。
 *
 * 暴露给 WebView 的 window.AdbBridge：
 *  - usbConnect(): 枚举 USB ADB 设备并请求权限
 *  - tcpConnect(host, port): 发起无线 TCP 连接
 *  - sendBase64(data): JS 层 @yume-chan/adb 写入的字节（Base64）
 *  - disconnect(): 断开
 *
 * 原生 -> JS：onData（Base64 字节流）/ onStatus（状态事件），
 * 由 MainActivity 通过 evaluateJavascript 推送。
 */
@SuppressLint("JavascriptInterface")
class AdbBridge(
    private val context: Context,
    private val onData: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var channel: Channel? = null
    private var pendingDevice: UsbDevice? = null

    @JavascriptInterface
    fun usbConnect(): Boolean {
        disconnect()
        val device = findAdbDevice()
        if (device == null) {
            onStatus("usb_no_device")
            return false
        }
        pendingDevice = device
        val pi = PendingIntent.getBroadcast(
            context,
            0,
            Intent(MainActivity.USB_PERMISSION),
            if (Build.VERSION.SDK_INT >= 23) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )
        usbManager.requestPermission(device, pi)
        onStatus("usb_requesting")
        return true
    }

    @JavascriptInterface
    fun tcpConnect(host: String, port: Int): Boolean {
        disconnect()
        Thread {
            val ch = TcpChannel { data -> onData(Base64.encodeToString(data, Base64.NO_WRAP)) }
            val ok = ch.connect(host.trim(), port)
            if (ok) {
                channel = ch
                onStatus("tcp_connected")
            } else {
                onStatus("tcp_error")
            }
        }.start()
        return true
    }

    @JavascriptInterface
    fun sendBase64(data: String): Boolean {
        return try {
            val bytes = Base64.decode(data, Base64.NO_WRAP)
            channel?.send(bytes) ?: false
        } catch (e: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun disconnect() {
        channel?.close()
        channel = null
    }

    @JavascriptInterface
    fun isConnected(): Boolean = channel != null

    /** 由 MainActivity 的 USB 权限广播回调调用。 */
    fun onUsbPermissionResult(device: UsbDevice?, granted: Boolean) {
        if (!granted) {
            onStatus("usb_permission_denied")
            return
        }
        val dev = device ?: pendingDevice ?: return
        pendingDevice = null
        Thread {
            val ch = UsbChannel { data -> onData(Base64.encodeToString(data, Base64.NO_WRAP)) }
            val ok = ch.connect(usbManager, dev)
            if (ok) {
                channel = ch
                onStatus("usb_connected")
            } else {
                onStatus("usb_error")
            }
        }.start()
    }

    private fun findAdbDevice(): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull { dev ->
            (0 until dev.interfaceCount).any { i ->
                val iface = dev.getInterface(i)
                iface.interfaceClass == UsbChannel.ADB_CLASS &&
                    iface.interfaceSubclass == UsbChannel.ADB_SUBCLASS &&
                    iface.interfaceProtocol == UsbChannel.ADB_PROTOCOL
            }
        }
    }
}
