package com.aoooa.webadb.bridge

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 原生 USB 通道：通过 UsbManager 直接打开设备的 ADB 接口。
 * 复刻 1.0 版经过实操验证的单 UsbRequest 预排队异步读取架构，
 * 解决 USB 读端点阻塞与 Android 7~10 认证授权弹窗问题。
 */
class UsbChannel(
    private val onData: (ByteArray) -> Unit,
    private val onStatus: (String) -> Unit = {}
) : Channel {

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
                }
            if (iface == null) {
                onStatus("未找到 ADB 接口 (class 255/66/1)，设备可能未开启 USB 调试")
                return false
            }
            onStatus("找到 ADB 接口: #" + iface.id)

            val conn = usbManager.openDevice(device)
            if (conn == null) {
                onStatus("打开 USB 设备失败（可能被系统占用）")
                return false
            }
            onStatus("USB 设备已打开")

            if (!conn.claimInterface(iface, true)) {
                onStatus("claim 接口失败（设备正被其他程序占用？）")
                conn.close()
                return false
            }
            onStatus("接口已 claim")

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
                onStatus("未找到 bulk 端点 (IN=${inEp != null} OUT=${outEp != null})")
                conn.close()
                return false
            }
            onStatus("端点就绪: IN=" + inEp.endpointNumber + " OUT=" + outEp.endpointNumber)

            connection = conn
            usbInterface = iface
            bulkIn = inEp
            bulkOut = outEp
            running = true

            startReadLoop(conn, inEp)
            onStatus("USB 通道与 1.0 读线程已启动，等待就绪...")
            Thread.sleep(200)
            true
        } catch (e: Exception) {
            onStatus("USB 连接异常: " + e.stackTraceToString())
            close()
            false
        }
    }

    /**
     * 1.0 版经过验证的单 UsbRequest 异步读循环：
     * 预先 queue 避免 requestWait 空转，超时自动重新 queue。
     */
    private fun startReadLoop(conn: UsbDeviceConnection, inEp: UsbEndpoint) {
        readThread = Thread {
            var readCount = 0
            var failCount = 0
            val bufSize = inEp.maxPacketSize * 8

            val req = UsbRequest()
            if (!req.initialize(conn, inEp)) {
                onStatus("usb_read: UsbRequest 初始化失败")
                return@Thread
            }

            val buf = ByteBuffer.allocateDirect(bufSize).order(ByteOrder.LITTLE_ENDIAN)
            req.setClientData(buf)

            if (!req.queue(buf, bufSize)) {
                onStatus("usb_read: 初始 queue 失败")
                return@Thread
            }
            onStatus("usb_read: 读线程与队列已正式就绪")

            while (running) {
                try {
                    val wait = conn.requestWait(1000L)
                    if (wait == null) {
                        if (running) req.queue(buf, bufSize)
                        continue
                    }

                    if (wait.endpoint == bulkOut) {
                        if (running) req.queue(buf, bufSize)
                        continue
                    }

                    val clientData = wait.getClientData() as? ByteBuffer
                    if (clientData != null && clientData.position() > 0) {
                        clientData.flip()
                        val data = ByteArray(clientData.remaining())
                        clientData.get(data)

                        readCount++
                        if (readCount <= 20) {
                            val preview = data.take(16).joinToString("") { "%02X".format(it) }
                            onStatus("usb_read #$readCount: ${data.size} 字节 [$preview]")
                        }
                        onData(data)
                    }

                    if (running) {
                        buf.clear()
                        req.queue(buf, bufSize)
                    }
                    failCount = 0
                } catch (e: Exception) {
                    if (running && failCount++ < 5) {
                        onStatus("usb_read 异常: " + (e.message ?: "未知"))
                    }
                }
            }
            try { req.close() } catch (_: Exception) {}
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
            var segments = 0
            while (offset < data.size) {
                val chunk = minOf(data.size - offset, out.maxPacketSize)
                val n = conn.bulkTransfer(out, data.copyOfRange(offset, offset + chunk), chunk, 500)
                if (n < 0) {
                    onStatus("usb_send 失败: 第${segments + 1}段 chunk=$chunk 返回-1")
                    return false
                }
                offset += n
                segments++
            }
            onStatus("usb_send 成功: ${data.size} 字节 (${segments} 段)")
            true
        } catch (e: Exception) {
            onStatus("usb_send 异常: " + (e.message ?: "未知"))
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