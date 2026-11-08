package com.aoooa.adb.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aoooa.adb.AdbManager
import com.aoooa.adb.adb.shell
import com.aoooa.adb.ui.i18n.I18n
import com.aoooa.adb.ui.i18n.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ToolsSection {
    TOOLS, COMMANDS
}

/**
 * 常见需要 ADB 提权 / 激活 / 启动服务的框架与应用规则实体
 */
data class AdbPrivilegedApp(
    val id: String,
    val packageName: String,
    val nameZh: String,
    val nameEn: String,
    val typeDescZh: String,
    val typeDescEn: String,
    val commands: List<String>,
    val isDynamicDetected: Boolean = false
)

val ADB_PRIVILEGED_PERMISSIONS = mapOf(
    "android.permission.WRITE_SECURE_SETTINGS" to ("修改安全设置" to "Write Secure Settings"),
    "android.permission.DUMP" to ("转储系统状态" to "Dump System"),
    "android.permission.PACKAGE_USAGE_STATS" to ("使用情况统计" to "Package Usage Stats"),
    "android.permission.READ_LOGS" to ("读取系统日志" to "Read Logs"),
    "android.permission.BATTERY_STATS" to ("电池统计数据" to "Battery Stats"),
    "android.permission.CHANGE_CONFIGURATION" to ("修改系统配置" to "Change Configuration"),
    "android.permission.SYSTEM_ALERT_WINDOW" to ("悬浮窗权限" to "System Alert Window"),
    "android.permission.SET_ANIMATION_SCALE" to ("修改动画缩放" to "Set Animation Scale"),
    "android.permission.SCHEDULE_EXACT_ALARM" to ("精确闹钟" to "Exact Alarm"),
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" to ("忽略电池优化" to "Ignore Battery Optimizations")
)

/** 部分特权权限仅 pm grant 不够，需要同步 appops */
internal val ADB_PERMISSION_APPOPS = mapOf(
    "android.permission.PACKAGE_USAGE_STATS" to listOf("GET_USAGE_STATS"),
    "android.permission.SYSTEM_ALERT_WINDOW" to listOf("SYSTEM_ALERT_WINDOW"),
    "android.permission.WRITE_SETTINGS" to listOf("WRITE_SETTINGS"),
    "android.permission.SCHEDULE_EXACT_ALARM" to listOf("SCHEDULE_EXACT_ALARM"),
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" to listOf("RUN_ANY_IN_BACKGROUND", "RUN_IN_BACKGROUND")
)

val KNOWN_ADB_APPS = listOf(
    AdbPrivilegedApp(
        id = "shizuku",
        packageName = "moe.shizuku.privileged.api",
        nameZh = "Shizuku",
        nameEn = "Shizuku",
        typeDescZh = "启动服务 (官方 rish 引导脚本)",
        typeDescEn = "Start Service (Official starter)",
        commands = listOf(
            "sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh || sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh || sh /data/user/0/moe.shizuku.privileged.api/start.sh"
        )
    ),
    AdbPrivilegedApp(
        id = "dhizuku",
        packageName = "com.rosan.dhizuku",
        nameZh = "Dhizuku",
        nameEn = "Dhizuku",
        typeDescZh = "激活 Device Owner 权限",
        typeDescEn = "Activate Device Owner Privilege",
        commands = listOf("dpm set-device-owner com.rosan.dhizuku/.server.DhizukuDAReceiver")
    ),
    AdbPrivilegedApp(
        id = "hail",
        packageName = "com.aistra.hail",
        nameZh = "雹 Hail",
        nameEn = "Hail",
        typeDescZh = "激活 Device Owner 权限",
        typeDescEn = "Activate Device Owner Privilege",
        commands = listOf("dpm set-device-owner com.aistra.hail/.receiver.DeviceAdminReceiver")
    ),
    AdbPrivilegedApp(
        id = "stopapp",
        packageName = "web1n.stopapp",
        nameZh = "小黑屋",
        nameEn = "StopApp",
        typeDescZh = "激活 Device Owner 权限",
        typeDescEn = "Activate Device Owner Privilege",
        commands = listOf("dpm set-device-owner web1n.stopapp/.receiver.AdminReceiver")
    ),
    AdbPrivilegedApp(
        id = "icebox",
        packageName = "com.catchingnow.icebox",
        nameZh = "冰箱 IceBox",
        nameEn = "IceBox",
        typeDescZh = "激活 Device Owner / ADB 服务",
        typeDescEn = "Activate Device Owner / ADB Service",
        commands = listOf(
            "dpm set-device-owner com.catchingnow.icebox/.receiver.DPMReceiver",
            "sh /sdcard/Android/data/com.catchingnow.icebox/files/start.sh || sh /storage/emulated/0/Android/data/com.catchingnow.icebox/files/start.sh"
        )
    ),
    AdbPrivilegedApp(
        id = "brevent",
        packageName = "me.piebridge.brevent",
        nameZh = "黑阈 Brevent",
        nameEn = "Brevent",
        typeDescZh = "启动服务 (brevent.sh)",
        typeDescEn = "Start Service (brevent.sh)",
        commands = listOf("sh /data/data/me.piebridge.brevent/brevent.sh || sh /sdcard/Android/data/me.piebridge.brevent/brevent.sh")
    ),
    AdbPrivilegedApp(
        id = "thanox",
        packageName = "github.tornaco.android.thanos",
        nameZh = "Thanox",
        nameEn = "Thanox",
        typeDescZh = "启动服务 (start.sh)",
        typeDescEn = "Start Service (start.sh)",
        commands = listOf("sh /data/system/thanos/start.sh || sh /sdcard/Android/data/github.tornaco.android.thanos/starter.sh")
    ),
    AdbPrivilegedApp(
        id = "vtools",
        packageName = "com.omarea.vtools",
        nameZh = "Scene 工具箱",
        nameEn = "Scene Toolbox",
        typeDescZh = "授予系统特权并启动守护服务",
        typeDescEn = "Grant Privileges & Start Daemon",
        commands = listOf(
            "pm grant com.omarea.vtools android.permission.WRITE_SECURE_SETTINGS",
            "pm grant com.omarea.vtools android.permission.DUMP",
            "pm grant com.omarea.vtools android.permission.PACKAGE_USAGE_STATS",
            "sh /storage/emulated/0/Android/data/com.omarea.vtools/up.sh || sh /sdcard/Android/data/com.omarea.vtools/up.sh"
        )
    ),
    AdbPrivilegedApp(
        id = "permissiondog",
        packageName = "com.catchingnow.permissiondog",
        nameZh = "权限狗 PermissionDog",
        nameEn = "PermissionDog",
        typeDescZh = "授予系统特权 (Secure Settings & Dump)",
        typeDescEn = "Grant Privileges (Secure Settings & Dump)",
        commands = listOf(
            "pm grant com.catchingnow.permissiondog android.permission.WRITE_SECURE_SETTINGS",
            "pm grant com.catchingnow.permissiondog android.permission.DUMP"
        )
    ),
    AdbPrivilegedApp(
        id = "twtools",
        packageName = "com.twtools.app",
        nameZh = "爱玩机工具箱",
        nameEn = "TwTools",
        typeDescZh = "授予系统特权 (Secure Settings & Usage Stats)",
        typeDescEn = "Grant Privileges (Secure Settings & Usage Stats)",
        commands = listOf(
            "pm grant com.twtools.app android.permission.WRITE_SECURE_SETTINGS",
            "pm grant com.twtools.app android.permission.DUMP",
            "pm grant com.twtools.app android.permission.PACKAGE_USAGE_STATS"
        )
    ),
    AdbPrivilegedApp(
        id = "island",
        packageName = "com.oasisfeng.island",
        nameZh = "炼妖壶 Island",
        nameEn = "Island",
        typeDescZh = "激活 Device Owner 权限",
        typeDescEn = "Activate Device Owner Privilege",
        commands = listOf("dpm set-device-owner com.oasisfeng.island/.IslandDeviceAdminReceiver")
    ),
    AdbPrivilegedApp(
        id = "greenify",
        packageName = "com.oasisfeng.greenify",
        nameZh = "绿色守护 Greenify",
        nameEn = "Greenify",
        typeDescZh = "授予系统特权 (休眠与唤醒追踪)",
        typeDescEn = "Grant Privileges (Hibernation & Wake-up Tracker)",
        commands = listOf(
            "pm grant com.oasisfeng.greenify android.permission.WRITE_SECURE_SETTINGS",
            "pm grant com.oasisfeng.greenify android.permission.DUMP",
            "pm grant com.oasisfeng.greenify android.permission.READ_LOGS",
            "pm grant com.oasisfeng.greenify android.permission.PACKAGE_USAGE_STATS"
        )
    ),
    AdbPrivilegedApp(
        id = "appops",
        packageName = "rikka.appops",
        nameZh = "App Ops",
        nameEn = "App Ops",
        typeDescZh = "启动服务 (官方 start.sh)",
        typeDescEn = "Start Service (Official start.sh)",
        commands = listOf(
            "sh /storage/emulated/0/Android/data/rikka.appops/files/start.sh || sh /sdcard/Android/data/rikka.appops/files/start.sh"
        )
    )
)

/**
 * 实用工具主页面：承载设备看板、5555端口开关、ADB特权应用授权、文件推送、Fastboot镜像刷写等卡片
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    s: Strings,
    lang: String,
    onNavigateToSection: (ToolsSection) -> Unit,
    onNavigateToHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val connected by AdbManager.connected
    val isFastboot by AdbManager.isFastbootMode
    val model by AdbManager.model
    val os by AdbManager.os
    val battery by AdbManager.battery
    val selinux by AdbManager.selinux
    val tcpip5555Enabled by AdbManager.isTcpip5555Enabled

    var menuExpanded by remember { mutableStateOf(false) }

    // 常用工具弹窗状态
    var showPushDialog by remember { mutableStateOf(false) }
    var showFlashDialog by remember { mutableStateOf(false) }
    var showAdbAuthDialog by remember { mutableStateOf(false) }

    var selectedPushUri by remember { mutableStateOf<Uri?>(null) }
    var selectedPushName by remember { mutableStateOf("") }
    var pushTargetDir by remember { mutableStateOf("/sdcard/Download/") }

    var selectedFlashUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFlashName by remember { mutableStateOf("") }
    var flashPartition by remember { mutableStateOf("boot") }

    // 执行结果弹窗状态
    var resultDialogTitle by remember { mutableStateOf("") }
    var resultDialogCommand by remember { mutableStateOf("") }
    var resultDialogOutput by remember { mutableStateOf<String?>(null) }
    var isRunningCommand by remember { mutableStateOf(false) }
    var showResultDialog by remember { mutableStateOf(false) }

    val pushPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedPushUri = uri
            selectedPushName = getFileNameFromUri(context, uri)
        }
    }

    val flashPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedFlashUri = uri
            selectedFlashName = getFileNameFromUri(context, uri)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(s.tabTools) },
            navigationIcon = {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.Menu, contentDescription = s.tabTools)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(s.tabTools, fontWeight = FontWeight.Bold) },
                        onClick = { menuExpanded = false },
                        leadingIcon = { Icon(Icons.Filled.Build, null) }
                    )
                    DropdownMenuItem(
                        text = { Text(s.tabCommands) },
                        onClick = {
                            menuExpanded = false
                            onNavigateToSection(ToolsSection.COMMANDS)
                        },
                        leadingIcon = { Icon(Icons.Filled.Code, null) }
                    )
                }
            },
            actions = {
                Text(
                    text = if (connected) (if (isFastboot) s.fastbootConnected else s.statusConnected) else s.statusDisconnected,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(12.dp))
            }
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. 设备状态信息看板与 5555 端口
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = s.deviceInfoTitle,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = if (connected) (if (isFastboot) s.fastbootConnected else s.statusConnected) else s.statusDisconnected,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (connected) {
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text("${s.model}: ${model.ifBlank { I18n.current.valueUnknown }}", style = MaterialTheme.typography.bodySmall)
                                    Spacer(Modifier.height(2.dp))
                                    Text("${s.os}: ${os.ifBlank { I18n.current.valueUnknown }}", style = MaterialTheme.typography.bodySmall)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text("${s.bat}: ${battery.ifBlank { I18n.current.valueUnknown }}", style = MaterialTheme.typography.bodySmall)
                                    Spacer(Modifier.height(2.dp))
                                    Text("${s.sel}: ${selinux.ifBlank { I18n.current.valueUnknown }}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (!isFastboot) {
                                Spacer(Modifier.height(10.dp))
                                HorizontalDivider()
                                Spacer(Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(s.tcpip5555StatusLabel, style = MaterialTheme.typography.bodySmall)
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            if (tcpip5555Enabled) s.statusOn else s.statusOff,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (tcpip5555Enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Button(
                                        onClick = { AdbManager.setTcpip5555(!tcpip5555Enabled) },
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                    ) {
                                        Text(if (tcpip5555Enabled) s.turnOff else s.turnOn)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. 应用与框架特权授权卡片
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilledTonalIconButton(
                                onClick = { showAdbAuthDialog = true },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.adbAuthBtn, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    s.adbAuthDesc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { showAdbAuthDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = connected && !isFastboot
                        ) {
                            Icon(Icons.Filled.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(s.adbAuthBtn)
                        }
                    }
                }
            }

            // 3. 文件推送卡片
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilledTonalIconButton(
                                onClick = { showPushDialog = true },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(Icons.Filled.UploadFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.pushTitle, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    s.pushHint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { showPushDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = connected && !isFastboot
                        ) {
                            Icon(Icons.Filled.DriveFileMove, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(s.pushTitle)
                        }
                    }
                }
            }

            // 4. Fastboot 镜像刷写卡片
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilledTonalIconButton(
                                onClick = { showFlashDialog = true },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(Icons.Filled.FlashOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.flashTitle, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    s.fastbootHint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { showFlashDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = connected && isFastboot
                        ) {
                            Icon(Icons.Filled.FlashOn, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(s.flashTitle)
                        }
                    }
                }
            }
        }
    }

    // 弹窗 A：ADB 文件推送 (Push)
    if (showPushDialog) {
        AlertDialog(
            onDismissRequest = { showPushDialog = false },
            title = { Text(s.pushTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = pushTargetDir,
                        onValueChange = { pushTargetDir = it },
                        label = { Text(s.pushTargetDirLabel) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = { pushPickerLauncher.launch("*/*") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (selectedPushName.isBlank()) s.pushChooseFileBtn else String.format(I18n.current.pushSelectedFile, selectedPushName))
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val uri = selectedPushUri
                        if (uri != null && selectedPushName.isNotBlank() && pushTargetDir.isNotBlank()) {
                            AdbManager.pushFile(context, uri, selectedPushName, pushTargetDir.trim())
                            showPushDialog = false
                            onNavigateToHome()
                        }
                    },
                    enabled = selectedPushUri != null && pushTargetDir.isNotBlank()
                ) {
                    Text(s.pushStartBtn)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showPushDialog = false }) { Text(s.cancel) }
            }
        )
    }

    // 弹窗 C：Fastboot 镜像刷写 (Flash)
    if (showFlashDialog) {
        AlertDialog(
            onDismissRequest = { showFlashDialog = false },
            title = { Text(s.flashTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = flashPartition,
                        onValueChange = { flashPartition = it },
                        label = { Text(s.flashPartitionLabel) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = { flashPickerLauncher.launch("*/*") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (selectedFlashName.isBlank()) s.flashChooseImgBtn else "已选: $selectedFlashName")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val uri = selectedFlashUri
                        if (uri != null && selectedFlashName.isNotBlank() && flashPartition.isNotBlank()) {
                            AdbManager.flashPartition(context, uri, selectedFlashName, flashPartition.trim())
                            showFlashDialog = false
                            onNavigateToHome()
                        }
                    },
                    enabled = selectedFlashUri != null && flashPartition.isNotBlank()
                ) {
                    Text(s.flashStartBtn)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showFlashDialog = false }) { Text(s.cancel) }
            }
        )
    }

    // 弹窗 6：ADB 框架与应用授权 (ADB Auth)
    if (showAdbAuthDialog) {
        val coroutineScope = rememberCoroutineScope()
        var isScanning by remember { mutableStateOf(true) }
        var isGranting by remember { mutableStateOf(false) }
        var detectedPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
        var allCandidateApps by remember { mutableStateOf<List<AdbPrivilegedApp>>(KNOWN_ADB_APPS) }
        val selectedAppIds = remember { mutableStateListOf<String>() }
        var authSearchQuery by remember { mutableStateOf("") }

        LaunchedEffect(Unit) {
            isScanning = true
            withContext(Dispatchers.IO) {
                val pkgSet = mutableSetOf<String>()
                val dynamicList = mutableListOf<AdbPrivilegedApp>()
                val knownPkgSet = KNOWN_ADB_APPS.map { it.packageName }.toSet()

                if (AdbManager.connected.value && AdbManager.connection?.isAuthenticated == true) {
                    val out = AdbManager.connection?.shell("pm list packages -3") ?: ""
                    out.split("\n").forEach { line ->
                        val pkg = line.removePrefix("package:").trim()
                        if (pkg.isNotBlank()) pkgSet.add(pkg)
                    }

                    // 1. 已知框架应用：只加入已安装的
                    dynamicList.addAll(KNOWN_ADB_APPS.filter { pkgSet.contains(it.packageName) })

                    // 2. 扫描设备中声明了特权权限的第三方应用
                    val smartMap = scanPrivilegedAppsViaAdbSmart()
                    for ((pkg, matchedPerms) in smartMap) {
                        if (knownPkgSet.contains(pkg)) continue
                        if (dynamicList.any { it.packageName == pkg }) continue
                        val pm = context.packageManager
                        val appLabel = try {
                            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                        } catch (_: Exception) {
                            pkg.substringAfterLast('.')
                        }
                        val descZh = "动态授权: " + matchedPerms.joinToString(", ") { ADB_PRIVILEGED_PERMISSIONS[it]?.first ?: it }
                        val descEn = "Dynamic grant: " + matchedPerms.joinToString(", ") { ADB_PRIVILEGED_PERMISSIONS[it]?.second ?: it }
                        dynamicList.add(
                            AdbPrivilegedApp(
                                id = "dyn_$pkg",
                                packageName = pkg,
                                nameZh = appLabel,
                                nameEn = appLabel,
                                typeDescZh = descZh,
                                typeDescEn = descEn,
                                commands = buildDynamicGrantCommands(pkg, matchedPerms),
                                isDynamicDetected = true
                            )
                        )
                    }
                } else {
                    // ADB 未连接时退化为从本地应用列表检测安装状态
                    try {
                        val pm = context.packageManager
                        val installedPackages = pm.getInstalledPackages(android.content.pm.PackageManager.GET_PERMISSIONS)
                        for (pi in installedPackages) {
                            val pkg = pi.packageName
                            pkgSet.add(pkg)
                            val matchedKnown = KNOWN_ADB_APPS.firstOrNull { it.packageName == pkg }
                            if (matchedKnown != null) {
                                dynamicList.add(matchedKnown)
                            } else {
                                val requested = pi.requestedPermissions ?: emptyArray()
                                val matchedPerms = requested.filter { ADB_PRIVILEGED_PERMISSIONS.containsKey(it) }
                                if (matchedPerms.isNotEmpty()) {
                                    val appLabel = pi.applicationInfo?.loadLabel(pm)?.toString() ?: pkg.substringAfterLast('.')
                                    val descZh = "动态授权: " + matchedPerms.joinToString(", ") { ADB_PRIVILEGED_PERMISSIONS[it]?.first ?: it }
                                    val descEn = "Dynamic grant: " + matchedPerms.joinToString(", ") { ADB_PRIVILEGED_PERMISSIONS[it]?.second ?: it }
                                    dynamicList.add(
                                        AdbPrivilegedApp(
                                            id = "dyn_$pkg",
                                            packageName = pkg,
                                            nameZh = appLabel,
                                            nameEn = appLabel,
                                            typeDescZh = descZh,
                                            typeDescEn = descEn,
                                            commands = buildDynamicGrantCommands(pkg, matchedPerms),
                                            isDynamicDetected = true
                                        )
                                    )
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }

                withContext(Dispatchers.Main) {
                    detectedPackages = pkgSet
                    allCandidateApps = if (dynamicList.isNotEmpty()) {
                        dynamicList.sortedWith(compareBy({ !it.id.startsWith("dyn_") }, { it.nameZh }))
                    } else {
                        KNOWN_ADB_APPS
                    }
                    selectedAppIds.clear()
                    selectedAppIds.addAll(
                        allCandidateApps
                            .filter { pkgSet.contains(it.packageName) }
                            .map { it.id }
                    )
                    isScanning = false
                }
            }
        }

        val visibleApps = remember(allCandidateApps, authSearchQuery) {
            if (authSearchQuery.isBlank()) {
                allCandidateApps
            } else {
                allCandidateApps.filter { app ->
                    app.nameZh.contains(authSearchQuery, ignoreCase = true) ||
                        app.nameEn.contains(authSearchQuery, ignoreCase = true) ||
                        app.packageName.contains(authSearchQuery, ignoreCase = true)
                }
            }
        }

        AlertDialog(
            onDismissRequest = { if (!isGranting) showAdbAuthDialog = false },
            title = {
                Column {
                    Text(s.adbAuthDialogTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = s.adbAuthDialogSub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                ) {
                    OutlinedTextField(
                        value = authSearchQuery,
                        onValueChange = { authSearchQuery = it },
                        placeholder = { Text(s.adbAuthSearchHint, fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Filled.Search, null, modifier = Modifier.size(16.dp)) },
                        trailingIcon = {
                            if (authSearchQuery.isNotEmpty()) {
                                IconButton(onClick = { authSearchQuery = "" }) {
                                    Icon(Icons.Filled.Clear, null, modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = String.format(s.adbAuthFoundCount, visibleApps.size, selectedAppIds.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = {
                                    selectedAppIds.clear()
                                    selectedAppIds.addAll(visibleApps.map { it.id })
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(s.cmdSelectAll, fontSize = 11.sp)
                            }
                            TextButton(
                                onClick = { selectedAppIds.clear() },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(s.cmdDeselectAll, fontSize = 11.sp)
                            }
                        }
                    }

                    if (isScanning) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                                Spacer(Modifier.height(12.dp))
                                Text(s.adbAuthScanning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else if (visibleApps.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(s.adbAuthNoAppsFound, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(visibleApps, key = { it.id }) { app ->
                                val isChecked = selectedAppIds.contains(app.id)
                                val isInstalled = detectedPackages.contains(app.packageName)
                                val appIconBitmap = remember(app.packageName) {
                                    loadAppIconBitmap(context, app.packageName)
                                }

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isChecked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (isChecked) selectedAppIds.remove(app.id) else selectedAppIds.add(app.id)
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = isChecked,
                                            onCheckedChange = { check ->
                                                if (check) selectedAppIds.add(app.id) else selectedAppIds.remove(app.id)
                                            },
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))

                                        if (appIconBitmap != null) {
                                            Image(
                                                bitmap = appIconBitmap,
                                                contentDescription = null,
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .clip(RoundedCornerShape(6.dp))
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    if (app.isDynamicDetected) Icons.Filled.Apps else Icons.Filled.Extension,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                        Spacer(Modifier.width(8.dp))

                                        Column(Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = if (lang == "zh") app.nameZh else app.nameEn,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                if (app.isDynamicDetected) {
                                                    Spacer(Modifier.width(4.dp))
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = MaterialTheme.colorScheme.tertiaryContainer,
                                                        modifier = Modifier.padding(horizontal = 2.dp)
                                                    ) {
                                                        Text(
                                                            text = s.adbAuthDynamicTag,
                                                            fontSize = 9.sp,
                                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                        )
                                                    }
                                                }
                                                if (!isInstalled) {
                                                    Spacer(Modifier.width(4.dp))
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                                        modifier = Modifier.padding(horizontal = 2.dp)
                                                    ) {
                                                        Text(
                                                            text = s.adbAuthNotInstalledTag,
                                                            fontSize = 9.sp,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                        )
                                                    }
                                                }
                                            }
                                            Spacer(Modifier.height(1.dp))
                                            Text(
                                                text = if (lang == "zh") app.typeDescZh else app.typeDescEn,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 11.sp
                                            )
                                            Text(
                                                text = app.packageName,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val chosen = allCandidateApps.filter { selectedAppIds.contains(it.id) }
                        if (chosen.isEmpty()) return@Button
                        isGranting = true
                        coroutineScope.launch {
                            val sb = StringBuilder()
                            withContext(Dispatchers.IO) {
                                for (app in chosen) {
                                    val title = if (lang == "zh") app.nameZh else app.nameEn
                                    sb.append("=== $title (${app.packageName}) ===\n")
                                    for (cmd in app.commands) {
                                        sb.append("$ $cmd\n")
                                        val out = if (!connected) {
                                            s.terminalNotConnected
                                        } else {
                                            AdbManager.execCapture(cmd).ifBlank { "OK (无输出)\n" }
                                        }
                                        sb.append(out.trim()).append("\n\n")
                                    }
                                }
                            }
                            isGranting = false
                            resultDialogTitle = s.adbAuthResultTitle
                            resultDialogCommand = "批量授权/激活 (${chosen.size} 项)"
                            resultDialogOutput = sb.toString().trim()
                            showResultDialog = true
                            showAdbAuthDialog = false
                        }
                    },
                    enabled = selectedAppIds.isNotEmpty() && !isGranting
                ) {
                    if (isGranting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(String.format(s.adbAuthGrantBtn, selectedAppIds.size))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showAdbAuthDialog = false }, enabled = !isGranting) {
                    Text(s.cancel)
                }
            }
        )
    }

    if (showResultDialog) {
        CommandResultDialog(
            title = resultDialogTitle,
            command = resultDialogCommand,
            output = resultDialogOutput,
            isLoading = isRunningCommand,
            s = s,
            onDismiss = { showResultDialog = false }
        )
    }
}
