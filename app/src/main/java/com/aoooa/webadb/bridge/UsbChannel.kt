package com.aoooa.webadb.bridge

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

/**
 * 原生 USB 通道：通过 UsbManager 直接打开设备的 ADB 接口。
 * ADB 接口特征：class=0xFF(255) subclass=0x42(66) protocol=0x01。
 *
 * 读写均采用 bulkTransfer 同步方式，与写操作统一 API，
 * 避免 UsbRequest.queue() 在不同厂商 ROM 上的兼容性问题。
 *
 * 读循环：轮询 bulkTransfer IN（超时 500ms），收到数据后回调 onData。
 */
class UsbChannel(
    private val onData: (ByteArray) -> Unit,
    private val onStatus: (String) -> Unit = {}
) : Channel {

    companion object {
        const val ADB_CLASS = 0xFF
        const val ADB_SUBCLASS = 0x42
        const val ADB_PROTOCOL = 0x01
        private const val READ_TIMEOUT_MS = 500
        private const val READ_BUF_SIZE = 16384
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
            onStatus("USB 通道已启动")
            true
        } catch (e: Exception) {
            onStatus("USB 连接异常: " + e.message)
            close()
            false
        }
    }

    /**
     * 同步读循环：用 bulkTransfer 轮询替代 UsbRequest 异步方案。
     * 部分厂商 ROM 对 UsbRequest 兼容性不稳定，但 bulkTransfer 同步方式更通用。
     */
    private fun startReadLoop(conn: UsbDeviceConnection, inEp: UsbEndpoint) {
        readThread = Thread {
            var readCount = 0
            var failCount = 0
            val buf = ByteArray(READ_BUF_SIZE)
            while (running) {
                try {
                    val n = conn.bulkTransfer(inEp, buf, buf.size, READ_TIMEOUT_MS)
                    if (n < 0) {
                        // 超时或无数据，继续轮询
                        if (failCount++ < 3) {
                            onStatus("usb_read: bulkTransfer 超时（等待数据中...）")
                        }
                        Thread.sleep(100)
                        continue
                    }
                    failCount = 0
                    val data = buf.copyOf(n)
                    readCount++
                    if (readCount <= 20) {
                        val preview = data.take(16).joinToString("") { "%02X".format(it) }
                        onStatus("usb_read #$readCount: ${data.size} 字节 [${preview}]")
                    }
                    onData(data)
                } catch (e: Exception) {
                    if (running && failCount++ < 5) {
                        onStatus("usb_read 异常: " + (e.message ?: "未知"))
                    }
                }
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    @Synchronized
    override fun send(data: ByteArray): Boolean {
        val out = bulkOut ?: return false
        val conn = connection ?: return false
        return try {
            if (data.size <= 24) {
                // 仅 Header（无 Payload，如 OKAY, CLSE 等）
                val n = conn.bulkTransfer(out, data, data.size, 1000)
                if (n <= 0) {
                    onStatus("usb_send 失败: Header ${data.size}B 返回 <=0 ($n)")
                    return false
                }
                onStatus("usb_send 成功: ${data.size} 字节 (Header)")
                true
            } else {
                // 遵循 AOSP 官方 USB 规范：Header (24B) 与 Payload 必须作为两次独立的 USB Bulk 传输发送
                val header = data.copyOfRange(0, 24)
                val nHdr = conn.bulkTransfer(out, header, 24, 1000)
                if (nHdr <= 0) {
                    onStatus("usb_send 失败: Header 24B 返回 <=0 ($nHdr)")
                    return false
                }

                val payload = data.copyOfRange(24, data.size)
                var offset = 0
                var segments = 0
                while (offset < payload.size) {
                    val chunk = minOf(payload.size - offset, out.maxPacketSize)
                    val n = conn.bulkTransfer(out, payload.copyOfRange(offset, offset + chunk), chunk, 1000)
                    if (n <= 0) {
                        onStatus("usb_send 失败: Payload 第${segments + 1}段 chunk=$chunk 返回 <=0 ($n)")
                        return false
                    }
                    offset += n
                    segments++
                }
                onStatus("usb_send 成功: 24B Header + ${payload.size}B Payload (共${segments + 1}段)")
                true
            }
        } catch (e: Exception) {
            onStatus("usb_send 异常: " + e.message)
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