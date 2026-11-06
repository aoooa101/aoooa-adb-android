package com.aoooa.adb.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aoooa.adb.Prefs
import com.aoooa.adb.mcp.AdbMcpServer
import com.aoooa.adb.ui.SettingsSubPage
import com.aoooa.adb.ui.i18n.Strings
import java.util.UUID

/**
 * MCP 服务配置独立管理页面：
 * 1. 支持自定义服务监听端口号及范围校验（1024 - 65535）；
 * 2. 支持标准 Bearer 鉴权开关与自由自定义输入访问密钥；
 * 3. 支持访问密钥一键复制、随机生成与调用方式说明；
 * 4. 运行中修改端口自动平滑重启服务以保证即时生效。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsMcpConfigPage(
    s: Strings,
    onNavigate: (SettingsSubPage) -> Unit
) {
    val context = LocalContext.current
    val isRunning by AdbMcpServer.isRunning

    var portText by remember { mutableStateOf(Prefs.mcpPort.toString()) }
    var authEnabled by remember { mutableStateOf(Prefs.mcpAuthEnabled) }
    var tokenText by remember { mutableStateOf(Prefs.mcpAuthToken) }

    val parsedPort = portText.toIntOrNull()
    val isPortValid = parsedPort != null && parsedPort in 1024..65535

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = s.mcpConfigPageTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { onNavigate(SettingsSubPage.MCP) }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. 服务端口自定义卡片
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Tag,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = s.mcpPortLabel,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        OutlinedTextField(
                            value = portText,
                            onValueChange = { input ->
                                portText = input.filter { it.isDigit() }
                            },
                            label = { Text("Port") },
                            isError = !isPortValid,
                            supportingText = {
                                if (!isPortValid) {
                                    Text(s.mcpPortInvalid, color = MaterialTheme.colorScheme.error)
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(
                                enabled = isPortValid && parsedPort != Prefs.mcpPort,
                                onClick = {
                                    if (parsedPort != null && isPortValid) {
                                        Prefs.mcpPort = parsedPort
                                        Toast.makeText(context, s.mcpConfigSavedNotice, Toast.LENGTH_SHORT).show()
                                        if (isRunning) {
                                            AdbMcpServer.restart(context)
                                        }
                                    }
                                }
                            ) {
                                Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(s.confirm)
                            }
                        }
                    }
                }
            }

            // 2. 鉴权验证与自定义密钥设置卡片
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.VpnKey,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = s.mcpTokenLabel,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        // 胶囊开关：鉴权验证
                        FilterChip(
                            selected = authEnabled,
                            onClick = {
                                val nextAuth = !authEnabled
                                authEnabled = nextAuth
                                Prefs.mcpAuthEnabled = nextAuth
                            },
                            label = {
                                Text(
                                    text = s.mcpSwitchAuth,
                                    fontSize = 13.sp,
                                    fontWeight = if (authEnabled) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    if (authEnabled) Icons.Filled.Lock else Icons.Filled.LockOpen,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (authEnabled) {
                            Spacer(Modifier.height(14.dp))

                            OutlinedTextField(
                                value = tokenText,
                                onValueChange = { input ->
                                    tokenText = input.trim()
                                    Prefs.mcpAuthToken = input.trim()
                                },
                                label = { Text(s.mcpSecretKeyCustomHint) },
                                singleLine = true,
                                trailingIcon = {
                                    IconButton(
                                        onClick = {
                                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            cm.setPrimaryClip(ClipData.newPlainText("MCP Token", tokenText))
                                            Toast.makeText(context, s.mcpTokenCopied, Toast.LENGTH_SHORT).show()
                                        }
                                    ) {
                                        Icon(Icons.Filled.ContentCopy, contentDescription = null)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Header: Authorization: Bearer <token>",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )

                                OutlinedButton(
                                    onClick = {
                                        val newToken = UUID.randomUUID().toString().replace("-", "")
                                        tokenText = newToken
                                        Prefs.mcpAuthToken = newToken
                                    }
                                ) {
                                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(s.mcpTokenGenerate, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
