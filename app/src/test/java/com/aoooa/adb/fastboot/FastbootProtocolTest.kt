package com.aoooa.adb.fastboot

import java.lang.reflect.Modifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FastbootProtocolTest {
    private fun reply(status: String, payload: String) = FastbootClient.FbResult(status, payload, "")

    @Test fun versionProbeRequiresSuccessfulNonemptyReply() {
        assertTrue(reply("OKAY", "0.4").confirmsVersion)
        assertTrue(reply("OKAY", "0.5").confirmsVersion)
        for (status in listOf("FAIL", "DATA", "TIMEOUT", "IO_ERROR")) {
            assertFalse(reply(status, "0.4").confirmsVersion)
        }
        assertFalse(reply("OKAY", " ").confirmsVersion)
    }

    @Test fun downloadRequiresExactLengthAndEightHexDigits() {
        assertTrue(reply("DATA", "0000000A").acceptsDownload(10))
        assertTrue(reply("DATA", "ffffffff").acceptsDownload(0xffffffffL))
        for (payload in listOf("00000009", "0000000b", "a", "00000000a", "+000000a", "0000000g")) {
            assertFalse(reply("DATA", payload).acceptsDownload(10))
        }
        assertFalse(reply("OKAY", "0000000a").acceptsDownload(10))
        assertFalse(reply("DATA", "00000000").acceptsDownload(0))
        assertFalse(reply("DATA", "00000000").acceptsDownload(0x100000000L))
    }

    @Test fun commandWriteMustBeComplete() {
        assertTrue(FastbootClient.fullCommandWrite(14, 14))
        for (sent in listOf(-1, 0, 13, 15)) assertFalse(FastbootClient.fullCommandWrite(14, sent))
    }

    @Test fun queryProbeAndWholeFlashUseSameInstanceMonitor() {
        for (name in listOf("execute", "verifyProtocol", "flashPartitionImage")) {
            val method = FastbootClient::class.java.declaredMethods.single { it.name == name }
            assertTrue(name, Modifier.isSynchronized(method.modifiers))
            assertFalse(name, Modifier.isStatic(method.modifiers))
        }
    }
}
