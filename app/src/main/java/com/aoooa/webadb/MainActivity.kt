package com.aoooa.webadb

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.aoooa.webadb.pairing.PairingService
import com.aoooa.webadb.ui.WebAdbApp

/**
 * WebADB 控制台 2.0（原生版）入口。
 * 纯 Compose UI + 原生 ADB 协议层 + Shizuku 模式通知栏无线配对。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var usbManager: UsbManager

    companion object {
        const val USB_PERMISSION = "com.aoooa.webadb.USB_PERMISSION"
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            AdbManager.log("已获得通知权限，启动无线配对服务...")
            startPairingServiceAndOpenSettings()
        } else {
            AdbManager.log("通知权限被拒绝，将无法通过通知栏下拉快捷输入配对码")
            // 降级：依然跳转开发者选项
            openDevelopmentSettings()
        }
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
        AdbManager.initFileLog(this)
        registerReceiver(usbReceiver, IntentFilter(USB_PERMISSION))

        setContent {
            WebAdbApp(
                onConnectUsb = { requestUsbPermission() },
                onSelfPairing = { startSelfPairingFlow() },
            )
        }

        handleUsbAttach(intent)
    }

    /**
     * 用户点击「自己调试自己」：
     * 1. 检查 Android 13+ 通知权限，没有则申请
     * 2. 启动 PairingService（通知栏显示搜索状态）
     * 3. 自动跳转到系统开发者选项
     */
    fun startSelfPairingFlow() {
        if (Build.VERSION.SDK_INT >= 33) {
            val hasPermission = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                AdbManager.log("请求通知权限以支持通知栏输入配对码...")
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        startPairingServiceAndOpenSettings()
    }

    private fun startPairingServiceAndOpenSettings() {
        PairingService.start(this)
        AdbManager.log("无线配对通知已发送，正在打开开发者选项...")
        openDevelopmentSettings()
    }

    private fun openDevelopmentSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            AdbManager.log("无法直接打开开发者选项: ${e.message}")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbAttach(intent)
    }

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
            AdbManager.log("检测到 USB ADB 设备，请求权限...")
            requestPermissionFor(device)
        }
    }

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
