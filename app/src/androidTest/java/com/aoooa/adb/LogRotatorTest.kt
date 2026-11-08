package com.aoooa.adb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aoooa.adb.util.LogRotator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

/**
 * 日志淘汰工具的动态测试：受管文件筛选、占用统计、超期淘汰与容量淘汰。
 *
 * 用例直接在被测应用的日志目录中读写文件，因此每个用例开始与结束时都会清理
 * 本模块命名的日志文件，保证统计口径只包含用例自身创建的内容。
 */
@RunWith(AndroidJUnit4::class)
class LogRotatorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val mb = 1024L * 1024L
    private val dayMs = 24L * 60 * 60 * 1000
    private val created = mutableListOf<File>()

    private fun logDirs(): List<File> = listOfNotNull(
        context.getExternalFilesDir("logs"),
        File(context.filesDir, "logs")
    )

    private fun managedFiles(): List<File> = logDirs().flatMap { dir ->
        dir.listFiles()?.filter { LogRotator.isManagedLogFile(it) } ?: emptyList()
    }

    private fun cleanAll() {
        for (dir in logDirs()) {
            dir.listFiles()?.forEach { file ->
                if (LogRotator.isManagedLogFile(file)) file.delete()
            }
        }
        created.clear()
    }

    @Before
    fun setUp() {
        cleanAll()
    }

    @After
    fun tearDown() {
        cleanAll()
    }

    private fun newLogFile(name: String, size: Long = 16L): File {
        val dir = context.filesDir.resolve("logs").apply { mkdirs() }
        val file = File(dir, name)
        if (size <= 0) {
            file.writeText("")
        } else {
            RandomAccessFile(file, "rw").use { it.setLength(size) }
        }
        created.add(file)
        return file
    }

    // ---- 受管文件筛选 ----

    @Test
    fun testIsManagedLogFileFiltersByName() {
        val managed = newLogFile("aoooa-adb_20260101_120000.log")
        assertTrue(LogRotator.isManagedLogFile(managed))

        val wrongSuffix = File(managed.parentFile, "aoooa-adb_x.txt").apply { writeText("x") }
        val wrongPrefix = File(managed.parentFile, "other.log").apply { writeText("x") }
        assertFalse(LogRotator.isManagedLogFile(wrongSuffix))
        assertFalse(LogRotator.isManagedLogFile(wrongPrefix))
    }

    @Test
    fun testDirectoryWithLogNameIsNotManaged() {
        val dir = File(context.filesDir.resolve("logs"), "aoooa-adb_dir.log").apply { mkdirs() }
        assertFalse("目录不应被当作日志文件", LogRotator.isManagedLogFile(dir))
        dir.delete()
    }

    @Test
    fun testTotalSizeSumsManagedFilesOnly() {
        newLogFile("aoooa-adb_a.log", 100 * 1024)
        newLogFile("aoooa-adb_b.log", 200 * 1024)
        File(context.filesDir.resolve("logs"), "ignored.log").apply { writeText("x".repeat(4096)) }

        assertEquals(300L * 1024, LogRotator.totalSize(context))
    }

    @Test
    fun testLogFilesListsOnlyManagedEntries() {
        val a = newLogFile("aoooa-adb_list_a.log", 8)
        val b = newLogFile("aoooa-adb_list_b.log", 8)

        val names = LogRotator.logFiles(context).map { it.name }
        assertTrue(names.contains(a.name))
        assertTrue(names.contains(b.name))
        assertFalse(names.contains("ignored.log"))
    }

    // ---- 超期淘汰 ----

    @Test
    fun testRotateDeletesExpiredFiles() {
        val expired = newLogFile("aoooa-adb_expired.log", 1024)
        expired.setLastModified(System.currentTimeMillis() - 8 * dayMs)
        val fresh = newLogFile("aoooa-adb_fresh.log", 1024)

        val messages = mutableListOf<String>()
        LogRotator.rotate(context, logger = { messages.add(it) })

        assertFalse("超期文件应被删除", expired.exists())
        assertTrue("未超期文件应保留", fresh.exists())
        assertTrue("应记录淘汰日志，实际: $messages", messages.any { it.contains("淘汰超期日志") })
    }

    @Test
    fun testRotateKeepsCurrentLogFileEvenIfExpired() {
        val current = newLogFile("aoooa-adb_current.log", 1024)
        current.setLastModified(System.currentTimeMillis() - 30 * dayMs)

        LogRotator.rotate(context, currentLogFile = current)

        assertTrue("正在写入的日志文件不参与删除", current.exists())
    }

    @Test
    fun testRotateIgnoresZeroTimestamp() {
        val unknownTime = newLogFile("aoooa-adb_notime.log", 512)
        unknownTime.setLastModified(0L)

        LogRotator.rotate(context)

        assertTrue("时间戳不可读时不应删除，避免误删", unknownTime.exists())
    }

    // ---- 容量淘汰 ----

    @Test
    fun testRotateTrimsOldestFilesWhenOverQuota() {
        // 3 × 8MB = 24MB 超出 20MB 配额，应从最旧的文件开始淘汰
        val now = System.currentTimeMillis()
        val oldest = newLogFile("aoooa-adb_oldest.log", 8 * mb).apply { setLastModified(now - 3 * dayMs) }
        val middle = newLogFile("aoooa-adb_middle.log", 8 * mb).apply { setLastModified(now - 2 * dayMs) }
        val newest = newLogFile("aoooa-adb_newest.log", 8 * mb).apply { setLastModified(now - dayMs) }

        val messages = mutableListOf<String>()
        LogRotator.rotate(context, logger = { messages.add(it) })

        assertFalse("最旧文件应先被淘汰", oldest.exists())
        assertTrue(middle.exists())
        assertTrue(newest.exists())
        assertTrue("总量应回落到配额内，实际 ${LogRotator.totalSize(context)}", LogRotator.totalSize(context) <= 20 * mb)
        assertTrue(messages.any { it.contains("容量超限淘汰日志") })
    }

    @Test
    fun testRotateKeepsFilesWhenWithinQuota() {
        newLogFile("aoooa-adb_small_a.log", mb)
        newLogFile("aoooa-adb_small_b.log", mb)

        LogRotator.rotate(context)

        assertEquals("未超配额时不应删除任何文件", 2 * mb, LogRotator.totalSize(context))
    }
}
