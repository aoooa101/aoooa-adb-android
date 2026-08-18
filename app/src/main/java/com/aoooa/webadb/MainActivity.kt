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
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.aoooa.webadb.bridge.AdbBridge

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var bridge: AdbBridge

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

        webView = findViewById(R.id.webview)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()

        bridge = AdbBridge(
            this,
            onData = { data -> pushJs("window.__adbOnData && window.__adbOnData('$data');") },
            onStatus = { msg -> pushJs("window.__adbOnStatus && window.__adbOnStatus('$msg');") }
        )
        webView.addJavascriptInterface(bridge, "AdbBridge")

        webView.loadUrl("file:///android_asset/index.html")
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
