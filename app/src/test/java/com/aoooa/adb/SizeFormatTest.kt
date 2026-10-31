package com.aoooa.adb

import com.aoooa.adb.util.SizeFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * 体积与运存格式化测试：单位切换边界与非正数兜底。
 */
class SizeFormatTest {

    private lateinit var originalLocale: Locale

    @Before
    fun setUp() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun testNonPositiveFileSize() {
        assertEquals("0 B", SizeFormat.fileSize(0))
        assertEquals("0 B", SizeFormat.fileSize(-1024))
    }

    @Test
    fun testByteRange() {
        assertEquals("1 B", SizeFormat.fileSize(1))
        assertEquals("1023 B", SizeFormat.fileSize(1023))
    }

    @Test
    fun testKilobyteRange() {
        assertEquals("1.0 KB", SizeFormat.fileSize(1024))
        assertEquals("1.5 KB", SizeFormat.fileSize(1536))
        assertEquals("1023.9 KB", SizeFormat.fileSize(1048473))
    }

    @Test
    fun testMegabyteRange() {
        assertEquals("1.00 MB", SizeFormat.fileSize(1048576))
        assertEquals("1.50 MB", SizeFormat.fileSize(1572864))
        assertEquals("2.00 MB", SizeFormat.fileSize(2097152))
    }

    @Test
    fun testRamSizeBelowOneGigabyte() {
        assertEquals("0 MB", SizeFormat.ramSize(0))
        assertEquals("0 MB", SizeFormat.ramSize(-1))
        assertEquals("1 MB", SizeFormat.ramSize(1048576))
        assertEquals("512 MB", SizeFormat.ramSize(536870912))
    }

    @Test
    fun testRamSizeAboveOneGigabyte() {
        assertEquals("1.0 GB", SizeFormat.ramSize(1073741824))
        assertEquals("1.5 GB", SizeFormat.ramSize(1610612736))
        assertEquals("8.0 GB", SizeFormat.ramSize(8589934592))
    }
}
