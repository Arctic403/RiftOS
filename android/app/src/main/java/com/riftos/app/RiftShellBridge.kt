package com.riftos.app

import android.webkit.WebView
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Native to trusted RiftOS shell-runtime RPC bridge.
 *
 * Keeps MCP from executing an Android/Linux shell directly. Requests are forwarded to
 * the existing RiftShell runtime in MainActivity's shell WebView and results return
 * through the shell's exact-origin RiftAndroid WebMessage channel.
 */
class RiftShellBridge(private val shellWebView: WebView) {
    companion object {
        private const val SHELL_TIMEOUT_MS = 60_000L
        private const val VORTEX_SESSION_TIMEOUT_MS = 105_000L
    }

    private val pending = ConcurrentHashMap<String, (JSONObject) -> Unit>()
    @Volatile private var closed = false

    fun execute(command: String, cwd: String?, reply: (JSONObject) -> Unit) {
        if (closed) {
            reply(JSONObject().put("ok", false).put("error", "RiftShell bridge closed"))
            return
        }
        val id = "shell-${UUID.randomUUID()}"
        pending[id] = reply
        val payload = JSONObject()
            .put("id", id)
            .put("command", command)
            .put("cwd", cwd ?: "/")

        shellWebView.post {
            if (!pending.containsKey(id)) return@post
            if (closed) {
                pending.remove(id)?.invoke(JSONObject().put("id", id).put("ok", false).put("error", "RiftShell bridge closed"))
                return@post
            }
            shellWebView.evaluateJavascript(
                "window.RiftShellMcpNative?.request(${JSONObject.quote(payload.toString())});",
                null
            )
            val timeoutMs = if (command.trim().matches(Regex("^vortex\\s+(?:test-wait|validate-wait|script-wait)\\b.*", RegexOption.IGNORE_CASE))) {
                VORTEX_SESSION_TIMEOUT_MS
            } else SHELL_TIMEOUT_MS
            shellWebView.postDelayed({
                pending.remove(id)?.invoke(
                    JSONObject()
                        .put("id", id)
                        .put("ok", false)
                        .put("error", "RiftShell bridge timed out")
                )
            }, timeoutMs)
        }
    }

    fun receive(result: JSONObject) {
        val id = result.optString("id")
        pending.remove(id)?.invoke(result)
    }

    fun close() {
        closed = true
        val callbacks = pending.entries.toList()
        pending.clear()
        callbacks.forEach { (id, reply) ->
            reply(JSONObject().put("id", id).put("ok", false).put("error", "RiftShell bridge closed"))
        }
    }
}
