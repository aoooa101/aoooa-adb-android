package com.aoooa.adb

import com.aoooa.adb.ui.TerminalCompletion
import org.junit.Assert.*
import org.junit.Test

class TerminalCompletionTest {
    @Test fun parsesCommandAndPath() {
        assertTrue(TerminalCompletion.parse("logc")!!.command)
        val request = TerminalCompletion.parse("ls /sdca")!!
        assertFalse(request.command)
        assertEquals("ls ", request.before)
        assertEquals("/sdca", request.word)
    }

    @Test fun handlesQuotesAndEscapedSpaces() {
        assertEquals("/sdcard/My F", TerminalCompletion.parse("ls '/sdcard/My F")!!.word)
        assertEquals("My F", TerminalCompletion.parse("ls My\\ F")!!.word)
        assertEquals("ls 'My File' ", TerminalCompletion.apply(TerminalCompletion.parse("ls My")!!, "My File", true))
        assertEquals("'a'\\''b'", TerminalCompletion.quote("a'b"))
    }

    @Test fun rejectsExecutableExpressions() {
        listOf("ls ${'$'}(id)", "echo `id`", "ls;id", "cat x | sh", "ls ${'$'}HOME")
            .forEach { assertNull(it, TerminalCompletion.parse(it)) }
    }

    @Test fun decodesCandidatesAndPreservesDirectory() {
        val request = TerminalCompletion.parse("ls /sd")!!
        val result = TerminalCompletion.candidates(request, "D\tsdcard\u0000F\tsdk\u0000noise")
        assertEquals(listOf("/sdcard/", "/sdk"), result.map { it.value })
        assertEquals("/sd", TerminalCompletion.commonPrefix(result))
    }

    @Test fun queryQuotesUserData() {
        val request = TerminalCompletion.parse("ls 'a;id'")!!
        val query = TerminalCompletion.query(request, "/My Folder")
        assertTrue(query.contains("prefix='a;id'"))
        assertTrue(query.contains("cd '/My Folder'"))
        assertFalse(query.contains("eval "))
    }
}
