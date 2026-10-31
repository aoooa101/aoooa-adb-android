package com.aoooa.adb.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aoooa.adb.AdbManager
import com.aoooa.adb.TerminalMode
import com.aoooa.adb.control.ControlSessionManager
import com.aoooa.adb.control.ControlSessionPhase
import com.aoooa.adb.log.FilterMode
import com.aoooa.adb.log.InstalledAppItem
import com.aoooa.adb.log.LogBufferMode
import com.aoooa.adb.log.LogKind
import com.aoooa.adb.log.LogLine
import com.aoooa.adb.log.LogManager
import com.aoooa.adb.log.LogSource
import com.aoooa.adb.log.LogTypeFilter
import com.aoooa.adb.model.TerminalLine
import com.aoooa.adb.shizuku.ShizukuManager
import com.aoooa.adb.ui.control.ControlModeScreen
import com.aoooa.adb.ui.app.AppManagerScreen
import com.aoooa.adb.ui.files.FileManagerScreen
import com.aoooa.adb.ui.i18n.Strings
import kotlinx.coroutines.launch

/**
 * 解析文本中的 ANSI SGR 颜色代码并构建 AnnotatedString
 */
fun parseAnsiText(raw: String): AnnotatedString {
    if (raw.isEmpty()) return AnnotatedString("")

    return buildAnnotatedString {
        var currentColor = Color(0xFFF8FAFC)
        val regex = Regex("\\u001B\\[([0-9;]*)m")
        var lastIndex = 0

        for (match in regex.findAll(raw)) {
            val plain = raw.substring(lastIndex, match.range.first)
            if (plain.isNotEmpty()) {
                val start = length
                append(plain)
                addStyle(SpanStyle(color = currentColor), start, length)
            }
            val codes = match.groupValues[1].split(";")
            for (c in codes) {
                currentColor = when (c) {
                    "30" -> Color(0xFF94A3B8) // 灰/黑
                    "31", "91" -> Color(0xFFF87171) // 红
                    "32", "92" -> Color(0xFF4ADE80) // 绿
                    "33", "93" -> Color(0xFFFBBF24) // 黄
                    "34", "94" -> Color(0xFF60A5FA) // 蓝
                    "35", "95" -> Color(0xFFC084FC) // 紫
                    "36", "96" -> Color(0xFF38BDF8) // 青
                    "37", "97" -> Color(0xFFF8FAFC) // 白
                    "0", "" -> Color(0xFFF8FAFC)     // 重置
                    else -> currentColor
                }
            }
            lastIndex = match.range.last + 1
        }

        if (lastIndex < raw.length) {
            val tail = raw.substring(lastIndex)
            val start = length
            append(tail)
            val finalColor = when {
                currentColor != Color(0xFFF8FAFC) -> currentColor
                tail.contains("Error", ignoreCase = true) || tail.contains("FAIL") || tail.startsWith("[错误]") || tail.startsWith("[失败]") || tail.startsWith("[未连接]") -> Color(0xFFF87171)
                tail.contains("Success", ignoreCase = true) || tail.contains("OKAY") || tail.startsWith("[成功]") -> Color(0xFF4ADE80)
                tail.startsWith("^C") || tail.startsWith("^Z") -> Color(0xFFFBBF24)
                else -> Color(0xFFF8FAFC)
            }
            addStyle(SpanStyle(color = finalColor), start, length)
        }
    }
}
