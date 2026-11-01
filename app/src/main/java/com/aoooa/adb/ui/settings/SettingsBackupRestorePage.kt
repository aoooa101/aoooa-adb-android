package com.aoooa.adb.ui.settings

import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aoooa.adb.ui.SettingsSubPage
import com.aoooa.adb.ui.i18n.Strings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份与恢复二级视图：负责应用配置与 ADB 密钥凭据的导出与导入
 */
@Composable
internal fun SettingsBackupRestorePage(
    s: Strings,
    exportBackupLauncher: ManagedActivityResultLauncher<String, Uri?>,
    importBackupLauncher: ManagedActivityResultLauncher<Array<String>, Uri?>,
    onExportKey: () -> Unit,
    onImportKey: () -> Unit,
    onNavigate: (SettingsSubPage) -> Unit,
) {
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
                text = s.settingsBackupTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 软件配置备份与恢复
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(s.backupAppConfigTitle, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(s.backupAppConfigDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    val ts = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
                                    exportBackupLauncher.launch("aoooa_adb_backup_$ts.json")
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(s.backupExportConfigBtn, fontSize = 13.sp)
                            }

                            OutlinedButton(
                                onClick = {
                                    importBackupLauncher.launch(arrayOf("application/json", "text/*"))
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(s.backupImportConfigBtn, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            // ADB 密钥凭据迁移（换机免重新授权）
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(s.backupKeyTitle, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(s.backupKeyDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { onExportKey() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(s.backupExportKeyBtn, fontSize = 13.sp)
                            }

                            OutlinedButton(
                                onClick = { onImportKey() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.VpnKey, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(s.backupImportKeyBtn, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
