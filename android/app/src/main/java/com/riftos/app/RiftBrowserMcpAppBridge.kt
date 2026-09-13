package com.riftos.app

import android.app.Activity
import android.net.Uri
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * Exact-origin browser-AI compatibility adapter for the local Rift MCP server.
 *
 * The page never receives a filesystem or general native-dispatcher object. It can
 * only send MCP JSON-RPC to RiftMcpServer; RiftToolHost remains the capability and
 * audit authority. This class is a page/native message bridge, not a remote relay.
 */
class RiftBrowserMcpAppBridge(
    private val activity: Activity,
    private val webView: WebView
) {
    companion object {
        private const val BRIDGE_NAME = "RiftMcpNative"
        private const val MAX_MESSAGE_BYTES = 9 * 1024 * 1024
        private val AI_ORIGINS = setOf(
            "https://chatgpt.com", "https://www.chatgpt.com",
            "https://github.com", "https://copilot.microsoft.com",
            "https://gemini.google.com", "https://google.com", "https://www.google.com",
            "https://claude.ai"
        )
    }

    private val shellBridge = RiftShellBridge(webView)
    private val toolHost = RiftMcpRuntime.toolHost(activity)
    private val server = RiftMcpRuntime.server(activity)
    private val script = buildString {
        append(activity.assets.open("adapters/ai-adapter-registry.js").bufferedReader().use { it.readText() })
        append("\n")
        append(activity.assets.open("riftbrowser-mcp-app.js").bufferedReader().use { it.readText() })
    }
    private var installed = false
    private var documentStartInstalled = false

    fun install() {
        RiftMcpRuntime.registerShellBridge(shellBridge)
        if (installed) return
        require(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            "Android System WebView is too old for Rift MCP App messaging"
        }
        WebViewCompat.addWebMessageListener(
            webView,
            BRIDGE_NAME,
            AI_ORIGINS
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
            if (request.optString("type") == "rift_shell_result") {
                RiftMcpRuntime.shellBridge()?.receive(request.optJSONObject("result") ?: JSONObject())
                return@addWebMessageListener
            }
            server.handleAsync(request, ::deliver)
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, script, AI_ORIGINS)
            documentStartInstalled = true
        }
        installed = true
    }

    fun ensureInjected(url: String?) {
        if (!installed || documentStartInstalled || !isAiUrl(url)) return
        webView.evaluateJavascript(script, null)
    }

    fun state(): JSONObject = JSONObject()
        .put("installed", installed)
        .put("mode", "rift-mcp-app-v2")
        .put("origin", "chatgpt.com")
        .put("transport", "in-process MCP JSON-RPC")
        .put("remoteRelay", false)
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
            webView.evaluateJavascript("window.RiftMcpAppNative?.__receive($payload);", null)
        }
    }

    private fun isAllowedOrigin(origin: Uri): Boolean {
        if (!origin.scheme.equals("https", ignoreCase = true)) return false
        val host = origin.host?.lowercase() ?: return false
        return host == "chatgpt.com" || host == "www.chatgpt.com" ||
            host == "github.com" || host == "www.github.com" ||
            host == "copilot.microsoft.com" ||
            host == "gemini.google.com" ||
            host == "google.com" || host == "www.google.com" ||
            host == "claude.ai" || host == "www.claude.ai"
    }

    private fun isAiUrl(url: String?): Boolean {
        val uri = runCatching { Uri.parse(url.orEmpty()) }.getOrNull() ?: return false
        return isAllowedOrigin(uri)
    }
}
