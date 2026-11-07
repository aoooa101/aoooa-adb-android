package com.aoooa.adb.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aoooa.adb.AdbManager
import com.aoooa.adb.BuildConfig
import com.aoooa.adb.Prefs
import com.aoooa.adb.R
import com.aoooa.adb.ui.i18n.I18n
import com.aoooa.adb.ui.theme.ThemeMode
import com.aoooa.adb.ui.theme.AoooaAdbTheme
import com.aoooa.adb.util.UpdateChecker
import com.aoooa.adb.util.UpdateInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    s: com.aoooa.adb.ui.i18n.Strings,
    debugMode: DebugMode,
    onDebugModeChange: (DebugMode) -> Unit,
    onConnectUsb: () -> Unit,
    onConnectFastboot: () -> Unit,
    onSelfPairing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val connected by AdbManager.connected

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(when (debugMode) {
                    DebugMode.WIRED -> s.wiredDebug
                    DebugMode.WIRELESS -> s.wirelessDebug
                    DebugMode.FASTBOOT -> s.fastbootDebug
                })
            },
            navigationIcon = {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.Menu, contentDescription = s.menuTitle)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(s.wirelessDebug) },
                        onClick = { onDebugModeChange(DebugMode.WIRELESS); menuExpanded = false },
                        leadingIcon = { Icon(Icons.Filled.Wifi, null) },
                    )
                    DropdownMenuItem(
                        text = { Text(s.wiredDebug) },
                        onClick = { onDebugModeChange(DebugMode.WIRED); menuExpanded = false },
                        leadingIcon = { Icon(Icons.Filled.Usb, null) },
                    )
                    DropdownMenuItem(
                        text = { Text(s.fastbootDebug) },
                        onClick = { onDebugModeChange(DebugMode.FASTBOOT); menuExpanded = false },
                        leadingIcon = { Icon(Icons.Filled.FlashOn, null) },
                    )
                }
            },
            actions = {
                Text(if (connected) s.statusConnected else s.statusDisconnected,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
            },
        )

        when (debugMode) {
            DebugMode.WIRED -> WiredDebugContent(s, onConnectUsb)
            DebugMode.WIRELESS -> WirelessDebugContent(s, onSelfPairing)
            DebugMode.FASTBOOT -> FastbootDebugContent(s, onConnectFastboot)
        }
    }
}

@Composable
internal fun WiredDebugContent(
    s: com.aoooa.adb.ui.i18n.Strings,
    onConnectUsb: () -> Unit,
) {
    val connected by AdbManager.connected

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Button(
                onClick = { if (connected) AdbManager.disconnect() else onConnectUsb() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(if (connected) Icons.Filled.LinkOff else Icons.Filled.Usb, null)
                Spacer(Modifier.width(8.dp))
                Text(if (connected) s.disconnect else s.connectUsb)
            }
        }
        item { LogPanel(s) }
    }
}

@Composable
internal fun FastbootDebugContent(
    s: com.aoooa.adb.ui.i18n.Strings,
    onConnectUsb: () -> Unit,
) {
    val connected by AdbManager.connected

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(s.fastbootTitle, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(s.fastbootHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { if (connected) AdbManager.disconnect() else onConnectUsb() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(if (connected) Icons.Filled.LinkOff else Icons.Filled.FlashOn, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (connected) s.disconnect else s.fastbootConnectBtn)
                    }
                }
            }
        }
        item { LogPanel(s) }
    }
}

@Composable
internal fun WirelessDebugContent(
    s: com.aoooa.adb.ui.i18n.Strings,
    onSelfPairing: () -> Unit = {},
) {
    var ipInput by remember { mutableStateOf("") }
    val connected by AdbManager.connected
    val context = LocalContext.current

    val discoveredPort by AdbManager.discoveredDebugPort
    val discoveredHost by AdbManager.discoveredDebugHost

    // 局域网扫描相关状态与协程
    var showScanDialog by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }
    var scannedDevices by remember { mutableStateOf<List<String>>(emptyList()) }
    val coroutineScope = rememberCoroutineScope()

    fun startLanScan() {
        showScanDialog = true
        isScanning = true
        scannedDevices = emptyList()
        coroutineScope.launch(Dispatchers.IO) {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            val multicastLock = wifiManager?.createMulticastLock("aoooa_lan_scan")?.apply {
                setReferenceCounted(true)
                runCatching { acquire() }
            }

            val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
            val found = Collections.synchronizedList(mutableListOf<String>())
            if (discoveredPort > 0) {
                found.add("${discoveredHost.ifBlank { "127.0.0.1" }}:$discoveredPort")
                withContext(Dispatchers.Main) {
                    scannedDevices = found.toList().distinct()
                }
            }

            // 启动 mDNS 动态监听 Android 11+ 无线调试广播 (_adb-tls-connect._tcp)
            var nsdListener: NsdManager.DiscoveryListener? = null
            if (nsdManager != null) {
                nsdListener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(regType: String) {}
                    override fun onServiceFound(service: NsdServiceInfo) {
                        try {
                            nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                    val host = serviceInfo.host?.hostAddress ?: "127.0.0.1"
                                    val port = serviceInfo.port
                                    if (port > 0) {
                                        val entry = "$host:$port"
                                        if (!found.contains(entry)) {
                                            found.add(entry)
                                            coroutineScope.launch(Dispatchers.Main) {
                                                scannedDevices = found.toList().distinct()
                                            }
                                        }
                                    }
                                }
                            })
                        } catch (_: Exception) {}
                    }
                    override fun onServiceLost(service: NsdServiceInfo) {}
                    override fun onDiscoveryStopped(serviceType: String) {}
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                }
                try {
                    nsdManager.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, nsdListener)
                } catch (e: Exception) {
                    AdbManager.debugLog("mDNS 扫描启动异常: ${e.message}")
                }
            }

            try {
                var prefix = ""
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                val interfaceList = mutableListOf<java.net.NetworkInterface>()
                while (interfaces.hasMoreElements()) {
                    interfaceList.add(interfaces.nextElement())
                }

                // 优先寻找 Wi-Fi 网卡 (wlan)，显式排除蜂窝移动数据 (rmnet) 与虚拟网卡
                val sortedIfaces = interfaceList.sortedByDescending { iface ->
                    val name = iface.name.lowercase()
                    when {
                        name.startsWith("wlan") -> 100
                        name.startsWith("eth") -> 90
                        name.contains("wlan") -> 80
                        name.contains("rmnet") || name.contains("dummy") || name.contains("p2p") || name.contains("tun") -> -100
                        else -> 10
                    }
                }

                for (iface in sortedIfaces) {
                    if (iface.isLoopback || !iface.isUp) continue
                    val name = iface.name.lowercase()
                    if (name.contains("rmnet") || name.contains("dummy") || name.contains("p2p") || name.contains("tun")) continue

                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                            val hostAddr = addr.hostAddress ?: ""
                            val parts = hostAddr.split(".")
                            if (parts.size == 4) {
                                prefix = "${parts[0]}.${parts[1]}.${parts[2]}."
                                break
                            }
                        }
                    }
                    if (prefix.isNotEmpty()) break
                }

                if (prefix.isNotEmpty()) {
                    val semaphore = Semaphore(32)
                    val jobs = (1..254).map { i ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                val target = "$prefix$i"
                                try {
                                    java.net.Socket().use { socket ->
                                        // 提高单次连接超时至 800ms，保证真实设备 SYN 握手不超时
                                        socket.connect(java.net.InetSocketAddress(target, 5555), 800)
                                        val entry = "$target:5555"
                                        if (!found.contains(entry)) {
                                            found.add(entry)
                                            coroutineScope.launch(Dispatchers.Main) {
                                                scannedDevices = found.toList().distinct()
                                            }
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    }
                    jobs.awaitAll()
                }
                // 为保证 mDNS 异步回包完全收拢，额外等待 1 秒
                kotlinx.coroutines.delay(1000)
            } catch (_: Exception) {
            } finally {
                try {
                    nsdListener?.let { nsdManager?.stopServiceDiscovery(it) }
                } catch (_: Exception) {}
                try {
                    if (multicastLock?.isHeld == true) multicastLock.release()
                } catch (_: Exception) {}
            }
            withContext(Dispatchers.Main) {
                scannedDevices = found.toList().distinct()
                isScanning = false
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(s.wirelessIpLabel, style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(
                value = ipInput,
                onValueChange = { ipInput = it },
                placeholder = { Text(s.wirelessIpHint) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { startLanScan() }) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = s.lanScanDialogTitle,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val input = ipInput.trim()
                    if (input.isNotEmpty()) {
                        val (host, port) = if (input.contains(":")) {
                            input.split(":").let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: 5555) }
                        } else input to 5555
                        if (connected) AdbManager.disconnect()
                        AdbManager.connectTcp(context, host, port)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(s.connectTcp)
            }
        }

        item {
            Text(s.pairingTitle, style = MaterialTheme.typography.titleSmall)
            Text(s.pairingHint, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onSelfPairing() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.NotificationsActive, null)
                Spacer(Modifier.width(6.dp))
                Text(s.pairingSelf)
            }
        }
        item { LogPanel(s) }
    }

    if (showScanDialog) {
        AlertDialog(
            onDismissRequest = { showScanDialog = false },
            title = { Text(s.lanScanDialogTitle) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isScanning) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(s.lanScanning, style = MaterialTheme.typography.bodyMedium)
                        }
                    } else if (scannedDevices.isEmpty()) {
                        Text(
                            s.lanScanEmpty,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(scannedDevices) { dev ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        ipInput = dev
                                        showScanDialog = false
                                        if (Prefs.lanScanAction == 1) {
                                            val (host, port) = if (dev.contains(":")) {
                                                dev.split(":").let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: 5555) }
                                            } else dev to 5555
                                            if (connected) AdbManager.disconnect()
                                            AdbManager.connectTcp(context, host, port)
                                        }
                                    },
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(dev, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                        Icon(Icons.Filled.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { if (isScanning) isScanning = false; showScanDialog = false }) {
                    Text(s.pairingCancel)
                }
            }
        )
    }
}

/**
 * 连接日志面板，支持长按自由选取复制与全文复制
 */
@Composable
internal fun LogPanel(
    s: com.aoooa.adb.ui.i18n.Strings,
    bottomContent: @Composable (() -> Unit)? = null
) {
    val logs = AdbManager.logs
    val context = LocalContext.current

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(s.logTitle, style = MaterialTheme.typography.labelMedium)
                Row {
                    TextButton(onClick = {
                        val text = logs.joinToString("\n")
                        if (text.isNotBlank()) {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("aoooa-adb log", text))
                            AdbManager.log(s.copyLog + " ✓")
                        }
                    }) { Text(s.copyLog) }
                    TextButton(onClick = { AdbManager.logs.clear() }) { Text(s.clear) }
                }
            }
            // 先做快照，避免后台写 logs 时 LazyColumn 遍历触发 ConcurrentModificationException
            val logSnapshot = remember(logs.size) {
                try {
                    logs.toList().takeLast(40)
                } catch (_: ConcurrentModificationException) {
                    emptyList()
                }
            }
            if (logSnapshot.isEmpty()) {
                Text(s.statusDisconnected, style = MaterialTheme.typography.bodySmall)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)
                ) {
                    items(logSnapshot.size) { idx ->
                        Text(
                            logSnapshot[idx],
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (bottomContent != null) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                bottomContent()
            }
        }
    }
}
