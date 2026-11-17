package com.aoooa.adb.ui.i18n

internal data class ControlStrings(
    val volumeUp: String,
    val volumeDown: String,
    val expandTools: String,
    val collapseTools: String,
    val videoClosed: String,
    val controlClosed: String,
    val controlSendFailed: String,
    val startFailed: String,
    val serverPushFailed: String,
    val audioFailed: String,
    val unknownError: String
) {
    /** 将内部错误码映射为用户提示，不输出原始异常内容。 */
    fun errorMessage(code: String): String = when (code) {
        "video_closed" -> videoClosed
        "control_closed" -> controlClosed
        "control_send_failed" -> controlSendFailed
        "start_failed", "open_control_failed", "server_asset_empty" -> startFailed
        "push_server_failed" -> serverPushFailed
        else -> unknownError
    }
}

private val controlZh = ControlStrings(
    volumeUp = "被控设备音量加",
    volumeDown = "被控设备音量减",
    expandTools = "展开投屏工具",
    collapseTools = "收起投屏工具",
    videoClosed = "视频连接已断开，请重新连接投屏",
    controlClosed = "控制连接已断开，请重新连接投屏",
    controlSendFailed = "控制指令发送失败，请重新连接投屏",
    startFailed = "投屏启动失败，请查看详细日志后重试",
    serverPushFailed = "投屏服务传输失败，请检查设备连接后重试",
    audioFailed = "音频传输或播放异常，请查看详细日志",
    unknownError = "投屏发生异常，请查看详细日志"
)

private val controlEn = ControlStrings(
    volumeUp = "Remote volume up",
    volumeDown = "Remote volume down",
    expandTools = "Expand mirroring tools",
    collapseTools = "Collapse mirroring tools",
    videoClosed = "Video connection closed. Reconnect screen mirroring.",
    controlClosed = "Control connection closed. Reconnect screen mirroring.",
    controlSendFailed = "Failed to send control input. Reconnect screen mirroring.",
    startFailed = "Failed to start screen mirroring. Check the detailed log and retry.",
    serverPushFailed = "Failed to transfer the mirroring server. Check the device connection and retry.",
    audioFailed = "Audio transmission or playback failed. Check the detailed log.",
    unknownError = "Screen mirroring encountered an error. Check the detailed log."
)

/** 连接状态复用已有文案，其余投屏错误使用功能文案映射。 */
internal fun Strings.controlErrorMessage(code: String): String = when (code) {
    "fastboot" -> controlStatusFastboot
    "disconnected", "no_connection" -> controlStatusDisconnected
    else -> control.errorMessage(code)
}

internal val Strings.control: ControlStrings
    get() = if (this === I18n.zh) controlZh else controlEn
