package com.aoooa.adb

import com.aoooa.adb.control.AudioPcmWriter
import org.junit.Assert.*
import org.junit.Test

class AudioPcmWriterTest {
    @Test fun shortWritesAdvanceOffsets() {
        val offsets = mutableListOf<Int>()
        AudioPcmWriter.write(5, { true }, { 0L }, {}, { offset, remaining ->
            offsets.add(offset)
            minOf(2, remaining)
        })
        assertEquals(listOf(0, 2, 4), offsets)
    }

    @Test fun zeroWritesTimeout() {
        var time = 0L
        val error = assertThrows(IllegalStateException::class.java) {
            AudioPcmWriter.write(5, { true }, { time }, { time += 10 }, { _, _ -> 0 }, 20)
        }
        assertEquals("audio_track_write_timeout", error.message)
    }

    @Test fun continuousShortWritesAlsoRespectDeadline() {
        var time = 0L
        var calls = 0
        assertThrows(IllegalStateException::class.java) {
            AudioPcmWriter.write(100, { true }, { time }, {}, { _, _ ->
                calls++
                time += 10
                1
            }, 20)
        }
        assertEquals(2, calls)
    }

    @Test fun stopDuringWaitDoesNotWriteAgain() {
        var active = true
        var calls = 0
        AudioPcmWriter.write(5, { active }, { 0L }, { active = false }, { _, _ -> calls++; 0 })
        assertEquals(1, calls)
    }

    @Test fun stoppedWriterDoesNotCallTransport() {
        AudioPcmWriter.write(5, { false }, { 0L }, { fail() }, { _, _ -> fail(); 0 })
    }

    @Test fun invalidWriteCountsFail() {
        listOf(-6, 6).forEach { result ->
            assertThrows(IllegalStateException::class.java) {
                AudioPcmWriter.write(5, { true }, { 0L }, {}, { _, _ -> result })
            }
        }
    }

    @Test fun emptyPayloadDoesNotCallTransport() {
        AudioPcmWriter.write(0, { true }, { 0L }, { fail() }, { _, _ -> fail(); 0 })
    }
}
