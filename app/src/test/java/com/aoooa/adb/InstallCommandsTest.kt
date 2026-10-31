package com.aoooa.adb

import com.aoooa.adb.adb.protocol.InstallCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 流式安装命令构造与会话号解析测试。
 */
class InstallCommandsTest {

    @Test
    fun testCreateCommand() {
        assertEquals(
            "exec:cmd package install-create -r -t -S 1048576",
            InstallCommands.create(1048576L)
        )
    }

    @Test
    fun testCreateFallbackCommand() {
        assertEquals(
            "exec:pm install-create -r -t -S 2048",
            InstallCommands.createFallback(2048L)
        )
    }

    @Test
    fun testWriteCommandCarriesSizeSessionAndStdinSuffix() {
        val cmd = InstallCommands.write(1000L, 42)
        assertEquals("exec:cmd package install-write -S 1000 42 base.apk -\u0000", cmd)
    }

    @Test
    fun testCommitCommands() {
        assertEquals("exec:cmd package install-commit 7", InstallCommands.commit(7))
        assertEquals("exec:pm install-commit 7", InstallCommands.commitFallback(7))
    }

    @Test
    fun testParseSessionIdFromSuccessOutput() {
        assertEquals(
            1234,
            InstallCommands.parseSessionId("Success: created install session [1234]")
        )
    }

    @Test
    fun testParseSessionIdFromMultiLineOutput() {
        val output = "Some warning line\nSuccess: created install session [5678]\n"
        assertEquals(5678, InstallCommands.parseSessionId(output))
    }

    @Test
    fun testParseSessionIdReturnsNullOnFailure() {
        assertNull(InstallCommands.parseSessionId("Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]"))
        assertNull(InstallCommands.parseSessionId(""))
        assertNull(InstallCommands.parseSessionId("Success"))
    }
}
