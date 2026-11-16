package com.aoooa.adb

import com.aoooa.adb.control.VideoFrameQueue
import org.junit.Assert.*
import org.junit.Test

class VideoFrameQueueTest {
    private fun frame(config: Boolean = false, key: Boolean = false, pts: Long = 0) =
        VideoFrameQueue.Frame(config, key, byteArrayOf(1), pts)

    @Test
    fun normalFramesKeepOrder() {
        val queue = VideoFrameQueue()
        queue.offer(frame(pts = 1))
        queue.offer(frame(pts = 2))
        assertEquals(1L, queue.poll()!!.frame.pts)
        assertEquals(2L, queue.poll()!!.frame.pts)
        assertNull(queue.poll())
    }

    @Test
    fun overloadRestoresConfigAndWaitsForKey() {
        val queue = VideoFrameQueue()
        queue.offer(frame(config = true))
        repeat(8) { queue.offer(frame(pts = it.toLong())) }
        val config = queue.poll()!!
        assertTrue(config.reset)
        assertTrue(config.frame.config)
        assertNull(queue.poll())
        queue.offer(frame(pts = 20))
        assertNull(queue.poll())
        queue.offer(frame(key = true, pts = 21))
        assertEquals(21L, queue.poll()!!.frame.pts)
    }

    @Test
    fun clearDropsConfigurationAndRecoveryState() {
        val queue = VideoFrameQueue()
        queue.offer(frame(config = true))
        queue.clear()
        assertFalse(queue.hasFrames())
        queue.offer(frame(pts = 3))
        assertFalse(queue.poll()!!.reset)
    }
}
