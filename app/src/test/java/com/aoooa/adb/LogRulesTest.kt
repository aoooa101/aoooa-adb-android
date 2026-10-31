package com.aoooa.adb

import com.aoooa.adb.log.LogRules
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志匹配规则测试：多候选分隔、大小写、正则与非法正则回退。
 */
class LogRulesTest {

    @Test
    fun testEmptyRuleMatchesEverything() {
        assertTrue(LogRules.matchText("anything", "", asRegex = false))
        assertTrue(LogRules.matchText("anything", "   ", asRegex = false))
    }

    @Test
    fun testPipeSeparatedCandidates() {
        assertTrue(LogRules.matchText("hello world", "hello|nope", asRegex = false))
        assertTrue(LogRules.matchText("hello world", "nope|world", asRegex = false))
        assertFalse(LogRules.matchText("hello world", "foo|bar", asRegex = false))
    }

    @Test
    fun testCommaSeparatedCandidates() {
        assertTrue(LogRules.matchText("fatal crash", "fatal,error", asRegex = false))
        assertFalse(LogRules.matchText("fatal crash", "error,warn", asRegex = false))
    }

    @Test
    fun testContainsMatchIsCaseInsensitive() {
        assertTrue(LogRules.matchText("FATAL EXCEPTION", "fatal", asRegex = false))
        assertTrue(LogRules.matchText("Background", "BACK", asRegex = false))
    }

    @Test
    fun testRegexMatch() {
        assertTrue(LogRules.matchText("error code 500", "\\d{3}", asRegex = true))
        assertFalse(LogRules.matchText("error code", "\\d{3}", asRegex = true))
        assertTrue(LogRules.matchText("Error: boom", "error.*boom", asRegex = true))
    }

    @Test
    fun testInvalidRegexFallsBackToContains() {
        // "[a-" 不是合法正则，应退化为普通包含匹配
        assertTrue(LogRules.matchText("取 [a- 区间", "[a-", asRegex = true))
        assertFalse(LogRules.matchText("nothing here", "[a-", asRegex = true))
    }

    @Test
    fun testMatchExcludeSemantics() {
        assertFalse(LogRules.matchExclude("fatal", "", asRegex = false))
        assertTrue(LogRules.matchExclude("fatal error", "fatal", asRegex = false))
        assertFalse(LogRules.matchExclude("normal line", "fatal", asRegex = false))
    }
}
