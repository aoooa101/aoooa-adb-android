package com.aoooa.adb.adb.proxy

import com.aoooa.adb.adb.AdbConnection
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 在独立 ADB 流和 Smart Socket 之间双向转发原始字节。 */
internal object ProxyStreamBridge {
    fun relay(socket: Socket, connection: AdbConnection, service: String) {
        val output = socket.getOutputStream()
        val queue = ArrayBlockingQueue<ByteArray>(8)
        val finished = AtomicBoolean(false)
        val overflow = AtomicBoolean(false)
        val ready = AtomicBoolean(false)
        val opened = CountDownLatch(1)
        val id = connection.openRawStream(service,
            onBytes = { bytes ->
                if (!queue.offer(bytes)) {
                    overflow.set(true)
                    finished.set(true)
                    opened.countDown()
                }
            },
            onClosed = { finished.set(true); opened.countDown() },
            onReady = { ready.set(true); opened.countDown() }, autoAcknowledge = false)
        if (id == 0) {
            SmartSocketProtocol.fail(output, "device offline")
            return
        }
        var reader: Thread? = null
        try {
            if (!opened.await(10, TimeUnit.SECONDS) || !ready.get() || overflow.get()) {
                SmartSocketProtocol.fail(output, "device service rejected or timed out")
                return
            }
            SmartSocketProtocol.okay(output)
            socket.soTimeout = 0
            reader = Thread({
                try {
                    val buffer = ByteArray(connection.rawChunkSize(64 * 1024))
                    val input = socket.getInputStream()
                    while (!finished.get()) {
                        val size = input.read(buffer)
                        // 客户端关闭发送方向后，设备输出仍由当前会话转发。
                        if (size < 0) break
                        if (size > 0 && !connection.writeRawStreamAwait(id, buffer.copyOf(size))) {
                            finished.set(true)
                        }
                    }
                } catch (_: Exception) {
                    finished.set(true)
                }
            }, "adb-proxy-input").apply { isDaemon = true; start() }
            while (!finished.get() || queue.isNotEmpty()) {
                if (overflow.get() || socket.isClosed || !connection.isAuthenticated) break
                val bytes = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                output.write(bytes)
                output.flush()
                connection.acknowledgeRawStream(id)
            }
        } finally {
            finished.set(true)
            connection.closeRawStream(id)
            runCatching { socket.close() }
            reader?.interrupt()
        }
    }
}
