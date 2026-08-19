package com.aoooa.webadb.adb

import android.content.Context
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * ADB 认证密钥：RSA-2048。
 * 全局持久化存储，签名算法严格对齐 AOSP / OpenSSL RSA_verify 标准规范。
 */
class AdbCrypto(context: Context? = null) {

    companion object {
        // AOSP 标准 SHA-1 DigestInfo ASN.1 前缀 (15 字节)
        // 30 21 30 09 06 05 2b 0e 03 02 1a 05 00 04 14
        private val SIGNATURE_AID = byteArrayOf(
            0x30.toByte(), 0x21.toByte(), 0x30.toByte(), 0x09.toByte(), 0x06.toByte(),
            0x05.toByte(), 0x2b.toByte(), 0x0e.toByte(), 0x03.toByte(), 0x02.toByte(),
            0x1a.toByte(), 0x05.toByte(), 0x00.toByte(), 0x04.toByte(), 0x14.toByte()
        )
    }

    private val keyPair: KeyPair by lazy {
        loadOrGenerateKeyPair(context)
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
     * 关键修复：AOSP adbd 使用 OpenSSL RSA_verify(NID_sha1, token, 20, ...) 校验，
     * 期望接收的是 (ASN.1 Header + token) 的 Raw PKCS#1 v1.5 私钥加密签名。
     * 切勿使用 Java 的 Signature.getInstance("SHA1withRSA")（会在内部额外重复 hash 一次 token 导致校验失败）。
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
     * 优先使用 C 语言原生 NDK 动态库 (libwebadb_native.so) 进行小端内存对齐编码。
     */
    fun encodePublicKey(): ByteArray {
        val pub = keyPair.public as RSAPublicKey
        val n = pub.modulus
        val e = pub.publicExponent

        if (com.aoooa.webadb.native.WebAdbNative.isLoaded) {
            try {
                return com.aoooa.webadb.native.WebAdbNative.encodeRsaPublicKey(n.toByteArray(), e.toInt())
            } catch (_: Throwable) {
            }
        }

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
}
