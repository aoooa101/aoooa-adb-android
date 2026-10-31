package com.aoooa.adb.adb

import android.content.Context
import com.aoooa.adb.adb.protocol.ShellProtocolV2
import com.aoooa.adb.adb.protocol.SyncProtocol
import com.aoooa.adb.adb.protocol.Utf8StreamCodec
import com.aoooa.adb.bridge.Channel
import com.aoooa.adb.bridge.TcpChannel
import com.aoooa.adb.ui.i18n.I18n
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ADB 连接层：负责认证握手（CNXN/AUTH/STLS）与 shell 会话（OPEN/WRTE/CLSE）。
 */
class AdbConnection(
    private val channel: Channel,
    context: Context? = null,
    internal val onLog: (String) -> Unit = {},
    internal val onDebugLog: (String) -> Unit = {}
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
        internal const val SHELL_TIMEOUT_MS = 30000L
        private const val RETRY_INTERVAL_MS = 2500L
        // 交互终端重组缓冲上限(防御异常数据导致内存膨胀)
        private const val INTERACTIVE_REASSEMBLY_LIMIT = 4 * 1024 * 1024
        // sync 协议单帧数据上限（SYNC_DATA_MAX，AOSP file_sync_protocol.h 定义 64KB）
        private const val SYNC_DATA_MAX = 65536
        // ADB 协议单包 payload 硬上限（AOSP adb.h 中 MAX_PAYLOAD = 1MB）
        private const val MAX_PAYLOAD_LIMIT = 1024 * 1024
    }

    private val crypto = AdbCrypto(context)
    internal val localIds = AtomicInteger(1)
    internal val pendingPackets = LinkedBlockingQueue<AdbPacket>()

    // 交互式终端长连接会话状态
    @Volatile
    private var interactiveLocalId = 0
    @Volatile
    private var interactiveRemoteId = 0
    @Volatile
    private var isInteractiveActive = false
    private var interactiveOutputCallback: ((String) -> Unit)? = null

    // 实时 Logcat 长连接流状态
    @Volatile
    private var logcatLocalId = 0
    @Volatile
    private var logcatRemoteId = 0
    @Volatile
    private var isLogcatActive = false
    private var logcatOutputCallback: ((String) -> Unit)? = null

    // logcat 流字节级累积缓冲:重组被 WRTE 包边界截断的 UTF-8 多字节序列
    private val logcatByteBuf = java.io.ByteArrayOutputStream()
    private val logcatByteLock = Any()

    // 交互终端流跨包重组缓冲:缓存不完整 SDU 帧头与帧数据
    private val interactiveByteBuf = java.io.ByteArrayOutputStream()
    // 交互终端 UTF-8 重组残余字节
    private val interactiveUtf8Carry = java.io.ByteArrayOutputStream()
    private val interactiveByteLock = Any()

    // 交互终端输出模式:true 表示被控端未封装 SDU 帧(纯文本流),按原始字节重组输出
    @Volatile
    private var isInteractiveRawMode = false

    /**
     * 通用二进制长连接流（控制模式 scrcpy 等）。
     * 仅做最小分发：不改写既有 shell / logcat / 认证逻辑。
     */
    private data class RawStream(
        val localId: Int,
        @Volatile var remoteId: Int = 0,
        @Volatile var active: Boolean = false,
        val onBytes: (ByteArray) -> Unit,
        val onClosed: (() -> Unit)?,
        val writeCredit: java.util.concurrent.Semaphore = java.util.concurrent.Semaphore(0)
    )
    private val rawStreams = java.util.concurrent.ConcurrentHashMap<Int, RawStream>()

    @Volatile
    private var authenticated = false
    private var sentSignature = false
    private var sentPublicKey = false // 标记是否已发送公钥（等待用户在屏幕点击允许）

    /** 协商后的单包 payload 上限：取本端声明与设备 CNXN 声明值的较小者，发送端据此切片。 */
    @Volatile
    private var negotiatedMaxPayload = MAX_PAYLOAD_LIMIT

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
                    onDebugLog("收到报文: $cmdName (arg0=${parsed.first.arg0} arg1=${parsed.first.arg1} len=${parsed.first.payload.size}B)")

                    // 优先分发给交互式终端 / Logcat / 通用二进制流（避免与单次指令互相干扰）
                    val currentIntLocalId = interactiveLocalId
                    val currentLogLocalId = logcatLocalId
                    val rawStream = rawStreams[parsed.first.arg1]
                    if (currentIntLocalId > 0 && parsed.first.arg1 == currentIntLocalId) {
                        when (parsed.first.command) {
                            AdbPacket.OKAY -> {
                                interactiveRemoteId = parsed.first.arg0
                                if (!isInteractiveActive) {
                                    isInteractiveActive = true
                                    onDebugLog("[PTY] AOSP ShellProtocol v2 伪终端握手成功 (localId=$currentIntLocalId, remoteId=$interactiveRemoteId)")
                                    // 发送 WindowSizeChange (id=5) 设置终端尺寸为 24行80列，触发 Shell 提示符回显
                                    sendPacket(
                                        AdbPacket(
                                            AdbPacket.WRTE,
                                            currentIntLocalId,
                                            interactiveRemoteId,
                                            ShellProtocolV2.buildWindowSize(24, 80)
                                        )
                                    )
                                }
                            }
                            AdbPacket.WRTE -> {
                                interactiveRemoteId = parsed.first.arg0
                                val payload = parsed.first.payload
                                var outputText: String? = null
                                var exitLog: String? = null
                                if (payload.isNotEmpty()) {
                                    // AOSP ShellProtocol v2 帧(kIdStdout=1/kIdStderr=2/kIdExit=3)跨包重组:
                                    // 帧头与帧数据可能被 WRTE 包边界拆分,统一累积后循环解帧,
                                    // 帧数据再经 UTF-8 重组器输出,避免多字节字符截断产生乱码
                                    synchronized(interactiveByteLock) {
                                        if (interactiveByteBuf.size() > INTERACTIVE_REASSEMBLY_LIMIT) {
                                            interactiveByteBuf.reset()
                                        }
                                        interactiveByteBuf.write(payload, 0, payload.size)
                                        val bytes = interactiveByteBuf.toByteArray()
                                        if (isInteractiveRawMode) {
                                            // 纯文本模式:上一包残留的多字节头与本包数据拼接后统一解码
                                            val carry = interactiveUtf8Carry.toByteArray()
                                            val merged = if (carry.isEmpty()) bytes else carry + bytes
                                            outputText = Utf8StreamCodec.decodeWithCarry(merged, interactiveUtf8Carry)
                                            interactiveByteBuf.reset()
                                        } else {
                                            var offset = 0
                                            while (bytes.size - offset >= 5) {
                                                val id = bytes[offset].toInt() and 0xFF
                                                val len = (bytes[offset + 1].toInt() and 0xFF) or
                                                    ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                                                    ((bytes[offset + 3].toInt() and 0xFF) shl 16) or
                                                    ((bytes[offset + 4].toInt() and 0xFF) shl 24)
                                                if ((id != 1 && id != 2 && id != 3) || len < 0) {
                                                    // 帧头非法:对端未封装 SDU,一次性降级为纯文本重组模式
                                                    isInteractiveRawMode = true
                                                    val carryBytes = interactiveUtf8Carry.toByteArray()
                                                    interactiveUtf8Carry.reset()
                                                    var text = outputText ?: ""
                                                    if (carryBytes.isNotEmpty()) {
                                                        text += String(carryBytes, Charsets.UTF_8)
                                                    }
                                                    if (offset < bytes.size) {
                                                        text += Utf8StreamCodec.decodeWithCarry(
                                                            bytes.copyOfRange(offset, bytes.size),
                                                            interactiveUtf8Carry
                                                        )
                                                    }
                                                    outputText = text
                                                    interactiveByteBuf.reset()
                                                    break
                                                }
                                                if (len > bytes.size - offset - 5) {
                                                    // 帧数据不完整:保留缓冲等待后续包补齐
                                                    break
                                                }
                                                when (id) {
                                                    3 -> {
                                                        val exitCode = if (len > 0) bytes[offset + 5].toInt() and 0xFF else 0
                                                        exitLog = "ℹ️ Shell 进程已退出 (exitCode=$exitCode)"
                                                    }
                                                    else -> if (len > 0) {
                                                        val frameData = bytes.copyOfRange(offset + 5, offset + 5 + len)
                                                        val text = Utf8StreamCodec.decodeWithCarry(frameData, interactiveUtf8Carry)
                                                        if (text.isNotEmpty()) {
                                                            outputText = (outputText ?: "") + text
                                                        }
                                                    }
                                                }
                                                offset += 5 + len
                                            }
                                            if (!isInteractiveRawMode) {
                                                interactiveByteBuf.reset()
                                                if (offset < bytes.size) {
                                                    interactiveByteBuf.write(bytes, offset, bytes.size - offset)
                                                }
                                            }
                                        }
                                    }
                                }
                                outputText?.let { interactiveOutputCallback?.invoke(it) }
                                exitLog?.let { onDebugLog(it) }
                                // 收到 PTY 输出后立即回送 OKAY 保证流控畅通
                                sendPacket(AdbPacket(AdbPacket.OKAY, currentIntLocalId, interactiveRemoteId))
                            }
                            AdbPacket.CLSE -> {
                                isInteractiveActive = false
                                interactiveRemoteId = 0
                                interactiveLocalId = 0
                                com.aoooa.adb.AdbManager.isInteractiveActive.value = false
                                interactiveOutputCallback?.invoke("\n[终端会话已结束]\n")
                                synchronized(interactiveByteLock) {
                                    interactiveByteBuf.reset()
                                    interactiveUtf8Carry.reset()
                                }
                                isInteractiveRawMode = false
                            }
                        }
                    } else if (currentLogLocalId > 0 && parsed.first.arg1 == currentLogLocalId) {
                        when (parsed.first.command) {
                            AdbPacket.OKAY -> {
                                logcatRemoteId = parsed.first.arg0
                                isLogcatActive = true
                            }
                            AdbPacket.WRTE -> {
                                logcatRemoteId = parsed.first.arg0
                                val payload = parsed.first.payload
                                if (payload.isNotEmpty()) {
                                    // 字节级累积后仅解码完整 UTF-8 前缀,半截多字节字符留待下包重组,避免解码出 U+FFFD 乱码
                                    var text: String? = null
                                    synchronized(logcatByteLock) {
                                        logcatByteBuf.write(payload, 0, payload.size)
                                        val bytes = logcatByteBuf.toByteArray()
                                        val validLen = Utf8StreamCodec.completePrefixLength(bytes)
                                        if (validLen > 0) {
                                            text = String(bytes, 0, validLen, Charsets.UTF_8)
                                            logcatByteBuf.reset()
                                            if (validLen < bytes.size) {
                                                logcatByteBuf.write(bytes, validLen, bytes.size - validLen)
                                            }
                                        }
                                    }
                                    text?.let { logcatOutputCallback?.invoke(it) }
                                }
                                sendPacket(AdbPacket(AdbPacket.OKAY, currentLogLocalId, logcatRemoteId))
                            }
                            AdbPacket.CLSE -> {
                                isLogcatActive = false
                                logcatRemoteId = 0
                                logcatLocalId = 0
                            }
                        }
                    } else if (rawStream != null) {
                        when (parsed.first.command) {
                            AdbPacket.OKAY -> {
                                rawStream.remoteId = parsed.first.arg0
                                rawStream.active = true
                                if (rawStream.writeCredit.availablePermits() == 0) rawStream.writeCredit.release()
                            }
                            AdbPacket.WRTE -> {
                                rawStream.remoteId = parsed.first.arg0
                                val payload = parsed.first.payload
                                if (payload.isNotEmpty()) {
                                    try {
                                        rawStream.onBytes(payload)
                                    } catch (t: Throwable) {
                                        onDebugLog("[RawStream] onBytes 异常 localId=${rawStream.localId}: ${t.message}")
                                    }
                                }
                                sendPacket(AdbPacket(AdbPacket.OKAY, rawStream.localId, rawStream.remoteId))
                            }
                            AdbPacket.CLSE -> {
                                rawStreams.remove(rawStream.localId)
                                rawStream.active = false
                                if (rawStream.writeCredit.availablePermits() == 0) rawStream.writeCredit.release()
                                try {
                                    rawStream.onClosed?.invoke()
                                } catch (_: Exception) {
                                }
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

    internal fun nextPacket(timeoutMs: Long): AdbPacket? =
        pendingPackets.poll(timeoutMs, TimeUnit.MILLISECONDS)

    /**
     * 依据设备 CNXN 返回的 arg1 计算实际可用 payload 上限。
     * AOSP update_version() 取 min(对端声明, MAX_PAYLOAD)，本端同样取较小值，
     * 否则超出设备上限的 WRTE 会被 check_header() 判定为坏包并断开连接。
     */
    private fun applyNegotiated(deviceMaxPayload: Int) {
        val mine = if (channel is TcpChannel) CONNECT_MAXDATA else USB_MAXDATA
        val peer = if (deviceMaxPayload > 0) deviceMaxPayload else mine
        negotiatedMaxPayload = minOf(mine, peer).coerceIn(64, MAX_PAYLOAD_LIMIT)
        onDebugLog("payload 协商结果: $negotiatedMaxPayload B (本端=$mine, 设备=$peer)")
    }

    /** 单次 sync DATA 帧可携带的数据字节数（8 字节帧头占用 payload 余量）。 */
    internal fun syncChunkSize(): Int =
        (negotiatedMaxPayload - 8).coerceIn(1, SYNC_DATA_MAX)

    /** 单次原始 WRTE 可携带的数据字节数（无附加帧头）。 */
    internal fun rawChunkSize(requested: Int): Int =
        minOf(requested, negotiatedMaxPayload).coerceAtLeast(1)

    val isAuthenticated: Boolean get() = authenticated

    @Synchronized
    internal fun sendPacket(packet: AdbPacket) {
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
        if (com.aoooa.adb.native.AdbNative.isLoaded) {
            try {
                val nativeCnxn = com.aoooa.adb.native.AdbNative.buildCnxnPacket(
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
        // 对端声明的长度必须先校验再分配，避免异常对端触发超大内存分配
        if (len < 0 || len > MAX_PAYLOAD_LIMIT) return null
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
                    onDebugLog("[TLS] 收到设备 STLS 请求 (ver=${firstPkt.arg0})，正在响应并升级 TLS 1.3 隧道...")
                    sendPacket(AdbPacket(AdbPacket.STLS, AdbPacket.STLS_VERSION, 0))
                    if (channel is TcpChannel) {
                        val ok = channel.upgradeToTls(crypto.getKeyManager(), onDebugLog)
                        if (!ok) {
                            onDebugLog("[TLS] 升级失败")
                            return false
                        }
                        onDebugLog("[TLS] 隧道就绪，正在接收设备认证确认...")
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
                    applyNegotiated(firstPkt.arg1)
                    onDebugLog("连接成功 (version=${firstPkt.arg0} maxPayload=${firstPkt.arg1})")
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
                    applyNegotiated(pkt.arg1)
                    onDebugLog("连接成功 (version=${pkt.arg0} maxPayload=${pkt.arg1})")
                    return true
                }
                AdbPacket.STLS -> {
                    onDebugLog("[TLS] 收到设备 STLS 请求 (ver=${pkt.arg0})，正在响应并升级 TLS 1.3 隧道...")
                    sendPacket(AdbPacket(AdbPacket.STLS, AdbPacket.STLS_VERSION, 0))
                    if (channel is TcpChannel) {
                        val ok = channel.upgradeToTls(crypto.getKeyManager(), onDebugLog)
                        if (!ok) {
                            onDebugLog("[TLS] 升级失败")
                            return false
                        }
                        onDebugLog("[TLS] 隧道就绪，正在接收设备认证确认...")
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
                            // AUTH(RSAPUBLICKEY) 载荷必须是 NUL 结尾的 "Base64(524B) 空格 user@host" 字符串，
                            // adbd 侧按 C 字符串解析，直接拼接 524 字节二进制会在首个 0x00 处被截断
                            val combined = (crypto.getAdbPublicKeyString() + "\u0000").toByteArray(Charsets.UTF_8)
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





    /**
     * 开启 AOSP 标准交互式伪终端（PTY）长连接 Shell 会话
     * 发送 shell,v2,pty,TERM=xterm-256color: 触发被控端 forkpty() 分配 Linux 虚拟终端与真实 PS1 提示符
     */
    fun openInteractiveShell(onOutput: (String) -> Unit): Boolean {
        if (!authenticated) return false
        if (isInteractiveActive && interactiveLocalId > 0 && interactiveRemoteId > 0) return true

        interactiveOutputCallback = onOutput
        synchronized(interactiveByteLock) {
            interactiveByteBuf.reset()
            interactiveUtf8Carry.reset()
        }
        isInteractiveRawMode = false
        val localId = localIds.getAndIncrement()
        interactiveLocalId = localId
        interactiveRemoteId = 0
        isInteractiveActive = false

        val servicePayload = "shell,v2,pty,TERM=xterm-256color:\u0000".toByteArray(Charsets.UTF_8)
        onDebugLog("[PTY] 正在向设备请求 AOSP 标准 ShellProtocol v2 伪终端: OPEN(shell,v2,pty: localId=$localId)")
        sendPacket(AdbPacket(AdbPacket.OPEN, localId, 0, servicePayload))
        return true
    }

    /**
     * 向交互式 Shell 发送标准 stdin 数据帧
     */
    fun writeInteractiveInput(data: ByteArray): Boolean {
        val lId = interactiveLocalId
        val rId = interactiveRemoteId
        if (lId == 0 || rId == 0) return false
        try {
            sendPacket(AdbPacket(AdbPacket.WRTE, lId, rId, ShellProtocolV2.buildStdin(data)))
            return true
        } catch (e: Exception) {
            onDebugLog("[PTY] writeInteractiveInput 异常: ${e.message}")
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
            sendPacket(
                AdbPacket(AdbPacket.WRTE, lId, rId, ShellProtocolV2.buildWindowSize(rows, cols))
            )
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
        synchronized(interactiveByteLock) {
            interactiveByteBuf.reset()
            interactiveUtf8Carry.reset()
        }
        isInteractiveRawMode = false
        com.aoooa.adb.AdbManager.isInteractiveActive.value = false
    }

    /**
     * 开启实时 Logcat 异步流式长连接会话
     */
    fun openLogcatStream(args: String = "-v time", onOutput: (String) -> Unit): AutoCloseable? {
        if (!authenticated) return null
        closeLogcatStream()

        logcatOutputCallback = onOutput
        synchronized(logcatByteLock) { logcatByteBuf.reset() }
        val localId = localIds.getAndIncrement()
        logcatLocalId = localId
        logcatRemoteId = 0
        isLogcatActive = false

        val cmd = "shell:logcat $args\u0000"
        onDebugLog("[Logcat] 正在开启流式日志通道: OPEN($cmd localId=$localId)")
        sendPacket(AdbPacket(AdbPacket.OPEN, localId, 0, cmd.toByteArray(Charsets.UTF_8)))

        return AutoCloseable {
            closeLogcatStream()
        }
    }

    /**
     * 关闭实时 Logcat 日志流
     */
    fun closeLogcatStream() {
        val lId = logcatLocalId
        val rId = logcatRemoteId
        if (lId > 0 && rId > 0) {
            try {
                sendPacket(AdbPacket(AdbPacket.CLSE, lId, rId))
            } catch (_: Exception) {}
        }
        isLogcatActive = false
        logcatLocalId = 0
        logcatRemoteId = 0
        logcatOutputCallback = null
        synchronized(logcatByteLock) { logcatByteBuf.reset() }
    }

    /**
     * 打开通用二进制长连接（如 localabstract:scrcpy_xxxx）。
     * @return localId；失败返回 0
     */
    fun openRawStream(
        service: String,
        onBytes: (ByteArray) -> Unit,
        onClosed: (() -> Unit)? = null
    ): Int {
        if (!authenticated) {
            onDebugLog("[RawStream] OPEN 拒绝：未认证 ($service)")
            return 0
        }
        val localId = localIds.getAndIncrement()
        rawStreams[localId] = RawStream(localId, onBytes = onBytes, onClosed = onClosed)
        val payload = if (service.endsWith("\u0000")) {
            service.toByteArray(Charsets.UTF_8)
        } else {
            (service + "\u0000").toByteArray(Charsets.UTF_8)
        }
        onDebugLog("[RawStream] OPEN($service localId=$localId)")
        sendPacket(AdbPacket(AdbPacket.OPEN, localId, 0, payload))
        return localId
    }

    /**
     * 带重试的二进制流打开：用于 localabstract 尚未就绪的短窗口。
     * @return localId；失败返回 0
     */
    fun openRawStreamWithRetry(
        service: String,
        onBytes: (ByteArray) -> Unit,
        onClosed: (() -> Unit)? = null,
        attempts: Int = 8,
        perAttemptTimeoutMs: Long = 1500L,
        gapMs: Long = 250L
    ): Int {
        var lastId = 0
        repeat(attempts.coerceAtLeast(1)) { idx ->
            if (!authenticated) {
                onDebugLog("[RawStream] retry 中止：未认证")
                return 0
            }
            lastId = openRawStream(service, onBytes, onClosed)
            if (lastId == 0) return 0
            if (awaitRawStreamReady(lastId, perAttemptTimeoutMs)) {
                onDebugLog("[RawStream] retry 成功 attempt=${idx + 1} localId=$lastId")
                return lastId
            }
            onDebugLog("[RawStream] attempt=${idx + 1} 未就绪，关闭后重试 ($service)")
            closeRawStream(lastId)
            lastId = 0
            try {
                Thread.sleep(gapMs)
            } catch (_: InterruptedException) {
            }
        }
        return 0
    }

    /** 等待 raw stream 收到首个 OKAY（拿到 remoteId） */
    fun awaitRawStreamReady(localId: Int, timeoutMs: Long = 8000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val s = rawStreams[localId] ?: return false
            if (s.active && s.remoteId != 0) return true
            Thread.sleep(30)
        }
        return rawStreams[localId]?.let { it.active && it.remoteId != 0 } == true
    }

    fun writeRawStream(localId: Int, data: ByteArray): Boolean {
        val s = rawStreams[localId] ?: return false
        val rId = s.remoteId
        if (!s.active || rId == 0 || data.isEmpty()) return false
        return try {
            sendPacket(AdbPacket(AdbPacket.WRTE, localId, rId, data))
            true
        } catch (e: Exception) {
            onDebugLog("[RawStream] write 失败 localId=$localId: ${e.message}")
            false
        }
    }

    /** 文件通道发送前等待 ADB OKAY，避免超出经典流控窗口。 */
    fun writeRawStreamAwait(localId: Int, data: ByteArray): Boolean {
        val stream = rawStreams[localId] ?: return false
        if (!stream.writeCredit.tryAcquire(30, TimeUnit.SECONDS)) return false
        return writeRawStream(localId, data)
    }

    fun closeRawStream(localId: Int) {
        val s = rawStreams.remove(localId) ?: return
        if (s.localId > 0 && s.remoteId > 0) {
            try {
                sendPacket(AdbPacket(AdbPacket.CLSE, s.localId, s.remoteId))
            } catch (_: Exception) {
            }
        }
        s.active = false
        if (s.writeCredit.availablePermits() == 0) s.writeCredit.release()
        try {
            s.onClosed?.invoke()
        } catch (_: Exception) {
        }
    }

    fun closeAllRawStreams() {
        val ids = rawStreams.keys.toList()
        for (id in ids) closeRawStream(id)
    }

    /**
     * 将内存字节通过 sync 协议推到远端路径（供 scrcpy-server 从 assets 下发）。
     * 不改变既有 Uri pushFile 逻辑。
     */
    fun pushBytes(
        data: ByteArray,
        remotePath: String,
        mode: Int = 33188,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean {
        if (!authenticated) return false
        if (data.isEmpty()) return false
        val remote = if (remotePath.contains(",")) remotePath else "$remotePath,$mode"

        pendingPackets.clear()
        val localId = localIds.getAndIncrement()
        var remoteId = 0

        sendPacket(AdbPacket(AdbPacket.OPEN, localId, 0, SyncProtocol.servicePayload))
        val openDeadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < openDeadline) {
            val pkt = nextPacket(500) ?: continue
            if (pkt.command == AdbPacket.OKAY && pkt.arg1 == localId) {
                remoteId = pkt.arg0
                break
            }
        }
        if (remoteId == 0) return false

        try {
            sendPacket(AdbPacket(AdbPacket.WRTE, localId, remoteId, SyncProtocol.sendRequest(remote)))

            var offset = 0
            val chunk = syncChunkSize()
            while (offset < data.size) {
                val n = minOf(chunk, data.size - offset)
                sendPacket(AdbPacket(AdbPacket.WRTE, localId, remoteId, SyncProtocol.dataChunk(data, offset, n)))
                offset += n
                onProgress?.invoke(offset.toFloat() / data.size.toFloat())
            }

            sendPacket(AdbPacket(AdbPacket.WRTE, localId, remoteId, SyncProtocol.done()))
            val confirmed = awaitSyncDone(localId, remoteId)
            sendPacket(AdbPacket(AdbPacket.CLSE, localId, remoteId))
            return confirmed
        } catch (e: Exception) {
            onDebugLog("pushBytes 异常: ${e.message}")
            try {
                sendPacket(AdbPacket(AdbPacket.CLSE, localId, remoteId))
            } catch (_: Exception) {
            }
            return false
        }
    }

    fun disconnect() {
        closeAllRawStreams()
        closeLogcatStream()
        closeInteractiveShell()
        authenticated = false
        channel.close()
    }
}
