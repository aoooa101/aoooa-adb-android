package com.aoooa.webadb.adb

import android.content.Context
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Principal
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.net.ssl.X509KeyManager

/**
 * ADB 认证密钥：RSA-2048。
 * 全局持久化存储，签名算法严格对齐 AOSP / OpenSSL RSA_verify 标准规范，
 * 并支持动态生成 X.509 自签名证书以实现 Android 11+ TLS 1.3 双向安全握手。
 */
class AdbCrypto(context: Context? = null) {

    companion object {
        const val KEY_NAME = "WebADB@aoooa101"

        // AOSP 标准 SHA-1 DigestInfo ASN.1 前缀 (15 字节)
        // 30 21 30 09 06 05 2b 0e 03 02 1a 05 00 04 14
        private val SIGNATURE_AID = byteArrayOf(
            0x30.toByte(), 0x21.toByte(), 0x30.toByte(), 0x09.toByte(), 0x06.toByte(),
            0x05.toByte(), 0x2b.toByte(), 0x0e.toByte(), 0x03.toByte(), 0x02.toByte(),
            0x1a.toByte(), 0x05.toByte(), 0x00.toByte(), 0x04.toByte(), 0x14.toByte()
        )
    }

    val keyPair: KeyPair by lazy {
        loadOrGenerateKeyPair(context)
    }

    val certificate: X509Certificate by lazy {
        generateSelfSignedCertificate()
    }

    private fun loadOrGenerateKeyPair(context: Context?): KeyPair {
        if (context != null) {
            try {
                val dir = File(context.filesDir, "keys")
                dir.mkdirs()
                val privFile = File(dir, "adbkey")
                val pubFile = File(dir, "adbkey.pub")

                if (privFile.exists() && pubFile.exists()) {
                    val kf = KeyFactory.getInstance("RSA")
                    val privSpec = PKCS8EncodedKeySpec(privFile.readBytes())
                    val pubSpec = X509EncodedKeySpec(pubFile.readBytes())
                    val priv: PrivateKey = kf.generatePrivate(privSpec)
                    val pub: PublicKey = kf.generatePublic(pubSpec)
                    return KeyPair(pub, priv)
                }

                // 生成新密钥并持久化保存
                val gen = KeyPairGenerator.getInstance("RSA")
                gen.initialize(2048)
                val pair = gen.generateKeyPair()
                privFile.writeBytes(pair.private.encoded)
                pubFile.writeBytes(pair.public.encoded)
                return pair
            } catch (_: Exception) {
            }
        }
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        return gen.generateKeyPair()
    }

    /**
     * 对 20 字节 token 制作标准 ADB RSA 签名。
     * AOSP adbd 使用 OpenSSL RSA_verify(NID_sha1, token, 20, ...) 校验。
     */
    fun sign(token: ByteArray): ByteArray {
        val digestBlock = ByteArray(SIGNATURE_AID.size + token.size)
        System.arraycopy(SIGNATURE_AID, 0, digestBlock, 0, SIGNATURE_AID.size)
        System.arraycopy(token, 0, digestBlock, SIGNATURE_AID.size, token.size)

        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keyPair.private)
        return cipher.doFinal(digestBlock)
    }

    /**
     * 编码为 adbd 的 RSAPublicKey 结构 (524B)：
     * 严格按照 AOSP 标准计算小端模数、n0inv 与 Montgomery 常量 (R^2 mod N)。
     */
    fun encodePublicKey(): ByteArray {
        val pub = keyPair.public as RSAPublicKey
        val n = pub.modulus
        val e = pub.publicExponent

        val words = (n.bitLength() + 31) / 32
        val TWO_32 = BigInteger.ONE.shiftLeft(32)
        val MASK = TWO_32.subtract(BigInteger.ONE)

        val n0 = n.and(MASK)
        val n0inv = n0.modInverse(TWO_32).negate().and(MASK)

        val r = BigInteger.ONE.shiftLeft(32 * words)
        val rr = r.multiply(r).mod(n)

        val buf = ByteBuffer.allocate(12 + 8 * words).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(words)
        buf.putInt(n0inv.toInt())
        for (i in 0 until words) {
            buf.putInt(n.shiftRight(32 * i).and(MASK).toInt())
        }
        for (i in 0 until words) {
            buf.putInt(rr.shiftRight(32 * i).and(MASK).toInt())
        }
        buf.putInt(e.toInt())
        return buf.array()
    }

    /**
     * 获取用于 Android 11+ 无线配对与已授权 keys 文件的标准公钥字符串：
     * 格式：<Base64(524B RSAPublicKey)> WebADB@aoooa101
     */
    fun getAdbPublicKeyString(): String {
        val encodedPub = encodePublicKey()
        val b64 = Base64.encodeToString(encodedPub, Base64.NO_WRAP)
        return "$b64 $KEY_NAME"
    }

    /**
     * 构造纯原生 X.509 DER 自签名证书（无需外部依赖），供 TLS 1.3 客户端认证。
     */
    private fun generateSelfSignedCertificate(): X509Certificate {
        val pubKeyInfo = keyPair.public.encoded // 已经是标准 SubjectPublicKeyInfo DER

        // 1. 构造 TBSCertificate (To-Be-Signed Certificate)
        // Version: v3 (0xA0, 0x03, 0x02, 0x01, 0x02)
        val version = byteArrayOf(0xA0.toByte(), 0x03, 0x02, 0x01, 0x02)

        // SerialNumber: 随机 8 字节正整数
        val serialBytes = ByteArray(8)
        SecureRandom().nextBytes(serialBytes)
        serialBytes[0] = (serialBytes[0].toInt() and 0x7F).toByte() // 保证正数
        val serial = derEncode(0x02, serialBytes)

        // Signature Algorithm: SHA256withRSA (OID: 1.2.840.113549.1.1.11, NULL)
        val sigAlg = byteArrayOf(
            0x30, 0x0D, 0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(),
            0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B, 0x05, 0x00
        )

        // Issuer & Subject: CN=WebADB@aoooa101
        val cnBytes = KEY_NAME.toByteArray(Charsets.UTF_8)
        val rdnAttr = derSequence(
            byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03) + derEncode(0x0C, cnBytes)
        )
        val rdnSet = derEncode(0x31, rdnAttr)
        val name = derEncode(0x30, rdnSet)

        // Validity: 2020-01-01 到 2050-01-01 (UTCTime: 200101000000Z ~ 500101000000Z)
        val notBefore = derEncode(0x17, "200101000000Z".toByteArray(Charsets.US_ASCII))
        val notAfter = derEncode(0x17, "500101000000Z".toByteArray(Charsets.US_ASCII))
        val validity = derSequence(notBefore + notAfter)

        // 组装 TBS
        val tbs = derSequence(version + serial + sigAlg + name + validity + name + pubKeyInfo)

        // 2. 用私钥对 TBS 进行 SHA256withRSA 签名
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(keyPair.private)
        signer.update(tbs)
        val signatureBytes = signer.sign()
        val bitStringSig = derEncode(0x03, byteArrayOf(0x00) + signatureBytes)

        // 3. 组装完整 Certificate SEQUENCE
        val fullCertDer = derSequence(tbs + sigAlg + bitStringSig)

        val cf = CertificateFactory.getInstance("X.509")
        return cf.generateCertificate(ByteArrayInputStream(fullCertDer)) as X509Certificate
    }

    private fun derEncode(tag: Int, value: ByteArray): ByteArray {
        val lenBytes = when {
            value.size < 128 -> byteArrayOf(value.size.toByte())
            value.size < 256 -> byteArrayOf(0x81.toByte(), value.size.toByte())
            else -> byteArrayOf(0x82.toByte(), (value.size shr 8).toByte(), (value.size and 0xFF).toByte())
        }
        val out = ByteArray(1 + lenBytes.size + value.size)
        out[0] = tag.toByte()
        System.arraycopy(lenBytes, 0, out, 1, lenBytes.size)
        System.arraycopy(value, 0, out, 1 + lenBytes.size, value.size)
        return out
    }

    private fun derSequence(contents: ByteArray): ByteArray = derEncode(0x30, contents)

    /**
     * 提供适配 TLS 1.3 的 X509KeyManager
     */
    fun getKeyManager(): X509KeyManager {
        val cert = certificate
        val priv = keyPair.private
        return object : X509KeyManager {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf("webadb")
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String = "webadb"
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf("webadb")
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String = "webadb"
            override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(cert)
            override fun getPrivateKey(alias: String?): PrivateKey = priv
        }
    }
}
