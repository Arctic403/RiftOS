package com.riftos.external.core

import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.riftos.app.RiftCoreComponentV1
import com.riftos.app.RiftCoreExecutionViewV1
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Genuine separately compiled Core state/catalog implementation.
 *
 * QuickJS is intentionally retained in the stable APK as a zero-authority,
 * versioned host execution service. This Core owns the RAPP session/event/
 * frame lifecycle and calls only RiftHostQuickJsV1, never embedded Core.
 *
 * Deliberately NOT selectable until an independent permissions/effects
 * broker, install/uninstall parity and on-device Core replacement/recovery
 * proof are complete. Runtime compilation is not promotion evidence.
 */
class IndependentCoreV1 : RiftCoreComponentV1, RiftCoreExecutionViewV1 {
    companion object {
        private const val SCHEMA = "riftos.external.core/1"
        // Independently recompiled trial revision; never implies device acceptance.
        private const val CANDIDATE_VERSION = "0.6.1"
        private const val APP_SCHEMA = "riftos.rapp/1"
        private const val MAX_APPS = 128
        private const val MAX_META_BYTES = 65536L
        private const val MAX_PROGRAM_BYTES = 1024 * 1024L
        private const val MAX_RUNTIME_BYTES = 8L * 1024 * 1024
        // This is NOT a production Core until the independent grant/effect
        // broker, install/uninstall and N-1 device proof are complete.
        // User-confirmed protected Recovery install may launch this candidate
        // for physical qualification; it is NOT device-accepted/production.
        // The APK selector retains failed-startup and manual embedded rollback.
        private const val EXTERNAL_CORE_MANUAL_DEVICE_TRIAL_ALLOWED = true
        private const val EXTERNAL_CORE_PROMOTION_PROVEN = false
        private val APP_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{1,63}$")
    }

    @Volatile private var app: Context? = null
    private var startedElapsedMs: Long = 0L
    private var focusId: String? = null
    private val lock = Any()
    private var independentSessions: IndependentRappSessions? = null
    private var javascriptRegistry: IndependentRuntimeRegistry? = null
    private var packagesOwner: IndependentRappPackages? = null

    override fun initialize(context: Context) {
        synchronized(lock) {
            if (app != null) return
            val application = context.applicationContext
            val root = File(application.filesDir, "riftfs")
            require(root.isDirectory && !Files.isSymbolicLink(root.toPath())) {
                "Independent Core RiftFS backing root unavailable"
            }
            require(EXTERNAL_CORE_MANUAL_DEVICE_TRIAL_ALLOWED) {
                "External Core device trial is not enabled for this source revision"
            }
            val programs = File(root, "system/programs")
            require(programs.isDirectory && !Files.isSymbolicLink(programs.toPath())) {
                "Independent Core cannot discover actual installed RAPP packages"
            }
            // Owning the catalogue in this DEX never starts the embedded
            // RiftCoreRuntime singleton; there is no implicit delegate.
            javascriptRegistry = IndependentRuntimeRegistry(application)
            val broker = IndependentRappEffects(application,
                installed = { id -> packagesOwner?.handlesInstalled(id) == true },
                authorizeGrant = { id, current, persist ->
                    packagesOwner?.withLivePackage(id, current, persist) == true
                })
            independentSessions = IndependentRappSessions(
                application, requireNotNull(javascriptRegistry),
                { id -> checkedPackage(application, id) },
                programRoot = programs, effects = broker
            )
            packagesOwner = IndependentRappPackages(application,
                stopRunningApp = { id -> requireNotNull(independentSessions).invalidateInstalled(id) },
                startRunningApp = { id -> requireNotNull(independentSessions).open(id) })
            app = application
            startedElapsedMs = SystemClock.elapsedRealtime()
        }
    }

    private fun context(context: Context): Context {
        if (app == null) initialize(context)
        return requireNotNull(app)
    }

    private fun programs(context: Context): File {
        val root = File(context(context).filesDir, "riftfs")
        val dir = File(root, "system/programs")
        require(dir.isDirectory && !Files.isSymbolicLink(dir.toPath())) {
            "Independent Core managed program directory missing"
        }
        return dir.canonicalFile
    }

    private fun checkedPackage(context: Context, id: String): JSONObject {
        require(APP_ID.matches(id)) { "Invalid installed RAPP id" }
        val root = programs(context)
        val folder = File(root, id)
        require(folder.isDirectory && !Files.isSymbolicLink(folder.toPath()) &&
            folder.canonicalFile.parentFile == root) {
            "RAPP package folder invalid"
        }
        val file = File(folder, "package.json")
        require(file.isFile && !Files.isSymbolicLink(file.toPath()) &&
            file.length() in 1L..MAX_META_BYTES) {
            "RAPP metadata missing or oversized"
        }
        val parsed = JSONObject(file.readText(Charsets.UTF_8))
        val manifest = parsed.getJSONObject("manifest")
        val rapp = parsed.getJSONObject("riftApp")
        require(parsed.getString("format") == "rift-program-package-v1" &&
            manifest.getString("id") == id &&
            rapp.getString("schema") == APP_SCHEMA &&
            rapp.getString("id") == id) {
            "RAPP metadata not a managed package"
        }
        for (name in arrayOf("program.bin", "runtime.bin")) {
            val asset = File(folder, name)
            require(asset.isFile && !Files.isSymbolicLink(asset.toPath()) &&
                asset.canonicalFile.parentFile == folder.canonicalFile &&
                asset.length() in 1L..(if (name == "runtime.bin") MAX_RUNTIME_BYTES else MAX_PROGRAM_BYTES)) {
                "Installed RAPP executable bytes not valid"
            }
            val expected = rapp.getString(
                if (name == "program.bin") "programSha256" else "runtimeSha256")
            require(expected.matches(Regex("^[0-9a-f]{64}$"))) {
                "RAPP stored hash format invalid"
            }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(asset.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            require(digest == expected) { "Installed RAPP binary hash changed" }
        }
        return JSONObject().put("id", id)
            .put("name", manifest.optString("name", id).take(128))
            .put("launcherIcon", manifest.optString("launcherIcon", "□").take(20))
            .put("abi", rapp.getString("abi"))
            .put("adapter", rapp.getString("adapter"))
            .put("presentation", rapp.getString("presentation"))
            .put("permissions", rapp.optJSONArray("permissions") ?: JSONArray())
            .put("path", "/C:/Programs/$id")
    }

    override fun installed(context: Context): JSONArray {
        val out = JSONArray()
        val root = programs(context)
        val candidates = root.listFiles()?.asSequence()
            ?.filter { it.isDirectory && APP_ID.matches(it.name) }
            ?.sortedBy { it.name.lowercase() }?.take(MAX_APPS)
            ?: emptySequence()
        for (dir in candidates) {
            val entry = runCatching { checkedPackage(context, dir.name) }.getOrNull()
            if (entry != null) out.put(entry)
        }
        return out
    }

    private fun sessions(context: Context): IndependentRappSessions {
        context(context)
        return requireNotNull(independentSessions)
    }

    private fun independentRegistry(context: Context): IndependentRuntimeRegistry {
        context(context)
        return requireNotNull(javascriptRegistry)
    }

    private fun packageManager(context: Context): IndependentRappPackages {
        context(context)
        return requireNotNull(packagesOwner)
    }

    private fun unavailable(operation: String): Nothing {
        // No fake "success"/frame or host-runtime proxy is permitted here.
        error("Independent Core $operation blocked: install/grant capability authority not yet independently proven")
    }

    override fun open(context: Context, id: String): JSONObject {
        return sessions(context).open(id)
    }

    override fun startApp(context: Context, id: String): JSONObject = open(context, id)

    override fun reattach(context: Context, id: String, generation: Long): JSONObject {
        return sessions(context).reattach(id, generation)
    }

    override fun stop(context: Context, id: String, generation: Long): JSONObject {
        return sessions(context).close(id, generation)
    }

    override fun stopApp(context: Context, id: String): JSONObject {
        return sessions(context).close(id, null)
    }

    override fun snapshot(context: Context, id: String): JSONObject {
        require(APP_ID.matches(id))
        return sessions(context).snapshot(id)
    }

    override fun focus(context: Context, id: String?): JSONObject {
        return sessions(context).focus(id)
    }

    override fun event(context: Context, id: String, generation: Long,
                       payload: JSONObject): JSONObject {
        return sessions(context).offer(id, generation, payload)
    }

    override fun install(context: Context, artifact: String): JSONObject {
        val display = artifact.trim().replace('\\', '/')
        val full = when {
            display.startsWith("/D:/Builds/") -> display
            display.startsWith("D:/Builds/") -> "/$display"
            else -> "/D:/Builds/" + display.trimStart('/')
        }
        val relative = RiftVolumePaths.normalizeDisplay(full)
            .removePrefix("/D:/Builds/")
        require(relative.isNotBlank() && !relative.startsWith("/")) {
            "RAPP install artifact must be on D:/Builds"
        }
        val root = File(context(context).filesDir, "riftfs/documents/builds").canonicalFile
        val file = File(root, relative).canonicalFile
        require(file.parentFile != null &&
            file.path.startsWith(root.path + File.separator)) {
            "RAPP artifact escapes D:/Builds"
        }
        return packageManager(context).install(file)
    }

    override fun uninstall(context: Context, id: String): JSONObject =
        packageManager(context).uninstall(id)

    override fun runningApps(context: Context): JSONArray =
        sessions(context).runningApps()

    override fun sessionsView(context: Context): JSONObject =
        sessions(context).sessionsView()

    override fun surfacesView(context: Context): JSONObject =
        sessions(context).surfacesView()

    override fun focusView(context: Context): JSONObject =
        sessions(context).focusView()

    override fun discoverRuntimeCandidates(context: Context): JSONObject =
        independentRegistry(context).state()

    override fun coreStatus(context: Context): JSONObject {
        val application = context(context)
        return JSONObject().put("schema", SCHEMA)
            .put("owner", "independent-external-dex")
            .put("candidateRevision", CANDIDATE_VERSION)
            .put("pid", Process.myPid())
            .put("uptimeMs", (SystemClock.elapsedRealtime() - startedElapsedMs).coerceAtLeast(0L))
            .put("installedRappCount", installed(application).length())
            .put("appSessions", sessionsView(application))
            .put("appSurfaces", surfacesView(application))
            .put("coreApps", JSONObject().put("schema", "riftos.core.apps/1")
                .put("apps", runningApps(application))
                .put("count", runningApps(application).length()))
            .put("runtimeExecutionReady", true)
            .put("manualDeviceTrialAllowed", EXTERNAL_CORE_MANUAL_DEVICE_TRIAL_ALLOWED)
            .put("candidateComplete", true)
            .put("deviceQualificationComplete", false)
            .put("readyForPromotion", EXTERNAL_CORE_PROMOTION_PROVEN)
    }
}
