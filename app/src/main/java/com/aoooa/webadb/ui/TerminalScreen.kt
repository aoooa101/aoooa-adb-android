package com.aoooa.webadb.ui

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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
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

    val terminalLines = AdbManager.terminalLines
    var currentTyping by remember { mutableStateOf("") }
    var extraBarInput by remember { mutableStateOf("") }
    var isCtrlActive by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // 常用快捷符号定义
    val extraSymbols = listOf("|", "&", "$", "~", "/", "-", "_", "*", "=", "\"", "'", ":", ";")

    // 进入终端界面时自动确保交互式长连接活跃（切 Tab 永不掉线、历史永不丢失）
    LaunchedEffect(connected) {
        if (connected && !isFastboot) {
            AdbManager.ensureInteractiveShell()
        }
    }

    // 自动触底滚动
    LaunchedEffect(terminalLines.size) {
        if (terminalLines.isNotEmpty()) {
            listState.animateScrollToItem(terminalLines.size - 1)
        }
    }

    // 发送用户命令或换行
    fun submitCommand(cmd: String) {
        val toSend = cmd + "\n"
        if (connected) {
            AdbManager.sendInteractiveInput(toSend)
        } else {
            terminalLines.add("❌ ${s.terminalNotConnected}")
        }
        currentTyping = ""
        extraBarInput = ""
    }

    // 处理辅助栏按键
    fun onExtraKeyClick(key: String) {
        when (key) {
            "Ctrl" -> isCtrlActive = !isCtrlActive
            "Tab" -> {
                AdbManager.sendInteractiveBytes(byteArrayOf(0x09)) // 0x09 Tab 补全
            }
            "Esc" -> {
                isCtrlActive = false
                AdbManager.sendInteractiveBytes(byteArrayOf(0x1B)) // 0x1B Esc
                currentTyping = ""
                extraBarInput = ""
            }
            "↑" -> {
                AdbManager.sendInteractiveBytes("\u001b[A".toByteArray(Charsets.US_ASCII)) // ANSI Up
            }
            "↓" -> {
                AdbManager.sendInteractiveBytes("\u001b[B".toByteArray(Charsets.US_ASCII)) // ANSI Down
            }
            "←" -> {
                AdbManager.sendInteractiveBytes("\u001b[D".toByteArray(Charsets.US_ASCII)) // ANSI Left
            }
            "→" -> {
                AdbManager.sendInteractiveBytes("\u001b[C".toByteArray(Charsets.US_ASCII)) // ANSI Right
            }
            "Ctrl+C" -> {
                AdbManager.sendInteractiveBytes(byteArrayOf(0x03)) // 0x03 SIGINT 中断
                isCtrlActive = false
            }
            "Ctrl+D" -> {
                AdbManager.sendInteractiveBytes(byteArrayOf(0x04)) // 0x04 EOF 退出
                isCtrlActive = false
            }
            "Ctrl+L" -> {
                AdbManager.sendInteractiveBytes(byteArrayOf(0x0C)) // 0x0C 清屏
                AdbManager.clearTerminal()
                isCtrlActive = false
            }
            "CLEAR" -> {
                AdbManager.clearTerminal()
            }
            else -> {
                if (isCtrlActive) {
                    val ch = key.firstOrNull()?.uppercaseChar()
                    if (ch != null && ch in 'A'..'Z') {
                        val ctrlByte = (ch.code - 'A'.code + 1).toByte()
                        AdbManager.sendInteractiveBytes(byteArrayOf(ctrlByte))
                    } else {
                        AdbManager.sendInteractiveInput(key)
                    }
                    isCtrlActive = false
                } else {
                    AdbManager.sendInteractiveInput(key)
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        // 顶部精简状态与清屏栏
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

            TextButton(
                onClick = { AdbManager.clearTerminal() },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(s.terminalClear, fontSize = 13.sp)
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
                    keyboardController?.show()
                }
                .padding(10.dp)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (terminalLines.isEmpty()) {
                    item {
                        Text(
                            text = if (connected) s.terminalHint else s.terminalNotConnected,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF64748B)
                        )
                    }
                } else {
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
                                cleanLine.endsWith("$") || cleanLine.endsWith("#") || cleanLine.contains(":/") -> Color(0xFF38BDF8)
                                else -> Color(0xFFF8FAFC)
                            }
                        )
                    }
                }

                // 当前正在键入的光标行
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = currentTyping,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 13.sp,
                                lineHeight = 17.sp
                            ),
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF38BDF8)
                        )
                        Box(
                            modifier = Modifier
                                .width(8.dp)
                                .height(14.dp)
                                .background(Color(0xFF38BDF8).copy(alpha = 0.8f))
                        )
                    }
                }
            }

            // 隐藏输入控件，捕获软键盘打字与回车事件
            BasicTextField(
                value = currentTyping,
                onValueChange = { currentTyping = it },
                modifier = Modifier
                    .size(1.dp)
                    .focusRequester(focusRequester),
                textStyle = TextStyle(color = Color.Transparent),
                cursorBrush = SolidColor(Color.Transparent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        submitCommand(currentTyping)
                    }
                )
            )
        }

        Spacer(Modifier.height(8.dp))

        // 底部横向滑动辅助按键栏（滑动到最末尾无缝展开精简输入框）
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
                    onClick = { onExtraKeyClick("Tab") },
                    label = { Text("Tab", fontSize = 13.sp) }
                )
            }
            item {
                AssistChip(
                    onClick = { onExtraKeyClick("Esc") },
                    label = { Text("Esc", fontSize = 13.sp) }
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
                    onClick = { onExtraKeyClick("←") },
                    label = { Text("←", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                )
            }
            item {
                AssistChip(
                    onClick = { onExtraKeyClick("→") },
                    label = { Text("→", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                )
            }

            if (isCtrlActive) {
                item {
                    FilledTonalButton(
                        onClick = { onExtraKeyClick("Ctrl+C") },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Ctrl+C", fontSize = 12.sp)
                    }
                }
                item {
                    FilledTonalButton(
                        onClick = { onExtraKeyClick("Ctrl+D") },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Ctrl+D", fontSize = 12.sp)
                    }
                }
                item {
                    FilledTonalButton(
                        onClick = { onExtraKeyClick("Ctrl+L") },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Ctrl+L", fontSize = 12.sp)
                    }
                }
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

            // 滑动到最末尾：无缝展开精简输入框（软键盘回车直接发送执行）
            item {
                OutlinedTextField(
                    value = extraBarInput,
                    onValueChange = { extraBarInput = it },
                    placeholder = { Text(s.terminalPlaceholder, fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.width(220.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (extraBarInput.isNotBlank()) {
                                submitCommand(extraBarInput)
                            }
                        }
                    )
                )
            }
        }
    }
}
