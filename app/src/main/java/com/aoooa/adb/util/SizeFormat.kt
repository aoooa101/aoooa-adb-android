package com.aoooa.adb.util

import android.content.Context
import java.util.Locale

/**
 * 体积与运存数值的格式化、本地日志目录占用统计。
 */
object SizeFormat {

    /** 格式化文件大小展示（B / KB / MB）。 */
    fun fileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1.0 -> String.format(Locale.getDefault(), "%.2f MB", mb)
            kb >= 1.0 -> String.format(Locale.getDefault(), "%.1f KB", kb)
            else -> "$bytes B"
        }
    }

    /** 格式化 RAM 已用字节展示（GB / MB）。 */
    fun ramSize(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) {
            String.format(Locale.getDefault(), "%.1f GB", gb)
        } else {
            val mb = bytes / (1024.0 * 1024.0)
            String.format(Locale.getDefault(), "%.0f MB", mb)
        }
    }

    /**
     * 统计本地日志占用的总字节数。
     * 筛选口径与 [LogRotator] 的淘汰口径一致，保证界面数值与可回收空间对应。
     */
    fun logDirectorySize(context: Context): Long = LogRotator.totalSize(context)
}
