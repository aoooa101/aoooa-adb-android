package com.aoooa.webadb.pairing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.aoooa.webadb.AdbManager

/**
 * 通知栏「发送/小飞机」按钮广播接收器。
 * 捕获用户在通知栏直接输入的 6 位配对码，并触发后台配对与连接流程。
 */
class PairingActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PAIRING_SUBMIT = "com.aoooa.webadb.ACTION_PAIRING_SUBMIT"
        const val ACTION_PAIRING_STOP = "com.aoooa.webadb.ACTION_PAIRING_STOP"
        const val EXTRA_PAIR_CODE = "extra_pair_code"
        const val KEY_TEXT_REPLY = "key_text_reply"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        when (intent.action) {
            ACTION_PAIRING_SUBMIT -> {
                val remoteInput = RemoteInput.getResultsFromIntent(intent)
                val code = remoteInput?.getCharSequence(KEY_TEXT_REPLY)?.toString()
                    ?: intent.getStringExtra(EXTRA_PAIR_CODE) ?: ""

                val cleanCode = code.trim()
                if (cleanCode.length != 6) {
                    AdbManager.log("通知栏输入配对码不合法（需要 6 位数字）: $cleanCode")
                    PairingService.updateNotificationError(context, "配对码必须为 6 位数字")
                    return
                }

                val port = PairingService.discoveredPort
                val host = PairingService.discoveredHost.ifBlank { "127.0.0.1" }

                if (port <= 0) {
                    AdbManager.log("尚未捕获到无线配对端口，请先在系统设置中点击「使用配对码配对设备」")
                    PairingService.updateNotificationError(context, "未找到配对端口，请点击使用配对码")
                    return
                }

                AdbManager.log("收到通知栏提交的配对码: host=$host port=$port code=$cleanCode")
                PairingService.updateNotificationProgress(context, "正在进行无线配对 ($host:$port)...")

                // 触发配对流程
                AdbManager.pair(host, port, cleanCode)
            }
            ACTION_PAIRING_STOP -> {
                PairingService.stop(context)
                AdbManager.log("已停止无线配对服务")
            }
        }
    }
}
