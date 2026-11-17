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
internal fun AdbConnection.openService(
    service: String,
    timeoutMs: Long = AdbConnection.SHELL_TIMEOUT_MS
): String {
    if (!isAuthenticated) return ""
    val output = AdbServiceOutput()
    val bytes = java.io.ByteArrayOutputStream()
    val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
    val localId = openRawStream(service, onBytes = { output.offer(it) },
        onClosed = { output.finish() }, autoAcknowledge = false)
    if (localId == 0) return ""
    try {
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) {
                onDebugLog("[Service] timeout localId=$localId timeoutMs=$timeoutMs receivedBytes=${bytes.size()}")
                break
            }
            val waitMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining).coerceIn(1L, 200L)
            val data = output.poll(waitMs)
            if (data != null) {
                bytes.write(data)
                acknowledgeRawStream(localId)
            }
            if (output.isFinished()) break
        }
        return String(bytes.toByteArray(), Charsets.UTF_8).trimEnd('\n')
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        throw error
    } finally {
        closeRawStream(localId)
    }
}

/** 在被控端执行一条 shell 命令并返回其输出。 */
fun AdbConnection.shell(command: String, logCommand: Boolean = true): String {
    if (logCommand) onLog("> $command")
    return openService("shell:$command")
}

/** 请求被控端把 adbd 切换到 TCP 监听模式。 */
fun AdbConnection.enableTcpip(port: Int = 5555): String = openService("tcpip:$port")

/** 请求被控端把 adbd 切回 USB 模式。 */
fun AdbConnection.disableTcpip(): String = openService("usb:")
