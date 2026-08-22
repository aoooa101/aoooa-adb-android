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
