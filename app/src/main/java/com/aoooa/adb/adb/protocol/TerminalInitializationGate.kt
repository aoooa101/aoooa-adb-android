package com.aoooa.adb.adb.protocol

/** 在首个有效目录标记前暂存初始化回显；超时或超限时保留输出并降级放行。 */
internal class TerminalInitializationGate(
    private val now: () -> Long = System::nanoTime,
    private val diagnose: (String) -> Unit = {}
) {
    private val pending = StringBuilder()
    private var active = true
    private val deadline = now() + 5_000_000_000L
    private val marker = Regex("\u001b]aoooa-cwd;([^\u0007\u001b]*)(?:\u0007|\u001b\\\\)")

    @Synchronized
    fun accept(text: String): String {
        if (!active) return text
        val diagnostic = text.take((65536 - pending.length).coerceAtLeast(0))
        if (diagnostic.isNotEmpty()) diagnose("[PTY init output] " + diagnostic
            .replace("\\", "\\\\").replace("\u001b", "\\e")
            .replace("\r", "\\r").replace("\n", "\\n").replace("\b", "\\b")
            .replace("\u0007", "\\a"))
        pending.append(text)
        val match = marker.findAll(pending).firstOrNull {
            TerminalDirectoryProtocol.directory("aoooa-cwd;" + it.groupValues[1]) != null
        }
        if (match != null) {
            diagnose("[PTY init] directory marker received")
            active = false
            return pending.substring(match.range.first).also { pending.setLength(0) }
        }
        if (pending.length >= 65536 || now() >= deadline) {
            diagnose("[PTY init] fallback: " + if (pending.length >= 65536) "buffer limit" else "timeout")
            active = false
            return pending.toString().also { pending.setLength(0) }
        }
        return ""
    }
}
