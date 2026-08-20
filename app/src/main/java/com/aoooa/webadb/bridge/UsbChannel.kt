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
import java.util.LinkedList

/**
 * 原生 USB 通道：通过 UsbManager 直接打开设备的 ADB 接口。
 * 完全复刻 1.0 版经过实操验证的 UsbRequest 池化架构，
 * 解决单 Request 重复使用导致 requestWait 空队列抛出异常问题。
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

    /** UsbRequest 对象池（复用避免反复创建，完全复刻 1.0 架构） */
    private val inRequestPool = LinkedList<UsbRequest>()

    @Volatile
    private var running = false

    private fun getInRequest(): UsbRequest? {
        synchronized(inRequestPool) {
            val conn = connection ?: return null
            val ep = bulkIn ?: return null
            // 每次新建 UsbRequest：requestWait 返回后的 request 不能安全复用，
            // 复用会导致 queue 失败（老设备尤为明显），新建最稳妥。
            return try {
                val req = UsbRequest()
                if (req.initialize(conn, ep)) req else null
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun releaseInRequest(req: UsbRequest) {
        synchronized(inRequestPool) {
            try { req.cancel() } catch (_: Exception) {}
            try { req.close() } catch (_: Exception) {}
            // 不再入池复用（已 close，直接丢弃）
        }
    }

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
            onStatus("USB 通道与 1.0 架构读线程已启动")
            Thread.sleep(150)
            true
        } catch (e: Exception) {
            onStatus("USB 连接异常: " + e.stackTraceToString())
            close()
            false
        }
    }

    /**
     * 1.0 版 UsbRequest 池化异步读循环，加上完整堆栈日志。
     */
    private fun startReadLoop(conn: UsbDeviceConnection, inEp: UsbEndpoint) {
        readThread = Thread {
            var readCount = 0
            var failCount = 0
            val bufSize = inEp.maxPacketSize * 8
            while (running) {
                try {
                    val req = getInRequest()
                    if (req == null) {
                        if (failCount++ < 3) {
                            onStatus("usb_read: UsbRequest 初始化失败")
                        }
                        Thread.sleep(200)
                        continue
                    }
                    val buf = ByteBuffer.allocateDirect(bufSize).order(ByteOrder.LITTLE_ENDIAN)
                    req.setClientData(buf)

                    if (!req.queue(buf, bufSize)) {
                        if (failCount++ < 3) {
                            onStatus("usb_read: queue 失败")
                        }
                        Thread.sleep(100)
                        continue
                    }

                    val wait = conn.requestWait()
                    if (wait == null) {
                        releaseInRequest(req)
                        continue
                    }

                    if (wait.endpoint == bulkOut) {
                        releaseInRequest(wait)
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
                            onStatus("usb_read #$readCount: ${data.size} 字节 [${preview}]")
                        }
                        onData(data)
                    }
                    releaseInRequest(wait)
                    failCount = 0
                } catch (e: Exception) {
                    if (running && failCount++ < 5) {
                        onStatus("usb_read 详细异常: " + e.stackTraceToString())
                    }
                }
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
            var segments = 0
            while (offset < data.size) {
                val chunk = minOf(data.size - offset, out.maxPacketSize)
                val n = conn.bulkTransfer(out, data.copyOfRange(offset, offset + chunk), chunk, 200)
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
            onStatus("usb_send 异常: " + e.stackTraceToString())
            false
        }
    }

    override fun close() {
        running = false
        readThread?.interrupt()
        readThread = null
        synchronized(inRequestPool) {
            for (req in inRequestPool) {
                try { req.close() } catch (_: Exception) {}
            }
            inRequestPool.clear()
        }
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