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
import com.aoooa.adb.files.RemoteFiles
import com.aoooa.adb.adb.installStream
import com.aoooa.adb.adb.shell
import com.aoooa.adb.ui.i18n.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 列表单项卡片：最左边图标，右侧上方名称（加粗），下方包名，无版本号
 */
@Composable
internal fun AppListItem(
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
internal fun AppIconView(
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

internal fun drawableToBitmap(drawable: Drawable): Bitmap {
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
internal fun loadLocalInstalledApps(context: Context): List<LocalAppItem> {
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
internal fun loadApps(
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

internal fun queryFileName(context: Context, uri: Uri): String? {
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
