package com.aoooa.adb.adb

/** 首包尚未取得对端版本，保留校验和；传输参数由调用方选择。 */
internal enum class InitialCnxn(val version: Int, val maxData: Int, private val banner: String) {
    USB(0x01000000, 4096, "host::aoooa101"),
    TCP(0x01000001, 1048576, "host::features=shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir,apex,abb,abb_exec,sendrecv_v2,sendrecv_v2_brotli,sendrecv_v2_lz4,sendrecv_v2_zstd");

    fun payload(): ByteArray = banner.toByteArray(Charsets.UTF_8)
    fun kotlinPacket(payload: ByteArray): ByteArray = AdbPacket(AdbPacket.CNXN, version, maxData, payload).toBytes()
}
