package com.aoooa.adb.adb.protocol

/**
 * 流式安装（install-create / install-write / install-commit）命令构造与输出解析。
 *
 * 新设备优先使用 `cmd package`，失败时由调用方回退到 `pm`。
 * install-write 从 stdin 读取 APK 数据，因此服务名以 "\0" 结尾并追加 "-"。
 */
object InstallCommands {

    /** 创建安装会话。 */
    fun create(fileSize: Long): String = "exec:cmd package install-create -r -t -S $fileSize"

    /** 创建安装会话（pm 回退形式）。 */
    fun createFallback(fileSize: Long): String = "exec:pm install-create -r -t -S $fileSize"

    /** 写入单个 APK 分片，数据来自 stdin。 */
    fun write(fileSize: Long, sessionId: Int): String =
        "exec:cmd package install-write -S $fileSize $sessionId base.apk -\u0000"

    /** 提交安装会话。 */
    fun commit(sessionId: Int): String = "exec:cmd package install-commit $sessionId"

    /** 提交安装会话（pm 回退形式）。 */
    fun commitFallback(sessionId: Int): String = "exec:pm install-commit $sessionId"

    /**
     * 从 install-create 输出中解析会话号，输出形如 "Success: created install session [1234]"。
     * @return 会话号；解析失败返回 null
     */
    fun parseSessionId(output: String): Int? =
        Regex("\\[(\\d+)\\]").find(output)?.groupValues?.get(1)?.toIntOrNull()
}
