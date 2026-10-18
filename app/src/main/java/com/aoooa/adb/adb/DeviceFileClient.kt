package com.aoooa.adb.adb

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** 使用独立 ADB 流处理文件命令及 Sync v1 传输。 */
class DeviceFileClient(private val connection: AdbConnection) {
    private class Pipe(private val connection: AdbConnection, service: String) : Closeable {
        private val queue = LinkedBlockingQueue<ByteArray>()
        @Volatile private var ended = false
        private var current = ByteArray(0)
        private var offset = 0
        private val id = connection.openRawStream(service, { queue.offer(it) }, { ended = true })

        init {
            if (id == 0 || (!connection.awaitRawStreamReady(id) && !ended)) {
                connection.closeRawStream(id)
                error("无法建立文件通道 / Cannot open file channel")
            }
        }

        fun send(bytes: ByteArray) {
            // 兼容 USB 经典 ADB 的 4096 字节最大负载。
            var start = 0
            while (start < bytes.size) {
                val end = minOf(start + 4096, bytes.size)
                check(connection.writeRawStreamAwait(id, bytes.copyOfRange(start, end))) {
                    "文件发送超时或连接断开 / File channel disconnected"
                }
                start = end
            }
        }

        fun readSome(): ByteArray? {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (System.nanoTime() < deadline) {
                val next = queue.poll(100, TimeUnit.MILLISECONDS)
                if (next != null) return next
                if (ended) return queue.poll()
            }
            error("文件通道读取超时 / File read timeout")
        }

        fun exact(count: Int): ByteArray {
            require(count in 0..65536)
            val result = ByteArray(count)
            var written = 0
            while (written < count) {
                if (offset == current.size) {
                    current = readSome() ?: error("文件传输提前结束 / Incomplete transfer")
                    offset = 0
                }
                val n = minOf(count - written, current.size - offset)
                current.copyInto(result, written, offset, offset + n)
                offset += n
                written += n
            }
            return result
        }

        override fun close() = connection.closeRawStream(id)
    }

    /** exec 服务不分配 PTY，保留 NUL 分隔符及原始 UTF-8 字节。 */
    fun exec(command: String): String = Pipe(connection, "exec:$command").use { pipe ->
        val output = ByteArrayOutputStream()
        while (true) {
            val bytes = pipe.readSome() ?: break
            check(output.size() + bytes.size <= 16 * 1024 * 1024) { "目录内容过多 / Output too large" }
            output.write(bytes)
        }
        String(output.toByteArray(), Charsets.UTF_8)
    }

    private fun request(id: String, path: String): ByteArray {
        val data = path.toByteArray(Charsets.UTF_8)
        require(data.size <= 1024 && '\u0000' !in path) { "路径过长或无效 / Invalid path" }
        return frame(id, data.size, data)
    }

    private fun frame(id: String, value: Int, data: ByteArray = ByteArray(0)): ByteArray =
        ByteBuffer.allocate(8 + data.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(id.toByteArray(Charsets.US_ASCII)).putInt(value).put(data).array()

    private fun header(pipe: Pipe): Pair<String, Int> {
        val bytes = pipe.exact(8)
        return String(bytes, 0, 4, Charsets.US_ASCII) to
            ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun failure(pipe: Pipe, length: Int): Nothing {
        check(length in 0..65536) { "无效的 Sync 错误长度 / Invalid Sync error" }
        error(String(pipe.exact(length), Charsets.UTF_8))
    }

    fun download(path: String, output: OutputStream, expectedSize: Long, progress: (Float) -> Unit) {
        Pipe(connection, "sync:").use { pipe ->
            pipe.send(request("RECV", path))
            var received = 0L
            while (true) {
                val (id, length) = header(pipe)
                when (id) {
                    "DATA" -> {
                        check(length in 0..65536) { "无效的 Sync 数据块 / Invalid Sync DATA" }
                        output.write(pipe.exact(length))
                        received += length
                        progress(if (expectedSize > 0) (received.toFloat() / expectedSize).coerceIn(0f, 1f) else 0f)
                    }
                    "DONE" -> {
                        output.flush()
                        progress(1f)
                        return
                    }
                    "FAIL" -> failure(pipe, length)
                    else -> error("无效的 Sync 响应: $id")
                }
            }
        }
    }

    fun upload(path: String, input: InputStream, size: Long, progress: (Float) -> Unit) {
        Pipe(connection, "sync:").use { pipe ->
            pipe.send(request("SEND", "$path,33206"))
            val buffer = ByteArray(32768)
            var sent = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                pipe.send(frame("DATA", n, buffer.copyOf(n)))
                sent += n
                progress(if (size > 0) (sent.toFloat() / size).coerceIn(0f, 0.99f) else 0f)
            }
            check(size < 0 || size == sent) { "源文件大小发生变化 / Source size changed" }
            pipe.send(frame("DONE", (System.currentTimeMillis() / 1000).toInt()))
            val (id, length) = header(pipe)
            if (id == "FAIL") failure(pipe, length)
            check(id == "OKAY") { "上传未获设备确认 / Upload not acknowledged" }
            progress(1f)
        }
    }
}
