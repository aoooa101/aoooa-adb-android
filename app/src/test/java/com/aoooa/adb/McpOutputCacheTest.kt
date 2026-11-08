package com.aoooa.adb

import com.aoooa.adb.mcp.McpOutputCache
import com.aoooa.adb.mcp.PagingSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP 长输出分页缓存测试：页数计算、页码夹取、单坑位替换与末页回收。
 *
 * 缓存对象为全局单例，每个用例结束后显式清理，避免定时器或残留会话影响后续断言。
 */
class McpOutputCacheTest {

    @After
    fun tearDown() {
        McpOutputCache.clearSession()
    }

    private fun lines(count: Int): List<String> = (1..count).map { "line$it" }

    // ---- PagingSession 分页计算 ----

    @Test
    fun testEmptySessionHasSinglePage() {
        val session = PagingSession("id", "cmd", emptyList(), pageSize = 120)
        assertEquals(1, session.totalPages)
        assertEquals(emptyList<String>(), session.getPage(1))
        assertTrue(session.isLastPage(1))
    }

    @Test
    fun testTotalPagesRoundsUp() {
        assertEquals(1, PagingSession("id", "cmd", lines(120), pageSize = 120).totalPages)
        assertEquals(2, PagingSession("id", "cmd", lines(121), pageSize = 120).totalPages)
        assertEquals(2, PagingSession("id", "cmd", lines(240), pageSize = 120).totalPages)
        assertEquals(3, PagingSession("id", "cmd", lines(241), pageSize = 120).totalPages)
    }

    @Test
    fun testGetPageReturnsSlicedLines() {
        val session = PagingSession("id", "cmd", lines(5), pageSize = 2)
        assertEquals(listOf("line1", "line2"), session.getPage(1))
        assertEquals(listOf("line3", "line4"), session.getPage(2))
        assertEquals(listOf("line5"), session.getPage(3))
    }

    @Test
    fun testGetPageClampsOutOfRange() {
        val session = PagingSession("id", "cmd", lines(5), pageSize = 2)
        assertEquals(session.getPage(1), session.getPage(0))
        assertEquals(session.getPage(1), session.getPage(-7))
        assertEquals(session.getPage(3), session.getPage(99))
    }

    @Test
    fun testIsLastPage() {
        val session = PagingSession("id", "cmd", lines(5), pageSize = 2)
        assertFalse(session.isLastPage(1))
        assertFalse(session.isLastPage(2))
        assertTrue(session.isLastPage(3))
        assertTrue(session.isLastPage(10))
    }

    // ---- McpOutputCache 单坑位缓存 ----

    @Test
    fun testShortOutputBypassesCache() {
        val output = lines(3).joinToString("\n")
        val (content, truncated) = McpOutputCache.registerOutput("cmd", output, pageSize = 120)

        assertEquals(output, content)
        assertFalse(truncated)
        assertNull("短输出不应建立翻页缓存", McpOutputCache.readPage(1))
    }

    @Test
    fun testOutputExactlyAtPageLimitBypassesCache() {
        val output = lines(120).joinToString("\n")
        val (content, truncated) = McpOutputCache.registerOutput("cmd", output, pageSize = 120)

        assertEquals(output, content)
        assertFalse(truncated)
        assertNull(McpOutputCache.readPage(1))
    }

    @Test
    fun testLongOutputReturnsFirstPageWithHint() {
        val output = lines(5).joinToString("\n")
        val (content, truncated) = McpOutputCache.registerOutput("cmd", output, pageSize = 2)

        assertTrue(truncated)
        assertTrue("首页应包含前两行", content.startsWith("line1\nline2"))
        assertTrue("首页应提示页码 1/3，实际: $content", content.contains("1/3"))
    }

    @Test
    fun testReadMiddlePageThenLastPageReleasesCache() {
        McpOutputCache.registerOutput("cmd", lines(5).joinToString("\n"), pageSize = 2)

        val second = McpOutputCache.readPage(2)
        assertTrue("第 2 页应包含 line3/line4，实际: $second", second!!.startsWith("line3\nline4"))
        assertTrue(second.contains("2/3"))

        val third = McpOutputCache.readPage(3)
        assertTrue("末页应包含 line5，实际: $third", third!!.startsWith("line5"))
        assertTrue("末页应提示缓存已释放", third.contains("已读至最后一页"))

        assertNull("末页读取后应回收缓存", McpOutputCache.readPage(1))
    }

    @Test
    fun testSingleSlotIsReplacedByNewOutput() {
        McpOutputCache.registerOutput("first", lines(4).joinToString("\n"), pageSize = 1)
        McpOutputCache.registerOutput("second", (1..6).map { "new$it" }.joinToString("\n"), pageSize = 3)

        val page = McpOutputCache.readPage(1)!!
        assertTrue("应只保留最近一次输出", page.startsWith("new1\nnew2\nnew3"))
        assertTrue("新会话总页数应为 2，实际: $page", page.contains("1/2"))
    }

    @Test
    fun testShortOutputClearsPreviousLongSession() {
        McpOutputCache.registerOutput("cmd", lines(9).joinToString("\n"), pageSize = 2)
        McpOutputCache.registerOutput("cmd", "ok", pageSize = 2)

        assertNull("短输出应释放上一次的翻页缓存", McpOutputCache.readPage(1))
    }

    @Test
    fun testClearSessionIsIdempotent() {
        McpOutputCache.registerOutput("cmd", lines(9).joinToString("\n"), pageSize = 2)
        McpOutputCache.clearSession()
        McpOutputCache.clearSession()

        assertNull(McpOutputCache.readPage(1))
    }

    @Test
    fun testReadPageWithoutSessionReturnsNull() {
        McpOutputCache.clearSession()
        assertNull(McpOutputCache.readPage(1))
    }
}
