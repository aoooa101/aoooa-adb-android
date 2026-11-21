package com.aoooa.adb

import androidx.compose.ui.graphics.Color
import com.aoooa.adb.log.LogDisplayFilter
import com.aoooa.adb.log.LogLine
import com.aoooa.adb.log.LogTypeFilter
import com.aoooa.adb.ui.parseAnsiText
import org.junit.Assert.*
import org.junit.Test

class TerminalAndLogDisplayTest {
    @Test fun plainStatusWordsDoNotChangeColor() {
        val text = "no error; FAIL_COUNT=0; Success; OKAY"
        val output = parseAnsiText(text)
        assertEquals(text, output.text)
        assertEquals(Color(0xFFF8FAFC), output.spanStyles.single().item.color)
    }

    @Test fun explicitRedStopsAtForegroundReset() {
        val result = parseAnsiText("\u001b[31mred\u001b[39mno error")
        assertEquals("redno error", result.text)
        assertEquals(listOf(Color(0xFFF87171), Color(0xFFF8FAFC)), result.spanStyles.map { it.item.color })
    }

    @Test fun logcatErrorFilterUsesSeverityNotShellWords() {
        fun matches(line: LogLine) = LogDisplayFilter.matches(line, true, LogTypeFilter.ERRORS, false, "", "", "", "", "")
        assertFalse(matches(LogLine(raw = "no error", level = "I")))
        assertTrue(matches(LogLine(raw = "unavailable", level = "E")))
    }
}
