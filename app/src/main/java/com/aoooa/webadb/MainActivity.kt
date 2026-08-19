package com.aoooa.webadb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.aoooa.webadb.ui.WebAdbApp

/**
 * WebADB 控制台 2.0（原生版）入口。
 * 纯 Compose UI + 原生 ADB 协议层。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var usbManager: UsbManager

    companion object {
        const val USB_PERMISSION = "com.aoooa.webadb.USB_PERMISSION"
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val device = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            // 兜底：广播可能漏报 granted，以实际授权状态为准
            if ((granted || (device != null && usbManager.hasPermission(device))) && device != null) {
                AdbManager.log("USB 权限已授予，开始连接...")
                AdbManager.connectUsb(this@MainActivity, device)
            } else {
                AdbManager.log("USB 权限被拒绝")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        Prefs.init(this)
        registerReceiver(usbReceiver, IntentFilter(USB_PERMISSION))

        setContent {
            WebAdbApp(
                onConnectUsb = { requestUsbPermission() },
            )
        }

        handleUsbAttach(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbAttach(intent)
    }

    /** 插线自动连接（系统弹权限框后直接连） */
    private fun handleUsbAttach(intent: Intent?) {
        if (intent == null || intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        } ?: return
        if (usbManager.hasPermission(device)) {
            AdbManager.log("检测到 USB ADB 设备，自动连接...")
            AdbManager.connectUsb(this, device)
        } else {
            // 插线但无权限：主动请求权限（否则永远不会弹权限框）
            AdbManager.log("检测到 USB ADB 设备，请求权限...")
            requestPermissionFor(device)
        }
    }

    /** 枚举 ADB 设备并请求权限（或直接连接） */
    private fun requestUsbPermission() {
        val device = usbManager.deviceList.values.firstOrNull { isAdbDevice(it) }
        if (device == null) {
            AdbManager.log("未找到 ADB 设备，请检查 USB 调试是否开启")
            return
        }
        if (usbManager.hasPermission(device)) {
            AdbManager.log("已有 USB 权限，直接连接...")
            AdbManager.connectUsb(this, device)
        } else {
            AdbManager.log("请求 USB 权限...")
            requestPermissionFor(device)
        }
    }

    private fun requestPermissionFor(device: UsbDevice) {
        val pi = PendingIntent.getBroadcast(
            this, 0, Intent(USB_PERMISSION),
            if (Build.VERSION.SDK_INT >= 23) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )
        usbManager.requestPermission(device, pi)
    }

    private fun isAdbDevice(dev: UsbDevice): Boolean {
        for (i in 0 until dev.interfaceCount) {
            val iface = dev.getInterface(i)
            if (iface.interfaceClass == 0xFF && iface.interfaceSubclass == 0x42 && iface.interfaceProtocol == 0x01) {
                return true
            }
        }
        return false
    }

    override fun onDestroy() {
        AdbManager.disconnect()
        runCatching { unregisterReceiver(usbReceiver) }
        super.onDestroy()
    }
}
