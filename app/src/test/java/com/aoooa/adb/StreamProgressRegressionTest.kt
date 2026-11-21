package com.aoooa.adb

import com.aoooa.adb.adb.protocol.TerminalStreamProcessor
import com.aoooa.adb.adb.proxy.SmartSocketProtocol
import com.aoooa.adb.log.BoundedTextPreview
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.Reader
import org.junit.Assert.*
import org.junit.Test

class StreamProgressRegressionTest {
    @Test fun previewStopsOnFirstZeroRead() {
        val reader = object : Reader() {
            var calls = 0
            override fun read(dst: CharArray, off: Int, len: Int): Int { assertEquals(1, ++calls); return 0 }
            override fun close() {}
        }
        assertThrows(IOException::class.java) { BoundedTextPreview.read(reader) }
    }

    @Test fun exactReadDistinguishesShortReadsEofAndZero() {
        val short = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun read(dst: ByteArray, off: Int, len: Int) = super.read(dst, off, minOf(1, len))
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), SmartSocketProtocol.readExactly(short, 3))
        assertThrows(EOFException::class.java) { SmartSocketProtocol.readExactly(short, 1) }
        val zero = object : ByteArrayInputStream(byteArrayOf()) {
            var calls = 0
            override fun read(dst: ByteArray, off: Int, len: Int): Int { assertEquals(1, ++calls); return 0 }
        }
        assertThrows(IOException::class.java) { SmartSocketProtocol.readExactly(zero, 1) }
    }

    @Test fun oversizedOscConsumesSplitTerminatorAndResumes() {
        val stream = TerminalStreamProcessor { fail("oversized marker dispatched") }
        stream.process("\u001b]aoooa-cwd;/" + "x".repeat(9000))
        stream.process("tail\u001b")
        assertEquals("ready", stream.process("\\ready").second)
    }

    @Test fun oversizedCsiDoesNotColorFollowingText() {
        val stream = TerminalStreamProcessor()
        stream.process("\u001b[" + "1;".repeat(5000))
        assertEquals("ready", stream.process("31mready").second)
    }

    @Test fun repeatedStyleChangesDoNotAccumulateCommandHistory() {
        val stream = TerminalStreamProcessor()
        val result = stream.process("\u001b[31m\u001b[32m".repeat(10000) + "X").second
        assertEquals("\u001b[32mX\u001b[0m", result)
    }
}
