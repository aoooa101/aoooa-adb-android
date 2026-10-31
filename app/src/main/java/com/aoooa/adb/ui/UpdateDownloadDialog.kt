package com.aoooa.adb.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.aoooa.adb.ui.i18n.Strings
import com.aoooa.adb.util.SizeFormat
import com.aoooa.adb.util.UpdateChecker
import com.aoooa.adb.util.UpdateDownloadState

/**
 * 更新包下载进度与校验结果对话框。
 *
 * 下载与校验期间允许折叠为后台任务，进度仍可从系统通知栏查看；
 * 校验不通过时展示阻止安装的原因，且不提供直接安装入口。
 */
@Composable
fun UpdateDownloadDialog(
    s: Strings,
    state: UpdateDownloadState,
    onHide: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current

    when (state) {
        is UpdateDownloadState.Downloading -> {
            AlertDialog(
                onDismissRequest = onHide,
                title = {
                    Text(
                        text = s.updateDownloadTitle,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (state.percent >= 0) {
                            Text(
                                text = "${state.percent}%",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(
                                progress = { state.percent / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = buildSizeText(s, state.downloaded, state.total),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }
                },
                confirmButton = {
                    OutlinedButton(onClick = {
                        // 折叠到后台后进度依赖系统通知，未授权时先提示用户
                        if (!hasNotificationPermission(context)) {
                            Toast.makeText(
                                context,
                                s.updateDownloadHideNoPermission,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        onHide()
                    }) {
                        Text(s.updateDownloadHide)
                    }
                }
            )
        }

        is UpdateDownloadState.Verifying -> {
            AlertDialog(
                onDismissRequest = {},
                title = {
                    Text(
                        text = s.updateDownloadVerifying,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "SHA-256",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }
                },
                confirmButton = {}
            )
        }

        is UpdateDownloadState.Failed -> {
            val title = if (state.hashMismatch) s.updateVerifyFailedTitle else s.updateDownloadFailedTitle
            AlertDialog(
                onDismissRequest = onClose,
                title = {
                    Text(
                        text = title,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = s.updateVerifyFailedMessage,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = onRetry) {
                        Text(s.updateDownloadRetry)
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = onClose) {
                        Text(s.updateDownloadClose)
                    }
                }
            )
        }

        is UpdateDownloadState.CachedUpdateAvailable -> {
            AlertDialog(
                onDismissRequest = onClose,
                title = {
                    Text(
                        text = s.updateCachedFoundTitle,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = String.format(
                                s.updateCachedFoundMessage,
                                state.currentVersionName,
                                state.cachedVersionName
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = SizeFormat.fileSize(state.apkFile.length()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        UpdateChecker.installApk(context, state.apkFile)
                        onClose()
                    }) {
                        Text(s.updateCachedInstall)
                    }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            UpdateChecker.deleteCachedApk(state.apkFile)
                            onClose()
                        }) {
                            Text(s.updateCachedClean)
                        }
                        TextButton(onClick = onClose) {
                            Text(s.cancel)
                        }
                    }
                }
            )
        }

        else -> Unit
    }
}

/** 拼接进度文本；总长度未知时只显示已下载量。 */
private fun buildSizeText(s: Strings, downloaded: Long, total: Long): String {
    return when {
        total > 0L -> "${SizeFormat.fileSize(downloaded)} / ${SizeFormat.fileSize(total)}"
        downloaded > 0L -> SizeFormat.fileSize(downloaded)
        else -> s.updateDownloadSizeUnknown
    }
}

/** 判断通知权限状态；Android 13 以下默认具备。 */
private fun hasNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED
}
