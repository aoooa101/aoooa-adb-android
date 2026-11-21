package com.aoooa.adb.adb

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class TcpEndpoint(val host: String, val port: Int) {
    val display: String get() = if (':' in host) "[$host]:$port" else "$host:$port"

    companion object {
        fun parse(text: String): TcpEndpoint? {
            val input = text.trim()
            val host: String
            val suffix: String
            if (input.startsWith('[')) {
                val end = input.indexOf(']')
                if (end < 2) return null
                host = input.substring(1, end)
                if (':' !in host) return null
                suffix = input.substring(end + 1)
            } else {
                host = input.substringBefore(':')
                suffix = input.substring(host.length)
            }
            if (host.isEmpty() || host.any { it.isWhitespace() || it.isISOControl() || it in "[]/\\?#@" }) return null
            val port = when {
                suffix.isEmpty() -> 5555
                suffix.startsWith(':') && suffix.drop(1).all { it in '0'..'9' } -> suffix.drop(1).toIntOrNull() ?: return null
                else -> return null
            }
            return if (port in 1..65535) TcpEndpoint(host, port) else null
        }
    }
}

/** 只保存当前进程中成功认证的 TCP 连接地址。 */
internal class TcpSessionHistory {
    private val endpoints = MutableStateFlow<List<TcpEndpoint>>(emptyList())
    val recent = endpoints.asStateFlow()

    fun record(endpoint: TcpEndpoint) {
        endpoints.update { old ->
            (listOf(endpoint) + old.filterNot { it.port == endpoint.port && it.host.equals(endpoint.host, ignoreCase = true) }).take(5)
        }
    }
}
