package com.aoooa.adb.control

/** 汇总视频处理阶段计数；所有时间来自本机单调时钟，不计算跨设备时钟差。 */
internal class VideoPipelineStats(private val nowMs: () -> Long) {
    private var received = 0L
    private var keys = 0L
    private var bytes = 0L
    private var inputs = 0L
    private var outputs = 0L
    private var submitted = 0L
    private var rendered = 0L
    private var retries = 0L
    private var reportAt = nowMs()
    private var recoveryAt: Long? = null
    private var recoveryPts: Long? = null

    @Synchronized fun receive(size: Int, key: Boolean) {
        received++
        bytes += size
        if (key) keys++
    }
    @Synchronized fun input() { inputs++ }
    @Synchronized fun output(render: Boolean) { outputs++; if (render) submitted++ }
    @Synchronized fun retry() { retries++ }

    @Synchronized fun beginRecovery(reason: String): String {
        if (recoveryAt == null) recoveryAt = nowMs()
        recoveryPts = null
        return "recovery_begin reason=$reason elapsedMs=${nowMs() - recoveryAt!!}"
    }

    @Synchronized fun keySubmitted(pts: Long): String? {
        val start = recoveryAt ?: return null
        if (recoveryPts != null) return null
        recoveryPts = pts
        return "recovery_key_submitted waitMs=${nowMs() - start} ptsUs=$pts"
    }

    @Synchronized fun rendered(pts: Long): String? {
        rendered++
        val start = recoveryAt ?: return null
        val keyPts = recoveryPts ?: return null
        if (pts < keyPts) return null
        recoveryAt = null
        recoveryPts = null
        return "recovery_rendered elapsedMs=${nowMs() - start} ptsUs=$pts"
    }

    @Synchronized fun summary(): String? {
        val now = nowMs()
        if (now - reportAt < 5000L) return null
        val result = "video_stats intervalMs=${now - reportAt} received=$received keys=$keys bytes=$bytes inputs=$inputs outputs=$outputs submitted=$submitted rendered=$rendered inputRetries=$retries recoveryMs=${recoveryAt?.let { now - it } ?: -1L}"
        received = 0; keys = 0; bytes = 0; inputs = 0; outputs = 0
        submitted = 0; rendered = 0; retries = 0
        reportAt = now
        return result
    }

    @Synchronized fun stop(): String? {
        val start = recoveryAt ?: return null
        recoveryAt = null
        recoveryPts = null
        return "recovery_aborted reason=session_stop elapsedMs=${nowMs() - start}"
    }
}
