# 安全政策 (Security Policy)

aoooa-adb 涉及底层 ADB 协议通信、USB OTG 调试与 Fastboot 固件交互，数据的安全性与通信隔离至关重要。

---

## 一、支持的版本 (Supported Versions)

我们仅为最新主干与当前版本分支提供安全漏洞修复与安全更新：

| 版本 (Version) | 支持状态 (Supported) |
| :--- | :--- |
| 2.7.x | :white_check_mark: 支持 |
| < 2.7.0 | :x: 不再提供安全补丁 |

---

## 二、如何私下报告安全漏洞 (Reporting a Vulnerability)

**⚠️ 严禁通过公开的 GitHub Issue、Discussions 或 Pull Request 提交任何安全漏洞或敏感利用方式！**

若发现安全缺陷（如特权绕过、TLS 配对缺陷、命令注入、私钥意外导出等）：

1. **通过 GitHub 私密漏洞报告（推荐）**：
   - 请前往仓库顶部的 **[Security (安全)](https://github.com/aoooa101/aoooa-adb-android/security)** 选项卡。
   - 点击 **"Report a vulnerability" (报告漏洞)** 按钮创建私密安全咨询（Advisory）。
   - 详细填写漏洞影响范围、触发条件、复现步骤与证明（PoC）。
2. **处理与响应流程**：
   - 维护者将在收到私密报告后尽快进行复现验证，评估威胁等级与影响范围。
   - 在安全修复补丁合并并发布正式版本之前，请配合保持漏洞细节私密（负责任披露，Coordinated Vulnerability Disclosure）。

---

## 三、敏感信息防护与隐私提醒

在日常使用及提交普通问题反馈时，请严格遵守以下原则：

- **严禁泄漏私钥**：切勿将设备或应用私有存储的 `adbkey` / `adbkey.pub` 导出并公开发布。
- **日志与截图脱敏**：复制终端控制台、Logcat 日志或上传截图前，务必检查并清除内网/公网 IP 地址、无线调试配对码（Pairing Code）以及设备专属序列号（SN）。
- **非受信任网络防护**：请避免在不可信的公共 Wi-Fi 网络下开放未受限的无线调试连接，防止未授权设备接入。
