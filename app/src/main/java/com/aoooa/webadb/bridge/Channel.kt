package com.aoooa.webadb.bridge

/**
 * ADB 传输通道抽象。
 * 由原生层实现（USB / TCP），字节流经 JS 桥喂给网页层 @yume-chan/adb 协议栈。
 */
interface Channel {
    /** 向设备写入字节（ADB 报文）。 */
    fun send(data: ByteArray): Boolean

    /** 关闭通道，释放资源。 */
    fun close()
}
