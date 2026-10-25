package com.aoooa.adb.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.aoooa.adb.Prefs
import com.aoooa.adb.adb.AdbConnection

/**
 * 应用 APK 导出与系统应用恢复。
 *
 * 导出流程：查询远端包路径 → 探测文件大小 → 在已授权 SAF 目录创建目标文件 →
 * 通过 sync 协议拉取。全部操作在独立线程执行，结果经回调返回。
 */
object ApkExporter {

    /**
     * 导出指定应用的 APK 到用户授权的导出目录。
     *
     * @param connection 已认证的 ADB 连接；为空或未认证时直接回调失败
     * @param onProgress 传输进度（0..1）
     * @param onResult 结果回调：是否成功、保存的文件名、提示信息
     */
    fun extract(
        connection: AdbConnection?,
        context: Context,
        packageName: String,
        appLabel: String,
        onProgress: (percent: Float) -> Unit,
        onResult: (success: Boolean, savedName: String, message: String) -> Unit
    ) {
        if (connection == null || !connection.isAuthenticated) {
            onResult(false, "", "未连接设备")
            return
        }
        val treeUriStr = Prefs.appDownloadDirUri
        if (treeUriStr.isBlank()) {
            onResult(false, "", "未配置 APK 导出保存目录")
            return
        }
        Thread {
            try {
                // 1. 查询远端安装包路径
                val pathOut = connection.shell("pm path $packageName")
                val remotePath = pathOut.lines()
                    .firstOrNull { it.trim().startsWith("package:") }
                    ?.trim()?.removePrefix("package:")?.trim()
                if (remotePath.isNullOrBlank()) {
                    onResult(false, "", "未找到该应用对应的安装包路径")
                    return@Thread
                }

                // 2. 探测文件大小，用于进度换算
                var expectedSize = -1L
                val statOut = connection.shell("stat -c %s \"$remotePath\" || ls -l \"$remotePath\"")
                val sizeMatch = Regex("\\d+").find(statOut)
                if (sizeMatch != null) {
                    expectedSize = sizeMatch.value.toLongOrNull() ?: -1L
                }

                // 3. 在 SAF 授权目录创建目标文件
                val safeLabel = appLabel.replace(Regex("[\\\\/:*?\"<>|\\s]"), "_")
                val targetFileName = "${packageName}_$safeLabel.apk"
                val treeUri = Uri.parse(treeUriStr)
                val targetDoc = DocumentFile.fromTreeUri(context, treeUri)
                    ?: run {
                        onResult(false, "", "无法访问已授权的导出目录")
                        return@Thread
                    }

                targetDoc.findFile(targetFileName)?.delete()
                val newFile = targetDoc.createFile("application/vnd.android.package-archive", targetFileName)
                    ?: run {
                        onResult(false, "", "在目标目录创建文件失败")
                        return@Thread
                    }

                val outStream = context.contentResolver.openOutputStream(newFile.uri)
                    ?: run {
                        onResult(false, "", "打开输出流失败")
                        return@Thread
                    }

                val ok = outStream.use { os ->
                    connection.pullFile(remotePath, os, expectedSize) { pct, _, _ ->
                        onProgress(pct)
                    }
                }

                if (ok) {
                    onResult(true, targetFileName, "导出完成")
                } else {
                    newFile.delete()
                    onResult(false, "", "从被控端传输 APK 失败")
                }
            } catch (e: Exception) {
                onResult(false, "", "导出应用 APK 异常: ${e.message}")
            }
        }.start()
    }

    /**
     * 恢复已被卸载的系统预装应用。
     */
    fun restore(
        connection: AdbConnection?,
        packageName: String,
        onResult: (Boolean, String) -> Unit
    ) {
        if (connection == null || !connection.isAuthenticated) {
            onResult(false, "未连接设备")
            return
        }
        Thread {
            try {
                var out = connection.shell("cmd package install-existing $packageName")
                if (out.isBlank() || out.contains("not found", ignoreCase = true) || out.contains("Error", ignoreCase = true)) {
                    out = connection.shell("pm install-existing $packageName")
                }
                val isSuccess = out.contains("installed", ignoreCase = true) ||
                        out.contains("Success", ignoreCase = true) ||
                        out.contains(packageName)
                onResult(isSuccess, out.trim())
            } catch (e: Exception) {
                onResult(false, "恢复异常: ${e.message}")
            }
        }.start()
    }
}
