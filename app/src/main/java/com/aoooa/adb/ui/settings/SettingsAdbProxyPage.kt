package com.aoooa.adb.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aoooa.adb.Prefs
import com.aoooa.adb.adb.AdbServerProxy
import com.aoooa.adb.adb.proxy.ProxyPreferences
import kotlinx.coroutines.delay

/** 管理本机代理配置并展示服务实际监听状态。 */
@Composable
internal fun SettingsAdbProxyPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val zh = Prefs.lang == "zh"
    var enabled by remember { mutableStateOf(ProxyPreferences.enabled(context)) }
    var portText by remember { mutableStateOf(ProxyPreferences.port(context).toString()) }
    var status by remember { mutableStateOf(AdbServerProxy.status()) }
    val port = portText.toIntOrNull()?.takeIf { it in 1024..65535 }
    LaunchedEffect(Unit) {
        while (true) {
            status = AdbServerProxy.status()
            delay(500)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = if (zh) "返回" else "Back")
            }
            Text(if (zh) "ADB 代理" else "ADB proxy", style = MaterialTheme.typography.titleMedium)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (zh) "启用 ADB 代理" else "Enable ADB proxy", Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { value ->
                    ProxyPreferences.setEnabled(context, value)
                    enabled = value
                    if (value) AdbServerProxy.start(ProxyPreferences.port(context)) else AdbServerProxy.stop()
                    status = AdbServerProxy.status()
                })
            }
            Text(
                if (zh) "默认关闭，仅允许本机访问。开启后，本机其他有网络权限的应用可能通过此端口操作已连接的设备。关闭代理不会断开应用的设备连接。"
                else "Off by default. Local access only. Other local apps with network permission may use this port to control the connected device. Stopping the proxy does not disconnect the app's device connection.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it },
                label = { Text(if (zh) "监听端口" else "Listening port") },
                supportingText = { Text("1024–65535 · ${if (zh) "默认" else "Default"} 5037") },
                isError = port == null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Button(enabled = port != null, onClick = {
                port?.let { value ->
                    ProxyPreferences.setPort(context, value)
                    if (enabled) {
                        AdbServerProxy.stop()
                        AdbServerProxy.start(value)
                    }
                    status = AdbServerProxy.status()
                }
            }) {
                Text(if (zh) "保存端口（已启用时重启代理）" else "Save port (restart if enabled)")
            }
            HorizontalDivider()
            val message = when {
                status.starting -> if (zh) "正在启动" else "Starting"
                status.port != null -> "${if (zh) "正在监听" else "Listening"} 127.0.0.1:${status.port}"
                status.error != null -> "${if (zh) "启动或运行失败" else "Proxy error"}: ${status.error}"
                else -> if (zh) "未启动" else "Stopped"
            }
            Text(message, style = MaterialTheme.typography.bodyMedium)
            if (enabled && status.port == null && !status.starting) {
                OutlinedButton(onClick = {
                    AdbServerProxy.start(ProxyPreferences.port(context))
                    status = AdbServerProxy.status()
                }) { Text(if (zh) "重新启动" else "Retry") }
            }
            Text(
                if (zh) "仅支持部分 ADB 服务，不是完整的官方 ADB Server。退出应用后服务不保证后台运行。修改端口或关闭代理会断开现有代理客户端。"
                else "Supports a subset of ADB services, not a full official ADB server. Background operation after leaving the app is not guaranteed. Changing the port or stopping the proxy disconnects proxy clients.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
