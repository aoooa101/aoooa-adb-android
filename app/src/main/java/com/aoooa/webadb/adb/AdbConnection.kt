package com.aoooa.webadb.adb

import com.aoooa.webadb.bridge.Channel
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ADB 连接层：负责认证握手（CNXN/AUTH）与 shell 会话（OPEN/WRTE/CLSE）。
 *
 * 读方向：传输层（UsbChannel/TcpChannel）构造时把 onData 回调指向本类的 onData，
 * 字节流在这里按 24B 头解析成 AdbPacket 并放入队列，由认证/命令循环消费。
 */
class AdbConnection(
    private val channel: Channel,
    private val onLog: (String) -> Unit = {}
) {
    companion object {
        private const val BANNER = "AdbWebadb/2.0"
        private const val AUTH_TIMEOUT_MS = 15000L
        private const val SHELL_TIMEOUT_MS = 30000L
    }

    private val crypto = AdbCrypto()
    private val localIds = AtomicInteger(1)
    private val pendingPackets = LinkedBlockingQueue<AdbPacket>()

    @Volatile
    private var authenticated = false

    private var recvBuf = ByteArray(0)

    /** 传输层 onData 回调：追加字节流并尝试解析完整包 */
    fun onData(bytes: ByteArray) {
        val tmp = ByteArray(recvBuf.size + bytes.size)
        System.arraycopy(recvBuf, 0, tmp, 0, recvBuf.size)
        System.arraycopy(bytes, 0, tmp, recvBuf.size, bytes.size)
        recvBuf = tmp
        while (true) {
            val parsed = AdbPacket.tryParse(recvBuf) ?: break
            recvBuf = recvBuf.copyOfRange(parsed.second, recvBuf.size)
            pendingPackets.offer(parsed.first)
        }
    }

    private fun nextPacket(timeoutMs: Long): AdbPacket? =
        pendingPackets.poll(timeoutMs, TimeUnit.MILLISECONDS)

    val isAuthenticated: Boolean get() = authenticated

    /**
     * 认证握手：
     *   CNXN → AUTH(TOKEN) → AUTH(SIGNATURE) →（若请求）AUTH(RSAPUBLICKEY) → CNXN
     * @return 是否认证成功
     */
    fun connect(): Boolean {
        if (authenticated) return true

        val banner = BANNER.toByteArray(Charsets.UTF_8)
        channel.send(AdbPacket(AdbPacket.CNXN, AdbPacket.VERSION, AdbPacket.MAX_PAYLOAD, banner).toBytes())
        onLog("CNXN 已发送")

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
                        channel.send(AdbPacket(AdbPacket.AUTH, AdbPacket.AUTH_SIGNATURE, 0, sig).toBytes())
                    }
                    AdbPacket.AUTH_PUBLICKEY -> {
                        onLog("设备请求公钥，发送 AUTH(RSAPUBLICKEY)...")
                        val pub = crypto.encodePublicKey()
                        val name = "webadb@android".toByteArray(Charsets.UTF_8)
                        val combined = ByteArray(pub.size + name.size + 1)
                        System.arraycopy(pub, 0, combined, 0, pub.size)
                        combined[pub.size] = 32 // ' '
                        System.arraycopy(name, 0, combined, pub.size + 1, name.size)
                        channel.send(AdbPacket(AdbPacket.AUTH, AdbPacket.AUTH_PUBLICKEY, 0, combined).toBytes())
                    }
                }
            }
        }
        onLog("认证超时")
        return false
    }

    /**
     * 执行 shell 命令并返回标准输出文本。
     * 内部流程：OPEN(shell:cmd) → OKAY → WRTE(stdout) → 回 OKAY → CLSE
     */
    fun shell(command: String): String {
        if (!authenticated) return ""
        val localId = localIds.getAndIncrement()
        val service = "shell:$command"
        val sb = StringBuilder()

        onLog("> $command")
        channel.send(AdbPacket(AdbPacket.OPEN, localId, 0, service.toByteArray(Charsets.UTF_8)).toBytes())

        val deadline = System.currentTimeMillis() + SHELL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val pkt = nextPacket(1000) ?: continue
            when (pkt.command) {
                AdbPacket.WRTE -> {
                    if (pkt.arg0 == localId) {
                        sb.append(String(pkt.payload, Charsets.UTF_8))
                        // 回 OKAY 确认，否则设备停止发送
                        channel.send(AdbPacket(AdbPacket.OKAY, pkt.arg0, pkt.arg1).toBytes())
                    }
                }
                AdbPacket.CLSE -> {
                    if (pkt.arg0 == localId || pkt.arg1 == localId) break
                }
                // OKAY：会话建立确认，无需处理
            }
        }
        return sb.toString().trimEnd('\n')
    }

    /** 断开连接 */
    fun disconnect() {
        authenticated = false
        channel.close()
    }
}
