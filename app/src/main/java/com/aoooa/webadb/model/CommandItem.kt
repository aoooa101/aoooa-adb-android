package com.aoooa.webadb.model

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

/**
 * 拥有一维唯一 ID 的终端行数据节点，确保 Compose Diff 列表时准确且保留全部历史
 */
data class TerminalLine(
    val id: Long,
    val text: String
)

