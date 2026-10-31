package com.aoooa.adb

import com.aoooa.adb.log.LogKind
import com.aoooa.adb.log.LogParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志行解析与分类测试：logcat 两种格式与崩溃/ANR/堆栈/系统日志分类。
 */
class LogParserTest {

    @Test
    fun testThreadtimeFormatParsing() {
        val line = LogParser.parse("09-06 12:34:56.789  1234  1250 I MyTag: hello device")
        assertEquals("09-06 12:34:56.789", line.time)
        assertEquals("1234", line.pid)
        assertEquals("1250", line.tid)
        assertEquals("I", line.level)
        assertEquals("MyTag", line.tag)
        assertEquals("hello device", line.message)
        assertEquals(LogKind.NORMAL, line.kind)
    }

    @Test
    fun testTimeFormatParsing() {
        val raw = "09-06 12:34:56.789 W/WindowManager(2000): screen frozen"
        val line = LogParser.parse(raw)
        assertEquals("09-06 12:34:56.789", line.time)
        assertEquals("W", line.level)
        assertEquals("WindowManager", line.tag)
        assertEquals("2000", line.pid)
        assertEquals("screen frozen", line.message)
        assertEquals(raw, line.raw)
    }

    @Test
    fun testFatalExceptionClassifiedAsCrash() {
        val line = LogParser.parse(
            "09-06 12:34:56.789 E/AndroidRuntime(1234): FATAL EXCEPTION: main"
        )
        assertEquals(LogKind.CRASH, line.kind)
    }

    @Test
    fun testProcessLineClassifiedAsCrash() {
        val line = LogParser.parse("  Process: com.example.app, PID: 4321")
        assertEquals(LogKind.CRASH, line.kind)
    }

    @Test
    fun testAnrClassification() {
        val line = LogParser.parse(
            "09-06 12:34:56.789  1234  1250 E ActivityManager: ANR in com.example.app (.MainActivity)"
        )
        assertEquals(LogKind.ANR, line.kind)
    }

    @Test
    fun testJavaStackFrameClassifiedAsStack() {
        val line = LogParser.parse("    at com.example.Foo.bar(Foo.kt:42)")
        assertEquals(LogKind.STACK, line.kind)
    }

    @Test
    fun testCausedByClassifiedAsStack() {
        val line = LogParser.parse("  Caused by: java.lang.NullPointerException: null")
        assertEquals(LogKind.STACK, line.kind)
    }

    @Test
    fun testMoreFramesClassifiedAsStack() {
        val line = LogParser.parse("\t... 12 more")
        assertEquals(LogKind.STACK, line.kind)
    }

    @Test
    fun testNativeCrashClassifiedAsCrash() {
        val line = LogParser.parse(
            "09-06 12:34:56.789  1234  1250 F DEBUG: Fatal signal 11 (SIGSEGV), code 1"
        )
        assertEquals(LogKind.CRASH, line.kind)
    }

    @Test
    fun testSystemComponentErrorClassifiedAsSystem() {
        val line = LogParser.parse(
            "09-06 12:34:56.789  1234  1250 E ActivityManager: low memory, killing proc"
        )
        assertEquals(LogKind.SYSTEM, line.kind)
    }

    @Test
    fun testApplicationErrorClassifiedAsError() {
        val line = LogParser.parse(
            "09-06 12:34:56.789  1234  1250 E MyApp: upload failed"
        )
        assertEquals(LogKind.ERROR, line.kind)
    }

    @Test
    fun testPlainLineKeepsRawAsMessage() {
        val raw = "some unformatted output"
        val line = LogParser.parse(raw)
        assertEquals(raw, line.message)
        assertEquals(raw, line.raw)
        assertEquals(LogKind.NORMAL, line.kind)
    }

    @Test
    fun testSystemTagSetCoversCrashProducers() {
        assertTrue(LogParser.reSystemTags.contains("AndroidRuntime"))
        assertTrue(LogParser.reSystemTags.contains("DEBUG"))
        assertTrue(LogParser.reSystemTags.contains("lowmemorykiller"))
    }
}
