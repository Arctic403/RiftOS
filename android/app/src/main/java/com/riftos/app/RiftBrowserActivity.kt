package com.riftos.app

import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import java.net.URLEncoder
import java.net.URLConnection

class RiftBrowserActivity : Activity() {
    companion object { const val EXTRA_URL = "url"; private const val FILE_CHOOSER_REQUEST = 7101 }
    private lateinit var webView: WebView
    private lateinit var address: EditText
    private var chooser: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xff0a0d12.toInt(); window.navigationBarColor = 0xff0a0d12.toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val back = Button(this).apply { text = "‹" }
        val forward = Button(this).apply { text = "›" }
        address = EditText(this).apply { setSingleLine(true); setSelectAllOnFocus(false); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        val go = Button(this).apply { text = "Go" }
        bar.addView(back); bar.addView(forward); bar.addView(address); bar.addView(go)
        webView = WebView(this)
        root.addView(bar); root.addView(webView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)); setContentView(root)

        webView.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW; setSupportMultipleWindows(false)
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?): Boolean {
                chooser?.onReceiveValue(null); chooser = filePathCallback
                return try { startActivityForResult(fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="*/*"; addCategory(Intent.CATEGORY_OPENABLE) }, FILE_CHOOSER_REQUEST); true }
                catch (_: Exception) { chooser?.onReceiveValue(null); chooser=null; false }
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri=request.url
                if (uri.scheme == "http" || uri.scheme == "https") return false
                return try { startActivity(Intent(Intent.ACTION_VIEW, uri)); true } catch (_: Exception) { true }
            }
            override fun onPageFinished(view: WebView, url: String) { address.setText(url); super.onPageFinished(view,url) }
        }
        webView.setDownloadListener { url, userAgent, disposition, mime, _ ->
            if (!url.startsWith("http://") && !url.startsWith("https://")) return@setDownloadListener
            val name=android.webkit.URLUtil.guessFileName(url, disposition, mime)
            val request=DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mime ?: URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream")
                addRequestHeader("User-Agent",userAgent); setTitle(name); setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalFilesDir(this@RiftBrowserActivity, Environment.DIRECTORY_DOWNLOADS, name)
            }
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        }
        fun navigate() {
            var value=address.text.toString().trim(); if(value.isBlank()) return
            value = when { value.startsWith("http://")||value.startsWith("https://") -> value; value.contains('.')&&!value.contains(' ') -> "https://$value"; else -> "https://www.google.com/search?q="+URLEncoder.encode(value,"UTF-8") }
            webView.loadUrl(value)
        }
        back.setOnClickListener { if(webView.canGoBack()) webView.goBack() else finish() }
        forward.setOnClickListener { if(webView.canGoForward()) webView.goForward() }
        go.setOnClickListener { navigate() }; address.setOnEditorActionListener { _,_,_-> navigate(); true }
        val start=intent.getStringExtra(EXTRA_URL).orEmpty().ifBlank { "https://chatgpt.com" }; address.setText(start); webView.loadUrl(start)
    }

    @Deprecated("Activity result compatibility path")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==FILE_CHOOSER_REQUEST){ val cb=chooser?:return; chooser=null; cb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode,data)) }
    }
    override fun onBackPressed(){ if(webView.canGoBack()) webView.goBack() else super.onBackPressed() }
    override fun onDestroy(){ chooser?.onReceiveValue(null); chooser=null; webView.destroy(); super.onDestroy() }
}
