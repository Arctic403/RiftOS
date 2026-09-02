package com.riftos.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    companion object {
        private const val PICK_TREE_REQUEST = 7001
        private const val APP_ORIGIN = "https://appassets.androidplatform.net"
        private const val START_URL = "$APP_ORIGIN/assets/www/index.html"
    }

    private lateinit var webView: WebView
    private lateinit var dispatcher: RiftNativeDispatcher
    private var pendingTreeRequestId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            userAgentString = "$userAgentString RiftOS-Android/0.1"
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

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

        dispatcher = RiftNativeDispatcher(
            activity = this,
            resultSink = ::sendNativeResult,
            directoryPicker = ::openDirectoryPicker
        )

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            error("This Android System WebView is too old for RiftOS native messaging. Update Android System WebView.")
        }

        WebViewCompat.addWebMessageListener(
            webView,
            "RiftAndroid",
            setOf(APP_ORIGIN)
        ) { _, message, sourceOrigin, isMainFrame, _ ->
            if (isMainFrame && sourceOrigin.toString().startsWith(APP_ORIGIN)) {
                dispatcher.handleAsync(message.data)
            }
        }

        if (savedInstanceState == null) webView.loadUrl(START_URL)
        else webView.restoreState(savedInstanceState)
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

    @Deprecated("Activity result compatibility path")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_TREE_REQUEST) return

        val requestId = pendingTreeRequestId ?: return
        pendingTreeRequestId = null
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            dispatcher.cancelDirectoryPick(requestId)
            return
        }

        val flags = data.flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            // Some providers do not expose persistable grants; the mount still works for this session.
        }
        dispatcher.completeDirectoryPick(requestId, uri)
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
        runOnUiThread { webView.evaluateJavascript(script, null) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        dispatcher.shutdown()
        webView.destroy()
        super.onDestroy()
    }
}
