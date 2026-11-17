package com.aoooa.adb.control

/** 有界压缩视频队列；拥塞时从匹配配置的关键帧边界恢复。 */
internal class VideoFrameQueue(
    private val maxFrames: Int = 120,
    private val maxBytes: Int = 16 * 1024 * 1024,
    private val maxAgeMs: Long = 500L,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    data class Frame(val config: Boolean, val key: Boolean, val data: ByteArray, val pts: Long)
    data class Next(val frame: Frame, val reset: Boolean)
    private data class Entry(val frame: Frame, val time: Long, val config: Frame?)
    private val frames = java.util.ArrayDeque<Entry>()
    private var bytes = 0
    private var latestConfig: Frame? = null
    private var waitingForKey = false
    private var resetPending = false
    private var mediaEnabled = true

    /** 暂停期间保留媒体参考链，容量限制仍然生效。 */
    @Synchronized
    fun setMediaEnabled(enabled: Boolean) {
        if (enabled && !mediaEnabled) {
            val now = nowMs()
            val retained = frames.map { it.copy(time = now) }
            frames.clear()
            frames.addAll(retained)
        }
        mediaEnabled = enabled
    }
    private var dropped = 0L
    private var recoveries = 0L
    private var lastReason = "none"

    init {
        require(maxFrames >= 2 && maxBytes > 0 && maxAgeMs > 0)
    }

    @Synchronized
    fun offer(frame: Frame) {
        val now = nowMs()
        if (frame.data.size > maxBytes) {
            dropped += frames.size + 1L
            frames.clear()
            bytes = 0
            if (frame.config) latestConfig = null
            markReset("oversized_packet")
            waitingForKey = true
            return
        }
        if (frame.config) latestConfig = frame
        if (waitingForKey && !frame.config && !frame.key) {
            dropped++
            return
        }
        if (waitingForKey && frame.key && !frame.config) {
            frames.clear()
            bytes = 0
            latestConfig?.let { append(Entry(it, now, it)) }
            if (bytes + frame.data.size > maxBytes) {
                dropped++
                return
            }
            waitingForKey = false
        }
        append(Entry(frame, now, latestConfig))
        val reason = limitReason(now)
        if (reason != null) recover(now, reason)
    }

    private fun append(entry: Entry) {
        frames.addLast(entry)
        bytes += entry.frame.data.size
    }

    private fun limitReason(now: Long): String? = when {
        bytes > maxBytes -> "bytes"
        frames.size > maxFrames -> "packets"
        mediaEnabled && frames.firstOrNull()?.let { now - it.time > maxAgeMs } == true -> "age"
        else -> null
    }

    private fun markReset(reason: String) {
        resetPending = true
        lastReason = reason
        recoveries++
    }

    private fun recover(now: Long, reason: String) {
        val pending = frames.toList()
        val index = pending.indexOfLast { it.frame.key && !it.frame.config }
        if (index >= 0) {
            val key = pending[index]
            val suffix = pending.subList(index, pending.size)
            val config = key.config
            val count = suffix.size + if (config != null) 1 else 0
            val size = suffix.sumOf { it.frame.data.size.toLong() } + (config?.data?.size ?: 0)
            if (count <= maxFrames && size <= maxBytes && (!mediaEnabled || now - key.time <= maxAgeMs)) {
                frames.clear()
                bytes = 0
                config?.let { append(Entry(it, key.time, it)) }
                suffix.forEach { append(it) }
                dropped += index.toLong()
                waitingForKey = false
                markReset(reason + "_retain_key")
                return
            }
        }
        dropped += frames.size.toLong()
        frames.clear()
        bytes = 0
        latestConfig?.let { append(Entry(it, now, it)) }
        waitingForKey = true
        markReset(reason + "_wait_key")
    }

    @Synchronized
    fun poll(): Next? {
        if (!mediaEnabled && frames.peekFirst()?.frame?.config != true) return null
        if (!waitingForKey) {
            val now = nowMs()
            limitReason(now)?.let { recover(now, it) }
        }
        val entry = frames.pollFirst() ?: return null
        bytes -= entry.frame.data.size
        val reset = resetPending
        resetPending = false
        return Next(entry.frame, reset)
    }

    @Synchronized
    fun needsReset(): Boolean = resetPending

    @Synchronized
    fun hasFrames(): Boolean = frames.isNotEmpty()

    @Synchronized
    fun hasConsumableFrames(): Boolean = frames.isNotEmpty() &&
        (mediaEnabled || frames.peekFirst().frame.config)

    @Synchronized
    fun diagnostics(): String {
        val age = frames.firstOrNull()?.let { (nowMs() - it.time).coerceAtLeast(0L) } ?: 0L
        return "queued=${frames.size} queueBytes=$bytes oldestAgeMs=$age waitingKey=$waitingForKey dropped=$dropped resets=$recoveries lastReset=$lastReason"
    }

    @Synchronized
    fun clear() {
        frames.clear()
        bytes = 0
        latestConfig = null
        waitingForKey = false
        resetPending = false
        mediaEnabled = true
        dropped = 0L
        recoveries = 0L
        lastReason = "none"
    }
}
