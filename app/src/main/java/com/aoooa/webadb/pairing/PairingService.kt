package com.aoooa.webadb.pairing

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
import com.aoooa.webadb.AdbManager
import com.aoooa.webadb.MainActivity
import com.aoooa.webadb.Prefs
import com.aoooa.webadb.R
import com.aoooa.webadb.ui.i18n.I18n

/**
 * 无线配对前台服务：
 * 1. 驻留通知栏，显示搜索状态
 * 2. 双轨监听 mDNS 广播：
 *    - _adb-tls-pairing._tcp (捕获配对端口)
 *    - _adb-tls-connect._tcp (捕获真正的无线调试连接端口)
 * 3. 自动捕获配对端口后，通知栏变身输入框 + 飞机按钮，下拉直接输入 6 位配对码
 */
class PairingService : Service() {

    private val s get() = if (Prefs.lang == "zh") I18n.zh else I18n.en

    companion object {
        const val CHANNEL_ID = "webadb_pairing_channel"
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

        fun start(context: Context) {
            val intent = Intent(context, PairingService::class.java)
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
                .setSmallIcon(R.drawable.ic_launcher)
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
                .setSmallIcon(R.drawable.ic_launcher)
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
                .setSmallIcon(R.drawable.ic_launcher)
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

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded(this)
        startForeground(NOTIFICATION_ID, buildSearchingNotification())
        startNsdDiscovery()
        AdbManager.debugLog("后台配对服务已启动，双轨监听无线配对与无线调试端口...")
    }

    private fun buildSearchingNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val stopIntent = Intent(this, PairingActionReceiver::class.java).apply {
            action = PairingActionReceiver.ACTION_PAIRING_STOP
        }
        val stopPi = PendingIntent.getBroadcast(
            this, 1, stopIntent,
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(s.notifSearchingTitle)
            .setContentText(s.notifSearchingText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(s.notifSearchingBig))
            .setContentIntent(openPi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .addAction(0, s.notifStopSearch, stopPi)
            .build()
    }

    private fun buildReadyNotification(host: String, port: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val remoteInput = RemoteInput.Builder(PairingActionReceiver.KEY_TEXT_REPLY)
            .setLabel(s.pairingCodeLabel)
            .build()

        val submitIntent = Intent(this, PairingActionReceiver::class.java).apply {
            action = PairingActionReceiver.ACTION_PAIRING_SUBMIT
        }
        val submitPi = PendingIntent.getBroadcast(
            this, 2, submitIntent,
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        )

        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_launcher,
            s.notifSendCode,
            submitPi
        ).addRemoteInput(remoteInput).build()

        val stopIntent = Intent(this, PairingActionReceiver::class.java).apply {
            action = PairingActionReceiver.ACTION_PAIRING_STOP
        }
        val stopPi = PendingIntent.getBroadcast(
            this, 3, stopIntent,
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val title = String.format(s.notifReadyTitle, port)
        val bigText = String.format(s.notifReadyBig, host, port)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
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

        // 1. 监听配对服务: _adb-tls-pairing._tcp
        pairingDiscoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}

            override fun onServiceFound(service: NsdServiceInfo) {
                try {
                    nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
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
                            AdbManager.debugLog("📡 自动发现无线调试主端口: $host:$port")
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
            nsdManager?.discoverServices("_adb-tls-pairing._tcp", NsdManager.PROTOCOL_DNS_SD, pairingDiscoveryListener)
            nsdManager?.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, connectDiscoveryListener)
        } catch (e: Exception) {
            AdbManager.debugLog("mDNS discoverServices 异常: ${e.message}")
        }
    }

    override fun onDestroy() {
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
