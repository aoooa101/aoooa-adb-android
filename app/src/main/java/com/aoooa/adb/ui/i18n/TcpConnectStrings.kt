package com.aoooa.adb.ui.i18n

internal data class TcpConnectStrings(val hint: String, val invalid: String, val recent: String)

private val tcpZh = TcpConnectStrings("输入 IP，端口可选", "请输入有效的 IP；端口须为 1–65535，IPv6 带端口时请用 [IPv6]:端口", "本次运行连接过的地址")
private val tcpEn = TcpConnectStrings("IP address, optional port", "Enter a valid IP; use port 1–65535 and [IPv6]:port for IPv6 with a port", "Connected addresses in this session")

internal val Strings.tcpConnect: TcpConnectStrings
    get() = if (this === I18n.zh) tcpZh else tcpEn
