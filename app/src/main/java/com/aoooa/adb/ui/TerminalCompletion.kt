package com.aoooa.adb.ui

/** 输入框末尾词的补全请求；不对变量、命令替换或 Shell 运算符求值。 */
internal data class CompletionRequest(val before: String, val word: String, val command: Boolean)

internal data class CompletionCandidate(val value: String, val directory: Boolean)

/** 构造只读候选查询并将结果转义回输入框，不提交用户命令。 */
internal object TerminalCompletion {
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun parse(input: String): CompletionRequest? {
        var start = 0
        var words = 0
        var quoting: Char? = null
        var escaped = false
        val word = StringBuilder()
        for ((index, c) in input.withIndex()) {
            if (escaped) {
                word.append(c)
                escaped = false
            } else if (c == '\\' && quoting != '\'') {
                escaped = true
            } else if (quoting != null) {
                if (c == quoting) quoting = null else {
                    if (quoting == '"' && c in "\u0024`") return null
                    word.append(c)
                }
            } else when {
                c == '\'' || c == '"' -> quoting = c
                c == ' ' || c == '\t' -> {
                    if (index > start) words++
                    start = index + 1
                    word.setLength(0)
                }
                c in "\u0024`;|&<>()\n\r" -> return null
                else -> word.append(c)
            }
        }
        if (escaped) return null
        return CompletionRequest(input.substring(0, start), word.toString(), words == 0)
    }

    fun query(request: CompletionRequest, workingDir: String): String {
        if (!workingDir.startsWith('/')) return "exit 0"
        val word = request.word
        val slash = word.lastIndexOf('/')
        val directory = if (slash >= 0) word.substring(0, slash + 1) else "./"
        val prefix = word.substring(slash + 1)
        val commands = if (request.command && slash < 0) """
            for n in cd pwd echo printf exit export unset alias unalias set getopts read test command type; do
                case "${'$'}n" in "${'$'}prefix"*) printf 'C\t%s\000' "${'$'}n";; esac
            done
            oldIFS=${'$'}IFS; IFS=:
            for d in ${'$'}PATH; do
                IFS=${'$'}oldIFS
                for f in "${'$'}{d:-.}/""${'$'}prefix"*; do
                    [ -f "${'$'}f" ] && [ -x "${'$'}f" ] || continue
                    printf 'C\t%s\000' "${'$'}{f##*/}"
                    count=${'$'}((count+1)); [ "${'$'}count" -ge 200 ] && exit
                done
                IFS=:
            done
            IFS=${'$'}oldIFS
        """.trimIndent() else ""
        return """
            cd ${quote(workingDir)} 2>/dev/null || exit
            prefix=${quote(prefix)}; dir=${quote(directory)}; count=0
            $commands
            for f in "${'$'}dir""${'$'}prefix"*; do
                [ -e "${'$'}f" ] || [ -L "${'$'}f" ] || continue
                if [ -d "${'$'}f" ]; then kind=D; else kind=F; fi
                printf '%s\t%s\000' "${'$'}kind" "${'$'}{f##*/}"
                count=${'$'}((count+1)); [ "${'$'}count" -ge 200 ] && break
            done
            exit 0
        """.trimIndent()
    }

    fun candidates(request: CompletionRequest, output: String): List<CompletionCandidate> {
        val directory = request.word.substringBeforeLast('/', "").let {
            if ('/' in request.word) "$it/" else ""
        }
        return output.split('\u0000').mapNotNull { record ->
            if (record.length < 3 || record[1] != '\t' || record[0] !in "CDF") return@mapNotNull null
            val name = record.substring(2)
            val value = (if (record[0] == 'C') name else directory + name) +
                (if (record[0] == 'D') "/" else "")
            if (!value.startsWith(request.word)) return@mapNotNull null
            CompletionCandidate(value, record[0] == 'D')
        }.distinctBy { it.value }.sortedBy { it.value }.take(200)
    }

    fun apply(request: CompletionRequest, value: String, finish: Boolean): String {
        val escaped = if (value.all { it.isLetterOrDigit() || it in "_./-" } && value.isNotEmpty()) value else quote(value)
        return request.before + escaped + if (finish && !value.endsWith('/')) " " else ""
    }

    fun commonPrefix(candidates: List<CompletionCandidate>): String =
        candidates.map { it.value }.reduceOrNull { a, b -> a.commonPrefixWith(b) }.orEmpty()
}
