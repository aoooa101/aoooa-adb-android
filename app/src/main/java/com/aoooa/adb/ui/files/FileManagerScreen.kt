package com.aoooa.adb.ui.files

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aoooa.adb.AdbManager
import com.aoooa.adb.files.RemoteEntry
import com.aoooa.adb.files.RemoteFiles
import com.aoooa.adb.adb.shell
import com.aoooa.adb.ui.CommandResultDialog
import com.aoooa.adb.ui.i18n.Strings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** 文件大小人类可读格式化 */
fun formatFileSize(bytes: Long): String {
    if (bytes < 0) return "--"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}

/** 扩展名识别集合 */
private val textExtensions = setOf(
    "txt", "log", "sh", "json", "xml", "md", "prop", "properties",
    "conf", "ini", "yaml", "yml", "rc", "cfg", "py", "c", "cpp", "h", "java", "kt"
)
private val imageExtensions = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
private val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "3gp", "webm")
private val audioExtensions = setOf("mp3", "flac", "wav", "m4a", "ogg", "aac")
private val archiveExtensions = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")

/** 原生 Material 矢量图标与分类语义色彩映射 */
private fun getFileVisuals(entry: RemoteEntry): Pair<ImageVector, Color> {
    if (entry.isDirectory) {
        return Icons.Filled.Folder to Color(0xFF60A5FA)
    }
    val ext = entry.name.substringAfterLast('.', "").lowercase()
    return when {
        ext == "apk" -> Icons.Filled.Android to Color(0xFF4ADE80)
        ext == "sh" -> Icons.Filled.Terminal to Color(0xFFC084FC)
        ext in imageExtensions -> Icons.Filled.Image to Color(0xFF38BDF8)
        ext in videoExtensions -> Icons.Filled.Movie to Color(0xFF818CF8)
        ext in audioExtensions -> Icons.Filled.MusicNote to Color(0xFFFBBF24)
        ext in archiveExtensions -> Icons.Filled.Archive to Color(0xFFFB923C)
        ext in textExtensions -> Icons.Filled.Description to Color(0xFF94A3B8)
        else -> Icons.Filled.InsertDriveFile to Color(0xFF94A3B8)
    }
}

/** 文件管理界面：支持目录浏览、搜索、文本与图片预览、脚本执行、属性与传输。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileManagerScreen(
    s: Strings,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connected by AdbManager.connected
    val fastboot by AdbManager.isFastbootMode
    val connection = AdbManager.connection
    val files = remember(connection) { connection?.let { RemoteFiles(it) } }

    var path by rememberSaveable { mutableStateOf("/sdcard") }
    var pathInput by rememberSaveable { mutableStateOf("/sdcard") }
    var entries by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    var query by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    var transferActive by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<RemoteEntry?>(null) }
    var editEntry by remember { mutableStateOf<RemoteEntry?>(null) }
    var editing by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var deleteEntry by remember { mutableStateOf<RemoteEntry?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var pendingDownload by remember { mutableStateOf<RemoteEntry?>(null) }
    var installApkEntry by remember { mutableStateOf<RemoteEntry?>(null) }
    var runScriptEntry by remember { mutableStateOf<RemoteEntry?>(null) }

    // 复用快捷指令的标准命令执行结果弹窗状态
    var scriptResultTitle by remember { mutableStateOf("") }
    var scriptResultCommand by remember { mutableStateOf("") }
    var scriptResultOutput by remember { mutableStateOf<String?>(null) }
    var isRunningScript by remember { mutableStateOf(false) }
    var showScriptResultDialog by remember { mutableStateOf(false) }

    // 预览弹窗状态
    var previewTextData by remember { mutableStateOf<Pair<String, String>?>(null) } // fileName to content
    var previewImageData by remember { mutableStateOf<Pair<String, Bitmap>?>(null) } // fileName to bitmap
    var isPreviewLoading by remember { mutableStateOf(false) }

    var uploadDirectory by rememberSaveable { mutableStateOf("/sdcard") }
    var clipboard by remember { mutableStateOf<Pair<RemoteEntry, Boolean>?>(null) }
    var pasteConfirm by remember { mutableStateOf(false) }
    val available = connected && !fastboot && files != null

    fun report(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    fun copyToClipboard(text: String, hint: String = s.fileContentCopied) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("aoooa-adb", text))
        report(hint)
    }

    fun runOperation(label: String, transfer: Boolean = false, block: suspend () -> Unit) {
        if (busy || !available) return
        busy = true
        status = label
        progress = 0f
        transferActive = transfer
        error = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: s.fileOpFailed
            } finally {
                busy = false
                transferActive = false
            }
        }
    }

    suspend fun reload(target: String) {
        val normalized = RemoteFiles.normalize(target)
        val listing = withContext(Dispatchers.IO) { requireNotNull(files).list(normalized) }
        path = normalized
        pathInput = normalized
        entries = listing
    }

    fun navigate(target: String) = runOperation(s.fileParentDir) { reload(target) }

    // 点击项时的逻辑：文件夹直接进入，文本/图片就地预览，其他弹长按菜单
    fun onItemClick(entry: RemoteEntry) {
        if (entry.isDirectory) {
            navigate(entry.path)
            return
        }
        val ext = entry.name.substringAfterLast('.', "").lowercase()
        when {
            ext in textExtensions -> {
                isPreviewLoading = true
                scope.launch {
                    try {
                        val content = withContext(Dispatchers.IO) {
                            requireNotNull(files).readText(entry.path)
                        }
                        previewTextData = entry.name to content
                    } catch (e: Exception) {
                        report(e.message ?: s.fileOpFailed)
                    } finally {
                        isPreviewLoading = false
                    }
                }
            }
            ext in imageExtensions -> {
                isPreviewLoading = true
                scope.launch {
                    try {
                        val bytes = withContext(Dispatchers.IO) {
                            requireNotNull(files).readBytes(entry.path)
                        }
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) {
                            previewImageData = entry.name to bmp
                        } else {
                            report(s.fileOpFailed)
                        }
                    } catch (e: Exception) {
                        report(e.message ?: s.fileOpFailed)
                    } finally {
                        isPreviewLoading = false
                    }
                }
            }
            else -> {
                selected = entry
            }
        }
    }

    LaunchedEffect(connected, fastboot, connection) {
        entries = emptyList()
        clipboard = null
        if (available) navigate(path)
    }
    BackHandler(enabled = busy) { report(s.fileWaitingOp) }
    BackHandler(enabled = available && !busy && path != "/") { navigate(RemoteFiles.parent(path)) }

    val onProgress: (Float) -> Unit = { value -> scope.launch { progress = value } }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val entry = pendingDownload
        pendingDownload = null
        if (uri != null && entry != null) {
            if (!available || busy) {
                scope.launch(Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) } }
                report(s.fileDeviceUnavailable)
            } else runOperation(s.fileActionDownload, true) {
                withContext(Dispatchers.IO) {
                    try {
                        val output = context.contentResolver.openOutputStream(uri, "w") ?: error("Cannot open destination")
                        output.use { requireNotNull(files).client.download(entry.path, it, entry.size, onProgress) }
                    } catch (e: Exception) {
                        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                        throw e
                    }
                }
                report(String.format(s.fileDownloadedSuccess, entry.name))
            }
        }
    }
    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null && available && !busy) runOperation(s.fileUpload, true) {
            withContext(Dispatchers.IO) {
                var fileName: String? = null
                var size = -1L
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val n = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val z = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (n >= 0 && !cursor.isNull(n)) fileName = cursor.getString(n)
                        if (z >= 0 && !cursor.isNull(z)) size = cursor.getLong(z)
                    }
                }
                val chosenName = fileName ?: error("无法读取文件名 / Missing file name")
                val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open source")
                input.use { requireNotNull(files).upload(uploadDirectory, chosenName, it, size, onProgress) }
            }
            reload(path)
            report(s.fileUploadSuccess)
        }
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = pathInput,
                    onValueChange = { pathInput = it },
                    label = { Text(s.fileDevicePath) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    enabled = !busy
                )
                IconButton(onClick = { navigate(pathInput) }, enabled = available && !busy) {
                    Icon(Icons.Filled.ArrowForward, s.fileGo)
                }
                IconButton(onClick = { navigate(path) }, enabled = available && !busy) {
                    Icon(Icons.Filled.Refresh, s.fileRefresh)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { navigate(RemoteFiles.parent(path)) }, enabled = available && !busy && path != "/") {
                    Icon(Icons.Filled.ArrowUpward, null)
                    Spacer(Modifier.width(4.dp))
                    Text(s.fileParentDir)
                }
                Text(String.format(s.fileItemCount, entries.size), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { navigate("/sdcard") }, enabled = available && !busy) {
                    Text(s.fileStorage)
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text(s.fileSearchHint) },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                modifier = Modifier.fillMaxWidth()
            )
            clipboard?.let { (entry, move) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = (if (move) s.fileMovePrefix else s.fileCopyPrefix) + entry.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { pasteConfirm = true }, enabled = available && !busy) {
                        Text(s.filePasteHere)
                    }
                    IconButton(onClick = { clipboard = null }, enabled = !busy) {
                        Icon(Icons.Filled.Close, s.cancel)
                    }
                }
            }
            if (busy || isPreviewLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (status.isNotBlank()) {
                    Text(status, style = MaterialTheme.typography.bodySmall)
                }
            }
            error?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
            }
            if (!available) {
                Text(s.fileConnectDeviceHint, modifier = Modifier.padding(16.dp))
            } else {
                val visible = entries.filter { it.name.contains(query, ignoreCase = true) }
                if (!busy && visible.isEmpty() && error == null) {
                    Text(s.fileEmpty, modifier = Modifier.padding(16.dp))
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 90.dp)) {
                    items(visible, key = { it.path }) { entry ->
                        val (icon, tint) = getFileVisuals(entry)
                        ListItem(
                            headlineContent = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    if (entry.isDirectory) s.fileFolder
                                    else "${formatFileSize(entry.size)}  ·  ${DateFormat.getDateTimeInstance().format(Date(entry.modified * 1000))}"
                                )
                            },
                            leadingContent = {
                                Icon(icon, contentDescription = null, tint = tint)
                            },
                            trailingContent = {
                                IconButton(onClick = { selected = entry }, enabled = !busy) {
                                    Icon(Icons.Filled.MoreVert, s.fileMore)
                                }
                            },
                            modifier = Modifier.combinedClickable(
                                enabled = !busy,
                                onClick = { onItemClick(entry) },
                                onLongClick = { selected = entry }
                            )
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            FloatingActionButton(onClick = { if (available && !busy) addMenu = true }) {
                Icon(Icons.Filled.Add, s.fileAdd)
            }
            DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                DropdownMenuItem(
                    text = { Text(s.fileUpload) },
                    onClick = {
                        addMenu = false
                        uploadDirectory = path
                        uploadLauncher.launch(arrayOf("*/*"))
                    }
                )
                DropdownMenuItem(
                    text = { Text(s.fileNewFolder) },
                    onClick = {
                        addMenu = false
                        editEntry = null
                        name = ""
                        editing = true
                    }
                )
            }
        }
    }

    selected?.let { entry ->
        val ext = entry.name.substringAfterLast('.', "").lowercase()
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(entry.name) },
            text = {
                Column {
                    // APK 快捷安装入口
                    if (entry.isFile && ext == "apk") {
                        TextButton(onClick = {
                            selected = null
                            installApkEntry = entry
                        }) {
                            Icon(Icons.Filled.Android, null, modifier = Modifier.size(18.dp), tint = Color(0xFF4ADE80))
                            Spacer(Modifier.width(6.dp))
                            Text(s.fileActionInstall, fontWeight = FontWeight.Bold, color = Color(0xFF4ADE80))
                        }
                    }
                    // SH 脚本免可执行权限直接运行入口
                    if (entry.isFile && ext == "sh") {
                        TextButton(onClick = {
                            selected = null
                            runScriptEntry = entry
                        }) {
                            Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp), tint = Color(0xFFC084FC))
                            Spacer(Modifier.width(6.dp))
                            Text(s.fileActionRunScript, fontWeight = FontWeight.Bold, color = Color(0xFFC084FC))
                        }
                    }
                    if (entry.isFile) {
                        TextButton(onClick = {
                            selected = null
                            pendingDownload = entry
                            saveLauncher.launch(entry.name)
                        }) {
                            Text(s.fileActionDownload)
                        }
                    }
                    TextButton(onClick = {
                        selected = null
                        editEntry = entry
                        name = entry.name
                        editing = true
                    }) {
                        Text(s.fileActionRename)
                    }
                    TextButton(onClick = {
                        selected = null
                        clipboard = entry to false
                    }) {
                        Text(s.fileActionCopy)
                    }
                    TextButton(onClick = {
                        selected = null
                        clipboard = entry to true
                    }) {
                        Text(s.fileActionMove)
                    }
                    TextButton(onClick = {
                        selected = null
                        runOperation(s.fileActionProperties) {
                            info = withContext(Dispatchers.IO) { requireNotNull(files).properties(entry) }
                        }
                    }) {
                        Text(s.fileActionProperties)
                    }
                    TextButton(onClick = {
                        selected = null
                        deleteEntry = entry
                    }) {
                        Text(s.fileActionDelete, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selected = null }) { Text(s.close) }
            }
        )
    }

    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(if (editEntry == null) s.fileNewFolder else s.fileActionRename) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(s.fileNameLabel) }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && !busy,
                    onClick = {
                        val entry = editEntry
                        val newName = name
                        editing = false
                        runOperation(s.fileSaving) {
                            withContext(Dispatchers.IO) {
                                if (entry == null) requireNotNull(files).mkdir(path, newName)
                                else requireNotNull(files).rename(entry, newName)
                            }
                            reload(path)
                        }
                    }
                ) {
                    Text(s.confirm)
                }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text(s.cancel) }
            }
        )
    }

    deleteEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteEntry = null },
            title = { Text(s.fileDeleteConfirmTitle) },
            text = {
                Text(entry.path + "\n\n" + if (entry.isDirectory) s.fileDeleteDirMsg else s.fileDeleteFileMsg)
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteEntry = null
                    runOperation(s.fileActionDelete) {
                        withContext(Dispatchers.IO) { requireNotNull(files).delete(entry) }
                        reload(path)
                    }
                }) {
                    Text(s.fileActionDelete, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteEntry = null }) { Text(s.cancel) }
            }
        )
    }

    // 安装未知应用风险提示弹窗
    installApkEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { installApkEntry = null },
            title = { Text(s.fileInstallRiskTitle) },
            text = {
                Text(
                    text = "${entry.name}\n${entry.path}\n\n${s.fileInstallRiskMsg}",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        installApkEntry = null
                        Thread {
                            try {
                                AdbManager.connection?.shell("pm install -r ${RemoteFiles.quote(entry.path)}")
                            } catch (_: Exception) {}
                        }.start()
                        report(s.fileInstallLaunched)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(s.fileInstallConfirmBtn)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { installApkEntry = null }) {
                    Text(s.cancel)
                }
            }
        )
    }

    // 运行脚本安全风险提示弹窗
    runScriptEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { runScriptEntry = null },
            title = { Text(s.fileRunScriptRiskTitle) },
            text = {
                Text(
                    text = "${entry.name}\n${entry.path}\n\n${s.fileRunScriptRiskMsg}",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val targetPath = entry.path
                        val targetName = entry.name
                        val cmd = "sh ${RemoteFiles.quote(targetPath)}"
                        runScriptEntry = null
                        scriptResultTitle = targetName
                        scriptResultCommand = cmd
                        scriptResultOutput = null
                        isRunningScript = true
                        showScriptResultDialog = true

                        // 复用 AdbManager.execCapture 标准命令执行端口
                        Thread {
                            val output = if (connected) {
                                AdbManager.execCapture(cmd).ifBlank { s.logNoOutput }
                            } else {
                                s.terminalNotConnected
                            }
                            scope.launch(Dispatchers.Main) {
                                scriptResultOutput = output
                                isRunningScript = false
                            }
                        }.start()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(s.fileRunScriptConfirmBtn)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { runScriptEntry = null }) {
                    Text(s.cancel)
                }
            }
        )
    }

    // 复用快捷指令命令结果展示视窗
    if (showScriptResultDialog) {
        CommandResultDialog(
            title = scriptResultTitle,
            command = scriptResultCommand,
            output = scriptResultOutput,
            isLoading = isRunningScript,
            s = s,
            onDismiss = { showScriptResultDialog = false }
        )
    }

    // 文本预览弹窗
    previewTextData?.let { (fileName, textContent) ->
        AlertDialog(
            onDismissRequest = { previewTextData = null },
            title = { Text(fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF0F172A),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                ) {
                    Text(
                        text = textContent.ifBlank { "(空文件 / Empty file)" },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = Color(0xFFF8FAFC),
                        modifier = Modifier
                            .padding(10.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            },
            confirmButton = {
                Button(onClick = { copyToClipboard(textContent) }) {
                    Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(s.fileCopyContent)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { previewTextData = null }) {
                    Text(s.close)
                }
            }
        )
    }

    // 图片预览弹窗
    previewImageData?.let { (fileName, bitmap) ->
        AlertDialog(
            onDismissRequest = { previewImageData = null },
            title = { Text(fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = fileName,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = { previewImageData = null }) {
                    Text(s.close)
                }
            }
        )
    }

    if (pasteConfirm) {
        clipboard?.let { (entry, move) ->
            AlertDialog(
                onDismissRequest = { pasteConfirm = false },
                title = { Text(if (move) s.fileActionMove else s.fileActionCopy) },
                text = {
                    Text("${entry.path}\n→ $path\n\n" + s.filePasteConfirmMsg)
                },
                confirmButton = {
                    TextButton(onClick = {
                        pasteConfirm = false
                        runOperation(if (move) s.fileActionMove else s.fileActionCopy) {
                            withContext(Dispatchers.IO) { requireNotNull(files).transfer(entry, path, move) }
                            clipboard = null
                            reload(path)
                        }
                    }) {
                        Text(s.confirm)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pasteConfirm = false }) { Text(s.cancel) }
                }
            )
        }
    }

    // 属性查看弹窗（带格式化大小与原始精确字节数对比）
    info?.let { details ->
        val target = selected
        AlertDialog(
            onDismissRequest = { info = null },
            title = { Text(s.fileActionProperties) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (target != null && target.isFile) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                        ) {
                            Text(
                                text = "大小：${formatFileSize(target.size)} (${String.format(Locale.US, "%,d", target.size)} 字节)",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                    Text(
                        text = details,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { info = null }) { Text(s.close) }
            }
        )
    }

    if (transferActive) {
        Dialog(onDismissRequest = {}) {
            Surface(shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(status)
                    LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                    Text("${(progress * 100).toInt()}%")
                    Text(s.fileTransferKeepConnected, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
