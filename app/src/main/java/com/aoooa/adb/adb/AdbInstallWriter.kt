package com.aoooa.adb.adb

import com.aoooa.adb.adb.protocol.InstallCommands
import com.aoooa.adb.ui.i18n.I18n
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** 在独立 ADB 流中发送 APK 并读取 install-write 结果。 */
internal fun AdbConnection.writeInstallSession(
    input: InputStream,
    fileSize: Long,
    sessionId: Int,
    compatible: Boolean,
    progress: (Float) -> Unit
): String {
    val output = AdbServiceOutput()
    val id = openRawStream(InstallCommands.write(fileSize, sessionId),
        onBytes = { output.offer(it) }, onClosed = { output.finish() }, autoAcknowledge = false)
    if (id == 0) return I18n.current.installWriteChannelFailed
    try {
        if (!awaitRawStreamReady(id, 6000L)) return I18n.current.installWriteChannelFailed
        val buffer = ByteArray(rawChunkSize(if (compatible) 8192 else 32768))
        var sent = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) throw java.io.IOException("APK reader made no progress")
            check(writeRawStreamAwait(id, buffer.copyOf(count))) { "ADB install stream write failed" }
            sent += count
            if (compatible) Thread.sleep(10)
            progress(sent.toFloat() / fileSize.toFloat())
        }
        check(sent == fileSize) { "APK size changed while reading" }
        val bytes = ByteArrayOutputStream()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            val data = output.poll(200)
            if (data != null) {
                check(bytes.size().toLong() + data.size <= 1024 * 1024) { "Install response too large" }
                bytes.write(data)
                acknowledgeRawStream(id)
            }
            if (output.isFinished()) {
                return String(bytes.toByteArray(), Charsets.UTF_8).trim()
            }
        }
        onDebugLog("[Install] write result timeout localId=$id")
        return I18n.current.installRemoteClosedEarly
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        throw error
    } finally {
        closeRawStream(id)
    }
}
