package com.aoooa.adb.log

import android.content.Context
import com.aoooa.adb.util.LogRotator
import java.io.File

internal class DiagnosticLogStore(private val context: Context) {
    fun list(): List<File> = LogRotator.logFiles(context).sortedByDescending { it.lastModified() }

    fun resolve(path: String): File = list().first { it.absolutePath == path && it.canonicalFile == it.absoluteFile }

    fun preview(path: String): Pair<String, Boolean> = resolve(path).bufferedReader(Charsets.UTF_8).use { reader ->
        val chars = CharArray(65536)
        var count = 0
        while (count < chars.size) {
            val n = reader.read(chars, count, chars.size - count)
            if (n <= 0) break
            count += n
        }
        String(chars, 0, count) to (reader.read() != -1)
    }
}
