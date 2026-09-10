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
import android.widget.FrameLayout
import org.json.JSONObject
import java.net.URLEncoder
import java.net.URLConnection
import kotlin.math.roundToInt

/**
 * Native browser surface hosted inside MainActivity and positioned over a RiftOS
 * desktop window.
 *
 * Ordinary sites receive no Rift native API. On the exact ChatGPT Web origin,
 * RiftBrowser installs the Rift MCP App compatibility adapter, which can talk only
 * to the in-process MCP server and therefore remains behind RiftToolHost policy.
 */
class RiftBrowserWindow(
    private val activity: Activity,
    private val host: FrameLayout,
    private val launchFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams?) -> Boolean,
    private val stateSink: (JSONObject) -> Unit,
    private val aiEventSink: (JSONObject) -> Unit
) {
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

    private val webView = WebView(activity)
    private val mcpApp = RiftBrowserMcpAppBridge(activity, webView, aiEventSink)
    private var popupWebView: WebView? = null
    private var requestedVisible = false
    private var hasBounds = false
    private var aiTransportOnly = false
    private var pendingAiTask: JSONObject? = null
    private var destroyed = false

    init {
        CookieManager.getInstance().setAcceptCookie(true)
        configureMainWebView(webView)
        mcpApp.install()
        installChromeClient()
        installWebViewClient()
        installDownloads()
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)
        webView.visibility = View.GONE
        webView.setBackgroundColor(0xff0a0d12.toInt())
        host.addView(webView, FrameLayout.LayoutParams(1, 1))
    }

    fun open(rawUrl: String?): JSONObject {
        ensureAlive()
        val target = normalizeStartUrl(rawUrl).ifBlank { webView.url?.takeIf { it.startsWith("https://chatgpt.com") } ?: "https://chatgpt.com" }
        aiTransportOnly = false
        requestedVisible = true
        updateCookiePolicy(target)
        if (webView.url.isNullOrBlank() || webView.url == "about:blank") {
            webView.loadUrl(target)
        } else if (rawUrl?.isNotBlank() == true && normalizeStartUrl(rawUrl) != webView.url) {
            webView.loadUrl(target)
        }
        applyVisibility()
        emitState()
        return state()
    }

    fun navigate(rawUrl: String?): JSONObject {
        ensureAlive()
        val target = normalizeStartUrl(rawUrl).ifBlank { "https://chatgpt.com" }
        updateCookiePolicy(target)
        webView.loadUrl(target)
        return state()
    }

    fun back(): JSONObject {
        ensureAlive()
        if (webView.canGoBack()) webView.goBack()
        emitState()
        return state()
    }

    fun forward(): JSONObject {
        ensureAlive()
        if (webView.canGoForward()) webView.goForward()
        emitState()
        return state()
    }

    fun reload(): JSONObject {
        ensureAlive()
        webView.reload()
        emitState()
        return state()
    }

    fun setVisible(visible: Boolean): JSONObject {
        ensureAlive()
        requestedVisible = visible
        applyVisibility()
        emitState()
        return state()
    }

    fun startAiTask(task: String, projectContext: String): JSONObject {
        ensureAlive()
        require(task.isNotBlank()) { "Rift AI task is empty" }
        aiTransportOnly = true
        requestedVisible = false
        ensureTransportLayout()
        pendingAiTask = JSONObject()
            .put("task", task.take(40_000))
            .put("projectContext", projectContext.take(8_000))
        applyVisibility()
        aiEventSink(JSONObject().put("type", "transport").put("message", "Opening fresh ChatGPT Web session"))
        webView.loadUrl("https://chatgpt.com/")
        emitState()
        return state().put("queued", true)
    }

    fun revealAiTransport(): JSONObject {
        ensureAlive()
        aiTransportOnly = false
        requestedVisible = true
        applyVisibility()
        emitState()
        return state()
    }

    fun hideAiTransport(): JSONObject {
        ensureAlive()
        aiTransportOnly = true
        requestedVisible = false
        ensureTransportLayout()
        applyVisibility()
        emitState()
        return state()
    }

    fun stopAiTask(): JSONObject {
        ensureAlive()
        val script = "window.RiftMcpAppControl?.stop?.();"
        webView.evaluateJavascript(script, null)
        return state()
    }

    fun setBounds(args: JSONObject): JSONObject {
        ensureAlive()
        val dpr = args.optDouble("dpr", 1.0).coerceIn(0.5, 8.0)
        val left = (args.optDouble("left", 0.0) * dpr).roundToInt().coerceAtLeast(0)
        val top = (args.optDouble("top", 0.0) * dpr).roundToInt().coerceAtLeast(0)
        val width = (args.optDouble("width", 1.0) * dpr).roundToInt().coerceAtLeast(1)
        val height = (args.optDouble("height", 1.0) * dpr).roundToInt().coerceAtLeast(1)
        val hostWidth = host.width.takeIf { it > 0 } ?: Int.MAX_VALUE
        val hostHeight = host.height.takeIf { it > 0 } ?: Int.MAX_VALUE
        val safeLeft = left.coerceAtMost((hostWidth - 1).coerceAtLeast(0))
        val safeTop = top.coerceAtMost((hostHeight - 1).coerceAtLeast(0))
        val safeWidth = width.coerceAtMost((hostWidth - safeLeft).coerceAtLeast(1))
        val safeHeight = height.coerceAtMost((hostHeight - safeTop).coerceAtLeast(1))
        val params = (webView.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(safeWidth, safeHeight)
        params.width = safeWidth
        params.height = safeHeight
        params.leftMargin = safeLeft
        params.topMargin = safeTop
        webView.layoutParams = params
        hasBounds = true
        applyVisibility()
        return state()
    }

    fun state(): JSONObject = JSONObject()
        .put("open", !destroyed)
        .put("visible", webView.visibility == View.VISIBLE)
        .put("url", webView.url ?: "")
        .put("title", webView.title ?: "RiftBrowser")
        .put("canGoBack", webView.canGoBack())
        .put("canGoForward", webView.canGoForward())
        .put("progress", webView.progress)
        .put("aiTransportOnly", aiTransportOnly)
        .put("riftMcpApp", mcpApp.state())

    fun close(): Boolean {
        if (destroyed) return true
        requestedVisible = false
        val aiActive = runCatching { RiftMcpRuntime.aiJournal(activity).state().optBoolean("active", false) }.getOrDefault(false)
        if (aiActive) {
            aiTransportOnly = true
            ensureTransportLayout()
            webView.visibility = View.INVISIBLE
        } else {
            webView.visibility = View.GONE
        }
        return true
    }

    fun onResume() {
        if (!destroyed) webView.onResume()
    }

    fun onPause() {
        if (!destroyed) webView.onPause()
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        requestedVisible = false
        destroyPopup()
        runCatching { mcpApp.destroy() }
        runCatching { host.removeView(webView) }
        runCatching { webView.stopLoading() }
        runCatching { webView.loadUrl("about:blank") }
        runCatching { webView.removeAllViews() }
        runCatching { webView.destroy() }
    }

    private fun ensureAlive() {
        check(!destroyed) { "RiftBrowser window has been destroyed" }
    }

    private fun applyVisibility() {
        if (aiTransportOnly) {
            ensureTransportLayout()
            webView.visibility = View.INVISIBLE
            return
        }
        webView.visibility = if (requestedVisible && hasBounds) View.VISIBLE else View.GONE
        if (webView.visibility == View.VISIBLE) webView.bringToFront()
    }

    private fun ensureTransportLayout() {
        val width = host.width.takeIf { it > 0 } ?: 1080
        val height = host.height.takeIf { it > 0 } ?: 1600
        val params = (webView.layoutParams as? FrameLayout.LayoutParams) ?: FrameLayout.LayoutParams(width, height)
        params.width = width
        params.height = height
        params.leftMargin = 0
        params.topMargin = 0
        webView.layoutParams = params
    }

    private fun dispatchPendingAiTask(attempt: Int = 0) {
        val payload = pendingAiTask ?: return
        if (attempt > 40) {
            pendingAiTask = null
            aiEventSink(JSONObject().put("type", "error").put("message", "ChatGPT Web transport did not become ready"))
            return
        }
        val payloadJs = payload.toString()
        val script = """(()=>{if(!window.RiftMcpAppControl||typeof window.RiftMcpAppControl.submitTask!=='function')return 'missing';window.RiftMcpAppControl.submitTask($payloadJs);return 'started';})()"""
        webView.evaluateJavascript(script) { result ->
            if (result?.contains("started") == true) {
                pendingAiTask = null
            } else {
                webView.postDelayed({ dispatchPendingAiTask(attempt + 1) }, 250L)
            }
        }
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

            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, false, false)
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                emitState()
            }
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
                updateCookiePolicy(url)
                emitState()
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView, url: String) {
                CookieManager.getInstance().flush()
                mcpApp.ensureInjected(url)
                if (aiTransportOnly && url.startsWith("https://chatgpt.com")) {
                    view.postDelayed({ dispatchPendingAiTask() }, 180L)
                }
                emitState()
                super.onPageFinished(view, url)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                emitState()
                super.doUpdateVisitedHistory(view, url, isReload)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                requestedVisible = false
                webView.visibility = View.GONE
                aiEventSink(JSONObject().put("type", "error").put("message", "ChatGPT Web renderer exited"))
                stateSink(JSONObject().put("open", true).put("visible", false).put("crashed", true))
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

    private fun emitState() {
        if (destroyed) return
        stateSink(state())
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
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (_: Exception) {
            true
        }
    }
}
