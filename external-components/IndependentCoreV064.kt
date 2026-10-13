package com.riftos.external.core

import android.content.Context
import com.riftos.app.RiftCoreExternalAppConnectionsV1
import com.riftos.app.RiftHostExternalAppBinderV1
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Core v0.6.4: ADDITIVE generic external Android app connection owner.
 * Inherits the device-proven 0.6.3 Core execution, sessions, effects and APK
 * install unchanged. Connections are only reachable from the authenticated
 * graphical :riftShell IPC, never by arbitrary RAPP or MCP requests.
 *
 * Android owns package signer inspection and Binder transport ONLY; this
 * independent Core owns the connection registry, signer pins and RiftFS access.
 * Editor tooling and app-specific semantics stay in the external APK.
 */
class IndependentCoreV064 : IndependentCoreV063(), RiftCoreExternalAppConnectionsV1 {
    companion object {
        private const val REQUEST_SCHEMA = "riftos.core.app-connection-request/1"
        private const val RESPONSE_SCHEMA = "riftos.core.app-connection-result/1"
        private const val PREFS = "rift-external-app-connections-v1"
        private const val MAX_CONNECTIONS = 8
        private const val MAX_REQUEST_BYTES = 24 * 1024
        private const val MAX_TEXT_BYTES = 16 * 1024
        private val ID = Regex("^[a-z][a-z0-9-]{1,39}$")
        private val FILE_ACTIONS = setOf("status", "list", "stat", "write",
            "compile", "preview", "native-proof", "build-apk")
    }

    private val lock = Any()

    override fun coreStatus(context: Context): JSONObject =
        super.coreStatus(context)
            .put("candidateRevision", "0.6.4")
            .put("appConnectionSchema", REQUEST_SCHEMA)
            .put("appConnectionsOwner", "independent-core")

    override fun appConnectionRequest(context: Context, request: JSONObject): JSONObject {
        super.initialize(context)
        require(request.toString().toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_BYTES) {
            "Core app connection request too large"
        }
        require(request.getString("schema") == REQUEST_SCHEMA) {
            "Invalid Core external app connection schema"
        }
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val operation = request.getString("operation")
        val id = request.optString("id", "")
        val data = when (operation) {
            "list" -> synchronized(lock) {
                val result = JSONArray()
                for ((key, value) in prefs.all) {
                    if (!key.startsWith("connection:") || value !is String) continue
                    val item = JSONObject(value)
                    result.put(JSONObject().put("id", key.removePrefix("connection:"))
                        .put("name", item.getString("name"))
                        .put("package", item.getString("package")))
                }
                JSONObject().put("connections", result)
            }
            "attach" -> {
                require(ID.matches(id)) { "Invalid external app connection ID" }
                val name = request.getString("name")
                require(name.length in 1..72) { "Invalid external connection label" }
                val appPackage = request.getString("package")
                val service = request.getString("service")
                val descriptor = request.getString("descriptor")
                require(descriptor.length in 3..160) { "Invalid external app descriptor" }
                val identity = RiftHostExternalAppBinderV1.identity(
                    context, appPackage, service)
                // An actual STATUS Binder round-trip is required before saving
                // this user-approved, package-signer-pinned connection.
                val status = RiftHostExternalAppBinderV1.execute(
                    context, appPackage, service, descriptor,
                    JSONObject().put("op", "status").toString())
                require(status.has("schema")) { "External app did not report its contract" }
                synchronized(lock) {
                    val key = "connection:$id"
                    require(prefs.contains(key) ||
                        prefs.all.keys.count { it.startsWith("connection:") } < MAX_CONNECTIONS) {
                        "External app registry is full"
                    }
                    val record = JSONObject()
                        .put("name", name).put("package", appPackage)
                        .put("service", service).put("descriptor", descriptor)
                        .put("signerSha256", identity.getString("signerSha256"))
                    check(prefs.edit().putString(key, record.toString()).commit()) {
                        "Could not save approved external app connection"
                    }
                }
                JSONObject().put("id", id).put("name", name)
                    .put("package", appPackage)
                    .put("signerSha256", identity.getString("signerSha256"))
                    .put("status", status)
            }
            "detach" -> {
                require(ID.matches(id)) { "Invalid external connection ID" }
                synchronized(lock) {
                    check(prefs.edit().remove("connection:$id").commit()) {
                        "Could not detach external app"
                    }
                }
                JSONObject().put("id", id).put("detached", true)
            }
            "execute" -> {
                val target = requireConnection(context, id)
                val command = request.getJSONObject("command")
                val action = command.getString("op")
                require(action in FILE_ACTIONS) { "Unsupported external app operation" }
                if (action == "write") {
                    require(command.getString("text").toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES) {
                        "Direct text write exceeds 16 KiB; use the transfer API"
                    }
                }
                val response = dispatch(context, target, command)
                JSONObject().put("id", id).put("command", action)
                    .put("remote", response)
            }
            "push" -> {
                val target = requireConnection(context, id)
                val src = request.getString("source")
                val targetPath = request.optString("destination").ifBlank {
                    src.replace('\\', '/').substringAfterLast('/')
                }
                require(targetPath.length in 1..240 &&
                    !targetPath.startsWith("/") &&
                    !targetPath.contains("..") && !targetPath.contains('\\') &&
                    !targetPath.contains(':')) {
                    "External destination must be a safe workspace-relative path"
                }
                val file = sourceFile(context, src)
                require(file.isFile && file.length() in 1L..MAX_TEXT_BYTES.toLong()) {
                    "Bridge proof supports text files up to 16 KiB"
                }
                val raw = file.readBytes()
                val content = String(raw, Charsets.UTF_8)
                require(content.toByteArray(Charsets.UTF_8).contentEquals(raw) &&
                    !content.contains('\u0000')) {
                    "External text transfer requires UTF-8 source"
                }
                val command = JSONObject().put("op", "write")
                    .put("path", targetPath).put("text", content)
                val response = dispatch(context, target, command)
                val sha = sha256(raw)
                require(response.getLong("bytes") == raw.size.toLong() &&
                    response.getString("sha256").equals(sha, ignoreCase = true)) {
                    "External app transfer receipt did not match source bytes and SHA-256"
                }
                JSONObject().put("id", id).put("source", src)
                    .put("destination", targetPath).put("bytes", raw.size)
                    .put("sha256", sha).put("verified", true)
            }
            else -> error("Unknown Core external app operation: $operation")
        }
        return JSONObject().put("schema", RESPONSE_SCHEMA)
            .put("operation", operation).put("ok", true).put("data", data)
    }

    private fun requireConnection(context: Context, id: String): JSONObject {
        require(ID.matches(id)) { "Invalid external connection ID" }
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val entry = synchronized(lock) {
            prefs.getString("connection:$id", null)
                ?: error("External app is not attached; attach it in RiftOS first")
        }
        val record = JSONObject(entry)
        val identity = RiftHostExternalAppBinderV1.identity(context,
            record.getString("package"), record.getString("service"))
        require(identity.getString("signerSha256") ==
            record.getString("signerSha256")) {
            "External app signer changed; explicitly detach and reattach"
        }
        return record
    }

    private fun dispatch(context: Context, target: JSONObject, command: JSONObject): JSONObject =
        RiftHostExternalAppBinderV1.execute(context,
            target.getString("package"), target.getString("service"),
            target.getString("descriptor"), command.toString())

    private fun sourceFile(context: Context, path: String): File {
        val display = RiftVolumePaths.normalizeDisplay(path)
        require(listOf("/D:/Workspace/", "/D:/Projects/", "/D:/Documents/")
            .any { display.startsWith(it) }) {
            "External app transfer is limited to user project/document files"
        }
        val root = File(context.applicationContext.filesDir, "riftfs").canonicalFile
        val target = File(root, RiftVolumePaths.resolveRelative(display)).canonicalFile
        require(target.path.startsWith(root.path + File.separator)) {
            "External app transfer escapes RiftFS"
        }
        var node: File? = File(root, RiftVolumePaths.resolveRelative(display))
        while (node != null && node != root) {
            require(!Files.isSymbolicLink(node.toPath())) { "Symbolic-link transfer denied" }
            node = node.parentFile
        }
        return target
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
