package com.riftos.app

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Crash-safe bounded persistence for hosted RiftCLI jobs.
 *
 * The store persists job state; it never executes or resumes work by itself. On process restart,
 * nonterminal jobs are reclassified as recovery_required so callers must make an explicit
 * retry-safe/idempotent recovery decision instead of blindly replaying side effects.
 */
internal class RiftCliPersistentJobStore(context: Context) {
    companion object {
        const val SCHEMA = "rift-cli-job-store-v1"
        private const val MAX_JOB_FILES = 32
        private const val MAX_JOB_BYTES = 4 * 1024 * 1024L
        private const val TERMINAL_RETENTION_MS = 24 * 60 * 60 * 1000L
        private val PROCESS_LOCK = Any()

        private val TERMINAL_STATUSES = setOf(
            "completed",
            "completed_result_too_large",
            "completed_with_failures",
            "failed",
            "cancelled",
            "cancelled_may_have_applied",
            "completed_after_cancel_request"
        )
    }

    private val root = File(context.applicationContext.filesDir, "riftcli-jobs").apply { mkdirs() }
    private val lock = PROCESS_LOCK

    fun save(snapshot: JSONObject) = synchronized(lock) {
        val jobId = snapshot.optString("jobId").trim()
        require(jobId.isNotBlank()) { "RiftCLI persisted job requires jobId" }
        pruneLocked()
        val target = fileFor(jobId)
        if (!target.exists()) {
            val retainedCount = root.listFiles()
                ?.count { it.isFile && it.name.endsWith(".json") }
                ?: 0
            require(retainedCount < MAX_JOB_FILES) {
                "RiftCLI persisted job capacity reached ($MAX_JOB_FILES); terminal jobs must be pruned before new work is accepted"
            }
        }
        require(jobId.toByteArray(Charsets.UTF_8).size <= 256) {
            "RiftCLI persisted jobId exceeds 256 UTF-8 bytes"
        }

        val frozen = JSONObject(snapshot.toString())
            .put("persistenceSchema", SCHEMA)
            .put("persistedAtEpochMs", System.currentTimeMillis())
        val payload = frozen.toString()
        val bytes = payload.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_JOB_BYTES) {
            "RiftCLI persisted job exceeds $MAX_JOB_BYTES bytes"
        }

        val envelope = JSONObject()
            .put("schema", SCHEMA)
            .put("payload", payload)
            .put("payloadSha256", sha256(bytes))
            .toString()
            .toByteArray(Charsets.UTF_8)
        require(envelope.size <= MAX_JOB_BYTES) {
            "RiftCLI persisted job envelope exceeds $MAX_JOB_BYTES bytes"
        }

        val atomic = AtomicFile(target)
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(envelope)
            stream.fd.sync()
            atomic.finishWrite(stream)
            stream = null
        } catch (error: Throwable) {
            stream?.let { atomic.failWrite(it) }
            throw error
        }
    }

    fun load(jobId: String): JSONObject? = synchronized(lock) {
        readLocked(fileFor(jobId))
    }

    fun list(requestId: String? = null): JSONArray = synchronized(lock) {
        pruneLocked()
        val rows = JSONArray()
        root.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.mapNotNull { candidate -> runCatching { readLocked(candidate) }.getOrNull() }
            ?.filterNotNull()
            ?.filter { requestId.isNullOrBlank() || it.optString("requestId") == requestId }
            ?.sortedBy { it.optLong("submittedAtEpochMs", it.optLong("persistedAtEpochMs", 0L)) }
            ?.forEach { rows.put(JSONObject(it.toString())) }
        rows
    }

    fun delete(jobId: String) = synchronized(lock) {
        AtomicFile(fileFor(jobId)).delete()
    }

    /**
     * Reclassify jobs interrupted by process death. This operation is idempotent.
     *
     * Nothing is auto-replayed here. A later recovery layer may resume only when the persisted
     * current operation is system-declared retry-safe/idempotent.
     */
    fun recoverInterruptedJobs(): JSONArray = synchronized(lock) {
        val recovered = JSONArray()
        root.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.forEach { candidate ->
                val row = runCatching { readLocked(candidate) }.getOrNull() ?: return@forEach
                val status = row.optString("status").trim().lowercase()
                if (status in TERMINAL_STATUSES || status == "recovery_required") return@forEach

                val previousLease = row.optJSONObject("authorityLease")
                val lease = if (previousLease != null) {
                    JSONObject(previousLease.toString())
                        .put("active", false)
                        .put("state", "released_on_process_loss")
                        .put("releasedAtEpochMs", System.currentTimeMillis())
                } else {
                    JSONObject()
                        .put("active", false)
                        .put("state", "none")
                        .put("authorizationBypass", false)
                        .put("perOperationAuthorizationRequired", true)
                }

                val recovery = JSONObject(row.optJSONObject("recoveryMetadata")?.toString() ?: "{}")
                    .put("detectedAtEpochMs", System.currentTimeMillis())
                    .put("previousStatus", status)
                    .put("disposition", "explicit-recovery-required")
                    .put("blindReplayAllowed", false)
                    .put("retrySafeResumeRequired", true)
                    .put("note", "Persisted nonterminal job survived process loss; do not replay the whole job or current step without system-declared retry safety.")

                row
                    .put("status", "recovery_required")
                    .put("terminal", false)
                    .put("jobOk", JSONObject.NULL)
                    .put("authorityLease", lease)
                    .put("recoveryMetadata", recovery)
                    .put(
                        "cancellationState",
                        if (row.optBoolean("cancelRequested", false)) "recovery_cancel_requested"
                        else "recovery_required"
                    )
                    .put("updatedAtEpochMs", System.currentTimeMillis())
                save(row)
                recovered.put(JSONObject(row.toString()))
            }
        recovered
    }

    private fun readLocked(file: File): JSONObject? {
        if (!file.exists()) return null
        require(file.length() in 1L..MAX_JOB_BYTES) {
            "RiftCLI persisted job file exceeds bound: ${file.name}"
        }
        val atomic = AtomicFile(file)
        val bytes = atomic.readFully()
        require(bytes.size <= MAX_JOB_BYTES) {
            "RiftCLI persisted job read exceeds bound: ${file.name}"
        }
        val envelope = JSONObject(bytes.toString(Charsets.UTF_8))
        require(envelope.optString("schema") == SCHEMA) {
            "RiftCLI persisted job schema mismatch: ${file.name}"
        }
        val payload = envelope.optString("payload")
        require(payload.isNotBlank()) {
            "RiftCLI persisted job payload missing: ${file.name}"
        }
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        require(payloadBytes.size <= MAX_JOB_BYTES) {
            "RiftCLI persisted payload exceeds bound: ${file.name}"
        }
        require(envelope.optString("payloadSha256") == sha256(payloadBytes)) {
            "RiftCLI persisted job seal mismatch: ${file.name}"
        }
        val row = JSONObject(payload)
        require(row.optString("jobId").isNotBlank()) {
            "RiftCLI persisted jobId missing: ${file.name}"
        }
        return row
    }

    private fun pruneLocked() {
        val now = System.currentTimeMillis()
        val readable = root.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.mapNotNull { candidate ->
                val row = runCatching { readLocked(candidate) }.getOrNull() ?: return@mapNotNull null
                Triple(candidate, row, row.optLong("updatedAtEpochMs", row.optLong("persistedAtEpochMs", 0L)))
            }
            ?.sortedBy { it.third }
            ?.toMutableList()
            ?: mutableListOf()

        val expired = readable.filter { (_, row, updated) ->
            row.optString("status") in TERMINAL_STATUSES &&
                updated > 0L &&
                now - updated >= TERMINAL_RETENTION_MS
        }
        expired.forEach { (candidate, _, _) ->
            AtomicFile(candidate).delete()
            readable.removeAll { it.first == candidate }
        }

        while (readable.size > MAX_JOB_FILES) {
            val terminalIndex = readable.indexOfFirst { it.second.optString("status") in TERMINAL_STATUSES }
            if (terminalIndex < 0) break
            val candidate = readable.removeAt(terminalIndex).first
            AtomicFile(candidate).delete()
        }
    }

    private fun fileFor(jobId: String): File =
        File(root, sha256(jobId.toByteArray(Charsets.UTF_8)) + ".json")

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
