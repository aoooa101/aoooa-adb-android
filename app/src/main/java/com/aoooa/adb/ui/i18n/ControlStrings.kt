package com.aoooa.adb.ui.i18n

internal data class ControlStrings(
    val volumeUp: String,
    val volumeDown: String
)

private val controlZh = ControlStrings(
    volumeUp = "被控设备音量加",
    volumeDown = "被控设备音量减"
)

private val controlEn = ControlStrings(
    volumeUp = "Remote volume up",
    volumeDown = "Remote volume down"
)

internal val Strings.control: ControlStrings
    get() = if (this === I18n.zh) controlZh else controlEn
