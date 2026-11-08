package com.aoooa.adb.pairing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.aoooa.adb.AdbManager
import com.aoooa.adb.ui.i18n.I18n
/**
 * 通知栏「发送/小飞机」按钮广播接收器。
 * 捕获用户在通知栏直接输入的 6 位配对码，并触发后台配对与连接流程。
 */
class PairingActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PAIRING_SUBMIT = "com.aoooa.adb.ACTION_PAIRING_SUBMIT"
        const val ACTION_PAIRING_STOP = "com.aoooa.adb.ACTION_PAIRING_STOP"
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
                    AdbManager.log(String.format(I18n.current.logPairCodeInvalid, cleanCode))
                    PairingService.updateNotificationError(context, I18n.current.notifPairCodeInvalid)
                    return
                }

                val port = PairingService.discoveredPort
                val host = PairingService.discoveredHost.ifBlank { "127.0.0.1" }

                if (port <= 0) {
                    AdbManager.log(I18n.current.logPairPortNotCaptured)
                    PairingService.updateNotificationError(context, I18n.current.notifPairPortNotFound)
                    return
                }

                AdbManager.log(String.format(I18n.current.logPairCodeSubmitted, host, port, cleanCode))
                PairingService.updateNotificationProgress(
                    context,
                    String.format(I18n.current.notifPairingInProgress, "$host:$port")
                )

                // 触发配对流程
                AdbManager.pair(host, port, cleanCode)
            }
            ACTION_PAIRING_STOP -> {
                PairingService.stop(context)
                AdbManager.log(I18n.current.logPairServiceStopped)
            }
        }
    }
}
