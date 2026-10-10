package com.riftos.app

import android.content.Context
import android.os.Process
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.SecureRandom

/**
 * Generic trusted-DEX activation *slot* and interrupted-boot rollback.
 *
 * Only one activation can be pending at a time, and v1 has one active slot.
 * This does not register an Android runtime provider or grant RAPP elevation.
 * The nonexported module Service runs under the SAME APP UID.
 */
internal object RiftCoreModuleActivation {
    const val STATUS_SCHEMA = "riftos.core.module-activation/1"
    private const val RECORD_SCHEMA = "riftos.core.module-active/1"
    private const val PREVIOUS_SCHEMA = "riftos.core.module-previous/1"
    private const val PROOF_SCHEMA = "riftos.core.module-proof/1"
    private const val MAX_RECORD = 4096
    private val digest = Regex("^[0-9a-f]{64}$")
    private val noncePattern = Regex("^[0-9a-f]{32}$")
    private val random = SecureRandom()
    private val lock = Any()

    private fun root(context: Context): File {
        val raw = File(context.applicationContext.filesDir, "module-store")
        require(!Files.isSymbolicLink(raw.toPath())) { "Symlinked module root" }
        require(raw.isDirectory || raw.mkdirs()) { "Core module root unavailable" }
        return raw.canonicalFile
    }
    private fun active(context: Context) = File(root(context), "active.json")
    private fun previous(context: Context) = File(root(context), "previous.json")
    private fun marker(context: Context) = File(root(context), "active.booting")
    private fun proof(context: Context) = File(root(context), "execution-proof.json")

    private fun readRecord(path: File): JSONObject? {
        require(!Files.isSymbolicLink(path.toPath())) { "Core module record symlink" }
        val atomic = AtomicFile(path)
        val main = path.exists()
        if (!main && !File(path.path + ".bak").exists()) return null
        val bytes = atomic.openRead().use { stream ->
            val data = ByteArray(MAX_RECORD + 1)
            var count = 0
            while (count < data.size) {
                val read = stream.read(data, count, data.size - count)
                if (read < 0) break
                if (read > 0) count += read
            }
            require(count in 1..MAX_RECORD && stream.read() == -1) {
                "Core module record missing or exceeds bound"
            }
            data.copyOf(count)
        }
        return JSONObject(String(bytes, Charsets.UTF_8))
    }

    private fun writeRecord(path: File, record: JSONObject) {
        val bytes = record.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_RECORD &&
            !Files.isSymbolicLink(path.toPath()) &&
            !Files.isSymbolicLink(File(path.path + ".new").toPath()) &&
            !Files.isSymbolicLink(File(path.path + ".bak").toPath())) {
            "Core module record write denied"
        }
        val atomic = AtomicFile(path)
        val out = atomic.startWrite()
        try {
            out.write(bytes)
            atomic.finishWrite(out)
        } catch (error: Throwable) {
            atomic.failWrite(out)
            throw error
        }
    }

    private fun validate(context: Context, value: JSONObject): RiftCoreModuleManifest {
        require(value.getString("schema") == RECORD_SCHEMA) {
            "Invalid Core module active pointer"
        }
        val id = value.getString("id")
        val revision = value.getString("manifestDigest")
        val nonce = value.getString("nonce")
        require(revision.matches(digest) && nonce.matches(noncePattern)) {
            "Core module active pointer fingerprint invalid"
        }
        val manifest = RiftCoreModuleStore.staged(context, id, revision)
        require(manifest.sha256 == value.getString("sha256") &&
            manifest.version == value.getString("version") &&
            manifest.entrypoint == value.getString("entrypoint")) {
            "Core module active pointer disagrees with sealed metadata"
        }
        return manifest
    }

    /**
     * Core-only transaction. Previous slot is persisted BEFORE pending marker.
     * The marker is fsync'd BEFORE updating active pointer. Crash at any point
     * after the marker must roll back on next Core startup.
     */
    fun activate(context: Context, id: String, revision: String): JSONObject =
        synchronized(lock) {
            val module = RiftCoreModuleStore.staged(context, id, revision)
            require(!marker(context).exists()) {
                "Previous Core module startup not recovered"
            }
            val prior = readRecord(active(context))
            if (prior != null) validate(context, prior)
            val backup = JSONObject()
                .put("schema", PREVIOUS_SCHEMA)
                .put("present", prior != null)
            if (prior != null) backup.put("record", prior)
            writeRecord(previous(context), backup)
            val bytes = ByteArray(16).also { random.nextBytes(it) }
            val nonce = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
            val value = JSONObject()
                .put("schema", RECORD_SCHEMA)
                .put("id", module.id)
                .put("version", module.version)
                .put("entrypoint", module.entrypoint)
                .put("sha256", module.sha256)
                .put("manifestDigest", revision)
                .put("nonce", nonce)
            AtomicFile(proof(context)).delete()
            val pending = marker(context)
            require(!Files.isSymbolicLink(pending.toPath()) &&
                pending.createNewFile()) { "Core module pending marker unavailable" }
            try {
                FileOutputStream(pending).use { output ->
                    output.write(("module-boot:" + nonce + "\n").toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }
                writeRecord(active(context), value)
            } catch (failure: Throwable) {
                runCatching { restore(context) }.onFailure { failure.addSuppressed(it) }
                throw failure
            }
            JSONObject().put("schema", STATUS_SCHEMA)
                .put("id", module.id).put("version", module.version)
                .put("sha256", module.sha256)
                .put("manifestDigest", revision)
                .put("activated", true)
                .put("serviceStartRequested", false)
        }

    /** Reads verified immutable state in the nonexported module-host process. */
    fun pendingHost(context: Context): JSONObject = synchronized(lock) {
        val pending = marker(context)
        require(!Files.isSymbolicLink(pending.toPath()) && pending.isFile) {
            "No approved Core module startup pending"
        }
        val current = readRecord(active(context))
            ?: error("Pending startup has no activated module")
        validate(context, current)
        current
    }

    /** Host publishes a matching receipt; stale proof from prior boot is denied. */
    fun complete(context: Context, activeRecord: JSONObject, processName: String): JSONObject =
        synchronized(lock) {
            require(processName == context.packageName + ":riftModuleHost") {
                "Module proof came from wrong Android process"
            }
            val current = pendingHost(context)
            for (key in listOf("schema", "id", "version", "entrypoint",
                "sha256", "manifestDigest", "nonce")) {
                require(current.getString(key) == activeRecord.getString(key)) {
                    "Activated module identity changed during execution"
                }
            }
            val receipt = JSONObject()
                .put("schema", PROOF_SCHEMA)
                .put("id", current.getString("id"))
                .put("version", current.getString("version"))
                .put("sha256", current.getString("sha256"))
                .put("manifestDigest", current.getString("manifestDigest"))
                .put("nonce", current.getString("nonce"))
                .put("pid", Process.myPid())
                .put("process", processName)
                .put("atMs", System.currentTimeMillis())
            writeRecord(proof(context), receipt)
            require(marker(context).delete()) { "Module startup marker not cleared" }
            receipt
        }

    /** Roll back a failed or interrupted module startup, never wipe RAPPs. */
    fun recover(context: Context): Boolean = synchronized(lock) {
        if (!marker(context).exists()) return@synchronized false
        restore(context)
        true
    }

    fun rollback(context: Context): Boolean = recover(context)

    private fun restore(context: Context) {
        val pending = marker(context)
        require(!Files.isSymbolicLink(pending.toPath()) && pending.exists()) {
            "No owned module startup marker to recover"
        }
        val prior = readRecord(previous(context))
            ?: error("Core module previous-version journal missing")
        require(prior.getString("schema") == PREVIOUS_SCHEMA) {
            "Core module rollback journal format invalid"
        }
        if (prior.getBoolean("present")) {
            val old = prior.getJSONObject("record")
            validate(context, old)
            writeRecord(active(context), old)
        } else AtomicFile(active(context)).delete()
        AtomicFile(proof(context)).delete()
        require(pending.delete()) { "Cannot clear module rollback marker" }
    }

    /** Core read-only status; a valid proof shows COMPLETED execution, not liveness. */
    fun status(context: Context): JSONObject = synchronized(lock) {
        val current = readRecord(active(context))
        val valid = current?.let { validate(context, it) }
        val pending = marker(context).exists()
        val savedProof = readRecord(proof(context))
        val matching = current != null && !pending && savedProof != null &&
            savedProof.optString("schema") == PROOF_SCHEMA &&
            savedProof.optString("id") == current.optString("id") &&
            savedProof.optString("manifestDigest") == current.optString("manifestDigest") &&
            savedProof.optString("nonce") == current.optString("nonce") &&
            savedProof.optString("sha256") == current.optString("sha256") &&
            savedProof.optString("process") ==
                context.packageName + ":riftModuleHost" &&
            savedProof.optInt("pid") > 0 &&
            savedProof.optInt("pid") != Process.myPid()
        JSONObject().put("schema", STATUS_SCHEMA)
            .put("singleActivationSlot", true)
            .put("active", valid != null)
            .put("activeId", valid?.id ?: JSONObject.NULL)
            .put("activeVersion", valid?.version ?: JSONObject.NULL)
            .put("activeSha256", valid?.sha256 ?: JSONObject.NULL)
            .put("pendingStartup", pending)
            .put("proofPresent", matching)
            .put("proof", if (matching) savedProof else JSONObject.NULL)
            .put("separateAndroidProcess", true)
            .put("separateAndroidUid", false)
            .put("runtimeProviderRegistered", false)
    }
}
