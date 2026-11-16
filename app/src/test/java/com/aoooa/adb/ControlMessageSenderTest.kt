package com.aoooa.adb

import com.aoooa.adb.control.ControlMessageSender
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ControlMessageSenderTest {
    @Test
    fun messagesKeepSubmissionOrder() {
        val received = Collections.synchronizedList(mutableListOf<Int>())
        val done = CountDownLatch(40)
        val sender = ControlMessageSender(
            write = { received.add(it[0].toInt()); done.countDown(); true },
            onFailure = { fail("Unexpected failure") }
        )
        try {
            repeat(20) { sender.send(listOf(byteArrayOf((it * 2).toByte()), byteArrayOf((it * 2 + 1).toByte()))) }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals((0 until 40).toList(), received.toList())
        } finally {
            sender.close()
        }
    }

    @Test
    fun failedWriteStopsFollowingMessages() {
        val failed = CountDownLatch(1)
        val received = Collections.synchronizedList(mutableListOf<Int>())
        val sender = ControlMessageSender(
            write = { received.add(it[0].toInt()); false },
            onFailure = { failed.countDown() }
        )
        try {
            sender.send(listOf(byteArrayOf(1), byteArrayOf(2)))
            assertTrue(failed.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(1), received.toList())
        } finally {
            sender.close()
        }
    }
}
