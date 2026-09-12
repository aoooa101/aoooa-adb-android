package com.aoooa.webadb.control

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * H.264 MediaCodec 硬解（不引入额外 SO）。
 * 关键修复：
 * - 缓存 SPS/PPS；Surface 重建后用 MediaFormat csd 恢复
 * - 重建后丢弃非关键帧直到下一个关键帧
 * - 避免喂帧失败后无脑狂重建导致全程黑屏/首帧冻结
 *
 * 文档约束（Android MediaCodec）：
 * 若已通过 MediaFormat 的 csd-0/csd-1 配置 SPS/PPS，
 * 就不要再额外 queue BUFFER_FLAG_CODEC_CONFIG（官方不推荐，部分机型会异常）。
 */
class ScrcpyVideoDecoder(
    private val onError: (String) -> Unit = {},
    private val onFrame: () -> Unit = {}
) {
    private val running = AtomicBoolean(false)
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var width: Int = 0
    private var height: Int = 0
    private var configured = false
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile private var cachedConfig: ByteArray? = null
    @Volatile private var cachedSps: ByteArray? = null
    @Volatile private var cachedPps: ByteArray? = null
    @Volatile private var needKeyFrame = true
    @Volatile private var lastErrorAt = 0L
    @Volatile private var consecutiveFeedErrors = 0

    private val sync = Any()

    fun attachSurface(surface: Surface?) {
        val h = handler
        if (h == null) {
            synchronized(sync) {
                this.surface = surface
            }
            return
        }
        h.post {
            synchronized(sync) {
                val old = this.surface
                this.surface = surface
                if (old === surface) return@synchronized

                if (surface != null && surface.isValid) {
                    if (configured && codec != null) {
                        try {
                            codec?.setOutputSurface(surface)
                            return@synchronized // 热挂载成功，无需销毁重置，画面瞬间恢复
                        } catch (e: Exception) {
                            onError("setOutputSurface_fallback:${e.javaClass.simpleName}:${e.message}")
                        }
                    }
                    if (width > 0 && height > 0) {
                        tryRecreateLocked(force = true)
                    }
                }
            }
        }
    }

    fun updateSize(w: Int, h: Int) {
        if (!ScrcpyProtocol.isPlausibleVideoSize(w, h)) return
        val hnd = handler
        val task = {
            synchronized(sync) {
                if (width == w && height == h && configured) return@synchronized
                width = w
                height = h
                tryRecreateLocked(force = true)
            }
        }
        if (hnd != null) hnd.post(task) else task()
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val t = HandlerThread("scrcpy-decoder").also { it.start() }
        thread = t
        handler = Handler(t.looper)
        needKeyFrame = true
        consecutiveFeedErrors = 0
    }

    fun stop() {
        running.set(false)
        val h = handler
        if (h != null) {
            h.post {
                synchronized(sync) {
                    releaseCodecLocked(keepSize = false)
                    width = 0
                    height = 0
                    cachedConfig = null
                    cachedSps = null
                    cachedPps = null
                    needKeyFrame = true
                }
            }
            try {
                thread?.quitSafely()
            } catch (_: Exception) {
            }
        } else {
            synchronized(sync) {
                releaseCodecLocked(keepSize = false)
                width = 0
                height = 0
                cachedConfig = null
                cachedSps = null
                cachedPps = null
            }
        }
        thread = null
        handler = null
    }

    fun feed(isConfig: Boolean, isKey: Boolean, data: ByteArray, ptsUs: Long) {
        if (!running.get() || data.isEmpty()) return
        val h = handler ?: return
        // 拷贝一份，避免上游缓冲被复用
        val copy = data.copyOf()
        h.post {
            synchronized(sync) {
                feedLocked(isConfig, isKey, copy, ptsUs)
            }
        }
    }

    private fun tryRecreateLocked(force: Boolean = false) {
        val s = surface
        if (s == null || !s.isValid || width <= 0 || height <= 0) {
            releaseCodecLocked(keepSize = true)
            return
        }
        if (!force && configured && codec != null) return

        // 还没拿到 SPS/PPS 时先不硬配，等 config 包
        if (cachedSps == null && cachedConfig == null) {
            releaseCodecLocked(keepSize = true)
            return
        }

        releaseCodecLocked(keepSize = true)
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
            val sps = cachedSps
            val pps = cachedPps
            if (sps != null && pps != null) {
                // H.264 使用 csd-0=SPS、csd-1=PPS 参数配置
                format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            } else {
                // fallback：整包 Annex-B config 放 csd-0（兼容旧路径）
                val csd = cachedConfig
                if (csd != null && csd.isNotEmpty()) {
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
                }
            }
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(format, s, null, 0)
            c.start()
            codec = c
            configured = true
            needKeyFrame = true
            consecutiveFeedErrors = 0
            // 注意：csd 已通过 MediaFormat 提交，禁止再 queue BUFFER_FLAG_CODEC_CONFIG
        } catch (e: Exception) {
            configured = false
            codec = null
            onError("decoder_config:${e.javaClass.simpleName}:${e.message}")
        }
    }

    private fun releaseCodecLocked(keepSize: Boolean) {
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
        configured = false
        if (!keepSize) {
            // no-op for size here; caller decides
        }
    }

    private fun feedLocked(isConfig: Boolean, isKey: Boolean, data: ByteArray, ptsUs: Long) {
        if (isConfig) {
            cachedConfig = data.copyOf()
            val pair = splitSpsPps(data)
            if (pair != null) {
                cachedSps = pair.first
                cachedPps = pair.second
            }
            // config 到达后，用 MediaFormat csd 重建；不再二次 queue CODEC_CONFIG
            if (surface != null && surface?.isValid == true && width > 0 && height > 0) {
                tryRecreateLocked(force = true)
            }
            return
        }

        if (!configured || codec == null) {
            if (surface != null && surface?.isValid == true && width > 0 && height > 0 &&
                (cachedSps != null || cachedConfig != null)
            ) {
                tryRecreateLocked(force = true)
            } else {
                return
            }
        }
        val codecNow = codec ?: return

        // 重建后必须等关键帧，否则会一直 decoder_feed 失败并看起来“卡在第一帧”
        if (needKeyFrame && !isKey) {
            return
        }

        try {
            // 普通帧喂入；不强行打 KEY_FRAME / CODEC_CONFIG 标志
            val ok = queueInputLocked(codecNow, data, ptsUs.coerceAtLeast(0L), 0)
            if (!ok) return

            if (isKey) {
                needKeyFrame = false
            }

            val info = MediaCodec.BufferInfo()
            var outIndex = codecNow.dequeueOutputBuffer(info, 0)
            var loops = 0
            while (outIndex != MediaCodec.INFO_TRY_AGAIN_LATER && loops < 16) {
                when {
                    outIndex >= 0 -> {
                        val s = surface
                        val render = s != null && s.isValid && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        codecNow.releaseOutputBuffer(outIndex, render)
                        if (render) onFrame()
                        consecutiveFeedErrors = 0
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // ignore
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                        // legacy
                    }
                }
                loops++
                outIndex = codecNow.dequeueOutputBuffer(info, 0)
            }
        } catch (e: Exception) {
            consecutiveFeedErrors++
            val now = System.currentTimeMillis()
            // 限频报错，避免刷屏
            if (now - lastErrorAt > 1000) {
                lastErrorAt = now
                onError("decoder_feed:${e.javaClass.simpleName}:${e.message}")
            }
            // 短时间内多次失败才重建，并强制等待关键帧
            if (consecutiveFeedErrors >= 2) {
                needKeyFrame = true
                tryRecreateLocked(force = true)
                consecutiveFeedErrors = 0
            }
        }
    }

    private fun queueInputLocked(
        codecNow: MediaCodec,
        data: ByteArray,
        ptsUs: Long,
        flags: Int
    ): Boolean {
        val inIndex = codecNow.dequeueInputBuffer(30_000)
        if (inIndex < 0) return false
        val input = codecNow.getInputBuffer(inIndex) ?: return false
        input.clear()
        if (data.size > input.capacity()) {
            onError("decoder_feed:input_too_large:${data.size}>${input.capacity()}")
            return false
        }
        input.put(data)
        codecNow.queueInputBuffer(inIndex, 0, data.size, ptsUs, flags)
        return true
    }

    /** 从 Annex-B config 拆 SPS/PPS（带 start code）。 */
    private fun splitSpsPps(config: ByteArray): Pair<ByteArray, ByteArray>? {
        val nals = splitAnnexB(config)
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (nal in nals) {
            val headerOffset = getNalHeaderOffset(nal)
            if (headerOffset < 0 || headerOffset >= nal.size) continue
            val nalType = nal[headerOffset].toInt() and 0x1F
            when (nalType) {
                7 -> sps = ensureFourByteStartCode(nal, headerOffset)
                8 -> pps = ensureFourByteStartCode(nal, headerOffset)
            }
        }
        return if (sps != null && pps != null) sps to pps else null
    }

    private fun getNalHeaderOffset(nal: ByteArray): Int {
        if (nal.size >= 4 && nal[0].toInt() == 0 && nal[1].toInt() == 0 && nal[2].toInt() == 0 && nal[3].toInt() == 1) return 4
        if (nal.size >= 3 && nal[0].toInt() == 0 && nal[1].toInt() == 0 && nal[2].toInt() == 1) return 3
        return -1
    }

    private fun ensureFourByteStartCode(nal: ByteArray, headerOffset: Int): ByteArray {
        if (headerOffset == 4) return nal
        // 3 字节起始码标准化补齐为 4 字节 00 00 00 01，以获得最佳硬件解码器兼容性
        val normalized = ByteArray(nal.size + 1)
        normalized[0] = 0
        normalized[1] = 0
        normalized[2] = 0
        normalized[3] = 1
        System.arraycopy(nal, 3, normalized, 4, nal.size - 3)
        return normalized
    }

    private fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                if (data[i + 2].toInt() == 1) {
                    starts.add(i)
                    i += 3
                    continue
                }
                if (i + 4 < data.size && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) {
                    starts.add(i)
                    i += 4
                    continue
                }
            }
            i++
        }
        if (starts.isEmpty()) {
            val wrapped = ByteArray(4 + data.size)
            wrapped[3] = 1
            System.arraycopy(data, 0, wrapped, 4, data.size)
            return listOf(wrapped)
        }
        val out = ArrayList<ByteArray>(starts.size)
        for (idx in starts.indices) {
            val from = starts[idx]
            val to = if (idx + 1 < starts.size) starts[idx + 1] else data.size
            out.add(data.copyOfRange(from, to))
        }
        return out
    }
}
