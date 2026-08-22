package com.aoooa.webadb.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.aoooa.webadb.AdbManager
import com.aoooa.webadb.R
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
    onSelfPairing: () -> Unit = {},
    initialThemeMode: ThemeMode = ThemeMode.SYSTEM,
    initialLang: String = "zh"
) {
    // 从 Prefs 读取持久化设置
    var themeMode by remember { mutableStateOf(ThemeMode.fromId(com.aoooa.webadb.Prefs.themeMode)) }
    var lang by remember { mutableStateOf(com.aoooa.webadb.Prefs.lang) }
    var showDisclaimer by remember { mutableStateOf(!com.aoooa.webadb.Prefs.hasAgreedDisclaimer) }
    var isAppReady by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val s = if (lang == "zh") I18n.zh else I18n.en

    // 启动动画平滑就绪（650ms 优雅过渡，防启动黑屏）
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(650)
        isAppReady = true
    }

    WebAdbTheme(mode = themeMode) {
        Crossfade(targetState = isAppReady, label = "AppLaunchTransition") { ready ->
            if (!ready) {
                SplashScreen(s = s)
            } else {
                if (showDisclaimer) {
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text(s.disclaimerTitle) },
                        text = { Text(s.disclaimerContent) },
                        confirmButton = {
                            Button(onClick = {
                                com.aoooa.webadb.Prefs.hasAgreedDisclaimer = true
                                showDisclaimer = false
                            }) {
                                Text(s.disclaimerAgree)
                            }
                        },
                        dismissButton = {
                            OutlinedButton(onClick = {
                                (context as? android.app.Activity)?.finish()
                            }) {
                                Text(s.disclaimerExit)
                            }
                        }
                    )
                }

                MainScreen(
                    s = s, lang = lang, themeMode = themeMode,
                    onThemeChange = { themeMode = it; com.aoooa.webadb.Prefs.themeMode = it.id },
                    onLangChange = { lang = it; com.aoooa.webadb.Prefs.lang = it },
                    onConnectUsb = onConnectUsb,
                    onSelfPairing = onSelfPairing,
                )
            }
        }
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
    onSelfPairing: () -> Unit,
) {
    var currentTab by remember { mutableStateOf(MainTab.HOME) }
    var debugMode by remember { mutableStateOf(DebugMode.WIRELESS) }

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
                onSelfPairing = onSelfPairing,
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
    onSelfPairing: () -> Unit,
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
                        text = { Text(s.wirelessDebug) },
                        onClick = { onDebugModeChange(DebugMode.WIRELESS); menuExpanded = false },
                        leadingIcon = { Icon(Icons.Filled.Wifi, null) },
                    )
                    DropdownMenuItem(
                        text = { Text(s.wiredDebug) },
                        onClick = { onDebugModeChange(DebugMode.WIRED); menuExpanded = false },
                        leadingIcon = { Icon(Icons.Filled.Usb, null) },
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
            DebugMode.WIRELESS -> WirelessDebugContent(s, onSelfPairing)
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
    val tcpip5555Enabled by AdbManager.isTcpip5555Enabled

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
            LogPanel(
                s = s,
                bottomContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${s.tcpip5555StatusLabel}:",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = if (tcpip5555Enabled) s.statusOn else s.statusOff,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (tcpip5555Enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { AdbManager.setTcpip5555(true) },
                                enabled = connected && !tcpip5555Enabled,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.Power, null)
                                Spacer(Modifier.width(4.dp))
                                Text(s.turnOn)
                            }
                            OutlinedButton(
                                onClick = { AdbManager.setTcpip5555(false) },
                                enabled = connected && tcpip5555Enabled,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.PowerOff, null)
                                Spacer(Modifier.width(4.dp))
                                Text(s.turnOff)
                            }
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun WirelessDebugContent(
    s: com.aoooa.webadb.ui.i18n.Strings,
    onSelfPairing: () -> Unit = {},
) {
    var ipInput by remember { mutableStateOf("") }
    val connected by AdbManager.connected
    val context = androidx.compose.ui.platform.LocalContext.current
    var showPairDialog by remember { mutableStateOf(false) }
    var pairIp by remember { mutableStateOf("") }
    var pairPort by remember { mutableStateOf("") }
    var pairCode by remember { mutableStateOf("") }

    val discoveredPort by AdbManager.discoveredDebugPort
    val discoveredHost by AdbManager.discoveredDebugHost

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (discoveredPort > 0 && !connected) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("📡 ${s.discoveredPortLabel}", style = MaterialTheme.typography.labelMedium)
                            Text("${discoveredHost.ifBlank { "127.0.0.1" }}:$discoveredPort", style = MaterialTheme.typography.bodyMedium)
                        }
                        Button(onClick = {
                            if (connected) AdbManager.disconnect()
                            AdbManager.connectTcp(context, discoveredHost.ifBlank { "127.0.0.1" }, discoveredPort)
                        }) {
                            Text(s.connectPairedBtn)
                        }
                    }
                }
            }
        }

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
                        AdbManager.connectTcp(context, host, port)
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
            Spacer(Modifier.height(4.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            // 自己调试自己：Shizuku 模式通知栏自动探测 + 下拉输入配对码
                            onSelfPairing()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.NotificationsActive, null)
                        Spacer(Modifier.width(4.dp))
                        Text(s.pairingSelf)
                    }
                    Button(
                        onClick = {
                            // 秒连本机已配对：若捕获到端口则秒连，否则自动启动搜索
                            if (connected) AdbManager.disconnect()
                            AdbManager.connectDiscovered(context)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Bolt, null)
                        Spacer(Modifier.width(4.dp))
                        Text(s.pairingPaired)
                    }
                }
                OutlinedButton(
                    onClick = {
                        // 调试另一台：手动输入对方 IP、端口和配对码
                        pairIp = ""
                        showPairDialog = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Devices, null)
                    Spacer(Modifier.width(6.dp))
                    Text(s.pairingOther)
                }
            }
        }
        item { LogPanel(s) }
    }

    // 手动配对信息输入对话框（用于调试另一台手机）
    if (showPairDialog) {
        AlertDialog(
            onDismissRequest = { showPairDialog = false },
            title = { Text(s.pairingInputTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pairIp,
                        onValueChange = { pairIp = it },
                        label = { Text(s.pairingIpLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = pairPort,
                        onValueChange = { pairPort = it },
                        label = { Text(s.pairingPortLabel) },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = pairCode,
                        onValueChange = { pairCode = it },
                        label = { Text(s.pairingCodeLabel) },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    AdbManager.log(s.pairingWait)
                    AdbManager.pair(pairIp.trim(), pairPort.trim().toIntOrNull() ?: 0, pairCode.trim())
                    showPairDialog = false
                }) { Text(s.pairingStart) }
            },
            dismissButton = {
                TextButton(onClick = { showPairDialog = false }) { Text(s.pairingCancel) }
            },
        )
    }
}

@Composable
private fun LogPanel(
    s: com.aoooa.webadb.ui.i18n.Strings,
    bottomContent: @Composable (() -> Unit)? = null
) {
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
            if (logs.isEmpty()) {
                Text(s.statusDisconnected, style = MaterialTheme.typography.bodySmall)
            } else {
                Column {
                    logs.takeLast(40).forEach { line ->
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
            if (bottomContent != null) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                bottomContent()
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
    val context = androidx.compose.ui.platform.LocalContext.current

    // 精准检测通知权限（全面兼容 Android 4.4 ~ 15，解决未授权却显示已授权的 Bug）
    fun checkNotificationPermission(): Boolean {
        val areEnabled = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!areEnabled) return false
        return if (android.os.Build.VERSION.SDK_INT >= 33) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    var hasNotifPerm by remember { mutableStateOf(checkNotificationPermission()) }

    // 从系统设置页面返回时自动刷新权限状态
    DisposableEffect(Unit) {
        val lifecycle = (context as? androidx.lifecycle.LifecycleOwner)?.lifecycle
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasNotifPerm = checkNotificationPermission()
            }
        }
        lifecycle?.addObserver(observer)
        onDispose {
            lifecycle?.removeObserver(observer)
        }
    }

    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotifPerm = checkNotificationPermission()
    }

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
                    Text(s.permissionLabel, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.permissionNotifTitle, style = MaterialTheme.typography.bodyMedium)
                            Text(s.permissionNotifDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (hasNotifPerm) {
                            Text(
                                s.permissionGranted,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Button(
                                onClick = {
                                    if (android.os.Build.VERSION.SDK_INT >= 33 && !hasNotifPerm) {
                                        permLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                    try {
                                        val intent = android.content.Intent().apply {
                                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                                action = android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
                                                putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                                            } else {
                                                action = "android.settings.APP_NOTIFICATION_SETTINGS"
                                                putExtra("app_package", context.packageName)
                                                putExtra("app_uid", context.applicationInfo.uid)
                                            }
                                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(intent)
                                    } catch (_: Exception) {}
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(s.permissionGrantBtn)
                            }
                        }
                    }
                }
            }
        }
        item {
            val aboutContext = androidx.compose.ui.platform.LocalContext.current
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(s.aboutLabel, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Text("${s.appName} · ${s.aboutVersion} 2.0.3")
                    Spacer(Modifier.height(4.dp))
                    Text(s.aboutDesc, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            try {
                                val intent = android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://github.com/aoooa101/aoooa-webadb-android")
                                )
                                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                aboutContext.startActivity(intent)
                            } catch (e: Exception) {
                                AdbManager.log("无法打开链接: ${e.message}")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("GitHub")
                    }
                }
            }
        }
    }
}

/**
 * 启动动画页面：顶部软件图标 + 旋转圈圈 + 跳动点点启动中文案（自适应暗色/亮色）
 */
@Composable
private fun SplashScreen(s: com.aoooa.webadb.ui.i18n.Strings) {
    var dotCount by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(350)
            dotCount = (dotCount + 1) % 4
        }
    }
    val dots = ".".repeat(dotCount)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher),
                contentDescription = null,
                modifier = Modifier.size(88.dp)
            )
            Spacer(Modifier.height(32.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(36.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp
            )
            Spacer(Modifier.height(18.dp))
            Text(
                text = "${s.starting}$dots",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
            )
        }
    }
}
