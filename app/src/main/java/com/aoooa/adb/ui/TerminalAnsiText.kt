package com.aoooa.adb.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.aoooa.adb.adb.protocol.TerminalSgr

private val sgrSequence = Regex("\\u001B\\[([0-9;:]*)m")

/** 按 SGR 状态渲染 Shell 文本，普通文本不参与日志级别着色。 */
fun parseAnsiText(raw: String): AnnotatedString = buildAnnotatedString {
    var colors = TerminalSgr()
    var offset = 0
    fun segment(end: Int) {
        if (end <= offset) return
        val start = length
        append(raw.substring(offset, end))
        addStyle(SpanStyle(
            color = colors.foreground?.let { Color(it) } ?: Color(0xFFF8FAFC),
            background = colors.background?.let { Color(it) } ?: Color.Unspecified
        ), start, length)
    }
    for (match in sgrSequence.findAll(raw)) {
        segment(match.range.first)
        colors = colors.applyParameters(match.groupValues[1])
        offset = match.range.last + 1
    }
    segment(raw.length)
}
