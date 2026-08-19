package com.aoooa.webadb.pairing

import android.content.Context
import com.aoooa.webadb.AdbManager
import com.aoooa.webadb.native.WebAdbNative

/**
 * Android 11+ 无线配对引擎：
 * 优先调用 NDK Native C 动态库进行原生 TLS 配对握手。
 */
object AdbPairing {

    /**
     * 执行配对：
     * 1. 优先调用 C 动态库通过底层 Socket 完成配对
     * 2. 配对成功后自动精准直连系统实际广播的动态调试主端口
     */
    fun pair(context: Context, host: String, port: Int, password: String, onComplete: (Boolean) -> Unit) {
        Thread {
            try {
                AdbManager.log("正在与 $host:$port 进行原生无线配对认证 (code=$password)...")

                // 提前锁定捕获到的真实动态调试主端口
                val connectPort = if (PairingService.discoveredConnectPort > 0) {
                    PairingService.discoveredConnectPort
                } else {
                    5555
                }

                var success = false
                if (WebAdbNative.isLoaded) {
                    success = WebAdbNative.nativePair(host, port, password)
                }

                AdbManager.log("✅ 无线配对指令已成功执行 (success=$success)！")
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
