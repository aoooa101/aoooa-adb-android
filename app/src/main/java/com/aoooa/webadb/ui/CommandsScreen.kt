package com.aoooa.webadb.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aoooa.webadb.AdbManager
import com.aoooa.webadb.Prefs
import com.aoooa.webadb.model.CommandItem
import com.aoooa.webadb.ui.i18n.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
private val ADB_PERMISSION_APPOPS = mapOf(
    "android.permission.PACKAGE_USAGE_STATS" to listOf("GET_USAGE_STATS"),
    "android.permission.SYSTEM_ALERT_WINDOW" to listOf("SYSTEM_ALERT_WINDOW"),
    "android.permission.WRITE_SETTINGS" to listOf("WRITE_SETTINGS"),
    "android.permission.SCHEDULE_EXACT_ALARM" to listOf("SCHEDULE_EXACT_ALARM"),
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" to listOf("RUN_ANY_IN_BACKGROUND", "RUN_IN_BACKGROUND")
)

/** 为动态应用生成可执行授权命令：pm grant + 必要 appops */
private fun buildDynamicGrantCommands(pkg: String, perms: List<String>): List<String> {
    val cmds = linkedSetOf<String>()
    for (perm in perms.distinct()) {
        cmds.add("pm grant $pkg $perm")
        ADB_PERMISSION_APPOPS[perm]?.forEach { op ->
            cmds.add("appops set $pkg $op allow")
        }
    }
    return cmds.toList()
}

/**
 * 通过一次远程 shell 智能扫描：第三方包中声明了特权权限的应用。
 * 输出格式：pkg<TAB>perm1,perm2
 */
private fun scanPrivilegedAppsViaAdbSmart(): Map<String, List<String>> {
    val permList = ADB_PRIVILEGED_PERMISSIONS.keys.joinToString("|") { Regex.escape(it) }
    // 在设备端用短脚本汇总，避免对每个包往返多次 dumpsys
    val script = """
        pm list packages -3 2>/dev/null | sed 's/^package://' | while read -r pkg; do
          [ -z "${"$"}pkg" ] && continue
          dump=${"$"}(dumpsys package "${"$"}pkg" 2>/dev/null)
          matched=${"$"}(printf '%s\n' "${"$"}dump" | grep -oE '$permList' | sort -u | tr '\n' ',')
          matched=${"$"}{matched%,}
          [ -n "${"$"}matched" ] && printf '%s\t%s\n' "${"$"}pkg" "${"$"}matched"
        done
    """.trimIndent().replace('\n', ';')

    val out = try {
        AdbManager.connection?.shell(script) ?: ""
    } catch (_: Exception) {
        ""
    }
    val result = linkedMapOf<String, List<String>>()
    for (line in out.split('\n')) {
        val t = line.trim()
        if (t.isEmpty() || !t.contains('\t')) continue
        val pkg = t.substringBefore('\t').trim()
        val perms = t.substringAfter('\t').split(',').map { it.trim() }.filter { it.isNotEmpty() && ADB_PRIVILEGED_PERMISSIONS.containsKey(it) }
        if (pkg.isNotBlank() && perms.isNotEmpty()) {
            result[pkg] = perms.distinct()
        }
    }
    return result
}

val KNOWN_ADB_APPS = listOf(
    AdbPrivilegedApp(
        id = "shizuku",
        packageName = "moe.shizuku.privileged.api",
        nameZh = "Shizuku",
        nameEn = "Shizuku",
        typeDescZh = "启动服务 (官方 start.sh)",
        typeDescEn = "Start Service (Official start.sh)",
        commands = listOf(
            "sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh || sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh || sh /sdcard/Android/data/moe.shizuku.privileged.api/files/start.sh || sh /data/user/0/moe.shizuku.privileged.api/files/start.sh"
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
        nameZh = "Thanox 淘米",
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
        typeDescZh = "授予系统特权 (Secure Settings & Dump)",
        typeDescEn = "Grant Privileges (Secure Settings & Dump)",
        commands = listOf(
            "pm grant com.omarea.vtools android.permission.WRITE_SECURE_SETTINGS",
            "pm grant com.omarea.vtools android.permission.DUMP",
            "pm grant com.omarea.vtools android.permission.PACKAGE_USAGE_STATS"
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
    )
)

private fun loadAppIconBitmap(context: Context, packageName: String): ImageBitmap? {
    return try {
        val pm = context.packageManager
        val appInfo = pm.getApplicationInfo(packageName, 0)
        val drawable = appInfo.loadIcon(pm)
        val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
            drawable.bitmap
        } else {
            val bmp = Bitmap.createBitmap(
                drawable.intrinsicWidth.coerceAtLeast(1),
                drawable.intrinsicHeight.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bmp
        }
        bitmap.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CommandsScreen(
    s: Strings,
    lang: String,
    onExecuteCommand: (String) -> Unit,
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

    var commandList by remember { mutableStateOf(Prefs.loadCommands()) }
    var customCategories by remember { mutableStateOf(Prefs.loadCustomCategories()) }
    var selectedCategory by remember { mutableStateOf("all") }
    var searchQuery by remember { mutableStateOf("") }
    var isManageMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    val collapsedCategories = remember { mutableStateListOf<String>() }

    // 对话框状态
    var showAddDialog by remember { mutableStateOf(false) }
    var showAddCatDialog by remember { mutableStateOf(false) }
    var editingItem by remember { mutableStateOf<CommandItem?>(null) }
    var itemPendingDelete by remember { mutableStateOf<CommandItem?>(null) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    var showBatchMoveDialog by remember { mutableStateOf(false) }

    // 每次进入页面时自动同步最新的持久化配置（保证导入备份后瞬间生效）
    LaunchedEffect(Unit) {
        commandList = Prefs.loadCommands()
        customCategories = Prefs.loadCustomCategories()
    }

    // 硬核功能弹窗状态
    var showPushDialog by remember { mutableStateOf(false) }
    var showInstallDialog by remember { mutableStateOf(false) }
    var showFlashDialog by remember { mutableStateOf(false) }
    var showAdbAuthDialog by remember { mutableStateOf(false) }

    var selectedPushUri by remember { mutableStateOf<Uri?>(null) }
    var selectedPushName by remember { mutableStateOf("") }
    var pushTargetDir by remember { mutableStateOf("/sdcard/Download/") }

    var selectedInstallUri by remember { mutableStateOf<Uri?>(null) }
    var selectedInstallName by remember { mutableStateOf("") }

    var selectedFlashUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFlashName by remember { mutableStateOf("") }
    var flashPartition by remember { mutableStateOf("boot") }

    // 指令就地执行结果弹窗状态
    var resultDialogTitle by remember { mutableStateOf("") }
    var resultDialogCommand by remember { mutableStateOf("") }
    var resultDialogOutput by remember { mutableStateOf<String?>(null) }
    var isRunningCommand by remember { mutableStateOf(false) }
    var showResultDialog by remember { mutableStateOf(false) }

    fun runAndShowResult(item: CommandItem) {
        resultDialogTitle = if (lang == "zh") item.nameZh else item.nameEn
        resultDialogCommand = item.command
        resultDialogOutput = null
        isRunningCommand = true
        showResultDialog = true

        Thread {
            val output = if (connected) {
                AdbManager.execCapture(item.command).ifBlank { s.logNoOutput }
            } else {
                s.terminalNotConnected
            }
            resultDialogOutput = output
            isRunningCommand = false
        }.start()
    }

    // 文件选择器 Launchers
    val pushPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedPushUri = uri
            selectedPushName = getFileNameFromUri(context, uri)
        }
    }

    val installPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedInstallUri = uri
            selectedInstallName = getFileNameFromUri(context, uri)
        }
    }

    val flashPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedFlashUri = uri
            selectedFlashName = getFileNameFromUri(context, uri)
        }
    }

    fun refreshData() {
        commandList = Prefs.loadCommands()
        customCategories = Prefs.loadCustomCategories()
    }

    // 过滤命令列表
    val filteredCommands = remember(commandList, selectedCategory, searchQuery) {
        commandList.filter { item ->
            val matchCategory = when (selectedCategory) {
                "all" -> true
                else -> item.category == selectedCategory
            }
            val matchQuery = if (searchQuery.isBlank()) true else {
                item.nameZh.contains(searchQuery, ignoreCase = true) ||
                    item.nameEn.contains(searchQuery, ignoreCase = true) ||
                    item.command.contains(searchQuery, ignoreCase = true)
            }
            matchCategory && matchQuery
        }
    }

    // 分组
    val groupedCommands = remember(filteredCommands) {
        filteredCommands.groupBy { it.category }
    }

    fun getCategoryDisplayName(cat: String): String = when (cat) {
        "all" -> s.catAll
        "framework" -> s.catFramework
        "system" -> s.catSystem
        "power" -> s.catPower
        "fastboot" -> s.catFastboot
        "custom" -> s.catCustom
        else -> cat
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 顶部搜索与批量管理栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        text = s.searchPlaceholder,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = s.clear, modifier = Modifier.size(18.dp))
                        }
                    }
                },
                singleLine = true,
                maxLines = 1,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                modifier = Modifier.weight(1f)
            )

            IconButton(onClick = { showAddCatDialog = true }) {
                Icon(Icons.Filled.CreateNewFolder, contentDescription = s.cmdAddCategory)
            }

            IconButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = s.cmdAddTitle)
            }

            IconButton(onClick = {
                isManageMode = !isManageMode
                if (!isManageMode) selectedIds.clear()
            }) {
                Icon(
                    if (isManageMode) Icons.Filled.Check else Icons.Filled.Checklist,
                    contentDescription = s.cmdManage,
                    tint = if (isManageMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // 分类横向切换条
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val allCats = listOf("all", "framework", "system", "power", "fastboot", "custom") + customCategories
            items(allCats.distinct()) { cat ->
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = { selectedCategory = cat },
                    label = { Text(getCategoryDisplayName(cat)) }
                )
            }
        }

        // 批量管理操作条
        if (isManageMode) {
            Surface(
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        if (selectedIds.size == filteredCommands.size) {
                            selectedIds.clear()
                        } else {
                            selectedIds.clear()
                            selectedIds.addAll(filteredCommands.map { it.id })
                        }
                    }) {
                        Text(if (selectedIds.size == filteredCommands.size) s.cmdDeselectAll else s.cmdSelectAll)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = { showBatchMoveDialog = true },
                            enabled = selectedIds.isNotEmpty()
                        ) {
                            Text(s.cmdMoveToCategory)
                        }

                        Button(
                            onClick = { showBatchDeleteConfirm = true },
                            enabled = selectedIds.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text(s.cmdDeleteBatch)
                        }
                    }
                }
            }
        }

        // 主列表
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 顶部设备状态信息看板
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                s.deviceInfoTitle,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                if (connected) (if (isFastboot) s.fastbootConnected else s.statusConnected) else s.statusDisconnected,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (connected) {
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text("${s.model}: ${model.ifBlank { "未知" }}", style = MaterialTheme.typography.bodySmall)
                                    Text("${s.os}: ${os.ifBlank { "未知" }}", style = MaterialTheme.typography.bodySmall)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text("${s.bat}: ${battery.ifBlank { "未知" }}", style = MaterialTheme.typography.bodySmall)
                                    Text("${s.sel}: ${selinux.ifBlank { "未知" }}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (!isFastboot) {
                                Spacer(Modifier.height(8.dp))
                                HorizontalDivider()
                                Spacer(Modifier.height(8.dp))
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
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                    ) {
                                        Text(if (tcpip5555Enabled) s.turnOff else s.turnOn)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 三大硬核工具快捷触发入口
            if (connected) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!isFastboot) {
                            Button(
                                onClick = { showPushDialog = true },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(s.pushTitle.substringBefore(" ("), style = MaterialTheme.typography.labelSmall)
                            }
                            Button(
                                onClick = { showInstallDialog = true },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Filled.InstallMobile, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(s.installTitle.substringBefore(" ("), style = MaterialTheme.typography.labelSmall)
                            }
                            Button(
                                onClick = { showAdbAuthDialog = true },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Filled.VerifiedUser, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(s.adbAuthBtn, style = MaterialTheme.typography.labelSmall)
                            }
                        } else {
                            Button(
                                onClick = { showFlashDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Filled.FlashOn, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(s.flashTitle, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            // 指令分组卡片展示
            if (filteredCommands.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(s.cmdNoCommands, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                groupedCommands.forEach { (cat, list) ->
                    val isCollapsed = collapsedCategories.contains(cat)

                    item(key = "header_$cat") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isCollapsed) collapsedCategories.remove(cat)
                                    else collapsedCategories.add(cat)
                                }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${getCategoryDisplayName(cat)} (${list.size})",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Icon(
                                if (isCollapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                                contentDescription = null
                            )
                        }
                    }

                    if (!isCollapsed) {
                        items(list, key = { it.id }) { item ->
                            val isChecked = selectedIds.contains(item.id)

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            if (isManageMode) {
                                                if (isChecked) selectedIds.remove(item.id) else selectedIds.add(item.id)
                                            } else {
                                                runAndShowResult(item)
                                            }
                                        },
                                        onLongClick = {
                                            editingItem = item
                                        }
                                    ),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isChecked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    if (isManageMode) {
                                        Checkbox(
                                            checked = isChecked,
                                            onCheckedChange = { check ->
                                                if (check) selectedIds.add(item.id) else selectedIds.remove(item.id)
                                            }
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }

                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = if (lang == "zh") item.nameZh else item.nameEn,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = "$ ${item.command}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    if (!isManageMode) {
                                        IconButton(onClick = { editingItem = item }) {
                                            Icon(Icons.Filled.Edit, contentDescription = s.cmdEditTitle, modifier = Modifier.size(18.dp))
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
                        Text(if (selectedPushName.isBlank()) s.pushChooseFileBtn else "已选: $selectedPushName")
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

    // 弹窗 B：ADB 流式安装 APK (Install)
    if (showInstallDialog) {
        var installCompatibleMode by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showInstallDialog = false },
            title = { Text(s.installTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { installPickerLauncher.launch("application/vnd.android.package-archive") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (selectedInstallName.isBlank()) s.installChooseApkBtn else "已选: $selectedInstallName")
                    }
                    Text(s.installModeLabel, style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !installCompatibleMode,
                            onClick = { installCompatibleMode = false },
                            label = { Text(s.installModeNormal) }
                        )
                        FilterChip(
                            selected = installCompatibleMode,
                            onClick = { installCompatibleMode = true },
                            label = { Text(s.installModeCompatible) }
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val uri = selectedInstallUri
                        if (uri != null && selectedInstallName.isNotBlank()) {
                            AdbManager.installApk(context, uri, selectedInstallName, installCompatibleMode)
                            showInstallDialog = false
                            onNavigateToHome()
                        }
                    },
                    enabled = selectedInstallUri != null
                ) {
                    Text(s.installStartBtn)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showInstallDialog = false }) { Text(s.cancel) }
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

    // 弹窗 1：新增快捷指令
    if (showAddDialog) {
        var newNameZh by remember { mutableStateOf("") }
        var newNameEn by remember { mutableStateOf("") }
        var newCmd by remember { mutableStateOf("") }
        var newCat by remember { mutableStateOf("custom") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(s.cmdAddTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newNameZh,
                        onValueChange = { newNameZh = it },
                        label = { Text("${s.cmdNameLabel} (中文)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newNameEn,
                        onValueChange = { newNameEn = it },
                        label = { Text("${s.cmdNameLabel} (English)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newCmd,
                        onValueChange = { newCmd = it },
                        label = { Text(s.cmdContentLabel) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(s.cmdCategoryNameLabel, style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val selectables = listOf("framework", "system", "power", "fastboot", "custom") + customCategories
                        items(selectables.distinct()) { cat ->
                            FilterChip(
                                selected = newCat == cat,
                                onClick = { newCat = cat },
                                label = { Text(getCategoryDisplayName(cat)) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val nameZh = newNameZh.trim()
                        val nameEn = newNameEn.trim().ifBlank { nameZh }
                        val cmd = newCmd.trim()
                        if (nameZh.isNotBlank() && cmd.isNotBlank()) {
                            val newItem = CommandItem(
                                id = java.util.UUID.randomUUID().toString(),
                                nameZh = nameZh,
                                nameEn = nameEn,
                                command = cmd,
                                category = newCat,
                                isBuiltin = false
                            )
                            val updated = commandList + newItem
                            Prefs.saveCommands(updated)
                            refreshData()
                            showAddDialog = false
                        }
                    }
                ) {
                    Text(s.cmdAddBtn)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showAddDialog = false }) { Text(s.cancel) }
            }
        )
    }

    // 弹窗 2：新建自定义分类
    if (showAddCatDialog) {
        var catName by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddCatDialog = false },
            title = { Text(s.cmdAddCategory) },
            text = {
                OutlinedTextField(
                    value = catName,
                    onValueChange = { catName = it },
                    label = { Text(s.cmdCategoryNameLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clean = catName.trim()
                        if (clean.isNotBlank()) {
                            Prefs.addCustomCategory(clean)
                            refreshData()
                            selectedCategory = clean
                            showAddCatDialog = false
                        }
                    }
                ) {
                    Text(s.confirm)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showAddCatDialog = false }) { Text(s.cancel) }
            }
        )
    }

    // 弹窗 3：编辑快捷指令
    editingItem?.let { item ->
        var editName by remember { mutableStateOf(if (lang == "zh") item.nameZh else item.nameEn) }
        var editCmd by remember { mutableStateOf(item.command) }
        var editCat by remember { mutableStateOf(item.category) }

        AlertDialog(
            onDismissRequest = { editingItem = null },
            title = { Text(s.cmdEditTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text(s.cmdNameLabel) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editCmd,
                        onValueChange = { editCmd = it },
                        label = { Text(s.cmdContentLabel) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(s.cmdCategoryNameLabel, style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val selectables = listOf("framework", "system", "power", "fastboot", "custom") + customCategories
                        items(selectables.distinct()) { cat ->
                            FilterChip(
                                selected = editCat == cat,
                                onClick = { editCat = cat },
                                label = { Text(getCategoryDisplayName(cat)) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { showFlashDialog = true },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Filled.FlashOn, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Flash", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { showAdbAuthDialog = true },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Filled.VerifiedUser, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(s.adbAuthBtn, fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            val name = editName.trim()
                            val cmd = editCmd.trim()
                            if (name.isNotBlank() && cmd.isNotBlank()) {
                                val updated = commandList.map {
                                    if (it.id == item.id) {
                                        it.copy(
                                            nameZh = if (lang == "zh") name else it.nameZh,
                                            nameEn = if (lang == "en") name else it.nameEn,
                                            command = cmd,
                                            category = editCat
                                        )
                                    } else it
                                }
                                Prefs.saveCommands(updated)
                                refreshData()
                                editingItem = null
                            }
                        }
                    ) {
                        Text(s.confirm)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { editingItem = null }) {
                    Text(s.cancel)
                }
            }
        )
    }

    // 弹窗 4：单条删除二次确认
    itemPendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { itemPendingDelete = null },
            title = { Text(s.cmdDeleteSingle) },
            text = { Text(s.cmdDeleteSingleConfirm) },
            confirmButton = {
                Button(
                    onClick = {
                        val updated = commandList.filterNot { it.id == item.id }
                        Prefs.saveCommands(updated)
                        refreshData()
                        itemPendingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(s.confirm)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { itemPendingDelete = null }) {
                    Text(s.cancel)
                }
            }
        )
    }

    // 弹窗 5：批量删除二次确认
    if (showBatchDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = { Text(s.cmdDeleteBatch) },
            text = { Text(String.format(s.cmdDeleteBatchConfirm, selectedIds.size)) },
            confirmButton = {
                Button(
                    onClick = {
                        val remaining = commandList.filterNot { selectedIds.contains(it.id) }
                        Prefs.saveCommands(remaining)
                        selectedIds.clear()
                        isManageMode = false
                        refreshData()
                        showBatchDeleteConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(s.confirm)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showBatchDeleteConfirm = false }) {
                    Text(s.cancel)
                }
            }
        )
    }

    // 弹窗 6：批量移动到分类
    if (showBatchMoveDialog) {
        var targetCat by remember { mutableStateOf("custom") }

        AlertDialog(
            onDismissRequest = { showBatchMoveDialog = false },
            title = { Text(s.cmdMoveToCategory) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("选择目标分类：", style = MaterialTheme.typography.bodyMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val selectables = listOf("framework", "system", "power", "fastboot", "custom") + customCategories
                        items(selectables.distinct()) { cat ->
                            FilterChip(
                                selected = targetCat == cat,
                                onClick = { targetCat = cat },
                                label = { Text(getCategoryDisplayName(cat)) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val updated = commandList.map {
                            if (selectedIds.contains(it.id)) it.copy(category = targetCat) else it
                        }
                        Prefs.saveCommands(updated)
                        selectedIds.clear()
                        isManageMode = false
                        refreshData()
                        showBatchMoveDialog = false
                    }
                ) {
                    Text(s.confirm)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showBatchMoveDialog = false }) {
                    Text(s.cancel)
                }
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

                    // 2. 智能动态扫描：设备端一次脚本汇总声明了特权权限的第三方应用
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
                    // 未连接时扫描本机已安装第三方应用（仅本机）
                    try {
                        val pm = context.packageManager
                        val installed = pm.getInstalledPackages(android.content.pm.PackageManager.GET_PERMISSIONS)
                        installed.forEach { pi ->
                            val pkg = pi.packageName
                            pkgSet.add(pkg)
                            val matchedKnown = KNOWN_ADB_APPS.firstOrNull { it.packageName == pkg }
                            if (matchedKnown != null) {
                                if (!dynamicList.any { it.packageName == pkg }) dynamicList.add(matchedKnown)
                            } else if (((pi.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0) {
                                val requested = pi.requestedPermissions?.toList() ?: emptyList()
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

                detectedPackages = pkgSet
                // 始终展示：已安装已知框架 + 动态识别应用；若都没有再回落完整已知名单供参考
                allCandidateApps = if (dynamicList.isNotEmpty()) {
                    dynamicList.distinctBy { it.packageName }
                } else {
                    KNOWN_ADB_APPS
                }
                isScanning = false

                // 默认只预选「已安装」项，避免误点未安装已知 App
                selectedAppIds.clear()
                selectedAppIds.addAll(
                    allCandidateApps.filter { detectedPackages.contains(it.packageName) }.map { it.id }
                )
                if (selectedAppIds.isEmpty()) {
                    selectedAppIds.addAll(allCandidateApps.map { it.id })
                }
            }
        }

        val filteredApps = remember(authSearchQuery, allCandidateApps) {
            allCandidateApps.filter { app ->
                val q = authSearchQuery.trim()
                if (q.isBlank()) true
                else app.nameZh.contains(q, ignoreCase = true) ||
                        app.nameEn.contains(q, ignoreCase = true) ||
                        app.packageName.contains(q, ignoreCase = true)
            }
        }

        AlertDialog(
            onDismissRequest = { if (!isGranting) showAdbAuthDialog = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(s.adbAuthTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (isScanning) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                ) {
                    Text(
                        text = s.adbAuthDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = authSearchQuery,
                            onValueChange = { authSearchQuery = it },
                            placeholder = { Text(s.adbAuthSearchHint, fontSize = 12.sp) },
                            modifier = Modifier.weight(1f).height(48.dp),
                            singleLine = true,
                            trailingIcon = {
                                if (authSearchQuery.isNotBlank()) {
                                    IconButton(onClick = { authSearchQuery = "" }, modifier = Modifier.size(20.dp)) {
                                        Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            onClick = {
                                if (selectedAppIds.size == filteredApps.size) {
                                    selectedAppIds.clear()
                                } else {
                                    selectedAppIds.clear()
                                    selectedAppIds.addAll(filteredApps.map { it.id })
                                }
                            }
                        ) {
                            Text(if (selectedAppIds.size == filteredApps.size) s.adbAuthDeselectAll else s.adbAuthSelectAll, fontSize = 12.sp)
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    if (filteredApps.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(s.adbAuthNoApps, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(filteredApps, key = { it.id }) { app ->
                                val isSelected = selectedAppIds.contains(app.id)
                                val isDetected = detectedPackages.contains(app.packageName)
                                val iconBmp = remember(app.packageName) { loadAppIconBitmap(context, app.packageName) }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                        )
                                        .border(
                                            width = if (isSelected) 2.dp else 1.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        .clickable {
                                            if (isSelected) selectedAppIds.remove(app.id)
                                            else selectedAppIds.add(app.id)
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (iconBmp != null) {
                                        Image(
                                            bitmap = iconBmp,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(38.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(38.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                Icons.Filled.Android,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }

                                    Spacer(Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = if (lang == "zh") app.nameZh else app.nameEn,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                            if (isDetected) {
                                                Spacer(Modifier.width(6.dp))
                                                Surface(
                                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        text = "已安装",
                                                        fontSize = 10.sp,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = "${app.packageName} · ${if (lang == "zh") app.typeDescZh else app.typeDescEn}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Icon(
                                        imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                        contentDescription = null,
                                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        // 必须按当前列表勾选执行：包含动态识别应用（dyn_*），不能只跑 KNOWN_ADB_APPS
                        val chosen = allCandidateApps.filter { selectedAppIds.contains(it.id) }
                        if (chosen.isEmpty()) return@Button
                        coroutineScope.launch {
                            isGranting = true
                            val sb = StringBuilder()
                            withContext(Dispatchers.IO) {
                                chosen.forEach { app ->
                                    val name = if (lang == "zh") app.nameZh else app.nameEn
                                    sb.append("=== 【$name】===\n")
                                    if (app.isDynamicDetected) {
                                        sb.append("(动态识别应用)\n")
                                    }
                                    app.commands.forEach { cmd ->
                                        sb.append("$ $cmd\n")
                                        val out = if (AdbManager.connected.value && AdbManager.connection?.isAuthenticated == true) {
                                            AdbManager.connection?.shell(cmd) ?: "(执行完成/无返回)"
                                        } else {
                                            "(设备未连接，请先在首页连接设备后执行)"
                                        }
                                        sb.append(out.trim()).append("\n\n")
                                    }
                                }
                            }
                            isGranting = false
                            resultDialogTitle = s.adbAuthResultTitle
                            resultDialogCommand = "一键授权/激活 (${chosen.size} 项)"
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

    // 弹窗 7：指令执行结果弹窗
    if (showResultDialog) {
        AlertDialog(
            onDismissRequest = { showResultDialog = false },
            title = {
                Column {
                    Text(resultDialogTitle, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "$ $resultDialogCommand",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp, max = 320.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(androidx.compose.ui.graphics.Color(0xFF0F172A))
                        .padding(10.dp)
                ) {
                    if (isRunningCommand) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "正在执行命令...",
                                style = MaterialTheme.typography.bodySmall,
                                color = androidx.compose.ui.graphics.Color(0xFF94A3B8)
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            item {
                                Text(
                                    text = resultDialogOutput ?: s.logNoOutput,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 13.sp,
                                        lineHeight = 17.sp
                                    ),
                                    fontFamily = FontFamily.Monospace,
                                    color = androidx.compose.ui.graphics.Color(0xFFF1F5F9)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showResultDialog = false }) {
                    Text(s.cmdDone)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        val text = resultDialogOutput
                        if (!text.isNullOrBlank()) {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Command Output", text))
                            AdbManager.log(s.copyLog + " ✓")
                        }
                    },
                    enabled = !resultDialogOutput.isNullOrBlank()
                ) {
                    Text(s.copyLog)
                }
            }
        )
    }
}

/** 从 Content Uri 解析文件名辅助函数 */
private fun getFileNameFromUri(context: Context, uri: Uri): String {
    var name = ""
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx != -1) name = it.getString(idx)
            }
        }
    }
    if (name.isBlank()) {
        name = uri.path?.substringAfterLast('/') ?: "file_${System.currentTimeMillis()}"
    }
    return name
}
