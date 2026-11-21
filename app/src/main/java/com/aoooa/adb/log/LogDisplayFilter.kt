package com.aoooa.adb.log

internal object LogDisplayFilter {
    private val searchRegexCache = SearchRegexCache()

    fun matches(item: LogLine, levelEnabled: Boolean, type: LogTypeFilter, asRegex: Boolean,
        tagInclude: String, tagExclude: String, keywordInclude: String, keywordExclude: String, query: String): Boolean {
        // 1) 级别多选
        if (item.level.isNotBlank() && item.time.isNotBlank() && !levelEnabled) {
            return false
        }

        // 2) 类型快筛
        when (type) {
            LogTypeFilter.ALL -> Unit
            LogTypeFilter.ERRORS -> {
                if (item.kind != LogKind.ERROR && item.kind != LogKind.CRASH &&
                    item.kind != LogKind.STACK && item.kind != LogKind.ANR &&
                    !item.level.uppercase().let { it == "E" || it == "F" || it == "W" }
                ) return false
            }
            LogTypeFilter.CRASH_STACK -> {
                if (item.kind != LogKind.CRASH && item.kind != LogKind.STACK) return false
            }
            LogTypeFilter.ANR -> {
                if (item.kind != LogKind.ANR) return false
            }
            LogTypeFilter.SYSTEM -> {
                if (item.kind != LogKind.SYSTEM && !LogParser.reSystemTags.any { item.tag.equals(it, true) }) return false
            }
        }

        val tagText = item.tag
        val full = item.raw

        // 3) Tag 包含 / 排除
        if (tagInclude.isNotBlank() && !LogRules.matchText(tagText.ifBlank { full }, tagInclude, asRegex)) {
            return false
        }
        if (LogRules.matchExclude(tagText.ifBlank { full }, tagExclude, asRegex)) {
            return false
        }

        // 4) 关键词包含 / 排除
        if (keywordInclude.isNotBlank() && !LogRules.matchText(full, keywordInclude, asRegex)) {
            return false
        }
        if (LogRules.matchExclude(full, keywordExclude, asRegex)) {
            return false
        }

        // 5) 搜索框
        val q = query.trim()
        if (q.isNotEmpty()) {
            val ok = if (asRegex) {
                // 搜索结果正则按关键词缓存：逐行编译会在日志量大时造成明显卡顿
                val regex = searchRegexCache.getOrCompile(q)
                if (regex != null) regex.containsMatchIn(full) else full.contains(q, ignoreCase = true)
            } else {
                full.contains(q, ignoreCase = true)
            }
            if (!ok) return false
        }
        return true
    }

    private class SearchRegexCache {
        private var key: String = ""
        private var regex: Regex? = null
        private var reportedKey: String = ""

        @Synchronized
        fun getOrCompile(keyword: String): Regex? {
            if (keyword == key) return regex
            key = keyword
            regex = try {
                Regex(keyword, RegexOption.IGNORE_CASE)
            } catch (e: Exception) {
                if (reportedKey != keyword) {
                    reportedKey = keyword
                    com.aoooa.adb.AdbManager.debugLog(
                        "[LogManager] 搜索词不是合法正则，已按普通文本匹配: ${e.javaClass.simpleName}: ${e.message}"
                    )
                }
                null
            }
            return regex
        }
    }
}
