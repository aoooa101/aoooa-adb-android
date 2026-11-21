package com.aoooa.adb

import com.aoooa.adb.ui.tools.VisibleSelection
import org.junit.Assert.assertEquals
import org.junit.Test

class VisibleSelectionTest {
    @Test fun sameCountDifferentIdsDoesNotMeanAllSelected() {
        assertEquals(0, VisibleSelection.count(listOf("a"), listOf("b")))
        assertEquals(setOf("a", "b"), VisibleSelection.toggle(listOf("a"), listOf("b")))
    }

    @Test fun deselectOnlyAffectsVisibleIds() {
        assertEquals(setOf("hidden"), VisibleSelection.toggle(listOf("a", "b"), listOf("hidden", "a", "b")))
    }

    @Test fun emptySearchResultHasNoActionTargets() {
        assertEquals(0, VisibleSelection.count(emptyList(), listOf("hidden")))
        assertEquals(setOf("hidden"), VisibleSelection.toggle(emptyList(), listOf("hidden")))
    }
}
