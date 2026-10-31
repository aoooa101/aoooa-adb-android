package com.aoooa.adb.adb

import android.content.Context
import com.aoooa.adb.adb.protocol.InstallCommands

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
    if (!isAuthenticated) return "未连接设备"
    val contentResolver = context.contentResolver
    val fileSize = try {
        contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
    } catch (_: Exception) { -1L }
    if (fileSize <= 0) return "无法读取 APK 文件大小"

    val inputStream = try {
        contentResolver.openInputStream(uri) ?: return "无法打开 APK 输入流"
    } catch (e: Exception) { return "读取异常: ${e.message}" }

    try {
        // 1. 创建安装 Session
        var createOut = openService(InstallCommands.create(fileSize))
        if (createOut.isBlank() || !createOut.contains("[")) {
            createOut = openService(InstallCommands.createFallback(fileSize))
        }
        val sessionId = InstallCommands.parseSessionId(createOut)
            ?: return "创建安装会话失败: $createOut"

        // 2. 建立 install-write 流
        pendingPackets.clear()
        val localId = localIds.getAndIncrement()
        var remoteId = 0
        val writeCmd = InstallCommands.write(fileSize, sessionId)
        sendPacket(AdbPacket(AdbPacket.OPEN, localId, 0, writeCmd.toByteArray(Charsets.UTF_8)))

        val deadline = System.currentTimeMillis() + 6000
        while (System.currentTimeMillis() < deadline) {
            val pkt = nextPacket(500) ?: continue
            if (pkt.command == AdbPacket.OKAY && pkt.arg1 == localId) {
                remoteId = pkt.arg0
                break
            }
        }
        if (remoteId == 0) return "建立写入通道失败"

        // 3. 流式写入 APK 字节（兼容模式使用 8KB 小块与流控控速）
        val chunkSize = rawChunkSize(if (useCompatibleMode) 8192 else 32768)
        val buf = ByteArray(chunkSize)
        var totalSent = 0L
        var blockCount = 0
        // 兼容模式每 4 块确认一次流控窗口；常规模式放宽到每 32 块，仅用于发现写入通道被提前关闭
        val ackInterval = if (useCompatibleMode) 4 else 32

        while (true) {
            val n = inputStream.read(buf)
            if (n <= 0) break
            val chunk = if (n == buf.size) buf else buf.copyOf(n)
            sendPacket(AdbPacket(AdbPacket.WRTE, localId, remoteId, chunk))
            totalSent += n
            blockCount++

            // 流控：按间隔确认写入通道是否已被被控端关闭
            if (blockCount % ackInterval == 0) {
                val ack = nextPacket(if (useCompatibleMode) 50 else 1)
                if (ack != null && ack.command == AdbPacket.CLSE && ack.arg1 == localId) {
                    return "被控端提前关闭写入通道"
                }
                if (useCompatibleMode) Thread.sleep(10)
            }

            onProgress(totalSent.toFloat() / fileSize.toFloat())
        }
        sendPacket(AdbPacket(AdbPacket.CLSE, localId, remoteId))

        // 排空 install-write 流的输出与退出状态：写入失败时 commit 必然失败，
        // 不读取该流的输出会让用户拿到与真实原因无关的报错
        val writeOut = StringBuilder()
        val writeDeadline = System.currentTimeMillis() + 2500
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
        if (writeText.isNotEmpty()) onDebugLog("install-write 输出: $writeText")

        // 4. 提交安装
        var commitOut = openService(InstallCommands.commit(sessionId))
        if (commitOut.isBlank()) {
            commitOut = openService(InstallCommands.commitFallback(sessionId))
        }
        return if (commitOut.contains("Success", ignoreCase = true)) "Success [安装成功]" else commitOut
    } catch (e: Exception) {
        return "流式安装异常: ${e.message}"
    } finally {
        try { inputStream.close() } catch (_: Exception) {}
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
    if (!isAuthenticated) return "未连接设备"
    if (!file.exists()) return "本地 APK 文件不存在"
    val fileSize = file.length()
    val inputStream = try {
        java.io.FileInputStream(file)
    } catch (e: Exception) { return "读取异常: ${e.message}" }

    try {
        var createOut = openService(InstallCommands.create(fileSize))
        if (createOut.isBlank() || !createOut.contains("[")) {
            createOut = openService(InstallCommands.createFallback(fileSize))
        }
        val sessionId = InstallCommands.parseSessionId(createOut)
            ?: return "创建安装会话失败: $createOut"

        pendingPackets.clear()
        val localId = localIds.getAndIncrement()
        var remoteId = 0
        val writeCmd = InstallCommands.write(fileSize, sessionId)
        sendPacket(AdbPacket(AdbPacket.OPEN, localId, 0, writeCmd.toByteArray(Charsets.UTF_8)))

        val deadline = System.currentTimeMillis() + 6000
        while (System.currentTimeMillis() < deadline) {
            val pkt = nextPacket(500) ?: continue
            if (pkt.command == AdbPacket.OKAY && pkt.arg1 == localId) {
                remoteId = pkt.arg0
                break
            }
        }
        if (remoteId == 0) return "建立写入通道失败"

        val chunkSize = rawChunkSize(if (useCompatibleMode) 8192 else 32768)
        val buf = ByteArray(chunkSize)
        var totalSent = 0L
        var blockCount = 0
        // 兼容模式每 4 块确认一次流控窗口；常规模式放宽到每 32 块，仅用于发现写入通道被提前关闭
        val ackInterval = if (useCompatibleMode) 4 else 32

        while (true) {
            val n = inputStream.read(buf)
            if (n <= 0) break
            val chunk = if (n == buf.size) buf else buf.copyOf(n)
            sendPacket(AdbPacket(AdbPacket.WRTE, localId, remoteId, chunk))
            totalSent += n
            blockCount++

            if (blockCount % ackInterval == 0) {
                val ack = nextPacket(if (useCompatibleMode) 50 else 1)
                if (ack != null && ack.command == AdbPacket.CLSE && ack.arg1 == localId) {
                    return "被控端提前关闭写入通道"
                }
                if (useCompatibleMode) Thread.sleep(10)
            }

            onProgress(totalSent.toFloat() / fileSize.toFloat())
        }
        sendPacket(AdbPacket(AdbPacket.CLSE, localId, remoteId))

        // 排空 install-write 流的输出与退出状态（同 Uri 重载）
        val writeOut = StringBuilder()
        val writeDeadline = System.currentTimeMillis() + 2500
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
        if (writeText.isNotEmpty()) onDebugLog("install-write 输出: $writeText")

        var commitOut = openService(InstallCommands.commit(sessionId))
        if (commitOut.isBlank()) {
            commitOut = openService(InstallCommands.commitFallback(sessionId))
        }
        return if (commitOut.contains("Success", ignoreCase = true)) "Success [安装成功]" else commitOut
    } catch (e: Exception) {
        return "流式安装异常: ${e.message}"
    } finally {
        try { inputStream.close() } catch (_: Exception) {}
    }
}
