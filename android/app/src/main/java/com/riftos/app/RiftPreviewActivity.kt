package com.riftos.app

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.io.FileInputStream
import java.net.URLConnection

class RiftPreviewActivity : Activity() {
    companion object { const val EXTRA_ROOT="root"; const val EXTRA_ENTRY="entry"; private const val HOST="riftpreview.local" }
    private lateinit var webView: WebView
    private lateinit var previewRoot: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=0xff0a0d12.toInt(); window.navigationBarColor=0xff0a0d12.toInt()
        val workspace=File(filesDir,"riftfs/workspace").canonicalFile
        val rootParts=normalize(intent.getStringExtra(EXTRA_ROOT).orEmpty())
        previewRoot=rootParts.fold(workspace){ current,segment->File(current,segment) }.canonicalFile
        require(previewRoot==workspace||previewRoot.path.startsWith(workspace.path+File.separator)){"Preview root escaped workspace"}
        require(previewRoot.exists()&&previewRoot.isDirectory){"Preview root does not exist"}
        webView=WebView(this); setContentView(webView)
        webView.settings.apply { javaScriptEnabled=true; domStorageEnabled=true; mediaPlaybackRequiresUserGesture=false; mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW; allowFileAccess=false; allowContentAccess=false }
        webView.webViewClient=object:WebViewClient(){
            override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse?{
                if(request.url.host!=HOST)return null
                return responseFor(request.url)
            }
        }
        val entry=normalize(intent.getStringExtra(EXTRA_ENTRY).orEmpty().ifBlank{"index.html"}).joinToString("/")
        webView.loadUrl("https://$HOST/$entry")
    }

    private fun normalize(raw:String):List<String>{
        val out=mutableListOf<String>()
        raw.replace('\\','/').split('/').forEach{ part->when{part.isBlank()||part=="."->Unit;part==".."->if(out.isNotEmpty())out.removeAt(out.lastIndex);else->out.add(part)} }
        return out
    }
    private fun fileFor(path:String):File?{
        val parts=normalize(path); var file=previewRoot
        parts.forEach{file=File(file,it)}
        file=file.canonicalFile
        if(file!=previewRoot&&!file.path.startsWith(previewRoot.path+File.separator))return null
        if(file.isDirectory)file=File(file,"index.html").canonicalFile
        if(!file.exists()||!file.isFile){
            val fallback=File(previewRoot,"index.html").canonicalFile
            if(parts.lastOrNull()?.contains('.')==false&&fallback.isFile)return fallback
            return null
        }
        return file
    }
    private fun responseFor(uri:Uri):WebResourceResponse{
        val file=fileFor(uri.path.orEmpty())?:return WebResourceResponse("text/plain","UTF-8",404,"Not Found",mapOf("Cache-Control" to "no-store"),"Not Found".byteInputStream())
        val mime=URLConnection.guessContentTypeFromName(file.name)?:when(file.extension.lowercase()){ "js","mjs"->"text/javascript";"json"->"application/json";"svg"->"image/svg+xml";"wasm"->"application/wasm";else->"application/octet-stream" }
        val encoding=if(mime.startsWith("text/")||mime in setOf("application/json","text/javascript","image/svg+xml"))"UTF-8" else null
        return WebResourceResponse(mime,encoding,FileInputStream(file))
    }
    override fun onBackPressed(){if(webView.canGoBack())webView.goBack()else super.onBackPressed()}
    override fun onDestroy(){webView.destroy();super.onDestroy()}
}
