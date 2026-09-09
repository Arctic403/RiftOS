package com.riftos.app

import android.app.Activity
import android.net.Uri
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * RiftBrowser compatibility adapter for ChatGPT Web plans that cannot register
 * a custom MCP app directly.
 *
 * The page never receives a filesystem/native dispatcher object. It can only send
 * MCP JSON-RPC to the in-process RiftMcpServer, which still enforces RiftToolHost
 * permissions and audit. Injection is restricted to the ChatGPT main-frame origin.
 */
class RiftBrowserMcpAppBridge(
    private val activity: Activity,
    private val webView: WebView
) {
    companion object {
        private const val BRIDGE_NAME = "RiftMcpNative"
        private const val MAX_MESSAGE_BYTES = 9 * 1024 * 1024
        private val CHATGPT_ORIGINS = setOf("https://chatgpt.com", "https://www.chatgpt.com")
    }

    private val toolHost = RiftMcpRuntime.toolHost(activity)
    private val server = RiftMcpServer(toolHost)
    private val script = activity.assets.open("riftbrowser-mcp-app.js").bufferedReader().use { it.readText() }
    private var installed = false
    private var documentStartInstalled = false

    fun install() {
        if (installed) return
        require(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            "Android System WebView is too old for Rift MCP App messaging"
        }
        WebViewCompat.addWebMessageListener(
            webView,
            BRIDGE_NAME,
            CHATGPT_ORIGINS
        ) { _, message, sourceOrigin, isMainFrame, _ ->
            if (!isMainFrame || !isAllowedOrigin(sourceOrigin)) return@addWebMessageListener
            val raw = message.data ?: return@addWebMessageListener
            if (raw.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_BYTES) {
                deliver(
                    JSONObject()
                        .put("jsonrpc", "2.0")
                        .put("id", JSONObject.NULL)
                        .put("error", JSONObject().put("code", -32001).put("message", "Rift MCP message too large"))
                )
                return@addWebMessageListener
            }
            val request = runCatching { JSONObject(raw) }.getOrElse {
                deliver(
                    JSONObject()
                        .put("jsonrpc", "2.0")
                        .put("id", JSONObject.NULL)
                        .put("error", JSONObject().put("code", -32700).put("message", "Invalid Rift MCP JSON"))
                )
                return@addWebMessageListener
            }
            server.handleAsync(request, ::deliver)
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, script, CHATGPT_ORIGINS)
            documentStartInstalled = true
        }
        installed = true
    }

    fun ensureInjected(url: String?) {
        if (!installed || documentStartInstalled || !isChatGptUrl(url)) return
        webView.evaluateJavascript(script, null)
    }

    fun state(): JSONObject = JSONObject()
        .put("installed", installed)
        .put("mode", "rift-mcp-app-v1")
        .put("origin", "chatgpt.com")
        .put("transport", "in-process MCP JSON-RPC")
        .put("tools", toolHost.tools().length())
        .put("access", toolHost.access())

    fun destroy() {
        if (!installed) return
        runCatching { WebViewCompat.removeWebMessageListener(webView, BRIDGE_NAME) }
        installed = false
    }

    private fun deliver(response: JSONObject) {
        val payload = response.toString()
        webView.post {
            if (activity.isFinishing) return@post
            webView.evaluateJavascript(
                "window.RiftMcpAppNative?.__receive($payload);",
                null
            )
        }
    }

    private fun isAllowedOrigin(origin: Uri): Boolean {
        if (!origin.scheme.equals("https", ignoreCase = true)) return false
        val host = origin.host?.lowercase() ?: return false
        return host == "chatgpt.com" || host == "www.chatgpt.com"
    }

    private fun isChatGptUrl(url: String?): Boolean {
        val uri = runCatching { Uri.parse(url.orEmpty()) }.getOrNull() ?: return false
        return isAllowedOrigin(uri)
    }
}
