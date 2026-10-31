package com.aoooa.adb.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
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
import com.aoooa.adb.ui.i18n.Strings
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

@Composable
internal fun SettingsScreen(
    s: Strings,
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
            SettingsSubPage.ROOT -> SettingsRootPage(s = s, onNavigate = { currentSubPage = it })

            // 二级页面：通用设置
            SettingsSubPage.GENERAL -> SettingsGeneralPage(s = s, lang = lang, themeMode = themeMode, primaryColorLong = primaryColorLong, onThemeChange = onThemeChange, onPrimaryColorChange = onPrimaryColorChange, onLangChange = onLangChange, logSizeText = logSizeText, exportBackupLauncher = exportBackupLauncher, importBackupLauncher = importBackupLauncher, onNavigate = { currentSubPage = it }, onShowReset = { showResetConfirm = it }, onShowCleanLog = { showCleanLogConfirm = it }, onShowCustomColor = { showCustomColorDialog = it })

            // 二级页面：关于（纯净居中布局 + 彩蛋）
            SettingsSubPage.ABOUT -> SettingsAboutPage(s = s, context = context, currentAppVersion = currentAppVersion, onTriggerFireworks = ::triggerFireworks, onNavigate = { currentSubPage = it })
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
 * 设置页分支视图：SettingsAboutPage
 */
@Composable
private fun SettingsAboutPage(
    s: Strings,
    context: Context,
    currentAppVersion: String,
    onTriggerFireworks: (Float, Float) -> Unit,
    onNavigate: (SettingsSubPage) -> Unit,
) {
    var iconOffsetX by remember { mutableFloatStateOf(0f) }
    var iconOffsetY by remember { mutableFloatStateOf(0f) }
    var checkingStatus by remember { mutableStateOf<String?>(null) }
    var remoteVerDisplay by remember { mutableStateOf(UpdateChecker.latestRemoteVersion) }
    var pendingUpdateInfo by remember { mutableStateOf<UpdateInfo?>(null) }

    // 设置页检出可用更新后，交由用户确认再启动下载
    pendingUpdateInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { pendingUpdateInfo = null },
            title = { Text("${s.updateDialogTitle} ${info.tagName}") },
            text = { Text(info.body.take(300).ifBlank { s.updateConfirm }) },
            confirmButton = {
                Button(onClick = {
                    pendingUpdateInfo = null
                    UpdateChecker.startDownload(context, info)
                }) {
                    Text(s.updateConfirm)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingUpdateInfo = null }) {
                    Text(s.updateSkip)
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部返回导航栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onNavigate(SettingsSubPage.ROOT) }) {
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
                                onTriggerFireworks(offset.x + 300f, offset.y + 450f)
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
                        UpdateChecker.checkUpdate(context, currentAppVersion) { info, latest, err ->
                            if (info != null) {
                                remoteVerDisplay = info.versionName
                                checkingStatus = s.aboutStatusNewVersion
                                pendingUpdateInfo = info
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

/**
 * 设置页分支视图：SettingsRootPage
 */
@Composable
private fun SettingsRootPage(
    s: Strings,
    onNavigate: (SettingsSubPage) -> Unit,
) {
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
                    .clickable { onNavigate(SettingsSubPage.GENERAL) },
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
                    .clickable { onNavigate(SettingsSubPage.ABOUT) },
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

/**
 * 设置页分支视图：SettingsGeneralPage
 */
@Composable
private fun SettingsGeneralPage(
    s: Strings,
    lang: String,
    themeMode: ThemeMode,
    primaryColorLong: Long,
    onThemeChange: (ThemeMode) -> Unit,
    onPrimaryColorChange: (Long) -> Unit,
    onLangChange: (String) -> Unit,
    logSizeText: String,
    exportBackupLauncher: ManagedActivityResultLauncher<String, Uri?>,
    importBackupLauncher: ManagedActivityResultLauncher<Array<String>, Uri?>,
    onNavigate: (SettingsSubPage) -> Unit,
    onShowReset: (Boolean) -> Unit,
    onShowCleanLog: (Boolean) -> Unit,
    onShowCustomColor: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部返回导航栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onNavigate(SettingsSubPage.ROOT) }) {
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
                                onClick = { onShowCustomColor(true) },
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
                            onClick = { onShowCleanLog(true) },
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
                            onClick = { onShowReset(true) },
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
