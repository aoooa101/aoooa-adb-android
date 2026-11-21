package com.aoooa.adb.log

import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticLogExporterTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun singleFileKeepsOriginalBytes() {
        val bytes = ByteArray(71421) { it.toByte() }
        val file = tmp.newFile("test.log").apply { writeBytes(bytes) }
        val output = ByteArrayOutputStream()
        DiagnosticLogExporter.write(listOf(file), output, false)
        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test fun duplicateNamesKeepBothEntries() {
        val files = listOf("a", "b").map { name -> tmp.newFolder(name).resolve("run.log").apply { writeText(name) } }
        val out = ByteArrayOutputStream()
        DiagnosticLogExporter.write(files, out, true)
        val entries = mutableMapOf<String, String>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; entries[entry.name] = zip.readBytes().decodeToString() }
        }
        assertEquals(mapOf("run.log" to "a", "run_1.log" to "b"), entries)
    }
}
