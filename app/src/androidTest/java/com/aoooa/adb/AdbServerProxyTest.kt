package com.aoooa.adb

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aoooa.adb.adb.AdbServerProxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 本地 ADB 代理服务的端到端测试：真实建立 TCP 连接并校验握手报文。
 *
 * 覆盖 host:version 版本回复、未连接设备时的空列表、未知命令的 FAIL 回复。
 */
@RunWith(AndroidJUnit4::class)
class AdbServerProxyTest {

    private var boundPort = 0

    @After
    fun tearDown() {
        AdbServerProxy.stop()
    }

    @Test
    fun hostVersionReturnsProtocolVersion() {
        Socket("127.0.0.1", startProxy()).use { socket ->
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            writeRequest(output, "host:version")

            assertEquals("OKAY", String(readN(input, 4), Charsets.US_ASCII))
            val payload = readHexLengthPayload(input)
            // 服务端版本常量 41 (0x29)，按 4 位十六进制返回
            assertEquals("0029", payload)
        }
    }

    @Test
    fun hostDevicesReturnsEmptyListWhenDisconnected() {
        Socket("127.0.0.1", startProxy()).use { socket ->
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            writeRequest(output, "host:devices")

            assertEquals("OKAY", String(readN(input, 4), Charsets.US_ASCII))
            val payload = readHexLengthPayload(input)
            assertTrue("未连接设备时列表应为空，实际: '$payload'", payload.isEmpty())
        }
    }

    @Test
    fun unknownCommandReturnsFail() {
        Socket("127.0.0.1", startProxy()).use { socket ->
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            writeRequest(output, "host:foo")

            assertEquals("FAIL", String(readN(input, 4), Charsets.US_ASCII))
            val reason = readHexLengthPayload(input)
            assertTrue("FAIL 原因应包含 unknown request，实际: '$reason'", reason.contains("unknown request"))
        }
    }

    @Test
    fun hostKillAcknowledgesRequest() {
        Socket("127.0.0.1", startProxy()).use { socket ->
            val output = socket.getOutputStream()
            writeRequest(output, "host:kill")
            assertEquals("OKAY", String(readN(socket.getInputStream(), 4), Charsets.US_ASCII))
        }
    }

    /** 启动代理并返回可连接的端口；服务端绑定在后台线程完成，需要重试连接。 */
    private fun startProxy(): Int {
        if (boundPort == 0) {
            boundPort = ServerSocket(0).use { it.localPort }
            AdbServerProxy.start(boundPort)
        }
        awaitAccepting(boundPort)
        return boundPort
    }

    private fun awaitAccepting(port: Int) {
        var lastError: Exception? = null
        repeat(50) {
            try {
                // 探测连接成功后立即关闭，真实请求由调用方重新建立
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
                return
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(100)
            }
        }
        throw AssertionError("本地代理端口 $port 未就绪", lastError)
    }

    private fun writeRequest(output: java.io.OutputStream, request: String) {
        val body = request.toByteArray(Charsets.UTF_8)
        val header = String.format("%04x", body.size).toByteArray(Charsets.US_ASCII)
        output.write(header)
        output.write(body)
        output.flush()
    }

    private fun readHexLengthPayload(input: InputStream): String {
        val lenHex = String(readN(input, 4), Charsets.US_ASCII)
        val len = lenHex.toInt(16)
        if (len == 0) return ""
        return String(readN(input, len), Charsets.UTF_8)
    }

    private fun readN(input: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(buffer, offset, size - offset)
            if (read < 0) throw AssertionError("连接已关闭，已读取 $offset/$size 字节")
            offset += read
        }
        return buffer
    }
}
