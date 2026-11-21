package com.aoooa.adb.adb.protocol

internal data class TerminalSgr(val foreground: Int? = null, val background: Int? = null) {
    fun applyParameters(parameters: String): TerminalSgr {
        var result = this
        val groups = parameters.split(';')
        var i = 0
        while (i < groups.size) {
            val group = groups[i++]
            val parts = group.split(':')
            val code = if (group.isEmpty()) 0 else parts[0].toIntOrNull()
            if (code == 38 || code == 48 || code == 58) {
                val sub = if (parts.size > 1) parts.drop(1) else {
                    val mode = groups.getOrNull(i++)
                    val count = when (mode) { "2" -> 3; "5" -> 1; else -> 0 }
                    val values = groups.drop(i).take(count)
                    i += count
                    listOfNotNull(mode) + values
                }
                val color = when (sub.firstOrNull()) {
                    "5" -> if (sub.size == 2) component(sub[1])?.let(::indexed) else null
                    "2" -> if (sub.size == 4 || (sub.size == 5 && sub[1] in listOf("", "0"))) {
                        val channels = sub.takeLast(3).map(::component)
                        if (channels.all { it != null }) rgb(channels[0]!!, channels[1]!!, channels[2]!!) else null
                    } else null
                    else -> null
                }
                if (color != null) result = when (code) { 38 -> result.copy(foreground = color); 48 -> result.copy(background = color); else -> result }
            } else if (parts.size == 1) {
                result = when (code) {
                    0 -> TerminalSgr()
                    39 -> result.copy(foreground = null)
                    49 -> result.copy(background = null)
                    in 30..37 -> result.copy(foreground = indexed(code!! - 30))
                    in 40..47 -> result.copy(background = indexed(code!! - 40))
                    in 90..97 -> result.copy(foreground = indexed(code!! - 90 + 8))
                    in 100..107 -> result.copy(background = indexed(code!! - 100 + 8))
                    else -> result
                }
            }
        }
        return result
    }

    companion object {
        private val palette = intArrayOf(0x94A3B8, 0xF87171, 0x4ADE80, 0xFBBF24, 0x60A5FA, 0xC084FC, 0x38BDF8, 0xF8FAFC,
            0x64748B, 0xF87171, 0x4ADE80, 0xFBBF24, 0x60A5FA, 0xC084FC, 0x38BDF8, 0xF8FAFC)
        private val levels = intArrayOf(0, 95, 135, 175, 215, 255)
        private fun component(s: String) = s.toIntOrNull()?.takeIf { it in 0..255 }
        private fun rgb(r: Int, g: Int, b: Int) = (0xff shl 24) or (r shl 16) or (g shl 8) or b
        private fun indexed(n: Int): Int = when {
            n < 16 -> (0xff shl 24) or palette[n]
            n < 232 -> (n - 16).let { rgb(levels[it / 36], levels[it / 6 % 6], levels[it % 6]) }
            else -> (8 + (n - 232) * 10).let { rgb(it, it, it) }
        }
    }
}
