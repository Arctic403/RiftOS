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
        private val APP_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{1,63}$")
    }

    @Volatile private var app: Context? = null
    private var startedElapsedMs: Long = 0L
    private var focusId: String? = null
    private val lock = Any()

    override fun initialize(context: Context) {
        synchronized(lock) {
            if (app != null) return
            val application = context.applicationContext
            val root = File(application.filesDir, "riftfs")
            require(root.isDirectory && !Files.isSymbolicLink(root.toPath())) {
                "Independent Core RiftFS backing root unavailable"
            }
            val programs = File(root, "system/programs")
            require(programs.isDirectory && !Files.isSymbolicLink(programs.toPath())) {
                "Independent Core cannot discover actual installed RAPP packages"
            }
            // Owning the catalogue in this DEX never starts the embedded
            // RiftCoreRuntime singleton; there is no implicit delegate.
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
                asset.length() in 1L..1048576L) {
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

    private fun unavailable(operation: String): Nothing {
        // No fake "success"/frame or host-runtime proxy is permitted here.
        error("Independent Core $operation blocked: external RAPP runtime/executor and capability broker not yet extracted")
    }

    override fun open(context: Context, id: String): JSONObject {
        checkedPackage(context, id)
        unavailable("BOOT")
    }

    override fun startApp(context: Context, id: String): JSONObject = open(context, id)

    override fun reattach(context: Context, id: String, generation: Long): JSONObject {
        require(generation > 0L && APP_ID.matches(id))
        unavailable("reattach")
    }

    override fun stop(context: Context, id: String, generation: Long): JSONObject {
        require(generation > 0L && APP_ID.matches(id))
        unavailable("stop session")
    }

    override fun stopApp(context: Context, id: String): JSONObject {
        require(APP_ID.matches(id))
        unavailable("stop app")
    }

    override fun snapshot(context: Context, id: String): JSONObject {
        require(APP_ID.matches(id))
        return JSONObject().put("schema", "riftos.core.surface-ipc/1")
            .put("owner", "riftos-external-core")
            .put("corePid", Process.myPid())
            .put("appId", id).put("present", false)
    }

    override fun focus(context: Context, id: String?): JSONObject {
        if (id != null) unavailable("focus without active session")
        synchronized(lock) { focusId = null }
        return JSONObject().put("schema", "riftos.core.input-focus/1")
            .put("owner", "riftos-external-core")
            .put("focusedAppId", JSONObject.NULL).put("attachmentGeneration", JSONObject.NULL)
    }

    override fun event(context: Context, id: String, generation: Long,
                       payload: JSONObject): JSONObject {
        require(generation > 0 && APP_ID.matches(id) &&
            payload.getInt("kind") in 0..13) { "Invalid independent Core RAPP input" }
        unavailable("event dispatch")
    }

    override fun install(context: Context, artifact: String): JSONObject =
        unavailable("install without independent grant/rollback authority")

    override fun uninstall(context: Context, id: String): JSONObject =
        unavailable("uninstall without independent grant revocation")

    override fun runningApps(context: Context): JSONArray = JSONArray()

    override fun sessionsView(context: Context): JSONObject =
        JSONObject().put("schema", "riftos.core.sessions/1")
            .put("owner", "riftos-external-core").put("count", 0)
            .put("attached", 0).put("detached", 0)
            .put("headlessExecution", true)
            .put("runtimeExecutionReady", false)

    override fun surfacesView(context: Context): JSONObject =
        JSONObject().put("schema", "riftos.core.app-surfaces/1")
            .put("owner", "riftos-external-core").put("count", 0)
            .put("surfaces", JSONArray())

    override fun focusView(context: Context): JSONObject =
        JSONObject().put("schema", "riftos.core.input-focus/1")
            .put("owner", "riftos-external-core")
            .put("focusedAppId", focusId ?: JSONObject.NULL)

    override fun discoverRuntimeCandidates(context: Context): JSONObject =
        JSONObject().put("schema", "riftos.external.runtime-candidates/1")
            .put("registered", 0).put("candidates", JSONArray())
            .put("runtimeExecutionReady", false)

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
                .put("apps", JSONArray()).put("count", 0))
            .put("runtimeExecutionReady", false)
            .put("candidateComplete", false)
            .put("readyForPromotion", false)
    }
}
