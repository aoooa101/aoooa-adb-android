package com.aoooa.adb.control

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * scrcpy 2.7 AAC 音频硬解 + AudioTrack 播放。
 * 约定与上游一致：48 kHz / 立体声；CONFIG 包作为 CSD-0（ASC）。
 */
class ScrcpyAudioDecoder(
    private val onError: (String) -> Unit = {},
    private val sampleRate: Int = 48_000,
    private val channelCount: Int = 2
) {
    private val lock = Any()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var configured = false
    private val started = AtomicBoolean(false)
    private var pendingAsc: ByteArray? = null

    fun start() {
        synchronized(lock) {
            if (started.get()) return
            val t = HandlerThread("scrcpy-audio").also { it.start() }
            thread = t
            handler = Handler(t.looper)
            started.set(true)
        }
    }

    fun stop() {
        synchronized(lock) {
            started.set(false)
            val h = handler
            if (h != null) {
                h.post {
                    synchronized(lock) {
                        releaseAllLocked()
                    }
                }
                try {
                    thread?.quitSafely()
                } catch (_: Exception) {
                }
            } else {
                releaseAllLocked()
            }
            handler = null
            thread = null
            pendingAsc = null
            configured = false
        }
    }

    fun feed(isConfig: Boolean, payload: ByteArray, ptsUs: Long) {
        if (!started.get() || payload.isEmpty()) return
        val h = handler ?: return
        val copy = payload.copyOf()
        h.post {
            synchronized(lock) {
                if (!started.get()) return@synchronized
                try {
                    if (isConfig) {
                        pendingAsc = copy
                        // ASC 变更后重建解码器
                        configureLocked(force = true)
                        return@synchronized
                    }
                    if (!configured) {
                        // 尚无 ASC 时尽量用默认参数建一次，等 ASC 后会重建
                        if (!configureLocked(force = false)) return@synchronized
                    }
                    val c = codec ?: return@synchronized
                    queueInputLocked(c, copy, ptsUs.coerceAtLeast(0L))
                    drainOutputLocked(c)
                } catch (t: Throwable) {
                    onError("audio_decode:${t.message}")
                    releaseCodecLocked()
                    configured = false
                }
            }
        }
    }

    private fun configureLocked(force: Boolean): Boolean {
        if (!force && configured && codec != null && track != null) return true
        releaseCodecLocked()
        releaseTrackLocked()
        return try {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRate,
                channelCount
            )
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            val asc = pendingAsc
            if (asc != null && asc.isNotEmpty()) {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(asc))
            } else {
                // 无 ASC 时补一个常见 LC-AAC stereo 48kHz AudioSpecificConfig：
                // AAC LC / 48000 / 2ch → 0x11 0x90
                format.setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0x11, 0x90.toByte())))
            }

            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            c.configure(format, null, null, 0)
            c.start()
            codec = c

            val channelMask = if (channelCount >= 2) {
                AudioFormat.CHANNEL_OUT_STEREO
            } else {
                AudioFormat.CHANNEL_OUT_MONO
            }
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(sampleRate / 10 * channelCount * 2)
            val at = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            at.play()
            track = at
            configured = true
            true
        } catch (t: Throwable) {
            onError("audio_configure:${t.message}")
            releaseCodecLocked()
            releaseTrackLocked()
            configured = false
            false
        }
    }

    private fun queueInputLocked(codecNow: MediaCodec, data: ByteArray, ptsUs: Long) {
        val inIndex = codecNow.dequeueInputBuffer(20_000)
        if (inIndex < 0) return
        val input = codecNow.getInputBuffer(inIndex) ?: return
        input.clear()
        if (data.size > input.capacity()) {
            onError("audio_input_too_large=${data.size}")
            return
        }
        input.put(data)
        codecNow.queueInputBuffer(inIndex, 0, data.size, ptsUs, 0)
    }

    private fun drainOutputLocked(codecNow: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var outIndex = codecNow.dequeueOutputBuffer(info, 0)
        var loops = 0
        val at = track ?: return
        while (outIndex != MediaCodec.INFO_TRY_AGAIN_LATER && loops < 16) {
            when {
                outIndex >= 0 -> {
                    val outBuf = codecNow.getOutputBuffer(outIndex)
                    if (outBuf != null && info.size > 0 &&
                        (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        val pcm = ByteArray(info.size)
                        outBuf.position(info.offset)
                        outBuf.get(pcm)
                        try {
                            at.write(pcm, 0, pcm.size)
                        } catch (t: Throwable) {
                            onError("audio_track_write:${t.message}")
                        }
                    }
                    codecNow.releaseOutputBuffer(outIndex, false)
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // ignore
                }
                outIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                    // ignore
                }
            }
            loops++
            outIndex = codecNow.dequeueOutputBuffer(info, 0)
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
    }

    private fun releaseTrackLocked() {
        try {
            track?.pause()
        } catch (_: Exception) {
        }
        try {
            track?.flush()
        } catch (_: Exception) {
        }
        try {
            track?.release()
        } catch (_: Exception) {
        }
        track = null
    }

    private fun releaseAllLocked() {
        releaseCodecLocked()
        releaseTrackLocked()
        configured = false
    }
}
