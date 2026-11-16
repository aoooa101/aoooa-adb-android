package com.aoooa.adb.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.aoooa.adb.BuildConfig
import rikka.shizuku.Shizuku
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 管理一次命令的 UserService 绑定、管道及取消。 */
internal object ShizukuCommandClient {
    private val main = Handler(Looper.getMainLooper())
    private val cleanup = Executors.newCachedThreadPool { task ->
        Thread(task, "shizuku-command-cleanup").apply { isDaemon = true }
    }

    class Session(
        val input: ParcelFileDescriptor.AutoCloseInputStream,
        private val dispose: () -> Unit
    ) : Closeable {
        private val closed = AtomicBoolean(false)
        override fun close() {
            if (closed.compareAndSet(false, true)) {
                runCatching { input.close() }
                dispose()
            }
        }
    }

    fun open(command: Array<String>): Session {
        check(Looper.myLooper() !== Looper.getMainLooper()) { "UserService binding requires a worker thread" }
        val token = UUID.randomUUID().toString()
        val args = Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, CommandUserService::class.java.name))
            .tag("command-$token")
            .version(BuildConfig.VERSION_CODE)
            .processNameSuffix("command")
            .daemon(false)
        val ready = CountDownLatch(1)
        val service = AtomicReference<ICommandService?>()
        val failure = AtomicReference<Exception?>()
        val cancelled = AtomicBoolean(false)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (!cancelled.get()) service.set(ICommandService.Stub.asInterface(binder))
                ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {
                service.set(null)
                ready.countDown()
            }
        }
        fun dispose() {
            if (!cancelled.compareAndSet(false, true)) return
            cleanup.execute {
                runCatching { service.getAndSet(null)?.stop(token) }
                main.post { runCatching { Shizuku.unbindUserService(args, connection, true) } }
            }
        }
        main.post {
            if (!cancelled.get()) {
                try {
                    Shizuku.bindUserService(args, connection)
                } catch (e: Exception) {
                    failure.set(e)
                    ready.countDown()
                }
            }
        }
        try {
            check(ready.await(15, TimeUnit.SECONDS)) { "UserService binding timed out" }
            failure.get()?.let { throw it }
            val remote = service.get() ?: error("UserService disconnected")
            val descriptor = remote.start(command, token) ?: error("UserService output unavailable")
            return Session(ParcelFileDescriptor.AutoCloseInputStream(descriptor), ::dispose)
        } catch (e: Exception) {
            dispose()
            if (e is InterruptedException) Thread.currentThread().interrupt()
            throw e
        }
    }
}
