package com.aoooa.webadb.shizuku

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.mutableStateOf
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * 官方 Shizuku 权限与特权服务管理器：
 * 1. 负责 Binder 存活探测与动态鉴权；
 * 2. 封装官方 requestPermission 交互；
 * 3. 支持在 Shizuku 特权环境下流式执行 logcat 与提取应用列表。
 */
object ShizukuManager {
    const val REQUEST_CODE_PERMISSION = 7001

    val isBinderAlive = mutableStateOf(false)
    val isAuthorized = mutableStateOf(false)

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        checkStatus()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        isBinderAlive.value = false
        isAuthorized.value = false
    }

    private val requestPermissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE_PERMISSION) {
            isAuthorized.value = (grantResult == PackageManager.PERMISSION_GRANTED)
        }
    }

    fun init() {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)
            checkStatus()
        } catch (_: Exception) {}
    }

    fun checkStatus() {
        try {
            val ping = Shizuku.pingBinder()
            isBinderAlive.value = ping
            if (ping) {
                val granted = try {
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                } catch (_: Exception) {
                    false
                }
                isAuthorized.value = granted
            } else {
                isAuthorized.value = false
            }
        } catch (_: Exception) {
            isBinderAlive.value = false
            isAuthorized.value = false
        }
    }

    fun requestPermission(activity: Activity? = null) {
        try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(REQUEST_CODE_PERMISSION)
                } else {
                    isAuthorized.value = true
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * 通过 Shizuku 执行单次 Shell 命令
     */
    fun exec(command: String): String {
        if (!isAuthorized.value) return ""
        return try {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            val reader = BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line).append("\n")
            }
            process.waitFor()
            sb.toString().trimEnd('\n')
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 通过 Shizuku 开启实时 Logcat 进程流
     */
    fun startLogcatProcess(args: String, onLine: (String) -> Unit): AutoCloseable? {
        if (!isAuthorized.value) return null
        return try {
            val cmdList = mutableListOf("logcat", "-v", "time")
            if (args.isNotBlank()) {
                cmdList.addAll(args.trim().split(Regex("\\s+")))
            }
            val process = Shizuku.newProcess(cmdList.toTypedArray(), null, null)
            val thread = Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        line?.let { onLine(it) }
                    }
                } catch (_: Exception) {}
            }
            thread.isDaemon = true
            thread.start()

            AutoCloseable {
                try {
                    process.destroy()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            null
        }
    }
}
