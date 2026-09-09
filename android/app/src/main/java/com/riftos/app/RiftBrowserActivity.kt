package com.riftos.app

import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
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
                value: api,
                configurable: false,
                enumerable: false,
                writable: false
              });
              console.info("[RiftBrowser] ChatGPT sandbox bridge ready");
            })();
        """.trimIndent()
    }

    private lateinit var webView: WebView
    private lateinit var address: EditText
    private lateinit var sandbox: RiftBrowserSandbox
    private val chatGptAgentScript: String by lazy {
        runCatching {
            assets.open(CHATGPT_AGENT_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrDefault("")
    }
    private var chooser: ValueCallback<Array<Uri>>? = null
    private var popupWebView: WebView? = null

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
        sandbox = RiftBrowserSandbox(this)
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)
        root.addView(bar)
        root.addView(webView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        configureMainWebView(webView)

        val debuggingEnabled = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(debuggingEnabled)

        if (WebViewFeature.isFeatureSupported(WebViewFeature.START_SAFE_BROWSING)) {
            @Suppress("DEPRECATION")
            WebViewCompat.startSafeBrowsing(applicationContext, null)
        }

        installChatGptSandboxBridge()

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
                    if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST)
                    true
                } catch (_: Exception) {
                    chooser?.onReceiveValue(null)
                    chooser = null
                    false
                }
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean {
                if (!isUserGesture || !isAuthFlowUrl(view.url)) return false
                destroyPopup()
                val popup = WebView(this@RiftBrowserActivity)
                popupWebView = popup
                configurePopupWebView(popup)
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        return redirectPopupToMain(request.url)
                    }

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
        }

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
                address.setText(url)
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView, url: String) {
                address.setText(url)
                if (isChatGptPage(url)) {
                    view.evaluateJavascript(SANDBOX_BOOTSTRAP) {
                        if (chatGptAgentScript.isNotBlank() && isChatGptPage(view.url)) {
                            view.evaluateJavascript(chatGptAgentScript, null)
                        }
                    }
                }
                CookieManager.getInstance().flush()
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
            updateCookiePolicy(value)
            webView.loadUrl(value)
        }

        back.setOnClickListener { if (webView.canGoBack()) webView.goBack() else finish() }
        forward.setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        go.setOnClickListener { navigate() }
        address.setOnEditorActionListener { _, _, _ -> navigate(); true }

        if (savedInstanceState == null) {
            val start = normalizeStartUrl(intent.getStringExtra(EXTRA_URL)).ifBlank { "https://chatgpt.com" }
            updateCookiePolicy(start)
            address.setText(start)
            webView.loadUrl(start)
        } else {
            webView.restoreState(savedInstanceState)
            updateCookiePolicy(webView.url)
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
            safeBrowsingEnabled = true
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
            safeBrowsingEnabled = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
    }

    private fun installChatGptSandboxBridge() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        WebViewCompat.addWebMessageListener(
            webView,
            "RiftSandbox",
            setOf(CHATGPT_ORIGIN)
        ) { _, message, sourceOrigin, isMainFrame, replyProxy ->
            if (!isMainFrame || sourceOrigin.toString() != CHATGPT_ORIGIN) return@addWebMessageListener
            val raw = message.data ?: return@addWebMessageListener
            sandbox.handleAsync(raw) { response ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) replyProxy.postMessage(response)
                }
            }
        }
    }

    private fun updateCookiePolicy(url: String?) {
        val allowThirdParty = isAuthFlowUrl(url)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, allowThirdParty)
    }

    private fun isChatGptPage(url: String?): Boolean {
        val uri = runCatching { Uri.parse(url.orEmpty()) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && uri.host.equals("chatgpt.com", ignoreCase = true)
    }

    private fun isAuthFlowUrl(url: String?): Boolean {
        val uri = runCatching { Uri.parse(url.orEmpty()) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        return host in AUTH_FLOW_HOSTS || host.endsWith(".chatgpt.com") || host.endsWith(".openai.com")
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
        destroyPopup()
        if (::sandbox.isInitialized) sandbox.shutdown()
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.removeAllViews()
            webView.destroy()
        }
        super.onDestroy()
    }
}
