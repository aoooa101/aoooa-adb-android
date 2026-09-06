# aoooa-adb (Android 客户端)

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Release](https://img.shields.io/github/v/release/aoooa101/aoooa-adb-android?color=10b981)](https://github.com/aoooa101/aoooa-adb-android/releases)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-7f52ff)](https://kotlinlang.org/)
[![Platform](https://img.shields.io/badge/Platform-Android%207.0%2B%20(API%2024%2B)-0284c7)](https://developer.android.com)

基于 Android 原生架构开发的 aoooa-adb 调试工具，无需电脑、无需 Root，支持有线 OTG、无线调试、Fastboot 救砖、文件传输与应用流式安装全功能。

## 架构说明 (2.5.7 原生版)

本项目 2.5.7 版本架构说明：
- UI 表现层：Kotlin + Jetpack Compose + Material 3（首页连接、控制台、快捷指令、设置）
- 终端与控制台：左上角三条杠菜单自由切换「Shell 终端」与「日志」。Shell 终端支持 AOSP ShellProtocol v2 交互式 PTY 伪终端与快捷符号输入；日志系统支持基于 AOSP ADB 协议流与官方 Shizuku 特权双通道的实时 Logcat 抓取、顶部关键词实时搜索过滤、日志级别智能着色高亮、应用白名单/黑名单严格互斥筛选与设备应用列表自动回填选择
- 特权与扩展能力：集成官方 Shizuku API（`dev.rikka.shizuku`），支持未连接外部设备时通过本机 Shizuku 授权直接抓取日志，并支持已连接外部调试设备时的互斥安全断开切换
- 崩溃与异常追溯：集成全线程未捕获异常崩溃拦截器（UncaughtExceptionHandler），发生异常时自动记录调用栈
- 数据管理与备份：支持偏好设置与快捷指令的 JSON 导出与恢复，支持本地内部存储调试日志清理
- 快捷指令：独立单次执行通道，指令点击弹窗异步执行并展示返回结果
- 协议核心层：纯 Kotlin + AOSP 原生协议实现 ADB 握手、RSA-2048 签名、Shell 会话、AOSP sync: 文件传输与 Streamed Install 流式安装，彻底移除冗余的 Linux 原生二进制，包体积立减 12MB 且彻底消除 Android 10+ Seccomp 沙箱限制
- 救砖模式：实现 Fastboot 协议客户端（支持端点探测，支持 getvar、reboot、单分区镜像 flash）
- 安全认证：支持 Android 11+ TLS 1.3 双向认证、EKM 通道绑定与 SPAKE2 (Edwards25519) 密钥协商
- 签名体系：V2 + V3 签名
- 运行环境：安装包极其轻量（约 3~4MB），离线环境可用

## 核心功能

1. **自己调试自己 (Android 11+ 无线配对)**
   - 自动嗅探本机的 `_adb-tls-pairing` 配对端口与 `_adb-tls-connect` 调试端口
   - 下拉通知栏直接输入 6 位配对码完成认证与一键直连，免 Root、免电脑

2. **秒连本机已配对**
   - 对已配对过的设备，开启系统「无线调试」后一键直接建立连接，无需重复输入配对码

3. **通用无线调试 (IP:端口)**
   - 顶部输入框支持连接任意局域网设备的 `IP:端口`（如 `192.168.x.x:5555` 或动态端口）
   - 支持通过 ADB 协议一键开启/关闭被控端的 5555 经典无线调试端口

4. **USB OTG 有线调试**
   - 通过 Android `UsbManager` 直连目标设备的 ADB 接口
   - 兼容 Android 7 ~ 15，支持即插即用与授权弹窗确认

5. **Fastboot 救砖与调试**
   - 多级端点兼容探测，支持直连处于 Bootloader/Fastboot 模式的设备（兼容 vivo/小米/MTK 等厂商非标描述符），执行变量查询、分区重启与单分区镜像烧录

6. **文件传输与流式安装**
   - 支持通过 AOSP 标准 `sync:` 协议向目标设备推送文件（ADB Push）
   - 支持免留存直接流式安装 APK（ADB Install），提供「普通模式 (极速流式)」与「兼容模式 (老设备流控)」双轨支持

7. **实时 Logcat 日志系统与互斥应用筛选**
   - 支持在控制台切换至「日志」页面，通过 ADB 长连接协议流或本机 Shizuku 特权实时抓取 Logcat
   - 顶部提供实时搜索框，支持按包名、Tag、关键字即时过滤与复制单行日志
   - 右上角齿轮支持配置「完整设备日志」/「指定应用日志」，并支持「应用白名单」与「应用黑名单」严格互斥筛选
   - 添加规则时支持自动拉取设备已安装应用列表，点击即可自动回填包名
   - 右下角悬浮按钮支持一键开始/暂停日志抓取

8. **快捷指令中心与自定义管理**
   - 内置主流开源框架授权指令库
   - 支持顶部实时搜索、自定义分类标签新建、分组折叠/展开、批量移动与带二次确认的批量删除

## 下载安装

从 [GitHub Releases](https://github.com/aoooa101/aoooa-adb-android/releases) 下载最新 APK 安装包。
所有 Release 产物均内置正式签名，支持后续版本直接覆盖更新。

## 项目目录结构

```text
app/src/main/
├── java/com/aoooa/webadb/
│   ├── MainActivity.kt        # 应用主入口与生命周期管理
│   ├── AdbManager.kt          # 全局连接状态、PTY 会话与字符流状态机
│   ├── Prefs.kt               # 设置持久化、自定义分类与快捷指令库
│   ├── adb/                   # ADB 协议核心层 (AdbConnection, AdbCrypto, AdbPacket)
│   ├── bridge/                # 原生传输通道 (TcpChannel, UsbChannel, Channel)
│   ├── fastboot/              # Fastboot 协议客户端 (FastbootClient 救砖/镜像刷写)
│   ├── log/                   # 实时日志与白名单/黑名单过滤引擎 (LogManager)
│   ├── shizuku/               # 官方 Shizuku 特权 API 与进程管理 (ShizukuManager)
│   ├── model/                 # 数据模型实体 (CommandItem, TerminalLine)
│   ├── pairing/               # Android 11+ 无线配对引擎 (AdbPairing, Spake2, PairingService)
│   └── ui/                    # Compose 原生 UI (MainScreen, TerminalScreen, CommandsScreen, Strings, Theme)
├── cpp/                       # C/C++ 原生模块 (webadb_native.c, CMakeLists.txt)
└── res/                       # 资源文件
```

## 权限声明

| 权限 | 用途 |
|---|---|
| `POST_NOTIFICATIONS` | Android 13+ 通知栏展示配对状态与快捷输入配对码 |
| `FOREGROUND_SERVICE` | 保持后台无线配对监听与通知栏交互服务稳定运行 |
| `FOREGROUND_SERVICE_DATA_SYNC` | Android 14+ 前台服务数据同步类型声明 |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Android 14+ 前台服务外部/局域网设备连接类型声明 |
| `INTERNET` | 无线调试 TCP/IP 与 TLS 1.3 通信 |
| `ACCESS_NETWORK_STATE` | 网络状态检测 |
| `android.hardware.usb.host` | USB OTG 连接 ADB 设备 |
| `moe.shizuku.manager.permission.API_V23` | Shizuku 官方 API 授权与特权日志抓取 |
| `READ_EXTERNAL_STORAGE` | Android 12 及以下读取本地待推送/安装的文件（仅限旧系统） |

## 开源协议

本项目遵循 GPL-3.0 开源协议。
