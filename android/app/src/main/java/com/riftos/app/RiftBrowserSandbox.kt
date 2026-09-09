package com.riftos.app

import android.content.Context
import android.os.StatFs
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Private filesystem exposed only to the ChatGPT origin inside RiftBrowser.
 *
 * Every path is resolved beneath filesDir/riftfs/browser-sandbox. The bridge
 * deliberately has no Android ContentResolver, SAF mount, shell, or app-wide
 * filesystem access, so a compromised webpage cannot escape this directory.
 */
class RiftBrowserSandbox(context: Context) {
    companion object {
        private const val MAX_BRIDGE_BYTES = 8 * 1024 * 1024
        private const val MAX_LIST_ENTRIES = 5000
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val root = File(context.filesDir, "riftfs/browser-sandbox").apply {
        mkdirs()
        listOf("workspace", "uploads", "downloads").forEach { File(this, it).mkdirs() }
    }

    fun handleAsync(raw: String, reply: (String) -> Unit) {
        executor.execute {
            val id = runCatching { JSONObject(raw).optString("id") }.getOrDefault("")
            val response = try {
                val request = JSONObject(raw)
                val requestId = request.optString("id")
                val method = request.optString("method")
                require(requestId.isNotBlank()) { "Missing sandbox request id" }
                require(method.isNotBlank()) { "Missing sandbox method" }
                val args = request.optJSONObject("args") ?: JSONObject()
                JSONObject()
                    .put("id", requestId)
                    .put("ok", true)
                    .put("value", dispatch(method, args) ?: JSONObject.NULL)
            } catch (error: Throwable) {
                JSONObject()
                    .put("id", id)
                    .put("ok", false)
                    .put("error", error.message ?: error.javaClass.simpleName)
            }
            reply(response.toString())
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    private fun dispatch(method: String, args: JSONObject): Any? = when (method) {
        "sandbox.info" -> info()
        "fs.stat" -> stat(args.optString("path"))
        "fs.list" -> list(args.optString("path"), args.optBoolean("recursive", false))
        "fs.readText" -> readText(args.getString("path"))
        "fs.writeText" -> writeText(args.getString("path"), args.optString("text"))
        "fs.readBase64" -> readBase64(args.getString("path"))
        "fs.writeBase64" -> writeBase64(args.getString("path"), args.getString("data"))
        "fs.mkdir" -> mkdir(args.getString("path"))
        "fs.remove" -> remove(args.getString("path"))
        "fs.move" -> move(args.getString("from"), args.getString("to"), args.optBoolean("overwrite", false))
        else -> throw IllegalArgumentException("Unsupported Rift sandbox method: $method")
    }

    private fun normalizeSegments(path: String): List<String> {
        val normalized = path.replace('\\', '/').trim('/')
        if (normalized.isBlank()) return emptyList()
        return normalized.split('/').filter { it.isNotBlank() }.map { segment ->
            require(segment != "." && segment != ".." && !segment.contains('\u0000')) { "Invalid path segment" }
            segment
        }
    }

    private fun sandboxFile(path: String): File {
        var file = root
        for (segment in normalizeSegments(path)) file = File(file, segment)
        val rootCanonical = root.canonicalFile
        val target = file.canonicalFile
        require(target == rootCanonical || target.path.startsWith(rootCanonical.path + File.separator)) {
            "Path escaped RiftBrowser sandbox"
        }
        return target
    }

    private fun relativePath(file: File): String =
        root.canonicalFile.toPath().relativize(file.canonicalFile.toPath()).toString().replace(File.separatorChar, '/')

    private fun stat(path: String): JSONObject? {
        val file = sandboxFile(path)
        if (!file.exists()) return null
        return JSONObject()
            .put("path", relativePath(file))
            .put("name", file.name)
            .put("kind", if (file.isDirectory) "directory" else "file")
            .put("size", if (file.isFile) file.length() else 0L)
            .put("modified", file.lastModified())
    }

    private fun list(path: String, recursive: Boolean): JSONArray {
        val base = sandboxFile(path)
        require(base.exists() && base.isDirectory) { "Directory not found: $path" }
        val out = JSONArray()
        var count = 0

        fun walk(directory: File) {
            directory.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { child ->
                require(count < MAX_LIST_ENTRIES) { "Sandbox listing exceeds $MAX_LIST_ENTRIES entries" }
                count += 1
                out.put(
                    JSONObject()
                        .put("path", relativePath(child))
                        .put("name", child.name)
                        .put("kind", if (child.isDirectory) "directory" else "file")
                        .put("size", if (child.isFile) child.length() else 0L)
                        .put("modified", child.lastModified())
                )
                if (recursive && child.isDirectory) walk(child)
            }
        }

        walk(base)
        return out
    }

    private fun readText(path: String): String {
        val file = sandboxFile(path)
        require(file.isFile) { "File not found: $path" }
        require(file.length() <= MAX_BRIDGE_BYTES) { "File is too large for the browser bridge" }
        return file.readText(Charsets.UTF_8)
    }

    private fun writeText(path: String, text: String): JSONObject {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BRIDGE_BYTES) { "Text payload is too large for the browser bridge" }
        val file = sandboxFile(path)
        require(file != root) { "Sandbox root is not a file" }
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return stat(path)!!
    }

    private fun readBase64(path: String): String {
        val file = sandboxFile(path)
        require(file.isFile) { "File not found: $path" }
        require(file.length() <= MAX_BRIDGE_BYTES) { "File is too large for the browser bridge" }
        return Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    }

    private fun writeBase64(path: String, data: String): JSONObject {
        require(data.length <= MAX_BRIDGE_BYTES * 2) { "Encoded payload is too large for the browser bridge" }
        val bytes = Base64.decode(data, Base64.DEFAULT)
        require(bytes.size <= MAX_BRIDGE_BYTES) { "Decoded payload is too large for the browser bridge" }
        val file = sandboxFile(path)
        require(file != root) { "Sandbox root is not a file" }
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return stat(path)!!
    }

    private fun mkdir(path: String): JSONObject {
        val directory = sandboxFile(path)
        require((directory.exists() && directory.isDirectory) || directory.mkdirs()) { "Could not create directory: $path" }
        return stat(path)!!
    }

    private fun remove(path: String): Boolean {
        require(normalizeSegments(path).isNotEmpty()) { "Cannot delete the sandbox root" }
        val file = sandboxFile(path)
        if (!file.exists()) return true
        return if (file.isDirectory) file.deleteRecursively() else file.delete()
    }

    private fun move(from: String, to: String, overwrite: Boolean): JSONObject {
        require(normalizeSegments(from).isNotEmpty()) { "Cannot move the sandbox root" }
        require(normalizeSegments(to).isNotEmpty()) { "Destination cannot be the sandbox root" }
        val source = sandboxFile(from)
        val destination = sandboxFile(to)
        require(source.exists()) { "Source not found: $from" }
        if (destination.exists()) {
            require(overwrite) { "Destination already exists: $to" }
            val deleted = if (destination.isDirectory) destination.deleteRecursively() else destination.delete()
            require(deleted) { "Could not replace destination: $to" }
        }
        destination.parentFile?.mkdirs()
        require(source.renameTo(destination)) { "Could not move $from to $to" }
        return stat(to)!!
    }

    private fun info(): JSONObject {
        val stats = StatFs(root.absolutePath)
        return JSONObject()
            .put("root", "riftfs/browser-sandbox")
            .put("writable", true)
            .put("maxBridgeBytes", MAX_BRIDGE_BYTES)
            .put("freeBytes", stats.availableBytes)
            .put("totalBytes", stats.totalBytes)
            .put("capabilities", JSONArray(listOf(
                "stat", "list", "readText", "writeText", "readBase64", "writeBase64", "mkdir", "remove", "move"
            )))
    }
}
