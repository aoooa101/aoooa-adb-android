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
        listOf(ProxyI18n.zh to ProxyI18n.en, CompletionI18n.zh to CompletionI18n.en).forEach { (zh, en) ->
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

    @Test fun proxyStatusFormatsAcceptRuntimeValues() {
        listOf(ProxyI18n.zh, ProxyI18n.en).forEach {
            assertTrue(it.listening.format("127.0.0.1", 5037).contains("127.0.0.1:5037"))
            assertTrue(it.error.format("BindException").contains("BindException"))
        }
    }
}
