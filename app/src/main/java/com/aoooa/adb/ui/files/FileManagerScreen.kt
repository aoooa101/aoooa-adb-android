package com.aoooa.adb.ui.files

import android.net.Uri
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.aoooa.adb.AdbManager
import com.aoooa.adb.files.RemoteEntry
import com.aoooa.adb.files.RemoteFiles
import com.aoooa.adb.ui.i18n.Strings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** 文件管理界面：支持目录浏览、搜索、属性、长按操作与传输。 */
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
    var uploadDirectory by rememberSaveable { mutableStateOf("/sdcard") }
    var clipboard by remember { mutableStateOf<Pair<RemoteEntry, Boolean>?>(null) }
    var pasteConfirm by remember { mutableStateOf(false) }
    val available = connected && !fastboot && files != null

    fun report(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
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
                    Icon(Icons.Filled.Refresh, s.refresh)
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
            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(status, style = MaterialTheme.typography.bodySmall)
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
                        ListItem(
                            headlineContent = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(if (entry.isDirectory) s.fileFolder else "${entry.size} B  ·  ${DateFormat.getDateTimeInstance().format(Date(entry.modified * 1000))}")
                            },
                            leadingContent = { Icon(if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile, null) },
                            trailingContent = {
                                IconButton(onClick = { selected = entry }, enabled = !busy) {
                                    Icon(Icons.Filled.MoreVert, s.more)
                                }
                            },
                            modifier = Modifier.combinedClickable(
                                enabled = !busy,
                                onClick = { if (entry.isDirectory) navigate(entry.path) else selected = entry },
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
                Icon(Icons.Filled.Add, s.add)
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
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(entry.name) },
            text = {
                Column {
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
                        runOperation(s.save) {
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

    info?.let { details ->
        AlertDialog(
            onDismissRequest = { info = null },
            title = { Text(s.fileActionProperties) },
            text = {
                Text(details, fontFamily = FontFamily.Monospace, modifier = Modifier.verticalScroll(rememberScrollState()))
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
