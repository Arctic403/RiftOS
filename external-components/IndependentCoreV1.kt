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
 * Deliberately NOT ready for promotion: the current RiftOS RAPP programs
 * execute JavaScript through a Core-owned QuickJS adapter. No independent
 * QuickJS adapter/capability broker has been extracted to this DEX yet.
 *
 * Never claim success for BOOT, frame rendering or input before a real runtime
 * executes the exact installed application. These routes fail closed until
 * the runtime is independently available. The read-only Core catalogue,
 * process ownership and typed V1 ABI are implemented without importing any
 * APK-owned execution class.
 */
class IndependentCoreV1 : RiftCoreComponentV1, RiftCoreExecutionViewV1 {
    companion object {
        private const val SCHEMA = "riftos.external.core/1"
        private const val APP_SCHEMA = "riftos.rapp/1"
        private const val MAX_APPS = 128
        private const val MAX_META_BYTES = 65536L
        private const val MAX_PROGRAM_BYTES = 1024 * 1024L
        private const val MAX_RUNTIME_BYTES = 8L * 1024 * 1024
        // This is NOT a production Core until both external JS execution
        // and independent user-grant/effect authority pass device evidence.
        private const val RAPP_ENGINE_AND_BROKER_PROVEN = false
        private val APP_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{1,63}$")
    }

    @Volatile private var app: Context? = null
    private var startedElapsedMs: Long = 0L
    private var focusId: String? = null
    private val lock = Any()
    private var independentSessions: IndependentRappSessions? = null
    private var javascriptRegistry: IndependentRuntimeRegistry? = null

    override fun initialize(context: Context) {
        synchronized(lock) {
            if (app != null) return
            val application = context.applicationContext
            val root = File(application.filesDir, "riftfs")
            require(root.isDirectory && !Files.isSymbolicLink(root.toPath())) {
                "Independent Core RiftFS backing root unavailable"
            }
            require(RAPP_ENGINE_AND_BROKER_PROVEN) {
                "External Core v0.2 not selectable: standalone JS VM and capability broker unproven"
            }
            val programs = File(root, "system/programs")
            require(programs.isDirectory && !Files.isSymbolicLink(programs.toPath())) {
                "Independent Core cannot discover actual installed RAPP packages"
            }
            // Owning the catalogue in this DEX never starts the embedded
            // RiftCoreRuntime singleton; there is no implicit delegate.
            javascriptRegistry = IndependentRuntimeRegistry(application)
            independentSessions = IndependentRappSessions(
                application, requireNotNull(javascriptRegistry),
                { id -> checkedPackage(application, id) }, programRoot = programs
            )
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

    private fun unavailable(operation: String): Nothing {
        // No fake "success"/frame or host-runtime proxy is permitted here.
        error("Independent Core $operation blocked: external RAPP runtime/executor and capability broker not yet extracted")
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

    override fun install(context: Context, artifact: String): JSONObject =
        unavailable("install without independent grant/rollback authority")

    override fun uninstall(context: Context, id: String): JSONObject =
        unavailable("uninstall without independent grant revocation")

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
            .put("pid", Process.myPid())
            .put("uptimeMs", (SystemClock.elapsedRealtime() - startedElapsedMs).coerceAtLeast(0L))
            .put("installedRappCount", installed(application).length())
            .put("appSessions", sessionsView(application))
            .put("appSurfaces", surfacesView(application))
            .put("coreApps", JSONObject().put("schema", "riftos.core.apps/1")
                .put("apps", runningApps(application))
                .put("count", runningApps(application).length()))
            .put("runtimeExecutionReady", false)
            .put("candidateComplete", false)
            .put("readyForPromotion", false)
    }
}
