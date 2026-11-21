package com.aoooa.adb

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aoooa.adb.adb.InitialCnxn
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
        val payload = banner.toByteArray(Charsets.US_ASCII)
        val packet = AdbNative.buildCnxnPacket(0x01000000, 4096, banner.toByteArray(Charsets.UTF_8))
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
        assertEquals(banner.last().code, packet.last().toInt())
    }

    @Test
    fun legacyPacketMatchesKotlinSerialization() {
        val banner = "host::features=shell_v2,cmd,stat_v2"
        val payload = banner.toByteArray(Charsets.US_ASCII)
        val expected = AdbPacket(AdbPacket.CNXN, 0x01000000, 4096, payload).toBytes()
        assertArrayEquals(expected, AdbNative.buildCnxnPacket(0x01000000, 4096, banner.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun initialPacketChecksumsBeforeNegotiation() {
        val banner = "host::features=shell_v2"
        for (version in listOf(0x01000001, 0x01000002)) {
            val packet = AdbNative.buildCnxnPacket(version, 1024 * 1024, banner.toByteArray(Charsets.UTF_8))
            val header = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(version, header.getInt(4))
            assertEquals(1024 * 1024, header.getInt(8))
            assertEquals(banner.toByteArray().sumOf { it.toInt() and 255 }, header.getInt(16))
            assertArrayEquals(banner.toByteArray(Charsets.US_ASCII), packet.copyOfRange(24, packet.size))
        }
    }

    @Test
    fun emptyBannerAddsNoBytes() {
        val packet = AdbNative.buildCnxnPacket(0x01000000, 4096, byteArrayOf())
        val header = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(24, packet.size)
        assertEquals(0, header.getInt(12))
        assertEquals(0, header.getInt(16))
    }

    @Test
    fun longBannerIsNotTruncated() {
        val banner = "host::features=" + "feature,".repeat(128)
        val packet = AdbNative.buildCnxnPacket(0x01000001, 1024 * 1024, banner.toByteArray(Charsets.UTF_8))
        assertEquals(24 + banner.length, packet.size)
        assertArrayEquals(banner.toByteArray(Charsets.US_ASCII), packet.copyOfRange(24, packet.size))
    }

    @Test
    fun successiveCallsHaveIndependentResults() {
        val first = AdbNative.buildCnxnPacket(0x01000000, 4096, "host::first".toByteArray())
        val saved = first.copyOf()
        val second = AdbNative.buildCnxnPacket(0x01000001, 1024 * 1024, "host::second".toByteArray())
        second.fill(0)
        assertArrayEquals(saved, first)
    }
    @Test fun productionProfilesMatchByteForByte() {
        for (profile in InitialCnxn.entries) {
            val payload = profile.payload()
            assertTrue(payload.none { it == 0.toByte() })
            assertArrayEquals(profile.kotlinPacket(payload), AdbNative.buildCnxnPacket(profile.version, profile.maxData, payload))
        }
    }

    @Test fun nativePreservesUtf8AndEmbeddedZeroWithoutAppendingBytes() {
        val bytes = "host::中\u0000end".toByteArray(Charsets.UTF_8)
        val packet = AdbNative.buildCnxnPacket(0x01000001, 1048576, bytes)
        assertArrayEquals(bytes, packet.copyOfRange(24, packet.size))
    }
}
