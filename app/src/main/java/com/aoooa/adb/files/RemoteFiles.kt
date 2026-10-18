package com.aoooa.adb.files

import com.aoooa.adb.adb.AdbConnection
import com.aoooa.adb.adb.DeviceFileClient
import java.util.UUID

data class RemoteEntry(val name: String, val path: String, val mode: String, val size: Long, val modified: Long) {
    val isDirectory: Boolean get() = mode.startsWith("d")
    val isFile: Boolean get() = mode.startsWith("-")
}

/** 远端文件操作，所有路径均作为单个 shell 参数传入。 */
class RemoteFiles(connection: AdbConnection) {
    val client = DeviceFileClient(connection)

    companion object {
        fun quote(value: String): String {
            require('\u0000' !in value)
            return "'" + value.replace("'", "'\"'\"'") + "'"
        }

        fun normalize(path: String): String {
            require(path.startsWith('/') && '\u0000' !in path) { "请输入绝对路径 / Absolute path required" }
            val parts = mutableListOf<String>()
            path.split('/').forEach {
                when (it) {
                    "", "." -> Unit
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                    else -> parts.add(it)
                }
            }
            return "/" + parts.joinToString("/")
        }

        fun child(directory: String, name: String): String {
            require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\u0000' !in name) {
                "名称不能为空，不能含 / 或 NUL / Invalid name"
            }
            return normalize(directory).trimEnd('/') + "/" + name
        }

        fun parent(path: String): String = normalize(path).substringBeforeLast('/').ifEmpty { "/" }
    }

    /** 独立随机结束标记用于区分命令失败和正常空输出。 */
    fun checked(command: String): String {
        val marker = "__FILE_${UUID.randomUUID().toString().replace("-", "")}__"
        val output = client.exec("{ $command; }; rc=\$?; printf '\\n$marker%s\\n' \"\$rc\"")
        val at = output.lastIndexOf("\n$marker")
        check(at >= 0) { "设备未返回完整操作结果 / Incomplete command result" }
        val body = output.substring(0, at)
        val code = output.substring(at + marker.length + 1).trim().toIntOrNull()
        check(code == 0) { body.ifBlank { "操作失败 ($code) / Operation failed" } }
        return body
    }

    fun list(path: String): List<RemoteEntry> {
        val p = quote(normalize(path))
        // find 的记录字段使用 NUL 分隔，保留文件名中的空格和换行。
        val raw = checked("cd $p && toybox find . -mindepth 1 -maxdepth 1 -printf '%M\\0%s\\0%T@\\0%f\\0'")
        val fields = raw.split('\u0000').dropLastWhile { it.isEmpty() }
        check(fields.size % 4 == 0) { "无法解析目录 / Invalid directory response" }
        return fields.chunked(4).map { f ->
            RemoteEntry(f[3], child(path, f[3]), f[0], f[1].toLong(), f[2].substringBefore('.').toLong())
        }.sortedWith(compareByDescending<RemoteEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    fun mkdir(directory: String, name: String) = checked("mkdir -- ${quote(child(directory, name))}")

    fun delete(entry: RemoteEntry) {
        require(normalize(entry.path) != "/")
        checked("rm -r -- ${quote(entry.path)}")
    }

    private fun absent(path: String): String {
        val q = quote(path)
        return "if [ -e $q ] || [ -L $q ]; then printf '目标已存在 / Destination exists\\n' >&2; false; else "
    }

    fun rename(entry: RemoteEntry, name: String) {
        val target = child(parent(entry.path), name)
        require(target != entry.path) { "名称未改变 / Name unchanged" }
        checked(absent(target) + "mv -n -- ${quote(entry.path)} ${quote(target)} && test ! -e ${quote(entry.path)} && test ! -L ${quote(entry.path)}; fi")
    }

    fun transfer(entry: RemoteEntry, directory: String, move: Boolean) {
        val target = child(directory, entry.name)
        // readlink -f 识别目录别名，防止目录复制到自身或其子目录。
        val sourceReal = checked("readlink -f -- ${quote(entry.path)}").trimEnd('\n')
        val dirReal = checked("readlink -f -- ${quote(directory)}").trimEnd('\n')
        val targetReal = child(dirReal, entry.name)
        require(targetReal != sourceReal && (!entry.isDirectory || !targetReal.startsWith(sourceReal.trimEnd('/') + "/"))) {
            "目标不能是自身或子目录 / Destination is inside source"
        }
        val operation = if (move) "mv -n" else "cp -R -P -n"
        try {
            checked(absent(target) + "$operation -- ${quote(entry.path)} ${quote(target)}; fi")
            if (move) checked("test ! -e ${quote(entry.path)} && test ! -L ${quote(entry.path)}")
        } catch (e: Exception) {
            throw IllegalStateException("${e.message}\n请检查目标是否存在部分内容；移动失败时请同时检查源目录。 / Inspect both source and destination after failure.", e)
        }
    }

    fun properties(entry: RemoteEntry): String = checked("stat -- ${quote(entry.path)}")

    /** 上传到独占临时目录，成功后发布，失败时保留目标原文件。 */
    fun upload(directory: String, name: String, input: java.io.InputStream, size: Long, progress: (Float) -> Unit) {
        val target = child(directory, name)
        val tempDir = child(directory, ".aoooa-upload-${UUID.randomUUID()}")
        checked("mkdir -- ${quote(tempDir)}")
        try {
            val tempPath = child(tempDir, name)
            client.upload(tempPath, input, size, progress)
            checked(absent(target) + "mv -n -- ${quote(tempPath)} ${quote(target)}; fi")
            checked("test ! -e ${quote(tempPath)}")
        } finally {
            runCatching { checked("rm -r -- ${quote(tempDir)}") }
        }
    }
}
