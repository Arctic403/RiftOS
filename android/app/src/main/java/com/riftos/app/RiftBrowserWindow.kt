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
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.net.URLEncoder
import java.net.URLConnection
import kotlin.math.roundToInt

/**
 * Native browser surface hosted inside MainActivity and positioned over a RiftOS
 * desktop window. The RiftOS HTML shell owns chrome/window management while this
 * class owns only the secure Android WebView content plane.
 */
class RiftBrowserWindow(
    private val activity: Activity,
    private val host: FrameLayout,
    private val launchFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams?) -> Boolean,
    private val stateSink: (JSONObject) -> Unit
) {
    companion object {
        private const val CHATGPT_ORIGIN = "https://chatgpt.com"
        private const val CHATGPT_AGENT_ASSET = "riftbrowser-chatgpt-agent.js"
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

        private val SANDBOX_BOOTSTRAP = """
            (() => {
              if (location.origin !== "https://chatgpt.com") return;
              if (globalThis.RiftSandboxFS || !globalThis.RiftSandbox || typeof globalThis.RiftSandbox.postMessage !== "function") return;
              const pending = new Map();
              let sequence = 0;
              globalThis.RiftSandbox.onmessage = event => {
                let message;
                try { message = JSON.parse(event.data); } catch (_) { return; }
                const request = pending.get(message.id);
                if (!request) return;
                pending.delete(message.id);
                if (message.ok) request.resolve(message.value);
                else request.reject(new Error(message.error || "Rift sandbox request failed"));
              };
              const call = (method, args = {}) => new Promise((resolve, reject) => {
                const id = "rift-sandbox-" + Date.now() + "-" + (++sequence);
                pending.set(id, { resolve, reject });
                globalThis.RiftSandbox.postMessage(JSON.stringify({ id, method, args }));
              });
              const api = Object.freeze({
                info: () => call("sandbox.info"),
                stat: path => call("fs.stat", { path: String(path || "") }),
                list: (path = "", options = {}) => call("fs.list", { path: String(path || ""), recursive: Boolean(options.recursive) }),
                readText: path => call("fs.readText", { path: String(path || "") }),
                writeText: (path, text) => call("fs.writeText", { path: String(path || ""), text: String(text ?? "") }),
                readBase64: path => call("fs.readBase64", { path: String(path || "") }),
                writeBase64: (path, data) => call("fs.writeBase64", { path: String(path || ""), data: String(data || "") }),
                mkdir: path => call("fs.mkdir", { path: String(path || "") }),
                remove: path => call("fs.remove", { path: String(path || "") }),
                move: (from, to, options = {}) => call("fs.move", {
                  from: String(from || ""),
                  to: String(to || ""),
                  overwrite: Boolean(options.overwrite)
                })
              });
              Object.defineProperty(globalThis, "RiftSandboxFS", {
                value: api, configurable: false, enumerable: false, writable: false
              });
              console.info("[RiftBrowser] in-window sandbox bridge ready");
            })();
        """.trimIndent()
    }

    private val sandbox = RiftBrowserSandbox(activity)
    private val chatGptAgentScript: String by lazy {
        runCatching {
            activity.assets.open(CHATGPT_AGENT_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrDefault("")
    }
    private val webView = WebView(activity)
    private var popupWebView: WebView? = null
    private var requestedVisible = false
    private var hasBounds = false
    private var destroyed = false

    init {
        CookieManager.getInstance().setAcceptCookie(true)
        configureMainWebView(webView)
        installSandboxBridge()
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
        val target = normalizeStartUrl(rawUrl).ifBlank { "https://chatgpt.com" }
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
        val params = (webView.layoutParams as? FrameLayout.LayoutParams) ?: FrameLayout.LayoutParams(safeWidth, safeHeight)
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

    fun close(): Boolean {
        if (destroyed) return true
        requestedVisible = false
        webView.visibility = View.GONE
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
        sandbox.shutdown()
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
        webView.visibility = if (requestedVisible && hasBounds) View.VISIBLE else View.GONE
        if (webView.visibility == View.VISIBLE) webView.bringToFront()
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

            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
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
                if (isChatGptPage(url)) {
                    view.evaluateJavascript(SANDBOX_BOOTSTRAP) {
                        if (chatGptAgentScript.isNotBlank() && isChatGptPage(view.url)) {
                            view.evaluateJavascript(chatGptAgentScript, null)
                        }
                    }
                }
                CookieManager.getInstance().flush()
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
            val safeName = guessed.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(180).ifBlank { "download" }
            val request = DownloadManager.Request(uri).apply {
                setMimeType(mime ?: URLConnection.guessContentTypeFromName(safeName) ?: "application/octet-stream")
                addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let { addRequestHeader("Cookie", it) }
                setTitle(safeName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, safeName)
            }
            (activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        }
    }

    private fun installSandboxBridge() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        WebViewCompat.addWebMessageListener(
            webView,
            "RiftSandbox",
            setOf(CHATGPT_ORIGIN)
        ) { _, message, sourceOrigin, isMainFrame, replyProxy ->
            if (!isMainFrame || sourceOrigin.toString() != CHATGPT_ORIGIN) return@addWebMessageListener
            val raw = message.data ?: return@addWebMessageListener
            sandbox.handleAsync(raw) { response ->
                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed) replyProxy.postMessage(response)
                }
            }
        }
    }

    private fun emitState() {
        if (destroyed) return
        stateSink(state())
    }

    private fun updateCookiePolicy(url: String?) {
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, isAuthFlowUrl(url))
    }

    private fun isChatGptPage(url: String?): Boolean {
        val uri = runCatching { Uri.parse(url.orEmpty()) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && uri.host.equals("chatgpt.com", ignoreCase = true)
    }

    private fun isAuthFlowUrl(url: String?): Boolean {
        val uri = runCatching { Uri.parse(url.orEmpty()) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val hostName = uri.host?.lowercase() ?: return false
        return hostName in AUTH_FLOW_HOSTS || hostName.endsWith(".chatgpt.com") || hostName.endsWith(".openai.com")
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
