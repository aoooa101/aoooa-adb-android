package com.aoooa.adb.ui.tools

import com.aoooa.adb.ui.ADB_PRIVILEGED_PERMISSIONS
import com.aoooa.adb.ui.AdbPrivilegedApp
import com.aoooa.adb.ui.KNOWN_ADB_APPS
import com.aoooa.adb.ui.buildDynamicGrantCommands
import com.aoooa.adb.ui.loadAppIconBitmap
import com.aoooa.adb.ui.scanPrivilegedAppsViaAdbSmart

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aoooa.adb.AdbManager
import com.aoooa.adb.adb.pullFile
import com.aoooa.adb.adb.shell
import com.aoooa.adb.ui.i18n.I18n
import com.aoooa.adb.ui.i18n.Strings
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdbAuthorizationDialog(s: Strings, lang: String, connected: Boolean, onDismiss: () -> Unit, onResult: (String, String) -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isScanning by remember { mutableStateOf(true) }
    var isGranting by remember { mutableStateOf(false) }
    var detectedPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var allCandidateApps by remember { mutableStateOf<List<AdbPrivilegedApp>>(KNOWN_ADB_APPS) }
    val selectedAppIds = remember { mutableStateListOf<String>() }
    var authSearchQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        isScanning = true
        val candidates = withContext(Dispatchers.IO) { scanAuthorizationCandidates(context) }
        detectedPackages = candidates.packages
        allCandidateApps = candidates.apps
        selectedAppIds.clear()
        selectedAppIds.addAll(candidates.apps.filter { it.packageName in candidates.packages }.map { it.id })
        isScanning = false
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

    val visibleIds = remember(visibleApps) { visibleApps.map { it.id } }
    val visibleCount = VisibleSelection.count(visibleIds, selectedAppIds)

    AlertDialog(
        onDismissRequest = { if (!isGranting) onDismiss() },
        title = {
            Column {
                Text(s.adbAuthTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = s.adbAuthDesc,
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
                        text = String.format(s.adbAuthFoundCount, visibleApps.size, visibleCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    val isAllSelected = visibleIds.isNotEmpty() && visibleCount == visibleIds.size
                    TextButton(
                        onClick = {
                            val updated = VisibleSelection.toggle(visibleIds, selectedAppIds)
                            selectedAppIds.clear()
                            selectedAppIds.addAll(updated)
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(if (isAllSelected) s.adbAuthDeselectAll else s.adbAuthSelectAll, fontSize = 11.sp)
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
                        Text(s.adbAuthNoApps, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    val chosen = visibleApps.filter { selectedAppIds.contains(it.id) }
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
                        onResult("批量授权/激活 (${chosen.size} 项)", sb.toString().trim())
                        onDismiss()
                    }
                },
                enabled = visibleCount > 0 && !isGranting
            ) {
                if (isGranting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(String.format(s.adbAuthGrantBtn, visibleCount))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = { onDismiss() }, enabled = !isGranting) {
                Text(s.cancel)
            }
        }
    )
}
