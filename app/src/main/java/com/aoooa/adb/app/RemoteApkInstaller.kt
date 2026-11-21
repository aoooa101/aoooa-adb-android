package com.aoooa.adb.app

import android.content.Context
import com.aoooa.adb.adb.AdbConnection
import com.aoooa.adb.adb.installStream
import com.aoooa.adb.adb.pushFile
import com.aoooa.adb.adb.shell
import com.aoooa.adb.ui.i18n.I18n

internal class RemoteApkInstaller(private val connection: () -> AdbConnection?, private val log: (String) -> Unit, private val debugLog: (String) -> Unit) {
    /** 流式安装 APK 文件 (无需被控端留存安装包) */
    fun installApk(context: Context, uri: android.net.Uri, fileName: String, useCompatibleMode: Boolean = false) {
        val conn = connection()
        if (conn == null) {
            log(I18n.current.logNeedAdbDevice)
            return
        }
        Thread {
            log(String.format(I18n.current.logInstallStreaming, fileName))
            var lastPct = -1
            val result = conn.installStream(context, uri, useCompatibleMode) { pct ->
                val intPct = (pct * 100).toInt()
                if (intPct % 25 == 0 && intPct != lastPct) {
                    lastPct = intPct
                    log(String.format(I18n.current.logInstallWriteProgress, intPct))
                }
            }
            log(result)
        }.start()
    }

    /**
     * 默认安装方式：推包到被控端 /data/local/tmp 临时目录执行安装
     * @param autoDelete 是否在安装完毕后自动删除临时包（严格等待 pm install 返回 Success/Failure 后执行）
     */
    fun installApkDefault(
        context: Context,
        uri: android.net.Uri,
        fileName: String,
        autoDelete: Boolean = true,
        onProgress: (percent: Float, stage: String) -> Unit,
        onResult: (success: Boolean, message: String) -> Unit
    ) {
        installStaged(autoDelete, onProgress, onResult) { conn, name, dir, progress ->
            conn.pushFile(context, uri, name, dir) { pct, _, _ -> progress(pct) }
        }
    }

    /**
     * 重载：支持本地直接从 File 执行推包安装
     */
    fun installApkDefault(
        file: java.io.File,
        fileName: String,
        autoDelete: Boolean = true,
        onProgress: (percent: Float, stage: String) -> Unit,
        onResult: (success: Boolean, message: String) -> Unit
    ) {
        installStaged(autoDelete, onProgress, onResult) { conn, name, dir, progress ->
            conn.pushFile(file, name, dir) { pct, _, _ -> progress(pct) }
        }
    }

    private fun installStaged(
        autoDelete: Boolean,
        onProgress: (Float, String) -> Unit,
        onResult: (Boolean, String) -> Unit,
        push: (AdbConnection, String, String, (Float) -> Unit) -> Boolean
    ) {
        val conn = connection()
        if (conn == null || !conn.isAuthenticated) {
            onResult(false, I18n.current.installNotConnected)
            return
        }
        Thread {
            val tmpDir = "/data/local/tmp"
            val tmpName = "aoooa_inst_${System.currentTimeMillis()}.apk"
            val tmpPath = "$tmpDir/$tmpName"
            try {
                onProgress(0f, I18n.current.installPushProgress)
                val ok = push(conn, tmpName, tmpDir) { pct ->
                    onProgress(pct * 0.7f, I18n.current.installPushProgress)
                }
                if (!ok) {
                    onResult(false, I18n.current.installPushFailed)
                    return@Thread
                }
                onProgress(0.8f, I18n.current.installCommitProgress)
                val installOut = conn.shell("pm install -r $tmpPath")
                val isSuccess = installOut.contains("Success", ignoreCase = true)
                onProgress(1f, if (isSuccess) I18n.current.installResultOk else I18n.current.installResultFail)
                onResult(isSuccess, installOut.trim())
            } catch (e: Exception) {
                onResult(false, String.format(I18n.current.installResultError, e.message ?: ""))
            } finally {
                if (autoDelete) {
                    try {
                        conn.shell("rm -f $tmpPath")
                    } catch (e: Exception) {
                        debugLog("清理被控端临时安装包失败: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            }
        }.start()
    }
}
