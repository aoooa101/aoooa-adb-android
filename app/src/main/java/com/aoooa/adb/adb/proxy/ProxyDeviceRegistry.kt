package com.aoooa.adb.adb.proxy

import com.aoooa.adb.AdbManager
import com.aoooa.adb.adb.AdbConnection

/** 将应用当前连接映射为单设备 ADB Server 传输身份。 */
internal object ProxyDeviceRegistry {
    data class Device(val connection: AdbConnection, val serial: String, val model: String,
                      val usb: Boolean, val id: Long) {
        fun listing(long: Boolean): String = if (long) {
            "$serial\tdevice model:${token(model)} transport_id:$id\n"
        } else "$serial\tdevice\n"
    }

    private var previous: AdbConnection? = null
    private var nextId = 0L

    @Synchronized
    fun current(): Device? {
        val connection = AdbManager.connection
        if (!AdbManager.connected.value || AdbManager.isFastbootMode.value ||
            connection == null || !connection.isAuthenticated) {
            previous = null
            return null
        }
        if (previous !== connection) {
            previous = connection
            nextId++
        }
        val serial = token(AdbManager.deviceName.value.ifBlank { "device-$nextId" })
        return Device(connection, serial, AdbManager.model.value, connection.isUsbTransport, nextId)
    }

    fun select(selector: String): Device? {
        val device = current() ?: return null
        return device.takeIf {
            when {
                selector == "any" -> true
                selector == "usb" -> it.usb
                selector == "local" -> !it.usb
                selector.startsWith("serial:") -> it.serial == selector.removePrefix("serial:")
                selector.startsWith("id:") -> it.id == selector.removePrefix("id:").toLongOrNull()
                else -> false
            }
        }
    }

    private fun token(text: String): String = text.map {
        if (it.isWhitespace() || it.code < 32 || it.code == 127) '_' else it
    }.joinToString("")
}
