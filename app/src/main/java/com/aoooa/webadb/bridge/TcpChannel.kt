package com.aoooa.webadb.bridge

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.conscrypt.Conscrypt

/**
 * 无线（TCP）通道：直连设备 adbd 的 5555 端口或 Android 11+ TLS 动态端口。
 * 支持标准 TCP 传输与 STLS 协商后的 TLS 1.3 双向安全升级。
 *
 * 关键设计：所有初始握手（CNXN/STLS/AUTH）通过同步 [readDirect] 完成，
 * 握手成功后由调用方调用 [startReading] 启动单一线程的异步读循环，
 * 彻底避免 TLS 升级前旧线程吞噬加密记录的问题。
 */
class TcpChannel(
    private val onData: (ByteArray) -> Unit,
    private val onStatus: (String) -> Unit = {}
) : Channel {

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var readThread: Thread? = null

    @Volatile
    private var running = false
    private var currentHost: String = ""
    private var currentPort: Int = 0

    /** 同步连接（调用方应在子线程执行）。不启动读循环。 */
    fun connect(host: String, port: Int): Boolean {
        return try {
            currentHost = host
            currentPort = port
            val sock = Socket()
            sock.connect(InetSocketAddress(host, port), 8000)
            sock.tcpNoDelay = true
            socket = sock
            input = sock.getInputStream()
            output = sock.getOutputStream()
            true
        } catch (e: Exception) {
            onStatus("tcp_connect_error: $host:$port -> ${e.javaClass.simpleName}: ${e.message}")
            close()
            false
        }
    }

    /**
     * 同步读取确切的 [len] 字节（用于初始握手阶段）。
     * 超时由底层 Socket SO_TIMEOUT 控制。
     */
    fun readDirect(len: Int): ByteArray? {
        return try {
            val buf = ByteArray(len)
            var offset = 0
            while (offset < len) {
                val n = input?.read(buf, offset, len - offset) ?: -1
                if (n < 0) return null
                offset += n
            }
            buf
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 升级现有 TCP 连接至 TLS 1.3 加密隧道（响应 Android 11+ STLS 握手）。
     * 不启动读循环，由调用方后续调用 [startReading]。
     */
    fun upgradeToTls(keyManager: KeyManager, onLog: (String) -> Unit = {}): Boolean {
        val rawSocket = socket ?: return false
        return try {
            onLog("正在初始化 TLS 1.3 双向认证上下文...")
            val sslContext = SSLContext.getInstance("TLSv1.3", Conscrypt.newProvider())
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            sslContext.init(arrayOf(keyManager), trustAll, SecureRandom())

            onLog("正在向 $currentHost:$currentPort 发起 TLS 1.3 握手...")
            val sslSocket = sslContext.socketFactory.createSocket(
                rawSocket,
                currentHost,
                currentPort,
                true
            ) as SSLSocket

            sslSocket.enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")
            sslSocket.useClientMode = true
            sslSocket.startHandshake()

            socket = sslSocket
            input = sslSocket.inputStream
            output = sslSocket.outputStream
            onLog("✅ TLS 1.3 安全通道建立完成 (Cipher: ${sslSocket.session.cipherSuite})")
            true
        } catch (e: Exception) {
            onLog("TLS 升级失败: ${e.message}")
            close()
            false
        }
    }

    /** 启动单一线程的异步读循环（仅在握手完全就绪后调用一次）。 */
    fun startReading() {
        if (readThread != null) return
        running = true
        readThread = Thread {
            val buffer = ByteArray(65536)
            var readCount = 0
            while (running) {
                try {
                    val inStream = input ?: break
                    val n = inStream.read(buffer)
                    if (n <= 0) break
                    readCount++
                    if (readCount <= 20) onStatus("tcp_read #$readCount: $n 字节")
                    onData(buffer.copyOf(n))
                } catch (e: Exception) {
                    break
                }
            }
            running = false
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    override fun send(data: ByteArray): Boolean {
        return try {
            output?.write(data)
            output?.flush()
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun close() {
        running = false
        readThread?.interrupt()
        readThread = null
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        input = null
        output = null
    }
}
