package com.aoooa.webadb.adb

import android.content.Context
import com.aoooa.webadb.bridge.Channel
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ADB 连接层：负责认证握手（CNXN/AUTH）与 shell 会话（OPEN/WRTE/CLSE）。
 * 严格按照 AOSP 标准规范实现。
 */
class AdbConnection(
    private val channel: Channel,
    context: Context? = null,
    private val onLog: (String) -> Unit = {}
) {
    companion object {
        // AOSP 标准兼容参数
        private const val CONNECT_VERSION = 0x01000000
        private const val CONNECT_MAXDATA = 4096
        private val CONNECT_PAYLOAD = byteArrayOf('h'.code.toByte(), 'o'.code.toByte(), 's'.code.toByte(), 't'.code.toByte(), ':'.code.toByte(), ':'.code.toByte(), 0)

        private const val AUTH_TIMEOUT_MS = 15000L
        private const val SHELL_TIMEOUT_MS = 30000L
        private const val RETRY_INTERVAL_MS = 2500L
    }

    private val crypto = AdbCrypto(context)
    private val localIds = AtomicInteger(1)
    private val pendingPackets = LinkedBlockingQueue<AdbPacket>()

    @Volatile
    private var authenticated = false
    private var sentSignature = false

    private var recvBuf = ByteArray(0)

    /** 传输层 onData 回调：追加字节流并尝试解析完整包 (带滑动窗口对齐防堵死) */
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
        val bytes = packet.toBytes()
        if (bytes.size > 24) {
            channel.send(bytes.copyOfRange(0, 24))
            channel.send(bytes.copyOfRange(24, bytes.size))
        } else {
            channel.send(bytes)
        }
    }

    private fun doSendCnxn(retryCount: Int) {
        if (com.aoooa.webadb.native.WebAdbNative.isLoaded) {
            try {
                val nativeCnxn = com.aoooa.webadb.native.WebAdbNative.buildCnxnPacket(
                    CONNECT_VERSION,
                    CONNECT_MAXDATA,
                    "host::\u0000"
                )
                if (nativeCnxn.size >= 24) {
                    val hexDump = nativeCnxn.take(48).joinToString("") { "%02X".format(it) }
                    onLog("CNXN (#$retryCount) hex: $hexDump (共${nativeCnxn.size}B via NDK Native C)")
                    channel.send(nativeCnxn.copyOfRange(0, 24))
                    if (nativeCnxn.size > 24) {
                        channel.send(nativeCnxn.copyOfRange(24, nativeCnxn.size))
                    }
                    return
                }
            } catch (t: Throwable) {
                onLog("Native CNXN 降级: ${t.message}")
            }
        }
        sendFallbackCnxn(retryCount)
    }

    private fun sendFallbackCnxn(retryCount: Int) {
        val cnxnPkt = AdbPacket(AdbPacket.CNXN, CONNECT_VERSION, CONNECT_MAXDATA, CONNECT_PAYLOAD)
        val raw = cnxnPkt.toBytes()
        val hexDump = raw.take(48).joinToString("") { "%02X".format(it) }
        onLog("CNXN (#$retryCount) hex: $hexDump (共${raw.size}B Kotlin Fallback)")
        sendPacket(cnxnPkt)
    }

    fun connect(): Boolean {
        if (authenticated) return true

        sentSignature = false
        Thread.sleep(200)

        var sendCount = 1
        doSendCnxn(sendCount)
        var lastSendTime = System.currentTimeMillis()

        val deadline = System.currentTimeMillis() + AUTH_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val pkt = nextPacket(500)
            if (pkt == null) {
                if (!authenticated && System.currentTimeMillis() - lastSendTime >= RETRY_INTERVAL_MS && sendCount < 4) {
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
                    onLog("连接成功 (version=${pkt.arg0} maxPayload=${pkt.arg1})")
                    return true
                }
                AdbPacket.AUTH -> {
                    if (pkt.arg0 == AdbPacket.AUTH_TOKEN) {
                        if (sentSignature) {
                            onLog("签名未直接通过，发送 AUTH(RSAPUBLICKEY) 触发授权弹窗...")
                            val pub = crypto.encodePublicKey()
                            val name = "webadb@android".toByteArray(Charsets.UTF_8)
                            val combined = ByteArray(pub.size + name.size + 1)
                            System.arraycopy(pub, 0, combined, 0, pub.size)
                            combined[pub.size] = 32 // ' '
                            System.arraycopy(name, 0, combined, pub.size + 1, name.size)
                            sendPacket(AdbPacket(AdbPacket.AUTH, AdbPacket.AUTH_PUBLICKEY, 0, combined))
                        } else {
                            onLog("收到 AUTH(TOKEN)，发送 RSA 签名...")
                            val sig = crypto.sign(pkt.payload)
                            sendPacket(AdbPacket(AdbPacket.AUTH, AdbPacket.AUTH_SIGNATURE, 0, sig))
                            sentSignature = true
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

    /** 通用 ADB 服务命令：OPEN(service\0) -> OKAY -> WRTE(stdout) -> 回 OKAY -> CLSE */
    private fun openService(service: String): String {
        if (!authenticated) return ""
        val localId = localIds.getAndIncrement()
        val sb = StringBuilder()
        var remoteId = 0

        // AOSP 规定: OPEN payload 必须以 \0 结尾
        val servicePayload = (service + "\u0000").toByteArray(Charsets.UTF_8)
        sendPacket(AdbPacket(AdbPacket.OPEN, localId, 0, servicePayload))

        val deadline = System.currentTimeMillis() + SHELL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val pkt = nextPacket(1000) ?: continue
            when (pkt.command) {
                AdbPacket.OKAY -> {
                    // OKAY(remoteId, localId)
                    if (pkt.arg1 == localId) {
                        remoteId = pkt.arg0
                    }
                }
                AdbPacket.WRTE -> {
                    // WRTE(remoteId, localId, data)
                    if (pkt.arg1 == localId) {
                        remoteId = pkt.arg0
                        sb.append(String(pkt.payload, Charsets.UTF_8))
                        // 确认回复 OKAY(localId, remoteId)
                        sendPacket(AdbPacket(AdbPacket.OKAY, localId, remoteId))
                    }
                }
                AdbPacket.CLSE -> {
                    // CLSE(remoteId, localId)
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
