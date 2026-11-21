package com.aoooa.adb

import com.aoooa.adb.log.BoundedTextPreview
import org.junit.Assert.assertEquals
import org.junit.Test

class BoundedTextPreviewTest {
    private val scalar = "\uD83D\uDE00"

    @Test fun cutInsidePairOmitsBothHalves() {
        assertEquals("a" to true, BoundedTextPreview.read("a${scalar}b".reader(), 2))
    }

    @Test fun pairEndingAtLimitIsKept() {
        assertEquals(scalar to false, BoundedTextPreview.read(scalar.reader(), 2))
        assertEquals(scalar to true, BoundedTextPreview.read((scalar + "x").reader(), 2))
    }

    @Test fun emptyShortAndExactLengthAreNotTruncated() {
        for (s in listOf("", "a", "ab")) assertEquals(s to false, BoundedTextPreview.read(s.reader(), 2))
    }
}
