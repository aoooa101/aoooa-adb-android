package com.aoooa.adb

import com.aoooa.adb.ui.i18n.I18n
import com.aoooa.adb.ui.i18n.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 界面文案静态完整性检查。
 *
 * 通过反射读取 zh / en 两套 [Strings] 实例的全部字段，校验：
 * 1. 不存在赋值遗漏导致的空文案（界面会显示为空白）；
 * 2. 中英两套的格式化占位符数量与顺序一致（否则 String.format 会抛异常或错位）；
 * 3. 英文分支不残留中文字符（语言名等需按母语展示的字段单独放行）。
 */
class StringsI18nTest {

    /** 语言名称按母语展示属于有意为之，英文分支允许出现中文字符。 */
    private val cjkAllowedFields = setOf("langZh")

    /**
     * 已声明但中英两套均未赋值的字段。
     *
     * 这些字段当前没有任何界面引用，反射读取时为空串。此处单独隔离，
     * 保证新增字段若忘记赋值仍会被下面的空文案用例拦截。
     */
    private val knownUnusedFields = setOf(
        "aboutLabel",
        "aboutVersion",
        "aboutDesc",
        "themeSystem",
        "themeDark",
        "themeLight",
        "wiredTitle"
    )

    private val cjkRegex = Regex("[\\u4e00-\\u9fff]")

    /** %s / %d / %1$s / %.2f 等格式化占位符 */
    private val placeholderRegex = Regex("%(?:\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[sdfxX]")

    private fun readAll(target: Strings): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (field in Strings::class.java.declaredFields) {
            if (field.type != String::class.java) continue
            field.isAccessible = true
            result[field.name] = (field.get(target) as? String) ?: ""
        }
        return result
    }

    @Test
    fun testStringsExposeFields() {
        assertTrue("Strings 应暴露大量文案字段", readAll(I18n.zh).size > 300)
    }

    @Test
    fun testNoBlankChineseStrings() {
        val blank = readAll(I18n.zh)
            .filterKeys { it !in knownUnusedFields }
            .filterValues { it.isBlank() }
            .keys
        assertTrue("中文文案存在未赋值字段: $blank", blank.isEmpty())
    }

    @Test
    fun testNoBlankEnglishStrings() {
        val blank = readAll(I18n.en)
            .filterKeys { it !in knownUnusedFields }
            .filterValues { it.isBlank() }
            .keys
        assertTrue("英文文案存在未赋值字段: $blank", blank.isEmpty())
    }

    @Test
    fun testNoNewUnassignedFields() {
        // 双向校验：中英均为空串的字段必须已在 knownUnusedFields 中登记，
        // 避免新增字段忘记赋值却静默通过检查；已启用的字段无需从清单移除。
        val zh = readAll(I18n.zh)
        val en = readAll(I18n.en)
        val blankInBoth = zh.filter { (name, value) ->
            value.isBlank() && (en[name] ?: "").isBlank()
        }.keys

        val unregistered = blankInBoth - knownUnusedFields
        assertTrue(
            "以下字段中英两套均未赋值，请补全文案或登记到 knownUnusedFields: $unregistered",
            unregistered.isEmpty()
        )
    }

    @Test
    fun testChineseAndEnglishFieldSetsMatch() {
        assertEquals(readAll(I18n.zh).keys, readAll(I18n.en).keys)
    }

    @Test
    fun testPlaceholdersMatchBetweenLanguages() {
        val zh = readAll(I18n.zh)
        val en = readAll(I18n.en)

        val mismatched = mutableListOf<String>()
        for ((name, zhText) in zh) {
            val enText = en[name] ?: continue
            val zhPlaceholders = placeholderRegex.findAll(zhText).map { it.value }.toList()
            val enPlaceholders = placeholderRegex.findAll(enText).map { it.value }.toList()
            if (zhPlaceholders != enPlaceholders) {
                mismatched.add("$name: zh=$zhPlaceholders en=$enPlaceholders")
            }
        }
        assertTrue("中英文占位符不一致: $mismatched", mismatched.isEmpty())
    }

    @Test
    fun testEnglishStringsHaveNoChineseCharacters() {
        val offenders = readAll(I18n.en)
            .filterKeys { it !in cjkAllowedFields }
            .filterValues { cjkRegex.containsMatchIn(it) }
            .map { "${it.key}=${it.value}" }

        assertTrue("英文文案残留中文: $offenders", offenders.isEmpty())
    }

    @Test
    fun testChineseStringsAreMostlyLocalized() {
        // 中文分支应基本为中文，仅品牌名、协议名、单位等允许为英文
        val strings = readAll(I18n.zh)
        val localized = strings.values.count { cjkRegex.containsMatchIn(it) }
        assertTrue(
            "中文文案本地化比例过低: $localized/${strings.size}",
            localized * 100 / strings.size > 80
        )
    }
}
