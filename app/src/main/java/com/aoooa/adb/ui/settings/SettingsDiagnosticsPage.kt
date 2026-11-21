package com.aoooa.adb.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aoooa.adb.log.DiagnosticLogExporter
import com.aoooa.adb.log.DiagnosticLogStore
import com.aoooa.adb.ui.i18n.DiagnosticsStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val FEEDBACK = "https://github.com/aoooa101/aoooa-adb-android/issues/new/choose"

@Composable
internal fun SettingsDiagnosticsPage(lang: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val d = remember(lang) { DiagnosticsStrings(lang == "zh") }
    val store = remember(context) { DiagnosticLogStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var listing by rememberSaveable { mutableStateOf(false) }
    var pending by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    fun message(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    fun save(uri: Uri?, archive: Boolean) {
        val paths = pending.toList(); pending = arrayListOf()
        if (uri == null || paths.isEmpty()) return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val files = paths.map(store::resolve)
                    requireNotNull(context.contentResolver.openOutputStream(uri)).use { DiagnosticLogExporter.write(files, it, archive) }
                }
                message(d.saved)
            } catch (e: java.io.IOException) { message("${d.failed}: ${e.localizedMessage}") }
              catch (e: RuntimeException) { if (e is kotlinx.coroutines.CancellationException) throw e; message("${d.failed}: ${e.localizedMessage}") }
        }
    }
    val zip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { save(it, true) }
    val single = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { save(it, false) }
    fun export(files: List<File>, archive: Boolean) {
        if (files.isEmpty()) { message(d.empty); return }
        pending = ArrayList(files.map { it.absolutePath })
        try { if (archive) zip.launch("aoooa-adb_${System.currentTimeMillis() / 1000L}.zip") else single.launch(files.single().name) }
        catch (e: android.content.ActivityNotFoundException) { pending = arrayListOf(); message(d.failed) }
    }
    BackHandler(enabled = listing) { listing = false }
    if (listing) { SettingsLogListPage(store, d, onBack = { listing = false }, onExport = ::export); return }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text(d.back) }; Text(d.title, style = MaterialTheme.typography.headlineSmall)
        DiagnosticCard(d.all) { scope.launch { export(withContext(Dispatchers.IO) { store.list() }, true) } }
        DiagnosticCard(d.list) { listing = true }
        DiagnosticCard(d.feedback) {
            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(FEEDBACK))) }
            catch (_: android.content.ActivityNotFoundException) {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(d.feedback, FEEDBACK)); message(d.linkCopied)
            }
        }
    }
}

@Composable
private fun DiagnosticCard(title: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(title, Modifier.padding(20.dp), style = MaterialTheme.typography.titleMedium) }
}
