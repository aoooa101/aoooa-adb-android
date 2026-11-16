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
 * 通过 MediaCodec 解码 scrcpy 2.7 AAC 音频，并使用 AudioTrack 播放 PCM。
 * 约定与上游一致：48 kHz / 立体声；CONFIG 包作为 CSD-0（ASC）。
 */
class ScrcpyAudioDecoder(
    private val onError: (String) -> Unit = {},
    private val sampleRate: Int = 48_000,
    private val channelCount: Int = 2
) {
    private val lock = Any()
    private var thread: HandlerThread? = null
    @Volatile private var handler: Handler? = null
    @Volatile private var queueSlots = java.util.concurrent.Semaphore(64)
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var configured = false
    private val started = AtomicBoolean(false)
    private var pendingAsc: ByteArray? = null
    @Volatile private var latestConfig = LatestAudioConfig()
    private var appliedConfig: LatestAudioConfig.Snapshot? = null

    fun start() {
        synchronized(lock) {
            if (started.get()) return
            val t = HandlerThread("scrcpy-audio").also { it.start() }
            thread = t
            handler = Handler(t.looper)
            queueSlots = java.util.concurrent.Semaphore(64)
            latestConfig = LatestAudioConfig()
            appliedConfig = null
            started.set(true)
        }
    }

    fun stop() {
        started.set(false)
        synchronized(lock) {
            val oldHandler = handler
            val oldThread = thread
            handler = null
            thread = null
            oldHandler?.removeCallbacksAndMessages(null)
            releaseAllLocked()
            oldThread?.quitSafely()
            pendingAsc = null
            configured = false
        }
    }

    fun feed(isConfig: Boolean, payload: ByteArray, ptsUs: Long) {
        if (!started.get() || payload.isEmpty()) return
        val h = handler ?: return
        val configs = latestConfig
        if (isConfig) {
            if (!configs.update(payload)) onError("audio_config_invalid_size")
            return
        }
        val snapshot = configs.snapshot()
        val slots = queueSlots
        if (payload.size > 65536 || !slots.tryAcquire()) return
        val copy = payload.copyOf()
        val posted = h.post {
            try {
                synchronized(lock) {
                    if (!started.get() || handler !== h || latestConfig !== configs ||
                        !configs.isCurrent(snapshot)
                    ) return@synchronized
                    try {
                        if (appliedConfig !== snapshot) {
                            pendingAsc = snapshot?.copyBytes()
                            appliedConfig = snapshot
                            if (!configureLocked(force = true)) return@synchronized
                        }
                        if (!configured) {
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
            } finally {
                slots.release()
            }
        }
        if (!posted) slots.release()
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
            codec = c
            c.configure(format, null, null, 0)
            c.start()

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
            track = at
            at.play()
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
        val input = codecNow.getInputBuffer(inIndex)
            ?: throw IllegalStateException("audio input buffer unavailable")
        input.clear()
        require(data.size <= input.capacity()) { "audio_input_too_large=${data.size}" }
        input.put(data)
        codecNow.queueInputBuffer(inIndex, 0, data.size, ptsUs, 0)
    }

    private fun drainOutputLocked(codecNow: MediaCodec) {
        val at = track ?: return
        val info = MediaCodec.BufferInfo()
        repeat(16) {
            val outIndex = codecNow.dequeueOutputBuffer(info, 0)
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (outIndex >= 0) {
                try {
                    val outBuf = codecNow.getOutputBuffer(outIndex)
                    if (outBuf != null && info.size > 0 &&
                        (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        val pcm = ByteArray(info.size)
                        outBuf.position(info.offset)
                        outBuf.get(pcm)
                        AudioPcmWriter.write(
                            size = pcm.size,
                            active = { started.get() },
                            nowMs = { android.os.SystemClock.elapsedRealtime() },
                            pause = { Thread.sleep(2L) },
                            send = { offset, remaining ->
                                at.write(pcm, offset, remaining, AudioTrack.WRITE_NON_BLOCKING)
                            }
                        )
                    }
                } finally {
                    codecNow.releaseOutputBuffer(outIndex, false)
                }
            }
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
