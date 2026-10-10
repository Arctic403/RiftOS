package com.riftos.app

import android.app.Application
import android.util.Log
import dalvik.system.DexClassLoader
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * The stable APK-side entrypoint that can start an embedded compatibility
 * component or a separately delivered DEX component on the next process boot.
 *
 * No live in-process class replacement: a new classloader is selected only
 * at an Android process startup. Shell and Core have independent selections.
 */
interface RiftBootstrapEntry {
    fun start(application: Application)
}

internal object RiftBootstrapHost {
    const val MODULE_SCHEMA = "riftos.bootstrap-module/1"
    private const val MAX_MODULE_BYTES = 32L * 1024 * 1024
    private const val TAG = "RiftBootstrapHost"

    /** Passive diagnostics. Does not load or execute any component. */
    fun status(context: android.content.Context): JSONObject {
        val root = File(context.applicationContext.filesDir, "bootstrap-components")
        return JSONObject()
            .put("schema", "riftos.bootstrap-host/1")
            .put("coreActivationPresent", File(root, "core.json").isFile)
            .put("coreInterruptedBoot", File(root, "core.booting").exists())
            .put("shellActivationPresent", File(root, "shell.json").isFile)
            .put("shellInterruptedBoot", File(root, "shell.booting").exists())
            .put("inProcessHotSwapEnabled", false)
            .put("embeddedFallbackAvailable", true)
    }

    fun startCore(application: Application) {
        // The stable host, not an optional Core plug-in, always owns these
        // C1.4 interrupted-transaction recoveries before module selection.
        runCatching { RiftCoreAdminRollbackProof.recover(application) }
            .onFailure { Log.e("RiftCoreAdmin", "Rollback recovery failed", it) }
        runCatching { RiftCoreAdminRegistryProof.recover(application) }
            .onFailure { Log.e("RiftCoreAdmin", "Registry recovery failed", it) }
        start(application, "core", object : RiftBootstrapEntry {
            override fun start(application: Application) {
                RiftCoreRuntime.initialize(application)
                RiftCoreShellRecovery.initialize(application)
                RiftMcpRuntime.relayClient(application).start()
            }
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
        val root = File(application.filesDir, "bootstrap-components")
        val activation = File(root, id + ".json")
        if (!activation.exists()) {
            embedded.start(application)
            return
        }

        // A previously interrupted external bootstrap always returns to the
        // known embedded baseline. Do not repeatedly crash-loop the same DEX.
        val inProgress = File(root, id + ".booting")
        if (inProgress.exists()) {
            Log.e(TAG, "Interrupted " + id + " module boot; using embedded compatibility component")
            embedded.start(application)
            return
        }

        val external = runCatching { load(application, root, id, activation) }
            .onFailure { Log.e(TAG, "Invalid " + id + " module; retaining embedded component", it) }
            .getOrNull()
        if (external == null) {
            embedded.start(application)
            return
        }

        // Marker is synced before untrusted/external entrypoint control. If
        // start throws or the process dies, the marker prevents a boot loop.
        root.mkdirs()
        require(inProgress.createNewFile()) { "Module bootstrap already in progress" }
        FileOutputStream(inProgress, false).use { stream ->
            stream.write(("booting:" + id + "\n").toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        try {
            external.start(application)
            require(inProgress.delete()) { "Could not finish module bootstrap marker" }
            Log.i(TAG, "External " + id + " component started")
        } catch (failure: Throwable) {
            Log.e(TAG, "External " + id + " startup failed; embedded recovery on next launch", failure)
            throw failure
        }
    }

    private fun load(
        application: Application,
        root: File,
        id: String,
        activation: File
    ): RiftBootstrapEntry {
        require(activation.isFile && activation.length() in 1L..4096L) {
            "Invalid activation record"
        }
        val record = JSONObject(activation.readText(Charsets.UTF_8))
        require(record.getString("schema") == MODULE_SCHEMA &&
            record.getString("component") == id &&
            record.getInt("api") == 1) {
            "Unsupported bootstrap component contract"
        }
        val className = record.getString("entrypoint")
        require(className.matches(Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$"))) {
            "Invalid module entrypoint"
        }
        val expectedSha = record.getString("sha256")
        require(expectedSha.matches(Regex("^[0-9a-f]{64}$"))) { "Invalid module digest" }

        // Fixed names prohibit manifest-controlled paths escaping app-private
        // storage. Modules are immutable while the classloader uses them.
        val module = File(root, id + ".dex")
        require(module.isFile && module.length() in 1L..MAX_MODULE_BYTES && !module.canWrite()) {
            "Module DEX must be immutable and within size limits"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        module.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        val actualSha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        require(actualSha == expectedSha) { "Bootstrap DEX digest mismatch" }
        val loader = DexClassLoader(module.absolutePath, application.codeCacheDir.absolutePath,
            null, application.classLoader)
        val type = loader.loadClass(className)
        require(RiftBootstrapEntry::class.java.isAssignableFrom(type)) {
            "Module does not implement the bootstrap entrypoint"
        }
        return type.getDeclaredConstructor().newInstance() as RiftBootstrapEntry
    }
}
