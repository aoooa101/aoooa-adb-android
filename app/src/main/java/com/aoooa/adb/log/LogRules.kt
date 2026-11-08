package com.aoooa.adb.log

/**
 * 日志文本匹配规则。
 *
 * 规则串支持逗号或竖线分隔多个候选（如 "tag1|tag2"），任一命中即视为匹配；
 * 正则模式下编译失败会退化为普通包含匹配。
 *
 * 规则串在过滤时会被逐行反复使用，因此候选拆分结果与编译后的正则会按规则串缓存，
 * 避免在每行日志上重复解析与编译。
 */
object LogRules {

    /** 缓存条目上限，规则串数量实际很少，该值仅用于防止异常输入撑爆内存。 */
    private const val MAX_CACHE_ENTRIES = 32

    /** 规则串 -> 拆分后的候选列表。 */
    private val partsCache = object : LinkedHashMap<String, List<String>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    /** 候选串 -> 编译后的正则；值为 null 表示该候选不是合法正则，需退化为包含匹配。 */
    private val regexCache = object : LinkedHashMap<String, Regex?>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Regex?>): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    /** 已告警过的非法正则，避免同一规则在每行日志上重复写日志。 */
    private val invalidRegexReported = HashSet<String>()

    /** 拆分规则串为候选列表，结果按原始规则串缓存。 */
    private fun splitParts(rawRule: String): List<String> {
        synchronized(partsCache) {
            partsCache[rawRule]?.let { return it }
        }
        val parts = rawRule.split(',', '|').map { it.trim() }.filter { it.isNotEmpty() }
        synchronized(partsCache) {
            partsCache[rawRule] = parts
        }
        return parts
    }

    /** 取候选对应的正则；非法正则返回 null，并按候选只告警一次。 */
    private fun regexOf(part: String): Regex? {
        synchronized(regexCache) {
            if (regexCache.containsKey(part)) return regexCache[part]
        }
        val compiled = try {
            Regex(part, setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
        } catch (e: Exception) {
            val shouldReport = synchronized(invalidRegexReported) { invalidRegexReported.add(part) }
            if (shouldReport) {
                com.aoooa.adb.AdbManager.debugLog(
                    "[LogRules] 过滤规则不是合法正则，已按普通文本匹配: \"$part\" (${e.javaClass.simpleName}: ${e.message})"
                )
            }
            null
        }
        synchronized(regexCache) {
            regexCache[part] = compiled
        }
        return compiled
    }

    /** 包含匹配：任一候选命中即为 true；规则为空视为不过滤。 */
    fun matchText(text: String, rule: String, asRegex: Boolean): Boolean {
        val rawRule = rule.trim()
        if (rawRule.isEmpty()) return true
        val parts = splitParts(rawRule)
        if (parts.isEmpty()) return true
        return parts.any { part ->
            if (asRegex) {
                val regex = regexOf(part)
                if (regex != null) regex.containsMatchIn(text) else text.contains(part, ignoreCase = true)
            } else {
                text.contains(part, ignoreCase = true)
            }
        }
    }

    /** 排除匹配：规则为空视为不排除。 */
    fun matchExclude(text: String, rule: String, asRegex: Boolean): Boolean {
        val rawRule = rule.trim()
        if (rawRule.isEmpty()) return false
        return matchText(text, rawRule, asRegex)
    }
}
