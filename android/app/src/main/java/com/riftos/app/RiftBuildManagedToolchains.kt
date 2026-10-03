package com.riftos.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Project-owned hot-swappable payload and compiler resolver.
 *
 * RiftOS validates paths, bounds, identities and a small execution-engine contract only. Compiler
 * semantics remain owned by the project payload/adapter.
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

    data class Compiler(
        val id: String,
        val engine: String,
        val payloadId: String?,
        val bundledAsset: String?,
        val abi: String,
        val entryClass: String?,
        val entryMethod: String,
        val protocol: String
    )

    companion object {
        const val MANIFEST = "riftbuild-hot.json"
        const val SCHEMA = "riftbuild-managed-payloads/1"
        const val COMPILER_PROTOCOL = "riftbuild-compiler-json/1"
        const val ENGINE_NATIVE_BUFFER = "native-buffer-v1"
        const val ENGINE_DEX_JSON = "dex-json-v1"

        private const val MAX_PAYLOADS = 64
        private const val MAX_COMPILERS = 32
        private const val MAX_PAYLOAD_BYTES = 128L * 1024L * 1024L
        private val SAFE_ID = Regex("^[A-Za-z0-9._+-]{1,80}$")
        private val SAFE_KIND = Regex("^[A-Za-z0-9._+-]{1,80}$")
        private val SAFE_ABI = Regex("^[A-Za-z0-9._+-]{1,40}$")
        private val SAFE_CLASS = Regex("^[A-Za-z_$][A-Za-z0-9_$.]{0,199}$")
        private val SAFE_METHOD = Regex("^[A-Za-z_$][A-Za-z0-9_$]{0,79}$")
        private val SAFE_ASSET = Regex("^riftbuild/compiler-seeds/[A-Za-z0-9._+-]{1,120}\\.apk$")
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

        val compilers = compilerStatus(project, manifest)
        if (!compilers.optBoolean("ready", true)) ready = false

        return JSONObject()
            .put("schema", "riftbuild-managed-payload-status/1")
            .put("manifestSchema", SCHEMA)
            .put("project", project.absolutePath)
            .put("manifest", MANIFEST)
            .put("payloadCount", rows.length())
            .put("payloads", rows)
            .put("compilerCount", compilers.getInt("compilerCount"))
            .put("compilers", compilers.getJSONArray("compilers"))
            .put("ready", ready)
    }

    fun compilerStatus(projectRoot: File): JSONObject {
        val project = checkedProject(projectRoot)
        return compilerStatus(project, readManifest(project))
            .put("project", project.absolutePath)
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

    fun resolveCompiler(projectRoot: File, id: String): Compiler {
        require(id.matches(SAFE_ID)) { "Managed compiler id is invalid" }
        val project = checkedProject(projectRoot)
        val manifest = readManifest(project)
        val compilers = manifest.optJSONObject("compilers")
            ?: error("Managed compiler registry is missing")
        require(compilers.length() <= MAX_COMPILERS) { "Managed compiler count exceeds limit" }
        require(compilers.has(id)) { "Managed compiler is not declared: $id" }

        val spec = compilers.getJSONObject(id)
        val engine = spec.optString("engine").trim()
        val payloadId = spec.optString("payload").trim().ifBlank { null }
        val bundledAsset = spec.optString("bundledAsset").trim().ifBlank { null }
        val abi = spec.optString("abi", "any").trim()
        val entryClass = spec.optString("entryClass").trim().ifBlank { null }
        val entryMethod = spec.optString("entryMethod", "run").trim()
        val protocol = spec.optString("protocol", COMPILER_PROTOCOL).trim()

        require(engine in setOf(ENGINE_NATIVE_BUFFER, ENGINE_DEX_JSON)) {
            "Managed compiler engine is unsupported: $engine"
        }
        require((payloadId == null) != (bundledAsset == null)) {
            "Managed compiler requires exactly one of payload or bundledAsset: $id"
        }
        payloadId?.let {
            require(it.matches(SAFE_ID)) { "Managed compiler payload id is invalid: $id" }
        }
        bundledAsset?.let {
            require(it.matches(SAFE_ASSET)) { "Managed compiler bundled asset is invalid: $id" }
        }
        require(abi.matches(SAFE_ABI)) { "Managed compiler ABI is invalid: $id" }
        require(protocol == COMPILER_PROTOCOL) { "Managed compiler protocol is unsupported: $id" }
        require(entryMethod.matches(SAFE_METHOD)) { "Managed compiler entry method is invalid: $id" }

        if (engine == ENGINE_DEX_JSON) {
            require(entryClass != null && entryClass.matches(SAFE_CLASS)) {
                "DEX managed compiler requires a valid entryClass: $id"
            }
        } else {
            require(entryClass == null) {
                "Native managed compiler must not declare entryClass: $id"
            }
            require(payloadId != null) {
                "Native managed compiler requires a project payload: $id"
            }
        }

        return Compiler(
            id = id,
            engine = engine,
            payloadId = payloadId,
            bundledAsset = bundledAsset,
            abi = abi,
            entryClass = entryClass,
            entryMethod = entryMethod,
            protocol = protocol
        )
    }

    private fun compilerStatus(project: File, manifest: JSONObject): JSONObject {
        val compilers = manifest.optJSONObject("compilers") ?: JSONObject()
        require(compilers.length() <= MAX_COMPILERS) { "Managed compiler count exceeds limit" }
        val rows = JSONArray()
        var ready = true
        for (id in compilers.keys().asSequence().toList().sorted()) {
            val row = runCatching {
                val compiler = resolveCompiler(project, id)
                if (compiler.payloadId != null) resolve(project, compiler.payloadId)
                JSONObject()
                    .put("id", compiler.id)
                    .put("engine", compiler.engine)
                    .put("payload", compiler.payloadId ?: JSONObject.NULL)
                    .put("bundledAsset", compiler.bundledAsset ?: JSONObject.NULL)
                    .put("abi", compiler.abi)
                    .put("entryClass", compiler.entryClass ?: JSONObject.NULL)
                    .put("entryMethod", compiler.entryMethod)
                    .put("protocol", compiler.protocol)
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
            .put("schema", "riftbuild-managed-compiler-status/1")
            .put("compilerCount", rows.length())
            .put("compilers", rows)
            .put("ready", ready)
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
