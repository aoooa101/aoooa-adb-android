package com.aoooa.adb.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 应用升级完成监听：新版本覆盖安装完成后清理缓存中的更新安装包。
 */
class AppUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            UpdateChecker.cleanUpdateCache(context)
        }
    }
}
