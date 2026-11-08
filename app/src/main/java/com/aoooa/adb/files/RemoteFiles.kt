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

        /** 将 POSIX st_mode 转换为 10 位标准权限字符串（如 drwxr-xr-x） */
        fun modeToString(mode: Int): String {
            val type = when (mode and 0xF000) {
                0x4000 -> 'd' // 目录 (S_IFDIR)
                0x8000 -> '-' // 普通文件 (S_IFREG)
                0xA000 -> 'l' // 符号链接 (S_IFLNK)
                0x2000 -> 'c' // 字符设备 (S_IFCHR)
                0x6000 -> 'b' // 块设备 (S_IFBLK)
                0x1000 -> 'p' // FIFO 管道 (S_IFIFO)
                0xC000 -> 's' // 套接字 (S_IFSOCK)
                else -> '-'
            }
            val r1 = if (mode and 0x100 != 0) 'r' else '-'
            val w1 = if (mode and 0x080 != 0) 'w' else '-'
            val x1 = if (mode and 0x040 != 0) (if (mode and 0x800 != 0) 's' else 'x') else (if (mode and 0x800 != 0) 'S' else '-')
            val r2 = if (mode and 0x020 != 0) 'r' else '-'
            val w2 = if (mode and 0x010 != 0) 'w' else '-'
            val x2 = if (mode and 0x008 != 0) (if (mode and 0x400 != 0) 's' else 'x') else (if (mode and 0x400 != 0) 'S' else '-')
            val r3 = if (mode and 0x004 != 0) 'r' else '-'
            val w3 = if (mode and 0x002 != 0) 'w' else '-'
            val x3 = if (mode and 0x001 != 0) (if (mode and 0x200 != 0) 't' else 'x') else (if (mode and 0x200 != 0) 'T' else '-')
            return "$type$r1$w1$x1$r2$w2$x2$r3$w3$x3"
        }
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

    /** 优先通过 ADB Sync LIST 协议列出目录项，异常时降级为通用 shell 命令。 */
    fun list(path: String): List<RemoteEntry> {
        val normalized = normalize(path)
        return try {
            val syncEntries = client.list(normalized)
            syncEntries.map { entry ->
                RemoteEntry(
                    name = entry.name,
                    path = child(normalized, entry.name),
                    mode = modeToString(entry.mode),
                    size = entry.size,
                    modified = entry.time
                )
            }.sortedWith(compareByDescending<RemoteEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
        } catch (e: Exception) {
            // 解析失败时退回目录列表降级方案，记录原始异常便于定位协议层问题
            com.aoooa.adb.AdbManager.debugLog(
                "[RemoteFiles] ls 解析失败，改用目录列表: $normalized (${e.javaClass.simpleName}: ${e.message})"
            )
            listFallback(normalized)
        }
    }

    private fun listFallback(path: String): List<RemoteEntry> {
        val p = quote(normalize(path))
        val cmd = "cd $p && { (toybox stat -c '%A\\t%s\\t%Y\\t%n' ./* ./.* 2>/dev/null || stat -c '%A\\t%s\\t%Y\\t%n' ./* ./.* 2>/dev/null) || true; }"
        val raw = checked(cmd)
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size >= 4) {
                val mode = parts[0]
                val size = parts[1].toLongOrNull() ?: 0L
                val time = parts[2].toLongOrNull() ?: 0L
                val rawName = parts.subList(3, parts.size).joinToString("\t").removePrefix("./")
                if (rawName != "." && rawName != ".." && rawName.isNotEmpty()) {
                    RemoteEntry(rawName, child(path, rawName), mode, size, time)
                } else null
            } else null
        }.toList().sortedWith(compareByDescending<RemoteEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
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

    /** 安全读取文本文件内容（默认最多读取前 128KB 避免超大文件造成内存压力） */
    fun readText(path: String, maxBytes: Int = 131072): String {
        return checked("head -c $maxBytes -- ${quote(path)}")
    }

    /** 读取远端二进制数据（用于图片解码，带最大字节上限） */
    fun readBytes(path: String, maxBytes: Long = 15 * 1024 * 1024): ByteArray {
        val out = object : java.io.ByteArrayOutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) {
                check(count.toLong() + len <= maxBytes) { "文件过大，超出预览限制 / File exceeds preview limit" }
                super.write(b, off, len)
            }
        }
        client.download(path, out, -1L) {}
        return out.toByteArray()
    }

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
