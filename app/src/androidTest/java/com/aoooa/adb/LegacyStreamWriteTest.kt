package com.aoooa.adb

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aoooa.adb.adb.AdbConnection
import com.aoooa.adb.adb.AdbPacket
import com.aoooa.adb.bridge.Channel
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyStreamWriteTest {
    private class Peer : Channel {
        lateinit var connection: AdbConnection
        val packets = LinkedBlockingQueue<AdbPacket>()
        override fun send(data: ByteArray): Boolean {
            val packet = AdbPacket.tryParse(data)!!.first
            if (packet.command == AdbPacket.CNXN) {
                connection.onData(AdbPacket(AdbPacket.CNXN, 0x01000000, 4096).toBytes())
            } else packets.offer(packet)
            return true
        }
        override fun close() = Unit
        fun ready(id: Int) = connection.onData(AdbPacket(AdbPacket.OKAY, 99, id).toBytes())
        fun receive(command: Int): AdbPacket {
            val packet = packets.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("missing packet")
            assertEquals(command, packet.command)
            return packet
        }
    }

    private fun fixture(test: (Peer, Int) -> Unit) {
        val peer = Peer()
        peer.connection = AdbConnection(peer)
        assertTrue(peer.connection.connect())
        val id = 100
        peer.connection.sendPacket(AdbPacket(AdbPacket.OPEN, id, 0, "sync:\u0000".toByteArray()))
        peer.receive(AdbPacket.OPEN)
        peer.ready(id)
        try { test(peer, id) } finally { peer.connection.disconnect() }
    }

    @Test fun legacyWriteWaitsForNextOkay() = fixture { peer, id ->
        peer.connection.sendPacket(AdbPacket(AdbPacket.WRTE, id, 99, byteArrayOf(1)))
        peer.receive(AdbPacket.WRTE)
        val task = FutureTask {
            peer.connection.sendPacket(AdbPacket(AdbPacket.WRTE, id, 99, byteArrayOf(2)))
        }
        Thread(task).apply { isDaemon = true; start() }
        try {
            assertNull(peer.packets.poll(200, TimeUnit.MILLISECONDS))
            assertFalse(task.isDone)
            peer.ready(id)
            task.get(5, TimeUnit.SECONDS)
            assertArrayEquals(byteArrayOf(2), peer.receive(AdbPacket.WRTE).payload)
        } finally { task.cancel(true) }
    }

    @Test fun oversizedPayloadIsSplitAndAcknowledged() = fixture { peer, id ->
        val bytes = ByteArray(5000) { it.toByte() }
        val task = FutureTask {
            peer.connection.sendPacket(AdbPacket(AdbPacket.WRTE, id, 99, bytes))
        }
        Thread(task).apply { isDaemon = true; start() }
        try {
            val first = peer.receive(AdbPacket.WRTE)
            assertEquals(4096, first.payload.size)
            assertNull(peer.packets.poll(200, TimeUnit.MILLISECONDS))
            peer.ready(id)
            val second = peer.receive(AdbPacket.WRTE)
            task.get(5, TimeUnit.SECONDS)
            assertArrayEquals(bytes, first.payload + second.payload)
        } finally { task.cancel(true) }
    }

    @Test fun disconnectCancelsPendingLegacyWrite() = fixture { peer, id ->
        peer.connection.sendPacket(AdbPacket(AdbPacket.WRTE, id, 99, byteArrayOf(1)))
        peer.receive(AdbPacket.WRTE)
        val task = FutureTask<Boolean> {
            try {
                peer.connection.sendPacket(AdbPacket(AdbPacket.WRTE, id, 99, byteArrayOf(2)))
                false
            } catch (_: Exception) { true }
        }
        Thread(task).apply { isDaemon = true; start() }
        peer.connection.disconnect()
        assertTrue(task.get(5, TimeUnit.SECONDS))
    }
}
