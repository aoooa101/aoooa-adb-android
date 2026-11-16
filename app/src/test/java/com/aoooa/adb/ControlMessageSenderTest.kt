package com.aoooa.adb

import com.aoooa.adb.control.ControlMessageSender
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ControlMessageSenderTest {
    @Test fun exceptionReportsTypeWithoutMessagePayload() {
        val done = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val sender = ControlMessageSender(
            write = { throw IllegalStateException("private-payload") },
            onFailure = { done.countDown() }, diagnose = { events += it }
        )
        try {
            sender.send(listOf(byteArrayOf(1)))
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertTrue(events.single().contains("write_exception type=IllegalStateException"))
            assertFalse(events.single().contains("private-payload"))
        } finally { sender.close() }
    }

    @Test fun congestionReportsOnlyOnce() {
        val started = CountDownLatch(1)
        val hold = CountDownLatch(1)
        val failures = java.util.concurrent.atomic.AtomicInteger()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val sender = ControlMessageSender(
            write = { started.countDown(); hold.await(); true },
            onFailure = { failures.incrementAndGet(); Unit }, diagnose = { events += it }
        )
        try {
            sender.send(listOf(byteArrayOf(1)))
            assertTrue(started.await(5, TimeUnit.SECONDS))
            repeat(300) { sender.send(listOf(byteArrayOf(2))) }
            assertEquals(1, failures.get())
            assertTrue(events.single().startsWith("queue_full"))
        } finally { hold.countDown(); sender.close() }
    }

    @Test fun explicitCloseDoesNotReportFailure() {
        val failures = java.util.concurrent.atomic.AtomicInteger()
        val sender = ControlMessageSender(write = { false }, onFailure = { failures.incrementAndGet(); Unit })
        sender.close()
        sender.send(listOf(byteArrayOf(1)))
        assertEquals(0, failures.get())
    }

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
