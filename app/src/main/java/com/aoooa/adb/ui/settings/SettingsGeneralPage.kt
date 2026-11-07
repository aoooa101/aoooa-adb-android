package com.aoooa.adb.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
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
    val context = LocalContext.current

    // 检测通知权限授予状态（兼容各 Android 版本与系统级通知开关）
    fun checkNotificationPermission(): Boolean {
        val areEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!areEnabled) return false
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    var hasNotifPerm by remember { mutableStateOf(checkNotificationPermission()) }

    // 页面恢复时动态刷新权限状态
    DisposableEffect(Unit) {
        val lifecycle = (context as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasNotifPerm = checkNotificationPermission()
            }
        }
        lifecycle?.addObserver(observer)
        onDispose {
            lifecycle?.removeObserver(observer)
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        hasNotifPerm = checkNotificationPermission()
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
            // 通知权限管理
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(s.notifPermTitle, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = s.notifPermDesc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        if (hasNotifPerm) {
                            AssistChip(
                                onClick = {},
                                label = { Text(s.notifPermGranted) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            )
                        } else {
                            Button(
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= 33 && !hasNotifPerm) {
                                        permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        try {
                                            val intent = Intent().apply {
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                    action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                                } else {
                                                    action = "android.settings.APP_NOTIFICATION_SETTINGS"
                                                    putExtra("app_package", context.packageName)
                                                    putExtra("app_uid", context.applicationInfo.uid)
                                                }
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(intent)
                                        } catch (_: Exception) {}
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(s.notifPermGoGrant)
                            }
                        }
                    }
                }
            }

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
