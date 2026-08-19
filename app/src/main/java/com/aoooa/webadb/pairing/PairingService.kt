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
 * 2. 使用 NsdManager 自动监听系统 mDNS 广播 (_adb-tls-pairing._tcp)
 * 3. 自动捕获配对端口后，通知栏变为带输入框 + 飞机按钮，供用户直接下拉输入 6 位配对码
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
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded(this)
        startForeground(NOTIFICATION_ID, buildSearchingNotification())
        startNsdDiscovery()
        AdbManager.log("后台配对服务已启动，正在监听系统 mDNS 无线配对广播...")
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

    /**
     * 发现配对端口后：通知栏变身带 RemoteInput 输入框 + 小飞机发送按钮
     */
    private fun buildReadyNotification(host: String, port: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )

        // 1. 创建 RemoteInput 输入框
        val remoteInput = RemoteInput.Builder(PairingActionReceiver.KEY_TEXT_REPLY)
            .setLabel("输入 6 位配对码")
            .build()

        // 2. 创建点击飞机按钮的 PendingIntent
        val submitIntent = Intent(this, PairingActionReceiver::class.java).apply {
            action = PairingActionReceiver.ACTION_PAIRING_SUBMIT
        }
        val submitPi = PendingIntent.getBroadcast(
            this, 2, submitIntent,
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 3. 构建 Action (带小飞机图标)
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

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                AdbManager.log("mDNS 搜索已启动: $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                AdbManager.log("发现 mDNS 服务: ${service.serviceName} (${service.serviceType})")
                // 解析服务获取具体端口
                try {
                    nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            AdbManager.log("解析配对服务失败: errorCode=$errorCode")
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val host = serviceInfo.host?.hostAddress ?: "127.0.0.1"
                            val port = serviceInfo.port
                            discoveredHost = host
                            discoveredPort = port
                            AdbManager.log("✅ 自动捕获到无线配对端口: $host:$port")

                            // 动态刷新通知栏：弹出输入框 + 小飞机按钮！
                            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                            nm.notify(NOTIFICATION_ID, buildReadyNotification(host, port))
                        }
                    })
                } catch (e: Exception) {
                    AdbManager.log("调用 resolveService 异常: ${e.message}")
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                AdbManager.log("mDNS 服务离线: ${service.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                AdbManager.log("mDNS 搜索已停止: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                AdbManager.log("启动 mDNS 搜索失败: errorCode=$errorCode")
                nsdManager?.stopServiceDiscovery(this)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                AdbManager.log("停止 mDNS 搜索失败: errorCode=$errorCode")
            }
        }

        try {
            // 监听 Android 11+ 的无线配对服务
            nsdManager?.discoverServices(
                "_adb-tls-pairing._tcp",
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener
            )
        } catch (e: Exception) {
            AdbManager.log("注册 discoverServices 异常: ${e.message}")
        }
    }

    override fun onDestroy() {
        try {
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (_: Exception) {}
        discoveryListener = null
        nsdManager = null
        discoveredPort = 0
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
