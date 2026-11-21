package com.aoooa.adb

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aoooa.adb.adb.protocol.TerminalStreamProcessor
import com.aoooa.adb.ui.parseAnsiText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalEscapeRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun recoveredLinesReachComposeWithoutControlPayload() {
        val stream = TerminalStreamProcessor()
        val lines = stream.process("before\u001b[" + "1".repeat(9000) + "\r").first + stream.process("\nafter\n").first
        compose.setContent { androidx.compose.foundation.layout.Column { lines.forEach { Text(parseAnsiText(it)) } } }
        compose.onNodeWithText("before").assertExists()
        compose.onNodeWithText("after").assertExists()
    }
}
