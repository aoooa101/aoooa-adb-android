package com.aoooa.adb.ui.connect

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aoooa.adb.adb.TcpEndpoint
import com.aoooa.adb.ui.i18n.TcpConnectStrings

internal fun acceptsTcpAddressDraft(value: String): Boolean = value.length <= 100

@Composable
internal fun TcpAddressInput(
    value: String,
    onChange: (String) -> Unit,
    labels: TcpConnectStrings,
    recent: List<TcpEndpoint>,
    onSelect: (TcpEndpoint) -> Unit,
    trailingIcon: @Composable () -> Unit
) {
    var showRecent by remember { mutableStateOf(false) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    fun closeRecent() {
        showRecent = false
        focusManager.clearFocus()
    }
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = { if (acceptsTcpAddressDraft(it)) onChange(it) },
            placeholder = { Text(labels.hint) },
            modifier = Modifier.fillMaxWidth().onFocusChanged { showRecent = it.isFocused },
            singleLine = true,
            trailingIcon = trailingIcon
        )
        if (showRecent && recent.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState())
                    .semantics { contentDescription = labels.recent }
            ) {
                recent.forEach { endpoint ->
                    DropdownMenuItem(text = { Text(endpoint.display) }, onClick = {
                        closeRecent()
                        onSelect(endpoint)
                    })
                }
            }
        }
    }
}
