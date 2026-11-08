package com.aoooa.adb.bridge

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
 * 关键设计：初始握手（CNXN/STLS/AUTH）通过同步 [readDirect] 完成，
 * 握手成功后由调用方调用 [startReading] 启动异步读循环。
 */
class TcpChannel(
    private val onData: (ByteArray) -> Unit,
    private val onStatus: (String) -> Unit = {},
    /**
     * 对端关闭或读写异常时的回调，参数为断开原因。
     * 主动调用 [close] 关闭通道时不触发该回调。
     */
    private val onDisconnected: (String) -> Unit = {}
) : Channel {

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var readThread: Thread? = null

    @Volatile
    private var running = false
    private val isDisconnectNotified = java.util.concurrent.atomic.AtomicBoolean(false)
    private var currentHost: String = ""
    private var currentPort: Int = 0

    /**
     * 触发断开回调，同一通道实例仅回调一次。
     *
     * @param reason 断开原因描述
     */
    private fun notifyDisconnected(reason: String) {
        if (isDisconnectNotified.compareAndSet(false, true)) {
            onDisconnected(reason)
        }
    }

    /** 同步连接（调用方应在子线程执行）。不启动读循环。 */
    fun connect(host: String, port: Int): Boolean {
        return try {
            isDisconnectNotified.set(false)
            currentHost = host
            currentPort = port
            val sock = Socket()
            sock.connect(InetSocketAddress(host, port), 8000)
            sock.tcpNoDelay = true
            // 开启 TCP 保活探测，探测间隔由系统决定，仅用于回收僵死连接
            sock.keepAlive = true
            // 握手阶段设置读超时，避免设备接受连接但不回包时无限阻塞；进入读循环前会恢复为 0
            sock.soTimeout = 10000
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
            val trustManager = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    if (chain.isNullOrEmpty()) {
                        throw java.security.cert.CertificateException("Client certificate chain is empty")
                    }
                }
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    if (chain.isNullOrEmpty()) {
                        throw java.security.cert.CertificateException("Server certificate chain is empty")
                    }
                    chain[0].checkValidity()
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            })
            sslContext.init(arrayOf(keyManager), trustManager, SecureRandom())

            onLog("正在向 $currentHost:$currentPort 发起 TLS 1.3 握手...")
            val sslSocket = sslContext.socketFactory.createSocket(
                rawSocket,
                currentHost,
                currentPort,
                true
            ) as SSLSocket

            sslSocket.enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")
            sslSocket.useClientMode = true
            // TLS 隧道继承握手阶段的读超时（SSLSocket 不继承原始 Socket 的超时设置）
            sslSocket.soTimeout = 10000
            sslSocket.startHandshake()

            socket = sslSocket
            input = sslSocket.inputStream
            output = sslSocket.outputStream
            onLog("[TLS] TLS 1.3 安全通道建立完成 (Cipher: ${sslSocket.session.cipherSuite})")
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
        // 长连接读循环不能带读超时，否则空闲期间会抛 SocketTimeoutException 终止收包
        try {
            socket?.soTimeout = 0
        } catch (e: Exception) {
            onStatus("tcp 读超时复位失败: ${e.javaClass.simpleName}: ${e.message}")
        }
        readThread = Thread {
            val buffer = ByteArray(65536)
            var readCount = 0
            var reason = "读循环退出"
            while (running) {
                try {
                    val inStream = input
                    if (inStream == null) {
                        reason = "输入流已释放"
                        break
                    }
                    val n = inStream.read(buffer)
                    if (n < 0) {
                        reason = "对端关闭连接"
                        break
                    }
                    if (n == 0) continue
                    readCount++
                    if (readCount <= 20) onStatus("tcp_read #$readCount: $n 字节")
                    onData(buffer.copyOf(n))
                } catch (e: java.net.SocketTimeoutException) {
                    // 防御性分支：读循环阶段 SO_TIMEOUT 已置 0，正常情况下不会触发
                    continue
                } catch (e: Exception) {
                    reason = "读取异常: ${e.javaClass.simpleName}: ${e.message}"
                    onStatus("tcp_read 异常终止: ${e.javaClass.simpleName}: ${e.message}")
                    break
                }
            }
            val wasRunning = running
            running = false
            if (wasRunning) {
                notifyDisconnected(reason)
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    @Synchronized
    override fun send(data: ByteArray): Boolean {
        return try {
            output?.write(data)
            output?.flush()
            true
        } catch (e: Exception) {
            onStatus("tcp_send 异常: ${e.javaClass.simpleName}: ${e.message}")
            notifyDisconnected("发送失败: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    @Synchronized
    override fun close() {
        running = false
        isDisconnectNotified.set(true)
        readThread?.interrupt()
        readThread = null
        try {
            socket?.close()
        } catch (e: Exception) {
            onStatus("tcp_close 异常: ${e.javaClass.simpleName}: ${e.message}")
        }
        socket = null
        input = null
        output = null
    }
}
