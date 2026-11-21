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

    /** 生成协商前的 CNXN 首包：24 字节头部及原样复制的 banner 字节。 */
    external fun buildCnxnPacket(version: Int, maxPayload: Int, payload: ByteArray): ByteArray
}
