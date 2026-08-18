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
    private var sendLogCount = 0

    @JavascriptInterface
    fun tcpConnect(host: String, port: Int): Boolean {
        disconnect()
        Thread {
            val ch = TcpChannel(
                onData = { data -> onData(Base64.encodeToString(data, Base64.NO_WRAP)) },
                onStatus = { msg -> onStatus("usb_log:" + msg) }
            )
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
            if (sendLogCount < 8) {
                sendLogCount++
                onStatus("usb_log:原生收到 #$sendLogCount: ${bytes.size} 字节")
            }
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
    fun usbConnect(): Boolean {
        disconnect()
        val devices = listAdbDevices()
        if (devices.isEmpty()) {
            onStatus("usb_no_device")
            return false
        }
        if (devices.size == 1) {
            onStatus("usb_log:检测到设备 " + devices[0].deviceName)
            connectUsbDevice(devices[0])
            return true
        }
        // 多设备：弹框让用户选择
        (context as? android.app.Activity)?.runOnUiThread {
            val names = devices.map { d ->
                listOfNotNull(d.manufacturerName, d.productName).joinToString(" ").ifBlank { d.deviceName }
            }.toTypedArray()
            android.app.AlertDialog.Builder(context)
                .setTitle("选择 USB 设备")
                .setItems(names) { _, which -> connectUsbDevice(devices[which]) }
                .setNegativeButton("取消", null)
                .show()
        }
        onStatus("usb_select_device")
        return true
    }

    /** 连接一个 USB 设备：已有权限直接开通道，否则在主线程请求权限。 */
    private fun connectUsbDevice(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            onStatus("usb_log:已有 USB 权限，直接打开通道")
            openUsbChannel(device)
            return
        }
        onStatus("usb_log:请求 USB 权限...")
        (context as? android.app.Activity)?.runOnUiThread {
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
        }
    }

    /** 在后台线程打开 USB 通道。 */
    private fun openUsbChannel(device: UsbDevice) {
        Thread {
            val ch = UsbChannel(
                onData = { data -> onData(Base64.encodeToString(data, Base64.NO_WRAP)) },
                onStatus = { msg -> onStatus("usb_log:" + msg) }
            )
            val ok = ch.connect(usbManager, device)
            if (ok) {
                channel = ch
                onStatus("usb_connected")
            } else {
                onStatus("usb_error")
            }
        }.start()
    }

        private fun listAdbDevices(): List<UsbDevice> =
            usbManager.deviceList.values.filter { dev ->
                (0 until dev.interfaceCount).any { i ->
                    val iface = dev.getInterface(i)
                    iface.interfaceClass == UsbChannel.ADB_CLASS &&
                        iface.interfaceSubclass == UsbChannel.ADB_SUBCLASS &&
                        iface.interfaceProtocol == UsbChannel.ADB_PROTOCOL
                }
            }

    /** 由 MainActivity 的 USB 权限广播回调调用。 */
    fun onUsbPermissionResult(device: UsbDevice?, granted: Boolean) {
        val dev = device ?: pendingDevice ?: return
        pendingDevice = null
        // 兜底：广播可能因 ROM 问题漏报授权结果，以设备实际授权状态为准
        val actuallyGranted = granted || usbManager.hasPermission(dev)
        onStatus("usb_log:权限回调 granted=$granted 实际授权=$actuallyGranted")
        if (!actuallyGranted) {
            onStatus("usb_permission_denied")
            return
        }
        openUsbChannel(dev)
    }

    /** 供 MainActivity 上报 USB 广播诊断信息到页面日志。 */
    fun logToPage(msg: String) {
        onStatus("usb_log:" + msg)
    }
}
