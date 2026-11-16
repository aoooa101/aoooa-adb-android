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
    var allowLan by remember { mutableStateOf(ProxyPreferences.allowLan(context)) }
    var showLanWarning by remember { mutableStateOf(false) }

    fun changeLan(allow: Boolean) {
        ProxyPreferences.setAllowLan(context, allow)
        allowLan = allow
        if (enabled) {
            AdbServerProxy.stop()
            AdbServerProxy.start(ProxyPreferences.port(context), allowLan = allow)
        }
    }
    var portText by remember { mutableStateOf(ProxyPreferences.port(context).toString()) }
    var status by remember { mutableStateOf(AdbServerProxy.status()) }
    val port = portText.toIntOrNull()?.takeIf { it in 1024..65535 }
    LaunchedEffect(Unit) {
        while (true) {
            status = AdbServerProxy.status()
            delay(500)
        }
    }
    if (showLanWarning) {
        AlertDialog(
            onDismissRequest = { showLanWarning = false },
            title = { Text(if (zh) "开启局域网 ADB 代理？" else "Enable LAN ADB proxy?") },
            text = {
                Text(if (zh)
                    "能够访问本机代理端口的设备，可能执行命令、安装应用和读写已连接设备的文件。此代理不提供额外身份验证或代理链路加密。仅在你信任且能够控制的网络中开启，勿在公共、共享或访客网络中使用，使用后及时关闭。此选项会保存，下次启用代理时继续生效。"
                    else "Devices able to reach this port may execute commands, install apps and access files on the connected device. The proxy provides no additional authentication or link encryption. Enable only on a trusted network you control, never on public, shared or guest networks. Disable after use. This option is saved and applies when the proxy is enabled again.")
            },
            confirmButton = {
                TextButton(onClick = {
                    changeLan(true)
                    status = AdbServerProxy.status()
                    showLanWarning = false
                }) { Text(if (zh) "我了解风险，开启" else "Understand and enable") }
            },
            dismissButton = {
                TextButton(onClick = { showLanWarning = false }) {
                    Text(if (zh) "取消" else "Cancel")
                }
            }
        )
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
                    if (value) AdbServerProxy.start(ProxyPreferences.port(context), allowLan = allowLan) else AdbServerProxy.stop()
                    status = AdbServerProxy.status()
                })
            }
            Text(
                if (zh) "代理和局域网访问默认关闭。本机其他有网络权限的应用也可能访问代理。关闭代理不会断开应用的设备连接。"
                else "Proxy and LAN access are off by default. Other local apps with network permission may access the proxy. Stopping it does not disconnect the app's device connection.",
                style = MaterialTheme.typography.bodySmall
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (zh) "允许局域网访问" else "Allow LAN access", Modifier.weight(1f))
                Switch(checked = allowLan, enabled = enabled, onCheckedChange = { allow ->
                    if (allow) showLanWarning = true else {
                        changeLan(false)
                        status = AdbServerProxy.status()
                    }
                })
            }
            Text(
                if (zh) "开启后监听所有 IPv4 网卡。其他设备应使用本机局域网 IP 和端口连接，而不是 0.0.0.0。切换范围会断开现有代理客户端。"
                else "LAN mode listens on all IPv4 interfaces. Connect using this device's LAN IP and port, not 0.0.0.0. Changing scope disconnects proxy clients.",
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
                        AdbServerProxy.start(value, allowLan = allowLan)
                    }
                    status = AdbServerProxy.status()
                }
            }) {
                Text(if (zh) "保存端口（已启用时重启代理）" else "Save port (restart if enabled)")
            }
            HorizontalDivider()
            val message = when {
                status.starting -> if (zh) "正在启动" else "Starting"
                status.port != null -> "${if (zh) "正在监听" else "Listening"} ${status.address}:${status.port}"
                status.error != null -> "${if (zh) "启动或运行失败" else "Proxy error"}: ${status.error}"
                else -> if (zh) "未启动" else "Stopped"
            }
            Text(message, style = MaterialTheme.typography.bodyMedium)
            if (enabled && status.port == null && !status.starting) {
                OutlinedButton(onClick = {
                    AdbServerProxy.start(ProxyPreferences.port(context), allowLan = allowLan)
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
