package com.aoooa.adb

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aoooa.adb.shizuku.CommandUserService
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommandUserServiceTest {
    @Test fun outputPipeIncludesStandardError() {
        val service = CommandUserService()
        val token = "output-test"
        val descriptor = service.start(arrayOf("sh", "-c", "printf stdout; printf stderr >&2"), token)
        try {
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val result = FutureTask<String> { input.bufferedReader().readText() }
                Thread(result).apply { isDaemon = true; start() }
                assertEquals("stdoutstderr", result.get(5, TimeUnit.SECONDS))
            }
        } finally { service.stop(token) }
    }

    @Test fun stoppingCommandClosesOutput() {
        val service = CommandUserService()
        val token = "stop-test"
        val descriptor = service.start(arrayOf("sh", "-c", "exec sleep 60"), token)
        try {
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val result = FutureTask<Boolean> {
                    try { input.read() == -1 } catch (_: java.io.IOException) { true }
                }
                Thread(result).apply { isDaemon = true; start() }
                service.stop(token)
                assertTrue(result.get(5, TimeUnit.SECONDS))
            }
        } finally { service.stop(token) }
    }
}
