package com.aoooa.adb.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aoooa.adb.mcp.McpRule
import com.aoooa.adb.mcp.McpRuleEngine
import com.aoooa.adb.mcp.McpRuleType
import com.aoooa.adb.ui.SettingsSubPage
import com.aoooa.adb.ui.i18n.I18n
import com.aoooa.adb.ui.i18n.Strings

/**
 * MCP 安全拦截规则独立管理页面：
 * 1. 提供规则列表查看、类型标签区分与附加说明展示；
 * 2. 支持单项点击编辑规则内容与单项快捷删除；
 * 3. 支持批量多选模式、全选/反选与一键批量删除二次确认；
 * 4. 支持手动新增动词拦截或精准拦截规则并即时持久化。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsMcpRulesPage(
    s: Strings,
    onNavigate: (SettingsSubPage) -> Unit
) {
    var rules by remember { mutableStateOf(McpRuleEngine.loadRules()) }
    var isBatchMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }

    var showAddDialog by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<McpRule?>(null) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (isBatchMode) "${s.mcpRuleBatchDelete} (${selectedIds.size})" else s.mcpRulesPageTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isBatchMode) {
                            isBatchMode = false
                            selectedIds.clear()
                        } else {
                            onNavigate(SettingsSubPage.MCP)
                        }
                    }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (isBatchMode) {
                        TextButton(onClick = {
                            if (selectedIds.size == rules.size) {
                                selectedIds.clear()
                            } else {
                                selectedIds.clear()
                                selectedIds.addAll(rules.map { it.id })
                            }
                        }) {
                            Text(if (selectedIds.size == rules.size) s.mcpRuleDeselectAll else s.mcpRuleSelectAll, fontSize = 13.sp)
                        }
                    } else {
                        if (rules.isNotEmpty()) {
                            IconButton(onClick = {
                                isBatchMode = true
                                selectedIds.clear()
                            }) {
                                Icon(Icons.Filled.Checklist, contentDescription = s.mcpRuleBatchDelete)
                            }
                        }
                        IconButton(onClick = { showAddDialog = true }) {
                            Icon(Icons.Filled.Add, contentDescription = s.mcpRuleAdd)
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (isBatchMode && selectedIds.isNotEmpty()) {
                Surface(
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            onClick = { showBatchDeleteConfirm = true }
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("${s.mcpRuleBatchDelete} (${selectedIds.size})")
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (rules.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Filled.Security,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = s.mcpRuleEmpty,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(rules, key = { it.id }) { rule ->
                    val isSelected = selectedIds.contains(rule.id)

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isBatchMode) {
                                    if (isSelected) selectedIds.remove(rule.id) else selectedIds.add(rule.id)
                                } else {
                                    editingRule = rule
                                }
                            },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isBatchMode) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { checked ->
                                        if (checked) selectedIds.add(rule.id) else selectedIds.remove(rule.id)
                                    }
                                )
                                Spacer(Modifier.width(8.dp))
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = if (rule.type == McpRuleType.EXACT) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer
                                    ) {
                                        Text(
                                            text = if (rule.type == McpRuleType.EXACT) I18n.current.ruleTypeExact else I18n.current.ruleTypeVerb,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (rule.type == McpRuleType.EXACT) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = rule.pattern,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }

                                if (rule.description.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = "${s.mcpRuleDescLabel}: ${rule.description}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            if (!isBatchMode) {
                                IconButton(
                                    onClick = {
                                        val updated = rules.filter { it.id != rule.id }
                                        rules = updated
                                        McpRuleEngine.saveRules(updated)
                                    }
                                ) {
                                    Icon(Icons.Filled.Delete, contentDescription = s.delete, tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 批量删除二次确认弹窗
    if (showBatchDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = { Text(s.mcpRuleDeleteConfirmTitle) },
            text = { Text(String.format(s.mcpRuleDeleteConfirmMsg, selectedIds.size)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val updated = rules.filter { !selectedIds.contains(it.id) }
                        rules = updated
                        McpRuleEngine.saveRules(updated)
                        selectedIds.clear()
                        isBatchMode = false
                        showBatchDeleteConfirm = false
                    }
                ) {
                    Text(s.confirm, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirm = false }) {
                    Text(s.cancel)
                }
            }
        )
    }

    // 添加拦截规则对话框
    if (showAddDialog) {
        var ruleType by remember { mutableStateOf(McpRuleType.VERB) }
        var patternText by remember { mutableStateOf("") }
        var descText by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(s.mcpRuleAdd) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = ruleType == McpRuleType.VERB,
                            onClick = { ruleType = McpRuleType.VERB },
                            label = { Text(s.mcpRuleTypeVerb, fontSize = 12.sp) }
                        )
                        FilterChip(
                            selected = ruleType == McpRuleType.EXACT,
                            onClick = { ruleType = McpRuleType.EXACT },
                            label = { Text(s.mcpRuleTypeExact, fontSize = 12.sp) }
                        )
                    }

                    OutlinedTextField(
                        value = patternText,
                        onValueChange = { patternText = it },
                        label = { Text(s.mcpRulePatternHint) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = descText,
                        onValueChange = { descText = it },
                        label = { Text(s.mcpRuleDescLabel) },
                        placeholder = { Text(s.mcpRuleDescHint) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = patternText.isNotBlank(),
                    onClick = {
                        val newRule = McpRule(
                            type = ruleType,
                            pattern = patternText.trim(),
                            description = descText.trim()
                        )
                        val updated = rules + newRule
                        rules = updated
                        McpRuleEngine.saveRules(updated)
                        showAddDialog = false
                    }
                ) {
                    Text(s.mcpRuleAdd)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text(s.cancel)
                }
            }
        )
    }

    // 编辑规则对话框
    editingRule?.let { target ->
        var editType by remember(target) { mutableStateOf(target.type) }
        var editPattern by remember(target) { mutableStateOf(target.pattern) }
        var editDesc by remember(target) { mutableStateOf(target.description) }

        AlertDialog(
            onDismissRequest = { editingRule = null },
            title = { Text(s.mcpRuleEditTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = editType == McpRuleType.VERB,
                            onClick = { editType = McpRuleType.VERB },
                            label = { Text(s.mcpRuleTypeVerb, fontSize = 12.sp) }
                        )
                        FilterChip(
                            selected = editType == McpRuleType.EXACT,
                            onClick = { editType = McpRuleType.EXACT },
                            label = { Text(s.mcpRuleTypeExact, fontSize = 12.sp) }
                        )
                    }

                    OutlinedTextField(
                        value = editPattern,
                        onValueChange = { editPattern = it },
                        label = { Text(s.mcpRulePatternHint) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = editDesc,
                        onValueChange = { editDesc = it },
                        label = { Text(s.mcpRuleDescLabel) },
                        placeholder = { Text(s.mcpRuleDescHint) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = editPattern.isNotBlank(),
                    onClick = {
                        val updated = rules.map {
                            if (it.id == target.id) {
                                it.copy(type = editType, pattern = editPattern.trim(), description = editDesc.trim())
                            } else it
                        }
                        rules = updated
                        McpRuleEngine.saveRules(updated)
                        editingRule = null
                    }
                ) {
                    Text(s.confirm)
                }
            },
            dismissButton = {
                TextButton(onClick = { editingRule = null }) {
                    Text(s.cancel)
                }
            }
        )
    }
}
