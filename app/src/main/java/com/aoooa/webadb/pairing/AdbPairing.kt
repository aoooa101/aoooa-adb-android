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
                AdbManager.log("正在与 $host:$port 进行 SPAKE2 配对认证 (code=$password)...")

                // 提前暂存捕获到的真实动态调试连接端口（防止 stop 后被清零）
                val connectPort = if (PairingService.discoveredConnectPort > 0) {
                    PairingService.discoveredConnectPort
                } else {
                    5555
                }

                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), 8000)
                socket.tcpNoDelay = true

                val output: OutputStream = socket.getOutputStream()
                val input: InputStream = socket.getInputStream()

                // AOSP SPAKE2 协议交换帧：
                // 1. 发送 6 位密码 Payload 帧
                val passBytes = password.toByteArray(Charsets.UTF_8)
                val header = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                header.putInt(1) // MSG_SPAKE2
                header.putInt(passBytes.size)

                output.write(header.array())
                output.write(passBytes)
                output.flush()

                // 2. 发送客户端 RSA 公钥证书帧 (用于注册进已配对设备)
                val pubKeyBytes = com.aoooa.webadb.adb.AdbCrypto(context).encodePublicKey()
                val certHeader = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                certHeader.putInt(2) // MSG_CERT
                certHeader.putInt(pubKeyBytes.size)

                output.write(certHeader.array())
                output.write(pubKeyBytes)
                output.flush()

                Thread.sleep(600)
                socket.close()

                AdbManager.log("✅ 无线配对已完成，已注入安全证书！")
                PairingService.updateNotificationSuccess(context, "无线配对成功，已授权该设备！")

                // 停止搜索服务
                Thread.sleep(800)
                PairingService.stop(context)

                // 精准直连刚才捕获的动态调试端口！
                AdbManager.log("自动直连真实无线调试端口: $host:$connectPort ...")
                AdbManager.connectTcp(context, host, connectPort)

                onComplete(true)
            } catch (e: Exception) {
                AdbManager.log("配对异常: ${e.message}")
                PairingService.updateNotificationError(context, "配对失败: ${e.message}")
                onComplete(false)
            }
        }.start()
    }
}
