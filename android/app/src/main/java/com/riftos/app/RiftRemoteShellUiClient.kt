package com.riftos.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * C1.3-D graphical-only UI/effect client for the Core-owned consent broker.
 * No grant persistence or Core capability execution occurs in the shell.
 */
class RiftRemoteShellUiClient(
    private val activity: Activity,
    private val desktop: RiftNativeDesktop,
    private val ipc: RiftShellCoreClient,
    private val openApp: (String) -> Unit,
    private val dispatchCommand: (String, String) -> Unit
) {
    private val worker = Executors.newSingleThreadScheduledExecutor { action ->
        Thread(action, "rift-shell-ui-ipc").apply { isDaemon = true }
    }
    @Volatile private var active = false
    @Volatile private var closed = false
    init {
        worker.scheduleWithFixedDelay({
            if (!active || closed) return@scheduleWithFixedDelay
            val response = runCatching { ipc.pollUi() }.getOrNull()
                ?: return@scheduleWithFixedDelay
            val launches = response.optJSONArray("launches")
            if (launches != null) {
                for (index in 0 until launches.length().coerceAtMost(8)) {
                    val id = launches.optString(index)
                    if (id.isBlank() || id.length > 128) continue
                    activity.runOnUiThread {
                        if (!closed && active && !activity.isFinishing && !activity.isDestroyed) {
                            openApp(id)
                        }
                    }
                }
            }
            val commands = response.optJSONArray("commands")
            if (commands != null) {
                for (index in 0 until commands.length().coerceAtMost(8)) {
                    val item = commands.optJSONObject(index) ?: continue
                    val method = item.optString("method")
                    val arg = item.optString("argument")
                    if (method !in setOf("open", "close", "browser") || arg.length > 2048) continue
                    activity.runOnUiThread {
                        if (!closed && active && !activity.isFinishing && !activity.isDestroyed) {
                            dispatchCommand(method, arg)
                        }
                    }
                }
            }
            val work = response.getJSONArray("work")
            for (index in 0 until work.length()) {
                val request = work.optJSONObject(index) ?: continue
                activity.runOnUiThread {
                    if (closed || activity.isFinishing || activity.isDestroyed) {
                        deny(request)
                    } else {
                        when (request.optString("kind")) {
                            "consent" -> showConsent(request)
                            "effect" -> executeUiEffect(request)
                            else -> deny(request)
                        }
                    }
                }
            }
        }, 100L, 350L, TimeUnit.MILLISECONDS)
    }
    fun resume() { active = true }
    fun pause() { active = false }
    fun destroy() {
        closed = true
        active = false
        worker.shutdownNow()
    }

    private fun submit(block: () -> Unit) {
        // The UI never blocks on Binder or a Core capability callback.
        runCatching { worker.execute { runCatching { block() } } }
    }

    private fun deny(request: JSONObject) {
        val ticket = request.optLong("ticket", -1L)
        if (ticket <= 0L) return
        if (request.optString("kind") == "consent") {
            submit { ipc.respondConsent(ticket, false) }
        } else submit {
            ipc.respondEffect(ticket, RiftRappCapabilityBroker.Result(
                ok = false, token = request.optInt("token"),
                error = "RiftShell UI is unavailable"))
        }
    }

    private fun showConsent(request: JSONObject) {
        val ticket = request.getLong("ticket")
        val capability = request.optString("capability").take(80)
        val name = request.optString("appName").take(96)
        var answered = false
        fun answer(allow: Boolean) {
            if (answered) return
            answered = true
            submit { ipc.respondConsent(ticket, allow) }
        }
        AlertDialog.Builder(activity)
            .setTitle("RiftOS permission")
            .setMessage("$name wants permission: $capability")
            .setPositiveButton("Allow") { _, _ -> answer(true) }
            .setNegativeButton("Deny") { _, _ -> answer(false) }
            .setNeutralButton("Cancel") { _, _ -> answer(false) }
            .setOnCancelListener { answer(false) }
            .show()
    }

    private fun executeUiEffect(request: JSONObject) {
        val ticket = request.getLong("ticket")
        val token = request.optInt("token")
        val response = runCatching {
            val capability = request.getString("capability")
            val operation = request.getString("operation")
            val value = request.optString("text")
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            when (capability to operation) {
                RiftAppAbi.Capability.CLIPBOARD_READ to "read" -> {
                    val contents = clipboard.primaryClip?.getItemAt(0)
                        ?.coerceToText(activity)?.toString().orEmpty()
                    val bytes = contents.toByteArray(Charsets.UTF_8)
                    require(bytes.size <= 32 * 1024) { "Clipboard exceeds IPC bound" }
                    RiftRappCapabilityBroker.Result(ok = true, token = token, bytes = bytes)
                }
                RiftAppAbi.Capability.CLIPBOARD_WRITE to "write" -> {
                    require(value.length <= 32 * 1024) { "Clipboard exceeds IPC bound" }
                    clipboard.setPrimaryClip(ClipData.newPlainText("RiftOS program", value))
                    RiftRappCapabilityBroker.Result(ok = true, token = token)
                }
                RiftAppAbi.Capability.SHARE to "text" -> {
                    require(value.length <= 32 * 1024) { "Share exceeds IPC bound" }
                    activity.startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, value)
                        }, request.optString("appName")))
                    RiftRappCapabilityBroker.Result(ok = true, token = token)
                }
                RiftAppAbi.Capability.WINDOW_TITLE to "set" -> {
                    require(value.isNotBlank() && value.length <= 96) { "Window title invalid" }
                    desktop.handle("desktop.window.title",
                        JSONObject().put("id", request.getString("appId"))
                            .put("title", value))
                    RiftRappCapabilityBroker.Result(ok = true, token = token)
                }
                else -> error("Unsupported remote shell UI effect")
            }
        }.getOrElse { error ->
            RiftRappCapabilityBroker.Result(ok = false, token = token,
                error = error.message ?: error.javaClass.simpleName)
        }
        submit { ipc.respondEffect(ticket, response) }
    }
}
