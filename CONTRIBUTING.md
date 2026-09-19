# aoooa-adb 贡献指南 (Contributing Guide)

感谢关注并参与 **aoooa-adb** 的开发与维护！无论是提交缺陷反馈、完善文档还是提交代码，我们都非常欢迎。

在开始贡献之前，请花几分钟阅读本指南。

---

## 一、开发环境要求

为了保证项目的顺利编译与运行，推荐使用以下环境配置：

- **操作系统**：Linux / macOS / Windows
- **IDE**：Android Studio (Ladybug / Koala 或兼容版本)
- **JDK**：JDK 17 (推荐 OpenJDK 17 / Eclipse Temurin 17)
- **Android SDK**：
  - Compile SDK: `35`
  - Min SDK: `24` (Android 7.0+)
  - Target SDK: `35`
- **C/C++ 原生开发**：
  - CMake: `3.22.1`
  - Android NDK: 推荐 25.x ~ 27.x
- **⚠️ 核心架构约束（重要）**：
  - 本项目 Native C 模块与应用 ABI **仅构建并支持 `arm64-v8a`**。
  - 测试设备或 Android 模拟器必须使用 **`arm64-v8a`** 架构镜像，在 x86 / x86_64 / 32位 ARM 架构上运行将因缺少原生 so 库而抛出 `UnsatisfiedLinkError`。

---

## 二、项目拉取与本地构建

### 1. 克隆代码仓库

```bash
git clone https://github.com/aoooa101/aoooa-adb-android.git
cd aoooa-adb-android
```

### 2. 构建与运行测试

推荐使用项目自带的 Gradle Wrapper 执行构建任务（无需提前在电脑安装配置 Gradle）：

- **运行单元测试**：
  ```bash
  ./gradlew test
  ```
- **编译 Debug APK**：
  ```bash
  ./gradlew assembleDebug
  ```
  产物路径位于：`app/build/outputs/apk/debug/aoooa-adb-<version>-arm64-v8a.apk`。

- **代码质量检查 (Lint)**：
  ```bash
  ./gradlew lint
  ```

*注：也可以使用 Android Studio 直接打开项目构建。*

---

## 三、提交 Bug 反馈与功能建议

1. **Bug 报告**：
   - 请通过 [GitHub Issues](https://github.com/aoooa101/aoooa-adb-android/issues/new/choose) 提交并选择 **Bug 报告模板**。
   - 务必按表单完整提供控制端设备、被控端设备型号、系统版本（如澎湃/ColorOS/原生等定制系统）、连接方式及复现步骤。
   - **⚠️ 隐私脱敏提醒**：在上传日志或截图前，请务必遮盖或删除设备 IP、无线配对码、序列号（SN）以及任何涉及隐私的敏感内容。
2. **功能建议**：
   - 欢迎提出有助于改善用户体验或扩展协议能力的新想法，请选择 **功能建议模板** 详细描述需求场景及期望流程。
3. **安全漏洞报告**：
   - 若涉及认证绕过、命令注入等敏感安全漏洞，**切勿在公开 Issue 中发布**，请参照 [SECURITY.md](SECURITY.md) 通过安全选项卡私密渠道上报。

---

## 四、Fastboot 模块与高危操作风险提示

**aoooa-adb** 包含 Fastboot 模式直连、Bootloader 变量读取、分区重启与镜像刷写等底层功能。此类功能直接与底层硬件与固件交互，贡献与测试时必须严格遵守以下准则：

1. **测试机隔离**：
   - 开发或调试 Fastboot 镜像刷写、分区写入（`flash`）、分区擦除（`erase`）或锁状态变更相关功能时，**严禁使用日常主力机测试**。必须使用具备官方线刷救砖能力、可随时进入 EDL/Fastboot 模式的专用备用机。
2. **数据完整性与校验**：
   - 对 Fastboot 通信协议与数据流做修改时，必须对传输包大小（Sparse Image 分块）、CRC 校验以及返回状态码（`OKAY` / `FAIL` / `DATA`）保持严格的异常处理，杜绝半包写入或协议死锁。
3. **免责与风险声明**：
   - 任何涉及 Bootloader 解锁、分区擦写、刷写第三方固件或 Magisk/KernelSU 镜像的操作均存在软砖或硬砖风险。贡献者在提交相关功能代码时，须保证提供充分的用户确认弹窗与风险告知。

---

## 五、代码规范与分支管理

1. **分支约定**：
   - 生产/发布主分支为 `main`。
   - 开发新特性或修复问题时，请从最新的 `main` 分支拉取新分支（例如 `feature/xxx` 或 `fix/xxx`）。

2. **代码风格**：
   - **Kotlin**：遵循官方 Kotlin 编码规范与 Jetpack Compose 最佳实践，避免无谓的重复重组与内存泄漏。
   - **C / 原生模块**：保持原生 C 代码严谨高效，注意内存安全与跨 ABI 调用约定。
   - **日志与调试**：正式提交的代码中，请清理临时的调试打印或无用代码。

3. **提交规范**：
   - 提交信息应简洁、清晰地说明本次变动的目的与内容。

---

## 六、贡献流程 (PR 规范)

1. **Fork 本仓库** 到个人 GitHub 账号。
2. 在新分支上完成修改并编写/补充必要的单元测试。
3. 在本地执行 `gradle test` 与 `gradle assembleDebug`，确保测试全部通过、项目正常编译。
4. 推送分支至个人 Fork 仓库，并发起针对 `main` 分支的 **Pull Request**。
5. 在 PR 描述中清晰说明改动背景、实现细节及验证方式。

---

## 七、开源协议

本项目遵循 [GPL-3.0 License](LICENSE)。所有提交到本仓库的代码均视为同意按照 GPL-3.0 协议进行开源与分发。
