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
import com.aoooa.webadb.R

/**
 * 无线配对前台服务：
 * 1. 驻留通知栏，显示搜索状态
 * 2. 双轨监听 mDNS 广播：
 *    - _adb-tls-pairing._tcp (捕获配对端口)
 *    - _adb-tls-connect._tcp (捕获真正的无线调试连接端口)
 * 3. 自动捕获配对端口后，通知栏变身输入框 + 飞机按钮，下拉直接输入 6 位配对码
 */
class PairingService : Service() {

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
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("WebADB 配对提示")
                .setContentText(msg)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
        }

        private fun buildProgressNotification(context: Context, msg: String): Notification {
            createChannelIfNeeded(context)
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("WebADB 无线配对")
                .setContentText(msg)
                .setProgress(0, 0, true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .build()
        }

        private fun buildSuccessNotification(context: Context, msg: String): Notification {
            createChannelIfNeeded(context)
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("WebADB 配对成功")
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
        AdbManager.log("后台配对服务已启动，双轨监听无线配对与无线调试端口...")
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
            .setContentTitle("🔍 正在搜索无线调试配对服务...")
            .setContentText("请在系统设置中进入「无线调试」并点击「使用配对码配对设备」")
            .setStyle(NotificationCompat.BigTextStyle().bigText("请在系统设置中进入「无线调试」并点击「使用配对码配对设备」，捕获到端口后可直接在此处输入配对码。"))
            .setContentIntent(openPi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .addAction(0, "停止搜索", stopPi)
            .build()
    }

    private fun buildReadyNotification(host: String, port: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val remoteInput = RemoteInput.Builder(PairingActionReceiver.KEY_TEXT_REPLY)
            .setLabel("输入 6 位配对码")
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
            "✈️ 发送配对码",
            submitPi
        ).addRemoteInput(remoteInput).build()

        val stopIntent = Intent(this, PairingActionReceiver::class.java).apply {
            action = PairingActionReceiver.ACTION_PAIRING_STOP
        }
        val stopPi = PendingIntent.getBroadcast(
            this, 3, stopIntent,
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("✨ 已找到无线配对服务（端口: $port）")
            .setContentText("请下滑通知栏，输入 6 位配对码并点击小飞机发送")
            .setStyle(NotificationCompat.BigTextStyle().bigText("已自动捕获配对端口: $host:$port！\n请下拉通知，在输入框中填入系统设置显示的 6 位配对码，点击 ✈️ 发送。"))
            .setContentIntent(openPi)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .addAction(replyAction)
            .addAction(0, "取消", stopPi)
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
                            AdbManager.log("✅ 捕获到无线配对端口: $host:$port")

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
                            AdbManager.log("📡 自动发现无线调试主端口: $host:$port")
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
            AdbManager.log("mDNS discoverServices 异常: ${e.message}")
        }
    }

    override fun onDestroy() {
        try { pairingDiscoveryListener?.let { nsdManager?.stopServiceDiscovery(it) } } catch (_: Exception) {}
        try { connectDiscoveryListener?.let { nsdManager?.stopServiceDiscovery(it) } } catch (_: Exception) {}
        pairingDiscoveryListener = null
        connectDiscoveryListener = null
        nsdManager = null
        discoveredPort = 0
        discoveredConnectPort = 0
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
