package com.aoooa.adb

import com.aoooa.adb.adb.TcpEndpoint
import com.aoooa.adb.adb.TcpSessionHistory
import org.junit.Assert.*
import org.junit.Test

class TcpSessionHistoryTest {
    @Test fun draftLimitAcceptsBoundaryAndRejectsOversizedInput() {
        assertTrue(com.aoooa.adb.ui.connect.acceptsTcpAddressDraft("a".repeat(100)))
        assertFalse(com.aoooa.adb.ui.connect.acceptsTcpAddressDraft("a".repeat(101)))
    }

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
        assertEquals(TcpEndpoint("192.168.1.2", 37001), TcpEndpoint.parse("192.168.1.2:37001"))
        val ipv6 = TcpEndpoint("::1", 5555)
        assertEquals(ipv6, TcpEndpoint.parse(ipv6.display))
        assertEquals(ipv6, TcpEndpoint.parse("::1"))
        assertEquals(TcpEndpoint("::ffff:192.0.2.1", 1), TcpEndpoint.parse("[::ffff:192.0.2.1]:1"))
        assertEquals(TcpEndpoint("fe80::1%wlan0", 65535), TcpEndpoint.parse("[fe80::1%wlan0]:65535"))
    }

    @Test fun malformedOrOutOfRangePortsAreRejected() {
        for (value in listOf("192.168.1.2:", "192.168.1.2:bad", "192.168.1.2:0", "192.168.1.2:65536", "192.168.1.2:+1")) {
            assertNull(value, TcpEndpoint.parse(value))
        }
    }

    @Test fun rejectsMalformedIpRegardlessOfPort() {
        val invalid = listOf("", "phone.local", "192.168.1", "256.0.0.1", "192.168.01.1", "1.2.3.4/24",
            "[::g]:5555", "[1::2::3]", "[1:2:3:4:5:6:7:8::]", "[::ffff:256.0.0.1]", "[192.0.2.1]", "fe80::1%")
        for (value in invalid) assertNull(value, TcpEndpoint.parse(value))
    }
}
