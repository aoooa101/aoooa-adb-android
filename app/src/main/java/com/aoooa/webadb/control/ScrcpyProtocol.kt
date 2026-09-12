package com.aoooa.webadb.control

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * scrcpy-server v2.7 协议常量与控制消息序列化。
 * 独立文件，不侵入既有 ADB 认证 / shell / logcat。
 */
object ScrcpyProtocol {
    const val SERVER_VERSION = "2.7"
    const val SERVER_ASSET_NAME = "scrcpy-server-v2.7"
    const val SERVER_REMOTE_PATH = "/data/local/tmp/scrcpy-server.jar"
    const val DEVICE_NAME_FIELD_LENGTH = 64
    const val CODEC_H264 = 0x68323634 // 'h264'

    // control message types (v2.7)
    const val TYPE_INJECT_KEYCODE = 0
    const val TYPE_INJECT_TOUCH = 2
    const val TYPE_BACK_OR_SCREEN_ON = 4

    // Android KeyEvent actions
    const val KEY_ACTION_DOWN = 0
    const val KEY_ACTION_UP = 1

    // Android MotionEvent actions
    const val MOTION_ACTION_DOWN = 0
    const val MOTION_ACTION_UP = 1
    const val MOTION_ACTION_MOVE = 2
    const val MOTION_ACTION_CANCEL = 3

    // common keycodes
    const val KEYCODE_HOME = 3
    const val KEYCODE_BACK = 4
    const val KEYCODE_APP_SWITCH = 187

    const val POINTER_ID_FINGER = -2L // SC_POINTER_ID_GENERIC_FINGER

    fun scidHex(scid: Int): String = "%08x".format(scid and 0x7FFFFFFF)

    fun abstractSocketName(scid: Int): String = "scrcpy_${scidHex(scid)}"

    fun buildServerCommand(
        scid: Int,
        maxSize: Int,
        videoBitRate: Int,
        controlEnabled: Boolean
    ): String {
        val args = buildString {
            append(SERVER_VERSION)
            append(" scid=").append(scidHex(scid))
            append(" tunnel_forward=true")
            append(" audio=false")
            append(" control=").append(controlEnabled)
            append(" cleanup=true")
            append(" max_size=").append(maxSize)
            append(" video_bit_rate=").append(videoBitRate)
            append(" send_frame_meta=true")
            append(" send_device_meta=true")
            append(" send_dummy_byte=true")
            append(" send_codec_meta=true")
        }
        return "CLASSPATH=$SERVER_REMOTE_PATH app_process / com.genymobile.scrcpy.Server $args"
    }

    fun injectKeycode(action: Int, keycode: Int, repeat: Int = 0, meta: Int = 0): ByteArray {
        val bb = ByteBuffer.allocate(14).order(ByteOrder.BIG_ENDIAN)
        bb.put(TYPE_INJECT_KEYCODE.toByte())
        bb.put(action.toByte())
        bb.putInt(keycode)
        bb.putInt(repeat)
        bb.putInt(meta)
        return bb.array()
    }

    fun injectKeyClick(keycode: Int): List<ByteArray> = listOf(
        injectKeycode(KEY_ACTION_DOWN, keycode),
        injectKeycode(KEY_ACTION_UP, keycode)
    )

    fun injectTouch(
        action: Int,
        x: Int,
        y: Int,
        videoWidth: Int,
        videoHeight: Int,
        pointerId: Long = POINTER_ID_FINGER,
        pressure: Float = if (action == MOTION_ACTION_UP || action == MOTION_ACTION_CANCEL) 0f else 1f,
        actionButton: Int = 0,
        buttons: Int = 0
    ): ByteArray {
        val bb = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        bb.put(TYPE_INJECT_TOUCH.toByte())
        bb.put(action.toByte())
        bb.putLong(pointerId)
        bb.putInt(x)
        bb.putInt(y)
        bb.putShort((videoWidth.coerceAtLeast(0) and 0xFFFF).toShort())
        bb.putShort((videoHeight.coerceAtLeast(0) and 0xFFFF).toShort())
        val p = (pressure.coerceIn(0f, 1f) * 0xFFFF).toInt() and 0xFFFF
        bb.putShort(p.toShort())
        bb.putInt(actionButton)
        bb.putInt(buttons)
        return bb.array()
    }

    fun backOrScreenOn(action: Int = KEY_ACTION_DOWN): ByteArray {
        return byteArrayOf(TYPE_BACK_OR_SCREEN_ON.toByte(), action.toByte())
    }
}

/** 视频流包解析器：拼帧后回调。 */
class ScrcpyVideoDemuxer(
    private val expectDummyByte: Boolean,
    private val onSessionSize: (width: Int, height: Int) -> Unit,
    private val onCodecId: (Int) -> Unit,
    private val onMediaPacket: (pts: Long, isConfig: Boolean, isKey: Boolean, payload: ByteArray) -> Unit,
    private val onDeviceName: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val buf = ArrayList<Byte>(256 * 1024)
    private var stage = if (expectDummyByte) Stage.DUMMY else Stage.DEVICE_NAME
    private var deviceNameRead = 0
    private val deviceNameBuf = ByteArray(ScrcpyProtocol.DEVICE_NAME_FIELD_LENGTH)
    private var codecRead = 0
    private val codecBuf = ByteArray(4)
    private var headerRead = 0
    private val headerBuf = ByteArray(12)
    private var packetRemaining = 0
    private var currentPts = 0L
    private var currentConfig = false
    private var currentKey = false
    private var packetBuf: ByteArray? = null
    private var packetOffset = 0

    private enum class Stage { DUMMY, DEVICE_NAME, CODEC, HEADER, PAYLOAD }

    @Synchronized
    fun accept(chunk: ByteArray) {
        for (b in chunk) buf.add(b)
        drain()
    }

    @Synchronized
    fun reset() {
        buf.clear()
        stage = if (expectDummyByte) Stage.DUMMY else Stage.DEVICE_NAME
        deviceNameRead = 0
        codecRead = 0
        headerRead = 0
        packetRemaining = 0
        packetBuf = null
        packetOffset = 0
    }

    private fun takeByte(): Int {
        return buf.removeAt(0).toInt() and 0xFF
    }

    private fun drain() {
        while (true) {
            when (stage) {
                Stage.DUMMY -> {
                    if (buf.isEmpty()) return
                    takeByte() // discard dummy
                    stage = Stage.DEVICE_NAME
                }
                Stage.DEVICE_NAME -> {
                    while (deviceNameRead < ScrcpyProtocol.DEVICE_NAME_FIELD_LENGTH && buf.isNotEmpty()) {
                        deviceNameBuf[deviceNameRead++] = takeByte().toByte()
                    }
                    if (deviceNameRead < ScrcpyProtocol.DEVICE_NAME_FIELD_LENGTH) return
                    var end = deviceNameBuf.indexOf(0)
                    if (end < 0) end = deviceNameBuf.size
                    val name = String(deviceNameBuf, 0, end, Charsets.UTF_8)
                    onDeviceName(name)
                    stage = Stage.CODEC
                }
                Stage.CODEC -> {
                    while (codecRead < 4 && buf.isNotEmpty()) {
                        codecBuf[codecRead++] = takeByte().toByte()
                    }
                    if (codecRead < 4) return
                    val codec = ByteBuffer.wrap(codecBuf).order(ByteOrder.BIG_ENDIAN).int
                    onCodecId(codec)
                    if (codec != ScrcpyProtocol.CODEC_H264) {
                        onError("unsupported_codec=0x%08X".format(codec))
                    }
                    stage = Stage.HEADER
                }
                Stage.HEADER -> {
                    while (headerRead < 12 && buf.isNotEmpty()) {
                        headerBuf[headerRead++] = takeByte().toByte()
                    }
                    if (headerRead < 12) return
                    parseHeader(headerBuf)
                    headerRead = 0
                }
                Stage.PAYLOAD -> {
                    val dest = packetBuf ?: return
                    while (packetRemaining > 0 && buf.isNotEmpty()) {
                        dest[packetOffset++] = takeByte().toByte()
                        packetRemaining--
                    }
                    if (packetRemaining > 0) return
                    val payload = dest
                    packetBuf = null
                    packetOffset = 0
                    onMediaPacket(currentPts, currentConfig, currentKey, payload)
                    stage = Stage.HEADER
                }
            }
        }
    }

    private fun parseHeader(h: ByteArray) {
        val bb = ByteBuffer.wrap(h).order(ByteOrder.BIG_ENDIAN)
        val first = bb.int
        val second = bb.int
        val third = bb.int
        val isSession = (first ushr 31) == 1
        if (isSession) {
            val width = second
            val height = third
            if (width > 0 && height > 0) onSessionSize(width, height)
            // session packet has no payload
            stage = Stage.HEADER
            return
        }
        // media packet: bits 63=0, 62=config, 61=keyframe; pts in low 61 bits of first 8 bytes
        val ptsHi = first
        val ptsLo = second
        currentConfig = ((ptsHi ushr 30) and 1) == 1
        currentKey = ((ptsHi ushr 29) and 1) == 1
        val pts61 = ((ptsHi.toLong() and 0x1FFFFFFF) shl 32) or (ptsLo.toLong() and 0xFFFFFFFFL)
        currentPts = pts61
        packetRemaining = third
        if (packetRemaining < 0 || packetRemaining > 16 * 1024 * 1024) {
            onError("bad_packet_size=$packetRemaining")
            packetRemaining = 0
            stage = Stage.HEADER
            return
        }
        if (packetRemaining == 0) {
            onMediaPacket(currentPts, currentConfig, currentKey, ByteArray(0))
            stage = Stage.HEADER
            return
        }
        packetBuf = ByteArray(packetRemaining)
        packetOffset = 0
        stage = Stage.PAYLOAD
    }
}
