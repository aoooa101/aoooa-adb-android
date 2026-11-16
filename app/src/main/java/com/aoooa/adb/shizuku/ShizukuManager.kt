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

    /** 后台状态检查执行器：远程 Binder 探测不在主线程执行。 */
    private val statusExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "shizuku-status").apply { isDaemon = true }
    }
    private val checkInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

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

    /**
     * 异步检查 Shizuku 状态。
     * 远程 Binder 调用可能阻塞，因此统一在后台单线程执行，避免阻塞主线程。
     */
    fun checkStatus() {
        if (!checkInFlight.compareAndSet(false, true)) return
        statusExecutor.execute {
            try {
                checkStatusBlocking()
            } finally {
                checkInFlight.set(false)
            }
        }
    }

    private fun checkStatusBlocking() {
        try {
            val ping = Shizuku.pingBinder()
            isBinderAlive.value = ping
            if (ping) {
                val granted = try {
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                } catch (e: Exception) {
                    com.aoooa.adb.AdbManager.debugLog("[Shizuku] 检查权限异常: ${e.javaClass.simpleName}: ${e.message}")
                    false
                }
                isAuthorized.value = granted
            } else {
                isAuthorized.value = false
            }
        } catch (e: Exception) {
            com.aoooa.adb.AdbManager.debugLog("[Shizuku] 检查状态异常: ${e.javaClass.simpleName}: ${e.message}")
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

    private val commandTimeouts = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "shizuku-command-timeout").apply { isDaemon = true }
    }

    /** 通过 UserService 执行有限输出命令，超时关闭管道并终止远端命令。 */
    fun exec(command: String): String {
        if (!isAuthorized.value) return ""
        return try {
            ShizukuCommandClient.open(arrayOf("sh", "-c", command)).use { session ->
                val timeout = commandTimeouts.schedule({ session.close() }, 30, java.util.concurrent.TimeUnit.SECONDS)
                try {
                    val reader = InputStreamReader(session.input, StandardCharsets.UTF_8)
                    val result = StringBuilder()
                    val buffer = CharArray(4096)
                    while (true) {
                        val n = reader.read(buffer)
                        if (n < 0) break
                        check(result.length + n <= 4 * 1024 * 1024) { "Command output limit exceeded" }
                        result.append(buffer, 0, n)
                    }
                    result.toString().trimEnd('\n')
                } finally {
                    timeout.cancel(false)
                }
            }
        } catch (e: Exception) {
            com.aoooa.adb.AdbManager.debugLog("[Shizuku] 执行命令异常: ${e.javaClass.simpleName}")
            ""
        }
    }

    /** 后台绑定 UserService 并逐行读取 Logcat，关闭句柄可取消绑定及读取。 */
    fun startLogcatProcess(args: String = "-v time", onLine: (String) -> Unit): AutoCloseable? {
        if (!isAuthorized.value) return null
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        val active = java.util.concurrent.atomic.AtomicReference<ShizukuCommandClient.Session?>()
        val worker = Thread({
            try {
                val options = args.ifBlank { "-v time" }.trim().split(Regex("\\s+"))
                ShizukuCommandClient.open((listOf("logcat") + options).toTypedArray()).use { session ->
                    active.set(session)
                    if (stopped.get()) return@use
                    BufferedReader(InputStreamReader(session.input, StandardCharsets.UTF_8)).use { reader ->
                        while (!stopped.get()) {
                            val line = reader.readLine() ?: break
                            onLine(line + "\n")
                        }
                    }
                }
            } catch (e: Exception) {
                if (!stopped.get()) {
                    com.aoooa.adb.AdbManager.debugLog("[Shizuku] Logcat 读取异常: ${e.javaClass.simpleName}")
                }
            } finally {
                active.getAndSet(null)?.close()
            }
        }, "shizuku-logcat-reader").apply { isDaemon = true; start() }
        return AutoCloseable {
            stopped.set(true)
            active.getAndSet(null)?.close()
            worker.interrupt()
        }
    }
}
