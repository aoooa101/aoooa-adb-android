package com.aoooa.adb.mcp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.aoooa.adb.MainActivity
import com.aoooa.adb.Prefs
import com.aoooa.adb.R
import com.aoooa.adb.ui.i18n.I18n

/**
 * MCP 服务前台通知管理器：
 * 1. 维护专用通知渠道并设置低打扰重要性；
 * 2. 构造常驻运行状态通知并在通知体展示局域网访问地址；
 * 3. 支持点击通知直达主界面及服务停止时自动撤销通知。
 */
object McpNotificationHelper {

    private const val CHANNEL_ID = "aoooa_adb_mcp_channel"
    private const val NOTIFICATION_ID = 10088

    /**
     * 显示或刷新 MCP 服务运行状态常驻通知。
     */
    fun showMcpRunningNotification(context: Context, addresses: List<String>) {
        val appContext = context.applicationContext
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        createChannelIfNeeded(appContext, nm)

        val s = if (Prefs.lang == "zh") I18n.zh else I18n.en

        val openIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            0,
            openIntent,
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val primaryAddress = addresses.firstOrNull { !it.contains("127.0.0.1") } ?: addresses.firstOrNull() ?: "http://127.0.0.1:8080"
        val allAddressesText = if (addresses.isEmpty()) primaryAddress else addresses.joinToString("\n")

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(s.mcpNotifTitle)
            .setContentText(primaryAddress)
            .setStyle(NotificationCompat.BigTextStyle().bigText(allAddressesText))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }

    /**
     * 撤销 MCP 服务通知。
     */
    fun cancelNotification(context: Context) {
        val appContext = context.applicationContext
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.cancel(NOTIFICATION_ID)
    }

    private fun createChannelIfNeeded(context: Context, nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val s = if (Prefs.lang == "zh") I18n.zh else I18n.en
            val channel = NotificationChannel(
                CHANNEL_ID,
                s.mcpNotifChannelName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = s.mcpNotifChannelDesc
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }
}
