package com.riftos.app

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.File

class MainActivity : Activity() {
    private lateinit var webView: WebView

    companion object {
        private const val REQUEST_GGUF = 4107
    }

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

    fun chooseGgufModel() {
        runOnUiThread {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/octet-stream"
            }
            startActivityForResult(intent, REQUEST_GGUF)
        }
    }

    @Deprecated("Legacy activity result is used to keep the host dependency-light")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_GGUF || resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        Thread {
            try {
                val name = safeDisplayName(uri)
                val models = File(filesDir, "riftfs/models").apply { mkdirs() }
                val target = File(models, name)
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Could not open selected model" }
                    target.outputStream().use { output -> input.copyTo(output, 1024 * 1024) }
                }
                emitModelImported("/models/${target.name}", null)
            } catch (t: Throwable) {
                emitModelImported(null, t.message ?: t.javaClass.simpleName)
            }
        }.start()
    }

    private fun safeDisplayName(uri: Uri): String {
        var displayName = "model.gguf"
        val cursor: Cursor? = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) displayName = it.getString(0) ?: displayName
        }
        val safe = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (safe.endsWith(".gguf", ignoreCase = true)) safe else "$safe.gguf"
    }

    private fun emitModelImported(path: String?, error: String?) {
        val payload = JSONObject()
            .put("ok", error == null)
            .put("path", path)
            .put("error", error)
            .toString()
        runOnUiThread {
            webView.evaluateJavascript(
                "window.RiftNativeEvents?.modelImported(${JSONObject.quote(payload)})",
                null
            )
        }
    }

    override fun onDestroy() {
        webView.removeJavascriptInterface("RiftNative")
        webView.destroy()
        super.onDestroy()
    }
}
