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
import com.aoooa.adb.ui.i18n.Strings
import com.aoooa.adb.ui.i18n.proxy
import com.aoooa.adb.adb.AdbServerProxy
import com.aoooa.adb.adb.proxy.ProxyPreferences
import kotlinx.coroutines.delay

/** 管理本机代理配置并展示服务实际监听状态。 */
@Composable
internal fun SettingsAdbProxyPage(s: Strings, onBack: () -> Unit) {
    val context = LocalContext.current
    val p = s.proxy
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
            title = { Text(p.warningTitle) },
            text = { Text(p.warningMessage) },
            confirmButton = {
                TextButton(onClick = {
                    changeLan(true)
                    status = AdbServerProxy.status()
                    showLanWarning = false
                }) { Text(p.confirmLan) }
            },
            dismissButton = {
                TextButton(onClick = { showLanWarning = false }) {
                    Text(p.cancel)
                }
            }
        )
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = p.back)
            }
            Text(p.title, style = MaterialTheme.typography.titleMedium)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.enable, Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { value ->
                    ProxyPreferences.setEnabled(context, value)
                    enabled = value
                    if (value) AdbServerProxy.start(ProxyPreferences.port(context), allowLan = allowLan) else AdbServerProxy.stop()
                    status = AdbServerProxy.status()
                })
            }
            Text(
                p.description,
                style = MaterialTheme.typography.bodySmall
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.allowLan, Modifier.weight(1f))
                Switch(checked = allowLan, enabled = enabled, onCheckedChange = { allow ->
                    if (allow) showLanWarning = true else {
                        changeLan(false)
                        status = AdbServerProxy.status()
                    }
                })
            }
            Text(
                p.lanDescription,
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it },
                label = { Text(p.port) },
                supportingText = { Text(p.portHint) },
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
                Text(p.savePort)
            }
            HorizontalDivider()
            val message = when {
                status.starting -> p.starting
                status.port != null -> p.listening.format(status.address, status.port)
                status.error != null -> p.error.format(status.error)
                else -> p.stopped
            }
            Text(message, style = MaterialTheme.typography.bodyMedium)
            if (enabled && status.port == null && !status.starting) {
                OutlinedButton(onClick = {
                    AdbServerProxy.start(ProxyPreferences.port(context), allowLan = allowLan)
                    status = AdbServerProxy.status()
                }) { Text(p.retry) }
            }
            Text(
                p.limitations,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
