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
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLConnection

class MainActivity : Activity() {
    companion object {
        private const val PICK_TREE_REQUEST = 7001
        private const val FILE_CHOOSER_REQUEST = 7002
        private const val NOTIFICATION_REQUEST = 7003
        private const val APP_ORIGIN = "https://appassets.androidplatform.net"
        private const val START_URL = "$APP_ORIGIN/assets/www/index.html"
    }

    private lateinit var webView: WebView
    private lateinit var dispatcher: RiftNativeDispatcher
    private var pendingTreeRequestId: String? = null
    private var pendingNotificationRequestId: String? = null
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xff0a0d12.toInt()
        window.navigationBarColor = 0xff0a0d12.toInt()

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this)
        setContentView(webView)
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
            userAgentString = "$userAgentString RiftOS-Android/0.2"
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
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback
                return try {
                    val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST)
                    true
                } catch (_: Exception) {
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = null
                    false
                }
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
            directoryPicker = ::openDirectoryPicker,
            notificationPermissionRequester = ::requestNotificationPermission
        )

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            error("Android System WebView is too old for RiftOS native messaging. Update Android System WebView.")
        }

        WebViewCompat.addWebMessageListener(
            webView,
            "RiftAndroid",
            setOf(APP_ORIGIN)
        ) { _, message, sourceOrigin, isMainFrame, _ ->
            if (isMainFrame && sourceOrigin.toString().startsWith(APP_ORIGIN)) {
                message.data?.let { dispatcher.handleAsync(it) }
            }
        }

        if (savedInstanceState == null) webView.loadUrl(START_URL) else webView.restoreState(savedInstanceState)
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
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != NOTIFICATION_REQUEST) return
        val requestId = pendingNotificationRequestId ?: return
        pendingNotificationRequestId = null
        dispatcher.completeNotificationPermission(requestId, grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
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

    override fun onResume() { super.onResume(); if (::webView.isInitialized) webView.onResume() }
    override fun onPause() { if (::webView.isInitialized) webView.onPause(); super.onPause() }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::dispatcher.isInitialized) dispatcher.shutdown()
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        if (::webView.isInitialized) {
            webView.stopLoading(); webView.loadUrl("about:blank"); webView.removeAllViews(); webView.destroy()
        }
        super.onDestroy()
    }
}
