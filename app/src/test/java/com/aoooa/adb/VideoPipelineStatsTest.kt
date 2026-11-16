package com.aoooa.adb

import com.aoooa.adb.control.VideoPipelineStats
import org.junit.Assert.*
import org.junit.Test

class VideoPipelineStatsTest {
    @Test fun summaryIsRateLimitedAndResetsCounters() {
        var now = 0L
        val stats = VideoPipelineStats { now }
        stats.receive(100, true)
        stats.input()
        stats.output(true)
        stats.retry()
        assertNull(stats.summary())
        now = 5000
        val summary = stats.summary()!!
        assertTrue(summary.contains("received=1"))
        assertTrue(summary.contains("inputRetries=1"))
        assertNull(stats.summary())
        now = 10000
        assertTrue(stats.summary()!!.contains("received=0"))
    }

    @Test fun recoveryRequiresKeySubmissionAndMatchingRender() {
        var now = 0L
        val stats = VideoPipelineStats { now }
        stats.beginRecovery("queue")
        assertNull(stats.rendered(50))
        now = 100
        assertTrue(stats.keySubmitted(100)!!.contains("waitMs=100"))
        assertNull(stats.rendered(99))
        now = 150
        assertTrue(stats.rendered(100)!!.contains("elapsedMs=150"))
        assertNull(stats.rendered(101))
        assertNull(stats.stop())
    }

    @Test fun stopReportsUnfinishedRecovery() {
        var now = 0L
        val stats = VideoPipelineStats { now }
        stats.beginRecovery("queue")
        now = 300
        assertTrue(stats.stop()!!.contains("elapsedMs=300"))
        assertNull(stats.stop())
    }

    @Test fun repeatedResetInvalidatesPendingRecoveryFrame() {
        val stats = VideoPipelineStats { 0L }
        stats.beginRecovery("first")
        stats.keySubmitted(100)
        stats.beginRecovery("second")
        assertNull(stats.rendered(100))
        stats.keySubmitted(200)
        assertNotNull(stats.rendered(200))
    }
}
