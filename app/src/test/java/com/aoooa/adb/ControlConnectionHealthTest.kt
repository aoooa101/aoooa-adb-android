package com.aoooa.adb

import com.aoooa.adb.control.ControlConnectionHealth
import org.junit.Assert.*
import org.junit.Test

class ControlConnectionHealthTest {
    @Test fun disconnectedTransportHasPriority() {
        listOf(false, true).forEach { video ->
            listOf(false, true).forEach { control ->
                assertEquals("disconnected", ControlConnectionHealth.error(false, video, control))
            }
        }
    }

    @Test fun missingVideoHasPriorityOverMissingControl() {
        assertEquals("video_closed", ControlConnectionHealth.error(true, false, false))
        assertEquals("video_closed", ControlConnectionHealth.error(true, false, true))
    }

    @Test fun missingControlIsNotHealthy() {
        assertEquals("control_closed", ControlConnectionHealth.error(true, true, false))
    }

    @Test fun allChannelsReadyIsHealthy() {
        assertNull(ControlConnectionHealth.error(true, true, true))
    }
}
