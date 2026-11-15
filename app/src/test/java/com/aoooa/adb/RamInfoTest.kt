package com.aoooa.adb

import com.aoooa.adb.util.RamInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * /proc/meminfo 解析测试：标准输出解析、字段缺失兜底与字节单位换算。
 */
class RamInfoTest {

    @Test
    fun testStandardMemInfoReturnsUsedAndTotalInBytes() {
        val meminfo = """
            MemTotal:        3900 kB
            MemFree:          800 kB
            MemAvailable:     1800 kB
            Buffers:         100 kB
        """.trimIndent()
        val result = RamInfo.parseMemInfo(meminfo)
        assertNotNull(result)
        val used = (3900 - 1800) * 1024L
        val total = 3900 * 1024L
        assertEquals(used, result!!.first)
        assertEquals(total, result.second)
    }

    @Test
    fun testMissingMemAvailableTreatsUsedAsTotal() {
        val meminfo = "MemTotal:        3900 kB\nMemFree:          800 kB\n"
        val result = RamInfo.parseMemInfo(meminfo)
        assertNotNull(result)
        // 无 MemAvailable 时 avail 计为 0，已用等于总量
        assertEquals(3900 * 1024L, result!!.first)
        assertEquals(3900 * 1024L, result.second)
    }

    @Test
    fun testMissingMemTotalReturnsNull() {
        val meminfo = "MemAvailable:     1800 kB\nMemFree:          800 kB\n"
        assertNull(RamInfo.parseMemInfo(meminfo))
    }

    @Test
    fun testEmptyInputReturnsNull() {
        assertNull(RamInfo.parseMemInfo(""))
    }

    @Test
    fun testAvailableLargerThanTotalClampsUsedToZero() {
        val meminfo = "MemTotal:        1000 kB\nMemAvailable:     2000 kB\n"
        val result = RamInfo.parseMemInfo(meminfo)
        assertNotNull(result)
        // avail > total 的脏输入不会产生负的已用值
        assertEquals(0L, result!!.first)
        assertEquals(1000 * 1024L, result.second)
    }

    @Test
    fun testCompactFormatWithoutSpaces() {
        val meminfo = "MemTotal:1234 kB\nMemAvailable:567 kB\n"
        val result = RamInfo.parseMemInfo(meminfo)
        assertNotNull(result)
        assertEquals((1234 - 567) * 1024L, result!!.first)
        assertEquals(1234 * 1024L, result.second)
    }

    @Test
    fun testRealisticProcMeminfo() {
        val meminfo = """
            MemTotal:       11898424 kB
            MemFree:          284536 kB
            MemAvailable:    7823104 kB
            Buffers:          332920 kB
            Cached:          6336416 kB
            SwapCached:            0 kB
            Active:          5123456 kB
            Inactive:        2567890 kB
        """.trimIndent()
        val result = RamInfo.parseMemInfo(meminfo)
        assertNotNull(result)
        assertEquals((11898424 - 7823104) * 1024L, result!!.first)
        assertEquals(11898424 * 1024L, result.second)
    }
}
