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
import com.aoooa.adb.util.UpdateDownloadState
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

    // 下载与校验状态由 UpdateChecker 统一持有，此处仅负责把状态映射到对话框可见性
    val downloadState by UpdateChecker.downloadState.collectAsState()
    var downloadDialogVisible by remember { mutableStateOf(false) }

    // 启动动画平滑就绪（650ms 过渡，防启动黑屏）+ 静默自动检测更新
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(650)
        isAppReady = true

        // 清理旧版本遗留的私有下载目录
        UpdateChecker.cleanupLegacyDownloadDir(context)
        // 恢复上次被中断的下载任务：仍在进行则续显进度，已完成则进入校验与安装。
        // 此处不调用 cleanUpdateCache：待安装包要在安装器读取期间保持存在，
        // 旧包由 startDownload 与安装完成后的 AppUpdateReceiver 负责清理。
        UpdateChecker.resumePendingInstall(context)

        UpdateChecker.checkUpdate(context, localVersion) { info, _, _ ->
            if (info != null && !Prefs.isUpdatePaused() && info.tagName != Prefs.ignoredUpdateVersion) {
                updateInfo = info
                showUpdateDialog = true
            }
        }
    }

    LaunchedEffect(downloadState) {
        when (downloadState) {
            is UpdateDownloadState.Downloading,
            is UpdateDownloadState.Verifying,
            is UpdateDownloadState.Failed -> downloadDialogVisible = true
            else -> Unit
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

                if (downloadDialogVisible) {
                    val state = downloadState
                    if (state is UpdateDownloadState.Downloading ||
                        state is UpdateDownloadState.Verifying ||
                        state is UpdateDownloadState.Failed
                    ) {
                        UpdateDownloadDialog(
                            s = s,
                            state = state,
                            onHide = { downloadDialogVisible = false },
                            onRetry = {
                                downloadDialogVisible = false
                                updateInfo?.let { UpdateChecker.startDownload(context, it) }
                            },
                            onClose = { downloadDialogVisible = false }
                        )
                    }
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
                        UpdateChecker.checkUpdate(context, localVersion) { info, isLatest, err ->
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
