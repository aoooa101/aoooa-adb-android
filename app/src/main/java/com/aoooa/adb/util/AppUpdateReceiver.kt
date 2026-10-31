package com.aoooa.adb.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 应用升级完成监听：新版本覆盖安装成功后，系统即刻触发本广播。
 * 用于在后台第一时间销毁安装包缓存，实现用完即焚、零空间残留。
 */
class AppUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            UpdateChecker.cleanUpdateCache(context)
        }
    }
}
