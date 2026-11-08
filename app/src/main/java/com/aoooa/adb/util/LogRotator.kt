package com.aoooa.adb.util

import android.content.Context
import java.io.File

/**
 * 本地日志文件的命名规约、占用统计与自动淘汰工具。
 *
 * 淘汰规则：
 * 1. 存活期：最后修改时间早于 [MAX_LOG_ALIVE_MS] 的文件删除；
 * 2. 容量：全部日志目录合并统计，超出 [MAX_TOTAL_BYTES] 时按最后修改时间由旧到新删除。
 *
 * 统计与淘汰共用 [isManagedLogFile] 筛选条件，界面展示的占用与实际可回收空间保持一致。
 * 正在写入的文件不参与删除，其体积计入容量上限。
 */
object LogRotator {

    private const val LOG_PREFIX = "aoooa-adb_"
    private const val LOG_SUFFIX = ".log"
    private const val MAX_LOG_ALIVE_MS = 7L * 24 * 60 * 60 * 1000L // 7 天
    private const val MAX_TOTAL_BYTES = 20L * 1024 * 1024L // 20 MB

    /** 日志目录候选：外部私有目录优先，内部私有目录作为回退。 */
    private fun logDirs(context: Context): List<File> = listOfNotNull(
        context.getExternalFilesDir("logs"),
        File(context.filesDir, "logs")
    )

    /** 是否为本模块写入的日志文件（与写入侧命名规约一致）。 */
    fun isManagedLogFile(file: File): Boolean =
        file.isFile && file.name.startsWith(LOG_PREFIX) && file.name.endsWith(LOG_SUFFIX)

    /**
     * 列出全部日志目录中由本模块写入的日志文件。
     * 单个目录枚举失败时跳过该目录，不影响其余目录的结果。
     */
    fun logFiles(context: Context): List<File> {
        val result = ArrayList<File>()
        for (dir in logDirs(context)) {
            if (!dir.exists() || !dir.isDirectory) continue
            val files = try {
                dir.listFiles()
            } catch (e: Exception) {
                null
            } ?: continue
            for (file in files) {
                if (isManagedLogFile(file)) result.add(file)
            }
        }
        return result
    }

    /** 统计受管日志文件的总体积（字节）。 */
    fun totalSize(context: Context): Long = logFiles(context).sumOf { it.length() }

    /**
     * 执行超期与超量淘汰。
     *
     * @param currentLogFile 正在写入的文件，不参与删除
     * @param logger 淘汰结果与异常的记录回调
     */
    fun rotate(context: Context, currentLogFile: File? = null, logger: (String) -> Unit = {}) {
        val now = System.currentTimeMillis()
        val current = currentLogFile

        val candidates = ArrayList<File>()
        try {
            for (file in logFiles(context)) {
                if (current != null && file.absolutePath == current.absolutePath) continue
                candidates.add(file)
            }
        } catch (e: Exception) {
            logger("[LogRotator] 日志目录枚举失败: ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        if (candidates.isEmpty()) return

        // 1. 存活期淘汰。lastModified 返回 0 表示时间戳不可读，不参与时间判定，避免误删
        val survivors = ArrayList<File>(candidates.size)
        for (file in candidates) {
            val modified = file.lastModified()
            if (modified > 0 && now - modified > MAX_LOG_ALIVE_MS) {
                if (file.delete()) {
                    logger("[LogRotator] 淘汰超期日志: ${file.name}")
                } else {
                    logger("[LogRotator] 超期日志删除失败: ${file.name}")
                    survivors.add(file)
                }
            } else {
                survivors.add(file)
            }
        }

        // 2. 容量淘汰。正在写入的文件计入总量但不可删除
        var totalBytes = survivors.sumOf { it.length() } + (current?.length() ?: 0L)
        if (totalBytes <= MAX_TOTAL_BYTES) return

        survivors.sortBy { it.lastModified() }
        for (file in survivors) {
            if (totalBytes <= MAX_TOTAL_BYTES) break
            val size = file.length()
            if (file.delete()) {
                totalBytes -= size
                logger("[LogRotator] 容量超限淘汰日志: ${file.name} (${size}B)")
            } else {
                logger("[LogRotator] 容量超限删除失败: ${file.name}")
            }
        }
        if (totalBytes > MAX_TOTAL_BYTES) {
            logger("[LogRotator] 日志总量 ${totalBytes}B 仍超出配额，剩余部分为正在写入的文件")
        }
    }
}
