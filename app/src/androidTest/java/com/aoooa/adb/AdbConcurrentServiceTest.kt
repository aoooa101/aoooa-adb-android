package com.aoooa.adb

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aoooa.adb.adb.*
import com.aoooa.adb.bridge.Channel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AdbConcurrentServiceTest {
    private class Peer : Channel {
        lateinit var connection: AdbConnection
        val sent = LinkedBlockingQueue<AdbPacket>()
        override fun send(data: ByteArray): Boolean {
            val packet = AdbPacket.tryParse(data)!!.first
            if (packet.command == AdbPacket.CNXN) {
                connection.onData(AdbPacket(AdbPacket.CNXN, 0x01000000, 4096).toBytes())
            } else sent.offer(packet)
            return true
        }
        override fun close() = Unit
        fun receive(command: Int): AdbPacket {
            val result = sent.poll(3, TimeUnit.SECONDS) ?: throw AssertionError("missing packet")
            assertEquals(command, result.command)
            return result
        }
        fun ready(id: Int) = deliver(AdbPacket.OKAY, id)
        fun deliver(command: Int, id: Int, text: String = "") {
            connection.onData(AdbPacket(command, id + 100, id, text.toByteArray()).toBytes())
        }
        fun service(name: String, timeoutMs: Long = 5000): FutureTask<String> = FutureTask {
            connection.openService("shell:$name", timeoutMs)
        }.also { Thread(it).apply { isDaemon = true; start() } }
    }

    @Test fun interleavedShellOutputAndAcknowledgementsAreIsolated() {
        val peer = Peer()
        peer.connection = AdbConnection(peer)
        assertTrue(peer.connection.connect())
        val a = peer.service("first")
        val first = peer.receive(AdbPacket.OPEN).arg0
        val b = peer.service("second")
        val second = peer.receive(AdbPacket.OPEN).arg0
        try {
            peer.ready(first); peer.ready(second)
            peer.deliver(AdbPacket.WRTE, second, "乙")
            val ackB = peer.receive(AdbPacket.OKAY)
            assertEquals(second, ackB.arg0)
            assertEquals(second + 100, ackB.arg1)
            peer.deliver(AdbPacket.WRTE, first, "甲")
            assertEquals(first, peer.receive(AdbPacket.OKAY).arg0)
            peer.deliver(AdbPacket.CLSE, first)
            peer.receive(AdbPacket.CLSE)
            assertEquals("甲", a.get(3, TimeUnit.SECONDS))
            peer.deliver(AdbPacket.WRTE, second, "二\n")
            assertEquals(second, peer.receive(AdbPacket.OKAY).arg0)
            peer.deliver(AdbPacket.CLSE, second)
            assertEquals("乙二", b.get(3, TimeUnit.SECONDS))
        } finally { a.cancel(true); b.cancel(true); peer.connection.disconnect() }
    }

    @Test fun lateReadyAfterOpenTimeoutIsClosed() {
        val peer = Peer()
        peer.connection = AdbConnection(peer)
        assertTrue(peer.connection.connect())
        val task = peer.service("late", 100)
        try {
            val id = peer.receive(AdbPacket.OPEN).arg0
            assertEquals("", task.get(3, TimeUnit.SECONDS))
            peer.ready(id)
            val close = peer.receive(AdbPacket.CLSE)
            assertEquals(id, close.arg0)
            assertEquals(id + 100, close.arg1)
        } finally { task.cancel(true); peer.connection.disconnect() }
    }

    @Test fun syncUploadAndShellResponsesRemainIndependent() {
        val peer = Peer()
        peer.connection = AdbConnection(peer)
        assertTrue(peer.connection.connect())
        val upload = FutureTask { peer.connection.pushBytes(byteArrayOf(1, 2, 3), "/data/local/tmp/test") }
        Thread(upload).apply { isDaemon = true; start() }
        val syncId = peer.receive(AdbPacket.OPEN).arg0
        peer.ready(syncId)
        val shell = peer.service("parallel")
        try {
            // OPEN 与首个 SEND 的顺序允许由线程调度决定。
            var shellId = 0
            var sendSeen = false
            repeat(2) {
                val packet = peer.sent.poll(3, TimeUnit.SECONDS) ?: throw AssertionError("missing initial packet")
                if (packet.command == AdbPacket.OPEN) shellId = packet.arg0
                else {
                    assertEquals(AdbPacket.WRTE, packet.command)
                    assertEquals(syncId, packet.arg0)
                    assertEquals("SEND", String(packet.payload, 0, 4, Charsets.US_ASCII))
                    sendSeen = true
                }
            }
            assertTrue(sendSeen)
            assertTrue(shellId > 0)
            peer.ready(shellId)
            peer.deliver(AdbPacket.WRTE, shellId, "parallel-output")
            assertEquals(shellId, peer.receive(AdbPacket.OKAY).arg0)
            peer.ready(syncId)
            val data = peer.receive(AdbPacket.WRTE)
            assertEquals("DATA", String(data.payload, 0, 4, Charsets.US_ASCII))
            assertArrayEquals(byteArrayOf(1, 2, 3), data.payload.copyOfRange(8, data.payload.size))
            peer.ready(syncId)
            assertEquals("DONE", String(peer.receive(AdbPacket.WRTE).payload, 0, 4, Charsets.US_ASCII))
            peer.deliver(AdbPacket.WRTE, syncId, "OKAY\u0000\u0000\u0000\u0000")
            peer.deliver(AdbPacket.CLSE, shellId)
            assertTrue(upload.get(3, TimeUnit.SECONDS))
            assertEquals("parallel-output", shell.get(3, TimeUnit.SECONDS))
        } finally { upload.cancel(true); shell.cancel(true); peer.connection.disconnect() }
    }

    @Test fun timedOutServiceDoesNotConsumeAnotherServiceOutput() {
        val peer = Peer()
        peer.connection = AdbConnection(peer)
        assertTrue(peer.connection.connect())
        val a = peer.service("timeout", 500)
        val first = peer.receive(AdbPacket.OPEN).arg0
        peer.ready(first)
        val b = peer.service("survivor")
        val second = peer.receive(AdbPacket.OPEN).arg0
        peer.ready(second)
        try {
            assertEquals("", a.get(3, TimeUnit.SECONDS))
            assertEquals(first, peer.receive(AdbPacket.CLSE).arg0)
            peer.deliver(AdbPacket.WRTE, second, "still-alive")
            assertEquals(second, peer.receive(AdbPacket.OKAY).arg0)
            peer.deliver(AdbPacket.CLSE, second)
            assertEquals("still-alive", b.get(3, TimeUnit.SECONDS))
        } finally { a.cancel(true); b.cancel(true); peer.connection.disconnect() }
    }
}
