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
    const val CODEC_AAC = 0x00616163 // 'aac'
    // scrcpy demuxer special codec ids
    const val CODEC_STREAM_DISABLED = 0
    const val CODEC_STREAM_ERROR = 1
    const val PACKET_HEADER_SIZE = 12

    // frame flags (v2.7): pts_flags is big-endian u64
    // bit63 = CONFIG, bit62 = KEY_FRAME, low 62 bits = PTS
    const val PACKET_FLAG_CONFIG = 1L shl 63
    const val PACKET_FLAG_KEY_FRAME = 1L shl 62
    const val PACKET_PTS_MASK = PACKET_FLAG_KEY_FRAME - 1

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
        controlEnabled: Boolean,
        audioEnabled: Boolean = false,
        audioCodec: String = "aac",
        audioBitRate: Int = 128_000
    ): String {
        val args = buildString {
            append(SERVER_VERSION)
            append(" scid=").append(scidHex(scid))
            append(" tunnel_forward=true")
            if (audioEnabled) {
                append(" audio=true")
                append(" audio_codec=").append(audioCodec)
                append(" audio_bit_rate=").append(audioBitRate)
            } else {
                append(" audio=false")
            }
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

    fun isPlausibleVideoSize(width: Int, height: Int): Boolean {
        return width in 16..4096 && height in 16..4096
    }
}

/**
 * scrcpy 2.7 视频流 demuxer。
 *
 * 首个 socket：
 * 1) 可选 dummy byte（tunnel_forward）
 * 2) device name 64B
 * 3) codec meta 12B = codecId(u32 BE) + width(u32 BE) + height(u32 BE)
 * 之后循环：
 * 4) frame header 12B = pts_flags(u64 BE) + size(u32 BE)
 * 5) payload size 字节
 */
class ScrcpyVideoDemuxer(
    private val expectDummyByte: Boolean,
    private val onSessionSize: (width: Int, height: Int) -> Unit,
    private val onCodecId: (Int) -> Unit,
    private val onMediaPacket: (pts: Long, isConfig: Boolean, isKey: Boolean, payload: ByteArray) -> Unit,
    private val onDeviceName: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private var buffer = ByteArray(0)
    private var offset = 0
    private var stage = if (expectDummyByte) Stage.DUMMY else Stage.DEVICE_NAME
    private var packetRemaining = 0
    private var currentPts = 0L
    private var currentConfig = false
    private var currentKey = false
    private var packetBuf: ByteArray? = null
    private var packetOffset = 0
    private var fatal = false

    private enum class Stage { DUMMY, DEVICE_NAME, CODEC_META, HEADER, PAYLOAD }

    @Synchronized
    fun accept(chunk: ByteArray) {
        if (fatal || chunk.isEmpty()) return
        append(chunk)
        drain()
        compactIfNeeded()
    }

    @Synchronized
    fun reset() {
        buffer = ByteArray(0)
        offset = 0
        stage = if (expectDummyByte) Stage.DUMMY else Stage.DEVICE_NAME
        packetRemaining = 0
        packetBuf = null
        packetOffset = 0
        fatal = false
        currentPts = 0L
        currentConfig = false
        currentKey = false
    }

    private fun append(chunk: ByteArray) {
        val available = buffer.size - offset
        if (available == 0) {
            buffer = chunk.copyOf()
            offset = 0
            return
        }
        val merged = ByteArray(available + chunk.size)
        System.arraycopy(buffer, offset, merged, 0, available)
        System.arraycopy(chunk, 0, merged, available, chunk.size)
        buffer = merged
        offset = 0
    }

    private fun remaining(): Int = buffer.size - offset

    private fun compactIfNeeded() {
        if (offset == 0) return
        if (offset >= buffer.size) {
            buffer = ByteArray(0)
            offset = 0
            return
        }
        // 已消费超过一半时压缩，避免长期持有大缓冲
        if (offset > 64 * 1024 && offset * 2 >= buffer.size) {
            val left = remaining()
            val nb = ByteArray(left)
            System.arraycopy(buffer, offset, nb, 0, left)
            buffer = nb
            offset = 0
        }
    }

    private fun readExact(n: Int): ByteArray? {
        if (remaining() < n) return null
        val out = ByteArray(n)
        System.arraycopy(buffer, offset, out, 0, n)
        offset += n
        return out
    }

    private fun drain() {
        while (!fatal) {
            when (stage) {
                Stage.DUMMY -> {
                    if (remaining() < 1) return
                    offset += 1
                    stage = Stage.DEVICE_NAME
                }

                Stage.DEVICE_NAME -> {
                    val nameBytes = readExact(ScrcpyProtocol.DEVICE_NAME_FIELD_LENGTH) ?: return
                    var end = nameBytes.indexOf(0)
                    if (end < 0) end = nameBytes.size
                    val name = String(nameBytes, 0, end, Charsets.UTF_8)
                    onDeviceName(name)
                    stage = Stage.CODEC_META
                }

                Stage.CODEC_META -> {
                    // v2.7: codec(u32) + width(u32) + height(u32)
                    val meta = readExact(12) ?: return
                    val bb = ByteBuffer.wrap(meta).order(ByteOrder.BIG_ENDIAN)
                    val codec = bb.int
                    val width = bb.int
                    val height = bb.int
                    onCodecId(codec)
                    if (codec != ScrcpyProtocol.CODEC_H264) {
                        fatal = true
                        onError("unsupported_codec=0x%08X".format(codec))
                        return
                    }
                    if (ScrcpyProtocol.isPlausibleVideoSize(width, height)) {
                        onSessionSize(width, height)
                    } else {
                        onError("bad_init_size=${width}x${height}")
                    }
                    stage = Stage.HEADER
                }

                Stage.HEADER -> {
                    val header = readExact(ScrcpyProtocol.PACKET_HEADER_SIZE) ?: return
                    val bb = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
                    val ptsFlags = bb.long
                    val size = bb.int // already unsigned semantics via later check
                    currentConfig = (ptsFlags and ScrcpyProtocol.PACKET_FLAG_CONFIG) != 0L
                    currentKey = (ptsFlags and ScrcpyProtocol.PACKET_FLAG_KEY_FRAME) != 0L
                    currentPts = ptsFlags and ScrcpyProtocol.PACKET_PTS_MASK
                    // Java int 可能是负数；按无符号理解但限制上限
                    val packetSize = size.toLong() and 0xFFFFFFFFL
                    if (packetSize <= 0L || packetSize > 8L * 1024L * 1024L) {
                        fatal = true
                        onError("bad_packet_size=$packetSize")
                        return
                    }
                    packetRemaining = packetSize.toInt()
                    packetBuf = ByteArray(packetRemaining)
                    packetOffset = 0
                    stage = Stage.PAYLOAD
                }

                Stage.PAYLOAD -> {
                    val dest = packetBuf ?: return
                    val need = packetRemaining
                    val have = remaining()
                    if (have <= 0) return
                    val n = minOf(need, have)
                    System.arraycopy(buffer, offset, dest, packetOffset, n)
                    offset += n
                    packetOffset += n
                    packetRemaining -= n
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
}

/**
 * scrcpy 2.7 音频流 demuxer。
 *
 * 音频 socket：
 * 1) codec id 4B（BE u32；0=设备禁用流，1=配置错误，其它为 codec FourCC）
 * 2) 循环 frame header 12B + payload
 */
class ScrcpyAudioDemuxer(
    private val onCodecId: (Int) -> Unit,
    private val onMediaPacket: (pts: Long, isConfig: Boolean, isKey: Boolean, payload: ByteArray) -> Unit,
    private val onDisabled: () -> Unit,
    private val onError: (String) -> Unit
) {
    private var buffer = ByteArray(0)
    private var offset = 0
    private var stage = Stage.CODEC_ID
    private var packetRemaining = 0
    private var currentPts = 0L
    private var currentConfig = false
    private var currentKey = false
    private var packetBuf: ByteArray? = null
    private var packetOffset = 0
    private var fatal = false

    private enum class Stage { CODEC_ID, HEADER, PAYLOAD }

    @Synchronized
    fun accept(chunk: ByteArray) {
        if (fatal || chunk.isEmpty()) return
        append(chunk)
        drain()
        compactIfNeeded()
    }

    @Synchronized
    fun reset() {
        buffer = ByteArray(0)
        offset = 0
        stage = Stage.CODEC_ID
        packetRemaining = 0
        packetBuf = null
        packetOffset = 0
        fatal = false
        currentPts = 0L
        currentConfig = false
        currentKey = false
    }

    private fun append(chunk: ByteArray) {
        val available = buffer.size - offset
        if (available == 0) {
            buffer = chunk.copyOf()
            offset = 0
            return
        }
        val merged = ByteArray(available + chunk.size)
        System.arraycopy(buffer, offset, merged, 0, available)
        System.arraycopy(chunk, 0, merged, available, chunk.size)
        buffer = merged
        offset = 0
    }

    private fun remaining(): Int = buffer.size - offset

    private fun compactIfNeeded() {
        if (offset == 0) return
        if (offset >= buffer.size) {
            buffer = ByteArray(0)
            offset = 0
            return
        }
        if (offset > 16 * 1024 && offset * 2 >= buffer.size) {
            val left = remaining()
            val nb = ByteArray(left)
            System.arraycopy(buffer, offset, nb, 0, left)
            buffer = nb
            offset = 0
        }
    }

    private fun readExact(n: Int): ByteArray? {
        if (remaining() < n) return null
        val out = ByteArray(n)
        System.arraycopy(buffer, offset, out, 0, n)
        offset += n
        return out
    }

    private fun drain() {
        while (!fatal) {
            when (stage) {
                Stage.CODEC_ID -> {
                    val idBytes = readExact(4) ?: return
                    val codec = ByteBuffer.wrap(idBytes).order(ByteOrder.BIG_ENDIAN).int
                    when (codec) {
                        ScrcpyProtocol.CODEC_STREAM_DISABLED -> {
                            fatal = true
                            onDisabled()
                            return
                        }
                        ScrcpyProtocol.CODEC_STREAM_ERROR -> {
                            fatal = true
                            onError("audio_stream_error")
                            return
                        }
                        else -> {
                            onCodecId(codec)
                            if (codec != ScrcpyProtocol.CODEC_AAC) {
                                fatal = true
                                onError("unsupported_audio_codec=0x%08X".format(codec))
                                return
                            }
                            stage = Stage.HEADER
                        }
                    }
                }

                Stage.HEADER -> {
                    val header = readExact(ScrcpyProtocol.PACKET_HEADER_SIZE) ?: return
                    val bb = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
                    val ptsFlags = bb.long
                    val size = bb.int
                    currentConfig = (ptsFlags and ScrcpyProtocol.PACKET_FLAG_CONFIG) != 0L
                    currentKey = (ptsFlags and ScrcpyProtocol.PACKET_FLAG_KEY_FRAME) != 0L
                    currentPts = ptsFlags and ScrcpyProtocol.PACKET_PTS_MASK
                    val packetSize = size.toLong() and 0xFFFFFFFFL
                    if (packetSize <= 0L || packetSize > 1L * 1024L * 1024L) {
                        fatal = true
                        onError("bad_audio_packet_size=$packetSize")
                        return
                    }
                    packetRemaining = packetSize.toInt()
                    packetBuf = ByteArray(packetRemaining)
                    packetOffset = 0
                    stage = Stage.PAYLOAD
                }

                Stage.PAYLOAD -> {
                    val dest = packetBuf ?: return
                    val need = packetRemaining
                    val have = remaining()
                    if (have <= 0) return
                    val n = minOf(need, have)
                    System.arraycopy(buffer, offset, dest, packetOffset, n)
                    offset += n
                    packetOffset += n
                    packetRemaining -= n
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
}
