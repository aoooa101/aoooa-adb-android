package com.aoooa.webadb.adb

import android.content.Context
import com.aoooa.webadb.bridge.Channel
import com.aoooa.webadb.bridge.TcpChannel
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
        private const val CONNECT_VERSION = 0x01000001
        private const val CONNECT_MAXDATA = 1048576
        private val CONNECT_PAYLOAD = "host::features=shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir,apex,abb,abb_exec,sendrecv_v2,sendrecv_v2_brotli,sendrecv_v2_lz4,sendrecv_v2_zstd\u0000".toByteArray(Charsets.UTF_8)

        private const val AUTH_TIMEOUT_MS = 25000L // 预留充足时间供用户在被控端屏幕点击“允许”
        private const val SHELL_TIMEOUT_MS = 30000L
        private const val RETRY_INTERVAL_MS = 2500L
    }

    private val crypto = AdbCrypto(context)
    private val localIds = AtomicInteger(1)
    private val pendingPackets = LinkedBlockingQueue<AdbPacket>()

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
                    pendingPackets.offer(parsed.first)
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
        channel.send(packet.toBytes())
    }

    private fun doSendCnxn(retryCount: Int) {
        if (com.aoooa.webadb.native.WebAdbNative.isLoaded) {
            try {
                val nativeCnxn = com.aoooa.webadb.native.WebAdbNative.buildCnxnPacket(
                    CONNECT_VERSION,
                    CONNECT_MAXDATA,
                    "host::aoooa101\u0000"
                )
                if (nativeCnxn.size >= 24) {
                    val hexDump = nativeCnxn.take(48).joinToString("") { "%02X".format(it) }
                    onDebugLog("CNXN (#$retryCount) hex: $hexDump (共${nativeCnxn.size}B via NDK Native C)")
                    channel.send(nativeCnxn)
                    return
                }
            } catch (t: Throwable) {
                onDebugLog("Native CNXN 降级: ${t.message}")
            }
        }
        sendFallbackCnxn(retryCount)
    }

    private fun sendFallbackCnxn(retryCount: Int) {
        val cnxnPkt = AdbPacket(AdbPacket.CNXN, CONNECT_VERSION, CONNECT_MAXDATA, CONNECT_PAYLOAD)
        val raw = cnxnPkt.toBytes()
        val hexDump = raw.take(48).joinToString("") { "%02X".format(it) }
        onDebugLog("CNXN (#$retryCount) hex: $hexDump (共${raw.size}B Kotlin Fallback)")
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
                    onLog("🔒 收到设备 STLS 请求 (ver=${firstPkt.arg0})，正在响应并升级 TLS 1.3 隧道...")
                    sendPacket(AdbPacket(AdbPacket.STLS, AdbPacket.STLS_VERSION, 0))
                    if (channel is TcpChannel) {
                        val ok = channel.upgradeToTls(crypto.getKeyManager(), onLog)
                        if (!ok) {
                            onLog("❌ TLS 升级失败")
                            return false
                        }
                        onLog("🚀 TLS 1.3 隧道就绪，正在接收设备认证确认...")
                        channel.startReading()
                    } else {
                        onLog("非 TCP 通道无法升级 TLS")
                        return false
                    }
                }
                AdbPacket.AUTH -> {
                    onLog("收到 AUTH(TOKEN)，发送 RSA 签名...")
                    val sig = crypto.sign(firstPkt.payload)
                    sendPacket(AdbPacket(AdbPacket.AUTH, AdbPacket.AUTH_SIGNATURE, 0, sig))
                    sentSignature = true
                    if (channel is TcpChannel) channel.startReading()
                }
                AdbPacket.CNXN -> {
                    authenticated = true
                    onLog("✅ 连接成功 (version=${firstPkt.arg0} maxPayload=${firstPkt.arg1})")
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
                // 关键修复：一旦发送了公钥进入等待用户授权阶段，停止重发 CNXN，防止重置被控端屏幕弹窗
                if (!authenticated && !sentPublicKey && System.currentTimeMillis() - lastSendTime >= RETRY_INTERVAL_MS && sendCount < 4) {
                    sendCount++
                    onLog("被控端未响应，自动重发 CNXN 握手请求 (#$sendCount)...")
                    doSendCnxn(sendCount)
                    lastSendTime = System.currentTimeMillis()
                }
                continue
            }

            when (pkt.command) {
                AdbPacket.CNXN -> {
                    authenticated = true
                    onLog("✅ 连接成功 (version=${pkt.arg0} maxPayload=${pkt.arg1})")
                    return true
                }
                AdbPacket.STLS -> {
                    onLog("🔒 收到设备 STLS 请求 (ver=${pkt.arg0})，正在响应并升级 TLS 1.3 隧道...")
                    // 响应 STLS 包
                    sendPacket(AdbPacket(AdbPacket.STLS, AdbPacket.STLS_VERSION, 0))
                    if (channel is TcpChannel) {
                        val ok = channel.upgradeToTls(crypto.getKeyManager(), onLog)
                        if (!ok) {
                            onLog("❌ TLS 升级失败")
                            return false
                        }
                        onLog("🚀 TLS 1.3 隧道就绪，正在接收设备认证确认...")
                        channel.startReading()
                    } else {
                        onLog("非 TCP 通道无法升级 TLS")
                        return false
                    }
                }
                AdbPacket.AUTH -> {
                    if (pkt.arg0 == AdbPacket.AUTH_TOKEN) {
                        if (sentSignature) {
                            onLog("签名未直接通过，发送 AUTH(RSAPUBLICKEY) 请在被控端屏幕点击允许...")
                            val pub = crypto.encodePublicKey()
                            // Android 7-9 规范：末尾必须带 \0 结束符
                            val name = "webadb@aoooa101\u0000".toByteArray(Charsets.UTF_8)
                            val combined = ByteArray(pub.size + name.size + 1)
                            System.arraycopy(pub, 0, combined, 0, pub.size)
                            combined[pub.size] = 32 // ' '
                            System.arraycopy(name, 0, combined, pub.size + 1, name.size)
                            sendPacket(AdbPacket(AdbPacket.AUTH, AdbPacket.AUTH_PUBLICKEY, 0, combined))
                            sentPublicKey = true // 标记已发公钥，等待用户点击允许
                        } else {
                            onLog("收到 AUTH(TOKEN)，发送 RSA 签名...")
                            val sig = crypto.sign(pkt.payload)
                            sendPacket(AdbPacket(AdbPacket.AUTH, AdbPacket.AUTH_SIGNATURE, 0, sig))
                            sentSignature = true
                            if (channel is TcpChannel) channel.startReading()
                        }
                    }
                }
            }
        }
        onLog("认证超时")
        return false
    }

    fun shell(command: String): String {
        onLog("> $command")
        return openService("shell:$command")
    }

    fun enableTcpip(port: Int = 5555): String = openService("tcpip:$port")

    fun disableTcpip(): String = openService("usb:")

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

    fun disconnect() {
        authenticated = false
        channel.close()
    }
}
