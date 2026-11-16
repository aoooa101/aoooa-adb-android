package com.aoooa.adb.pairing

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import com.aoooa.adb.AdbManager
import java.util.ArrayDeque

/** 管理单类服务发现；解析请求由进程内共享队列串行执行。 */
internal class NsdDiscoverySession(
    private val manager: NsdManager,
    private val serviceType: String,
    private val label: String,
    private val onResolved: (NsdServiceInfo) -> Unit
) : AutoCloseable {
    private var closed = false
    private var started = false
    private var requested = false

    private fun log(message: String) = AdbManager.debugLog("[mDNS][$label][$serviceType] $message")

    private val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) = NsdResolveQueue.dispatch {
            started = true
            log("发现已启动")
            if (closed) stop()
        }

        override fun onServiceFound(service: NsdServiceInfo) = NsdResolveQueue.dispatch {
            if (!closed) NsdResolveQueue.enqueue(manager, service, { closed }, ::log) { info ->
                if (!closed) onResolved(info)
            }
        }

        override fun onServiceLost(service: NsdServiceInfo) = Unit

        override fun onDiscoveryStopped(type: String) = NsdResolveQueue.dispatch {
            started = false
            requested = false
            log("发现已停止")
        }

        override fun onStartDiscoveryFailed(type: String, errorCode: Int) = NsdResolveQueue.dispatch {
            requested = false
            started = false
            log("发现启动失败: errorCode=$errorCode")
        }

        override fun onStopDiscoveryFailed(type: String, errorCode: Int) = NsdResolveQueue.dispatch {
            log("发现停止失败: errorCode=$errorCode")
        }
    }

    fun start() = NsdResolveQueue.dispatch {
        if (!closed && !requested) {
            requested = true
            try {
                manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: Exception) {
                requested = false
                log("发现启动异常: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun stop() {
        if (!started) return
        started = false
        try {
            manager.stopServiceDiscovery(listener)
        } catch (e: Exception) {
            log("发现停止异常: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun close() = NsdResolveQueue.dispatch {
        closed = true
        stop()
    }
}

/** 在主线程串行解析；关闭的会话不再处理结果，正在进行的解析仍等待系统回调释放槽位。 */
private object NsdResolveQueue {
    private val handler = Handler(Looper.getMainLooper())
    private data class Request(
        val manager: NsdManager,
        val service: NsdServiceInfo,
        val cancelled: () -> Boolean,
        val log: (String) -> Unit,
        val resolved: (NsdServiceInfo) -> Unit,
        var retries: Int = 0
    )
    private val pending = ArrayDeque<Request>()
    private var active: Request? = null

    fun dispatch(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post { action() }
    }

    fun enqueue(
        manager: NsdManager,
        service: NsdServiceInfo,
        cancelled: () -> Boolean,
        log: (String) -> Unit,
        resolved: (NsdServiceInfo) -> Unit
    ) {
        pending.addLast(Request(manager, service, cancelled, log, resolved))
        next()
    }

    private fun next() {
        if (active != null) return
        while (pending.isNotEmpty()) {
            val request = pending.removeFirst()
            if (request.cancelled()) continue
            active = request
            resolve(request)
            return
        }
    }

    private fun finish(request: Request) {
        if (active !== request) return
        active = null
        next()
    }

    private fun resolve(request: Request) {
        if (request.cancelled()) {
            finish(request)
            return
        }
        try {
            request.manager.resolveService(request.service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = dispatch {
                    if (active === request) {
                        request.log("解析失败: service=${info.serviceName}, errorCode=$errorCode, retry=${request.retries}")
                        if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE &&
                            !request.cancelled() && request.retries < 3
                        ) {
                            request.retries++
                            handler.postDelayed({ resolve(request) }, 250L)
                        } else {
                            finish(request)
                        }
                    }
                }

                override fun onServiceResolved(info: NsdServiceInfo) = dispatch {
                    if (active === request) {
                        try {
                            if (!request.cancelled()) {
                                request.log("解析成功: service=${info.serviceName}, host=${info.host?.hostAddress}, port=${info.port}")
                                request.resolved(info)
                            }
                        } catch (e: Exception) {
                            request.log("解析结果处理异常: service=${info.serviceName}, ${e.javaClass.simpleName}: ${e.message}")
                        } finally {
                            finish(request)
                        }
                    }
                }
            })
        } catch (e: Exception) {
            request.log("解析调用异常: service=${request.service.serviceName}, ${e.javaClass.simpleName}: ${e.message}")
            finish(request)
        }
    }
}
