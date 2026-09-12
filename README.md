<div align="center">

# aoooa-adb

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Release](https://img.shields.io/github/v/release/aoooa101/aoooa-adb-android?color=10b981)](https://github.com/aoooa101/aoooa-adb-android/releases)
[![Downloads](https://img.shields.io/github/downloads/aoooa101/aoooa-adb-android/total?color=blueviolet)](https://github.com/aoooa101/aoooa-adb-android/releases)
[![GitHub Stars](https://img.shields.io/github/stars/aoooa101/aoooa-adb-android?color=gold)](https://github.com/aoooa101/aoooa-adb-android/stargazers)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-7f52ff)](https://kotlinlang.org/)
[![Platform](https://img.shields.io/badge/Platform-Android%207.0%2B%20(API%2024%2B)-0284c7)](https://developer.android.com)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](https://github.com/aoooa101/aoooa-adb-android/pulls)

**简体中文** | [English](README_EN.md)

基于 Android 原生架构开发的 ADB / Fastboot 调试与控制台工具。无需依赖电脑，在移动端即可完成设备调试、终端交互与运维管理。

</div>

---

## 特性

- **USB OTG 有线调试**：通过 USB Host 直连被控设备，支持即插即用与连接授权。
- **无线配对与调试**：完整支持 Android 11+ 无线配对流程（SPAKE2 / TLS 1.3）及通用 `IP:端口` 远程连接。
- **交互式 Shell 终端**：基于 AOSP ShellProtocol v2 实现交互式 PTY 伪终端，提供常用快捷控制键。
- **实时日志系统**：支持 Logcat 实时日志流抓取、应用过滤、关键字搜索与日志导出。
- **Fastboot 刷机与调试**：支持 Fastboot 模式设备直连、变量查询、分区重启与镜像刷写。
- **文件与应用管理**：支持 ADB Push 文件推送与 APK 流式安装。
- **快捷指令库**：内置常用调试命令预设，支持自定义分类、批量管理与配置导入导出。

## 下载与安装

前往 [GitHub Releases](https://github.com/aoooa101/aoooa-adb-android/releases) 获取最新预编译 APK 安装包。

## 项目结构

```text
app/src/main/
├── java/com/aoooa/webadb/
│   ├── MainActivity.kt        # 应用入口与生命周期
│   ├── AdbManager.kt          # 全局连接与终端状态机
│   ├── Prefs.kt               # 配置持久化与指令管理
│   ├── adb/                   # ADB 协议核心层 (AdbConnection, AdbCrypto)
│   ├── bridge/                # 传输通道抽象 (TcpChannel, UsbChannel)
│   ├── fastboot/              # Fastboot 协议客户端
│   ├── log/                   # 实时日志引擎与应用过滤
│   ├── shizuku/               # Shizuku API 扩展支持
│   ├── pairing/               # Android 11+ 无线配对引擎 (AdbPairing, Spake2)
│   └── ui/                    # Jetpack Compose UI
├── cpp/                       # C 原生模块 (webadb_native.c)
└── res/                       # 资源文件
```

## 权限声明

| 权限 | 用途说明 |
|---|---|
| `POST_NOTIFICATIONS` | Android 13+ 通知栏展示配对状态与快捷输入 |
| `FOREGROUND_SERVICE` | 保持后台无线配对与设备通信服务稳定 |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Android 14+ 外部/局域网设备连接类型声明 |
| `INTERNET` | 无线调试网络通信与 TLS 加密传输 |
| `android.hardware.usb.host` | USB OTG 访问与控制被控设备 |
| `moe.shizuku.manager.permission.API_V23` | Shizuku 特权 API 授权（可选） |

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=aoooa101/aoooa-adb-android&type=Date)](https://star-history.com/#aoooa101/aoooa-adb-android&Date)

## 开源协议

本项目遵循 [GPL-3.0](LICENSE) 开源协议。
