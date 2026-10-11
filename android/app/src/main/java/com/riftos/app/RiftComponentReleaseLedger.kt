package com.riftos.app

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/**
 * Permanent APK-owned revision journal for separately compiled Core and Shell.
 *
 * No public/RAPP/terminal entry point stages or promotes an implementation.
 * Only trusted host callers may propose a QUALIFIED version and accept it
 * after a separate physical proof. All operations are serialized and atomic.
 *
 * A pending revision is NEVER last-known-good. A failed pending revision is
 * quarantined by SHA and we select the previous accepted external revision
 * first, then the retained embedded implementation during migration.
 */
internal object RiftComponentReleaseLedger {
    const val SCHEMA = "riftos.host.revisions/1"
    private const val RECORD_SCHEMA = "riftos.bootstrap-module/1"
    private const val MAX_BYTES = 12 * 1024
    private val DIGEST = Regex("^[0-9a-f]{64}$")
    private val ENTRY = Regex("^com\\.riftos\\.external\\.(core|shell)\\.[A-Za-z_][A-Za-z0-9_.]*$")

    private fun checkId(id: String) = require(id == "core" || id == "shell") {
        "Only protected Core/Shell revisions are versioned"
    }

    private fun file(context: Context, id: String): File {
        checkId(id)
        val root = RiftBootstrapComponentStore.root(context)
        require(!Files.isSymbolicLink(root.toPath()) &&
            (root.isDirectory || root.mkdirs())) { "Revision root invalid" }
        return File(root, "$id.revisions.json")
    }

    private fun validate(context: Context, id: String, value: JSONObject): JSONObject {
        checkId(id)
        require(value.getString("schema") == RECORD_SCHEMA &&
            value.getInt("api") == 1 &&
            value.getString("component") == id) { "Revision ABI/component mismatch" }
        val sha = value.getString("sha256")
        val entrypoint = value.getString("entrypoint")
        require(sha.matches(DIGEST) && entrypoint.matches(ENTRY) &&
            entrypoint.startsWith("com.riftos.external.$id.")) {
            "Revision SHA or namespace invalid"
        }
        val dex = RiftBootstrapComponentStore.dexFile(context, id, sha)
        require(dex.isFile && !Files.isSymbolicLink(dex.toPath()) &&
            dex.length() in 1L..(32L * 1024 * 1024) && !dex.canWrite()) {
            "Revision executable is missing or not immutable"
        }
        // Verify immutable bytes again on every journal read/recovery,
        // never trust a stale pointer or caller-provided digest.
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        dex.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") {
            "%02x".format(it.toInt() and 255)
        }
        require(actual == sha) { "Revision executable digest mismatch" }
        return JSONObject(value.toString())
    }

    private fun read(context: Context, id: String, verifyFiles: Boolean = true): JSONObject? {
        val path = file(context, id)
        if (!path.exists() && !File(path.path + ".bak").exists()) return null
        require(!Files.isSymbolicLink(path.toPath())) { "Revision record symlink" }
        val bytes = AtomicFile(path).openRead().use { stream ->
            val buffer = ByteArray(MAX_BYTES + 1)
            val count = stream.read(buffer)
            require(count in 1..MAX_BYTES && stream.read() == -1) {
                "Revision record oversized"
            }
            buffer.copyOf(count)
        }
        val value = JSONObject(String(bytes, Charsets.UTF_8))
        require(value.getString("schema") == SCHEMA &&
            value.getString("component") == id) { "Revision journal invalid" }
        if (verifyFiles) {
            for (field in listOf("pending", "lastKnownGood", "previousKnownGood")) {
                value.optJSONObject(field)?.let { validate(context, id, it) }
            }
        }
        return value
    }

    private fun write(context: Context, id: String, state: JSONObject) {
        val path = file(context, id)
        for (suffix in listOf("", ".new", ".bak")) {
            require(!Files.isSymbolicLink(File(path.path + suffix).toPath())) {
                "Revision write target symlinked"
            }
        }
        val bytes = state.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_BYTES) { "Revision state size invalid" }
        val atomic = AtomicFile(path)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (failure: Throwable) {
            atomic.failWrite(stream)
            throw failure
        }
    }

    private fun empty(id: String) = JSONObject()
        .put("schema", SCHEMA)
        .put("component", id)
        .put("sequence", 0L)
        .put("status", "embedded")
        .put("rejectedSha256", JSONObject.NULL)
        .put("rejectedReason", JSONObject.NULL)
        .put("pending", JSONObject.NULL)
        .put("lastKnownGood", JSONObject.NULL)
        .put("previousKnownGood", JSONObject.NULL)

    /** This does NOT select a revision; caller must independently qualify it. */
    @Synchronized
    fun recordPrepared(context: Context, id: String, qualified: JSONObject): JSONObject {
        val validated = validate(context, id, qualified)
        val state = read(context, id) ?: empty(id)
        val rejected = state.optString("rejectedSha256")
        require(rejected != validated.getString("sha256")) {
            "Quarantined component revision cannot be reactivated"
        }
        require(state.isNull("pending")) { "An external revision is already pending proof" }
        state.put("pending", validated)
            .put("status", "pending-restart")
            .put("sequence", state.optLong("sequence") + 1L)
            .put("preparedAt", System.currentTimeMillis())
        write(context, id, state)
        return summary(state)
    }

    /** Trusted accept only AFTER device health is established for exact SHA. */
    @Synchronized
    fun acceptProven(context: Context, id: String, sha: String): JSONObject {
        require(sha.matches(DIGEST)) { "Invalid accepted SHA" }
        val state = read(context, id) ?: error("No revision journal")
        val pending = state.optJSONObject("pending") ?: error("No pending revision")
        require(pending.getString("sha256") == sha) { "Accepted SHA differs from pending" }
        validate(context, id, pending)
        val prior = state.optJSONObject("lastKnownGood")
        state.put("previousKnownGood",
            prior?.let { JSONObject(it.toString()) } ?: JSONObject.NULL)
            .put("lastKnownGood", JSONObject(pending.toString()))
            .put("pending", JSONObject.NULL)
            .put("status", "external-known-good")
            .put("acceptedAt", System.currentTimeMillis())
            .put("sequence", state.optLong("sequence") + 1L)
        write(context, id, state)
        return summary(state)
    }

    /**
     * On a failed update we prefer the previous known-good external revision.
     * The caller MUST atomically update its active pointer after this verdict.
     * This method does not execute any external code or alter a process.
     */
    @Synchronized
    fun failed(context: Context, id: String, reason: String): JSONObject {
        // A damaged DEX is itself a failure; do not require hashing a
        // corrupt pending candidate merely to quarantine it.
        val state = read(context, id, verifyFiles = false) ?: empty(id)
        val pending = state.optJSONObject("pending")
        val accepted = state.optJSONObject("lastKnownGood")
        if (pending != null) {
            state.put("rejectedSha256", pending.optString("sha256"))
                .put("pending", JSONObject.NULL)
        } else if (accepted != null) {
            // Post-acceptance failure: demote the dying revision and use N-1.
            state.put("rejectedSha256", accepted.optString("sha256"))
                .put("lastKnownGood",
                    state.optJSONObject("previousKnownGood")?.let {
                        JSONObject(it.toString())
                    } ?: JSONObject.NULL)
                .put("previousKnownGood", JSONObject.NULL)
        }
        state.put("rejectedReason", reason.take(120))
            .put("status", if (state.optJSONObject("lastKnownGood") != null)
                "external-rollback-required" else "embedded-rollback-required")
            .put("failedAt", System.currentTimeMillis())
            .put("sequence", state.optLong("sequence") + 1L)
        write(context, id, state)
        return summary(state)
    }

    @Synchronized
    fun isAccepted(context: Context, id: String, sha: String): Boolean {
        if (!sha.matches(DIGEST)) return false
        return runCatching {
            val state = read(context, id, verifyFiles = false) ?: return false
            state.isNull("pending") &&
                state.optJSONObject("lastKnownGood")?.optString("sha256") == sha &&
                state.optString("status") == "external-known-good"
        }.getOrDefault(false)
    }

    @Synchronized
    fun recoveryTarget(context: Context, id: String): JSONObject? {
        val state = read(context, id, verifyFiles = false) ?: return null
        val good = state.optJSONObject("lastKnownGood") ?: return null
        if (good.optString("sha256") == state.optString("rejectedSha256")) return null
        return validate(context, id, good)
    }

    /** Restore the already-accepted N-1 to normal restart policy after rollback. */
    @Synchronized
    fun confirmRollback(context: Context, id: String, sha: String): JSONObject {
        require(sha.matches(DIGEST)) { "Restored revision SHA invalid" }
        val state = read(context, id, verifyFiles = false)
            ?: error("No protected rollback journal")
        require(state.optString("status") == "external-rollback-required" &&
            state.isNull("pending") &&
            state.optJSONObject("lastKnownGood")?.optString("sha256") == sha) {
            "Protected rollback verdict changed before pointer restoration"
        }
        validate(context, id, state.getJSONObject("lastKnownGood"))
        val pointer = RiftBootstrapComponentStore.active(context, id)
        require(pointer?.optString("sha256") == sha) {
            "Restored active pointer digest mismatch"
        }
        state.put("status", "external-known-good")
            .put("restoredKnownGoodAt", System.currentTimeMillis())
            .put("sequence", state.optLong("sequence") + 1L)
        write(context, id, state)
        return summary(state)
    }

    /**
     * A manual Recovery target must come from this APK's earlier device-accepted
     * revisions. The Recovery DEX and receipt are checked separately.
     */
    @Synchronized
    fun checkManualRestoreTarget(context: Context, id: String, sha: String) {
        require(sha.matches(DIGEST)) { "Invalid manual recovery target" }
        val state = read(context, id, verifyFiles = false)
            ?: error("No accepted external revisions")
        val accepted = sequenceOf(
            state.optJSONObject("lastKnownGood"),
            state.optJSONObject("previousKnownGood")
        ).filterNotNull().any { it.optString("sha256") == sha }
        require(accepted) {
            "Recovery target was not previously accepted on device"
        }
    }

    /**
     * Legacy journal reconciliation while the simpler APK bootstrap is being
     * device-proven. NO Core code or Shell code is started here.
     * Deleting the selected pointer returns to the known-good embedded seed.
     */
    @Synchronized
    fun manualRestored(context: Context, id: String, target: JSONObject?) {
        checkId(id)
        if (target != null) {
            checkManualRestoreTarget(context, id, target.getString("sha256"))
            validate(context, id, target)
        }
        // Embedded migration seed must remain recoverable even when a failed
        // external update also damaged the old revision metadata.
        val current = if (target == null) {
            runCatching { read(context, id, verifyFiles = false) }.getOrNull() ?: empty(id)
        } else {
            read(context, id, verifyFiles = false) ?: empty(id)
        }
        val active = RiftBootstrapComponentStore.active(context, id)
        require(if (target == null) active == null else
            active?.optString("sha256") == target.optString("sha256")) {
            "Recovery pointer differs from selected target"
        }
        // Returning to embedded must NOT erase the previously accepted
        // external backup's trusted identity. Keep it only as a manual
        // recovery option, not as an automatically selected revision.
        val retainedBackup = if (target == null) {
            current.optJSONObject("previousKnownGood")
                ?: current.optJSONObject("lastKnownGood")
        } else null
        val restored = empty(id)
            .put("sequence", current.optLong("sequence") + 1L)
            .put("status", if (target == null) "embedded" else "external-known-good")
            .put("lastKnownGood",
                target?.let { JSONObject(it.toString()) } ?: JSONObject.NULL)
            .put("previousKnownGood",
                retainedBackup?.let { JSONObject(it.toString()) } ?: JSONObject.NULL)
            .put("pending", JSONObject.NULL)
            .put("manualRestoredAt", System.currentTimeMillis())
        write(context, id, restored)
    }

    private fun summary(state: JSONObject): JSONObject {
        val pending = state.optJSONObject("pending")
        val accepted = state.optJSONObject("lastKnownGood")
        return JSONObject()
            .put("schema", SCHEMA)
            .put("component", state.optString("component"))
            .put("status", state.optString("status"))
            .put("sequence", state.optLong("sequence"))
            .put("pendingSha256", pending?.optString("sha256") ?: JSONObject.NULL)
            .put("lastKnownGoodSha256", accepted?.optString("sha256") ?: JSONObject.NULL)
            .put("previousKnownGoodSha256",
                state.optJSONObject("previousKnownGood")?.optString("sha256") ?: JSONObject.NULL)
            .put("rejectedSha256", state.optString("rejectedSha256").takeIf {
                it.matches(DIGEST)
            } ?: JSONObject.NULL)
            .put("lastRejectionReason", state.optString("rejectedReason").take(120))
    }

    @Synchronized
    fun status(context: Context, id: String): JSONObject {
        // Status is metadata only; do not re-hash every DEX on each poll.
        return runCatching { summary(read(context, id, verifyFiles = false) ?: empty(id)) }
            .getOrElse { JSONObject().put("schema", SCHEMA)
                .put("component", id).put("status", "unavailable")
                .put("lastErrorType", it.javaClass.simpleName) }
    }
}
