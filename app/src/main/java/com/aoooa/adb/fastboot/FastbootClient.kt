package com.aoooa.adb.fastboot

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

/**
 * Fastboot 协议客户端，基于 Android UsbManager 实现。
 *
 * Fastboot 协议规范：
 * - 接口特征：class=0xFF(255), subclass=0x42(66), protocol=0x03(3)
 * - 交互协议：纯 ASCII 请求指令 + 4 字节响应头状态机（对齐 AOSP fastboot 官方规范）：
 *   - INFOxxxx : 过程输出信息（如 getvar:all 连续输出）
 *   - TEXTxxxx : 追加到信息流的长文本分片（无换行）
 *   - OKAYxxxx : 成功完成
 *   - FAILxxxx : 失败原因
 *   - DATAxxxx : 准备传输数据（12 字节定长包，8 位十六进制长度）
 */
class FastbootClient(
    private val onLog: (String) -> Unit = {},
    private val onDebugLog: (String) -> Unit = {}
) {
    companion object {
        const val FASTBOOT_CLASS = 0xFF
        const val FASTBOOT_SUBCLASS = 0x42
        const val FASTBOOT_PROTOCOL = 0x03
        private const val ADB_PROTOCOL = 0x01
        private const val TIMEOUT_MS = 3000
        private const val RESPONSE_TIMEOUT_MS = 60000
        private const val FLASH_TIMEOUT_MS = 600000
        private const val DOWNLOAD_OKAY_TIMEOUT_MS = 15000
        private const val MAX_COMMAND_BYTES = 4096
        private const val IDLE_POLL_MS = 1500
        private const val MAX_IDLE_ROUNDS = 3
    }

    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var bulkIn: UsbEndpoint? = null
    private var bulkOut: UsbEndpoint? = null

    @Volatile
    private var isConnected = false

    val connected: Boolean get() = isConnected

    /**
     * 连接 Fastboot USB 设备并初始化端点（对齐 Google fastboot-mobile 多级兼容规范）
     */
    fun connect(usbManager: UsbManager, device: UsbDevice): Boolean {
        return try {
            val allIfaces = (0 until device.interfaceCount).map { device.getInterface(it) }

            // 多级查找通信接口（严格对齐 AOSP 官方 fastboot 识别策略）：
            // 官方 fastboot.cpp match_fastboot_with_serial() 仅匹配 class=0xFF/subclass=0x42/protocol=0x03，
            // 不做宽松兜底、不做探测；设备端官方 gadget (f_fastboot.c) 同样定义 FF/42/03 + 双 bulk
            // 1. 标准 Fastboot: class=255, subclass=66, protocol=3（官方唯一识别标准）
            // 2. 厂商非标变体兜底: class=255, subclass=66（排除 ADB protocol=1）且须含 Bulk IN/OUT
            //    （subclass=0x42 为强特异信号；高通 EDL 9008 为 FF/FF/FF、MTK VCOM/三星 Odin 等
            //     非 fastboot 接口均不会命中，故不再对任意 class=0xFF bulk 接口兜底）
            val iface = allIfaces.firstOrNull {
                it.interfaceClass == FASTBOOT_CLASS && it.interfaceSubclass == FASTBOOT_SUBCLASS && it.interfaceProtocol == FASTBOOT_PROTOCOL
            } ?: allIfaces.firstOrNull {
                it.interfaceClass == FASTBOOT_CLASS && it.interfaceSubclass == FASTBOOT_SUBCLASS &&
                    it.interfaceProtocol != ADB_PROTOCOL && hasBulkInOut(it)
            }

            if (iface == null) {
                onLog("未找到 Fastboot 通信端点，设备描述符不兼容")
                return false
            }

            onDebugLog("选定 Fastboot 接口: #${iface.id} (class=${iface.interfaceClass} sub=${iface.interfaceSubclass} proto=${iface.interfaceProtocol})")

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

    private fun hasBulkInOut(iface: UsbInterface): Boolean {
        var hasIn = false
        var hasOut = false
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) hasIn = true
                if (ep.direction == UsbConstants.USB_DIR_OUT) hasOut = true
            }
        }
        return hasIn && hasOut
    }

    /**
     * 解析 getvar:max-download-size 等返回的十六进制大小（兼容 0x 前缀与附带的状态行）
     */
    private fun parseSizeHex(raw: String): Long {
        // 对齐官方 fastboot.cpp 的 strtoul(response, NULL, 0) 语义：
        // 0x 前缀按十六进制，纯数字按十进制；无前缀纯十六进制仅作非标设备兜底
        val line = raw.lines().firstOrNull { it.isNotBlank() }?.trim() ?: return -1L
        val body = line.substringBefore(' ').trim().substringAfterLast(':').trim()
        if (body.startsWith("0x") || body.startsWith("0X")) {
            return body.substring(2).toLongOrNull(16) ?: -1L
        }
        return body.toLongOrNull(10) ?: body.toLongOrNull(16) ?: -1L
    }

    /** 命令终态：status 取值 OKAY / FAIL / DATA / TIMEOUT，payload 为终态随附文本。 */
    private data class FbResult(
        val status: String,
        val payload: String,
        val log: String
    ) {
        val isOkay: Boolean get() = status == "OKAY"
    }

    /**
     * 执行一条 Fastboot 命令并接收完整返回（UI 展示用文本形式）。
     */
    fun execute(rawCommand: String, timeoutMs: Int = RESPONSE_TIMEOUT_MS): String =
        runCommand(rawCommand, timeoutMs).log

    /**
     * 执行一条 Fastboot 命令并返回结构化终态。
     *
     * @param quietExit true 表示连续静默即视为结束（普通查询命令）；
     *                  false 表示仅受总时限约束（flash/erase 等写入期间
     *                  bootloader 可能长时间静默，静默不能作为结束依据）
     */
    private fun runCommand(rawCommand: String, timeoutMs: Int, quietExit: Boolean = true): FbResult {
        if (!isConnected) return FbResult("FAIL", "", "Fastboot 设备未连接")
        val conn = connection ?: return FbResult("FAIL", "", "Fastboot 连接已断开")
        val out = bulkOut ?: return FbResult("FAIL", "", "输出端点不可用")
        val inEp = bulkIn ?: return FbResult("FAIL", "", "输入端点不可用")

        // 格式化命令（兼容用户输入 fastboot getvar all / getvar:all 等形式）
        var cmd = rawCommand.trim()
        if (cmd.startsWith("fastboot ")) {
            cmd = cmd.substring(9).trim()
        }
        if (cmd.startsWith("getvar ") && !cmd.startsWith("getvar:")) {
            cmd = "getvar:" + cmd.substring(7).trim()
        }

        val cmdBytes = cmd.toByteArray(Charsets.US_ASCII)
        // 官方协议：命令须为单包且不超过 4096 字节
        if (cmdBytes.size > MAX_COMMAND_BYTES) {
            return FbResult("FAIL", "", "命令超出官方协议 4096 字节上限 (${cmdBytes.size})")
        }
        onDebugLog("Fastboot 发送: $cmd")

        // 1. 发送命令
        val sent = conn.bulkTransfer(out, cmdBytes, cmdBytes.size, TIMEOUT_MS)
        if (sent <= 0) {
            return FbResult("FAIL", "", "发送命令失败 (返回 $sent)")
        }

        // 2. 接收响应状态流 (INFO / TEXT / OKAY / FAIL / DATA)
        // 官方协议：INFO/TEXT 为过程输出，须持续接收直至 OKAY/FAIL/DATA 终结
        val sb = StringBuilder()
        val buffer = ByteArray(4096)
        val deadline = System.currentTimeMillis() + timeoutMs
        var idleRounds = 0

        while (System.currentTimeMillis() < deadline) {
            val len = conn.bulkTransfer(inEp, buffer, buffer.size, IDLE_POLL_MS)
            if (len <= 0) {
                if (quietExit && ++idleRounds >= MAX_IDLE_ROUNDS) break
                continue
            }
            idleRounds = 0

            val response = String(buffer, 0, len, Charsets.US_ASCII)
            if (response.length < 4) {
                sb.append(response).append("\n")
                continue
            }
            val status = response.substring(0, 4)
            val payload = response.substring(4)

            when (status) {
                "INFO" -> {
                    if (payload.isNotBlank()) {
                        onLog("(bootloader) $payload")
                        sb.append(payload).append("\n")
                    }
                }
                "TEXT" -> {
                    // 官方协议 step#2b: TEXT 无格式化、无换行，payload 预期以 NULL 结尾，
                    // 拼接前须剥离尾部 NUL 终止符，避免污染终端显示
                    val textPayload = payload.trimEnd('\u0000')
                    if (textPayload.isNotEmpty()) sb.append(textPayload)
                }
                "OKAY" -> {
                    // getvar 的返回值位于 OKAY 之后（OKAY<value>），必须单独带出
                    if (payload.isNotBlank()) sb.append(payload).append("\n")
                    sb.append("OKAY [完成]")
                    return FbResult("OKAY", payload.trimEnd('\u0000'), sb.toString().trim())
                }
                "FAIL" -> {
                    sb.append("FAIL [失败]: ").append(payload)
                    return FbResult("FAIL", payload, sb.toString().trim())
                }
                "DATA" -> {
                    // 官方协议 DATA: 12 字节定长包，后 8 位十六进制为数据阶段长度
                    sb.append("DATA ").append(payload).append(" (数据阶段就绪)")
                    return FbResult("DATA", payload, sb.toString().trim())
                }
                else -> sb.append(response).append("\n")
            }
        }

        sb.append("\n[超时] 设备在 ${timeoutMs}ms 内未给出终态响应")
        return FbResult("TIMEOUT", "", sb.toString().trim())
    }

    /**
     * 刷入分区镜像（download 传输 + flash 烧录）
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
            // 0. 官方规范前置校验：先查询 max-download-size，超限直接拒绝，
            //    避免传输一半被 bootloader 拒绝或中断。
            //    getvar 的返回值位于 OKAY 响应的 payload 内，不能从拼接后的日志文本解析
            val maxDlResp = runCommand("getvar:max-download-size", RESPONSE_TIMEOUT_MS)
            val maxDl = parseSizeHex(
                if (maxDlResp.payload.isNotBlank()) maxDlResp.payload else maxDlResp.log
            )
            if (maxDl > 0 && fileSize > maxDl) {
                return "镜像 ${fileSize / 1024 / 1024}MB 超过设备 max-download-size 限制 (${maxDl / 1024 / 1024}MB)，请使用 sparse 镜像或分段刷入"
            }

            // 1. 发送 download 命令 (8位16进制大小)
            val hexSize = "%08x".format(fileSize)
            val dlCmd = "download:$hexSize".toByteArray(Charsets.US_ASCII)
            onLog("正在准备上传镜像到内存 ($hexSize, ${(fileSize / 1024 / 1024)}MB)...")
            val dlSent = conn.bulkTransfer(out, dlCmd, dlCmd.size, TIMEOUT_MS)
            if (dlSent <= 0) return "发送 download 指令失败 (返回 $dlSent)"

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
                // 对齐官方 usb_linux.cpp Write 语义：循环补发直到整块发完，
                // 防止 bulkTransfer 部分发送（0<sent<n）导致镜像数据流错位刷坏
                var offset = 0
                while (offset < n) {
                    val sent = conn.bulkTransfer(out, buffer, offset, n - offset, TIMEOUT_MS)
                    if (sent <= 0) return "发送镜像数据中断 (返回 $sent, 已传 $totalSent/$fileSize)"
                    offset += sent
                }
                totalSent += n
                onProgress(totalSent.toFloat() / fileSize.toFloat())
            }

            // download 阶段要求精确发送 0x%08x 字节，短读会让设备一直等待剩余数据
            if (totalSent != fileSize) {
                return "镜像读取不完整 (已读 $totalSent/$fileSize)，已中止刷入"
            }

            // 读取 download 完成后的 OKAY
            // 官方 transport 为阻塞等待；大镜像入 RAM 后校验可能超过数秒，放宽至 15 秒。
            // 期间 bootloader 可能先发 INFO/TEXT（如校验进度），须继续等到 OKAY/FAIL 为止
            var okStr = ""
            var transferOk = false
            val okDeadline = System.currentTimeMillis() + DOWNLOAD_OKAY_TIMEOUT_MS
            while (System.currentTimeMillis() < okDeadline) {
                val okLen = conn.bulkTransfer(inEp, respBuf, respBuf.size, 1500)
                if (okLen <= 0) continue
                val chunk = String(respBuf, 0, okLen, Charsets.US_ASCII)
                if (chunk.length < 4) continue
                when (chunk.substring(0, 4)) {
                    "OKAY" -> { transferOk = true; okStr = chunk; }
                    "FAIL" -> { okStr = chunk; }
                    "INFO" -> onLog("(bootloader) ${chunk.substring(4)}")
                    "TEXT" -> {}
                }
                if (transferOk || chunk.startsWith("FAIL")) break
            }
            if (!transferOk) return "镜像传输校验失败: ${okStr.ifBlank { "设备未确认" }}"
            onLog("镜像数据上传完毕，正在烧录至物理分区 [$partition]...")

            // 3. 发送 flash:分区名
            // 官方行为对齐：flash 烧录大分区时 bootloader 长时间静默直至 OKAY/FAIL，
            // 静默不能作为结束依据，此处仅受总时限约束
            return runCommand("flash:$partition", FLASH_TIMEOUT_MS, quietExit = false).log
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
