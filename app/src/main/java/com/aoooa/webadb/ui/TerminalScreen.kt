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
import com.aoooa.webadb.TerminalMode
import com.aoooa.webadb.model.TerminalLine
import com.aoooa.webadb.ui.i18n.Strings
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

// ADB 终端绿色命令提示符
private const val ADB_PROMPT = "\u001B[32maoooa-adb$\u001B[0m "

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
    var terminalMode by AdbManager.currentTerminalMode
    var menuExpanded by remember { mutableStateOf(false) }
    var isInAdbShell by remember { mutableStateOf(false) }

    val shellLines = AdbManager.terminalLines
    val adbLines = AdbManager.adbTerminalLines
    val currentLines = if (terminalMode == TerminalMode.SHELL) shellLines else adbLines

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
    LaunchedEffect(terminalMode, connected) {
        if (terminalMode == TerminalMode.SHELL) {
            if (connected) {
                shellLines.removeAll { it.text.startsWith("[未连接]") }
                if (shellLines.isEmpty()) {
                    shellLines.add(TerminalLine(text = s.terminalHint))
                }
            } else {
                if (shellLines.isEmpty()) {
                    shellLines.add(TerminalLine(text = "[未连接] ${s.terminalNotConnected}"))
                }
            }
        } else {
            if (adbLines.isEmpty()) {
                adbLines.add(TerminalLine(text = s.terminalAdbHint))
                adbLines.add(TerminalLine(text = ADB_PROMPT))
            }
        }
    }

    // 自动触底跟随（仅当用户原本就在底部且未手动拖拽滑动时才触发，让用户随时往上滑翻阅历史）
    LaunchedEffect(currentLines.size) {
        if (currentLines.isNotEmpty() && !listState.isScrollInProgress && isAtBottom) {
            listState.scrollToItem(currentLines.size - 1)
        }
    }

    // 发送与执行用户命令
    fun submitCommand(cmdText: String) {
        val trimmed = cmdText.trim()
        if (trimmed.isEmpty()) return

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
            if (currentLines.isNotEmpty()) {
                listState.scrollToItem(currentLines.size - 1)
            }
        }

        // ADB 终端模式：挂载原生执行引擎与加载状态
        if (terminalMode == TerminalMode.ADB) {
            if (isInAdbShell) {
                // 已进入远程被控端 Shell 环境
                val prompt = AdbManager.getShellPrompt()
                if (trimmed.equals("exit", ignoreCase = true)) {
                    // 退出被控端 Shell，恢复回到本地 ADB 终端提示符
                    isInAdbShell = false
                    adbLines.add(TerminalLine(text = "$prompt$trimmed"))
                    adbLines.add(TerminalLine(text = ADB_PROMPT))
                    return
                }

                adbLines.add(TerminalLine(text = "$prompt$trimmed"))
                isExecuting = true
                AdbManager.execTerminal(trimmed) {
                    isExecuting = false
                    if (isInAdbShell) {
                        adbLines.add(TerminalLine(text = AdbManager.getShellPrompt()))
                    }
                }
                return
            }

            // 检查用户是否敲了 "adb shell" 或 "shell" 准备进入交互式 Shell 环境
            if (trimmed.equals("adb shell", ignoreCase = true) || trimmed.equals("shell", ignoreCase = true)) {
                adbLines.add(TerminalLine(text = "$ADB_PROMPT$trimmed"))
                if (connected) {
                    isInAdbShell = true
                    adbLines.add(TerminalLine(text = AdbManager.getShellPrompt()))
                } else {
                    adbLines.add(TerminalLine(text = "error: no devices/emulators found"))
                    adbLines.add(TerminalLine(text = ADB_PROMPT))
                }
                return
            }

            // 普通本地原生 ADB 命令执行
            isExecuting = true
            adbLines.add(TerminalLine(text = "$ADB_PROMPT$trimmed"))
            AdbManager.executeAdbCommand(context, trimmed) {
                isExecuting = false
                adbLines.add(TerminalLine(text = ADB_PROMPT))
            }
            return
        }

        // Shell 终端模式（原有逻辑保持完全不变）
        if (!connected) {
            shellLines.add(TerminalLine(text = "[未连接] ${s.terminalNotConnected}"))
            return
        }

        // 发送命令到底层统一交互通道（ADB 走常驻真实 PTY 会话，Fastboot 走单次指令通道）
        if (isFastboot) {
            isExecuting = true
            shellLines.add(TerminalLine(text = "${AdbManager.getShellPrompt()}$trimmed"))
            AdbManager.execTerminal(trimmed) {
                isExecuting = false
                shellLines.add(TerminalLine(text = AdbManager.getShellPrompt()))
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
                if (terminalMode == TerminalMode.SHELL) {
                    AdbManager.clearTerminal()
                } else {
                    AdbManager.cancelAdbCommand()
                    AdbManager.clearAdbTerminal()
                    isExecuting = false
                }
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
                    if (terminalMode == TerminalMode.ADB) {
                        AdbManager.cancelAdbCommand()
                        isExecuting = false
                    } else if (connected) {
                        AdbManager.sendTerminalControl(0x03.toByte())
                    }
                    return
                }
                'd' -> {
                    // 触发 Ctrl+D 真实退出
                    commandInput = ""
                    historyIndex = -1
                    isCtrlActive = false
                    if (terminalMode == TerminalMode.ADB) {
                        if (isInAdbShell) {
                            // 退出被控端 Shell，恢复回到本地绿色提示符
                            val prompt = AdbManager.getShellPrompt()
                            isInAdbShell = false
                            adbLines.add(TerminalLine(text = "${prompt}exit"))
                            adbLines.add(TerminalLine(text = ADB_PROMPT))
                        } else {
                            AdbManager.cancelAdbCommand()
                            isExecuting = false
                            adbLines.add(TerminalLine(text = "exit"))
                            adbLines.add(TerminalLine(text = ADB_PROMPT))
                        }
                    } else if (connected) {
                        // Shell 终端：执行真正的断开连接，UI 状态与日志全量同步更新
                        shellLines.add(TerminalLine(text = "[断开] 用户通过 Ctrl+D 主动断开设备连接"))
                        AdbManager.disconnect()
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
                // 左上角三条杠（汉堡菜单，打开终端模式切换）
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Filled.Menu,
                            contentDescription = s.terminalSwitchTitle,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = s.terminalModeShell,
                                            fontWeight = if (terminalMode == TerminalMode.SHELL) FontWeight.Bold else FontWeight.Normal,
                                            color = if (terminalMode == TerminalMode.SHELL) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                        if (terminalMode == TerminalMode.SHELL) {
                                            Spacer(Modifier.width(6.dp))
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = s.terminalModeShellDesc,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                terminalMode = TerminalMode.SHELL
                                menuExpanded = false
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Terminal,
                                    contentDescription = null,
                                    tint = if (terminalMode == TerminalMode.SHELL) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )

                        HorizontalDivider()

                        DropdownMenuItem(
                            text = {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = s.terminalModeAdb,
                                            fontWeight = if (terminalMode == TerminalMode.ADB) FontWeight.Bold else FontWeight.Normal,
                                            color = if (terminalMode == TerminalMode.ADB) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                        if (terminalMode == TerminalMode.ADB) {
                                            Spacer(Modifier.width(6.dp))
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = s.terminalModeAdbDesc,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                terminalMode = TerminalMode.ADB
                                menuExpanded = false
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Code,
                                    contentDescription = null,
                                    tint = if (terminalMode == TerminalMode.ADB) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )
                    }
                }

                Spacer(Modifier.width(4.dp))

                // 终端模式与连接状态指示
                Column {
                    val modeLabel = if (terminalMode == TerminalMode.SHELL) s.terminalModeShell else s.terminalModeAdb
                    val statusText = if (terminalMode == TerminalMode.SHELL) {
                        if (connected) deviceName.ifBlank { s.statusConnected } else s.terminalNotConnected
                    } else {
                        s.terminalAdbReady
                    }
                    val statusColor = if (terminalMode == TerminalMode.SHELL) {
                        if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    } else {
                        Color(0xFF4ADE80)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Text(
                                text = modeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.titleSmall,
                            color = statusColor
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = {
                        val allOutput = currentLines.joinToString("\n") { it.text }
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

        // 终端主视窗
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
                    items = currentLines,
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
                placeholder = {
                    Text(
                        text = if (terminalMode == TerminalMode.SHELL) s.terminalPlaceholder else s.terminalAdbPlaceholder,
                        fontSize = 13.sp
                    )
                },
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
