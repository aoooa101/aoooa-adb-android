package com.aoooa.adb

import com.aoooa.adb.log.UiLogStyle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiLogStyleTest {
    @Test
    fun recognizesChineseFailures() {
        listOf("TCP 连接失败", "ADB 认证错误", "操作异常", "连接超时", "无法打开文件", "设备未授权")
            .forEach { assertTrue(it, UiLogStyle.isError(it)) }
    }

    @Test
    fun recognizesEnglishFailures() {
        listOf("Connection failed", "Failure", "ERROR: connection refused", "SocketTimeoutException",
            "Request timed out", "Permission denied", "Cannot open file", "Device not connected")
            .forEach { assertTrue(it, UiLogStyle.isError(it)) }
    }

    @Test
    fun leavesNormalStatusUnchanged() {
        listOf("配对成功", "正在连接设备", "TCP connected", "Disconnected")
            .forEach { assertFalse(it, UiLogStyle.isError(it)) }
    }
}
