package com.riftos.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Project-owned hot-swappable payload resolver.
 *
 * RiftOS validates paths, bounds, and exact hashes only. It never interprets compiler/runtime
 * semantics. Adding a new VM/compiler/runtime later requires only a project manifest edit.
 */
class RiftBuildManagedToolchains(private val workspaceRoot: File) {
    data class Payload(
        val id: String,
        val kind: String,
        val abi: String,
        val file: File,
        val sha256: String,
        val bytes: Long
    )

    companion object {
        const val MANIFEST = "riftbuild-hot.json"
        const val SCHEMA = "riftbuild-managed-payloads/1"
        private const val MAX_PAYLOADS = 64
        private const val MAX_PAYLOAD_BYTES = 16L * 1024L * 1024L
        private val SAFE_ID = Regex("^[A-Za-z0-9._+-]{1,80}$")
        private val SAFE_KIND = Regex("^[A-Za-z0-9._+-]{1,80}$")
        private val SAFE_ABI = Regex("^[A-Za-z0-9._+-]{1,40}$")
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
    }

    fun status(projectRoot: File): JSONObject {
        val project = checkedProject(projectRoot)
        val manifest = readManifest(project)
        val payloads = manifest.getJSONObject("payloads")
        require(payloads.length() <= MAX_PAYLOADS) { "Managed payload count exceeds limit" }

        val ids = payloads.keys().asSequence().toList().sorted()
        val rows = JSONArray()
        var ready = true
        for (id in ids) {
            val row = runCatching {
                val payload = resolve(project, id)
                JSONObject()
                    .put("id", payload.id)
                    .put("kind", payload.kind)
                    .put("abi", payload.abi)
                    .put("path", payload.file.relativeTo(project).invariantSeparatorsPath)
                    .put("bytes", payload.bytes)
                    .put("sha256", payload.sha256)
                    .put("ready", true)
            }.getOrElse { error ->
                ready = false
                JSONObject()
                    .put("id", id)
                    .put("ready", false)
                    .put("error", error.message ?: error.javaClass.simpleName)
            }
            rows.put(row)
        }

        return JSONObject()
            .put("schema", "riftbuild-managed-payload-status/1")
            .put("manifestSchema", SCHEMA)
            .put("project", project.absolutePath)
            .put("manifest", MANIFEST)
            .put("payloadCount", rows.length())
            .put("payloads", rows)
            .put("ready", ready)
    }

    fun describe(projectRoot: File, id: String): JSONObject {
        val project = checkedProject(projectRoot)
        val payload = resolve(project, id)
        return JSONObject()
            .put("schema", "riftbuild-managed-payload/1")
            .put("id", payload.id)
            .put("kind", payload.kind)
            .put("abi", payload.abi)
            .put("path", payload.file.relativeTo(project).invariantSeparatorsPath)
            .put("bytes", payload.bytes)
            .put("sha256", payload.sha256)
            .put("ready", true)
    }

    fun resolve(projectRoot: File, id: String): Payload {
        require(id.matches(SAFE_ID)) { "Managed payload id is invalid" }
        val project = checkedProject(projectRoot)
        val manifest = readManifest(project)
        val payloads = manifest.getJSONObject("payloads")
        require(payloads.length() <= MAX_PAYLOADS) { "Managed payload count exceeds limit" }
        require(payloads.has(id)) { "Managed payload is not declared: $id" }

        val spec = payloads.getJSONObject(id)
        val kind = spec.optString("kind").trim()
        val abi = spec.optString("abi", "any").trim()
        val path = spec.optString("path").trim()
        val expectedSha = spec.optString("sha256").trim().lowercase()
        val declaredMax = spec.optLong("maxBytes", MAX_PAYLOAD_BYTES)

        require(kind.matches(SAFE_KIND)) { "Managed payload kind is invalid: $id" }
        require(abi.matches(SAFE_ABI)) { "Managed payload ABI is invalid: $id" }
        require(path.isNotBlank() && !path.startsWith("/") && !path.contains("\\")) {
            "Managed payload path is invalid: $id"
        }
        require(expectedSha.matches(SHA256_HEX)) { "Managed payload SHA-256 is invalid: $id" }
        require(declaredMax in 1..MAX_PAYLOAD_BYTES) { "Managed payload maxBytes is invalid: $id" }

        val file = File(project, path).canonicalFile
        require(confinedTo(project, file)) { "Managed payload escaped project root: $id" }
        require(file.isFile) { "Managed payload file is missing: $id" }
        require(file.length() in 1..declaredMax) { "Managed payload byte size is out of bounds: $id" }

        val actualSha = sha256(file)
        require(actualSha == expectedSha) {
            "Managed payload SHA-256 mismatch: $id expected $expectedSha got $actualSha"
        }

        return Payload(id, kind, abi, file, actualSha, file.length())
    }

    private fun checkedProject(projectRoot: File): File {
        val project = projectRoot.canonicalFile
        require(confinedTo(workspaceRoot.canonicalFile, project)) {
            "Managed payload project escaped workspace"
        }
        require(project.isDirectory) { "Managed payload project is not a directory" }
        return project
    }

    private fun readManifest(project: File): JSONObject {
        val file = File(project, MANIFEST).canonicalFile
        require(confinedTo(project, file) && file.isFile) {
            "Managed payload manifest is missing: $MANIFEST"
        }
        require(file.length() in 1..(256L * 1024L)) { "Managed payload manifest is oversized" }
        val json = JSONObject(file.readText(Charsets.UTF_8))
        require(json.optString("schema") == SCHEMA) { "Unsupported managed payload manifest schema" }
        require(json.has("payloads") && json.opt("payloads") is JSONObject) {
            "Managed payload manifest requires payloads object"
        }
        return json
    }

    private fun confinedTo(root: File, child: File): Boolean {
        val rootPath = root.canonicalFile.toPath()
        val childPath = child.canonicalFile.toPath()
        return childPath == rootPath || childPath.startsWith(rootPath)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
