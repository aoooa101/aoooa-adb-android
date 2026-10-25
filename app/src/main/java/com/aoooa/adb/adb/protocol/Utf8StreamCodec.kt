package com.aoooa.adb.adb.protocol

import java.io.ByteArrayOutputStream

/**
 * UTF-8 流式解码工具。
 *
 * 用于处理被 ADB 报文边界截断的多字节字符序列：只解码完整 UTF-8 前缀，
 * 末尾不完整的残余字节留待与后续报文拼接后再解码，避免产生 U+FFFD 替换字符。
 */
object Utf8StreamCodec {

    /**
     * 计算字节数组中完整 UTF-8 序列的前缀长度。
     * 若最后一个多字节序列被截断，返回其首字节下标，截断部分留待与后续数据重组。
     */
    fun completePrefixLength(bytes: ByteArray): Int {
        val n = bytes.size
        var i = n - 1
        var lastLead = -1
        // 自尾部向前最多回溯 4 字节，定位最后一个 UTF-8 首字节（非 10xxxxxx 续字节）
        while (i >= 0 && i >= n - 4) {
            val b = bytes[i].toInt() and 0xFF
            if ((b and 0xC0) != 0x80) {
                lastLead = i
                break
            }
            i--
        }
        if (lastLead < 0) return n
        val lead = bytes[lastLead].toInt() and 0xFF
        val expected = when {
            (lead and 0x80) == 0 -> 1
            (lead and 0xE0) == 0xC0 -> 2
            (lead and 0xF0) == 0xE0 -> 3
            (lead and 0xF8) == 0xF0 -> 4
            else -> return n
        }
        return if (n - lastLead >= expected) n else lastLead
    }

    /**
     * 解码字节缓冲中的完整 UTF-8 前缀，末尾不完整的多字节残余写入 [carry] 等待与后续数据合并。
     * @return 本次可安全解码的文本
     */
    fun decodeWithCarry(bytes: ByteArray, carry: ByteArrayOutputStream): String {
        val valid = completePrefixLength(bytes)
        val text = if (valid > 0) String(bytes, 0, valid, Charsets.UTF_8) else ""
        carry.reset()
        if (valid < bytes.size) {
            carry.write(bytes, valid, bytes.size - valid)
        }
        return text
    }
}
