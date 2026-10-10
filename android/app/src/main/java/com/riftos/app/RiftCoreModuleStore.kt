package com.riftos.app

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Core-owned content-addressed *staging* for trusted generic modules.
 *
 * Does not activate a revision, execute DEX, register runtime providers,
 * create platform services or grant RAPP/Android permissions.
 *
 * The trusted UI must independently gain a specific one-use stage approval
 * before dispatching any stream to this store through protected Core IPC.
 */
internal object RiftCoreModuleStore {
    const val STAGING_SCHEMA = "riftos.core.module-stage/1"
    private const val MAX_MODULE_IDS = 32
    private const val MAX_REVISIONS_PER_ID = 16
    private const val MAX_RECORD_BYTES = 4096
    private val SAFE_ID = Regex("^[a-z][a-z0-9._-]{0,79}$")
    private val SAFE_DIGEST = Regex("^[0-9a-f]{64}$")
    private val lock = Any()

    private fun root(context: Context): File {
        val raw = File(context.applicationContext.filesDir, "module-store")
        require(!Files.isSymbolicLink(raw.toPath())) { "Symlinked module-store" }
        require(raw.isDirectory || raw.mkdirs()) { "Module-store unavailable" }
        return raw.canonicalFile
    }

    private fun directory(context: Context, id: String): File {
        require(id.matches(SAFE_ID) && !id.startsWith("riftos.")) {
            "Invalid external module identity"
        }
        val base = root(context)
        val dir = File(base, id)
        require(!Files.isSymbolicLink(dir.toPath()) &&
            dir.canonicalFile.parentFile == base) {
            "External module directory escaped Core storage"
        }
        return dir
    }

    private fun sha256(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                if (n > 0) sha.update(chunk, 0, n)
            }
        }
        return sha.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun verifyDex(file: File, sha: String) {
        require(sha.matches(SAFE_DIGEST) &&
            !Files.isSymbolicLink(file.toPath()) && file.isFile &&
            file.length() in 1L..RiftCoreModuleManifest.MAX_MODULE_BYTES &&
            !file.canWrite()) { "Core staged module DEX invalid" }
        file.inputStream().use { input ->
            val header = ByteArray(8)
            require(input.read(header) == 8 &&
                header[0] == 100.toByte() &&
                header[1] == 101.toByte() &&
                header[2] == 120.toByte() &&
                header[3] == 10.toByte() &&
                header[7] == 0.toByte()) {
                "Core module is not Android DEX"
            }
        }
        require(sha256(file) == sha) { "Core module SHA-256 mismatch" }
    }

    private fun atomicWrite(file: File, bytes: ByteArray) {
        require(bytes.size in 1..MAX_RECORD_BYTES) { "Module record exceeds bound" }
        require(!Files.isSymbolicLink(file.toPath()) &&
            !Files.isSymbolicLink(File(file.path + ".new").toPath()) &&
            !Files.isSymbolicLink(File(file.path + ".bak").toPath())) {
            "Core module record symlink"
        }
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (e: Throwable) {
            atomic.failWrite(output)
            throw e
        }
    }

    private fun readManifest(file: File): RiftCoreModuleManifest {
        require(!Files.isSymbolicLink(file.toPath())) { "Module metadata symlink" }
        val size = file.length()
        require(file.isFile && size in 1..MAX_RECORD_BYTES) {
            "Missing/oversized Core module metadata"
        }
        return RiftCoreModuleManifest.parse(AtomicFile(file).openRead().use {
            input -> input.readBytes()
        })
    }

    fun staged(context: Context, id: String, revision: String): RiftCoreModuleManifest =
        synchronized(lock) {
            require(revision.matches(SAFE_DIGEST)) { "Invalid module revision" }
            val dir = directory(context, id)
            val manifest = readManifest(File(dir, "$revision.json"))
            require(manifest.id == id && manifest.identityDigest() == revision) {
                "Core staged module manifest identity mismatch"
            }
            verifyDex(File(dir, "$revision.dex"), manifest.sha256)
            manifest
        }

    fun file(context: Context, id: String, revision: String): File {
        staged(context, id, revision) // Always verify full sealed bytes before execution
        return File(directory(context, id), "$revision.dex")
    }

    /**
     * The approved input stream and bounded manifest are both caller-provided;
     * Core checks EVERY field, SHA and size again before sealing private bytes.
     * A crash during staging may leave an unreferenced DEX, never activation.
     */
    fun stage(context: Context, manifestBytes: ByteArray, input: InputStream): JSONObject =
        synchronized(lock) {
            val manifest = RiftCoreModuleManifest.parse(manifestBytes)
            val base = root(context)
            val ids = base.listFiles()?.filter { it.isDirectory }.orEmpty()
            require(ids.size <= MAX_MODULE_IDS &&
                (ids.size < MAX_MODULE_IDS || ids.any { it.name == manifest.id })) {
                "Core module-id limit exceeded"
            }
            val directory = directory(context, manifest.id)
            require(directory.isDirectory || directory.mkdirs()) {
                "Cannot create Core module directory"
            }
            val revision = manifest.identityDigest()
            val existingRevisions = directory.listFiles()?.count {
                it.isFile && it.name.endsWith(".json")
            } ?: 0
            require(existingRevisions < MAX_REVISIONS_PER_ID ||
                File(directory, "$revision.json").exists()) {
                "Core module revision limit exceeded"
            }
            val destination = File(directory, "$revision.dex")
            val record = File(directory, "$revision.json")
            if (destination.exists()) {
                verifyDex(destination, manifest.sha256)
            } else {
                val temporary = File.createTempFile(".module-stage-", ".partial", directory)
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    FileOutputStream(temporary).use { output ->
                        val chunk = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(chunk)
                            if (count < 0) break
                            if (count == 0) continue
                            total += count
                            require(total <= RiftCoreModuleManifest.MAX_MODULE_BYTES) {
                                "Core module exceeds 32 MiB"
                            }
                            digest.update(chunk, 0, count)
                            output.write(chunk, 0, count)
                        }
                        output.fd.sync()
                    }
                    require(total > 8L && manifest.sha256 ==
                        digest.digest().joinToString("") {
                            "%02x".format(it.toInt() and 0xff)
                        }) { "Core module length or digest mismatch" }
                    require(temporary.setReadOnly()) { "Cannot seal Core module DEX" }
                    verifyDex(temporary, manifest.sha256)
                    require(!destination.exists() && temporary.renameTo(destination)) {
                        "Cannot commit immutable Core module DEX"
                    }
                } finally {
                    if (temporary.exists()) temporary.delete()
                }
            }
            if (record.exists()) {
                val prior = readManifest(record)
                require(prior == manifest && prior.identityDigest() == revision) {
                    "Core staged revision metadata mismatch"
                }
            } else {
                val canonical = manifest.receipt().apply { remove("manifestDigest") }
                atomicWrite(record, canonical.toString().toByteArray(Charsets.UTF_8))
            }
            // A staged module has NO activation pointer, registration or grant.
            JSONObject().put("schema", STAGING_SCHEMA)
                .put("id", manifest.id)
                .put("version", manifest.version)
                .put("kind", manifest.kind)
                .put("entrypoint", manifest.entrypoint)
                .put("sha256", manifest.sha256)
                .put("manifestDigest", revision)
                .put("bytes", destination.length())
                .put("staged", true)
                .put("activated", false)
                .put("runtimeProviderRegistered", false)
        }

    fun inventory(context: Context): JSONObject = synchronized(lock) {
        val base = root(context)
        val items = JSONArray()
        val dirs = base.listFiles().orEmpty().filter { it.isDirectory }
        require(dirs.size <= MAX_MODULE_IDS) { "Core module directory count exceeded" }
        for (dir in dirs.sortedBy { it.name }) {
            require(dir.isDirectory && !Files.isSymbolicLink(dir.toPath()) &&
                dir.canonicalFile.parentFile == base) {
                "Unexpected item in Core module-store"
            }
            val candidates = dir.listFiles().orEmpty()
            require(candidates.size <= MAX_REVISIONS_PER_ID * 2 + 8) {
                "Core module directory exceeds bound"
            }
            for (item in candidates.filter { it.name.endsWith(".json") }.sortedBy { it.name }) {
                val revision = item.name.removeSuffix(".json")
                val record = staged(context, dir.name, revision)
                items.put(JSONObject()
                    .put("id", record.id)
                    .put("version", record.version)
                    .put("kind", record.kind)
                    .put("sha256", record.sha256)
                    .put("manifestDigest", revision))
            }
        }
        JSONObject().put("schema", "riftos.core.module-inventory/1")
            .put("stagedCount", items.length())
            .put("modules", items)
            .put("activationEnabled", true)
    }
}
