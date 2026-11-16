package com.aoooa.adb

import com.aoooa.adb.adb.protocol.TerminalInitializationGate
import org.junit.Assert.*
import org.junit.Test

class TerminalInitializationGateTest {
    @Test fun hidesInitializationEchoAcrossChunksAndRetainsDirectoryMarker() {
        val gate = TerminalInitializationGate()
        assertEquals("", gate.accept("PS1=internal\r\nprompt $ \u001b]aoooa-"))
        val output = gate.accept("cwd;/sdcard/My 文件\u0007prompt $ ")
        assertEquals("\u001b]aoooa-cwd;/sdcard/My 文件\u0007prompt $ ", output)
        assertEquals("PS1=user-command\n", gate.accept("PS1=user-command\n"))
    }

    @Test fun supportsEscapeBackslashTerminator() {
        val gate = TerminalInitializationGate()
        assertEquals("\u001b]aoooa-cwd;/\u001b\\prompt", gate.accept("echo\u001b]aoooa-cwd;/\u001b\\prompt"))
    }

    @Test fun fallsBackWithoutDiscardingTextAfterDeadline() {
        var time = 0L
        val gate = TerminalInitializationGate(now = { time })
        assertEquals("", gate.accept("startup-error"))
        time = 6_000_000_000L
        assertEquals("startup-error\nnext", gate.accept("\nnext"))
        assertEquals("later", gate.accept("later"))
    }

    @Test fun oversizedInitializationIsReleased() {
        val gate = TerminalInitializationGate()
        val text = "x".repeat(65536)
        assertEquals(text, gate.accept(text))
    }
}
