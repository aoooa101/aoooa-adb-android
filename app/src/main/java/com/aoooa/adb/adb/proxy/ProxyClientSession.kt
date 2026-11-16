package com.aoooa.adb.adb.proxy

import com.aoooa.adb.AdbManager
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** 单个 Smart Socket 客户端的主机查询及设备服务切换。 */
internal class ProxyClientSession(private val socket: Socket, private val running: () -> Boolean,
                                  private val stopServer: () -> Unit) {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()
    private val supportedFeatures = setOf("shell_v2", "cmd", "stat_v2", "ls_v2",
        "fixed_push_mkdir", "apex", "abb", "abb_exec", "sendrecv_v2")

    fun run() {
        socket.soTimeout = 15000
        val request = SmartSocketProtocol.readRequest(input) ?: return
        when {
            request == "host:version" -> SmartSocketProtocol.reply(output, "0029")
            request == "host:kill" -> { SmartSocketProtocol.okay(output); stopServer() }
            request == "host:devices" || request == "host:devices-l" ->
                SmartSocketProtocol.reply(output, listing(request.endsWith("-l")))
            request == "host:track-devices" || request == "host:track-devices-l" -> track(request.endsWith("-l"))
            request == "host:host-features" -> SmartSocketProtocol.reply(output, supportedFeatures.sorted().joinToString(","))
            request.startsWith("host:connect:") -> connect(request.removePrefix("host:connect:"))
            request == "host:disconnect" || request.startsWith("host:disconnect:") -> disconnect(request.substringAfter("host:disconnect", "").removePrefix(":"))
            transportSelector(request) != null -> transport(transportSelector(request)!!, request.startsWith("host:tport:"))
            else -> query(request)
        }
    }

    private fun listing(long: Boolean) = ProxyDeviceRegistry.current()?.listing(long).orEmpty()

    private fun track(long: Boolean) {
        socket.soTimeout = 1000
        SmartSocketProtocol.okay(output)
        var previous: String? = null
        while (running() && !socket.isClosed) {
            val current = listing(long)
            if (current != previous) {
                SmartSocketProtocol.data(output, current)
                previous = current
            }
            try {
                if (input.read() < 0) return
                return
            } catch (_: java.net.SocketTimeoutException) {
                // 超时用于检查设备列表及服务停止状态。
            }
        }
    }

    private fun transport(selector: String, returnId: Boolean) {
        val device = ProxyDeviceRegistry.select(selector)
        if (device == null) {
            SmartSocketProtocol.fail(output, "device not found")
            return
        }
        SmartSocketProtocol.okay(output)
        if (returnId) {
            output.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(device.id).array())
            output.flush()
        }
        val service = SmartSocketProtocol.readRequest(input) ?: return
        if (ProxyDeviceRegistry.current()?.connection !== device.connection) {
            SmartSocketProtocol.fail(output, "device offline")
            return
        }
        if (!isDeviceService(service)) {
            SmartSocketProtocol.fail(output, "service not supported via proxy")
            return
        }
        ProxyStreamBridge.relay(socket, device.connection, service)
    }

    private fun query(request: String) {
        val command = request.substringAfterLast(':')
        val prefix = request.removeSuffix(":$command")
        val selector = when {
            prefix == "host" -> "any"
            prefix == "host-usb" -> "usb"
            prefix == "host-local" -> "local"
            prefix.startsWith("host-serial:") -> "serial:" + prefix.removePrefix("host-serial:")
            prefix.startsWith("host-transport-id:") -> "id:" + prefix.removePrefix("host-transport-id:")
            else -> null
        }
        if (selector == null || command !in setOf("features", "get-state", "get-serialno", "get-devpath")) {
            SmartSocketProtocol.fail(output, "unknown request: $request")
            return
        }
        val device = ProxyDeviceRegistry.select(selector)
        if (device == null) {
            SmartSocketProtocol.fail(output, "device not found")
            return
        }
        val reply = when (command) {
            "features" -> device.connection.remoteFeatures.intersect(supportedFeatures).sorted().joinToString(",")
            "get-state" -> "device"
            "get-serialno" -> device.serial
            else -> "unknown"
        }
        SmartSocketProtocol.reply(output, reply)
    }

    private fun connect(target: String) {
        val address = parseAddress(target)
        if (address == null) {
            SmartSocketProtocol.reply(output, "unable to connect: invalid address")
            return
        }
        val serial = "${address.first}:${address.second}"
        fun matches(device: ProxyDeviceRegistry.Device): Boolean {
            val current = device.serial
            val parsed = parseAddress(current) ?: current.substringBeforeLast(':', "")
                .takeIf { it.isNotEmpty() }?.let { host ->
                    current.substringAfterLast(':').toIntOrNull()?.let { host to it }
                }
            return !device.usb && parsed == address
        }
        val current = ProxyDeviceRegistry.current()
        if (current != null) {
            SmartSocketProtocol.reply(output, if (matches(current)) "already connected to $serial"
                else "unable to connect: another device is active")
            return
        }
        AdbManager.connectTcp(null, address.first, address.second)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(35)
        while (running() && System.nanoTime() < deadline) {
            val device = ProxyDeviceRegistry.current()
            if (device != null) {
                SmartSocketProtocol.reply(output, if (matches(device)) "connected to $serial"
                    else "unable to connect: another device became active")
                return
            }
            Thread.sleep(100)
        }
        SmartSocketProtocol.reply(output, "unable to connect to $serial: connection not established")
    }

    private fun disconnect(target: String) {
        val device = ProxyDeviceRegistry.current()
        if (device != null && !device.usb && (target.isEmpty() || target == device.serial)) {
            AdbManager.disconnect()
            SmartSocketProtocol.reply(output, "disconnected ${device.serial}")
        } else {
            SmartSocketProtocol.reply(output, if (target.isEmpty()) "disconnected everything" else "error: no such device '$target'")
        }
    }

    companion object {
        internal fun transportSelector(request: String): String? = when {
            request.startsWith("host:tport:") -> request.removePrefix("host:tport:")
            request.startsWith("host:transport-id:") -> "id:" + request.removePrefix("host:transport-id:")
            request.startsWith("host:transport:") -> "serial:" + request.removePrefix("host:transport:")
            request == "host:transport-any" -> "any"
            request == "host:transport-usb" -> "usb"
            request == "host:transport-local" -> "local"
            else -> null
        }

        internal fun isDeviceService(service: String): Boolean = service.isNotEmpty() &&
            ('\u0000' !in service || service.startsWith("abb:") || service.startsWith("abb_exec:")) &&
            (service.startsWith("shell:") || service.startsWith("shell,") || service.startsWith("exec:") ||
                service == "sync:" || service.startsWith("tcp:") || service.startsWith("localabstract:") ||
                service.startsWith("localfilesystem:") || service.startsWith("localreserved:") ||
                service.startsWith("abb:") || service.startsWith("abb_exec:") || service.startsWith("reboot:") ||
                service == "root:" || service == "unroot:" || service == "remount:")

        internal fun parseAddress(target: String): Pair<String, Int>? {
            val host: String
            val port: Int
            if (target.startsWith('[')) {
                val end = target.indexOf(']')
                if (end <= 1) return null
                host = target.substring(1, end)
                val suffix = target.substring(end + 1)
                port = if (suffix.isEmpty()) 5555 else {
                    if (!suffix.startsWith(':')) return null
                    suffix.drop(1).toIntOrNull() ?: return null
                }
            } else {
                if (target.count { it == ':' } > 1) return null
                host = target.substringBefore(':')
                port = if (':' in target) target.substringAfter(':').toIntOrNull() ?: return null else 5555
            }
            if (host.isBlank() || host.any { it.isWhitespace() || it.code < 32 } || port !in 1..65535) return null
            return host to port
        }
    }
}
