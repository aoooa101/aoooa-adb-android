package com.aoooa.adb.adb

import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** 单个服务的输出队列；结束标记保持在已有数据之后，溢出显式失败。 */
internal class AdbServiceOutput(capacity: Int = 8) {
    private val queue = LinkedBlockingQueue<ByteArray>(capacity)
    private val end = ByteArray(0)
    @Volatile private var closed = false
    @Volatile private var overflow = false

    @Synchronized fun offer(data: ByteArray) {
        if (closed) return
        if (!queue.offer(data)) {
            overflow = true
            closed = true
        }
    }

    @Synchronized fun finish() {
        closed = true
        queue.offer(end)
    }

    fun poll(timeoutMs: Long): ByteArray? {
        if (overflow) throw IOException("ADB service output queue overflow")
        val data = queue.poll(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        if (overflow) throw IOException("ADB service output queue overflow")
        return if (data === end) null else data
    }

    fun isFinished(): Boolean = closed && queue.isEmpty()
}
