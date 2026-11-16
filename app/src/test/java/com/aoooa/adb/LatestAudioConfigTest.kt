package com.aoooa.adb

import com.aoooa.adb.control.LatestAudioConfig
import org.junit.Assert.*
import org.junit.Test

class LatestAudioConfigTest {
    @Test fun replacementInvalidatesQueuedSnapshot() {
        val configs = LatestAudioConfig()
        assertTrue(configs.update(byteArrayOf(1)))
        val old = configs.snapshot()
        assertTrue(configs.update(byteArrayOf(2)))
        assertFalse(configs.isCurrent(old))
        assertTrue(configs.isCurrent(configs.snapshot()))
        assertArrayEquals(byteArrayOf(2), configs.snapshot()!!.copyBytes())
    }

    @Test fun snapshotsDoNotShareMutableArrays() {
        val configs = LatestAudioConfig()
        val input = byteArrayOf(1, 2)
        configs.update(input)
        input[0] = 9
        val output = configs.snapshot()!!.copyBytes()
        output[1] = 9
        assertArrayEquals(byteArrayOf(1, 2), configs.snapshot()!!.copyBytes())
    }

    @Test fun invalidConfigPreservesLastValidSnapshot() {
        val configs = LatestAudioConfig()
        assertTrue(configs.update(ByteArray(65536)))
        val valid = configs.snapshot()
        assertFalse(configs.update(ByteArray(65537)))
        assertFalse(configs.update(byteArrayOf()))
        assertSame(valid, configs.snapshot())
    }

    @Test fun repeatedUpdatesRetainOnlyLatestConfig() {
        val configs = LatestAudioConfig()
        repeat(1000) { configs.update(byteArrayOf((it % 128).toByte())) }
        assertArrayEquals(byteArrayOf((999 % 128).toByte()), configs.snapshot()!!.copyBytes())
    }

    @Test fun newSessionDoesNotAcceptPreviousSnapshot() {
        val previous = LatestAudioConfig()
        previous.update(byteArrayOf(1))
        val current = LatestAudioConfig()
        assertNull(current.snapshot())
        assertTrue(current.isCurrent(null))
        assertFalse(current.isCurrent(previous.snapshot()))
    }
}
