package com.aoooa.adb

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.aoooa.adb.control.ControlSessionManager
import com.aoooa.adb.control.ControlSessionPhase
import com.aoooa.adb.log.LogLine
import com.aoooa.adb.log.LogManager
import com.aoooa.adb.log.LogTypeFilter
import com.aoooa.adb.model.TerminalLine
import com.aoooa.adb.ui.TerminalScreen
import com.aoooa.adb.ui.i18n.I18n
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** 通过终端公开界面验证真实菜单操作，不直接访问文件私有组件。 */
class TerminalActionsTest {
    @get:Rule val compose = createComposeRule()
    private val restore = mutableListOf<() -> Unit>()
    private val visible = androidx.compose.runtime.mutableStateOf(true)
    private var mounted = false
    private val strings get() = I18n.en
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun <T> save(state: MutableState<T>, replacement: T) {
        val original = state.value
        restore.add { state.value = original }
        state.value = replacement
    }

    @Before fun prepare() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assumeFalse("不修改活动设备连接", AdbManager.connected.value)
            assumeFalse("不修改活动日志采集", LogManager.isCapturing.value)
            assumeFalse("不干扰活动控制会话", ControlSessionManager.phase.value in
                setOf(ControlSessionPhase.RUNNING, ControlSessionPhase.PREPARING))
            Prefs.init(context)
            val terminal = AdbManager.terminalLines.toList()
            val logs = LogManager.logLines.toList()
            val messages = AdbManager.logs.toList()
            restore.add {
                AdbManager.terminalLines.clear()
                AdbManager.terminalLines.addAll(terminal)
                LogManager.logLines.clear()
                LogManager.logLines.addAll(logs)
                AdbManager.logs.clear()
                AdbManager.logs.addAll(messages)
            }
            AdbManager.terminalLines.clear()
            AdbManager.terminalLines.add(TerminalLine(text = "terminal-menu-fixture"))
            LogManager.logLines.clear()
            LogManager.logLines.add(LogLine(raw = "log-menu-fixture", message = "log-menu-fixture"))
            save(AdbManager.currentTerminalMode, TerminalMode.SHELL)
            save(LogManager.searchQuery, "")
            save(LogManager.typeFilter, LogTypeFilter.ALL)
            save(LogManager.enabledLevelsMask, 0b111111)
            save(LogManager.tagInclude, "")
            save(LogManager.tagExclude, "")
            save(LogManager.keywordInclude, "")
            save(LogManager.keywordExclude, "")
            save(LogManager.useRegex, false)
            save(LogManager.ringDropCount, 0L)
        }
    }

    @After fun cleanup() {
        try {
            if (mounted) {
                compose.runOnIdle { visible.value = false }
                compose.waitForIdle()
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                restore.asReversed().forEach { it() }
                restore.clear()
            }
        }
    }

    private fun show(mode: TerminalMode) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            AdbManager.currentTerminalMode.value = mode
        }
        compose.setContent {
            if (visible.value) MaterialTheme { TerminalScreen(s = strings, lang = "en") }
        }
        mounted = true
        compose.waitForIdle()
    }

    private fun openActions() {
        compose.onNodeWithContentDescription(strings.menuTitle).performClick()
    }

    private fun assertOwnClipboard(expected: String) {
        compose.runOnIdle {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(expected, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
    }

    @Test fun shellActionsAppearOnlyAfterOpeningMenu() {
        show(TerminalMode.SHELL)
        compose.onNodeWithText(strings.terminalCopy).assertDoesNotExist()
        openActions()
        compose.onNodeWithText(strings.terminalCopy).assertIsDisplayed()
        compose.onNodeWithText(strings.terminalClear).assertIsDisplayed()
    }

    @Test fun shellCopyUsesTerminalOutputAndClosesMenu() {
        show(TerminalMode.SHELL)
        openActions()
        compose.onNodeWithText(strings.terminalCopy).performClick()
        assertOwnClipboard("terminal-menu-fixture")
        compose.onNodeWithText(strings.terminalCopy).assertDoesNotExist()
    }

    @Test fun shellClearRemovesTerminalOutput() {
        show(TerminalMode.SHELL)
        openActions()
        compose.onNodeWithText(strings.terminalClear).performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, AdbManager.terminalLines.size) }
        compose.onNodeWithText("terminal-menu-fixture").assertDoesNotExist()
    }

    @Test fun logCopyUsesDisplayedLogOutput() {
        show(TerminalMode.LOG)
        openActions()
        compose.onNodeWithText(strings.terminalCopy).performClick()
        assertOwnClipboard("log-menu-fixture")
    }

    @Test fun logClearRemovesLogBuffer() {
        show(TerminalMode.LOG)
        openActions()
        compose.onNodeWithText(strings.clear).performClick()
        compose.waitUntil(5000) { LogManager.logLines.isEmpty() }
        compose.onNodeWithText("log-menu-fixture").assertDoesNotExist()
    }

    @Test fun logSettingsRemainsAvailableBesideMenu() {
        show(TerminalMode.LOG)
        compose.onNodeWithContentDescription(strings.logSettingsCd).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.menuTitle).assertIsDisplayed()
    }
}
