package com.aoooa.adb

import com.aoooa.adb.mcp.McpRule
import com.aoooa.adb.mcp.McpRuleEngine
import com.aoooa.adb.mcp.McpRuleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP 安全规则引擎的纯解析逻辑测试。
 *
 * 覆盖串联命令切分（含引号保护）与命令动词提取（环境变量前缀、重定向、路径 basename）。
 * 依赖 SharedPreferences 的规则读写与检测入口分别在仪器化测试中验证。
 */
class McpRuleEngineTest {

    // ---- 串联命令切分 ----

    @Test
    fun testSplitAndOperator() {
        assertEquals(listOf("ls", "pwd"), McpRuleEngine.splitChainedCommands("ls && pwd"))
    }

    @Test
    fun testSplitOrOperator() {
        assertEquals(listOf("ls", "pwd"), McpRuleEngine.splitChainedCommands("ls || pwd"))
    }

    @Test
    fun testSplitSingleCharOperators() {
        assertEquals(listOf("a", "b"), McpRuleEngine.splitChainedCommands("a; b"))
        assertEquals(listOf("a", "b"), McpRuleEngine.splitChainedCommands("a | b"))
        assertEquals(listOf("a", "b"), McpRuleEngine.splitChainedCommands("a & b"))
        assertEquals(listOf("a", "b"), McpRuleEngine.splitChainedCommands("a\nb"))
    }

    @Test
    fun testSplitTrimsAndDropsEmptyParts() {
        assertEquals(listOf("a", "b"), McpRuleEngine.splitChainedCommands("  a ;; ; b  "))
        assertEquals(emptyList<String>(), McpRuleEngine.splitChainedCommands(""))
        assertEquals(emptyList<String>(), McpRuleEngine.splitChainedCommands("  ; ;  "))
    }

    @Test
    fun testDoubleQuotedContentIsNotSplit() {
        val result = McpRuleEngine.splitChainedCommands("echo \"a && b\"")
        assertEquals(1, result.size)
        assertEquals("echo \"a && b\"", result[0])
    }

    @Test
    fun testSingleQuotedContentIsNotSplit() {
        val result = McpRuleEngine.splitChainedCommands("sh -c 'x ; y'")
        assertEquals(1, result.size)
        assertEquals("sh -c 'x ; y'", result[0])
    }

    @Test
    fun testOperatorsAroundQuotedSegments() {
        val result = McpRuleEngine.splitChainedCommands("echo \"a | b\" && pwd")
        assertEquals(2, result.size)
        assertEquals("echo \"a | b\"", result[0])
        assertEquals("pwd", result[1])
    }

    @Test
    fun testSplitKeepsChainedVerbsInOrder() {
        val result = McpRuleEngine.splitChainedCommands("ls > /sdcard/out.txt && rm -rf /sdcard/old | cat")
        assertEquals(3, result.size)
        assertEquals("ls > /sdcard/out.txt", result[0])
        assertEquals("rm -rf /sdcard/old", result[1])
        assertEquals("cat", result[2])
    }

    // ---- 命令动词提取 ----

    @Test
    fun testExtractPlainVerb() {
        assertEquals("reboot", McpRuleEngine.extractCommandVerb("reboot"))
        assertEquals("pm", McpRuleEngine.extractCommandVerb("pm list packages"))
    }

    @Test
    fun testExtractSkipsEnvironmentAssignment() {
        assertEquals("reboot", McpRuleEngine.extractCommandVerb("A=1 B=2 reboot"))
    }

    @Test
    fun testExtractUsesBasenameForPath() {
        assertEquals("rm", McpRuleEngine.extractCommandVerb("/system/bin/rm -rf /data/local/tmp/x"))
        assertEquals("ls", McpRuleEngine.extractCommandVerb("./bin/ls -l"))
    }

    @Test
    fun testExtractStripsLeadingBackslashAndQuotes() {
        assertEquals("ls", McpRuleEngine.extractCommandVerb("\\ls -l"))
        assertEquals("reboot", McpRuleEngine.extractCommandVerb("\"reboot\""))
    }

    @Test
    fun testExtractSkipsRedirectionToken() {
        assertEquals("echo", McpRuleEngine.extractCommandVerb("echo hi 2>&1"))
        assertNull("只有重定向符号时不应提取出动词", McpRuleEngine.extractCommandVerb("2>&1"))
        // 重定向号被跳过后，紧随其后的目标路径按 basename 规则成为唯一候选动词
        assertEquals("null", McpRuleEngine.extractCommandVerb("> /dev/null"))
        assertNull(McpRuleEngine.extractCommandVerb(">"))
    }

    @Test
    fun testExtractReturnsNullForBlankInput() {
        assertNull(McpRuleEngine.extractCommandVerb(""))
        assertNull(McpRuleEngine.extractCommandVerb("   "))
    }

    // ---- 规则模型 ----

    @Test
    fun testRuleDefaults() {
        val rule = McpRule(pattern = "reboot")
        assertEquals(McpRuleType.VERB, rule.type)
        assertEquals("", rule.description)
        assertTrue("默认应生成非空 id", rule.id.isNotBlank())
        assertNotEquals("不同规则的默认 id 不应重复", rule.id, McpRule(pattern = "reboot").id)
    }

    @Test
    fun testDefaultRulesAreEmpty() {
        assertTrue("默认不预设拦截规则，应由用户按需添加", McpRuleEngine.defaultRules.isEmpty())
    }
}
