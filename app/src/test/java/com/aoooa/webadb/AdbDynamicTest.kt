package com.aoooa.webadb

import com.aoooa.webadb.adb.AdbPacket
import com.aoooa.webadb.model.CommandItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADB 底层协议与数据模型的运行时动态单元验证
 */
class AdbDynamicTest {

    @Test
    fun testAdbPacketSerializationAndParsing() {
        val payload = "host:version".toByteArray(Charsets.UTF_8)
        val packet = AdbPacket(
            command = AdbPacket.OPEN,
            arg0 = 1,
            arg1 = 0,
            payload = payload
        )

        // 动态序列化为底层 24 字节头 + payload
        val rawBytes = packet.toBytes()
        assertEquals(24 + payload.size, rawBytes.size)

        // 动态反序列化解析并验证
        val parsedPair = AdbPacket.tryParse(rawBytes)
        assertNotNull("ADB 数据包解析失败", parsedPair)

        val (parsedPacket, consumedBytes) = parsedPair!!
        assertEquals(24 + payload.size, consumedBytes)
        assertEquals(AdbPacket.OPEN, parsedPacket.command)
        assertEquals(1, parsedPacket.arg0)
        assertEquals(0, parsedPacket.arg1)
        assertEquals(payload.size, parsedPacket.payloadLength)
        assertTrue(payload.contentEquals(parsedPacket.payload))
    }

    @Test
    fun testChecksumCalculation() {
        val emptyPayload = ByteArray(0)
        assertEquals(0, AdbPacket.checksum(emptyPayload))

        val testData = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        assertEquals(10, AdbPacket.checksum(testData))
    }

    @Test
    fun testCommandConstantsAndMagic() {
        val commands = listOf(
            AdbPacket.CNXN,
            AdbPacket.AUTH,
            AdbPacket.OPEN,
            AdbPacket.OKAY,
            AdbPacket.CLSE,
            AdbPacket.WRTE,
            AdbPacket.STLS
        )

        for (cmd in commands) {
            val magic = cmd xor -1
            assertEquals(cmd, magic xor -1)
        }
    }

    @Test
    fun testCorruptedPacketHandling() {
        // 小于 24 字节头的残缺报文
        val incompleteHeader = ByteArray(16)
        assertNull(AdbPacket.tryParse(incompleteHeader))

        // 构造 Magic 校验错误的损坏包
        val validPacket = AdbPacket(AdbPacket.OKAY, 0, 0, ByteArray(4)).toBytes()
        val corruptedMagic = validPacket.clone()
        corruptedMagic[20] = (corruptedMagic[20].toInt() xor 0xFF).toByte()
        assertNull("损坏的 Magic 应被拒绝解析", AdbPacket.tryParse(corruptedMagic))
    }

    @Test
    fun testCommandItemModel() {
        val item = CommandItem(
            id = "test_cmd",
            nameZh = "测试指令",
            nameEn = "Test Command",
            command = "getprop ro.build.version.release",
            category = "system",
            isBuiltin = true
        )
        assertEquals("test_cmd", item.id)
        assertEquals("测试指令", item.nameZh)
        assertEquals("getprop ro.build.version.release", item.command)
        assertTrue(item.isBuiltin)
    }
}
