package com.aoooa.adb.log

import java.io.File
import java.io.OutputStream
import java.io.EOFException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object DiagnosticLogExporter {
    fun write(files: List<File>, output: OutputStream, zip: Boolean) {
        require(files.isNotEmpty() && (zip || files.size == 1))
        if (!zip) { copySnapshot(files.single(), output); return }
        val names = hashSetOf<String>()
        ZipOutputStream(output.buffered()).use { archive ->
            for (file in files) {
                var entry = file.name
                var index = 1
                while (!names.add(entry)) entry = "${file.nameWithoutExtension}_${index++}.log"
                archive.putNextEntry(ZipEntry(entry))
                copySnapshot(file, archive)
                archive.closeEntry()
            }
        }
    }

    private fun copySnapshot(file: File, output: OutputStream) {
        file.inputStream().use { input ->
            var remaining = input.channel.size()
            val buffer = ByteArray(16384)
            while (remaining > 0) {
                val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                if (n < 0) throw EOFException(file.name)
                output.write(buffer, 0, n)
                remaining -= n
            }
        }
    }
}
