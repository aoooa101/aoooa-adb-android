package com.aoooa.webadb.adb

import com.aoooa.webadb.AdbManager
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * 本地 5037 端口 ADB Server 代理服务：
 * 1. 监听 127.0.0.1:5037（仅限本机回环访问，安全隔离）；
 * 2. 响应原生 adb CLI 的标准握手指令（host:version, host:devices, host:connect 等）；
 * 3. 实时同步 UI 可视化连接状态与命令行 devices 列表；
 * 4. 支持在命令行通过 adb shell 执行远程设备指令并透传回显。
 */
object AdbServerProxy {

    private const val DEFAULT_PORT = 5037
    private const val ADB_SERVER_VERSION = 41 // 0x29, 即 1.0.41

    @Volatile
    private var isRunning = false
    private var serverSocket: ServerSocket? = null
    private val threadPool = Executors.newCachedThreadPool()

    /** 启动本地 5037 代理服务 */
    @Synchronized
    fun start(port: Int = DEFAULT_PORT) {
        if (isRunning) return
        isRunning = true

        threadPool.execute {
            try {
                // 仅绑定 127.0.0.1 回环地址
                val server = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
                serverSocket = server
                AdbManager.debugLog("[AdbServerProxy] 本地 5037 ADB 代理服务已启动 (127.0.0.1:$port)")

                while (isRunning && !server.isClosed) {
                    try {
                        val clientSocket = server.accept()
                        threadPool.execute { handleClient(clientSocket) }
                    } catch (e: Exception) {
                        if (!isRunning) break
                    }
                }
            } catch (e: Exception) {
                AdbManager.debugLog("[AdbServerProxy] 端口 $port 启动受限: ${e.message}")
            }
        }
    }

    /** 停止代理服务 */
    @Synchronized
    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 15000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            while (isRunning && socket.isConnected && !socket.isClosed) {
                val req = readAdbRequest(input) ?: break
                AdbManager.debugLog("[AdbServerProxy] 收到 CLI 请求: '$req'")

                when {
                    req == "host:version" -> {
                        // 回复 OKAY + 4 字节十六进制长度 + 0029 (41)
                        sendOkay(output)
                        val hexVer = String.format("%04x", ADB_SERVER_VERSION)
                        sendHexLengthData(output, hexVer)
                    }

                    req == "host:devices" -> {
                        sendOkay(output)
                        val deviceStr = buildDeviceList(longFormat = false)
                        sendHexLengthData(output, deviceStr)
                    }

                    req == "host:devices-l" -> {
                        sendOkay(output)
                        val deviceStr = buildDeviceList(longFormat = true)
                        sendHexLengthData(output, deviceStr)
                    }

                    req.startsWith("host:connect:") -> {
                        val target = req.removePrefix("host:connect:")
                        handleConnectCommand(output, target)
                    }

                    req.startsWith("host:disconnect:") || req == "host:disconnect" -> {
                        AdbManager.disconnect()
                        sendOkay(output)
                        val reply = "disconnected\n"
                        sendHexLengthData(output, reply)
                    }

                    req.startsWith("host:transport:") || req == "host:transport-any" || req == "host:transport-local" || req == "host:transport-usb" -> {
                        if (!AdbManager.connected.value) {
                            sendFail(output, "device not found")
                            break
                        }
                        sendOkay(output)
                        // 进入设备通信透传阶段
                        handleDeviceSession(socket, input, output)
                        break
                    }

                    req == "host:kill" -> {
                        sendOkay(output)
                        break
                    }

                    else -> {
                        // 未知命令优雅返回 FAIL
                        sendFail(output, "unknown request: $req")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            AdbManager.debugLog("[AdbServerProxy] Client 交互结束: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /** 读取 4 字节十六进制前缀 + 请求内容 */
    private fun readAdbRequest(input: InputStream): String? {
        val lenBuf = readFully(input, 4) ?: return null
        val lenStr = String(lenBuf, StandardCharsets.US_ASCII)
        val len = try {
            lenStr.toInt(16)
        } catch (_: Exception) {
            return null
        }
        if (len <= 0 || len > 65536) return null

        val dataBuf = readFully(input, len) ?: return null
        return String(dataBuf, StandardCharsets.UTF_8)
    }

    /** 构建设备列表文本（与 UI 连接状态实时同步） */
    private fun buildDeviceList(longFormat: Boolean): String {
        if (!AdbManager.connected.value) {
            return ""
        }

        val devName = AdbManager.deviceName.value.ifBlank { "device-1" }
        val devModel = AdbManager.model.value.ifBlank { "android" }

        return if (longFormat) {
            "$devName\tdevice product:$devModel model:$devModel device:$devName transport_id:1\n"
        } else {
            "$devName\tdevice\n"
        }
    }

    /** 处理 adb connect 命令行 */
    private fun handleConnectCommand(output: OutputStream, target: String) {
        val parts = target.split(":")
        val host = parts[0]
        val port = parts.getOrNull(1)?.toIntOrNull() ?: 5555

        sendOkay(output)

        // 异步尝试 TCP 连接并更新 UI 状态
        Thread {
            AdbManager.connectTcp(null, host, port)
        }.start()

        val reply = "connected to $host:$port\n"
        sendHexLengthData(output, reply)
    }

    /** 设备级会话透传（如 adb shell cmd） */
    private fun handleDeviceSession(socket: Socket, input: InputStream, output: OutputStream) {
        socket.soTimeout = 0 // 长时间命令（如 shell, logcat）取消超时
        val req = readAdbRequest(input) ?: return
        AdbManager.debugLog("[AdbServerProxy] 设备服务请求: '$req'")

        when {
            req.startsWith("shell:") -> {
                val cmd = req.removePrefix("shell:")
                sendOkay(output)

                val conn = AdbManager.connection
                if (conn != null && conn.isAuthenticated) {
                    val result = conn.shell(cmd)
                    if (result.isNotEmpty()) {
                        output.write(result.toByteArray(StandardCharsets.UTF_8))
                        output.flush()
                    }
                } else {
                    val err = "device offline\n".toByteArray(StandardCharsets.UTF_8)
                    output.write(err)
                    output.flush()
                }
            }

            else -> {
                sendFail(output, "service not supported via proxy: $req")
            }
        }
    }

    private fun sendOkay(output: OutputStream) {
        output.write("OKAY".toByteArray(StandardCharsets.US_ASCII))
        output.flush()
    }

    private fun sendFail(output: OutputStream, reason: String) {
        val bytes = reason.toByteArray(StandardCharsets.UTF_8)
        val lenHex = String.format("%04x", bytes.size)
        output.write("FAIL".toByteArray(StandardCharsets.US_ASCII))
        output.write(lenHex.toByteArray(StandardCharsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun sendHexLengthData(output: OutputStream, data: String) {
        val bytes = data.toByteArray(StandardCharsets.UTF_8)
        val lenHex = String.format("%04x", bytes.size)
        output.write(lenHex.toByteArray(StandardCharsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun readFully(input: InputStream, size: Int): ByteArray? {
        val buffer = ByteArray(size)
        var totalRead = 0
        while (totalRead < size) {
            val count = input.read(buffer, totalRead, size - totalRead)
            if (count < 0) return null
            totalRead += count
        }
        return buffer
    }
}
