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
import androidx.compose.foundation.lazy.LazyListState
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
import com.aoooa.adb.adb.shell
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
import com.aoooa.adb.ui.i18n.I18n
import com.aoooa.adb.ui.i18n.Strings
import kotlinx.coroutines.launch
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
    val controlPhase by ControlSessionManager.phase

    // 离开控制模式视图时主动释放本地会话资源
    LaunchedEffect(terminalMode, controlPhase) {
        if (terminalMode != TerminalMode.CONTROL &&
            (controlPhase == ControlSessionPhase.RUNNING ||
                controlPhase == ControlSessionPhase.PREPARING)
        ) {
            ControlSessionManager.stop("leave_tab")
        }
    }

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
    var completing by remember { mutableStateOf(false) }
    var completionChoices by remember { mutableStateOf<List<CompletionCandidate>>(emptyList()) }
    var completionRequest by remember { mutableStateOf<CompletionRequest?>(null) }
    var completionInput by remember { mutableStateOf("") }
    var completionConnection by remember {
        mutableStateOf<com.aoooa.adb.adb.AdbConnection?>(null)
    }

    fun completeInput() {
        if (completing || !connected || isFastboot) return
        val original = commandInput
        val request = TerminalCompletion.parse(original)
        if (request == null) {
            android.widget.Toast.makeText(context, if (lang == "zh") "暂不补全包含变量或复杂运算符的输入" else "Completion does not expand variables or shell operators", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val connectionAtStart = AdbManager.connection?.takeIf { it.isAuthenticated } ?: return
        val workingDir = AdbManager.currentWorkingDir.value
        completing = true
        coroutineScope.launch {
            try {
                val output = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    connectionAtStart.shell(TerminalCompletion.query(request, workingDir))
                }
                if (commandInput == original && AdbManager.connected.value &&
                    !AdbManager.isFastbootMode.value && connectionAtStart.isAuthenticated &&
                    AdbManager.connection === connectionAtStart) {
                    val candidates = TerminalCompletion.candidates(request, output)
                    when (candidates.size) {
                        0 -> android.widget.Toast.makeText(context, if (lang == "zh") "没有找到补全候选" else "No completion candidates found", android.widget.Toast.LENGTH_SHORT).show()
                        1 -> commandInput = TerminalCompletion.apply(request, candidates.single().value, true)
                        else -> {
                            val prefix = TerminalCompletion.commonPrefix(candidates)
                            if (prefix.length > request.word.length) {
                                commandInput = TerminalCompletion.apply(request, prefix, false)
                            }
                            completionRequest = request
                            completionConnection = connectionAtStart
                            completionInput = commandInput
                            completionChoices = candidates
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AdbManager.debugLog("[Completion] 候选查询异常: ${e.javaClass.simpleName}")
                android.widget.Toast.makeText(context, if (lang == "zh") "补全查询失败" else "Completion lookup failed", android.widget.Toast.LENGTH_SHORT).show()
            } finally {
                completing = false
            }
        }
    }

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

    FollowLatestEffect(
        state = listState,
        enabled = terminalMode == TerminalMode.SHELL,
        itemCount = shellLines.size,
        contentVersion = shellLines.lastOrNull()?.let { it.id to it.text }
    )
    FollowLatestEffect(
        state = logListState,
        enabled = terminalMode == TerminalMode.LOG,
        itemCount = filteredLogs.size,
        contentVersion = filteredLogs.lastOrNull()?.id
    )

    val extraSymbols = listOf("|", "&", "$", "~", "/", "-", "_", "*", "=", "\"", "'", ":", ";")

    // 初始化终端欢迎文案
    LaunchedEffect(terminalMode, connected) {
        if (terminalMode == TerminalMode.SHELL) {
            if (connected) {
                shellLines.removeAll { it.text.startsWith("[未连接]") || it.text.startsWith("[Disconnected]") }
                if (shellLines.isEmpty()) {
                    shellLines.add(TerminalLine(text = s.terminalHint))
                }
            } else {
                if (shellLines.isEmpty()) {
                    shellLines.add(TerminalLine(text = "[${s.statusDisconnected}] ${s.terminalNotConnected}"))
                }
            }
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
            shellLines.add(TerminalLine(text = "[${s.statusDisconnected}] ${s.terminalNotConnected}"))
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
                if (connected && !isFastboot) {
                    AdbManager.sendTerminalControl(0x0D.toByte())
                }
            }
            "Enter" -> {
                if (connected && !isFastboot) AdbManager.sendTerminalControl(0x0D.toByte())
                isCtrlActive = false
            }
            "Tab" -> completeInput()
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
                        shellLines.add(TerminalLine(text = s.logCtrlDDisconnected))
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
            TerminalTopBar(s = s, context = context, terminalMode = terminalMode, menuExpanded = menuExpanded, showSettingsDialog = showSettingsDialog, connected = connected, deviceName = deviceName, isFastboot = isFastboot, filteredLogs = filteredLogs, isCapturing = isCapturing, shellLines = shellLines, onTerminalModeChange = { terminalMode = it }, onMenuExpandedChange = { menuExpanded = it }, onShowSettingsChange = { showSettingsDialog = it }, onExtraKeyClick = ::onExtraKeyClick)

            // 日志模式：类型快筛 + 搜索框
            TerminalLogFilterBar(s = s, terminalMode = terminalMode, typeFilter = typeFilter, useRegex = useRegex, searchQuery = searchQuery)

            // 主视窗
            TerminalMainViewport(s = s, terminalMode = terminalMode, context = context, filteredLogs = filteredLogs, shellLines = shellLines, listState = listState, logListState = logListState, focusRequester = focusRequester, isCapturing = isCapturing, highlightSpecial = highlightSpecial, receivedCount = receivedCount, filteredDropCount = filteredDropCount, ringDropCount = ringDropCount)

            // 底部控制栏（仅 Shell 模式显示按键辅助栏与命令输入框）
            TerminalShellInputBar(s = s, terminalMode = terminalMode, connected = connected, isFastboot = isFastboot, commandInput = commandInput, isCtrlActive = isCtrlActive, isExecuting = isExecuting, extraSymbols = extraSymbols, focusRequester = focusRequester, submitCommand = ::submitCommand, onExtraKeyClick = ::onExtraKeyClick, onInputTextChange = ::onInputTextChange)
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
                    Icon(Icons.Filled.Pause, contentDescription = s.logPauseCd, modifier = Modifier.size(28.dp))
                } else {
                    // 暂停/未抓取：显示三角形向右（播放图标）
                    Icon(Icons.Filled.PlayArrow, contentDescription = s.logStartCd, modifier = Modifier.size(28.dp))
                }
            }
        }
    }

    if (completionChoices.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { completionChoices = emptyList() },
            title = { Text(if (lang == "zh") "选择补全候选" else "Choose completion") },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(completionChoices) { candidate ->
                        TextButton(onClick = {
                            val request = completionRequest
                            if (request != null && commandInput == completionInput &&
                                AdbManager.connected.value && !AdbManager.isFastbootMode.value &&
                                completionConnection?.isAuthenticated == true &&
                                AdbManager.connection === completionConnection) {
                                commandInput = TerminalCompletion.apply(request, candidate.value, true)
                            }
                            completionChoices = emptyList()
                        }) {
                            Text(candidate.value, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { completionChoices = emptyList() }) { Text(s.cancel) }
            }
        )
    }

    // 右上角齿轮打开的日志配置弹窗
    if (showSettingsDialog) {
        LogSettingsDialog(
            s = s,
            context = context,
            onDismiss = { showSettingsDialog = false }
        )
    }
}

/**
 * 设置页分支视图：TerminalShellInputBar
 */
@Composable
private fun TerminalShellInputBar(
    s: Strings,
    terminalMode: TerminalMode,
    connected: Boolean,
    isFastboot: Boolean,
    commandInput: String,
    isCtrlActive: Boolean,
    isExecuting: Boolean,
    extraSymbols: List<String>,
    focusRequester: FocusRequester,
    submitCommand: (String) -> Unit,
    onExtraKeyClick: (String) -> Unit,
    onInputTextChange: (String) -> Unit,
) {
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
                    onClick = { onExtraKeyClick("Enter") },
                    enabled = connected && !isFastboot,
                    label = { Text(s.terminalKeyEnter, fontSize = 12.sp) }
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

/**
 * 设置页分支视图：TerminalMainViewport
 */
@Composable
private fun ColumnScope.TerminalMainViewport(
    s: Strings,
    terminalMode: TerminalMode,
    context: Context,
    filteredLogs: List<LogLine>,
    shellLines: List<TerminalLine>,
    listState: LazyListState,
    logListState: LazyListState,
    focusRequester: FocusRequester,
    isCapturing: Boolean,
    highlightSpecial: Boolean,
    receivedCount: Long,
    filteredDropCount: Long,
    ringDropCount: Long,
) {
    if (terminalMode == TerminalMode.CONTROL) {
        ControlModeScreen(
            s = s,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )
    } else if (terminalMode == TerminalMode.FILE_MANAGER) {
        FileManagerScreen(s = s, modifier = Modifier.weight(1f).fillMaxWidth())
    } else if (terminalMode == TerminalMode.APP_MANAGER) {
        AppManagerScreen(
            s = s,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )
    } else {
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
                            text = if (isCapturing) s.logEmptyCapturing else s.logEmptyIdle,
                            color = Color(0xFF64748B),
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        if (isCapturing && (receivedCount > 0L || filteredDropCount > 0L)) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = s.logStatsLine.format(receivedCount, filteredDropCount, ringDropCount),
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
                                    AdbManager.log(I18n.current.logCopiedLine)
                                }
                        )
                    }
                }
            }
        }
    }
    } // end non-CONTROL main viewport
}

/**
 * 设置页分支视图：TerminalLogFilterBar
 */
@Composable
private fun TerminalLogFilterBar(
    s: Strings,
    terminalMode: TerminalMode,
    typeFilter: LogTypeFilter,
    useRegex: Boolean,
    searchQuery: String,
) {
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
                    label = {
                        Text(
                            when (tf) {
                                LogTypeFilter.ALL -> s.logTypeAll
                                LogTypeFilter.ERRORS -> s.logTypeErrors
                                LogTypeFilter.CRASH_STACK -> s.logTypeCrashStack
                                LogTypeFilter.ANR -> s.logTypeAnr
                                LogTypeFilter.SYSTEM -> s.logTypeSystem
                            },
                            fontSize = 11.sp
                        )
                    }
                )
            }
            item {
                FilterChip(
                    selected = useRegex,
                    onClick = {
                        LogManager.useRegex.value = !useRegex
                        LogManager.saveSettings()
                    },
                    label = { Text(if (useRegex) s.logRegexOn else s.logRegex, fontSize = 11.sp) }
                )
            }
            item {
                AssistChip(
                    onClick = { LogManager.applyQuickPreset("crash") },
                    label = { Text(s.logPresetCrash, fontSize = 11.sp) }
                )
            }
            item {
                AssistChip(
                    onClick = { LogManager.applyQuickPreset("errors") },
                    label = { Text(s.logPresetError, fontSize = 11.sp) }
                )
            }
            item {
                AssistChip(
                    onClick = { LogManager.applyQuickPreset("all") },
                    label = { Text(s.logResetFilter, fontSize = 11.sp) }
                )
            }
        }
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { LogManager.searchQuery.value = it },
            placeholder = {
                Text(
                    if (useRegex) s.logSearchHintRegex else s.logSearchHint,
                    fontSize = 12.sp
                )
            },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { LogManager.searchQuery.value = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = s.logClearSearchCd, modifier = Modifier.size(16.dp))
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
}

/**
 * 设置页分支视图：TerminalTopBar
 */
@Composable
private fun TerminalTopBar(
    s: Strings,
    context: Context,
    terminalMode: TerminalMode,
    menuExpanded: Boolean,
    showSettingsDialog: Boolean,
    connected: Boolean,
    deviceName: String,
    isFastboot: Boolean,
    filteredLogs: List<LogLine>,
    isCapturing: Boolean,
    shellLines: List<TerminalLine>,
    onTerminalModeChange: (TerminalMode) -> Unit,
    onMenuExpandedChange: (Boolean) -> Unit,
    onShowSettingsChange: (Boolean) -> Unit,
    onExtraKeyClick: (String) -> Unit,
) {
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
                    onClick = { onMenuExpandedChange(true) },
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
                    onDismissRequest = { onMenuExpandedChange(false) }
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
                            onTerminalModeChange(TerminalMode.SHELL)
                            onMenuExpandedChange(false)
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
                            onTerminalModeChange(TerminalMode.LOG)
                            onMenuExpandedChange(false)
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Notes,
                                contentDescription = null,
                                tint = if (terminalMode == TerminalMode.LOG) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )

                    HorizontalDivider()

                    DropdownMenuItem(
                        text = {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = s.terminalModeControl,
                                        fontWeight = if (terminalMode == TerminalMode.CONTROL) FontWeight.Bold else FontWeight.Normal,
                                        color = if (terminalMode == TerminalMode.CONTROL) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                    if (terminalMode == TerminalMode.CONTROL) {
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
                                    text = s.terminalModeControlDesc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            onTerminalModeChange(TerminalMode.CONTROL)
                            onMenuExpandedChange(false)
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Videocam,
                                contentDescription = null,
                                tint = if (terminalMode == TerminalMode.CONTROL) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )

                    HorizontalDivider()

                    DropdownMenuItem(
                        text = {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = s.terminalModeAppManager,
                                        fontWeight = if (terminalMode == TerminalMode.APP_MANAGER) FontWeight.Bold else FontWeight.Normal,
                                        color = if (terminalMode == TerminalMode.APP_MANAGER) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                    if (terminalMode == TerminalMode.APP_MANAGER) {
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
                                    text = s.terminalModeAppManagerDesc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            onTerminalModeChange(TerminalMode.APP_MANAGER)
                            onMenuExpandedChange(false)
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Apps,
                                contentDescription = null,
                                tint = if (terminalMode == TerminalMode.APP_MANAGER) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = s.terminalModeFileManager,
                                        fontWeight = if (terminalMode == TerminalMode.FILE_MANAGER) FontWeight.Bold else FontWeight.Normal,
                                        color = if (terminalMode == TerminalMode.FILE_MANAGER) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                    if (terminalMode == TerminalMode.FILE_MANAGER) {
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
                                    text = s.terminalModeFileManagerDesc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            onTerminalModeChange(TerminalMode.FILE_MANAGER)
                            onMenuExpandedChange(false)
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Folder,
                                contentDescription = null,
                                tint = if (terminalMode == TerminalMode.FILE_MANAGER) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                }
            }

            Spacer(Modifier.width(4.dp))

            // 模式与状态标签
            Column {
                val modeLabel = when (terminalMode) {
                    TerminalMode.SHELL -> s.terminalModeShell
                    TerminalMode.LOG -> s.terminalModeAdb
                    TerminalMode.CONTROL -> s.terminalModeControl
                    TerminalMode.APP_MANAGER -> s.terminalModeAppManager
                    TerminalMode.FILE_MANAGER -> s.terminalModeFileManager
                }
                val statusText = when (terminalMode) {
                    TerminalMode.SHELL -> {
                        if (connected) deviceName.ifBlank { s.statusConnected } else s.statusDisconnected
                    }
                    TerminalMode.LOG -> {
                        when {
                            isCapturing -> s.logStatusCapturing.format(filteredLogs.size)
                            ShizukuManager.isAuthorized.value -> s.logStatusShizukuReady
                            connected -> s.logStatusAdbReady
                            else -> s.logStatusReady
                        }
                    }
                    TerminalMode.CONTROL -> {
                        when {
                            isFastboot -> s.controlStatusFastboot
                            connected -> s.controlStatusConnected.format(deviceName.ifBlank { s.statusConnected })
                            else -> s.statusDisconnected
                        }
                    }
                    TerminalMode.APP_MANAGER, TerminalMode.FILE_MANAGER -> {
                        if (connected) deviceName.ifBlank { s.statusConnected } else s.statusDisconnected
                    }
                }
                val statusColor = when (terminalMode) {
                    TerminalMode.SHELL -> {
                        if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    }
                    TerminalMode.LOG -> {
                        if (isCapturing) Color(0xFF4ADE80) else MaterialTheme.colorScheme.primary
                    }
                    TerminalMode.CONTROL -> {
                        if (connected && !isFastboot) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    }
                    TerminalMode.APP_MANAGER, TerminalMode.FILE_MANAGER -> {
                        if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    }
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
                        color = statusColor,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (terminalMode == TerminalMode.LOG) {
                IconButton(
                    onClick = { onShowSettingsChange(true) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = s.logSettingsCd,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            if (terminalMode == TerminalMode.SHELL || terminalMode == TerminalMode.LOG) {
                var actionsExpanded by remember(terminalMode) { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { actionsExpanded = true }) {
                        Icon(Icons.Filled.Menu, contentDescription = s.menuTitle)
                    }
                    DropdownMenu(
                        expanded = actionsExpanded,
                        onDismissRequest = { actionsExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(s.terminalCopy) },
                            leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                            onClick = {
                                actionsExpanded = false
                                val logMode = terminalMode == TerminalMode.LOG
                                val output = if (logMode) filteredLogs.joinToString("\n") { it.raw }
                                    else shellLines.joinToString("\n") { it.text }
                                if (output.isNotBlank()) {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val label = if (logMode) "aoooa-adb logs" else "aoooa-adb terminal"
                                    cm.setPrimaryClip(ClipData.newPlainText(label, output))
                                    AdbManager.log(s.copyLog + " ✓")
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (terminalMode == TerminalMode.LOG) s.clear else s.terminalClear) },
                            leadingIcon = { Icon(Icons.Filled.DeleteSweep, contentDescription = null) },
                            onClick = {
                                actionsExpanded = false
                                if (terminalMode == TerminalMode.LOG) LogManager.clearLogs()
                                else onExtraKeyClick("CLEAR")
                            }
                        )
                    }
                }
            }
        }
    }
}
