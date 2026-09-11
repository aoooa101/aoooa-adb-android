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
import com.aoooa.webadb.AdbManager
import com.aoooa.webadb.TerminalMode
import com.aoooa.webadb.log.FilterMode
import com.aoooa.webadb.log.InstalledAppItem
import com.aoooa.webadb.log.LogBufferMode
import com.aoooa.webadb.log.LogKind
import com.aoooa.webadb.log.LogLine
import com.aoooa.webadb.log.LogManager
import com.aoooa.webadb.log.LogSource
import com.aoooa.webadb.log.LogTypeFilter
import com.aoooa.webadb.model.TerminalLine
import com.aoooa.webadb.shizuku.ShizukuManager
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
    var terminalMode by AdbManager.currentTerminalMode
    var menuExpanded by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    val shellLines = AdbManager.terminalLines

    var commandInput by remember { mutableStateOf("") }
    val commandHistory = remember { mutableStateListOf<String>() }
    var historyIndex by remember { mutableIntStateOf(-1) }
    var isExecuting by remember { mutableStateOf(false) }
    var isCtrlActive by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val logListState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()

    val isCapturing by LogManager.isCapturing
    val searchQuery by LogManager.searchQuery
    val allLogLines = LogManager.logLines
    val receivedCount by LogManager.receivedCount
    val filteredDropCount by LogManager.filteredDropCount
    val ringDropCount by LogManager.ringDropCount
    val typeFilter by LogManager.typeFilter
    val enabledLevelsMask by LogManager.enabledLevelsMask
    val tagInclude by LogManager.tagInclude
    val tagExclude by LogManager.tagExclude
    val keywordInclude by LogManager.keywordInclude
    val keywordExclude by LogManager.keywordExclude
    val useRegex by LogManager.useRegex
    val highlightSpecial by LogManager.highlightSpecial

    // 日志实时过滤计算（显示侧完整过滤系统）
    val filteredLogs by remember(
        allLogLines.size,
        searchQuery,
        typeFilter,
        enabledLevelsMask,
        tagInclude,
        tagExclude,
        keywordInclude,
        keywordExclude,
        useRegex
    ) {
        derivedStateOf {
            allLogLines.filter { LogManager.matchesDisplayFilters(it) }
        }
    }

    // 智能吸底
    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            if (totalItems <= 1) return@derivedStateOf true
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= totalItems - 2
        }
    }

    val isLogAtBottom by remember {
        derivedStateOf {
            val layoutInfo = logListState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            if (totalItems <= 1) return@derivedStateOf true
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= totalItems - 2
        }
    }

    val extraSymbols = listOf("|", "&", "$", "~", "/", "-", "_", "*", "=", "\"", "'", ":", ";")

    // 初始化终端欢迎文案
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
        }
    }

    // 自动触底跟随
    LaunchedEffect(shellLines.size) {
        if (terminalMode == TerminalMode.SHELL && shellLines.isNotEmpty() && !listState.isScrollInProgress && isAtBottom) {
            listState.scrollToItem(shellLines.size - 1)
        }
    }

    LaunchedEffect(filteredLogs.size) {
        if (terminalMode == TerminalMode.LOG && filteredLogs.isNotEmpty() && !logListState.isScrollInProgress && isLogAtBottom) {
            logListState.scrollToItem(filteredLogs.size - 1)
        }
    }

    fun submitCommand(cmdText: String) {
        val trimmed = cmdText.trim()
        if (trimmed.isEmpty()) return

        if (commandHistory.isEmpty() || commandHistory.last() != trimmed) {
            commandHistory.add(trimmed)
            if (commandHistory.size > 100) commandHistory.removeAt(0)
        }
        historyIndex = -1
        commandInput = ""
        isCtrlActive = false

        coroutineScope.launch {
            if (shellLines.isNotEmpty()) {
                listState.scrollToItem(shellLines.size - 1)
            }
        }

        if (!connected) {
            shellLines.add(TerminalLine(text = "[未连接] ${s.terminalNotConnected}"))
            return
        }

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

    fun onExtraKeyClick(key: String) {
        when (key) {
            "Ctrl" -> isCtrlActive = !isCtrlActive
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
            else -> commandInput += key
        }
    }

    fun onInputTextChange(newText: String) {
        if (isCtrlActive && newText.isNotEmpty() && newText.length > commandInput.length) {
            val lastChar = newText.last()
            when (lastChar.lowercaseChar()) {
                'c' -> {
                    commandInput = ""
                    historyIndex = -1
                    isCtrlActive = false
                    if (connected) AdbManager.sendTerminalControl(0x03.toByte())
                    return
                }
                'd' -> {
                    commandInput = ""
                    historyIndex = -1
                    isCtrlActive = false
                    if (connected) {
                        shellLines.add(TerminalLine(text = "[断开] 用户通过 Ctrl+D 主动断开设备连接"))
                        AdbManager.disconnect()
                    }
                    return
                }
                'l' -> {
                    commandInput = ""
                    historyIndex = -1
                    isCtrlActive = false
                    AdbManager.clearTerminal()
                    if (connected && !isFastboot) AdbManager.sendTerminalControl(0x0C.toByte())
                    return
                }
                'z' -> {
                    commandInput = ""
                    historyIndex = -1
                    isCtrlActive = false
                    if (connected) AdbManager.sendTerminalControl(0x1A.toByte())
                    return
                }
            }
        }
        commandInput = newText
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            // 顶部状态栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 左上角汉堡菜单（切换 Shell / 日志）
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
                                                fontWeight = if (terminalMode == TerminalMode.LOG) FontWeight.Bold else FontWeight.Normal,
                                                color = if (terminalMode == TerminalMode.LOG) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                            if (terminalMode == TerminalMode.LOG) {
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
                                    terminalMode = TerminalMode.LOG
                                    menuExpanded = false
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Notes,
                                        contentDescription = null,
                                        tint = if (terminalMode == TerminalMode.LOG) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            )
                        }
                    }

                    Spacer(Modifier.width(4.dp))

                    // 模式与状态标签
                    Column {
                        val modeLabel = if (terminalMode == TerminalMode.SHELL) s.terminalModeShell else s.terminalModeAdb
                        val statusText = if (terminalMode == TerminalMode.SHELL) {
                            if (connected) deviceName.ifBlank { s.statusConnected } else s.terminalNotConnected
                        } else {
                            when {
                                isCapturing -> "抓取中 (${filteredLogs.size}行)"
                                ShizukuManager.isAuthorized.value -> "Shizuku 就绪"
                                connected -> "ADB 就绪"
                                else -> "就绪"
                            }
                        }
                        val statusColor = if (terminalMode == TerminalMode.SHELL) {
                            if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        } else {
                            if (isCapturing) Color(0xFF4ADE80) else MaterialTheme.colorScheme.primary
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

                // 右侧功能按钮
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (terminalMode == TerminalMode.LOG) {
                        // 日志模式：齿轮设置按钮
                        IconButton(
                            onClick = { showSettingsDialog = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = "日志设置",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        TextButton(
                            onClick = {
                                val allOutput = filteredLogs.joinToString("\n") { it.raw }
                                if (allOutput.isNotBlank()) {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("aoooa-adb logs", allOutput))
                                    AdbManager.log(s.copyLog + " ✓")
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(s.terminalCopy, fontSize = 12.sp)
                        }

                        TextButton(
                            onClick = { LogManager.clearLogs() },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(s.clear, fontSize = 12.sp)
                        }
                    } else {
                        // Shell 终端模式：复制与清屏
                        TextButton(
                            onClick = {
                                val allOutput = shellLines.joinToString("\n") { it.text }
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
            }

            // 日志模式：类型快筛 + 搜索框
            if (terminalMode == TerminalMode.LOG) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(LogTypeFilter.entries.toList(), key = { it.prefValue }) { tf ->
                        FilterChip(
                            selected = typeFilter == tf,
                            onClick = {
                                LogManager.typeFilter.value = tf
                                LogManager.saveSettings()
                            },
                            label = { Text(tf.labelZh, fontSize = 11.sp) }
                        )
                    }
                    item {
                        FilterChip(
                            selected = useRegex,
                            onClick = {
                                LogManager.useRegex.value = !useRegex
                                LogManager.saveSettings()
                            },
                            label = { Text(if (useRegex) "正则开" else "正则", fontSize = 11.sp) }
                        )
                    }
                    item {
                        AssistChip(
                            onClick = { LogManager.applyQuickPreset("crash") },
                            label = { Text("崩溃预设", fontSize = 11.sp) }
                        )
                    }
                    item {
                        AssistChip(
                            onClick = { LogManager.applyQuickPreset("errors") },
                            label = { Text("错误预设", fontSize = 11.sp) }
                        )
                    }
                    item {
                        AssistChip(
                            onClick = { LogManager.applyQuickPreset("all") },
                            label = { Text("重置过滤", fontSize = 11.sp) }
                        )
                    }
                }
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { LogManager.searchQuery.value = it },
                    placeholder = {
                        Text(
                            if (useRegex) "正则搜索（消息/Tag/包名）..." else "实时搜索日志（包名 / Tag / 关键字）...",
                            fontSize = 12.sp
                        )
                    },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { LogManager.searchQuery.value = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "清空搜索", modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    shape = RoundedCornerShape(8.dp)
                )
            }

            // 主视窗
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
                        // 输入框仅在 SHELL 模式挂载 focusRequester；日志模式请求焦点会直接崩溃
                        if (terminalMode == TerminalMode.SHELL) {
                            try {
                                focusRequester.requestFocus()
                            } catch (_: IllegalStateException) {
                            }
                        }
                    }
                    .padding(8.dp)
            ) {
                if (terminalMode == TerminalMode.SHELL) {
                    // Shell 终端列表
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(
                            items = shellLines,
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
                } else {
                    // 日志列表
                    if (filteredLogs.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = if (isCapturing) "正在监听日志输出..." else "点击右下角播放按钮开始抓取日志",
                                    color = Color(0xFF64748B),
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                if (isCapturing && (receivedCount > 0L || filteredDropCount > 0L)) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = "已收 $receivedCount 行 · 过滤丢弃 $filteredDropCount · 环缓冲淘汰 $ringDropCount",
                                        color = Color(0xFF94A3B8),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            state = logListState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(
                                items = filteredLogs,
                                key = { it.id }
                            ) { logLine ->
                                val levelColor = when (logLine.level.uppercase()) {
                                    "E", "F" -> Color(0xFFF87171) // 红色
                                    "W" -> Color(0xFFFBBF24)      // 黄色
                                    "I" -> Color(0xFF60A5FA)      // 蓝色
                                    "D" -> Color(0xFF38BDF8)      // 青色
                                    else -> Color(0xFFE2E8F0)     // 浅灰
                                }
                                val kindColor = when (logLine.kind) {
                                    LogKind.CRASH -> Color(0xFFFF4D6D)
                                    LogKind.STACK -> Color(0xFFFF8FAB)
                                    LogKind.ANR -> Color(0xFFFFB703)
                                    LogKind.ERROR -> Color(0xFFF87171)
                                    LogKind.SYSTEM -> Color(0xFF94A3B8)
                                    LogKind.NORMAL -> levelColor
                                }
                                val textColor = if (highlightSpecial && logLine.kind != LogKind.NORMAL) kindColor else levelColor
                                val bgColor = when {
                                    !highlightSpecial -> Color.Transparent
                                    logLine.kind == LogKind.CRASH -> Color(0x33FF1744)
                                    logLine.kind == LogKind.STACK -> Color(0x22FF8FAB)
                                    logLine.kind == LogKind.ANR -> Color(0x33FB8C00)
                                    logLine.kind == LogKind.ERROR -> Color(0x18EF5350)
                                    else -> Color.Transparent
                                }
                                val prefix = when {
                                    !highlightSpecial -> ""
                                    logLine.kind == LogKind.CRASH -> "💥 "
                                    logLine.kind == LogKind.STACK -> "↳ "
                                    logLine.kind == LogKind.ANR -> "⏱ "
                                    logLine.kind == LogKind.ERROR -> "⚠ "
                                    else -> ""
                                }

                                Text(
                                    text = prefix + logLine.raw,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp,
                                        color = textColor,
                                        fontWeight = if (logLine.kind == LogKind.CRASH) FontWeight.SemiBold else FontWeight.Normal
                                    ),
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(bgColor, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                        .clickable {
                                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            cm.setPrimaryClip(ClipData.newPlainText("Log Line", logLine.raw))
                                            AdbManager.log("已复制单行日志 ✓")
                                        }
                                )
                            }
                        }
                    }
                }
            }

            // 底部控制栏（仅 Shell 模式显示按键辅助栏与命令输入框）
            if (terminalMode == TerminalMode.SHELL) {
                Spacer(Modifier.height(6.dp))

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
                        keyboardActions = KeyboardActions(onSend = { submitCommand(commandInput) })
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

        // 日志模式：右下角播放/暂停悬浮按钮 (FAB)
        if (terminalMode == TerminalMode.LOG) {
            FloatingActionButton(
                onClick = {
                    if (isCapturing) {
                        LogManager.pauseCapture()
                    } else {
                        LogManager.startCapture(context)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp),
                shape = CircleShape,
                containerColor = if (isCapturing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                contentColor = Color.White
            ) {
                if (isCapturing) {
                    // 抓取中：显示两条竖杠（暂停图标）
                    Icon(Icons.Filled.Pause, contentDescription = "暂停抓取", modifier = Modifier.size(28.dp))
                } else {
                    // 暂停/未抓取：显示三角形向右（播放图标）
                    Icon(Icons.Filled.PlayArrow, contentDescription = "开始抓取", modifier = Modifier.size(28.dp))
                }
            }
        }
    }

    // 右上角齿轮打开的日志配置弹窗
    if (showSettingsDialog) {
        LogSettingsDialog(
            context = context,
            onDismiss = { showSettingsDialog = false }
        )
    }
}

/**
 * 日志配置管理弹窗（包含 Shizuku 连接入口、日志来源单选、白名单/黑名单互斥管理与应用选择器）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogSettingsDialog(
    context: Context,
    onDismiss: () -> Unit
) {
    val connected by AdbManager.connected
    val isShizukuAuthorized by ShizukuManager.isAuthorized
    val isShizukuAlive by ShizukuManager.isBinderAlive

    var logSource by LogManager.logSource
    var filterMode by LogManager.filterMode
    var bufferMode by LogManager.bufferMode
    var includeHistory by LogManager.includeHistory
    var historyLines by LogManager.historyLines
    var minLevel by LogManager.minLevel
    var maxBufferLines by LogManager.maxBufferLines
    var enabledLevelsMask by LogManager.enabledLevelsMask
    var typeFilter by LogManager.typeFilter
    var tagInclude by LogManager.tagInclude
    var tagExclude by LogManager.tagExclude
    var keywordInclude by LogManager.keywordInclude
    var keywordExclude by LogManager.keywordExclude
    var useRegex by LogManager.useRegex
    var highlightSpecial by LogManager.highlightSpecial
    val whitelist = LogManager.whitelist
    val blacklist = LogManager.blacklist
    val isCapturing by LogManager.isCapturing
    val receivedCount by LogManager.receivedCount
    val filteredDropCount by LogManager.filteredDropCount
    val ringDropCount by LogManager.ringDropCount

    var showDisconnectConfirmDialog by remember { mutableStateOf(false) }
    var showAddRuleDialog by remember { mutableStateOf<FilterMode?>(null) }

    val levelLabels = listOf("V", "D", "I", "W", "E", "F")

    Dialog(onDismissRequest = {
        LogManager.saveSettings()
        onDismiss()
    }) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "日志抓取设置",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(
                        onClick = {
                            LogManager.saveSettings()
                            onDismiss()
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭", modifier = Modifier.size(20.dp))
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 1. Shizuku 连接与权限卡片
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "用 Shizuku 查看日志",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "通过本机 Shizuku 授权，免连外部电脑直接抓取本机系统/应用日志",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))

                        if (connected) {
                            // 当已连接外部调试设备：置灰并显示已连接，点击触发断开确认
                            Button(
                                onClick = { showDisconnectConfirmDialog = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(Icons.Filled.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("已连接到调试设备（点击断开）", fontSize = 12.sp)
                            }
                        } else {
                            // 未连接普通外部设备
                            if (isShizukuAuthorized) {
                                Button(
                                    onClick = {},
                                    enabled = false,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        disabledContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        disabledContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                ) {
                                    Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("已授权 (Shizuku 就绪)", fontSize = 12.sp)
                                }
                            } else {
                                Button(
                                    onClick = {
                                        ShizukuManager.requestPermission()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Filled.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (isShizukuAlive) "连接 / 申请 Shizuku 授权" else "连接 Shizuku (请先启动服务)", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 2. 日志来源选择
                Text(
                    text = "日志抓取来源",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = logSource == LogSource.FULL_DEVICE,
                        onClick = {
                            logSource = LogSource.FULL_DEVICE
                            LogManager.saveSettings()
                        },
                        label = { Text("完整设备日志", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = logSource == LogSource.TARGET_APPS,
                        onClick = {
                            logSource = LogSource.TARGET_APPS
                            // 指定应用相关默认切到白名单，避免“看起来选了却仍全量放行”
                            if (filterMode == FilterMode.NONE) {
                                filterMode = FilterMode.WHITELIST
                            }
                            LogManager.saveSettings()
                        },
                        label = { Text("指定应用相关", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (logSource == LogSource.TARGET_APPS && whitelist.isEmpty() && filterMode != FilterMode.BLACKLIST) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "提示：已选“指定应用相关”，请至少添加一个白名单包名，否则抓取结果可能不符合预期。",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 11.sp
                    )
                }

                Spacer(Modifier.height(12.dp))

                // 2.1 完整抓取参数
                Text(
                    text = "完整抓取参数",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "缓冲：" + bufferMode.labelZh,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    LogBufferMode.entries.forEach { mode ->
                        FilterChip(
                            selected = bufferMode == mode,
                            onClick = {
                                bufferMode = mode
                                LogManager.saveSettings()
                            },
                            label = {
                                Text(
                                    when (mode) {
                                        LogBufferMode.DEFAULT -> "默认"
                                        LogBufferMode.ALL -> "全部"
                                        LogBufferMode.MAIN -> "main"
                                        LogBufferMode.SYSTEM -> "system"
                                        LogBufferMode.CRASH -> "crash"
                                        LogBufferMode.EVENTS -> "events"
                                    },
                                    fontSize = 11.sp
                                )
                            }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("启动时带历史 (-T)", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(
                            text = "先拉缓冲内已有日志，再继续实时跟随",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = includeHistory,
                        onCheckedChange = {
                            includeHistory = it
                            LogManager.saveSettings()
                        }
                    )
                }

                if (includeHistory) {
                    Spacer(Modifier.height(4.dp))
                    Text("历史行数: $historyLines", fontSize = 12.sp)
                    Slider(
                        value = historyLines.toFloat(),
                        onValueChange = { historyLines = it.toInt().coerceIn(50, 5000) },
                        onValueChangeFinished = { LogManager.saveSettings() },
                        valueRange = 50f..5000f,
                        steps = 98
                    )
                }

                Spacer(Modifier.height(4.dp))
                Text("最小级别（≥ 所选级别才入库）", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    levelLabels.forEachIndexed { idx, label ->
                        FilterChip(
                            selected = minLevel == idx,
                            onClick = {
                                minLevel = idx
                                LogManager.saveSettings()
                            },
                            label = { Text(label, fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text("界面环缓冲上限: $maxBufferLines 行", fontSize = 12.sp)
                Slider(
                    value = maxBufferLines.toFloat(),
                    onValueChange = { maxBufferLines = it.toInt().coerceIn(1000, 30000) },
                    onValueChangeFinished = { LogManager.saveSettings() },
                    valueRange = 1000f..30000f,
                    steps = 28
                )

                Text(
                    text = "当前统计：已收 $receivedCount · 过滤丢弃 $filteredDropCount · 环缓冲淘汰 $ringDropCount"
                        + if (isCapturing) "（改缓冲/历史需停止后重新开始抓取才生效）" else "",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(12.dp))

                // 2.2 显示过滤系统（级别多选 / 类型 / Tag / 关键词 / 正则 / 高亮）
                Text(
                    text = "显示过滤系统",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text("级别多选（显示）", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    levelLabels.forEachIndexed { idx, label ->
                        val on = (enabledLevelsMask and (1 shl idx)) != 0
                        FilterChip(
                            selected = on,
                            onClick = { LogManager.toggleLevel(idx) },
                            label = { Text(label, fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text("日志类型快筛", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    LogTypeFilter.entries.forEach { tf ->
                        FilterChip(
                            selected = typeFilter == tf,
                            onClick = {
                                typeFilter = tf
                                LogManager.saveSettings()
                            },
                            label = { Text(tf.labelZh, fontSize = 11.sp) }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text("一键预设", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    AssistChip(onClick = { LogManager.applyQuickPreset("all") }, label = { Text("全部", fontSize = 11.sp) })
                    AssistChip(onClick = { LogManager.applyQuickPreset("errors") }, label = { Text("仅错误", fontSize = 11.sp) })
                    AssistChip(onClick = { LogManager.applyQuickPreset("crash") }, label = { Text("崩溃堆栈", fontSize = 11.sp) })
                    AssistChip(onClick = { LogManager.applyQuickPreset("anr") }, label = { Text("ANR", fontSize = 11.sp) })
                    AssistChip(onClick = { LogManager.applyQuickPreset("system") }, label = { Text("系统", fontSize = 11.sp) })
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = tagInclude,
                    onValueChange = {
                        tagInclude = it
                    },
                    label = { Text("Tag 包含（逗号/| 分隔）", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = tagExclude,
                    onValueChange = { tagExclude = it },
                    label = { Text("Tag 排除", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = keywordInclude,
                    onValueChange = { keywordInclude = it },
                    label = { Text("关键词包含", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = keywordExclude,
                    onValueChange = { keywordExclude = it },
                    label = { Text("关键词排除", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("按正则匹配", fontSize = 13.sp)
                    Switch(
                        checked = useRegex,
                        onCheckedChange = {
                            useRegex = it
                            LogManager.saveSettings()
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("错误/堆栈高亮", fontSize = 13.sp)
                        Text("崩溃、堆栈、ANR 行着色标记", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = highlightSpecial,
                        onCheckedChange = {
                            highlightSpecial = it
                            LogManager.saveSettings()
                        }
                    )
                }
                TextButton(
                    onClick = { LogManager.saveSettings() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("保存过滤规则", fontSize = 12.sp)
                }

                Spacer(Modifier.height(12.dp))

                // 3. 应用过滤模式（互斥）
                Text(
                    text = "应用过滤规则（白名单 / 黑名单 互斥）",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = filterMode == FilterMode.NONE,
                        onClick = {
                            filterMode = FilterMode.NONE
                            LogManager.saveSettings()
                        },
                        label = { Text("不过滤", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = filterMode == FilterMode.WHITELIST,
                        onClick = {
                            filterMode = FilterMode.WHITELIST
                            LogManager.saveSettings()
                        },
                        label = { Text("应用白名单", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = filterMode == FilterMode.BLACKLIST,
                        onClick = {
                            filterMode = FilterMode.BLACKLIST
                            LogManager.saveSettings()
                        },
                        label = { Text("应用黑名单", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(8.dp))

                // 白名单 / 黑名单内容区域
                if (filterMode == FilterMode.WHITELIST) {
                    Text(
                        text = "白名单：仅抓取和显示以下应用的日志",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF4ADE80),
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        for (pkg in whitelist) {
                            InputChip(
                                selected = false,
                                onClick = {},
                                label = { Text(pkg, fontSize = 11.sp) },
                                trailingIcon = {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "删除",
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clickable { LogManager.removeWhitelistPackage(pkg) }
                                    )
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { showAddRuleDialog = FilterMode.WHITELIST },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("添加白名单应用", fontSize = 12.sp)
                    }
                } else if (filterMode == FilterMode.BLACKLIST) {
                    Text(
                        text = "黑名单：自动排除并过滤以下应用的日志",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFF87171),
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        for (pkg in blacklist) {
                            InputChip(
                                selected = false,
                                onClick = {},
                                label = { Text(pkg, fontSize = 11.sp) },
                                trailingIcon = {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "删除",
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clickable { LogManager.removeBlacklistPackage(pkg) }
                                    )
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { showAddRuleDialog = FilterMode.BLACKLIST },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("添加黑名单应用", fontSize = 12.sp)
                    }
                }
            }
        }
    }

    // 确认断开外部调试连接对话框
    if (showDisconnectConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirmDialog = false },
            title = { Text("断开设备连接确认") },
            text = { Text("当前已通过 USB / 无线调试连接到外部设备。切换至 Shizuku 查看本机日志需要先断开当前连接，是否继续？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        AdbManager.disconnect()
                        showDisconnectConfirmDialog = false
                    }
                ) {
                    Text("断开并切换", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 添加白名单/黑名单包名对话框
    showAddRuleDialog?.let { targetMode ->
        AddAppRuleDialog(
            context = context,
            mode = targetMode,
            onDismiss = { showAddRuleDialog = null },
            onConfirm = { inputPkg ->
                if (targetMode == FilterMode.WHITELIST) {
                    LogManager.addWhitelistPackage(inputPkg)
                } else {
                    LogManager.addBlacklistPackage(inputPkg)
                }
                showAddRuleDialog = null
            }
        )
    }
}

/**
 * 添加应用规则对话框（带包名输入框、右侧“从设备读取应用”按钮、确认与取消）
 */
@Composable
fun AddAppRuleDialog(
    context: Context,
    mode: FilterMode,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var pkgInput by remember { mutableStateOf("") }
    var showAppPicker by remember { mutableStateOf(false) }

    val modeTitle = if (mode == FilterMode.WHITELIST) "添加白名单应用" else "添加黑名单应用"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(modeTitle, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "输入应用包名，或点击右侧按钮直接从设备已安装应用中选择：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = pkgInput,
                        onValueChange = { pkgInput = it },
                        placeholder = { Text("如: com.example.app", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )

                    Button(
                        onClick = { showAppPicker = true },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Filled.Apps, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("选择应用", fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (pkgInput.isNotBlank()) {
                        onConfirm(pkgInput.trim())
                    }
                },
                enabled = pkgInput.isNotBlank()
            ) {
                Text("确认")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )

    if (showAppPicker) {
        AppPickerDialog(
            context = context,
            onDismiss = { showAppPicker = false },
            onSelect = { selectedPkg ->
                pkgInput = selectedPkg
                showAppPicker = false
            }
        )
    }
}

/**
 * 设备已安装应用列表选择弹窗
 */
@Composable
fun AppPickerDialog(
    context: Context,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    var appList by remember { mutableStateOf<List<InstalledAppItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchKey by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        LogManager.fetchInstalledApps(context) { list ->
            appList = list
            isLoading = false
        }
    }

    val filteredList = remember(appList, searchKey) {
        if (searchKey.isBlank()) appList
        else appList.filter { it.packageName.contains(searchKey, ignoreCase = true) || it.label.contains(searchKey, ignoreCase = true) }
    }

    Dialog(
        onDismissRequest = {
            LogManager.saveSettings()
            onDismiss()
        }
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .padding(12.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("选择设备应用", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭", modifier = Modifier.size(20.dp))
                    }
                }

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = searchKey,
                    onValueChange = { searchKey = it },
                    placeholder = { Text("搜索应用名或包名...", fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall,
                    shape = RoundedCornerShape(8.dp)
                )

                Spacer(Modifier.height(8.dp))

                if (isLoading) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                } else if (filteredList.isEmpty()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("未找到匹配应用", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(filteredList, key = { it.packageName }) { item ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(item.packageName) },
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(item.label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text(item.packageName, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
