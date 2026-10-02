package com.aoooa.adb.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aoooa.adb.AdbManager
import com.aoooa.adb.BuildConfig
import com.aoooa.adb.Prefs
import com.aoooa.adb.R
import com.aoooa.adb.ui.i18n.I18n
import com.aoooa.adb.ui.theme.ThemeMode
import com.aoooa.adb.ui.theme.AoooaAdbTheme
import com.aoooa.adb.util.UpdateChecker
import com.aoooa.adb.util.UpdateInfo
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlin.random.Random
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
fun AoooaAdbApp(
    onConnectUsb: () -> Unit = {},
    onConnectFastboot: () -> Unit = {},
    onSelfPairing: () -> Unit = {},
    initialThemeMode: ThemeMode = ThemeMode.SYSTEM,
    initialLang: String = "zh"
) {
    var themeMode by remember { mutableStateOf(ThemeMode.fromId(Prefs.themeMode)) }
    var primaryColorLong by remember { mutableLongStateOf(Prefs.themePrimaryColor) }
    var lang by remember { mutableStateOf(Prefs.lang) }
    var showDisclaimer by remember { mutableStateOf(!Prefs.hasAgreedDisclaimer) }
    var isAppReady by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val s = if (lang == "zh") I18n.zh else I18n.en
    val localVersion = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: BuildConfig.VERSION_NAME
        } catch (_: Exception) {
            BuildConfig.VERSION_NAME
        }
    }

    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var ignoreThisVersionChecked by remember { mutableStateOf(false) }

    // 启动动画平滑就绪（650ms 过渡，防启动黑屏）+ 静默自动检测更新
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(650)
        isAppReady = true

        UpdateChecker.checkUpdate(localVersion) { info, _, _ ->
            if (info != null && !Prefs.isUpdatePaused() && info.tagName != Prefs.ignoredUpdateVersion) {
                updateInfo = info
                showUpdateDialog = true
            }
        }
    }

    AoooaAdbTheme(mode = themeMode, primaryColor = Color(primaryColorLong)) {
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

                if (showUpdateDialog && updateInfo != null) {
                    val info = updateInfo!!
                    AlertDialog(
                        onDismissRequest = {
                            if (ignoreThisVersionChecked) {
                                Prefs.ignoredUpdateVersion = info.tagName
                            }
                            showUpdateDialog = false
                        },
                        title = {
                            Text(
                                text = "${s.updateDialogTitle} ${info.tagName}",
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        text = {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 300.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f, fill = false)
                                        .fillMaxWidth()
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    Text(
                                        text = info.body,
                                        style = MaterialTheme.typography.bodySmall,
                                        lineHeight = 18.sp
                                    )
                                }
                                Spacer(Modifier.height(12.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { ignoreThisVersionChecked = !ignoreThisVersionChecked }
                                ) {
                                    Checkbox(
                                        checked = ignoreThisVersionChecked,
                                        onCheckedChange = { ignoreThisVersionChecked = it }
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = s.ignoreThisVersion,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    if (ignoreThisVersionChecked) {
                                        Prefs.ignoredUpdateVersion = info.tagName
                                    }
                                    showUpdateDialog = false
                                    UpdateChecker.startDownload(context, info)
                                    AdbManager.log(s.updateDownloading)
                                }
                            ) {
                                Text(s.updateConfirm)
                            }
                        },
                        dismissButton = {
                            OutlinedButton(
                                onClick = {
                                    if (ignoreThisVersionChecked) {
                                        Prefs.ignoredUpdateVersion = info.tagName
                                    }
                                    showUpdateDialog = false
                                }
                            ) {
                                Text(s.updateSkip)
                            }
                        }
                    )
                }

                MainScreen(
                    s = s, lang = lang, themeMode = themeMode,
                    primaryColorLong = primaryColorLong,
                    onThemeChange = { themeMode = it; Prefs.themeMode = it.id },
                    onPrimaryColorChange = { primaryColorLong = it; Prefs.themePrimaryColor = it },
                    onLangChange = { lang = it; Prefs.lang = it },
                    onConnectUsb = onConnectUsb,
                    onConnectFastboot = onConnectFastboot,
                    onSelfPairing = onSelfPairing,
                    onManualCheckUpdate = {
                        AdbManager.log(s.checkingUpdate)
                        UpdateChecker.checkUpdate(localVersion) { info, isLatest, err ->
                            if (info != null) {
                                updateInfo = info
                                showUpdateDialog = true
                            } else if (isLatest) {
                                AdbManager.log(s.isLatestVersion + " ✓")
                            } else {
                                AdbManager.log(s.updateFailed + (if (err != null) ": $err" else ""))
                            }
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    s: com.aoooa.adb.ui.i18n.Strings,
    lang: String,
    themeMode: ThemeMode,
    primaryColorLong: Long = 0xFF2563EBL,
    onThemeChange: (ThemeMode) -> Unit,
    onPrimaryColorChange: (Long) -> Unit = {},
    onLangChange: (String) -> Unit,
    onConnectUsb: () -> Unit,
    onConnectFastboot: () -> Unit,
    onSelfPairing: () -> Unit,
    onManualCheckUpdate: () -> Unit = {}
) {
    var currentTab by remember { mutableStateOf(MainTab.HOME) }
    var debugMode by remember { mutableStateOf(DebugMode.WIRELESS) }
    var pendingFastbootMode by remember { mutableStateOf(false) }
    var fastbootDontShowAgain by remember { mutableStateOf(false) }
    var fastbootConfirmCountdown by remember { mutableIntStateOf(0) }

    LaunchedEffect(pendingFastbootMode) {
        if (!pendingFastbootMode) {
            fastbootConfirmCountdown = 0
            return@LaunchedEffect
        }
        fastbootDontShowAgain = false
        for (i in 3 downTo 0) {
            if (!pendingFastbootMode) return@LaunchedEffect
            fastbootConfirmCountdown = i
            if (i > 0) kotlinx.coroutines.delay(1000)
        }
    }

    if (pendingFastbootMode) {
        AlertDialog(
            onDismissRequest = {
                pendingFastbootMode = false
            },
            title = { Text(s.fastbootExperimentalTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(s.fastbootExperimentalMessage)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { fastbootDontShowAgain = !fastbootDontShowAgain }
                    ) {
                        Checkbox(
                            checked = fastbootDontShowAgain,
                            onCheckedChange = { fastbootDontShowAgain = it }
                        )
                        Text(s.fastbootExperimentalDontShowAgain)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (fastbootDontShowAgain) {
                            Prefs.hideFastbootExperimentalWarning = true
                        }
                        pendingFastbootMode = false
                        debugMode = DebugMode.FASTBOOT
                    },
                    enabled = fastbootConfirmCountdown == 0
                ) {
                    Text(
                        if (fastbootConfirmCountdown > 0) {
                            "${s.fastbootExperimentalConfirm} (${fastbootConfirmCountdown})"
                        } else {
                            s.fastbootExperimentalConfirm
                        }
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingFastbootMode = false }
                ) {
                    Text(s.fastbootExperimentalCancel)
                }
            }
        )
    }

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
                onDebugModeChange = { mode ->
                    if (mode == DebugMode.FASTBOOT &&
                        debugMode != DebugMode.FASTBOOT &&
                        !Prefs.hideFastbootExperimentalWarning
                    ) {
                        pendingFastbootMode = true
                    } else {
                        debugMode = mode
                    }
                },
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
                primaryColorLong = primaryColorLong,
                onThemeChange = onThemeChange,
                onPrimaryColorChange = onPrimaryColorChange,
                onLangChange = onLangChange,
                onManualCheckUpdate = onManualCheckUpdate,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    s: com.aoooa.adb.ui.i18n.Strings,
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
    s: com.aoooa.adb.ui.i18n.Strings,
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
    s: com.aoooa.adb.ui.i18n.Strings,
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
    s: com.aoooa.adb.ui.i18n.Strings,
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
 * 连接日志面板，支持长按自由选取复制与全文复制
 */
@Composable
private fun LogPanel(
    s: com.aoooa.adb.ui.i18n.Strings,
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
            // 先做快照，避免后台写 logs 时 LazyColumn 遍历触发 ConcurrentModificationException
            val logSnapshot = remember(logs.size) {
                try {
                    logs.toList().takeLast(40)
                } catch (_: ConcurrentModificationException) {
                    emptyList()
                }
            }
            if (logSnapshot.isEmpty()) {
                Text(s.statusDisconnected, style = MaterialTheme.typography.bodySmall)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)
                ) {
                    items(logSnapshot.size) { idx ->
                        Text(
                            logSnapshot[idx],
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


enum class SettingsSubPage {
    ROOT, GENERAL, ABOUT
}

data class FireworkParticle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    val color: Color,
    val size: Float,
    var alpha: Float = 1f
)

@Composable
private fun SettingsScreen(
    s: com.aoooa.adb.ui.i18n.Strings,
    lang: String,
    themeMode: ThemeMode,
    primaryColorLong: Long = 0xFF2563EBL,
    onThemeChange: (ThemeMode) -> Unit,
    onPrimaryColorChange: (Long) -> Unit = {},
    onLangChange: (String) -> Unit,
    onManualCheckUpdate: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var currentSubPage by remember { mutableStateOf(SettingsSubPage.ROOT) }

    // 系统手势/物理返回键监听：二级页面下返回一级菜单
    BackHandler(enabled = currentSubPage != SettingsSubPage.ROOT) {
        currentSubPage = SettingsSubPage.ROOT
    }

    var showResetConfirm by remember { mutableStateOf(false) }
    var showCleanLogConfirm by remember { mutableStateOf(false) }
    var showCustomColorDialog by remember { mutableStateOf(false) }
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
                        put("themePrimaryColor", Prefs.themePrimaryColor)
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
                Toast.makeText(context, s.backupExportSuccess, Toast.LENGTH_SHORT).show()
                AdbManager.log(s.backupExportSuccess + " ✓")
            } catch (e: Exception) {
                Toast.makeText(context, "导出备份失败: ${e.message}", Toast.LENGTH_LONG).show()
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
                    Toast.makeText(context, s.backupFormatError, Toast.LENGTH_LONG).show()
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
                    if (prefObj.has("themePrimaryColor")) {
                        val col = prefObj.getLong("themePrimaryColor")
                        onPrimaryColorChange(col)
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
                val cmdList = mutableListOf<com.aoooa.adb.model.CommandItem>()
                for (i in 0 until cmdArr.length()) {
                    val cObj = cmdArr.getJSONObject(i)
                    cmdList.add(
                        com.aoooa.adb.model.CommandItem(
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

                Toast.makeText(context, s.backupImportSuccess, Toast.LENGTH_SHORT).show()
                AdbManager.log(s.backupImportSuccess + " ✓")
            } catch (e: Exception) {
                Toast.makeText(context, "${s.backupFormatError}: ${e.message}", Toast.LENGTH_LONG).show()
                AdbManager.log("${s.backupFormatError}: ${e.message}")
            }
        }
    }

    val currentAppVersion = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: BuildConfig.VERSION_NAME
        } catch (_: Exception) {
            BuildConfig.VERSION_NAME
        }
    }

    // 烟花粒子列表与状态
    val fireworkParticles = remember { mutableStateListOf<FireworkParticle>() }

    LaunchedEffect(fireworkParticles.size) {
        if (fireworkParticles.isNotEmpty()) {
            while (fireworkParticles.isNotEmpty()) {
                kotlinx.coroutines.delay(16)
                for (p in fireworkParticles) {
                    p.x += p.vx
                    p.y += p.vy
                    p.vy += 0.35f
                    p.vx *= 0.98f
                    p.alpha -= 0.018f
                }
                fireworkParticles.removeAll { it.alpha <= 0f }
            }
        }
    }

    fun triggerFireworks(centerX: Float, centerY: Float) {
        val colors = listOf(
            Color(0xFFFF3366), Color(0xFFFF9933), Color(0xFFFFEE33),
            Color(0xFF33CC66), Color(0xFF3399FF), Color(0xFF9933FF),
            Color(0xFFFF33CC), Color(0xFF00FFCC)
        )
        for (i in 0 until 80) {
            val angle = Random.nextFloat() * 2f * Math.PI.toFloat()
            val speed = Random.nextFloat() * 14f + 3f
            fireworkParticles.add(
                FireworkParticle(
                    x = centerX,
                    y = centerY,
                    vx = kotlin.math.cos(angle) * speed,
                    vy = kotlin.math.sin(angle) * speed,
                    color = colors[Random.nextInt(colors.size)],
                    size = Random.nextFloat() * 8f + 6f
                )
            )
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        when (currentSubPage) {
            // 一级主菜单：仅两大项（通用设置、关于）
            SettingsSubPage.ROOT -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        Text(
                            text = s.tabSettings,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }

                    // 1. 通用设置入口卡片
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { currentSubPage = SettingsSubPage.GENERAL },
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(18.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(46.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Filled.Tune,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = s.settingsGeneral,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        text = s.settingsGeneralDesc,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    Icons.Filled.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // 2. 关于入口卡片
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { currentSubPage = SettingsSubPage.ABOUT },
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(18.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    modifier = Modifier.size(46.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Filled.Info,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = s.settingsAbout,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        text = s.settingsAboutDesc,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    Icons.Filled.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // 二级页面：通用设置
            SettingsSubPage.GENERAL -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    // 顶部返回导航栏
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { currentSubPage = SettingsSubPage.ROOT }) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                        }
                        Text(
                            text = s.settingsGeneral,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // 1. 主题与颜色设置
                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(s.themeLabel, style = MaterialTheme.typography.titleSmall)
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        ThemeMode.entries.forEach { mode ->
                                            FilterChip(
                                                selected = themeMode == mode,
                                                onClick = { onThemeChange(mode) },
                                                label = { Text(if (lang == "zh") mode.labelZh else mode.labelEn) },
                                            )
                                        }
                                    }

                                    Spacer(Modifier.height(14.dp))
                                    Text(s.themeColorLabel, style = MaterialTheme.typography.titleSmall)
                                    Spacer(Modifier.height(8.dp))

                                    val presetColors = listOf(
                                        0xFF2563EBL to s.colorDefaultBlue,
                                        0xFF8B5CF6L to s.colorPurple,
                                        0xFF10B981L to s.colorGreen,
                                        0xFFF97316L to s.colorOrange,
                                        0xFFEC4899L to s.colorPink,
                                        0xFFEF4444L to s.colorRed,
                                        0xFF06B6D4L to s.colorCyan
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        presetColors.take(4).forEach { (colorVal, colorName) ->
                                            FilterChip(
                                                selected = primaryColorLong == colorVal,
                                                onClick = { onPrimaryColorChange(colorVal) },
                                                label = { Text(colorName, fontSize = 12.sp) }
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        presetColors.drop(4).forEach { (colorVal, colorName) ->
                                            FilterChip(
                                                selected = primaryColorLong == colorVal,
                                                onClick = { onPrimaryColorChange(colorVal) },
                                                label = { Text(colorName, fontSize = 12.sp) }
                                            )
                                        }
                                        val isCustom = presetColors.none { it.first == primaryColorLong }
                                        FilterChip(
                                            selected = isCustom,
                                            onClick = { showCustomColorDialog = true },
                                            label = { Text(s.colorCustom, fontSize = 12.sp) }
                                        )
                                    }
                                }
                            }
                        }

                        // 2. 语言设置
                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(s.langLabel, style = MaterialTheme.typography.titleSmall)
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilterChip(selected = lang == "zh", onClick = { onLangChange("zh") }, label = { Text(s.langZh) })
                                        FilterChip(selected = lang == "en", onClick = { onLangChange("en") }, label = { Text(s.langEn) })
                                    }
                                }
                            }
                        }

                        // 3. 缓存清理卡片
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

                        // 4. 配置与数据备份卡片
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

                                    Spacer(Modifier.height(12.dp))
                                    HorizontalDivider()
                                    Spacer(Modifier.height(10.dp))

                                    // 恢复默认预设指令
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

                        // 更新提醒设置（暂停启动时自动检查更新）
                        item {
                            var pauseUntil by remember { mutableLongStateOf(Prefs.pauseUpdateUntil) }
                            val statusText = when {
                                pauseUntil == -1L -> s.pauseUpdatePermanentStatus
                                pauseUntil > System.currentTimeMillis() -> {
                                    val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                    String.format(s.pauseUpdateUntilDate, df.format(Date(pauseUntil)))
                                }
                                else -> s.pauseUpdateNormalStatus
                            }

                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(s.pauseUpdateSectionTitle, style = MaterialTheme.typography.titleSmall)
                                    Spacer(Modifier.height(4.dp))
                                    Text(s.pauseUpdateSectionDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = String.format(s.pauseUpdateStatus, statusText),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        FilterChip(
                                            selected = (pauseUntil == 0L || (pauseUntil > 0 && pauseUntil <= System.currentTimeMillis())),
                                            onClick = {
                                                Prefs.pauseUpdateUntil = 0L
                                                pauseUntil = 0L
                                                AdbManager.log(s.pauseUpdateNormalStatus)
                                            },
                                            label = { Text(s.pauseUpdateNormal, fontSize = 12.sp) }
                                        )

                                        FilterChip(
                                            selected = (pauseUntil > System.currentTimeMillis() && pauseUntil <= System.currentTimeMillis() + 8 * 24 * 3600 * 1000L),
                                            onClick = {
                                                val target = System.currentTimeMillis() + 7 * 24 * 3600 * 1000L
                                                Prefs.pauseUpdateUntil = target
                                                pauseUntil = target
                                                val df = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                                                AdbManager.log(String.format(s.pauseUpdateUntilDate, df.format(Date(target))))
                                            },
                                            label = { Text(s.pauseUpdate7Days, fontSize = 12.sp) }
                                        )

                                        FilterChip(
                                            selected = (pauseUntil > System.currentTimeMillis() + 8 * 24 * 3600 * 1000L),
                                            onClick = {
                                                val target = System.currentTimeMillis() + 14 * 24 * 3600 * 1000L
                                                Prefs.pauseUpdateUntil = target
                                                pauseUntil = target
                                                val df = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                                                AdbManager.log(String.format(s.pauseUpdateUntilDate, df.format(Date(target))))
                                            },
                                            label = { Text(s.pauseUpdate14Days, fontSize = 12.sp) }
                                        )

                                        FilterChip(
                                            selected = (pauseUntil == -1L),
                                            onClick = {
                                                Prefs.pauseUpdateUntil = -1L
                                                pauseUntil = -1L
                                                AdbManager.log(s.pauseUpdatePermanentStatus)
                                            },
                                            label = { Text(s.pauseUpdatePermanent, fontSize = 12.sp) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 二级页面：关于（纯净居中布局 + 彩蛋）
            SettingsSubPage.ABOUT -> {
                var iconOffsetX by remember { mutableFloatStateOf(0f) }
                var iconOffsetY by remember { mutableFloatStateOf(0f) }
                var checkingStatus by remember { mutableStateOf<String?>(null) }
                var remoteVerDisplay by remember { mutableStateOf(UpdateChecker.latestRemoteVersion) }

                Column(modifier = Modifier.fillMaxSize()) {
                    // 顶部返回导航栏
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { currentSubPage = SettingsSubPage.ROOT }) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                        }
                        Text(
                            text = s.settingsAbout,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // 1. App 图标（彩蛋一：支持屏幕任意拖动）
                            Box(
                                modifier = Modifier
                                    .offset { IntOffset(iconOffsetX.roundToInt(), iconOffsetY.roundToInt()) }
                                    .pointerInput(Unit) {
                                        detectDragGestures { change, dragAmount ->
                                            change.consume()
                                            iconOffsetX += dragAmount.x
                                            iconOffsetY += dragAmount.y
                                        }
                                    }
                            ) {
                                Card(
                                    shape = RoundedCornerShape(22.dp),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                                ) {
                                    Image(
                                        painter = painterResource(R.drawable.ic_launcher),
                                        contentDescription = "App Icon",
                                        modifier = Modifier
                                            .size(92.dp)
                                            .clip(RoundedCornerShape(22.dp))
                                    )
                                }
                            }

                            Spacer(Modifier.height(18.dp))

                            // 2. 应用名称（彩蛋二：长按名称绽放烟花）
                            Box(
                                modifier = Modifier.pointerInput(Unit) {
                                    detectTapGestures(
                                        onLongPress = { offset ->
                                            triggerFireworks(offset.x + 300f, offset.y + 450f)
                                        }
                                    )
                                }
                            ) {
                                Text(
                                    text = s.appName,
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Spacer(Modifier.height(14.dp))

                            // 3. 当前版本号
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            ) {
                                Text(
                                    text = "${s.aboutCurrentVersion}: v$currentAppVersion",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }

                            Spacer(Modifier.height(8.dp))

                            // 4. 检测到的远端版本
                            val displayRemote = remoteVerDisplay?.let { "v$it" } ?: s.aboutStatusNotChecked
                            Text(
                                text = "${s.aboutRemoteVersion}: $displayRemote",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(Modifier.height(8.dp))

                            // 5. 是否为最新版状态展示
                            val statusText = when {
                                checkingStatus != null -> checkingStatus!!
                                remoteVerDisplay == null -> s.aboutStatusNotChecked
                                remoteVerDisplay == currentAppVersion -> s.aboutStatusLatest
                                else -> s.aboutStatusNewVersion
                            }

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (statusText == s.aboutStatusLatest) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = statusText + if (statusText == s.aboutStatusLatest) " ✓" else "",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (statusText == s.aboutStatusLatest) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                            }

                            Spacer(Modifier.height(28.dp))

                            // 6. 最下方居中：检查更新大按钮
                            Button(
                                onClick = {
                                    checkingStatus = s.aboutStatusChecking
                                    UpdateChecker.checkUpdate(currentAppVersion) { info, latest, err ->
                                        if (info != null) {
                                            remoteVerDisplay = info.versionName
                                            checkingStatus = s.aboutStatusNewVersion
                                        } else if (latest) {
                                            remoteVerDisplay = UpdateChecker.latestRemoteVersion ?: currentAppVersion
                                            checkingStatus = s.aboutStatusLatest
                                        } else {
                                            checkingStatus = err ?: "检测异常"
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth(0.68f)
                                    .height(46.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(s.checkUpdate, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            }

                            Spacer(Modifier.height(12.dp))

                            // 7. 前往 GitHub 仓库
                            OutlinedButton(
                                onClick = {
                                    try {
                                        val intent = android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse("https://github.com/aoooa101/aoooa-adb-android")
                                        )
                                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        AdbManager.log("无法打开链接: ${e.message}")
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(0.68f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(s.aboutGoToRepo, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }

        // 烟花粒子覆盖绘制层
        if (fireworkParticles.isNotEmpty()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                fireworkParticles.forEach { p ->
                    drawCircle(
                        color = p.color.copy(alpha = p.alpha.coerceIn(0f, 1f)),
                        radius = p.size,
                        center = Offset(p.x, p.y)
                    )
                }
            }
        }
    }

    // 自定义颜色取色弹窗
    if (showCustomColorDialog) {
        var rVal by remember { mutableFloatStateOf(Color(primaryColorLong).red * 255f) }
        var gVal by remember { mutableFloatStateOf(Color(primaryColorLong).green * 255f) }
        var bVal by remember { mutableFloatStateOf(Color(primaryColorLong).blue * 255f) }
        val previewColor = Color(rVal.toInt(), gVal.toInt(), bVal.toInt())

        AlertDialog(
            onDismissRequest = { showCustomColorDialog = false },
            title = { Text(s.colorCustomDialogTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(previewColor)
                    )
                    Text("红 (R): ${rVal.toInt()}", fontSize = 12.sp)
                    Slider(
                        value = rVal,
                        onValueChange = { rVal = it },
                        valueRange = 0f..255f
                    )
                    Text("绿 (G): ${gVal.toInt()}", fontSize = 12.sp)
                    Slider(
                        value = gVal,
                        onValueChange = { gVal = it },
                        valueRange = 0f..255f
                    )
                    Text("蓝 (B): ${bVal.toInt()}", fontSize = 12.sp)
                    Slider(
                        value = bVal,
                        onValueChange = { bVal = it },
                        valueRange = 0f..255f
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val finalLong = (0xFFL shl 24) or (rVal.toLong() shl 16) or (gVal.toLong() shl 8) or bVal.toLong()
                    onPrimaryColorChange(finalLong)
                    showCustomColorDialog = false
                }) {
                    Text(s.confirm)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showCustomColorDialog = false }) {
                    Text(s.cancel)
                }
            }
        )
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
                        Toast.makeText(context, s.logCleanSuccess, Toast.LENGTH_SHORT).show()
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
                        Toast.makeText(context, s.cmdRestoreDefault, Toast.LENGTH_SHORT).show()
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
private fun SplashScreen(s: com.aoooa.adb.ui.i18n.Strings) {
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
