package com.aoooa.adb.shizuku

import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import java.util.concurrent.ConcurrentHashMap

/** 在 Shizuku 特权进程中执行命令并通过管道返回合并输出。 */
@Keep
class CommandUserService : ICommandService.Stub() {
    private data class Command(val process: Process, val output: ParcelFileDescriptor)
    private val commands = ConcurrentHashMap<String, Command>()

    override fun start(command: Array<out String>, token: String): ParcelFileDescriptor {
        require(command.isNotEmpty() && command.size <= 128)
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            val process = ProcessBuilder(command.toList()).redirectErrorStream(true).start()
            process.outputStream.close()
            val entry = Command(process, pipe[1])
            commands.put(token, entry)?.let { previous ->
                previous.process.destroy()
                runCatching { previous.output.close() }
            }
            Thread({
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                        process.inputStream.use { it.copyTo(output) }
                    }
                    process.waitFor()
                } catch (_: Exception) {
                    process.destroy()
                } finally {
                    commands.remove(token, entry)
                    process.destroy()
                    runCatching { pipe[1].close() }
                }
            }, "shizuku-command-output").apply { isDaemon = true; start() }
            return pipe[0]
        } catch (e: Exception) {
            pipe.forEach { runCatching { it.close() } }
            throw e
        }
    }

    override fun stop(token: String) {
        commands.remove(token)?.let { entry ->
            entry.process.destroy()
            runCatching { entry.output.close() }
        }
    }

    override fun destroy() {
        for (token in commands.keys) stop(token)
        kotlin.system.exitProcess(0)
    }
}
