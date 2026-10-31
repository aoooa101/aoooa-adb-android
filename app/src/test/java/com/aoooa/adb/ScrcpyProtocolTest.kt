package com.aoooa.adb

import com.aoooa.adb.control.ScrcpyAudioDemuxer
import com.aoooa.adb.control.ScrcpyProtocol
import com.aoooa.adb.control.ScrcpyVideoDemuxer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * scrcpy 协议测试：控制消息字节布局、握手参数与视频/音频流分片重组。
 */
class ScrcpyProtocolTest {

    private fun deviceNameField(name: String): ByteArray {
        val field = ByteArray(ScrcpyProtocol.DEVICE_NAME_FIELD_LENGTH)
        val bytes = name.toByteArray(Charsets.UTF_8)
        System.arraycopy(bytes, 0, field, 0, minOf(bytes.size, field.size - 1))
        return field
    }

    private fun codecMeta(codec: Int, width: Int, height: Int): ByteArray {
        val bb = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(codec)
        bb.putInt(width)
        bb.putInt(height)
        return bb.array()
    }

    private fun frameHeader(ptsFlags: Long, size: Int): ByteArray {
        val bb = ByteBuffer.allocate(ScrcpyProtocol.PACKET_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        bb.putLong(ptsFlags)
        bb.putInt(size)
        return bb.array()
    }

    @Test
    fun testScidHexIsZeroPadded() {
        assertEquals("0000abcd", ScrcpyProtocol.scidHex(0xABCD))
        assertEquals("00000000", ScrcpyProtocol.scidHex(0))
        // 负值按 31 位掩码处理，避免符号位进入十六进制
        assertEquals("7fffffff", ScrcpyProtocol.scidHex(-1))
    }

    @Test
    fun testAbstractSocketName() {
        assertEquals("scrcpy_0000abcd", ScrcpyProtocol.abstractSocketName(0xABCD))
    }

    @Test
    fun testServerCommandWithoutAudio() {
        val cmd = ScrcpyProtocol.buildServerCommand(
            scid = 0xABCD,
            maxSize = 1080,
            videoBitRate = 8_000_000,
            controlEnabled = true
        )
        assertTrue(cmd.startsWith("CLASSPATH=${ScrcpyProtocol.SERVER_REMOTE_PATH} app_process / com.genymobile.scrcpy.Server 2.7"))
        assertTrue(cmd.contains("scid=0000abcd"))
        assertTrue(cmd.contains("tunnel_forward=true"))
        assertTrue(cmd.contains("audio=false"))
        assertTrue(cmd.contains("control=true"))
        assertTrue(cmd.contains("max_size=1080"))
        assertTrue(cmd.contains("video_bit_rate=8000000"))
        assertTrue(cmd.contains("send_frame_meta=true"))
        assertFalse(cmd.contains("audio_codec="))
    }

    @Test
    fun testServerCommandWithAudio() {
        val cmd = ScrcpyProtocol.buildServerCommand(
            scid = 1,
            maxSize = 720,
            videoBitRate = 4_000_000,
            controlEnabled = false,
            audioEnabled = true,
            audioCodec = "aac",
            audioBitRate = 128_000
        )
        assertTrue(cmd.contains("audio=true"))
        assertTrue(cmd.contains("audio_codec=aac"))
        assertTrue(cmd.contains("audio_bit_rate=128000"))
        assertTrue(cmd.contains("control=false"))
    }

    @Test
    fun testInjectKeycodeLayout() {
        val msg = ScrcpyProtocol.injectKeycode(
            ScrcpyProtocol.KEY_ACTION_DOWN,
            ScrcpyProtocol.KEYCODE_HOME
        )
        assertEquals(14, msg.size)
        assertEquals(ScrcpyProtocol.TYPE_INJECT_KEYCODE, msg[0].toInt())
        assertEquals(ScrcpyProtocol.KEY_ACTION_DOWN, msg[1].toInt())

        val bb = ByteBuffer.wrap(msg).order(ByteOrder.BIG_ENDIAN)
        assertEquals(ScrcpyProtocol.KEYCODE_HOME, bb.getInt(2))
        assertEquals(0, bb.getInt(6))
        assertEquals(0, bb.getInt(10))
    }

    @Test
    fun testInjectKeyClickProducesDownAndUp() {
        val messages = ScrcpyProtocol.injectKeyClick(ScrcpyProtocol.KEYCODE_BACK)
        assertEquals(2, messages.size)
        assertEquals(ScrcpyProtocol.KEY_ACTION_DOWN, messages[0][1].toInt())
        assertEquals(ScrcpyProtocol.KEY_ACTION_UP, messages[1][1].toInt())

        val down = ByteBuffer.wrap(messages[0]).order(ByteOrder.BIG_ENDIAN)
        assertEquals(ScrcpyProtocol.KEYCODE_BACK, down.getInt(2))
    }

    @Test
    fun testInjectTouchLayout() {
        val msg = ScrcpyProtocol.injectTouch(
            action = ScrcpyProtocol.MOTION_ACTION_DOWN,
            x = 100,
            y = 200,
            videoWidth = 1080,
            videoHeight = 1920
        )
        assertEquals(32, msg.size)
        assertEquals(ScrcpyProtocol.TYPE_INJECT_TOUCH, msg[0].toInt())
        assertEquals(ScrcpyProtocol.MOTION_ACTION_DOWN, msg[1].toInt())

        val bb = ByteBuffer.wrap(msg).order(ByteOrder.BIG_ENDIAN)
        assertEquals(ScrcpyProtocol.POINTER_ID_FINGER, bb.getLong(2))
        assertEquals(100, bb.getInt(10))
        assertEquals(200, bb.getInt(14))
        assertEquals(1080, bb.getShort(18).toInt())
        assertEquals(1920, bb.getShort(20).toInt())
        // 按下事件默认压力为 1.0，编码为 0xFFFF
        assertEquals(0xFFFF, bb.getShort(22).toInt() and 0xFFFF)
        assertEquals(0, bb.getInt(24))
        assertEquals(0, bb.getInt(28))
    }

    @Test
    fun testInjectTouchReleaseHasZeroPressure() {
        val msg = ScrcpyProtocol.injectTouch(
            action = ScrcpyProtocol.MOTION_ACTION_UP,
            x = 0,
            y = 0,
            videoWidth = 1080,
            videoHeight = 1920
        )
        val bb = ByteBuffer.wrap(msg).order(ByteOrder.BIG_ENDIAN)
        assertEquals(0, bb.getShort(22).toInt())
    }

    @Test
    fun testInjectTouchClampsPressure() {
        val msg = ScrcpyProtocol.injectTouch(
            action = ScrcpyProtocol.MOTION_ACTION_MOVE,
            x = 10,
            y = 10,
            videoWidth = 1080,
            videoHeight = 1920,
            pressure = 5f
        )
        val bb = ByteBuffer.wrap(msg).order(ByteOrder.BIG_ENDIAN)
        assertEquals(0xFFFF, bb.getShort(22).toInt() and 0xFFFF)
    }

    @Test
    fun testBackOrScreenOn() {
        assertArrayEquals(
            byteArrayOf(ScrcpyProtocol.TYPE_BACK_OR_SCREEN_ON.toByte(), 0),
            ScrcpyProtocol.backOrScreenOn()
        )
        assertArrayEquals(
            byteArrayOf(ScrcpyProtocol.TYPE_BACK_OR_SCREEN_ON.toByte(), 1),
            ScrcpyProtocol.backOrScreenOn(ScrcpyProtocol.KEY_ACTION_UP)
        )
    }

    @Test
    fun testPlausibleVideoSize() {
        assertTrue(ScrcpyProtocol.isPlausibleVideoSize(16, 16))
        assertTrue(ScrcpyProtocol.isPlausibleVideoSize(1080, 1920))
        assertTrue(ScrcpyProtocol.isPlausibleVideoSize(4096, 4096))
        assertFalse(ScrcpyProtocol.isPlausibleVideoSize(15, 1080))
        assertFalse(ScrcpyProtocol.isPlausibleVideoSize(1080, 15))
        assertFalse(ScrcpyProtocol.isPlausibleVideoSize(4097, 1080))
    }

    @Test
    fun testVideoDemuxerReassemblesByteByByte() {
        val events = mutableListOf<String>()
        val demuxer = ScrcpyVideoDemuxer(
            expectDummyByte = false,
            onSessionSize = { w, h -> events += "size:${w}x$h" },
            onCodecId = { codec -> events += "codec:$codec" },
            onMediaPacket = { pts, config, key, payload ->
                events += "packet:$pts:$config:$key:${payload.size}"
            },
            onDeviceName = { name -> events += "name:$name" },
            onError = { err -> events += "error:$err" }
        )

        val payload = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val stream = deviceNameField("pixel-7") +
            codecMeta(ScrcpyProtocol.CODEC_H264, 1080, 1920) +
            frameHeader(ScrcpyProtocol.PACKET_FLAG_KEY_FRAME or 123456L, payload.size) +
            payload

        for (b in stream) {
            demuxer.accept(byteArrayOf(b))
        }

        assertEquals(
            listOf(
                "name:pixel-7",
                "codec:${ScrcpyProtocol.CODEC_H264}",
                "size:1080x1920",
                "packet:123456:false:true:8"
            ),
            events
        )
    }

    @Test
    fun testVideoDemuxerFlagsConfigPacket() {
        val events = mutableListOf<String>()
        val demuxer = ScrcpyVideoDemuxer(
            expectDummyByte = true,
            onSessionSize = { _, _ -> },
            onCodecId = { },
            onMediaPacket = { pts, config, key, payload ->
                events += "packet:$pts:$config:$key:${payload.size}"
            },
            onDeviceName = { },
            onError = { }
        )

        val payload = byteArrayOf(9, 9, 9, 9)
        val stream = byteArrayOf(0) + // dummy byte
            deviceNameField("dev") +
            codecMeta(ScrcpyProtocol.CODEC_H264, 720, 1280) +
            frameHeader(ScrcpyProtocol.PACKET_FLAG_CONFIG or 42L, payload.size) +
            payload

        demuxer.accept(stream)
        assertEquals(listOf("packet:42:true:false:4"), events)
    }

    @Test
    fun testVideoDemuxerRejectsUnsupportedCodec() {
        val errors = mutableListOf<String>()
        val demuxer = ScrcpyVideoDemuxer(
            expectDummyByte = false,
            onSessionSize = { _, _ -> },
            onCodecId = { },
            onMediaPacket = { _, _, _, _ -> },
            onDeviceName = { },
            onError = { err -> errors += err }
        )

        demuxer.accept(deviceNameField("dev") + codecMeta(0x00000002, 1080, 1920))
        assertEquals(1, errors.size)
        assertTrue(errors[0].startsWith("unsupported_codec="))
    }

    @Test
    fun testVideoDemuxerRejectsBadPacketSize() {
        val errors = mutableListOf<String>()
        val demuxer = ScrcpyVideoDemuxer(
            expectDummyByte = false,
            onSessionSize = { _, _ -> },
            onCodecId = { },
            onMediaPacket = { _, _, _, _ -> },
            onDeviceName = { },
            onError = { err -> errors += err }
        )

        demuxer.accept(
            deviceNameField("dev") +
                codecMeta(ScrcpyProtocol.CODEC_H264, 1080, 1920) +
                frameHeader(0L, 0)
        )
        assertEquals(1, errors.size)
        assertTrue(errors[0].startsWith("bad_packet_size="))
    }

    @Test
    fun testVideoDemuxerResetClearsStage() {
        val names = mutableListOf<String>()
        val demuxer = ScrcpyVideoDemuxer(
            expectDummyByte = false,
            onSessionSize = { _, _ -> },
            onCodecId = { },
            onMediaPacket = { _, _, _, _ -> },
            onDeviceName = { name -> names += name },
            onError = { }
        )

        demuxer.accept(deviceNameField("first"))
        demuxer.reset()
        demuxer.accept(deviceNameField("second"))

        assertEquals(listOf("first", "second"), names)
    }

    @Test
    fun testAudioDemuxerDetectsDisabledStream() {
        var disabled = 0
        val demuxer = ScrcpyAudioDemuxer(
            onCodecId = { },
            onMediaPacket = { _, _, _, _ -> },
            onDisabled = { disabled++ },
            onError = { }
        )

        val bytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
            .putInt(ScrcpyProtocol.CODEC_STREAM_DISABLED)
            .array()
        demuxer.accept(bytes)
        assertEquals(1, disabled)
    }

    @Test
    fun testAudioDemuxerParsesAacPacket() {
        val events = mutableListOf<String>()
        val demuxer = ScrcpyAudioDemuxer(
            onCodecId = { codec -> events += "codec:$codec" },
            onMediaPacket = { pts, config, key, payload ->
                events += "packet:$pts:$config:$key:${payload.size}"
            },
            onDisabled = { },
            onError = { err -> events += "error:$err" }
        )

        val payload = byteArrayOf(1, 2, 3)
        val stream = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
            .putInt(ScrcpyProtocol.CODEC_AAC)
            .array() +
            frameHeader(ScrcpyProtocol.PACKET_FLAG_KEY_FRAME or 777L, payload.size) +
            payload

        demuxer.accept(stream)
        assertEquals(
            listOf(
                "codec:${ScrcpyProtocol.CODEC_AAC}",
                "packet:777:false:true:3"
            ),
            events
        )
    }

    @Test
    fun testAudioDemuxerReportsStreamError() {
        val errors = mutableListOf<String>()
        val demuxer = ScrcpyAudioDemuxer(
            onCodecId = { },
            onMediaPacket = { _, _, _, _ -> },
            onDisabled = { },
            onError = { err -> errors += err }
        )

        val bytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
            .putInt(ScrcpyProtocol.CODEC_STREAM_ERROR)
            .array()
        demuxer.accept(bytes)
        assertEquals(listOf("audio_stream_error"), errors)
    }
}
