package com.aoooa.adb.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aoooa.adb.AdbManager
import com.aoooa.adb.ui.i18n.Strings
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.EnumMap
import kotlin.random.Random

/**
 * Android 11+ 配对其他设备弹窗组件：
 * 支持智能配对（自动抓取配对端口）、扫码配对（展示标准 AOSP 配对二维码）与手动配对。
 */
@Composable
fun PairOtherDeviceDialog(
    s: Strings,
    onDismissRequest: () -> Unit,
) {
    // 0: 智能配对, 1: 扫码配对, 2: 手动配对
    var selectedTab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    // 智能配对状态
    var smartCode by remember { mutableStateOf("") }
    var discoveredDevices by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var selectedDevice by remember { mutableStateOf<Pair<String, Int>?>(null) }

    // 扫码配对状态
    var qrPassword by remember { mutableStateOf(generateRandomCode()) }
    var qrServiceName by remember { mutableStateOf("aoooa-${Random.nextInt(1000, 9999)}") }
    val qrPayload = remember(qrPassword, qrServiceName) {
        "WIFI:T:ADB;S:$qrServiceName;P:$qrPassword;;"
    }
    val qrBitmap = remember(qrPayload) {
        generateQrBitmap(qrPayload, 480)
    }

    // 手动配对状态
    var manualIp by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("") }
    var manualCode by remember { mutableStateOf("") }

    // 监听局域网内目标设备的 _adb-tls-pairing._tcp 配对广播
    DisposableEffect(selectedTab) {
        if (selectedTab == 2) return@DisposableEffect onDispose {}

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = wifiManager?.createMulticastLock("aoooa_pairing_other_multicast")?.apply {
            setReferenceCounted(true)
            runCatching { acquire() }
        }

        val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager

        // 获取本机所有活跃的 IPv4 地址，用于精确排除本机广播
        val localIps = mutableSetOf("127.0.0.1", "localhost")
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        addr.hostAddress?.let { localIps.add(it) }
                    }
                }
            }
        } catch (_: Exception) {}

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                try {
                    nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val host = serviceInfo.host?.hostAddress ?: return
                            val port = serviceInfo.port
                            // 排除本机 IP，仅保留局域网其他设备
                            if (host !in localIps && port > 0) {
                                val pairItem = host to port
                                val currentList = discoveredDevices.toMutableList()
                                if (!currentList.contains(pairItem)) {
                                    currentList.add(pairItem)
                                    discoveredDevices = currentList
                                    if (selectedDevice == null) {
                                        selectedDevice = pairItem
                                    }
                                }
                                // 扫码配对模式下，对方设备扫码后自动发起配对
                                if (selectedTab == 1) {
                                    AdbManager.log(s.pairingWait)
                                    AdbManager.pair(host, port, qrPassword)
                                    onDismissRequest()
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
            nsdManager?.discoverServices("_adb-tls-pairing._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {}

        onDispose {
            try {
                nsdManager?.stopServiceDiscovery(listener)
            } catch (_: Exception) {}
            try {
                if (multicastLock?.isHeld == true) multicastLock.release()
            } catch (_: Exception) {}
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(s.pairingOther)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text(s.pairingSmartTab) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text(s.pairingQrTab) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text(s.pairingManualTab) }
                    )
                }

                when (selectedTab) {
                    0 -> {
                        // 智能配对面板说明
                        Text(
                            text = s.pairingSmartDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        val dev = selectedDevice
                        if (dev == null) {
                            // 正在扫描中
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    horizontalAlignment = Alignment.CenterVertically,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp)
                                        Spacer(Modifier.width(10.dp))
                                        Text(s.lanScanning, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    }
                                    Text(
                                        text = s.pairingWaitingTarget,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            // 已捕获到设备
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Column {
                                        Text(
                                            text = String.format(s.pairingFoundDevice, "${dev.first}:${dev.second}"),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                            }

                            // 如果局域网捕获到多个配对设备，允许点选切换
                            if (discoveredDevices.size > 1) {
                                LazyColumn(
                                    modifier = Modifier.fillMaxWidth().heightIn(max = 100.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    items(discoveredDevices) { item ->
                                        val isCur = (item == selectedDevice)
                                        Card(
                                            modifier = Modifier.fillMaxWidth().clickable { selectedDevice = item },
                                            colors = CardDefaults.cardColors(
                                                containerColor = if (isCur) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                            )
                                        ) {
                                            Text(
                                                text = "${item.first}:${item.second}",
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        OutlinedTextField(
                            value = smartCode,
                            onValueChange = { if (it.length <= 6) smartCode = it },
                            label = { Text(s.pairingCodeLabel) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    1 -> {
                        // 扫码配对面板说明
                        Text(
                            text = s.pairingQrDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterVertically,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (qrBitmap != null) {
                                Box(
                                    modifier = Modifier
                                        .size(190.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.White)
                                        .padding(8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        bitmap = qrBitmap,
                                        contentDescription = s.pairingQrTab,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            } else {
                                CircularProgressIndicator(modifier = Modifier.size(36.dp))
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(s.lanScanning, style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.width(16.dp))
                                TextButton(
                                    onClick = {
                                        qrPassword = generateRandomCode()
                                        qrServiceName = "aoooa-${Random.nextInt(1000, 9999)}"
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(s.refresh, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }

                    2 -> {
                        // 手动配对面板说明
                        Text(
                            text = s.pairingManualDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = manualIp,
                            onValueChange = { manualIp = it },
                            label = { Text(s.pairingIpLabel) },
                            placeholder = { Text("192.168.x.x") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        OutlinedTextField(
                            value = manualPort,
                            onValueChange = { manualPort = it },
                            label = { Text(s.pairingPortLabel) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        OutlinedTextField(
                            value = manualCode,
                            onValueChange = { if (it.length <= 6) manualCode = it },
                            label = { Text(s.pairingCodeLabel) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (selectedTab != 1) {
                TextButton(
                    onClick = {
                        if (selectedTab == 0) {
                            val dev = selectedDevice
                            val code = smartCode.trim()
                            if (dev != null && code.isNotEmpty()) {
                                AdbManager.log(s.pairingWait)
                                AdbManager.pair(dev.first, dev.second, code)
                                onDismissRequest()
                            }
                        } else {
                            val ip = manualIp.trim()
                            val port = manualPort.trim().toIntOrNull() ?: 0
                            val code = manualCode.trim()
                            if (ip.isNotEmpty() && port > 0 && code.isNotEmpty()) {
                                AdbManager.log(s.pairingWait)
                                AdbManager.pair(ip, port, code)
                                onDismissRequest()
                            }
                        }
                    },
                    enabled = if (selectedTab == 0) {
                        selectedDevice != null && smartCode.trim().length == 6
                    } else {
                        manualIp.trim().isNotEmpty() && (manualPort.trim().toIntOrNull() ?: 0) > 0 && manualCode.trim().length == 6
                    }
                ) {
                    Text(s.pairingStart)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(s.pairingCancel)
            }
        }
    )
}

/** 生成 6 位随机配对码 */
private fun generateRandomCode(): String = Random.nextInt(100000, 999999).toString()

/** 使用 ZXing 生成 ImageBitmap */
private fun generateQrBitmap(content: String, size: Int): ImageBitmap? {
    return try {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.CHARACTER_SET, "UTF-8")
            put(EncodeHintType.MARGIN, 1)
        }
        val bitMatrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            size,
            size,
            hints
        )
        val width = bitMatrix.width
        val height = bitMatrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (bitMatrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE
            }
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}
