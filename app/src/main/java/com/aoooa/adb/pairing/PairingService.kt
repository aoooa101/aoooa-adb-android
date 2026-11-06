package com.aoooa.adb.pairing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.aoooa.adb.AdbManager
import com.aoooa.adb.MainActivity
import com.aoooa.adb.Prefs
import com.aoooa.adb.R
import com.aoooa.adb.ui.i18n.I18n

/**
 * 无线配对前台服务：
 * 1. 驻留通知栏显示配对状态与快捷输入
 * 2. 监听 mDNS 广播（_adb-tls-pairing 与 _adb-tls-connect）
 */
class PairingService : Service() {

    private val s get() = if (Prefs.lang == "zh") I18n.zh else I18n.en

    companion object {
        const val CHANNEL_ID = "aoooa_adb_pairing_channel"
        const val NOTIFICATION_ID = 10086

        @Volatile
        var discoveredHost: String = "127.0.0.1"
            private set

        @Volatile
        var discoveredPort: Int = 0
            private set

        @Volatile
        var discoveredConnectPort: Int = 0
            private set

        const val EXTRA_CONNECT_ONLY = "extra_connect_only"

        fun start(context: Context, connectOnly: Boolean = false) {
            val intent = Intent(context, PairingService::class.java).apply {
                putExtra(EXTRA_CONNECT_ONLY, connectOnly)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, PairingService::class.java)
            context.stopService(intent)
        }

        fun updateNotificationError(context: Context, errorMsg: String) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(NOTIFICATION_ID, buildErrorNotification(context, errorMsg))
        }

        fun updateNotificationProgress(context: Context, progressMsg: String) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(NOTIFICATION_ID, buildProgressNotification(context, progressMsg))
        }

        fun updateNotificationSuccess(context: Context, successMsg: String) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(NOTIFICATION_ID, buildSuccessNotification(context, successMsg))
        }

        private fun buildErrorNotification(context: Context, msg: String): Notification {
            createChannelIfNeeded(context)
            val s = if (Prefs.lang == "zh") I18n.zh else I18n.en
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(s.notifErrorTitle)
                .setContentText(msg)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
        }

        private fun buildProgressNotification(context: Context, msg: String): Notification {
            createChannelIfNeeded(context)
            val s = if (Prefs.lang == "zh") I18n.zh else I18n.en
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(s.appName)
                .setContentText(msg)
                .setProgress(0, 0, true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .build()
        }

        private fun buildSuccessNotification(context: Context, msg: String): Notification {
            createChannelIfNeeded(context)
            val s = if (Prefs.lang == "zh") I18n.zh else I18n.en
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(s.notifSuccessTitle)
                .setContentText(msg)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
        }

        fun createChannelIfNeeded(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "无线配对服务",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "用于在通知栏快速输入 Android 11+ 无线配对码"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private var nsdManager: NsdManager? = null
    private var pairingDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var connectDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var isConnectOnly: Boolean = false
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isConnectOnly = intent?.getBooleanExtra(EXTRA_CONNECT_ONLY, false) ?: false
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded(this)
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        multicastLock = wifiManager?.createMulticastLock("aoooa_pairing_multicast")?.apply {
            setReferenceCounted(true)
            runCatching { acquire() }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                buildSearchingNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildSearchingNotification())
        }
        startNsdDiscovery()
        AdbManager.debugLog("后台配对服务已启动，双轨监听无线配对与无线调试端口...")
    }

    private fun buildSearchingNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply { setPackage(packageName) }
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val stopIntent = Intent(this, PairingActionReceiver::class.java).apply {
            action = PairingActionReceiver.ACTION_PAIRING_STOP
            setPackage(packageName)
        }
        val stopPi = PendingIntent.getBroadcast(
            this, 1, stopIntent,
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val title = if (isConnectOnly) s.notifSearchingConnectTitle else s.notifSearchingTitle
        val text = if (isConnectOnly) s.notifSearchingConnectText else s.notifSearchingText
        val bigText = if (isConnectOnly) s.notifSearchingConnectText else s.notifSearchingBig

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(openPi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .addAction(0, s.notifStopSearch, stopPi)
            .build()
    }

    private fun buildReadyNotification(host: String, port: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply { setPackage(packageName) }
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val remoteInput = RemoteInput.Builder(PairingActionReceiver.KEY_TEXT_REPLY)
            .setLabel(s.pairingCodeLabel)
            .build()

        val submitIntent = Intent(this, PairingActionReceiver::class.java).apply {
            action = PairingActionReceiver.ACTION_PAIRING_SUBMIT
            setPackage(packageName)
        }
        val submitPi = PendingIntent.getBroadcast(
            this, 2, submitIntent,
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        )

        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_stat_notify,
            s.notifSendCode,
            submitPi
        ).addRemoteInput(remoteInput).build()

        val stopIntent = Intent(this, PairingActionReceiver::class.java).apply {
            action = PairingActionReceiver.ACTION_PAIRING_STOP
            setPackage(packageName)
        }
        val stopPi = PendingIntent.getBroadcast(
            this, 3, stopIntent,
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val title = String.format(s.notifReadyTitle, port)
        val bigText = String.format(s.notifReadyBig, host, port)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(s.notifReadyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(openPi)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .addAction(replyAction)
            .addAction(0, s.notifCancel, stopPi)
            .build()
    }

    private fun startNsdDiscovery() {
        nsdManager = getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsdManager == null) return

        // 1. 监听配对服务: _adb-tls-pairing._tcp（直连模式无需监听）
        pairingDiscoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}

            override fun onServiceFound(service: NsdServiceInfo) {
                if (isConnectOnly) return
                try {
                    nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            if (isConnectOnly) return
                            val host = serviceInfo.host?.hostAddress ?: "127.0.0.1"
                            val port = serviceInfo.port
                            discoveredHost = host
                            discoveredPort = port
                            AdbManager.log(String.format(I18n.current.logDiscoveredPort, host, port))

                            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                            nm.notify(NOTIFICATION_ID, buildReadyNotification(host, port))
                        }
                    })
                } catch (_: Exception) {}
            }

            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        // 2. 监听实际无线调试服务: _adb-tls-connect._tcp (捕获真正的动态调试端口)
        connectDiscoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}

            override fun onServiceFound(service: NsdServiceInfo) {
                try {
                    nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val host = serviceInfo.host?.hostAddress ?: "127.0.0.1"
                            val port = serviceInfo.port
                            discoveredConnectPort = port
                            discoveredHost = host
                            AdbManager.discoveredDebugHost.value = host
                            AdbManager.discoveredDebugPort.value = port
                            AdbManager.debugLog("[mDNS] 自动发现无线调试主端口: $host:$port")
                            if (isConnectOnly) {
                                AdbManager.connectTcp(this@PairingService, host, port)
                                stopSelf()
                            }
                        }
                    })
                } catch (_: Exception) {}
            }

            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        try {
            if (!isConnectOnly) {
                nsdManager?.discoverServices("_adb-tls-pairing._tcp", NsdManager.PROTOCOL_DNS_SD, pairingDiscoveryListener)
            }
            nsdManager?.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, connectDiscoveryListener)
        } catch (e: Exception) {
            AdbManager.debugLog("mDNS discoverServices 异常: ${e.message}")
        }
    }

    override fun onDestroy() {
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (_: Exception) {}
        multicastLock = null
        try { pairingDiscoveryListener?.let { nsdManager?.stopServiceDiscovery(it) } } catch (_: Exception) {}
        try { connectDiscoveryListener?.let { nsdManager?.stopServiceDiscovery(it) } } catch (_: Exception) {}
        pairingDiscoveryListener = null
        connectDiscoveryListener = null
        nsdManager = null
        discoveredPort = 0
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
