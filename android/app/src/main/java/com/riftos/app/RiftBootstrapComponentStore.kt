package com.riftos.app

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.nio.file.Files

/**
 * Owner for candidate bootstrap component bytes and versioned activation.
 *
 * No RAPP, terminal or exported Binder API calls these methods. A future
 * native, explicit user approval surface is required before any install.
 * Only the disposable "probe" slot is eligible for activation until the
 * Android process/recovery contract is physically proven.
 */
internal object RiftBootstrapComponentStore {
    const val SCHEMA = "riftos.bootstrap-module/1"
    private const val MAX_BYTES = 32L * 1024 * 1024
    private const val MAX_RECORD_BYTES = 4096L
    private val COMPONENTS = setOf("probe", "core", "shell")
    private val DIGEST = Regex("^[0-9a-f]{64}$")
    private val ENTRYPOINT = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$")

    fun root(context: Context): File =
        File(context.applicationContext.filesDir, "bootstrap-components")

    private fun ensureRoot(context: Context): File {
        val dir = root(context)
        require(!Files.isSymbolicLink(dir.toPath())) { "Symlinked bootstrap root" }
        require((dir.isDirectory || dir.mkdirs()) && !Files.isSymbolicLink(dir.toPath())) {
            "Bootstrap storage unavailable"
        }
        return dir
    }

    private fun verifyCandidate(file: File, expected: String) {
        require(expected.matches(DIGEST)) { "Invalid module SHA-256" }
        require(file.isFile && !Files.isSymbolicLink(file.toPath()) &&
            file.length() in 1L..MAX_BYTES && !file.canWrite()) {
            "Bootstrap module must be an immutable regular file"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                if (n > 0) digest.update(buf, 0, n)
            }
        }
        require(hex(digest.digest()) == expected) { "Bootstrap module digest mismatch" }
    }

    private fun hex(bytes: ByteArray) =
        bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun record(component: String, entrypoint: String, sha: String): JSONObject =
        JSONObject().put("schema", SCHEMA).put("api", 1)
            .put("component", component).put("entrypoint", entrypoint)
            .put("sha256", sha)

    private fun validate(component: String, entrypoint: String, sha: String) {
        require(component in COMPONENTS && entrypoint.matches(ENTRYPOINT) &&
            sha.matches(DIGEST)) { "Invalid bootstrap candidate identity" }
    }

    /**
     * Stage a DEX without activating it. Caller owns and closes input.
     * The fixed final filename is content-addressed, not caller-supplied.
     */
    @Synchronized
    fun stage(context: Context, component: String, entrypoint: String,
              input: InputStream): JSONObject {
        require(component in COMPONENTS && entrypoint.matches(ENTRYPOINT)) {
            "Invalid bootstrap candidate identity"
        }
        val dir = ensureRoot(context)
        val temporary = File.createTempFile(".bootstrap-stage-", ".partial", dir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            temporary.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    total += n
                    require(total <= MAX_BYTES) { "Bootstrap module exceeds size limit" }
                    digest.update(buf, 0, n)
                    output.write(buf, 0, n)
                }
                output.fd.sync()
            }
            require(total > 0) { "Empty bootstrap module" }
            // Refuse arbitrary files; this API accepts an Android DEX payload.
            temporary.inputStream().use { header ->
                val prefix = ByteArray(4)
                require(header.read(prefix) == 4 &&
                    prefix.contentEquals(byteArrayOf(100, 101, 120, 10))) {
                    "Expected Android DEX header"
                }
            }
            val sha = hex(digest.digest())
            val output = File(dir, component + "-" + sha + ".dex")
            if (output.exists()) {
                verifyCandidate(output, sha)
            } else {
                require(temporary.setReadOnly()) { "Cannot seal staged DEX" }
                require(temporary.renameTo(output)) { "Could not commit staged DEX" }
                verifyCandidate(output, sha)
            }
            return record(component, entrypoint, sha).put("staged", true)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun atomicRead(file: File): ByteArray? {
        val atomic = AtomicFile(file)
        if (!file.exists() && !File(file.path + ".bak").exists() &&
            !File(file.path + ".new").exists()) return null
        require(!Files.isSymbolicLink(file.toPath())) { "Symlinked activation" }
        val bytes = atomic.openRead().use { it.readBytes() }
        require(bytes.size in 1..MAX_RECORD_BYTES.toInt()) { "Invalid activation length" }
        return bytes
    }

    private fun atomicWrite(file: File, data: ByteArray) {
        require(data.size in 1..MAX_RECORD_BYTES.toInt()) { "Invalid activation record" }
        require(!Files.isSymbolicLink(file.toPath()) &&
            !Files.isSymbolicLink(File(file.path + ".new").toPath()) &&
            !Files.isSymbolicLink(File(file.path + ".bak").toPath())) {
            "Symlinked activation record"
        }
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(data)
            atomic.finishWrite(stream)
        } catch (failure: Throwable) {
            atomic.failWrite(stream)
            throw failure
        }
    }

    /** Called by host at boot. AtomicFile recovers any interrupted pointer write. */
    fun active(context: Context, component: String): JSONObject? {
        require(component in COMPONENTS) { "Unknown component" }
        val bytes = atomicRead(File(root(context), component + ".json")) ?: return null
        val value = JSONObject(String(bytes, Charsets.UTF_8))
        validate(component, value.getString("entrypoint"), value.getString("sha256"))
        require(value.getString("schema") == SCHEMA && value.getInt("api") == 1 &&
            value.getString("component") == component) { "Wrong module activation schema" }
        return value
    }

    fun dexFile(context: Context, component: String, sha: String): File {
        require(component in COMPONENTS && sha.matches(DIGEST)) { "Invalid module lookup" }
        return File(root(context), component + "-" + sha + ".dex")
    }

    /** Proof-only activation; Core/Shell remain protected embedded implementations. */
    @Synchronized
    fun activateProbe(context: Context, sha: String, entrypoint: String): JSONObject {
        validate("probe", entrypoint, sha)
        val dir = ensureRoot(context)
        require(!File(dir, "probe.booting").exists()) {
            "Interrupted probe must be recovered before activation"
        }
        verifyCandidate(dexFile(context, "probe", sha), sha)
        val current = atomicRead(File(dir, "probe.json"))
        val fallback = JSONObject().put("schema", "riftos.bootstrap-fallback/1")
            .put("component", "probe").put("embedded", current == null)
        if (current != null) fallback.put("previous", JSONObject(String(current, Charsets.UTF_8)))
        atomicWrite(File(dir, "probe.previous.json"),
            fallback.toString().toByteArray(Charsets.UTF_8))
        val next = record("probe", entrypoint, sha)
        atomicWrite(File(dir, "probe.json"), next.toString().toByteArray(Charsets.UTF_8))
        return next.put("restartRequired", true)
    }

    /**
     * Revert a failed probe before selecting any code. No files or Core state
     * outside bootstrap-components are modified. Can be called after failure.
     */
    @Synchronized
    fun recoverProbe(context: Context): Boolean = restoreProbe(context, true)

    @Synchronized
    fun rollbackProbe(context: Context): Boolean = restoreProbe(context, false)

    private fun restoreProbe(context: Context, requireInterrupted: Boolean): Boolean {
        val dir = root(context)
        val marker = File(dir, "probe.booting")
        if (requireInterrupted && !marker.exists()) return false
        val prior = atomicRead(File(dir, "probe.previous.json"))
            ?: error("Probe interrupted without rollback record")
        val fallback = JSONObject(String(prior, Charsets.UTF_8))
        require(fallback.getString("schema") == "riftos.bootstrap-fallback/1" &&
            fallback.getString("component") == "probe") { "Invalid probe rollback record" }
        val active = AtomicFile(File(dir, "probe.json"))
        if (fallback.getBoolean("embedded")) {
            active.delete()
        } else {
            val previous = fallback.getJSONObject("previous")
            validate("probe", previous.getString("entrypoint"), previous.getString("sha256"))
            require(previous.getString("component") == "probe" &&
                previous.getString("schema") == SCHEMA && previous.getInt("api") == 1) {
                "Invalid rollback target"
            }
            verifyCandidate(dexFile(context, "probe", previous.getString("sha256")),
                previous.getString("sha256"))
            atomicWrite(File(dir, "probe.json"), previous.toString().toByteArray(Charsets.UTF_8))
        }
        if (marker.exists()) require(marker.delete()) { "Cannot clear interrupted probe marker" }
        return true
    }

    @Synchronized
    fun resetProbe(context: Context) {
        val dir = ensureRoot(context)
        require(!File(dir, "probe.booting").exists()) {
            "Cannot reset while probe boot is interrupted"
        }
        AtomicFile(File(dir, "probe.json")).delete()
        AtomicFile(File(dir, "probe.previous.json")).delete()
    }
}
