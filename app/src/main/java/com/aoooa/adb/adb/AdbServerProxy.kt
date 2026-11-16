package com.aoooa.adb.adb

import com.aoooa.adb.AdbManager
import com.aoooa.adb.adb.proxy.ProxyClientSession
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/** 本机回环 Smart Socket 服务；设备服务通过独立 ADB 流转发。 */
object AdbServerProxy {
    data class Status(val starting: Boolean, val port: Int?, val error: String?, val address: String? = null)
    private var lastError: String? = null

    @Synchronized
    fun status(): Status {
        val listener = serverSocket?.takeUnless { it.isClosed }
        return Status(starting, listener?.localPort, lastError, listener?.inetAddress?.hostAddress)
    }

    private var generation = 0L
    private var starting = false
    private var serverSocket: ServerSocket? = null
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val slots = Semaphore(16)
    private val workers = Executors.newCachedThreadPool { task ->
        Thread(task, "adb-proxy").apply { isDaemon = true }
    }

    @Synchronized
    fun start(port: Int = 5037, allowLan: Boolean = false) {
        require(port in 1..65535)
        if (starting || serverSocket != null) return
        starting = true
        lastError = null
        val run = ++generation
        val address = if (allowLan) "0.0.0.0" else "127.0.0.1"
        workers.execute { listen(port, run, address) }
    }

    private fun listen(port: Int, run: Long, address: String) {
        var server: ServerSocket? = null
        try {
            val listener = ServerSocket(port, 16, InetAddress.getByName(address))
            server = listener
            synchronized(this) {
                if (generation != run) {
                    listener.close()
                    return
                }
                serverSocket = listener
                starting = false
            }
            AdbManager.debugLog("[AdbServerProxy] 监听 $address:$port")
            while (active(run)) {
                val client = listener.accept()
                synchronized(this) {
                    if (generation != run || !slots.tryAcquire()) {
                        client.close()
                    } else {
                        clients.add(client)
                        workers.execute {
                            try {
                                ProxyClientSession(client, { active(run) }, { stopGeneration(run) }).run()
                            } catch (e: Exception) {
                                AdbManager.debugLog("[AdbServerProxy] 会话结束: ${e.javaClass.simpleName}")
                            } finally {
                                runCatching { client.close() }
                                clients.remove(client)
                                slots.release()
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            synchronized(this) {
                if (generation == run) {
                    lastError = "${e.javaClass.simpleName}: ${e.message.orEmpty()}"
                    AdbManager.debugLog("[AdbServerProxy] 监听结束: $lastError")
                }
            }
        } finally {
            runCatching { server?.close() }
            synchronized(this) {
                if (generation == run) {
                    starting = false
                    serverSocket = null
                    clients.forEach { runCatching { it.close() } }
                }
            }
        }
    }

    @Synchronized
    private fun active(run: Long) = generation == run && (starting || serverSocket != null)

    @Synchronized
    private fun stopGeneration(run: Long) {
        if (generation == run) stop()
    }

    @Synchronized
    fun stop() {
        generation++
        starting = false
        lastError = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        clients.forEach { runCatching { it.close() } }
    }
}
