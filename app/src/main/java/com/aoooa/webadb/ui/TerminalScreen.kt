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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aoooa.webadb.AdbManager
import com.aoooa.webadb.ui.i18n.Strings

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

    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    // 常用快捷符号定义
    val extraSymbols = listOf("|", "&", "$", "~", "/", "-", "_", "*", "=", "\"", "'", ":", ";")

    // 初始化终端欢迎文案与提示符
    LaunchedEffect(connected) {
        if (terminalLines.isEmpty()) {
            if (connected) {
                terminalLines.add(s.terminalHint)
                terminalLines.add(AdbManager.getShellPrompt())
            } else {
                terminalLines.add("❌ ${s.terminalNotConnected}")
            }
        }
    }

    // 自动触底滚动
    LaunchedEffect(terminalLines.size) {
        if (terminalLines.isNotEmpty()) {
            listState.animateScrollToItem(terminalLines.size - 1)
        }
    }

    // 发送与执行用户 Shell 命令（严格保证即时回显、永不蒸发）
    fun submitCommand(cmdText: String) {
        val trimmed = cmdText.trim()
        if (trimmed.isEmpty()) return

        if (!connected) {
            terminalLines.add("❌ ${s.terminalNotConnected}")
            return
        }

        // 1. 记录历史命令
        if (commandHistory.isEmpty() || commandHistory.last() != trimmed) {
            commandHistory.add(trimmed)
            if (commandHistory.size > 100) commandHistory.removeAt(0)
        }
        historyIndex = -1

        // 2. 立即在控制台上屏回显：提示符 + 用户输入的命令
        val prompt = AdbManager.getShellPrompt()
        // 如果最后一行是空白提示符，先替换为当前命令行
        if (terminalLines.isNotEmpty() && terminalLines.last().trim() == prompt.trim()) {
            terminalLines.removeAt(terminalLines.size - 1)
        }
        terminalLines.add("$prompt$trimmed")

        // 3. 清空输入框
        commandInput = ""
        isExecuting = true

        // 4. 调用底层可靠执行通道，执行完成后追加新一行提示符
        AdbManager.execTerminal(trimmed) {
            isExecuting = false
            terminalLines.add(AdbManager.getShellPrompt())
        }
    }

    // 处理辅助栏快捷按键
    fun onExtraKeyClick(key: String) {
        when (key) {
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
                if (connected) {
                    terminalLines.add(AdbManager.getShellPrompt())
                }
            }
            "Ctrl+C" -> {
                commandInput = ""
                historyIndex = -1
                if (connected) {
                    terminalLines.add("^C")
                    terminalLines.add(AdbManager.getShellPrompt())
                }
            }
            "Tab" -> {
                commandInput += "    "
            }
            "Esc" -> {
                commandInput = ""
                historyIndex = -1
            }
            else -> {
                commandInput += key
            }
        }
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
                        val allOutput = terminalLines.joinToString("\n")
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

        // 终端主视窗（经典黑客深蓝黑背景，支持长文本滚屏）
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
                items(terminalLines) { line ->
                    // 过滤 ANSI 控制序列字符，保持输出干净易读
                    val cleanLine = line.replace(Regex("\u001B\\[[;?0-9]*[a-zA-Z]"), "")
                    Text(
                        text = cleanLine,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 13.sp,
                            lineHeight = 17.sp
                        ),
                        fontFamily = FontFamily.Monospace,
                        color = when {
                            cleanLine.contains("Error", ignoreCase = true) || cleanLine.contains("FAIL") || cleanLine.startsWith("❌") -> Color(0xFFF87171)
                            cleanLine.contains("Success", ignoreCase = true) || cleanLine.contains("OKAY") -> Color(0xFF4ADE80)
                            cleanLine.contains(":/ $") || cleanLine.contains(":/ #") || cleanLine.startsWith("shell@") || cleanLine.startsWith("root@") -> Color(0xFF38BDF8)
                            cleanLine.startsWith("^C") -> Color(0xFFFBBF24)
                            else -> Color(0xFFF8FAFC)
                        }
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
                    onClick = { onExtraKeyClick("Ctrl+C") },
                    label = { Text("Ctrl+C", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                )
            }
            item {
                AssistChip(
                    onClick = { onExtraKeyClick("Esc") },
                    label = { Text("Esc", fontSize = 12.sp) }
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
                onValueChange = { commandInput = it },
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
