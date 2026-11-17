# aoooa-adb Usage Guide

[简体中文](help.md) | **English**

Last updated: November 17, 2026.

## Before You Start

This guide explains how to use aoooa-adb. Instructions beyond this project's own features and controls, including phone settings, enabling wireless debugging, and third-party tools, are provided for reference only. They are not guaranteed to work on every device. If the steps do not match your device or do not work, please search for a guide for your phone model and system version.

### Permissions and Privacy

**To use all features, grant all permissions and system authorizations requested by the app. Missing permissions may prevent the related features from working. Available permission settings vary by Android version; follow the prompts shown on your device.**

Permissions and authorizations are used for the following purposes:

- **Notifications**: Enter pairing codes from the notification shade and view service status and download progress. Enable app notifications before pairing this device.
- **Network and nearby devices**: Connect through wireless debugging and discover devices on the local network. Network access is also used to check for updates and download APKs. Some permissions are granted automatically by Android without a separate prompt.
- **File access**: Read files and APKs you select for transfer or installation. Depending on your system, access may be granted through a storage permission or the system file picker.
- **Installed app list**: Let you select apps installed on this device and install them on the target device.
- **USB access**: Allow the app to access a connected USB device. You must also approve USB debugging on the target device.
- **Install unknown apps**: Install app updates through the system package installer.
- **Shizuku authorization**: Use features that rely on Shizuku, such as capturing logs through Shizuku. Start Shizuku and grant access before using these features. This authorization is not required if you do not use them.

**This app does not steal your private data. Permissions are used only to provide features such as device connections, debugging, file transfers, app management, and logging—not to collect private data without your authorization.**

Update checks and APK downloads contact update servers or download services; not every feature works entirely offline. Logs and command output may contain sensitive information. Review them before sharing and redact sensitive details before submitting feedback. Be careful when authorizing MCP clients: allowing a client to execute commands may give it access to data on the target device.

## Contents

- [1. Getting Started](#1-getting-started)
- [2. MCP Setup](#2-mcp-setup)
- [3. Feedback](#3-feedback)

## 1 Getting Started

### 1.1 USB Debugging (Wired Connection)

On the phone running this app, enable OTG / OTG Connection if your system provides that switch. On the target device, enable "USB debugging" in Developer options. Setting names and locations vary by phone; please look up the steps for your model if needed. Device-specific instructions are not covered here.

On the aoooa-adb home screen, tap the three-line menu in the top-left and select wired debugging. Connect the devices using a USB data cable and an OTG adapter if needed. Allow the app to access the USB device, then unlock the target device and approve its USB debugging prompt. If the connection does not start automatically, tap the connection button on the wired debugging page. When the status in the top-right changes from "Disconnected" to "Connected", the connection is ready.

### 1.2 Wireless Debugging

#### Enable Wireless Debugging (Android 11 and Later)

Follow these steps on the target device. If you are pairing this phone with itself, use this phone's settings.

1. Open "Settings > About phone" and find "Build number". On some phones, it is under "Software information" or "Version information". Tap it seven times and enter your screen lock PIN or password if prompted, until developer mode is enabled. Skip this step if it is already enabled.
2. Return to Settings and find "Developer options", usually under "System" or "Additional settings". You can also search for it in Settings.
3. Connect the phone to Wi-Fi, open "Developer options > Wireless debugging", and enable it. If asked to allow debugging on the current network, approve only if you trust the network.
4. Open the Wireless debugging details page to find the connection IP address and port. Select "Pair device with pairing code" to see the pairing IP address, pairing port, and pairing code.

Setting names and locations vary by phone. These steps are for reference only; follow what your device actually shows. If an option is missing or the steps do not work, please search for instructions for your phone brand, model, and system version. Device-specific methods are not covered here. This pairing method does not apply to Android 10 or earlier.

#### Connect to a Device

Open the app. The wireless debugging page is shown by default; switch to it if needed.

- **Android 10 and earlier**: The target device must already be listening for ADB connections over TCP/IP. Enabling USB debugging alone does not enable wireless connections. Please look up the setup steps for your device. Enter its IP address, optionally followed by a port. If you omit the port, the app uses `5555`; use the port actually configured on the target device.
- **Android 11 and later**: Pair the device before connecting for the first time, as described below. For a previously paired device, enter the connection IP address and port from its Wireless debugging details page into the app's general connection field, in `IP:port` format, then tap Connect.

**The pairing port and connection port are different. Use the port in the pairing-code dialog for pairing, and the port on the Wireless debugging details page for connecting. The IP address or port may change after restarting wireless debugging or switching networks. Check the current values rather than reusing an old address.**

### 1.3 Wireless Pairing

Android 11 and later devices that support system wireless debugging can pair and connect without a computer or cable. Enable wireless debugging first using the steps above.

Under "Android 11+ Pairing", choose whether to pair this device or another device, then follow the prompts.

#### Pair This Device

Enable notifications for the app, select "Pair This Device", and follow the app's prompts. Open "Wireless debugging > Pair device with pairing code" in your phone's settings. Keep the pairing dialog open, pull down the notification shade, and enter and send the current code through the app's pairing notification.

Notification-based code entry requires app notifications to be enabled. Use the code shown in the current dialog; if it expires, get a new one.

#### Pair Another Device

Connect the controlling device and the target device to the same local network. Three pairing modes are available; the first two are recommended:

- **Smart Pairing**: On the target device, open "Wireless debugging > Pair device with pairing code" and keep the dialog open. The app discovers the pairing address and port automatically. Select the target device and enter its pairing code.
- **QR pairing**: On the target device, select "Pair device with QR code". Use that device to scan the QR code displayed by this app, then follow the prompts.
- **Manual Pairing**: Open the pairing-code dialog on the target device, then enter the IP address, pairing port, and pairing code from that dialog into this app.

Pairing does not necessarily mean the device is connected. If the status in the top-right still says "Disconnected", return to the target device's Wireless debugging details page and enter its connection IP address and port in the app's general connection field.

### Important Notes

All wireless debugging operations above, on both Android 11 and later and Android 10 and earlier, require the devices to be on the same local network. Even on the same Wi-Fi network, a guest network or router isolation setting may prevent devices from reaching each other. If discovery fails, try manual pairing and check your network settings.

Enable debugging only on trusted networks. Do not share pairing codes with strangers or authorize devices you do not recognize. Turn off wireless debugging when it is no longer needed.

## 2 MCP Setup

Open "Settings > MCP Settings" in the app to configure MCP. The MCP (Model Context Protocol) service lets AI clients such as Claude Desktop and Cursor call the app's built-in tools over a standard protocol to dispatch and debug the connected device.

### 2.1 Service Switch & Communication Modes

- **Enable MCP Server**: Master switch. While running, ongoing status is shown in the notification shade.
- **Allow LAN Access**: When enabled, devices on the same network can reach the local MCP service. A risk warning dialog appears before enabling, and token authentication is strongly recommended. If you still leave it off, only use it on a network you trust.
- **Communication Modes**: Standard HTTP Mode (SHTTP) and SSE Streaming Mode can be enabled in any combination, but at least one must remain on; the SSE mode has a configurable maximum number of clients (default 2). Changing the modes prompts a service restart to take effect.
- **Listening Address & Client Config**: Tap the listening address to copy it; the "Client Config (Official JSON)" can be pasted directly into clients such as Claude Desktop and Cursor.
- **Server Config**: Set the listening port (1024–65535) and the service access token (regenerable). With authentication verification enabled, clients must present the token.

### 2.2 Security Blocking Rules

The "Security Rules" section blocks high-risk commands requested by clients and supports adding, editing, and batch deletion:

- **Exact match**: Intercepts when the trimmed command string matches exactly.
- **Verb match**: Intercepts when the primary command verb matches after chained command expansion.
- Each rule may carry a description, returned to the AI as the explanation when an interception triggers.

### 2.3 Available Tools

- **Execute shell command (`execute_shell_command`)**: Runs non-interactive shell commands on the connected Android device for system management, status queries, and log filtering; overlong output is cached in pages and can be read further by passing a page number.
- **List security rules (`list_security_rules`)**: Returns the active security blocking rules (mode, pattern, and description) so the AI can proactively avoid prohibited high-risk commands.

### Important Notes

- Do not rely solely on the rules; they may be bypassed.
- Always enable token authentication when enabling LAN access.
- Authorize clients with caution: a client allowed to execute commands may access data on the target device.

## 3 Feedback

If something goes wrong, save the logs first, then restart the app and try again. If the problem persists, you can also restart your phone after saving the logs. Do not clear the logs or app data first, as this may remove information needed to investigate.

- **Home screen logs**: Tap "Copy Log" in the home screen's log panel, then paste the text into a file or note and save it. Do not rely on the clipboard alone to keep your logs.
- **Detailed app logs**: These are usually stored in `/sdcard/Android/data/com.aoooa.adb/files/logs/`. Save the files covering the time of the problem. If external storage is unavailable, the app uses an internal directory instead. Some systems also restrict access to `Android/data`. If you cannot access the files, save the logs you can copy from the home screen. System log capture and this app's own runtime logs are different things.

If restarting does not resolve the problem, open an issue on the [GitHub issues page](https://github.com/aoooa101/aoooa-adb-android/issues). Include the saved logs, the time of the problem, steps to reproduce it, your phone model, Android version, and app version. A GitHub account is required to submit an issue.

Logs may contain sensitive information. Redact it before submitting, and do not attach passwords, tokens, pairing codes, or ADB private keys.

## References

These official documents explain Android system setup, not this app's controls. Desktop commands shown there do not represent buttons or features in this app.

- [Android documentation: ADB, USB debugging, and wireless debugging](https://developer.android.com/tools/adb)
- [Android documentation: Enable developer options](https://developer.android.com/studio/debug/dev-options)
