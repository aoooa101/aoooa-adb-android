package com.aoooa.webadb.adb

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ADB 报文：24 字节头 + payload（little-endian）。
 *
 * 头结构：
 *   command      (4B)  - CNXN/AUTH/OPEN/WRTE/CLSE/OKAY
 *   arg0         (4B)  - 随命令变化（AUTH 类型 / 流 ID 等）
 *   arg1         (4B)  - 随命令变化
 *   payloadLength (4B)
 *   checksum     (4B)  - payload 字节和 & 0xffffffff
 *   magic        (4B)  - command ^ 0xffffffff
 */
class AdbPacket(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray = ByteArray(0)
) {
    val payloadLength: Int get() = payload.size

    /** 序列化为字节流（24B 头 + payload） */
    fun toBytes(): ByteArray {
        val buf = ByteBuffer.allocate(24 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(command)
        buf.putInt(arg0)
        buf.putInt(arg1)
        buf.putInt(payload.size)
        buf.putInt(checksum(payload))
        buf.putInt(command xor -1)
        buf.put(payload)
        return buf.array()
    }

    companion object {
        // AOSP 标准小端序命令字：按 Little-Endian 写入后精确对应 ASCII 字符
        const val CNXN = 0x4e584e43 // 字节流: 43 4E 58 4E ("CNXN")
        const val AUTH = 0x48545541 // 字节流: 41 55 54 48 ("AUTH")
        const val OPEN = 0x4e45504f // 字节流: 4F 50 45 4E ("OPEN")
        const val OKAY = 0x59414b4f // 字节流: 4F 4B 41 59 ("OKAY")
        const val CLSE = 0x45534c43 // 字节流: 43 4C 53 45 ("CLSE")
        const val WRTE = 0x45545257 // 字节流: 57 52 54 45 ("WRTE")

        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_PUBLICKEY = 3

        const val VERSION = 0x01000000
        const val MAX_PAYLOAD = 4096

        fun checksum(payload: ByteArray): Int {
            var sum = 0L
            for (b in payload) sum += b.toInt() and 0xff
            return (sum and 0xffffffffL).toInt()
        }

        /**
         * 从字节流缓冲区尝试解析出一个完整包。
         * @return (包, 消费字节数)；数据不足或校验失败返回 null
         */
        fun tryParse(buffer: ByteArray): Pair<AdbPacket, Int>? {
            if (buffer.size < 24) return null
            val dv = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
            val command = dv.int
            val arg0 = dv.int
            val arg1 = dv.int
            val len = dv.int
            dv.int // checksum
            val magic = dv.int
            if (magic != (command xor -1)) return null
            if (buffer.size < 24 + len) return null
            val payload = ByteArray(len)
            System.arraycopy(buffer, 24, payload, 0, len)
            return AdbPacket(command, arg0, arg1, payload) to (24 + len)
        }
    }
}
