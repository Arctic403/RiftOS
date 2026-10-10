package com.riftos.app

import android.app.Application
import android.util.AtomicFile
import android.util.Log
import dalvik.system.DexClassLoader
import dalvik.system.DexFile
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest

/**
 * E1 safe-startup selector infrastructure.
 *
 * The APK-owned, proven embedded Core is always available. A future extracted
 * Core DEX can be selected ONLY after a separate trusted installer produces
 * BOTH an immutable Core activation pointer and an independently verified
 * qualification receipt for the exact SHA/entrypoint/ABI. No production
 * activation/qualification writer is exposed in E1; the switch is dormant.
 *
 * This is a same-UID, same-process classloader, NOT a security sandbox.
 * Core's current broad Context callback / transitive APK-owned dependencies
 * must be removed from external implementations before qualification.
 */
internal object RiftCoreCandidateSwitch {
    const val SCHEMA = "riftos.core.candidate-switch/1"
    private const val QUALIFIED_SCHEMA = "riftos.core.qualified/1"
    private const val BOOT_MARKER = "core.booting"
    private const val QUALIFIED_FILE = "core.qualified.json"
    private const val MAX_RECORD_BYTES = 4096
    private const val MAX_DEX_BYTES = 32L * 1024 * 1024
    private const val MAX_CLASSES = 4096
    private const val PREFIX = "com.riftos.external.core."
    private const val TAG = "RiftCoreCandidateSwitch"
    private val DIGEST = Regex("^[0-9a-f]{64}$")

    @Volatile private var lastSelection = "embedded"
    @Volatile private var lastFallback = "none"

    private fun root(application: Application) =
        RiftBootstrapComponentStore.root(application)

    private fun readQualification(file: File): JSONObject? {
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        require(!Files.isSymbolicLink(file.toPath())) { "Core qualification symlink" }
        val bytes = AtomicFile(file).openRead().use { input ->
            val buffer = ByteArray(MAX_RECORD_BYTES + 1)
            val count = input.read(buffer)
            require(count in 1..MAX_RECORD_BYTES && input.read() == -1) {
                "Core qualification oversized"
            }
            buffer.copyOf(count)
        }
        return JSONObject(String(bytes, Charsets.UTF_8))
    }

    private fun verifySha(file: File, expected: String) {
        require(expected.matches(DIGEST)) { "Core DEX SHA format invalid" }
        require(file.isFile && !Files.isSymbolicLink(file.toPath()) &&
            file.length() in 1L..MAX_DEX_BYTES && !file.canWrite()) {
            "Core candidate must be a sealed regular DEX"
        }
        val hasher = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (count > 0) hasher.update(buffer, 0, count)
            }
        }
        val actual = hasher.digest().joinToString("") {
            "%02x".format(it.toInt() and 255)
        }
        require(actual == expected) { "Core DEX SHA mismatch" }
    }

    private fun load(application: Application, record: JSONObject): RiftCoreComponentV1 {
        require(record.getString("schema") == RiftBootstrapHost.MODULE_SCHEMA &&
            record.getInt("api") == 1 && record.getString("component") == "core") {
            "Core activation pointer ABI invalid"
        }
        val entrypoint = record.getString("entrypoint")
        val sha = record.getString("sha256")
        require(entrypoint.startsWith(PREFIX) && sha.matches(DIGEST)) {
            "Core candidate namespace/SHA invalid"
        }
        val qualification = readQualification(File(root(application), QUALIFIED_FILE))
            ?: error("Independent Core qualification receipt missing")
        require(qualification.getString("schema") == QUALIFIED_SCHEMA &&
            qualification.getInt("abi") == RiftHostCoreComponents.ABI_VERSION &&
            qualification.getString("sha256") == sha &&
            qualification.getString("entrypoint") == entrypoint &&
            qualification.getBoolean("completeOwnership") &&
            qualification.getBoolean("inactiveLoadProven")) {
            "Core candidate not independently qualified for this ABI/SHA"
        }
        val file = RiftBootstrapComponentStore.dexFile(application, "core", sha)
        verifySha(file, sha)

        // Fail closed if any candidate-defined class is APK-owned or shadows
        // an Android/host class. The candidate's complete class inventory must
        // be checked BEFORE external bytecode executes.
        @Suppress("DEPRECATION")
        val dex = DexFile(file.absolutePath)
        try {
            val entries = dex.entries()
            var count = 0
            var foundEntry = false
            while (entries.hasMoreElements()) {
                val name = entries.nextElement()
                count++
                require(count <= MAX_CLASSES) { "Core candidate class bound exceeded" }
                require(name.startsWith(PREFIX)) {
                    "Core candidate contains an unexpected package"
                }
                require(runCatching { application.classLoader.loadClass(name) }.isFailure) {
                    "Core candidate duplicates an APK-owned class"
                }
                if (name == entrypoint) foundEntry = true
            }
            require(count > 0 && foundEntry) { "Core candidate entrypoint missing" }
        } finally {
            dex.close()
        }

        val loader = DexClassLoader(file.absolutePath,
            application.codeCacheDir.absolutePath, null, application.classLoader)
        val implementation = loader.loadClass(entrypoint)
        require(implementation.classLoader === loader &&
            RiftCoreComponentV1::class.java.isAssignableFrom(implementation)) {
            "Core candidate did not implement APK-owned Core V1 ABI"
        }
        return implementation.getDeclaredConstructor().newInstance() as RiftCoreComponentV1
    }

    /** Any interrupted unaccepted Core boot is rolled back on the NEXT start. */
    @Synchronized
    fun selectAtBoot(application: Application): RiftCoreComponentV1? {
        lastSelection = "embedded"
        val root = root(application)
        val marker = File(root, BOOT_MARKER)
        if (marker.exists()) {
            fallback(application, "interrupted-boot")
            return null
        }
        val record = runCatching { RiftBootstrapComponentStore.active(application, "core") }
            .getOrElse {
                fallback(application, "invalid-activation-record")
                return null
            } ?: return null
        val candidate = runCatching { load(application, record) }.getOrElse {
            Log.e(TAG, "Core candidate rejected, using embedded", it)
            RiftCoreRecoveryDiagnostics.recordCandidateFailure(application, "verification", it)
            fallback(application, "candidate-verification-failed")
            return null
        }
        try {
            require(marker.createNewFile()) { "Core startup marker not exclusive" }
            FileOutputStream(marker, false).use {
                it.write("unaccepted-core-startup\n".toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
        } catch (failure: Throwable) {
            fallback(application, "startup-marker-failed")
            return null
        }
        lastSelection = "external-unaccepted"
        return candidate
    }

    /** Revoke only the fixed private Core activation pointer; retain sealed DEX. */
    @Synchronized
    fun fallback(application: Application, reason: String) {
        lastSelection = "embedded"
        lastFallback = reason
        try {
            RiftBootstrapComponentStore.resetCoreToEmbedded(application)
        } catch (failure: Throwable) {
            // Never run a failed external implementation. Pending marker
            // remains as conservative boot protection if rollback fails.
            Log.e(TAG, "Core activation rollback requires recovery", failure)
        }
    }

    fun status(): JSONObject = JSONObject()
        .put("schema", SCHEMA)
        .put("selected", lastSelection)
        .put("lastFallback", lastFallback)
        .put("inactiveCandidateOnly", lastSelection == "embedded")
        .put("automaticPromotionEnabled", false)
        .put("inProcessHotSwapEnabled", false)
}
