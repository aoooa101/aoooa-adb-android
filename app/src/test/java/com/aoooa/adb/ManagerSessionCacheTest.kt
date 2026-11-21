package com.aoooa.adb

import com.aoooa.adb.ui.SessionListing
import com.aoooa.adb.ui.preview.previewSampleSize
import org.junit.Assert.*
import org.junit.Test

class ManagerSessionCacheTest {
    @Test fun cachedResultSkipsReloadAndFailedRefreshKeepsIt() {
        val listing = SessionListing<String> { true }
        assertTrue(listing.start(false))
        assertFalse(listing.start(true))
        assertTrue(listing.finish("old"))
        assertFalse(listing.start(false))
        assertTrue(listing.start(true))
        assertTrue(listing.finish(null))
        assertEquals("old", listing.state.value)
    }

    @Test fun closedSessionRejectsLateResultAndLargeDataIsNotCached() {
        val listing = SessionListing<String> { it.length <= 3 }
        assertTrue(listing.start(false))
        assertTrue(listing.finish("oversized"))
        assertNull(listing.state.value)
        assertTrue(listing.start(false))
        listing.close()
        assertFalse(listing.finish("new"))
        assertFalse(listing.start(true))
        assertNull(listing.state.value)
    }

    @Test fun previewSamplingLimitsPixelCountIncludingExtremeDimensions() {
        assertEquals(1, previewSampleSize(640, 480))
        for ((w, h) in listOf(8000 to 8000, 1 to Int.MAX_VALUE, Int.MAX_VALUE to Int.MAX_VALUE)) {
            val scale = previewSampleSize(w, h).toLong()
            val width = (w + scale - 1) / scale
            val height = (h + scale - 1) / scale
            assertTrue(width <= 2048 && height <= 2048 && width * height <= 2_000_000)
        }
    }
}
