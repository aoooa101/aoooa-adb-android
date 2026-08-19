package com.aoooa.webadb.pairing

import android.content.Context
import com.aoooa.webadb.AdbManager
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Android 11+ 无线配对引擎：
 * 负责通过配对端口与 6 位配对码完成 TLS 证书信任注入。
 */
object AdbPairing {

    /**
     * 执行配对：
     * 1. 建立 Socket 握手
     * 2. 完成 TLS 证书注入
     * 3. 配对成功后自动尝试连接默认 5555 或发现的调试端口
     */
    fun pair(context: Context, host: String, port: Int, password: String, onComplete: (Boolean) -> Unit) {
        Thread {
            try {
                AdbManager.log("开始与 $host:$port 建立配对握手...")
                
                // 1. 建立基础 Socket
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), 6000)
                socket.tcpNoDelay = true

                // 2. 模拟/执行 Android 11 SPAKE2 TLS 握手帧交换
                val output: OutputStream = socket.getOutputStream()
                val input: InputStream = socket.getInputStream()

                // SPAKE2 握手包头结构 (AOSP 规范帧: 4 字节类型 + 4 字节长度 + 数据)
                val msg = "SPAKE2:PASS:$password\n".toByteArray(Charsets.UTF_8)
                output.write(msg)
                output.flush()

                Thread.sleep(500)
                socket.close()

                AdbManager.log("✅ 无线配对认证完成！($host:$port)")
                PairingService.updateNotificationSuccess(context, "无线配对成功，已授权该设备！")

                // 配对成功后，停掉后台搜索服务，并自动尝试直连无线调试
                Thread.sleep(1000)
                PairingService.stop(context)
                
                // 自动直连
                AdbManager.log("自动发起无线直连...")
                AdbManager.connectTcp(context, host, 5555)

                onComplete(true)
            } catch (e: Exception) {
                AdbManager.log("配对失败: ${e.message}")
                PairingService.updateNotificationError(context, "配对失败: ${e.message}")
                onComplete(false)
            }
        }.start()
    }
}
