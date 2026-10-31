package com.aoooa.adb.ui

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
import com.aoooa.adb.AdbManager
import com.aoooa.adb.Prefs
import com.aoooa.adb.model.CommandItem
import com.aoooa.adb.adb.shell
import com.aoooa.adb.ui.i18n.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 为动态应用生成可执行授权命令：pm grant + 必要 appops */
internal fun buildDynamicGrantCommands(pkg: String, perms: List<String>): List<String> {
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
 * 扫描设备中声明了特权权限的第三方应用。
 * 输出格式：pkg<TAB>perm1,perm2
 */
internal fun scanPrivilegedAppsViaAdbSmart(): Map<String, List<String>> {
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

internal fun loadAppIconBitmap(context: Context, packageName: String): ImageBitmap? {
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

/** 从 Content Uri 解析文件名辅助函数 */
internal fun getFileNameFromUri(context: Context, uri: Uri): String {
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
