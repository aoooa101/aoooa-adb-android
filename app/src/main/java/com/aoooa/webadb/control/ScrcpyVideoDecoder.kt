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
 * 输入：scrcpy Annex-B 帧；输出：渲染到 Surface。
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

    private val sync = Any()

    fun attachSurface(surface: Surface?) {
        synchronized(sync) {
            this.surface = surface
            if (configured && surface != null && codec != null) {
                // 重建：尺寸已有则立即配置
                tryRecreateLocked()
            }
        }
    }

    fun updateSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        synchronized(sync) {
            if (width == w && height == h && configured) return
            width = w
            height = h
            tryRecreateLocked()
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val t = HandlerThread("scrcpy-decoder").also { it.start() }
        thread = t
        handler = Handler(t.looper)
    }

    fun stop() {
        running.set(false)
        synchronized(sync) {
            releaseCodecLocked()
            width = 0
            height = 0
            configured = false
        }
        try {
            thread?.quitSafely()
        } catch (_: Exception) {
        }
        thread = null
        handler = null
    }

    fun feed(isConfig: Boolean, isKey: Boolean, data: ByteArray, ptsUs: Long) {
        if (!running.get() || data.isEmpty()) return
        val h = handler ?: return
        h.post {
            synchronized(sync) {
                feedLocked(isConfig, isKey, data, ptsUs)
            }
        }
    }

    private fun tryRecreateLocked() {
        releaseCodecLocked()
        val s = surface
        if (s == null || !s.isValid || width <= 0 || height <= 0) {
            configured = false
            return
        }
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(format, s, null, 0)
            c.start()
            codec = c
            configured = true
        } catch (e: Exception) {
            configured = false
            codec = null
            onError("decoder_config:${e.message}")
        }
    }

    private fun releaseCodecLocked() {
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
    }

    private fun feedLocked(isConfig: Boolean, isKey: Boolean, data: ByteArray, ptsUs: Long) {
        val c = codec
        if (!configured || c == null) {
            if (width > 0 && height > 0 && surface != null) {
                tryRecreateLocked()
            }
        }
        val codecNow = codec ?: return
        try {
            val inIndex = codecNow.dequeueInputBuffer(20_000)
            if (inIndex >= 0) {
                val input: ByteBuffer? = codecNow.getInputBuffer(inIndex)
                input?.clear()
                input?.put(data)
                var flags = 0
                if (isConfig) flags = flags or MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                if (isKey) flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                codecNow.queueInputBuffer(inIndex, 0, data.size, ptsUs.coerceAtLeast(0L), flags)
            }

            val info = MediaCodec.BufferInfo()
            var outIndex = codecNow.dequeueOutputBuffer(info, 0)
            var loops = 0
            while (outIndex >= 0 && loops < 8) {
                codecNow.releaseOutputBuffer(outIndex, true)
                onFrame()
                loops++
                outIndex = codecNow.dequeueOutputBuffer(info, 0)
            }
        } catch (e: Exception) {
            onError("decoder_feed:${e.message}")
            tryRecreateLocked()
        }
    }
}
