package com.aoooa.adb.ui.i18n

/** 终端补全结果与不支持输入的提示文案。 */
internal data class CompletionStrings(
    val unsupported: String,
    val empty: String,
    val failed: String,
    val choose: String
)

internal object CompletionI18n {
    val zh = CompletionStrings(
        unsupported = "暂不补全包含变量或复杂运算符的输入",
        empty = "没有找到补全候选",
        failed = "补全查询失败",
        choose = "选择补全候选"
    )
    val en = CompletionStrings(
        unsupported = "Completion does not expand variables or shell operators",
        empty = "No completion candidates found",
        failed = "Completion lookup failed",
        choose = "Choose completion"
    )
}

internal val Strings.completion: CompletionStrings
    get() = if (this === I18n.zh) CompletionI18n.zh else CompletionI18n.en
