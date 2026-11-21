package com.aoooa.adb

import com.aoooa.adb.adb.TcpEndpoint
import com.aoooa.adb.adb.TcpSessionHistory
import org.junit.Assert.*
import org.junit.Test

class TcpSessionHistoryTest {
    @Test fun retainsFiveLatestSuccessfulAddresses() {
        val history = TcpSessionHistory()
        assertTrue(history.recent.value.isEmpty())
        (1..7).forEach { history.record(TcpEndpoint("test", it)) }
        assertEquals(listOf(7, 6, 5, 4, 3), history.recent.value.map { it.port })
        assertTrue(TcpSessionHistory().recent.value.isEmpty())
    }

    @Test fun reconnectMovesAddressToFrontWithoutDuplicate() {
        val history = TcpSessionHistory()
        history.record(TcpEndpoint("Device", 5555))
        history.record(TcpEndpoint("device", 5556))
        history.record(TcpEndpoint("device", 5555))
        assertEquals(listOf("device:5555", "device:5556"), history.recent.value.map { it.display })
    }

    @Test fun parsesDefaultExplicitAndBracketedAddresses() {
        assertEquals(TcpEndpoint("127.0.0.1", 5555), TcpEndpoint.parse(" 127.0.0.1 "))
        assertEquals(TcpEndpoint("phone.local", 37001), TcpEndpoint.parse("phone.local:37001"))
        val ipv6 = TcpEndpoint("::1", 5555)
        assertEquals(ipv6, TcpEndpoint.parse(ipv6.display))
    }

    @Test fun malformedOrOutOfRangePortsAreRejected() {
        for (value in listOf("", "host:", "host:bad", "host:0", "host:65536", "host:5555:9")) {
            assertNull(value, TcpEndpoint.parse(value))
        }
    }
}
