package com.riftos.app

import android.webkit.WebView
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Native to RiftOS web-runtime shell RPC bridge.
 *
 * Keeps MCP from executing an OS shell directly. Requests are forwarded to the
 * existing RiftShell runtime and results are correlated by id.
 */
class RiftShellBridge(private val webView: WebView) {
    private val pending = ConcurrentHashMap<String, (JSONObject) -> Unit>()

    fun execute(command: String, cwd: String?, reply: (JSONObject) -> Unit) {
        val id = "shell-${UUID.randomUUID()}"
        pending[id] = reply
        val payload = JSONObject()
            .put("id", id)
            .put("command", command)
            .put("cwd", cwd ?: "/")

        webView.post {
            webView.evaluateJavascript(
                "window.RiftShellMcpNative?.request(${JSONObject.quote(payload.toString())});",
                null
            )
        }
    }

    fun receive(result: JSONObject) {
        val id = result.optString("id")
        pending.remove(id)?.invoke(result)
    }

    fun clear() {
        pending.clear()
    }
}
