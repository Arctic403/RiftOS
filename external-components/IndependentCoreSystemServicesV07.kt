package com.riftos.external.core

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/**
 * RiftOS private-device system services, owned by the independent Core DEX.
 *
 * Android's authenticated :riftShell Binder endpoint is the ONLY transport
 * to this interface; a RAPP has no method to call it as its own capability.
 * Routine user filesystem, settings, task and grant operations need no
 * enterprise admin ticket chain. Component activation and independent manual
 * Recovery intentionally remain outside this interface and APK-owned.
 */
internal class IndependentCoreSystemServicesV07(
    private val app: Context,
    private val core: IndependentCoreV1
) {
    companion object {
        private const val SCHEMA = "riftos.core.system-request/1"
        private const val RESULT = "riftos.core.system-result/1"
        private const val MAX_FILE_TEXT = 64 * 1024
        private const val MAX_ROWS = 128
        private val APP_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{1,63}$")
        private val USER_SETTINGS = mapOf(
            "appearance.theme" to setOf("dark", "light", "system"),
            "filesystem.showHidden" to setOf("true", "false"),
            "taskbar.showClock" to setOf("true", "false")
        )
    }

    private val root = File(app.filesDir, "riftfs").canonicalFile
    private val settings = app.getSharedPreferences("rift-core-user-settings-v1",
        Context.MODE_PRIVATE)
    private val grants = app.getSharedPreferences("rift-native", Context.MODE_PRIVATE)

    fun request(request: JSONObject): JSONObject {
        require(request.toString().toByteArray(Charsets.UTF_8).size <= 70 * 1024) {
            "System request exceeds bounded Core IPC"
        }
        require(request.getString("schema") == SCHEMA) {
            "Invalid independent Core system request schema"
        }
        val op = request.getString("operation")
        val data = when (op) {
            "status" -> JSONObject()
                .put("core", core.coreStatus(app))
                .put("systemServices", "external-core-v0.7")
            "volumes" -> JSONObject().put("volumes", RiftVolumePaths.describe())
            "tasks.list" -> JSONObject()
                .put("apps", core.runningApps(app))
                .put("sessions", core.sessionsView(app))
                .put("surfaces", core.surfacesView(app))
            "tasks.close" -> closeApp(request)
            "fs.list" -> list(request.getString("path"))
            "fs.stat" -> stat(request.getString("path"))
            "fs.read" -> read(request.getString("path"))
            "fs.write" -> write(request.getString("path"), request.getString("text"))
            "fs.mkdir" -> makeDirectory(request.getString("path"))
            "fs.delete" -> deleteFile(request.getString("path"))
            "fs.copy" -> transfer(request.getString("path"),
                request.getString("destination"), move = false)
            "fs.move" -> transfer(request.getString("path"),
                request.getString("destination"), move = true)
            "settings.get" -> getSettings()
            "settings.set" -> setSetting(request.getString("key"),
                request.getString("value"))
            "permissions.list" -> listGrants()
            "permissions.revoke" -> revokeGrant(request.getString("appId"),
                request.optString("capability", "all"))
            else -> error("Unknown independent Core system operation: " + op.take(60))
        }
        return JSONObject().put("schema", RESULT).put("operation", op)
            .put("ok", true).put("data", data)
    }

    private fun display(raw: String): String {
        require(raw.length in 3..240 && !raw.contains('\u0000')) {
            "RiftFS system path invalid"
        }
        val path = RiftVolumePaths.normalizeDisplay(raw)
        require(RiftVolumePaths.volume(path) != null) {
            "Only RiftOS C:/ and D:/ volumes are available"
        }
        return path
    }

    private fun file(path: String): File {
        val target = File(root, RiftVolumePaths.resolveRelative(path)).canonicalFile
        require(target.path.startsWith(root.path + File.separator)) {
            "System path escapes RiftFS"
        }
        var node: File? = File(root, RiftVolumePaths.resolveRelative(path))
        while (node != null && node != root) {
            require(!Files.isSymbolicLink(node.toPath())) {
                "System path contains symlink"
            }
            node = node.parentFile
        }
        return target
    }

    private fun writable(path: String): File {
        // System C: and protected component payloads remain managed by their
        // own install/Recovery machinery, not ordinary desktop file editing.
        require(path.startsWith("/D:/") &&
            !path.equals("/D:/", ignoreCase = true)) {
            "System volume is read-only; use the installed component manager"
        }
        val lower = path.lowercase(java.util.Locale.ROOT)
        require(lower != "/d:/builds/components" &&
            !lower.startsWith("/d:/builds/components/")) {
            "Protected component build output is not a desktop file target"
        }
        val value = file(path)
        // D:/Builds and D:/Documents are aliases into the same backing tree.
        // Protect the actual component directory, not just one display path.
        val components = File(root, "documents/builds/Components").canonicalFile
        require(value != components &&
            !value.path.startsWith(components.path + File.separator)) {
            "Protected component build output is not a desktop file target"
        }
        require(value.parentFile?.isDirectory == true) {
            "Destination parent directory does not exist"
        }
        return value
    }

    private fun attributes(path: String, target: File): JSONObject = JSONObject()
        .put("path", path)
        .put("directory", target.isDirectory)
        .put("size", if (target.isFile) target.length() else 0L)
        .put("modifiedAtMs", target.lastModified())

    private fun list(raw: String): JSONObject {
        val path = display(raw)
        val volume = RiftVolumePaths.volume(path)!!
        val out = JSONArray()
        val names = LinkedHashSet<String>()
        if (RiftVolumePaths.isVolumeRoot(path)) names.addAll(volume.roots.keys)
        val target = file(path)
        if (target.exists()) {
            require(target.isDirectory) { "Not a directory: $path" }
            target.listFiles()?.forEach { names.add(it.name) }
        } else {
            require(RiftVolumePaths.isVolumeRoot(path)) { "Directory not found: $path" }
        }
        for (name in names.sorted().take(MAX_ROWS)) {
            if (name.length !in 1..128 || name.contains('/')) continue
            val child = display(path.trimEnd('/') + "/" + name)
            val node = file(child)
            if (node.exists()) out.put(attributes(child, node))
        }
        return JSONObject().put("path", path).put("entries", out)
            .put("truncated", names.size > MAX_ROWS)
    }

    private fun stat(raw: String): JSONObject {
        val path = display(raw)
        val target = file(path)
        require(target.exists() || RiftVolumePaths.isVolumeRoot(path)) {
            "File or directory not found: $path"
        }
        return attributes(path, target)
    }

    private fun read(raw: String): JSONObject {
        val path = display(raw)
        val target = file(path)
        require(target.isFile && target.length() in 0L..MAX_FILE_TEXT.toLong()) {
            "Text file missing or exceeds the 64 KiB system preview limit"
        }
        return JSONObject().put("path", path)
            .put("text", target.readText(Charsets.UTF_8))
            .put("bytes", target.length())
    }

    private fun write(raw: String, text: String): JSONObject {
        val path = display(raw)
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_FILE_TEXT) {
            "System text write exceeds 64 KiB"
        }
        val target = writable(path)
        require(!target.isDirectory) { "Cannot replace directory with text" }
        val bytes = text.toByteArray(Charsets.UTF_8)
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (failure: Throwable) {
            atomic.failWrite(stream)
            throw failure
        }
        return JSONObject().put("path", path).put("bytes", bytes.size)
    }

    private fun makeDirectory(raw: String): JSONObject {
        val path = display(raw)
        val target = writable(path)
        require(!target.exists()) { "Directory already exists" }
        require(target.mkdir()) { "Could not create directory" }
        return JSONObject().put("path", path).put("created", true)
    }

    private fun deleteFile(raw: String): JSONObject {
        val path = display(raw)
        val target = writable(path)
        require(target.isFile) { "Only files can be deleted; directory deletion is unavailable" }
        require(target.delete()) { "Could not delete file" }
        return JSONObject().put("path", path).put("deleted", true)
    }

    private fun transfer(raw: String, rawDestination: String, move: Boolean): JSONObject {
        val sourcePath = display(raw)
        val destPath = display(rawDestination)
        val source = if (move) writable(sourcePath) else file(sourcePath)
        val target = writable(destPath)
        require(source.isFile && source.length() <= MAX_FILE_TEXT &&
            !target.exists() && source != target) {
            "Only files up to 64 KiB can be copied/moved without overwrite"
        }
        require(source.copyTo(target, overwrite = false).isFile) {
            "Core file copy failed"
        }
        if (move) require(source.delete()) {
            "Copied file; could not remove source (destination preserved)"
        }
        return JSONObject().put("path", sourcePath).put("destination", destPath)
            .put("moved", move)
    }

    private fun closeApp(request: JSONObject): JSONObject {
        val id = request.getString("appId")
        require(APP_ID.matches(id)) { "Only a valid installed RAPP can be closed" }
        val result = core.stopApp(app, id)
        return JSONObject().put("appId", id).put("result", result)
    }

    private fun getSettings(): JSONObject = JSONObject().apply {
        for ((key, choices) in USER_SETTINGS) {
            val value = settings.getString(key, null)
            put(key, value?.takeIf { it in choices } ?: choices.first())
        }
    }

    private fun setSetting(key: String, value: String): JSONObject {
        require(USER_SETTINGS[key]?.contains(value) == true) {
            "Unknown or invalid Core user setting"
        }
        require(settings.edit().putString(key, value).commit()) {
            "Core setting was not persisted"
        }
        return JSONObject().put("key", key).put("value", value)
    }

    private fun listGrants(): JSONObject {
        val known = HashSet<String>()
        val installed = core.installed(app)
        for (i in 0 until installed.length()) {
            val id = installed.optJSONObject(i)?.optString("id").orEmpty()
            if (APP_ID.matches(id)) known.add(id)
        }
        val out = JSONArray()
        for (id in known.sorted().take(MAX_ROWS)) {
            val stored = grants.getString("setting:permissions:$id", null) ?: continue
            require(stored.length <= 16 * 1024) { "Core grant record oversized" }
            val values = JSONObject(stored).getJSONArray("value")
            require(values.length() <= 32) { "Core grant list exceeds bounds" }
            out.put(JSONObject().put("appId", id).put("grants", values))
        }
        return JSONObject().put("apps", out)
    }

    private fun revokeGrant(id: String, capability: String): JSONObject {
        require(APP_ID.matches(id)) { "Invalid Core RAPP grant identity" }
        require(capability == "all" || capability.matches(
            Regex("^[A-Za-z0-9._-]{1,64}$"))) {
            "Invalid RAPP capability name"
        }
        val key = "setting:permissions:$id"
        var removed = false
        // Share the independent package manager's install/uninstall lock.
        // A concurrent reinstall must not inherit stale permission changes.
        require(core.withLiveGrantChange(app, id) {
            val raw = grants.getString(key, null)
            if (raw != null) {
                require(raw.length <= 16 * 1024) { "Core grant record oversized" }
                val values = JSONObject(raw).getJSONArray("value")
                require(values.length() <= 32) { "Core grant list exceeds bounds" }
                val keep = JSONArray()
                for (i in 0 until values.length()) {
                    val item = values.getString(i)
                    if (capability == "all" || item == capability) removed = true
                    else keep.put(item)
                }
                if (removed) {
                    val next = if (capability == "all") null else
                        JSONObject(raw).put("value", keep).toString()
                    val editor = grants.edit()
                    if (next == null) editor.remove(key) else editor.putString(key, next)
                    check(editor.commit()) { "Core grant revocation was not persisted" }
                }
            }
        }) { "RAPP is no longer installed" }
        return JSONObject().put("appId", id).put("capability", capability)
            .put("revoked", removed)
    }
}
