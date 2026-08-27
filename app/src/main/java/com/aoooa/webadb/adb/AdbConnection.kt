package com.aoooa.webadb.adb

import android.content.Context
import com.aoooa.webadb.bridge.Channel
import com.aoooa.webadb.bridge.TcpChannel
import com.aoooa.webadb.ui.i18n.I18n
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ADB 连接层：负责认证握手（CNXN/AUTH/STLS）与 shell 会话（OPEN/WRTE/CLSE）。
 * 严格按照 AOSP 标准规范实现，全面兼容 Android 7 ~ 15，并支持 Android 11+ TLS 1.3 隧道协商。
 */
class AdbConnection(
    private val channel: Channel,
    context: Context? = null,
    private val onLog: (String) -> Unit = {},
    private val onDebugLog: (String) -> Unit = {}
) {
    companion object {
        // 无线 (TcpChannel) 使用现代协议参数（STLS/大 payload/features）
        private const val CONNECT_VERSION = 0x01000001
        private const val CONNECT_MAXDATA = 1048576
        private val CONNECT_PAYLOAD = "host::features=shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir,apex,abb,abb_exec,sendrecv_v2,sendrecv_v2_brotli,sendrecv_v2_lz4,sendrecv_v2_zstd\u0000".toByteArray(Charsets.UTF_8)

        // 有线 USB 使用经典协议参数（兼容 Android 7-9 老设备，版本号 0x01000000）
        private const val USB_VERSION = 0x01000000
        private const val USB_MAXDATA = 4096
        private val USB_PAYLOAD = "host::aoooa101\u0000".toByteArray(Charsets.UTF_8)

        private const val AUTH_TIMEOUT_MS = 25000L // 预留充足时间供用户在被控端屏幕点击“允许”
        private const val SHELL_TIMEOUT_MS = 30000L
        private const val RETRY_INTERVAL_MS = 2500L
    }

    private val crypto = AdbCrypto(context)
    private val localIds = AtomicInteger(1)
    private val pendingPackets = LinkedBlockingQueue<AdbPacket>()

    // 交互式终端长连接会话状态
    @Volatile
    private var interactiveLocalId = 0
    @Volatile
    private var interactiveRemoteId = 0
    @Volatile
    private var isInteractiveActive = false
    private var interactiveOutputCallback: ((String) -> Unit)? = null

    @Volatile
    private var authenticated = false
    private var sentSignature = false
    private var sentPublicKey = false // 标记是否已发送公钥（等待用户在屏幕点击允许）

    private var recvBuf = ByteArray(0)

    fun onData(bytes: ByteArray) {
        synchronized(this) {
            val tmp = ByteArray(recvBuf.size + bytes.size)
            System.arraycopy(recvBuf, 0, tmp, 0, recvBuf.size)
            System.arraycopy(bytes, 0, tmp, recvBuf.size, bytes.size)
            recvBuf = tmp

            while (recvBuf.size >= 24) {
                val parsed = AdbPacket.tryParse(recvBuf)
                if (parsed != null) {
                    recvBuf = recvBuf.copyOfRange(parsed.second, recvBuf.size)
                    val cmdName = when (parsed.first.command) {
                        AdbPacket.OKAY -> "OKAY"
                        AdbPacket.WRTE -> "WRTE"
                        AdbPacket.CLSE -> "CLSE"
                        AdbPacket.CNXN -> "CNXN"
                        AdbPacket.AUTH -> "AUTH"
                        AdbPacket.STLS -> "STLS"
                        AdbPacket.OPEN -> "OPEN"
                        else -> "0x%08X".format(parsed.first.command)
                    }
                    onDebugLog("📥 收到报文: $cmdName (arg0=${parsed.first.arg0} arg1=${parsed.first.arg1} len=${parsed.first.payload.size}B)")

                    // 优先分发给交互式终端流（避免与单次指令互相干扰）
                    val currentIntLocalId = interactiveLocalId
                    if (currentIntLocalId > 0 && parsed.first.arg1 == currentIntLocalId) {
                        when (parsed.first.command) {
                            AdbPacket.OKAY -> {
                                interactiveRemoteId = parsed.first.arg0
                                if (!isInteractiveActive) {
                                    isInteractiveActive = true
                                    onDebugLog("✅ AOSP ShellProtocol v2 PTY 伪终端握手成功 (localId=$currentIntLocalId, remoteId=$interactiveRemoteId)")
                                    // 发送 WindowSizeChange (id=5) 设置终端尺寸为 24行80列（被控端 sh 启动会自动吐出唯一真实提示符）
                                    val winPayload = "24x80,0x0\u0000".toByteArray(Charsets.UTF_8)
                                    val winBb = ByteBuffer.allocate(5 + winPayload.size).order(ByteOrder.LITTLE_ENDIAN)
                                    winBb.put(5.toByte()) // kIdWindowSizeChange
                                    winBb.putInt(winPayload.size)
                                    winBb.put(winPayload)
                                    sendPacket(AdbPacket(AdbPacket.WRTE, currentIntLocalId, interactiveRemoteId, winBb.array()))
                                }
                            }
                            AdbPacket.WRTE -> {
                                interactiveRemoteId = parsed.first.arg0
                                val payload = parsed.first.payload
                                // AOSP 官方 ShellProtocol v2 规范解包:
                                // kIdStdout = 1, kIdStderr = 2, kIdExit = 3
                                if (payload.size >= 5) {
                                    val id = payload[0].toInt()
                                    val len = ByteBuffer.wrap(payload, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
                                    if (len in 0..payload.size - 5 && (id == 1 || id == 2)) {
                                        val text = String(payload, 5, len, Charsets.UTF_8)
                                        interactiveOutputCallback?.invoke(text)
                                    } else if (id == 3 && payload.size >= 6) {
                                        val exitCode = payload[5].toInt()
                                        onDebugLog("ℹ️ Shell 进程已退出 (exitCode=$exitCode)")
                                    } else {
                                        val text = String(payload, Charsets.UTF_8)
                                        interactiveOutputCallback?.invoke(text)
                                    }
                                } else if (payload.isNotEmpty()) {
                                    val text = String(payload, Charsets.UTF_8)
                                    interactiveOutputCallback?.invoke(text)
                                }
                                // 收到 PTY 输出后立即回送 OKAY 保证流控畅通
                                sendPacket(AdbPacket(AdbPacket.OKAY, currentIntLocalId, interactiveRemoteId))
                            }
                            AdbPacket.CLSE -> {
                                isInteractiveActive = false
                                interactiveRemoteId = 0
                                interactiveLocalId = 0
                                com.aoooa.webadb.AdbManager.isInteractiveActive.value = false
                                interactiveOutputCallback?.invoke("\n[终端会话已结束]\n")
                            }
                        }
                    } else {
                        pendingPackets.offer(parsed.first)
                    }
                } else {
                    val dv = java.nio.ByteBuffer.wrap(recvBuf).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    val command = dv.int
                    dv.int; dv.int; dv.int; dv.int
                    val magic = dv.int
                    if (magic != (command xor -1)) {
                        recvBuf = recvBuf.copyOfRange(1, recvBuf.size)
                    } else {
                        break
                    }
                }
            }
        }
    }

    private fun nextPacket(timeoutMs: Long): AdbPacket? =
        pendingPackets.poll(timeoutMs, TimeUnit.MILLISECONDS)

    val isAuthenticated: Boolean get() = authenticated

    private fun sendPacket(packet: AdbPacket) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            Thread {
                try {
                    channel.send(packet.toBytes())
                } catch (_: Exception) {}
            }.start()
        } else {
            channel.send(packet.toBytes())
        }
    }

    private fun doSendCnxn(retryCount: Int) {
        val isTcp = channel is TcpChannel
        val version = if (isTcp) CONNECT_VERSION else USB_VERSION
        val maxData = if (isTcp) CONNECT_MAXDATA else USB_MAXDATA
        val payload = if (isTcp) CONNECT_PAYLOAD else USB_PAYLOAD
        if (com.aoooa.webadb.native.WebAdbNative.isLoaded) {
            try {
                val nativeCnxn = com.aoooa.webadb.native.WebAdbNative.buildCnxnPacket(
                    version,
                    maxData,
                    if (isTcp) "host::aoooa101\u0000" else "host::aoooa101\u0000"
                )
                if (nativeCnxn.size >= 24) {
                    val hexDump = nativeCnxn.take(48).joinToString("") { "%02X".format(it) }
                    onDebugLog("CNXN (#$retryCount) hex: $hexDump (共${nativeCnxn.size}B via NDK Native C) [${if (isTcp) "TCP" else "USB"}]")
                    channel.send(nativeCnxn)
                    return
                }
            } catch (t: Throwable) {
                onDebugLog("Native CNXN 降级: ${t.message}")
            }
        }
        sendFallbackCnxn(retryCount, isTcp, version, maxData, payload)
    }

    private fun sendFallbackCnxn(retryCount: Int, isTcp: Boolean, version: Int, maxData: Int, payload: ByteArray) {
        val cnxnPkt = AdbPacket(AdbPacket.CNXN, version, maxData, payload)
        val raw = cnxnPkt.toBytes()
        val hexDump = raw.take(48).joinToString("") { "%02X".format(it) }
        onDebugLog("CNXN (#$retryCount) hex: $hexDump (共${raw.size}B Kotlin Fallback) [${if (isTcp) "TCP" else "USB"}]")
        sendPacket(cnxnPkt)
    }

    /** 同步读取一个完整 ADB 报文（仅 TcpChannel 可用），用于初始握手阶段。 */
    private fun readPacketSync(): AdbPacket? {
        val tcp = channel as? TcpChannel ?: return null
        val header = tcp.readDirect(24) ?: return null
        val dv = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = dv.int
        val arg0 = dv.int
        val arg1 = dv.int
        val len = dv.int
        dv.int // checksum
        val magic = dv.int
        if (magic != (command xor -1)) return null
        val payload = if (len > 0) tcp.readDirect(len) ?: return null else ByteArray(0)
        return AdbPacket(command, arg0, arg1, payload)
    }

    fun connect(): Boolean {
        if (authenticated) return true

        sentSignature = false
        sentPublicKey = false
        Thread.sleep(200)

        var sendCount = 1
        doSendCnxn(sendCount)

        // 首次报文同步读取，避免 TLS 前并发读线程问题
        val firstPkt = readPacketSync()
        if (firstPkt != null) {
            when (firstPkt.command) {
                AdbPacket.STLS -> {
                    onDebugLog("🔒 收到设备 STLS 请求 (ver=${firstPkt.arg0})，正在响应并升级 TLS 1.3 隧道...")
                    sendPacket(AdbPacket(AdbPacket.STLS, AdbPacket.STLS_VERSION, 0))
                    if (channel is TcpChannel) {
                        val ok = channel.upgradeToTls(crypto.getKeyManager(), onDebugLog)
                        if (!ok) {
                            onDebugLog("❌ TLS 升级失败")
                            return false
                        }
                        onDebugLog("🚀 TLS 1.3 隧道就绪，正在接收设备认证确认...")
                        channel.startReading()
                    } else {
                        onDebugLog("非 TCP 通道无法升级 TLS")
                        return false
                    }
                }
                AdbPacket.AUTH -> {
                    onDebugLog("收到 AUTH(TOKEN)，发送 RSA 签名...")
                    val sig = crypto.sign(firstPkt.payload)
                    sendPacket(AdbPacket(AdbPacket.AUTH, AdbPacket.AUTH_SIGNATURE, 0, sig))
                    sentSignature = true
                    if (channel is TcpChannel) channel.startReading()
                }
                AdbPacket.CNXN -> {
                    authenticated = true
                    onDebugLog("✅ 连接成功 (version=${firstPkt.arg0} maxPayload=${firstPkt.arg1})")
                    if (channel is TcpChannel) channel.startReading()
                    return true
                }
                else -> {
                    if (channel is TcpChannel) {
                        onData(firstPkt.toBytes())
                        channel.startReading()
                    } else {
                        return false
                    }
                }
            }
        } else {
            if (channel is TcpChannel) channel.startReading()
        }

        val deadline = System.currentTimeMillis() + AUTH_TIMEOUT_MS
        var lastSendTime = System.currentTimeMillis()
        sendCount = 1
        while (System.currentTimeMillis() < deadline) {
            val pkt = nextPacket(500)
            if (pkt == null) {
                if (!authenticated && !sentPublicKey && System.currentTimeMillis() - lastSendTime >= RETRY_INTERVAL_MS && sendCount < 4) {
                    sendCount++
                    onDebugLog("被控端未响应，自动重发 CNXN 握手请求 (#$sendCount)...")
                    doSendCnxn(sendCount)
                    lastSendTime = System.currentTimeMillis()
                }
                continue
            }

            when (pkt.command) {
                AdbPacket.CNXN -> {
                    authenticated = true
                    onDebugLog("✅ 连接成功 (version=${pkt.arg0} maxPayload=${pkt.arg1})")
                    return true
                }
                AdbPacket.STLS -> {
                    onDebugLog("🔒 收到设备 STLS 请求 (ver=${pkt.arg0})，正在响应并升级 TLS 1.3 隧道...")
                    sendPacket(AdbPacket(AdbPacket.STLS, AdbPacket.STLS_VERSION, 0))
                    if (channel is TcpChannel) {
                        val ok = channel.upgradeToTls(crypto.getKeyManager(), onDebugLog)
                        if (!ok) {
                            onDebugLog("❌ TLS 升级失败")
                            return false
                        }
                        onDebugLog("🚀 TLS 1.3 隧道就绪，正在接收设备认证确认...")
                        channel.startReading()
                    } else {
                        onDebugLog("非 TCP 通道无法升级 TLS")
                        return false
                    }
                }
                AdbPacket.AUTH -> {
                    if (pkt.arg0 == AdbPacket.AUTH_TOKEN) {
                        if (sentSignature) {
                            onLog(I18n.current.logAuthWaitScreen)
                            val pub = crypto.encodePublicKey()
                            val name = "webadb@aoooa101\u0000".toByteArray(Charsets.UTF_8)
                            val combined = ByteArray(pub.size + name.size + 1)
                            System.arraycopy(pub, 0, combined, 0, pub.size)
                            combined[pub.size] = 32 // ' '
                            System.arraycopy(name, 0, combined, pub.size + 1, name.size)
                            sendPacket(AdbPacket(AdbPacket.AUTH, AdbPacket.AUTH_PUBLICKEY, 0, combined))
                            sentPublicKey = true
                        } else {
                            onDebugLog("收到 AUTH(TOKEN)，发送 RSA 签名...")
                            val sig = crypto.sign(pkt.payload)
                            sendPacket(AdbPacket(AdbPacket.AUTH, AdbPacket.AUTH_SIGNATURE, 0, sig))
                            sentSignature = true
                            if (channel is TcpChannel) channel.startReading()
                        }
                    }
                }
            }
        }
        onLog(I18n.current.logAuthTimeout)
        return false
    }

    fun shell(command: String): String {
        onLog("> $command")
        return openService("shell:$command")
    }

    fun enableTcpip(port: Int = 5555): String = openService("tcpip:$port")

    fun disableTcpip(): String = openService("usb:")

    /**
     * AOSP 标准 sync: 协议流式推送文件到目标设备指定目录
     */
    fun pushFile(
        context: Context,
        uri: android.net.Uri,
        fileName: String,
        targetDir: String,
        onProgress: (percent: Float, sent: Long, total: Long) -> Unit
    ): Boolean {
        if (!authenticated) return false
        val cleanDir = if (targetDir.endsWith("/")) targetDir.dropLast(1) else targetDir
        val remotePath = "$cleanDir/$fileName,33206" // 0100666 标准文件权限
        val contentResolver = context.contentResolver

        val fileSize = try {
            contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        } catch (_: Exception) { -1L }

        val inputStream = try {
            contentResolver.openInputStream(uri) ?: return false
        } catch (_: Exception) { return false }

        pendingPackets.clear()
        val localId = localIds.getAndIncrement()
        var remoteId = 0

        // 1. 发起 sync: 服务流
        sendPacket(AdbPacket(AdbPacket.OPEN, localId, 0, "sync:\u0000".toByteArray(Charsets.UTF_8)))
        val openDeadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < openDeadline) {
            val pkt = nextPacket(500) ?: continue
            if (pkt.command == AdbPacket.OKAY && pkt.arg1 == localId) {
                remoteId = pkt.arg0
                break
            }
        }
        if (remoteId == 0) {
            inputStream.close()
            return false
        }

        try {
            // 2. 发送 SEND 报文头
            val pathBytes = remotePath.toByteArray(Charsets.UTF_8)
            val sendHeader = ByteBuffer.allocate(8 + pathBytes.size).order(ByteOrder.LITTLE_ENDIAN)
            sendHeader.put("SEND".toByteArray(Charsets.US_ASCII))
            sendHeader.putInt(pathBytes.size)
            sendHeader.put(pathBytes)
            sendPacket(AdbPacket(AdbPacket.WRTE, localId, remoteId, sendHeader.array()))

            // 3. 循环发送 DATA 分块（每块 64KB）
            val buffer = ByteArray(65536)
            var totalSent = 0L
            while (true) {
                val read = inputStream.read(buffer)
                if (read <= 0) break

                val dataHeader = ByteBuffer.allocate(8 + read).order(ByteOrder.LITTLE_ENDIAN)
                dataHeader.put("DATA".toByteArray(Charsets.US_ASCII))
                dataHeader.putInt(read)
                dataHeader.put(buffer, 0, read)
                sendPacket(AdbPacket(AdbPacket.WRTE, localId, remoteId, dataHeader.array()))

                totalSent += read
                val pct = if (fileSize > 0) (totalSent.toFloat() / fileSize.toFloat()) else 0f
                onProgress(pct, totalSent, fileSize)
            }

            // 4. 发送 DONE 报文
            val doneHeader = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            doneHeader.put("DONE".toByteArray(Charsets.US_ASCII))
            doneHeader.putInt((System.currentTimeMillis() / 1000).toInt())
            sendPacket(AdbPacket(AdbPacket.WRTE, localId, remoteId, doneHeader.array()))

            // 5. 接收确认并关闭流
            Thread.sleep(200)
            sendPacket(AdbPacket(AdbPacket.CLSE, localId, remoteId))
            return true
        } catch (e: Exception) {
            onDebugLog("pushFile 异常: ${e.message}")
            sendPacket(AdbPacket(AdbPacket.CLSE, localId, remoteId))
            return false
        } finally {
            try { inputStream.close() } catch (_: Exception) {}
        }
    }

    /**
     * AOSP 标准流式安装应用（免被控端留存安装包）
     * @param useCompatibleMode 是否启用兼容模式（老设备限速流控）
     */
    fun installStream(
        context: Context,
        uri: android.net.Uri,
        useCompatibleMode: Boolean = false,
        onProgress: (percent: Float) -> Unit
    ): String {
        if (!authenticated) return "未连接设备"
        val contentResolver = context.contentResolver
        val fileSize = try {
            contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        } catch (_: Exception) { -1L }
        if (fileSize <= 0) return "无法读取 APK 文件大小"

        val inputStream = try {
            contentResolver.openInputStream(uri) ?: return "无法打开 APK 输入流"
        } catch (e: Exception) { return "读取异常: ${e.message}" }

        try {
            // 1. 创建安装 Session
            var createOut = openService("exec:cmd package install-create -r -t -S $fileSize")
            if (createOut.isBlank() || !createOut.contains("[")) {
                createOut = openService("exec:pm install-create -r -t -S $fileSize")
            }
            val match = Regex("\\[(\\d+)\\]").find(createOut)
            val sessionId = match?.groupValues?.get(1)?.toIntOrNull()
                ?: return "创建安装会话失败: $createOut"

            // 2. 建立 install-write 流
            pendingPackets.clear()
            val localId = localIds.getAndIncrement()
            var remoteId = 0
            val writeCmd = "exec:cmd package install-write -S $fileSize $sessionId base.apk -\u0000"
            sendPacket(AdbPacket(AdbPacket.OPEN, localId, 0, writeCmd.toByteArray(Charsets.UTF_8)))

            val deadline = System.currentTimeMillis() + 6000
            while (System.currentTimeMillis() < deadline) {
                val pkt = nextPacket(500) ?: continue
                if (pkt.command == AdbPacket.OKAY && pkt.arg1 == localId) {
                    remoteId = pkt.arg0
                    break
                }
            }
            if (remoteId == 0) return "建立写入通道失败"

            // 3. 流式写入 APK 字节（兼容模式使用 8KB 小块与流控控速）
            val chunkSize = if (useCompatibleMode) 8192 else 32768
            val buf = ByteArray(chunkSize)
            var totalSent = 0L
            var blockCount = 0

            while (true) {
                val n = inputStream.read(buf)
                if (n <= 0) break
                val chunk = if (n == buf.size) buf else buf.copyOf(n)
                sendPacket(AdbPacket(AdbPacket.WRTE, localId, remoteId, chunk))
                totalSent += n
                blockCount++

                // 兼容模式流控：每 4 块消费一次返回的 OKAY 确认或进行微缓冲，彻底防止 Android 9 缓冲区溢出丢包
                if (useCompatibleMode && blockCount % 4 == 0) {
                    val ack = nextPacket(50)
                    if (ack != null && ack.command == AdbPacket.CLSE && ack.arg1 == localId) {
                        return "被控端提前关闭写入通道"
                    }
                    Thread.sleep(10)
                }

                onProgress(totalSent.toFloat() / fileSize.toFloat())
            }
            sendPacket(AdbPacket(AdbPacket.CLSE, localId, remoteId))
            Thread.sleep(if (useCompatibleMode) 600 else 300)

            // 4. 提交安装
            var commitOut = openService("exec:cmd package install-commit $sessionId")
            if (commitOut.isBlank()) {
                commitOut = openService("exec:pm install-commit $sessionId")
            }
            return if (commitOut.contains("Success", ignoreCase = true)) "Success [安装成功]" else commitOut
        } catch (e: Exception) {
            return "流式安装异常: ${e.message}"
        } finally {
            try { inputStream.close() } catch (_: Exception) {}
        }
    }

    private fun openService(service: String): String {
        if (!authenticated) return ""
        pendingPackets.clear()
        val localId = localIds.getAndIncrement()
        val sb = StringBuilder()
        var remoteId = 0

        val servicePayload = (service + "\u0000").toByteArray(Charsets.UTF_8)
        onDebugLog("发送 OPEN($service localId=$localId payload=${servicePayload.size}B)")
        sendPacket(AdbPacket(AdbPacket.OPEN, localId, 0, servicePayload))

        val deadline = System.currentTimeMillis() + SHELL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val pkt = nextPacket(1000) ?: continue
            val cmdName = when (pkt.command) {
                AdbPacket.OKAY -> "OKAY"
                AdbPacket.WRTE -> "WRTE"
                AdbPacket.CLSE -> "CLSE"
                AdbPacket.CNXN -> "CNXN"
                AdbPacket.AUTH -> "AUTH"
                AdbPacket.STLS -> "STLS"
                AdbPacket.OPEN -> "OPEN"
                else -> "0x%08X".format(pkt.command)
            }
            onDebugLog("${cmdName} 到达 (arg0=${pkt.arg0} arg1=${pkt.arg1} payload=${pkt.payload.size}B) localId=$localId")
            when (pkt.command) {
                AdbPacket.OKAY -> {
                    if (pkt.arg1 == localId) {
                        remoteId = pkt.arg0
                    }
                }
                AdbPacket.WRTE -> {
                    if (pkt.arg1 == localId) {
                        remoteId = pkt.arg0
                        sb.append(String(pkt.payload, Charsets.UTF_8))
                        sendPacket(AdbPacket(AdbPacket.OKAY, localId, remoteId))
                    }
                }
                AdbPacket.CLSE -> {
                    if (pkt.arg1 == localId) break
                }
            }
        }
        return sb.toString().trimEnd('\n')
    }

    /**
     * 开启 AOSP 标准交互式伪终端（PTY）长连接 Shell 会话
     * 发送 shell,v2,pty,TERM=xterm-256color: 触发被控端 forkpty() 分配 Linux 虚拟终端与真实 PS1 提示符
     */
    fun openInteractiveShell(onOutput: (String) -> Unit): Boolean {
        if (!authenticated) return false
        if (isInteractiveActive && interactiveLocalId > 0 && interactiveRemoteId > 0) return true

        interactiveOutputCallback = onOutput
        val localId = localIds.getAndIncrement()
        interactiveLocalId = localId
        interactiveRemoteId = 0
        isInteractiveActive = false

        val servicePayload = "shell,v2,pty,TERM=xterm-256color:\u0000".toByteArray(Charsets.UTF_8)
        onDebugLog("🚀 正在向设备请求 AOSP 标准 ShellProtocol v2 PTY 伪终端: OPEN(shell,v2,pty: localId=$localId)")
        sendPacket(AdbPacket(AdbPacket.OPEN, localId, 0, servicePayload))
        return true
    }

    /**
     * 向交互式 Shell 发送用户输入的按键或控制字符（严格按照 AOSP ShellProtocol v2 stdin 数据帧封装）
     */
    fun writeInteractiveInput(data: ByteArray): Boolean {
        val lId = interactiveLocalId
        val rId = interactiveRemoteId
        if (lId == 0 || rId == 0) return false
        try {
            // AOSP ShellProtocol v2 stdin 帧封装: [1字节 id=0] + [4字节长度 (小端)] + [payload]
            val bb = ByteBuffer.allocate(5 + data.size).order(ByteOrder.LITTLE_ENDIAN)
            bb.put(0.toByte()) // kIdStdin = 0
            bb.putInt(data.size)
            bb.put(data)
            sendPacket(AdbPacket(AdbPacket.WRTE, lId, rId, bb.array()))
            return true
        } catch (e: Exception) {
            onDebugLog("❌ writeInteractiveInput 异常: ${e.message}")
            return false
        }
    }

    /**
     * 动态同步终端窗口尺寸给远程 Shell（AOSP ShellProtocol v2 kIdWindowSizeChange = 5）
     */
    fun sendInteractiveWindowSize(rows: Int = 24, cols: Int = 80): Boolean {
        val lId = interactiveLocalId
        val rId = interactiveRemoteId
        if (lId == 0 || rId == 0) return false
        try {
            val winPayload = "${rows}x${cols},0x0\u0000".toByteArray(Charsets.UTF_8)
            val winBb = ByteBuffer.allocate(5 + winPayload.size).order(ByteOrder.LITTLE_ENDIAN)
            winBb.put(5.toByte()) // kIdWindowSizeChange = 5
            winBb.putInt(winPayload.size)
            winBb.put(winPayload)
            sendPacket(AdbPacket(AdbPacket.WRTE, lId, rId, winBb.array()))
            return true
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 关闭交互式 Shell 终端会话
     */
    fun closeInteractiveShell() {
        val lId = interactiveLocalId
        val rId = interactiveRemoteId
        if (lId > 0 && rId > 0) {
            try {
                sendPacket(AdbPacket(AdbPacket.CLSE, lId, rId))
            } catch (_: Exception) {}
        }
        isInteractiveActive = false
        interactiveLocalId = 0
        interactiveRemoteId = 0
        interactiveOutputCallback = null
        com.aoooa.webadb.AdbManager.isInteractiveActive.value = false
    }

    fun disconnect() {
        closeInteractiveShell()
        authenticated = false
        channel.close()
    }
}
