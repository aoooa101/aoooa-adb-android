package com.aoooa.adb

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aoooa.adb.adb.TcpEndpoint
import com.aoooa.adb.ui.connect.TcpAddressInput
import com.aoooa.adb.ui.i18n.TcpConnectStrings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TcpAddressInputTest {
    @get:Rule val compose = createComposeRule()

    @Test fun canClearAndTypeAgainWhileHistoryIsVisible() {
        val draft = mutableStateOf("192.168.1.2:5555")
        val recent = TcpEndpoint("192.168.1.3", 5555)
        compose.setContent {
            TcpAddressInput(draft.value, { draft.value = it }, TcpConnectStrings("", "", "Recent"),
                listOf(recent), onSelect = { error("Editing must not connect") }, trailingIcon = {})
        }

        val input = compose.onNode(hasSetTextAction())
        input.performClick()
        compose.onNodeWithText(recent.display).assertExists()
        input.performTextClearance()
        input.assertIsFocused().assertTextEquals("")
        compose.onNodeWithText(recent.display).assertExists()
        input.performTextInput("127.0.0.1")
        input.assertIsFocused().assertTextEquals("127.0.0.1")
    }
}
