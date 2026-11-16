package com.aoooa.adb.adb

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 串行发送单个经典 ADB 流的数据，每个 WRTE 消耗一次 OKAY 额度。 */
internal class AdbStreamWriter(
    private val write: (ByteArray) -> Boolean,
    private val onFailure: () -> Unit,
    private val timeoutMs: Long = 30_000L
) {
    private val closed = AtomicBoolean(false)
    private val credit = Semaphore(0)
    private val pending = java.util.concurrent.ConcurrentHashMap.newKeySet<CompletableFuture<Boolean>>()
    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(256),
        { task -> Thread(task, "adb-stream-writer").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )

    fun ready() {
        if (!closed.get()) credit.release()
    }

    fun send(data: ByteArray): CompletableFuture<Boolean> {
        val result = CompletableFuture<Boolean>()
        if (closed.get()) return result.apply { complete(false) }
        val copy = data.copyOf()
        pending.add(result)
        try {
            executor.execute {
                try {
                    val sent = !closed.get() && credit.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS) &&
                        !closed.get() && write(copy)
                    result.complete(sent)
                    if (!sent) fail()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    result.complete(false)
                    if (!closed.get()) fail()
                } catch (_: Exception) {
                    result.complete(false)
                    fail()
                } finally {
                    pending.remove(result)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            result.complete(false)
            pending.remove(result)
            fail()
        }
        return result
    }

    private fun fail() {
        if (closeOnce()) onFailure()
    }

    private fun closeOnce(): Boolean {
        if (!closed.compareAndSet(false, true)) return false
        executor.shutdownNow()
        pending.forEach { it.complete(false) }
        pending.clear()
        return true
    }

    fun close() {
        closeOnce()
    }
}
