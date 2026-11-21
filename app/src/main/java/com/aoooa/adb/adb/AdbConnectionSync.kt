package com.aoooa.adb.adb

import android.content.Context
import com.aoooa.adb.adb.protocol.SyncProtocol
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Sync v1 头部始终为四字节类型和四字节小端长度或状态值。 */
private fun AdbServicePipe.syncHeader(timeoutMs: Long = 12000): Pair<String, Int> {
    val bytes = exact(8, timeoutMs)
    return String(bytes, 0, 4, Charsets.US_ASCII) to
        ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
}

private fun AdbServicePipe.syncFailure(length: Int): Nothing {
    require(length in 0..65536) { "Invalid Sync failure length" }
    throw java.io.IOException(String(exact(length), Charsets.UTF_8))
}

/** 文件和内存推送共享独立 Sync 流，不消费其他服务的报文。输入流由调用方关闭。 */
internal fun AdbConnection.pushSyncInput(
    input: InputStream,
    remoteWithMode: String,
    size: Long,
    progress: (Float, Long, Long) -> Unit
): Boolean {
    if (!isAuthenticated) return false
    return try {
        AdbServicePipe(this, SyncProtocol.SERVICE).use { pipe ->
            pipe.send(SyncProtocol.sendRequest(remoteWithMode))
            val buffer = ByteArray(syncChunkSize())
            var sent = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) throw java.io.IOException("Sync reader made no progress")
                pipe.send(SyncProtocol.dataChunk(buffer, 0, count))
                sent += count
                progress(if (size > 0) sent.toFloat() / size else 0f, sent, size)
            }
            check(size < 0 || sent == size) { "Source size changed during transfer" }
            pipe.send(SyncProtocol.done())
            val (type, value) = pipe.syncHeader(3000)
            if (type == SyncProtocol.MAGIC_FAIL) pipe.syncFailure(value)
            check(type == SyncProtocol.MAGIC_OKAY && value == 0) { "Sync upload not acknowledged" }
            true
        }
    } catch (error: Exception) {
        if (error is InterruptedException) Thread.currentThread().interrupt()
        onDebugLog("pushFile failed: ${error.javaClass.simpleName}: ${error.message}")
        false
    }
}

/** 从 Uri 推送文件，无法查询大小时仍允许流式读取。 */
fun AdbConnection.pushFile(
    context: Context,
    uri: android.net.Uri,
    fileName: String,
    targetDir: String,
    onProgress: (percent: Float, sent: Long, total: Long) -> Unit
): Boolean {
    if (!isAuthenticated) return false
    val resolver = context.contentResolver
    val size = try {
        resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
    } catch (error: Exception) {
        onDebugLog("读取待发送文件大小失败: ${error.javaClass.simpleName}: ${error.message}")
        -1L
    }
    return try {
        val input = resolver.openInputStream(uri) ?: return false
        input.use { pushSyncInput(it, "${targetDir.removeSuffix("/")}/$fileName,33188", size, onProgress) }
    } catch (error: Exception) {
        if (error is InterruptedException) Thread.currentThread().interrupt()
        onDebugLog("pushFile source failed: ${error.javaClass.simpleName}: ${error.message}")
        false
    }
}

/** 从本地文件推送，完成前核对发送字节数。 */
fun AdbConnection.pushFile(
    file: java.io.File,
    fileName: String,
    targetDir: String,
    onProgress: (percent: Float, sent: Long, total: Long) -> Unit
): Boolean {
    if (!isAuthenticated || !file.exists()) return false
    return try {
        file.inputStream().use {
            pushSyncInput(it, "${targetDir.removeSuffix("/")}/$fileName,33188", file.length(), onProgress)
        }
    } catch (error: Exception) {
        if (error is InterruptedException) Thread.currentThread().interrupt()
        onDebugLog("pushFile source failed: ${error.javaClass.simpleName}: ${error.message}")
        false
    }
}

/** 拉取文件并重组跨 ADB 数据包的 Sync 帧；输出流由调用方持有。 */
fun AdbConnection.pullFile(
    remotePath: String,
    outputStream: java.io.OutputStream,
    expectedSize: Long = -1L,
    onProgress: (percent: Float, sent: Long, total: Long) -> Unit
): Boolean {
    if (!isAuthenticated) return false
    return try {
        AdbServicePipe(this, SyncProtocol.SERVICE).use { pipe ->
            pipe.send(SyncProtocol.recvRequest(remotePath))
            var received = 0L
            while (true) {
                val (type, length) = pipe.syncHeader()
                when (type) {
                    SyncProtocol.MAGIC_DONE -> {
                        outputStream.flush()
                        return@use expectedSize < 0 || received == expectedSize
                    }
                    SyncProtocol.MAGIC_DATA -> {
                        require(length in 0..65536) { "Invalid Sync data length" }
                        val bytes = pipe.exact(length)
                        outputStream.write(bytes)
                        received += length
                        onProgress(if (expectedSize > 0) (received.toFloat() / expectedSize).coerceIn(0f, 1f) else 0f,
                            received, expectedSize)
                    }
                    SyncProtocol.MAGIC_FAIL -> pipe.syncFailure(length)
                    else -> throw java.io.IOException("Invalid Sync response: $type")
                }
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }
    } catch (error: Exception) {
        if (error is InterruptedException) Thread.currentThread().interrupt()
        onDebugLog("pullFile failed: ${error.javaClass.simpleName}: ${error.message}")
        false
    }
}
