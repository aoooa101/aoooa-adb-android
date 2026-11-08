package com.aoooa.adb.mcp

import java.net.URI

/**
 * MCP 请求来源安全校验工具：
 * 校验 HTTP Origin 请求头，防御 DNS Rebinding 与非受信任跨域请求。
 */
object McpOriginGuard {

    private val TRUSTED_SCHEMES = listOf(
        "chrome-extension://",
        "moz-extension://",
        "safari-web-extension://",
        "vscode-webview://",
        "vscode-file://"
    )

    private val TRUSTED_LOCAL_HOSTS = setOf(
        "localhost",
        "127.0.0.1",
        "::1",
        "[::1]"
    )

    /**
     * 判断 Origin 是否为受信任的本地地址或扩展来源。
     */
    fun isTrustedLocalOrigin(origin: String): Boolean {
        val raw = origin.trim()
        if (raw.isEmpty()) return false
        val lower = raw.lowercase()

        // 1. 匹配受信任的浏览器扩展与编辑器插件 Scheme 前缀
        if (TRUSTED_SCHEMES.any { lower.startsWith(it) }) {
            return true
        }

        // 2. 解析 URI 并校验 Scheme 与 Host
        val uri = runCatching { URI(lower) }.getOrNull() ?: return false
        val scheme = uri.scheme ?: return false
        if (scheme != "http" && scheme != "https") {
            return false
        }

        val host = uri.host ?: return false
        return host in TRUSTED_LOCAL_HOSTS
    }
}
