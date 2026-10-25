package com.aoooa.adb.log

/**
 * 日志文本匹配规则。
 *
 * 规则串支持逗号或竖线分隔多个候选（如 "tag1|tag2"），任一命中即视为匹配；
 * 正则模式下编译失败会退化为普通包含匹配。
 */
object LogRules {

    /** 包含匹配：任一候选命中即为 true；规则为空视为不过滤。 */
    fun matchText(text: String, rule: String, asRegex: Boolean): Boolean {
        val rawRule = rule.trim()
        if (rawRule.isEmpty()) return true
        val parts = rawRule.split(',', '|').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return true
        return parts.any { part ->
            if (asRegex) {
                try {
                    Regex(part, setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)).containsMatchIn(text)
                } catch (_: Exception) {
                    text.contains(part, ignoreCase = true)
                }
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
