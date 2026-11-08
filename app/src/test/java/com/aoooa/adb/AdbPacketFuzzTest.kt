package com.aoooa.adb

import com.aoooa.adb.adb.AdbPacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * ADB 报文编解码的随机化动态测试。
 *
 * 使用固定种子的伪随机数据，覆盖不同 payload 长度、参数组合与损坏输入的解析行为，
 * 验证序列化与反序列化始终互为逆运算，且异常输入只会被拒绝、不会抛异常。
 */
class AdbPacketFuzzTest {

    private val commands = intArrayOf(
        AdbPacket.CNXN,
        AdbPacket.AUTH,
        AdbPacket.OPEN,
        AdbPacket.OKAY,
        AdbPacket.CLSE,
        AdbPacket.WRTE,
        AdbPacket.STLS
    )

    @Test
    fun testRandomRoundTrip() {
        val random = Random(0x5EEDL)
        repeat(200) { iteration ->
            val payloadSize = random.nextInt(AdbPacket.MAX_PAYLOAD + 1)
            val payload = ByteArray(payloadSize).also { random.nextBytes(it) }
            val packet = AdbPacket(
                command = commands[random.nextInt(commands.size)],
                arg0 = random.nextInt(),
                arg1 = random.nextInt(),
                payload = payload
            )

            val parsed = AdbPacket.tryParse(packet.toBytes())
            assertNotNull("第 $iteration 轮解析失败", parsed)

            val (restored, consumed) = parsed!!
            assertEquals("第 $iteration 轮消费字节数错误", 24 + payloadSize, consumed)
            assertEquals("第 $iteration 轮命令字不一致", packet.command, restored.command)
            assertEquals("第 $iteration 轮 arg0 不一致", packet.arg0, restored.arg0)
            assertEquals("第 $iteration 轮 arg1 不一致", packet.arg1, restored.arg1)
            assertArrayEquals("第 $iteration 轮 payload 不一致", payload, restored.payload)
        }
    }

    @Test
    fun testTrailingBytesAreNotConsumed() {
        val packet = AdbPacket(AdbPacket.WRTE, 3, 7, byteArrayOf(1, 2, 3))
        val stream = packet.toBytes() + AdbPacket(AdbPacket.OKAY, 0, 0, ByteArray(0)).toBytes()

        val first = AdbPacket.tryParse(stream)!!
        assertEquals(27, first.second)
        assertEquals(AdbPacket.WRTE, first.first.command)

        val rest = stream.copyOfRange(first.second, stream.size)
        val second = AdbPacket.tryParse(rest)!!
        assertEquals(24, second.second)
        assertEquals(AdbPacket.OKAY, second.first.command)
    }

    @Test
    fun testTruncatedPayloadRejected() {
        val packet = AdbPacket(AdbPacket.WRTE, 1, 1, ByteArray(16) { it.toByte() })
        val bytes = packet.toBytes()

        for (cut in 1..16) {
            val truncated = bytes.copyOfRange(0, bytes.size - cut)
            assertNull("缺少 $cut 字节时不应解析成功", AdbPacket.tryParse(truncated))
        }
        // 恰好完整时应可解析
        assertNotNull(AdbPacket.tryParse(bytes))
    }

    @Test
    fun testChecksumIsInformationalOnly() {
        // 解析侧不校验 checksum 字段，篡改后仍应成功解析（与 AOSP adb 行为一致）
        val bytes = AdbPacket(AdbPacket.OKAY, 0, 0, byteArrayOf(1, 2)).toBytes()
        bytes[16] = (bytes[16].toInt() xor 0xFF).toByte()
        assertNotNull(AdbPacket.tryParse(bytes))
    }

    @Test
    fun testOversizedDeclaredLengthRejected() {
        val bytes = AdbPacket(AdbPacket.OKAY, 0, 0, ByteArray(4)).toBytes()
        // 篡改 payload 长度为 0x7FFFFFFF（超过 1MB 上限）
        bytes[12] = 0xFF.toByte()
        bytes[13] = 0xFF.toByte()
        bytes[14] = 0xFF.toByte()
        bytes[15] = 0x7F
        assertNull("超长 payload 声明应被拒绝", AdbPacket.tryParse(bytes))
    }

    @Test
    fun testNegativeDeclaredLengthRejected() {
        val bytes = AdbPacket(AdbPacket.OKAY, 0, 0, ByteArray(0)).toBytes()
        bytes[12] = 0xFF.toByte()
        bytes[13] = 0xFF.toByte()
        bytes[14] = 0xFF.toByte()
        bytes[15] = 0xFF.toByte()
        assertNull("负数 payload 长度应被拒绝", AdbPacket.tryParse(bytes))
    }

    @Test
    fun testRandomGarbageNeverThrows() {
        val random = Random(20261108L)
        repeat(300) { iteration ->
            val buffer = ByteArray(24 + random.nextInt(64))
            random.nextBytes(buffer)
            val result = try {
                AdbPacket.tryParse(buffer)
            } catch (e: Exception) {
                throw AssertionError("第 $iteration 轮随机输入触发异常: ${e.javaClass.simpleName}", e)
            }
            if (result != null) {
                val (packet, consumed) = result
                assertTrue("消费字节数不得超过缓冲区", consumed <= buffer.size)
                assertEquals(consumed, 24 + packet.payloadLength)
            }
        }
    }

    @Test
    fun testChecksumWrapsWithin32Bits() {
        // 全部 0xFF 的 4KB payload，累加和 4096 * 255 = 1044480，不应溢出 int
        val payload = ByteArray(AdbPacket.MAX_PAYLOAD) { 0xFF.toByte() }
        assertEquals(4096 * 255, AdbPacket.checksum(payload))

        // 高位字节参与时按无符号累加
        val signedBytes = byteArrayOf((-1).toByte(), (-1).toByte(), (-1).toByte())
        assertEquals(765, AdbPacket.checksum(signedBytes))
    }

    @Test
    fun testEmptyPayloadRoundTrip() {
        val packet = AdbPacket(AdbPacket.CLSE, 5, 6, ByteArray(0))
        val bytes = packet.toBytes()
        assertEquals(24, bytes.size)

        val parsed = AdbPacket.tryParse(bytes)!!
        assertEquals(0, parsed.first.payloadLength)
        assertEquals(AdbPacket.CLSE, parsed.first.command)
    }
}
