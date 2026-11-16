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
    fun statusReflectsListenerAndStop() {
        val port = startProxy()
        val running = AdbServerProxy.status()
        assertEquals(port, running.port)
        assertTrue(!running.starting)
        assertEquals(null, running.error)
        AdbServerProxy.stop()
        val stopped = AdbServerProxy.status()
        assertEquals(null, stopped.port)
        assertTrue(!stopped.starting)
        assertEquals(null, stopped.error)
    }

    @Test
    fun listenerScopeDefaultsToLoopbackAndCanEnableLan() {
        val port = startProxy()
        assertEquals("127.0.0.1", AdbServerProxy.status().address)
        AdbServerProxy.stop()
        AdbServerProxy.start(port, allowLan = true)
        awaitAccepting(port)
        assertEquals("0.0.0.0", AdbServerProxy.status().address)
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
        org.junit.Assume.assumeFalse(AdbManager.connected.value)
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

    @Test fun missingModernTransportReturnsFail() {
        org.junit.Assume.assumeFalse(AdbManager.connected.value)
        Socket("127.0.0.1", startProxy()).use { socket ->
            socket.soTimeout = 3000
            writeRequest(socket.getOutputStream(), "host:tport:any")
            assertEquals("FAIL", String(readN(socket.getInputStream(), 4), Charsets.US_ASCII))
            assertTrue(readHexLengthPayload(socket.getInputStream()).contains("device not found"))
        }
    }

    @Test fun trackDevicesSendsInitialListWithoutClosing() {
        org.junit.Assume.assumeFalse(AdbManager.connected.value)
        Socket("127.0.0.1", startProxy()).use { socket ->
            socket.soTimeout = 3000
            writeRequest(socket.getOutputStream(), "host:track-devices")
            assertEquals("OKAY", String(readN(socket.getInputStream(), 4), Charsets.US_ASCII))
            assertEquals("", readHexLengthPayload(socket.getInputStream()))
            socket.soTimeout = 200
            try {
                assertTrue("跟踪连接不应关闭", socket.getInputStream().read() >= 0)
            } catch (_: java.net.SocketTimeoutException) {
                // 无列表变化时连接保持打开且不发送重复消息。
            }
        }
    }

    @Test fun killStopsListenerAndServerCanRestart() {
        val port = startProxy()
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3000
            writeRequest(socket.getOutputStream(), "host:kill")
            assertEquals("OKAY", String(readN(socket.getInputStream(), 4), Charsets.US_ASCII))
            assertEquals(-1, socket.getInputStream().read())
        }
        AdbServerProxy.start(port)
        awaitAccepting(port)
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3000
            writeRequest(socket.getOutputStream(), "host:version")
            assertEquals("OKAY", String(readN(socket.getInputStream(), 4), Charsets.US_ASCII))
            assertEquals("0029", readHexLengthPayload(socket.getInputStream()))
        }
    }

    @Test fun occupiedPortCanBeRetried() {
        val occupied = ServerSocket(0)
        val port = occupied.localPort
        try {
            AdbServerProxy.start(port)
            Thread.sleep(300)
        } finally {
            occupied.close()
        }
        repeat(20) {
            AdbServerProxy.start(port)
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 200)
                    socket.soTimeout = 3000
                    writeRequest(socket.getOutputStream(), "host:version")
                    assertEquals("OKAY", String(readN(socket.getInputStream(), 4), Charsets.US_ASCII))
                    assertEquals("0029", readHexLengthPayload(socket.getInputStream()))
                }
                return
            } catch (_: java.net.ConnectException) {
                Thread.sleep(100)
            }
        }
        throw AssertionError("端口释放后代理未恢复")
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
                val status = AdbServerProxy.status()
                if (status.starting || status.port != port) {
                    Thread.sleep(100)
                    return@repeat
                }
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 500)
                    socket.soTimeout = 1000
                    writeRequest(socket.getOutputStream(), "host:version")
                    assertEquals("OKAY", String(readN(socket.getInputStream(), 4), Charsets.US_ASCII))
                    assertEquals("0029", readHexLengthPayload(socket.getInputStream()))
                }
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
