package com.aoooa.adb

import com.aoooa.adb.adb.protocol.TerminalStreamProcessor
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalEscapeBoundaryTest {
    private fun checkChunks(prefix: String, separator: String, expectedLines: List<String>, expectedTail: String) {
        val text = prefix + separator + "B"
        val splits = listOf(0, 1, 2, 8192, prefix.length - 1, prefix.length, prefix.length + 1, text.length).filter { it in 0..text.length }
        for (split in splits) {
            val stream = TerminalStreamProcessor { error("Invalid OSC dispatched") }
            val first = stream.process(text.take(split))
            val second = stream.process(text.drop(split))
            assertEquals("split=$split", expectedLines, first.first + second.first)
            assertEquals("split=$split", expectedTail, second.second)
        }
    }

    @Test fun csiPreservesLfAndCrLfRegardlessOfChunking() {
        for (length in listOf(3, 8189, 8190, 8191, 9000)) {
            for (newline in listOf("\n", "\r\n")) checkChunks("A\u001b[" + "1".repeat(length), newline, listOf("A"), "B")
        }
    }

    @Test fun overlongOscRecoversAtLineBoundary() {
        for (newline in listOf("\n", "\r\n")) checkChunks("A\u001b]" + "x".repeat(9000), newline, listOf("A"), "B")
    }

    @Test fun carriageReturnStillRedrawsInsteadOfAddingLine() {
        for (op in listOf("[", "]")) checkChunks("AC\u001b" + op + "1".repeat(9000), "\r", emptyList(), "BC")
    }

    @Test fun oscStillAcceptsAValidDirectoryAcrossSplitTerminator() {
        val seen = mutableListOf<String>()
        val stream = TerminalStreamProcessor { seen.add(it) }
        stream.process("\u001b]aoooa-cwd;/tmp\u001b")
        assertEquals("B", stream.process("\\B").second)
        assertEquals(listOf("/tmp"), seen)
    }
}
