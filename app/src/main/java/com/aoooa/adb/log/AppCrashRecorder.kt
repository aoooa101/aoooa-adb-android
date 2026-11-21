package com.aoooa.adb.log

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object AppCrashRecorder {
    private const val LOG_TAG = "aoooa-adb"
    private var installed = false

    @Synchronized
    fun install(flushLogsBlocking: () -> Unit, fileLogSync: (String) -> Unit) {
        if (installed) return
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
                val crashMsg = buildString {
                    appendLine("\n==================== 崩溃异常捕获 (CRASH) ====================")
                    appendLine("[$time] 触发线程: ${thread.name} (ID: ${thread.id})")
                    appendLine("[$time] 设备型号: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (${android.os.Build.PRODUCT})")
                    appendLine("[$time] 系统版本: Android ${android.os.Build.VERSION.RELEASE} (SDK API ${android.os.Build.VERSION.SDK_INT})")
                    appendLine("[$time] 异常类型: ${throwable.javaClass.name}")
                    appendLine("[$time] 异常信息: ${throwable.message}")
                    appendLine("[$time] 完整调用栈:")
                    appendLine(throwable.stackTraceToString())
                    appendLine("=================================================================\n")
                }
                // 崩溃前先将队列中已产生的日志落盘，保证现场日志完整
                flushLogsBlocking()
                fileLogSync(crashMsg)
            } catch (e: Exception) {
                // 崩溃现场写入文件失败时降级到 Logcat，保留原始堆栈
                android.util.Log.e(LOG_TAG, "崩溃日志写入失败: ${e.javaClass.simpleName}: ${e.message}", e)
                android.util.Log.e(LOG_TAG, "原始崩溃: ${throwable.javaClass.name}: ${throwable.message}", throwable)
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
        installed = true
    }
}
