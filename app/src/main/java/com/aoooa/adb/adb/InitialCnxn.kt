package com.aoooa.adb.adb

/** 首包尚未取得对端版本，保留校验和；传输参数由调用方选择。 */
internal enum class InitialCnxn(val version: Int, val maxData: Int) {
    USB(0x01000000, 4096),
    TCP(0x01000001, 1048576);

    fun payload(): ByteArray = "host::features=$SHELL_V2".toByteArray(Charsets.UTF_8)

    companion object { const val SHELL_V2 = "shell_v2" }

    fun kotlinPacket(payload: ByteArray): ByteArray = AdbPacket(AdbPacket.CNXN, version, maxData, payload).toBytes()
}
