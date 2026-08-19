package com.aoooa.webadb.pairing

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 纯原生 Kotlin 实现的 SPAKE2 密码认证密钥协商算法（完全对齐 BoringSSL / AOSP adbd 规范）。
 * 零外部网络依赖，基于 Edwards25519 椭圆曲线与 SHA-512 成绩转录。
 */
class Spake2(
    private val isClient: Boolean = true,
    private val myName: ByteArray = "adb pair client\u0000".toByteArray(Charsets.UTF_8),
    private val theirName: ByteArray = "adb pair server\u0000".toByteArray(Charsets.UTF_8)
) {
    companion object {
        // Edwards25519 曲线参数
        private val P = BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19))
        private val L = BigInteger.valueOf(2).pow(252).add(BigInteger("27742317777372353535851937790883648493"))
        private val D = BigInteger("-121665").multiply(BigInteger("121666").modInverse(P)).mod(P)

        // Edwards25519 基点 G
        private val G = decodePoint("5866666666666666666666666666666666666666666666666666666666666666".hexToBytes())

        // BoringSSL SPAKE2 固定参考点 M 与 N
        private val M = decodePoint("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e".hexToBytes())
        private val N = decodePoint("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778".hexToBytes())

        private fun String.hexToBytes(): ByteArray {
            val len = length
            val data = ByteArray(len / 2)
            var i = 0
            while (i < len) {
                data[i / 2] = ((Character.digit(this[i], 16) shl 4) + Character.digit(this[i + 1], 16)).toByte()
                i += 2
            }
            return data
        }

        private data class Point(val x: BigInteger, val y: BigInteger)

        private fun pointAdd(p1: Point?, p2: Point?): Point? {
            if (p1 == null) return p2
            if (p2 == null) return p1
            val x1x2 = p1.x.multiply(p2.x).mod(P)
            val y1y2 = p1.y.multiply(p2.y).mod(P)
            val dTerm = D.multiply(x1x2).mod(P).multiply(y1y2).mod(P)

            val x3Num = p1.x.multiply(p2.y).add(p1.y.multiply(p2.x)).mod(P)
            val x3Den = BigInteger.ONE.add(dTerm).mod(P)
            val x3 = x3Num.multiply(x3Den.modInverse(P)).mod(P)

            val y3Num = p1.y.multiply(p2.y).add(p1.x.multiply(p2.x)).mod(P)
            val y3Den = BigInteger.ONE.subtract(dTerm).mod(P)
            val y3 = y3Num.multiply(y3Den.modInverse(P)).mod(P)

            return Point(x3, y3)
        }

        private fun pointNeg(p: Point): Point = Point(P.subtract(p.x).mod(P), p.y)

        private fun scalarMul(p: Point, k: BigInteger): Point {
            var r: Point? = null
            var q: Point? = p
            var rem = k
            while (rem > BigInteger.ZERO) {
                if (rem.testBit(0)) {
                    r = pointAdd(r, q)
                }
                q = pointAdd(q, q)
                rem = rem.shiftRight(1)
            }
            return r ?: Point(BigInteger.ZERO, BigInteger.ONE)
        }

        private fun encodePoint(p: Point): ByteArray {
            val yBytes = p.y.toByteArray()
            val out = ByteArray(32)
            // 复制 Little-Endian y 坐标
            for (i in yBytes.indices) {
                val idx = yBytes.size - 1 - i
                if (idx >= 0 && i < 32) {
                    out[i] = yBytes[idx]
                }
            }
            // 最高位存 x 坐标的最低位符号
            if (p.x.testBit(0)) {
                out[31] = (out[31].toInt() or 0x80).toByte()
            } else {
                out[31] = (out[31].toInt() and 0x7F).toByte()
            }
            return out
        }

        private fun decodePoint(bytes: ByteArray): Point {
            var y = BigInteger.ZERO
            for (i in 0 until 32) {
                val b = if (i == 31) bytes[i].toInt() and 0x7F else bytes[i].toInt() and 0xFF
                y = y.or(BigInteger.valueOf(b.toLong()).shiftLeft(8 * i))
            }
            val x0 = (bytes[31].toInt() shr 7) and 1

            val y2 = y.multiply(y).mod(P)
            val u = y2.subtract(BigInteger.ONE).mod(P)
            val v = D.multiply(y2).add(BigInteger.ONE).mod(P)
            val x2 = u.multiply(v.modInverse(P)).mod(P)

            // x = x2^((P+3)/8) mod P
            val exp = P.add(BigInteger.valueOf(3)).divide(BigInteger.valueOf(8))
            var x = x2.modPow(exp, P)
            if (x.multiply(x).mod(P) != x2) {
                val sqrtMinus1 = BigInteger.valueOf(2).modPow(P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4)), P)
                x = x.multiply(sqrtMinus1).mod(P)
            }
            if (x.testBit(0) != (x0 == 1)) {
                x = P.subtract(x).mod(P)
            }
            return Point(x, y)
        }
    }

    private var privScalar = BigInteger.ZERO
    private var passwordHash = ByteArray(0)
    private var passwordScalar = BigInteger.ZERO
    private var myMsg = ByteArray(0)

    /**
     * 第一步：根据密码生成 SPAKE2 消息（Alice 广播 P* = x*G + w*M）
     */
    fun generateMessage(password: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        passwordHash = md.digest(password)

        // w = passwordHash mod L
        var w = BigInteger(1, passwordHash.reversedArray()).mod(L)
        // 按照 BoringSSL 规范消除 cofactor 泄露：保证低 3 位为 0
        if (w.testBit(0)) w = w.add(L)
        if (w.testBit(1)) w = w.add(L.shiftLeft(1))
        if (w.testBit(2)) w = w.add(L.shiftLeft(2))
        passwordScalar = w

        // 生成随机私钥标量 x (乘以 8 清除 cofactor)
        val randBytes = ByteArray(32)
        SecureRandom().nextBytes(randBytes)
        val rawX = BigInteger(1, randBytes).mod(L)
        privScalar = rawX.shiftLeft(3) // x * 8

        val pPoint = scalarMul(G, privScalar)
        val maskPoint = scalarMul(if (isClient) M else N, passwordScalar)
        val pStar = pointAdd(pPoint, maskPoint) ?: Point(BigInteger.ZERO, BigInteger.ONE)

        myMsg = encodePoint(pStar)
        return myMsg
    }

    /**
     * 第二步：处理对端消息并计算共享对称密钥（K = x*(Q* - w*N)）
     */
    fun processMessage(theirMsg: ByteArray): ByteArray {
        if (theirMsg.size != 32) throw IllegalArgumentException("非法消息长度: ${theirMsg.size}")
        val qStar = decodePoint(theirMsg)

        val peerMask = scalarMul(if (isClient) N else M, passwordScalar)
        val qPoint = pointAdd(qStar, pointNeg(peerMask)) ?: Point(BigInteger.ZERO, BigInteger.ONE)
        val kPoint = scalarMul(qPoint, privScalar)
        val kEncoded = encodePoint(kPoint)

        val md = MessageDigest.getInstance("SHA-512")
        if (isClient) {
            updateWithLen(md, myName)
            updateWithLen(md, theirName)
            updateWithLen(md, myMsg)
            updateWithLen(md, theirMsg)
        } else {
            updateWithLen(md, theirName)
            updateWithLen(md, myName)
            updateWithLen(md, theirMsg)
            updateWithLen(md, myMsg)
        }
        updateWithLen(md, kEncoded)
        updateWithLen(md, passwordHash)

        return md.digest()
    }

    private fun updateWithLen(md: MessageDigest, data: ByteArray) {
        val lenBytes = ByteArray(8)
        var l = data.size.toLong()
        for (i in 0 until 8) {
            lenBytes[i] = (l and 0xFF).toByte()
            l = l shr 8
        }
        md.update(lenBytes)
        md.update(data)
    }
}
