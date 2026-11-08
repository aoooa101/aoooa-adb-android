package com.aoooa.adb.mcp

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.aoooa.adb.AdbManager
import com.aoooa.adb.Prefs
import com.aoooa.adb.adb.shell
import com.aoooa.adb.ui.i18n.I18n
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ADB MCP (Model Context Protocol) 独立服务实现：
 * 1. 提供基于标准 HTTP / SSE 的轻量级双模通信服务；
 * 2. 支持 Bearer Token 标准 HTTP 鉴权验证与 WWW-Authenticate 规范；
 * 3. 采用协程互斥锁 (Mutex) 实现并发命令排队执行机制；
 * 4. 接入安全规则引擎并在执行前拦截危险命令；
 * 5. 接入长输出单坑位缓存与翻页控制机制；
 * 6. 联动系统前台通知栏展示运行状态与局域网访问地址。
 */
object AdbMcpServer {

    val isRunning = mutableStateOf(false)

    /** 启动流程进行中标记，防止并发重复绑定端口。 */
    @Volatile
    private var isStarting = false

    /** 启动代际号：停止或重启时递增，用于作废在途的端口绑定。 */
    private val startToken = java.util.concurrent.atomic.AtomicInteger(0)

    private var appContext: Context? = null
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 全局命令执行互斥锁：限制并发排队执行，防止多命令争抢底层通道 */
    private val commandMutex = Mutex()

    /** SSE 活跃客户端连接输出流集合 */
    private val sseClients = CopyOnWriteArrayList<PrintWriter>()

    /** 当前监听地址缓存：由启动/重启流程在 IO 线程刷新，界面只读取该状态，避免组合期枚举网卡 */
    private val cachedAddresses = mutableStateOf<List<String>>(emptyList())

    /**
     * 启动 MCP 服务端。
     * 端口绑定与地址枚举属于阻塞操作，统一在 IO 协程中执行，调用方（含主线程）立即返回。
     *
     * @return true 表示启动请求已受理；实际监听结果通过 [isRunning] 与日志体现
     */
    @Synchronized
    fun start(context: Context? = null): Boolean {
        if (context != null) {
            appContext = context.applicationContext
        }
        if (isRunning.value) return true
        if (isStarting) return false

        // 允许局域网访问但未启用鉴权时，仅作提示并继续按用户选择启动
        if (Prefs.mcpAllowLan && !Prefs.mcpAuthEnabled) {
            AdbManager.log(I18n.current.logMcpLanNoAuth)
            AdbManager.debugLog("[MCP] 安全提示: allowLan=true 且 authEnabled=false")
        }

        isStarting = true
        val token = startToken.incrementAndGet()
        scope.launch {
            val port = Prefs.mcpPort
            val allowLan = Prefs.mcpAllowLan
            try {
                val bindAddress = if (allowLan) {
                    InetAddress.getByName("0.0.0.0")
                } else {
                    InetAddress.getByName("127.0.0.1")
                }

                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(bindAddress, port))

                // 绑定期间可能已执行停止或重新启动，此时不得提交运行状态
                if (startToken.get() != token) {
                    isStarting = false
                    try {
                        socket.close()
                    } catch (e: Exception) {
                        AdbManager.debugLog("[MCP] 作废的监听端口关闭失败: ${e.javaClass.simpleName}: ${e.message}")
                    }
                    return@launch
                }

                serverSocket = socket
                isRunning.value = true
                isStarting = false

                serverJob = scope.launch {
                    while (isActive && !socket.isClosed) {
                        try {
                            val client = socket.accept()
                            launch { handleClientSocket(client) }
                        } catch (e: Exception) {
                            AdbManager.debugLog("[MCP] accept 循环结束: ${e.javaClass.simpleName}: ${e.message}")
                            break
                        }
                    }
                }

                val addrs = computeListeningAddresses()
                cachedAddresses.value = addrs
                AdbManager.log(String.format(I18n.current.logMcpStarted, addrs.firstOrNull() ?: ""))
                AdbManager.debugLog("[MCP] ServerSocket 成功监听: ${addrs.joinToString(", ")}")

                appContext?.let { ctx ->
                    McpNotificationHelper.showMcpRunningNotification(ctx, addrs)
                }
            } catch (e: Exception) {
                isStarting = false
                AdbManager.log(String.format(I18n.current.logMcpStartFailed, port, e.localizedMessage ?: e.message ?: ""))
                AdbManager.debugLog("[MCP] ServerSocket 启动异常: ${e.stackTraceToString()}")
                // 启动失败时同步回落开关状态，避免界面显示为已启用
                Prefs.mcpEnabled = false
                stop()
            }
        }
        return true
    }

    /**
     * 停止 MCP 服务端。
     */
    @Synchronized
    fun stop() {
        if (isRunning.value) {
            AdbManager.log(I18n.current.logMcpStopped)
            AdbManager.debugLog("[MCP] 服务及连接已释放")
        }
        isRunning.value = false
        isStarting = false
        startToken.incrementAndGet()
        cachedAddresses.value = emptyList()
        serverJob?.cancel()
        serverJob = null

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        // 清理所有已连接的 SSE 客户端
        sseClients.forEach {
            try { it.close() } catch (_: Exception) {}
        }
        sseClients.clear()

        McpOutputCache.clearSession()

        appContext?.let { ctx ->
            McpNotificationHelper.cancelNotification(ctx)
        }
    }

    /**
     * 重启 MCP 服务端。
     */
    @Synchronized
    fun restart(context: Context? = null): Boolean {
        if (context != null) {
            appContext = context.applicationContext
        }
        stop()
        return start(context)
    }

    /**
     * 获取当前服务监听的所有可用访问地址列表。
     * 直接返回启动流程中缓存的快照，不做网卡枚举，可在组合阶段安全调用。
     */
    fun getListeningAddresses(): List<String> = cachedAddresses.value

    /**
     * 在后台重新枚举并刷新访问地址缓存。
     * 供界面在局域网开关变化后调用，避免在组合阶段执行网卡枚举。
     */
    fun refreshListeningAddresses() {
        if (!isRunning.value) {
            cachedAddresses.value = emptyList()
            return
        }
        scope.launch {
            cachedAddresses.value = computeListeningAddresses()
        }
    }

    /**
     * 枚举当前可用的访问地址（含局域网 IP）。
     * 会遍历全部网络接口，属于阻塞操作，只能在后台线程或协程中调用。
     */
    private fun computeListeningAddresses(): List<String> {
        val port = Prefs.mcpPort
        val addresses = mutableListOf<String>()
        addresses.add("http://127.0.0.1:$port")

        if (Prefs.mcpAllowLan) {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val networkInterface = interfaces.nextElement()
                    if (!networkInterface.isUp || networkInterface.isLoopback) continue

                    val inetAddresses = networkInterface.inetAddresses
                    while (inetAddresses.hasMoreElements()) {
                        val addr = inetAddresses.nextElement()
                        if (!addr.isLoopbackAddress && addr.hostAddress?.contains(':') == false) {
                            val ip = addr.hostAddress ?: continue
                            val url = "http://$ip:$port"
                            if (!addresses.contains(url)) {
                                addresses.add(url)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                AdbManager.debugLog("[MCP] 网卡地址枚举失败，已跳过该网卡: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        return addresses
    }

    /**
     * 处理单次客户端 Socket 请求（支持 HTTP GET, POST 与 OPTIONS）。
     */
    /** 单次请求读取与保活空闲等待的超时（毫秒）。 */
    private const val SOCKET_READ_TIMEOUT_MS = 15_000
    /** HTTP 请求行与单个头部行的最大字节数。 */
    private const val MAX_HEADER_LINE_BYTES = 8 * 1024
    /** 单个请求允许的头部行数上限。 */
    private const val MAX_HEADER_LINES = 100
    /** 单次请求体最大字节数。 */
    private const val MAX_BODY_BYTES = 5 * 1024 * 1024

    /**
     * 已解析的 HTTP 请求元数据。
     */
    private class HttpRequest(
        val method: String,
        val path: String,
        val accept: String,
        val authorization: String,
        val origin: String,
        val contentLength: Int,
        val keepAlive: Boolean
    )

    /**
     * 从输入流读取一行 ASCII 文本（以 LF 结束，自动去除 CR）。
     * 超过单行长度上限或流已结束时返回 null。
     */
    private fun readAsciiLine(input: InputStream): String? {
        val sb = StringBuilder(128)
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') sb.setLength(sb.length - 1)
                return sb.toString()
            }
            if (sb.length >= MAX_HEADER_LINE_BYTES) return null
            sb.append(b.toChar())
        }
    }

    /**
     * 读取指定长度的请求体。
     * 长度不足表示连接中断，返回 null。
     */
    private fun readBody(input: InputStream, length: Int): ByteArray? {
        if (length == 0) return ByteArray(0)
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(buffer, offset, length - offset)
            if (n < 0) return null
            offset += n
        }
        return buffer
    }

    /**
     * 解析请求行与请求头，返回 null 表示连接结束或请求不合法。
     * HTTP/1.1 默认复用连接，可通过 Connection 头显式覆盖。
     */
    private fun readHttpRequest(input: InputStream): HttpRequest? {
        var requestLine = readAsciiLine(input) ?: return null
        var blankGuard = 0
        while (requestLine.isEmpty()) {
            if (++blankGuard > 4) return null
            requestLine = readAsciiLine(input) ?: return null
        }
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        val version = parts.getOrNull(2) ?: "HTTP/1.0"

        var accept = ""
        var authorization = ""
        var origin = ""
        var contentLengthRaw = "0"
        var connectionHeader = ""
        var lineCount = 0
        while (true) {
            val line = readAsciiLine(input) ?: return null
            if (line.isEmpty()) break
            if (++lineCount > MAX_HEADER_LINES) return null
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val name = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            when (name) {
                "content-length" -> contentLengthRaw = value
                "accept" -> accept = value
                "authorization" -> authorization = value
                "origin" -> origin = value
                "connection" -> connectionHeader = value
            }
        }
        val contentLength = contentLengthRaw.toIntOrNull() ?: return null
        val keepAlive = when {
            connectionHeader.contains("close", ignoreCase = true) -> false
            connectionHeader.contains("keep-alive", ignoreCase = true) -> true
            else -> version.equals("HTTP/1.1", ignoreCase = true)
        }
        return HttpRequest(
            method = parts[0].uppercase(),
            path = parts[1].substringBefore("?"),
            accept = accept,
            authorization = authorization,
            origin = origin,
            contentLength = contentLength,
            keepAlive = keepAlive
        )
    }

    /**
     * 处理单次客户端 Socket 连接。
     * 在同一连接上循环处理请求（HTTP keep-alive），SSE 等长连接在单次响应后关闭。
     */
    private suspend fun handleClientSocket(socket: Socket) {
        // 开启 TCP_NODELAY，禁用 Nagle 算法，消除小包网络延迟
        socket.tcpNoDelay = true
        // 读取与保活空闲等待共用该超时，防止挂起连接长期占用线程
        socket.soTimeout = SOCKET_READ_TIMEOUT_MS

        socket.use { s ->
            val input = BufferedInputStream(s.getInputStream(), 16 * 1024)
            val printWriter = PrintWriter(
                BufferedWriter(OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), 8 * 1024),
                false
            )
            while (true) {
                val request = try {
                    readHttpRequest(input)
                } catch (e: java.net.SocketTimeoutException) {
                    return@use
                } catch (e: Exception) {
                    AdbManager.debugLog("[MCP] 请求解析异常: ${e.javaClass.simpleName}: ${e.message}")
                    return@use
                } ?: return@use

                AdbManager.debugLog("[MCP] 收到请求: ${request.method} ${request.path}")
                val reusable = try {
                    handleHttpRequest(request, input, printWriter, s)
                } catch (e: Exception) {
                    AdbManager.debugLog("[MCP] 请求处理异常: ${e.javaClass.simpleName}: ${e.message}")
                    false
                }
                printWriter.flush()
                if (!reusable) return@use
            }
        }
    }

    /**
     * 处理一条已解析的 HTTP 请求。
     * 返回 true 表示连接可继续复用，false 表示响应完成后需要关闭连接。
     */
    private suspend fun handleHttpRequest(
        request: HttpRequest,
        input: InputStream,
        out: PrintWriter,
        socket: Socket
    ): Boolean {
        val method = request.method
        val path = request.path
        val keepAlive = request.keepAlive
        // 未消费请求体时不能复用连接，否则残留字节会被当作下一条请求解析
        val reusableWhenBodyEmpty = keepAlive && request.contentLength == 0

        // 1. OPTIONS 预检请求直接放行并附带 CORS 标头
        if (method == "OPTIONS") {
            sendHttpOptionsResponse(out, keepAlive)
            return reusableWhenBodyEmpty
        }

        // 2. 防御 DNS Rebinding：未开启 Token 鉴权时拦截非受信任来源
        if (!Prefs.mcpAuthEnabled && request.origin.isNotEmpty()) {
            val isLocalOrigin = McpOriginGuard.isTrustedLocalOrigin(request.origin)
            if (!isLocalOrigin && !Prefs.mcpAllowLan) {
                AdbManager.debugLog("[MCP] 安全拦截非本地 Origin 请求: ${request.origin}")
                sendHttpResponse(out, 403, "Forbidden", "text/plain", "Cross-origin request blocked by security rule", keepAlive)
                return reusableWhenBodyEmpty
            }
        }

        // 3. 标准 Bearer 鉴权校验（基于 MCP 官方 OAuth 2.1 / RFC 6750 规范）
        if (Prefs.mcpAuthEnabled) {
            if (!isValidCredential(request.authorization, Prefs.mcpAuthToken)) {
                AdbManager.debugLog("[MCP] 鉴权失败: 凭据无效 (path: $path)")
                sendHttpUnauthorizedResponse(out, keepAlive)
                return reusableWhenBodyEmpty
            }
        }

        if (method == "GET") {
            val isSseRequest = request.accept.contains("text/event-stream") || path == "/sse" ||
                (path == "/mcp" && request.accept.contains("text/event-stream"))
            if (isSseRequest) {
                if (!Prefs.mcpEnableSse) {
                    AdbManager.debugLog("[MCP] SSE 请求被拒: 当前已关闭 SSE 模式 (path: $path)")
                    sendHttpResponse(out, 403, "Forbidden", "text/plain", "SSE mode is disabled in settings", keepAlive)
                    return reusableWhenBodyEmpty
                }
                // SSE 为长连接流式响应，不参与请求复用
                handleGetRequest(path, request.accept, out, socket)
                return false
            }
            val info = "aoooa-adb MCP Server is running.\n" +
                "HTTP Mode: ${if (Prefs.mcpEnableHttp) "Enabled" else "Disabled"}\n" +
                "SSE Mode: ${if (Prefs.mcpEnableSse) "Enabled" else "Disabled"}\n" +
                "Endpoints: /, /mcp, /sse"
            sendHttpResponse(out, 200, "OK", "text/plain", info, keepAlive)
            return reusableWhenBodyEmpty
        }

        if (method == "POST") {
            val isSseMessagePost = path == "/messages" || (sseClients.isNotEmpty() && Prefs.mcpEnableSse)
            if (!isSseMessagePost && !Prefs.mcpEnableHttp) {
                AdbManager.debugLog("[MCP] HTTP 请求被拒: 当前已关闭独立 HTTP 模式 (path: $path)")
                sendHttpResponse(out, 403, "Forbidden", "text/plain", "HTTP mode is disabled in settings", keepAlive)
                return reusableWhenBodyEmpty
            }
            if (isSseMessagePost && !Prefs.mcpEnableSse && !Prefs.mcpEnableHttp) {
                AdbManager.debugLog("[MCP] POST 请求被拒: 通信模式均已停用 (path: $path)")
                sendHttpResponse(out, 403, "Forbidden", "text/plain", "All communication modes are disabled", keepAlive)
                return reusableWhenBodyEmpty
            }

            // 请求体超限时未消费字节流，连接不可复用
            if (request.contentLength < 0 || request.contentLength > MAX_BODY_BYTES) {
                sendHttpResponse(out, 413, "Payload Too Large", "text/plain", "Payload Too Large", false)
                return false
            }

            val bodyBytes = readBody(input, request.contentLength) ?: return false
            val body = String(bodyBytes, StandardCharsets.UTF_8)
            val responseJson = handleJsonRpcRequest(body)

            if (responseJson.isEmpty()) {
                // JSON-RPC 通知或无需返回内容的空响应，依据 MCP 规范回传 202 Accepted 且无 body
                sendHttpResponse(out, 202, "Accepted", "text/plain", "", keepAlive)
            } else {
                sendHttpResponse(out, 200, "OK", "application/json", responseJson, keepAlive)
            }

            // 仅在有活跃 SSE 客户端挂载时，在后台异步广播消息
            if (sseClients.isNotEmpty() && Prefs.mcpEnableSse) {
                scope.launch(Dispatchers.IO) {
                    broadcastSseMessage(responseJson)
                }
            }
            return keepAlive
        }

        sendHttpResponse(out, 405, "Method Not Allowed", "text/plain", "Method Not Allowed", keepAlive)
        return reusableWhenBodyEmpty
    }

    /**
     * 校验 Bearer 凭据：
     * 1. 兼容 "Bearer &lt;token&gt;" 与裸 token 两种携带方式；
     * 2. 采用常数时间比较，避免通过响应耗时逐字节猜测凭据。
     */
    private fun isValidCredential(authorization: String, expectedToken: String): Boolean {
        if (expectedToken.isEmpty()) return false
        val provided = if (authorization.startsWith("Bearer ", ignoreCase = true)) {
            authorization.substring(7).trim()
        } else {
            authorization.trim()
        }
        if (provided.isEmpty()) return false
        return java.security.MessageDigest.isEqual(
            provided.toByteArray(StandardCharsets.UTF_8),
            expectedToken.toByteArray(StandardCharsets.UTF_8)
        )
    }

    /**
     * 处理 GET 请求：支持 SSE 事件流建立与普通状态探测。
     */
    private suspend fun handleGetRequest(path: String, accept: String, out: PrintWriter, socket: Socket) {
        val isSseRequest = accept.contains("text/event-stream") || path == "/sse" || (path == "/mcp" && accept.contains("text/event-stream"))
        if (!isSseRequest) return

        val maxClients = Prefs.mcpMaxSseClients.coerceAtLeast(1)
        if (sseClients.size >= maxClients) {
            AdbManager.debugLog("[MCP] SSE 连接超限: 当前已达上限 ($maxClients)")
            sendHttpResponse(out, 429, "Too Many Requests", "text/plain", "Too Many Requests: Maximum SSE clients reached ($maxClients)")
            return
        }

        // SSE 长连接取消输入流读取超时，由心跳与错误检测管理连接生命周期
        socket.soTimeout = 0

        out.print("HTTP/1.1 200 OK\r\n")
        out.print("Content-Type: text/event-stream; charset=utf-8\r\n")
        out.print("Cache-Control: no-cache\r\n")
        out.print("Connection: keep-alive\r\n")
        out.print("Access-Control-Allow-Origin: *\r\n\r\n")
        out.flush()

        // 发送 MCP 协议端点重定向事件 (兼容 2024-11-05 协议)
        out.print("event: endpoint\r\ndata: /messages\r\n\r\n")
        out.flush()

        sseClients.add(out)
        AdbManager.debugLog("[MCP] SSE 客户端建立连接成功 (当前活动连接数: ${sseClients.size})")

        try {
            // 维持长连接保活心跳与对端断开检测
            while (isRunning.value && !socket.isClosed) {
                delay(15000)
                synchronized(out) {
                    out.print(": ping\r\n\r\n")
                    out.flush()
                }
                if (out.checkError()) break
            }
        } catch (e: Exception) {
            AdbManager.debugLog("[MCP] SSE 连接中断: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            sseClients.remove(out)
            AdbManager.debugLog("[MCP] SSE 客户端断开连接 (当前活动连接数: ${sseClients.size})")
        }
    }

    /**
     * 向所有已连接的 SSE 客户端广播 JSON-RPC 消息。
     */
    private fun broadcastSseMessage(jsonText: String) {
        if (sseClients.isEmpty() || jsonText.isBlank()) return
        val event = "event: message\r\ndata: $jsonText\r\n\r\n"
        val deadClients = mutableListOf<PrintWriter>()
        sseClients.forEach { client ->
            try {
                synchronized(client) {
                    client.print(event)
                    client.flush()
                }
                if (client.checkError()) {
                    deadClients.add(client)
                }
            } catch (e: Exception) {
                AdbManager.debugLog("[MCP] SSE 广播写入失败，移除该客户端: ${e.javaClass.simpleName}: ${e.message}")
                deadClients.add(client)
            }
        }
        if (deadClients.isNotEmpty()) {
            sseClients.removeAll(deadClients)
        }
    }

    /**
     * 发送 401 未授权响应（附带标准 WWW-Authenticate 标头）。
     */
    private fun sendHttpUnauthorizedResponse(out: PrintWriter, keepAlive: Boolean = false) {
        val body = "Unauthorized: Invalid or missing Bearer token.\r\n"
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        out.print("HTTP/1.1 401 Unauthorized\r\n")
        out.print("Content-Type: text/plain; charset=utf-8\r\n")
        out.print("WWW-Authenticate: Bearer realm=\"aoooa-adb-mcp\"\r\n")
        out.print("Content-Length: ${bytes.size}\r\n")
        out.print("Access-Control-Allow-Origin: *\r\n")
        out.print("Access-Control-Allow-Headers: Content-Type, Accept, Authorization\r\n")
        out.print("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        out.print(if (keepAlive) "Connection: keep-alive\r\n\r\n" else "Connection: close\r\n\r\n")
        out.print(body)
        out.flush()
    }

    /**
     * 发送标准 HTTP 响应。
     *
     * @param keepAlive 为 true 时声明连接复用，避免客户端为每次调用重新建立 TCP 连接
     */
    private fun sendHttpResponse(
        out: PrintWriter,
        status: Int,
        statusText: String,
        contentType: String,
        body: String,
        keepAlive: Boolean = false
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        out.print("HTTP/1.1 $status $statusText\r\n")
        out.print("Content-Type: $contentType; charset=utf-8\r\n")
        out.print("Content-Length: ${bytes.size}\r\n")
        out.print("Access-Control-Allow-Origin: *\r\n")
        out.print("Access-Control-Allow-Headers: Content-Type, Accept, Authorization\r\n")
        out.print("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        out.print(if (keepAlive) "Connection: keep-alive\r\n\r\n" else "Connection: close\r\n\r\n")
        out.print(body)
        out.flush()
    }

    /**
     * 发送 CORS 预检响应。
     */
    private fun sendHttpOptionsResponse(out: PrintWriter, keepAlive: Boolean = false) {
        out.print("HTTP/1.1 204 No Content\r\n")
        out.print("Access-Control-Allow-Origin: *\r\n")
        out.print("Access-Control-Allow-Headers: Content-Type, Accept, Authorization\r\n")
        out.print("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        out.print(if (keepAlive) "Connection: keep-alive\r\n\r\n" else "Connection: close\r\n\r\n")
        out.flush()
    }

    /**
     * JSON-RPC 消息调度器：处理 initialize, tools/list 与 tools/call 请求。
     */
    private suspend fun handleJsonRpcRequest(body: String): String {
        return try {
            val json = JSONObject(body)
            val id = json.opt("id")
            val method = json.optString("method", "")

            when (method) {
                "initialize" -> {
                    val result = JSONObject().apply {
                        put("protocolVersion", "2024-11-05")
                        put("capabilities", JSONObject().apply {
                            put("tools", JSONObject().apply {
                                put("listChanged", false)
                            })
                        })
                        put("serverInfo", JSONObject().apply {
                            put("name", "aoooa-adb-mcp")
                            put("version", "1.0.0")
                        })
                    }
                    createJsonRpcResponse(id, result).toString()
                }
                "notifications/initialized" -> {
                    // 客户端就绪确认通知，无需返回数据体
                    ""
                }
                "tools/list" -> {
                    val toolsArray = JSONArray().apply {
                        put(buildShellToolDefinition())
                        put(buildRulesToolDefinition())
                    }
                    val result = JSONObject().apply {
                        put("tools", toolsArray)
                    }
                    createJsonRpcResponse(id, result).toString()
                }
                "tools/call" -> {
                    val params = json.optJSONObject("params") ?: JSONObject()
                    val toolName = params.optString("name", "")
                    val args = params.optJSONObject("arguments") ?: JSONObject()

                    val toolResult = when (toolName) {
                        "execute_shell_command" -> handleExecuteShellTool(args)
                        "list_security_rules" -> handleListRulesTool()
                        else -> ToolExecutionResult(McpStrings.shellUnknownTool(toolName), isError = true)
                    }

                    val result = JSONObject().apply {
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", toolResult.text)
                            })
                        })
                        put("isError", toolResult.isError)
                    }
                    createJsonRpcResponse(id, result).toString()
                }
                "ping" -> {
                    createJsonRpcResponse(id, JSONObject()).toString()
                }
                else -> {
                    createJsonRpcError(id, -32601, "Method not found: $method").toString()
                }
            }
        } catch (e: Exception) {
            createJsonRpcError(null, -32700, "Parse error: ${e.message}").toString()
        }
    }

    /**
     * 构建 execute_shell_command 工具元数据定义。
     */
    private fun buildShellToolDefinition(): JSONObject {
        return JSONObject().apply {
            put("name", "execute_shell_command")
            put("description", McpStrings.shellToolDesc)
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("command", JSONObject().apply {
                        put("type", "string")
                        put("description", McpStrings.shellParamCommand)
                    })
                    put("page", JSONObject().apply {
                        put("type", "integer")
                        put("description", McpStrings.shellParamPage)
                    })
                })
                put("required", JSONArray().apply {
                    put("command")
                })
            })
        }
    }

    /**
     * 构建 list_security_rules 工具元数据定义。
     */
    private fun buildRulesToolDefinition(): JSONObject {
        return JSONObject().apply {
            put("name", "list_security_rules")
            put("description", McpStrings.rulesToolDesc)
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            })
        }
    }

    /**
     * 工具执行结果承载模型：分离响应文本与错误状态位。
     */
    private data class ToolExecutionResult(
        val text: String,
        val isError: Boolean = false
    )

    /**
     * 处理安全拦截规则列表查询工具调用。
     */
    private fun handleListRulesTool(): ToolExecutionResult {
        return ToolExecutionResult(
            text = McpRuleEngine.getRulesSummaryJson().toString(2),
            isError = false
        )
    }

    /**
     * 处理 Shell 工具调用：包含状态预检、排队互斥锁、规则拦截、底层复用与长输出分页。
     */
    private suspend fun handleExecuteShellTool(args: JSONObject): ToolExecutionResult {
        val page = args.optInt("page", 1)

        // 若直接请求第 2 页及以后的数据，优先读取缓存
        if (page > 1) {
            val cachedPage = McpOutputCache.readPage(page)
            if (cachedPage != null) {
                return ToolExecutionResult(cachedPage, isError = false)
            }
            return ToolExecutionResult(McpStrings.shellCacheExpired, isError = true)
        }

        val command = args.optString("command", "").trim()
        if (command.isEmpty()) {
            return ToolExecutionResult(McpStrings.shellEmptyCmd, isError = true)
        }

        // 1. 安全规则引擎检测
        val check = McpRuleEngine.check(command)
        if (!check.allowed) {
            AdbManager.log(String.format(I18n.current.logMcpCmdBlocked, maskSensitiveArgs(command)))
            AdbManager.debugLog("[MCP] 命中规则: ${check.matchedRule?.pattern}, 说明: ${check.reason}")
            return ToolExecutionResult(McpStrings.shellBlocked(check.reason), isError = true)
        }

        // 2. 连接状态前置毫秒级预检：未连接或未授权立即秒拒，避免底层 Socket 挂起阻塞
        val activeConn = AdbManager.connection
        if (activeConn == null || !activeConn.isAuthenticated) {
            AdbManager.debugLog("[MCP] 执行失败: ADB 设备未连接或未授权")
            return ToolExecutionResult(McpStrings.shellNoDevice, isError = true)
        }

        // 3. 协程互斥锁排队执行：防止多模型批量并发打崩底层连接
        return commandMutex.withLock {
            val conn = AdbManager.connection
            if (conn == null || !conn.isAuthenticated) {
                AdbManager.debugLog("[MCP] 执行失败: ADB 设备未连接或授权已失效")
                return@withLock ToolExecutionResult(McpStrings.shellNoDevice, isError = true)
            }

            try {
                AdbManager.log(String.format(I18n.current.logMcpCmdExec, maskSensitiveArgs(command)))
                val startMs = System.currentTimeMillis()
                // 4. 复用现有底层非交互式 shell 通道
                val output = conn.shell(command)
                val cost = System.currentTimeMillis() - startMs
                AdbManager.debugLog("[MCP] 执行完成 (耗时 ${cost}ms, 输出 ${output.length} 字符)")

                // 5. 长输出分页判定与缓存登记
                val (finalText, _) = McpOutputCache.registerOutput(command, output, pageSize = 120)
                ToolExecutionResult(finalText, isError = false)
            } catch (e: Exception) {
                AdbManager.debugLog("[MCP] 执行异常: ${e.message}")
                ToolExecutionResult(String.format(I18n.current.mcpExecError, e.message ?: ""), isError = true)
            }
        }
    }

    /** 执行日志中需要脱敏的参数名（兼容 "-x value" 与 "-x=value" 写法）。 */
    private val SENSITIVE_ARG_PATTERN = Regex(
        "(?i)((?:--?|\\s)(?:password|passwd|pwd|token|secret|api[-_]?key|auth[-_]?key|psk)(?:=|\\s+))" +
            "(\"[^\"]*\"|'[^']*'|[^\\s;]+)"
    )

    /**
     * 对执行日志中的命令做敏感参数脱敏。
     * 只替换密钥类参数的值，命令主体保持原样以便审计排查。
     */
    private fun maskSensitiveArgs(command: String): String =
        SENSITIVE_ARG_PATTERN.replace(command) { match -> match.groupValues[1] + "***" }

    private fun createJsonRpcResponse(id: Any?, result: JSONObject): JSONObject {
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id ?: JSONObject.NULL)
            put("result", result)
        }
    }

    private fun createJsonRpcError(id: Any?, code: Int, message: String): JSONObject {
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id ?: JSONObject.NULL)
            put("error", JSONObject().apply {
                put("code", code)
                put("message", message)
            })
        }
    }
}
