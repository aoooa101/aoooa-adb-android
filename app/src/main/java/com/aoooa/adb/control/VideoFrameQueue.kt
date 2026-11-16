package com.aoooa.adb.control

/** 有界压缩视频队列；过载后丢弃旧参考链并等待关键帧。 */
internal class VideoFrameQueue {
    data class Frame(val config: Boolean, val key: Boolean, val data: ByteArray, val pts: Long)
    data class Next(val frame: Frame, val reset: Boolean)
    private val frames = java.util.ArrayDeque<Frame>()
    private var bytes = 0
    private var latestConfig: Frame? = null
    private var waitingForKey = false
    private var resetPending = false

    @Synchronized
    fun offer(frame: Frame) {
        val limit = 16 * 1024 * 1024
        if (frame.data.size > limit) {
            frames.clear()
            bytes = 0
            if (frame.config) latestConfig = null
            resetPending = true
            waitingForKey = true
            return
        }
        if (frame.config) latestConfig = frame
        if (frames.size >= 8 || bytes + frame.data.size > limit) {
            frames.clear()
            bytes = 0
            resetPending = true
            waitingForKey = true
            if (!frame.config) latestConfig?.let { append(it) }
        }
        if (bytes + frame.data.size > limit) return
        if (frame.config) {
            append(frame)
        } else if (!waitingForKey || frame.key) {
            waitingForKey = false
            append(frame)
        }
    }

    private fun append(frame: Frame) {
        frames.addLast(frame)
        bytes += frame.data.size
    }

    @Synchronized
    fun poll(): Next? {
        val frame = frames.pollFirst() ?: return null
        bytes -= frame.data.size
        val reset = resetPending
        resetPending = false
        return Next(frame, reset)
    }

    @Synchronized
    fun needsReset(): Boolean = resetPending

    @Synchronized
    fun hasFrames(): Boolean = frames.isNotEmpty()

    @Synchronized
    fun clear() {
        frames.clear()
        bytes = 0
        latestConfig = null
        waitingForKey = false
        resetPending = false
    }
}
