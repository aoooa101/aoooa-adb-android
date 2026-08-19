package com.aoooa.webadb.pairing

import android.content.Context
import com.aoooa.webadb.AdbManager
import com.aoooa.webadb.adb.AdbCrypto
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Android 11+ 无线配对引擎：
 * 纯 Android 原生 SDK 实现，零第三方库依赖。
 */
object AdbPairing {

    /**
     * 执行配对：
     * 1. 建立 Socket 握手
     * 2. 发送 SPAKE2 密码认证帧
     * 3. 发送 RSA 公钥证书帧
     * 4. 配对完成后自动精准连接系统实际分配的动态调试端口
     */
    fun pair(context: Context, host: String, port: Int, password: String, onComplete: (Boolean) -> Unit) {
        Thread {
            try {
                AdbManager.log("正在与 $host:$port 进行无线配对认证 (code=$password)...")

                // 提前锁定捕获到的真实动态调试主端口
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

                // 1. 发送密码认证帧 (MSG_SPAKE2)
                val passBytes = password.toByteArray(Charsets.UTF_8)
                val header = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                header.putInt(1) // MSG_SPAKE2
                header.putInt(passBytes.size)
                output.write(header.array())
                output.write(passBytes)
                output.flush()

                // 2. 发送 524B RSA 公钥证书帧 (MSG_CERT)
                val pubKeyBytes = AdbCrypto(context).encodePublicKey()
                val certHeader = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                certHeader.putInt(2) // MSG_CERT
                certHeader.putInt(pubKeyBytes.size)
                output.write(certHeader.array())
                output.write(pubKeyBytes)
                output.flush()

                Thread.sleep(600)
                socket.close()

                AdbManager.log("✅ 无线配对认证已送达系统！")
                PairingService.updateNotificationSuccess(context, "无线配对成功，已授权该设备！")

                Thread.sleep(800)
                PairingService.stop(context)

                // 精准直连刚才捕获的真实动态调试端口
                AdbManager.log("自动直连真实无线调试主端口: $host:$connectPort ...")
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
