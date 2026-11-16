package com.aoooa.adb.control

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** H.264 Surface 解码：配置 SPS/PPS、有界输入队列及解码器候选回退。 */
class ScrcpyVideoDecoder(
    private val onError: (String) -> Unit = {},
    private val onFrame: () -> Unit = {},
    private val diagnose: (String) -> Unit = {},
    private val onSizeChanged: (Int, Int) -> Unit = { _, _ -> }
) {
    private val running = AtomicBoolean(false)
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var width: Int = 0
    private var height: Int = 0
    private var configured = false
    private var recreateAfterMs = 0L
    private var lastRecoveryLogAtMs = 0L
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile private var cachedConfig: ByteArray? = null
    @Volatile private var cachedSps: ByteArray? = null
    @Volatile private var cachedPps: ByteArray? = null
    @Volatile private var needKeyFrame = true

    private val sync = Any()
    private val inputQueue = VideoFrameQueue()
    private var retryFrame: VideoFrameQueue.Frame? = null
    private val drainOutput = object : Runnable {
        override fun run() {
            val owner = handler ?: return
            if (!running.get() || android.os.Looper.myLooper() !== owner.looper) return
            synchronized(sync) {
                if (!running.get() || handler !== owner) return
                try {
                    codec?.let { drainOutputLocked(it) }
                    if (inputQueue.needsReset()) retryFrame = null
                    val retry = retryFrame
                    retryFrame = null
                    val next = if (retry == null) inputQueue.poll() else VideoFrameQueue.Next(retry, false)
                    if (next != null) {
                        if (next.reset) {
                            retryFrame = null
                            needKeyFrame = true
                            releaseCodecLocked(keepSize = true)
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastRecoveryLogAtMs >= 1_000L) {
                                lastRecoveryLogAtMs = now
                                diagnose("queue_overload recovery=wait_keyframe")
                            }
                        }
                        val frame = next.frame
                        feedLocked(frame.config, frame.key, frame.data, frame.pts)
                    }
                } catch (e: Exception) {
                    recoverCodecLocked("decoder_output", e)
                }
            }
            if (running.get() && handler === owner) {
                owner.postDelayed(this, if (retryFrame == null && inputQueue.hasFrames()) 0L else 5L)
            }
        }
    }

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
                            return@synchronized // 输出 Surface 已切换，保留当前解码状态。
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
        handler?.post(drainOutput)
        needKeyFrame = true
    }

    fun stop() {
        running.set(false)
        val oldHandler = handler
        val oldThread = thread
        handler = null
        thread = null
        oldHandler?.removeCallbacksAndMessages(null)
        synchronized(sync) {
            inputQueue.clear()
            retryFrame = null
            releaseCodecLocked(keepSize = false)
            width = 0
            height = 0
            cachedConfig = null
            cachedSps = null
            cachedPps = null
            recreateAfterMs = 0L
            lastRecoveryLogAtMs = 0L
            needKeyFrame = true
        }
        oldThread?.quitSafely()
    }

    fun feed(isConfig: Boolean, isKey: Boolean, data: ByteArray, ptsUs: Long) {
        if (!running.get() || data.isEmpty()) return
        inputQueue.offer(VideoFrameQueue.Frame(isConfig, isKey, data.copyOf(), ptsUs))
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

        if (android.os.SystemClock.elapsedRealtime() < recreateAfterMs) return
        releaseCodecLocked(keepSize = true)
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 3 * 1024 * 1024)
            val sps = cachedSps
            val pps = cachedPps
            if (sps != null && pps != null) {
                // H.264 使用 csd-0=SPS、csd-1=PPS 参数配置（高通/三星/MTK 规范）
                format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            } else {
                // fallback：整包 Annex-B config 放 csd-0
                val csd = cachedConfig
                if (csd != null && csd.isNotEmpty()) {
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
                }
            }

            val selected = CodecCandidateSelector.select(
                candidates = listOf("default", "c2.android.avc.decoder", "OMX.google.h264.decoder"),
                create = { name ->
                    if (name == "default") MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    else MediaCodec.createByCodecName(name)
                },
                initialize = { candidate ->
                    candidate.configure(format, s, null, 0)
                    candidate.start()
                },
                release = { candidate -> candidate.release() },
                diagnose = { event -> diagnose("$event width=$width height=$height") }
            )
            codec = selected
            configured = selected != null
            needKeyFrame = true
            retryFrame = null
            if (selected == null) {
                recreateAfterMs = android.os.SystemClock.elapsedRealtime() + 1_000L
                onError("decoder_config:all_candidates_failed")
            } else {
                recreateAfterMs = 0L
            }
        } catch (e: Exception) {
            releaseCodecLocked(keepSize = true)
            recreateAfterMs = android.os.SystemClock.elapsedRealtime() + 1_000L
            onError("decoder_config:${e.javaClass.simpleName}")
        }
    }

    private fun releaseCodecLocked(keepSize: Boolean) {
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (e: Exception) {
            onError("decoder_release_failed type=${e.javaClass.simpleName}")
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
            cachedSps = pair?.first
            cachedPps = pair?.second
            pair?.first?.let { sps ->
                H264SpsParser.size(sps)?.let { (w, h) ->
                    if (width != w || height != h) {
                        width = w
                        height = h
                        onSizeChanged(w, h)
                    }
                }
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

        // 重建后必须等关键帧，否则会一直 decoder_feed 失败并导致卡在第一帧或绿屏
        if (needKeyFrame && !isKey) {
            return
        }

        try {
            drainOutputLocked(codecNow)
            val ok = queueInputLocked(codecNow, data, ptsUs.coerceAtLeast(0L), 0)
            if (!ok) {
                drainOutputLocked(codecNow)
                retryFrame = VideoFrameQueue.Frame(false, isKey, data, ptsUs)
                return
            }
            if (isKey) needKeyFrame = false
            drainOutputLocked(codecNow)
        } catch (e: Exception) {
            recoverCodecLocked("decoder_input", e)
        }
    }

    /** 放弃异常实例持有的缓冲区，下一关键帧到达后重新配置。 */
    private fun recoverCodecLocked(stage: String, error: Exception) {
        releaseCodecLocked(keepSize = true)
        retryFrame = null
        needKeyFrame = true
        val now = android.os.SystemClock.elapsedRealtime()
        recreateAfterMs = now + 1_000L
        if (now - lastRecoveryLogAtMs >= 1_000L) {
            lastRecoveryLogAtMs = now
            onError("$stage type=${error.javaClass.simpleName} recovery=release_wait_keyframe")
        }
    }

    private fun drainOutputLocked(codecNow: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        repeat(16) {
            val index = codecNow.dequeueOutputBuffer(info, 0)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val format = codecNow.outputFormat
                val codedWidth = format.getInteger(MediaFormat.KEY_WIDTH)
                val codedHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
                val w = if (format.containsKey("crop-left") && format.containsKey("crop-right")) {
                    format.getInteger("crop-right") - format.getInteger("crop-left") + 1
                } else codedWidth
                val h = if (format.containsKey("crop-top") && format.containsKey("crop-bottom")) {
                    format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1
                } else codedHeight
                if (ScrcpyProtocol.isPlausibleVideoSize(w, h) && (width != w || height != h)) {
                    width = w
                    height = h
                    onSizeChanged(w, h)
                }
            }
            if (index >= 0) {
                val render = surface?.isValid == true &&
                    (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                codecNow.releaseOutputBuffer(index, render)
                if (render) onFrame()
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
        val input = codecNow.getInputBuffer(inIndex)
            ?: throw IllegalStateException("decoder input buffer unavailable")
        input.clear()
        require(data.size <= input.capacity()) {
            "decoder_feed:input_too_large:${data.size}>${input.capacity()}"
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
        // 将 3 字节起始码补齐为 4 字节 00 00 00 01
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
