package com.aoooa.adb.log

/**
 * 日志行解析与分类。
 *
 * 支持 logcat `-v time`（含 PID 分隔）与 `-v threadtime` 两种行格式，
 * 并按崩溃 / ANR / 堆栈 / 系统日志等特征归类，供界面着色与类型快筛使用。
 */
enum class LogKind {
    NORMAL,
    ERROR,
    CRASH,       // FATAL EXCEPTION / Process death / native dump 头
    STACK,       // 堆栈续行（at / Caused by / Native frames）
    ANR,
    SYSTEM
}

object LogParser {

    private val logcatTimeRegex = Regex(
        """^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+([VDIWEF])/([^(:]+)(?:\(\s*(\d+)\))?:\s*(.*)$"""
    )

    // logcat -v threadtime：09-06 12:34:56.789  1234  1250 I Tag: message
    private val logcatThreadtimeRegex = Regex(
        """^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+([^:]+):\s*(.*)$"""
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

    /** 系统组件 Tag，用于把系统日志与普通错误区分开。 */
    val reSystemTags = setOf(
        "ActivityManager", "WindowManager", "PackageManager", "ActivityTaskManager",
        "SystemServer", "zygote", "Zygote", "art", "AndroidRuntime", "DEBUG", "libc",
        "crash_dump", "tombstoned", "lowmemorykiller"
    )

    /**
     * 按内容特征判定日志类型。
     *
     * @param level 日志级别（V/D/I/W/E/F）
     * @param tag 日志 Tag
     * @param message 日志正文；为空时回退到 [raw]
     * @param raw 原始整行
     */
    fun classify(level: String, tag: String, message: String, raw: String): LogKind {
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

    /** 解析单行日志；无法匹配已知格式时整行作为正文。 */
    fun parse(line: String): LogLine {
        val tt = logcatThreadtimeRegex.find(line)
        if (tt != null) {
            val (time, pid, tid, level, tag, message) = tt.destructured
            val cleanTag = tag.trim()
            return LogLine(
                raw = line,
                time = time,
                pid = pid.trim(),
                tid = tid.trim(),
                level = level,
                tag = cleanTag,
                message = message,
                kind = classify(level, cleanTag, message, line)
            )
        }
        val tm = logcatTimeRegex.find(line)
        if (tm != null) {
            val (time, level, tag, pidStr, message) = tm.destructured
            val cleanTag = tag.trim()
            return LogLine(
                raw = line,
                time = time,
                level = level,
                tag = cleanTag,
                pid = pidStr.trim(),
                message = message,
                kind = classify(level, cleanTag, message, line)
            )
        }
        val kind = classify("", "", line, line)
        return LogLine(raw = line, message = line, kind = kind)
    }
}
