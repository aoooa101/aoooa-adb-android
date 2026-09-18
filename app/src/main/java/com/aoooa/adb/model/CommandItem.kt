package com.aoooa.adb.model

/**
 * 快捷命令数据实体
 */
data class CommandItem(
    val id: String,
    val nameZh: String,
    val nameEn: String,
    val command: String,
    val category: String = "framework", // framework / system / power / fastboot / custom
    val isBuiltin: Boolean = false
)

private val lineIdGenerator = java.util.concurrent.atomic.AtomicLong(1L)

/**
 * 终端行数据模型，携带唯一 ID 供列表渲染
 */
data class TerminalLine(
    val id: Long = lineIdGenerator.getAndIncrement(),
    val text: String
)

