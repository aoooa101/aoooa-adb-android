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

    private val receiveLock = Any()
    private val sendLock = Any()
    private val disconnected = java.util.concurrent.atomic.AtomicBoolean(false)
    private val controlWrites = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, java.util.concurrent.ArrayBlockingQueue<Runnable>(256),
        { task -> Thread(task, "adb-control-writer").apply { isDaemon = true } },
        java.util.concurrent.ThreadPoolExecutor.AbortPolicy()
    )

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
    private var interactiveInitialization: String? = null
    private var interactiveWindowPending = false

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
    private val interactiveStderrCarry = java.io.ByteArrayOutputStream()
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
        val onReady: (() -> Unit)? = null,
        val autoAcknowledge: Boolean = true,
        val writeCredit: java.util.concurrent.Semaphore = java.util.concurrent.Semaphore(0)
    )
    private val rawStreams = java.util.concurrent.ConcurrentHashMap<Int, RawStream>()
    private val streamWriters = java.util.concurrent.ConcurrentHashMap<Int, AdbStreamWriter>()
    private val streamRemoteIds = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    @Volatile
    private var authenticated = false
    private var sentSignature = false
    private var sentPublicKey = false // 标记是否已发送公钥（等待用户在屏幕点击允许）

    /** 协商后的单包 payload 上限：取本端声明与设备 CNXN 声明值的较小者，发送端据此切片。 */
    @Volatile
    private var negotiatedMaxPayload = MAX_PAYLOAD_LIMIT

    private var recvBuf = ByteArray(0)
    private var trafficPackets = 0L
    private var trafficBytes = 0L
    private var trafficSummaryAtNs = System.nanoTime()

    fun onData(bytes: ByteArray) {
        synchronized(receiveLock) {
            if (disconnected.get()) return
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
                    if (parsed.first.command == AdbPacket.WRTE || parsed.first.command == AdbPacket.OKAY) {
                        trafficPackets++
                        trafficBytes += parsed.first.payload.size
                        val now = System.nanoTime()
                        if (now - trafficSummaryAtNs >= 5_000_000_000L) {
                            onDebugLog("[ADB traffic] packets=$trafficPackets payloadBytes=$trafficBytes")
                            trafficPackets = 0L
                            trafficBytes = 0L
                            trafficSummaryAtNs = now
                        }
                    } else {
                        onDebugLog("收到报文: $cmdName (arg0=${parsed.first.arg0} arg1=${parsed.first.arg1} len=${parsed.first.payload.size}B)")
                    }

                    if (parsed.first.command == AdbPacket.OKAY) {
                        streamWriters[parsed.first.arg1]?.let { writer ->
                            streamRemoteIds[parsed.first.arg1] = parsed.first.arg0
                            writer.ready()
                        }
                    } else if (parsed.first.command == AdbPacket.CLSE) {
                        streamWriters.remove(parsed.first.arg1)?.close()
                        streamRemoteIds.remove(parsed.first.arg1)
                    }

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
                                    val initialization = interactiveInitialization
                                    interactiveInitialization = null
                                    if (initialization != null) {
                                        interactiveWindowPending = true
                                        writeInteractiveInput(initialization.toByteArray(Charsets.UTF_8))
                                    }
                                    onDebugLog("[PTY] AOSP ShellProtocol v2 伪终端握手成功 (localId=$currentIntLocalId, remoteId=$interactiveRemoteId)")
                                    // 未发送初始化帧时设置初始窗口尺寸，避免连续发送未确认的 WRTE。
                                    if (initialization == null) {
                                        sendPacket(
                                            AdbPacket(
                                                AdbPacket.WRTE,
                                                currentIntLocalId,
                                                interactiveRemoteId,
                                                ShellProtocolV2.buildWindowSize(24, 80)
                                            )
                                        )
                                    }
                                } else if (interactiveWindowPending) {
                                    interactiveWindowPending = false
                                    sendInteractiveWindowSize()
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
                                            outputText = Utf8StreamCodec.decodeChunk(bytes, interactiveUtf8Carry)
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
                                                    outputText = (outputText ?: "") + Utf8StreamCodec.decodeChunk(
                                                        bytes.copyOfRange(offset, bytes.size), interactiveUtf8Carry
                                                    )
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
                                                        val carry = if (id == 1) interactiveUtf8Carry else interactiveStderrCarry
                                                        val text = Utf8StreamCodec.decodeChunk(frameData, carry)
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
                                interactiveInitialization = null
                                interactiveWindowPending = false
                                interactiveRemoteId = 0
                                interactiveLocalId = 0
                                com.aoooa.adb.AdbManager.isInteractiveActive.value = false
                                interactiveOutputCallback?.invoke("\n[终端会话已结束]\n")
                                synchronized(interactiveByteLock) {
                                    interactiveByteBuf.reset()
                                    interactiveUtf8Carry.reset()
                                    interactiveStderrCarry.reset()
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
                                val firstReady = !rawStream.active
                                rawStream.remoteId = parsed.first.arg0
                                rawStream.active = true
                                if (rawStream.writeCredit.availablePermits() == 0) rawStream.writeCredit.release()
                                if (firstReady) rawStream.onReady?.invoke()
                            }
                            AdbPacket.WRTE -> {
                                rawStream.remoteId = parsed.first.arg0
                                val payload = parsed.first.payload
                                if (payload.isNotEmpty() || !rawStream.autoAcknowledge) {
                                    try {
                                        rawStream.onBytes(payload)
                                    } catch (t: Throwable) {
                                        onDebugLog("[RawStream] onBytes 异常 localId=${rawStream.localId}: ${t.message}")
                                    }
                                }
                                if (rawStream.autoAcknowledge) {
                                    sendPacket(AdbPacket(AdbPacket.OKAY, rawStream.localId, rawStream.remoteId))
                                }
                            }
                            AdbPacket.CLSE -> {
                                if (rawStreams.remove(rawStream.localId, rawStream)) {
                                    val wasActive = rawStream.active
                                    rawStream.active = false
                                    if (rawStream.writeCredit.availablePermits() == 0) rawStream.writeCredit.release()
                                    try {
                                        if (wasActive && !disconnected.get()) {
                                            sendPacket(AdbPacket(AdbPacket.CLSE, rawStream.localId, parsed.first.arg0))
                                        }
                                    } finally {
                                        try {
                                            rawStream.onClosed?.invoke()
                                        } catch (e: Exception) {
                                            onDebugLog("[RawStream] onClosed 回调异常 localId=${rawStream.localId}: ${e.javaClass.simpleName}: ${e.message}")
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        val packet = parsed.first
                        when (packet.command) {
                            AdbPacket.CNXN, AdbPacket.AUTH, AdbPacket.STLS -> pendingPackets.offer(packet)
                            AdbPacket.OKAY -> {
                                if (!streamWriters.containsKey(packet.arg1) && packet.arg0 != 0 && packet.arg1 != 0) {
                                    sendPacket(AdbPacket(AdbPacket.CLSE, packet.arg1, packet.arg0))
                                }
                            }
                        }
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
        val mine = if (channel is TcpChannel) InitialCnxn.TCP.maxData else InitialCnxn.USB.maxData
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
    internal val isUsbTransport: Boolean get() = channel !is TcpChannel
    @Volatile
    internal var remoteFeatures: Set<String> = emptySet()
        private set

    private fun readRemoteFeatures(payload: ByteArray) {
        val banner = String(payload, Charsets.UTF_8).trimEnd('\u0000')
        remoteFeatures = banner.substringAfter("::", "").split(';')
            .firstOrNull { it.startsWith("features=") }?.removePrefix("features=")
            ?.split(',')?.filter { it.isNotEmpty() }?.toSet().orEmpty()
    }

    internal fun sendPacket(packet: AdbPacket) {
        check(!disconnected.get()) { "ADB connection is closed" }
        val mainThread = android.os.Looper.getMainLooper()?.let { it === android.os.Looper.myLooper() } == true
        if (packet.command == AdbPacket.OPEN && !rawStreams.containsKey(packet.arg0)) {
            val id = packet.arg0
            streamWriters.put(id, AdbStreamWriter(
                write = { data ->
                    val remote = streamRemoteIds[id]
                    remote != null && sendPacketChecked(AdbPacket(AdbPacket.WRTE, id, remote, data))
                },
                onFailure = {
                    onDebugLog("[ADB stream] send_failed localId=$id")
                    val remote = streamRemoteIds.remove(id) ?: 0
                    streamWriters.remove(id)?.close()
                    if (remote != 0) sendPacketChecked(AdbPacket(AdbPacket.CLSE, id, remote))
                    // 发送失败已经关闭本流，不向认证队列投递服务关闭事件。
                }
            ))?.close()
        }
        if (packet.command == AdbPacket.WRTE && !rawStreams.containsKey(packet.arg0)) {
            val writer = streamWriters[packet.arg0] ?: error("ADB stream is closed")
            var offset = 0
            do {
                val end = minOf(offset + negotiatedMaxPayload, packet.payload.size)
                val future = writer.send(packet.payload.copyOfRange(offset, end))
                if (!mainThread && !Thread.holdsLock(receiveLock)) {
                    try {
                        check(future.get(31, TimeUnit.SECONDS)) { "ADB stream write failed" }
                    } catch (e: Exception) {
                        streamWriters.remove(packet.arg0)?.close()
                        streamRemoteIds.remove(packet.arg0)
                        if (e is InterruptedException) Thread.currentThread().interrupt()
                        throw e
                    }
                }
                offset = end
            } while (offset < packet.payload.size)
            return
        }
        if (packet.command == AdbPacket.CLSE) {
            streamWriters.remove(packet.arg0)?.close()
            streamRemoteIds.remove(packet.arg0)
        }
        if (mainThread || Thread.holdsLock(receiveLock)) {
            val copy = AdbPacket(packet.command, packet.arg0, packet.arg1, packet.payload.copyOf())
            try {
                controlWrites.execute {
                    if (!sendPacketChecked(copy) && !disconnected.get()) disconnect()
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                onDebugLog("[ADB send] control_queue_rejected")
                disconnect()
            }
        } else {
            check(sendPacketChecked(packet)) { "ADB channel write failed" }
        }
    }

    /** 发送锁仅保护完整报文写入，不与收包缓冲共享监视器。 */
    private fun sendPacketChecked(packet: AdbPacket): Boolean = synchronized(sendLock) {
        if (disconnected.get()) return@synchronized false
        try {
            channel.send(packet.toBytes()).also { sent ->
                if (!sent) onDebugLog("[ADB send] channel_failed command=${packet.command} localId=${packet.arg0} bytes=${packet.payload.size}")
            }
        } catch (e: Exception) {
            onDebugLog("[ADB send] exception command=${packet.command} localId=${packet.arg0} type=${e.javaClass.simpleName}")
            false
        }
    }

    private fun doSendCnxn(retryCount: Int) {
        val profile = if (channel is TcpChannel) InitialCnxn.TCP else InitialCnxn.USB
        val payload = profile.payload()
        val native = if (com.aoooa.adb.native.AdbNative.isLoaded) {
            try {
                com.aoooa.adb.native.AdbNative.buildCnxnPacket(profile.version, profile.maxData, payload)
            } catch (e: LinkageError) {
                onDebugLog("Native CNXN unavailable: ${e.javaClass.simpleName}")
                null
            }
        } else null
        val raw = native ?: profile.kotlinPacket(payload)
        val hex = raw.take(48).joinToString("") { "%02X".format(it) }
        onDebugLog("CNXN (#$retryCount) hex: $hex (${raw.size}B via ${if (native == null) "Kotlin" else "NDK Native C"}) [$profile]")
        synchronized(sendLock) {
            check(!disconnected.get() && channel.send(raw)) { "ADB CNXN write failed" }
        }
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
        if (disconnected.get()) return false
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
                    readRemoteFeatures(firstPkt.payload)
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
                    readRemoteFeatures(pkt.payload)
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
    fun openInteractiveShell(initialization: String? = null, onOutput: (String) -> Unit): Boolean {
        if (!authenticated) return false
        if (isInteractiveActive && interactiveLocalId > 0 && interactiveRemoteId > 0) return true

        interactiveInitialization = initialization
        interactiveWindowPending = false
        interactiveOutputCallback = onOutput
        synchronized(interactiveByteLock) {
            interactiveByteBuf.reset()
            interactiveUtf8Carry.reset()
            interactiveStderrCarry.reset()
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
        if (!disconnected.get() && lId > 0 && rId > 0) {
            try {
                sendPacket(AdbPacket(AdbPacket.CLSE, lId, rId))
            } catch (e: Exception) {
                onDebugLog("交互式终端关闭报文发送异常: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        isInteractiveActive = false
        interactiveLocalId = 0
        interactiveRemoteId = 0
        interactiveOutputCallback = null
        interactiveInitialization = null
        interactiveWindowPending = false
        synchronized(interactiveByteLock) {
            interactiveByteBuf.reset()
            interactiveUtf8Carry.reset()
            interactiveStderrCarry.reset()
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
        if (!disconnected.get() && lId > 0 && rId > 0) {
            try {
                sendPacket(AdbPacket(AdbPacket.CLSE, lId, rId))
            } catch (e: Exception) {
                onDebugLog("日志流关闭报文发送异常: ${e.javaClass.simpleName}: ${e.message}")
            }
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
        onClosed: (() -> Unit)? = null,
        onReady: (() -> Unit)? = null,
        autoAcknowledge: Boolean = true
    ): Int {
        if (!authenticated) {
            onDebugLog("[RawStream] OPEN 拒绝：未认证 ($service)")
            return 0
        }
        val localId = localIds.getAndIncrement()
        rawStreams[localId] = RawStream(localId, onBytes = onBytes, onClosed = onClosed,
            onReady = onReady, autoAcknowledge = autoAcknowledge)
        val payload = if (service.endsWith("\u0000")) {
            service.toByteArray(Charsets.UTF_8)
        } else {
            (service + "\u0000").toByteArray(Charsets.UTF_8)
        }
        onDebugLog("[RawStream] OPEN($service localId=$localId)")
        try {
            check(!disconnected.get()) { "ADB connection is closed" }
            sendPacket(AdbPacket(AdbPacket.OPEN, localId, 0, payload))
            return localId
        } catch (error: Exception) {
            closeRawStream(localId)
            throw error
        }
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

    /** 手动流确认仅用于消费完数据后推进远端发送。 */
    internal fun acknowledgeRawStream(localId: Int) {
        val stream = rawStreams[localId] ?: return
        if (stream.active && !stream.autoAcknowledge) {
            sendPacket(AdbPacket(AdbPacket.OKAY, localId, stream.remoteId))
        }
    }

    /** 非阻塞写入；没有设备确认额度时返回 false。 */
    fun writeRawStream(localId: Int, data: ByteArray): Boolean {
        val stream = rawStreams[localId] ?: return false
        if (data.isEmpty() || !stream.active || !stream.writeCredit.tryAcquire()) return false
        return writeRawStreamCredited(stream, data)
    }

    /** 在后台线程等待设备 OKAY；关闭流会唤醒等待者。 */
    fun writeRawStreamAwait(localId: Int, data: ByteArray, timeoutMs: Long = 30_000L, slowAfterMs: Long = 0L): Boolean {
        val stream = rawStreams[localId] ?: return false
        if (data.isEmpty() || !stream.active) return false
        if (Thread.holdsLock(receiveLock) ||
            android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
        ) {
            onDebugLog("[RawStream] write_rejected reason=callback_or_main_thread localId=$localId")
            return false
        }
        val startedAt = System.nanoTime()
        var slow = false
        try {
            val acquired = WriteCreditWait.await(timeoutMs, slowAfterMs,
                acquire = { stream.writeCredit.tryAcquire(it, TimeUnit.NANOSECONDS) },
                onSlow = {
                    slow = true
                    onDebugLog("[RawStream] credit_wait_slow localId=$localId elapsedMs=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)} timeoutMs=$timeoutMs")
                })
            if (!acquired) {
                onDebugLog("[RawStream] write_timeout localId=$localId timeoutMs=$timeoutMs bytes=${data.size}")
                closeRawStream(localId)
                return false
            }
            if (slow) {
                val state = if (stream.active && rawStreams[localId] === stream) "ready" else "closed"
                onDebugLog("[RawStream] credit_wait_end localId=$localId state=$state elapsedMs=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)}")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            onDebugLog("[RawStream] write_interrupted localId=$localId")
            return false
        }
        return writeRawStreamCredited(stream, data)
    }

    private fun writeRawStreamCredited(stream: RawStream, data: ByteArray): Boolean {
        if (!stream.active || stream.remoteId == 0 || rawStreams[stream.localId] !== stream) {
            return false
        }
        if (data.size > negotiatedMaxPayload) {
            onDebugLog("[RawStream] write_rejected reason=payload_limit localId=${stream.localId} bytes=${data.size} limit=$negotiatedMaxPayload")
            closeRawStream(stream.localId)
            return false
        }
        val sent = sendPacketChecked(AdbPacket(AdbPacket.WRTE, stream.localId, stream.remoteId, data))
        if (!sent) closeRawStream(stream.localId)
        return sent
    }

    fun closeRawStream(localId: Int) {
        val s = rawStreams.remove(localId) ?: return
        if (!disconnected.get() && s.localId > 0 && s.remoteId > 0) {
            try {
                sendPacket(AdbPacket(AdbPacket.CLSE, s.localId, s.remoteId))
            } catch (e: Exception) {
                onDebugLog("原始流关闭报文发送异常 localId=${s.localId}: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        s.active = false
        if (s.writeCredit.availablePermits() == 0) s.writeCredit.release()
        try {
            s.onClosed?.invoke()
        } catch (e: Exception) {
            onDebugLog("原始流 onClosed 回调异常 localId=${s.localId}: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun closeAllRawStreams() {
        // 直接迭代并发视图，不依据瞬时 size 创建单元素列表。
        for (id in rawStreams.keys) closeRawStream(id)
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

        return data.inputStream().use { input ->
            pushSyncInput(input, remote, data.size.toLong()) { percent, _, _ ->
                onProgress?.invoke(percent)
            }
        }
    }

    fun disconnect() {
        if (!disconnected.compareAndSet(false, true)) return
        authenticated = false
        try {
            channel.close()
        } catch (e: Exception) {
            onDebugLog("[ADB close] channel_exception type=${e.javaClass.simpleName}")
        }
        controlWrites.shutdownNow()
        streamWriters.values.forEach { it.close() }
        streamWriters.clear()
        streamRemoteIds.clear()
        closeAllRawStreams()
        closeLogcatStream()
        closeInteractiveShell()
    }
}
