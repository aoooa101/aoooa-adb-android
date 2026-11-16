package com.aoooa.adb

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aoooa.adb.adb.AdbPacket
import com.aoooa.adb.native.AdbNative
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 在 Android 运行时验证原生库加载及 JNI 生成的 CNXN 报文。 */
@RunWith(AndroidJUnit4::class)
class AdbNativeTest {
    @Before
    fun requireNativeLibrary() {
        assertTrue("测试 APK 必须包含与运行设备 ABI 匹配的原生库", AdbNative.isLoaded)
    }

    @Test
    fun libraryIsLoaded() {
        assertTrue(AdbNative.isLoaded)
    }

    @Test
    fun headerUsesAospLayoutAndLittleEndian() {
        val banner = "host::features=shell_v2,cmd"
        val payload = (banner + "\u0000").toByteArray(Charsets.US_ASCII)
        val packet = AdbNative.buildCnxnPacket(0x01000000, 4096, banner)
        val header = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(24 + payload.size, packet.size)
        assertArrayEquals("CNXN".toByteArray(Charsets.US_ASCII), packet.copyOfRange(0, 4))
        assertEquals(0x4e584e43, header.int)
        assertEquals(0x01000000, header.int)
        assertEquals(4096, header.int)
        assertEquals(payload.size, header.int)
        assertEquals(payload.sumOf { it.toInt() and 0xff }, header.int)
        assertEquals(0x4e584e43 xor -1, header.int)
        assertArrayEquals(payload, packet.copyOfRange(24, packet.size))
        assertEquals(0, packet.last().toInt())
    }

    @Test
    fun legacyPacketMatchesKotlinSerialization() {
        val banner = "host::features=shell_v2,cmd,stat_v2"
        val payload = (banner + "\u0000").toByteArray(Charsets.US_ASCII)
        val expected = AdbPacket(AdbPacket.CNXN, 0x01000000, 4096, payload).toBytes()
        assertArrayEquals(expected, AdbNative.buildCnxnPacket(0x01000000, 4096, banner))
    }

    @Test
    fun modernProtocolSkipsChecksum() {
        val banner = "host::features=shell_v2"
        for (version in listOf(0x01000001, 0x01000002)) {
            val packet = AdbNative.buildCnxnPacket(version, 1024 * 1024, banner)
            val header = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(version, header.getInt(4))
            assertEquals(1024 * 1024, header.getInt(8))
            assertEquals(0, header.getInt(16))
            assertArrayEquals((banner + "\u0000").toByteArray(Charsets.US_ASCII), packet.copyOfRange(24, packet.size))
        }
    }

    @Test
    fun emptyBannerContainsTerminatingNul() {
        val packet = AdbNative.buildCnxnPacket(0x01000000, 4096, "")
        val header = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(25, packet.size)
        assertEquals(1, header.getInt(12))
        assertEquals(0, header.getInt(16))
        assertEquals(0, packet[24].toInt())
    }

    @Test
    fun longBannerIsNotTruncated() {
        val banner = "host::features=" + "feature,".repeat(128)
        val packet = AdbNative.buildCnxnPacket(0x01000001, 1024 * 1024, banner)
        assertEquals(24 + banner.length + 1, packet.size)
        assertArrayEquals((banner + "\u0000").toByteArray(Charsets.US_ASCII), packet.copyOfRange(24, packet.size))
    }

    @Test
    fun successiveCallsHaveIndependentResults() {
        val first = AdbNative.buildCnxnPacket(0x01000000, 4096, "host::first")
        val saved = first.copyOf()
        val second = AdbNative.buildCnxnPacket(0x01000001, 1024 * 1024, "host::second")
        second.fill(0)
        assertArrayEquals(saved, first)
    }
}
