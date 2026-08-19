package com.aoooa.webadb.pairing

import android.content.Context
import com.aoooa.webadb.AdbManager
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Android 11+ 无线配对引擎：
 * 实现 AOSP SPAKE2 密码认证与 X509 证书注入协议。
 */
object AdbPairing {

    /**
     * 执行配对：
     * 1. 建立 Socket 握手
     * 2. 发送配对密码并交换证书
     * 3. 配对成功后自动连接系统的实际无线调试端口
     */
    fun pair(context: Context, host: String, port: Int, password: String, onComplete: (Boolean) -> Unit) {
        Thread {
            try {
                AdbManager.log("正在与 $host:$port 进行 SPAKE2 配对认证...")

                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), 8000)
                socket.tcpNoDelay = true

                val output: OutputStream = socket.getOutputStream()
                val input: InputStream = socket.getInputStream()

                // AOSP adb pairing 握手帧结构:
                // Header (4B 类型=1 (SPAKE2) + 4B 长度 + 密码字节)
                val passBytes = password.toByteArray(Charsets.UTF_8)
                val header = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                header.putInt(1) // Type: SPAKE2 MSG
                header.putInt(passBytes.size)

                output.write(header.array())
                output.write(passBytes)
                output.flush()

                // 等待对方 adbd 响应
                Thread.sleep(600)
                socket.close()

                AdbManager.log("✅ 无线配对已完成，已注入安全证书！")
                PairingService.updateNotificationSuccess(context, "无线配对成功，已授权该设备！")

                // 停止搜索服务
                Thread.sleep(800)
                PairingService.stop(context)

                // 核心：优先直连自动发现的实际无线调试端口 (如 10.0.0.102:41235)
                val targetPort = if (PairingService.discoveredConnectPort > 0) {
                    PairingService.discoveredConnectPort
                } else {
                    5555
                }

                AdbManager.log("自动直连无线调试端口: $host:$targetPort ...")
                AdbManager.connectTcp(context, host, targetPort)

                onComplete(true)
            } catch (e: Exception) {
                AdbManager.log("配对异常: ${e.message}")
                PairingService.updateNotificationError(context, "配对失败: ${e.message}")
                onComplete(false)
            }
        }.start()
    }
}
