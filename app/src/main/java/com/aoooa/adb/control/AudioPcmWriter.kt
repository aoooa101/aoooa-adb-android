package com.aoooa.adb.control

/** 在总时间预算内完成非阻塞 PCM 写入，停止信号使剩余数据停止发送。 */
internal object AudioPcmWriter {
    fun write(
        size: Int,
        active: () -> Boolean,
        nowMs: () -> Long,
        pause: () -> Unit,
        send: (offset: Int, remaining: Int) -> Int,
        timeoutMs: Long = 250L
    ) {
        require(size >= 0 && timeoutMs > 0)
        val start = nowMs()
        var offset = 0
        while (offset < size && active()) {
            check(nowMs() - start < timeoutMs) { "audio_track_write_timeout" }
            val remaining = size - offset
            val written = send(offset, remaining)
            check(written in 0..remaining) { "audio_track_write:$written" }
            if (written == 0) pause() else offset += written
        }
    }
}
