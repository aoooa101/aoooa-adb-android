package com.aoooa.adb.log

import java.io.Reader

internal object BoundedTextPreview {
    fun read(reader: Reader, limit: Int = 65536): Pair<String, Boolean> {
        require(limit > 0)
        val chars = CharArray(limit)
        var count = 0
        while (count < limit) {
            val n = reader.read(chars, count, limit - count)
            if (n < 0) return String(chars, 0, count) to false
            if (n == 0) throw java.io.IOException("Log preview reader made no progress")
            count += n
        }
        val next = reader.read()
        if (next >= 0 && Character.isLowSurrogate(next.toChar()) && Character.isHighSurrogate(chars[count - 1])) count--
        return String(chars, 0, count) to (next >= 0)
    }
}
