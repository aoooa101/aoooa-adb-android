package com.aoooa.webadb.bridge

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

/**
 * 原生 USB 通道：通过 UsbManager 直接打开设备的 ADB 接口（替代网页版 WebUSB）。
 * ADB 接口特征：class=0xFF(255) subclass=0x42(66) protocol=0x01。
 */
class UsbChannel(private val onData: (ByteArray) -> Unit) : Channel {

    companion object {
        const val ADB_CLASS = 0xFF
        const val ADB_SUBCLASS = 0x42
        const val ADB_PROTOCOL = 0x01
    }

    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var bulkIn: UsbEndpoint? = null
    private var bulkOut: UsbEndpoint? = null
    private var readThread: Thread? = null

    @Volatile
    private var running = false

    /** 权限已授予后同步打开设备并启动读循环。 */
    fun connect(usbManager: UsbManager, device: UsbDevice): Boolean {
        return try {
            val iface = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull {
                    it.interfaceClass == ADB_CLASS &&
                        it.interfaceSubclass == ADB_SUBCLASS &&
                        it.interfaceProtocol == ADB_PROTOCOL
                } ?: return false

            val conn = usbManager.openDevice(device) ?: return false
            if (!conn.claimInterface(iface, true)) {
                conn.close()
                return false
            }

            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN && inEp == null) inEp = ep
                    else if (ep.direction == UsbConstants.USB_DIR_OUT && outEp == null) outEp = ep
                }
            }
            if (inEp == null || outEp == null) {
                conn.close()
                return false
            }

            connection = conn
            usbInterface = iface
            bulkIn = inEp
            bulkOut = outEp
            running = true
            startReadLoop(conn, inEp)
            true
        } catch (e: Exception) {
            close()
            false
        }
    }

    private fun startReadLoop(conn: UsbDeviceConnection, inEp: UsbEndpoint) {
        readThread = Thread {
            val buffer = ByteArray(inEp.maxPacketSize * 8)
            while (running) {
                val n = conn.bulkTransfer(inEp, buffer, buffer.size, 200)
                if (n > 0) {
                    onData(buffer.copyOf(n))
                }
                // n <= 0：超时或错误，继续轮询（断开时由 close 停止）
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    override fun send(data: ByteArray): Boolean {
        val out = bulkOut ?: return false
        val conn = connection ?: return false
        return try {
            var offset = 0
            while (offset < data.size) {
                val chunk = minOf(data.size - offset, out.maxPacketSize)
                val n = conn.bulkTransfer(out, data.copyOfRange(offset, offset + chunk), chunk, 200)
                if (n < 0) return false
                offset += n
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun close() {
        running = false
        readThread?.interrupt()
        readThread = null
        try {
            usbInterface?.let { connection?.releaseInterface(it) }
        } catch (_: Exception) {
        }
        try {
            connection?.close()
        } catch (_: Exception) {
        }
        connection = null
        usbInterface = null
        bulkIn = null
        bulkOut = null
    }
}
