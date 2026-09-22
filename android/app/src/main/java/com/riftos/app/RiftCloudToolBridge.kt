package com.riftos.app

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/** ToolHost-facing adapter for RiftCloud. Cloudflare credentials remain inside RiftCloud. */
class RiftCloudToolBridge(context: Context) {
    companion object {
        private const val MAX_SOURCE_BYTES = 16 * 1024 * 1024
        private const val CHUNK_BYTES = 96 * 1024

        val MUTATING_OPS = setOf(
            "source_pull",
            "replace_from_workspace",
            "upload_from_workspace",
            "upload_commit",
            "worker_delete",
            "secret_put",
            "secret_delete",
            "settings_patch",
            "subdomain_update",
            "subdomain_delete",
            "deployment_create",
        )
    }

    private val appContext = context.applicationContext
    private val bridge = RiftCloudBridgeClient(appContext)
    private val workspaceRoot = File(appContext.filesDir, "riftfs/workspace").apply { mkdirs() }.canonicalFile

    fun execute(rawArgs: JSONObject): JSONObject {
        val args = JSONObject(rawArgs.toString())
        val op = args.optString("op").trim().lowercase()
        require(op.isNotBlank()) { "rift_cloud.op is required" }

        return when (op) {
            "source_pull" -> sourcePull(args)
            "replace_from_workspace" -> replaceFromWorkspace(args)
            "upload_from_workspace" -> uploadFromWorkspace(args)
            else -> bridge.execute(args)
        }
    }

    fun mutates(args: JSONObject): Boolean =
        args.optString("op").trim().lowercase() in MUTATING_OPS

    fun close() {
        bridge.close()
    }

    private fun sourcePull(args: JSONObject): JSONObject {
        val workerId = requiredString(args, "workerId", 255)
        val path = requiredString(args, "path", 4096)
        val overwrite = args.optBoolean("overwrite", false)
        val destination = resolveWorkspace(path)

        if (destination.exists() && !overwrite) {
            throw IllegalArgumentException("destination already exists: $path")
        }
        destination.parentFile?.mkdirs()

        val opened = bridge.execute(
            JSONObject().put("op", "source_open").put("workerId", workerId)
        )
        requireOk(opened, "RiftCloud source open failed")
        val handle = opened.getString("handle")
        val total = opened.getInt("bytes")
        require(total in 0..MAX_SOURCE_BYTES) { "RiftCloud source exceeds 16 MiB" }
        val expectedSha = opened.optString("sha256").lowercase()
        require(expectedSha.matches(Regex("^[0-9a-f]{64}$"))) {
            "RiftCloud source SHA-256 is invalid"
        }

        val temporary = File(
            destination.parentFile ?: workspaceRoot,
            ".${destination.name}.riftcloud-${UUID.randomUUID()}.tmp"
        )
        val digest = MessageDigest.getInstance("SHA-256")
        var offset = 0

        try {
            FileOutputStream(temporary, false).use { output ->
                while (offset < total) {
                    val chunk = bridge.execute(
                        JSONObject()
                            .put("op", "source_read")
                            .put("handle", handle)
                            .put("offset", offset)
                            .put("maxBytes", CHUNK_BYTES)
                    )
                    requireOk(chunk, "RiftCloud source read failed")
                    require(chunk.optInt("offset", -1) == offset) {
                        "RiftCloud source chunk offset mismatch"
                    }
                    val decoded = Base64.decode(chunk.optString("data"), Base64.DEFAULT)
                    require(chunk.optInt("bytes", -1) == decoded.size) {
                        "RiftCloud source chunk byte count mismatch"
                    }
                    val next = chunk.optInt("nextOffset", -1)
                    require(next == offset + decoded.size && next <= total) {
                        "RiftCloud source chunk nextOffset mismatch"
                    }
                    require(decoded.isNotEmpty() || next == total) {
                        "RiftCloud source transfer made no progress"
                    }
                    output.write(decoded)
                    digest.update(decoded)
                    offset = next
                }
            }

            require(temporary.length() == total.toLong()) {
                "RiftCloud source transfer length mismatch"
            }
            val actualSha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            require(actualSha == expectedSha) { "RiftCloud source SHA-256 mismatch" }

            if (destination.exists()) {
                require(overwrite) { "destination already exists: $path" }
                require(destination.delete()) { "Could not replace existing destination: $path" }
            }
            require(temporary.renameTo(destination)) {
                "Could not commit RiftCloud source to $path"
            }

            return JSONObject()
                .put("ok", true)
                .put("workerId", workerId)
                .put("path", workspaceDisplay(destination))
                .put("bytes", total)
                .put("sha256", actualSha)
        } finally {
            runCatching {
                bridge.execute(JSONObject().put("op", "source_close").put("handle", handle))
            }
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun replaceFromWorkspace(args: JSONObject): JSONObject {
        val workerId = requiredString(args, "workerId", 255)
        val sourcePath = requiredString(args, "path", 4096)
        val source = resolveWorkspace(sourcePath)
        require(source.isFile) { "source file not found: $sourcePath" }
        require(source.length() in 0L..MAX_SOURCE_BYTES.toLong()) { "Worker source exceeds 16 MiB" }

        val opened = bridge.execute(
            JSONObject().put("op", "source_open").put("workerId", workerId)
        )
        requireOk(opened, "RiftCloud source open failed")
        val handle = opened.getString("handle")
        var stage: String? = null

        try {
            val begun = bridge.execute(
                JSONObject().put("op", "replace_begin").put("handle", handle)
            )
            requireOk(begun, "RiftCloud replacement stage failed")
            stage = begun.getString("stage")
            val bytes = streamUpload(stage, source)
            val committed = bridge.execute(
                JSONObject().put("op", "upload_commit").put("stage", stage)
            )
            requireOk(committed, "RiftCloud replacement failed")
            stage = null
            return JSONObject(committed.toString())
                .put("sourcePath", workspaceDisplay(source))
                .put("sourceBytes", bytes)
        } finally {
            stage?.let {
                runCatching {
                    bridge.execute(JSONObject().put("op", "upload_abort").put("stage", it))
                }
            }
            runCatching {
                bridge.execute(JSONObject().put("op", "source_close").put("handle", handle))
            }
        }
    }

    private fun uploadFromWorkspace(args: JSONObject): JSONObject {
        val workerId = requiredString(args, "workerId", 255)
        val sourcePath = requiredString(args, "path", 4096)
        val source = resolveWorkspace(sourcePath)
        require(source.isFile) { "source file not found: $sourcePath" }
        require(source.length() in 0L..MAX_SOURCE_BYTES.toLong()) { "Worker source exceeds 16 MiB" }

        val metadata = args.optJSONObject("metadata")
            ?: throw IllegalArgumentException("upload_from_workspace.metadata is required")
        require(metadata.toString().toByteArray(Charsets.UTF_8).size <= 256 * 1024) {
            "Worker upload metadata exceeds 256 KiB"
        }

        val begun = bridge.execute(
            JSONObject()
                .put("op", "upload_begin")
                .put("workerId", workerId)
                .put("metadata", JSONObject(metadata.toString()))
                .put("partName", args.optString("partName", "index.js"))
                .put("contentType", args.optString("contentType", "application/javascript+module"))
        )
        requireOk(begun, "RiftCloud upload stage failed")
        var stage: String? = begun.getString("stage")

        try {
            val bytes = streamUpload(stage!!, source)
            val committed = bridge.execute(
                JSONObject().put("op", "upload_commit").put("stage", stage)
            )
            requireOk(committed, "RiftCloud Worker upload failed")
            stage = null
            return JSONObject(committed.toString())
                .put("sourcePath", workspaceDisplay(source))
                .put("sourceBytes", bytes)
        } finally {
            stage?.let {
                runCatching {
                    bridge.execute(JSONObject().put("op", "upload_abort").put("stage", it))
                }
            }
        }
    }

    private fun streamUpload(stage: String, source: File): Int {
        var offset = 0
        val buffer = ByteArray(CHUNK_BYTES)

        source.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue

                val payload = if (read == buffer.size) buffer else buffer.copyOf(read)
                val response = bridge.execute(
                    JSONObject()
                        .put("op", "upload_write")
                        .put("stage", stage)
                        .put("offset", offset)
                        .put("data", Base64.encodeToString(payload, Base64.NO_WRAP))
                )
                requireOk(response, "RiftCloud upload chunk failed")
                val next = response.optInt("nextOffset", -1)
                require(next == offset + read) { "RiftCloud upload chunk nextOffset mismatch" }
                offset = next
            }
        }

        require(offset.toLong() == source.length()) { "RiftCloud upload byte count mismatch" }
        return offset
    }

    private fun resolveWorkspace(rawPath: String): File {
        var path = rawPath.trim().replace('\\', '/')
        if (path.startsWith("/")) path = path.trimStart('/')
        if (path.startsWith("workspace/", ignoreCase = true)) {
            path = path.substringAfter('/')
        }
        require(path.isNotBlank()) { "workspace path must not be blank" }
        val file = File(workspaceRoot, path).canonicalFile
        require(file == workspaceRoot || file.path.startsWith(workspaceRoot.path + File.separator)) {
            "path escaped RiftOS workspace"
        }
        return file
    }

    private fun workspaceDisplay(file: File): String {
        val relative = file.canonicalFile.relativeTo(workspaceRoot).invariantSeparatorsPath
        return if (relative.isBlank()) "workspace" else "workspace/$relative"
    }

    private fun requiredString(args: JSONObject, key: String, maxChars: Int): String {
        val value = args.optString(key).trim()
        require(value.isNotBlank()) { "$key is required" }
        require(value.length <= maxChars) { "$key is too long" }
        return value
    }

    private fun requireOk(response: JSONObject, fallback: String) {
        require(response.optBoolean("ok", false)) {
            response.optString("error").ifBlank { fallback }
        }
    }
}
