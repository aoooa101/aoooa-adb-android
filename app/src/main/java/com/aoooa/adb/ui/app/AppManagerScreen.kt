package com.aoooa.adb.ui.app

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.documentfile.provider.DocumentFile
import com.aoooa.adb.AdbManager
import com.aoooa.adb.Prefs
import com.aoooa.adb.ui.i18n.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 被控端应用实体
 */
data class ManagedAppItem(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val isDisabled: Boolean,
    val isRunning: Boolean,
    val isUninstalled: Boolean = false
)

/**
 * 本机已安装应用实体（用于从本机直装至被控端）
 */
data class LocalAppItem(
    val packageName: String,
    val label: String,
    val apkFile: File,
    val isUserApp: Boolean
)

/**
 * 安装来源类型（本机已装应用 vs 本地存储 APK 文件）
 */
sealed class InstallSource {
    data class LocalApp(val label: String, val file: File) : InstallSource()
    data class StorageFile(val fileName: String, val uri: Uri) : InstallSource()
}

/**
 * 筛选器枚举
 */
enum class AppFilter {
    ALL, UNINSTALLED, RUNNING, DISABLED, USER, SYSTEM
}

@Composable
fun AppManagerScreen(
    s: Strings,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val connected by AdbManager.connected

    var appList by remember { mutableStateOf<List<ManagedAppItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var currentFilter by remember { mutableStateOf(AppFilter.ALL) }
    var filterMenuExpanded by remember { mutableStateOf(false) }

    // 运行内存状态 (已用, 总大小)
    var ramUsage by remember { mutableStateOf<Pair<Long, Long>?>(null) }

    // 卡片交互弹窗状态
    var selectedApp by remember { mutableStateOf<ManagedAppItem?>(null) }
    var showActionDialog by remember { mutableStateOf(false) }
    var showAppInfoDialog by remember { mutableStateOf(false) }
    var appInfoText by remember { mutableStateOf("") }
    var showClearDataConfirm by remember { mutableStateOf(false) }

    // 全局模态传输进度指示窗状态
    var isProgressModalVisible by remember { mutableStateOf(false) }
    var progressModalTitle by remember { mutableStateOf("") }
    var progressModalPercent by remember { mutableFloatStateOf(0f) }

    // 首次导出引导配置状态
    var showExportGuideDialog by remember { mutableStateOf(false) }
    var pendingExtractApp by remember { mutableStateOf<ManagedAppItem?>(null) }
    var chosenDirUri by remember { mutableStateOf<Uri?>(null) }
    var chosenDirDisplay by remember { mutableStateOf(Prefs.appDownloadDirDisplay) }

    // 安装流程状态
    var showLocalAppPickerDialog by remember { mutableStateOf(false) }
    var localAppSearchQuery by remember { mutableStateOf("") }
    var localAppList by remember { mutableStateOf<List<LocalAppItem>>(emptyList()) }
    var isLoadingLocalApps by remember { mutableStateOf(false) }
    var showSwitchToFileConfirm by remember { mutableStateOf(false) }

    // 待安装目标源与模式弹窗
    var pendingInstallSource by remember { mutableStateOf<InstallSource?>(null) }
    var showInstallModeDialog by remember { mutableStateOf(false) }
    var autoDeleteTempApk by remember { mutableStateOf(true) }

    // SAF 目录树授权 Launcher
    val dirPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                chosenDirUri = uri
                val doc = DocumentFile.fromTreeUri(context, uri)
                chosenDirDisplay = doc?.name ?: "Download"
            } catch (e: Exception) {
                Toast.makeText(context, String.format(s.appPermissionDirFailed, e.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 安装包文件选择 Launcher
    val apkPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val fileName = queryFileName(context, uri) ?: "app.apk"
            pendingInstallSource = InstallSource.StorageFile(fileName, uri)
            showInstallModeDialog = true
        }
    }

    // 执行安装核心逻辑（统一分发流式与推包）
    fun startInstall(source: InstallSource, isStream: Boolean) {
        if (!connected) {
            Toast.makeText(context, s.terminalNotConnected, Toast.LENGTH_SHORT).show()
            return
        }

        isProgressModalVisible = true
        progressModalTitle = s.appProgressInstalling
        progressModalPercent = 0f

        when (source) {
            is InstallSource.LocalApp -> {
                if (isStream) {
                    Thread {
                        val conn = AdbManager.connection
                        val res = conn?.installStream(source.file, false) { pct ->
                            progressModalPercent = pct
                        } ?: s.terminalNotConnected

                        coroutineScope.launch(Dispatchers.Main) {
                            isProgressModalVisible = false
                            Toast.makeText(context, res, Toast.LENGTH_LONG).show()
                            loadApps(context) { list, ram -> appList = list; ramUsage = ram }
                        }
                    }.start()
                } else {
                    AdbManager.installApkDefault(
                        file = source.file,
                        fileName = "${source.file.name}.apk",
                        autoDelete = autoDeleteTempApk,
                        onProgress = { pct, _ -> progressModalPercent = pct },
                        onResult = { ok, msg ->
                            coroutineScope.launch(Dispatchers.Main) {
                                isProgressModalVisible = false
                                val hint = if (ok) {
                                    if (autoDeleteTempApk) s.appInstallSuccessCleaned else s.appInstallSuccessRetained
                                } else String.format(s.appInstallFailed, msg)
                                Toast.makeText(context, hint, Toast.LENGTH_LONG).show()
                                loadApps(context) { list, ram -> appList = list; ramUsage = ram }
                            }
                        }
                    )
                }
            }
            is InstallSource.StorageFile -> {
                if (isStream) {
                    Thread {
                        val conn = AdbManager.connection
                        val res = conn?.installStream(context, source.uri, false) { pct ->
                            progressModalPercent = pct
                        } ?: s.terminalNotConnected

                        coroutineScope.launch(Dispatchers.Main) {
                            isProgressModalVisible = false
                            Toast.makeText(context, res, Toast.LENGTH_LONG).show()
                            loadApps(context) { list, ram -> appList = list; ramUsage = ram }
                        }
                    }.start()
                } else {
                    AdbManager.installApkDefault(
                        context = context,
                        uri = source.uri,
                        fileName = source.fileName,
                        autoDelete = autoDeleteTempApk,
                        onProgress = { pct, _ -> progressModalPercent = pct },
                        onResult = { ok, msg ->
                            coroutineScope.launch(Dispatchers.Main) {
                                isProgressModalVisible = false
                                val hint = if (ok) {
                                    if (autoDeleteTempApk) s.appInstallSuccessCleaned else s.appInstallSuccessRetained
                                } else String.format(s.appInstallFailed, msg)
                                Toast.makeText(context, hint, Toast.LENGTH_LONG).show()
                                loadApps(context) { list, ram -> appList = list; ramUsage = ram }
                            }
                        }
                    )
                }
            }
        }
    }

    // 执行应用导出备份逻辑
    fun triggerExtractApp(app: ManagedAppItem) {
        val savedTree = Prefs.appDownloadDirUri
        if (savedTree.isBlank()) {
            pendingExtractApp = app
            chosenDirDisplay = Prefs.appDownloadDirDisplay.ifBlank { "Download" }
            showExportGuideDialog = true
        } else {
            isProgressModalVisible = true
            progressModalTitle = s.appProgressDownloading
            progressModalPercent = 0f

            AdbManager.extractAppApk(
                context = context,
                packageName = app.packageName,
                appLabel = app.label,
                onProgress = { pct ->
                    progressModalPercent = pct
                },
                onResult = { ok, fileName, msg ->
                    coroutineScope.launch(Dispatchers.Main) {
                        isProgressModalVisible = false
                        if (ok) {
                            val disp = Prefs.appDownloadDirDisplay.ifBlank { "Download" }
                            Toast.makeText(context, String.format(s.appDownloadComplete, "$disp/$fileName"), Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, String.format(s.appExtractFailed, msg), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            )
        }
    }

    // 数据加载生命周期响应
    LaunchedEffect(connected) {
        if (connected) {
            isLoading = true
            loadApps(context) { list, ram ->
                appList = list
                ramUsage = ram
                isLoading = false
            }
        } else {
            appList = emptyList()
            ramUsage = null
        }
    }

    // 本地已安装应用检索（懒加载）
    LaunchedEffect(showLocalAppPickerDialog) {
        if (showLocalAppPickerDialog && localAppList.isEmpty()) {
            isLoadingLocalApps = true
            withContext(Dispatchers.IO) {
                val list = loadLocalInstalledApps(context)
                withContext(Dispatchers.Main) {
                    localAppList = list
                    isLoadingLocalApps = false
                }
            }
        }
    }

    // 过滤与搜索
    val filteredList = remember(appList, searchQuery, currentFilter) {
        appList.filter { item ->
            val matchFilter = when (currentFilter) {
                AppFilter.ALL -> !item.isUninstalled
                AppFilter.UNINSTALLED -> item.isUninstalled
                AppFilter.RUNNING -> !item.isUninstalled && item.isRunning
                AppFilter.DISABLED -> !item.isUninstalled && item.isDisabled
                AppFilter.USER -> !item.isUninstalled && !item.isSystem
                AppFilter.SYSTEM -> !item.isUninstalled && item.isSystem
            }
            val matchSearch = if (searchQuery.isBlank()) true else {
                item.label.contains(searchQuery, ignoreCase = true) ||
                        item.packageName.contains(searchQuery, ignoreCase = true)
            }
            matchFilter && matchSearch
        }
    }

    // 本机应用过滤列表
    val filteredLocalApps = remember(localAppList, localAppSearchQuery) {
        if (localAppSearchQuery.isBlank()) localAppList
        else localAppList.filter {
            it.label.contains(localAppSearchQuery, ignoreCase = true) ||
                    it.packageName.contains(localAppSearchQuery, ignoreCase = true)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 主界面内容（带高斯模糊响应）
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (isProgressModalVisible) Modifier.blur(16.dp) else Modifier)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            // 1. 顶部搜索框 + 右上角三条杠筛选菜单
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(s.appSearchHint, fontSize = 13.sp) },
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )

                // 筛选菜单入口
                Box {
                    IconButton(
                        onClick = { filterMenuExpanded = true },
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FilterList,
                            contentDescription = "Filter",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    DropdownMenu(
                        expanded = filterMenuExpanded,
                        onDismissRequest = { filterMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(s.appFilterAll, fontWeight = if (currentFilter == AppFilter.ALL) FontWeight.Bold else FontWeight.Normal) },
                            onClick = { currentFilter = AppFilter.ALL; filterMenuExpanded = false },
                            leadingIcon = { Icon(Icons.Filled.Apps, null) }
                        )
                        DropdownMenuItem(
                            text = { Text(s.appFilterUninstalled, fontWeight = if (currentFilter == AppFilter.UNINSTALLED) FontWeight.Bold else FontWeight.Normal) },
                            onClick = { currentFilter = AppFilter.UNINSTALLED; filterMenuExpanded = false },
                            leadingIcon = { Icon(Icons.Filled.Restore, null) }
                        )
                        DropdownMenuItem(
                            text = { Text(s.appFilterRunning, fontWeight = if (currentFilter == AppFilter.RUNNING) FontWeight.Bold else FontWeight.Normal) },
                            onClick = { currentFilter = AppFilter.RUNNING; filterMenuExpanded = false },
                            leadingIcon = { Icon(Icons.Filled.PlayArrow, null) }
                        )
                        DropdownMenuItem(
                            text = { Text(s.appFilterDisabled, fontWeight = if (currentFilter == AppFilter.DISABLED) FontWeight.Bold else FontWeight.Normal) },
                            onClick = { currentFilter = AppFilter.DISABLED; filterMenuExpanded = false },
                            leadingIcon = { Icon(Icons.Filled.Block, null) }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(s.appFilterUser, fontWeight = if (currentFilter == AppFilter.USER) FontWeight.Bold else FontWeight.Normal) },
                            onClick = { currentFilter = AppFilter.USER; filterMenuExpanded = false },
                            leadingIcon = { Icon(Icons.Filled.Person, null) }
                        )
                        DropdownMenuItem(
                            text = { Text(s.appFilterSystem, fontWeight = if (currentFilter == AppFilter.SYSTEM) FontWeight.Bold else FontWeight.Normal) },
                            onClick = { currentFilter = AppFilter.SYSTEM; filterMenuExpanded = false },
                            leadingIcon = { Icon(Icons.Filled.Settings, null) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // 2. 运行内存监控条（纯已用大小，无百分比）
            ramUsage?.let { (usedBytes, totalBytes) ->
                val progress = if (totalBytes > 0) (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
                val usedStr = AdbManager.formatRamSize(usedBytes)
                val totalStr = AdbManager.formatRamSize(totalBytes)

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = String.format(s.appRamUsed, usedStr),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = String.format(s.appRamTotal, totalStr),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = if (progress > 0.85f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // 状态标签与刷新
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when (currentFilter) {
                        AppFilter.ALL -> s.appFilterAll
                        AppFilter.UNINSTALLED -> s.appFilterUninstalled
                        AppFilter.RUNNING -> s.appFilterRunning
                        AppFilter.DISABLED -> s.appFilterDisabled
                        AppFilter.USER -> s.appFilterUser
                        AppFilter.SYSTEM -> s.appFilterSystem
                    } + " (${filteredList.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(
                    onClick = {
                        isLoading = true
                        loadApps(context) { list, ram ->
                            appList = list
                            ramUsage = ram
                            isLoading = false
                        }
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh", modifier = Modifier.size(16.dp))
                }
            }

            Spacer(Modifier.height(4.dp))

            // 3. 应用列表视窗
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp))
                }
            } else if (!connected) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(s.terminalNotConnected, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (filteredList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text("无匹配应用", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(filteredList, key = { it.packageName }) { app ->
                        AppListItem(
                            context = context,
                            app = app,
                            s = s,
                            onClick = {
                                selectedApp = app
                                showActionDialog = true
                            }
                        )
                    }
                }
            }
        }

        // 4. 右下角圆圈加号悬浮安装按钮 (FAB)：默认弹本机应用选择
        FloatingActionButton(
            onClick = { showLocalAppPickerDialog = true },
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .then(if (isProgressModalVisible) Modifier.blur(16.dp) else Modifier)
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Install App")
        }

        // 5. 居中全屏毛玻璃百分比进度弹窗 (模糊遮罩)
        if (isProgressModalVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(enabled = false) {},
                contentAlignment = Alignment.Center
            ) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth(0.82f)
                        .padding(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = progressModalTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(16.dp))
                        LinearProgressIndicator(
                            progress = { progressModalPercent.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "${(progressModalPercent * 100).toInt()}%",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }

    // 弹窗 1：【选择要安装的本机应用】（右上角带切换图标）
    if (showLocalAppPickerDialog) {
        Dialog(onDismissRequest = { showLocalAppPickerDialog = false }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.82f)
                    .padding(8.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // 标题栏：左侧标题，右侧极简切换图标按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = s.appInstallPickLocalAppTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { showSwitchToFileConfirm = true },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.SwapHoriz,
                                    contentDescription = "Switch Mode",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(
                                onClick = { showLocalAppPickerDialog = false },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // 检索输入框
                    OutlinedTextField(
                        value = localAppSearchQuery,
                        onValueChange = { localAppSearchQuery = it },
                        placeholder = { Text(s.appSearchHint, fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Filled.Search, null, modifier = Modifier.size(16.dp)) },
                        trailingIcon = {
                            if (localAppSearchQuery.isNotEmpty()) {
                                IconButton(onClick = { localAppSearchQuery = "" }) {
                                    Icon(Icons.Filled.Close, null, modifier = Modifier.size(14.dp))
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )

                    Spacer(Modifier.height(10.dp))

                    if (isLoadingLocalApps) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        }
                    } else if (filteredLocalApps.isEmpty()) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("未检索到本机应用", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(filteredLocalApps, key = { it.packageName }) { localApp ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            pendingInstallSource = InstallSource.LocalApp(localApp.label, localApp.apkFile)
                                            showLocalAppPickerDialog = false
                                            showInstallModeDialog = true
                                        },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        AppIconView(context, localApp.packageName, Modifier.size(38.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = localApp.label,
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = localApp.packageName,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
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

    // 弹窗 2：切换到选择安装包模式提示确认框
    if (showSwitchToFileConfirm) {
        AlertDialog(
            onDismissRequest = { showSwitchToFileConfirm = false },
            title = { Text("切换模式") },
            text = { Text(s.appInstallSwitchToFileHint) },
            confirmButton = {
                Button(
                    onClick = {
                        showSwitchToFileConfirm = false
                        showLocalAppPickerDialog = false
                        apkPickerLauncher.launch("application/vnd.android.package-archive")
                    }
                ) {
                    Text("是")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showSwitchToFileConfirm = false }) { Text(s.cancel) }
            }
        )
    }

    // 弹窗 3：安装模式选择对话框 (流式安装 vs 默认安装 + 自动删除勾选)
    if (showInstallModeDialog && pendingInstallSource != null) {
        val source = pendingInstallSource!!
        val targetName = when (source) {
            is InstallSource.LocalApp -> source.label
            is InstallSource.StorageFile -> source.fileName
        }

        AlertDialog(
            onDismissRequest = { showInstallModeDialog = false },
            title = { Text(s.appInstallChooseMethod) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("待安装目标: $targetName", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showInstallModeDialog = false
                                startInstall(source, isStream = true)
                            },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(s.appInstallModeStream, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            Text(s.appInstallModeStreamDesc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showInstallModeDialog = false
                                startInstall(source, isStream = false)
                            },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(s.appInstallModeDefault, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            Text(s.appInstallModeDefaultDesc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    // 自动删除勾选框
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { autoDeleteTempApk = !autoDeleteTempApk }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Checkbox(
                            checked = autoDeleteTempApk,
                            onCheckedChange = { autoDeleteTempApk = it }
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(s.appInstallAutoDelete, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showInstallModeDialog = false }) { Text(s.cancel) }
            }
        )
    }

    // 弹窗 4：应用操作菜单对话框
    if (showActionDialog && selectedApp != null) {
        val app = selectedApp!!

        AlertDialog(
            onDismissRequest = { showActionDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIconView(context, app.packageName, Modifier.size(36.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(app.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            text = {
                if (app.isUninstalled) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(s.appUninstalledRestoreHint, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = {
                                showActionDialog = false
                                AdbManager.restoreUninstalledApp(app.packageName) { success, msg ->
                                    coroutineScope.launch(Dispatchers.Main) {
                                        val hint = if (success) s.appActionRestoreSuccess else String.format(s.appActionRestoreFail, msg)
                                        Toast.makeText(context, hint, Toast.LENGTH_SHORT).show()
                                        loadApps(context) { list, ram ->
                                            appList = list
                                            ramUsage = ram
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Filled.Restore, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(s.appActionRestore, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // 启动
                            OutlinedButton(
                                onClick = {
                                    showActionDialog = false
                                    Thread {
                                        AdbManager.connection?.shell("monkey -p ${app.packageName} -c android.intent.category.LAUNCHER 1")
                                    }.start()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(s.appActionLaunch, fontSize = 12.sp)
                            }
                            // 停止
                            OutlinedButton(
                                onClick = {
                                    showActionDialog = false
                                    Thread {
                                        AdbManager.connection?.shell("am force-stop ${app.packageName}")
                                        coroutineScope.launch(Dispatchers.Main) {
                                            Toast.makeText(context, "已停止运行", Toast.LENGTH_SHORT).show()
                                            loadApps(context) { list, ram -> appList = list; ramUsage = ram }
                                        }
                                    }.start()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.Stop, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(s.appActionStop, fontSize = 12.sp)
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // 停用 / 启用
                            OutlinedButton(
                                onClick = {
                                    showActionDialog = false
                                    Thread {
                                        if (app.isDisabled) {
                                            AdbManager.connection?.shell("pm enable ${app.packageName}")
                                        } else {
                                            AdbManager.connection?.shell("pm disable-user --user 0 ${app.packageName}")
                                        }
                                        coroutineScope.launch(Dispatchers.Main) {
                                            Toast.makeText(context, if (app.isDisabled) "已启用" else "已停用", Toast.LENGTH_SHORT).show()
                                            loadApps(context) { list, ram -> appList = list; ramUsage = ram }
                                        }
                                    }.start()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(if (app.isDisabled) Icons.Filled.CheckCircle else Icons.Filled.Block, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (app.isDisabled) s.appActionEnable else s.appActionDisable, fontSize = 12.sp)
                            }
                            // 卸载
                            OutlinedButton(
                                onClick = {
                                    showActionDialog = false
                                    Thread {
                                        val cmd = if (app.isSystem) "pm uninstall -k --user 0 ${app.packageName}" else "pm uninstall ${app.packageName}"
                                        AdbManager.connection?.shell(cmd)
                                        coroutineScope.launch(Dispatchers.Main) {
                                            Toast.makeText(context, "已卸载", Toast.LENGTH_SHORT).show()
                                            loadApps(context) { list, ram -> appList = list; ramUsage = ram }
                                        }
                                    }.start()
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Filled.Delete, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(s.appActionUninstall, fontSize = 12.sp)
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // 清除缓存
                            OutlinedButton(
                                onClick = {
                                    showActionDialog = false
                                    Thread {
                                        AdbManager.connection?.shell("pm trim-caches 999G")
                                        coroutineScope.launch(Dispatchers.Main) {
                                            Toast.makeText(context, s.appActionCacheTrimmed, Toast.LENGTH_SHORT).show()
                                        }
                                    }.start()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.CleaningServices, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(s.appActionClearCache, fontSize = 12.sp)
                            }
                            // 清除数据
                            OutlinedButton(
                                onClick = {
                                    showClearDataConfirm = true
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Filled.DeleteSweep, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(s.appActionClearData, fontSize = 12.sp)
                            }
                        }

                        // 提取 APK 安装包 (ADB Sync RECV)
                        Button(
                            onClick = {
                                showActionDialog = false
                                triggerExtractApp(app)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Filled.Download, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(s.appActionDownload, fontWeight = FontWeight.Bold)
                        }

                        // 应用详情
                        OutlinedButton(
                            onClick = {
                                showActionDialog = false
                                Thread {
                                    val conn = AdbManager.connection
                                    if (conn != null) {
                                        val path = conn.shell("pm path ${app.packageName}").trim()
                                        val dump = conn.shell("dumpsys package ${app.packageName} | grep -E 'userId=|firstInstallTime|lastUpdateTime'").trim()
                                        appInfoText = "=== APK Path ===\n$path\n\n=== Package Dumpsys ===\n$dump"
                                        coroutineScope.launch(Dispatchers.Main) {
                                            showAppInfoDialog = true
                                        }
                                    }
                                }.start()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Info, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(s.appActionInfo, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showActionDialog = false }) { Text(s.close) }
            }
        )
    }

    // 弹窗 5：清除数据二次防误触确认警告
    if (showClearDataConfirm && selectedApp != null) {
        val app = selectedApp!!
        AlertDialog(
            onDismissRequest = { showClearDataConfirm = false },
            title = { Text(s.appActionClearDataConfirmTitle) },
            text = { Text(s.appActionClearDataConfirmMsg) },
            confirmButton = {
                Button(
                    onClick = {
                        showClearDataConfirm = false
                        showActionDialog = false
                        Thread {
                            AdbManager.connection?.shell("pm clear ${app.packageName}")
                            coroutineScope.launch(Dispatchers.Main) {
                                Toast.makeText(context, s.appActionDataCleared, Toast.LENGTH_SHORT).show()
                                loadApps(context) { list, ram -> appList = list; ramUsage = ram }
                            }
                        }.start()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(s.appConfirmClear)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showClearDataConfirm = false }) { Text(s.cancel) }
            }
        )
    }

    // 弹窗 6：应用详细信息展示
    if (showAppInfoDialog) {
        AlertDialog(
            onDismissRequest = { showAppInfoDialog = false },
            title = { Text(s.appActionInfo) },
            text = {
                Text(
                    text = appInfoText,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            },
            confirmButton = {
                Button(onClick = { showAppInfoDialog = false }) { Text(s.confirm) }
            }
        )
    }

    // 弹窗 7：首次导出目录配置引导
    if (showExportGuideDialog) {
        AlertDialog(
            onDismissRequest = { showExportGuideDialog = false },
            title = { Text(s.appDownloadDirGuideTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(s.appDownloadDirGuideMsg, style = MaterialTheme.typography.bodyMedium)

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { dirPickerLauncher.launch(null) },
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(chosenDirDisplay.ifBlank { s.appDownloadDirSelectBtn }, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                Text(s.appDownloadDirReselectHint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val uri = chosenDirUri
                        if (uri != null) {
                            Prefs.appDownloadDirUri = uri.toString()
                            Prefs.appDownloadDirDisplay = chosenDirDisplay
                        } else if (Prefs.appDownloadDirUri.isNotBlank()) {
                            // 保持已有配置
                        } else {
                            Prefs.appDownloadDirDisplay = chosenDirDisplay
                        }
                        showExportGuideDialog = false
                        pendingExtractApp?.let { app ->
                            triggerExtractApp(app)
                        }
                    },
                    enabled = chosenDirUri != null || Prefs.appDownloadDirUri.isNotBlank()
                ) {
                    Text(s.confirm)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showExportGuideDialog = false }) { Text(s.cancel) }
            }
        )
    }
}

/**
 * 列表单项卡片：最左边图标，右侧上方名称（加粗），下方包名，无版本号
 */
@Composable
private fun AppListItem(
    context: Context,
    app: ManagedAppItem,
    s: Strings,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 最左侧：应用图标
            AppIconView(context, app.packageName, Modifier.size(44.dp))

            Spacer(Modifier.width(12.dp))

            // 右侧上下两行：上方名称，下方包名（无版本号）
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (app.isDisabled) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.errorContainer
                        ) {
                            Text(s.appStatusDisabled, fontSize = 10.sp, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                        }
                    } else if (app.isUninstalled) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Text(s.appStatusUninstalled, fontSize = 10.sp, color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 带有本地 PackageManager 缓存与远程机器人回退的应用图标呈现
 */
@Composable
private fun AppIconView(
    context: Context,
    packageName: String,
    modifier: Modifier = Modifier
) {
    val pm = context.packageManager
    var bitmap by remember(packageName) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(packageName) {
        withContext(Dispatchers.IO) {
            try {
                val appInfo = pm.getApplicationInfo(packageName, 0)
                val d = pm.getApplicationIcon(appInfo)
                val bm = drawableToBitmap(d)
                withContext(Dispatchers.Main) { bitmap = bm }
            } catch (_: Exception) {
                // 远程设备未在本地安装时，回退到 null 渲染彩色通用图标
            }
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            modifier = modifier.clip(RoundedCornerShape(8.dp))
        )
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Android,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

private fun drawableToBitmap(drawable: Drawable): Bitmap {
    if (drawable is BitmapDrawable && drawable.bitmap != null) {
        return drawable.bitmap
    }
    val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
    val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}

/**
 * 读取当前主控端（手机本机）安装的应用
 */
private fun loadLocalInstalledApps(context: Context): List<LocalAppItem> {
    val pm = context.packageManager
    val result = mutableListOf<LocalAppItem>()
    try {
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        for (app in apps) {
            val isUserApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0
            val src = app.sourceDir
            if (!src.isNullOrBlank()) {
                val f = File(src)
                if (f.exists()) {
                    val label = try { pm.getApplicationLabel(app).toString() } catch (_: Exception) { app.packageName }
                    result.add(LocalAppItem(app.packageName, label, f, isUserApp))
                }
            }
        }
    } catch (_: Exception) {}
    return result.sortedWith(compareByDescending<LocalAppItem> { it.isUserApp }.thenBy { it.label.lowercase() })
}

/**
 * 后台异步加载全部应用数据、卸载预装包与内存信息
 */
private fun loadApps(
    context: Context,
    onResult: (List<ManagedAppItem>, Pair<Long, Long>?) -> Unit
) {
    Thread {
        val conn = AdbManager.connection
        val pm = context.packageManager
        val items = mutableListOf<ManagedAppItem>()
        var ram: Pair<Long, Long>? = null

        if (conn != null && conn.isAuthenticated) {
            // 1. 读取内存
            ram = AdbManager.getRamUsage()

            try {
                // 2. 全部活跃应用
                val allPkgsOut = conn.shell("pm list packages")
                val activePkgs = allPkgsOut.lines()
                    .map { it.trim().removePrefix("package:").trim() }
                    .filter { it.isNotBlank() }
                    .toSet()

                // 3. 系统应用
                val sysPkgsOut = conn.shell("pm list packages -s")
                val sysPkgs = sysPkgsOut.lines()
                    .map { it.trim().removePrefix("package:").trim() }
                    .filter { it.isNotBlank() }
                    .toSet()

                // 4. 已停用应用
                val disabledPkgsOut = conn.shell("pm list packages -d")
                val disabledPkgs = disabledPkgsOut.lines()
                    .map { it.trim().removePrefix("package:").trim() }
                    .filter { it.isNotBlank() }
                    .toSet()

                // 5. 正在运行的应用 (提取活跃进程宿主包)
                val runningPkgs = mutableSetOf<String>()
                val psOut = conn.shell("dumpsys activity processes")
                for (line in psOut.lines()) {
                    if (line.contains("ProcessRecord{")) {
                        val match = Regex(":[a-zA-Z0-9_.]+/").find(line)
                        if (match != null) {
                            val p = match.value.removePrefix(":").removeSuffix("/")
                            runningPkgs.add(p)
                        }
                    }
                }

                // 6. 已被卸载的系统预装应用
                val uninstalledSysOut = conn.shell("pm list packages -u -s")
                val allSysIncludingUninstalled = uninstalledSysOut.lines()
                    .map { it.trim().removePrefix("package:").trim() }
                    .filter { it.isNotBlank() }
                    .toSet()
                val uninstalledSysPkgs = allSysIncludingUninstalled - activePkgs

                // 组装活跃应用
                for (pkg in activePkgs) {
                    val label = try {
                        val ai = pm.getApplicationInfo(pkg, 0)
                        pm.getApplicationLabel(ai).toString()
                    } catch (_: Exception) {
                        pkg
                    }
                    items.add(
                        ManagedAppItem(
                            packageName = pkg,
                            label = label,
                            isSystem = sysPkgs.contains(pkg),
                            isDisabled = disabledPkgs.contains(pkg),
                            isRunning = runningPkgs.contains(pkg),
                            isUninstalled = false
                        )
                    )
                }

                // 组装已被卸载的系统应用
                for (pkg in uninstalledSysPkgs) {
                    val label = try {
                        val ai = pm.getApplicationInfo(pkg, 0)
                        pm.getApplicationLabel(ai).toString()
                    } catch (_: Exception) {
                        pkg
                    }
                    items.add(
                        ManagedAppItem(
                            packageName = pkg,
                            label = label,
                            isSystem = true,
                            isDisabled = false,
                            isRunning = false,
                            isUninstalled = true
                        )
                    )
                }
            } catch (_: Exception) {}
        }

        val sorted = items.sortedWith(
            compareBy<ManagedAppItem> { it.isUninstalled }
                .thenBy { it.isSystem }
                .thenBy { it.label.lowercase() }
        )
        onResult(sorted, ram)
    }.start()
}

private fun queryFileName(context: Context, uri: Uri): String? {
    if (uri.scheme == "content") {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx)
                }
            }
        } catch (_: Exception) {}
    }
    return uri.lastPathSegment
}
