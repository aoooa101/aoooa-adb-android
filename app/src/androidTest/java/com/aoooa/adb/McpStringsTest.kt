package com.aoooa.adb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aoooa.adb.mcp.McpStrings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MCP 工具文案的双语完整性检查。
 *
 * McpStrings 通过 Prefs.lang 动态切换语言，因此需在真实 SharedPreferences 环境下
 * 逐条读取属性 getter，校验两种语言下均无空文案、英文分支不残留中文。
 */
@RunWith(AndroidJUnit4::class)
class McpStringsTest {

    private lateinit var originalLang: String

    private val cjkRegex = Regex("[\\u4e00-\\u9fff]")

    @Before
    fun setUp() {
        Prefs.init(ApplicationProvider.getApplicationContext<Context>())
        originalLang = Prefs.lang
    }

    @After
    fun tearDown() {
        Prefs.lang = originalLang
    }

    /** 通过反射读取全部无参 String 属性（getter），规避逐个手写清单。 */
    private fun readAll(): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (method in McpStrings::class.java.declaredMethods) {
            if (method.parameterCount != 0) continue
            if (method.returnType != String::class.java) continue
            if (!method.name.startsWith("get")) continue
            if (!java.lang.reflect.Modifier.isPublic(method.modifiers)) continue
            val value = method.invoke(McpStrings) as? String ?: ""
            result[method.name.removePrefix("get").replaceFirstChar { it.lowercase() }] = value
        }
        return result
    }

    @Test
    fun testChineseStringsAreFilled() {
        Prefs.lang = "zh"
        val blank = readAll().filterValues { it.isBlank() }.keys
        assertTrue("中文 MCP 文案存在空值: $blank", blank.isEmpty())
    }

    @Test
    fun testEnglishStringsAreFilled() {
        Prefs.lang = "en"
        val blank = readAll().filterValues { it.isBlank() }.keys
        assertTrue("英文 MCP 文案存在空值: $blank", blank.isEmpty())
    }

    @Test
    fun testEnglishStringsHaveNoChineseCharacters() {
        Prefs.lang = "en"
        val offenders = readAll().filterValues { cjkRegex.containsMatchIn(it) }.keys
        assertTrue("英文 MCP 文案残留中文: $offenders", offenders.isEmpty())
    }

    @Test
    fun testChineseAndEnglishDiffer() {
        Prefs.lang = "zh"
        val zh = readAll()
        Prefs.lang = "en"
        val en = readAll()

        assertEquals("两种语言应暴露相同的文案集合", zh.keys, en.keys)
        val identical = zh.filter { (name, value) -> en[name] == value }.keys
        assertTrue("中英文文案完全相同，疑似漏译: $identical", identical.isEmpty())
    }

    @Test
    fun testBlockedAndErrorMessagesIncludeDetail() {
        Prefs.lang = "zh"
        val reason = "禁止删除"
        val blocked = McpStrings.shellBlocked(reason)
        assertTrue("拦截提示应包含规则说明，实际: $blocked", blocked.contains(reason))

        Prefs.lang = "en"
        val blockedEn = McpStrings.shellBlocked(reason)
        assertTrue("英文拦截提示应包含规则说明，实际: $blockedEn", blockedEn.contains(reason))
        assertTrue(blockedEn.contains("Blocked", ignoreCase = true))

        assertTrue(McpStrings.shellUnknownTool("custom_tool").contains("custom_tool"))
    }
}
