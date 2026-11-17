package com.aoooa.adb.adb

import java.util.concurrent.TimeUnit

/** 在同一个单调时钟预算内分段等待发送额度，慢等待通知不延长预算。 */
internal object WriteCreditWait {
    fun await(
        timeoutMs: Long,
        slowAfterMs: Long,
        acquire: (Long) -> Boolean,
        onSlow: () -> Unit,
        nowNs: () -> Long = System::nanoTime
    ): Boolean {
        val budget = TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
        if (slowAfterMs <= 0 || slowAfterMs >= timeoutMs) return acquire(budget)
        val start = nowNs()
        if (acquire(TimeUnit.MILLISECONDS.toNanos(slowAfterMs))) return true
        onSlow()
        val remaining = (budget - (nowNs() - start)).coerceAtLeast(0L)
        return acquire(remaining)
    }
}
