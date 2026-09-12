package com.riftos.app

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLConnection
import java.util.concurrent.Executors

class MainActivity : Activity() {
    companion object {
        private const val PICK_TREE_REQUEST = 7001
        private const val FILE_CHOOSER_REQUEST = 7002
        private const val NOTIFICATION_REQUEST = 7003
        private const val SYSTEM_DUMP_REQUEST = 7004
        private const val APP_ORIGIN = "https://appassets.androidplatform.net"
        private const val START_URL = "$APP_ORIGIN/assets/www/index.html"
    }

    private lateinit var rootView: FrameLayout
    private lateinit var webView: WebView
    private lateinit var browserWindow: RiftBrowserWindow
    private lateinit var dispatcher: RiftNativeDispatcher
    private lateinit var systemDump: RiftSystemDump
    private lateinit var workspaceWatcher: RiftWorkspaceWatcher
    private val kernelExecutor = Executors.newSingleThreadExecutor()
    private var pendingTreeRequestId: String? = null
    private var pendingNotificationRequestId: String? = null
    private var pendingSystemDumpRequestId: String? = null
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Target SDK 35+ is edge-to-edge by default. Own the insets explicitly so the
        // WebView viewport never extends behind Samsung's side navigation bar/cutout.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = 0xff0a0d12.toInt()
        window.navigationBarColor = 0xff0a0d12.toInt()

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        rootView = FrameLayout(this).apply {
            clipChildren = true
            clipToPadding = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val safe = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            if (view.paddingLeft != safe.left || view.paddingTop != safe.top ||
                view.paddingRight != safe.right || view.paddingBottom != safe.bottom
            ) {
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            }
            windowInsets
        }
        webView = WebView(this).apply {
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            overScrollMode = android.view.View.OVER_SCROLL_NEVER
        }
        systemDump = RiftSystemDump(this)
        workspaceWatcher = RiftWorkspaceWatcher(this, ::sendWorkspaceEvent)
        rootView.addView(
            webView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        setContentView(rootView)
        ViewCompat.requestApplyInsets(rootView)
        browserWindow = RiftBrowserWindow(
            activity = this,
            host = rootView,
            launchFileChooser = ::launchFileChooser,
            stateSink = ::sendBrowserWindowState
        )
        CookieManager.getInstance().setAcceptCookie(true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            userAgentString = "$userAgentString RiftOS-Android/0.9.1"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }

        val debuggingEnabled = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(debuggingEnabled)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                val callback = filePathCallback ?: return false
                return launchFileChooser(callback, fileChooserParams)
            }
        }

        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            @Deprecated("Deprecated in Android WebView")
            override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(Uri.parse(url))

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (!request.isForMainFrame || uri.host == "appassets.androidplatform.net") return false
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                } catch (_: Exception) {
                    true
                }
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (!url.startsWith("http://") && !url.startsWith("https://")) return@setDownloadListener
            val guessed = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType ?: URLConnection.guessContentTypeFromName(guessed) ?: "application/octet-stream")
                addRequestHeader("User-Agent", userAgent)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setTitle(guessed)
                setDestinationInExternalFilesDir(this@MainActivity, Environment.DIRECTORY_DOWNLOADS, guessed)
            }
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        }

        dispatcher = RiftNativeDispatcher(
            activity = this,
            resultSink = ::sendNativeResult,
            progressSink = ::sendNativeProgress,
            directoryPicker = ::openDirectoryPicker,
            notificationPermissionRequester = ::requestNotificationPermission
        )

        // The relay is outbound-only and starts only after the user enables and configures it.
        RiftMcpRuntime.relayClient(this).start()

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            error("Android System WebView is too old for RiftOS native messaging. Update Android System WebView.")
        }

        WebViewCompat.addWebMessageListener(
            webView,
            "RiftAndroid",
            setOf(APP_ORIGIN)
        ) { _, message, sourceOrigin, isMainFrame, _ ->
            if (isMainFrame && sourceOrigin.toString().startsWith(APP_ORIGIN)) {
                message.data?.let { raw ->
                    if (!handleKernelRequest(raw)) dispatcher.handleAsync(raw)
                }
            }
        }

        if (savedInstanceState == null) webView.loadUrl(START_URL) else webView.restoreState(savedInstanceState)
    }

    private fun handleKernelRequest(raw: String): Boolean {
        val message = runCatching { JSONObject(raw) }.getOrNull() ?: return false
        val method = message.optString("method")
        val requestId = message.optString("id")
        val args = message.optJSONObject("args") ?: JSONObject()
        if (requestId.isBlank()) return method == "system.dump.save" || method.startsWith("browser.window.")

        when (method) {
            "system.dump.save" -> openSystemDumpPicker(requestId)
            "browser.window.open" -> runBrowserCommand(requestId) { browserWindow.open(args.optString("url", "https://chatgpt.com")) }
            "browser.window.navigate" -> runBrowserCommand(requestId) { browserWindow.navigate(args.optString("url", "https://chatgpt.com")) }
            "browser.window.back" -> runBrowserCommand(requestId) { browserWindow.back() }
            "browser.window.forward" -> runBrowserCommand(requestId) { browserWindow.forward() }
            "browser.window.reload" -> runBrowserCommand(requestId) { browserWindow.reload() }
            "browser.window.bounds" -> runBrowserCommand(requestId) { browserWindow.setBounds(args) }
            "browser.window.visible" -> runBrowserCommand(requestId) { browserWindow.setVisible(args.optBoolean("visible", true)) }
            "browser.window.state" -> runBrowserCommand(requestId) { browserWindow.state() }
            "browser.window.close" -> runBrowserCommand(requestId) { JSONObject().put("closed", browserWindow.close()) }
            "workspace.watch.start" -> runKernelCommand(requestId) { workspaceWatcher.start() }
            "workspace.watch.stop" -> runKernelCommand(requestId) { workspaceWatcher.stop() }
            "workspace.watch.state" -> runKernelCommand(requestId) { workspaceWatcher.state() }
            else -> return false
        }
        return true
    }

    private fun runKernelCommand(requestId: String, command: () -> Any?) {
        try {
            sendNativeResult(requestId, true, command(), null)
        } catch (error: Throwable) {
            sendNativeResult(requestId, false, null, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun runBrowserCommand(requestId: String, command: () -> Any?) {
        runOnUiThread {
            try {
                sendNativeResult(requestId, true, command(), null)
            } catch (error: Throwable) {
                sendNativeResult(requestId, false, null, error.message ?: error.javaClass.simpleName)
            }
        }
    }

    private fun sendWorkspaceEvent(event: JSONObject) {
        val script = "window.RiftWorkspaceNative?.__event(${event});"
        runOnUiThread {
            if (!isFinishing && ::webView.isInitialized) webView.evaluateJavascript(script, null)
        }
    }

    private fun sendBrowserWindowState(state: JSONObject) {
        val script = "window.RiftBrowserNative?.__state(${state});"
        runOnUiThread {
            if (!isFinishing && ::webView.isInitialized) webView.evaluateJavascript(script, null)
        }
    }

    fun openDesktopBrowser(rawUrl: String) {
        val urlJs = JSONObject.quote(rawUrl.ifBlank { "https://chatgpt.com" })
        val script = "window.RiftDesktop?.openBrowser($urlJs);"
        runOnUiThread {
            if (!isFinishing && ::webView.isInitialized) webView.evaluateJavascript(script, null)
        }
    }

    private fun launchFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams?
    ): Boolean {
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = callback
        return try {
            val intent = params?.createIntent() ?: Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            if (params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            startActivityForResult(intent, FILE_CHOOSER_REQUEST)
            true
        } catch (_: Exception) {
            fileChooserCallback?.onReceiveValue(null)
            fileChooserCallback = null
            false
        }
    }

    private fun openSystemDumpPicker(requestId: String) {
        runOnUiThread {
            if (pendingSystemDumpRequestId != null) {
                sendNativeResult(requestId, false, null, "A system dump save is already open")
                return@runOnUiThread
            }
            pendingSystemDumpRequestId = requestId
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, systemDump.defaultFileName())
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            try {
                startActivityForResult(intent, SYSTEM_DUMP_REQUEST)
            } catch (error: Exception) {
                pendingSystemDumpRequestId = null
                sendNativeResult(requestId, false, null, error.message ?: "Could not open system dump picker")
            }
        }
    }

    private fun openDirectoryPicker(requestId: String) {
        runOnUiThread {
            pendingTreeRequestId = requestId
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            }
            startActivityForResult(intent, PICK_TREE_REQUEST)
        }
    }

    private fun requestNotificationPermission(requestId: String) {
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            dispatcher.completeNotificationPermission(requestId, true)
            return
        }
        pendingNotificationRequestId = requestId
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
    }

    @Deprecated("Activity result compatibility path")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            PICK_TREE_REQUEST -> {
                val requestId = pendingTreeRequestId ?: return
                pendingTreeRequestId = null
                val uri = data?.data
                if (resultCode != RESULT_OK || uri == null) {
                    dispatcher.cancelDirectoryPick(requestId)
                    return
                }
                val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                try { contentResolver.takePersistableUriPermission(uri, flags) } catch (_: SecurityException) {}
                dispatcher.completeDirectoryPick(requestId, uri)
            }
            FILE_CHOOSER_REQUEST -> {
                val callback = fileChooserCallback ?: return
                fileChooserCallback = null
                callback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            }
            SYSTEM_DUMP_REQUEST -> {
                val requestId = pendingSystemDumpRequestId ?: return
                pendingSystemDumpRequestId = null
                val uri = data?.data
                if (resultCode != RESULT_OK || uri == null) {
                    sendNativeResult(
                        requestId,
                        true,
                        JSONObject().put("saved", false).put("cancelled", true),
                        null
                    )
                    return
                }
                kernelExecutor.execute {
                    try {
                        sendNativeResult(requestId, true, systemDump.save(uri), null)
                    } catch (error: Throwable) {
                        sendNativeResult(requestId, false, null, error.message ?: error.javaClass.simpleName)
                    }
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != NOTIFICATION_REQUEST) return
        val requestId = pendingNotificationRequestId ?: return
        pendingNotificationRequestId = null
        dispatcher.completeNotificationPermission(requestId, grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
    }

    private fun sendNativeProgress(value: JSONObject) {
        val script = "window.RiftTransferUI?.__progress(${value});"
        runOnUiThread { if (!isFinishing) webView.evaluateJavascript(script, null) }
    }

    private fun sendNativeResult(id: String, ok: Boolean, value: Any?, error: String?) {
        val valueJs = when (value) {
            null -> "null"
            is JSONObject, is JSONArray -> value.toString()
            is Boolean, is Number -> value.toString()
            else -> JSONObject.quote(value.toString())
        }
        val errorJs = if (error == null) "null" else JSONObject.quote(error)
        val script = "window.RiftNative?.__resolve(${JSONObject.quote(id)},${if (ok) "true" else "false"},$valueJs,$errorJs);"
        runOnUiThread { if (!isFinishing) webView.evaluateJavascript(script, null) }
    }

    override fun onBackPressed() {
        if (!::webView.isInitialized) return super.onBackPressed()
        webView.evaluateJavascript("Boolean(window.RiftAndroidBack?.())") { result ->
            if (result == "true") return@evaluateJavascript
            if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
        if (::browserWindow.isInitialized) browserWindow.onResume()
    }

    override fun onPause() {
        if (::browserWindow.isInitialized) browserWindow.onPause()
        if (::webView.isInitialized) webView.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::workspaceWatcher.isInitialized) workspaceWatcher.shutdown()
        if (::dispatcher.isInitialized) dispatcher.shutdown()
        if (::browserWindow.isInitialized) browserWindow.destroy()
        kernelExecutor.shutdownNow()
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        pendingSystemDumpRequestId = null
        if (::webView.isInitialized) {
            webView.stopLoading(); webView.loadUrl("about:blank"); webView.removeAllViews(); webView.destroy()
        }
        super.onDestroy()
    }
}
