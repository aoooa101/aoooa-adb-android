package com.aoooa.adb.adb

import android.content.Context
import com.aoooa.adb.adb.protocol.InstallCommands
import com.aoooa.adb.ui.i18n.I18n

/**
 * AdbConnection 的流式安装实现（install-create / install-write / install-commit）。
 *
 * 职责边界：只负责安装会话的建立、APK 字节写入与提交，
 * 传输层报文收发由 [AdbConnection]，服务流 OPEN 由 [AdbConnection.openService] 提供。
 */

/**
 * 重载：支持 Uri 来源的 AOSP 标准流式安装
 * @param useCompatibleMode 是否启用兼容模式（老设备限速流控）
 */
fun AdbConnection.installStream(
    context: Context,
    uri: android.net.Uri,
    useCompatibleMode: Boolean = false,
    onProgress: (percent: Float) -> Unit
): String {
    if (!isAuthenticated) return I18n.current.installNotConnected
    val contentResolver = context.contentResolver
    val fileSize = try {
        contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
    } catch (_: Exception) { -1L }
    if (fileSize <= 0) return I18n.current.installApkSizeUnreadable

    val inputStream = try {
        contentResolver.openInputStream(uri) ?: return I18n.current.installApkStreamUnavailable
    } catch (e: Exception) { return String.format(I18n.current.installReadError, e.message ?: "") }

    var unfinishedSession: Int? = null
    var unfinishedStream = 0
    var unfinishedRemote = 0
    try {
        // 1. 创建安装 Session
        var createOut = openService(InstallCommands.create(fileSize))
        if (createOut.isBlank() || !createOut.contains("[")) {
            createOut = openService(InstallCommands.createFallback(fileSize))
        }
        val sessionId = InstallCommands.parseSessionId(createOut)
            ?: return String.format(I18n.current.installSessionCreateFailed, createOut)
        unfinishedSession = sessionId

        // 2. 建立 install-write 流
        pendingPackets.clear()
        val localId = localIds.getAndIncrement()
        unfinishedStream = localId
        var remoteId = 0
        val writeCmd = InstallCommands.write(fileSize, sessionId)
        sendPacket(AdbPacket(AdbPacket.OPEN, localId, 0, writeCmd.toByteArray(Charsets.UTF_8)))

        val deadline = System.currentTimeMillis() + 6000
        while (System.currentTimeMillis() < deadline) {
            val pkt = nextPacket(500) ?: continue
            if (pkt.command == AdbPacket.OKAY && pkt.arg1 == localId) {
                remoteId = pkt.arg0
                unfinishedRemote = remoteId
                break
            }
        }
        if (remoteId == 0) return I18n.current.installWriteChannelFailed

        // 3. 流式写入 APK 字节（兼容模式使用 8KB 小块与流控控速）
        val chunkSize = rawChunkSize(if (useCompatibleMode) 8192 else 32768)
        val buf = ByteArray(chunkSize)
        var totalSent = 0L

        while (true) {
            val n = inputStream.read(buf)
            if (n <= 0) break
            val chunk = if (n == buf.size) buf else buf.copyOf(n)
            sendPacket(AdbPacket(AdbPacket.WRTE, localId, remoteId, chunk))
            totalSent += n
            if (useCompatibleMode) Thread.sleep(10)

            onProgress(totalSent.toFloat() / fileSize.toFloat())
        }

        // 排空 install-write 流的输出与退出状态：写入失败时 commit 必然失败，
        // 不读取该流的输出会让用户拿到与真实原因无关的报错
        val writeOut = StringBuilder()
        val writeDeadline = System.currentTimeMillis() + 30_000
        var writeClosed = false
        while (!writeClosed && System.currentTimeMillis() < writeDeadline) {
            val pkt = nextPacket(200) ?: continue
            if (pkt.arg1 != localId) continue
            when (pkt.command) {
                AdbPacket.WRTE -> {
                    if (pkt.payload.isNotEmpty()) {
                        writeOut.append(String(pkt.payload, Charsets.UTF_8))
                    }
                    sendPacket(AdbPacket(AdbPacket.OKAY, localId, remoteId))
                }
                AdbPacket.CLSE -> writeClosed = true
            }
        }
        val writeText = writeOut.toString().trim()
        if (!writeClosed) sendPacket(AdbPacket(AdbPacket.CLSE, localId, remoteId))
        unfinishedStream = 0
        if (writeText.isNotEmpty()) onDebugLog("install-write 输出: $writeText")
        if (!writeText.contains("Success", ignoreCase = true)) {
            return writeText.ifBlank { I18n.current.installRemoteClosedEarly }
        }

        // 4. 提交安装
        var commitOut = openService(InstallCommands.commit(sessionId))
        if (commitOut.isBlank()) {
            commitOut = openService(InstallCommands.commitFallback(sessionId))
        }
        val success = commitOut.contains("Success", ignoreCase = true)
        if (success) unfinishedSession = null
        return if (success) I18n.current.installSuccessTag else commitOut
    } catch (e: Exception) {
        return String.format(I18n.current.installStreamError, e.message ?: "")
    } finally {
        try { inputStream.close() } catch (_: Exception) {}
        if (unfinishedStream != 0 && unfinishedRemote != 0 && isAuthenticated) {
            try {
                sendPacket(AdbPacket(AdbPacket.CLSE, unfinishedStream, unfinishedRemote))
            } catch (cleanupError: Exception) {
                onDebugLog("[Install] stream close failed localId=$unfinishedStream type=${cleanupError.javaClass.simpleName}")
            }
        }
        InstallSessionCleanup.abandon(unfinishedSession, isAuthenticated,
            execute = { openService(it) }, log = { onDebugLog(it) })
    }
}

/**
 * 重载：支持本地直接从 File 进行 AOSP 标准流式安装
 */
fun AdbConnection.installStream(
    file: java.io.File,
    useCompatibleMode: Boolean = false,
    onProgress: (percent: Float) -> Unit
): String {
    if (!isAuthenticated) return I18n.current.installNotConnected
    if (!file.exists()) return I18n.current.installLocalApkMissing
    val fileSize = file.length()
    val inputStream = try {
        java.io.FileInputStream(file)
    } catch (e: Exception) { return String.format(I18n.current.installReadError, e.message ?: "") }

    var unfinishedSession: Int? = null
    var unfinishedStream = 0
    var unfinishedRemote = 0
    try {
        var createOut = openService(InstallCommands.create(fileSize))
        if (createOut.isBlank() || !createOut.contains("[")) {
            createOut = openService(InstallCommands.createFallback(fileSize))
        }
        val sessionId = InstallCommands.parseSessionId(createOut)
            ?: return String.format(I18n.current.installSessionCreateFailed, createOut)
        unfinishedSession = sessionId

        pendingPackets.clear()
        val localId = localIds.getAndIncrement()
        unfinishedStream = localId
        var remoteId = 0
        val writeCmd = InstallCommands.write(fileSize, sessionId)
        sendPacket(AdbPacket(AdbPacket.OPEN, localId, 0, writeCmd.toByteArray(Charsets.UTF_8)))

        val deadline = System.currentTimeMillis() + 6000
        while (System.currentTimeMillis() < deadline) {
            val pkt = nextPacket(500) ?: continue
            if (pkt.command == AdbPacket.OKAY && pkt.arg1 == localId) {
                remoteId = pkt.arg0
                unfinishedRemote = remoteId
                break
            }
        }
        if (remoteId == 0) return I18n.current.installWriteChannelFailed

        val chunkSize = rawChunkSize(if (useCompatibleMode) 8192 else 32768)
        val buf = ByteArray(chunkSize)
        var totalSent = 0L

        while (true) {
            val n = inputStream.read(buf)
            if (n <= 0) break
            val chunk = if (n == buf.size) buf else buf.copyOf(n)
            sendPacket(AdbPacket(AdbPacket.WRTE, localId, remoteId, chunk))
            totalSent += n
            if (useCompatibleMode) Thread.sleep(10)

            onProgress(totalSent.toFloat() / fileSize.toFloat())
        }

        // 排空 install-write 流的输出与退出状态（同 Uri 重载）
        val writeOut = StringBuilder()
        val writeDeadline = System.currentTimeMillis() + 30_000
        var writeClosed = false
        while (!writeClosed && System.currentTimeMillis() < writeDeadline) {
            val pkt = nextPacket(200) ?: continue
            if (pkt.arg1 != localId) continue
            when (pkt.command) {
                AdbPacket.WRTE -> {
                    if (pkt.payload.isNotEmpty()) {
                        writeOut.append(String(pkt.payload, Charsets.UTF_8))
                    }
                    sendPacket(AdbPacket(AdbPacket.OKAY, localId, remoteId))
                }
                AdbPacket.CLSE -> writeClosed = true
            }
        }
        val writeText = writeOut.toString().trim()
        if (!writeClosed) sendPacket(AdbPacket(AdbPacket.CLSE, localId, remoteId))
        unfinishedStream = 0
        if (writeText.isNotEmpty()) onDebugLog("install-write 输出: $writeText")
        if (!writeText.contains("Success", ignoreCase = true)) {
            return writeText.ifBlank { I18n.current.installRemoteClosedEarly }
        }

        var commitOut = openService(InstallCommands.commit(sessionId))
        if (commitOut.isBlank()) {
            commitOut = openService(InstallCommands.commitFallback(sessionId))
        }
        val success = commitOut.contains("Success", ignoreCase = true)
        if (success) unfinishedSession = null
        return if (success) I18n.current.installSuccessTag else commitOut
    } catch (e: Exception) {
        return String.format(I18n.current.installStreamError, e.message ?: "")
    } finally {
        try { inputStream.close() } catch (_: Exception) {}
        if (unfinishedStream != 0 && unfinishedRemote != 0 && isAuthenticated) {
            try {
                sendPacket(AdbPacket(AdbPacket.CLSE, unfinishedStream, unfinishedRemote))
            } catch (cleanupError: Exception) {
                onDebugLog("[Install] stream close failed localId=$unfinishedStream type=${cleanupError.javaClass.simpleName}")
            }
        }
        InstallSessionCleanup.abandon(unfinishedSession, isAuthenticated,
            execute = { openService(it) }, log = { onDebugLog(it) })
    }
}
