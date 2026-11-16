package com.aoooa.adb

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aoooa.adb.adb.AdbConnection
import com.aoooa.adb.adb.AdbPacket
import com.aoooa.adb.adb.proxy.ProxyStreamBridge
import com.aoooa.adb.bridge.Channel
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 模拟设备 ADB 报文，验证代理字节流及流控，不使用全局设备连接。 */
@RunWith(AndroidJUnit4::class)
class AdbProxyStreamTest {
    private class Peer : Channel {
        lateinit var connection: AdbConnection
        val packets = LinkedBlockingQueue<AdbPacket>()
        override fun send(data: ByteArray): Boolean {
            val packet = AdbPacket.tryParse(data)!!.first
            if (packet.command == AdbPacket.CNXN) {
                connection.onData(AdbPacket(AdbPacket.CNXN, 0x01000001, 4096,
                    "device::features=shell_v2,stat_v2\u0000".toByteArray()).toBytes())
            } else packets.offer(packet)
            return true
        }
        override fun close() = Unit
        fun receive(command: Int): AdbPacket {
            val packet = packets.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("missing ADB packet")
            assertEquals(command, packet.command)
            return packet
        }
        fun send(command: Int, local: Int, bytes: ByteArray = byteArrayOf()) {
            connection.onData(AdbPacket(command, 99, local, bytes).toBytes())
        }
    }

    private fun fixture(test: (Peer, Socket) -> Unit) {
        val peer = Peer()
        val connection = AdbConnection(peer)
        peer.connection = connection
        assertTrue(connection.connect())
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            Socket("127.0.0.1", server.localPort).use { client ->
                client.soTimeout = 5000
                val accepted = server.accept()
                val result = java.util.concurrent.FutureTask {
                    accepted.use { ProxyStreamBridge.relay(it, connection, "shell,v2,raw:cat") }
                }
                val worker = Thread(result).apply { isDaemon = true; start() }
                try { test(peer, client) } finally {
                    accepted.close()
                    connection.disconnect()
                    result.get(6, TimeUnit.SECONDS)
                    worker.join(1000)
                }
            }
        }
    }

    @Test fun binaryOutputAndInputRemainUnchanged() = fixture { peer, client ->
        val open = peer.receive(AdbPacket.OPEN)
        assertEquals("shell,v2,raw:cat\u0000", String(open.payload))
        peer.send(AdbPacket.OKAY, open.arg0)
        val input = java.io.DataInputStream(client.getInputStream())
        val status = ByteArray(4).also { input.readFully(it) }
        assertEquals("OKAY", String(status))
        val bytes = byteArrayOf(0, -1, 10, 0, 127, -128)
        peer.send(AdbPacket.WRTE, open.arg0, bytes)
        assertArrayEquals(bytes, ByteArray(bytes.size).also { input.readFully(it) })
        peer.receive(AdbPacket.OKAY)
        client.getOutputStream().write(bytes)
        client.getOutputStream().flush()
        assertArrayEquals(bytes, peer.receive(AdbPacket.WRTE).payload)
        peer.send(AdbPacket.OKAY, open.arg0)
        peer.send(AdbPacket.CLSE, open.arg0)
        peer.receive(AdbPacket.CLSE)
        assertEquals(-1, input.read())
    }

    @Test fun clientOutputShutdownKeepsDeviceOutputOpen() = fixture { peer, client ->
        val open = peer.receive(AdbPacket.OPEN)
        peer.send(AdbPacket.OKAY, open.arg0)
        val input = java.io.DataInputStream(client.getInputStream())
        assertEquals("OKAY", String(ByteArray(4).also { input.readFully(it) }))
        client.shutdownOutput()
        assertNull("输入 EOF 不应关闭设备流", peer.packets.poll(500, TimeUnit.MILLISECONDS))
        listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6)).forEach { bytes ->
            peer.send(AdbPacket.WRTE, open.arg0, bytes)
            assertArrayEquals(bytes, ByteArray(bytes.size).also { input.readFully(it) })
            peer.receive(AdbPacket.OKAY)
        }
        peer.send(AdbPacket.CLSE, open.arg0)
        peer.receive(AdbPacket.CLSE)
        assertEquals(-1, input.read())
    }

    @Test fun rejectedServiceReturnsFailNotOkay() = fixture { peer, client ->
        val open = peer.receive(AdbPacket.OPEN)
        peer.send(AdbPacket.CLSE, open.arg0)
        val status = ByteArray(4)
        java.io.DataInputStream(client.getInputStream()).readFully(status)
        assertEquals("FAIL", String(status))
    }

    @Test fun immediateExitKeepsQueuedOutput() = fixture { peer, client ->
        val open = peer.receive(AdbPacket.OPEN)
        peer.send(AdbPacket.OKAY, open.arg0)
        peer.send(AdbPacket.WRTE, open.arg0, byteArrayOf(3, 1, 0, 0, 0, 7))
        peer.send(AdbPacket.CLSE, open.arg0)
        val bytes = client.getInputStream().readBytes()
        assertArrayEquals("OKAY".toByteArray() + byteArrayOf(3, 1, 0, 0, 0, 7), bytes)
    }
}
