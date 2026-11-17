package com.aoooa.adb.adb

import android.content.Context
import com.aoooa.adb.adb.protocol.InstallCommands
import com.aoooa.adb.ui.i18n.I18n
import java.io.InputStream

/** 从 Uri 读取 APK，使用独立服务流创建、写入和提交安装会话。 */
fun AdbConnection.installStream(
    context: Context,
    uri: android.net.Uri,
    useCompatibleMode: Boolean = false,
    onProgress: (percent: Float) -> Unit
): String {
    if (!isAuthenticated) return I18n.current.installNotConnected
    val resolver = context.contentResolver
    val size = try {
        resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
    } catch (_: Exception) { -1L }
    if (size <= 0) return I18n.current.installApkSizeUnreadable
    val input = try {
        resolver.openInputStream(uri) ?: return I18n.current.installApkStreamUnavailable
    } catch (error: Exception) {
        return String.format(I18n.current.installReadError, error.message ?: "")
    }
    return installFromInput(input, size, useCompatibleMode, onProgress)
}

/** 从本地文件读取 APK；普通模式与兼容模式共享安装会话生命周期。 */
fun AdbConnection.installStream(
    file: java.io.File,
    useCompatibleMode: Boolean = false,
    onProgress: (percent: Float) -> Unit
): String {
    if (!isAuthenticated) return I18n.current.installNotConnected
    if (!file.exists()) return I18n.current.installLocalApkMissing
    val size = file.length()
    if (size <= 0) return I18n.current.installApkSizeUnreadable
    val input = try {
        java.io.FileInputStream(file)
    } catch (error: Exception) {
        return String.format(I18n.current.installReadError, error.message ?: "")
    }
    return installFromInput(input, size, useCompatibleMode, onProgress)
}

private fun AdbConnection.installFromInput(
    input: InputStream,
    size: Long,
    compatible: Boolean,
    progress: (Float) -> Unit
): String {
    var unfinishedSession: Int? = null
    try {
        var createOut = openService(InstallCommands.create(size))
        if (createOut.isBlank() || !createOut.contains("[")) {
            createOut = openService(InstallCommands.createFallback(size))
        }
        val sessionId = InstallCommands.parseSessionId(createOut)
            ?: return String.format(I18n.current.installSessionCreateFailed, createOut)
        unfinishedSession = sessionId

        val writeText = writeInstallSession(input, size, sessionId, compatible, progress)
        if (writeText.isNotEmpty()) onDebugLog("install-write 输出: $writeText")
        if (!writeText.contains("Success", ignoreCase = true)) {
            return writeText.ifBlank { I18n.current.installRemoteClosedEarly }
        }

        var commitOut = openService(InstallCommands.commit(sessionId))
        if (commitOut.isBlank()) commitOut = openService(InstallCommands.commitFallback(sessionId))
        val success = commitOut.contains("Success", ignoreCase = true)
        if (success) unfinishedSession = null
        return if (success) I18n.current.installSuccessTag else commitOut
    } catch (error: Exception) {
        if (error is InterruptedException) Thread.currentThread().interrupt()
        return String.format(I18n.current.installStreamError, error.message ?: "")
    } finally {
        try { input.close() } catch (_: Exception) {}
        InstallSessionCleanup.abandon(unfinishedSession, isAuthenticated,
            execute = { openService(it) }, log = { onDebugLog(it) })
    }
}
