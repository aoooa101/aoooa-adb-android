package com.aoooa.webadb.ui

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
import com.aoooa.webadb.AdbManager
import com.aoooa.webadb.TerminalLine
import com.aoooa.webadb.ui.i18n.Strings

/**
 * 极简纯原生 ANSI 颜色高亮解析器（精准匹配 SGR 颜色代码，绝不误伤正文字母）
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
            // 语义化兜底高亮（仅在未设置显式 ANSI 颜色时生效）
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

@Composable
fun TerminalScreen(
    s: Strings,
    lang: String,
    modifier: Modifier = Modifier
) {
    val connected by AdbManager.connected
    val isFastboot by AdbManager.isFastbootMode
    val deviceName by AdbManager.deviceName
    val context = LocalContext.current

    val terminalLines = AdbManager.terminalLines
    var commandInput by remember { mutableStateOf("") }
    val commandHistory = remember { mutableStateListOf<String>() }
    var historyIndex by remember { mutableIntStateOf(-1) }
    var isExecuting by remember { mutableStateOf(false) }
    var isCtrlActive by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()

    // 智能触底状态判断：只有用户在底部附近时才自动吸底跟随，向上翻阅历史时绝不打扰
    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            if (totalItems <= 1) return@derivedStateOf true
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= totalItems - 2
        }
    }

    // 常用快捷符号定义
    val extraSymbols = listOf("|", "&", "$", "~", "/", "-", "_", "*", "=", "\"", "'", ":", ";")

    // 初始化终端欢迎文案与连接状态清理
    LaunchedEffect(connected) {
        if (connected) {
            terminalLines.removeAll { it.text.startsWith("[未连接]") }
            if (terminalLines.isEmpty()) {
                terminalLines.add(TerminalLine(0L, s.terminalHint))
            }
        } else {
            if (terminalLines.isEmpty()) {
                terminalLines.add(TerminalLine(0L, "[未连接] ${s.terminalNotConnected}"))
            }
        }
    }

    // 自动触底跟随（仅当用户原本就在底部且未手动拖拽滑动时才触发，让用户随时往上滑翻阅历史）
    LaunchedEffect(terminalLines.size) {
        if (terminalLines.isNotEmpty() && !listState.isScrollInProgress && isAtBottom) {
            listState.scrollToItem(terminalLines.size - 1)
        }
    }

    // 发送与执行用户 Shell 命令（真实 PTY 双向长连接，统一通道）
    fun submitCommand(cmdText: String) {
        val trimmed = cmdText.trim()
        if (trimmed.isEmpty()) return

        if (!connected) {
            terminalLines.add(TerminalLine(0L, "[未连接] ${s.terminalNotConnected}"))
            return
        }

        // 1. 记录历史命令
        if (commandHistory.isEmpty() || commandHistory.last() != trimmed) {
            commandHistory.add(trimmed)
            if (commandHistory.size > 100) commandHistory.removeAt(0)
        }
        historyIndex = -1

        // 2. 清空输入框并复位 Ctrl
        commandInput = ""
        isCtrlActive = false

        // 3. 用户主动提交命令时，强制瞬时滑到底部查看执行输出
        coroutineScope.launch {
            if (terminalLines.isNotEmpty()) {
                listState.scrollToItem(terminalLines.size - 1)
            }
        }

        // 4. 发送命令到底层统一交互通道（ADB 走常驻真实 PTY 会话，Fastboot 走单次指令通道）
        if (isFastboot) {
            isExecuting = true
            terminalLines.add(TerminalLine(0L, "${AdbManager.getShellPrompt()}$trimmed"))
            AdbManager.execTerminal(trimmed) {
                isExecuting = false
                terminalLines.add(TerminalLine(0L, AdbManager.getShellPrompt()))
            }
        } else {
            AdbManager.sendTerminalInput(trimmed)
        }
    }

    // 处理辅助栏快捷按键与 Ctrl 粘滞逻辑
    fun onExtraKeyClick(key: String) {
        when (key) {
            "Ctrl" -> {
                isCtrlActive = !isCtrlActive
            }
            "↑" -> {
                if (commandHistory.isNotEmpty()) {
                    if (historyIndex == -1) {
                        historyIndex = commandHistory.size - 1
                    } else if (historyIndex > 0) {
                        historyIndex--
                    }
                    commandInput = commandHistory.getOrElse(historyIndex) { "" }
                }
            }
            "↓" -> {
                if (commandHistory.isNotEmpty() && historyIndex != -1) {
                    if (historyIndex < commandHistory.size - 1) {
                        historyIndex++
                        commandInput = commandHistory[historyIndex]
                    } else {
                        historyIndex = -1
                        commandInput = ""
                    }
                }
            }
            "CLEAR" -> {
                AdbManager.clearTerminal()
                isCtrlActive = false
            }
            "Tab" -> {
                if (connected && !isFastboot) {
                    AdbManager.sendTerminalControl(0x09.toByte())
                } else {
                    commandInput += "    "
                }
            }
            "Esc" -> {
                if (connected && !isFastboot) {
                    AdbManager.sendTerminalControl(0x1B.toByte())
                }
                commandInput = ""
                historyIndex = -1
                isCtrlActive = false
            }
            else -> {
                commandInput += key
            }
        }
    }

    // 输入框内容变动监听（捕获 Ctrl 粘滞状态下的按键组合）
    fun onInputTextChange(newText: String) {
        if (isCtrlActive && newText.isNotEmpty() && newText.length > commandInput.length) {
            val lastChar = newText.last()
            when (lastChar.lowercaseChar()) {
                'c' -> {
                    // 触发 Ctrl+C SIGINT 中断（PTY 自动回显 ^C 并由系统输出新提示符）
                    commandInput = ""
                    historyIndex = -1
                    isCtrlActive = false
                    if (connected) {
                        AdbManager.sendTerminalControl(0x03.toByte())
                    }
                    return
                }
                'd' -> {
                    // 触发 Ctrl+D EOF 退出
                    commandInput = ""
                    historyIndex = -1
                    isCtrlActive = false
                    if (connected) {
                        AdbManager.sendTerminalControl(0x04.toByte())
                    }
                    return
                }
                'l' -> {
                    // 触发 Ctrl+L 清屏
                    commandInput = ""
                    historyIndex = -1
                    isCtrlActive = false
                    AdbManager.clearTerminal()
                    if (connected && !isFastboot) {
                        AdbManager.sendTerminalControl(0x0C.toByte())
                    }
                    return
                }
                'z' -> {
                    // 触发 Ctrl+Z 挂起
                    commandInput = ""
                    historyIndex = -1
                    isCtrlActive = false
                    if (connected) {
                        AdbManager.sendTerminalControl(0x1A.toByte())
                    }
                    return
                }
            }
        }
        commandInput = newText
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        // 顶部精简状态与快捷操作栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Terminal,
                    contentDescription = null,
                    tint = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (connected) deviceName.ifBlank { s.statusConnected } else s.terminalNotConnected,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = {
                        val allOutput = terminalLines.joinToString("\n") { it.text }
                        if (allOutput.isNotBlank()) {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("aoooa-adb terminal", allOutput))
                            AdbManager.log(s.copyLog + " ✓")
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(s.terminalCopy, fontSize = 12.sp)
                }

                TextButton(
                    onClick = { onExtraKeyClick("CLEAR") },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(s.terminalClear, fontSize = 12.sp)
                }
            }
        }

        // 终端主视窗（经典黑客深蓝黑背景，支持长文本滚屏与 ANSI 彩色高亮）
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0B0F19))
                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(8.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    focusRequester.requestFocus()
                }
                .padding(10.dp)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(
                    items = terminalLines,
                    key = { it.id }
                ) { line ->
                    Text(
                        text = parseAnsiText(line.text),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 13.sp,
                            lineHeight = 17.sp
                        ),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // 辅助按键与快捷符号横向滑动栏
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            item {
                FilterChip(
                    selected = isCtrlActive,
                    onClick = { onExtraKeyClick("Ctrl") },
                    label = { Text("Ctrl", fontWeight = FontWeight.Bold, fontSize = 13.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
            item {
                AssistChip(
                    onClick = { onExtraKeyClick("↑") },
                    label = { Text("↑", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                )
            }
            item {
                AssistChip(
                    onClick = { onExtraKeyClick("↓") },
                    label = { Text("↓", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                )
            }
            item {
                AssistChip(
                    onClick = { onExtraKeyClick("Tab") },
                    label = { Text("Tab", fontSize = 12.sp) }
                )
            }
            item {
                AssistChip(
                    onClick = { onExtraKeyClick("Esc") },
                    label = { Text("Esc", fontSize = 12.sp) }
                )
            }
            item {
                AssistChip(
                    onClick = { onExtraKeyClick("CLEAR") },
                    label = { Text("CLEAR", fontSize = 12.sp) }
                )
            }

            items(extraSymbols) { sym ->
                AssistChip(
                    onClick = { onExtraKeyClick(sym) },
                    label = { Text(sym, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // 底部主输入控制栏（带发送按钮与软键盘发送支持）
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = commandInput,
                onValueChange = { onInputTextChange(it) },
                placeholder = { Text(s.terminalPlaceholder, fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        submitCommand(commandInput)
                    }
                )
            )

            Button(
                onClick = { submitCommand(commandInput) },
                enabled = commandInput.isNotBlank() && !isExecuting,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
            ) {
                if (isExecuting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(Icons.Filled.Send, contentDescription = s.terminalSend, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
