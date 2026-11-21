package com.aoooa.adb

import com.aoooa.adb.adb.AdbConnection
import com.aoooa.adb.adb.AdbPacket
import com.aoooa.adb.adb.protocol.ShellProtocolV2
import com.aoooa.adb.bridge.Channel
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class InteractiveCompatibilityTest {
    private fun verify(banner: String, modern: Boolean) {
        val sent = LinkedBlockingQueue<AdbPacket>()
        val output = LinkedBlockingQueue<String>()
        lateinit var conn: AdbConnection
        val peer = object : Channel {
            override fun send(data: ByteArray): Boolean {
                val packet = requireNotNull(AdbPacket.tryParse(data)).first
                if (packet.command == AdbPacket.CNXN) {
                    assertEquals("host::features=shell_v2", packet.payload.decodeToString())
                    conn.onData(AdbPacket(AdbPacket.CNXN, 0x01000000, 4096, banner.toByteArray()).toBytes())
                } else sent.offer(packet)
                return true
            }
            override fun close() {}
        }
        conn = AdbConnection(peer)
        fun next() = requireNotNull(sent.poll(3, TimeUnit.SECONDS))
        try {
            assertTrue(conn.connect())
            assertTrue(conn.openInteractiveShell("pwd\n") { output.offer(it) })
            val open = next()
            assertEquals(AdbPacket.OPEN, open.command)
            assertEquals(if (modern) "shell,v2,pty,TERM=xterm-256color:\u0000" else "shell:\u0000", open.payload.decodeToString())
            fun inject(command: Int, bytes: ByteArray = byteArrayOf()) = conn.onData(AdbPacket(command, 42, open.arg0, bytes).toBytes())
            inject(AdbPacket.OKAY)
            val input = next()
            assertEquals(AdbPacket.WRTE, input.command)
            val init = "pwd\n".toByteArray()
            assertArrayEquals(if (modern) ShellProtocolV2.buildStdin(init) else init, input.payload)
            inject(AdbPacket.OKAY)
            if (modern) {
                assertArrayEquals(ShellProtocolV2.buildWindowSize(24, 80), next().payload)
                inject(AdbPacket.OKAY)
            } else assertFalse(conn.sendInteractiveWindowSize())
            val text = "中文\n".toByteArray()
            if (modern) {
                inject(AdbPacket.WRTE, ShellProtocolV2.buildFrame(ShellProtocolV2.ID_STDOUT, text))
            } else {
                inject(AdbPacket.WRTE, text.copyOfRange(0, 1))
                assertEquals(AdbPacket.OKAY, next().command)
                assertTrue(output.isEmpty())
                inject(AdbPacket.WRTE, text.copyOfRange(1, text.size))
            }
            assertEquals("中文\n", output.poll(3, TimeUnit.SECONDS))
            assertEquals(AdbPacket.OKAY, next().command)
        } finally { conn.disconnect() }
    }

    @Test fun missingFeaturesUsesLegacyShell() = verify("device::ro.product.model=test", false)
    @Test fun unknownFeaturesUseLegacyShell() = verify("device::features=vendor_only\u0000", false)
    @Test fun advertisedShellV2UsesFramedShell() = verify("device::features=vendor_only,shell_v2\u0000", true)
}
