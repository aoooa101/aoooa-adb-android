package com.aoooa.webadb.native

/**
 * 原生 C/C++ (NDK) 动态链接库 JNI 桥接。
 * 针对 arm64-v8a 架构优化，提供 C 语言层面的 ADB 报文打包、公钥结构体编码及校验。
 */
object WebAdbNative {

    @Volatile
    var isLoaded = false
        private set

    init {
        try {
            System.loadLibrary("webadb_native")
            isLoaded = true
        } catch (e: Throwable) {
            isLoaded = false
        }
    }

    /**
     * 在 C 层生成标准的 CNXN 报文（Header 24B + payload）
     */
    external fun buildCnxnPacket(version: Int, maxPayload: Int, banner: String): ByteArray

    /**
     * 在 C 层生成标准的 RSAPublicKey 524 字节结构体（小端内存对齐）
     */
    external fun encodeRsaPublicKey(modulusBytes: ByteArray, publicExponent: Int): ByteArray

    /**
     * 在 C 层计算 ADB checksum
     */
    external fun calculateChecksum(payload: ByteArray): Int
}
