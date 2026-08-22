package com.aoooa.webadb.fastboot

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

/**
 * 原生 Fastboot 协议客户端（纯 Kotlin + Android UsbManager 实现，零外部依赖与 .so 库）。
 *
 * Fastboot 协议规范：
 * - 接口特征：class=0xFF(255), subclass=0x42(66), protocol=0x03(3)
 * - 交互协议：纯 ASCII 请求指令 + 4 字节响应头状态机：
 *   - INFOxxxx : 过程输出信息（如 getvar:all 连续输出）
 *   - OKAYxxxx : 成功完成
 *   - FAILxxxx : 失败原因
 *   - DATAxxxx : 准备传输数据
 */
class FastbootClient(
    private val onLog: (String) -> Unit = {},
    private val onDebugLog: (String) -> Unit = {}
) {
    companion object {
        const val FASTBOOT_CLASS = 0xFF
        const val FASTBOOT_SUBCLASS = 0x42
        const val FASTBOOT_PROTOCOL = 0x03
        private const val TIMEOUT_MS = 3000
    }

    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var bulkIn: UsbEndpoint? = null
    private var bulkOut: UsbEndpoint? = null

    @Volatile
    private var isConnected = false

    val connected: Boolean get() = isConnected

    /**
     * 连接 Fastboot USB 设备并初始化端点
     */
    fun connect(usbManager: UsbManager, device: UsbDevice): Boolean {
        return try {
            val iface = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull {
                    it.interfaceClass == FASTBOOT_CLASS &&
                        it.interfaceSubclass == FASTBOOT_SUBCLASS &&
                        it.interfaceProtocol == FASTBOOT_PROTOCOL
                }
            if (iface == null) {
                onLog("未找到 Fastboot 接口 (class 255/66/3)，设备可能未处于 Fastboot 模式")
                return false
            }

            val conn = usbManager.openDevice(device)
            if (conn == null) {
                onLog("打开 USB 设备失败（可能已被系统或其他程序占用）")
                return false
            }

            if (!conn.claimInterface(iface, true)) {
                onLog("claim Fastboot 接口失败")
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
                onLog("未找到 Fastboot Bulk 端点")
                conn.close()
                return false
            }

            connection = conn
            usbInterface = iface
            bulkIn = inEp
            bulkOut = outEp
            isConnected = true
            onDebugLog("Fastboot 通道就绪: IN=#${inEp.endpointNumber} OUT=#${outEp.endpointNumber}")
            true
        } catch (e: Exception) {
            onLog("Fastboot 连接异常: ${e.message}")
            disconnect()
            false
        }
    }

    /**
     * 执行一条 Fastboot 命令并接收完整返回
     */
    fun execute(rawCommand: String): String {
        if (!isConnected) return "Fastboot 设备未连接"
        val conn = connection ?: return "Fastboot 连接已断开"
        val out = bulkOut ?: return "输出端点不可用"
        val inEp = bulkIn ?: return "输入端点不可用"

        // 格式化命令（兼容用户输入 fastboot getvar all / getvar:all 等形式）
        var cmd = rawCommand.trim()
        if (cmd.startsWith("fastboot ")) {
            cmd = cmd.substring(9).trim()
        }
        if (cmd.startsWith("getvar ") && !cmd.startsWith("getvar:")) {
            cmd = "getvar:" + cmd.substring(7).trim()
        }

        val cmdBytes = cmd.toByteArray(Charsets.US_ASCII)
        onDebugLog("Fastboot 发送: $cmd")

        // 1. 发送命令
        val sent = conn.bulkTransfer(out, cmdBytes, cmdBytes.size, TIMEOUT_MS)
        if (sent <= 0) {
            return "发送命令失败 (返回 $sent)"
        }

        // 2. 接收响应状态流 (INFO / OKAY / FAIL / DATA)
        val sb = StringBuilder()
        val buffer = ByteArray(4096)
        val deadline = System.currentTimeMillis() + 8000

        while (System.currentTimeMillis() < deadline) {
            val len = conn.bulkTransfer(inEp, buffer, buffer.size, 1500)
            if (len <= 0) break

            val response = String(buffer, 0, len, Charsets.US_ASCII)
            if (response.length >= 4) {
                val status = response.substring(0, 4)
                val payload = response.substring(4)

                when (status) {
                    "INFO" -> {
                        if (payload.isNotBlank()) {
                            onLog("(bootloader) $payload")
                            sb.append(payload).append("\n")
                        }
                    }
                    "OKAY" -> {
                        if (payload.isNotBlank()) sb.append(payload).append("\n")
                        sb.append("OKAY [完成]")
                        break
                    }
                    "FAIL" -> {
                        sb.append("FAIL [失败]: ").append(payload)
                        break
                    }
                    "DATA" -> {
                        sb.append("DATA ").append(payload)
                        break
                    }
                    else -> {
                        sb.append(response).append("\n")
                    }
                }
            } else {
                sb.append(response).append("\n")
            }
        }

        return sb.toString().trim()
    }

    /**
     * AOSP 标准刷入单分区镜像流程（download 镜像流式上传 + flash 物理分区烧录）
     */
    fun flashPartitionImage(
        context: android.content.Context,
        uri: android.net.Uri,
        partition: String,
        onProgress: (percent: Float) -> Unit
    ): String {
        if (!isConnected) return "Fastboot 设备未连接"
        val conn = connection ?: return "Fastboot 连接已断开"
        val out = bulkOut ?: return "输出端点不可用"
        val inEp = bulkIn ?: return "输入端点不可用"

        val contentResolver = context.contentResolver
        val fileSize = try {
            contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        } catch (_: Exception) { -1L }
        if (fileSize <= 0) return "无法读取镜像文件大小"

        val inputStream = try {
            contentResolver.openInputStream(uri) ?: return "无法打开镜像输入流"
        } catch (e: Exception) { return "读取异常: ${e.message}" }

        try {
            // 1. 发送 download 命令 (8位16进制大小)
            val hexSize = "%08x".format(fileSize)
            val dlCmd = "download:$hexSize".toByteArray(Charsets.US_ASCII)
            onLog("正在准备上传镜像到内存 ($hexSize, ${(fileSize / 1024 / 1024)}MB)...")
            conn.bulkTransfer(out, dlCmd, dlCmd.size, TIMEOUT_MS)

            // 读取 DATA 响应
            val respBuf = ByteArray(256)
            val respLen = conn.bulkTransfer(inEp, respBuf, respBuf.size, 4000)
            if (respLen <= 0) return "设备未响应 download 指令"
            val respStr = String(respBuf, 0, respLen, Charsets.US_ASCII)
            if (!respStr.startsWith("DATA")) return "设备拒绝下载数据: $respStr"

            // 2. 循环推送镜像数据
            val buffer = ByteArray(65536)
            var totalSent = 0L
            while (true) {
                val n = inputStream.read(buffer)
                if (n <= 0) break
                val sent = conn.bulkTransfer(out, buffer, n, TIMEOUT_MS)
                if (sent <= 0) return "发送镜像数据中断 (返回 $sent)"
                totalSent += n
                onProgress(totalSent.toFloat() / fileSize.toFloat())
            }

            // 读取 download 完成后的 OKAY
            val okLen = conn.bulkTransfer(inEp, respBuf, respBuf.size, 6000)
            val okStr = if (okLen > 0) String(respBuf, 0, okLen, Charsets.US_ASCII) else ""
            if (!okStr.startsWith("OKAY")) return "镜像传输校验失败: $okStr"
            onLog("镜像数据上传完毕，正在烧录至物理分区 [$partition]...")

            // 3. 发送 flash:分区名
            return execute("flash:$partition")
        } catch (e: Exception) {
            return "刷入镜像异常: ${e.message}"
        } finally {
            try { inputStream.close() } catch (_: Exception) {}
        }
    }

    /**
     * 断开连接并释放资源
     */
    fun disconnect() {
        isConnected = false
        try {
            usbInterface?.let { connection?.releaseInterface(it) }
        } catch (_: Exception) {}
        try {
            connection?.close()
        } catch (_: Exception) {}
        connection = null
        usbInterface = null
        bulkIn = null
        bulkOut = null
    }
}
