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

    /**
     * 检查并获取原生 adb 可执行文件路径。
     * 优先使用系统安装的原生库目录 nativeLibraryDir：libadb.so 作为 jniLibs 打包，
     * 系统安装时会提取到 /data/app/<pkg>/lib/<abi>/（manifest extractNativeLibs=true），
     * 该目录文件 SELinux 类型允许执行；而 Android 10+ 禁止 exec 应用私有 files/ 目录
     * 下的文件（error=13 Permission denied），故私有目录仅作低版本兼容兜底。
     */
    fun getAdbExecutable(context: Context): File? {
        // 1. 优先探测系统原生库目录（系统安装时已带可执行权限，SELinux 放行 exec）
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val soFile = File(nativeDir, "libadb.so")
        if (soFile.exists() && soFile.length() > 1000) {
            try {
                if (!soFile.canExecute()) {
                    soFile.setExecutable(true, false)
                }
            } catch (_: Exception) {}
            if (soFile.canExecute()) {
                return soFile
            }
        }

        // 2. 兜底：若私有 files/bin/adb 已存在且完整，赋予权限后返回（兼容低版本 Android）
        val binDir = File(context.filesDir, "bin")
        val adbFile = File(binDir, "adb")
        if (adbFile.exists() && adbFile.length() > 1000) {
            try {
                if (!adbFile.canExecute()) {
                    adbFile.setExecutable(true, false)
                    Runtime.getRuntime().exec(arrayOf("chmod", "755", adbFile.absolutePath)).waitFor()
                }
            } catch (_: Exception) {}
            return adbFile
        }

        // 3. 终极自解压保障：从自身 APK (base.apk) 提取 libadb.so 到私有 bin/adb
        try {
            binDir.mkdirs()
            val apkPath = context.applicationInfo.sourceDir
            val apkFile = File(apkPath)
            if (apkFile.exists()) {
                java.util.zip.ZipFile(apkFile).use { zip ->
                    val entry = zip.getEntry("lib/arm64-v8a/libadb.so")
                        ?: zip.entries().asSequence().firstOrNull { it.name.endsWith("/libadb.so") }
                    if (entry != null) {
                        zip.getInputStream(entry).use { input ->
                            java.io.FileOutputStream(adbFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
            }

            if (adbFile.exists() && adbFile.length() > 1000) {
                adbFile.setReadable(true, false)
                adbFile.setExecutable(true, false)
                try {
                    Runtime.getRuntime().exec(arrayOf("chmod", "755", adbFile.absolutePath)).waitFor()
                } catch (_: Exception) {}
                return adbFile
            }
        } catch (e: Exception) {
            AdbManager.debugLog("[AdbCli] 提取并部署 libadb.so 异常: ${e.message}")
        }

        return null
    }

    /** 检查并获取原生 fastboot 可执行文件路径（优先 nativeLibraryDir） */
    fun getFastbootExecutable(context: Context): File? {
        // 1. 优先探测系统原生库目录
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val soFile = File(nativeDir, "libfastboot.so")
        if (soFile.exists() && soFile.length() > 1000) {
            try {
                if (!soFile.canExecute()) {
                    soFile.setExecutable(true, false)
                }
            } catch (_: Exception) {}
            if (soFile.canExecute()) {
                return soFile
            }
        }

        // 2. 探测私有 bin 目录: files/bin/fastboot
        val binDir = File(context.filesDir, "bin")
        val fbFile = File(binDir, "fastboot")
        if (fbFile.exists() && fbFile.length() > 1000) {
            try {
                if (!fbFile.canExecute()) {
                    fbFile.setExecutable(true, false)
                    Runtime.getRuntime().exec(arrayOf("chmod", "755", fbFile.absolutePath)).waitFor()
                }
            } catch (_: Exception) {}
            return fbFile
        }

        // 3. 终极自解压保障：从自身 APK (base.apk) 提取 libfastboot.so 到私有 bin/fastboot
        try {
            binDir.mkdirs()
            val apkPath = context.applicationInfo.sourceDir
            val apkFile = File(apkPath)
            if (apkFile.exists()) {
                java.util.zip.ZipFile(apkFile).use { zip ->
                    val entry = zip.getEntry("lib/arm64-v8a/libfastboot.so")
                        ?: zip.entries().asSequence().firstOrNull { it.name.endsWith("/libfastboot.so") }
                    if (entry != null) {
                        zip.getInputStream(entry).use { input ->
                            java.io.FileOutputStream(fbFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
            }

            if (fbFile.exists() && fbFile.length() > 1000) {
                fbFile.setReadable(true, false)
                fbFile.setExecutable(true, false)
                try {
                    Runtime.getRuntime().exec(arrayOf("chmod", "755", fbFile.absolutePath)).waitFor()
                } catch (_: Exception) {}
                return fbFile
            }
        } catch (e: Exception) {
            AdbManager.debugLog("[AdbCli] 提取并部署 libfastboot.so 异常: ${e.message}")
        }

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
     * 执行原生 ADB / Fastboot 命令
     * @param context 上下文
     * @param cmdLine 用户输入的命令字符串（如 "adb devices", "fastboot devices", "shell ls /sdcard"）
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
                val rawArgs = splitCommand(trimmed)
                val isFastbootCmd = rawArgs.isNotEmpty() && rawArgs[0].equals("fastboot", ignoreCase = true)

                val binFile = if (isFastbootCmd) {
                    getFastbootExecutable(context)
                } else {
                    getAdbExecutable(context)
                }

                if (binFile == null) {
                    val targetName = if (isFastbootCmd) "fastboot 二进制 (libfastboot.so)" else "adb 二进制 (libadb.so)"
                    AdbManager.appendAdbTerminalContent("[错误] 未找到原生 $targetName\n")
                    onComplete()
                    return@execute
                }

                if (!isFastbootCmd) {
                    // 准备 ADB 密钥专属适配
                    syncAdbKey(context)
                }

                val finalArgs = mutableListOf<String>()
                finalArgs.add(binFile.absolutePath)

                if (rawArgs.isNotEmpty() && (rawArgs[0].equals("adb", ignoreCase = true) || rawArgs[0].equals("fastboot", ignoreCase = true))) {
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

                var totalCharsRead = 0
                val buffer = CharArray(2048)
                val lineSb = java.lang.StringBuilder()

                InputStreamReader(process.inputStream, StandardCharsets.UTF_8).use { reader ->
                    var count: Int
                    while (reader.read(buffer).also { count = it } != -1) {
                        totalCharsRead += count
                        for (i in 0 until count) {
                            val ch = buffer[i]
                            if (ch == '\n') {
                                AdbManager.appendAdbTerminalContent(lineSb.toString() + "\n")
                                lineSb.setLength(0)
                            } else {
                                lineSb.append(ch)
                            }
                        }
                    }
                }

                if (lineSb.isNotEmpty()) {
                    AdbManager.appendAdbTerminalContent(lineSb.toString() + "\n")
                    lineSb.setLength(0)
                }

                val exitCode = process.waitFor()
                AdbManager.debugLog("[AdbCli] 命令执行完毕: '$trimmed', exitCode=$exitCode, 读取字符数=$totalCharsRead")

                if (exitCode != 0 && totalCharsRead == 0) {
                    val msg = when (exitCode) {
                        132 -> "[执行异常] 进程被系统信号终止: SIGILL (Illegal instruction 非法指令)"
                        139 -> "[执行异常] 进程被系统信号终止: SIGSEGV (段错误/内存访问违规)"
                        else -> "[执行异常] 命令异常退出: exitCode=$exitCode"
                    }
                    AdbManager.appendAdbTerminalContent("$msg\n")
                }
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
