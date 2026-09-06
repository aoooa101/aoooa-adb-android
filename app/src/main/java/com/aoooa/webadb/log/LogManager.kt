package com.aoooa.webadb.log

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.aoooa.webadb.AdbManager
import com.aoooa.webadb.shizuku.ShizukuManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class LogSource {
    FULL_DEVICE,    // 完整设备日志
    TARGET_APPS     // 仅指定应用日志
}

enum class FilterMode {
    NONE,           // 不按应用过滤
    WHITELIST,      // 白名单模式（只抓取指定应用）
    BLACKLIST       // 黑名单模式（排除指定应用）
}

data class LogLine(
    val id: String = UUID.randomUUID().toString(),
    val raw: String,
    val time: String = "",
    val pid: String = "",
    val level: String = "V",
    val tag: String = "",
    val message: String = ""
)

data class InstalledAppItem(
    val packageName: String,
    val label: String
)

object LogManager {
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 抓取状态与参数 */
    val isCapturing = mutableStateOf(false)
    val logSource = mutableStateOf(LogSource.FULL_DEVICE)
    val filterMode = mutableStateOf(FilterMode.NONE)

    /** 白名单与黑名单（严格互斥） */
    val whitelist = mutableStateListOf<String>()
    val blacklist = mutableStateListOf<String>()

    /** 搜索框关键词 */
    val searchQuery = mutableStateOf("")

    /** 界面日志缓冲列表（最多保持 5000 行） */
    val logLines = mutableStateListOf<LogLine>()

    /** 动态缓存：PID -> 包名 / 包名 -> PID集合 */
    private val pidToPackage = ConcurrentHashMap<String, String>()
    private val packageToPids = ConcurrentHashMap<String, MutableSet<String>>()

    @Volatile
    private var activeStream: AutoCloseable? = null

    // 标准 logcat -v time 正则匹配：09-06 12:34:56.789 D/Tag( 1234): message
    private val logcatTimeRegex = Regex("""^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+([VDIWEF])/([^(:]+)(?:\(\s*(\d+)\))?:\s*(.*)$""")

    /**
     * 切换过滤模式（保证白名单与黑名单严格互斥）
     */
    fun setFilterMode(mode: FilterMode) {
        filterMode.value = mode
    }

    fun addWhitelistPackage(pkg: String) {
        val clean = pkg.trim()
        if (clean.isNotBlank() && !whitelist.contains(clean)) {
            whitelist.add(clean)
        }
    }

    fun removeWhitelistPackage(pkg: String) {
        whitelist.remove(pkg)
    }

    fun addBlacklistPackage(pkg: String) {
        val clean = pkg.trim()
        if (clean.isNotBlank() && !blacklist.contains(clean)) {
            blacklist.add(clean)
        }
    }

    fun removeBlacklistPackage(pkg: String) {
        blacklist.remove(pkg)
    }

    fun clearLogs() {
        mainHandler.post {
            logLines.clear()
        }
    }

    /**
     * 刷新目标设备的 PID <-> Package 映射表
     */
    fun refreshProcessMap() {
        Thread {
            try {
                val output = when {
                    AdbManager.connected.value -> AdbManager.execCapture("ps -A -o PID,NAME")
                    ShizukuManager.isAuthorized.value -> ShizukuManager.exec("ps -A -o PID,NAME")
                    else -> ""
                }
                if (output.isNotBlank()) {
                    val lines = output.split("\n")
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() || trimmed.startsWith("PID")) continue
                        val parts = trimmed.split(Regex("""\s+"""), limit = 2)
                        if (parts.size >= 2) {
                            val pid = parts[0]
                            val pkg = parts[1]
                            pidToPackage[pid] = pkg
                            packageToPids.computeIfAbsent(pkg) { ConcurrentHashMap.newKeySet() }.add(pid)
                        }
                    }
                }
            } catch (_: Exception) {}
        }.start()
    }

    /**
     * 开始抓取实时日志
     */
    fun startCapture(context: Context) {
        if (isCapturing.value) return
        stopCapture()

        refreshProcessMap()

        val onLineReceived: (String) -> Unit = { rawChunk ->
            val lines = rawChunk.split("\n")
            val parsedBatch = mutableListOf<LogLine>()
            for (lineText in lines) {
                val line = lineText.trimEnd('\r', '\n')
                if (line.isBlank()) continue

                val match = logcatTimeRegex.find(line)
                val logItem = if (match != null) {
                    val (time, level, tag, pidStr, message) = match.destructured
                    val pid = pidStr.trim()
                    LogLine(
                        raw = line,
                        time = time,
                        level = level,
                        tag = tag.trim(),
                        pid = pid,
                        message = message
                    )
                } else {
                    LogLine(
                        raw = line,
                        message = line
                    )
                }

                // 核心白名单与黑名单互斥筛选逻辑
                if (isLogAllowed(logItem)) {
                    parsedBatch.add(logItem)
                }
            }

            if (parsedBatch.isNotEmpty()) {
                mainHandler.post {
                    logLines.addAll(parsedBatch)
                    if (logLines.size > 5000) {
                        val removeCount = logLines.size - 5000
                        repeat(removeCount) { logLines.removeAt(0) }
                    }
                }
            }
        }

        // 优先通过 ADB 连接抓取
        if (AdbManager.connected.value && AdbManager.connection?.isAuthenticated == true) {
            val handle = AdbManager.connection?.openLogcatStream("-v time", onLineReceived)
            if (handle != null) {
                activeStream = handle
                isCapturing.value = true
                return
            }
        }

        // 其次通过 Shizuku 抓取
        if (ShizukuManager.isAuthorized.value) {
            val handle = ShizukuManager.startLogcatProcess("-v time", onLineReceived)
            if (handle != null) {
                activeStream = handle
                isCapturing.value = true
                return
            }
        }

        // 若均未连接，尝试启动 Shizuku 授权或提示未连接
        if (!AdbManager.connected.value) {
            if (ShizukuManager.isBinderAlive.value && !ShizukuManager.isAuthorized.value) {
                ShizukuManager.requestPermission()
            }
        }
    }

    /**
     * 暂停/停止抓取实时日志
     */
    fun pauseCapture() {
        stopCapture()
    }

    private fun stopCapture() {
        try {
            activeStream?.close()
        } catch (_: Exception) {}
        activeStream = null
        isCapturing.value = false
    }

    /**
     * 判断单行日志是否符合当前过滤规则（白名单/黑名单互斥）
     */
    private fun isLogAllowed(item: LogLine): Boolean {
        val currentMode = filterMode.value
        if (currentMode == FilterMode.NONE && logSource.value == LogSource.FULL_DEVICE) {
            return true
        }

        val associatedPkg = if (item.pid.isNotBlank()) pidToPackage[item.pid] else null
        val fullText = item.raw

        when (currentMode) {
            FilterMode.WHITELIST -> {
                if (whitelist.isEmpty()) return true
                for (target in whitelist) {
                    if (associatedPkg != null && (associatedPkg == target || associatedPkg.startsWith("$target:"))) return true
                    if (fullText.contains(target, ignoreCase = true)) return true
                }
                return false
            }
            FilterMode.BLACKLIST -> {
                if (blacklist.isEmpty()) return true
                for (blocked in blacklist) {
                    if (associatedPkg != null && (associatedPkg == blocked || associatedPkg.startsWith("$blocked:"))) return false
                    if (fullText.contains(blocked, ignoreCase = true)) return false
                }
                return true
            }
            FilterMode.NONE -> {
                return true
            }
        }
    }

    /**
     * 读取设备上已安装应用列表（供用户在添加白名单/黑名单时选择自动填入）
     */
    fun fetchInstalledApps(context: Context, onResult: (List<InstalledAppItem>) -> Unit) {
        Thread {
            val resultList = mutableListOf<InstalledAppItem>()
            val pm = context.packageManager

            // 1. 若 ADB 已连接，优先从被控设备提取第三方应用
            if (AdbManager.connected.value) {
                try {
                    val out = AdbManager.execCapture("pm list packages -3")
                    if (out.isNotBlank()) {
                        val lines = out.split("\n")
                        for (l in lines) {
                            val clean = l.trim().removePrefix("package:").trim()
                            if (clean.isNotBlank()) {
                                resultList.add(InstalledAppItem(packageName = clean, label = clean))
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            // 2. 若未从 ADB 拿到或处于 Shizuku 本机环境，从 PackageManager 或 Shizuku 获取
            if (resultList.isEmpty()) {
                try {
                    val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    for (app in apps) {
                        // 过滤非系统应用或全部应用
                        val isNonSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0
                        val label = pm.getApplicationLabel(app).toString()
                        val pkg = app.packageName
                        if (isNonSystem) {
                            resultList.add(0, InstalledAppItem(packageName = pkg, label = label))
                        } else {
                            resultList.add(InstalledAppItem(packageName = pkg, label = label))
                        }
                    }
                } catch (_: Exception) {}
            }

            mainHandler.post {
                onResult(resultList.distinctBy { it.packageName })
            }
        }.start()
    }
}
