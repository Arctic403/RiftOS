package com.riftos.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * Disposable graphical-shell CLIENT of Core capability requests.
 * It displays consent and implements UI-only clipboard/share/window operations.
 * It cannot write Core grants, sign APKs, run builds or access Core filesystem.
 */
class RiftRappShellCapabilityClient(
    private val activity: Activity,
    private val desktop: RiftNativeDesktop
) {
    private var subscription = 0L

    init {
        subscription = RiftCoreShellCapabilityRequests.subscribe(
        object : RiftCoreShellCapabilityRequests.Client {
            override fun showConsent(request: RiftCoreShellCapabilityRequests.Consent) {
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) {
                        RiftCoreShellCapabilityRequests.respondConsent(subscription, request.ticket, false)
                        return@runOnUiThread
                    }
                    AlertDialog.Builder(activity)
                        .setTitle("RiftOS permission")
                        .setMessage("${request.appName} wants permission: ${request.capability}")
                        .setPositiveButton("Allow") { _, _ ->
                            RiftCoreShellCapabilityRequests.respondConsent(subscription, request.ticket, true)
                        }
                        .setNegativeButton("Deny") { _, _ ->
                            RiftCoreShellCapabilityRequests.respondConsent(subscription, request.ticket, false)
                        }
                        .setOnCancelListener {
                            RiftCoreShellCapabilityRequests.respondConsent(subscription, request.ticket, false)
                        }
                        .show()
                }
            }

            override fun executeUiEffect(request: RiftCoreShellCapabilityRequests.UiEffect) {
                activity.runOnUiThread {
                    val result = if (activity.isFinishing || activity.isDestroyed) {
                        RiftRappCapabilityBroker.Result(ok = false, token = request.effect.token, error = "Shell UI detached")
                    } else runCatching {
                        performUiEffect(request)
                    }.getOrElse { error ->
                        RiftRappCapabilityBroker.Result(
                            ok = false, token = request.effect.token,
                            error = error.message ?: error.javaClass.simpleName
                        )
                    }
                    RiftCoreShellCapabilityRequests.respondUiEffect(subscription, request.ticket, result)
                }
            }
        }
    )
    }

    fun destroy() {
        RiftCoreShellCapabilityRequests.unsubscribe(subscription)
    }

    private fun performUiEffect(request: RiftCoreShellCapabilityRequests.UiEffect): RiftRappCapabilityBroker.Result {
        val effect = request.effect
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        when (effect.capability to effect.operation) {
            RiftAppAbi.Capability.CLIPBOARD_READ to "read" -> {
                val value = clipboard.primaryClip?.getItemAt(0)?.coerceToText(activity)?.toString().orEmpty()
                val bytes = value.toByteArray(Charsets.UTF_8)
                require(bytes.size <= 256 * 1024) { "Clipboard result exceeded bound" }
                return RiftRappCapabilityBroker.Result(ok = true, token = effect.token, bytes = bytes)
            }
            RiftAppAbi.Capability.CLIPBOARD_WRITE to "write" -> {
                require(effect.text.length <= 64_000) { "Clipboard text exceeded bound" }
                clipboard.setPrimaryClip(ClipData.newPlainText("RiftOS program", effect.text))
            }
            RiftAppAbi.Capability.SHARE to "text" -> {
                require(effect.text.length <= 256_000) { "Share text exceeded bound" }
                activity.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, effect.text)
                        },
                        request.appName
                    )
                )
            }
            RiftAppAbi.Capability.WINDOW_TITLE to "set" -> {
                require(effect.text.isNotBlank() && effect.text.length <= 96) { "Invalid window title" }
                desktop.handle(
                    "desktop.window.title",
                    JSONObject().put("id", request.appId).put("title", effect.text)
                )
            }
            else -> error("Unsupported shell UI effect")
        }
        return RiftRappCapabilityBroker.Result(ok = true, token = effect.token)
    }
}
