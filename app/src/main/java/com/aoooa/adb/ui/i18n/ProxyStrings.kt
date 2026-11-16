package com.aoooa.adb.ui.i18n

/** ADB 代理页面的类型化文案，通过主语言实例选择对应语言。 */
internal data class ProxyStrings(
    val title: String,
    val enable: String,
    val description: String,
    val allowLan: String,
    val lanDescription: String,
    val warningTitle: String,
    val warningMessage: String,
    val confirmLan: String,
    val cancel: String,
    val back: String,
    val port: String,
    val portHint: String,
    val savePort: String,
    val starting: String,
    val listening: String,
    val error: String,
    val stopped: String,
    val retry: String,
    val limitations: String
)

internal object ProxyI18n {
    val zh = ProxyStrings(
        title = "ADB 代理",
        enable = "启用 ADB 代理",
        description = "代理和局域网访问默认关闭。本机其他有网络权限的应用也可能访问代理。关闭代理不会断开应用的设备连接。",
        allowLan = "允许局域网访问",
        lanDescription = "开启后监听所有 IPv4 网卡。其他设备应使用本机局域网 IP 和端口连接，而不是 0.0.0.0。切换范围会断开现有代理客户端。",
        warningTitle = "开启局域网 ADB 代理？",
        warningMessage = "能够访问本机代理端口的设备，可能执行命令、安装应用和读写已连接设备的文件。此代理不提供额外身份验证或代理链路加密。仅在你信任且能够控制的网络中开启，勿在公共、共享或访客网络中使用，使用后及时关闭。此选项会保存，下次启用代理时继续生效。",
        confirmLan = "我了解风险，开启",
        cancel = "取消",
        back = "返回",
        port = "监听端口",
        portHint = "1024–65535 · 默认 5037",
        savePort = "保存端口（已启用时重启代理）",
        starting = "正在启动",
        listening = "正在监听 %s:%d",
        error = "启动或运行失败：%s",
        stopped = "未启动",
        retry = "重新启动",
        limitations = "仅支持部分 ADB 服务，不是完整的官方 ADB Server。退出应用后服务不保证后台运行。修改端口或关闭代理会断开现有代理客户端。"
    )
    val en = ProxyStrings(
        title = "ADB proxy",
        enable = "Enable ADB proxy",
        description = "Proxy and LAN access are off by default. Other local apps with network permission may access the proxy. Stopping it does not disconnect the app's device connection.",
        allowLan = "Allow LAN access",
        lanDescription = "LAN mode listens on all IPv4 interfaces. Connect using this device's LAN IP and port, not 0.0.0.0. Changing scope disconnects proxy clients.",
        warningTitle = "Enable LAN ADB proxy?",
        warningMessage = "Devices able to reach this port may execute commands, install apps and access files on the connected device. The proxy provides no additional authentication or link encryption. Enable only on a trusted network you control, never on public, shared or guest networks. Disable after use. This option is saved and applies when the proxy is enabled again.",
        confirmLan = "Understand and enable",
        cancel = "Cancel",
        back = "Back",
        port = "Listening port",
        portHint = "1024–65535 · Default 5037",
        savePort = "Save port (restart if enabled)",
        starting = "Starting",
        listening = "Listening %s:%d",
        error = "Proxy error: %s",
        stopped = "Stopped",
        retry = "Retry",
        limitations = "Supports a subset of ADB services, not a full official ADB server. Background operation after leaving the app is not guaranteed. Changing the port or stopping the proxy disconnects proxy clients."
    )
}

/** 使用调用方传入的语言实例，不在页面重复读取持久化语言配置。 */
internal val Strings.proxy: ProxyStrings
    get() = if (this === I18n.zh) ProxyI18n.zh else ProxyI18n.en
