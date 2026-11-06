package com.aoooa.adb.mcp

import kotlinx.coroutines.*
import java.util.UUID

/**
 * 分页缓存数据块。
 * lines: 完整输出的行集合
 * pageSize: 每页行数限制
 * createdAt: 创建时间戳
 */
data class PagingSession(
    val sessionId: String,
    val command: String,
    val lines: List<String>,
    val pageSize: Int = 120,
    val createdAt: Long = System.currentTimeMillis()
) {
    val totalPages: Int
        get() = if (lines.isEmpty()) 1 else (lines.size + pageSize - 1) / pageSize

    fun getPage(page: Int): List<String> {
        val validPage = page.coerceIn(1, totalPages)
        val fromIndex = (validPage - 1) * pageSize
        val toIndex = (fromIndex + pageSize).coerceAtMost(lines.size)
        return lines.subList(fromIndex, toIndex)
    }

    fun isLastPage(page: Int): Boolean = page >= totalPages
}

/**
 * MCP 长输出单坑位缓存管理器：
 * 1. 仅当输出超出阈值时建立翻页缓存，短输出零内存开销；
 * 2. 单坑位互斥存储，新命令执行立即清理释放旧缓存；
 * 3. 内置 60 秒自动销毁协程定时器，读取至最后一页时即刻主动回收。
 */
object McpOutputCache {

    private const val DEFAULT_PAGE_SIZE = 120
    private const val CACHE_TTL_MS = 60_000L

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    private var activeSession: PagingSession? = null

    @Volatile
    private var expireJob: Job? = null

    private val lock = Any()

    /**
     * 判定输出是否需要分页暂存。
     * 若超出单页上限，将旧缓存剔除并存入新会话，启动 60 秒倒计时自毁。
     */
    fun registerOutput(command: String, output: String, pageSize: Int = DEFAULT_PAGE_SIZE): Pair<String, Boolean> {
        val lines = output.split('\n')
        if (lines.size <= pageSize) {
            // 短输出不触发缓存机制，直接返回原内容
            clearSession()
            return Pair(output, false)
        }

        val sessionId = UUID.randomUUID().toString()
        val session = PagingSession(
            sessionId = sessionId,
            command = command,
            lines = lines,
            pageSize = pageSize
        )

        synchronized(lock) {
            expireJob?.cancel()
            activeSession = session

            // 启动 60 秒超时自毁协程
            expireJob = scope.launch {
                delay(CACHE_TTL_MS)
                synchronized(lock) {
                    if (activeSession?.sessionId == sessionId) {
                        activeSession = null
                    }
                }
            }
        }

        val firstPageContent = session.getPage(1).joinToString("\n")
        val hint = "\n\n[输出已截断: 当前显示第 1/${session.totalPages} 页 (共 ${lines.size} 行)。可使用翻页参数 page=2 查看剩余内容]"
        return Pair(firstPageContent + hint, true)
    }

    /**
     * 读取指定页码内容。
     * 若访问最后一页，则立即主动清理销毁缓存。
     */
    fun readPage(page: Int): String? {
        synchronized(lock) {
            val session = activeSession ?: return null
            val content = session.getPage(page).joinToString("\n")
            val isLast = session.isLastPage(page)

            val result = if (isLast) {
                // 最后一页读取完毕，即刻销毁缓存释放内存
                clearSession()
                "$content\n\n[已读至最后一页，翻页缓存已释放]"
            } else {
                "$content\n\n[当前显示第 $page/${session.totalPages} 页 (共 ${session.lines.size} 行)。可传入 page=${page + 1} 继续查看]"
            }
            return result
        }
    }

    /**
     * 主动清空并取消定时器。
     */
    fun clearSession() {
        synchronized(lock) {
            expireJob?.cancel()
            expireJob = null
            activeSession = null
        }
    }
}
