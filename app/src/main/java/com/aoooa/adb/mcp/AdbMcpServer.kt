package com.aoooa.adb.mcp

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.aoooa.adb.AdbManager
import com.aoooa.adb.Prefs
import com.aoooa.adb.adb.shell
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

    private var appContext: Context? = null
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 全局命令执行互斥锁：限制并发排队执行，防止多命令争抢底层通道 */
    private val commandMutex = Mutex()

    /** SSE 活跃客户端连接输出流集合 */
    private val sseClients = CopyOnWriteArrayList<PrintWriter>()

    /**
     * 启动 MCP 服务端。
     */
    @Synchronized
    fun start(context: Context? = null): Boolean {
        if (context != null) {
            appContext = context.applicationContext
        }
        if (isRunning.value) return true

        return try {
            val allowLan = Prefs.mcpAllowLan
            val port = Prefs.mcpPort

            val bindAddress = if (allowLan) {
                InetAddress.getByName("0.0.0.0")
            } else {
                InetAddress.getByName("127.0.0.1")
            }

            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(bindAddress, port))
            serverSocket = socket
            isRunning.value = true

            serverJob = scope.launch {
                while (isActive && !socket.isClosed) {
                    try {
                        val client = socket.accept()
                        launch { handleClientSocket(client) }
                    } catch (_: Exception) {
                        break
                    }
                }
            }

            val addrs = getListeningAddresses()
            AdbManager.log("[MCP] 服务已启动 (${addrs.firstOrNull() ?: ""})")
            AdbManager.debugLog("[MCP] ServerSocket 成功监听: ${addrs.joinToString(", ")}")

            appContext?.let { ctx ->
                McpNotificationHelper.showMcpRunningNotification(ctx, addrs)
            }

            true
        } catch (e: Exception) {
            AdbManager.log("[MCP] 服务启动失败: 端口 $port 可能被占用 (${e.localizedMessage ?: e.message})")
            AdbManager.debugLog("[MCP] ServerSocket 启动异常: ${e.stackTraceToString()}")
            stop()
            false
        }
    }

    /**
     * 停止 MCP 服务端。
     */
    @Synchronized
    fun stop() {
        if (isRunning.value) {
            AdbManager.log("[MCP] 服务已停止")
            AdbManager.debugLog("[MCP] 服务及连接已释放")
        }
        isRunning.value = false
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
     */
    fun getListeningAddresses(): List<String> {
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
            } catch (_: Exception) {}
        }

        return addresses
    }

    /**
     * 处理单次客户端 Socket 请求（支持 HTTP GET, POST 与 OPTIONS）。
     */
    private suspend fun handleClientSocket(socket: Socket) {
        // 开启 TCP_NODELAY，禁用 Nagle 算法，消除小包网络延迟
        socket.tcpNoDelay = true
        // 慢连接读取超时 15 秒，防止未发完整 HTTP 头的挂起连接占用资源
        socket.soTimeout = 15000

        socket.use { s ->
            val input = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
            val output = OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8)
            val printWriter = PrintWriter(output, true)

            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val rawPath = parts[1]
            val path = rawPath.substringBefore("?")
            AdbManager.debugLog("[MCP] 收到请求: $method $path")

            // 解析 HTTP 头信息
            var contentLength = 0
            var acceptHeader = ""
            var authHeader = ""
            var originHeader = ""
            var line: String? = input.readLine()
            while (!line.isNullOrEmpty()) {
                val lower = line.lowercase()
                if (lower.startsWith("content-length:")) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                } else if (lower.startsWith("accept:")) {
                    acceptHeader = line.substringAfter(":").trim()
                } else if (lower.startsWith("authorization:")) {
                    authHeader = line.substringAfter(":").trim()
                } else if (lower.startsWith("origin:")) {
                    originHeader = line.substringAfter(":").trim()
                }
                line = input.readLine()
            }

            // 1. OPTIONS 预检请求直接放行并附带 CORS 标头
            if (method == "OPTIONS") {
                sendHttpOptionsResponse(printWriter)
                return@use
            }

            // 2. 防御 DNS Rebinding：未开启 Token 鉴权时拦截非受信任来源
            if (!Prefs.mcpAuthEnabled && originHeader.isNotEmpty()) {
                val isLocalOrigin = originHeader.contains("localhost") ||
                    originHeader.contains("127.0.0.1") ||
                    originHeader.startsWith("chrome-extension://") ||
                    originHeader.startsWith("moz-extension://") ||
                    originHeader.startsWith("vscode-")
                if (!isLocalOrigin && !Prefs.mcpAllowLan) {
                    AdbManager.debugLog("[MCP] 安全拦截非本地 Origin 请求: $originHeader")
                    sendHttpResponse(printWriter, 403, "Forbidden", "text/plain", "Cross-origin request blocked by security rule")
                    return@use
                }
            }

            // 3. 标准 Bearer 鉴权校验（基于 MCP 官方 OAuth 2.1 / RFC 6750 规范）
            if (Prefs.mcpAuthEnabled) {
                val expectedToken = Prefs.mcpAuthToken
                val expectedBearer = "Bearer $expectedToken"
                val isValid = authHeader.equals(expectedBearer, ignoreCase = true) ||
                    authHeader.equals(expectedToken, ignoreCase = false)
                if (!isValid) {
                    AdbManager.debugLog("[MCP] 鉴权失败: 凭据无效 (path: $path)")
                    sendHttpUnauthorizedResponse(printWriter)
                    return@use
                }
            }

            if (method == "GET") {
                val isSseRequest = acceptHeader.contains("text/event-stream") || path == "/sse" || (path == "/mcp" && acceptHeader.contains("text/event-stream"))
                if (isSseRequest && !Prefs.mcpEnableSse) {
                    AdbManager.debugLog("[MCP] SSE 请求被拒: 当前已关闭 SSE 模式 (path: $path)")
                    sendHttpResponse(printWriter, 403, "Forbidden", "text/plain", "SSE mode is disabled in settings")
                    return@use
                }
                handleGetRequest(path, acceptHeader, printWriter, s)
            } else if (method == "POST") {
                val isSseMessagePost = path == "/messages" || (sseClients.isNotEmpty() && Prefs.mcpEnableSse)
                if (!isSseMessagePost && !Prefs.mcpEnableHttp) {
                    AdbManager.debugLog("[MCP] HTTP 请求被拒: 当前已关闭独立 HTTP 模式 (path: $path)")
                    sendHttpResponse(printWriter, 403, "Forbidden", "text/plain", "HTTP mode is disabled in settings")
                    return@use
                }
                if (isSseMessagePost && !Prefs.mcpEnableSse && !Prefs.mcpEnableHttp) {
                    AdbManager.debugLog("[MCP] POST 请求被拒: 通信模式均已停用 (path: $path)")
                    sendHttpResponse(printWriter, 403, "Forbidden", "text/plain", "All communication modes are disabled")
                    return@use
                }

                // 限制单次请求体最大长度为 5MB，超出或非法长度返回 413
                val maxPayloadSize = 5 * 1024 * 1024
                if (contentLength < 0 || contentLength > maxPayloadSize) {
                    sendHttpResponse(printWriter, 413, "Payload Too Large", "text/plain", "Payload Too Large")
                    return@use
                }

                val bodyChars = CharArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val read = input.read(bodyChars, readTotal, contentLength - readTotal)
                    if (read == -1) break
                    readTotal += read
                }
                val body = String(bodyChars, 0, readTotal)
                val responseJson = handleJsonRpcRequest(body)

                if (responseJson.isEmpty()) {
                    // JSON-RPC 通知或无需返回内容的空响应，依据 MCP 规范回传 202 Accepted 且无 body
                    sendHttpResponse(printWriter, 202, "Accepted", "text/plain", "")
                } else {
                    // 优先回传当前 HTTP 请求的响应
                    sendHttpResponse(printWriter, 200, "OK", "application/json", responseJson)
                }

                // 仅在有活跃 SSE 客户端挂载时，在后台异步广播消息
                if (sseClients.isNotEmpty() && Prefs.mcpEnableSse) {
                    scope.launch(Dispatchers.IO) {
                        broadcastSseMessage(responseJson)
                    }
                }
            } else {
                sendHttpResponse(printWriter, 405, "Method Not Allowed", "text/plain", "Method Not Allowed")
            }
        }
    }

    /**
     * 处理 GET 请求：支持 SSE 事件流建立与普通状态探测。
     */
    private suspend fun handleGetRequest(path: String, accept: String, out: PrintWriter, socket: Socket) {
        val isSseRequest = accept.contains("text/event-stream") || path == "/sse" || (path == "/mcp" && accept.contains("text/event-stream"))

        if (isSseRequest) {
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
            } catch (_: Exception) {
            } finally {
                sseClients.remove(out)
                AdbManager.debugLog("[MCP] SSE 客户端断开连接 (当前活动连接数: ${sseClients.size})")
            }
        } else {
            val info = "aoooa-adb MCP Server is running.\n" +
                "HTTP Mode: ${if (Prefs.mcpEnableHttp) "Enabled" else "Disabled"}\n" +
                "SSE Mode: ${if (Prefs.mcpEnableSse) "Enabled" else "Disabled"}\n" +
                "Endpoints: /, /mcp, /sse"
            sendHttpResponse(out, 200, "OK", "text/plain", info)
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
            } catch (_: Exception) {
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
    private fun sendHttpUnauthorizedResponse(out: PrintWriter) {
        val body = "Unauthorized: Invalid or missing Bearer token.\r\n"
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        out.print("HTTP/1.1 401 Unauthorized\r\n")
        out.print("Content-Type: text/plain; charset=utf-8\r\n")
        out.print("WWW-Authenticate: Bearer realm=\"aoooa-adb-mcp\"\r\n")
        out.print("Content-Length: ${bytes.size}\r\n")
        out.print("Access-Control-Allow-Origin: *\r\n")
        out.print("Access-Control-Allow-Headers: Content-Type, Accept, Authorization\r\n")
        out.print("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        out.print("Connection: close\r\n\r\n")
        out.print(body)
        out.flush()
    }

    /**
     * 发送标准 HTTP 响应。
     */
    private fun sendHttpResponse(out: PrintWriter, status: Int, statusText: String, contentType: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        out.print("HTTP/1.1 $status $statusText\r\n")
        out.print("Content-Type: $contentType; charset=utf-8\r\n")
        out.print("Content-Length: ${bytes.size}\r\n")
        out.print("Access-Control-Allow-Origin: *\r\n")
        out.print("Access-Control-Allow-Headers: Content-Type, Accept, Authorization\r\n")
        out.print("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        out.print("Connection: close\r\n\r\n")
        out.print(body)
        out.flush()
    }

    /**
     * 发送 CORS 预检响应。
     */
    private fun sendHttpOptionsResponse(out: PrintWriter) {
        out.print("HTTP/1.1 204 No Content\r\n")
        out.print("Access-Control-Allow-Origin: *\r\n")
        out.print("Access-Control-Allow-Headers: Content-Type, Accept, Authorization\r\n")
        out.print("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        out.print("Connection: close\r\n\r\n")
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

                    val toolResult = if (toolName == "execute_shell_command") {
                        handleExecuteShellTool(args)
                    } else {
                        "未知工具: $toolName"
                    }

                    val result = JSONObject().apply {
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", toolResult)
                            })
                        })
                        put("isError", false)
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
            put("description", "在已连接的 Android 设备上执行非交互式 Shell 命令，支持系统管理、状态查询、包管理与日志过滤。输出过长时自动触发分页。")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("command", JSONObject().apply {
                        put("type", "string")
                        put("description", "需要执行的 Shell 命令语句 (如 'dumpsys battery' 或 'logcat -d -t 100')")
                    })
                    put("page", JSONObject().apply {
                        put("type", "integer")
                        put("description", "当上一次输出触发截断分页时，传入目标页码读取后续内容 (默认 1)")
                    })
                })
                put("required", JSONArray().apply {
                    put("command")
                })
            })
        }
    }

    /**
     * 处理 Shell 工具调用：包含排队互斥锁、规则拦截、底层复用与长输出分页。
     */
    private suspend fun handleExecuteShellTool(args: JSONObject): String {
        val page = args.optInt("page", 1)

        // 若直接请求第 2 页及以后的数据，优先读取缓存
        if (page > 1) {
            val cachedPage = McpOutputCache.readPage(page)
            if (cachedPage != null) {
                return cachedPage
            }
            return "[提示] 翻页缓存已过期或已释放，请重新执行该命令。"
        }

        val command = args.optString("command", "").trim()
        if (command.isEmpty()) {
            return "[错误] 命令不能为空。"
        }

        // 1. 安全规则引擎检测
        val check = McpRuleEngine.check(command)
        if (!check.allowed) {
            AdbManager.log("[MCP] 安全拦截高危命令: $command")
            AdbManager.debugLog("[MCP] 命中规则: ${check.matchedRule?.pattern}, 说明: ${check.reason}")
            return "[安全拦截] 命令已被安全规则引擎拦截。\n附加说明: ${check.reason}"
        }

        // 2. 协程互斥锁排队执行：防止多模型批量并发打崩底层连接
        return commandMutex.withLock {
            val conn = AdbManager.connection
            if (conn == null) {
                AdbManager.debugLog("[MCP] 执行失败: ADB 设备未连接")
                return@withLock "[错误] ADB 设备未连接或未授权，请先在 App 首页建立连接。"
            }

            try {
                AdbManager.log("[MCP] 执行命令: $command")
                val startMs = System.currentTimeMillis()
                // 3. 复用现有底层非交互式 shell 通道
                val output = conn.shell(command)
                val cost = System.currentTimeMillis() - startMs
                AdbManager.debugLog("[MCP] 执行完成 (耗时 ${cost}ms, 输出 ${output.length} 字符)")

                // 4. 长输出分页判定与缓存登记
                val (finalText, _) = McpOutputCache.registerOutput(command, output, pageSize = 120)
                finalText
            } catch (e: Exception) {
                AdbManager.debugLog("[MCP] 执行异常: ${e.message}")
                "[执行异常] ${e.message}"
            }
        }
    }

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
