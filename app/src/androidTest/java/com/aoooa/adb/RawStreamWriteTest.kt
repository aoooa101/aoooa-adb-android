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

@RunWith(AndroidJUnit4::class)
class RawStreamWriteTest {
    private class Peer : Channel {
        lateinit var connection: AdbConnection
        val packets = LinkedBlockingQueue<AdbPacket>()
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        var failWrites = false
        override fun send(data: ByteArray): Boolean {
            val packet = AdbPacket.tryParse(data)!!.first
            if (packet.command == AdbPacket.CNXN) {
                connection.onData(AdbPacket(AdbPacket.CNXN, 0x01000001, 4096,
                    "device::features=shell_v2\u0000".toByteArray()).toBytes())
                return true
            }
            if (failWrites && packet.command == AdbPacket.WRTE) return false
            packets.offer(packet)
            return true
        }
        override fun close() = Unit
        fun ready(localId: Int) = connection.onData(AdbPacket(AdbPacket.OKAY, 99, localId).toBytes())
        fun receive(command: Int): AdbPacket {
            val packet = packets.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("missing packet")
            assertEquals(command, packet.command)
            return packet
        }
    }

    private fun fixture(test: (Peer, Int) -> Unit) {
        val peer = Peer()
        val connection = AdbConnection(peer, onDebugLog = { peer.events += it })
        peer.connection = connection
        assertTrue(connection.connect())
        val localId = connection.openRawStream("localabstract:test", onBytes = {})
        peer.receive(AdbPacket.OPEN)
        peer.ready(localId)
        try { test(peer, localId) } finally { connection.disconnect() }
    }

    @Test fun nextWriteWaitsForOkay() = fixture { peer, localId ->
        assertTrue(peer.connection.writeRawStreamAwait(localId, byteArrayOf(1)))
        peer.receive(AdbPacket.WRTE)
        val started = CountDownLatch(1)
        val result = FutureTask<Boolean> {
            started.countDown()
            peer.connection.writeRawStreamAwait(localId, byteArrayOf(2))
        }
        Thread(result).apply { isDaemon = true; start() }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertNull(peer.packets.poll(200, TimeUnit.MILLISECONDS))
            assertFalse(result.isDone)
            peer.ready(localId)
            assertTrue(result.get(5, TimeUnit.SECONDS))
            assertArrayEquals(byteArrayOf(2), peer.receive(AdbPacket.WRTE).payload)
        } finally { result.cancel(true) }
    }

    @Test fun nonBlockingWriteCannotBypassCredit() = fixture { peer, localId ->
        assertTrue(peer.connection.writeRawStream(localId, byteArrayOf(1)))
        peer.receive(AdbPacket.WRTE)
        assertFalse(peer.connection.writeRawStream(localId, byteArrayOf(2)))
        assertNull(peer.packets.poll(100, TimeUnit.MILLISECONDS))
        peer.ready(localId)
        assertTrue(peer.connection.writeRawStream(localId, byteArrayOf(3)))
        peer.receive(AdbPacket.WRTE)
    }

    @Test fun interruptedWaitPreservesInterruptFlag() = fixture { peer, localId ->
        assertTrue(peer.connection.writeRawStreamAwait(localId, byteArrayOf(1)))
        peer.receive(AdbPacket.WRTE)
        val result = FutureTask<Boolean> {
            Thread.currentThread().interrupt()
            val sent = peer.connection.writeRawStreamAwait(localId, byteArrayOf(2))
            !sent && Thread.currentThread().isInterrupted
        }
        Thread(result).apply { isDaemon = true; start() }
        assertTrue(result.get(5, TimeUnit.SECONDS))
        assertTrue(peer.events.any { it.contains("write_interrupted") })
    }

    @Test fun channelFalseIsReportedAndStreamClosed() = fixture { peer, localId ->
        peer.failWrites = true
        assertFalse(peer.connection.writeRawStreamAwait(localId, byteArrayOf(1)))
        peer.receive(AdbPacket.CLSE)
        assertTrue(peer.events.any { it.contains("channel_failed") })
        assertFalse(peer.connection.writeRawStreamAwait(localId, byteArrayOf(2)))
    }

    @Test fun missingOkayTimesOutAndClosesStream() = fixture { peer, localId ->
        assertTrue(peer.connection.writeRawStreamAwait(localId, byteArrayOf(1)))
        peer.receive(AdbPacket.WRTE)
        assertFalse(peer.connection.writeRawStreamAwait(localId, byteArrayOf(2), timeoutMs = 20L))
        peer.receive(AdbPacket.CLSE)
        assertTrue(peer.events.any { it.contains("write_timeout") })
    }

    @Test fun closingStreamUnblocksWaitingWriter() = fixture { peer, localId ->
        assertTrue(peer.connection.writeRawStreamAwait(localId, byteArrayOf(1)))
        peer.receive(AdbPacket.WRTE)
        val result = FutureTask<Boolean> { peer.connection.writeRawStreamAwait(localId, byteArrayOf(2)) }
        Thread(result).apply { isDaemon = true; start() }
        try {
            peer.connection.closeRawStream(localId)
            assertFalse(result.get(5, TimeUnit.SECONDS))
            peer.receive(AdbPacket.CLSE)
            assertNull(peer.packets.poll(100, TimeUnit.MILLISECONDS))
        } finally { result.cancel(true) }
    }
}
