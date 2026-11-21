package com.aoooa.adb.adb.protocol

/** 当前文本行的光标覆盖与 SGR 样式；不模拟多行全屏终端。 */
internal class TerminalLineBuffer {
    private data class Cell(val character: Int, val style: String)
    private val cells = ArrayList<Cell>()
    private var cursor = 0
    private var colors = TerminalSgr()
    private var style = ""

    fun append(character: Char) = appendCodePoint(character.code)

    fun appendCodePoint(character: Int) {
        while (cells.size < cursor) cells.add(Cell(' '.code, ""))
        val cell = Cell(character, style)
        if (cursor < cells.size) cells[cursor] = cell else cells.add(cell)
        cursor++
    }

    fun sgr(sequence: String) {
        val parameters = sequence.removePrefix("\u001b[").removeSuffix("m")
        val next = colors.applyParameters(parameters)
        if (next == colors) return
        colors = next
        style = when {
            next.foreground == null && next.background == null -> ""
            parameters.length <= 32 && TerminalSgr().applyParameters(parameters) == next -> sequence
            else -> buildString {
                fun emit(prefix: Int, argb: Int) {
                    append("\u001b[${prefix};2;${argb ushr 16 and 255};${argb ushr 8 and 255};${argb and 255}m")
                }
                next.foreground?.let { emit(38, it) }
                next.background?.let { emit(48, it) }
            }
        }
    }

    fun carriageReturn() { cursor = 0 }
    fun backspace() { cursor = (cursor - 1).coerceAtLeast(0) }
    fun move(delta: Int) { cursor = (cursor.toLong() + delta).coerceIn(0L, 65536L).toInt() }
    fun column(value: Int) { cursor = value.coerceIn(0, 65536) }

    fun erase(mode: Int) {
        when (mode) {
            0 -> if (cursor < cells.size) cells.subList(cursor, cells.size).clear()
            1 -> for (index in 0..minOf(cursor, cells.lastIndex)) cells[index] = Cell(' '.code, "")
            2 -> cells.clear()
        }
    }

    fun clear() {
        cells.clear()
        cursor = 0
    }

    fun reset() {
        clear()
        colors = TerminalSgr()
        style = ""
    }

    override fun toString(): String = buildString {
        var current = ""
        for (cell in cells) {
            if (cell.style != current) {
                if (current.isNotEmpty()) append("\u001b[0m")
                append(cell.style)
                current = cell.style
            }
            appendCodePoint(cell.character)
        }
        if (current.isNotEmpty()) append("\u001b[0m")
    }
}
