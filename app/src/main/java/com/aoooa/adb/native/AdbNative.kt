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

    /** 生成 CNXN 报文（Header 24B + banner payload，banner 必须以 NUL 结尾） */
    external fun buildCnxnPacket(version: Int, maxPayload: Int, banner: String): ByteArray
}
