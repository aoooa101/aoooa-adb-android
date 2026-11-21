package com.aoooa.adb.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.aoooa.adb.adb.protocol.TerminalSgr

/**
 * 解析文本中的 ANSI SGR 颜色代码并构建 AnnotatedString
 */
fun parseAnsiText(raw: String): AnnotatedString {
    if (raw.isEmpty()) return AnnotatedString("")

    return buildAnnotatedString {
        var colors = TerminalSgr()
        val regex = Regex("\\u001B\\[([0-9;:]*)m")
        var lastIndex = 0

        for (match in regex.findAll(raw)) {
            val plain = raw.substring(lastIndex, match.range.first)
            if (plain.isNotEmpty()) {
                val start = length
                append(plain)
                addStyle(SpanStyle(color = colors.foreground?.let { Color(it) } ?: Color(0xFFF8FAFC), background = colors.background?.let { Color(it) } ?: Color.Unspecified), start, length)
            }
            colors = colors.applyParameters(match.groupValues[1])
            lastIndex = match.range.last + 1
        }

        if (lastIndex < raw.length) {
            val tail = raw.substring(lastIndex)
            val start = length
            append(tail)
            val finalColor = when {
                colors.foreground != null -> Color(requireNotNull(colors.foreground))
                // 错误类行：同时识别中英文前缀，避免界面语言切换后高亮失效
                tail.contains("Error", ignoreCase = true) || tail.contains("FAIL") ||
                    tail.startsWith("[错误]") || tail.startsWith("[Error]") ||
                    tail.startsWith("[失败]") || tail.startsWith("[Failed]") || tail.startsWith("[Fail]") ||
                    tail.startsWith("[未连接]") || tail.startsWith("[Not connected]") ||
                    tail.startsWith("[Disconnected]") -> Color(0xFFF87171)
                // 成功类行：同样兼容中英文
                tail.contains("Success", ignoreCase = true) || tail.contains("OKAY") ||
                    tail.startsWith("[成功]") || tail.startsWith("[OK]") ||
                    tail.startsWith("[Success]") -> Color(0xFF4ADE80)
                tail.startsWith("^C") || tail.startsWith("^Z") -> Color(0xFFFBBF24)
                else -> Color(0xFFF8FAFC)
            }
            addStyle(SpanStyle(color = finalColor, background = colors.background?.let { Color(it) } ?: Color.Unspecified), start, length)
        }
    }
}
