package com.aoooa.webadb.adb

import android.content.Context
import com.aoooa.webadb.bridge.Channel
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ADB 连接层：负责认证握手（CNXN/AUTH）与 shell 会话（OPEN/WRTE/CLSE）。
 */
class AdbConnection(
    private val channel: Channel,
    context: Context? = null,
    private val onLog: (String) -> Unit = {}
) {
    companion object {
        // 全版本兼容 BANNER (Android 7 - 14)
        private const val BANNER = "host::features=shell_v2,cmd,stat_v2,list_v2,fixed_push_mkdir,apex,abb,abb_exec,remount_shell,track_app,sendrecv_v2"
        private const val AUTH_TIMEOUT_MS = 15000L
        private const val SHELL_TIMEOUT_MS = 30000L
    }

    private val crypto = AdbCrypto(context)
    private val localIds = AtomicInteger(1)
    private val pendingPackets = LinkedBlockingQueue<AdbPacket>()

    @Volatile
    private var authenticated = false

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

    fun connect(): Boolean {
        if (authenticated) return true

        Thread.sleep(300)

        if (com.aoooa.webadb.native.WebAdbNative.isLoaded) {
            try {
                val nativeCnxn = com.aoooa.webadb.native.WebAdbNative.buildCnxnPacket(
                    AdbPacket.VERSION,
                    AdbPacket.MAX_PAYLOAD,
                    BANNER
                )
                if (nativeCnxn.size > 24) {
                    val hexDump = nativeCnxn.take(48).joinToString("") { "%02X".format(it) }
                    onLog("Native C CNXN hex: $hexDump (共${nativeCnxn.size}B)")
                    channel.send(nativeCnxn.copyOfRange(0, 24))
                    channel.send(nativeCnxn.copyOfRange(24, nativeCnxn.size))
                    onLog("CNXN 已发送 (via NDK Native C)")
                }
            } catch (t: Throwable) {
                onLog("Native CNXN 降级: ${t.message}")
                sendFallbackCnxn()
            }
        } else {
            sendFallbackCnxn()
        }

        val deadline = System.currentTimeMillis() + AUTH_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val pkt = nextPacket(500) ?: continue
            when (pkt.command) {
                AdbPacket.CNXN -> {
                    authenticated = true
                    onLog("连接成功 (version=${pkt.arg0} maxPayload=${pkt.arg1})")
                    return true
                }
                AdbPacket.AUTH -> when (pkt.arg0) {
                    AdbPacket.AUTH_TOKEN -> {
                        onLog("收到 AUTH(TOKEN)，发送签名...")
                        val sig = crypto.sign(pkt.payload)
                        sendPacket(AdbPacket(AdbPacket.AUTH, AdbPacket.AUTH_SIGNATURE, 0, sig))
                    }
                    AdbPacket.AUTH_PUBLICKEY -> {
                        onLog("设备请求公钥，发送 AUTH(RSAPUBLICKEY)...")
                        val pub = crypto.encodePublicKey()
                        val name = "webadb@android".toByteArray(Charsets.UTF_8)
                        val combined = ByteArray(pub.size + name.size + 1)
                        System.arraycopy(pub, 0, combined, 0, pub.size)
                        combined[pub.size] = 32 // ' '
                        System.arraycopy(name, 0, combined, pub.size + 1, name.size)
                        sendPacket(AdbPacket(AdbPacket.AUTH, AdbPacket.AUTH_PUBLICKEY, 0, combined))
                    }
                }
            }
        }
        onLog("认证超时")
        return false
    }

    private fun sendFallbackCnxn() {
        val banner = BANNER.toByteArray(Charsets.UTF_8)
        val cnxnPkt = AdbPacket(AdbPacket.CNXN, AdbPacket.VERSION, AdbPacket.MAX_PAYLOAD, banner)
        val raw = cnxnPkt.toBytes()
        val hexDump = raw.take(48).joinToString("") { "%02X".format(it) }
        onLog("CNXN hex: $hexDump (共${raw.size}B)")
        sendPacket(cnxnPkt)
        onLog("CNXN 已发送 (Kotlin Fallback)")
    }

    fun shell(command: String): String {
        onLog("> $command")
        return openService("shell:$command")
    }

    fun enableTcpip(port: Int = 5555): String = openService("tcpip:$port")

    fun disableTcpip(): String = openService("usb:")

    private fun openService(service: String): String {
        if (!authenticated) return ""
        val localId = localIds.getAndIncrement()
        val sb = StringBuilder()

        sendPacket(AdbPacket(AdbPacket.OPEN, localId, 0, service.toByteArray(Charsets.UTF_8)))

        val deadline = System.currentTimeMillis() + SHELL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val pkt = nextPacket(1000) ?: continue
            when (pkt.command) {
                AdbPacket.WRTE -> {
                    if (pkt.arg0 == localId) {
                        sb.append(String(pkt.payload, Charsets.UTF_8))
                        sendPacket(AdbPacket(AdbPacket.OKAY, pkt.arg0, pkt.arg1))
                    }
                }
                AdbPacket.CLSE -> {
                    if (pkt.arg0 == localId || pkt.arg1 == localId) break
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
