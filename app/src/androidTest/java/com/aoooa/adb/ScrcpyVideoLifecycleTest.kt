package com.aoooa.adb

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aoooa.adb.control.ScrcpyVideoDecoder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.Collections

/** 无媒体输入时的 Surface 解绑及解码线程生命周期测试。 */
@RunWith(AndroidJUnit4::class)
class ScrcpyVideoLifecycleTest {
    @Test fun repeatedDetachAndRestartCompletes() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val decoder = ScrcpyVideoDecoder(onError = { errors.add(it) }, diagnose = { events.add(it) })
        val task = FutureTask {
            try {
                repeat(10) {
                    decoder.start()
                    decoder.attachSurface(null)
                    decoder.attachSurface(null)
                    decoder.stop()
                }
            } finally {
                decoder.stop()
            }
        }
        Thread(task).apply { isDaemon = true; start() }
        try {
            task.get(10, TimeUnit.SECONDS)
            assertTrue(errors.toString(), errors.isEmpty())
            assertEquals(20, events.count { it.startsWith("surface_detached") })
        } finally {
            task.cancel(true)
        }
    }
}
