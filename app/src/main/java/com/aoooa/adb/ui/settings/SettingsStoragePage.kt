package com.aoooa.adb.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aoooa.adb.AdbManager
import com.aoooa.adb.ui.SettingsSubPage
import com.aoooa.adb.ui.i18n.Strings
import java.io.File

/**
 * 存储管理二级视图：统计应用存储占用、清理临时缓存、运行日志及清除应用数据
 */
@Composable
internal fun SettingsStoragePage(
    s: Strings,
    context: Context,
    logSizeText: String,
    onNavigate: (SettingsSubPage) -> Unit,
    onShowCleanLog: (Boolean) -> Unit,
    onShowClearAllData: (Boolean) -> Unit,
    onRefreshStorage: () -> Unit,
) {
    var appSizeText by remember {
        mutableStateOf(AdbManager.formatFileSize(try { File(context.packageCodePath).length() } catch (_: Exception) { 0L }))
    }
    var dataSizeText by remember {
        mutableStateOf(AdbManager.formatFileSize(try { getFolderSize(context.filesDir) } catch (_: Exception) { 0L }))
    }
    var cacheSizeText by remember {
        val totalCache = try {
            getFolderSize(context.cacheDir) + (context.externalCacheDir?.let { getFolderSize(it) } ?: 0L)
        } catch (_: Exception) { 0L }
        mutableStateOf(AdbManager.formatFileSize(totalCache))
    }

    fun refreshAllSizes() {
        appSizeText = AdbManager.formatFileSize(try { File(context.packageCodePath).length() } catch (_: Exception) { 0L })
        dataSizeText = AdbManager.formatFileSize(try { getFolderSize(context.filesDir) } catch (_: Exception) { 0L })
        val totalCache = try {
            getFolderSize(context.cacheDir) + (context.externalCacheDir?.let { getFolderSize(it) } ?: 0L)
        } catch (_: Exception) { 0L }
        cacheSizeText = AdbManager.formatFileSize(totalCache)
        onRefreshStorage()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onNavigate(SettingsSubPage.ROOT) }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = s.settingsStorageTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 存储占用看板
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(s.storageOverviewTitle, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(12.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(s.storageAppSize, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(appSizeText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(s.storageDataSize, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(dataSizeText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(s.storageCacheSize, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(cacheSizeText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(s.storageLogSize, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(logSizeText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            // 清理缓存
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(s.storageCleanCacheBtn, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(s.storageCacheSize + ": " + cacheSizeText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                try {
                                    context.cacheDir.deleteRecursively()
                                    context.externalCacheDir?.deleteRecursively()
                                } catch (_: Exception) {}
                                refreshAllSizes()
                                Toast.makeText(context, s.storageCleanCacheSuccess, Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.CleaningServices, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(s.storageCleanCacheBtn)
                        }
                    }
                }
            }

            // 清空运行日志
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(s.logCleanSectionTitle, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(s.logCleanSectionDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = String.format(s.logCurrentSize, logSizeText),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { onShowCleanLog(true) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(s.storageCleanLogBtn)
                        }
                    }
                }
            }

            // 清除应用存储（高危）
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f))
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(s.storageClearAllDataBtn, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(4.dp))
                        Text(s.storageClearAllDataWarning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Button(
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            onClick = { onShowClearAllData(true) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Warning, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(s.storageClearAllDataBtn)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 递归计算文件夹占用大小（字节）
 */
private fun getFolderSize(dir: File?): Long {
    if (dir == null || !dir.exists()) return 0L
    var size = 0L
    dir.listFiles()?.forEach { file ->
        size += if (file.isDirectory) getFolderSize(file) else file.length()
    }
    return size
}
