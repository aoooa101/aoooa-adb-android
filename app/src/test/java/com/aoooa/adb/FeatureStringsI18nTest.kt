package com.aoooa.adb

import com.aoooa.adb.ui.i18n.*
import org.junit.Assert.*
import org.junit.Test

/** 检查独立功能文案的完整性、格式参数与主语言映射。 */
class FeatureStringsI18nTest {
    private fun strings(value: Any): Map<String, String> = value.javaClass.declaredFields
        .filter { it.type == String::class.java }
        .associate { field ->
            field.isAccessible = true
            field.name to (field.get(value) as String)
        }

    @Test fun featureTranslationsAreComplete() {
        val placeholders = Regex("%(?:\\d+\\$)?[sdf]")
        listOf(ProxyI18n.zh to ProxyI18n.en, CompletionI18n.zh to CompletionI18n.en,
            I18n.zh.control to I18n.en.control).forEach { (zh, en) ->
            val chinese = strings(zh)
            val english = strings(en)
            assertTrue(chinese.isNotEmpty())
            assertEquals(chinese.keys, english.keys)
            chinese.forEach { (key, value) ->
                val translated = english.getValue(key)
                assertTrue(key, value.isNotBlank() && translated.isNotBlank())
                assertFalse(key, Regex("[\u4e00-\u9fff]").containsMatchIn(translated))
                assertEquals(key, placeholders.findAll(value).map { it.value }.toList(),
                    placeholders.findAll(translated).map { it.value }.toList())
            }
        }
    }

    @Test fun featureTranslationsFollowParentLanguage() {
        assertSame(ProxyI18n.zh, I18n.zh.proxy)
        assertSame(ProxyI18n.en, I18n.en.proxy)
        assertSame(CompletionI18n.zh, I18n.zh.completion)
        assertSame(CompletionI18n.en, I18n.en.completion)
    }

    @Test fun controlErrorsUseLocalizedMessages() {
        listOf(I18n.zh, I18n.en).forEach { parent ->
            val text = parent.control
            val expected = mapOf(
                "video_closed" to text.videoClosed,
                "control_closed" to text.controlClosed,
                "control_send_failed" to text.controlSendFailed,
                "start_failed" to text.startFailed,
                "open_control_failed" to text.startFailed,
                "server_asset_empty" to text.startFailed,
                "push_server_failed" to text.serverPushFailed,
                "fastboot" to parent.controlStatusFastboot,
                "disconnected" to parent.controlStatusDisconnected,
                "no_connection" to parent.controlStatusDisconnected
            )
            expected.forEach { (code, message) ->
                assertEquals(code, message, parent.controlErrorMessage(code))
                assertNotEquals(code, parent.controlErrorMessage(code))
            }
        }
        assertNotEquals(I18n.zh.control.videoClosed, I18n.en.control.videoClosed)
        assertEquals("被控设备音量加", I18n.zh.control.volumeUp)
        assertEquals("Remote volume down", I18n.en.control.volumeDown)
    }

    @Test fun unknownControlErrorsDoNotExposeRawDetails() {
        listOf(I18n.zh, I18n.en).forEach { parent ->
            listOf("", "unknown_code", "Exception: private-payload /private/path", "open_video_failed(private-payload)")
                .forEach { raw ->
                    val message = parent.controlErrorMessage(raw)
                    assertEquals(parent.control.unknownError, message)
                    assertFalse(message.contains("private-payload"))
                    assertFalse(message.contains("/private/path"))
                }
        }
    }

    @Test fun proxyStatusFormatsAcceptRuntimeValues() {
        listOf(ProxyI18n.zh, ProxyI18n.en).forEach {
            assertTrue(it.listening.format("127.0.0.1", 5037).contains("127.0.0.1:5037"))
            assertTrue(it.error.format("BindException").contains("BindException"))
        }
    }
}
