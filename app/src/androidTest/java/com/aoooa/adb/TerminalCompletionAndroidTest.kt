package com.aoooa.adb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aoooa.adb.ui.TerminalCompletion
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** 在 Android Shell 中验证候选查询、文件名转义和命令隔离。 */
@RunWith(AndroidJUnit4::class)
class TerminalCompletionAndroidTest {
    private lateinit var root: File

    @Before fun prepare() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        root = File(context.cacheDir, "completion-test-${java.util.UUID.randomUUID()}")
        assertTrue(root.mkdirs())
    }

    @After fun cleanup() {
        if (::root.isInitialized) root.deleteRecursively()
    }

    private fun runShell(script: String): String {
        val output = File(root, "query-output")
        val process = ProcessBuilder("/system/bin/sh", "-c", script)
            .redirectOutput(output).redirectError(File(root, "query-error")).start()
        try {
            assertTrue("Shell 查询超时", process.waitFor(5, TimeUnit.SECONDS))
            assertEquals("Shell 查询失败: ${File(root, "query-error").readText()}", 0, process.exitValue())
            return output.readText()
        } finally {
            process.destroy()
        }
    }

    private fun complete(input: String) = TerminalCompletion.parse(input)!!.let { request ->
        TerminalCompletion.candidates(request, runShell(TerminalCompletion.query(request, root.absolutePath)))
    }

    @Test fun findsFilesAndDirectories() {
        File(root, "sample.txt").writeText("")
        assertTrue(File(root, "sample-dir").mkdir())
        assertEquals(listOf("sample-dir/", "sample.txt"), complete("cat sam").map { it.value })
    }

    @Test fun supportsSpacesChineseAndQuotes() {
        listOf("My File", "My 文件", "My 'quote'").forEach { File(root, it).writeText("") }
        val request = TerminalCompletion.parse("cat 'My ")!!
        val candidates = TerminalCompletion.candidates(request, runShell(TerminalCompletion.query(request, root.absolutePath)))
        assertEquals(setOf("My File", "My 文件", "My 'quote'"), candidates.map { it.value }.toSet())
        for (candidate in candidates) {
            val encoded = TerminalCompletion.apply(request, candidate.value, true).removePrefix("cat ")
            assertEquals(candidate.value, runShell("printf '%s' $encoded"))
        }
    }

    @Test fun treatsInjectionCharactersAsLiteralNames() {
        val name = "a;touch marker"
        File(root, name).writeText("")
        val candidates = complete("cat 'a;touch marker'")
        assertEquals(listOf(name), candidates.map { it.value })
        assertFalse(File(root, "marker").exists())
    }

    @Test fun resolvesRelativeSubdirectory() {
        val dir = File(root, "sub folder").apply { mkdir() }
        File(dir, "result.txt").writeText("")
        assertEquals(listOf("sub folder/result.txt"), complete("cat 'sub folder/re").map { it.value })
    }

    @Test fun findsShellBuiltinsWithoutExecutingInput() {
        assertTrue(complete("prin").any { it.value == "printf" })
    }

    @Test fun interactivePromptReportsDirectoryAfterCd() {
        val directory = File(root, "My 文件").apply { assertTrue(mkdir()) }
        val output = File(root, "prompt-output")
        val process = ProcessBuilder("/system/bin/sh", "-i")
            .redirectErrorStream(true).redirectOutput(output).start()
        try {
            process.outputStream.bufferedWriter().use { input ->
                input.write(com.aoooa.adb.adb.protocol.TerminalDirectoryProtocol.initialization)
                input.write("cd ${TerminalCompletion.quote(directory.absolutePath)}\n")
                input.write("exit\n")
            }
            assertTrue("交互 Shell 超时", process.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            val markers = Regex("\u001b]aoooa-cwd;([^\u0007]*)\u0007")
                .findAll(output.readText()).map { it.groupValues[1] }.toList()
            assertTrue("提示符未报告切换后的目录", directory.absolutePath in markers)
        } finally {
            process.destroy()
        }
    }

    @Test fun missingPrefixReturnsNoCandidates() {
        assertTrue(complete("cat no-such-prefix-12345").isEmpty())
    }
}
