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
 * Guarded alternate real graphical Shell. Default stays APK-embedded.
 *
 * This is a trusted same-UID Android DEX component, never a sandbox; qualified
 * version must own the actual desktop/window manager to pass device proof.
 * This class exposes NO staging, qualification, promotion or activation writer.
 */
internal object RiftShellCandidateSwitch {
    const val SCHEMA = "riftos.shell.candidate-switch/1"
    private const val QUALIFIED_SCHEMA = "riftos.shell.qualified/1"
    private const val ENTRY_PREFIX = "com.riftos.external.shell."
    private const val MAX_MODULE_BYTES = 32L * 1024 * 1024
    private const val MAX_CLASSES = 4096
    private val SHA = Regex("^[0-9a-f]{64}$")
    private const val TAG = "RiftShellCandidate"
    @Volatile private var selection = "embedded"
    @Volatile private var selectedSha: String? = null
    @Volatile private var fallbackReason = "none"

    private fun readQualified(application: Application): JSONObject? {
        val file = File(RiftBootstrapComponentStore.root(application), "shell.qualified.json")
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        require(!Files.isSymbolicLink(file.toPath())) { "Shell qualification symlink" }
        val bytes = AtomicFile(file).openRead().use { stream ->
            val buffer = ByteArray(4097)
            val count = stream.read(buffer)
            require(count in 1..4096 && stream.read() == -1) {
                "Shell qualification record oversized"
            }
            buffer.copyOf(count)
        }
        return JSONObject(String(bytes, Charsets.UTF_8))
    }

    private fun load(application: Application, record: JSONObject): RiftShellGraphicalComponentV1 {
        require(record.getString("schema") == RiftBootstrapHost.MODULE_SCHEMA &&
            record.getString("component") == "shell" && record.getInt("api") == 1) {
            "Unsupported graphical Shell component record"
        }
        val sha = record.getString("sha256")
        val entrypoint = record.getString("entrypoint")
        require(sha.matches(SHA) && entrypoint.startsWith(ENTRY_PREFIX) &&
            entrypoint.matches(Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$"))) {
            "Shell revision identity invalid"
        }
        val qualified = RiftProtectedRevisionRecovery.verifiedReceipt(
            application, "shell", record)
        require(qualified.getString("schema") == QUALIFIED_SCHEMA &&
            qualified.getInt("abi") == RiftHostCoreComponents.ABI_VERSION &&
            qualified.getString("sha256") == sha &&
            qualified.getString("entrypoint") == entrypoint &&
            qualified.getBoolean("completeOwnership") &&
            qualified.getBoolean("inactiveLoadProven")) {
            "Shell revision not independently qualified"
        }
        val file = RiftBootstrapComponentStore.dexFile(application, "shell", sha)
        require(file.isFile && !Files.isSymbolicLink(file.toPath()) &&
            file.length() in 1L..MAX_MODULE_BYTES && !file.canWrite()) {
            "Shell DEX is not immutable"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        require(digest.digest().joinToString("") {
            "%02x".format(it.toInt() and 255)
        } == sha) { "Shell DEX digest mismatch" }

        @Suppress("DEPRECATION")
        val dex = DexFile(file.absolutePath)
        try {
            val entries = dex.entries()
            var count = 0
            var hasEntrypoint = false
            while (entries.hasMoreElements()) {
                val name = entries.nextElement()
                count++
                require(count <= MAX_CLASSES && name.startsWith(ENTRY_PREFIX)) {
                    "Shell candidate includes host or unsupported classes"
                }
                require(runCatching {
                    application.classLoader.loadClass(name)
                }.isFailure) { "Shell candidate class shadows host APK" }
                if (name == entrypoint) hasEntrypoint = true
            }
            require(count > 0 && hasEntrypoint) { "Shell candidate entrypoint not in DEX" }
        } finally {
            dex.close()
        }

        val loader = DexClassLoader(file.absolutePath,
            application.codeCacheDir.absolutePath, null, application.classLoader)
        val type = loader.loadClass(entrypoint)
        require(type.classLoader === loader &&
            RiftShellGraphicalComponentV1::class.java.isAssignableFrom(type)) {
            "Shell candidate does not implement native graphical V1 contract"
        }
        return type.getDeclaredConstructor().newInstance() as RiftShellGraphicalComponentV1
    }

    @Synchronized
    fun selectAtBoot(application: Application): RiftShellGraphicalComponentV1? {
        selection = "embedded"
        selectedSha = null
        val marker = File(RiftBootstrapComponentStore.root(application), "shell.booting")
        if (marker.exists()) {
            fallback(application, "interrupted-shell-boot")
            return null
        }
        val active = runCatching {
            RiftBootstrapComponentStore.active(application, "shell")
        }.getOrElse {
            fallback(application, "invalid-shell-activation")
            return null
        } ?: return null
        val candidate = runCatching { load(application, active) }.getOrElse {
            Log.e(TAG, "External graphical Shell rejected", it)
            fallback(application, "invalid-shell-candidate")
            return null
        }
        try {
            require(marker.createNewFile()) { "Shell startup already in progress" }
            FileOutputStream(marker, false).use {
                it.write("unaccepted-shell-startup\n".toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
        } catch (failure: Throwable) {
            fallback(application, "shell-startup-marker-failed")
            return null
        }
        selection = "external-unaccepted"
        selectedSha = active.getString("sha256")
        return candidate
    }

    @Synchronized
    fun fallback(application: Application, reason: String) {
        selection = "embedded"
        selectedSha = null
        fallbackReason = reason
        val decision = RiftProtectedRevisionRecovery.rollback(
            application, "shell", reason)
        if (!decision.optBoolean("rollbackRecorded", false)) {
            Log.w(TAG, "Shell revision recovery degraded to embedded")
        }
    }

    /**
     * The real Shell process reports its exact attached graphical revision.
     * Core checks actual Binder PID and this private, bounded receipt before
     * a user may accept a known-good Shell update.
     */
    fun reportAttached(application: Application) {
        val sha = selectedSha ?: error("Embedded Shell has no external receipt")
        require(selection == "external-unaccepted") {
            "Cannot report an unselected graphical Shell"
        }
        val active = RiftBootstrapComponentStore.active(application, "shell")
            ?: error("Shell activation pointer absent")
        require(active.getString("sha256") == sha) {
            "Graphical Shell active SHA changed before attach"
        }
        val alreadyAccepted = RiftComponentReleaseLedger.isAccepted(
            application, "shell", sha)
        if (alreadyAccepted) {
            RiftBootstrapComponentStore.clearProtectedStartupMarker(
                application, "shell")
            selection = "external-known-good"
        }
        val record = JSONObject().put("schema", "riftos.shell.attached/1")
            .put("sha256", sha)
            .put("pid", android.os.Process.myPid())
            .put("atMs", System.currentTimeMillis())
        val output = File(RiftBootstrapComponentStore.root(application),
            "shell-attached.json")
        require(!Files.isSymbolicLink(output.toPath()) &&
            !Files.isSymbolicLink(File(output.path + ".bak").toPath())) {
            "Shell attached receipt symlinked"
        }
        val atomic = AtomicFile(output)
        val bytes = record.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..4096)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (failure: Throwable) {
            atomic.failWrite(stream)
            throw failure
        }
    }

    fun attached(context: android.content.Context): JSONObject? {
        val file = File(RiftBootstrapComponentStore.root(context),
            "shell-attached.json")
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        require(!Files.isSymbolicLink(file.toPath())) { "Shell attached status symlinked" }
        val bytes = AtomicFile(file).openRead().use {
            val buf = ByteArray(4097)
            val count = it.read(buf)
            require(count in 1..4096 && it.read() == -1) {
                "Shell attached receipt oversized"
            }
            buf.copyOf(count)
        }
        return JSONObject(String(bytes, Charsets.UTF_8)).also {
            require(it.getString("schema") == "riftos.shell.attached/1" &&
                it.getString("sha256").matches(SHA) &&
                it.getInt("pid") > 0) { "Shell attached receipt corrupt" }
        }
    }

    fun reportStopped(application: Application) {
        val file = File(RiftBootstrapComponentStore.root(application),
            "shell-attached.json")
        val old = runCatching { attached(application) }.getOrNull()
        if (old?.optInt("pid") == android.os.Process.myPid()) {
            AtomicFile(file).delete()
        }
    }

    fun status(): JSONObject = JSONObject()
        .put("schema", SCHEMA)
        .put("selected", selection)
        .put("selectedSha256", selectedSha ?: JSONObject.NULL)
        .put("lastFallback", fallbackReason)
        .put("automaticPromotionEnabled", false)
        .put("inProcessHotSwapEnabled", false)
}
