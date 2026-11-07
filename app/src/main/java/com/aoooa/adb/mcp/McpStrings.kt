package com.aoooa.adb.mcp

import com.aoooa.adb.Prefs

/**
 * MCP (Model Context Protocol) 独立多语言文案管理：
 * 1. 负责工具描述、参数说明、错误提示与规则模式的国际化展示；
 * 2. 动态读取当前应用语言设置 (Prefs.appLanguage)；
 * 3. 独立于 UI 界面文案系统，维持模块解耦与单一职责。
 */
object McpStrings {

    private val isEn: Boolean
        get() = Prefs.appLanguage == "en"

    val shellToolDesc: String
        get() = if (isEn) {
            "Execute non-interactive ADB shell commands on the connected Android device. Call list_security_rules prior to execution to inspect command restrictions."
        } else {
            "在已连接的 Android 设备上执行非交互式 Shell 命令，支持系统管理、状态查询与日志过滤。执行前可调用 list_security_rules 查询拦截规则。"
        }

    val shellParamCommand: String
        get() = if (isEn) {
            "The shell command statement to execute (e.g. 'dumpsys battery' or 'logcat -d -t 100')"
        } else {
            "需要执行的 Shell 命令语句 (如 'dumpsys battery' 或 'logcat -d -t 100')"
        }

    val shellParamPage: String
        get() = if (isEn) {
            "Target page index to fetch when previous output was truncated into pages (default 1)"
        } else {
            "当上一次输出触发截断分页时，传入目标页码读取后续内容 (默认 1)"
        }

    val rulesToolDesc: String
        get() = if (isEn) {
            "Retrieve active security interception rules as JSON containing match mode (VERB/EXACT), target pattern, and reason, helping AI proactively avoid prohibited operations."
        } else {
            "获取当前服务端配置的安全拦截规则列表，返回包含模式（动词/精准）、拦截目标及附加原因的 JSON 数据，用于提前规避被禁用的高危命令。"
        }

    val ruleModeVerb: String
        get() = if (isEn) {
            "Verb match (intercepts if primary command verb matches after chained expansion)"
        } else {
            "动词拦截 (串联命令拆解后，执行动词匹配即拦截)"
        }

    val ruleModeExact: String
        get() = if (isEn) {
            "Exact match (intercepts if trimmed command string matches exactly)"
        } else {
            "精准拦截 (整条命令去除首尾空格后完全匹配即拦截)"
        }

    val rulesMsgEmpty: String
        get() = if (isEn) {
            "No security rules configured; all legitimate shell commands are permitted."
        } else {
            "当前未配置任何拦截规则，所有合法 Shell 命令均可执行。"
        }

    val rulesMsgActive: String
        get() = if (isEn) {
            "Active security rules successfully retrieved."
        } else {
            "成功获取当前生效的安全拦截规则列表。"
        }

    fun shellBlocked(reason: String): String {
        return if (isEn) {
            "[Security Blocked] Command blocked by security rule engine.\nReason: $reason"
        } else {
            "[安全拦截] 命令已被安全规则引擎拦截。\n附加说明: $reason"
        }
    }

    val shellNoDevice: String
        get() = if (isEn) {
            "[Error] ADB device not connected or unauthorized. Please connect on the app home screen."
        } else {
            "[错误] ADB 设备未连接或未授权，请先在 App 首页建立连接。"
        }

    val shellEmptyCmd: String
        get() = if (isEn) {
            "[Error] Command cannot be empty."
        } else {
            "[错误] 命令不能为空。"
        }

    val shellCacheExpired: String
        get() = if (isEn) {
            "[Notice] Page cache has expired or been released. Please re-run the command."
        } else {
            "[提示] 翻页缓存已过期或已释放，请重新执行该命令。"
        }

    fun shellUnknownTool(name: String): String {
        return if (isEn) "Unknown tool: $name" else "未知工具: $name"
    }
}
