package com.aoooa.adb.ui.i18n

internal data class TcpConnectStrings(val hint: String, val invalid: String, val recent: String)

private val tcpZh = TcpConnectStrings("输入 IP 或 IP:端口", "请输入有效的地址和端口", "本次运行连接过的地址")
private val tcpEn = TcpConnectStrings("IP or IP:port", "Enter a valid address and port", "Connected addresses in this session")

internal val Strings.tcpConnect: TcpConnectStrings
    get() = if (this === I18n.zh) tcpZh else tcpEn
