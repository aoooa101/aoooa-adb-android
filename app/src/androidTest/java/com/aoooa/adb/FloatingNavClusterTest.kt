package com.aoooa.adb

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.aoooa.adb.ui.control.FloatingNavCluster
import com.aoooa.adb.ui.i18n.I18n
import com.aoooa.adb.ui.i18n.control
import org.junit.Rule
import org.junit.Test

class FloatingNavClusterTest {
    @get:Rule val compose = createComposeRule()

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                FloatingNavCluster(I18n.en, {}, {}, {}, Modifier.size(300.dp, 400.dp))
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    @Test fun manualCollapseAndExpandAreDistinctActions() {
        show()
        compose.onNodeWithContentDescription(I18n.en.control.collapseTools)
            .assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithContentDescription(I18n.en.control.collapseTools).assertDoesNotExist()
        compose.onNodeWithContentDescription(I18n.en.controlNavHome).assertDoesNotExist()
        compose.onNodeWithContentDescription(I18n.en.control.expandTools)
            .assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithContentDescription(I18n.en.control.collapseTools).assertIsDisplayed()
        compose.onNodeWithContentDescription(I18n.en.controlNavHome).assertIsDisplayed()
        compose.onNodeWithContentDescription(I18n.en.control.expandTools).assertDoesNotExist()
    }

    @Test fun draggingCollapseButtonDoesNotCollapse() {
        show()
        compose.onNodeWithContentDescription(I18n.en.control.collapseTools).performTouchInput {
            swipe(center, center + Offset(-180f, 20f), 400)
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithContentDescription(I18n.en.control.collapseTools).assertIsDisplayed()
        compose.onNodeWithContentDescription(I18n.en.controlNavHome).assertIsDisplayed()
        compose.onNodeWithContentDescription(I18n.en.control.expandTools).assertDoesNotExist()
    }

    @Test fun idleCollapsesAndClickExpands() {
        show()
        compose.mainClock.advanceTimeBy(5100)
        compose.onNodeWithContentDescription(I18n.en.control.expandTools).assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithContentDescription(I18n.en.controlNavHome).assertIsDisplayed()
        compose.onNodeWithContentDescription(I18n.en.control.expandTools).assertDoesNotExist()
    }

    @Test fun draggingCollapsedArrowDoesNotExpand() {
        show()
        compose.mainClock.advanceTimeBy(5100)
        compose.onNodeWithContentDescription(I18n.en.control.expandTools).performTouchInput {
            swipe(center, center + Offset(-180f, 40f), 400)
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithContentDescription(I18n.en.control.expandTools).assertIsDisplayed()
        compose.onNodeWithContentDescription(I18n.en.controlNavHome).assertDoesNotExist()
    }
}
