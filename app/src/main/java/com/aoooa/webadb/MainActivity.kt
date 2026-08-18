package com.aoooa.webadb

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewAssetLoader.AssetsPathHandler
import com.aoooa.webadb.bridge.AdbBridge

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var bridge: AdbBridge

    private val usbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }

    companion object {
        const val USB_PERMISSION = "com.aoooa.webadb.USB_PERMISSION"
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == USB_PERMISSION) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                // 诊断：打印系统广播携带的全部信息，定位 ROM 是否漏报 granted
                val extras = intent.extras?.keySet()?.joinToString(",") ?: "(无 extras)"
                runCatching { bridge.logToPage("USB 广播: granted=$granted extras=[$extras]") }
                val device = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                bridge.onUsbPermissionResult(device, granted)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        registerReceiver(usbReceiver, IntentFilter(USB_PERMISSION))

        // 关键修复：WebView 加载 file:// 时页面不是 secure context，
        // crypto.subtle 不可用 → @yume-chan/adb 无法生成 RSA 凭据 → ADB 认证超时。
        // 改用 WebViewAssetLoader 将 assets 挂到虚拟 HTTPS 域名 appassets.androidplatform.net：
        // 页面仍是 100% 本地文件、零网络请求，但 WebView 认为处于安全上下文，
        // crypto.subtle 恢复可用，ADB 认证即可完成。
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/", AssetsPathHandler(this))
            .build()

        webView = findViewById(R.id.webview)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // 页面完全走 WebViewAssetLoader，不再需要 file/content 直读，关闭更安全
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }
        }
        webView.webChromeClient = WebChromeClient()

        bridge = AdbBridge(
            this,
            onData = { data -> pushJs("window.__adbOnData && window.__adbOnData('$data');") },
            onStatus = { msg -> pushJs("window.__adbOnStatus && window.__adbOnStatus('$msg');") }
        )
        webView.addJavascriptInterface(bridge, "AdbBridge")

        webView.loadUrl("https://appassets.androidplatform.net/index.html")

        // 处理 App 启动时系统可能已携带的 USB_DEVICE_ATTACHED（插线启动场景）
        handleUsbAttach(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 插线时系统弹权限框（可勾选"一律允许"），允许后自动发起连接
        handleUsbAttach(intent)
    }

    /**
     * 处理 USB_DEVICE_ATTACHED：走系统权限授予路径（插线自动弹框），
     * 绕开部分 ROM 上 requestPermission 广播漏报 granted 的问题。
     */
    private fun handleUsbAttach(intent: Intent?) {
        if (intent == null || intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        if (!::bridge.isInitialized) return
        val device = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        } ?: return
        if (usbManager.hasPermission(device)) {
            bridge.logToPage("检测到 USB ADB 设备插入，权限已授予，自动连接...")
            bridge.usbConnect()
        } else {
            bridge.logToPage("检测到 USB ADB 设备插入，请在弹出的系统窗口点「允许」（可勾选一律允许）后重试")
        }
    }

    private fun pushJs(js: String) {
        runOnUiThread {
            if (::webView.isInitialized) {
                webView.evaluateJavascript(js, null)
            }
        }
    }

    override fun onDestroy() {
        bridge.disconnect()
        runCatching { unregisterReceiver(usbReceiver) }
        super.onDestroy()
    }
}
