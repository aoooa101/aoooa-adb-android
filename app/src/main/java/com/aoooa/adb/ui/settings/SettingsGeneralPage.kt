package com.aoooa.adb.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aoooa.adb.AdbManager
import com.aoooa.adb.Prefs
import com.aoooa.adb.ui.SettingsSubPage
import com.aoooa.adb.ui.i18n.Strings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 通用设置二级视图：更新提醒策略与出厂重置
 */
@Composable
internal fun SettingsGeneralPage(
    s: Strings,
    onNavigate: (SettingsSubPage) -> Unit,
    onShowReset: (Boolean) -> Unit,
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
                text = s.settingsGeneralTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 更新提醒设置
            item {
                var pauseUntil by remember { mutableLongStateOf(Prefs.pauseUpdateUntil) }
                val statusText = when {
                    pauseUntil == -1L -> s.pauseUpdatePermanentStatus
                    pauseUntil > System.currentTimeMillis() -> {
                        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        String.format(s.pauseUpdateUntilDate, df.format(Date(pauseUntil)))
                    }
                    else -> s.pauseUpdateNormalStatus
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(s.pauseUpdateSectionTitle, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(s.pauseUpdateSectionDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = String.format(s.pauseUpdateStatus, statusText),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = (pauseUntil == 0L || (pauseUntil > 0 && pauseUntil <= System.currentTimeMillis())),
                                onClick = {
                                    Prefs.pauseUpdateUntil = 0L
                                    pauseUntil = 0L
                                    AdbManager.log(s.pauseUpdateNormalStatus)
                                },
                                label = { Text(s.pauseUpdateNormal, fontSize = 12.sp) }
                            )

                            FilterChip(
                                selected = (pauseUntil > System.currentTimeMillis() && pauseUntil <= System.currentTimeMillis() + 8 * 24 * 3600 * 1000L),
                                onClick = {
                                    val target = System.currentTimeMillis() + 7 * 24 * 3600 * 1000L
                                    Prefs.pauseUpdateUntil = target
                                    pauseUntil = target
                                    val df = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                                    AdbManager.log(String.format(s.pauseUpdateUntilDate, df.format(Date(target))))
                                },
                                label = { Text(s.pauseUpdate7Days, fontSize = 12.sp) }
                            )

                            FilterChip(
                                selected = (pauseUntil > System.currentTimeMillis() + 8 * 24 * 3600 * 1000L),
                                onClick = {
                                    val target = System.currentTimeMillis() + 14 * 24 * 3600 * 1000L
                                    Prefs.pauseUpdateUntil = target
                                    pauseUntil = target
                                    val df = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                                    AdbManager.log(String.format(s.pauseUpdateUntilDate, df.format(Date(target))))
                                },
                                label = { Text(s.pauseUpdate14Days, fontSize = 12.sp) }
                            )

                            FilterChip(
                                selected = (pauseUntil == -1L),
                                onClick = {
                                    Prefs.pauseUpdateUntil = -1L
                                    pauseUntil = -1L
                                    AdbManager.log(s.pauseUpdatePermanentStatus)
                                },
                                label = { Text(s.pauseUpdatePermanent, fontSize = 12.sp) }
                            )
                        }
                    }
                }
            }

            // 恢复出厂设置
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(s.cmdRestoreDefault, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(s.cmdRestoreDefaultConfirm, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { onShowReset(true) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Restore, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(s.cmdRestoreDefault)
                        }
                    }
                }
            }
        }
    }
}
