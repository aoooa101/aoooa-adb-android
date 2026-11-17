package com.aoooa.adb.adb

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 独立服务流的字节读写适配器，读取线程消费报文后发送确认。 */
internal class AdbServicePipe(private val connection: AdbConnection, service: String) : Closeable {
    private val output = AdbServiceOutput()
    private val id = connection.openRawStream(service, { output.offer(it) }, { output.finish() }, autoAcknowledge = false)
    private var current = ByteArray(0)
    private var offset = 0

    init {
        try {
            check(id != 0 && connection.awaitRawStreamReady(id, 5000)) { "ADB service open failed" }
        } catch (error: Exception) {
            if (id != 0) connection.closeRawStream(id)
            if (error is InterruptedException) Thread.currentThread().interrupt()
            throw error
        }
    }

    fun send(bytes: ByteArray) {
        var offset = 0
        val chunk = connection.rawChunkSize(65536)
        while (offset < bytes.size) {
            val end = minOf(offset + chunk, bytes.size)
            check(connection.writeRawStreamAwait(id, bytes.copyOfRange(offset, end))) { "ADB service write failed" }
            offset = end
        }
    }

    fun exact(count: Int, timeoutMs: Long = 12000): ByteArray {
        require(count in 0..65536)
        val result = ByteArray(count)
        var written = 0
        var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (written < count) {
            if (offset == current.size) {
                if (output.isFinished()) throw EOFException("ADB service closed before response completed")
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw IOException("ADB service read timeout")
                val data = output.poll(TimeUnit.NANOSECONDS.toMillis(remaining).coerceIn(1, 200))
                    ?: continue
                current = data
                offset = 0
                connection.acknowledgeRawStream(id)
                deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
                if (current.isEmpty()) continue
            }
            val amount = minOf(count - written, current.size - offset)
            current.copyInto(result, written, offset, offset + amount)
            written += amount
            offset += amount
        }
        return result
    }

    override fun close() = connection.closeRawStream(id)
}
