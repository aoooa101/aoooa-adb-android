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
                if (!isIpv6(host)) return null
                suffix = input.substring(end + 1)
            } else if (input.indexOf(':') != input.lastIndexOf(':')) {
                if (!isIpv6(input)) return null
                host = input
                suffix = ""
            } else {
                host = input.substringBefore(':')
                if (!isIpv4(host)) return null
                suffix = input.substring(host.length)
            }
            val port = when {
                suffix.isEmpty() -> 5555
                suffix.startsWith(':') && suffix.drop(1).all { it in '0'..'9' } -> suffix.drop(1).toIntOrNull() ?: return null
                else -> return null
            }
            return if (port in 1..65535) TcpEndpoint(host, port) else null
        }

        private fun isIpv4(host: String): Boolean {
            if (host.length !in 7..15) return false
            val parts = host.split('.')
            return parts.size == 4 && parts.all { part ->
                part.length in 1..3 && (part.length == 1 || part[0] != '0') &&
                    part.all { it in '0'..'9' } && part.toInt() <= 255
            }
        }

        private fun isIpv6(host: String): Boolean {
            val address = host.substringBefore('%')
            if (address.length !in 2..45) return false
            if ('%' in host) {
                val zone = host.substringAfter('%')
                if (zone.isEmpty() || zone.any { it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' && it !in "_.-" }) return false
            }
            val compression = address.indexOf("::")
            if (compression >= 0 && address.indexOf("::", compression + 1) >= 0) return false
            val groups = if (compression < 0) address.split(':') else {
                val left = address.substring(0, compression)
                val right = address.substring(compression + 2)
                (if (left.isEmpty()) emptyList() else left.split(':')) +
                    (if (right.isEmpty()) emptyList() else right.split(':'))
            }
            var units = 0
            for ((index, group) in groups.withIndex()) {
                if ('.' in group) {
                    if (index != groups.lastIndex || !address.endsWith(group) || !isIpv4(group)) return false
                    units += 2
                } else {
                    if (group.length !in 1..4 || group.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return false
                    units++
                }
            }
            return if (compression < 0) units == 8 else units < 8
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
