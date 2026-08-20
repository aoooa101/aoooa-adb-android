# WebADB 控制台 · Android 客户端

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Release](https://img.shields.io/github/v/release/aoooa101/aoooa-webadb-android?include_prereleases&logo=android&logoColor=white)](https://github.com/aoooa101/aoooa-webadb-android/releases)
[![USB Native](https://img.shields.io/badge/USB-Native-0284c7?logo=android&logoColor=white)]()
[![Wireless](https://img.shields.io/badge/Wireless-ADB%20over%20TCP-10b981)]()
[![Build](https://img.shields.io/badge/Build-GitHub%20Actions-6366f1)](https://github.com/aoooa101/aoooa-webadb-android/actions)

基于 [aoooa-webadb](https://github.com/aoooa101/aoooa-webadb) 网页版的 Android 客户端。

网页版受限于浏览器安全模型（无法直连 TCP），App 版通过**原生层解锁传输通道**：

| 通道 | 说明 |
|---|---|
| **USB 连接** | 原生 `UsbManager` 直连设备 ADB 接口（替代网页版 WebUSB） |
| **无线调试** | 原生 `Socket` 直连 `IP:5555`（浏览器做不了的事，App 原生层可以） |
| **网页 UI 全复用** | WebView 加载本地页面，`@yume-chan/adb` 协议栈跑在 JS 层，字节流经原生桥双向传输 |

## 功能

- 原生 USB 连接 / 无线 TCP 连接（IP:5555）
- 设备信息（型号 / Android 版本 / 电量 / SELinux）
- 一键启动 Shizuku（自动定位 libshizuku.so）
- 一键开启/关闭 5555 无线调试端口（ADB 协议命令，非 root 有效）
- 预设命令 + 自定义命令执行
- 中英文界面

## 构建

无需本地 Android Studio —— GitHub Actions 云端编译：

1. 推送 `main` 分支自动触发 `assembleDebug`
2. 也可在 Actions 页手动运行（选择 debug / release）
3. 构建产物 APK 自动发布到 **Releases** 页面（prerelease）
4. 手机下载 APK，允许"安装未知应用"后安装

### 版本签名

- **debug**：Android 调试签名，可直接安装
- **release**：需在仓库 Secrets 配置 `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`（生成方式见下）

```bash
# 生成 keystore（示例）
keytool -genkeypair -keystore release.keystore -alias webadb \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass <密码> -keypass <密码> -dname "CN=WebADB"

# 转 Base64 后填入仓库 Secret
base64 -w 0 release.keystore
```

## 权限声明

| 权限 | 用途 |
|---|---|
| `android.hardware.usb.host` | 原生 USB 连接 ADB 设备 |
| `INTERNET` | 无线调试 TCP 连接 |
| `ACCESS_NETWORK_STATE` | 网络状态检测 |
| `NEARBY_WIFI_DEVICES` (neverForLocation) | Android 13+ 局域网设备通信 |

## 目录结构

```
app/src/main/
├── AndroidManifest.xml        # 权限与应用声明
├── assets/
│   ├── index.html             # App 版网页 UI（原生桥模式）
│   └── vendor/adb-bundle.js   # @yume-chan/adb 本地打包（单实例）
├── java/com/aoooa/webadb/
│   ├── MainActivity.kt        # WebView 容器 + JS 桥
│   └── bridge/                # 原生传输通道（USB / TCP）
└── res/                       # 主题 / 图标 / 布局
```

## 许可证

GPL-3.0（ADB 协议层 @yume-chan/adb 为 MIT，详见 `THIRD_PARTY_NOTICES` 对应说明）
