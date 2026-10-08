package com.riftos.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Generic project-confined compiler execution backing the RAPP build.local capability.
 * Recipes, packaging and signing semantics do not live here.
 */
class RiftLocalBuildCapability(context: Context) {
    private data class ProjectRef(val display: String, val file: File)

    companion object {
        private val SAFE_SEGMENT = Regex("^[A-Za-z0-9._+-]{1,120}$")
    }

    private val appContext = context.applicationContext
    private val riftRoot = File(appContext.filesDir, "riftfs").apply { mkdirs() }.canonicalFile
    private val workspaceRoot = File(riftRoot, "workspace").apply { mkdirs() }.canonicalFile
    private val managed = RiftBuildManagedToolchains(workspaceRoot)
    private val jvmDex = RiftJvmDexService(appContext, workspaceRoot, riftRoot)

    fun compilerStatus(project: String, cwd: String = "/D:/Workspace"): JSONObject {
        val ref = resolveProject(project, cwd)
        require(ref.file.isDirectory) { "Build project is not a directory: " + ref.display }
        return managed.compilerStatus(ref.file).put("project", ref.display)
    }

    fun compilerRun(
        project: String,
        compilerId: String,
        requestPath: String,
        cwd: String = "/D:/Workspace"
    ): JSONObject {
        val ref = resolveProject(project, cwd)
        val requestFile = projectFile(ref, requestPath)
        require(requestFile.isFile && requestFile.length() in 1..(512L * 1024L)) {
            "Managed compiler request is missing or exceeds 512 KiB"
        }
        return runManagedCompiler(
            ref,
            compilerId,
            JSONObject(requestFile.readText(Charsets.UTF_8))
        ).put("request", projectDisplay(ref, requestFile))
    }

    fun compilerRunInline(
        project: String,
        compilerId: String,
        request: JSONObject,
        cwd: String = "/D:/Workspace"
    ): JSONObject = runManagedCompiler(resolveProject(project, cwd), compilerId, request)

    fun jvmToolchainStatus(): JSONObject = jvmDex.status()

    fun dexJvmClasses(
        project: String,
        classesDir: String,
        outputDir: String,
        minSdk: Int,
        cwd: String = "/D:/Workspace"
    ): JSONObject {
        val ref = resolveProject(project, cwd)
        return jvmDex.dexJvmClasses(ref.file, classesDir, outputDir, minSdk)
            .put("project", ref.display)
    }

    private fun runManagedCompiler(
        ref: ProjectRef,
        compilerId: String,
        request: JSONObject
    ): JSONObject {
        require(request.optString("schema") == RiftBuildManagedToolchains.COMPILER_PROTOCOL) {
            "Managed compiler request schema is unsupported"
        }
        val compiler = managed.resolveCompiler(ref.file, compilerId)
        val payload = resolveCompilerPayload(ref, compiler)
        val receipt = JSONObject()
            .put("schema", "riftbuild-managed-compiler-run/1")
            .put("project", ref.display)
            .put("compilerId", compiler.id)
            .put("engine", compiler.engine)
            .put("payloadSha256", payload.second)
            .put("protocol", compiler.protocol)

        return when (compiler.engine) {
            RiftBuildManagedToolchains.ENGINE_DEX_JSON -> {
                val entryClass = compiler.entryClass
                    ?: error("DEX managed compiler entry class is missing")
                val result = RiftManagedJvmToolService.run(
                    appContext,
                    payload.first,
                    payload.second,
                    entryClass,
                    compiler.entryMethod,
                    normalizeRequest(ref, request).toString()
                )
                val state = result.getString("status").orEmpty()
                receipt.put("state", state)
                if (state != "success") {
                    receipt
                        .put("errorClass", result.getString("errorClass") ?: JSONObject.NULL)
                        .put("detail", result.getString("detail") ?: JSONObject.NULL)
                } else {
                    val responseText = result.getString("responseJson")
                        ?: error("Managed JVM compiler returned no response JSON")
                    require(responseText.toByteArray(Charsets.UTF_8).size <= 512 * 1024) {
                        "Managed JVM compiler response exceeds 512 KiB"
                    }
                    val response = JSONObject(responseText)
                    require(response.optString("schema") == "riftbuild-compiler-response/1") {
                        "Managed JVM compiler response schema is unsupported"
                    }
                    receipt
                        .put("state", response.optString("state", "success"))
                        .put("response", response)
                }
            }

            RiftBuildManagedToolchains.ENGINE_NATIVE_BUFFER -> {
                val sourceRelative = request.optString("source").trim()
                val outputRelative = request.optString("output").trim()
                val capacity = request.optInt("outputCapacity", 512 * 1024)
                require(sourceRelative.isNotBlank()) {
                    "Native managed compiler request requires source"
                }
                require(
                    outputRelative.startsWith("build/riftbuild/") &&
                        !outputRelative.contains("\\")
                ) {
                    "Native managed compiler output must stay under build/riftbuild"
                }
                require(capacity in 1..(512 * 1024)) {
                    "Native managed compiler output capacity is out of bounds"
                }
                val source = projectFile(ref, sourceRelative)
                require(source.isFile && source.length() in 0..(512L * 1024L)) {
                    "Native managed compiler source is missing or oversized"
                }
                val output = projectFile(ref, outputRelative)
                val hostAbi = if (android.os.Process.is64Bit()) "arm64" else "arm32"
                require(compiler.abi == "any" || compiler.abi == hostAbi) {
                    "Managed compiler ABI mismatch: compiler=" + compiler.abi + " host=" + hostAbi
                }
                val result = RiftNativeBufferCompilerService.compile(
                    appContext,
                    payload.first.readBytes(),
                    source.readBytes(),
                    capacity
                )
                val state = result.getString("status").orEmpty()
                receipt
                    .put("state", state)
                    .put("hostStatus", result.getInt("hostStatus", -1))
                    .put("returnValue", result.getLong("returnValue", 0xffffffffL))
                if (state == "success") {
                    val bytes = result.getByteArray("output")
                        ?: error("Native managed compiler reported success without output")
                    require(bytes.size == result.getLong("returnValue").toInt()) {
                        "Native managed compiler output length drift"
                    }
                    val parent = output.parentFile
                        ?: error("Native managed compiler output has no parent")
                    require(parent.mkdirs() || parent.isDirectory)
                    val temp = File(parent, "." + output.name + ".tmp").canonicalFile
                    require(confinedTo(ref.file, temp)) {
                        "Native managed compiler temp output escaped project"
                    }
                    temp.writeBytes(bytes)
                    if (output.exists()) require(output.delete())
                    require(temp.renameTo(output)) {
                        "Could not commit native managed compiler output"
                    }
                    receipt
                        .put("output", projectDisplay(ref, output))
                        .put("outputBytes", bytes.size)
                        .put("outputSha256", sha256(bytes))
                }
                receipt
            }

            else -> error("Unsupported managed compiler engine: " + compiler.engine)
        }
    }

    private fun normalizeRequest(ref: ProjectRef, request: JSONObject): JSONObject {
        val normalized = JSONObject(request.toString()).put("projectRoot", ref.file.absolutePath)
        request.optJSONArray("sources")?.let { sources ->
            require(sources.length() in 1..64) {
                "Managed compiler source count is out of bounds"
            }
            var total = 0L
            for (index in 0 until sources.length()) {
                val relative = sources.getString(index).trim()
                require(safeRelative(relative)) { "Managed compiler source path is invalid" }
                val file = projectFile(ref, relative)
                require(file.isFile) { "Managed compiler source is missing: $relative" }
                total += file.length()
                require(total <= 8L * 1024L * 1024L) {
                    "Managed compiler total source bytes exceed 8 MiB"
                }
            }
        }
        request.optJSONArray("classpath")?.let { classpath ->
            require(classpath.length() in 0..32) {
                "Managed compiler classpath count is out of bounds"
            }
            val toolchainRoot = File(riftRoot, "system/toolchains").canonicalFile
            for (index in 0 until classpath.length()) {
                val file = File(classpath.getString(index).trim()).canonicalFile
                require(confinedTo(ref.file, file) || confinedTo(toolchainRoot, file)) {
                    "Managed compiler classpath escaped project/toolchains"
                }
                require(file.isFile) { "Managed compiler classpath entry is missing" }
            }
        }
        val outputRelative = request.optString("outputDir").trim()
        if (outputRelative.isNotBlank()) {
            require(
                outputRelative.startsWith("build/riftbuild/") &&
                    !outputRelative.contains("\\")
            ) {
                "Managed compiler outputDir must stay under build/riftbuild"
            }
            val output = projectFile(ref, outputRelative)
            require(output.mkdirs() || output.isDirectory) {
                "Could not create managed compiler outputDir"
            }
        }
        return normalized
    }

    private fun resolveCompilerPayload(
        ref: ProjectRef,
        compiler: RiftBuildManagedToolchains.Compiler
    ): Pair<File, String> {
        compiler.payloadId?.let { id ->
            val payload = managed.resolve(ref.file, id)
            return payload.file to payload.sha256
        }
        val asset = compiler.bundledAsset
            ?: error("Managed compiler payload source is missing")
        val root = File(riftRoot, "system/toolchains/rift-managed-compilers")
            .apply { mkdirs() }
            .canonicalFile
        val target = File(root, asset.substringAfterLast('/')).canonicalFile
        require(confinedTo(root, target)) {
            "Managed compiler bundled target escaped cache root"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val temp = File(root, "." + target.name + ".tmp").canonicalFile
        var total = 0L
        appContext.assets.open(asset).buffered().use { input ->
            temp.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                    require(total <= 128L * 1024L * 1024L) {
                        "Managed compiler bundled payload exceeds 128 MiB"
                    }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
        }
        require(total > 0) { "Managed compiler bundled payload is empty" }
        val expected = digest.digest().joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
        if (!target.isFile || target.length() != temp.length() || sha256(target) != expected) {
            if (target.exists()) require(target.delete())
            require(temp.renameTo(target)) {
                "Could not commit managed compiler cached payload"
            }
        } else {
            require(temp.delete())
        }
        require(sha256(target) == expected) {
            "Managed compiler cached payload identity mismatch"
        }
        return target to expected
    }

    private fun resolveProject(raw: String, cwd: String): ProjectRef {
        require(raw.isNotBlank()) { "Build project path is required" }
        val rawDisplay = workspaceAlias(raw)
        val cwdDisplay = workspaceAlias(cwd)
        val joined = if (rawDisplay.startsWith("/")) {
            rawDisplay
        } else {
            val base = if (
                cwdDisplay == "/D:/Workspace" ||
                cwdDisplay.startsWith("/D:/Workspace/")
            ) cwdDisplay else "/D:/Workspace"
            base.trimEnd('/') + "/" + rawDisplay
        }
        val display = RiftVolumePaths.normalizeDisplay(joined)
        require(display == "/D:/Workspace" || display.startsWith("/D:/Workspace/")) {
            "Build projects must live under D:/Workspace"
        }
        val file = File(riftRoot, RiftVolumePaths.resolveRelative(display)).canonicalFile
        require(confinedTo(workspaceRoot, file)) { "Build project escaped workspace" }
        require(file.isDirectory) { "Build project is not a directory: $display" }
        return ProjectRef(display, file)
    }

    private fun workspaceAlias(raw: String): String {
        val value = raw.trim().replace('\\', '/')
        return when {
            value == "/workspace" || value == "workspace" -> "/D:/Workspace"
            value.startsWith("/workspace/") ->
                "/D:/Workspace/" + value.removePrefix("/workspace/")
            value.startsWith("workspace/") ->
                "/D:/Workspace/" + value.removePrefix("workspace/")
            else -> value
        }
    }

    private fun projectFile(ref: ProjectRef, relative: String): File {
        require(safeRelative(relative)) { "Unsafe project-relative build path" }
        val file = File(ref.file, relative).canonicalFile
        require(confinedTo(ref.file, file)) {
            "Build project-relative path escaped project root"
        }
        return file
    }

    private fun projectDisplay(ref: ProjectRef, file: File): String {
        val canonical = file.canonicalFile
        require(confinedTo(ref.file, canonical))
        val relative = canonical.relativeTo(ref.file).invariantSeparatorsPath
        return if (relative.isBlank()) ref.display else ref.display + "/" + relative
    }

    private fun safeRelative(raw: String): Boolean {
        val value = raw.replace('\\', '/')
        return value.isNotBlank() &&
            !value.startsWith("/") &&
            !Regex("^[A-Za-z]:").containsMatchIn(value) &&
            value.split('/').all {
                it.isNotBlank() && it != "." && it != ".." && SAFE_SEGMENT.matches(it)
            }
    }

    private fun confinedTo(root: File, child: File): Boolean {
        val a = root.canonicalFile
        val b = child.canonicalFile
        return b == a || b.path.startsWith(a.path + File.separator)
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
        return digest.digest().joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
