package com.riftos.app

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-local request broker that lets MCP control the currently mounted Workspace Live page.
 * The page remains sandboxed inside the RiftOS shell; this broker never exposes a WebView or
 * Android object directly to chatgpt.com.
 */
object RiftWorkspaceLiveController {
    private const val REQUEST_TIMEOUT_MS = 20_000L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sequence = AtomicLong(0)
    private val pending = LinkedHashMap<String, Pending>()
    @Volatile private var dispatcher: ((JSONObject, (Boolean) -> Unit) -> Unit)? = null

    private data class Pending(
        val reply: (JSONObject) -> Unit,
        val timeout: Runnable
    )

    @Synchronized
    fun attach(next: (JSONObject, (Boolean) -> Unit) -> Unit) {
        dispatcher = next
    }

    @Synchronized
    fun detach() {
        dispatcher = null
        val callbacks = pending.values.toList()
        pending.clear()
        callbacks.forEach { entry ->
            mainHandler.removeCallbacks(entry.timeout)
            entry.reply(JSONObject().put("ok", false).put("error", "Workspace Live controller detached"))
        }
    }

    fun callAsync(args: JSONObject, reply: (JSONObject) -> Unit) {
        val current = dispatcher
        if (current == null) {
            reply(JSONObject().put("ok", false).put("error", "Workspace Live controller is unavailable"))
            return
        }

        val id = "live-page-${System.currentTimeMillis()}-${sequence.incrementAndGet()}"
        val request = JSONObject(args.toString()).put("id", id)
        val timeout = Runnable { fail(id, "Workspace Live page control timed out") }
        synchronized(this) {
            pending[id] = Pending(reply, timeout)
        }
        mainHandler.postDelayed(timeout, REQUEST_TIMEOUT_MS)

        current(request) { accepted ->
            if (!accepted) fail(id, "Workspace Live page is not open")
        }
    }

    fun complete(response: JSONObject): JSONObject {
        val id = response.optString("id").trim()
        if (id.isBlank()) return JSONObject().put("accepted", false).put("error", "Missing Workspace Live control id")
        val entry = synchronized(this) { pending.remove(id) }
            ?: return JSONObject().put("accepted", false).put("id", id).put("error", "Unknown or expired Workspace Live control id")
        mainHandler.removeCallbacks(entry.timeout)
        if (response.optBoolean("ok", false)) {
            entry.reply(
                JSONObject()
                    .put("ok", true)
                    .put("value", response.opt("value") ?: JSONObject.NULL)
            )
        } else {
            entry.reply(
                JSONObject()
                    .put("ok", false)
                    .put("error", response.optString("error", "Workspace Live page control failed"))
            )
        }
        return JSONObject().put("accepted", true).put("id", id)
    }

    private fun fail(id: String, message: String) {
        val entry = synchronized(this) { pending.remove(id) } ?: return
        mainHandler.removeCallbacks(entry.timeout)
        entry.reply(JSONObject().put("ok", false).put("error", message))
    }
}
