package com.aoooa.adb

import com.aoooa.adb.adb.proxy.SmartSocketProtocol
import com.aoooa.adb.adb.proxy.ProxyClientSession
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class SmartSocketProtocolTest {
    @Test fun readsFragmentedRequests() {
        val bytes = "000chost:version".toByteArray()
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 1))
        }
        assertEquals("host:version", SmartSocketProtocol.readRequest(input))
        assertNull(SmartSocketProtocol.readRequest(input))
    }

    @Test fun replyLengthCountsUtf8Bytes() {
        val output = ByteArrayOutputStream()
        SmartSocketProtocol.reply(output, "中")
        assertArrayEquals("OKAY0003中".toByteArray(Charsets.UTF_8), output.toByteArray())
    }

    @Test fun rejectsInvalidAndTruncatedHeaders() {
        listOf("zzzz", "0000", "0004ab", "00").forEach { value ->
            try {
                SmartSocketProtocol.readRequest(ByteArrayInputStream(value.toByteArray()))
                fail("accepted invalid request: $value")
            } catch (_: IllegalArgumentException) {
            } catch (_: java.io.EOFException) {
            }
        }
    }

    @Test fun selectorsPreserveSerialAndTransportIdentity() {
        assertEquals("serial:127.0.0.1:5555", ProxyClientSession.transportSelector("host:tport:serial:127.0.0.1:5555"))
        assertEquals("id:42", ProxyClientSession.transportSelector("host:transport-id:42"))
        assertEquals("usb", ProxyClientSession.transportSelector("host:transport-usb"))
        assertNull(ProxyClientSession.transportSelector("host:devices"))
    }

    @Test fun validatesAddressesAndBinaryServices() {
        assertEquals("::1" to 5555, ProxyClientSession.parseAddress("[::1]:5555"))
        assertEquals("localhost" to 5555, ProxyClientSession.parseAddress("localhost"))
        listOf("", "x:0", "x:65536", "x:bad", "[::1]bad").forEach {
            assertNull(it, ProxyClientSession.parseAddress(it))
        }
        assertTrue(ProxyClientSession.isDeviceService("shell,v2,raw:cat"))
        assertTrue(ProxyClientSession.isDeviceService("sync:"))
        assertTrue(ProxyClientSession.isDeviceService("abb_exec:package\u0000install-create"))
        assertFalse(ProxyClientSession.isDeviceService("shell:echo\u0000bad"))
        assertFalse(ProxyClientSession.isDeviceService("reverse:forward:tcp:1;tcp:2"))
    }
}
