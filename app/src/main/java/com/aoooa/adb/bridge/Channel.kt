package com.aoooa.adb.bridge

/**
 * ADB 传输通道接口（USB / TCP），负责底层原始数据包收发。
 */
interface Channel {
    /** 向设备写入字节（ADB 报文）。 */
    fun send(data: ByteArray): Boolean

    /** 关闭通道，释放资源。 */
    fun close()
}
