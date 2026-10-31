package com.aoooa.adb

import com.aoooa.adb.adb.AdbPacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AdbPacket 边界条件测试：长度上限、校验和回绕、截断与多余字节。
 */
class AdbPacketEdgeTest {

    private fun buildHeader(command: Int, arg0: Int, arg1: Int, len: Int): ByteArray {
        val bb = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(command)
        bb.putInt(arg0)
        bb.putInt(arg1)
        bb.putInt(len)
        bb.putInt(0)
        bb.putInt(command xor -1)
        return bb.array()
    }

    @Test
    fun testEmptyPayloadSerialization() {
        val packet = AdbPacket(AdbPacket.CNXN, 0, 0)
        val raw = packet.toBytes()
        assertEquals(24, raw.size)
        assertEquals(0, packet.payloadLength)
        assertEquals(0, AdbPacket.checksum(packet.payload))

        val parsed = AdbPacket.tryParse(raw)
        assertNotNull(parsed)
        assertEquals(24, parsed!!.second)
        assertEquals(0, parsed.first.payloadLength)
    }

    @Test
    fun testLittleEndianCommandBytes() {
        val raw = AdbPacket(AdbPacket.CNXN, 0, 0).toBytes()
        // "CNXN" 以小端写入后应还原为 ASCII 字节序列
        assertArrayEquals(byteArrayOf(0x43, 0x4E, 0x58, 0x4E), raw.copyOfRange(0, 4))
        // magic 字段 = command ^ 0xffffffff
        val magic = ByteBuffer.wrap(raw, 20, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(AdbPacket.CNXN xor -1, magic)
    }

    @Test
    fun testChecksumWrapsToNegative() {
        // 8421505 个 0xFF 之和为 2147483775，超出 Int 正数上限，应回绕为负值
        val payload = ByteArray(8421505) { 0xFF.toByte() }
        assertEquals(-2147483521, AdbPacket.checksum(payload))
    }

    @Test
    fun testChecksumTreatsBytesAsUnsigned() {
        val payload = byteArrayOf(0xFF.toByte(), 0x80.toByte(), 0x01)
        assertEquals(255 + 128 + 1, AdbPacket.checksum(payload))
    }

    @Test
    fun testRejectPayloadLengthBeyondLimit() {
        // 长度超过 1MB 上限，应在解析阶段被拒绝
        val buffer = buildHeader(AdbPacket.WRTE, 1, 2, 1048577)
        assertNull(AdbPacket.tryParse(buffer))
    }

    @Test
    fun testRejectTruncatedPayload() {
        val full = AdbPacket(AdbPacket.WRTE, 1, 2, byteArrayOf(1, 2, 3, 4)).toBytes()
        // 去掉最后一个 payload 字节，声明长度与实际数据不符
        val truncated = full.copyOf(full.size - 1)
        assertNull(AdbPacket.tryParse(truncated))
    }

    @Test
    fun testConsumesOnlyOnePacketFromStream() {
        val a = AdbPacket(AdbPacket.OKAY, 1, 0, byteArrayOf(9))
        val b = AdbPacket(AdbPacket.CLSE, 1, 0, byteArrayOf(8))
        val stream = a.toBytes() + b.toBytes()

        val first = AdbPacket.tryParse(stream)
        assertNotNull(first)
        assertEquals(25, first!!.second)

        val rest = stream.copyOfRange(first.second, stream.size)
        val second = AdbPacket.tryParse(rest)
        assertNotNull(second)
        assertEquals(AdbPacket.CLSE, second!!.first.command)
        assertEquals(8, second.first.payload[0].toInt())
    }

    @Test
    fun testRoundTripWithMaxPayload() {
        val payload = ByteArray(AdbPacket.MAX_PAYLOAD) { (it % 251).toByte() }
        val raw = AdbPacket(AdbPacket.STLS, 0, AdbPacket.STLS_VERSION, payload).toBytes()
        assertEquals(24 + AdbPacket.MAX_PAYLOAD, raw.size)

        val parsed = AdbPacket.tryParse(raw)
        assertNotNull(parsed)
        assertArrayEquals(payload, parsed!!.first.payload)
    }

    @Test
    fun testMagicMismatchRejected() {
        val raw = AdbPacket(AdbPacket.OPEN, 0, 0, byteArrayOf(1, 2)).toBytes()
        raw[23] = (raw[23].toInt() xor 0x7F).toByte()
        assertNull(AdbPacket.tryParse(raw))
    }

    @Test
    fun testHeaderOnlyBelowMinimumSize() {
        assertNull(AdbPacket.tryParse(ByteArray(23)))
        assertNull(AdbPacket.tryParse(ByteArray(0)))
    }
}
