package com.aoooa.webadb.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.aoooa.webadb.AdbManager
import com.aoooa.webadb.Prefs
import com.aoooa.webadb.model.CommandItem
import com.aoooa.webadb.ui.i18n.Strings

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CommandsScreen(
    s: Strings,
    lang: String,
    onExecuteCommand: (String) -> Unit,
    onNavigateToHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val connected by AdbManager.connected
    val isFastboot by AdbManager.isFastbootMode
    val model by AdbManager.model
    val os by AdbManager.os
    val battery by AdbManager.battery
    val selinux by AdbManager.selinux
    val tcpip5555Enabled by AdbManager.isTcpip5555Enabled

    var commandList by remember { mutableStateOf(Prefs.loadCommands()) }
    var customCategories by remember { mutableStateOf(Prefs.loadCustomCategories()) }
    var selectedCategory by remember { mutableStateOf("all") }
    var searchQuery by remember { mutableStateOf("") }
    var isManageMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    val collapsedCategories = remember { mutableStateListOf<String>() }

    // 对话框状态
    var showAddDialog by remember { mutableStateOf(false) }
    var showAddCatDialog by remember { mutableStateOf(false) }
    var editingItem by remember { mutableStateOf<CommandItem?>(null) }
    var itemPendingDelete by remember { mutableStateOf<CommandItem?>(null) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    var showBatchMoveDialog by remember { mutableStateOf(false) }

    // 每次进入页面时自动同步最新的持久化配置（保证导入备份后瞬间生效）
    LaunchedEffect(Unit) {
        commandList = Prefs.loadCommands()
        customCategories = Prefs.loadCustomCategories()
    }

    // 硬核功能弹窗状态
    var showPushDialog by remember { mutableStateOf(false) }
    var showInstallDialog by remember { mutableStateOf(false) }
    var showFlashDialog by remember { mutableStateOf(false) }

    var selectedPushUri by remember { mutableStateOf<Uri?>(null) }
    var selectedPushName by remember { mutableStateOf("") }
    var pushTargetDir by remember { mutableStateOf("/sdcard/Download/") }

    var selectedInstallUri by remember { mutableStateOf<Uri?>(null) }
    var selectedInstallName by remember { mutableStateOf("") }

    var selectedFlashUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFlashName by remember { mutableStateOf("") }
    var flashPartition by remember { mutableStateOf("boot") }

    // 指令就地执行结果弹窗状态
    var resultDialogTitle by remember { mutableStateOf("") }
    var resultDialogCommand by remember { mutableStateOf("") }
    var resultDialogOutput by remember { mutableStateOf<String?>(null) }
    var isRunningCommand by remember { mutableStateOf(false) }
    var showResultDialog by remember { mutableStateOf(false) }

    fun runAndShowResult(item: CommandItem) {
        resultDialogTitle = if (lang == "zh") item.nameZh else item.nameEn
        resultDialogCommand = item.command
        resultDialogOutput = null
        isRunningCommand = true
        showResultDialog = true

        Thread {
            val output = if (connected) {
                AdbManager.execCapture(item.command).ifBlank { s.logNoOutput }
            } else {
                s.terminalNotConnected
            }
            resultDialogOutput = output
            isRunningCommand = false
        }.start()
    }

    // 文件选择器 Launchers
    val pushPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedPushUri = uri
            selectedPushName = getFileNameFromUri(context, uri)
        }
    }

    val installPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedInstallUri = uri
            selectedInstallName = getFileNameFromUri(context, uri)
        }
    }

    val flashPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedFlashUri = uri
            selectedFlashName = getFileNameFromUri(context, uri)
        }
    }

    fun refreshData() {
        commandList = Prefs.loadCommands()
        customCategories = Prefs.loadCustomCategories()
    }

    fun getCategoryDisplayName(catKey: String): String {
        return when (catKey) {
            "all" -> s.catAll
            "framework" -> s.catFramework
            "system" -> s.catSystem
            "power" -> s.catPower
            "fastboot" -> s.catFastboot
            "custom" -> s.catCustom
            else -> catKey
        }
    }

    val allCategoryKeys = remember(customCategories) {
        listOf("all", "framework", "system", "power", "fastboot") + customCategories
    }

    val filteredList = remember(commandList, selectedCategory, searchQuery, lang) {
        commandList.filter { item ->
            val matchCat = if (selectedCategory == "all") true else item.category == selectedCategory
            val name = if (lang == "zh") item.nameZh else item.nameEn
            val matchQuery = if (searchQuery.isBlank()) true else {
                name.contains(searchQuery, ignoreCase = true) ||
                    item.nameZh.contains(searchQuery, ignoreCase = true) ||
                    item.nameEn.contains(searchQuery, ignoreCase = true) ||
                    item.command.contains(searchQuery, ignoreCase = true)
            }
            matchCat && matchQuery
        }
    }

    val groupedCommands = remember(filteredList) {
        filteredList.groupBy { it.category }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(s.tabCommands) },
            actions = {
                if (isManageMode) {
                    TextButton(onClick = {
                        if (selectedIds.size == filteredList.size) {
                            selectedIds.clear()
                        } else {
                            selectedIds.clear()
                            selectedIds.addAll(filteredList.map { it.id })
                        }
                    }) {
                        Text(if (selectedIds.size == filteredList.size && filteredList.isNotEmpty()) s.cmdDeselectAll else s.cmdSelectAll)
                    }
                    IconButton(onClick = {
                        isManageMode = false
                        selectedIds.clear()
                    }) {
                        Icon(Icons.Filled.Done, contentDescription = s.cmdDone)
                    }
                } else {
                    IconButton(onClick = { showAddCatDialog = true }) {
                        Icon(Icons.Filled.CreateNewFolder, contentDescription = s.cmdAddCategory)
                    }
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Filled.Add, contentDescription = s.cmdAddTitle)
                    }
                    IconButton(onClick = { isManageMode = true }) {
                        Icon(Icons.Filled.Checklist, contentDescription = s.cmdManage)
                    }
                }
            }
        )

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. 实时搜索框
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(s.searchPlaceholder) },
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Filled.Clear, contentDescription = null)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            // 2. 已连接设备状态与控制看板
            if (connected) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = s.deviceInfoTitle,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )

                            // 硬件四项属性
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (model.isNotBlank()) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Filled.Smartphone, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                            Spacer(Modifier.width(6.dp))
                                            Text("${s.model}: $model", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    if (battery.isNotBlank()) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Filled.BatteryStd, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                            Spacer(Modifier.width(6.dp))
                                            Text("${s.bat}: $battery", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (os.isNotBlank()) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Filled.Android, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                            Spacer(Modifier.width(6.dp))
                                            Text("${s.os}: $os", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    if (selinux.isNotBlank()) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Filled.Security, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                            Spacer(Modifier.width(6.dp))
                                            Text("${s.sel}: $selinux", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }

                            if (!isFastboot) {
                                HorizontalDivider()

                                // 5555 无线调试开关
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(6.dp))
                                        Text("${s.tcpip5555StatusLabel}:", style = MaterialTheme.typography.bodyMedium)
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = if (tcpip5555Enabled) s.statusOn else s.statusOff,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = if (tcpip5555Enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                        )
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Button(
                                            onClick = { AdbManager.setTcpip5555(true) },
                                            enabled = !tcpip5555Enabled,
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                        ) {
                                            Icon(Icons.Filled.Power, null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(s.turnOn)
                                        }
                                        OutlinedButton(
                                            onClick = { AdbManager.setTcpip5555(false) },
                                            enabled = tcpip5555Enabled,
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                        ) {
                                            Icon(Icons.Filled.PowerOff, null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(s.turnOff)
                                        }
                                    }
                                }
                            }

                            // 断开连接按钮
                            Button(
                                onClick = { AdbManager.disconnect() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.LinkOff, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(s.disconnect)
                            }
                        }
                    }
                }
            }

            // 3. 硬核工具专属卡片区（文件传输、流式安装、分区刷写）
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isFastboot) {
                        Button(
                            onClick = { showPushDialog = true },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Filled.DriveFileMove, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(s.pushTitle.substringBefore(" ("), style = MaterialTheme.typography.labelMedium)
                        }
                        Button(
                            onClick = { showInstallDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Filled.InstallMobile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(s.installTitle.substringBefore(" ("), style = MaterialTheme.typography.labelMedium)
                        }
                    } else {
                        Button(
                            onClick = { showFlashDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Filled.FlashOn, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(s.flashTitle.substringBefore(" ("), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            // 4. 分类横向滚动标签栏
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(allCategoryKeys) { catKey ->
                        FilterChip(
                            selected = selectedCategory == catKey,
                            onClick = { selectedCategory = catKey },
                            label = { Text(getCategoryDisplayName(catKey)) }
                        )
                    }
                }
            }

            // 5. 快捷指令分组列表（支持折叠/展开）
            if (filteredList.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(s.cmdNoCommands, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                    }
                }
            } else {
                groupedCommands.forEach { (catKey, itemsInCat) ->
                    val isCollapsed = collapsedCategories.contains(catKey)

                    item(key = "header_$catKey") {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isCollapsed) collapsedCategories.remove(catKey)
                                    else collapsedCategories.add(catKey)
                                }
                                .padding(vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        if (isCollapsed) Icons.Filled.ChevronRight else Icons.Filled.ExpandMore,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = getCategoryDisplayName(catKey),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "(${itemsInCat.size})",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    if (!isCollapsed) {
                        items(itemsInCat, key = { it.id }) { item ->
                            val isChecked = selectedIds.contains(item.id)
                            val displayName = if (lang == "zh") item.nameZh.ifBlank { item.nameEn } else item.nameEn.ifBlank { item.nameZh }

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            if (isManageMode) {
                                                if (isChecked) selectedIds.remove(item.id) else selectedIds.add(item.id)
                                            } else {
                                                runAndShowResult(item)
                                            }
                                        },
                                        onLongClick = {
                                            if (!isManageMode) {
                                                editingItem = item
                                            }
                                        }
                                    )
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp).fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isManageMode) {
                                        Checkbox(
                                            checked = isChecked,
                                            onCheckedChange = { checked ->
                                                if (checked) selectedIds.add(item.id) else selectedIds.remove(item.id)
                                            }
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    } else {
                                        Icon(
                                            Icons.Filled.Terminal,
                                            contentDescription = null,
                                            modifier = Modifier.size(22.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(Modifier.width(12.dp))
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(displayName, style = MaterialTheme.typography.titleSmall)
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            item.command,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    if (!isManageMode) {
                                        Icon(
                                            Icons.Filled.PlayArrow,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 6. 批量管理底部操作栏
        if (isManageMode && selectedIds.isNotEmpty()) {
            Surface(
                tonalElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${selectedIds.size} 项已选", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showBatchMoveDialog = true }) {
                            Icon(Icons.Filled.DriveFileMove, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(s.cmdMoveToCategory)
                        }
                        Button(
                            onClick = { showBatchDeleteConfirm = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(s.cmdDeleteBatch)
                        }
                    }
                }
            }
        }
    }

    // --- 对话框区域 ---

    // 弹窗 A：文件传输 (Push)
    if (showPushDialog) {
        AlertDialog(
            onDismissRequest = { showPushDialog = false },
            title = { Text(s.pushTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = pushTargetDir,
                        onValueChange = { pushTargetDir = it },
                        label = { Text(s.pushTargetDirLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = { pushPickerLauncher.launch("*/*") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (selectedPushName.isNotBlank()) selectedPushName else s.pushChooseFileBtn)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val uri = selectedPushUri
                        if (uri != null && selectedPushName.isNotBlank()) {
                            AdbManager.pushFile(context, uri, selectedPushName, pushTargetDir.trim())
                            showPushDialog = false
                            onNavigateToHome()
                        }
                    },
                    enabled = selectedPushUri != null
                ) {
                    Text(s.pushStartBtn)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showPushDialog = false }) { Text(s.cancel) }
            }
        )
    }

    // 弹窗 B：流式安装应用 (Install)
    if (showInstallDialog) {
        var useCompatibleInstall by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showInstallDialog = false },
            title = { Text(s.installTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("直接向目标手机内存流式推送并安装 APK，无需在被控端预存安装包文件：", style = MaterialTheme.typography.bodySmall)

                    OutlinedButton(
                        onClick = { installPickerLauncher.launch("*/*") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Android, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (selectedInstallName.isNotBlank()) selectedInstallName else s.installChooseApkBtn)
                    }

                    Text(s.installModeLabel, style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !useCompatibleInstall,
                            onClick = { useCompatibleInstall = false },
                            label = { Text(s.installModeNormal) }
                        )
                        FilterChip(
                            selected = useCompatibleInstall,
                            onClick = { useCompatibleInstall = true },
                            label = { Text(s.installModeCompatible) }
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val uri = selectedInstallUri
                        if (uri != null && selectedInstallName.isNotBlank()) {
                            AdbManager.installApk(context, uri, selectedInstallName, useCompatibleInstall)
                            showInstallDialog = false
                            onNavigateToHome()
                        }
                    },
                    enabled = selectedInstallUri != null
                ) {
                    Text(s.installStartBtn)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showInstallDialog = false }) { Text(s.cancel) }
            }
        )
    }

    // 弹窗 C：Fastboot 镜像刷写 (Flash)
    if (showFlashDialog) {
        AlertDialog(
            onDismissRequest = { showFlashDialog = false },
            title = { Text(s.flashTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = flashPartition,
                        onValueChange = { flashPartition = it },
                        label = { Text(s.flashPartitionLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("快捷目标分区：", style = MaterialTheme.typography.labelSmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val quickParts = listOf("boot", "init_boot", "recovery", "vbmeta", "dtbo", "vendor_boot")
                        items(quickParts) { part ->
                            FilterChip(
                                selected = flashPartition == part,
                                onClick = { flashPartition = part },
                                label = { Text(part) }
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = { flashPickerLauncher.launch("*/*") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.FlashOn, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (selectedFlashName.isNotBlank()) selectedFlashName else s.flashChooseImgBtn)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val uri = selectedFlashUri
                        if (uri != null && selectedFlashName.isNotBlank() && flashPartition.isNotBlank()) {
                            AdbManager.flashPartition(context, uri, selectedFlashName, flashPartition.trim())
                            showFlashDialog = false
                            onNavigateToHome()
                        }
                    },
                    enabled = selectedFlashUri != null && flashPartition.isNotBlank()
                ) {
                    Text(s.flashStartBtn)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showFlashDialog = false }) { Text(s.cancel) }
            }
        )
    }

    // 弹窗 1：新增快捷指令
    if (showAddDialog) {
        var inputName by remember { mutableStateOf("") }
        var inputCmd by remember { mutableStateOf("") }
        var inputCat by remember { mutableStateOf(if (selectedCategory != "all") selectedCategory else "custom") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(s.cmdAddTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = inputName,
                        onValueChange = { inputName = it },
                        label = { Text(s.cmdNameLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = inputCmd,
                        onValueChange = { inputCmd = it },
                        label = { Text(s.cmdContentLabel) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(s.cmdCategoryNameLabel, style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val selectables = listOf("framework", "system", "power", "fastboot", "custom") + customCategories
                        items(selectables.distinct()) { cat ->
                            FilterChip(
                                selected = inputCat == cat,
                                onClick = { inputCat = cat },
                                label = { Text(getCategoryDisplayName(cat)) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = inputName.trim()
                        val cmd = inputCmd.trim()
                        if (name.isNotBlank() && cmd.isNotBlank()) {
                            val newItem = CommandItem(
                                id = "custom_${System.currentTimeMillis()}",
                                nameZh = name,
                                nameEn = name,
                                command = cmd,
                                category = inputCat,
                                isBuiltin = false
                            )
                            val updated = commandList + newItem
                            Prefs.saveCommands(updated)
                            refreshData()
                            showAddDialog = false
                        }
                    }
                ) {
                    Text(s.confirm)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showAddDialog = false }) {
                    Text(s.cancel)
                }
            }
        )
    }

    // 弹窗 2：新建分类
    if (showAddCatDialog) {
        var inputNewCat by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddCatDialog = false },
            title = { Text(s.cmdAddCategory) },
            text = {
                OutlinedTextField(
                    value = inputNewCat,
                    onValueChange = { inputNewCat = it },
                    label = { Text(s.cmdCategoryNameLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = inputNewCat.trim()
                        if (name.isNotBlank()) {
                            Prefs.addCustomCategory(name)
                            refreshData()
                            selectedCategory = name
                            showAddCatDialog = false
                        }
                    }
                ) {
                    Text(s.confirm)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showAddCatDialog = false }) {
                    Text(s.cancel)
                }
            }
        )
    }

    // 弹窗 3：编辑单条指令
    editingItem?.let { item ->
        var editName by remember(item) { mutableStateOf(if (lang == "zh") item.nameZh else item.nameEn) }
        var editCmd by remember(item) { mutableStateOf(item.command) }
        var editCat by remember(item) { mutableStateOf(item.category) }

        AlertDialog(
            onDismissRequest = { editingItem = null },
            title = { Text(s.cmdEditTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text(s.cmdNameLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editCmd,
                        onValueChange = { editCmd = it },
                        label = { Text(s.cmdContentLabel) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(s.cmdCategoryNameLabel, style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val selectables = listOf("framework", "system", "power", "fastboot", "custom") + customCategories
                        items(selectables.distinct()) { cat ->
                            FilterChip(
                                selected = editCat == cat,
                                onClick = { editCat = cat },
                                label = { Text(getCategoryDisplayName(cat)) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            itemPendingDelete = item
                            editingItem = null
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(s.cmdDeleteSingle)
                    }
                    Button(
                        onClick = {
                            val name = editName.trim()
                            val cmd = editCmd.trim()
                            if (name.isNotBlank() && cmd.isNotBlank()) {
                                val updated = commandList.map {
                                    if (it.id == item.id) {
                                        it.copy(
                                            nameZh = if (lang == "zh") name else it.nameZh,
                                            nameEn = if (lang == "en") name else it.nameEn,
                                            command = cmd,
                                            category = editCat
                                        )
                                    } else it
                                }
                                Prefs.saveCommands(updated)
                                refreshData()
                                editingItem = null
                            }
                        }
                    ) {
                        Text(s.confirm)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { editingItem = null }) {
                    Text(s.cancel)
                }
            }
        )
    }

    // 弹窗 4：单条删除二次确认
    itemPendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { itemPendingDelete = null },
            title = { Text(s.cmdDeleteSingle) },
            text = { Text(s.cmdDeleteSingleConfirm) },
            confirmButton = {
                Button(
                    onClick = {
                        val updated = commandList.filterNot { it.id == item.id }
                        Prefs.saveCommands(updated)
                        refreshData()
                        itemPendingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(s.confirm)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { itemPendingDelete = null }) {
                    Text(s.cancel)
                }
            }
        )
    }

    // 弹窗 5：批量删除二次确认
    if (showBatchDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = { Text(s.cmdDeleteBatch) },
            text = { Text(String.format(s.cmdDeleteBatchConfirm, selectedIds.size)) },
            confirmButton = {
                Button(
                    onClick = {
                        val remaining = commandList.filterNot { selectedIds.contains(it.id) }
                        Prefs.saveCommands(remaining)
                        selectedIds.clear()
                        isManageMode = false
                        refreshData()
                        showBatchDeleteConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(s.confirm)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showBatchDeleteConfirm = false }) {
                    Text(s.cancel)
                }
            }
        )
    }

    // 弹窗 6：批量移动到分类
    if (showBatchMoveDialog) {
        var targetCat by remember { mutableStateOf("custom") }

        AlertDialog(
            onDismissRequest = { showBatchMoveDialog = false },
            title = { Text(s.cmdMoveToCategory) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("选择目标分类：", style = MaterialTheme.typography.bodyMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val selectables = listOf("framework", "system", "power", "fastboot", "custom") + customCategories
                        items(selectables.distinct()) { cat ->
                            FilterChip(
                                selected = targetCat == cat,
                                onClick = { targetCat = cat },
                                label = { Text(getCategoryDisplayName(cat)) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val updated = commandList.map {
                            if (selectedIds.contains(it.id)) it.copy(category = targetCat) else it
                        }
                        Prefs.saveCommands(updated)
                        selectedIds.clear()
                        isManageMode = false
                        refreshData()
                        showBatchMoveDialog = false
                    }
                ) {
                    Text(s.confirm)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showBatchMoveDialog = false }) {
                    Text(s.cancel)
                }
            }
        )
    }

    // 弹窗 7：指令执行结果弹窗
    if (showResultDialog) {
        AlertDialog(
            onDismissRequest = { showResultDialog = false },
            title = {
                Column {
                    Text(resultDialogTitle, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "$ $resultDialogCommand",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp, max = 320.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .background(androidx.compose.ui.graphics.Color(0xFF0F172A))
                        .padding(10.dp)
                ) {
                    if (isRunningCommand) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "正在执行命令...",
                                style = MaterialTheme.typography.bodySmall,
                                color = androidx.compose.ui.graphics.Color(0xFF94A3B8)
                            )
                        }
                    } else {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                item {
                                    Text(
                                        text = resultDialogOutput ?: s.logNoOutput,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 13.sp,
                                            lineHeight = 17.sp
                                        ),
                                        fontFamily = FontFamily.Monospace,
                                        color = androidx.compose.ui.graphics.Color(0xFFF1F5F9)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showResultDialog = false }) {
                    Text(s.cmdDone)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        val text = resultDialogOutput
                        if (!text.isNullOrBlank()) {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Command Output", text))
                            AdbManager.log(s.copyLog + " ✓")
                        }
                    },
                    enabled = !resultDialogOutput.isNullOrBlank()
                ) {
                    Text(s.copyLog)
                }
            }
        )
    }
}

/** 智能定位并启动 Shizuku */
private fun runShizukuAction() {
    Thread {
        AdbManager.log("正在智能探测 Shizuku 安装路径...")
        val pathOut = AdbManager.execCapture("pm path moe.shizuku.privileged.api")
        val match = Regex("package:(.+)/base\\.apk").find(pathOut)
        if (match == null) {
            AdbManager.log("未检测到 Shizuku，请确认已安装 moe.shizuku.privileged.api")
            return@Thread
        }
        val apkDir = match.groupValues[1].trim()
        val candidatePaths = listOf(
            "$apkDir/lib/arm64/libshizuku.so",
            "$apkDir/lib/arm/libshizuku.so",
            "$apkDir/lib/x86_64/libshizuku.so",
            "$apkDir/lib/x86/libshizuku.so"
        )
        var soPath = ""
        for (p in candidatePaths) {
            val check = AdbManager.execCapture("ls $p 2>/dev/null").trim()
            if (check.endsWith("libshizuku.so")) {
                soPath = p
                break
            }
        }
        if (soPath.isBlank()) {
            val lsCheck = AdbManager.execCapture("ls $apkDir/lib/*/libshizuku.so 2>/dev/null").trim()
            if (lsCheck.contains("libshizuku.so")) {
                soPath = lsCheck.lines().firstOrNull()?.trim() ?: ""
            }
        }
        if (soPath.isNotBlank()) {
            AdbManager.log("正在拉起 Shizuku 服务: $soPath")
            AdbManager.exec(soPath)
        } else {
            AdbManager.log("未找到 libshizuku.so 运行库")
        }
    }.start()
}

/** 从 Content Uri 解析文件名辅助函数 */
private fun getFileNameFromUri(context: Context, uri: Uri): String {
    var name = ""
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx != -1) name = it.getString(idx)
            }
        }
    }
    if (name.isBlank()) {
        name = uri.path?.substringAfterLast('/') ?: "file_${System.currentTimeMillis()}"
    }
    return name
}
