package com.aoooa.adb

import com.aoooa.adb.mcp.McpOriginGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP 请求来源安全校验测试：
 * 验证对本地合法 Origin 的放行，以及对 DNS Rebinding 与子域名绕过等恶意 payload 的拦截。
 */
class McpOriginGuardTest {

    @Test
    fun testTrustedLocalOriginsPass() {
        // 本地 IPv4 与域名
        assertTrue(McpOriginGuard.isTrustedLocalOrigin("http://localhost"))
        assertTrue(McpOriginGuard.isTrustedLocalOrigin("http://localhost:8080"))
        assertTrue(McpOriginGuard.isTrustedLocalOrigin("https://localhost:3000"))
        assertTrue(McpOriginGuard.isTrustedLocalOrigin("http://127.0.0.1"))
        assertTrue(McpOriginGuard.isTrustedLocalOrigin("http://127.0.0.1:8765"))

        // IPv6 环回地址
        assertTrue(McpOriginGuard.isTrustedLocalOrigin("http://[::1]"))
        assertTrue(McpOriginGuard.isTrustedLocalOrigin("http://[::1]:8080"))

        // 大小写不敏感校验
        assertTrue(McpOriginGuard.isTrustedLocalOrigin("HTTP://LOCALHOST:8080"))
        assertTrue(McpOriginGuard.isTrustedLocalOrigin("http://127.0.0.1:8080/"))
    }

    @Test
    fun testTrustedExtensionsPass() {
        // 浏览器扩展协议
        assertTrue(McpOriginGuard.isTrustedLocalOrigin("chrome-extension://abcdefghijklmnop"))
        assertTrue(McpOriginGuard.isTrustedLocalOrigin("moz-extension://12345678-1234-1234-1234-123456789abc"))
        assertTrue(McpOriginGuard.isTrustedLocalOrigin("safari-web-extension://com.example.extension"))

        // 编辑器 Webview 协议
        assertTrue(McpOriginGuard.isTrustedLocalOrigin("vscode-webview://12345678"))
        assertTrue(McpOriginGuard.isTrustedLocalOrigin("vscode-file://vscode-app"))
    }

    @Test
    fun testMaliciousSubdomainsBlocked() {
        // 子域名欺骗（包含 localhost 或 127.0.0.1 关键字的外部域名）
        assertFalse(McpOriginGuard.isTrustedLocalOrigin("http://localhost.evil.com"))
        assertFalse(McpOriginGuard.isTrustedLocalOrigin("http://localhost.attacker.com:8080"))
        assertFalse(McpOriginGuard.isTrustedLocalOrigin("http://127.0.0.1.attacker.com"))
        assertFalse(McpOriginGuard.isTrustedLocalOrigin("http://xlocalhost.com"))
        assertFalse(McpOriginGuard.isTrustedLocalOrigin("http://evil-localhost.org"))
        assertFalse(McpOriginGuard.isTrustedLocalOrigin("http://fake127.0.0.1.com"))
    }

    @Test
    fun testUserInfoAndPathConfusionBlocked() {
        // URL UserInfo 混淆（尝试将 localhost 置于用户名或密码字段）
        assertFalse(McpOriginGuard.isTrustedLocalOrigin("http://localhost@attacker.com"))
        assertFalse(McpOriginGuard.isTrustedLocalOrigin("http://127.0.0.1@evil.com:8080"))

        // 路径或查询参数混淆
        assertFalse(McpOriginGuard.isTrustedLocalOrigin("http://attacker.com/localhost"))
        assertFalse(McpOriginGuard.isTrustedLocalOrigin("http://attacker.com?param=127.0.0.1"))
    }

    @Test
    fun testUntrustedSchemesAndMalformedBlocked() {
        // 伪造或不受信任的协议
        assertFalse(McpOriginGuard.isTrustedLocalOrigin("vscode-malicious://payload"))
        assertFalse(McpOriginGuard.isTrustedLocalOrigin("javascript:alert(1)"))
        assertFalse(McpOriginGuard.isTrustedLocalOrigin("file:///sdcard/app.apk"))

        // 空串与畸形字符串
        assertFalse(McpOriginGuard.isTrustedLocalOrigin(""))
        assertFalse(McpOriginGuard.isTrustedLocalOrigin("   "))
        assertFalse(McpOriginGuard.isTrustedLocalOrigin("not a valid uri"))
    }
}
