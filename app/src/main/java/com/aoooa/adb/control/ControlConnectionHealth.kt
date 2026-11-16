package com.aoooa.adb.control

/** 按传输连接、视频通道、控制通道的依赖顺序判断会话状态。 */
internal object ControlConnectionHealth {
    fun error(connected: Boolean, videoReady: Boolean, controlReady: Boolean): String? = when {
        !connected -> "disconnected"
        !videoReady -> "video_closed"
        !controlReady -> "control_closed"
        else -> null
    }
}
