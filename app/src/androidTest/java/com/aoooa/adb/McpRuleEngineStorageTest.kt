package com.aoooa.adb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aoooa.adb.mcp.McpRule
import com.aoooa.adb.mcp.McpRuleEngine
import com.aoooa.adb.mcp.McpRuleType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MCP 安全规则的持久化与检测入口测试。
 *
 * 依赖真实 SharedPreferences 与 org.json：验证规则读写回环、类型容错回退、
 * 精准拦截与动词拦截的实际判定结果。用例结束会恢复原有的规则配置。
 */
@RunWith(AndroidJUnit4::class)
class McpRuleEngineStorageTest {

    private lateinit var originalRulesJson: String

    @Before
    fun setUp() {
        Prefs.init(ApplicationProvider.getApplicationContext<Context>())
        originalRulesJson = Prefs.mcpRulesJson
        McpRuleEngine.saveRules(emptyList())
    }

    @After
    fun tearDown() {
        Prefs.mcpRulesJson = originalRulesJson
    }

    // ---- 持久化 ----

    @Test
    fun testSaveAndLoadRoundTrip() {
        val rules = listOf(
            McpRule(id = "rule-1", type = McpRuleType.EXACT, pattern = "settings put global adb_enabled 0", description = "禁用调试"),
            McpRule(id = "rule-2", type = McpRuleType.VERB, pattern = "rm", description = "禁止删除")
        )
        McpRuleEngine.saveRules(rules)

        val loaded = McpRuleEngine.loadRules()
        assertEquals(2, loaded.size)
        assertEquals("rule-1", loaded[0].id)
        assertEquals(McpRuleType.EXACT, loaded[0].type)
        assertEquals("settings put global adb_enabled 0", loaded[0].pattern)
        assertEquals("禁用调试", loaded[0].description)
        assertEquals(McpRuleType.VERB, loaded[1].type)
        assertEquals("rm", loaded[1].pattern)
    }

    @Test
    fun testUnknownRuleTypeFallsBackToVerb() {
        Prefs.mcpRulesJson = """[{"id":"x","type":"UNKNOWN","pattern":"reboot"}]"""
        val loaded = McpRuleEngine.loadRules()
        assertEquals(1, loaded.size)
        assertEquals(McpRuleType.VERB, loaded[0].type)
        assertEquals("reboot", loaded[0].pattern)
    }

    @Test
    fun testMalformedJsonReturnsDefaultRules() {
        Prefs.mcpRulesJson = "{not-a-json"
        val loaded = McpRuleEngine.loadRules()
        assertEquals(McpRuleEngine.defaultRules, loaded)
    }

    @Test
    fun testBlankJsonReturnsEmptyRules() {
        Prefs.mcpRulesJson = "   "
        assertTrue(McpRuleEngine.loadRules().isEmpty())
    }

    // ---- 检测入口 ----

    @Test
    fun testVerbRuleBlocksDirectCommand() {
        McpRuleEngine.saveRules(listOf(McpRule(type = McpRuleType.VERB, pattern = "reboot")))
        val result = McpRuleEngine.check("reboot -p")
        assertFalse(result.allowed)
        assertEquals("reboot", result.matchedRule?.pattern)
        assertTrue(result.reason.isNotBlank())
    }

    @Test
    fun testVerbRuleBlocksChainedCommand() {
        McpRuleEngine.saveRules(listOf(McpRule(type = McpRuleType.VERB, pattern = "rm")))
        assertFalse(McpRuleEngine.check("ls && rm -rf /data/local/tmp/x").allowed)
        assertFalse(McpRuleEngine.check("echo hi | rm -rf /sdcard/x").allowed)
    }

    @Test
    fun testVerbRuleMatchesPathInvocation() {
        McpRuleEngine.saveRules(listOf(McpRule(type = McpRuleType.VERB, pattern = "rm")))
        assertFalse(McpRuleEngine.check("/system/bin/rm -rf /data/local/tmp/x").allowed)
    }

    @Test
    fun testUnrelatedCommandIsAllowed() {
        McpRuleEngine.saveRules(listOf(McpRule(type = McpRuleType.VERB, pattern = "rm")))
        assertTrue(McpRuleEngine.check("ls -l /sdcard").allowed)
    }

    @Test
    fun testExactRuleBlocksOnlyWholeCommand() {
        val pattern = "settings put global adb_enabled 0"
        McpRuleEngine.saveRules(listOf(McpRule(type = McpRuleType.EXACT, pattern = pattern)))

        assertFalse("首尾空格应被裁剪后命中", McpRuleEngine.check("  $pattern  ").allowed)
        assertFalse("精准匹配不区分大小写", McpRuleEngine.check(pattern.uppercase()).allowed)
        assertTrue("追加其他参数后不再属于精准命中", McpRuleEngine.check("$pattern && ls").allowed)
    }

    @Test
    fun testEmptyCommandIsAllowed() {
        McpRuleEngine.saveRules(listOf(McpRule(type = McpRuleType.VERB, pattern = "rm")))
        assertTrue(McpRuleEngine.check("   ").allowed)
    }

    @Test
    fun testRulesSummaryJsonStructure() {
        McpRuleEngine.saveRules(
            listOf(
                McpRule(type = McpRuleType.VERB, pattern = "rm"),
                McpRule(type = McpRuleType.EXACT, pattern = "fastboot flash", description = "禁止刷机")
            )
        )

        val json = McpRuleEngine.getRulesSummaryJson()
        assertEquals(2, json.getInt("total"))
        assertEquals(2, json.getJSONArray("rules").length())

        val first = json.getJSONArray("rules").getJSONObject(0)
        assertEquals("VERB", first.getString("mode"))
        assertEquals("rm", first.getString("pattern"))
        assertTrue(first.getString("modeDescription").isNotBlank())

        val second = json.getJSONArray("rules").getJSONObject(1)
        assertEquals("禁止刷机", second.getString("reason"))
        assertTrue(json.getString("message").isNotBlank())
    }

    @Test
    fun testEmptyRulesSummaryReportsEmptyMessage() {
        McpRuleEngine.saveRules(emptyList())
        val json = McpRuleEngine.getRulesSummaryJson()
        assertEquals(0, json.getInt("total"))
        assertEquals(0, json.getJSONArray("rules").length())
        assertTrue(json.getString("message").isNotBlank())
    }
}
