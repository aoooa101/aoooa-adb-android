package com.aoooa.adb

import com.aoooa.adb.adb.protocol.SyncProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * sync: 协议报文构造测试：魔数、长度字段与数据块布局。
 */
class SyncProtocolTest {

    private fun magicOf(bytes: ByteArray): String =
        String(bytes, 0, 4, Charsets.US_ASCII)

    private fun lengthOf(bytes: ByteArray): Int =
        ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int

    @Test
    fun testServicePayloadEndsWithNul() {
        val payload = SyncProtocol.servicePayload
        assertEquals(6, payload.size)
        assertEquals("sync:", String(payload, 0, 5, Charsets.US_ASCII))
        assertEquals(0, payload[5].toInt())
    }

    @Test
    fun testSendRequestLayout() {
        val path = "/data/local/tmp/a.apk,33206"
        val req = SyncProtocol.sendRequest(path)

        assertEquals("SEND", magicOf(req))
        assertEquals(path.toByteArray(Charsets.UTF_8).size, lengthOf(req))
        assertEquals(8 + path.length, req.size)
        assertEquals(path, String(req, 8, req.size - 8, Charsets.UTF_8))
    }

    @Test
    fun testRecvRequestLayout() {
        val path = "/sdcard/test.txt"
        val req = SyncProtocol.recvRequest(path)

        assertEquals("RECV", magicOf(req))
        assertEquals(path.length, lengthOf(req))
        assertEquals(path, String(req, 8, req.size - 8, Charsets.UTF_8))
    }

    @Test
    fun testDataChunkHonorsOffsetAndLength() {
        val source = byteArrayOf(10, 20, 30, 40, 50, 60)
        val chunk = SyncProtocol.dataChunk(source, 2, 3)

        assertEquals(8 + 3, chunk.size)
        assertEquals("DATA", magicOf(chunk))
        assertEquals(3, lengthOf(chunk))
        assertArrayEquals(byteArrayOf(30, 40, 50), chunk.copyOfRange(8, 11))
    }

    @Test
    fun testDataChunkZeroLength() {
        val chunk = SyncProtocol.dataChunk(ByteArray(0), 0, 0)
        assertEquals(8, chunk.size)
        assertEquals(0, lengthOf(chunk))
    }

    @Test
    fun testDoneCarriesTimestampInLengthField() {
        val done = SyncProtocol.done(1700000000L)
        assertEquals(8, done.size)
        assertEquals("DONE", magicOf(done))
        assertEquals(1700000000, lengthOf(done))
    }

    @Test
    fun testProtocolMagicConstants() {
        assertEquals("DATA", SyncProtocol.MAGIC_DATA)
        assertEquals("FAIL", SyncProtocol.MAGIC_FAIL)
        assertEquals("OKAY", SyncProtocol.MAGIC_OKAY)
        assertEquals("SEND", SyncProtocol.MAGIC_SEND)
        assertEquals("RECV", SyncProtocol.MAGIC_RECV)
        assertEquals("DONE", SyncProtocol.MAGIC_DONE)
    }
}
