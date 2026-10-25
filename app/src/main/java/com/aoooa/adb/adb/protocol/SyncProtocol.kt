package com.aoooa.adb.adb.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AOSP sync: 协议请求报文构造。
 *
 * 报文格式：[4 字节 magic（ASCII）][4 字节长度（小端）] + 数据。
 * SEND / RECV 的长度字段为路径长度，DATA 为数据块长度，
 * DONE 的长度字段复用为发送方时间戳（秒）。
 */
object SyncProtocol {

    /** 推送请求，声明远端路径与权限。 */
    const val MAGIC_SEND = "SEND"

    /** 拉取请求。 */
    const val MAGIC_RECV = "RECV"

    /** 推送数据块。 */
    const val MAGIC_DATA = "DATA"

    /** 结束标识，可携带失败信息。 */
    const val MAGIC_DONE = "DONE"

    /** 失败响应。 */
    const val MAGIC_FAIL = "FAIL"

    /** sync: 服务成功响应。 */
    const val MAGIC_OKAY = "OKAY"

    /** sync: 服务流名称。 */
    const val SERVICE = "sync:\u0000"

    /** sync: 服务流 OPEN 报文 payload（结尾带 NUL，调用方直接使用）。 */
    val servicePayload: ByteArray get() = SERVICE.toByteArray(Charsets.UTF_8)

    /**
     * 构造 SEND 请求。
     * @param remotePath 远端路径，含权限后缀，如 "/data/local/tmp/a.apk,33206"
     */
    fun sendRequest(remotePath: String): ByteArray = withMagic(MAGIC_SEND, remotePath.toByteArray(Charsets.UTF_8))

    /** 构造 RECV 请求。 */
    fun recvRequest(remotePath: String): ByteArray = withMagic(MAGIC_RECV, remotePath.toByteArray(Charsets.UTF_8))

    /**
     * 构造 DATA 数据块。
     * @param data 源字节数组
     * @param offset 起始下标
     * @param length 块长度，协议上限 65536 字节
     */
    fun dataChunk(data: ByteArray, offset: Int, length: Int): ByteArray {
        val bb = ByteBuffer.allocate(8 + length).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(MAGIC_DATA.toByteArray(Charsets.US_ASCII))
        bb.putInt(length)
        bb.put(data, offset, length)
        return bb.array()
    }

    /** 构造 DONE 结束报文，长度字段写入时间戳（秒）。 */
    fun done(timestampSec: Long = System.currentTimeMillis() / 1000): ByteArray {
        val bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(MAGIC_DONE.toByteArray(Charsets.US_ASCII))
        bb.putInt(timestampSec.toInt())
        return bb.array()
    }

    private fun withMagic(magic: String, payload: ByteArray): ByteArray {
        val bb = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(magic.toByteArray(Charsets.US_ASCII))
        bb.putInt(payload.size)
        bb.put(payload)
        return bb.array()
    }
}
