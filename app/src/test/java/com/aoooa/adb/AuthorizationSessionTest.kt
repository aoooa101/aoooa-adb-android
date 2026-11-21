package com.aoooa.adb

import com.aoooa.adb.ui.tools.checkedSessionCommand
import org.junit.Assert.*
import org.junit.Test

class AuthorizationSessionTest {
    @Test fun staleSessionNeverStartsCommand() {
        assertNull(checkedSessionCommand({ false }) { error("unexpected execution") })
    }

    @Test fun reconnectDuringCommandInvalidatesBatch() {
        val original = Any()
        var active = original
        assertNull(checkedSessionCommand({ active === original }) { active = Any(); "" })
        assertNull(checkedSessionCommand({ active === original }) { error("unexpected continuation") })
    }

    @Test fun emptyReplyFromSameSessionRemainsEmpty() {
        assertEquals("", checkedSessionCommand({ true }) { "" })
    }
}
