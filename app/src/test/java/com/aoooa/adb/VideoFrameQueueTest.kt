package com.aoooa.adb

import com.aoooa.adb.control.VideoFrameQueue
import org.junit.Assert.*
import org.junit.Test

class VideoFrameQueueTest {
    @Test fun oversizedFrameIsDroppedAndRequiresKeyFrame() {
        val queue = VideoFrameQueue()
        queue.offer(VideoFrameQueue.Frame(false, true, ByteArray(16 * 1024 * 1024 + 1), 1))
        assertFalse(queue.hasFrames())
        assertTrue(queue.needsReset())
        queue.offer(frame(pts = 2))
        assertNull(queue.poll())
        queue.offer(frame(key = true, pts = 3))
        val next = queue.poll()!!
        assertTrue(next.reset)
        assertEquals(3L, next.frame.pts)
    }

    @Test fun restoredConfigAndKeyCannotExceedByteLimit() {
        val queue = VideoFrameQueue()
        queue.offer(VideoFrameQueue.Frame(true, false, ByteArray(9 * 1024 * 1024), 0))
        queue.offer(VideoFrameQueue.Frame(false, true, ByteArray(9 * 1024 * 1024), 1))
        assertTrue(queue.poll()!!.frame.config)
        assertNull(queue.poll())
        queue.offer(frame(pts = 2))
        assertNull(queue.poll())
        queue.offer(frame(key = true, pts = 3))
        assertEquals(3L, queue.poll()!!.frame.pts)
    }

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
