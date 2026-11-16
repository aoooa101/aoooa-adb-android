package com.aoooa.adb

import com.aoooa.adb.control.H264SpsParser
import org.junit.Assert.*
import org.junit.Test

class H264SpsParserTest {
    private fun sps(width: Int, height: Int, shortStart: Boolean = false, high: Boolean = false): ByteArray {
        val bits = StringBuilder()
        fun fixed(value: Int, count: Int) { bits.append(value.toString(2).padStart(count, '0')) }
        fun ue(value: Int) {
            val binary = (value + 1).toString(2)
            bits.append("0".repeat(binary.length - 1)).append(binary)
        }
        fixed(if (high) 100 else 66, 8)
        fixed(0, 8)
        fixed(40, 8)
        ue(0)
        if (high) {
            ue(1)
            ue(0)
            ue(0)
            fixed(0, 1)
            fixed(0, 1)
        }
        ue(0)
        ue(0)
        ue(0)
        ue(1)
        fixed(0, 1)
        ue((width + 15) / 16 - 1)
        ue((height + 15) / 16 - 1)
        fixed(1, 1)
        fixed(1, 1)
        val right = ((width + 15) / 16 * 16 - width) / 2
        val bottom = ((height + 15) / 16 * 16 - height) / 2
        fixed(if (right > 0 || bottom > 0) 1 else 0, 1)
        if (right > 0 || bottom > 0) {
            ue(0)
            ue(right)
            ue(0)
            ue(bottom)
        }
        fixed(0, 1)
        fixed(1, 1)
        while (bits.length % 8 != 0) bits.append('0')
        val raw = bits.toString().chunked(8).map { it.toInt(2).toByte() }
        val escaped = ArrayList<Byte>()
        var zeros = 0
        for (byte in raw) {
            val value = byte.toInt() and 255
            if (zeros >= 2 && value <= 3) {
                escaped.add(3)
                zeros = 0
            }
            escaped.add(byte)
            zeros = if (value == 0) zeros + 1 else 0
        }
        val start = if (shortStart) byteArrayOf(0, 0, 1, 0x67) else byteArrayOf(0, 0, 0, 1, 0x67)
        return start + escaped.toByteArray()
    }

    @Test fun portraitAndLandscapeUseActualSpsSize() {
        assertEquals(1080 to 1920, H264SpsParser.size(sps(1080, 1920)))
        assertEquals(1920 to 1080, H264SpsParser.size(sps(1920, 1080)))
    }

    @Test fun supportsHighProfileAndThreeByteStartCode() {
        assertEquals(720 to 1280, H264SpsParser.size(sps(720, 1280, shortStart = true, high = true)))
    }

    @Test fun truncatedAndInvalidPacketsAreRejected() {
        assertNull(H264SpsParser.size(byteArrayOf()))
        assertNull(H264SpsParser.size(byteArrayOf(0, 0, 0, 1, 0x67)))
        assertNull(H264SpsParser.size(byteArrayOf(0, 0, 1, 0x68, 0)))
        assertNull(H264SpsParser.size(sps(8192, 1080)))
        val valid = sps(1080, 1920)
        assertNull(H264SpsParser.size(valid.copyOf(7)))
    }
}
