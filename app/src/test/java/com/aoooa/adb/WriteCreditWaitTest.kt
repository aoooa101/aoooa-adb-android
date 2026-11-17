package com.aoooa.adb

import com.aoooa.adb.adb.WriteCreditWait
import org.junit.Assert.*
import org.junit.Test

class WriteCreditWaitTest {
    @Test fun immediateCreditDoesNotReportSlowWait() {
        var calls = 0
        assertTrue(WriteCreditWait.await(10000, 5000,
            { calls++; true }, { fail("Unexpected slow event") }, { 0L }))
        assertEquals(1, calls)
    }

    @Test fun slowCreditUsesRemainingBudgetIncludingDiagnosticTime() {
        var now = 0L
        var warnings = 0
        val waits = mutableListOf<Long>()
        val result = WriteCreditWait.await(10000, 5000, {
            waits.add(it)
            if (waits.size == 1) { now += it; false } else true
        }, { warnings++; now += 200_000_000L }, { now })
        assertTrue(result)
        assertEquals(listOf(5_000_000_000L, 4_800_000_000L), waits)
        assertEquals(1, warnings)
    }

    @Test fun bothStagesCanTimeout() {
        var now = 0L
        var warnings = 0
        assertFalse(WriteCreditWait.await(10000, 5000,
            { now += it; false }, { warnings++ }, { now }))
        assertEquals(10_000_000_000L, now)
        assertEquals(1, warnings)
    }

    @Test fun disabledWarningKeepsSingleWait() {
        val waits = mutableListOf<Long>()
        assertFalse(WriteCreditWait.await(30000, 0,
            { waits.add(it); false }, { fail() }, { 0L }))
        assertEquals(listOf(30_000_000_000L), waits)
    }

    @Test fun elapsedBudgetNeverBecomesNegative() {
        var now = 0L
        val waits = mutableListOf<Long>()
        WriteCreditWait.await(10000, 5000, { waits.add(it); false },
            { now = 11_000_000_000L }, { now })
        assertEquals(0L, waits.last())
    }

    @Test fun interruptionPropagatesToConnectionHandler() {
        assertThrows(InterruptedException::class.java) {
            WriteCreditWait.await(10000, 5000,
                { throw InterruptedException() }, { fail() }, { 0L })
        }
    }
}
