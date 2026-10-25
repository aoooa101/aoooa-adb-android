package com.aoooa.adb.adb.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AOSP ShellProtocol v2 帧构造。
 *
 * 帧格式：[1 字节帧类型 id][4 字节 payload 长度（小端）][payload]。
 * 该格式用于 `shell,v2,pty` 服务流的 stdin 写入与窗口尺寸同步；
 * 设备返回的 stdout / stderr / exit 帧由接收端解析。
 */
object ShellProtocolV2 {

    /** stdin 数据帧。 */
    const val ID_STDIN = 0

    /** stdout 数据帧。 */
    const val ID_STDOUT = 1

    /** stderr 数据帧。 */
    const val ID_STDERR = 2

    /** 子进程退出帧，payload 首字节为退出码。 */
    const val ID_EXIT = 3

    /** 终端窗口尺寸变更帧。 */
    const val ID_WINDOW_SIZE_CHANGE = 5

    /**
     * 构造一个 SDU 帧。
     * @param id 帧类型，取值见本对象的 ID_* 常量
     * @param payload 帧数据
     */
    fun buildFrame(id: Int, payload: ByteArray): ByteArray {
        val bb = ByteBuffer.allocate(5 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(id.toByte())
        bb.putInt(payload.size)
        bb.put(payload)
        return bb.array()
    }

    /** 构造 stdin 帧。 */
    fun buildStdin(data: ByteArray): ByteArray = buildFrame(ID_STDIN, data)

    /**
     * 构造窗口尺寸变更帧。
     * payload 格式为 "行x列,0x0\0"，用于触发被控端 Shell 提示符回显与终端重排。
     */
    fun buildWindowSize(rows: Int = 24, cols: Int = 80): ByteArray =
        buildFrame(
            ID_WINDOW_SIZE_CHANGE,
            "${rows}x${cols},0x0\u0000".toByteArray(Charsets.UTF_8)
        )
}
