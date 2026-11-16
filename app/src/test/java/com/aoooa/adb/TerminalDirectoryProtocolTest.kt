package com.aoooa.adb

import com.aoooa.adb.adb.protocol.TerminalDirectoryProtocol
import org.junit.Assert.*
import org.junit.Test

class TerminalDirectoryProtocolTest {
    @Test fun acceptsAbsoluteDirectoriesAsData() {
        listOf("/", "/sdcard/My 文件", "/a'quote", "/a;command").forEach { path ->
            assertEquals(path, TerminalDirectoryProtocol.directory("aoooa-cwd;$path"))
        }
    }

    @Test fun rejectsUnrelatedRelativeAndControlData() {
        listOf("0;title", "aoooa-cwd;relative", "aoooa-cwd;", "aoooa-cwd;/a\nnext",
            "aoooa-cwd;/a\u0007", "aoooa-cwd;/a\u001b", "aoooa-cwd;/a\u007f",
            "aoooa-cwd;/" + "a".repeat(4096)).forEach { value ->
            assertNull(value, TerminalDirectoryProtocol.directory(value))
        }
    }

    @Test fun initializationPreservesPromptAndDefersDirectoryExpansion() {
        val initialization = TerminalDirectoryProtocol.initialization
        assertTrue(initialization.contains("$(printf"))
        assertTrue(initialization.contains("\"\$PWD\""))
        assertTrue(initialization.contains("\$PS1"))
        assertTrue(initialization.endsWith("export PS1\n"))
    }
}
