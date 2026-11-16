package com.aoooa.adb

import com.aoooa.adb.control.CodecCandidateSelector
import org.junit.Assert.*
import org.junit.Test

class CodecCandidateSelectorTest {
    @Test fun failedInitializationReleasesBeforeFallback() {
        val released = mutableListOf<String>()
        val events = mutableListOf<String>()
        val result = CodecCandidateSelector.select(
            candidates = listOf("hardware", "software"),
            create = { it },
            initialize = { if (it == "hardware") throw IllegalStateException() },
            release = { released += it },
            diagnose = { events += it }
        )
        assertEquals("software", result)
        assertEquals(listOf("hardware"), released)
        assertTrue(events.first().contains("codec_candidate_failed"))
        assertEquals("codec_ready candidate=software", events.last())
    }

    @Test fun creationAndReleaseFailuresStillTryRemainingCandidates() {
        val events = mutableListOf<String>()
        val result = CodecCandidateSelector.select(
            candidates = listOf("missing", "broken", "working"),
            create = { if (it == "missing") throw IllegalArgumentException() else it },
            initialize = { if (it == "broken") throw IllegalStateException() },
            release = { throw IllegalStateException() },
            diagnose = { events += it }
        )
        assertEquals("working", result)
        assertTrue(events.any { it.contains("codec_release_failed candidate=broken") })
    }

    @Test fun allFailedCandidatesAreReleased() {
        val released = mutableListOf<String>()
        val result = CodecCandidateSelector.select(
            candidates = listOf("one", "two"), create = { it },
            initialize = { throw IllegalStateException() }, release = { released += it },
            diagnose = {}
        )
        assertNull(result)
        assertEquals(listOf("one", "two"), released)
    }
}
