package com.aoooa.adb

import com.aoooa.adb.ui.selection.VisibleSelection
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
    @Test fun refreshWithSameSizeDropsMissingFiles() {
        val old = java.io.File("old.log")
        val keep = java.io.File("keep.log")
        val new = java.io.File("new.log")
        val visible = listOf(keep, new)
        val retained = VisibleSelection.retainVisible(visible, setOf(old, keep))
        assertEquals(setOf(keep), retained)
        assertEquals(visible.toSet(), VisibleSelection.toggle(visible, retained))
    }
}
