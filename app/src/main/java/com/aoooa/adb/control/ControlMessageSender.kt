package com.aoooa.adb.control

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** 串行发送一个投屏会话的控制消息；拥塞或发送失败后终止该发送器。 */
internal class ControlMessageSender(
    private val write: (ByteArray) -> Boolean,
    private val onFailure: () -> Unit,
    private val diagnose: (String) -> Unit = {}
) {
    private val closed = AtomicBoolean(false)
    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(256),
        { task -> Thread(task, "scrcpy-control-writer").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )

    fun send(messages: List<ByteArray>) {
        if (closed.get()) return
        try {
            executor.execute {
                if (!closed.get()) {
                    try {
                        for (message in messages) {
                            if (closed.get()) break
                            if (!write(message)) {
                                fail("write_failed")
                                break
                            }
                        }
                    } catch (e: Exception) {
                        fail("write_exception type=${e.javaClass.simpleName}")
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            fail("queue_full")
        }
    }

    private fun fail(reason: String) {
        if (closed.compareAndSet(false, true)) {
            val pending = executor.shutdownNow().size
            try {
                diagnose("$reason pending=$pending")
            } finally {
                onFailure()
            }
        }
    }

    fun close() {
        closed.set(true)
        executor.shutdownNow()
    }
}
