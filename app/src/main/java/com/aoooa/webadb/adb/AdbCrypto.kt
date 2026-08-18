package com.aoooa.webadb.adb

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey

/**
 * ADB 认证密钥：RSA-2048。
 *
 * - 签名：SHA1withRSA（adbd 用 RSA_verify 验证 token）
 * - 公钥：按 adbd 的 RSAPublicKey 结构编码（n0inv / n[] / rr[] / exponent）
 */
class AdbCrypto {

    private val keyPair: KeyPair = run {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        gen.generateKeyPair()
    }

    /** 对 20 字节 token 做 SHA1withRSA 签名 */
    fun sign(token: ByteArray): ByteArray {
        val sig = Signature.getInstance("SHA1withRSA")
        sig.initSign(keyPair.private)
        sig.update(token)
        return sig.sign()
    }

    /**
     * 编码为 adbd 的 RSAPublicKey 结构：
     *   int len;                // n[] 的 uint32 数量
     *   uint32 n0inv;           // -1 / n[0] mod 2^32
     *   uint32 n[RSANUMWORDS];  // 模数（小端 word 数组）
     *   uint32 rr[RSANUMWORDS]; // R^2 mod n
     *   int exponent;           // 3 或 65537
     */
    fun encodePublicKey(): ByteArray {
        val pub = keyPair.public as RSAPublicKey
        val n = pub.modulus
        val e = pub.publicExponent
        val words = (n.bitLength() + 31) / 32

        val TWO_32 = BigInteger.ONE.shiftLeft(32)
        val MASK = TWO_32.subtract(BigInteger.ONE)

        // n0inv = -1/n[0] mod 2^32
        val n0 = n.and(MASK)
        val n0inv = n0.modInverse(TWO_32).negate().and(MASK)

        // rr = R^2 mod n, R = 2^(32*words)
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
