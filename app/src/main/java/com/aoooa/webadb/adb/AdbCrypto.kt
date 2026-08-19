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
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * ADB 认证密钥：RSA-2048。
 * 全局持久化存储（与 1.0 版行为一致），避免每次连接动态生成新 Key 触发被控端 adbd 的防爆破冻结。
 */
class AdbCrypto(context: Context? = null) {

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

    /** 对 20 字节 token 做 SHA1withRSA 签名 */
    fun sign(token: ByteArray): ByteArray {
        val sig = Signature.getInstance("SHA1withRSA")
        sig.initSign(keyPair.private)
        sig.update(token)
        return sig.sign()
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
