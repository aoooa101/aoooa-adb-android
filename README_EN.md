<div align="center">

# aoooa-adb

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Release](https://img.shields.io/github/v/release/aoooa101/aoooa-adb-android?color=10b981)](https://github.com/aoooa101/aoooa-adb-android/releases)
[![Downloads](https://img.shields.io/github/downloads/aoooa101/aoooa-adb-android/total?color=blueviolet)](https://github.com/aoooa101/aoooa-adb-android/releases)
[![GitHub Stars](https://img.shields.io/github/stars/aoooa101/aoooa-adb-android?color=gold)](https://github.com/aoooa101/aoooa-adb-android/stargazers)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-7f52ff)](https://kotlinlang.org/)
[![Platform](https://img.shields.io/badge/Platform-Android%207.0%2B%20(API%2024%2B)-0284c7)](https://developer.android.com)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](https://github.com/aoooa101/aoooa-adb-android/pulls)

[简体中文](README.md) | **English**

A native Android-based ADB / Fastboot debugging and console tool. Debug devices, interact with terminals, and manage devices directly from your Android device without a computer.

</div>

---

## Features

- **USB OTG Wired Debugging**: Directly connect to target devices via USB Host mode, supporting plug-and-play and connection authorization.
- **Wireless Pairing & Debugging**: Fully supports Android 11+ wireless pairing workflows (SPAKE2 / TLS 1.3) and standard `IP:Port` remote connections.
- **Interactive Shell Terminal**: Implements an interactive PTY pseudo-terminal based on AOSP ShellProtocol v2, complete with convenient shortcut keys.
- **Remote Control Mode (Scrcpy)**: Low-latency screen streaming, reverse touch and navigation key injection, supporting AAC audio synchronization.
- **Real-time Logging System**: Supports streaming Logcat capture, package whitelist/blacklist filtering, keyword search, and log export.
- **Fastboot Flashing & Debugging**: Direct connection in Fastboot mode for querying device variables, rebooting partitions, and flashing images.
- **Batch App Privilege Authorization**: Scans and grants ADB privileges to common third-party tools (Shizuku, Dhizuku, Scene, PermissionDog, etc.).
- **File & App Management**: Supports ADB Push file transfers and streaming APK installations.
- **Shortcut Presets & Backup**: Built-in debugging presets with custom categories, batch management, and JSON configuration import/export.

## Download & Installation

Visit [GitHub Releases](https://github.com/aoooa101/aoooa-adb-android/releases) to download the latest pre-built APK packages.

## Project Structure

```text
app/src/main/
├── java/com/aoooa/adb/
│   ├── MainActivity.kt        # Application entry point and lifecycle
│   ├── AdbManager.kt          # Global connection & terminal state machine
│   ├── Prefs.kt               # Configuration persistence & shortcut management
│   ├── adb/                   # ADB protocol core (AdbConnection, AdbCrypto)
│   ├── bridge/                # Transport channel abstraction (TcpChannel, UsbChannel)
│   ├── fastboot/              # Fastboot protocol client
│   ├── log/                   # Real-time log engine & app filtering
│   ├── shizuku/               # Shizuku API extension support
│   ├── pairing/               # Android 11+ wireless pairing engine (AdbPairing, Spake2)
│   └── ui/                    # Jetpack Compose UI
├── cpp/                       # Native C module (aoooa_adb_native.c)
└── res/                       # Resource files
```

## Permissions

| Permission | Description |
|---|---|
| `android.hardware.usb.host` | Accesses and controls target devices via USB OTG |
| `INTERNET` | Network communication and TLS encrypted transmission for wireless debugging |
| `ACCESS_NETWORK_STATE` | Detects network connectivity and wireless debugging availability |
| `NEARBY_WIFI_DEVICES` | Local network device communication and mDNS discovery (Android 13+) |
| `POST_NOTIFICATIONS` | Displays pairing status and quick inputs in the notification bar (Android 13+) |
| `FOREGROUND_SERVICE` | Keeps background wireless pairing and device communication services alive |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Declares connected device service type (Android 14+) |
| `FOREGROUND_SERVICE_DATA_SYNC` | Declares data sync service type for file transfers (Android 14+) |
| `READ_EXTERNAL_STORAGE` | Reads local files for push transfers and APK installs (Android 12 and below) |
| `QUERY_ALL_PACKAGES` | Allows querying installed apps on the host device for direct one-tap installation to target (Normal permission, zero popups) |
| `REQUEST_INSTALL_PACKAGES` | Launches package installer for in-app updates (Android 8.0+) |
| `moe.shizuku.manager.permission.API_V23` | Shizuku privileged API authorization (Optional) |

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=aoooa101/aoooa-adb-android&type=Date)](https://star-history.com/#aoooa101/aoooa-adb-android&Date)

## License

This project is licensed under the [GPL-3.0](LICENSE) License.
