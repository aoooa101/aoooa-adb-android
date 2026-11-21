package com.aoooa.adb.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aoooa.adb.AdbManager
import com.aoooa.adb.log.DiagnosticLogStore
import com.aoooa.adb.ui.i18n.DiagnosticsStrings
import com.aoooa.adb.ui.preview.TextFilePreview
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SettingsLogListPage(store: DiagnosticLogStore, d: DiagnosticsStrings, onBack: () -> Unit, onExport: (List<File>, Boolean) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf(emptyList<File>()) }
    var revision by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<File>()) }
    var preview by remember { mutableStateOf<Triple<File, String, Boolean>?>(null) }
    fun exitEdit() { editing = false; selected = emptySet() }
    BackHandler(enabled = editing) { exitEdit() }
    LaunchedEffect(revision) { files = withContext(Dispatchers.IO) { store.list() } }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (editing) exitEdit() else onBack() }) { Text(if (editing) d.cancel else d.back) }
            Text(d.list, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Box { IconButton(onClick = { menu = true }) { Icon(Icons.Filled.Menu, d.edit) }; DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) { DropdownMenuItem(text = { Text(d.edit) }, onClick = { editing = true; menu = false }) } }
        }
        if (editing) Row {
            TextButton(onClick = { selected = if (selected.size == files.size) emptySet() else files.toSet() }) { Text(d.selectAll) }
            TextButton(enabled = selected.isNotEmpty(), onClick = { onExport(files.filter { it in selected }, true) }) { Text(d.export) }
            TextButton(enabled = selected.isNotEmpty(), onClick = { confirm = true }) { Text(d.delete) }
        }
        if (files.isEmpty()) Text(d.empty)
        LazyColumn { items(files, key = { it.absolutePath }) { f ->
            Row(Modifier.fillMaxWidth().clickable {
                if (editing) selected = if (f in selected) selected - f else selected + f
                else scope.launch {
                    try { val result = withContext(Dispatchers.IO) { store.preview(f.absolutePath) }; preview = Triple(f, result.first, result.second) }
                    catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; Toast.makeText(ctx, d.failed, Toast.LENGTH_SHORT).show(); revision++ }
                }
            }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (editing) Checkbox(checked = f in selected, onCheckedChange = null)
                Column { Text(f.name); Text(listOfNotNull(AdbManager.formatFileSize(f.length()), d.active.takeIf { f == AdbManager.getLogFile() }).joinToString(" · "), style = MaterialTheme.typography.bodySmall) }
            }
        } }
    }
    if (confirm) {
        val skip = selected.count { it == AdbManager.getLogFile() }
        AlertDialog(onDismissRequest = { confirm = false }, text = { Text(d.confirm(selected.size - skip, skip)) }, confirmButton = { TextButton(onClick = {
            confirm = false; val targets = selected.toList(); exitEdit()
            scope.launch { val n = withContext(Dispatchers.IO) { targets.count { f -> runCatching { AdbManager.deleteHistoricalLog(store.resolve(f.absolutePath)) }.getOrDefault(false) } }; Toast.makeText(ctx, d.removed(n, targets.size - n), Toast.LENGTH_SHORT).show(); revision++ }
        }) { Text(d.delete) } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text(d.cancel) } })
    }
    preview?.let { (file, text, partial) -> TextFilePreview(file.name, text, d.copy, d.cancel, onCopy = { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(file.name, text)) }, onClose = { preview = null }, exportLabel = d.export, onExport = { onExport(listOf(file), false) }, notice = if (partial) d.truncated else null) }
}
