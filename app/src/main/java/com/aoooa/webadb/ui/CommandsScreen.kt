package com.aoooa.webadb.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    // 过滤命令列表
    val filteredCommands = remember(commandList, selectedCategory, searchQuery) {
        commandList.filter { item ->
            val matchCategory = when (selectedCategory) {
                "all" -> true
                else -> item.category == selectedCategory
            }
            val matchQuery = if (searchQuery.isBlank()) true else {
                item.nameZh.contains(searchQuery, ignoreCase = true) ||
                    item.nameEn.contains(searchQuery, ignoreCase = true) ||
                    item.command.contains(searchQuery, ignoreCase = true)
            }
            matchCategory && matchQuery
        }
    }

    // 分组
    val groupedCommands = remember(filteredCommands) {
        filteredCommands.groupBy { it.category }
    }

    fun getCategoryDisplayName(cat: String): String = when (cat) {
        "all" -> s.catAll
        "framework" -> s.catFramework
        "system" -> s.catSystem
        "power" -> s.catPower
        "fastboot" -> s.catFastboot
        "custom" -> s.catCustom
        else -> cat
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 顶部搜索与批量管理栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(s.searchPlaceholder, fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = s.clear)
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )

            IconButton(onClick = { showAddCatDialog = true }) {
                Icon(Icons.Filled.CreateNewFolder, contentDescription = s.cmdAddCategory)
            }

            IconButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = s.cmdAddTitle)
            }

            IconButton(onClick = {
                isManageMode = !isManageMode
                if (!isManageMode) selectedIds.clear()
            }) {
                Icon(
                    if (isManageMode) Icons.Filled.Check else Icons.Filled.Checklist,
                    contentDescription = s.cmdManage,
                    tint = if (isManageMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // 分类横向切换条
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val allCats = listOf("all", "framework", "system", "power", "fastboot", "custom") + customCategories
            items(allCats.distinct()) { cat ->
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = { selectedCategory = cat },
                    label = { Text(getCategoryDisplayName(cat)) }
                )
            }
        }

        // 批量管理操作条
        if (isManageMode) {
            Surface(
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        if (selectedIds.size == filteredCommands.size) {
                            selectedIds.clear()
                        } else {
                            selectedIds.clear()
                            selectedIds.addAll(filteredCommands.map { it.id })
                        }
                    }) {
                        Text(if (selectedIds.size == filteredCommands.size) s.cmdDeselectAll else s.cmdSelectAll)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = { showBatchMoveDialog = true },
                            enabled = selectedIds.isNotEmpty()
                        ) {
                            Text(s.cmdMoveToCategory)
                        }

                        Button(
                            onClick = { showBatchDeleteConfirm = true },
                            enabled = selectedIds.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text(s.cmdDeleteBatch)
                        }
                    }
                }
            }
        }

        // 主列表
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 顶部设备状态信息看板
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                s.deviceInfoTitle,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                if (connected) (if (isFastboot) s.fastbootConnected else s.statusConnected) else s.statusDisconnected,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (connected) {
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text("${s.model}: ${model.ifBlank { "未知" }}", style = MaterialTheme.typography.bodySmall)
                                    Text("${s.os}: ${os.ifBlank { "未知" }}", style = MaterialTheme.typography.bodySmall)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text("${s.bat}: ${battery.ifBlank { "未知" }}", style = MaterialTheme.typography.bodySmall)
                                    Text("${s.sel}: ${selinux.ifBlank { "未知" }}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (!isFastboot) {
                                Spacer(Modifier.height(8.dp))
                                HorizontalDivider()
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(s.tcpip5555StatusLabel, style = MaterialTheme.typography.bodySmall)
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            if (tcpip5555Enabled) s.statusOn else s.statusOff,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (tcpip5555Enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Button(
                                        onClick = { AdbManager.setTcpip5555(!tcpip5555Enabled) },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                    ) {
                                        Text(if (tcpip5555Enabled) s.turnOff else s.turnOn)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 三大硬核工具快捷触发入口
            if (connected) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!isFastboot) {
                            Button(
                                onClick = { showPushDialog = true },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(s.pushTitle.substringBefore(" ("), style = MaterialTheme.typography.labelMedium)
                            }
                            Button(
                                onClick = { showInstallDialog = true },
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
                                Text(s.flashTitle, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            // 指令分组卡片展示
            if (filteredCommands.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(s.cmdNoCommands, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                groupedCommands.forEach { (cat, list) ->
                    val isCollapsed = collapsedCategories.contains(cat)

                    item(key = "header_$cat") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isCollapsed) collapsedCategories.remove(cat)
                                    else collapsedCategories.add(cat)
                                }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${getCategoryDisplayName(cat)} (${list.size})",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Icon(
                                if (isCollapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                                contentDescription = null
                            )
                        }
                    }

                    if (!isCollapsed) {
                        items(list, key = { it.id }) { item ->
                            val isChecked = selectedIds.contains(item.id)

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
                                            editingItem = item
                                        }
                                    ),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isChecked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    if (isManageMode) {
                                        Checkbox(
                                            checked = isChecked,
                                            onCheckedChange = { check ->
                                                if (check) selectedIds.add(item.id) else selectedIds.remove(item.id)
                                            }
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }

                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = if (lang == "zh") item.nameZh else item.nameEn,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = "$ ${item.command}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    if (!isManageMode) {
                                        IconButton(onClick = { editingItem = item }) {
                                            Icon(Icons.Filled.Edit, contentDescription = s.cmdEditTitle, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 弹窗 A：ADB 文件推送 (Push)
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
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = { pushPickerLauncher.launch("*/*") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (selectedPushName.isBlank()) s.pushChooseFileBtn else "已选: $selectedPushName")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val uri = selectedPushUri
                        if (uri != null && selectedPushName.isNotBlank() && pushTargetDir.isNotBlank()) {
                            AdbManager.pushFile(context, uri, selectedPushName, pushTargetDir.trim())
                            showPushDialog = false
                            onNavigateToHome()
                        }
                    },
                    enabled = selectedPushUri != null && pushTargetDir.isNotBlank()
                ) {
                    Text(s.pushStartBtn)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showPushDialog = false }) { Text(s.cancel) }
            }
        )
    }

    // 弹窗 B：ADB 流式安装 APK (Install)
    if (showInstallDialog) {
        var installCompatibleMode by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showInstallDialog = false },
            title = { Text(s.installTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { installPickerLauncher.launch("application/vnd.android.package-archive") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (selectedInstallName.isBlank()) s.installChooseApkBtn else "已选: $selectedInstallName")
                    }
                    Text(s.installModeLabel, style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !installCompatibleMode,
                            onClick = { installCompatibleMode = false },
                            label = { Text(s.installModeNormal) }
                        )
                        FilterChip(
                            selected = installCompatibleMode,
                            onClick = { installCompatibleMode = true },
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
                            AdbManager.installApk(context, uri, selectedInstallName, installCompatibleMode)
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
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = { flashPickerLauncher.launch("*/*") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (selectedFlashName.isBlank()) s.flashChooseImgBtn else "已选: $selectedFlashName")
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
        var newNameZh by remember { mutableStateOf("") }
        var newNameEn by remember { mutableStateOf("") }
        var newCmd by remember { mutableStateOf("") }
        var newCat by remember { mutableStateOf("custom") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(s.cmdAddTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newNameZh,
                        onValueChange = { newNameZh = it },
                        label = { Text("${s.cmdNameLabel} (中文)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newNameEn,
                        onValueChange = { newNameEn = it },
                        label = { Text("${s.cmdNameLabel} (English)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newCmd,
                        onValueChange = { newCmd = it },
                        label = { Text(s.cmdContentLabel) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(s.cmdCategoryNameLabel, style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val selectables = listOf("framework", "system", "power", "fastboot", "custom") + customCategories
                        items(selectables.distinct()) { cat ->
                            FilterChip(
                                selected = newCat == cat,
                                onClick = { newCat = cat },
                                label = { Text(getCategoryDisplayName(cat)) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val nameZh = newNameZh.trim()
                        val nameEn = newNameEn.trim().ifBlank { nameZh }
                        val cmd = newCmd.trim()
                        if (nameZh.isNotBlank() && cmd.isNotBlank()) {
                            val newItem = CommandItem(
                                id = java.util.UUID.randomUUID().toString(),
                                nameZh = nameZh,
                                nameEn = nameEn,
                                command = cmd,
                                category = newCat,
                                isBuiltin = false
                            )
                            val updated = commandList + newItem
                            Prefs.saveCommands(updated)
                            refreshData()
                            showAddDialog = false
                        }
                    }
                ) {
                    Text(s.cmdAddBtn)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showAddDialog = false }) { Text(s.cancel) }
            }
        )
    }

    // 弹窗 2：新建自定义分类
    if (showAddCatDialog) {
        var catName by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddCatDialog = false },
            title = { Text(s.cmdAddCategory) },
            text = {
                OutlinedTextField(
                    value = catName,
                    onValueChange = { catName = it },
                    label = { Text(s.cmdCategoryNameLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clean = catName.trim()
                        if (clean.isNotBlank()) {
                            Prefs.addCustomCategory(clean)
                            refreshData()
                            selectedCategory = clean
                            showAddCatDialog = false
                        }
                    }
                ) {
                    Text(s.confirm)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showAddCatDialog = false }) { Text(s.cancel) }
            }
        )
    }

    // 弹窗 3：编辑快捷指令
    editingItem?.let { item ->
        var editName by remember { mutableStateOf(if (lang == "zh") item.nameZh else item.nameEn) }
        var editCmd by remember { mutableStateOf(item.command) }
        var editCat by remember { mutableStateOf(item.category) }

        AlertDialog(
            onDismissRequest = { editingItem = null },
            title = { Text(s.cmdEditTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text(s.cmdNameLabel) },
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
                        .clip(RoundedCornerShape(8.dp))
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
