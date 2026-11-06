package com.aoooa.adb.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aoooa.adb.Prefs
import com.aoooa.adb.mcp.AdbMcpServer
import com.aoooa.adb.mcp.McpRuleEngine
import com.aoooa.adb.ui.SettingsSubPage
import com.aoooa.adb.ui.i18n.Strings
import java.util.UUID

/**
 * MCP 设置主页面：
 * 1. 负责 MCP 服务的启停胶囊开关、局域网访问胶囊开关与标准 Bearer 鉴权开关；
 * 2. 运行状态展示与多地址滚动防超限展示栏（支持点击一键复制）；
 * 3. 密钥 Token 查看、复制与随机生成管理卡片；
 * 4. 安全拦截规则独立二级菜单卡片（点击直达三级规则管理页面）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsMcpPage(
    s: Strings,
    onNavigate: (SettingsSubPage) -> Unit
) {
    val context = LocalContext.current
    var isRunning by AdbMcpServer.isRunning
    var allowLan by remember { mutableStateOf(Prefs.mcpAllowLan) }
    var authEnabled by remember { mutableStateOf(Prefs.mcpAuthEnabled) }
    var authToken by remember { mutableStateOf(Prefs.mcpAuthToken) }
    var showRestartDialog by remember { mutableStateOf(false) }
    var pendingLanState by remember { mutableStateOf(false) }

    val ruleCount = remember { McpRuleEngine.loadRules().size }

    val addresses by remember(isRunning, allowLan) {
        derivedStateOf {
            if (isRunning) AdbMcpServer.getListeningAddresses() else emptyList()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部导航栏
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
                text = s.settingsMcpTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. 服务控制与运行状态卡片
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = s.settingsMcpTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )

                            // 运行状态徽章
                            Surface(
                                shape = CircleShape,
                                color = if (isRunning) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    text = if (isRunning) s.mcpStatusRunning else s.mcpStatusStopped,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = s.settingsMcpDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(Modifier.height(14.dp))

                        // 胶囊开关 1：开启 MCP 服务
                        FilterChip(
                            selected = isRunning,
                            onClick = {
                                if (isRunning) {
                                    AdbMcpServer.stop()
                                    Prefs.mcpEnabled = false
                                } else {
                                    val success = AdbMcpServer.start(context)
                                    Prefs.mcpEnabled = success
                                }
                            },
                            label = {
                                Text(
                                    text = s.mcpSwitchEnable,
                                    fontSize = 13.sp,
                                    fontWeight = if (isRunning) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    if (isRunning) Icons.Filled.CheckCircle else Icons.Filled.PlayCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(8.dp))

                        // 胶囊开关 2：允许局域网访问
                        FilterChip(
                            selected = allowLan,
                            onClick = {
                                val nextState = !allowLan
                                if (isRunning) {
                                    pendingLanState = nextState
                                    showRestartDialog = true
                                } else {
                                    allowLan = nextState
                                    Prefs.mcpAllowLan = nextState
                                }
                            },
                            label = {
                                Text(
                                    text = s.mcpSwitchLan,
                                    fontSize = 13.sp,
                                    fontWeight = if (allowLan) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    if (allowLan) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(8.dp))

                        // 胶囊开关 3：鉴权验证 (标准 Bearer Token)
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
                    }
                }
            }

            // 2. 密钥 Token 管理卡片（仅当开启鉴权时展示）
            if (authEnabled) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = s.mcpTokenLabel,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )

                                TextButton(
                                    onClick = {
                                        val newToken = UUID.randomUUID().toString().replace("-", "")
                                        authToken = newToken
                                        Prefs.mcpAuthToken = newToken
                                    }
                                ) {
                                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(s.mcpTokenGenerate, fontSize = 12.sp)
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText("MCP Token", authToken))
                                        Toast.makeText(context, s.mcpTokenCopied, Toast.LENGTH_SHORT).show()
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = authToken,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        Icons.Filled.ContentCopy,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. 监听地址展示栏卡片
            if (isRunning) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = s.mcpAddressTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            Spacer(Modifier.height(8.dp))

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                addresses.forEach { addr ->
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                cm.setPrimaryClip(ClipData.newPlainText("MCP Address", addr))
                                                Toast.makeText(context, s.mcpCopySuccess, Toast.LENGTH_SHORT).show()
                                            }
                                    ) {
                                        Text(
                                            text = addr,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 4. 安全拦截规则独立二级菜单卡片
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigate(SettingsSubPage.MCP_RULES) },
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Filled.Security,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }

                            Spacer(Modifier.width(14.dp))

                            Column {
                                Text(
                                    text = s.mcpMenuRulesTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "${s.mcpMenuRulesSubtitle} (${ruleCount})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    // 重启服务确认弹窗
    if (showRestartDialog) {
        AlertDialog(
            onDismissRequest = { showRestartDialog = false },
            title = { Text(s.mcpRestartDialogTitle) },
            text = { Text(s.mcpRestartDialogMsg) },
            confirmButton = {
                TextButton(
                    onClick = {
                        allowLan = pendingLanState
                        Prefs.mcpAllowLan = pendingLanState
                        showRestartDialog = false
                        AdbMcpServer.restart(context)
                    }
                ) {
                    Text(s.mcpRestartConfirm)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        allowLan = pendingLanState
                        Prefs.mcpAllowLan = pendingLanState
                        showRestartDialog = false
                    }
                ) {
                    Text(s.mcpRestartCancel)
                }
            }
        )
    }
}
