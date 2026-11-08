package com.aoooa.adb.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aoooa.adb.AdbManager
import com.aoooa.adb.Prefs
import com.aoooa.adb.model.CommandItem
import com.aoooa.adb.ui.i18n.Strings

/**
 * 快捷指令页面：纯粹专注于快捷指令的浏览、搜索、执行、分组与编辑管理
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CommandsScreen(
    s: Strings,
    lang: String,
    onNavigateToSection: (ToolsSection) -> Unit = {},
    onExecuteCommand: (String) -> Unit = {},
    onNavigateToHome: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val connected by AdbManager.connected

    var commandList by remember { mutableStateOf(Prefs.loadCommands()) }
    var customCategories by remember { mutableStateOf(Prefs.loadCustomCategories()) }
    var selectedCategory by remember { mutableStateOf("all") }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var isManageMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    val collapsedCategories = remember { mutableStateListOf<String>() }

    // 菜单展开状态
    var leftMenuExpanded by remember { mutableStateOf(false) }
    var rightMenuExpanded by remember { mutableStateOf(false) }

    // 对话框状态
    var showAddDialog by remember { mutableStateOf(false) }
    var showAddCatDialog by remember { mutableStateOf(false) }
    var editingItem by remember { mutableStateOf<CommandItem?>(null) }
    var itemPendingDelete by remember { mutableStateOf<CommandItem?>(null) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    var showBatchMoveDialog by remember { mutableStateOf(false) }

    // 执行结果弹窗状态
    var resultDialogTitle by remember { mutableStateOf("") }
    var resultDialogCommand by remember { mutableStateOf("") }
    var resultDialogOutput by remember { mutableStateOf<String?>(null) }
    var isRunningCommand by remember { mutableStateOf(false) }
    var showResultDialog by remember { mutableStateOf(false) }

    // 每次进入页面时同步最新的持久化配置
    LaunchedEffect(Unit) {
        commandList = Prefs.loadCommands()
        customCategories = Prefs.loadCustomCategories()
    }

    fun refreshData() {
        commandList = Prefs.loadCommands()
        customCategories = Prefs.loadCustomCategories()
    }

    fun runAndShowResult(item: CommandItem) {
        resultDialogTitle = if (lang == "zh") item.nameZh else item.nameEn
        resultDialogCommand = item.command
        resultDialogOutput = null
        isRunningCommand = true
        showResultDialog = true

        Thread {
            val output = if (!connected) {
                s.terminalNotConnected
            } else {
                AdbManager.execCapture(item.command).ifBlank { s.logNoOutput }
            }
            resultDialogOutput = output
            isRunningCommand = false
        }.start()
    }

    // 过滤命令列表
    val filteredCommands = remember(commandList, selectedCategory, searchQuery, isSearchActive) {
        commandList.filter { item ->
            val matchCategory = when (selectedCategory) {
                "all" -> true
                else -> item.category == selectedCategory
            }
            val matchQuery = if (!isSearchActive || searchQuery.isBlank()) true else {
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
        TopAppBar(
            title = { Text(s.tabCommands) },
            navigationIcon = {
                // 左上角三条杠：工具与快捷指令切换
                IconButton(onClick = { leftMenuExpanded = true }) {
                    Icon(Icons.Filled.Menu, contentDescription = s.tabCommands)
                }
                DropdownMenu(
                    expanded = leftMenuExpanded,
                    onDismissRequest = { leftMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(s.tabTools) },
                        onClick = {
                            leftMenuExpanded = false
                            onNavigateToSection(ToolsSection.TOOLS)
                        },
                        leadingIcon = { Icon(Icons.Filled.Build, null) }
                    )
                    DropdownMenuItem(
                        text = { Text(s.tabCommands, fontWeight = FontWeight.Bold) },
                        onClick = { leftMenuExpanded = false },
                        leadingIcon = { Icon(Icons.Filled.Code, null) }
                    )
                }
            },
            actions = {
                // 右上角三条杠：收纳搜索、添加命令、添加分组、批量管理
                IconButton(onClick = { rightMenuExpanded = true }) {
                    Icon(Icons.Filled.Menu, contentDescription = s.cmdManage)
                }
                DropdownMenu(
                    expanded = rightMenuExpanded,
                    onDismissRequest = { rightMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(s.searchCommands) },
                        onClick = {
                            rightMenuExpanded = false
                            isSearchActive = true
                        },
                        leadingIcon = { Icon(Icons.Filled.Search, null) }
                    )
                    DropdownMenuItem(
                        text = { Text(s.cmdAddTitle) },
                        onClick = {
                            rightMenuExpanded = false
                            showAddDialog = true
                        },
                        leadingIcon = { Icon(Icons.Filled.Add, null) }
                    )
                    DropdownMenuItem(
                        text = { Text(s.cmdAddCategory) },
                        onClick = {
                            rightMenuExpanded = false
                            showAddCatDialog = true
                        },
                        leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) }
                    )
                    DropdownMenuItem(
                        text = { Text(if (isManageMode) s.cmdDone else s.cmdManage) },
                        onClick = {
                            rightMenuExpanded = false
                            isManageMode = !isManageMode
                            if (!isManageMode) selectedIds.clear()
                        },
                        leadingIcon = {
                            Icon(
                                if (isManageMode) Icons.Filled.Check else Icons.Filled.Checklist,
                                null,
                                tint = if (isManageMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    )
                }
            }
        )

        // 仅在点击搜索时才弹出的顶部搜索条
        if (isSearchActive) {
            Surface(
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text(
                                text = s.searchPlaceholder,
                                fontSize = 13.sp,
                                maxLines = 1
                            )
                        },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Filled.Clear, contentDescription = s.clear, modifier = Modifier.size(18.dp))
                                }
                            }
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        searchQuery = ""
                        isSearchActive = false
                    }) {
                        Text(s.cancel)
                    }
                }
            }
        }

        // 分类横向切换条
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
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
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
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

        // 快捷指令列表
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (filteredCommands.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
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
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
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
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            itemPendingDelete = item
                            editingItem = null
                        }
                    ) {
                        Text(s.delete, color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { editingItem = null }) {
                        Text(s.cancel)
                    }
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
                    Text(s.cmdCategoryNameLabel, style = MaterialTheme.typography.bodyMedium)
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

    // 指令执行结果弹窗（复用通用组件）
    if (showResultDialog) {
        CommandResultDialog(
            title = resultDialogTitle,
            command = resultDialogCommand,
            output = resultDialogOutput,
            isLoading = isRunningCommand,
            s = s,
            onDismiss = { showResultDialog = false }
        )
    }
}
