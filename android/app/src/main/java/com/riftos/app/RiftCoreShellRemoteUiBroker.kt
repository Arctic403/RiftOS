package com.riftos.app

import android.os.Bundle
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * C1.3-D process boundary adapter for Core-issued consent/UI effects.
 * Core owns ticket allocation, single-use response settlement and grant
 * authorization. The shell receives only bounded UI work, not capabilities.
 * A lost shell leaves tickets pending until Core's existing expiry fails closed.
 */
object RiftCoreShellRemoteUiBroker {
    const val SCHEMA = "riftos.core.shell-ui/1"
    private const val MAX_QUEUE = 64
    private const val MAX_EFFECT_TEXT_BYTES = 32 * 1024
    private var ownerId = 0L
    private val pending = ArrayDeque<JSONObject>()

    @Synchronized
    fun ensureRegistered() {
        if (ownerId != 0L) return
        ownerId = RiftCoreShellCapabilityRequests.subscribe(object :
            RiftCoreShellCapabilityRequests.Client {
            override fun showConsent(request: RiftCoreShellCapabilityRequests.Consent) {
                enqueue(JSONObject().put("kind", "consent")
                    .put("ticket", request.ticket)
                    .put("appId", request.appId).put("appName", request.appName)
                    .put("capability", request.capability))
            }

            override fun executeUiEffect(request: RiftCoreShellCapabilityRequests.UiEffect) {
                val effect = request.effect
                if (effect.text.toByteArray(Charsets.UTF_8).size > MAX_EFFECT_TEXT_BYTES ||
                    effect.bytes.isNotEmpty()) {
                    RiftCoreShellCapabilityRequests.respondUiEffect(ownerId, request.ticket,
                        RiftRappCapabilityBroker.Result(ok = false, token = effect.token,
                            error = "UI effect exceeds cross-process payload bound"))
                    return
                }
                enqueue(JSONObject().put("kind", "effect")
                    .put("ticket", request.ticket)
                    .put("appId", request.appId).put("appName", request.appName)
                    .put("capability", effect.capability)
                    .put("operation", effect.operation).put("token", effect.token)
                    .put("text", effect.text))
            }
        })
    }

    @Synchronized
    private fun enqueue(event: JSONObject) {
        if (pending.size >= MAX_QUEUE) {
            val ticket = event.optLong("ticket", -1L)
            if (event.optString("kind") == "consent") {
                RiftCoreShellCapabilityRequests.respondConsent(ownerId, ticket, false)
            } else {
                RiftCoreShellCapabilityRequests.respondUiEffect(ownerId, ticket,
                    RiftRappCapabilityBroker.Result(ok = false,
                        token = event.optInt("token"),
                        error = "Core shell UI queue full"))
            }
            return
        }
        pending.addLast(event)
    }

    @Synchronized
    fun poll(): JSONObject {
        ensureRegistered()
        val work = JSONArray()
        while (pending.isNotEmpty() && work.length() < 8) {
            work.put(pending.removeFirst())
        }
        return JSONObject().put("schema", SCHEMA).put("work", work)
    }

    fun respond(data: Bundle): JSONObject {
        ensureRegistered()
        require(data.getString("schema") == SCHEMA) { "Shell UI response schema mismatch" }
        val ticket = data.getLong("ticket", -1L)
        require(ticket > 0L) { "Shell UI ticket invalid" }
        val kind = data.getString("kind").orEmpty()
        val accepted = when (kind) {
            "consent" -> RiftCoreShellCapabilityRequests.respondConsent(
                ownerId, ticket, data.getBoolean("granted", false))
            "effect" -> {
                val encoded = data.getString("bytes").orEmpty()
                require(encoded.length <= 48_000) { "Shell UI effect response exceeded bound" }
                val bytes = if (encoded.isEmpty()) ByteArray(0) else
                    Base64.decode(encoded, Base64.NO_WRAP)
                require(bytes.size <= 32 * 1024) { "Shell UI effect result exceeded bound" }
                val error = data.getString("error")?.take(2048)
                RiftCoreShellCapabilityRequests.respondUiEffect(
                    ownerId, ticket,
                    RiftRappCapabilityBroker.Result(
                        ok = data.getBoolean("ok", false),
                        token = data.getInt("token", 0),
                        text = data.getString("text")?.take(4096).orEmpty(),
                        bytes = bytes,
                        error = error
                    )
                )
            }
            else -> error("Shell UI response kind unsupported")
        }
        return JSONObject().put("schema", SCHEMA).put("accepted", accepted)
    }
}
