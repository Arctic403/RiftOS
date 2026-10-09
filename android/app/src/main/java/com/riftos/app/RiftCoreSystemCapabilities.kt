package com.riftos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * C1.4-A: fail-closed Core-only system-elevation policy and durable audit.
 *
 * This is NOT Android root or an unrestricted grant mechanism. No elevated
 * operation is authorized in A. Future C1.4 gates may attach a trusted,
 * single-use user-consent policy before enabling narrowly scoped operations.
 * RAPP manifest declarations and existing app-local capability grants never
 * become system-admin authority.
 */
internal object RiftCoreSystemCapabilities {
    const val SCHEMA = "riftos.core.system-capabilities/1"
    const val AUDIT_SCHEMA = "riftos.core.system-capability-audit/1"
    private const val PREFS = "rift-core-system-policy"
    private const val AUDIT_KEY = "audit"
    private const val MAX_ENTRIES = 64
    private const val MAX_SERIALIZED_BYTES = 24 * 1024
    private val LOCK = Any()

    val RESTRICTED = setOf(
        "system.fs.read",
        "system.fs.write",
        "software.install",
        "runtime.register",
        "process.protected.kill"
    )

    private val SAFE_ACTOR = Regex("^[A-Za-z0-9._:-]{1,96}$")

    /**
     * Record a denied privileged attempt without capturing requested paths,
     * keys, package payloads or other potentially sensitive arguments.
     * Persist synchronously so revocation/audit failures cannot pass silently.
     */
    /** Existing ordinary RAPP boundary denials remain audited and fail closed. */
    fun recordDenied(context: Context, actor: String, operation: String): JSONObject =
        recordDecision(context, actor, operation, "denied", "no-trusted-elevation")

    /**
     * C1.4-B: bounded durable decision metadata only, never bearer ticket,
     * signer fingerprint, raw target path, key or effect argument.
     */
    fun recordDecision(
        context: Context, actor: String, operation: String,
        outcome: String, reason: String
    ): JSONObject {
        require(SAFE_ACTOR.matches(actor)) { "Core system capability actor invalid" }
        require(operation in RESTRICTED) { "Unknown Core system capability" }
        require(outcome in setOf(
            "requested", "approved", "denied", "revoked", "expired", "consumed"
        )) { "Core admin decision outcome invalid" }
        require(reason.matches(SAFE_ACTOR)) { "Core admin audit reason invalid" }
        synchronized(LOCK) {
            val prefs = context.applicationContext.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE
            )
            val old = readBounded(prefs.getString(AUDIT_KEY, null))
            val events = JSONArray()
            val start = (old.length() - MAX_ENTRIES + 1).coerceAtLeast(0)
            for (i in start until old.length()) {
                events.put(old.getJSONObject(i))
            }
            val entry = JSONObject()
                .put("atMs", System.currentTimeMillis())
                .put("actor", actor)
                .put("operation", operation)
                .put("outcome", outcome)
                .put("reason", reason)
            events.put(entry)
            val serialized = events.toString()
            require(serialized.toByteArray(Charsets.UTF_8).size <= MAX_SERIALIZED_BYTES) {
                "Core system capability audit bound exceeded"
            }
            check(prefs.edit().putString(AUDIT_KEY, serialized).commit()) {
                "Core system capability audit persistence failed"
            }
            return entry
        }
    }

    /** Every system elevation remains prohibited until a later consent gate. */
    fun requireElevated(
        context: Context,
        actor: String,
        operation: String
    ): Nothing {
        recordDenied(context, actor, operation)
        throw SecurityException("Core denied system elevation: explicit trusted authorization required")
    }

    fun status(context: Context): JSONObject {
        synchronized(LOCK) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val events = readBounded(prefs.getString(AUDIT_KEY, null))
            return JSONObject()
                .put("schema", SCHEMA)
                .put("authority", "riftos-core")
                .put("adminElevationEnabled", false)
                .put("defaultDecision", "deny")
                .put("grantCount", 0)
                .put("restrictedOperations", JSONArray(RESTRICTED.sorted()))
                .put("auditEntries", events.length())
                .put("revocableSystemGrants", false)
                .put("phase", "C1.4-A-policy-foundation")
        }
    }

    /** Read-only, bounded recent decisions; no raw arguments or secret data. */
    fun audit(context: Context, limit: Int = 16): JSONObject {
        require(limit in 1..MAX_ENTRIES) { "Core audit read limit invalid" }
        synchronized(LOCK) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val recorded = readBounded(prefs.getString(AUDIT_KEY, null))
            val out = JSONArray()
            for (i in (recorded.length() - limit).coerceAtLeast(0) until recorded.length()) {
                out.put(recorded.getJSONObject(i))
            }
            return JSONObject().put("schema", AUDIT_SCHEMA)
                .put("authority", "riftos-core")
                .put("entries", out)
                .put("returned", out.length())
        }
    }

    private fun readBounded(raw: String?): JSONArray {
        if (raw == null) return JSONArray()
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_SERIALIZED_BYTES) {
            "Core system capability audit corrupted/oversized"
        }
        val saved = JSONArray(raw)
        require(saved.length() <= MAX_ENTRIES) {
            "Core system capability audit entry bound exceeded"
        }
        return saved
    }
}
