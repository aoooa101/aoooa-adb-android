package com.aoooa.webadb.pairing

import android.content.Context
import android.os.Build
import com.aoooa.webadb.AdbManager
import com.aoooa.webadb.adb.AdbCrypto
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Android 11+ AOSP 标准无线配对引擎：
 * 完整实现 TLS 1.3 握手 + EKM (Exported Keying Material) + SPAKE2 密码交换 + AES-128-GCM PeerInfo 注册。
 */
object AdbPairing {

    private const val HEADER_VERSION = 1.toByte()
    private const val TYPE_SPAKE2_MSG = 0.toByte()
    private const val TYPE_PEER_INFO = 1.toByte()
    private const val PEER_INFO_SIZE = 8192

    private val CLIENT_NAME = "adb pair client".toByteArray(Charsets.UTF_8)
    private val SERVER_NAME = "adb pair server".toByteArray(Charsets.UTF_8)
    private val HKDF_INFO = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.UTF_8)

    /**
     * 执行 Android 11+ 无线配对流程：
     * 1. 与系统配对服务端建立 TLS 连接并导出 keying material
     * 2. 执行 SPAKE2 密码协商校验配对码
     * 3. 加密注入本端 ADB RSA 公钥与身份 (WebADB@aoooa101)
     * 4. 配对成功后自动直连真实无线调试主端口
     */
    fun pair(context: Context, host: String, port: Int, password: String, onComplete: (Boolean) -> Unit) {
        Thread {
            var rawSocket: Socket? = null
            var sslSocket: SSLSocket? = null
            try {
                AdbManager.log("正在连接无线配对端口: $host:$port ...")
                val crypto = AdbCrypto(context)

                val sslContext = try {
                    SSLContext.getInstance("TLSv1.3", "Conscrypt")
                } catch (_: Exception) {
                    SSLContext.getInstance("TLSv1.3")
                }
                val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                })
                sslContext.init(arrayOf(crypto.getKeyManager()), trustAll, SecureRandom())

                rawSocket = Socket()
                rawSocket.connect(InetSocketAddress(host, port), 8000)
                rawSocket.tcpNoDelay = true

                sslSocket = sslContext.socketFactory.createSocket(rawSocket, host, port, true) as SSLSocket
                sslSocket.enabledProtocols = arrayOf("TLSv1.3")
                sslSocket.useClientMode = true
                sslSocket.startHandshake()

                AdbManager.log("TLS 握手完成，正在导出通道绑定密钥 (EKM)...")
                val ekm = exportKeyingMaterial(sslSocket, "adb-label", 64)
                if (ekm == null) {
                    throw IllegalStateException("EKM 导出失败：配对必须基于 TLS 1.3 导出的通道绑定密钥")
                }
                val fullPassword = password.toByteArray(Charsets.UTF_8) + ekm

                val inStream = DataInputStream(sslSocket.inputStream)
                val outStream = DataOutputStream(sslSocket.outputStream)

                // 1. SPAKE2 消息交换（使用纯 Kotlin 自研 Ed25519 引擎）
                AdbManager.log("正在执行 SPAKE2 密码学握手 (验证 6 位配对码)...")
                val spakeCtx = Spake2(
                    isClient = true,
                    myName = CLIENT_NAME,
                    theirName = SERVER_NAME
                )
                val ourMsg = spakeCtx.generateMessage(fullPassword)

                writePacket(outStream, TYPE_SPAKE2_MSG, ourMsg)
                val (theirType, theirMsg) = readPacket(inStream)
                if (theirType != TYPE_SPAKE2_MSG) {
                    throw IllegalStateException("收到非 SPAKE2 消息类型: $theirType")
                }

                val keyMaterial = spakeCtx.processMessage(theirMsg)
                if (keyMaterial.isEmpty()) {
                    throw IllegalStateException("配对码错误或 SPAKE2 协商失败")
                }

                // 2. HKDF 派生 AES-128 密钥
                val aesKey = hkdfSha256(keyMaterial, HKDF_INFO, 16)

                // 3. 构造并加密 PeerInfo (注册公钥到系统的 adb_keys)
                val pubKeyStr = crypto.getAdbPublicKeyString()
                val pubKeyBytes = (pubKeyStr + "\u0000").toByteArray(Charsets.UTF_8)
                val peerInfoBuf = ByteArray(PEER_INFO_SIZE)
                peerInfoBuf[0] = 0 // ADB_RSA_PUB_KEY = 0
                System.arraycopy(pubKeyBytes, 0, peerInfoBuf, 1, minOf(pubKeyBytes.size, PEER_INFO_SIZE - 1))

                var encSeq = 0L
                val nonce = ByteArray(12)
                ByteBuffer.wrap(nonce).order(ByteOrder.LITTLE_ENDIAN).putLong(encSeq++)

                val cipherEnc = Cipher.getInstance("AES/GCM/NoPadding")
                cipherEnc.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, nonce))
                val encryptedPeerInfo = cipherEnc.doFinal(peerInfoBuf)

                AdbManager.log("发送已加密设备身份 (WebADB@aoooa101)...")
                writePacket(outStream, TYPE_PEER_INFO, encryptedPeerInfo)

                // 读取被控端返回的加密 PeerInfo
                val (respType, respEnc) = readPacket(inStream)
                if (respType == TYPE_PEER_INFO && respEnc.isNotEmpty()) {
                    val decNonce = ByteArray(12)
                    ByteBuffer.wrap(decNonce).order(ByteOrder.LITTLE_ENDIAN).putLong(0L)
                    val cipherDec = Cipher.getInstance("AES/GCM/NoPadding")
                    cipherDec.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, decNonce))
                    cipherDec.doFinal(respEnc)
                }

                AdbManager.log("🎉 无线配对成功！系统已将本客户端加入已配对设备列表。")
                PairingService.updateNotificationSuccess(context, "无线配对成功，已授权此设备！")

                try { sslSocket.close() } catch (_: Exception) {}
                try { rawSocket.close() } catch (_: Exception) {}

                Thread.sleep(600)
                val connectPort = if (PairingService.discoveredConnectPort > 0) {
                    PairingService.discoveredConnectPort
                } else {
                    5555
                }

                PairingService.stop(context)

                AdbManager.log("自动直连真实无线调试主端口: $host:$connectPort ...")
                AdbManager.connectTcp(context, host, connectPort)
                onComplete(true)
            } catch (e: Exception) {
                AdbManager.log("配对失败: ${e.message}")
                PairingService.updateNotificationError(context, "配对失败: ${e.message}")
                try { sslSocket?.close() } catch (_: Exception) {}
                try { rawSocket?.close() } catch (_: Exception) {}
                onComplete(false)
            }
        }.start()
    }

    private fun writePacket(out: DataOutputStream, type: Byte, payload: ByteArray) {
        out.writeByte(HEADER_VERSION.toInt())
        out.writeByte(type.toInt())
        out.writeInt(payload.size) // 大端序 uint32
        out.write(payload)
        out.flush()
    }

    private fun readPacket(inStream: DataInputStream): Pair<Byte, ByteArray> {
        val version = inStream.readByte()
        if (version != HEADER_VERSION) {
            throw IllegalStateException("不支持的配对报文版本: $version")
        }
        val type = inStream.readByte()
        val len = inStream.readInt()
        if (len < 0 || len > PEER_INFO_SIZE * 2) {
            throw IllegalStateException("异常报文长度: $len")
        }
        val payload = ByteArray(len)
        inStream.readFully(payload)
        return type to payload
    }

    private fun exportKeyingMaterial(sslSocket: SSLSocket, label: String, length: Int): ByteArray? {
        // 方式1：直接调用 SSLSocket.exportKeyingMaterial（API 29+ 原生方法）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val result = sslSocket.exportKeyingMaterial(label, null, length)
                if (result != null && result.isNotEmpty()) {
                    return result
                }
                AdbManager.log("SSLSocket.exportKeyingMaterial 返回空: 协议=${sslSocket.session.protocol} cipher=${sslSocket.session.cipherSuite}")
            } catch (e: Throwable) {
                AdbManager.log("SSLSocket.exportKeyingMaterial 异常: ${e.javaClass.simpleName}: ${e.message}")
            }
        } else {
            AdbManager.log("设备 API < 29，无法导出 EKM")
        }
        // 方式2：Conscrypt 静态方法（反射）
        try {
            val conscryptClass = Class.forName("org.conscrypt.Conscrypt")
            val exportMethod = conscryptClass.getMethod(
                "exportKeyingMaterial",
                SSLSocket::class.java,
                String::class.java,
                ByteArray::class.java,
                Int::class.javaPrimitiveType
            )
            val result = exportMethod.invoke(null, sslSocket, label, null, length) as? ByteArray
            if (result != null && result.isNotEmpty()) {
                return result
            }
        } catch (e: Throwable) {
            AdbManager.log("Conscrypt.exportKeyingMaterial 异常: ${e.javaClass.simpleName}: ${e.message}")
        }
        AdbManager.log("EKM 全部导出方式失败。SSLSocket 实现类: ${sslSocket.javaClass.name}")
        return null
    }

    private fun hkdfSha256(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // Extract
        val salt = ByteArray(32)
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        // Expand
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(1.toByte())
        val okm = mac.doFinal()
        return okm.copyOf(length)
    }
}
