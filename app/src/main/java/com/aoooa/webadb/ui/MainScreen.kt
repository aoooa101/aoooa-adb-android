package com.aoooa.webadb.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aoooa.webadb.ui.i18n.I18n
import com.aoooa.webadb.ui.theme.ThemeMode
import com.aoooa.webadb.ui.theme.WebAdbTheme

/** 连接方式：有线 / 无线 */
enum class DebugMode(val id: Int) {
    WIRED(0), WIRELESS(1);

    companion object {
        fun fromId(id: Int): DebugMode = entries.firstOrNull { it.id == id } ?: WIRED
    }
}

/** 底部导航页 */
enum class MainTab(val id: Int) {
    HOME(0), SETTINGS(1);

    companion object {
        fun fromId(id: Int): MainTab = entries.firstOrNull { it.id == id } ?: HOME
    }
}

/**
 * App 根入口：管理主题 / 语言状态。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun WebAdbApp(
    initialThemeMode: ThemeMode = ThemeMode.SYSTEM,
    initialLang: String = "zh"
) {
    var themeMode by remember { mutableStateOf(initialThemeMode) }
    var lang by remember { mutableStateOf(initialLang) }
    val s = if (lang == "zh") I18n.zh else I18n.en

    WebAdbTheme(mode = themeMode) {
        MainScreen(
            s = s,
            lang = lang,
            themeMode = themeMode,
            onThemeChange = { themeMode = it },
            onLangChange = { lang = it },
        )
    }
}

/**
 * 主界面：底部导航（首页 / 设置）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    s: com.aoooa.webadb.ui.i18n.Strings,
    lang: String,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    onLangChange: (String) -> Unit,
) {
    var currentTab by remember { mutableStateOf(MainTab.HOME) }
    var debugMode by remember { mutableStateOf(DebugMode.WIRED) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentTab == MainTab.HOME,
                    onClick = { currentTab = MainTab.HOME },
                    icon = { Icon(Icons.Filled.Home, contentDescription = s.tabHome) },
                    label = { Text(s.tabHome) }
                )
                NavigationBarItem(
                    selected = currentTab == MainTab.SETTINGS,
                    onClick = { currentTab = MainTab.SETTINGS },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = s.tabSettings) },
                    label = { Text(s.tabSettings) }
                )
            }
        }
    ) { padding ->
        when (currentTab) {
            MainTab.HOME -> HomeScreen(
                s = s,
                debugMode = debugMode,
                onDebugModeChange = { debugMode = it },
                modifier = Modifier.padding(padding),
            )
            MainTab.SETTINGS -> SettingsScreen(
                s = s,
                lang = lang,
                themeMode = themeMode,
                onThemeChange = onThemeChange,
                onLangChange = onLangChange,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

/**
 * 首页：左上角三条杠折叠菜单选择「有线调试 / 无线调试」。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    s: com.aoooa.webadb.ui.i18n.Strings,
    debugMode: DebugMode,
    onDebugModeChange: (DebugMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        // 顶部栏：三条杠折叠菜单 + 标题 + 状态
        TopAppBar(
            title = {
                Text(
                    when (debugMode) {
                        DebugMode.WIRED -> s.wiredDebug
                        DebugMode.WIRELESS -> s.wirelessDebug
                    }
                )
            },
            navigationIcon = {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.Menu, contentDescription = s.menuTitle)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(s.wiredDebug) },
                        onClick = { onDebugModeChange(DebugMode.WIRED); menuExpanded = false },
                        leadingIcon = { Icon(Icons.Filled.Usb, null) },
                    )
                    DropdownMenuItem(
                        text = { Text(s.wirelessDebug) },
                        onClick = { onDebugModeChange(DebugMode.WIRELESS); menuExpanded = false },
                        leadingIcon = { Icon(Icons.Filled.Wifi, null) },
                    )
                }
            },
            actions = {
                Text(s.statusDisconnected, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(12.dp))
            },
        )

        // 内容区
        when (debugMode) {
            DebugMode.WIRED -> WiredDebugContent(s)
            DebugMode.WIRELESS -> WirelessDebugContent(s)
        }
    }
}

/**
 * 有线调试页（第 3 批接功能）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun WiredDebugContent(s: com.aoooa.webadb.ui.i18n.Strings) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            OutlinedButton(
                onClick = { /* 第 3 批接入 USB 连接 */ },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Usb, null)
                Spacer(Modifier.width(8.dp))
                Text(s.connectUsb)
            }
        }
        item {
            Text(s.wiredHint, style = MaterialTheme.typography.bodySmall)
        }
        item {
            LogPanel(s)
        }
    }
}

/**
 * 无线调试页（第 4 批接功能）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun WirelessDebugContent(s: com.aoooa.webadb.ui.i18n.Strings) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(s.wirelessIpLabel, style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(
                value = "",
                onValueChange = { },
                placeholder = { Text(s.wirelessIpHint) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { /* 第 4 批接入 TCP 连接 */ },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(s.connectTcp)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { /* 开启 5555 */ }, modifier = Modifier.weight(1f)) {
                    Text(s.enable5555)
                }
                OutlinedButton(onClick = { /* 关闭 5555 */ }, modifier = Modifier.weight(1f)) {
                    Text(s.disable5555)
                }
            }
        }
        item {
            Text(s.pairingTitle, style = MaterialTheme.typography.titleSmall)
            Text(s.pairingHint, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { /* 自己调试自己 */ }, modifier = Modifier.weight(1f)) {
                    Text(s.pairingSelf)
                }
                OutlinedButton(onClick = { /* 调试另一台 */ }, modifier = Modifier.weight(1f)) {
                    Text(s.pairingOther)
                }
            }
        }
        item {
            LogPanel(s)
        }
    }
}

/**
 * 终端日志面板（第 2/3 批接入真实日志流）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun LogPanel(s: com.aoooa.webadb.ui.i18n.Strings) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(s.logTitle, style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { }) { Text(s.clear) }
            }
            // 命令输入
            OutlinedTextField(
                value = "",
                onValueChange = { },
                placeholder = { Text(s.cmdPlaceholder) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { /* 执行命令 */ }, modifier = Modifier.fillMaxWidth()) {
                Text(s.exec)
            }
        }
    }
}

/**
 * 设置页：主题、语言、关于。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    s: com.aoooa.webadb.ui.i18n.Strings,
    lang: String,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    onLangChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 主题
        item {
            Text(s.themeLabel, style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = themeMode == mode,
                        onClick = { onThemeChange(mode) },
                        label = {
                            Text(
                                when (lang) {
                                    "zh" -> mode.labelZh
                                    else -> mode.labelEn
                                }
                            )
                        },
                    )
                }
            }
        }
        // 语言
        item {
            Text(s.langLabel, style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = lang == "zh",
                    onClick = { onLangChange("zh") },
                    label = { Text(s.langZh) },
                )
                FilterChip(
                    selected = lang == "en",
                    onClick = { onLangChange("en") },
                    label = { Text(s.langEn) },
                )
            }
        }
        // 关于
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(s.aboutLabel, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Text("${s.appName} · ${s.aboutVersion} 2.0.0-beta")
                    Spacer(Modifier.height(4.dp))
                    Text(s.aboutDesc, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
