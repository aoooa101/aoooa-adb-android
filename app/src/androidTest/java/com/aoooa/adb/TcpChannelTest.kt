package com.aoooa.adb

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aoooa.adb.bridge.TcpChannel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 使用回环服务端验证 TCP 通道，不依赖真实 ADB 设备或外部网络。 */
@RunWith(AndroidJUnit4::class)
class TcpChannelTest {
    private fun server() = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply {
        soTimeout = 3000
    }

    @Test fun readDirectCombinesFragments() {
        server().use { server ->
            val channel = TcpChannel(onData = {})
            try {
                assertTrue(channel.connect("127.0.0.1", server.localPort))
                server.accept().use { peer ->
                    val result = java.util.concurrent.FutureTask { channel.readDirect(6) }
                    val worker = Thread(result).apply { isDaemon = true; start() }
                    try {
                        peer.getOutputStream().write(byteArrayOf(1, 2))
                        peer.getOutputStream().flush()
                        peer.getOutputStream().write(byteArrayOf(3, 4, 5, 6))
                        peer.getOutputStream().flush()
                        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), result.get(3, TimeUnit.SECONDS))
                    } finally {
                        channel.close()
                        worker.join(1000)
                    }
                }
            } finally { channel.close() }
        }
    }

    @Test fun sendsExactBytesAndLogsSocketConnection() {
        server().use { server ->
            val messages = mutableListOf<String>()
            val channel = TcpChannel(onData = {}, onStatus = { messages.add(it) })
            try {
                assertTrue(channel.connect("127.0.0.1", server.localPort))
                server.accept().use { peer ->
                    peer.soTimeout = 3000
                    val expected = byteArrayOf(0, 1, 127, -1)
                    assertTrue(channel.send(expected))
                    val actual = ByteArray(expected.size)
                    java.io.DataInputStream(peer.getInputStream()).readFully(actual)
                    assertArrayEquals(expected, actual)
                    assertTrue(messages.any { it.startsWith("tcp_connected:") })
                }
            } finally { channel.close() }
        }
    }

    @Test fun peerClosureNotifiesExactlyOnce() {
        server().use { server ->
            val disconnected = CountDownLatch(1)
            val count = AtomicInteger()
            val channel = TcpChannel(onData = {}, onDisconnected = {
                count.incrementAndGet()
                disconnected.countDown()
            })
            try {
                assertTrue(channel.connect("127.0.0.1", server.localPort))
                val peer = server.accept()
                try { channel.startReading() } finally { peer.close() }
                assertTrue(disconnected.await(3, TimeUnit.SECONDS))
                channel.close()
                assertEquals(1, count.get())
            } finally { channel.close() }
        }
    }

    @Test fun activeCloseClosesSocketWithoutDisconnectNotification() {
        server().use { server ->
            val count = AtomicInteger()
            val channel = TcpChannel(onData = {}, onDisconnected = { count.incrementAndGet() })
            try {
                assertTrue(channel.connect("127.0.0.1", server.localPort))
                server.accept().use { peer ->
                    peer.soTimeout = 3000
                    channel.startReading()
                    channel.close()
                    assertEquals(-1, peer.getInputStream().read())
                    assertEquals(0, count.get())
                }
            } finally { channel.close() }
        }
    }

    @Test fun readDirectReturnsNullOnEarlyEof() {
        server().use { server ->
            val channel = TcpChannel(onData = {})
            try {
                assertTrue(channel.connect("127.0.0.1", server.localPort))
                server.accept().use { peer -> peer.getOutputStream().write(byteArrayOf(1)) }
                assertNull(channel.readDirect(2))
            } finally { channel.close() }
        }
    }
}
