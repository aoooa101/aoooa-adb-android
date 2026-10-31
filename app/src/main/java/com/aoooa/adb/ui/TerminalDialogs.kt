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
 * 日志配置管理弹窗（包含 Shizuku 连接入口、日志来源单选、白名单/黑名单互斥管理与应用选择器）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogSettingsDialog(
    s: Strings,
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
                        text = s.logSettingsTitle,
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
                        Icon(Icons.Filled.Close, contentDescription = s.logCloseCd, modifier = Modifier.size(20.dp))
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
                            text = s.logShizukuTitle,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = s.logShizukuDesc,
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
                                Text(s.logConnectedDebugDisconnect, fontSize = 12.sp)
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
                                    Text(s.logShizukuAuthorized, fontSize = 12.sp)
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
                                    Text(if (isShizukuAlive) s.logShizukuConnectAuth else s.logShizukuConnectNeedService, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 2. 日志来源选择
                Text(
                    text = s.logSourceTitle,
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
                        label = { Text(s.logSourceFullDevice, fontSize = 12.sp) },
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
                        label = { Text(s.logSourceTargetApps, fontSize = 12.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (logSource == LogSource.TARGET_APPS && whitelist.isEmpty() && filterMode != FilterMode.BLACKLIST) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = s.logSourceTargetAppsHint,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 11.sp
                    )
                }

                Spacer(Modifier.height(12.dp))

                // 2.1 完整抓取参数
                Text(
                    text = s.logCaptureParamsTitle,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = s.logBufferPrefix + when (bufferMode) {
                                        LogBufferMode.DEFAULT -> s.logBufferDefault
                                        LogBufferMode.ALL -> s.logBufferAll
                                        LogBufferMode.MAIN -> s.logBufferMain
                                        LogBufferMode.SYSTEM -> s.logBufferSystem
                                        LogBufferMode.CRASH -> s.logBufferCrash
                                        LogBufferMode.EVENTS -> s.logBufferEvents
                                    },
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
                                        LogBufferMode.DEFAULT -> s.logBufferDefault
                                        LogBufferMode.ALL -> s.logBufferAll
                                        LogBufferMode.MAIN -> s.logBufferMain
                                        LogBufferMode.SYSTEM -> s.logBufferSystem
                                        LogBufferMode.CRASH -> s.logBufferCrash
                                        LogBufferMode.EVENTS -> s.logBufferEvents
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
                        Text(s.logIncludeHistoryLabel, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(
                            text = s.logIncludeHistoryHint,
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
                    Text(s.logHistoryLines.format(historyLines), fontSize = 12.sp)
                    Slider(
                        value = historyLines.toFloat(),
                        onValueChange = { historyLines = it.toInt().coerceIn(50, 5000) },
                        onValueChangeFinished = { LogManager.saveSettings() },
                        valueRange = 50f..5000f,
                        steps = 98
                    )
                }

                Spacer(Modifier.height(4.dp))
                Text(s.logMinLevelLabel, fontSize = 13.sp, fontWeight = FontWeight.Medium)
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
                Text(s.logRingBufferLimit.format(maxBufferLines), fontSize = 12.sp)
                Slider(
                    value = maxBufferLines.toFloat(),
                    onValueChange = { maxBufferLines = it.toInt().coerceIn(1000, 30000) },
                    onValueChangeFinished = { LogManager.saveSettings() },
                    valueRange = 1000f..30000f,
                    steps = 28
                )

                Text(
                    text = s.logStatsDetailed.format(receivedCount, filteredDropCount, ringDropCount)
                        + if (isCapturing) s.logStatsNeedRestart else "",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(12.dp))

                // 2.2 显示过滤系统（级别多选 / 类型 / Tag / 关键词 / 正则 / 高亮）
                Text(
                    text = s.logDisplayFilterTitle,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(s.logLevelMultiSelect, fontSize = 12.sp, fontWeight = FontWeight.Medium)
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
                Text(s.logTypeFilterTitle, fontSize = 12.sp, fontWeight = FontWeight.Medium)
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
                }

                Spacer(Modifier.height(8.dp))
                Text(s.logQuickPresets, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    AssistChip(onClick = { LogManager.applyQuickPreset("all") }, label = { Text(s.logPresetAll, fontSize = 11.sp) })
                    AssistChip(onClick = { LogManager.applyQuickPreset("errors") }, label = { Text(s.logPresetErrorsOnly, fontSize = 11.sp) })
                    AssistChip(onClick = { LogManager.applyQuickPreset("crash") }, label = { Text(s.logPresetCrashStack, fontSize = 11.sp) })
                    AssistChip(onClick = { LogManager.applyQuickPreset("anr") }, label = { Text(s.logPresetAnr, fontSize = 11.sp) })
                    AssistChip(onClick = { LogManager.applyQuickPreset("system") }, label = { Text(s.logPresetSystem, fontSize = 11.sp) })
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = tagInclude,
                    onValueChange = {
                        tagInclude = it
                    },
                    label = { Text(s.logTagInclude, fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = tagExclude,
                    onValueChange = { tagExclude = it },
                    label = { Text(s.logTagExclude, fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = keywordInclude,
                    onValueChange = { keywordInclude = it },
                    label = { Text(s.logKeywordInclude, fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = keywordExclude,
                    onValueChange = { keywordExclude = it },
                    label = { Text(s.logKeywordExclude, fontSize = 12.sp) },
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
                    Text(s.logUseRegexLabel, fontSize = 13.sp)
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
                        Text(s.logHighlightLabel, fontSize = 13.sp)
                        Text(s.logHighlightHint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Text(s.logSaveFilterRules, fontSize = 12.sp)
                }

                Spacer(Modifier.height(12.dp))

                // 3. 应用过滤模式（互斥）
                Text(
                    text = s.logAppFilterTitle,
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
                        label = { Text(s.logFilterNone, fontSize = 11.sp) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = filterMode == FilterMode.WHITELIST,
                        onClick = {
                            filterMode = FilterMode.WHITELIST
                            LogManager.saveSettings()
                        },
                        label = { Text(s.logFilterWhitelist, fontSize = 11.sp) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = filterMode == FilterMode.BLACKLIST,
                        onClick = {
                            filterMode = FilterMode.BLACKLIST
                            LogManager.saveSettings()
                        },
                        label = { Text(s.logFilterBlacklist, fontSize = 11.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(8.dp))

                // 白名单 / 黑名单内容区域
                if (filterMode == FilterMode.WHITELIST) {
                    Text(
                        text = s.logWhitelistDesc,
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
                                        contentDescription = s.logDeleteCd,
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
                        Text(s.logAddWhitelistApp, fontSize = 12.sp)
                    }
                } else if (filterMode == FilterMode.BLACKLIST) {
                    Text(
                        text = s.logBlacklistDesc,
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
                                        contentDescription = s.logDeleteCd,
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
                        Text(s.logAddBlacklistApp, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    // 确认断开外部调试连接对话框
    if (showDisconnectConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirmDialog = false },
            title = { Text(s.logDisconnectConfirmTitle) },
            text = { Text(s.logDisconnectConfirmMsg) },
            confirmButton = {
                TextButton(
                    onClick = {
                        AdbManager.disconnect()
                        showDisconnectConfirmDialog = false
                    }
                ) {
                    Text(s.logDisconnectAndSwitch, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirmDialog = false }) {
                    Text(s.cancel)
                }
            }
        )
    }

    // 添加白名单/黑名单包名对话框
    showAddRuleDialog?.let { targetMode ->
        AddAppRuleDialog(
            s = s,
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
    s: Strings,
    context: Context,
    mode: FilterMode,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var pkgInput by remember { mutableStateOf("") }
    var showAppPicker by remember { mutableStateOf(false) }

    val modeTitle = if (mode == FilterMode.WHITELIST) s.logAddWhitelistApp else s.logAddBlacklistApp

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(modeTitle, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = s.logAddRuleHint,
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
                        placeholder = { Text(s.logPkgPlaceholder, fontSize = 12.sp) },
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
                        Text(s.logPickApp, fontSize = 12.sp)
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
                Text(s.confirm)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(s.cancel)
            }
        }
    )

    if (showAppPicker) {
        AppPickerDialog(
            s = s,
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
    s: Strings,
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
                    Text(s.logPickAppTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = s.logCloseCd, modifier = Modifier.size(20.dp))
                    }
                }

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = searchKey,
                    onValueChange = { searchKey = it },
                    placeholder = { Text(s.logSearchAppHint, fontSize = 12.sp) },
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
                        Text(s.logNoMatchingApp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
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
