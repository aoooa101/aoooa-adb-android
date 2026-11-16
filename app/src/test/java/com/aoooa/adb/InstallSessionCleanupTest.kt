package com.aoooa.adb

import com.aoooa.adb.adb.InstallSessionCleanup
import org.junit.Assert.*
import org.junit.Test

class InstallSessionCleanupTest {
    @Test fun completedSessionDoesNotAbandon() {
        InstallSessionCleanup.abandon(null, true, { fail(); "" }, { fail() })
    }

    @Test fun disconnectedSessionDoesNotExecute() {
        val logs = mutableListOf<String>()
        InstallSessionCleanup.abandon(7, false, { fail(); "" }, { logs.add(it) })
        assertTrue(logs.single().contains("disconnected"))
    }

    @Test fun successfulAbandonDoesNotFallback() {
        val commands = mutableListOf<String>()
        InstallSessionCleanup.abandon(7, true, { commands.add(it); "Success" }, { fail() })
        assertEquals(listOf("exec:cmd package install-abandon 7"), commands)
    }

    @Test fun unsuccessfulCommandFallsBackToPm() {
        val commands = mutableListOf<String>()
        InstallSessionCleanup.abandon(7, true, {
            commands.add(it)
            if (commands.size == 1) "Failure" else "Success"
        }, { fail() })
        assertEquals(listOf("exec:cmd package install-abandon 7", "exec:pm install-abandon 7"), commands)
    }

    @Test fun bothFailuresAreReported() {
        val logs = mutableListOf<String>()
        InstallSessionCleanup.abandon(7, true, { "Failure" }, { logs.add(it) })
        assertTrue(logs.single().contains("unconfirmed"))
    }

    @Test fun cleanupExceptionDoesNotEscapeOrExposeMessage() {
        val logs = mutableListOf<String>()
        InstallSessionCleanup.abandon(7, true, { throw IllegalStateException("private-payload") }, { logs.add(it) })
        assertTrue(logs.single().contains("IllegalStateException"))
        assertFalse(logs.single().contains("private-payload"))
    }
}
