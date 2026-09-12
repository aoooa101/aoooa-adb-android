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
import java.util.concurrent.atomic.AtomicInteger

enum class LogSource {
    FULL_DEVICE,    // 完整设备日志
    TARGET_APPS     // 仅指定应用日志
}

enum class FilterMode {
    NONE,           // 不按应用过滤
    WHITELIST,      // 白名单模式（只抓取指定应用）
    BLACKLIST       // 黑名单模式（排除指定应用）
}

/** 日志缓冲选择（对应 logcat -b） */
enum class LogBufferMode(val prefValue: Int, val arg: String?, val labelZh: String) {
    DEFAULT(0, null, "默认 (main+system+crash)"),
    ALL(1, "all", "全部缓冲 (-b all)"),
    MAIN(2, "main", "仅 main"),
    SYSTEM(3, "system", "仅 system"),
    CRASH(4, "crash", "仅 crash"),
    EVENTS(5, "events", "仅 events");

    companion object {
        fun fromPref(v: Int): LogBufferMode = entries.firstOrNull { it.prefValue == v } ?: DEFAULT
    }
}

/** 日志语义类型（用于快筛与高亮） */
enum class LogKind {
    NORMAL,
    ERROR,
    CRASH,       // FATAL EXCEPTION / Process death / native dump 头
    STACK,       // 堆栈续行（at / Caused by / Native frames）
    ANR,
    SYSTEM
}

/** 显示侧类型快筛 */
enum class LogTypeFilter(val prefValue: Int, val labelZh: String) {
    ALL(0, "全部"),
    ERRORS(1, "仅错误"),
    CRASH_STACK(2, "崩溃/堆栈"),
    ANR(3, "ANR"),
    SYSTEM(4, "系统");

    companion object {
        fun fromPref(v: Int): LogTypeFilter = entries.firstOrNull { it.prefValue == v } ?: ALL
    }
}

data class LogLine(
    val id: String = UUID.randomUUID().toString(),
    val raw: String,
    val time: String = "",
    val pid: String = "",
    val tid: String = "",
    val level: String = "V",
    val tag: String = "",
    val message: String = "",
    val kind: LogKind = LogKind.NORMAL
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
    val bufferMode = mutableStateOf(LogBufferMode.DEFAULT)
    val includeHistory = mutableStateOf(true)
    val historyLines = mutableStateOf(500)
    val minLevel = mutableStateOf(0) // 0=V .. 5=F（抓取侧入库最低门槛）
    val maxBufferLines = mutableStateOf(8000)

    /** 显示侧过滤器：级别多选掩码 bit0=V..bit5=F */
    val enabledLevelsMask = mutableStateOf(0b111111)
    val typeFilter = mutableStateOf(LogTypeFilter.ALL)
    val tagInclude = mutableStateOf("")
    val tagExclude = mutableStateOf("")
    val keywordInclude = mutableStateOf("")
    val keywordExclude = mutableStateOf("")
    val useRegex = mutableStateOf(false)
    val highlightSpecial = mutableStateOf(true)

    /** 白名单与黑名单（互斥） */
    val whitelist = mutableStateListOf<String>()
    val blacklist = mutableStateListOf<String>()

    /** 搜索框关键词（显示侧快速搜索） */
    val searchQuery = mutableStateOf("")

    /** 界面日志缓冲列表（上限由 maxBufferLines 控制） */
    val logLines = mutableStateListOf<LogLine>()

    /** 统计：接收行数 / 过滤丢弃 / 环缓冲淘汰 */
    val receivedCount = mutableStateOf(0L)
    val filteredDropCount = mutableStateOf(0L)
    val ringDropCount = mutableStateOf(0L)

    /** 动态缓存：PID -> 主包名 / 主包名 -> PID集合 */
    private val pidToPackage = ConcurrentHashMap<String, String>()
    private val packageToPids = ConcurrentHashMap<String, MutableSet<String>>()

    /** 抓取会话 ID 计数器（消除多线程停止与重开竞态） */
    private val sessionCounter = AtomicInteger(0)

    /** ADB 流跨包半截行残留缓冲（会话内） */
    private val lineCarry = StringBuilder()
    private val lineCarryLock = Any()

    @Volatile
    private var activeStream: AutoCloseable? = null

    @Volatile
    private var pidRefreshRunnable: Runnable? = null

    private val PID_REFRESH_INTERVAL_MS = 8000L

    // logcat -v time：09-06 12:34:56.789 D/Tag( 1234): message
    private val logcatTimeRegex = Regex(
        """^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+([VDIWEF])/([^(:]+)(?:\(\s*(\d+)\))?:\s*(.*)$"""
    )

    // logcat -v threadtime：09-06 12:34:56.789  1234  1250 I Tag: message
    private val logcatThreadtimeRegex = Regex(
        """^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+([^:]+):\s*(.*)$"""
    )

    private val levelRank = mapOf(
        "V" to 0, "D" to 1, "I" to 2, "W" to 3, "E" to 4, "F" to 5, "A" to 5
    )

    // 应用堆栈 / 崩溃 / ANR 等特征（入库分类，参考 AndroidRuntime FATAL + ANR + native dump）
    private val reFatalException = Regex("""FATAL EXCEPTION""", RegexOption.IGNORE_CASE)
    private val reProcessLine = Regex("""^\s*Process:\s*[\w.]+,\s*PID:\s*\d+""", RegexOption.IGNORE_CASE)
    private val reJavaStackAt = Regex("""^\s*at\s+[\w.$/<\[\]\-]+\([\w.:$]+\)""")
    private val reCausedBy = Regex("""^\s*Caused by:""", RegexOption.IGNORE_CASE)
    private val reSuppressed = Regex("""^\s*Suppressed:""", RegexOption.IGNORE_CASE)
    private val reMore = Regex("""^\s*\.\.\.\s*\d+\s+more""")
    private val reAnr = Regex("""\bANR in\b|Input dispatching timed out|Application Not Responding""", RegexOption.IGNORE_CASE)
    private val reNativeCrash = Regex("""Fatal signal|SIGSEGV|SIGABRT|SIGBUS|SIGILL|>>> .+ <<<|^\s*backtrace:|#\d+\s+pc\s+""", RegexOption.IGNORE_CASE)
    private val reSystemTags = setOf(
        "ActivityManager", "WindowManager", "PackageManager", "ActivityTaskManager",
        "SystemServer", "zygote", "Zygote", "art", "AndroidRuntime", "DEBUG", "libc",
        "crash_dump", "tombstoned", "lowmemorykiller"
    )

    /**
     * 初始化：从持久化 Prefs 恢复日志来源、过滤模式与黑白名单
     */
    fun init() {
        try {
            logSource.value = if (com.aoooa.webadb.Prefs.logSource == 1) LogSource.TARGET_APPS else LogSource.FULL_DEVICE
            filterMode.value = when (com.aoooa.webadb.Prefs.logFilterMode) {
                1 -> FilterMode.WHITELIST
                2 -> FilterMode.BLACKLIST
                else -> FilterMode.NONE
            }
            bufferMode.value = LogBufferMode.fromPref(com.aoooa.webadb.Prefs.logBufferMode)
            includeHistory.value = com.aoooa.webadb.Prefs.logIncludeHistory
            historyLines.value = com.aoooa.webadb.Prefs.logHistoryLines
            minLevel.value = com.aoooa.webadb.Prefs.logMinLevel
            maxBufferLines.value = com.aoooa.webadb.Prefs.logMaxBufferLines
            enabledLevelsMask.value = com.aoooa.webadb.Prefs.logEnabledLevelsMask
            typeFilter.value = LogTypeFilter.fromPref(com.aoooa.webadb.Prefs.logTypeFilter)
            tagInclude.value = com.aoooa.webadb.Prefs.logTagInclude
            tagExclude.value = com.aoooa.webadb.Prefs.logTagExclude
            keywordInclude.value = com.aoooa.webadb.Prefs.logKeywordInclude
            keywordExclude.value = com.aoooa.webadb.Prefs.logKeywordExclude
            useRegex.value = com.aoooa.webadb.Prefs.logUseRegex
            highlightSpecial.value = com.aoooa.webadb.Prefs.logHighlightSpecial
            whitelist.clear()
            whitelist.addAll(com.aoooa.webadb.Prefs.loadLogWhitelist())
            blacklist.clear()
            blacklist.addAll(com.aoooa.webadb.Prefs.loadLogBlacklist())
        } catch (e: Exception) {
            AdbManager.debugLog("[LogManager] 恢复日志持久化配置异常: ${e.message}")
        }
    }

    /**
     * 保存当前日志来源、过滤模式与黑白名单到持久化 Prefs
     */
    fun saveSettings() {
        try {
            com.aoooa.webadb.Prefs.logSource = if (logSource.value == LogSource.TARGET_APPS) 1 else 0
            com.aoooa.webadb.Prefs.logFilterMode = when (filterMode.value) {
                FilterMode.WHITELIST -> 1
                FilterMode.BLACKLIST -> 2
                else -> 0
            }
            com.aoooa.webadb.Prefs.logBufferMode = bufferMode.value.prefValue
            com.aoooa.webadb.Prefs.logIncludeHistory = includeHistory.value
            com.aoooa.webadb.Prefs.logHistoryLines = historyLines.value
            com.aoooa.webadb.Prefs.logMinLevel = minLevel.value
            com.aoooa.webadb.Prefs.logMaxBufferLines = maxBufferLines.value
            com.aoooa.webadb.Prefs.logEnabledLevelsMask = enabledLevelsMask.value
            com.aoooa.webadb.Prefs.logTypeFilter = typeFilter.value.prefValue
            com.aoooa.webadb.Prefs.logTagInclude = tagInclude.value
            com.aoooa.webadb.Prefs.logTagExclude = tagExclude.value
            com.aoooa.webadb.Prefs.logKeywordInclude = keywordInclude.value
            com.aoooa.webadb.Prefs.logKeywordExclude = keywordExclude.value
            com.aoooa.webadb.Prefs.logUseRegex = useRegex.value
            com.aoooa.webadb.Prefs.logHighlightSpecial = highlightSpecial.value
            com.aoooa.webadb.Prefs.saveLogWhitelist(whitelist.toList())
            com.aoooa.webadb.Prefs.saveLogBlacklist(blacklist.toList())
        } catch (e: Exception) {
            AdbManager.debugLog("[LogManager] 保存日志持久化配置异常: ${e.message}")
        }
    }

    fun isLevelEnabled(level: String): Boolean {
        val rank = levelRank[level.uppercase()] ?: return true
        return (enabledLevelsMask.value and (1 shl rank)) != 0
    }

    fun toggleLevel(rank: Int) {
        if (rank !in 0..5) return
        val bit = 1 shl rank
        val next = enabledLevelsMask.value xor bit
        // 至少保留一个级别
        enabledLevelsMask.value = if (next == 0) bit else next
        saveSettings()
    }

    fun applyQuickPreset(preset: String) {
        when (preset) {
            "all" -> {
                enabledLevelsMask.value = 0b111111
                typeFilter.value = LogTypeFilter.ALL
                tagInclude.value = ""
                tagExclude.value = ""
                keywordInclude.value = ""
                keywordExclude.value = ""
                useRegex.value = false
                searchQuery.value = ""
            }
            "errors" -> {
                enabledLevelsMask.value = 0b111000 // W/E/F
                typeFilter.value = LogTypeFilter.ERRORS
                minLevel.value = 3
            }
            "crash" -> {
                enabledLevelsMask.value = 0b110000 // E/F
                typeFilter.value = LogTypeFilter.CRASH_STACK
                minLevel.value = 4
                tagInclude.value = "AndroidRuntime,DEBUG,libc,crash_dump"
                keywordInclude.value = "FATAL EXCEPTION|Caused by:|^\\s*at |Process:"
                useRegex.value = true
            }
            "anr" -> {
                enabledLevelsMask.value = 0b111000
                typeFilter.value = LogTypeFilter.ANR
                keywordInclude.value = "ANR in|Input dispatching timed out|Application Not Responding"
                useRegex.value = true
            }
            "system" -> {
                typeFilter.value = LogTypeFilter.SYSTEM
                tagInclude.value = "ActivityManager,WindowManager,PackageManager,ActivityTaskManager,SystemServer,zygote,Zygote"
                useRegex.value = false
            }
        }
        saveSettings()
    }

    /**
     * 切换过滤模式（保证白名单与黑名单严格互斥并持久化）
     */
    fun setFilterMode(mode: FilterMode) {
        filterMode.value = mode
        saveSettings()
    }

    fun addWhitelistPackage(pkg: String) {
        val clean = pkg.trim()
        if (clean.isNotBlank() && !whitelist.contains(clean)) {
            whitelist.add(clean)
            saveSettings()
        }
    }

    fun removeWhitelistPackage(pkg: String) {
        if (whitelist.remove(pkg)) {
            saveSettings()
        }
    }

    fun addBlacklistPackage(pkg: String) {
        val clean = pkg.trim()
        if (clean.isNotBlank() && !blacklist.contains(clean)) {
            blacklist.add(clean)
            saveSettings()
        }
    }

    fun removeBlacklistPackage(pkg: String) {
        if (blacklist.remove(pkg)) {
            saveSettings()
        }
    }

    fun clearLogs() {
        mainHandler.post {
            logLines.clear()
            ringDropCount.value = 0L
        }
    }

    fun resetCounters() {
        receivedCount.value = 0L
        filteredDropCount.value = 0L
        ringDropCount.value = 0L
    }

    /**
     * 构建 logcat 参数：优先 threadtime + 可选缓冲 + 可选历史
     */
    fun buildLogcatArgs(): String {
        val parts = mutableListOf<String>()
        bufferMode.value.arg?.let {
            parts.add("-b")
            parts.add(it)
        }
        parts.add("-v")
        parts.add("threadtime")
        if (includeHistory.value) {
            val n = historyLines.value.coerceIn(50, 5000)
            parts.add("-T")
            parts.add(n.toString())
        }
        // 最小级别交给本地过滤，便于与包名/搜索规则联动
        return parts.joinToString(" ")
    }

    private fun startPidRefreshLoop(session: Int) {
        stopPidRefreshLoop()
        val runnable = object : Runnable {
            override fun run() {
                if (sessionCounter.get() != session || !isCapturing.value) return
                refreshProcessMap()
                mainHandler.postDelayed(this, PID_REFRESH_INTERVAL_MS)
            }
        }
        pidRefreshRunnable = runnable
        mainHandler.postDelayed(runnable, PID_REFRESH_INTERVAL_MS)
    }

    private fun stopPidRefreshLoop() {
        pidRefreshRunnable?.let { mainHandler.removeCallbacks(it) }
        pidRefreshRunnable = null
    }

    private fun classifyLogKind(level: String, tag: String, message: String, raw: String): LogKind {
        val msg = if (message.isNotBlank()) message else raw
        val combined = "$tag $msg"
        val lv = level.uppercase()
        val isAndroidRuntimeCrash =
            tag.equals("AndroidRuntime", true) &&
                lv in setOf("E", "F") &&
                (combined.contains("Exception", ignoreCase = true) ||
                    combined.contains("Error", ignoreCase = true) ||
                    reFatalException.containsMatchIn(combined))

        return when {
            reFatalException.containsMatchIn(combined) ||
                reProcessLine.containsMatchIn(msg) ||
                isAndroidRuntimeCrash -> LogKind.CRASH
            reAnr.containsMatchIn(combined) -> LogKind.ANR
            reNativeCrash.containsMatchIn(combined) &&
                (tag.equals("DEBUG", true) || tag.equals("libc", true) ||
                    tag.contains("crash", true) || combined.contains("backtrace", true) ||
                    combined.contains("Fatal signal", true) || combined.contains(">>>")) -> LogKind.CRASH
            reJavaStackAt.containsMatchIn(msg) ||
                reCausedBy.containsMatchIn(msg) ||
                reSuppressed.containsMatchIn(msg) ||
                reMore.containsMatchIn(msg) ||
                msg.trimStart().startsWith("at ") -> LogKind.STACK
            lv in setOf("E", "F") -> {
                if (reSystemTags.any { tag.equals(it, true) }) LogKind.SYSTEM else LogKind.ERROR
            }
            reSystemTags.any { tag.equals(it, true) } -> LogKind.SYSTEM
            else -> LogKind.NORMAL
        }
    }

    private fun parseLogLine(line: String): LogLine {
        val tt = logcatThreadtimeRegex.find(line)
        if (tt != null) {
            val (time, pid, tid, level, tag, message) = tt.destructured
            val cleanTag = tag.trim()
            val cleanMsg = message
            return LogLine(
                raw = line,
                time = time,
                pid = pid.trim(),
                tid = tid.trim(),
                level = level,
                tag = cleanTag,
                message = cleanMsg,
                kind = classifyLogKind(level, cleanTag, cleanMsg, line)
            )
        }
        val tm = logcatTimeRegex.find(line)
        if (tm != null) {
            val (time, level, tag, pidStr, message) = tm.destructured
            val cleanTag = tag.trim()
            val cleanMsg = message
            return LogLine(
                raw = line,
                time = time,
                level = level,
                tag = cleanTag,
                pid = pidStr.trim(),
                message = cleanMsg,
                kind = classifyLogKind(level, cleanTag, cleanMsg, line)
            )
        }
        val kind = classifyLogKind("", "", line, line)
        return LogLine(raw = line, message = line, kind = kind)
    }

    private fun matchTextRule(text: String, rule: String, asRegex: Boolean): Boolean {
        val rawRule = rule.trim()
        if (rawRule.isEmpty()) return true
        val parts = rawRule.split(',', '|').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return true
        return parts.any { part ->
            if (asRegex) {
                try {
                    Regex(part, setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)).containsMatchIn(text)
                } catch (_: Exception) {
                    text.contains(part, ignoreCase = true)
                }
            } else {
                text.contains(part, ignoreCase = true)
            }
        }
    }

    private fun matchExcludeRule(text: String, rule: String, asRegex: Boolean): Boolean {
        val rawRule = rule.trim()
        if (rawRule.isEmpty()) return false
        return matchTextRule(text, rawRule, asRegex)
    }

    /**
     * 显示侧过滤（不影响底层环缓冲入库）：类型快筛 + 级别多选 + Tag/关键词/正则 + 搜索框
     */
    fun matchesDisplayFilters(item: LogLine): Boolean {
        // 1) 级别多选
        if (item.level.isNotBlank() && item.time.isNotBlank() && !isLevelEnabled(item.level)) {
            return false
        }

        // 2) 类型快筛
        when (typeFilter.value) {
            LogTypeFilter.ALL -> Unit
            LogTypeFilter.ERRORS -> {
                if (item.kind != LogKind.ERROR && item.kind != LogKind.CRASH &&
                    item.kind != LogKind.STACK && item.kind != LogKind.ANR &&
                    item.level.uppercase() !in setOf("E", "F", "W")
                ) return false
            }
            LogTypeFilter.CRASH_STACK -> {
                if (item.kind != LogKind.CRASH && item.kind != LogKind.STACK) return false
            }
            LogTypeFilter.ANR -> {
                if (item.kind != LogKind.ANR) return false
            }
            LogTypeFilter.SYSTEM -> {
                if (item.kind != LogKind.SYSTEM && !reSystemTags.any { item.tag.equals(it, true) }) return false
            }
        }

        val asRegex = useRegex.value
        val tagText = item.tag
        val full = item.raw

        // 3) Tag 包含 / 排除
        if (tagInclude.value.isNotBlank() && !matchTextRule(tagText.ifBlank { full }, tagInclude.value, asRegex)) {
            return false
        }
        if (matchExcludeRule(tagText.ifBlank { full }, tagExclude.value, asRegex)) {
            return false
        }

        // 4) 关键词包含 / 排除
        if (keywordInclude.value.isNotBlank() && !matchTextRule(full, keywordInclude.value, asRegex)) {
            return false
        }
        if (matchExcludeRule(full, keywordExclude.value, asRegex)) {
            return false
        }

        // 5) 搜索框
        val q = searchQuery.value.trim()
        if (q.isNotEmpty()) {
            val ok = if (asRegex) {
                try {
                    Regex(q, RegexOption.IGNORE_CASE).containsMatchIn(full)
                } catch (_: Exception) {
                    full.contains(q, ignoreCase = true)
                }
            } else {
                full.contains(q, ignoreCase = true)
            }
            if (!ok) return false
        }
        return true
    }

    /**
     * 处理一整段原始文本：跨包半截行拼接 + 解析 + 过滤入库
     */
    private fun ingestRawChunk(rawChunk: String, currentSession: Int) {
        if (sessionCounter.get() != currentSession || !isCapturing.value) return

        val completeLines = mutableListOf<String>()
        synchronized(lineCarryLock) {
            lineCarry.append(rawChunk)
            var text = lineCarry.toString().replace("\r\n", "\n").replace('\r', '\n')
            var start = 0
            var idx = text.indexOf('\n', start)
            while (idx >= 0) {
                val one = text.substring(start, idx)
                if (one.isNotEmpty()) completeLines.add(one)
                start = idx + 1
                idx = text.indexOf('\n', start)
            }
            lineCarry.setLength(0)
            if (start < text.length) {
                lineCarry.append(text.substring(start))
            }
        }

        if (completeLines.isEmpty()) return

        val parsedBatch = mutableListOf<LogLine>()
        var localReceived = 0L
        var localFiltered = 0L

        for (line in completeLines) {
            val logItem = parseLogLine(line)
            localReceived++
            if (isLogAllowed(logItem)) {
                parsedBatch.add(logItem)
            } else {
                localFiltered++
            }
        }

        mainHandler.post {
            if (sessionCounter.get() != currentSession || !isCapturing.value) return@post
            if (localReceived > 0) {
                receivedCount.value = receivedCount.value + localReceived
            }
            if (localFiltered > 0) {
                filteredDropCount.value = filteredDropCount.value + localFiltered
            }
            if (parsedBatch.isNotEmpty()) {
                logLines.addAll(parsedBatch)
                val limit = maxBufferLines.value.coerceIn(1000, 30000)
                if (logLines.size > limit) {
                    val removeCount = logLines.size - limit
                    repeat(removeCount) { logLines.removeAt(0) }
                    ringDropCount.value = ringDropCount.value + removeCount
                }
            }
        }
    }

    /**
     * 刷新目标设备的 PID 与包名映射表
     */
    fun refreshProcessMap() {
        Thread {
            try {
                var output = ""
                if (AdbManager.connected.value) {
                    output = AdbManager.execCapture("ps -A -o PID,NAME")
                    if (output.isBlank() || !output.contains("\n")) {
                        output = AdbManager.execCapture("ps -A")
                    }
                } else if (ShizukuManager.isAuthorized.value) {
                    output = ShizukuManager.exec("ps -A -o PID,NAME")
                    if (output.isBlank() || !output.contains("\n")) {
                        output = ShizukuManager.exec("ps -A")
                    }
                }

                if (output.isNotBlank()) {
                    val tempPidToPkg = HashMap<String, String>()
                    val tempPkgToPids = HashMap<String, MutableSet<String>>()
                    val lines = output.split("\n")

                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() || trimmed.startsWith("USER") || trimmed.startsWith("PID")) continue
                        val parts = trimmed.split(Regex("""\s+"""))
                        if (parts.size >= 2) {
                            // 兼容 ps -o PID,NAME (首列为 PID, 末列为 NAME) 或标准 ps -A (第二列为 PID, 末列为 NAME)
                            val pidCandidate = parts.firstOrNull { it.all { c -> c.isDigit() } }
                            val rawName = parts.last()
                            if (pidCandidate != null && rawName.isNotBlank()) {
                                // 归一化：将 com.pkg:subservice 映射回主包名 com.pkg
                                val mainPkg = if (rawName.contains(":")) rawName.substringBefore(":") else rawName
                                tempPidToPkg[pidCandidate] = mainPkg
                                tempPkgToPids.computeIfAbsent(mainPkg) { HashSet() }.add(pidCandidate)
                            }
                        }
                    }

                    if (tempPidToPkg.isNotEmpty()) {
                        pidToPackage.clear()
                        pidToPackage.putAll(tempPidToPkg)
                        packageToPids.clear()
                        for ((k, v) in tempPkgToPids) {
                            packageToPids[k] = ConcurrentHashMap.newKeySet<String>().apply { addAll(v) }
                        }
                        AdbManager.debugLog("[LogManager] 进程映射表已原子刷新，共缓存 ${tempPidToPkg.size} 个活动 PID")
                    }
                }
            } catch (e: Exception) {
                AdbManager.debugLog("[LogManager] 刷新进程表异常: ${e.message}")
            }
        }.start()
    }

    /**
     * 开始抓取实时日志（支持会话 ID 竞态保护与未就绪明确提示）
     */
    fun startCapture(context: Context) {
        if (isCapturing.value) return
        stopCapture()

        val currentSession = sessionCounter.incrementAndGet()
        synchronized(lineCarryLock) { lineCarry.setLength(0) }
        resetCounters()
        saveSettings()
        refreshProcessMap()

        val onLineReceived: (String) -> Unit = { rawChunk ->
            if (sessionCounter.get() == currentSession && isCapturing.value) {
                ingestRawChunk(rawChunk, currentSession)
            }
        }

        val args = buildLogcatArgs()
        AdbManager.debugLog("[LogManager] 准备启动 logcat: $args")

        // 1. 优先通过 ADB 连接抓取
        if (AdbManager.connected.value && AdbManager.connection?.isAuthenticated == true) {
            val handle = AdbManager.connection?.openLogcatStream(args, onLineReceived)
            if (handle != null) {
                activeStream = handle
                isCapturing.value = true
                startPidRefreshLoop(currentSession)
                AdbManager.debugLog("[LogManager] ADB 流式日志抓取已启动 (Session #$currentSession, args=$args)")
                return
            }
        }

        // 2. 其次通过 Shizuku 抓取
        if (ShizukuManager.isAuthorized.value) {
            val handle = ShizukuManager.startLogcatProcess(args, onLineReceived)
            if (handle != null) {
                activeStream = handle
                isCapturing.value = true
                startPidRefreshLoop(currentSession)
                AdbManager.debugLog("[LogManager] Shizuku 流式日志抓取已启动 (Session #$currentSession, args=$args)")
                return
            }
        }

        // 3. 若均未就绪，输出明确提示信息引导用户
        mainHandler.post {
            logLines.add(
                LogLine(
                    raw = "[提示] 未连接外部 ADB 调试设备，且未获得本机 Shizuku 授权。",
                    level = "W",
                    tag = "aoooa-adb",
                    message = "请在首页连接设备，或在右上角齿轮设置中点击「连接 Shizuku」获取授权后查看日志。"
                )
            )
        }

        if (ShizukuManager.isBinderAlive.value && !ShizukuManager.isAuthorized.value) {
            ShizukuManager.requestPermission()
        }
    }

    /**
     * 暂停/停止抓取实时日志
     */
    fun pauseCapture() {
        stopCapture()
    }

    private fun stopCapture() {
        sessionCounter.incrementAndGet() // 使之前的读取会话失效
        stopPidRefreshLoop()
        try {
            activeStream?.close()
        } catch (e: Exception) {
            AdbManager.debugLog("[LogManager] 关闭日志流句柄异常: ${e.message}")
        }
        activeStream = null
        isCapturing.value = false
        // 停止时冲刷半截行残留
        synchronized(lineCarryLock) {
            val leftover = lineCarry.toString().trim()
            lineCarry.setLength(0)
            if (leftover.isNotEmpty()) {
                mainHandler.post {
                    val item = parseLogLine(leftover)
                    if (isLogAllowed(item)) {
                        logLines.add(item)
                    }
                }
            }
        }
    }

    /**
     * 判断单行日志是否符合当前过滤规则（级别 + 来源 + 白/黑名单）
     */
    private fun isLogAllowed(item: LogLine): Boolean {
        // 1) 最小级别：已解析出 time+level 的标准行才做级别比较；banner / 非结构化行放行
        if (item.level.isNotBlank() && item.time.isNotBlank()) {
            val rank = levelRank[item.level.uppercase()] ?: 0
            if (rank < minLevel.value) return false
        }

        val currentMode = filterMode.value
        val source = logSource.value
        val associatedPkg = if (item.pid.isNotBlank()) pidToPackage[item.pid] else null
        val fullText = item.raw

        fun matchTargetList(list: List<String>): Boolean {
            if (list.isEmpty()) return false
            for (target in list) {
                if (associatedPkg != null && (associatedPkg == target || associatedPkg.startsWith("$target:"))) return true
                if (fullText.contains(target, ignoreCase = true)) return true
                if (item.tag.contains(target, ignoreCase = true)) return true
            }
            return false
        }

        when (currentMode) {
            FilterMode.WHITELIST -> {
                if (whitelist.isEmpty()) {
                    // 白名单空：指定应用模式下封锁，避免“空壳放行全部”
                    return source != LogSource.TARGET_APPS
                }
                return matchTargetList(whitelist)
            }
            FilterMode.BLACKLIST -> {
                if (blacklist.isEmpty()) return true
                return !matchTargetList(blacklist)
            }
            FilterMode.NONE -> {
                if (source == LogSource.FULL_DEVICE) return true
                // TARGET_APPS + NONE：白名单非空则按白名单；空白名单时放行（UI 会提示去配置/切换白名单）
                return if (whitelist.isEmpty()) true else matchTargetList(whitelist)
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
                } catch (e: Exception) {
                    AdbManager.debugLog("[LogManager] 从 ADB 读取应用列表异常: ${e.message}")
                }
            }

            // 2. 若未从 ADB 拿到或处于 Shizuku 本机环境，从 PackageManager 或 Shizuku 获取
            if (resultList.isEmpty()) {
                try {
                    val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    for (app in apps) {
                        val isNonSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0
                        val label = pm.getApplicationLabel(app).toString()
                        val pkg = app.packageName
                        if (isNonSystem) {
                            resultList.add(0, InstalledAppItem(packageName = pkg, label = label))
                        } else {
                            resultList.add(InstalledAppItem(packageName = pkg, label = label))
                        }
                    }
                } catch (e: Exception) {
                    AdbManager.debugLog("[LogManager] 从本机 PackageManager 读取应用列表异常: ${e.message}")
                }
            }

            mainHandler.post {
                onResult(resultList.distinctBy { it.packageName })
            }
        }.start()
    }
}
