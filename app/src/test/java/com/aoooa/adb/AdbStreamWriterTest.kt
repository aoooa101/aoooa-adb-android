package com.aoooa.adb

import com.aoooa.adb.adb.AdbStreamWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class AdbStreamWriterTest {
    @Test fun eachWriteRequiresItsOwnOkayAndCopiesInput() {
        val sent = LinkedBlockingQueue<ByteArray>()
        val writer = AdbStreamWriter(write = { sent.offer(it) }, onFailure = {})
        try {
            val source = byteArrayOf(1)
            val first = writer.send(source)
            source[0] = 9
            assertNull(sent.poll(100, TimeUnit.MILLISECONDS))
            writer.ready()
            assertTrue(first.get(2, TimeUnit.SECONDS))
            assertArrayEquals(byteArrayOf(1), sent.poll(2, TimeUnit.SECONDS))
            val second = writer.send(byteArrayOf(2))
            assertNull(sent.poll(100, TimeUnit.MILLISECONDS))
            assertFalse(second.isDone)
            writer.ready()
            assertTrue(second.get(2, TimeUnit.SECONDS))
            assertArrayEquals(byteArrayOf(2), sent.poll(2, TimeUnit.SECONDS))
        } finally { writer.close() }
    }

    @Test fun closeCancelsActiveAndQueuedWrites() {
        val writes = AtomicInteger()
        val writer = AdbStreamWriter(write = { writes.incrementAndGet(); true }, onFailure = {})
        val first = writer.send(byteArrayOf(1))
        val second = writer.send(byteArrayOf(2))
        writer.close()
        assertFalse(first.get(2, TimeUnit.SECONDS))
        assertFalse(second.get(2, TimeUnit.SECONDS))
        writer.ready()
        assertFalse(writer.send(byteArrayOf(3)).get(2, TimeUnit.SECONDS))
        assertEquals(0, writes.get())
    }

    @Test fun missingOkayFailsOnceAndCancelsQueue() {
        val failure = CountDownLatch(1)
        val failures = AtomicInteger()
        val writer = AdbStreamWriter(write = { true }, onFailure = {
            failures.incrementAndGet()
            failure.countDown()
        }, timeoutMs = 20)
        try {
            val first = writer.send(byteArrayOf(1))
            val second = writer.send(byteArrayOf(2))
            assertFalse(first.get(2, TimeUnit.SECONDS))
            assertTrue(failure.await(2, TimeUnit.SECONDS))
            assertFalse(second.get(2, TimeUnit.SECONDS))
            assertEquals(1, failures.get())
        } finally { writer.close() }
    }

    @Test fun channelFailureStopsSender() {
        val failure = CountDownLatch(1)
        val writer = AdbStreamWriter(write = { false }, onFailure = { failure.countDown() })
        try {
            writer.ready()
            assertFalse(writer.send(byteArrayOf(1)).get(2, TimeUnit.SECONDS))
            assertTrue(failure.await(2, TimeUnit.SECONDS))
            assertFalse(writer.send(byteArrayOf(2)).get(2, TimeUnit.SECONDS))
        } finally { writer.close() }
    }
}
