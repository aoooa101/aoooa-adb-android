package com.aoooa.adb

import com.aoooa.adb.adb.protocol.ShellProtocolV2
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ShellProtocol v2 帧格式测试：帧头布局与 payload 编码。
 */
class ShellProtocolV2Test {

    @Test
    fun testFrameHeaderLayout() {
        val payload = "hi".toByteArray(Charsets.UTF_8)
        val frame = ShellProtocolV2.buildFrame(ShellProtocolV2.ID_STDIN, payload)

        assertEquals(5 + payload.size, frame.size)
        assertEquals(0, frame[0].toInt())
        // payload 长度为小端序：2 -> 02 00 00 00
        assertEquals(2, frame[1].toInt())
        assertEquals(0, frame[2].toInt())
        assertEquals(0, frame[3].toInt())
        assertEquals(0, frame[4].toInt())
        assertEquals('h'.code.toByte(), frame[5])
        assertEquals('i'.code.toByte(), frame[6])
    }

    @Test
    fun testStdinFrameUsesStdinId() {
        val frame = ShellProtocolV2.buildStdin(byteArrayOf(0x41, 0x42))
        assertEquals(ShellProtocolV2.ID_STDIN, frame[0].toInt())
        assertEquals(7, frame.size)
    }

    @Test
    fun testWindowSizeFrameEncoding() {
        val frame = ShellProtocolV2.buildWindowSize(rows = 24, cols = 80)
        val payloadLen = 10 // "24x80,0x0" + NUL
        assertEquals(5 + payloadLen, frame.size)
        assertEquals(ShellProtocolV2.ID_WINDOW_SIZE_CHANGE, frame[0].toInt())

        val body = String(frame, 5, payloadLen, Charsets.UTF_8)
        assertEquals("24x80,0x0\u0000", body)
    }

    @Test
    fun testWindowSizeFrameCustomDimensions() {
        val frame = ShellProtocolV2.buildWindowSize(rows = 60, cols = 120)
        val len = frame[1].toInt()
        val body = String(frame, 5, len, Charsets.UTF_8)
        assertEquals("60x120,0x0\u0000", body)
    }

    @Test
    fun testEmptyPayloadFrame() {
        val frame = ShellProtocolV2.buildFrame(ShellProtocolV2.ID_STDOUT, ByteArray(0))
        assertEquals(5, frame.size)
        assertEquals(1, frame[0].toInt())
        assertEquals(0, frame[1].toInt())
    }

    @Test
    fun testExitIdConstant() {
        val frame = ShellProtocolV2.buildFrame(ShellProtocolV2.ID_EXIT, byteArrayOf(0))
        assertEquals(3, frame[0].toInt())
        assertEquals(6, frame.size)
    }
}
