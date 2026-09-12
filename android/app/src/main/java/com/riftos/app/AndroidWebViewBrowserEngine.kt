package com.riftos.app

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Environment
import android.os.Message
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
import org.json.JSONObject
import java.net.URLConnection

/** Android System WebView compatibility backend for RiftBrowserEngine. */
class AndroidWebViewBrowserEngine(
    private val activity: Activity,
    private val launchFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams?) -> Boolean,
    private val stateChanged: () -> Unit
) : RiftBrowserEngine {
    companion object {
        private val EXTERNAL_SCHEMES = setOf("mailto", "tel", "geo")
        private val AUTH_FLOW_HOSTS = setOf(
            "chatgpt.com",
            "www.chatgpt.com",
            "openai.com",
            "www.openai.com",
            "auth.openai.com",
            "accounts.google.com",
            "login.microsoftonline.com",
            "login.live.com",
            "appleid.apple.com"
        )
    }

    val webView = WebView(activity)
    override val view get() = webView
    override val rendererId = "android-webview"

    private val mcpApp = RiftBrowserMcpAppBridge(activity, webView)
    private var popupWebView: WebView? = null
    private var crashed = false

    init {
        CookieManager.getInstance().setAcceptCookie(true)
        configureMainWebView(webView)
        mcpApp.install()
        installChromeClient()
        installWebViewClient()
        installDownloads()
        webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, false)
        webView.setBackgroundColor(0xff0a0d12.toInt())
    }

    override fun currentUrl(): String = webView.url.orEmpty()

    override fun loadUrl(url: String) {
        crashed = false
        updateCookiePolicy(url)
        webView.loadUrl(url)
    }

    override fun canGoBack(): Boolean = webView.canGoBack()
    override fun goBack() { if (webView.canGoBack()) webView.goBack() }
    override fun canGoForward(): Boolean = webView.canGoForward()
    override fun goForward() { if (webView.canGoForward()) webView.goForward() }
    override fun reload() { crashed = false; webView.reload() }

    override fun state(): JSONObject = JSONObject()
        .put("renderer", rendererId)
        .put("url", webView.url ?: "")
        .put("title", webView.title ?: "RiftBrowser")
        .put("canGoBack", webView.canGoBack())
        .put("canGoForward", webView.canGoForward())
        .put("progress", webView.progress)
        .put("crashed", crashed)
        .put("riftMcpApp", mcpApp.state())

    override fun onResume() { webView.onResume() }
    override fun onPause() { webView.onPause() }

    override fun destroy() {
        destroyPopup()
        runCatching { mcpApp.destroy() }
        runCatching { webView.stopLoading() }
        runCatching { webView.loadUrl("about:blank") }
        runCatching { webView.removeAllViews() }
        runCatching { webView.destroy() }
    }

    private fun configureMainWebView(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            setGeolocationEnabled(false)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) safeBrowsingEnabled = true
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = false
        }
    }

    private fun configurePopupWebView(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            setGeolocationEnabled(false)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
    }

    private fun installChromeClient() {
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                val callback = filePathCallback ?: return false
                return launchFileChooser(callback, fileChooserParams)
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean {
                if (!isUserGesture || !isAuthFlowUrl(view.url)) return false
                destroyPopup()
                val popup = WebView(activity)
                popupWebView = popup
                configurePopupWebView(popup)
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        redirectPopupToMain(request.url)

                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        if (url != "about:blank") redirectPopupToMain(Uri.parse(url))
                    }
                }
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: run {
                    destroyPopup()
                    return false
                }
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) {
                if (window === popupWebView) destroyPopup()
            }

            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, false, false)
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) { stateChanged() }
        }
    }

    private fun installWebViewClient() {
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val uri = request.url
                return when (uri.scheme?.lowercase()) {
                    "https" -> {
                        updateCookiePolicy(uri.toString())
                        false
                    }
                    "http" -> {
                        val upgraded = uri.buildUpon().scheme("https").build().toString()
                        updateCookiePolicy(upgraded)
                        view.loadUrl(upgraded)
                        true
                    }
                    in EXTERNAL_SCHEMES -> openExternal(uri)
                    else -> true
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError?) {
                handler.cancel()
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                crashed = false
                updateCookiePolicy(url)
                stateChanged()
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView, url: String) {
                CookieManager.getInstance().flush()
                mcpApp.ensureInjected(url)
                stateChanged()
                super.onPageFinished(view, url)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                stateChanged()
                super.doUpdateVisitedHistory(view, url, isReload)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                crashed = true
                stateChanged()
                return true
            }
        }
    }

    private fun installDownloads() {
        webView.setDownloadListener { url, userAgent, disposition, mime, _ ->
            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return@setDownloadListener
            if (uri.scheme?.lowercase() != "https") return@setDownloadListener
            val guessed = android.webkit.URLUtil.guessFileName(url, disposition, mime)
            val safeName = guessed.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .take(180)
                .ifBlank { "download" }
            val request = DownloadManager.Request(uri).apply {
                setMimeType(mime ?: URLConnection.guessContentTypeFromName(safeName) ?: "application/octet-stream")
                addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let {
                    addRequestHeader("Cookie", it)
                }
                setTitle(safeName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, safeName)
            }
            (activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        }
    }

    private fun updateCookiePolicy(url: String?) {
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, isAuthFlowUrl(url))
    }

    private fun isAuthFlowUrl(url: String?): Boolean {
        val uri = runCatching { Uri.parse(url.orEmpty()) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val hostName = uri.host?.lowercase() ?: return false
        return hostName in AUTH_FLOW_HOSTS ||
            hostName.endsWith(".chatgpt.com") ||
            hostName.endsWith(".openai.com")
    }

    private fun redirectPopupToMain(uri: Uri): Boolean {
        if (uri.scheme?.lowercase() != "https") {
            destroyPopup()
            return true
        }
        updateCookiePolicy(uri.toString())
        webView.loadUrl(uri.toString())
        destroyPopup()
        return true
    }

    private fun destroyPopup() {
        popupWebView?.let { popup ->
            popupWebView = null
            runCatching { popup.stopLoading() }
            runCatching { popup.loadUrl("about:blank") }
            runCatching { popup.removeAllViews() }
            runCatching { popup.destroy() }
        }
    }

    private fun openExternal(uri: Uri): Boolean {
        if (uri.scheme?.lowercase() !in EXTERNAL_SCHEMES) return true
        return try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (_: Exception) {
            true
        }
    }
}
