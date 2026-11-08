package com.aoooa.adb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aoooa.adb.model.CommandItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 设置持久化的动态测试：快捷指令读写、旧版命令自动迁移、自定义分类与 MCP 端口/令牌。
 *
 * 用例结束后通过公开 API 还原被修改的配置项，避免污染真机上的实际设置。
 */
@RunWith(AndroidJUnit4::class)
class PrefsTest {

    private lateinit var originalCommands: List<CommandItem>
    private lateinit var originalCategories: List<String>
    private var originalPort: Int = 0
    private lateinit var originalToken: String

    @Before
    fun setUp() {
        Prefs.init(ApplicationProvider.getApplicationContext<Context>())
        originalCommands = Prefs.loadCommands()
        originalCategories = Prefs.loadCustomCategories()
        originalPort = Prefs.mcpPort
        originalToken = Prefs.mcpAuthToken
    }

    @After
    fun tearDown() {
        Prefs.saveCommands(originalCommands)
        Prefs.saveCustomCategories(originalCategories)
        Prefs.mcpPort = originalPort
        Prefs.mcpAuthToken = originalToken
    }

    // ---- 快捷指令 ----

    @Test
    fun testCommandSaveAndLoadRoundTrip() {
        val commands = listOf(
            CommandItem(
                id = "test_a",
                nameZh = "测试甲",
                nameEn = "Test A",
                command = "dumpsys battery",
                category = "system",
                isBuiltin = false
            ),
            CommandItem(
                id = "test_b",
                nameZh = "测试乙",
                nameEn = "Test B",
                command = "logcat -d -t 50",
                category = "log",
                isBuiltin = true
            )
        )
        Prefs.saveCommands(commands)

        val loaded = Prefs.loadCommands()
        assertEquals(2, loaded.size)
        assertEquals("test_a", loaded[0].id)
        assertEquals("测试甲", loaded[0].nameZh)
        assertEquals("dumpsys battery", loaded[0].command)
        assertFalse(loaded[0].isBuiltin)
        assertEquals("log", loaded[1].category)
        assertTrue(loaded[1].isBuiltin)
    }

    @Test
    fun testLegacyShizukuCommandIsUpgraded() {
        val legacy = listOf(
            CommandItem(
                id = "cmd_shizuku",
                nameZh = "启动 Shizuku 服务",
                nameEn = "Start Shizuku Service",
                command = "sh /sdcard/Android/data/moe.shizuku.privileged.api/starter.sh",
                category = "framework",
                isBuiltin = true
            )
        )
        Prefs.saveCommands(legacy)

        val loaded = Prefs.loadCommands()
        assertEquals(1, loaded.size)
        assertTrue(
            "旧版单路径命令应升级为多路径命令，实际: ${loaded[0].command}",
            loaded[0].command.contains("||") && loaded[0].command.contains("start.sh")
        )
    }

    @Test
    fun testEmptyCommandListFallsBackToDefaults() {
        Prefs.saveCommands(emptyList())
        val loaded = Prefs.loadCommands()
        assertTrue("空配置应回退到默认指令列表", loaded.isNotEmpty())
        assertTrue(loaded.any { it.id == "cmd_shizuku" })
    }

    @Test
    fun testResetDefaultCommandsPersists() {
        Prefs.saveCommands(emptyList())
        val defaults = Prefs.resetDefaultCommands()

        assertTrue(defaults.isNotEmpty())
        assertEquals(defaults.size, Prefs.loadCommands().size)
    }

    @Test
    fun testDefaultCommandsHaveUniqueIdsAndCommands() {
        val defaults = Prefs.getDefaultCommands()
        assertTrue("默认指令不应为空", defaults.isNotEmpty())
        assertEquals("默认指令 id 应唯一", defaults.size, defaults.map { it.id }.toSet().size)
        assertTrue("默认指令命令体不应为空", defaults.all { it.command.isNotBlank() })
        assertTrue("默认指令应同时提供中英文名称", defaults.all { it.nameZh.isNotBlank() && it.nameEn.isNotBlank() })
    }

    // ---- 自定义分类 ----

    @Test
    fun testCustomCategoryAddDedupesAndTrims() {
        Prefs.saveCustomCategories(emptyList())

        Prefs.addCustomCategory("  我的分类  ")
        Prefs.addCustomCategory("我的分类")
        Prefs.addCustomCategory("   ")

        val loaded = Prefs.loadCustomCategories()
        assertEquals(1, loaded.size)
        assertEquals("我的分类", loaded[0])
    }

    @Test
    fun testBlankCategoriesAreDroppedOnSave() {
        Prefs.saveCustomCategories(listOf("a", "  ", "", "b"))
        assertEquals(listOf("a", "b"), Prefs.loadCustomCategories())
    }

    // ---- MCP 配置 ----

    @Test
    fun testMcpPortIsCoercedIntoValidRange() {
        Prefs.mcpPort = 80
        assertEquals("低于 1024 的端口应被夹取", 1024, Prefs.mcpPort)

        Prefs.mcpPort = 70000
        assertEquals("超过 65535 的端口应被夹取", 65535, Prefs.mcpPort)

        Prefs.mcpPort = 9999
        assertEquals(9999, Prefs.mcpPort)
    }

    @Test
    fun testMcpAuthTokenIsGeneratedOnceAndStable() {
        Prefs.mcpAuthToken = ""
        val first = Prefs.mcpAuthToken

        assertEquals("令牌应为 32 位十六进制（UUID 去横线）", 32, first.length)
        assertTrue("令牌应仅含十六进制字符", first.all { it.isDigit() || it in 'a'..'f' })
        assertEquals("重复读取应返回同一令牌", first, Prefs.mcpAuthToken)
    }

    @Test
    fun testMcpAuthTokenCanBeReplaced() {
        Prefs.mcpAuthToken = "custom-token-value"
        assertEquals("custom-token-value", Prefs.mcpAuthToken)
        assertNotEquals("替换后不应回退到自动生成的令牌", 32, Prefs.mcpAuthToken.length)
    }
}
