package com.aoooa.adb.util

/**
 * 被控端 /proc/meminfo 解析。
 */
object RamInfo {

    /**
     * 解析 MemTotal 与 MemAvailable，返回 Pair(已用字节, 总字节)。
     *
     * @param memInfo cat /proc/meminfo 的原始输出
     * @return 无法解析出 MemTotal 时返回 null
     */
    fun parseMemInfo(memInfo: String): Pair<Long, Long>? {
        var totalKb = 0L
        var availKb = 0L
        for (line in memInfo.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("MemTotal:")) {
                totalKb = Regex("\\d+").find(trimmed)?.value?.toLongOrNull() ?: 0L
            } else if (trimmed.startsWith("MemAvailable:")) {
                availKb = Regex("\\d+").find(trimmed)?.value?.toLongOrNull() ?: 0L
            }
        }
        if (totalKb <= 0) return null
        val usedKb = (totalKb - availKb).coerceAtLeast(0L)
        return Pair(usedKb * 1024L, totalKb * 1024L)
    }
}
