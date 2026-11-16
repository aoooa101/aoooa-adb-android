package com.aoooa.adb.adb.proxy

import android.content.Context

/** 本机 ADB 代理的启用状态与监听端口持久化。 */
internal object ProxyPreferences {
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("adb_proxy", Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean("enabled", false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("enabled", enabled).apply()
    }

    fun port(context: Context): Int = prefs(context).getInt("port", 5037)
        .takeIf { it in 1024..65535 } ?: 5037

    fun setPort(context: Context, port: Int) {
        require(port in 1024..65535)
        prefs(context).edit().putInt("port", port).apply()
    }
}
