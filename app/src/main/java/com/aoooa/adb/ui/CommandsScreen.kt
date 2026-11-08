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
            items(allCats.distinct()) { cat ->\n                FilterChip(\n                    selected = selectedCategory == cat,\n                    onClick = { selectedCategory = cat },\n                    label = { Text(getCategoryDisplayName(cat)) }\n                )\n            }\n        }\n\n        // 批量管理操作条\n        if (isManageMode) {\n            Surface(\n                tonalElevation = 2.dp,\n                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)\n            ) {\n                Row(\n                    modifier = Modifier\n                        .fillMaxWidth()\n                        .padding(horizontal = 16.dp, vertical = 6.dp),\n                    horizontalArrangement = Arrangement.SpaceBetween,\n                    verticalAlignment = Alignment.CenterVertically\n                ) {\n                    TextButton(onClick = {\n                        if (selectedIds.size == filteredCommands.size) {\n                            selectedIds.clear()\n                        } else {\n                            selectedIds.clear()\n                            selectedIds.addAll(filteredCommands.map { it.id })\n                        }\n                    }) {\n                        Text(if (selectedIds.size == filteredCommands.size) s.cmdDeselectAll else s.cmdSelectAll)\n                    }\n\n                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {\n                        TextButton(\n                            onClick = { showBatchMoveDialog = true },\n                            enabled = selectedIds.isNotEmpty()\n                        ) {\n                            Text(s.cmdMoveToCategory)\n                        }\n\n                        Button(\n                            onClick = { showBatchDeleteConfirm = true },\n                            enabled = selectedIds.isNotEmpty(),\n                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)\n                        ) {\n                            Text(s.cmdDeleteBatch)\n                        }\n                    }\n                }\n            }\n        }\n\n        // 快捷指令列表\n        LazyColumn(\n            modifier = Modifier.weight(1f),\n            contentPadding = PaddingValues(16.dp),\n            verticalArrangement = Arrangement.spacedBy(10.dp)\n        ) {\n            if (filteredCommands.isEmpty()) {\n                item {\n                    Box(\n                        modifier = Modifier\n                            .fillMaxWidth()\n                            .padding(vertical = 40.dp),\n                        contentAlignment = Alignment.Center\n                    ) {\n                        Text(s.cmdNoCommands, color = MaterialTheme.colorScheme.onSurfaceVariant)\n                    }\n                }\n            } else {\n                groupedCommands.forEach { (cat, list) ->\n                    val isCollapsed = collapsedCategories.contains(cat)\n\n                    item(key = \"header_$cat\") {\n                        Row(\n                            modifier = Modifier\n                                .fillMaxWidth()\n                                .clickable {\n                                    if (isCollapsed) collapsedCategories.remove(cat)\n                                    else collapsedCategories.add(cat)\n                                }\n                                .padding(vertical = 4.dp),\n                            horizontalArrangement = Arrangement.SpaceBetween,\n                            verticalAlignment = Alignment.CenterVertically\n                        ) {\n                            Text(\n                                \"${getCategoryDisplayName(cat)} (${list.size})\",\n                                style = MaterialTheme.typography.titleSmall,\n                                color = MaterialTheme.colorScheme.primary\n                            )\n                            Icon(\n                                if (isCollapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,\n                                contentDescription = null\n                            )\n                        }\n                    }\n\n                    if (!isCollapsed) {\n                        items(list, key = { it.id }) { item ->\n                            val isChecked = selectedIds.contains(item.id)\n\n                            Card(\n                                modifier = Modifier\n                                    .fillMaxWidth()\n                                    .combinedClickable(\n                                        onClick = {\n                                            if (isManageMode) {\n                                                if (isChecked) selectedIds.remove(item.id) else selectedIds.add(item.id)\n                                            } else {\n                                                runAndShowResult(item)\n                                            }\n                                        },\n                                        onLongClick = {\n                                            editingItem = item\n                                        }\n                                    ),\n                                colors = CardDefaults.cardColors(\n                                    containerColor = if (isChecked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface\n                                )\n                            ) {\n                                Row(\n                                    modifier = Modifier\n                                        .fillMaxWidth()\n                                        .padding(12.dp),\n                                    verticalAlignment = Alignment.CenterVertically,\n                                    horizontalArrangement = Arrangement.SpaceBetween\n                                ) {\n                                    if (isManageMode) {\n                                        Checkbox(\n                                            checked = isChecked,\n                                            onCheckedChange = { check ->\n                                                if (check) selectedIds.add(item.id) else selectedIds.remove(item.id)\n                                            }\n                                        )\n                                        Spacer(Modifier.width(8.dp))\n                                    }\n\n                                    Column(Modifier.weight(1f)) {\n                                        Text(\n                                            text = if (lang == \"zh\") item.nameZh else item.nameEn,\n                                            style = MaterialTheme.typography.bodyMedium,\n                                            fontWeight = FontWeight.Medium\n                                        )\n                                        Spacer(Modifier.height(2.dp))\n                                        Text(\n                                            text = \"$ ${item.command}\",\n                                            style = MaterialTheme.typography.bodySmall,\n                                            fontFamily = FontFamily.Monospace,\n                                            color = MaterialTheme.colorScheme.onSurfaceVariant\n                                        )\n                                    }\n\n                                    if (!isManageMode) {\n                                        IconButton(onClick = { editingItem = item }) {\n                                            Icon(Icons.Filled.Edit, contentDescription = s.cmdEditTitle, modifier = Modifier.size(18.dp))\n                                        }\n                                    }\n                                }\n                            }\n                        }\n                    }\n                }\n            }\n        }\n    }\n\n    // 弹窗 1：新增快捷指令\n    if (showAddDialog) {\n        var newNameZh by remember { mutableStateOf(\"\") }\n        var newNameEn by remember { mutableStateOf(\"\") }\n        var newCmd by remember { mutableStateOf(\"\") }\n        var newCat by remember { mutableStateOf(\"custom\") }\n\n        AlertDialog(\n            onDismissRequest = { showAddDialog = false },\n            title = { Text(s.cmdAddTitle) },\n            text = {\n                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {\n                    OutlinedTextField(\n                        value = newNameZh,\n                        onValueChange = { newNameZh = it },\n                        label = { Text(\"${s.cmdNameLabel} (中文)\") },\n                        modifier = Modifier.fillMaxWidth()\n                    )\n                    OutlinedTextField(\n                        value = newNameEn,\n                        onValueChange = { newNameEn = it },\n                        label = { Text(\"${s.cmdNameLabel} (English)\") },\n                        modifier = Modifier.fillMaxWidth()\n                    )\n                    OutlinedTextField(\n                        value = newCmd,\n                        onValueChange = { newCmd = it },\n                        label = { Text(s.cmdContentLabel) },\n                        modifier = Modifier.fillMaxWidth()\n                    )\n                    Text(s.cmdCategoryNameLabel, style = MaterialTheme.typography.labelMedium)\n                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {\n                        val selectables = listOf(\"framework\", \"system\", \"power\", \"fastboot\", \"custom\") + customCategories\n                        items(selectables.distinct()) { cat ->\n                            FilterChip(\n                                selected = newCat == cat,\n                                onClick = { newCat = cat },\n                                label = { Text(getCategoryDisplayName(cat)) }\n                            )\n                        }\n                    }\n                }\n            },\n            confirmButton = {\n                Button(\n                    onClick = {\n                        val nameZh = newNameZh.trim()\n                        val nameEn = newNameEn.trim().ifBlank { nameZh }\n                        val cmd = newCmd.trim()\n                        if (nameZh.isNotBlank() && cmd.isNotBlank()) {\n                            val newItem = CommandItem(\n                                id = java.util.UUID.randomUUID().toString(),\n                                nameZh = nameZh,\n                                nameEn = nameEn,\n                                command = cmd,\n                                category = newCat,\n                                isBuiltin = false\n                            )\n                            val updated = commandList + newItem\n                            Prefs.saveCommands(updated)\n                            refreshData()\n                            showAddDialog = false\n                        }\n                    }\n                ) {\n                    Text(s.cmdAddBtn)\n                }\n            },\n            dismissButton = {\n                OutlinedButton(onClick = { showAddDialog = false }) { Text(s.cancel) }\n            }\n        )\n    }\n\n    // 弹窗 2：新建自定义分类\n    if (showAddCatDialog) {\n        var catName by remember { mutableStateOf(\"\") }\n\n        AlertDialog(\n            onDismissRequest = { showAddCatDialog = false },\n            title = { Text(s.cmdAddCategory) },\n            text = {\n                OutlinedTextField(\n                    value = catName,\n                    onValueChange = { catName = it },\n                    label = { Text(s.cmdCategoryNameLabel) },\n                    singleLine = true,\n                    modifier = Modifier.fillMaxWidth()\n                )\n            },\n            confirmButton = {\n                Button(\n                    onClick = {\n                        val clean = catName.trim()\n                        if (clean.isNotBlank()) {\n                            Prefs.addCustomCategory(clean)\n                            refreshData()\n                            selectedCategory = clean\n                            showAddCatDialog = false\n                        }\n                    }\n                ) {\n                    Text(s.confirm)\n                }\n            },\n            dismissButton = {\n                OutlinedButton(onClick = { showAddCatDialog = false }) { Text(s.cancel) }\n            }\n        )\n    }\n\n    // 弹窗 3：编辑快捷指令\n    editingItem?.let { item ->\n        var editName by remember { mutableStateOf(if (lang == \"zh\") item.nameZh else item.nameEn) }\n        var editCmd by remember { mutableStateOf(item.command) }\n        var editCat by remember { mutableStateOf(item.category) }\n\n        AlertDialog(\n            onDismissRequest = { editingItem = null },\n            title = { Text(s.cmdEditTitle) },\n            text = {\n                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {\n                    OutlinedTextField(\n                        value = editName,\n                        onValueChange = { editName = it },\n                        label = { Text(s.cmdNameLabel) },\n                        modifier = Modifier.fillMaxWidth()\n                    )\n                    OutlinedTextField(\n                        value = editCmd,\n                        onValueChange = { editCmd = it },\n                        label = { Text(s.cmdContentLabel) },\n                        modifier = Modifier.fillMaxWidth()\n                    )\n                    Text(s.cmdCategoryNameLabel, style = MaterialTheme.typography.labelMedium)\n                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {\n                        val selectables = listOf(\"framework\", \"system\", \"power\", \"fastboot\", \"custom\") + customCategories\n                        items(selectables.distinct()) { cat ->\n                            FilterChip(\n                                selected = editCat == cat,\n                                onClick = { editCat = cat },\n                                label = { Text(getCategoryDisplayName(cat)) }\n                            )\n                        }\n                    }\n                }\n            },\n            confirmButton = {\n                Button(\n                    onClick = {\n                        val name = editName.trim()\n                        val cmd = editCmd.trim()\n                        if (name.isNotBlank() && cmd.isNotBlank()) {\n                            val updated = commandList.map {\n                                if (it.id == item.id) {\n                                    it.copy(\n                                        nameZh = if (lang == \"zh\") name else it.nameZh,\n                                        nameEn = if (lang == \"en\") name else it.nameEn,\n                                        command = cmd,\n                                        category = editCat\n                                    )\n                                } else it\n                            }\n                            Prefs.saveCommands(updated)\n                            refreshData()\n                            editingItem = null\n                        }\n                    }\n                ) {\n                    Text(s.confirm)\n                }\n            },\n            dismissButton = {\n                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {\n                    TextButton(\n                        onClick = {\n                            itemPendingDelete = item\n                            editingItem = null\n                        }\n                    ) {\n                        Text(s.delete, color = MaterialTheme.colorScheme.error)\n                    }\n                    TextButton(onClick = { editingItem = null }) {\n                        Text(s.cancel)\n                    }\n                }\n            }\n        )\n    }\n\n    // 弹窗 4：单条删除二次确认\n    itemPendingDelete?.let { item ->\n        AlertDialog(\n            onDismissRequest = { itemPendingDelete = null },\n            title = { Text(s.cmdDeleteSingle) },\n            text = { Text(s.cmdDeleteSingleConfirm) },\n            confirmButton = {\n                Button(\n                    onClick = {\n                        val updated = commandList.filterNot { it.id == item.id }\n                        Prefs.saveCommands(updated)\n                        refreshData()\n                        itemPendingDelete = null\n                    },\n                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)\n                ) {\n                    Text(s.confirm)\n                }\n            },\n            dismissButton = {\n                OutlinedButton(onClick = { itemPendingDelete = null }) {\n                    Text(s.cancel)\n                }\n            }\n        )\n    }\n\n    // 弹窗 5：批量删除二次确认\n    if (showBatchDeleteConfirm) {\n        AlertDialog(\n            onDismissRequest = { showBatchDeleteConfirm = false },\n            title = { Text(s.cmdDeleteBatch) },\n            text = { Text(String.format(s.cmdDeleteBatchConfirm, selectedIds.size)) },\n            confirmButton = {\n                Button(\n                    onClick = {\n                        val remaining = commandList.filterNot { selectedIds.contains(it.id) }\n                        Prefs.saveCommands(remaining)\n                        selectedIds.clear()\n                        isManageMode = false\n                        refreshData()\n                        showBatchDeleteConfirm = false\n                    },\n                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)\n                ) {\n                    Text(s.confirm)\n                }\n            },\n            dismissButton = {\n                OutlinedButton(onClick = { showBatchDeleteConfirm = false }) {\n                    Text(s.cancel)\n                }\n            }\n        )\n    }\n\n    // 弹窗 6：批量移动到分类\n    if (showBatchMoveDialog) {\n        var targetCat by remember { mutableStateOf(\"custom\") }\n\n        AlertDialog(\n            onDismissRequest = { showBatchMoveDialog = false },\n            title = { Text(s.cmdMoveToCategory) },\n            text = {\n                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {\n                    Text(s.cmdCategoryNameLabel, style = MaterialTheme.typography.bodyMedium)\n                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {\n                        val selectables = listOf(\"framework\", \"system\", \"power\", \"fastboot\", \"custom\") + customCategories\n                        items(selectables.distinct()) { cat ->\n                            FilterChip(\n                                selected = targetCat == cat,\n                                onClick = { targetCat = cat },\n                                label = { Text(getCategoryDisplayName(cat)) }\n                            )\n                        }\n                    }\n                }\n            },\n            confirmButton = {\n                Button(\n                    onClick = {\n                        val updated = commandList.map {\n                            if (selectedIds.contains(it.id)) it.copy(category = targetCat) else it\n                        }\n                        Prefs.saveCommands(updated)\n                        selectedIds.clear()\n                        isManageMode = false\n                        refreshData()\n                        showBatchMoveDialog = false\n                    }\n                ) {\n                    Text(s.confirm)\n                }\n            },\n            dismissButton = {\n                OutlinedButton(onClick = { showBatchMoveDialog = false }) {\n                    Text(s.cancel)\n                }\n            }\n        )\n    }\n\n    // 指令执行结果弹窗（复用通用组件）\n    if (showResultDialog) {\n        CommandResultDialog(\n            title = resultDialogTitle,\n            command = resultDialogCommand,\n            output = resultDialogOutput,\n            isLoading = isRunningCommand,\n            s = s,\n            onDismiss = { showResultDialog = false }\n        )\n    }\n}\n