package com.riftos.app

import android.app.Activity
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportMultipleWindows(false)
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            addJavascriptInterface(RiftHostBridge(this@MainActivity), "RiftNative")
            loadUrl("file:///android_asset/bootstrap.html")
        }

        setContentView(webView)
    }

    override fun onDestroy() {
        webView.removeJavascriptInterface("RiftNative")
        webView.destroy()
        super.onDestroy()
    }
}
