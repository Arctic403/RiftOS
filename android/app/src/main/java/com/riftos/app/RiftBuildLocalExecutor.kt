package com.riftos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Workspace-bounded native RiftBuild controller.
 *
 * Native Compile V1 delegates structured compiler argv execution to RiftBuildNativeToolchain.
 * Project/source text is never interpreted as a shell command. This controller validates Android
 * projects, records bounded plans/runs, packages prepared artifacts, and routes bounded APK operations.
 */
class RiftBuildLocalExecutor(context: Context) {
    data class CommandResult(val output: String, val value: JSONObject)
    private data class ProjectRef(val display: String, val file: File)
    private data class ManifestAttr(
        val namespace: Int,
        val name: Int,
        val rawValue: Int,
        val dataType: Int,
        val data: Int
    )

    companion object {
        private const val MAX_PROJECT_FILES = 20_000
        private const val MAX_PROJECT_BYTES = 512L * 1024L * 1024L
        private const val MAX_PACKAGE_FILES = 5_000
        private const val MAX_PACKAGE_BYTES = 256L * 1024L * 1024L
        private const val MAX_TEXT_BYTES = 1024L * 1024L
        private const val MAX_RUNS = 100
        private const val XML_TYPE = 0x0003
        private val TARGETS = setOf("arm32", "arm64", "universal")
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
        private val SAFE_SEGMENT = Regex("^[A-Za-z0-9._+-]{1,120}$")
        private val DEX_ENTRY = Regex("^classes(?:[2-9]|[1-9][0-9]+)?\\.dex$")
    }

    private val appContext = context.applicationContext
    private val riftRoot = File(appContext.filesDir, "riftfs").apply { mkdirs() }.canonicalFile
    private val workspaceRoot = File(riftRoot, "workspace").apply { mkdirs() }.canonicalFile
    private val runRoot = File(riftRoot, "system/riftbuild/v1/runs").apply { mkdirs() }.canonicalFile
    private val artifactRoot = File(riftRoot, "documents/builds").apply { mkdirs() }.canonicalFile
    private val nativeToolchain = RiftBuildNativeToolchain(appContext, riftRoot, workspaceRoot)
    private val managedToolchains = RiftBuildManagedToolchains(workspaceRoot)
    private val kotlinCompiler = RiftBuildKotlinCompiler(appContext, workspaceRoot, riftRoot)
    private val nativeApp = RiftBuildNativeApp(workspaceRoot)
    private val rappManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RiftRappManager(appContext)
    }
    private val apkSigner = RiftApkV2Signer(appContext)
    private val installer = RiftBuildInstaller(appContext)

    fun executeShell(args: MutableList<String>, cwd: String): CommandResult {
        val sub = args.removeFirstOrNull()?.lowercase() ?: "doctor"
        val value = when (sub) {
            "help" -> JSONObject()
                .put("schema", "riftbuild-native-help-v1")
                .put("usage", "riftbuild doctor [project] | validate <project> | plan <project> [arm32|arm64|universal] | toolchain-status | toolchain-install-bundled | managed-status <project> | managed-payload <project> <id> | managed-copy <project> <id> <output> | compiler-status <project> | compiler-run <project> <compiler-id> <request.json> | kotlin-status | kotlin-compile <project> | compile-native <project> [arm32|arm64|universal] | compile-object <project> <source.S> [arm32|arm64] | extract-object-text <project> <object.o> [arm32|arm64] | prepare-native-app <project> | pack <project> [target] | pack-rapp <project> | install-rapp <artifact.rapp> | launch-rapp <id> | rapp-list | sign <unsigned-apk> | verify <signed-apk> | install-proof <signed-apk> | install-status | launch-proof | runs [limit] | artifacts [project]")
            "doctor" -> doctor(args.firstOrNull(), cwd)
            "validate" -> validate(args.firstOrNull() ?: error("usage: riftbuild validate <project>"), cwd)
            "plan" -> plan(
                args.firstOrNull() ?: error("usage: riftbuild plan <project> [arm32|arm64|universal]"),
                args.getOrNull(1) ?: "universal",
                cwd
            )
            "toolchain-status" -> nativeToolchain.status()
            "toolchain-install-bundled" -> nativeToolchain.installBundled()
            "managed-status" -> managedStatus(
                args.firstOrNull() ?: error("usage: riftbuild managed-status <project>"),
                cwd
            )
            "managed-payload" -> managedPayload(
                args.firstOrNull() ?: error("usage: riftbuild managed-payload <project> <id>"),
                args.getOrNull(1) ?: error("usage: riftbuild managed-payload <project> <id>"),
                cwd
            )
            "managed-copy" -> managedCopy(
                args.firstOrNull() ?: error("usage: riftbuild managed-copy <project> <id> <output>"),
                args.getOrNull(1) ?: error("usage: riftbuild managed-copy <project> <id> <output>"),
                args.getOrNull(2) ?: error("usage: riftbuild managed-copy <project> <id> <output>"),
                cwd
            )
            "compiler-status" -> compilerStatus(
                args.firstOrNull() ?: error("usage: riftbuild compiler-status <project>"),
                cwd
            )
            "compiler-run" -> compilerRun(
                args.firstOrNull() ?: error("usage: riftbuild compiler-run <project> <compiler-id> <request.json>"),
                args.getOrNull(1) ?: error("usage: riftbuild compiler-run <project> <compiler-id> <request.json>"),
                args.getOrNull(2) ?: error("usage: riftbuild compiler-run <project> <compiler-id> <request.json>"),
                cwd
            )
            "kotlin-status" -> kotlinCompiler.status()
            "kotlin-compile" -> kotlinCompile(
                args.firstOrNull() ?: error("usage: riftbuild kotlin-compile <project>"),
                cwd
            )
            "compile-native" -> compileNative(
                args.firstOrNull() ?: error("usage: riftbuild compile-native <project> [arm32|arm64|universal]"),
                args.getOrNull(1) ?: "universal",
                cwd
            )
            "compile-object" -> compileObject(
                args.firstOrNull() ?: error("usage: riftbuild compile-object <project> <source.S> [arm32|arm64]"),
                args.getOrNull(1) ?: error("usage: riftbuild compile-object <project> <source.S> [arm32|arm64]"),
                args.getOrNull(2) ?: "arm32",
                cwd
            )
            "extract-object-text" -> extractObjectText(
                args.firstOrNull() ?: error("usage: riftbuild extract-object-text <project> <object.o> [arm32|arm64]"),
                args.getOrNull(1) ?: error("usage: riftbuild extract-object-text <project> <object.o> [arm32|arm64]"),
                args.getOrNull(2) ?: "arm32",
                cwd
            )
            "prepare-native-app" -> prepareNativeApp(
                args.firstOrNull() ?: error("usage: riftbuild prepare-native-app <project>"),
                cwd
            )
            "pack" -> pack(
                args.firstOrNull() ?: error("usage: riftbuild pack <project> [arm32|arm64|universal]"),
                args.getOrNull(1) ?: "universal",
                cwd
            )
            "pack-rapp" -> packRapp(
                args.firstOrNull() ?: error("usage: riftbuild pack-rapp <project>"),
                cwd
            )
            "install-rapp" -> installRapp(
                args.firstOrNull() ?: error("usage: riftbuild install-rapp <artifact.rapp>")
            )
            "launch-rapp" -> launchRapp(
                args.firstOrNull() ?: error("usage: riftbuild launch-rapp <id>")
            )
            "rapp-list" -> JSONObject()
                .put("schema", "riftbuild-rapp-list-v1")
                .put("apps", rappManager.listInstalled())
            "sign" -> signArtifact(args.firstOrNull() ?: error("usage: riftbuild sign <unsigned-apk>"))
            "verify" -> verifyArtifact(args.firstOrNull() ?: error("usage: riftbuild verify <signed-apk>"))
            "install-proof" -> installProof(args.firstOrNull() ?: error("usage: riftbuild install-proof <signed-apk>"))
            "install-status" -> installer.status()
            "launch-proof" -> installer.launchProof()
            "runs" -> JSONObject().put("schema", "riftbuild-runs-v1").put("runs", runs(args.firstOrNull()?.toIntOrNull() ?: 20))
            "artifacts" -> JSONObject().put("schema", "riftbuild-artifacts-v1").put("artifacts", artifacts(args.firstOrNull(), cwd))
            else -> error("unknown riftbuild command: " + sub)
        }
        return CommandResult(value.toString(2), value)
    }

    fun doctor(project: String? = null, cwd: String = "/D:/Workspace"): JSONObject {
        val projectValue = project?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { validate(raw, cwd) }.getOrElse {
                JSONObject().put("project", raw).put("sourceReady", false).put("error", it.message ?: it.javaClass.simpleName)
            }
        }
        val packReady = projectValue?.optBoolean("sourceReady", false) == true &&
            projectValue.optBoolean("preparedPackageReady", false)
        val toolchain = nativeToolchain.status()
        val compileReady = toolchain.optBoolean("ready", false)
        val blockers = JSONArray()
        toolchain.optJSONArray("blockers")?.let { values ->
            for (i in 0 until values.length()) blockers.put("native-compile: " + values.getString(i))
        }
        blockers.put("package-install: Android may still require Allow from this source + user confirmation")
        return JSONObject()
            .put("schema", "riftbuild-native-doctor-v1")
            .put("available", true)
            .put("nativeExecutor", true)
            .put("structuredCompilerProcessExecution", true)
            .put("rawShellExecution", false)
            .put("downloadedToolchainsAllowed", true)
            .put("workspaceOnly", true)
            .put("sourceValidationReady", true)
            .put("preparedArtifactPackagerReady", true)
            .put("packReady", packReady)
            .put("compileReady", compileReady)
            .put("toolchain", toolchain)
            .put("signingReady", true)
            .put("verificationReady", true)
            .put("installOwnerReady", true)
            .put("installReady", appContext.packageManager.canRequestPackageInstalls())
            .put("ready", compileReady && (projectValue == null || packReady))
            .put("project", projectValue ?: JSONObject.NULL)
            .put("artifactRoot", "/D:/Builds")
            .put("blockers", blockers)
    }

    fun managedStatus(
        project: String,
        cwd: String = "/D:/Workspace"
    ): JSONObject {
        val ref = resolveProject(project, cwd)
        require(ref.file.isDirectory) { "Build project is not a directory: " + ref.display }
        return managedToolchains.status(ref.file)
            .put("project", ref.display)
    }

    fun managedPayload(
        project: String,
        id: String,
        cwd: String = "/D:/Workspace"
    ): JSONObject {
        val ref = resolveProject(project, cwd)
        require(ref.file.isDirectory) { "Build project is not a directory: " + ref.display }
        return managedToolchains.describe(ref.file, id)
            .put("project", ref.display)
    }

    fun managedCopy(
        project: String,
        id: String,
        outputPath: String,
        cwd: String = "/D:/Workspace"
    ): JSONObject {
        val ref = resolveProject(project, cwd)
        require(ref.file.isDirectory) { "Build project is not a directory: " + ref.display }
        val payload = managedToolchains.resolve(ref.file, id)

        val normalizedOutput = outputPath.trim().replace('\\', '/')
        require(
            normalizedOutput.startsWith("build/riftbuild/managed/") ||
                normalizedOutput.contains("/build/riftbuild/managed/")
        ) {
            "Managed payload output must stay under a build/riftbuild/managed directory"
        }
        val output = projectFile(ref, normalizedOutput)
        require(output.canonicalFile != payload.file.canonicalFile) {
            "Managed payload output must not replace its declared source"
        }

        val parent = output.parentFile ?: error("Managed payload output has no parent")
        require(parent.mkdirs() || parent.isDirectory) {
            "Could not create managed payload output directory"
        }
        val temp = File(parent, "." + output.name + ".tmp").canonicalFile
        require(confinedTo(ref.file, temp)) { "Managed payload temp escaped project" }
        if (temp.exists()) require(temp.delete()) { "Could not replace stale managed payload temp" }

        payload.file.inputStream().buffered().use { input ->
            temp.outputStream().buffered().use { outputStream ->
                input.copyTo(outputStream, 64 * 1024)
            }
        }
        require(sha256(temp) == payload.sha256) {
            "Managed payload copy identity mismatch: " + id
        }
        if (output.exists()) require(output.delete()) {
            "Could not replace managed payload output"
        }
        require(temp.renameTo(output)) {
            "Could not commit managed payload output"
        }

        return JSONObject()
            .put("schema", "riftbuild-managed-payload-copy/1")
            .put("state", "materialized")
            .put("project", ref.display)
            .put("id", payload.id)
            .put("kind", payload.kind)
            .put("abi", payload.abi)
            .put("sourceSha256", payload.sha256)
            .put("output", projectDisplay(ref, output))
            .put("outputBytes", output.length())
            .put("outputSha256", sha256(output))
    }

    fun compilerStatus(
        project: String,
        cwd: String = "/D:/Workspace"
    ): JSONObject {
        val ref = resolveProject(project, cwd)
        require(ref.file.isDirectory) { "Build project is not a directory: " + ref.display }
        return managedToolchains.compilerStatus(ref.file)
            .put("project", ref.display)
    }

    fun compilerRun(
        project: String,
        compilerId: String,
        requestPath: String,
        cwd: String = "/D:/Workspace"
    ): JSONObject {
        val ref = resolveProject(project, cwd)
        require(ref.file.isDirectory) { "Build project is not a directory: " + ref.display }

        val requestFile = projectFile(ref, requestPath)
        require(requestFile.isFile && requestFile.length() in 1..(512L * 1024L)) {
            "Managed compiler request is missing or exceeds 512 KiB"
        }
        val request = JSONObject(requestFile.readText(Charsets.UTF_8))
        return runManagedCompiler(ref, compilerId, request)
            .put("request", projectDisplay(ref, requestFile))
    }

    private fun runManagedCompiler(
        ref: ProjectRef,
        compilerId: String,
        request: JSONObject
    ): JSONObject {
        require(request.optString("schema") == RiftBuildManagedToolchains.COMPILER_PROTOCOL) {
            "Managed compiler request schema is unsupported"
        }

        val compiler = managedToolchains.resolveCompiler(ref.file, compilerId)
        val payload = resolveManagedCompilerPayload(ref, compiler)
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
                val normalized = normalizeManagedCompilerRequest(ref, request)
                val result = RiftManagedJvmToolService.run(
                    appContext,
                    payload.first,
                    payload.second,
                    entryClass,
                    compiler.entryMethod,
                    normalized.toString()
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
                    require(
                        response.optString("schema") == "riftbuild-compiler-response/1"
                    ) {
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
                    val outputBytes = result.getByteArray("output")
                        ?: error("Native managed compiler reported success without output")
                    require(outputBytes.size == result.getLong("returnValue").toInt()) {
                        "Native managed compiler output length drift"
                    }
                    val parent = output.parentFile
                        ?: error("Native managed compiler output has no parent")
                    require(parent.mkdirs() || parent.isDirectory) {
                        "Could not create native managed compiler output directory"
                    }
                    val temp = File(parent, "." + output.name + ".tmp").canonicalFile
                    require(confinedTo(ref.file, temp)) {
                        "Native managed compiler temp output escaped project"
                    }
                    temp.outputStream().use { it.write(outputBytes) }
                    if (output.exists()) require(output.delete()) {
                        "Could not replace native managed compiler output"
                    }
                    require(temp.renameTo(output)) {
                        "Could not commit native managed compiler output"
                    }
                    receipt
                        .put("output", projectDisplay(ref, output))
                        .put("outputBytes", outputBytes.size)
                        .put("outputSha256", sha256(outputBytes))
                }
                receipt
            }

            else -> error("Unsupported managed compiler engine: " + compiler.engine)
        }
    }

    private fun normalizeManagedCompilerRequest(
        ref: ProjectRef,
        request: JSONObject
    ): JSONObject {
        val normalized = JSONObject(request.toString())
        normalized.put("projectRoot", ref.file.absolutePath)

        request.optJSONArray("sources")?.let { sources ->
            require(sources.length() in 1..64) {
                "Managed compiler source count is out of bounds"
            }
            var total = 0L
            for (index in 0 until sources.length()) {
                val relative = sources.getString(index).trim()
                require(relative.isNotBlank() && !relative.startsWith("/") && !relative.contains("\\")) {
                    "Managed compiler source path is invalid"
                }
                val file = projectFile(ref, relative)
                require(file.isFile) { "Managed compiler source is missing: " + relative }
                total += file.length()
                require(total <= 8L * 1024L * 1024L) {
                    "Managed compiler total source bytes exceed 8 MiB"
                }
            }
        }

        val outputDir = request.optString("outputDir").trim()
        if (outputDir.isNotBlank()) {
            require(outputDir.startsWith("build/riftbuild/") && !outputDir.contains("\\")) {
                "Managed compiler outputDir must stay under build/riftbuild"
            }
            val output = projectFile(ref, outputDir)
            require(output.mkdirs() || output.isDirectory) {
                "Could not create managed compiler outputDir"
            }
        }
        return normalized
    }

    private fun resolveManagedCompilerPayload(
        ref: ProjectRef,
        compiler: RiftBuildManagedToolchains.Compiler
    ): Pair<File, String> {
        compiler.payloadId?.let { payloadId ->
            val payload = managedToolchains.resolve(ref.file, payloadId)
            return payload.file to payload.sha256
        }

        val asset = compiler.bundledAsset
            ?: error("Managed compiler payload source is missing")
        val root = File(riftRoot, "system/toolchains/rift-managed-compilers")
            .apply { mkdirs() }
            .canonicalFile
        require(root.isDirectory) { "Managed compiler cache root is unavailable" }

        val target = File(root, asset.substringAfterLast('/')).canonicalFile
        require(target.toPath().startsWith(root.toPath())) {
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
        val expected = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }

        if (
            !target.isFile ||
            target.length() != temp.length() ||
            sha256(target) != expected
        ) {
            if (target.exists()) require(target.delete()) {
                "Could not replace managed compiler cached payload"
            }
            require(temp.renameTo(target)) {
                "Could not commit managed compiler cached payload"
            }
        } else {
            require(temp.delete()) { "Could not remove redundant managed compiler temp payload" }
        }
        require(sha256(target) == expected) {
            "Managed compiler cached payload identity mismatch"
        }
        return target to expected
    }

    fun kotlinCompile(
        project: String,
        cwd: String = "/D:/Workspace"
    ): JSONObject {
        val ref = resolveProject(project, cwd)
        require(ref.file.isDirectory) { "Build project is not a directory: " + ref.display }
        return kotlinCompiler.compile(ref.file) { compilerId, request ->
            runManagedCompiler(ref, compilerId, request)
        }.put("project", ref.display)
    }

    fun compileNative(project: String, target: String = "universal", cwd: String = "/D:/Workspace"): JSONObject {
        val ref = resolveProject(project, cwd)
        require(ref.file.isDirectory) { "Build project is not a directory: " + ref.display }
        return nativeToolchain.compile(ref.file, normalizeTarget(target))
    }

    fun compileObject(
        project: String,
        sourcePath: String,
        target: String = "arm32",
        cwd: String = "/D:/Workspace"
    ): JSONObject {
        val ref = resolveProject(project, cwd)
        require(ref.file.isDirectory) { "Build project is not a directory: " + ref.display }
        return nativeToolchain.compileAssemblyObject(
            ref.file,
            sourcePath,
            target.lowercase()
        ).put("project", ref.display)
    }

    fun extractObjectText(
        project: String,
        objectPath: String,
        target: String = "arm32",
        cwd: String = "/D:/Workspace"
    ): JSONObject {
        val ref = resolveProject(project, cwd)
        require(ref.file.isDirectory) { "Build project is not a directory: " + ref.display }
        return nativeToolchain.extractRelocationFreeText(
            ref.file,
            objectPath,
            target.lowercase()
        ).put("project", ref.display)
    }
    fun prepareNativeApp(project: String, cwd: String = "/D:/Workspace"): JSONObject {
        val ref = resolveProject(project, cwd)
        require(ref.file.isDirectory) { "Build project is not a directory: " + ref.display }
        return nativeApp.prepare(ref.file).put("project", ref.display)
    }

    fun validate(project: String, cwd: String = "/D:/Workspace"): JSONObject {
        val ref = resolveProject(project, cwd)
        require(ref.file.isDirectory) { "Build project is not a directory: " + ref.display }

        var files = 0
        var bytes = 0L
        ref.file.walkTopDown().forEach { file ->
            RiftDeadline.check("RiftBuild project scan")
            require(confinedTo(ref.file, file)) { "Build project escaped project root" }
            if (!file.isFile) return@forEach
            files += 1
            require(files <= MAX_PROJECT_FILES) { "Build project exceeds file-count limit" }
            bytes += file.length()
            require(bytes <= MAX_PROJECT_BYTES) { "Build project exceeds byte limit" }
        }

        val checks = JSONArray()
        fun check(id: String, ok: Boolean, detail: String) {
            checks.put(JSONObject().put("id", id).put("ok", ok).put("detail", detail))
        }

        val settings = firstExisting(ref.file, "settings.gradle.kts", "settings.gradle")
        val rootGradle = firstExisting(ref.file, "build.gradle.kts", "build.gradle")
        val appGradle = firstExisting(ref.file, "app/build.gradle.kts", "app/build.gradle")
        val manifest = File(ref.file, "app/src/main/AndroidManifest.xml")
        val nativeProjectManifest = File(ref.file, "rift-native.json")
        val nativeAppManifest = File(ref.file, "rift-app.json")
        val manifestNativeProject =
            nativeProjectManifest.isFile && nativeAppManifest.isFile

        var nativeActivity = false
        var nativeLibraryName = ""
        var activityName = ""
        var requiresDex = false

        if (manifestNativeProject) {
            val projectReceipt = runCatching {
                nativeToolchain.validateProject(ref.file)
            }
            check(
                "rift-native-manifest",
                projectReceipt.isSuccess,
                projectReceipt.getOrNull()?.optString("projectSchema")
                    ?: projectReceipt.exceptionOrNull()?.message
                    ?: "invalid rift-native.json"
            )

            val appReceipt = runCatching {
                nativeApp.validateProject(ref.file)
            }
            check(
                "rift-app-manifest",
                appReceipt.isSuccess,
                appReceipt.getOrNull()?.optString("appSchema")
                    ?: appReceipt.exceptionOrNull()?.message
                    ?: "invalid rift-app.json"
            )

            appReceipt.getOrNull()?.let { receipt ->
                nativeLibraryName = receipt.optString("library")
                activityName = receipt.optString("activityClass")
                nativeActivity =
                    receipt.optString("activityProfile") == "native-activity"
                requiresDex = receipt.optBoolean("requiresDex")
            }
        } else {
            check("settings", settings != null, settings?.name ?: "missing settings.gradle(.kts)")
            check("root-gradle", rootGradle != null, rootGradle?.name ?: "missing build.gradle(.kts)")
            check(
                "app-gradle",
                appGradle != null,
                appGradle?.relativeTo(ref.file)?.invariantSeparatorsPath
                    ?: "missing app/build.gradle(.kts)"
            )
            check(
                "manifest",
                manifest.isFile,
                if (manifest.isFile) {
                    "app/src/main/AndroidManifest.xml"
                } else {
                    "missing AndroidManifest.xml"
                }
            )

            if (manifest.isFile) {
                val text = readTextBounded(manifest)
                activityName =
                    Regex("""<activity\b[^>]*android:name\s*=\s*["']([^"']+)["']""")
                        .find(text)?.groupValues?.getOrNull(1).orEmpty()
                nativeActivity =
                    activityName == "android.app.NativeActivity" ||
                        text.contains("android.app.NativeActivity")
                requiresDex = activityName.isNotBlank() && !nativeActivity
                nativeLibraryName =
                    Regex("""android\.app\.lib_name[\s\S]*?android:value\s*=\s*["']([^"']+)["']""")
                        .find(text)?.groupValues?.getOrNull(1).orEmpty()
                check(
                    "activity",
                    activityName.isNotBlank(),
                    if (activityName.isBlank()) "launch activity missing" else activityName
                )
                if (nativeActivity) {
                    check(
                        "native-library-name",
                        nativeLibraryName.isNotBlank(),
                        if (nativeLibraryName.isBlank()) {
                            "android.app.lib_name missing"
                        } else {
                            nativeLibraryName
                        }
                    )
                }
            }
        }

        val arm64Source = File(ref.file, "app/src/main/cpp/generated/arm64-v8a/rift_ir_entry.S")
        val arm32Source = File(ref.file, "app/src/main/cpp/generated/armeabi-v7a/rift_ir_entry.S")
        val riftNativeProof = arm64Source.isFile || arm32Source.isFile
        if (riftNativeProof) {
            check("rift-arm64-source", arm64Source.isFile, if (arm64Source.isFile) sha256(arm64Source) else "missing arm64 source")
            check("rift-arm32-source", arm32Source.isFile, if (arm32Source.isFile) sha256(arm32Source) else "missing arm32 source")
            val gradleText = appGradle?.let(::readTextBounded).orEmpty()
            val dualAbi = gradleText.contains("armeabi-v7a") && gradleText.contains("arm64-v8a")
            check("dual-abi-declaration", dualAbi, if (dualAbi) "arm32 + arm64 declared" else "dual ABI filters missing")
        }

        val sourceReady = (0 until checks.length()).all { checks.getJSONObject(it).optBoolean("ok") }
        val prepared = inspectPrepared(ref, "universal", requiresDex)
        return JSONObject()
            .put("schema", "riftbuild-native-validation-v1")
            .put("project", ref.display)
            .put("projectName", ref.file.name)
            .put("projectSha256", treeSha256(ref.file))
            .put("files", files)
            .put("bytes", bytes)
            .put("sourceReady", sourceReady)
            .put("androidGradleProject", settings != null && rootGradle != null && appGradle != null && manifest.isFile)
            .put("manifestNativeProject", manifestNativeProject)
            .put("nativeActivity", nativeActivity)
            .put("nativeLibraryName", nativeLibraryName)
            .put("activityName", activityName)
            .put("requiresDex", requiresDex)
            .put("riftNativeProof", riftNativeProof)
            .put("checks", checks)
            .put("preparedPackageReady", prepared.optBoolean("ready"))
            .put("prepared", prepared)
    }

    fun plan(project: String, target: String = "universal", cwd: String = "/D:/Workspace"): JSONObject {
        val normalizedTarget = normalizeTarget(target)
        val validation = validate(project, cwd)
        val ref = resolveProject(project, cwd)
        val requiresDex = validation.optBoolean("requiresDex", false)
        val prepared = inspectPrepared(ref, normalizedTarget, requiresDex)
        val sourceReady = validation.optBoolean("sourceReady")
        val packReady = sourceReady && prepared.optBoolean("ready")
        val prepareHint = "materialize a bounded prepared Android package"
        return JSONObject()
            .put("format", "riftbuild-native-plan-v1")
            .put("project", ref.display)
            .put("projectSha256", validation.optString("projectSha256"))
            .put("target", normalizedTarget)
            .put("sourceReady", sourceReady)
            .put("packReady", packReady)
            .put("fullBuildReady", false)
            .put("prepared", prepared)
            .put("artifactRoot", artifactProjectDisplay(ref))
            .put("stages", JSONArray()
                .put(stage("source-validation", if (sourceReady) "ready" else "blocked", if (sourceReady) null else "source validation failed"))
                .put(stage(
                    "prepared-native-proof",
                    if (hasPreparedNative(prepared, normalizedTarget)) "prepared" else "blocked",
                    if (hasPreparedNative(prepared, normalizedTarget)) null else prepareHint
                ))
                .put(stage("apk-package", if (packReady) "ready" else "blocked", if (packReady) null else "prepared binary Android artifacts incomplete"))
                .put(stage("apk-signing", "ready-v2", null))
                .put(stage("artifact-verification", "ready-v2", null))
                .put(stage("package-install", "user-confirmed", "restricted to RiftBuild proof-package allowlist; Android user confirmation may be required")))
    }


    fun prepare(args: JSONObject, cwd: String = "/D:/Workspace"): JSONObject {
        require(!args.has("command") && !args.has("shell") && !args.has("exec")) { "RiftBuild does not accept raw commands" }
        val kind = args.optString("kind", "native-app")
        val project = args.optString("project").ifBlank { args.optString("projectPath") }
        require(project.isNotBlank()) { "build.prepare requires a project root" }
        return when (kind) {
            "native-app" -> prepareNativeApp(project, cwd)
            else -> error("build.prepare kind must be native-app")
        }
    }

    @Synchronized
    fun submit(args: JSONObject, cwd: String = "/D:/Workspace"): JSONObject {
        require(!args.has("command") && !args.has("shell") && !args.has("exec")) { "RiftBuild does not accept raw commands" }
        val project = args.optString("project").ifBlank { args.optString("projectPath") }
        require(project.isNotBlank()) { "build.submit requires project" }
        val target = normalizeTarget(args.optString("target", "universal"))
        val currentPlan = plan(project, target, cwd)
        return if (currentPlan.optBoolean("packReady")) pack(project, target, cwd)
        else blockedRun(resolveProject(project, cwd), target, currentPlan)
    }

    @Synchronized
    fun pack(project: String, target: String = "universal", cwd: String = "/D:/Workspace"): JSONObject {
        val normalizedTarget = normalizeTarget(target)
        val ref = resolveProject(project, cwd)
        val currentPlan = plan(project, normalizedTarget, cwd)
        if (!currentPlan.optBoolean("packReady")) return blockedRun(ref, normalizedTarget, currentPlan)

        val prepared = File(ref.file, "build/riftbuild/prepared").canonicalFile
        val entries = collectPreparedEntries(prepared, normalizedTarget)
        val id = runId()
        val outDir = File(artifactProjectRoot(ref), id).apply { mkdirs() }.canonicalFile
        require(confinedTo(artifactRoot, outDir)) { "RiftBuild output escaped D:/Builds" }
        val apk = File(outDir, safeName(ref.file.name) + "-" + normalizedTarget + "-unsigned.apk")
        val temp = File(outDir, "." + apk.name + ".tmp")
        val seen = linkedSetOf<String>()
        var total = 0L

        try {
            ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                for ((entryName, source) in entries) {
                    require(seen.add(entryName)) { "duplicate APK entry: " + entryName }
                    require(seen.size <= MAX_PACKAGE_FILES) { "APK entry-count limit exceeded" }
                    total += source.length()
                    require(total <= MAX_PACKAGE_BYTES) { "APK package byte limit exceeded" }
                    zip.putNextEntry(ZipEntry(entryName).apply { time = 0L })
                    source.inputStream().buffered().use { input ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            RiftDeadline.check("RiftBuild APK pack")
                            val read = input.read(buffer)
                            if (read <= 0) break
                            zip.write(buffer, 0, read)
                        }
                    }
                    zip.closeEntry()
                }
            }
            require(temp.renameTo(apk)) { "could not publish unsigned APK" }
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }

        val receipt = JSONObject()
            .put("format", "riftbuild-apk-package-receipt-v1")
            .put("state", "packaged-unsigned")
            .put("runId", id)
            .put("project", ref.display)
            .put("projectSha256", currentPlan.optString("projectSha256"))
            .put("target", normalizedTarget)
            .put("artifact", artifactDisplay(apk))
            .put("artifactSha256", sha256(apk))
            .put("artifactBytes", apk.length())
            .put("entries", seen.size)
            .put("signed", false)
            .put("installableClaimed", false)
            .put("blockers", JSONArray()
                .put("run riftbuild sign on this bounded unsigned artifact")
                .put("install is allowed only after independent v2 verification and Android user confirmation"))
            .put("createdAt", System.currentTimeMillis())
        atomicWrite(File(outDir, "receipt.json"), receipt.toString(2).toByteArray(Charsets.UTF_8))
        writeRun(receipt)
        return receipt
    }

    @Synchronized
    fun packRapp(project: String, cwd: String = "/D:/Workspace"): JSONObject {
        val ref = resolveProject(project, cwd)
        val packed = rappManager.pack(ref.file)
        val receipt = JSONObject(packed.receipt.toString())
            .put("runId", runId())
            .put("project", ref.display)
            .put("artifact", artifactDisplay(packed.artifact))
            .put("createdAt", System.currentTimeMillis())
        writeRun(receipt)
        return receipt
    }

    @Synchronized
    fun installRapp(rawArtifact: String): JSONObject {
        val artifact = resolveArtifact(rawArtifact)
        val receipt = rappManager.install(artifact)
            .put("runId", runId())
            .put("artifact", artifactDisplay(artifact))
            .put("artifactSha256", sha256(artifact))
            .put("createdAt", System.currentTimeMillis())
        writeRun(receipt)
        return receipt
    }

    fun launchRapp(id: String): JSONObject {
        val receipt = rappManager.launch(id)
            .put("runId", runId())
            .put("createdAt", System.currentTimeMillis())
        writeRun(receipt)
        return receipt
    }


    @Synchronized
    fun signArtifact(rawArtifact: String): JSONObject {
        val unsignedApk = resolveArtifact(rawArtifact)
        require(unsignedApk.name.endsWith("-unsigned.apk")) { "RiftBuild sign accepts only *-unsigned.apk artifacts" }
        val signedApk = File(
            unsignedApk.parentFile,
            unsignedApk.name.removeSuffix("-unsigned.apk") + "-signed.apk"
        ).canonicalFile
        require(confinedTo(artifactRoot, signedApk)) { "signed APK output escaped D:/Builds" }

        val signed = apkSigner.sign(unsignedApk, signedApk)
        val id = runId()
        val receipt = JSONObject()
            .put("format", "riftbuild-apk-v2-signing-receipt-v1")
            .put("state", "signed-v2")
            .put("runId", id)
            .put("inputArtifact", artifactDisplay(unsignedApk))
            .put("inputSha256", sha256(unsignedApk))
            .put("artifact", artifactDisplay(signedApk))
            .put("artifactSha256", signed.apkSha256)
            .put("artifactBytes", signed.outputBytes)
            .put("scheme", 2)
            .put("signatureAlgorithmId", "0x0103")
            .put("certificateSha256", signed.certificateSha256)
            .put("publicKeySha256", signed.publicKeySha256)
            .put("contentDigestSha256", signed.contentDigestSha256)
            .put("signingBlockBytes", signed.signingBlockBytes)
            .put("signatureVerified", true)
            .put("installableClaimed", false)
            .put("createdAt", System.currentTimeMillis())
        atomicWrite(
            File(signedApk.parentFile, "signing-receipt.json"),
            receipt.toString(2).toByteArray(Charsets.UTF_8)
        )
        writeRun(receipt)
        return receipt
    }

    fun verifyArtifact(rawArtifact: String): JSONObject {
        val signedApk = resolveArtifact(rawArtifact)
        require(signedApk.name.endsWith("-signed.apk")) { "RiftBuild verify accepts only *-signed.apk artifacts" }
        val verified = apkSigner.verify(signedApk)
        val id = runId()
        val receipt = JSONObject()
            .put("format", "riftbuild-apk-v2-verification-receipt-v1")
            .put("state", "verified-v2")
            .put("runId", id)
            .put("artifact", artifactDisplay(signedApk))
            .put("artifactSha256", verified.apkSha256)
            .put("artifactBytes", signedApk.length())
            .put("scheme", 2)
            .put("signatureAlgorithmId", "0x0103")
            .put("certificateSha256", verified.certificateSha256)
            .put("publicKeySha256", verified.publicKeySha256)
            .put("contentDigestSha256", verified.contentDigestSha256)
            .put("signingBlockBytes", verified.signingBlockBytes)
            .put("signatureVerified", true)
            .put("installableClaimed", false)
            .put("verifiedAt", System.currentTimeMillis())
        atomicWrite(
            File(signedApk.parentFile, "verification-receipt.json"),
            receipt.toString(2).toByteArray(Charsets.UTF_8)
        )
        writeRun(receipt)
        return receipt
    }

    @Synchronized
    fun installProof(rawArtifact: String): JSONObject {
        val signedApk = resolveArtifact(rawArtifact)
        require(signedApk.name.endsWith("-signed.apk")) { "RiftBuild install-proof accepts only *-signed.apk artifacts" }
        val verified = apkSigner.verify(signedApk)
        val result = installer.installProof(signedApk, verified)
            .put("format", "riftbuild-install-proof-v1")
            .put("runId", runId())
            .put("artifact", artifactDisplay(signedApk))
            .put("artifactSha256", verified.apkSha256)
            .put("certificateSha256", verified.certificateSha256)
            .put("signatureVerified", true)
            .put("installableClaimed", false)
        writeRun(result)
        return result
    }

    fun runs(limit: Int = 20): JSONArray {
        val out = JSONArray()
        runRoot.listFiles()
            ?.filter { it.isFile && it.extension.equals("json", true) }
            ?.sortedByDescending { it.lastModified() }
            ?.take(limit.coerceIn(1, MAX_RUNS))
            ?.forEach { file ->
                if (file.length() <= MAX_TEXT_BYTES) {
                    runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull()?.let(out::put)
                }
            }
        return out
    }

    fun artifacts(project: String? = null, cwd: String = "/D:/Workspace"): JSONArray {
        val root = if (project.isNullOrBlank()) artifactRoot else artifactProjectRoot(resolveProject(project, cwd))
        if (!root.isDirectory) return JSONArray()
        val out = JSONArray()
        var rows = 0
        root.walkTopDown().forEach { file ->
            RiftDeadline.check("RiftBuild artifact list")
            if (file == root) return@forEach
            rows += 1
            require(rows <= MAX_PACKAGE_FILES) { "RiftBuild artifact listing limit exceeded" }
            out.put(JSONObject()
                .put("path", artifactDisplay(file))
                .put("name", file.name)
                .put("kind", if (file.isDirectory) "directory" else "file")
                .put("size", if (file.isFile) file.length() else 0L)
                .put("modified", file.lastModified()))
        }
        return out
    }

    private fun resolveProject(raw: String, cwd: String): ProjectRef {
        require(raw.isNotBlank()) { "RiftBuild project path is required" }
        val rawDisplay = workspaceAlias(raw)
        val cwdDisplay = workspaceAlias(cwd)
        val joined = if (rawDisplay.startsWith("/")) rawDisplay else {
            val base = if (cwdDisplay == "/D:/Workspace" || cwdDisplay.startsWith("/D:/Workspace/")) cwdDisplay else "/D:/Workspace"
            base.trimEnd('/') + "/" + rawDisplay
        }
        val display = RiftVolumePaths.normalizeDisplay(joined)
        require(display == "/D:/Workspace" || display.startsWith("/D:/Workspace/")) { "RiftBuild projects must live under D:/Workspace" }
        val file = File(riftRoot, RiftVolumePaths.resolveRelative(display)).canonicalFile
        require(confinedTo(workspaceRoot, file)) { "RiftBuild project escaped workspace" }
        return ProjectRef(display, file)
    }

    private fun workspaceAlias(raw: String): String {
        val value = raw.trim().replace('\\', '/')
        return when {
            value == "/workspace" || value == "workspace" -> "/D:/Workspace"
            value.startsWith("/workspace/") -> "/D:/Workspace/" + value.removePrefix("/workspace/")
            value.startsWith("workspace/") -> "/D:/Workspace/" + value.removePrefix("workspace/")
            else -> value
        }
    }

    private fun normalizeTarget(raw: String): String {
        val value = raw.trim().lowercase().ifBlank { "universal" }
        require(TARGETS.contains(value)) { "RiftBuild target must be arm32, arm64 or universal" }
        return value
    }


    private fun projectFile(ref: ProjectRef, relative: String): File {
        require(safeZipPath(relative)) { "Unsafe project-relative RiftBuild path" }
        val file = File(ref.file, relative).canonicalFile
        require(confinedTo(ref.file, file)) { "RiftBuild project-relative path escaped project root" }
        return file
    }

    private fun projectDisplay(ref: ProjectRef, file: File): String {
        val canonical = file.canonicalFile
        require(confinedTo(ref.file, canonical)) { "RiftBuild display path escaped project root" }
        val relative = canonical.relativeTo(ref.file).invariantSeparatorsPath
        return if (relative.isBlank()) ref.display else ref.display + "/" + relative
    }

    private fun hasPreparedNative(prepared: JSONObject, target: String): Boolean {
        val arm64 = prepared.optJSONArray("arm64Libraries")?.length() ?: 0
        val arm32 = prepared.optJSONArray("arm32Libraries")?.length() ?: 0
        return when (target) {
            "arm64" -> arm64 > 0
            "arm32" -> arm32 > 0
            else -> arm64 > 0 && arm32 > 0
        }
    }







    private fun inspectPrepared(ref: ProjectRef, target: String, requiresDex: Boolean = false): JSONObject {
        val prepared = File(ref.file, "build/riftbuild/prepared").canonicalFile
        if (!prepared.isDirectory || !confinedTo(ref.file, prepared)) {
            return JSONObject()
                .put("ready", false)
                .put("root", ref.display + "/build/riftbuild/prepared")
                .put("blockers", JSONArray().put("prepared directory missing"))
        }
        val manifest = File(prepared, "AndroidManifest.xml")
        val arm64 = nativeLibraries(prepared, "arm64-v8a")
        val arm32 = nativeLibraries(prepared, "armeabi-v7a")
        val dexFiles = prepared.listFiles()
            ?.filter { it.isFile && DEX_ENTRY.matches(it.name) }
            ?.sortedBy { dexEntryOrder(it.name) }
            .orEmpty()
        val blockers = JSONArray()
        if (!isBinaryAndroidManifest(manifest)) blockers.put("AndroidManifest.xml must be compiled Android binary XML")
        if ((target == "arm64" || target == "universal") && arm64.isEmpty()) blockers.put("arm64-v8a native library missing")
        if ((target == "arm32" || target == "universal") && arm32.isEmpty()) blockers.put("armeabi-v7a native library missing")
        if (requiresDex && dexFiles.none { it.name == "classes.dex" }) blockers.put("classes.dex missing for code-bearing Activity package")
        return JSONObject()
            .put("ready", blockers.length() == 0)
            .put("root", ref.display + "/build/riftbuild/prepared")
            .put("binaryManifest", isBinaryAndroidManifest(manifest))
            .put("requiresDex", requiresDex)
            .put("dexFiles", JSONArray(dexFiles.map { it.name }))
            .put("arm64Libraries", JSONArray(arm64.map { it.name }))
            .put("arm32Libraries", JSONArray(arm32.map { it.name }))
            .put("blockers", blockers)
    }

    private fun collectPreparedEntries(prepared: File, target: String): List<Pair<String, File>> {
        require(prepared.isDirectory) { "prepared package directory missing" }
        val allowedTop = setOf("AndroidManifest.xml", "resources.arsc", "lib", "assets")
        prepared.listFiles()?.forEach { entry ->
            require(
                allowedTop.contains(entry.name) ||
                    (entry.isFile && DEX_ENTRY.matches(entry.name))
            ) {
                "unsupported prepared APK input: " + entry.name
            }
        }

        val out = ArrayList<Pair<String, File>>()
        val manifest = File(prepared, "AndroidManifest.xml")
        require(isBinaryAndroidManifest(manifest)) { "AndroidManifest.xml must be compiled Android binary XML" }
        out += "AndroidManifest.xml" to manifest
        prepared.listFiles()
            ?.filter { it.isFile && DEX_ENTRY.matches(it.name) }
            ?.sortedBy { dexEntryOrder(it.name) }
            ?.forEach { dex -> out += dex.name to dex }
        File(prepared, "resources.arsc").takeIf { it.isFile }?.let { out += "resources.arsc" to it }

        val abis = when (target) {
            "arm64" -> listOf("arm64-v8a")
            "arm32" -> listOf("armeabi-v7a")
            else -> listOf("arm64-v8a", "armeabi-v7a")
        }
        for (abi in abis) {
            val libs = nativeLibraries(prepared, abi)
            require(libs.isNotEmpty()) { abi + " native library missing" }
            libs.sortedBy { it.name }.forEach { lib ->
                require(lib.name.endsWith(".so") && SAFE_SEGMENT.matches(lib.name)) { "unsafe native library name" }
                out += "lib/" + abi + "/" + lib.name to lib
            }
        }

        val assets = File(prepared, "assets")
        if (assets.isDirectory) {
            assets.walkTopDown().filter { it.isFile }.forEach { file ->
                RiftDeadline.check("RiftBuild prepared assets")
                require(confinedTo(assets, file)) { "prepared asset escaped root" }
                val relative = file.relativeTo(assets).invariantSeparatorsPath
                require(safeZipPath(relative)) { "unsafe prepared asset path: " + relative }
                out += "assets/" + relative to file
            }
        }
        return out
    }

    private fun dexEntryOrder(name: String): Int =
        if (name == "classes.dex") 1
        else name.removePrefix("classes").removeSuffix(".dex").toIntOrNull()
            ?: Int.MAX_VALUE

    private fun nativeLibraries(prepared: File, abi: String): List<File> {
        val dir = File(prepared, "lib/" + abi).canonicalFile
        if (!dir.isDirectory || !confinedTo(prepared, dir)) return emptyList()
        return dir.listFiles()?.filter { it.isFile && it.extension == "so" && confinedTo(dir, it) }.orEmpty()
    }

    private fun isBinaryAndroidManifest(file: File): Boolean {
        if (!file.isFile || file.length() < 8L || file.length() > Int.MAX_VALUE.toLong()) return false
        val header = ByteArray(8)
        file.inputStream().use { if (it.read(header) != 8) return false }
        val type = (header[0].toInt() and 0xff) or ((header[1].toInt() and 0xff) shl 8)
        val headerSize = (header[2].toInt() and 0xff) or ((header[3].toInt() and 0xff) shl 8)
        val declaredSize =
            (header[4].toInt() and 0xff) or
                ((header[5].toInt() and 0xff) shl 8) or
                ((header[6].toInt() and 0xff) shl 16) or
                ((header[7].toInt() and 0xff) shl 24)
        return type == XML_TYPE && headerSize == 8 && declaredSize == file.length().toInt()
    }

    private fun blockedRun(ref: ProjectRef, target: String, currentPlan: JSONObject): JSONObject {
        val value = JSONObject()
            .put("format", "riftbuild-native-run-v1")
            .put("id", runId())
            .put("state", "blocked")
            .put("project", ref.display)
            .put("projectSha256", currentPlan.optString("projectSha256"))
            .put("target", target)
            .put("plan", currentPlan)
            .put("blockers", JSONArray()
                .put("prepared native ELF and/or Android binary manifest is incomplete")
                .put("APK signing is pending"))
            .put("at", System.currentTimeMillis())
        writeRun(value)
        return value
    }

    private fun writeRun(value: JSONObject) {
        val id = value.optString("runId").ifBlank { value.optString("id") }.ifBlank { runId() }
        atomicWrite(File(runRoot, safeName(id) + ".json"), value.toString(2).toByteArray(Charsets.UTF_8))
        runRoot.listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_RUNS)
            ?.forEach { it.delete() }
    }

    private fun stage(id: String, state: String, blocker: String?): JSONObject =
        JSONObject().put("id", id).put("state", state).put("blocker", blocker ?: JSONObject.NULL)

    private fun artifactProjectRoot(ref: ProjectRef): File {
        val key = safeName(ref.file.name) + "-" + sha256(ref.display.toByteArray(Charsets.UTF_8)).take(8)
        val root = File(artifactRoot, key).canonicalFile
        require(confinedTo(artifactRoot, root)) { "RiftBuild artifact root escaped D:/Builds" }
        return root
    }

    private fun artifactProjectDisplay(ref: ProjectRef): String = artifactDisplay(artifactProjectRoot(ref))

    private fun resolveArtifact(raw: String): File {
        require(raw.isNotBlank()) { "RiftBuild artifact path is required" }
        val value = raw.trim().replace('\\', '/')
        val absolute = when {
            value == "/D:/Builds" || value.startsWith("/D:/Builds/") -> value
            value == "D:/Builds" || value.startsWith("D:/Builds/") -> "/" + value
            else -> "/D:/Builds/" + value.trimStart('/')
        }
        val display = RiftVolumePaths.normalizeDisplay(absolute)
        require(display.startsWith("/D:/Builds/")) { "RiftBuild artifact must live under D:/Builds" }
        val relative = display.removePrefix("/D:/Builds/").trim('/')
        require(relative.isNotBlank()) { "RiftBuild artifact file is required" }
        val file = File(artifactRoot, relative).canonicalFile
        require(confinedTo(artifactRoot, file)) { "RiftBuild artifact escaped D:/Builds" }
        require(file.isFile) { "RiftBuild artifact not found: " + display }
        return file
    }

    private fun artifactDisplay(file: File): String {
        val canonical = file.canonicalFile
        require(confinedTo(artifactRoot, canonical)) { "artifact escaped D:/Builds" }
        val relative = canonical.relativeTo(artifactRoot).invariantSeparatorsPath
        return if (relative.isBlank()) "/D:/Builds" else "/D:/Builds/" + relative
    }

    private fun firstExisting(root: File, vararg paths: String): File? =
        paths.asSequence().map { File(root, it) }.firstOrNull { it.isFile }

    private fun readTextBounded(file: File): String {
        require(file.isFile) { "file not found" }
        require(file.length() <= MAX_TEXT_BYTES) { "text file exceeds RiftBuild limit" }
        return file.readText(Charsets.UTF_8)
    }

    private fun safeZipPath(raw: String): Boolean {
        val value = raw.replace('\\', '/')
        return value.isNotBlank() && !value.startsWith("/") &&
            !Regex("^[A-Za-z]:").containsMatchIn(value) &&
            value.split('/').all { it.isNotBlank() && it != "." && it != ".." && SAFE_SEGMENT.matches(it) }
    }

    private fun safeName(raw: String): String {
        val clean = raw.replace(Regex("[^A-Za-z0-9._+-]"), "-").trim('-').take(120)
        require(clean.isNotBlank()) { "RiftBuild name is empty after normalization" }
        return clean
    }

    private fun confinedTo(root: File, child: File): Boolean {
        val a = root.canonicalFile
        val b = child.canonicalFile
        return b == a || b.path.startsWith(a.path + File.separator)
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "." + target.name + ".riftbuild-" + System.nanoTime() + ".tmp")
        temp.writeBytes(bytes)
        if (target.exists()) require(target.delete()) { "could not replace build record" }
        require(temp.renameTo(target)) { "could not publish build record" }
    }

    private fun treeSha256(root: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        root.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.forEach { file ->
            RiftDeadline.check("RiftBuild tree hash")
            digest.update(file.relativeTo(root).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    RiftDeadline.check("RiftBuild tree hash")
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) digest.update(buffer, 0, read)
                }
            }
            digest.update(0.toByte())
        }
        return hex(digest.digest())
    }

    private fun deleteTreeBounded(root: File, maxEntries: Int): Boolean {
        var entries = 0
        fun remove(node: File): Boolean {
            RiftDeadline.check("RiftBuild cleanup")
            require(++entries <= maxEntries) { "RiftBuild cleanup exceeds $maxEntries entries" }
            if (node.isDirectory) {
                val children = node.listFiles()
                    ?: throw IllegalStateException("Could not read RiftBuild cleanup directory")
                children.forEach { child ->
                    require(remove(child)) { "Could not delete RiftBuild cleanup entry" }
                }
            }
            return node.delete()
        }
        return !root.exists() || remove(root)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                RiftDeadline.check("RiftBuild file hash")
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    private fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) }
    private fun runId(): String = "build-" + System.currentTimeMillis() + "-" + java.lang.Long.toHexString(System.nanoTime()).takeLast(10)
}
