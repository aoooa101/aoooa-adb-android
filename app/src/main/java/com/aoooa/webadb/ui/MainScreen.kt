package com.aoooa.webadb.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aoooa.webadb.AdbManager
import com.aoooa.webadb.Prefs
import com.aoooa.webadb.R
import com.aoooa.webadb.ui.i18n.I18n
import com.aoooa.webadb.ui.theme.ThemeMode
import com.aoooa.webadb.ui.theme.WebAdbTheme
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DebugMode(val id: Int) {
    WIRED(0), WIRELESS(1), FASTBOOT(2);
    companion object { fun fromId(id: Int): DebugMode = entries.firstOrNull { it.id == id } ?: WIRELESS }
}

enum class MainTab(val id: Int) {
    HOME(0), TERMINAL(1), COMMANDS(2), SETTINGS(3);
    companion object { fun fromId(id: Int): MainTab = entries.firstOrNull { it.id == id } ?: HOME }
}

@Composable
fun WebAdbApp(
    onConnectUsb: () -> Unit = {},
    onConnectFastboot: () -> Unit = {},
    onSelfPairing: () -> Unit = {},
    initialThemeMode: ThemeMode = ThemeMode.SYSTEM,
    initialLang: String = "zh"
) {
    var themeMode by remember { mutableStateOf(ThemeMode.fromId(Prefs.themeMode)) }
    var lang by remember { mutableStateOf(Prefs.lang) }
    var showDisclaimer by remember { mutableStateOf(!Prefs.hasAgreedDisclaimer) }
    var isAppReady by remember { mutableStateOf(false) }
    val context = LocalContext.current
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
                                Prefs.hasAgreedDisclaimer = true
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
                    onThemeChange = { themeMode = it; Prefs.themeMode = it.id },
                    onLangChange = { lang = it; Prefs.lang = it },
                    onConnectUsb = onConnectUsb,
                    onConnectFastboot = onConnectFastboot,
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
    onConnectFastboot: () -> Unit,
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
                    selected = currentTab == MainTab.TERMINAL,
                    onClick = { currentTab = MainTab.TERMINAL },
                    icon = { Icon(Icons.Filled.Terminal, contentDescription = s.tabTerminal) },
                    label = { Text(s.tabTerminal) }
                )
                NavigationBarItem(
                    selected = currentTab == MainTab.COMMANDS,
                    onClick = { currentTab = MainTab.COMMANDS },
                    icon = { Icon(Icons.Filled.Code, contentDescription = s.tabCommands) },
                    label = { Text(s.tabCommands) }
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
                onConnectFastboot = onConnectFastboot,
                onSelfPairing = onSelfPairing,
                modifier = Modifier.padding(padding),
            )
            MainTab.TERMINAL -> TerminalScreen(
                s = s,
                lang = lang,
                modifier = Modifier.padding(padding),
            )
            MainTab.COMMANDS -> CommandsScreen(
                s = s,
                lang = lang,
                onExecuteCommand = { AdbManager.exec(it) },
                onNavigateToHome = { currentTab = MainTab.HOME },
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
    onConnectFastboot: () -> Unit,
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
                    DebugMode.FASTBOOT -> s.fastbootDebug
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
                    DropdownMenuItem(
                        text = { Text(s.fastbootDebug) },
                        onClick = { onDebugModeChange(DebugMode.FASTBOOT); menuExpanded = false },
                        leadingIcon = { Icon(Icons.Filled.FlashOn, null) },
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
            DebugMode.FASTBOOT -> FastbootDebugContent(s, onConnectFastboot)
        }
    }
}

@Composable
private fun WiredDebugContent(
    s: com.aoooa.webadb.ui.i18n.Strings,
    onConnectUsb: () -> Unit,
) {
    val connected by AdbManager.connected

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
        item { LogPanel(s) }
    }
}

@Composable
private fun FastbootDebugContent(
    s: com.aoooa.webadb.ui.i18n.Strings,
    onConnectUsb: () -> Unit,
) {
    val connected by AdbManager.connected

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(s.fastbootTitle, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(s.fastbootHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { if (connected) AdbManager.disconnect() else onConnectUsb() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(if (connected) Icons.Filled.LinkOff else Icons.Filled.FlashOn, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (connected) s.disconnect else s.fastbootConnectBtn)
                    }
                }
            }
        }
        item { LogPanel(s) }
    }
}

@Composable
private fun WirelessDebugContent(
    s: com.aoooa.webadb.ui.i18n.Strings,
    onSelfPairing: () -> Unit = {},
) {
    var ipInput by remember { mutableStateOf("") }
    val connected by AdbManager.connected
    val context = LocalContext.current
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(s.discoveredPortLabel, style = MaterialTheme.typography.labelMedium)
                            }
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
            Text(s.pairingTitle, style = MaterialTheme.typography.titleSmall)
            Text(s.pairingHint, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onSelfPairing() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.NotificationsActive, null)
                        Spacer(Modifier.width(4.dp))
                        Text(s.pairingSelf)
                    }
                    Button(
                        onClick = {
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

/**
 * 纯连接日志面板（已彻底移除命令行输入框，支持长按自由选取复制与一键全局复制）
 */
@Composable
private fun LogPanel(
    s: com.aoooa.webadb.ui.i18n.Strings,
    bottomContent: @Composable (() -> Unit)? = null
) {
    val logs = AdbManager.logs
    val context = LocalContext.current

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
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("aoooa-adb log", text))
                            AdbManager.log(s.copyLog + " ✓")
                        }
                    }) { Text(s.copyLog) }
                    TextButton(onClick = { AdbManager.logs.clear() }) { Text(s.clear) }
                }
            }
            if (logs.isEmpty()) {
                Text(s.statusDisconnected, style = MaterialTheme.typography.bodySmall)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)
                ) {
                    items(logs.takeLast(40)) { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
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
    val context = LocalContext.current
    var showResetConfirm by remember { mutableStateOf(false) }
    var showCleanLogConfirm by remember { mutableStateOf(false) }
    var logSizeText by remember { mutableStateOf(AdbManager.formatFileSize(AdbManager.getLogDirectorySize(context))) }

    fun refreshLogSize() {
        logSizeText = AdbManager.formatFileSize(AdbManager.getLogDirectorySize(context))
    }

    // JSON 备份导出 Launcher
    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val backupObj = JSONObject().apply {
                    put("version", 1)
                    put("appName", "aoooa-adb")
                    put("exportTime", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date()))
                    put("preferences", JSONObject().apply {
                        put("themeMode", Prefs.themeMode)
                        put("lang", Prefs.lang)
                    })
                    val customCats = JSONArray()
                    Prefs.loadCustomCategories().forEach { customCats.put(it) }
                    put("customCategories", customCats)

                    val cmdsArray = JSONArray()
                    Prefs.loadCommands().forEach { cmd ->
                        cmdsArray.put(JSONObject().apply {
                            put("id", cmd.id)
                            put("nameZh", cmd.nameZh)
                            put("nameEn", cmd.nameEn)
                            put("command", cmd.command)
                            put("category", cmd.category)
                            put("isBuiltin", cmd.isBuiltin)
                        })
                    }
                    put("commands", cmdsArray)
                }

                context.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(backupObj.toString(2).toByteArray(Charsets.UTF_8))
                }
                AdbManager.log(s.backupExportSuccess + " ✓")
            } catch (e: Exception) {
                AdbManager.log("导出备份异常: ${e.message}")
            }
        }
    }

    // JSON 备份恢复 Launcher
    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val jsonStr = context.contentResolver.openInputStream(uri)?.use { isStream ->
                    isStream.bufferedReader().use { it.readText() }
                } ?: ""

                val obj = JSONObject(jsonStr)
                if (!obj.has("commands")) {
                    AdbManager.log(s.backupFormatError)
                    return@rememberLauncherForActivityResult
                }

                // 恢复偏好
                if (obj.has("preferences")) {
                    val prefObj = obj.getJSONObject("preferences")
                    if (prefObj.has("themeMode")) {
                        val tm = ThemeMode.fromId(prefObj.getInt("themeMode"))
                        onThemeChange(tm)
                    }
                    if (prefObj.has("lang")) {
                        val l = prefObj.getString("lang")
                        onLangChange(l)
                    }
                }

                // 恢复分类
                if (obj.has("customCategories")) {
                    val catArr = obj.getJSONArray("customCategories")
                    val catList = mutableListOf<String>()
                    for (i in 0 until catArr.length()) {
                        val cat = catArr.getString(i)
                        if (cat.isNotBlank() && !catList.contains(cat)) catList.add(cat)
                    }
                    Prefs.saveCustomCategories(catList)
                }

                // 恢复指令
                val cmdArr = obj.getJSONArray("commands")
                val cmdList = mutableListOf<com.aoooa.webadb.model.CommandItem>()
                for (i in 0 until cmdArr.length()) {
                    val cObj = cmdArr.getJSONObject(i)
                    cmdList.add(
                        com.aoooa.webadb.model.CommandItem(
                            id = cObj.optString("id", java.util.UUID.randomUUID().toString()),
                            nameZh = cObj.optString("nameZh", ""),
                            nameEn = cObj.optString("nameEn", ""),
                            command = cObj.optString("command", ""),
                            category = cObj.optString("category", "custom"),
                            isBuiltin = cObj.optBoolean("isBuiltin", false)
                        )
                    )
                }
                if (cmdList.isNotEmpty()) {
                    Prefs.saveCommands(cmdList)
                }

                AdbManager.log(s.backupImportSuccess + " ✓")
            } catch (e: Exception) {
                AdbManager.log("${s.backupFormatError}: ${e.message}")
            }
        }
    }

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

    DisposableEffect(Unit) {
        val lifecycle = (context as? androidx.lifecycle.LifecycleOwner)?.lifecycle
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasNotifPerm = checkNotificationPermission()
                refreshLogSize()
            }
        }
        lifecycle?.addObserver(observer)
        onDispose {
            lifecycle?.removeObserver(observer)
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
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

        // 配置与数据备份卡片
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(s.backupSectionTitle, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(s.backupSectionDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                val ts = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
                                exportBackupLauncher.launch("aoooa_adb_backup_$ts.json")
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(s.exportBackupBtn, fontSize = 13.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                importBackupLauncher.launch(arrayOf("application/json", "text/*"))
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(s.importBackupBtn, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // 日志存储管理卡片
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(s.logCleanSectionTitle, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(s.logCleanSectionDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = String.format(s.logCurrentSize, logSizeText),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { showCleanLogConfirm = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(s.logCleanBtn)
                    }
                }
            }
        }

        // 权限管理
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

        // 恢复默认预设指令
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(s.cmdRestoreDefault, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(s.cmdRestoreDefaultConfirm, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showResetConfirm = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Restore, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(s.cmdRestoreDefault)
                    }
                }
            }
        }

        item {
            val aboutContext = LocalContext.current
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(s.aboutLabel, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Text("${s.appName} · ${s.aboutVersion} 2.5.4")
                    Spacer(Modifier.height(4.dp))
                    Text(s.aboutDesc, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            try {
                                val intent = android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://github.com/aoooa101/aoooa-adb-android")
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

    if (showCleanLogConfirm) {
        AlertDialog(
            onDismissRequest = { showCleanLogConfirm = false },
            title = { Text(s.logCleanBtn) },
            text = { Text(s.logCleanSectionDesc) },
            confirmButton = {
                Button(
                    onClick = {
                        AdbManager.clearLocalLogs(context)
                        showCleanLogConfirm = false
                        refreshLogSize()
                        AdbManager.log(s.logCleanSuccess + " ✓")
                    }
                ) {
                    Text(s.confirm)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showCleanLogConfirm = false }) {
                    Text(s.cancel)
                }
            }
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(s.cmdRestoreDefault) },
            text = { Text(s.cmdRestoreDefaultConfirm) },
            confirmButton = {
                Button(
                    onClick = {
                        Prefs.resetDefaultCommands()
                        showResetConfirm = false
                        AdbManager.log(s.cmdRestoreDefault + " ✓")
                    }
                ) {
                    Text(s.confirm)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showResetConfirm = false }) {
                    Text(s.cancel)
                }
            }
        )
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
