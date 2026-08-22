# aoooa-adb (Android 客户端)

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Release](https://img.shields.io/github/v/release/aoooa101/aoooa-webadb-android?color=10b981)](https://github.com/aoooa101/aoooa-webadb-android/releases)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-7f52ff)](https://kotlinlang.org/)
[![Platform](https://img.shields.io/badge/Platform-Android%207.0%2B%20(API%2024%2B)-0284c7)](https://developer.android.com)

基于 Android 原生架构开发的 aoooa-adb 调试工具，无需电脑、无需 Root，支持有线 OTG、无线调试、Fastboot 救砖、文件传输与应用流式安装全功能。

## 架构说明 (2.5 原生版)

本项目 2.5 版本采用纯原生现代架构开发：
- UI 表现层：Kotlin + Jetpack Compose + Material 3（三 Tab 架构：首页大控制台、快捷指令中心、设置）
- 协议核心层：原生实现 ADB 握手、RSA-2048 签名、Shell 会话、AOSP 标准 sync: 文件传输与 Streamed Install 流式安装
- 救砖模式：原生实现 Fastboot 协议客户端（零外部 .so 依赖，支持 getvar、reboot、单分区镜像 flash）
- 密码学引擎：原生实现 Android 11+ TLS 1.3 双向认证、EKM 通道绑定与 SPAKE2 (Edwards25519) 密钥协商，完全对齐 AOSP / BoringSSL 规范
- 签名体系：纯净 V2 + V3 现代化签名（去除 V1 冗余，禁用 V4 伴生文件）
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

5. **双语国际化与自适应**
   - 自动识别系统语言（中文显示 zh，其他语言默认 en）
   - 支持在设置页面随时切换语言

6. **权限管理与合规**
   - 首次启动展示开发者调试免责声明
   - 设置页提供通知权限实时状态检测与一键授权跳转

## 下载安装

从 [GitHub Releases](https://github.com/aoooa101/aoooa-webadb-android/releases) 下载最新 APK 安装包。
所有 Release 产物均内置正式签名，支持后续版本直接覆盖更新。

## 项目目录结构

```text
app/src/main/
├── java/com/aoooa/webadb/
│   ├── MainActivity.kt        # 应用主入口与生命周期管理
│   ├── AdbManager.kt          # 全局连接状态与会话管理
│   ├── Prefs.kt               # 设置持久化与系统语言自适应
│   ├── adb/                   # ADB 协议核心层 (AdbConnection, AdbCrypto, AdbPacket)
│   ├── bridge/                # 原生传输通道 (TcpChannel, UsbChannel, Channel)
│   ├── pairing/               # Android 11+ 无线配对引擎 (AdbPairing, Spake2, PairingService)
│   └── ui/                    # Compose 原生 UI 与双语国际化 (MainScreen, Strings, Theme)
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
