package com.aoooa.adb.mcp

import com.aoooa.adb.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 拦截规则类型：
 * EXACT: 精准拦截（整条命令去除首尾空格后完全匹配）
 * VERB: 命令拦截（拆解串联命令后按执行动词匹配）
 */
enum class McpRuleType {
    EXACT,
    VERB
}

/**
 * 安全拦截规则模型。
 * pattern: 拦截目标文本
 * description: 附加说明(可选)，拦截触发时返回给 AI
 */
data class McpRule(
    val id: String = UUID.randomUUID().toString(),
    val type: McpRuleType = McpRuleType.VERB,
    val pattern: String,
    val description: String = ""
)

/**
 * 规则引擎检测结果。
 */
data class RuleCheckResult(
    val allowed: Boolean,
    val matchedRule: McpRule? = null,
    val reason: String = ""
)

/**
 * MCP 安全规则引擎：
 * 1. 负责命令拆分与串联操作符（&&, ||, ;, |, \n, &）解包；
 * 2. 剥离环境变量前缀赋值与重定向干扰，精准定位子命令动词；
 * 3. 执行精准匹配与动词匹配检测，并输出附加说明。
 */
object McpRuleEngine {

    /** 默认预设安全拦截规则列表（不作默认预设，完全由用户按需添加） */
    val defaultRules: List<McpRule> = emptyList()

    /** 从本地存储加载规则列表 */
    fun loadRules(): List<McpRule> {
        val json = Prefs.mcpRulesJson
        if (json.isBlank()) {
            return emptyList()
        }
        return try {
            val array = JSONArray(json)
            val list = mutableListOf<McpRule>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val typeStr = obj.optString("type", McpRuleType.VERB.name)
                val ruleType = try {
                    McpRuleType.valueOf(typeStr)
                } catch (_: Exception) {
                    McpRuleType.VERB
                }
                list.add(
                    McpRule(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        type = ruleType,
                        pattern = obj.optString("pattern", ""),
                        description = obj.optString("description", "")
                    )
                )
            }
            if (list.isEmpty()) defaultRules else list
        } catch (_: Exception) {
            defaultRules
        }
    }

    /** 保存规则列表至本地存储 */
    fun saveRules(rules: List<McpRule>) {
        try {
            val array = JSONArray()
            for (rule in rules) {
                val obj = JSONObject().apply {
                    put("id", rule.id)
                    put("type", rule.type.name)
                    put("pattern", rule.pattern)
                    put("description", rule.description)
                }
                array.put(obj)
            }
            Prefs.mcpRulesJson = array.toString()
        } catch (_: Exception) {}
    }

    /**
     * 核心检测入口：
     * 先比对精准拦截规则，再拆解串联命令比对动词拦截规则。
     */
    fun check(command: String): RuleCheckResult {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) {
            return RuleCheckResult(allowed = true)
        }

        val rules = loadRules()

        // 1. 精准匹配检测
        for (rule in rules) {
            if (rule.type == McpRuleType.EXACT) {
                if (trimmed.equals(rule.pattern.trim(), ignoreCase = true)) {
                    val desc = rule.description.ifBlank { "触发精准拦截规则" }
                    return RuleCheckResult(
                        allowed = false,
                        matchedRule = rule,
                        reason = desc
                    )
                }
            }
        }

        // 2. 串联命令拆解与动词比对
        val subCommands = splitChainedCommands(trimmed)
        val verbRules = rules.filter { it.type == McpRuleType.VERB }

        for (subCmd in subCommands) {
            val verb = extractCommandVerb(subCmd) ?: continue
            for (rule in verbRules) {
                val target = rule.pattern.trim()
                if (target.isNotBlank() && verb.equals(target, ignoreCase = true)) {
                    val desc = rule.description.ifBlank { "触发命令拦截规则: $target" }
                    return RuleCheckResult(
                        allowed = false,
                        matchedRule = rule,
                        reason = desc
                    )
                }
            }
        }

        return RuleCheckResult(allowed = true)
    }

    /**
     * 串联命令安全切分：
     * 识别 &&, ||, ;, \n, |, & 连接符；保留引号内部字符不被切断。
     */
    fun splitChainedCommands(raw: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inSingleQuote = false
        var inDoubleQuote = false
        var i = 0
        val len = raw.length

        while (i < len) {
            val c = raw[i]

            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote
                current.append(c)
                i++
                continue
            }
            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote
                current.append(c)
                i++
                continue
            }

            if (!inSingleQuote && !inDoubleQuote) {
                // 判断双字符串联符: && 或 ||
                if (i + 1 < len) {
                    val next = raw[i + 1]
                    if ((c == '&' && next == '&') || (c == '|' && next == '|')) {
                        val part = current.toString().trim()
                        if (part.isNotEmpty()) result.add(part)
                        current.clear()
                        i += 2
                        continue
                    }
                }

                // 判断单字符串联符: ;, \n, |, &
                if (c == ';' || c == '\n' || c == '|' || c == '&') {
                    val part = current.toString().trim()
                    if (part.isNotEmpty()) result.add(part)
                    current.clear()
                    i++
                    continue
                }
            }

            current.append(c)
            i++
        }

        val lastPart = current.toString().trim()
        if (lastPart.isNotEmpty()) {
            result.add(lastPart)
        }

        return result
    }

    /**
     * 提取子命令中真实执行的程序动词：
     * 1. 过滤环境变量临时赋值前缀 (如 A=1 B=2)；
     * 2. 过滤重定向符号 (<, >, 2>&1)；
     * 3. 提取路径 basename 并剥离前置反斜杠与引号。
     */
    fun extractCommandVerb(subCmd: String): String? {
        val tokens = subCmd.trim().split(Regex("\\s+"))
        val envRegex = Regex("^[A-Za-z_][A-Za-z0-9_]*=.*")

        for (token in tokens) {
            val t = token.trim()
            if (t.isEmpty()) continue

            // 过滤环境变量定义
            if (envRegex.matches(t)) continue

            // 过滤重定向符号
            if (t.startsWith(">") || t.startsWith("<") || t.startsWith("1>") || t.startsWith("2>")) continue

            // 清理前置转义反斜杠与包裹引号
            var cleaned = t.trimStart('\\').trim('\'', '"')

            // 若包含绝对或相对路径，提取末尾程序名
            if (cleaned.contains('/')) {
                cleaned = cleaned.substringAfterLast('/')
            }

            if (cleaned.isNotBlank()) {
                return cleaned
            }
        }
        return null
    }

    /**
     * 构建供 AI 或外部客户端读取的安全规则结构化 JSON 摘要：
     * 1. 字段 key (total, rules, mode, pattern, reason, message) 保持固定英文；
     * 2. 模式代码 (VERB/EXACT) 保持固定机器可读枚举；
     * 3. 模式详细说明与状态提示按 McpStrings 动态输出当前语言。
     */
    fun getRulesSummaryJson(): JSONObject {
        val rules = loadRules()
        val rulesArray = JSONArray()

        for (rule in rules) {
            val ruleObj = JSONObject().apply {
                put("mode", rule.type.name)
                put("modeDescription", if (rule.type == McpRuleType.VERB) McpStrings.ruleModeVerb else McpStrings.ruleModeExact)
                put("pattern", rule.pattern)
                put("reason", rule.description.ifBlank {
                    if (rule.type == McpRuleType.VERB) McpStrings.ruleModeVerb else McpStrings.ruleModeExact
                })
            }
            rulesArray.put(ruleObj)
        }

        return JSONObject().apply {
            put("total", rules.size)
            put("rules", rulesArray)
            put("message", if (rules.isEmpty()) McpStrings.rulesMsgEmpty else McpStrings.rulesMsgActive)
        }
    }
}
