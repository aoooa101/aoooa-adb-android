package com.aoooa.adb.adb.proxy

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/** ADB Smart Socket 的四位十六进制长度及状态报文。 */
internal object SmartSocketProtocol {
    fun readRequest(input: InputStream): String? {
        val first = input.read()
        if (first < 0) return null
        val header = byteArrayOf(first.toByte()) + readExactly(input, 3)
        val text = String(header, Charsets.US_ASCII)
        require(text.all { it in "0123456789abcdefABCDEF" }) { "invalid request length" }
        val length = text.toInt(16)
        require(length > 0) { "empty service" }
        return String(readExactly(input, length), Charsets.UTF_8)
    }

    fun readExactly(input: InputStream, length: Int): ByteArray {
        val data = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(data, offset, length - offset)
            if (count < 0) throw EOFException("truncated request")
            if (count == 0) throw java.io.IOException("Request reader made no progress")
            offset += count
        }
        return data
    }

    fun okay(output: OutputStream) {
        output.write("OKAY".toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    fun data(output: OutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 0xffff) { "response too large" }
        output.write(String.format(Locale.US, "%04x", bytes.size).toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    fun reply(output: OutputStream, text: String) {
        okay(output)
        data(output, text)
    }

    fun fail(output: OutputStream, reason: String) {
        output.write("FAIL".toByteArray(Charsets.US_ASCII))
        data(output, reason.take(4096))
    }
}
