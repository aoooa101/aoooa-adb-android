package com.aoooa.webadb.adb

import android.content.Context
import com.aoooa.webadb.AdbManager
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * 原生 ADB 命令行（libadb.so）进程执行与交互管理引擎：
 * 1. 负责调度执行应用私有库路径下的原生 libadb.so 二进制；
 * 2. 注入 HOME、TMPDIR、ADB_SERVER_SOCKET 等专属环境变量适配，确保 adb 参数与密钥正常运作；
 * 3. 自动将 App 内部 RSA 密钥同步至 $HOME/.android/adbkey，免去重复弹窗授权；
 * 4. 支持长时间运行命令（如 logcat）的流式实时输出以及 Ctrl+C 强制打断。
 */
object AdbCliExecutor {

    private val executor = Executors.newCachedThreadPool()

    @Volatile
    private var activeProcess: Process? = null

    /** 检查并获取原生 adb 可执行文件路径（支持多级探测与从自身 APK 自动提取） */
    fun getAdbExecutable(context: Context): File? {
        // 1. 优先探测系统原生库目录
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val soFile = File(nativeDir, "libadb.so")
        if (soFile.exists() && soFile.length() > 1000) {
            if (!soFile.canExecute()) {
                try { soFile.setExecutable(true, false) } catch (_: Exception) {}
            }
            if (soFile.canExecute()) {
                return soFile
            }
        }

        // 2. 探测私有 bin 目录: files/bin/adb
        val binDir = File(context.filesDir, "bin")
        val fallback = File(binDir, "adb")
        if (fallback.exists() && fallback.length() > 1000) {
            if (!fallback.canExecute()) {
                try { fallback.setExecutable(true, false) } catch (_: Exception) {}
            }
            if (fallback.canExecute()) {
                return fallback
            }
        }

        // 3. 终极自解压保障：从自身 APK (sourceDir) 提取 libadb.so 到 files/bin/adb
        try {
            val apkPath = context.applicationInfo.sourceDir
            val apkFile = File(apkPath)
            if (apkFile.exists()) {
                java.util.zip.ZipFile(apkFile).use { zip ->
                    val entry = zip.getEntry("lib/arm64-v8a/libadb.so")
                        ?: zip.entries().asSequence().firstOrNull { it.name.endsWith("/libadb.so") }
                    if (entry != null) {
                        binDir.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            java.io.FileOutputStream(fallback).use { output ->
                                input.copyTo(output)
                            }
                        }
                        fallback.setReadable(true, false)
                        fallback.setExecutable(true, false)
                        try {
                            Runtime.getRuntime().exec(arrayOf("chmod", "755", fallback.absolutePath)).waitFor()
                        } catch (_: Exception) {}
                        if (fallback.exists() && fallback.length() > 1000) {
                            return fallback
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AdbManager.debugLog("[AdbCli] 自解压 libadb.so 异常: ${e.message}")
        }

        if (fallback.exists()) return fallback

        return null
    }

    /**
     * 专属适配：将 App 内部生成的 RSA 密钥同步至 $HOME/.android/ 目录下，
     * 保证原生 adb CLI 与应用复用同一对身份认证密钥，免去目标手机重复弹窗授权。
     */
    private fun syncAdbKey(context: Context) {
        try {
            val appKeyDir = File(context.filesDir, "keys")
            val appPriv = File(appKeyDir, "adbkey")
            val appPub = File(appKeyDir, "adbkey.pub")

            if (appPriv.exists()) {
                val dotAndroid = File(context.filesDir, ".android")
                if (!dotAndroid.exists()) {
                    dotAndroid.mkdirs()
                }
                val targetPriv = File(dotAndroid, "adbkey")
                val targetPub = File(dotAndroid, "adbkey.pub")

                if (!targetPriv.exists() || targetPriv.length() != appPriv.length()) {
                    appPriv.copyTo(targetPriv, overwrite = true)
                    try { targetPriv.setReadable(true, true); targetPriv.setWritable(true, true) } catch (_: Exception) {}
                }
                if (appPub.exists() && (!targetPub.exists() || targetPub.length() != appPub.length())) {
                    appPub.copyTo(targetPub, overwrite = true)
                    try { targetPub.setReadable(true, true); targetPub.setWritable(true, true) } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            AdbManager.debugLog("[AdbCli] 同步 adbkey 异常: ${e.message}")
        }
    }

    /**
     * 执行原生 ADB 命令
     * @param context 上下文
     * @param cmdLine 用户输入的命令字符串（如 "adb devices", "shell ls /sdcard"）
     * @param onComplete 执行完成回调
     */
    fun execute(context: Context, cmdLine: String, onComplete: () -> Unit = {}) {
        val trimmed = cmdLine.trim()
        if (trimmed.isEmpty()) {
            onComplete()
            return
        }

        // 如果已有命令在运行，先终止旧命令
        cancelCurrent()

        executor.execute {
            try {
                val adbFile = getAdbExecutable(context)
                if (adbFile == null) {
                    AdbManager.appendAdbTerminalContent("[错误] 未找到原生 adb 二进制 (libadb.so)\n")
                    onComplete()
                    return@execute
                }

                // 准备密钥专属适配
                syncAdbKey(context)

                // 解析命令行参数（智能处理是否有 adb 前缀）
                val rawArgs = splitCommand(trimmed)
                val finalArgs = mutableListOf<String>()
                finalArgs.add(adbFile.absolutePath)

                if (rawArgs.isNotEmpty() && rawArgs[0].equals("adb", ignoreCase = true)) {
                    finalArgs.addAll(rawArgs.drop(1))
                } else {
                    finalArgs.addAll(rawArgs)
                }

                val pb = ProcessBuilder(finalArgs)
                val env = pb.environment()

                // 核心专属环境变量注入
                val filesPath = context.filesDir.absolutePath
                val cachePath = context.cacheDir.absolutePath
                val nativeDir = context.applicationInfo.nativeLibraryDir

                env["HOME"] = filesPath
                env["TMPDIR"] = cachePath
                env["ANDROID_PREFS_ROOT"] = filesPath
                env["ADB_SERVER_SOCKET"] = "tcp:127.0.0.1:5037"
                env["PATH"] = "$nativeDir:" + (System.getenv("PATH") ?: "/system/bin")

                pb.directory(context.filesDir)
                pb.redirectErrorStream(true)

                val process = pb.start()
                activeProcess = process

                BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        line?.let { AdbManager.appendAdbTerminalContent(it + "\n") }
                    }
                }

                process.waitFor()
            } catch (e: Exception) {
                AdbManager.appendAdbTerminalContent("[执行异常] ${e.message}\n")
            } finally {
                activeProcess = null
                onComplete()
            }
        }
    }

    /** 终止当前正在运行的 ADB 命令行进程（响应用户 Ctrl+C） */
    fun cancelCurrent() {
        val proc = activeProcess
        if (proc != null) {
            try {
                proc.destroyForcibly()
                AdbManager.appendAdbTerminalContent("^C\n")
            } catch (_: Exception) {}
            activeProcess = null
        }
    }

    /** 简单的 shell 参数拆分（支持引号包裹） */
    private fun splitCommand(cmd: String): List<String> {
        val result = mutableListOf<String>()
        val sb = java.lang.StringBuilder()
        var inQuote = false
        var quoteChar = ' '

        for (c in cmd) {
            when {
                (c == '\'' || c == '"') && !inQuote -> {
                    inQuote = true
                    quoteChar = c
                }
                c == quoteChar && inQuote -> {
                    inQuote = false
                }
                c.isWhitespace() && !inQuote -> {
                    if (sb.isNotEmpty()) {
                        result.add(sb.toString())
                        sb.clear()
                    }
                }
                else -> {
                    sb.append(c)
                }
            }
        }
        if (sb.isNotEmpty()) {
            result.add(sb.toString())
        }
        return result
    }
}
