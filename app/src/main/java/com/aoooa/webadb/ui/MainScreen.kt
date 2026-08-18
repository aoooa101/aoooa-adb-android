package com.aoooa.webadb.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.aoooa.webadb.AdbManager
import com.aoooa.webadb.ui.i18n.I18n
import com.aoooa.webadb.ui.theme.ThemeMode
import com.aoooa.webadb.ui.theme.WebAdbTheme

enum class DebugMode(val id: Int) {
    WIRED(0), WIRELESS(1);
    companion object { fun fromId(id: Int): DebugMode = entries.firstOrNull { it.id == id } ?: WIRED }
}

enum class MainTab(val id: Int) {
    HOME(0), SETTINGS(1);
    companion object { fun fromId(id: Int): MainTab = entries.firstOrNull { it.id == id } ?: HOME }
}

@Composable
fun WebAdbApp(
    onConnectUsb: () -> Unit = {},
    initialThemeMode: ThemeMode = ThemeMode.SYSTEM,
    initialLang: String = "zh"
) {
    var themeMode by remember { mutableStateOf(initialThemeMode) }
    var lang by remember { mutableStateOf(initialLang) }
    val s = if (lang == "zh") I18n.zh else I18n.en

    WebAdbTheme(mode = themeMode) {
        MainScreen(
            s = s, lang = lang, themeMode = themeMode,
            onThemeChange = { themeMode = it },
            onLangChange = { lang = it },
            onConnectUsb = onConnectUsb,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    s: com.aoooa.webadb.ui.i18n.Strings,
    lang: String,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    onLangChange: (String) -> Unit,
    onConnectUsb: () -> Unit,
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
                s = s, debugMode = debugMode,
                onDebugModeChange = { debugMode = it },
                onConnectUsb = onConnectUsb,
                modifier = Modifier.padding(padding),
            )
            MainTab.SETTINGS -> SettingsScreen(
                s = s, lang = lang, themeMode = themeMode,
                onThemeChange = onThemeChange, onLangChange = onLangChange,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    s: com.aoooa.webadb.ui.i18n.Strings,
    debugMode: DebugMode,
    onDebugModeChange: (DebugMode) -> Unit,
    onConnectUsb: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val connected by AdbManager.connected

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(when (debugMode) {
                    DebugMode.WIRED -> s.wiredDebug
                    DebugMode.WIRELESS -> s.wirelessDebug
                })
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
                Text(if (connected) s.statusConnected else s.statusDisconnected,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
            },
        )

        when (debugMode) {
            DebugMode.WIRED -> WiredDebugContent(s, onConnectUsb)
            DebugMode.WIRELESS -> WirelessDebugContent(s)
        }
    }
}

@Composable
private fun WiredDebugContent(
    s: com.aoooa.webadb.ui.i18n.Strings,
    onConnectUsb: () -> Unit,
) {
    val connected by AdbManager.connected
    val model by AdbManager.model
    val os by AdbManager.os
    val battery by AdbManager.battery
    val selinux by AdbManager.selinux

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Button(
                onClick = { if (connected) AdbManager.disconnect() else onConnectUsb() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(if (connected) Icons.Filled.LinkOff else Icons.Filled.Usb, null)
                Spacer(Modifier.width(8.dp))
                Text(if (connected) s.disconnect else s.connectUsb)
            }
        }
        if (connected) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        if (model.isNotBlank()) Text("${s.model}: $model")
                        if (os.isNotBlank()) Text("${s.os}: $os")
                        if (battery.isNotBlank()) Text("${s.bat}: $battery")
                        if (selinux.isNotBlank()) Text("${s.sel}: $selinux")
                    }
                }
            }
        }
        item {
            LogPanel(s)
        }
    }
}

@Composable
private fun WirelessDebugContent(s: com.aoooa.webadb.ui.i18n.Strings) {
    var ipInput by remember { mutableStateOf("") }
    val connected by AdbManager.connected
    val context = androidx.compose.ui.platform.LocalContext.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(s.wirelessIpLabel, style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(
                value = ipInput,
                onValueChange = { ipInput = it },
                placeholder = { Text(s.wirelessIpHint) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val input = ipInput.trim()
                    if (input.isNotEmpty()) {
                        val (host, port) = if (input.contains(":")) {
                            input.split(":").let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: 5555) }
                        } else input to 5555
                        if (connected) AdbManager.disconnect()
                        AdbManager.connectTcp(host, port)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(s.connectTcp)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { AdbManager.enableTcpip() }, modifier = Modifier.weight(1f), enabled = connected) {
                    Text(s.enable5555)
                }
                OutlinedButton(onClick = { AdbManager.disableTcpip() }, modifier = Modifier.weight(1f), enabled = connected) {
                    Text(s.disable5555)
                }
            }
        }
        item {
            Text(s.pairingTitle, style = MaterialTheme.typography.titleSmall)
            Text(s.pairingHint, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        // 自己调试自己：打开系统开发者选项/无线调试设置
                        try {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            AdbManager.log("无法打开开发者选项: ${e.message}")
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(s.pairingSelf)
                }
                OutlinedButton(
                    onClick = {
                        // 调试另一台：引导输入对方配对信息（第 4 批增强）
                        AdbManager.log("配对功能开发中：请输入目标设备 IP 后连接")
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(s.pairingOther)
                }
            }
        }
        item { LogPanel(s) }
    }
}

@Composable
private fun LogPanel(s: com.aoooa.webadb.ui.i18n.Strings) {
    var cmd by remember { mutableStateOf("") }
    val logs = AdbManager.logs
    val context = androidx.compose.ui.platform.LocalContext.current

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(s.logTitle, style = MaterialTheme.typography.labelMedium)
                Row {
                    TextButton(onClick = {
                        // 一键复制全部日志到剪贴板
                        val text = logs.joinToString("\n")
                        if (text.isNotBlank()) {
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("WebADB log", text))
                            AdbManager.log(s.copyLog + " ✓")
                        }
                    }) { Text(s.copyLog) }
                    TextButton(onClick = { AdbManager.logs.clear() }) { Text(s.clear) }
                }
            }
            // 日志显示（最新在上）
            if (logs.isEmpty()) {
                Text(s.statusDisconnected, style = MaterialTheme.typography.bodySmall)
            } else {
                Column {
                    logs.take(40).forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = cmd,
                onValueChange = { cmd = it },
                placeholder = { Text(s.cmdPlaceholder) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { AdbManager.exec(cmd); cmd = "" }, modifier = Modifier.fillMaxWidth()) {
                Text(s.exec)
            }
        }
    }
}

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
        item {
            Text(s.themeLabel, style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = themeMode == mode,
                        onClick = { onThemeChange(mode) },
                        label = { Text(if (lang == "zh") mode.labelZh else mode.labelEn) },
                    )
                }
            }
        }
        item {
            Text(s.langLabel, style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = lang == "zh", onClick = { onLangChange("zh") }, label = { Text(s.langZh) })
                FilterChip(selected = lang == "en", onClick = { onLangChange("en") }, label = { Text(s.langEn) })
            }
        }
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
