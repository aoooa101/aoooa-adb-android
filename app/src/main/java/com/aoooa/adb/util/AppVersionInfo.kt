package com.aoooa.adb.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** 查询本机当前应用安装信息，不通过 ADB 或远端 shell。 */
internal data class AppVersionInfo(val name: String?, val code: Long?) {
    fun display(unknown: String): String = "${name ?: unknown} (${code?.toString() ?: unknown})"

    fun logHeader(createdUnixSec: Long = System.currentTimeMillis() / 1_000L): String =
        "=== App versionName=${name ?: "unknown"} versionCode=${code?.toString() ?: "unknown"} " +
            "Android=${Build.VERSION.RELEASE} SDK=${Build.VERSION.SDK_INT} logCreatedUnixSec=$createdUnixSec ===\n"

    companion object {
        @Suppress("DEPRECATION")
        fun read(context: Context): AppVersionInfo = try {
            val manager = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= 33) {
                manager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                manager.getPackageInfo(context.packageName, 0)
            }
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            AppVersionInfo(info.versionName?.takeIf { it.isNotBlank() }, code)
        } catch (_: PackageManager.NameNotFoundException) {
            AppVersionInfo(null, null)
        } catch (_: SecurityException) {
            AppVersionInfo(null, null)
        }
    }
}
