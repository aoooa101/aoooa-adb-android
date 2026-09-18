package com.aoooa.adb.shizuku

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.mutableStateOf
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Shizuku 权限与服务管理：
 * 1. 负责 Binder 存活探测与鉴权；
 * 2. 封装 requestPermission 交互；
 * 3. 在 Shizuku 环境下执行 shell/logcat 命令。
 */
object ShizukuManager {
    const val REQUEST_CODE_PERMISSION = 7001

    val isBinderAlive = mutableStateOf(false)
    val isAuthorized = mutableStateOf(false)

    @Volatile
    private var isInitialized = false

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
            com.aoooa.adb.AdbManager.debugLog("[Shizuku] 授权回调: isAuthorized=${isAuthorized.value}")
        }
    }

    fun init() {
        if (isInitialized) {
            checkStatus()
            return
        }
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)
            isInitialized = true
            checkStatus()
        } catch (e: Exception) {
            com.aoooa.adb.AdbManager.debugLog("[Shizuku] 初始化监听异常: ${e.message}")
        }
    }

    fun checkStatus() {
        try {
            val ping = Shizuku.pingBinder()
            isBinderAlive.value = ping
            if (ping) {
                val granted = try {
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                } catch (e: Exception) {
                    com.aoooa.adb.AdbManager.debugLog("[Shizuku] 检查权限异常: ${e.message}")
                    false
                }
                isAuthorized.value = granted
            } else {
                isAuthorized.value = false
            }
        } catch (e: Exception) {
            com.aoooa.adb.AdbManager.debugLog("[Shizuku] 检查状态异常: ${e.message}")
            isBinderAlive.value = false
            isAuthorized.value = false
        }
    }

    fun requestPermission() {
        try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(REQUEST_CODE_PERMISSION)
                } else {
                    isAuthorized.value = true
                }
            } else {
                com.aoooa.adb.AdbManager.debugLog("[Shizuku] Binder 未就绪，无法发起授权申请")
            }
        } catch (e: Exception) {
            com.aoooa.adb.AdbManager.debugLog("[Shizuku] 请求授权异常: ${e.message}")
        }
    }

    /**
     * 底层通过反射调用 Shizuku.newProcess，绕过 Kotlin 编译器的可见性限制
     */
    private fun createShizukuProcess(cmd: Array<String>, env: Array<String>? = null, dir: String? = null): Process {
        val method = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        method.isAccessible = true
        return method.invoke(null, cmd, env, dir) as Process
    }

    /**
     * 通过 Shizuku 执行单次 Shell 命令
     */
    fun exec(command: String): String {
        if (!isAuthorized.value) return ""
        return try {
            val process = createShizukuProcess(arrayOf("sh", "-c", command), null, null)
            val reader = BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line).append("\n")
            }
            process.waitFor()
            sb.toString().trimEnd('\n')
        } catch (e: Exception) {
            com.aoooa.adb.AdbManager.debugLog("[Shizuku] 执行命令异常: ${e.message}")
            ""
        }
    }

    /**
     * 通过 Shizuku 开启实时 Logcat 进程流
     */
    fun startLogcatProcess(args: String = "-v time", onLine: (String) -> Unit): AutoCloseable? {
        if (!isAuthorized.value) return null
        return try {
            val cmdList = mutableListOf("logcat")
            if (args.isNotBlank()) {
                cmdList.addAll(args.trim().split(Regex("\\s+")))
            } else {
                cmdList.addAll(listOf("-v", "time"))
            }
            val process = createShizukuProcess(cmdList.toTypedArray(), null, null)
            val thread = Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        line?.let { onLine(it) }
                    }
                } catch (e: Exception) {
                    com.aoooa.adb.AdbManager.debugLog("[Shizuku] Logcat 读取流中断: ${e.message}")
                }
            }
            thread.isDaemon = true
            thread.start()

            AutoCloseable {
                try {
                    process.destroy()
                } catch (e: Exception) {
                    com.aoooa.adb.AdbManager.debugLog("[Shizuku] 销毁 Logcat 进程异常: ${e.message}")
                }
            }
        } catch (e: Exception) {
            com.aoooa.adb.AdbManager.debugLog("[Shizuku] 启动 Logcat 进程异常: ${e.message}")
            null
        }
    }
}
