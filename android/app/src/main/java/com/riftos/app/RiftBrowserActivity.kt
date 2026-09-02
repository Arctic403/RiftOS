package com.riftos.app

import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.net.URLEncoder
import java.net.URLConnection

class RiftBrowserActivity : Activity() {
    companion object {
        const val EXTRA_URL = "url"
        private const val FILE_CHOOSER_REQUEST = 7101
        private val EXTERNAL_SCHEMES = setOf("mailto", "tel", "geo")
    }

    private lateinit var webView: WebView
    private lateinit var address: EditText
    private var chooser: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xff0a0d12.toInt()
        window.navigationBarColor = 0xff0a0d12.toInt()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val back = Button(this).apply { text = "‹" }
        val forward = Button(this).apply { text = "›" }
        address = EditText(this).apply {
            setSingleLine(true)
            setSelectAllOnFocus(false)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val go = Button(this).apply { text = "Go" }
        bar.addView(back)
        bar.addView(forward)
        bar.addView(address)
        bar.addView(go)

        webView = WebView(this)
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)
        root.addView(bar)
        root.addView(webView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, false)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            setGeolocationEnabled(false)
            safeBrowsingEnabled = true
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = false
        }

        val debuggingEnabled = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(debuggingEnabled)

        if (WebViewFeature.isFeatureSupported(WebViewFeature.START_SAFE_BROWSING)) {
            @Suppress("DEPRECATION")
            WebViewCompat.startSafeBrowsing(applicationContext, null)
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                chooser?.onReceiveValue(null)
                chooser = filePathCallback
                return try {
                    val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST)
                    true
                } catch (_: Exception) {
                    chooser?.onReceiveValue(null)
                    chooser = null
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, false, false)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val uri = request.url
                return when (uri.scheme?.lowercase()) {
                    "https" -> false
                    "http" -> {
                        view.loadUrl(uri.buildUpon().scheme("https").build().toString())
                        true
                    }
                    in EXTERNAL_SCHEMES -> openExternal(uri)
                    else -> true
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError?) {
                handler.cancel()
            }

            override fun onPageFinished(view: WebView, url: String) {
                address.setText(url)
                super.onPageFinished(view, url)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                runOnUiThread { if (!isFinishing && !isDestroyed) recreate() }
                return true
            }
        }

        webView.setDownloadListener { url, userAgent, disposition, mime, _ ->
            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return@setDownloadListener
            if (uri.scheme?.lowercase() != "https") return@setDownloadListener

            val guessed = android.webkit.URLUtil.guessFileName(url, disposition, mime)
            val safeName = guessed.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(180).ifBlank { "download" }
            val request = DownloadManager.Request(uri).apply {
                setMimeType(mime ?: URLConnection.guessContentTypeFromName(safeName) ?: "application/octet-stream")
                addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let { addRequestHeader("Cookie", it) }
                setTitle(safeName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalFilesDir(this@RiftBrowserActivity, Environment.DIRECTORY_DOWNLOADS, safeName)
            }
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        }

        fun navigate() {
            var value = address.text.toString().trim()
            if (value.isBlank()) return
            value = when {
                value.startsWith("https://", ignoreCase = true) -> value
                value.startsWith("http://", ignoreCase = true) -> "https://${value.substringAfter("://")}" 
                value.contains('.') && !value.contains(' ') -> "https://$value"
                else -> "https://www.google.com/search?q=" + URLEncoder.encode(value, "UTF-8")
            }
            webView.loadUrl(value)
        }

        back.setOnClickListener { if (webView.canGoBack()) webView.goBack() else finish() }
        forward.setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        go.setOnClickListener { navigate() }
        address.setOnEditorActionListener { _, _, _ -> navigate(); true }

        if (savedInstanceState == null) {
            val start = normalizeStartUrl(intent.getStringExtra(EXTRA_URL)).ifBlank { "https://chatgpt.com" }
            address.setText(start)
            webView.loadUrl(start)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    private fun normalizeStartUrl(raw: String?): String {
        val value = raw.orEmpty().trim()
        if (value.isBlank()) return ""
        return when {
            value.startsWith("https://", ignoreCase = true) -> value
            value.startsWith("http://", ignoreCase = true) -> "https://${value.substringAfter("://")}" 
            value.contains('.') && !value.contains(' ') -> "https://$value"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(value, "UTF-8")
        }
    }

    private fun openExternal(uri: Uri): Boolean {
        if (uri.scheme?.lowercase() !in EXTERNAL_SCHEMES) return true
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (_: Exception) {
            true
        }
    }

    @Deprecated("Activity result compatibility path")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_REQUEST) {
            val cb = chooser ?: return
            chooser = null
            cb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onPause() {
        if (::webView.isInitialized) webView.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::webView.isInitialized) webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        chooser?.onReceiveValue(null)
        chooser = null
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.removeAllViews()
            webView.destroy()
        }
        super.onDestroy()
    }
}
