package com.aoooa.adb

import com.aoooa.adb.adb.protocol.Utf8StreamCodec
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * UTF-8 流式解码测试：多字节字符被报文边界截断后的前缀计算与拼接。
 */
class Utf8StreamCodecTest {

    private val zhong = "中".toByteArray(Charsets.UTF_8)   // E4 B8 AD
    private val wen = "文".toByteArray(Charsets.UTF_8)     // E6 96 87
    private val emoji = "😀".toByteArray(Charsets.UTF_8)   // F0 9F 98 80

    @Test
    fun testAsciiPrefixIsWholeArray() {
        val bytes = "abc".toByteArray(Charsets.UTF_8)
        assertEquals(3, Utf8StreamCodec.completePrefixLength(bytes))
    }

    @Test
    fun testCompleteThreeByteSequence() {
        assertEquals(3, Utf8StreamCodec.completePrefixLength(zhong))
        assertEquals(4, Utf8StreamCodec.completePrefixLength("a".toByteArray() + zhong))
        assertEquals(6, Utf8StreamCodec.completePrefixLength(zhong + wen))
    }

    @Test
    fun testTruncatedThreeByteSequence() {
        assertEquals(0, Utf8StreamCodec.completePrefixLength(zhong.copyOfRange(0, 2)))
        assertEquals(0, Utf8StreamCodec.completePrefixLength(zhong.copyOfRange(0, 1)))
        assertEquals(1, Utf8StreamCodec.completePrefixLength("a".toByteArray() + zhong.copyOfRange(0, 2)))
    }

    @Test
    fun testEmojiFourByteSequence() {
        assertEquals(4, Utf8StreamCodec.completePrefixLength(emoji))
        assertEquals(0, Utf8StreamCodec.completePrefixLength(emoji.copyOfRange(0, 3)))
        assertEquals(0, Utf8StreamCodec.completePrefixLength(emoji.copyOfRange(0, 2)))
    }

    @Test
    fun testInvalidLeadByteIsPassedThrough() {
        val invalid = byteArrayOf(0xFF.toByte())
        assertEquals(1, Utf8StreamCodec.completePrefixLength(invalid))
    }

    @Test
    fun testDecodeWithCarryKeepsTrailingBytes() {
        val carry = ByteArrayOutputStream()
        val text = Utf8StreamCodec.decodeWithCarry(zhong.copyOfRange(0, 2), carry)

        assertEquals("", text)
        assertEquals(2, carry.size())
    }

    @Test
    fun testDecodeWithCarryReassemblesSplitCharacter() {
        val carry = ByteArrayOutputStream()
        assertEquals("", Utf8StreamCodec.decodeWithCarry(zhong.copyOfRange(0, 2), carry))

        val merged = carry.toByteArray() + zhong.copyOfRange(2, 3)
        val text = Utf8StreamCodec.decodeWithCarry(merged, carry)
        assertEquals("中", text)
        assertEquals(0, carry.size())
    }

    @Test
    fun testDecodeWithCarryFullTextAndNoRemainder() {
        val carry = ByteArrayOutputStream()
        val text = Utf8StreamCodec.decodeWithCarry("你好".toByteArray(Charsets.UTF_8), carry)

        assertEquals("你好", text)
        assertEquals(0, carry.size())
    }

    @Test
    fun testDecodeWithCarryEmptyInput() {
        val carry = ByteArrayOutputStream()
        assertEquals("", Utf8StreamCodec.decodeWithCarry(ByteArray(0), carry))
        assertEquals(0, carry.size())
    }
}
