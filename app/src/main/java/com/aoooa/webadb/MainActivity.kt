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
import com.aoooa.webadb.ui.i18n.I18n

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
            AdbManager.log(I18n.current.logNotifSearching)
            startPairingServiceAndOpenSettings()
        } else {
            AdbManager.log(I18n.current.logNotifDenied)
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
                AdbManager.log(I18n.current.logUsbPermGranted)
                if (isFastbootDevice(device)) {
                    AdbManager.connectFastboot(this@MainActivity, device)
                } else {
                    AdbManager.connectUsb(this@MainActivity, device)
                }
            } else {
                AdbManager.log(I18n.current.logUsbPermDenied)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        Prefs.init(this)
        AdbManager.initFileLog(this)
        ContextCompat.registerReceiver(
            this,
            usbReceiver,
            IntentFilter(USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        setContent {
            WebAdbApp(
                onConnectUsb = { requestUsbPermission() },
                onConnectFastboot = { requestFastbootPermission() },
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
                AdbManager.log(I18n.current.logNotifSearching)
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        startPairingServiceAndOpenSettings()
    }

    private fun startPairingServiceAndOpenSettings() {
        PairingService.start(this)
        AdbManager.log(I18n.current.logNotifSearching)
        openDevelopmentSettings()
    }

    private fun openDevelopmentSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            AdbManager.debugLog("无法直接打开开发者选项: ${e.message}")
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
            AdbManager.log(I18n.current.logDetectedUsbDevice)
            AdbManager.connectUsb(this, device)
        } else {
            AdbManager.log(I18n.current.logRequestingUsbPerm)
            requestPermissionFor(device)
        }
    }

    private fun requestUsbPermission() {
        val device = usbManager.deviceList.values.firstOrNull { isAdbDevice(it) }
        if (device == null) {
            AdbManager.log(I18n.current.logNoAdbDevice)
            return
        }
        if (usbManager.hasPermission(device)) {
            AdbManager.log(I18n.current.logDetectedUsbDevice)
            AdbManager.connectUsb(this, device)
        } else {
            AdbManager.log(I18n.current.logRequestingUsbPerm)
            requestPermissionFor(device)
        }
    }

    private fun requestPermissionFor(device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= 31) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else if (Build.VERSION.SDK_INT >= 23) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getBroadcast(
            this, 0, Intent(USB_PERMISSION),
            flags
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

    private fun isFastbootDevice(dev: UsbDevice): Boolean {
        for (i in 0 until dev.interfaceCount) {
            val iface = dev.getInterface(i)
            // 1. 标准 Fastboot (255/66/3)
            if (iface.interfaceClass == 0xFF && iface.interfaceSubclass == 0x42 && iface.interfaceProtocol == 0x03) return true
            // 2. 厂商非标 Fastboot (255/66/*)
            if (iface.interfaceClass == 0xFF && iface.interfaceSubclass == 0x42) return true
            // 3. 通用包含 Bulk IN/OUT 的 Vendor 接口
            var hasIn = false
            var hasOut = false
            for (j in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(j)
                if (ep.type == android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_IN) hasIn = true
                    if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_OUT) hasOut = true
                }
            }
            if (iface.interfaceClass == 0xFF && hasIn && hasOut) return true
        }
        return false
    }

    private fun requestFastbootPermission() {
        val allDevs = usbManager.deviceList.values.toList()
        if (allDevs.isEmpty()) {
            AdbManager.log("未检测到 USB 硬件信号（设备列表为空）")
            AdbManager.log("提示：被控端重启进入 Fastboot 时，主控手机（OPPO/vivo等）可能会自动断开 OTG 供电，请重新拔插一下 OTG 线并在系统设置中确认开启「OTG 连接」")
            return
        }

        val device = allDevs.firstOrNull { isFastbootDevice(it) } ?: allDevs.firstOrNull { !isAdbDevice(it) } ?: allDevs.first()
        AdbManager.log("正在准备连接 Fastboot 设备: VID=0x${device.vendorId.toString(16)} PID=0x${device.productId.toString(16)} (接口数=${device.interfaceCount})")
        if (usbManager.hasPermission(device)) {
            AdbManager.log("已获得权限，正在建立 Fastboot 通道...")
            AdbManager.connectFastboot(this, device)
        } else {
            AdbManager.log(I18n.current.logRequestingUsbPerm)
            requestPermissionFor(device)
        }
    }

    override fun onDestroy() {
        AdbManager.disconnect()
        runCatching { unregisterReceiver(usbReceiver) }
        super.onDestroy()
    }
}
