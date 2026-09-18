package com.aoooa.adb.native

/**
 * NDK JNI 桥接，提供 C 层 ADB 报文处理与辅助函数。
 */
object AdbNative {

    @Volatile
    var isLoaded = false
        private set

    init {
        try {
            System.loadLibrary("aoooa_adb_native")
            isLoaded = true
        } catch (e: Throwable) {
            isLoaded = false
        }
    }

    /** 生成 CNXN 报文（Header 24B + payload） */
    external fun buildCnxnPacket(version: Int, maxPayload: Int, banner: String): ByteArray

    /** 生成 RSAPublicKey 524 字节结构体 */
    external fun encodeRsaPublicKey(modulusBytes: ByteArray, publicExponent: Int): ByteArray

    /** 计算 ADB checksum */
    external fun calculateChecksum(payload: ByteArray): Int

    /** 原生无线配对握手 */
    external fun nativePair(host: String, port: Int, code: String): Boolean
}
