package com.riftos.app

import android.content.Context
import java.net.URI
import java.util.UUID

data class RiftRelayConfig(
    val enabled: Boolean,
    val endpoint: String,
    val token: String?,
    val deviceId: String
) {
    val configured: Boolean get() = endpoint.isNotBlank() && !token.isNullOrBlank()
}

/** Persistent relay configuration. The bearer token is encrypted by RiftSecretStore. */
class RiftRelaySettings(context: Context) {
    companion object {
        private const val PREFS = "rift-mcp-relay"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_DEVICE_ID = "deviceId"
        private const val KEY_CLI_ACK_SEQUENCE = "cliAckSequence"
        private const val TOKEN_SECRET = "rift.relay.token"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val secrets = RiftSecretStore(context.applicationContext)

    fun load(): RiftRelayConfig {
        val deviceId = prefs.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().also {
                prefs.edit().putString(KEY_DEVICE_ID, it).apply()
            }
        return RiftRelayConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            endpoint = prefs.getString(KEY_ENDPOINT, "").orEmpty(),
            token = secrets.get(TOKEN_SECRET),
            deviceId = deviceId
        )
    }

    fun loadCliAckSequence(): Long =
        prefs.getLong(KEY_CLI_ACK_SEQUENCE, 0L).coerceAtLeast(0L)

    fun saveCliAckSequence(sequence: Long): Boolean {
        if (sequence <= 0L) return false
        val current = loadCliAckSequence()
        if (sequence <= current) return true
        return prefs.edit()
            .putLong(KEY_CLI_ACK_SEQUENCE, sequence)
            .commit()
    }

    fun save(enabled: Boolean, endpoint: String, replacementToken: String?) {
        val normalized = endpoint.trim().removeSuffix("/")
        if (enabled || normalized.isNotBlank()) {
            val uri = runCatching { URI(normalized) }.getOrNull()
            require(uri != null && uri.scheme.equals("wss", ignoreCase = true) && !uri.host.isNullOrBlank()) {
                "Relay endpoint must be a valid wss:// URL with a host"
            }
            require(uri.userInfo == null && uri.fragment == null) { "Relay endpoint must not include user info or a fragment" }
        }
        val token = replacementToken?.trim().orEmpty()
        if (token.isNotEmpty()) {
            require(token.length <= 4096 && token.none { it == '\r' || it == '\n' || it.code < 0x20 || it.code == 0x7f }) {
                "Relay pairing token is invalid"
            }
            secrets.set(TOKEN_SECRET, token)
        }
        require(!enabled || secrets.get(TOKEN_SECRET)?.isNotBlank() == true) {
            "A relay pairing token is required"
        }
        prefs.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_ENDPOINT, normalized)
            .apply()
    }

    fun clearToken() {
        secrets.remove(TOKEN_SECRET)
        prefs.edit().putBoolean(KEY_ENABLED, false).apply()
    }
}
