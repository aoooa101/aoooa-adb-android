package com.aoooa.adb.adb

/**
 * AdbConnection 的一次性服务流实现（shell / tcpip / usb 等 OPEN→WRTE→CLSE 短连接）。
 *
 * 职责边界：只负责"发一条命令并收集完整输出"，
 * 长连接的交互终端、logcat 与二进制流由 [AdbConnection] 自身维护会话状态。
 */

/**
 * 打开一个服务流并收集其全部输出，直到对端 CLSE。
 *
 * @param service 服务名，不含结尾 NUL
 * @return 设备返回的完整文本输出（已去除结尾换行）
 */
internal fun AdbConnection.openService(service: String): String {
    if (!isAuthenticated) return ""
    pendingPackets.clear()
    val localId = localIds.getAndIncrement()
    // 字节级累积输出,结束后一次性按 UTF-8 解码,避免逐包解码截断多字节字符
    val byteBuf = java.io.ByteArrayOutputStream()
    var remoteId = 0

    val servicePayload = (service + "\u0000").toByteArray(Charsets.UTF_8)
    onDebugLog("发送 OPEN($service localId=$localId payload=${servicePayload.size}B)")
    sendPacket(AdbPacket(AdbPacket.OPEN, localId, 0, servicePayload))

    val deadline = System.currentTimeMillis() + AdbConnection.SHELL_TIMEOUT_MS
    while (System.currentTimeMillis() < deadline) {
        val pkt = nextPacket(1000) ?: continue
        val cmdName = when (pkt.command) {
            AdbPacket.OKAY -> "OKAY"
            AdbPacket.WRTE -> "WRTE"
            AdbPacket.CLSE -> "CLSE"
            AdbPacket.CNXN -> "CNXN"
            AdbPacket.AUTH -> "AUTH"
            AdbPacket.STLS -> "STLS"
            AdbPacket.OPEN -> "OPEN"
            else -> "0x%08X".format(pkt.command)
        }
        onDebugLog("${cmdName} 到达 (arg0=${pkt.arg0} arg1=${pkt.arg1} payload=${pkt.payload.size}B) localId=$localId")
        when (pkt.command) {
            AdbPacket.OKAY -> {
                if (pkt.arg1 == localId) {
                    remoteId = pkt.arg0
                }
            }
            AdbPacket.WRTE -> {
                if (pkt.arg1 == localId) {
                    remoteId = pkt.arg0
                    byteBuf.write(pkt.payload, 0, pkt.payload.size)
                    sendPacket(AdbPacket(AdbPacket.OKAY, localId, remoteId))
                }
            }
            AdbPacket.CLSE -> {
                if (pkt.arg1 == localId) break
            }
        }
    }
    // 一次性 UTF-8 解码,不再因 WRTE 包边界截断多字节字符产生乱码
    return String(byteBuf.toByteArray(), Charsets.UTF_8).trimEnd('\n')
}

/** 在被控端执行一条 shell 命令并返回其输出。 */
fun AdbConnection.shell(command: String): String {
    onLog("> $command")
    return openService("shell:$command")
}

/** 请求被控端把 adbd 切换到 TCP 监听模式。 */
fun AdbConnection.enableTcpip(port: Int = 5555): String = openService("tcpip:$port")

/** 请求被控端把 adbd 切回 USB 模式。 */
fun AdbConnection.disableTcpip(): String = openService("usb:")
