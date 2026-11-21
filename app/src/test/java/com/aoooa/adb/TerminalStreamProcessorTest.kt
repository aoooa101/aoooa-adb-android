package com.aoooa.adb

import com.aoooa.adb.adb.protocol.TerminalSgr
import com.aoooa.adb.adb.protocol.TerminalStreamProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalStreamProcessorTest {
    @Test fun extendedColorsApplyToForegroundAndBackground() {
        val colors = TerminalSgr().applyParameters("38;5;196;48:2::1:2:3")
        assertEquals(0xffff0000.toInt(), colors.foreground)
        assertEquals(0xff010203.toInt(), colors.background)
        assertNull(colors.applyParameters("39").foreground)
        assertNull(colors.applyParameters("49").background)
        assertEquals(colors, colors.applyParameters("38;2;0;256;0"))
        assertEquals(TerminalSgr(), colors.applyParameters("0"))
    }

    @Test fun splitColonSequenceKeepsTextAndStyle() {
        val stream = TerminalStreamProcessor()
        assertEquals("", stream.process("\u001b[38:2::255:").second)
        assertEquals(listOf("\u001b[38:2::255:0:0mX\u001b[0m"), stream.process("0:0mX\n").first)
    }

    @Test fun supplementaryCharacterSurvivesChunkBoundaryAndOverwritesAtomically() {
        val stream = TerminalStreamProcessor()
        val scalar = "\uD83D\uDE00"
        assertEquals("", stream.process(scalar.substring(0, 1)).second)
        assertEquals(scalar + "Z", stream.process(scalar.substring(1) + "Z").second)
        assertEquals(listOf("AZ"), stream.process("\rA\n").first)
    }

    @Test fun directoryMarkerReportsPathWithoutShowingItInOutput() {
        val seen = mutableListOf<String>()
        val stream = TerminalStreamProcessor { seen.add(it) }
        stream.process("\u001b]aoooa-cwd;/sd")
        assertEquals("$ ", stream.process("card\u0007$ ").second)
        assertEquals(listOf("/sdcard"), seen)
    }
}
