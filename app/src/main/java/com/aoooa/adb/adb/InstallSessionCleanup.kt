package com.aoooa.adb.adb

import com.aoooa.adb.adb.protocol.InstallCommands

/** 取消未完成的安装任务，清理失败通过日志返回而不替换安装结果。 */
internal object InstallSessionCleanup {
    fun abandon(sessionId: Int?, connected: Boolean, execute: (String) -> String, log: (String) -> Unit) {
        if (sessionId == null) return
        if (!connected) {
            log("[Install] abandon skipped: disconnected session=$sessionId")
            return
        }
        try {
            val result = execute(InstallCommands.abandon(sessionId))
            if (!result.contains("Success", ignoreCase = true)) {
                val fallback = execute(InstallCommands.abandonFallback(sessionId))
                if (!fallback.contains("Success", ignoreCase = true)) {
                    log("[Install] abandon unconfirmed session=$sessionId")
                }
            }
        } catch (error: Exception) {
            log("[Install] abandon failed session=$sessionId type=${error.javaClass.simpleName}")
        }
    }
}
