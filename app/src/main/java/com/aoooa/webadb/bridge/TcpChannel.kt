package com.aoooa.webadb.bridge

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 无线（TCP）通道：直连设备 adbd 的 5555 端口。
 * 浏览器 WebSocket 无法直连裸 TCP，但 App 原生层可以——这就是 App 版解锁无线调试的关键。
 */
class TcpChannel(private val onData: (ByteArray) -> Unit) : Channel {

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var readThread: Thread? = null

    @Volatile
    private var running = false

    /** 同步连接（调用方应在子线程执行）。 */
    fun connect(host: String, port: Int): Boolean {
        return try {
            val sock = Socket()
            sock.connect(InetSocketAddress(host, port), 8000)
            sock.tcpNoDelay = true
            socket = sock
            input = sock.getInputStream()
            output = sock.getOutputStream()
            running = true
            startReadLoop()
            true
        } catch (e: Exception) {
            close()
            false
        }
    }

    private fun startReadLoop() {
        readThread = Thread {
            val buffer = ByteArray(65536)
            while (running) {
                try {
                    val n = input?.read(buffer) ?: -1
                    if (n <= 0) break
                    onData(buffer.copyOf(n))
                } catch (e: Exception) {
                    break
                }
            }
            running = false
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    override fun send(data: ByteArray): Boolean {
        return try {
            output?.write(data)
            output?.flush()
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun close() {
        running = false
        readThread?.interrupt()
        readThread = null
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        input = null
        output = null
    }
}
