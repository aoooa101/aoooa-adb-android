package com.aoooa.adb

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.aoooa.adb.ui.FollowLatestEffect
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 使用真实 Compose 列表验证内容跟随与手势暂停。 */
class FollowLatestEffectTest {
    @get:Rule val compose = createComposeRule()
    private val rows = mutableStateListOf<String>()
    private lateinit var state: LazyListState

    private fun show() {
        rows.addAll((0 until 50).map { "row-$it" })
        compose.setContent {
            state = rememberLazyListState()
            FollowLatestEffect(state, true, rows.size, rows.lastOrNull())
            LazyColumn(state = state, modifier = Modifier.width(240.dp).height(160.dp).testTag("rows")) {
                items(rows) { row -> Text(row, modifier = Modifier.heightIn(min = 32.dp)) }
            }
        }
        compose.waitForIdle()
    }

    private fun awaitBottom() {
        compose.waitUntil(5000) { !state.canScrollForward && state.layoutInfo.totalItemsCount == rows.size }
    }

    @Test fun initialContentStartsAtBottom() {
        show()
        awaitBottom()
        compose.onNodeWithText("row-49").assertIsDisplayed()
    }

    @Test fun appendedRowsStayVisible() {
        show()
        awaitBottom()
        compose.runOnIdle { rows.add("latest") }
        awaitBottom()
        compose.onNodeWithText("latest").assertIsDisplayed()
    }

    @Test fun sameSizeBufferReplacementStillFollows() {
        show()
        awaitBottom()
        compose.runOnIdle { rows.removeAt(0); rows.add("replacement") }
        awaitBottom()
        compose.onNodeWithText("replacement").assertIsDisplayed()
    }

    @Test fun growingLastLineStillFollows() {
        show()
        awaitBottom()
        compose.runOnIdle { rows[rows.lastIndex] = (1..20).joinToString("\n") { "output-$it" } }
        compose.waitForIdle()
        awaitBottom()
    }

    @Test fun draggingUpPausesAndReturningToBottomResumes() {
        show()
        awaitBottom()
        compose.onNodeWithTag("rows").performTouchInput { swipeDown() }
        compose.waitForIdle()
        val first = compose.runOnIdle { state.firstVisibleItemIndex }
        compose.runOnIdle { assertTrue(state.canScrollForward); rows.add("paused-update") }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(first, state.firstVisibleItemIndex) }
        compose.onNodeWithTag("rows").performScrollToIndex(rows.lastIndex)
        compose.waitForIdle()
        compose.runOnIdle { rows.add("resumed-update") }
        awaitBottom()
        compose.onNodeWithText("resumed-update").assertIsDisplayed()
    }
}
