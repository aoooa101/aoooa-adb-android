package com.aoooa.adb

import com.aoooa.adb.adb.protocol.TerminalLineBuffer
import org.junit.Assert.*
import org.junit.Test

class TerminalLineBufferTest {
    @Test fun carriageReturnOverwritesInsteadOfAppending() {
        val line = TerminalLineBuffer()
        "prompt $ ls old".forEach { line.append(it) }
        line.carriageReturn()
        "prompt $ ls new-file".forEach { line.append(it) }
        assertEquals("prompt $ ls new-file", line.toString())
    }

    @Test fun backspaceSpaceBackspaceErasesOneCharacter() {
        val line = TerminalLineBuffer()
        "abc".forEach { line.append(it) }
        line.backspace()
        line.append(' ')
        line.backspace()
        line.append('d')
        assertEquals("abd", line.toString())
    }

    @Test fun eraseToEndRemovesStaleSuffix() {
        val line = TerminalLineBuffer()
        "abcdef".forEach { line.append(it) }
        line.column(2)
        line.erase(0)
        line.append('X')
        assertEquals("abX", line.toString())
    }

    @Test fun redrawPreservesStylesAndClearResetsCursor() {
        val line = TerminalLineBuffer()
        line.sgr("\u001b[31m")
        line.append('a')
        assertEquals("\u001b[31ma\u001b[0m", line.toString())
        line.reset()
        line.append('b')
        assertEquals("b", line.toString())
    }
}
