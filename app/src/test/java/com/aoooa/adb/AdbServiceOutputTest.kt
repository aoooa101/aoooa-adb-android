package com.aoooa.adb

import com.aoooa.adb.adb.AdbServiceOutput
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AdbServiceOutputTest {
    @Test fun closeKeepsEarlierOutput() {
        val output = AdbServiceOutput()
        output.offer(byteArrayOf(1))
        output.offer(byteArrayOf(2))
        output.finish()
        assertArrayEquals(byteArrayOf(1), output.poll(0))
        assertArrayEquals(byteArrayOf(2), output.poll(0))
        assertNull(output.poll(0))
        assertTrue(output.isFinished())
    }

    @Test fun fullQueueStillTerminatesAfterData() {
        val output = AdbServiceOutput(1)
        output.offer(byteArrayOf(1))
        output.finish()
        assertArrayEquals(byteArrayOf(1), output.poll(0))
        assertTrue(output.isFinished())
    }

    @Test fun emptyPacketRemainsAvailableForAcknowledgement() {
        val output = AdbServiceOutput()
        output.offer(byteArrayOf())
        val packet = output.poll(0)
        assertNotNull(packet)
        assertEquals(0, packet!!.size)
        assertFalse(output.isFinished())
    }

    @Test fun overflowFailsInsteadOfReturningTruncatedSuccess() {
        val output = AdbServiceOutput(1)
        output.offer(byteArrayOf(1))
        output.offer(byteArrayOf(2))
        assertThrows(IOException::class.java) { output.poll(0) }
    }

    @Test fun independentOutputsDoNotShareDataOrClosure() {
        val a = AdbServiceOutput()
        val b = AdbServiceOutput()
        a.finish()
        b.offer(byteArrayOf(9))
        assertNull(a.poll(0))
        assertTrue(a.isFinished())
        assertArrayEquals(byteArrayOf(9), b.poll(0))
        assertFalse(b.isFinished())
    }
}
