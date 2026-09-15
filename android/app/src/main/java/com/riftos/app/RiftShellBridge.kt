package com.riftos.app

import android.webkit.WebView
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

interface RiftShellExecutor {
    fun execute(command: String, cwd: String?, reply: (JSONObject) -> Unit)
    fun close()
}

/** Trusted compatibility RPC. A deadline ends waiting, never claims to undo side effects. */
class RiftShellBridge(private val shellWebView: WebView) : RiftShellExecutor {
    companion object {
        private const val SHELL_TIMEOUT_MS = 60_000L
        private const val DEVLAB_AGENT_TIMEOUT_MS = 90_000L
        private const val VORTEX_SESSION_TIMEOUT_MS = 105_000L
    }

    private class Request(val reply: (JSONObject) -> Unit) {
        @Volatile var timer: ScheduledFuture<*>? = null
    }
    private val pending = ConcurrentHashMap<String, Request>()
    private val deadlines = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var closed = false

    @Synchronized
    override fun execute(command: String, cwd: String?, reply: (JSONObject) -> Unit) {
        if (closed) {
            reply(JSONObject().put("ok", false).put("error", "RiftShell bridge closed"))
            return
        }
        val id = "shell-${UUID.randomUUID()}"
        val request = Request(reply)
        pending[id] = request
        val timeoutMs = when {
            command.trim().matches(Regex("^vortex\\s+(?:test-wait|validate-wait|script-wait)\\b.*", RegexOption.IGNORE_CASE)) -> VORTEX_SESSION_TIMEOUT_MS
            command.trim().matches(Regex("^(?:devlab\\s+rpc|riftos-agent\\s+devlab)\\b.*", RegexOption.IGNORE_CASE)) -> DEVLAB_AGENT_TIMEOUT_MS
            else -> SHELL_TIMEOUT_MS
        }
        // Start before posting to the UI; a blocked UI must not block the deadline.
        request.timer = deadlines.schedule({
            fail(id, "RiftShell deadline exceeded after ${timeoutMs}ms; execution outcome is unknown. " +
                "An accepted command may still be running; do not blindly retry. Request $id")
        }, timeoutMs, TimeUnit.MILLISECONDS)
        val payload = JSONObject().put("id", id).put("command", command).put("cwd", cwd ?: "/")
            .put("expiresAt", System.currentTimeMillis() + timeoutMs)
        if (!shellWebView.post {
            if (!pending.containsKey(id)) return@post
            try {
                shellWebView.evaluateJavascript(
                    "(function(){const b=window.RiftShellMcpNative;" +
                        "if(!b||typeof b.request!=='function'||typeof b.poll!=='function')" +
                        "return {ok:false,error:'RiftShell compatibility runtime is not ready'};" +
                        "return b.request(${JSONObject.quote(payload.toString())});})()"
                ) { raw ->
                    if (!pending.containsKey(id)) return@evaluateJavascript
                    val ack = runCatching { JSONObject(raw ?: "") }.getOrNull()
                    if (ack?.optBoolean("accepted", false) == true) poll(id)
                    else fail(id, ack?.optString("error")?.takeIf { it.isNotBlank() }
                        ?: "RiftShell dispatch was not acknowledged; execution outcome is unknown. Request $id")
                }
            } catch (error: Throwable) {
                fail(id, "RiftShell dispatch failed: ${error.message}")
            }
        }) fail(id, "RiftShell UI rejected dispatch")
    }

    private fun poll(id: String) {
        if (!pending.containsKey(id)) return
        shellWebView.postDelayed({
            if (!pending.containsKey(id)) return@postDelayed
            try {
                shellWebView.evaluateJavascript(
                    "window.RiftShellMcpNative?.poll(${JSONObject.quote(id)})"
                ) { raw ->
                    if (!pending.containsKey(id)) return@evaluateJavascript
                    val value = runCatching { JSONObject(raw ?: "") }.getOrNull()
                    when {
                        value?.optString("state") == "completed" -> {
                            val result = value.optJSONObject("result")
                            if (result != null) receive(result)
                            else fail(id, "RiftShell returned an invalid completion")
                        }
                        value?.optString("state") == "running" -> poll(id)
                        else -> fail(id, "RiftShell request state was lost; execution outcome is unknown. Request $id")
                    }
                }
            } catch (error: Throwable) {
                fail(id, "RiftShell result retrieval failed; execution outcome is unknown: ${error.message}")
            }
        }, 1_000L)
    }

    private fun fail(id: String, error: String) {
        receive(JSONObject().put("id", id).put("ok", false).put("error", error))
    }

    fun receive(result: JSONObject) {
        val request = pending.remove(result.optString("id")) ?: return
        request.timer?.cancel(false)
        request.reply(result)
    }

    @Synchronized
    override fun close() {
        closed = true
        pending.keys.toList().forEach { fail(it, "RiftShell bridge closed; in-flight execution outcome is unknown") }
        deadlines.shutdownNow()
    }
}
