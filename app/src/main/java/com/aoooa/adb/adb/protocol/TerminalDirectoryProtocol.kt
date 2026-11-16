package com.aoooa.adb.adb.protocol

/** 通过交互式 Shell 提示符传递工作目录；标记只作为状态数据解析。 */
internal object TerminalDirectoryProtocol {
    private const val PREFIX = "aoooa-cwd;"

    /** 保留原提示符，并在每次提示符参数展开时读取当前 PWD。 */
    val initialization: String =
        """PS1='${'$'}(printf "\033]$PREFIX%s\007" "${'$'}PWD")'"${'$'}PS1"; export PS1
"""

    fun directory(osc: String): String? {
        if (!osc.startsWith(PREFIX)) return null
        val path = osc.removePrefix(PREFIX)
        return path.takeIf {
            it.startsWith('/') && it.length <= 4096 &&
                it.none { c -> c.code < 32 || c.code == 127 }
        }
    }
}
