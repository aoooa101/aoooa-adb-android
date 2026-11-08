package com.aoooa.adb

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
import com.aoooa.adb.pairing.PairingService
import com.aoooa.adb.ui.AoooaAdbApp
import com.aoooa.adb.ui.i18n.I18n
import com.aoooa.adb.util.UpdateChecker

/**
 * aoooa-adb 应用主入口。
 * 基于 Jetpack Compose 构建。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var usbManager: UsbManager

    companion object {
        const val USB_PERMISSION = "com.aoooa.adb.USB_PERMISSION"

        /** 初始化任务执行器：应用内单例，避免每次 onCreate 新建线程池。 */
        private val initExecutor: java.util.concurrent.ExecutorService =
            java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "app-init").apply { isDaemon = true }
            }
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

    private val usbDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val device = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            // 广播回调抛出异常会导致进程崩溃，异常统一转交调试日志
            try {
                AdbManager.onUsbDeviceDetached(device)
            } catch (e: Exception) {
                AdbManager.debugLog("USB 拔出广播处理异常: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        Prefs.init(this)
        com.aoooa.adb.shizuku.ShizukuManager.init()
        com.aoooa.adb.log.LogManager.init()
        // 异步初始化文件日志、预热 ADB 认证密钥并清理更新缓存，避免主线程磁盘 IO 阻塞 Compose 首帧绘制
        initExecutor.execute {
            AdbManager.initFileLog(this@MainActivity)
            UpdateChecker.cleanUpdateCache(this@MainActivity)
        }
        // ADB 认证密钥为首次连接时才惰性生成的 RSA-2048，单独线程预热可缩短首次连接等待
        initExecutor.execute {
            try {
                com.aoooa.adb.adb.AdbCrypto(applicationContext).keyPair
            } catch (e: Exception) {
                AdbManager.debugLog("ADB 密钥预热失败（首次连接时重新生成）: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        ContextCompat.registerReceiver(
            this,
            usbReceiver,
            IntentFilter(USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // USB 插拔为系统受保护广播，仅系统可发送，注册时需显式声明导出标志
        ContextCompat.registerReceiver(
            this,
            usbDetachReceiver,
            IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
            ContextCompat.RECEIVER_EXPORTED
        )

        // 若此前已开启 MCP 服务，则在后台初始化启动
        if (Prefs.mcpEnabled) {
            com.aoooa.adb.mcp.AdbMcpServer.start(this)
        }

        setContent {
            AoooaAdbApp(
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
            // 1. 标准 Fastboot (255/66/3)，对齐 AOSP fastboot.cpp match_fastboot_with_serial()
            if (iface.interfaceClass == 0xFF && iface.interfaceSubclass == 0x42 && iface.interfaceProtocol == 0x03) return true
            // 2. 厂商非标变体兜底 (255/66/*，排除 ADB protocol=1) 且须含 Bulk IN/OUT，
            //    与 FastbootClient.connect() 的接口选择完全一致（不再对任意 0xFF bulk 接口兜底）
            if (iface.interfaceClass == 0xFF && iface.interfaceSubclass == 0x42 &&
                iface.interfaceProtocol != 0x01 && hasBulkInOut(iface)) return true
        }
        return false
    }

    private fun hasBulkInOut(iface: android.hardware.usb.UsbInterface): Boolean {
        var hasIn = false
        var hasOut = false
        for (j in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(j)
            if (ep.type == android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_IN) hasIn = true
                if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_OUT) hasOut = true
            }
        }
        return hasIn && hasOut
    }

    private fun requestFastbootPermission() {
        val allDevs = usbManager.deviceList.values.toList()
        if (allDevs.isEmpty()) {
            AdbManager.log(I18n.current.logNoUsbDeviceFound)
            return
        }

        val device = allDevs.firstOrNull { isFastbootDevice(it) }
        if (device == null) {
            AdbManager.log(I18n.current.logNoFastbootDeviceFound)
            return
        }
        AdbManager.log(
            String.format(
                I18n.current.logFastbootPreparing,
                device.vendorId.toString(16),
                device.productId.toString(16),
                device.interfaceCount
            )
        )
        if (usbManager.hasPermission(device)) {
            AdbManager.log(I18n.current.logFastbootPermGranted)
            AdbManager.connectFastboot(this, device)
        } else {
            AdbManager.log(I18n.current.logRequestingUsbPerm)
            requestPermissionFor(device)
        }
    }

    override fun onResume() {
        super.onResume()
        com.aoooa.adb.shizuku.ShizukuManager.checkStatus()
    }

    override fun onDestroy() {
        AdbManager.disconnect()
        com.aoooa.adb.mcp.AdbMcpServer.stop()
        runCatching { unregisterReceiver(usbReceiver) }
        runCatching { unregisterReceiver(usbDetachReceiver) }
        super.onDestroy()
    }
}

