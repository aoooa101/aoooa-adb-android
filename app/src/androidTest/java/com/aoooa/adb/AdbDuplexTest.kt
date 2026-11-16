package com.aoooa.adb

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aoooa.adb.adb.AdbConnection
import com.aoooa.adb.adb.AdbPacket
import com.aoooa.adb.bridge.Channel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 用阻塞发送的模拟通道检查收包与断开不依赖发送锁。 */
@RunWith(AndroidJUnit4::class)
class AdbDuplexTest {
    private class Peer : Channel {
        lateinit var connection: AdbConnection
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val packets = LinkedBlockingQueue<AdbPacket>()
        @Volatile var closed = false
        override fun send(data: ByteArray): Boolean {
            val packet = AdbPacket.tryParse(data)!!.first
            if (packet.command == AdbPacket.CNXN) {
                connection.onData(AdbPacket(AdbPacket.CNXN, 0x01000000, 4096).toBytes())
                return true
            }
            if (packet.command == AdbPacket.WRTE) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "test send timeout" }
            }
            if (closed) return false
            packets.offer(packet)
            return true
        }
        override fun close() {
            closed = true
            release.countDown()
        }
    }

    private fun fixture(test: (Peer, Int, LinkedBlockingQueue<ByteArray>) -> Unit) {
        val peer = Peer()
        peer.connection = AdbConnection(peer)
        assertTrue(peer.connection.connect())
        val received = LinkedBlockingQueue<ByteArray>()
        val id = peer.connection.openRawStream("localabstract:duplex-test", onBytes = { received.offer(it) })
        assertEquals(AdbPacket.OPEN, peer.packets.poll(3, TimeUnit.SECONDS)?.command)
        peer.connection.onData(AdbPacket(AdbPacket.OKAY, 99, id).toBytes())
        try { test(peer, id, received) } finally {
            peer.release.countDown()
            peer.connection.disconnect()
        }
    }

    @Test fun receiveAndAcknowledgementQueueProceedWhileWriteIsBlocked() = fixture { peer, id, received ->
        val writing = FutureTask<Boolean> { peer.connection.writeRawStreamAwait(id, byteArrayOf(1)) }
        Thread(writing).apply { isDaemon = true; start() }
        try {
            assertTrue(peer.entered.await(3, TimeUnit.SECONDS))
            val bytes = byteArrayOf(4, 5, 6)
            val receiving = FutureTask {
                peer.connection.onData(AdbPacket(AdbPacket.WRTE, 99, id, bytes).toBytes())
            }
            Thread(receiving).apply { isDaemon = true; start() }
            try {
                receiving.get(2, TimeUnit.SECONDS)
                assertArrayEquals(bytes, received.poll(2, TimeUnit.SECONDS))
                assertFalse(writing.isDone)
                peer.release.countDown()
                assertTrue(writing.get(3, TimeUnit.SECONDS))
                assertEquals(AdbPacket.WRTE, peer.packets.poll(3, TimeUnit.SECONDS)?.command)
                assertEquals(AdbPacket.OKAY, peer.packets.poll(3, TimeUnit.SECONDS)?.command)
            } finally { receiving.cancel(true) }
        } finally { writing.cancel(true) }
    }

    @Test fun concurrentSingleStreamAndBulkCloseNotifyOnce() = fixture { peer, initialId, _ ->
        peer.connection.closeRawStream(initialId)
        peer.packets.clear()
        repeat(100) {
            val closed = java.util.concurrent.atomic.AtomicInteger()
            val id = peer.connection.openRawStream("localabstract:close-race",
                onBytes = {}, onClosed = { closed.incrementAndGet(); Unit })
            assertEquals(AdbPacket.OPEN, peer.packets.poll(3, TimeUnit.SECONDS)?.command)
            val start = CountDownLatch(1)
            val single = FutureTask { start.await(); peer.connection.closeRawStream(id) }
            val bulk = FutureTask { start.await(); peer.connection.closeAllRawStreams() }
            Thread(single).apply { isDaemon = true; start() }
            Thread(bulk).apply { isDaemon = true; start() }
            try {
                start.countDown()
                single.get(3, TimeUnit.SECONDS)
                bulk.get(3, TimeUnit.SECONDS)
                assertEquals(1, closed.get())
                peer.connection.closeAllRawStreams()
                assertEquals(1, closed.get())
            } finally {
                start.countDown()
                single.cancel(true)
                bulk.cancel(true)
            }
        }
    }

    @Test fun disconnectClosesTransportBeforeWaitingForBlockedWrite() = fixture { peer, id, _ ->
        val writing = FutureTask<Boolean> { peer.connection.writeRawStreamAwait(id, byteArrayOf(1)) }
        Thread(writing).apply { isDaemon = true; start() }
        assertTrue(peer.entered.await(3, TimeUnit.SECONDS))
        val closing = FutureTask { peer.connection.disconnect() }
        Thread(closing).apply { isDaemon = true; start() }
        try {
            closing.get(2, TimeUnit.SECONDS)
            assertTrue(peer.closed)
            assertFalse(writing.get(3, TimeUnit.SECONDS))
            assertFalse(peer.connection.isAuthenticated)
            assertFalse(peer.connection.connect())
        } finally {
            peer.release.countDown()
            closing.cancel(true)
            writing.cancel(true)
        }
    }
}
