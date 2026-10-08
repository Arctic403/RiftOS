package com.riftos.app

import org.json.JSONObject

/**
 * C1.2-B2-B1: Core-owned, generation-bound focus lease protocol.
 *
 * A replaceable shell reports its preferred foreground window; only the Core
 * session registry may mint or revoke the matching installed-app lease.
 * C1.2-B2-B2 enforces focused-input admission and ticket delivery in Core.
 * This remains in-process and is NOT C1.3 independent-shell IPC proof.
 */
class RiftCoreInputFocus {
    companion object {
        const val SCHEMA = "riftos.core.input-focus/1"
    }

    data class Lease(val appId: String, val attachmentGeneration: Long, val revision: Long)

    private var current: Lease? = null
    private var nextRevision = 0L

    @Synchronized
    fun requestVerified(appId: String?, attachmentGeneration: Long?): Lease? {
        val validId = appId?.takeIf { it.isNotBlank() }
        val validGeneration = attachmentGeneration?.takeIf { it > 0L }
        val selected = if (validId != null && validGeneration != null) {
            validId to validGeneration
        } else null
        val previous = current
        if (selected?.first == previous?.appId &&
            selected?.second == previous?.attachmentGeneration
        ) return previous
        check(nextRevision < Long.MAX_VALUE) { "Core focus lease revision exhausted" }
        nextRevision += 1L
        current = selected?.let { Lease(it.first, it.second, nextRevision) }
        return current
    }

    @Synchronized
    fun revoke(appId: String) {
        if (current?.appId == appId) requestVerified(null, null)
    }

    /** Reject input unless this exact attached application generation owns focus. */
    @Synchronized
    fun requireCurrentLease(appId: String, attachmentGeneration: Long) {
        val lease = current
        require(lease != null && lease.appId == appId &&
            lease.attachmentGeneration == attachmentGeneration) {
            "RAPP Core focused input requires current focus lease"
        }
    }

    @Synchronized
    fun current(): Lease? = current?.copy()

    @Synchronized
    fun status(): JSONObject {
        val lease = current
        return JSONObject()
            .put("schema", SCHEMA)
            .put("owner", "riftos-core")
            .put("revision", nextRevision)
            .put("focusedAppId", lease?.appId ?: JSONObject.NULL)
            .put("attachmentGeneration", lease?.attachmentGeneration ?: JSONObject.NULL)
            .put("focusEnforcedForInput", true)
            .put("shellClientProtocol", "in-process")
    }
}
