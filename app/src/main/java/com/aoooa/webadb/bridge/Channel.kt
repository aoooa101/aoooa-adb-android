package com.aoooa.webadb.bridge

/**
 * ADB 传输通道抽象。
 * 由原生传输层实现（USB / TCP），负责 ADB 底层原始数据包的收发。
 */
interface Channel {
    /** 向设备写入字节（ADB 报文）。 */
    fun send(data: ByteArray): Boolean

    /** 关闭通道，释放资源。 */
    fun close()
}
