package com.aoooa.adb.adb.protocol

/**
 * 终端流式字符处理器：
 * 1. 支持跨 TCP/USB 数据包的不完整 ANSI 转义序列自动续接；
 * 2. 保留 ANSI SGR 颜色代码 (\u001B[...m)，过滤光标移动与控制码；
 * 3. 处理 \r\n 换行、\r 行首重绘与 \b / 0x7F 退格；
 * 4. 处理控制字符与乱码过滤。
 */
internal class TerminalStreamProcessor(private val onDirectory: (String) -> Unit = {}) {
    private val maxEscapeLength = 8192
    private var discard: Char? = null
    private var oscEsc = false
    private val buffer = TerminalLineBuffer()
    private var pendingEscape = "" // 跨数据块暂存的未闭合转义序列

    @Synchronized
    fun process(raw: String): Pair<List<String>, String> {
        val completedLines = mutableListOf<String>()
        val input = if (pendingEscape.isNotEmpty()) {
            val combined = pendingEscape + raw
            pendingEscape = ""
            combined
        } else {
            raw
        }

        var i = 0
        val len = input.length

        while (i < len) {
            val c = input[i]
            if (discard != null) {
                if (discard == '[' && c !in '\u0020'..'\u003F') {
                    discard = null
                    if (c !in '@'..'~') continue
                } else if (discard == ']') {
                    if (c == '\u0007' || (oscEsc && c == '\\')) discard = null
                    oscEsc = c == '\u001B'
                }
                i++
                continue
            }
            when {
                // 处理 ANSI 转义控制码 (\u001B 开头)
                c == '\u001B' -> {
                    val start = i
                    i++
                    if (i >= len) {
                        // 序列在 \u001B 处被切断，暂存等待下一个数据块
                        pendingEscape = input.substring(start)
                        break
                    }

                    val next = input[i]
                    when (next) {
                        '[' -> {
                            // CSI 序列: \u001B[ ... <cmd>
                            i++
                            while (i < len && input[i] in '\u0020'..'\u003F') {
                                i++
                            }
                            if (i >= len) {
                                if (i - start <= maxEscapeLength) pendingEscape = input.substring(start) else discard = next
                                break
                            }
                            val cmd = input[i]
                            i++
                            if (i - start > maxEscapeLength) continue
                            val seq = input.substring(start, i)
                            val parameter = seq.substring(2, seq.length - 1).toIntOrNull()
                            when (cmd) {
                                'm' -> buffer.sgr(seq)
                                'K' -> buffer.erase(parameter ?: 0)
                                'C' -> buffer.move((parameter ?: 1).coerceAtLeast(1))
                                'D' -> buffer.move(-(parameter ?: 1).coerceAtLeast(1))
                                'G' -> buffer.column((parameter ?: 1).coerceAtLeast(1) - 1)
                            }
                        }
                        ']' -> {
                            // OSC 序列: \u001B] ... (\u0007 | \u001B\)
                            i++
                            var oscClosed = false
                            while (i < len) {
                                if (input[i] == '\u0007') {
                                    i++
                                    oscClosed = true
                                    break
                                } else if (input[i] == '\u001B' && i + 1 < len && input[i + 1] == '\\') {
                                    i += 2
                                    oscClosed = true
                                    break
                                }
                                i++
                            }
                            if (!oscClosed) {
                                if (i - start <= maxEscapeLength) pendingEscape = input.substring(start)
                                else { discard = next; oscEsc = input.last() == '\u001B' }
                                break
                            }
                            if (i - start > maxEscapeLength) continue
                            val terminatorSize = if (input[i - 1] == '\u0007') 1 else 2
                            val osc = input.substring(start + 2, i - terminatorSize)
                            TerminalDirectoryProtocol.directory(osc)?.let(onDirectory)
                        }
                        '(', ')', '*', '+' -> {
                            // 指定字符集序列 (如 \u001B(B)
                            i++
                            if (i < len) {
                                i++ // 吸收字符集标识
                            } else {
                                pendingEscape = input.substring(start)
                                break
                            }
                        }
                        else -> {
                            // 单字节转义 (如 \u001BM, \u001B7, \u001B8 等)，安全吸收
                            i++
                        }
                    }
                }
                // \r\n 换行
                c == '\r' && i + 1 < len && input[i + 1] == '\n' -> {
                    completedLines.add(buffer.toString())
                    buffer.clear()
                    i += 2
                }
                // 单独的 \n 换行
                c == '\n' -> {
                    completedLines.add(buffer.toString())
                    buffer.clear()
                    i++
                }
                // 单独的 \r 回车（行首光标重置）
                c == '\r' -> {
                    if (i + 1 >= len) {
                        pendingEscape = "\r"
                        break
                    }
                    buffer.carriageReturn()
                    i++
                }
                // \b 退格 (Backspace) 与 0x7F (DEL)
                c == '\b' || c.code == 0x7F -> {
                    buffer.backspace()
                    i++
                }
                // 忽略其他不可打印控制字节（保留制表符 \t）
                c.code < 32 && c != '\t' -> {
                    i++
                }
                else -> {
                    if (Character.isHighSurrogate(c) && i + 1 == len) {
                        pendingEscape = c.toString()
                        break
                    }
                    val codePoint = Character.codePointAt(input, i)
                    buffer.appendCodePoint(codePoint)
                    i += Character.charCount(codePoint)
                }
            }
        }
        return completedLines to buffer.toString()
    }

    @Synchronized
    fun clear() {
        discard = null
        oscEsc = false
        buffer.reset()
        pendingEscape = ""
    }
}
