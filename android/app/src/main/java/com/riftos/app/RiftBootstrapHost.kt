package com.riftos.app

import android.app.Application
import android.util.Log
import dalvik.system.DexClassLoader
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Stable Android process entrypoint and compatibility fallback. The two
 * critical components still run embedded until physical modularization proof.
 * Only "probe" may load separately installed DEX on process startup.
 */
interface RiftBootstrapEntry {
    fun start(application: Application)
}

internal object RiftBootstrapHost {
    const val MODULE_SCHEMA = "riftos.bootstrap-module/1"
    private const val MAX_MODULE_BYTES = 32L * 1024 * 1024
    private const val TAG = "RiftBootstrapHost"

    fun status(context: android.content.Context): JSONObject {
        val root = RiftBootstrapComponentStore.root(context)
        val proof = runCatching {
            val marker = android.util.AtomicFile(File(root, "probe-proof.json"))
            val bytes = marker.openRead().use { stream ->
                val bounded = ByteArray(4097)
                val count = stream.read(bounded)
                require(count in 1..4096 && stream.read() == -1) { "Probe proof oversized" }
                bounded.copyOf(count)
            }
            JSONObject(String(bytes, Charsets.UTF_8)).also {
                require(it.optString("schema") == "riftos.bootstrap-probe-proof/1") {
                    "Invalid probe proof"
                }
            }
        }.getOrNull()
        return JSONObject()
            .put("schema", "riftos.bootstrap-host/1")
            .put("coreActivationPresent", File(root, "core.json").isFile)
            .put("coreInterruptedBoot", File(root, "core.booting").exists())
            .put("shellActivationPresent", File(root, "shell.json").isFile)
            .put("shellInterruptedBoot", File(root, "shell.booting").exists())
            .put("probeActivationPresent", File(root, "probe.json").isFile)
            .put("probeInterruptedBoot", File(root, "probe.booting").exists())
            .put("probeProofPresent", proof != null)
            .put("probeProof", proof ?: JSONObject.NULL)
            .put("criticalExternalActivationEnabled", false)
            .put("inProcessHotSwapEnabled", false)
            .put("embeddedFallbackAvailable", true)
    }

    fun startCore(application: Application) {
        // These authority-bound recoveries belong to the APK host, not an
        // external code module; preserve their exact existing startup order.
        runCatching { RiftCoreAdminRollbackProof.recover(application) }
            .onFailure { Log.e("RiftCoreAdmin", "Rollback recovery failed", it) }
        runCatching { RiftCoreAdminRegistryProof.recover(application) }
            .onFailure { Log.e("RiftCoreAdmin", "Registry recovery failed", it) }
        runCatching { RiftCoreModuleActivation.recover(application) }
            .onFailure { Log.e("RiftModuleHost", "Pending module rollback failed", it) }
        start(application, "core", object : RiftBootstrapEntry {
            override fun start(application: Application) {
                RiftCoreRuntime.initialize(application)
                RiftCoreShellRecovery.initialize(application)
                RiftMcpRuntime.relayClient(application).start()
            }
        })
    }

    /** Called only in :riftBootstrapProbe, never from the main Core process. */
    fun startProbe(application: Application) {
        start(application, "probe", object : RiftBootstrapEntry {
            override fun start(application: Application) = Unit
        })
    }

    fun prepareShell(application: Application) {
        start(application, "shell", object : RiftBootstrapEntry {
            override fun start(application: Application) {
                RiftBrowserWindow.prepareRemoteShellWebViewDirectory()
            }
        })
    }

    private fun start(application: Application, id: String, embedded: RiftBootstrapEntry) {
        // Current Android manifest components and Core services are still
        // compiled inside the APK. Never let an experimental DEX replace
        // either critical process entrypoint before device-proven extraction.
        if (id != "probe") {
            embedded.start(application)
            return
        }

        val root = RiftBootstrapComponentStore.root(application)
        val inProgress = File(root, id + ".booting")
        if (inProgress.exists()) {
            Log.e(TAG, "Interrupted " + id + " module boot; restoring prior activation")
            runCatching { RiftBootstrapComponentStore.recoverProbe(application) }
                .onFailure { Log.e(TAG, "Rollback unavailable; retaining embedded probe", it) }
            embedded.start(application)
            return
        }

        val activation = runCatching { RiftBootstrapComponentStore.active(application, id) }
            .onFailure { Log.e(TAG, "Invalid module activation; retaining embedded probe", it) }
            .getOrNull()
        if (activation == null) {
            embedded.start(application)
            return
        }
        val external = runCatching { load(application, id, activation) }
            .onFailure { Log.e(TAG, "Module invalid; retaining embedded probe", it) }
            .getOrNull()
        if (external == null) {
            embedded.start(application)
            return
        }

        // Persist before calling external code. An exception or process death
        // leaves this marker and triggers previous/embedded recovery on boot.
        require(inProgress.createNewFile()) { "Module bootstrap already in progress" }
        FileOutputStream(inProgress, false).use { output ->
            output.write(("booting:" + id + "\n").toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        try {
            external.start(application)
            require(inProgress.delete()) { "Failed to clear component startup marker" }
            Log.i(TAG, "External " + id + " component started")
        } catch (failure: Throwable) {
            Log.e(TAG, "External " + id + " failed; recovery required next boot", failure)
            throw failure
        }
    }

    private fun load(application: Application, id: String, record: JSONObject): RiftBootstrapEntry {
        require(record.getString("schema") == MODULE_SCHEMA &&
            record.getInt("api") == 1 && record.getString("component") == id) {
            "Unsupported module ABI"
        }
        val className = record.getString("entrypoint")
        require(className.matches(Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$"))) {
            "Invalid entrypoint"
        }
        val sha = record.getString("sha256")
        require(sha.matches(Regex("^[0-9a-f]{64}$"))) { "Invalid module fingerprint" }
        val module = RiftBootstrapComponentStore.dexFile(application, id, sha)
        require(module.isFile && !Files.isSymbolicLink(module.toPath()) &&
            module.length() in 1L..MAX_MODULE_BYTES && !module.canWrite()) {
            "Module DEX is not immutable"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        module.inputStream().buffered().use { stream ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                if (n > 0) digest.update(buf, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        require(actual == sha) { "Module digest mismatch" }
        require(runCatching { application.classLoader.loadClass(className) }.isFailure) {
            "External module entrypoint must not shadow an APK-owned class"
        }
        val loader = DexClassLoader(module.absolutePath, application.codeCacheDir.absolutePath,
            null, application.classLoader)
        val type = loader.loadClass(className)
        require(RiftBootstrapEntry::class.java.isAssignableFrom(type)) {
            "Module entrypoint contract mismatch"
        }
        return type.getDeclaredConstructor().newInstance() as RiftBootstrapEntry
    }
}
