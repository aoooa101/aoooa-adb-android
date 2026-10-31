package com.aoooa.adb.adb

import android.content.Context
import com.aoooa.adb.adb.protocol.SyncProtocol
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AdbConnection 的 sync:（文件传输）协议实现。
 *
 * 职责边界：只负责 SEND / RECV / DATA / DONE 帧的收发与本地文件流对接，
 * 传输层报文编解码、认证与流控由 [AdbConnection] 提供。
 */

/**
 * 等待 sync: 服务对 DONE 报文的确认响应。
 *
 * 设备写入成功时以 4 字节 "OKAY" 回复，失败时回复 "FAIL" + 长度（小端）+ 原因文本。
 * 传输层 OKAY 报文不带 payload，不参与判定。
 *
 * @return true 表示设备确认写入成功
 */
internal fun AdbConnection.awaitSyncDone(
    localId: Int,
    remoteId: Int,
    timeoutMs: Long = 3000L
): Boolean {
    val skipped = ArrayList<AdbPacket>()
    val deadline = System.currentTimeMillis() + timeoutMs
    var confirmed = false
    while (System.currentTimeMillis() < deadline) {
        val pkt = nextPacket(200) ?: continue
        if (pkt.arg1 != localId) {
            skipped.add(pkt)
            continue
        }
        if (pkt.command == AdbPacket.CLSE) break
        val payload = pkt.payload
        if (pkt.command != AdbPacket.WRTE || payload.size < 4) continue
        val magic = String(payload, 0, 4, Charsets.US_ASCII)
        if (magic == SyncProtocol.MAGIC_OKAY) {
            confirmed = true
            break
        }
        if (magic == SyncProtocol.MAGIC_FAIL) {
            val len = if (payload.size >= 8) {
                ByteBuffer.wrap(payload, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            } else {
                0
            }
            val msg = if (len > 0 && payload.size >= 8 + len) {
                String(payload, 8, len, Charsets.UTF_8)
            } else {
                SyncProtocol.MAGIC_FAIL
            }
            onDebugLog("sync DONE 失败: $msg")
            break
        }
    }
    // 判定期间跳过的其他流报文放回队列，避免影响后续读写
    for (p in skipped) pendingPackets.offer(p)
    if (!confirmed) onDebugLog("sync DONE 未获确认 (localId=$localId)")
    return confirmed
}

/**
 * AOSP 标准 sync: 协议流式推送文件到目标设备指定目录
 */
fun AdbConnection.pushFile(
    context: Context,
    uri: android.net.Uri,
    fileName: String,
    targetDir: String,
    onProgress: (percent: Float, sent: Long, total: Long) -> Unit
): Boolean {
    if (!isAuthenticated) return false
    val cleanDir = if (targetDir.endsWith("/")) targetDir.dropLast(1) else targetDir
    val remotePath = "$cleanDir/$fileName,33188" // 0100644 标准文件权限
    val contentResolver = context.contentResolver

    val fileSize = try {
        contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
    } catch (_: Exception) { -1L }

    val inputStream = try {
        contentResolver.openInputStream(uri) ?: return false
    } catch (_: Exception) { return false }

    pendingPackets.clear()
    val localId = localIds.getAndIncrement()
    var remoteId = 0

    // 1. 发起 sync: 服务流
    sendPacket(AdbPacket(AdbPacket.OPEN, localId, 0, SyncProtocol.servicePayload))
    val openDeadline = System.currentTimeMillis() + 5000
    while (System.currentTimeMillis() < openDeadline) {
        val pkt = nextPacket(500) ?: continue
        if (pkt.command == AdbPacket.OKAY && pkt.arg1 == localId) {
            remoteId = pkt.arg0
            break
        }
    }
    if (remoteId == 0) {
        inputStream.close()
        return false
    }

    try {
        // 2. 发送 SEND 报文头
        sendPacket(AdbPacket(AdbPacket.WRTE, localId, remoteId, SyncProtocol.sendRequest(remotePath)))

        // 3. 循环发送 DATA 分块（按协商后的 payload 上限切片，8 字节为 sync DATA 帧头）
        val buffer = ByteArray(syncChunkSize())
        var totalSent = 0L
        while (true) {
            val read = inputStream.read(buffer)
            if (read <= 0) break

            sendPacket(AdbPacket(AdbPacket.WRTE, localId, remoteId, SyncProtocol.dataChunk(buffer, 0, read)))

            totalSent += read
            val pct = if (fileSize > 0) (totalSent.toFloat() / fileSize.toFloat()) else 0f
            onProgress(pct, totalSent, fileSize)
        }

        // 4. 发送 DONE 报文
        sendPacket(AdbPacket(AdbPacket.WRTE, localId, remoteId, SyncProtocol.done()))

        // 5. 等待设备确认写入结果后关闭流
        val confirmed = awaitSyncDone(localId, remoteId)
        sendPacket(AdbPacket(AdbPacket.CLSE, localId, remoteId))
        return confirmed
    } catch (e: Exception) {
        onDebugLog("pushFile 异常: ${e.message}")
        sendPacket(AdbPacket(AdbPacket.CLSE, localId, remoteId))
        return false
    } finally {
        try { inputStream.close() } catch (_: Exception) {}
    }
}

/**
 * 重载：支持直接从本地 File 推送
 */
fun AdbConnection.pushFile(
    file: java.io.File,
    fileName: String,
    targetDir: String,
    onProgress: (percent: Float, sent: Long, total: Long) -> Unit
): Boolean {
    if (!isAuthenticated || !file.exists()) return false
    val cleanDir = if (targetDir.endsWith("/")) targetDir.dropLast(1) else targetDir
    val remotePath = "$cleanDir/$fileName,33188"
    val fileSize = file.length()
    val inputStream = try {
        java.io.FileInputStream(file)
    } catch (_: Exception) { return false }

    pendingPackets.clear()
    val localId = localIds.getAndIncrement()
    var remoteId = 0

    sendPacket(AdbPacket(AdbPacket.OPEN, localId, 0, SyncProtocol.servicePayload))
    val openDeadline = System.currentTimeMillis() + 5000
    while (System.currentTimeMillis() < openDeadline) {
        val pkt = nextPacket(500) ?: continue
        if (pkt.command == AdbPacket.OKAY && pkt.arg1 == localId) {
            remoteId = pkt.arg0
            break
        }
    }
    if (remoteId == 0) {
        try { inputStream.close() } catch (_: Exception) {}
        return false
    }

    try {
        sendPacket(AdbPacket(AdbPacket.WRTE, localId, remoteId, SyncProtocol.sendRequest(remotePath)))

        // 按协商后的 payload 上限切片（8 字节为 sync DATA 帧头）
        val buffer = ByteArray(syncChunkSize())
        var totalSent = 0L
        while (true) {
            val read = inputStream.read(buffer)
            if (read <= 0) break

            sendPacket(AdbPacket(AdbPacket.WRTE, localId, remoteId, SyncProtocol.dataChunk(buffer, 0, read)))

            totalSent += read
            val pct = if (fileSize > 0) (totalSent.toFloat() / fileSize.toFloat()) else 0f
            onProgress(pct, totalSent, fileSize)
        }

        sendPacket(AdbPacket(AdbPacket.WRTE, localId, remoteId, SyncProtocol.done()))

        val confirmed = awaitSyncDone(localId, remoteId)
        sendPacket(AdbPacket(AdbPacket.CLSE, localId, remoteId))
        return confirmed
    } catch (e: Exception) {
        onDebugLog("pushFile(File) 异常: ${e.message}")
        sendPacket(AdbPacket(AdbPacket.CLSE, localId, remoteId))
        return false
    } finally {
        try { inputStream.close() } catch (_: Exception) {}
    }
}

/**
 * AOSP 标准 sync: 协议拉取文件到本地输出流 (支持分块解析与进度回调)
 */
fun AdbConnection.pullFile(
    remotePath: String,
    outputStream: java.io.OutputStream,
    expectedSize: Long = -1L,
    onProgress: (percent: Float, sent: Long, total: Long) -> Unit
): Boolean {
    if (!isAuthenticated) return false
    pendingPackets.clear()
    val localId = localIds.getAndIncrement()
    var remoteId = 0

    // 1. 发起 sync: 服务流
    sendPacket(AdbPacket(AdbPacket.OPEN, localId, 0, SyncProtocol.servicePayload))
    val openDeadline = System.currentTimeMillis() + 5000
    while (System.currentTimeMillis() < openDeadline) {
        val pkt = nextPacket(500) ?: continue
        if (pkt.command == AdbPacket.OKAY && pkt.arg1 == localId) {
            remoteId = pkt.arg0
            break
        }
    }
    if (remoteId == 0) return false

    try {
        // 2. 发送 RECV 报文头
        sendPacket(AdbPacket(AdbPacket.WRTE, localId, remoteId, SyncProtocol.recvRequest(remotePath)))

        var totalRead = 0L
        val packetDeadline = 12000L
        var lastPacketTime = System.currentTimeMillis()
        var isDone = false
        var failed = false

        val chunkBuffer = java.io.ByteArrayOutputStream()

        while (!isDone && System.currentTimeMillis() - lastPacketTime < packetDeadline) {
            val pkt = nextPacket(500) ?: continue
            lastPacketTime = System.currentTimeMillis()

            if (pkt.command == AdbPacket.CLSE && pkt.arg1 == localId) {
                break
            }

            if (pkt.command == AdbPacket.WRTE && pkt.arg1 == localId) {
                sendPacket(AdbPacket(AdbPacket.OKAY, localId, remoteId))
                chunkBuffer.write(pkt.payload)

                val raw = chunkBuffer.toByteArray()
                var offset = 0
                while (offset + 8 <= raw.size) {
                    val magic = String(raw, offset, 4, Charsets.US_ASCII)
                    val length = java.nio.ByteBuffer.wrap(raw, offset + 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                    if (magic != SyncProtocol.MAGIC_DONE && length !in 0..65536) {
                        // 异常帧直接中止也必须关闭流，否则 adbd 侧 sync 服务 fd 常驻不释放
                        sendPacket(AdbPacket(AdbPacket.CLSE, localId, remoteId))
                        return false
                    }
                    if (magic == SyncProtocol.MAGIC_DONE) {
                        isDone = true
                        offset += 8
                        break
                    } else if (magic == SyncProtocol.MAGIC_DATA) {
                        if (offset + 8 + length <= raw.size) {
                            outputStream.write(raw, offset + 8, length)
                            totalRead += length
                            offset += 8 + length
                            val pct = if (expectedSize > 0) (totalRead.toFloat() / expectedSize.toFloat()).coerceIn(0f, 1f) else 0f
                            onProgress(pct, totalRead, expectedSize)
                        } else {
                            break
                        }
                    } else if (magic == SyncProtocol.MAGIC_FAIL) {
                        if (raw.size - offset - 8 < length) break
                        failed = true
                        val msg = if (offset + 8 + length <= raw.size) String(raw, offset + 8, length, Charsets.UTF_8) else SyncProtocol.MAGIC_FAIL
                        onDebugLog("pullFile 远端错误: $msg")
                        isDone = true
                        break
                    } else {
                        offset++
                    }
                }

                chunkBuffer.reset()
                if (offset < raw.size) {
                    chunkBuffer.write(raw, offset, raw.size - offset)
                }
            }
        }
        outputStream.flush()
        sendPacket(AdbPacket(AdbPacket.CLSE, localId, remoteId))
        return isDone && !failed && (expectedSize < 0 || totalRead == expectedSize)
    } catch (e: Exception) {
        onDebugLog("pullFile 异常: ${e.message}")
        sendPacket(AdbPacket(AdbPacket.CLSE, localId, remoteId))
        return false
    }
}
