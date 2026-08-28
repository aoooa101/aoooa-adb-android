# aoooa-adb (Android 客户端)

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Release](https://img.shields.io/github/v/release/aoooa101/aoooa-adb-android?color=10b981)](https://github.com/aoooa101/aoooa-adb-android/releases)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-7f52ff)](https://kotlinlang.org/)
[![Platform](https://img.shields.io/badge/Platform-Android%207.0%2B%20(API%2024%2B)-0284c7)](https://developer.android.com)

基于 Android 原生架构开发的 aoooa-adb 调试工具，无需电脑、无需 Root，支持有线 OTG、无线调试、Fastboot 救砖、文件传输与应用流式安装全功能。

## 架构说明 (2.5.4 原生版)

本项目 2.5.4 版本采用纯原生现代架构开发：
- UI 表现层：Kotlin + Jetpack Compose + Material 3（四 Tab 架构：首页连接中心、交互式控制台、快捷指令中心、设置）
- 终端与控制台：AOSP 标准 ShellProtocol v2 全双工真·交互式 PTY 伪终端，内置纯 Kotlin TTY 字符流状态机（支持 `\r` 行首重绘、`\b` 退格删除、真 `Ctrl+C` 信号中断、环境变量常驻与 `cd` 路径相对跳转跟随），配备纯原生 ANSI SGR 颜色高亮解析引擎
- 崩溃与异常追溯：集成全线程未捕获异常崩溃拦截器（UncaughtExceptionHandler），发生闪退时自动将设备型号、SDK 版本与完整崩溃调用栈同步记录并强制写盘
- 数据管理与备份：支持偏好设置与全部自定义快捷指令的 JSON 一键导出与导入恢复（严格遵循 Android SAF 规范，零危险存储权限），支持本地内部存储调试日志一键安全物理清理
- 快捷指令与就地交互：独立单次执行通道，指令点击就地弹窗异步执行并展示完整返回，支持长按自由选取结果
- 协议核心层：原生实现 ADB 握手、RSA-2048 签名、Shell 会话、AOSP 标准 sync: 文件传输与 Streamed Install 流式安装（支持普通极速/兼容流控双模）
- 救砖模式：原生实现 Fastboot 协议客户端（对齐 Google 规范多级智能端点探测，零外部 .so 依赖，支持 getvar、reboot、单分区镜像 flash）
- 密码学引擎：原生实现 Android 11+ TLS 1.3 双向认证、EKM 通道绑定与 SPAKE2 (Edwards25519) 密钥协商，完全对齐 AOSP / BoringSSL 规范
- 签名体系：纯净 V2 + V3 现代化强制签名（去除 V1 冗余，禁用 V4 伴生文件）
- 零外部依赖：安装包体积极致轻量，断网环境完全可用

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

7. **快捷指令中心与自定义管理**
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
│   ├── model/                 # 数据模型实体 (CommandItem 快捷指令)
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

## 开源协议

本项目遵循 GPL-3.0 开源协议。
