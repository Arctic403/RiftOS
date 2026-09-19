package com.riftos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Workspace-bounded native RiftBuild controller.
 *
 * No raw process execution lives here. v0.1 validates Android projects, records bounded plans/runs,
 * and packages already-prepared binary Android artifacts into an unsigned APK under D:/Builds.
 */
class RiftBuildLocalExecutor(context: Context) {
    data class CommandResult(val output: String, val value: JSONObject)
    private data class ProjectRef(val display: String, val file: File)
    private data class RiftppV0Image(
        val key: String,
        val abi: String,
        val elfClass: Int,
        val machine: Int,
        val sourcePath: String,
        val sourceSha256: String,
        val rawSha256: String,
        val canonicalValueSha256: String,
        val bytes: ByteArray
    )
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
        private const val RIFTPP_V0_SCHEMA = "riftpp-direct-elf-shared-v0-bytes/1"
        private const val RIFTPP_V0_BRIDGE = "compiler/native_backend/evidence/DIRECT-ELF-SHARED-V0-BYTES.json"
        private const val RIFTPP_V0_WRITER = "compiler/native_backend/elf_shared_v0.riftpp"
        private const val RIFTPP_V0_APK_PROJECT = "apk-proof"
        private const val RIFTPP_V0_LIBRARY = "libriftpp_nativeproof.so"
        private const val RIFTPP_V0_MANIFEST_SOURCE = "apk-proof/app/src/main/AndroidManifest.xml"
        private const val RIFTPP_V0_MANIFEST_SOURCE_SHA = "eb0e8b7f3020499b50b135d1ef93c60af89f997c7c1984ec3d17d32c1595a6c1"
        private const val RIFTPP_V0_APP_GRADLE = "apk-proof/app/build.gradle.kts"
        private const val RIFTPP_V0_APP_GRADLE_SHA = "1d1739a07896c4d7f1e521fa154a0285c5c4eefe87eab718830fee37194c0765"
        private const val RIFTPP_V0_BINARY_MANIFEST_BYTES = 1440
        private const val RIFTPP_V0_BINARY_MANIFEST_SHA = "ac035bb5bf89f55a3f34bae8eea980108324d2f36333f1e708f8a0b82af8e7c2"
        private const val XML_NO_INDEX = -1
        private const val XML_STRING_POOL_TYPE = 0x0001
        private const val XML_TYPE = 0x0003
        private const val XML_START_NAMESPACE_TYPE = 0x0100
        private const val XML_END_NAMESPACE_TYPE = 0x0101
        private const val XML_START_ELEMENT_TYPE = 0x0102
        private const val XML_END_ELEMENT_TYPE = 0x0103
        private const val XML_RESOURCE_MAP_TYPE = 0x0180
        private const val XML_UTF8_FLAG = 0x00000100
        private const val XML_VALUE_STRING = 0x03
        private const val XML_VALUE_INT_DEC = 0x10
        private const val XML_VALUE_INT_BOOLEAN = 0x12
        private val RIFTPP_V0_MANIFEST_STRINGS = listOf(
            "name", "hasCode", "exported", "value", "minSdkVersion", "versionCode", "versionName", "targetSdkVersion",
            "android", "http://schemas.android.com/apk/res/android", "manifest", "package", "com.riftpp.nativeproof", "1",
            "0.1.0-native-proof", "uses-sdk", "26", "36", "application", "false", "activity",
            "android.app.NativeActivity", "true", "meta-data", "android.app.lib_name", "riftpp_nativeproof",
            "intent-filter", "action", "android.intent.action.MAIN", "category", "android.intent.category.LAUNCHER"
        )
        private val RIFTPP_V0_MANIFEST_RESOURCE_IDS = intArrayOf(
            0x01010003, 0x0101000c, 0x01010010, 0x01010024,
            0x0101020c, 0x0101021b, 0x0101021c, 0x01010270
        )
        private val TARGETS = setOf("arm32", "arm64", "universal")
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
        private val SAFE_SEGMENT = Regex("^[A-Za-z0-9._+-]{1,120}$")
    }

    private val appContext = context.applicationContext
    private val riftRoot = File(appContext.filesDir, "riftfs").apply { mkdirs() }.canonicalFile
    private val workspaceRoot = File(riftRoot, "workspace").apply { mkdirs() }.canonicalFile
    private val runRoot = File(riftRoot, "system/riftbuild/v1/runs").apply { mkdirs() }.canonicalFile
    private val artifactRoot = File(riftRoot, "documents/builds").apply { mkdirs() }.canonicalFile

    fun executeShell(args: MutableList<String>, cwd: String): CommandResult {
        val sub = args.removeFirstOrNull()?.lowercase() ?: "doctor"
        val value = when (sub) {
            "help" -> JSONObject()
                .put("schema", "riftbuild-native-help-v1")
                .put("usage", "riftbuild doctor [project] | validate <project> | plan <project> [arm32|arm64|universal] | prepare-riftpp-v0 <riftpp-root> [target] | pack <project> [target] | runs [limit] | artifacts [project]")
            "doctor" -> doctor(args.firstOrNull(), cwd)
            "validate" -> validate(args.firstOrNull() ?: error("usage: riftbuild validate <project>"), cwd)
            "plan" -> plan(
                args.firstOrNull() ?: error("usage: riftbuild plan <project> [arm32|arm64|universal]"),
                args.getOrNull(1) ?: "universal",
                cwd
            )
            "prepare-riftpp-v0" -> prepareRiftppV0(
                args.firstOrNull() ?: error("usage: riftbuild prepare-riftpp-v0 <riftpp-root> [arm32|arm64|universal]"),
                args.getOrNull(1) ?: "universal",
                cwd
            )
            "pack" -> pack(
                args.firstOrNull() ?: error("usage: riftbuild pack <project> [arm32|arm64|universal]"),
                args.getOrNull(1) ?: "universal",
                cwd
            )
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
        return JSONObject()
            .put("schema", "riftbuild-native-doctor-v1")
            .put("available", true)
            .put("nativeExecutor", true)
            .put("rawProcessExecution", false)
            .put("arbitraryShell", false)
            .put("workspaceOnly", true)
            .put("sourceValidationReady", true)
            .put("preparedArtifactPackagerReady", true)
            .put("packReady", packReady)
            .put("compileReady", false)
            .put("signingReady", false)
            .put("installReady", false)
            .put("ready", false)
            .put("project", projectValue ?: JSONObject.NULL)
            .put("artifactRoot", "/D:/Builds")
            .put("blockers", JSONArray()
                .put("android-binary-manifest: prepared Android binary manifest is still required before packaging")
                .put("apk-signing: bounded in-process signing is pending")
                .put("package-install: explicit PackageInstaller ownership is pending"))
    }

    fun validate(project: String, cwd: String = "/D:/Workspace"): JSONObject {
        val ref = resolveProject(project, cwd)
        require(ref.file.isDirectory) { "Build project is not a directory: " + ref.display }

        var files = 0
        var bytes = 0L
        ref.file.walkTopDown().forEach { file ->
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

        check("settings", settings != null, settings?.name ?: "missing settings.gradle(.kts)")
        check("root-gradle", rootGradle != null, rootGradle?.name ?: "missing build.gradle(.kts)")
        check("app-gradle", appGradle != null, appGradle?.relativeTo(ref.file)?.invariantSeparatorsPath ?: "missing app/build.gradle(.kts)")
        check("manifest", manifest.isFile, if (manifest.isFile) "app/src/main/AndroidManifest.xml" else "missing AndroidManifest.xml")

        var nativeActivity = false
        var nativeLibraryName = ""
        if (manifest.isFile) {
            val text = readTextBounded(manifest)
            nativeActivity = text.contains("android.app.NativeActivity")
            nativeLibraryName = Regex("android\.app\.lib_name[\\s\\S]*?android:value\\s*=\\s*[\"']([^\"']+)[\"']")
                .find(text)?.groupValues?.getOrNull(1).orEmpty()
            check("native-activity", nativeActivity, if (nativeActivity) "NativeActivity declared" else "NativeActivity missing")
            check("native-library-name", nativeLibraryName.isNotBlank(), if (nativeLibraryName.isBlank()) "android.app.lib_name missing" else nativeLibraryName)
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
        val prepared = inspectPrepared(ref, "universal")
        return JSONObject()
            .put("schema", "riftbuild-native-validation-v1")
            .put("project", ref.display)
            .put("projectName", ref.file.name)
            .put("projectSha256", treeSha256(ref.file))
            .put("files", files)
            .put("bytes", bytes)
            .put("sourceReady", sourceReady)
            .put("androidGradleProject", settings != null && rootGradle != null && appGradle != null && manifest.isFile)
            .put("nativeActivity", nativeActivity)
            .put("nativeLibraryName", nativeLibraryName)
            .put("riftNativeProof", riftNativeProof)
            .put("checks", checks)
            .put("preparedPackageReady", prepared.optBoolean("ready"))
            .put("prepared", prepared)
    }

    fun plan(project: String, target: String = "universal", cwd: String = "/D:/Workspace"): JSONObject {
        val normalizedTarget = normalizeTarget(target)
        val validation = validate(project, cwd)
        val ref = resolveProject(project, cwd)
        val prepared = inspectPrepared(ref, normalizedTarget)
        val sourceReady = validation.optBoolean("sourceReady")
        val packReady = sourceReady && prepared.optBoolean("ready")
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
                .put(stage("riftpp-ir-backend", "reference-ready", "existing Rift++ proof surface"))
                .put(stage(
                    "direct-elf-shared-object",
                    if (hasPreparedNative(prepared, normalizedTarget)) "prepared" else "blocked",
                    if (hasPreparedNative(prepared, normalizedTarget)) null else "run prepare-riftpp-v0 for the current Rift++ V0 proof"
                ))
                .put(stage("apk-package", if (packReady) "ready" else "blocked", if (packReady) null else "prepared binary Android artifacts incomplete"))
                .put(stage("apk-signing", "blocked", "bounded in-process signer pending"))
                .put(stage("artifact-verification", "partial", "ZIP + SHA receipt only"))
                .put(stage("package-install", "blocked", "PackageInstaller owner pending")))
    }


    fun prepare(args: JSONObject, cwd: String = "/D:/Workspace"): JSONObject {
        require(!args.has("command") && !args.has("shell") && !args.has("exec")) { "RiftBuild does not accept raw commands" }
        val kind = args.optString("kind", "riftpp-v0")
        require(kind == "riftpp-v0") { "build.prepare kind must be riftpp-v0" }
        val project = args.optString("project").ifBlank { args.optString("projectPath") }
        require(project.isNotBlank()) { "build.prepare requires the Rift++ project root" }
        return prepareRiftppV0(project, args.optString("target", "universal"), cwd)
    }

    @Synchronized
    fun prepareRiftppV0(project: String, target: String = "universal", cwd: String = "/D:/Workspace"): JSONObject {
        val normalizedTarget = normalizeTarget(target)
        val ref = resolveProject(project, cwd)

        verifyProjectSource(ref, RIFTPP_V0_MANIFEST_SOURCE, RIFTPP_V0_MANIFEST_SOURCE_SHA)
        verifyProjectSource(ref, RIFTPP_V0_APP_GRADLE, RIFTPP_V0_APP_GRADLE_SHA)
        val binaryManifest = buildRiftppV0BinaryManifest()

        val bridgeFile = projectFile(ref, RIFTPP_V0_BRIDGE)
        require(bridgeFile.isFile) { "Rift++ direct-ELF V0 bridge artifact is missing" }
        val bridge = JSONObject(readTextBounded(bridgeFile))
        require(bridge.optString("schema") == RIFTPP_V0_SCHEMA) { "Unsupported Rift++ direct-ELF V0 bridge schema" }

        val writer = bridge.optJSONObject("writer") ?: error("Rift++ V0 bridge writer identity is missing")
        require(writer.optString("path") == RIFTPP_V0_WRITER) { "Rift++ V0 bridge writer path drift" }
        verifyProjectSource(ref, writer.optString("path"), writer.optString("sha256"))

        val exports = bridge.optJSONObject("exports") ?: error("Rift++ V0 bridge exports are missing")
        val selected = when (normalizedTarget) {
            "arm64" -> listOf(readRiftppV0Image(ref, exports, "aarch64", "arm64-v8a", 2, 183))
            "arm32" -> listOf(readRiftppV0Image(ref, exports, "armv7", "armeabi-v7a", 1, 40))
            else -> listOf(
                readRiftppV0Image(ref, exports, "aarch64", "arm64-v8a", 2, 183),
                readRiftppV0Image(ref, exports, "armv7", "armeabi-v7a", 1, 40)
            )
        }

        val apkProject = projectFile(ref, RIFTPP_V0_APK_PROJECT)
        require(apkProject.isDirectory) { "Rift++ apk-proof project is missing" }
        val buildRoot = File(apkProject, "build/riftbuild").canonicalFile
        require(confinedTo(apkProject, buildRoot)) { "RiftBuild V0 build root escaped apk-proof" }
        val preparedRoot = File(buildRoot, "prepared").canonicalFile
        val libRoot = File(preparedRoot, "lib").canonicalFile
        require(confinedTo(buildRoot, preparedRoot)) { "RiftBuild prepared root escaped build/riftbuild" }
        require(confinedTo(buildRoot, libRoot)) { "RiftBuild prepared library root escaped build/riftbuild" }
        require(preparedRoot.mkdirs() || preparedRoot.isDirectory) { "Could not create prepared package root" }

        val manifestOutput = File(preparedRoot, "AndroidManifest.xml").canonicalFile
        require(confinedTo(preparedRoot, manifestOutput)) { "RiftBuild binary manifest escaped prepared root" }
        atomicWrite(manifestOutput, binaryManifest)
        require(manifestOutput.length() == RIFTPP_V0_BINARY_MANIFEST_BYTES.toLong()) { "Materialized binary manifest byte count drift" }
        require(sha256(manifestOutput) == RIFTPP_V0_BINARY_MANIFEST_SHA) { "Materialized binary manifest SHA-256 drift" }
        require(isBinaryAndroidManifest(manifestOutput)) { "Materialized AndroidManifest.xml failed binary XML validation" }

        if (libRoot.exists()) require(libRoot.deleteRecursively()) { "Could not clear stale prepared native libraries" }
        require(libRoot.mkdirs() || libRoot.isDirectory) { "Could not create prepared native library root" }

        val outputs = JSONArray()
        for (image in selected) {
            val abiRoot = File(libRoot, image.abi).canonicalFile
            require(confinedTo(libRoot, abiRoot)) { "RiftBuild ABI output escaped prepared/lib" }
            require(abiRoot.mkdirs() || abiRoot.isDirectory) { "Could not create ABI output directory" }
            val output = File(abiRoot, RIFTPP_V0_LIBRARY).canonicalFile
            require(confinedTo(abiRoot, output)) { "RiftBuild ELF output escaped ABI directory" }
            atomicWrite(output, image.bytes)
            require(output.length() == image.bytes.size.toLong()) { "Materialized ELF byte count drift" }
            val materializedSha = sha256(output)
            require(materializedSha == image.rawSha256) { "Materialized ELF SHA-256 drift" }

            outputs.put(JSONObject()
                .put("key", image.key)
                .put("abi", image.abi)
                .put("path", projectDisplay(ref, output))
                .put("bytes", image.bytes.size)
                .put("rawSha256", materializedSha)
                .put("canonicalValueSha256", image.canonicalValueSha256)
                .put("elfClass", image.elfClass)
                .put("machine", image.machine))
        }

        val runId = runId()
        val result = JSONObject()
            .put("format", "riftbuild-riftpp-v0-materialization-v1")
            .put("runId", runId)
            .put("state", "prepared-native")
            .put("project", ref.display)
            .put("androidProject", ref.display + "/" + RIFTPP_V0_APK_PROJECT)
            .put("target", normalizedTarget)
            .put("bridge", ref.display + "/" + RIFTPP_V0_BRIDGE)
            .put("bridgeSha256", sha256(bridgeFile))
            .put("libraryName", RIFTPP_V0_LIBRARY)
            .put("outputs", outputs)
            .put("manifest", JSONObject()
                .put("path", projectDisplay(ref, manifestOutput))
                .put("bytes", manifestOutput.length())
                .put("sha256", sha256(manifestOutput))
                .put("sourceSha256", RIFTPP_V0_MANIFEST_SOURCE_SHA)
                .put("gradleSha256", RIFTPP_V0_APP_GRADLE_SHA))
            .put("manifestReady", true)
            .put("signed", false)
            .put("installableClaimed", false)
            .put("createdAt", System.currentTimeMillis())

        atomicWrite(
            File(buildRoot, "riftpp-v0-materialization.json"),
            result.toString(2).toByteArray(Charsets.UTF_8)
        )
        writeRun(result)
        return result
    }

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
                    source.inputStream().buffered().use { it.copyTo(zip) }
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
                .put("APK signing is not implemented yet")
                .put("device install/launch proof is not implemented yet"))
            .put("createdAt", System.currentTimeMillis())
        atomicWrite(File(outDir, "receipt.json"), receipt.toString(2).toByteArray(Charsets.UTF_8))
        writeRun(receipt)
        return receipt
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


    private fun readRiftppV0Image(
        ref: ProjectRef,
        exports: JSONObject,
        key: String,
        expectedAbi: String,
        expectedClass: Int,
        expectedMachine: Int
    ): RiftppV0Image {
        val value = exports.optJSONObject(key) ?: error("Rift++ V0 export is missing: " + key)
        require(value.optString("abi") == expectedAbi) { "Rift++ V0 ABI drift: " + key }
        require(value.optInt("elfClass") == if (expectedClass == 2) 64 else 32) { "Rift++ V0 ELF class metadata drift: " + key }
        require(value.optInt("machine") == expectedMachine) { "Rift++ V0 machine metadata drift: " + key }

        val sourcePath = value.optString("path")
        val sourceSha = value.optString("sourceSha256")
        verifyProjectSource(ref, sourcePath, sourceSha)

        val data = value.optJSONArray("data") ?: error("Rift++ V0 byte array is missing: " + key)
        val declaredBytes = value.optInt("bytes", -1)
        require(declaredBytes == data.length()) { "Rift++ V0 byte-count metadata drift: " + key }
        require(declaredBytes in 1..MAX_TEXT_BYTES.toInt()) { "Rift++ V0 byte array exceeds bounded bridge limit" }

        val bytes = ByteArray(data.length())
        for (index in 0 until data.length()) {
            val number = data.optInt(index, -1)
            require(number in 0..255) { "Rift++ V0 bridge byte outside 0..255 at " + key + "[" + index + "]" }
            bytes[index] = number.toByte()
        }

        val rawSha = value.optString("rawSha256")
        require(SHA256_HEX.matches(rawSha)) { "Rift++ V0 raw SHA-256 metadata is invalid: " + key }
        require(sha256(bytes) == rawSha) { "Rift++ V0 raw SHA-256 mismatch: " + key }

        val canonical = value.optString("canonicalValueSha256")
        require(SHA256_HEX.matches(canonical)) { "Rift++ V0 canonical value SHA-256 metadata is invalid: " + key }

        verifyElfImage(bytes, expectedClass, expectedMachine)
        return RiftppV0Image(
            key = key,
            abi = expectedAbi,
            elfClass = if (expectedClass == 2) 64 else 32,
            machine = expectedMachine,
            sourcePath = sourcePath,
            sourceSha256 = sourceSha,
            rawSha256 = rawSha,
            canonicalValueSha256 = canonical,
            bytes = bytes
        )
    }

    private fun verifyProjectSource(ref: ProjectRef, relative: String, expectedSha256: String) {
        require(safeZipPath(relative)) { "Unsafe Rift++ V0 source path" }
        require(SHA256_HEX.matches(expectedSha256)) { "Invalid Rift++ V0 source SHA-256" }
        val source = projectFile(ref, relative)
        require(source.isFile) { "Rift++ V0 source file is missing: " + relative }
        require(sha256(source) == expectedSha256) { "Rift++ V0 source identity drift: " + relative }
    }

    private fun verifyElfImage(bytes: ByteArray, expectedClass: Int, expectedMachine: Int) {
        require(bytes.size >= 40) { "Rift++ V0 ELF image is truncated" }
        require(
            (bytes[0].toInt() and 0xff) == 0x7f &&
                bytes[1].toInt() == 'E'.code &&
                bytes[2].toInt() == 'L'.code &&
                bytes[3].toInt() == 'F'.code
        ) { "Rift++ V0 ELF magic mismatch" }
        require((bytes[4].toInt() and 0xff) == expectedClass) { "Rift++ V0 ELF class mismatch" }
        require((bytes[5].toInt() and 0xff) == 1) { "Rift++ V0 ELF must be little-endian" }
        require((bytes[16].toInt() and 0xff) == 3 && (bytes[17].toInt() and 0xff) == 0) { "Rift++ V0 ELF must be ET_DYN" }
        val machine = (bytes[18].toInt() and 0xff) or ((bytes[19].toInt() and 0xff) shl 8)
        require(machine == expectedMachine) { "Rift++ V0 ELF machine mismatch" }
        if (expectedClass == 1 && expectedMachine == 40) {
            require((bytes[39].toInt() and 0xff) == 5) { "Rift++ V0 ARM ELF must declare EABI5" }
        }
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

    private fun buildRiftppV0BinaryManifest(): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(buildManifestStringPool())
        body.write(buildManifestResourceMap())
        body.write(buildManifestNamespace(XML_START_NAMESPACE_TYPE))

        body.write(buildManifestStartElement(
            "manifest",
            listOf(
                manifestStringAttr("package", "com.riftpp.nativeproof", XML_NO_INDEX),
                manifestIntAttr("versionCode", "1", 1),
                manifestStringAttr("versionName", "0.1.0-native-proof")
            )
        ))
        body.write(buildManifestStartElement(
            "uses-sdk",
            listOf(
                manifestIntAttr("minSdkVersion", "26", 26),
                manifestIntAttr("targetSdkVersion", "36", 36)
            )
        ))
        body.write(buildManifestEndElement("uses-sdk"))
        body.write(buildManifestStartElement(
            "application",
            listOf(manifestBoolAttr("hasCode", "false", false))
        ))
        body.write(buildManifestStartElement(
            "activity",
            listOf(
                manifestStringAttr("name", "android.app.NativeActivity"),
                manifestBoolAttr("exported", "true", true)
            )
        ))
        body.write(buildManifestStartElement(
            "meta-data",
            listOf(
                manifestStringAttr("name", "android.app.lib_name"),
                manifestStringAttr("value", "riftpp_nativeproof")
            )
        ))
        body.write(buildManifestEndElement("meta-data"))
        body.write(buildManifestStartElement("intent-filter", emptyList()))
        body.write(buildManifestStartElement(
            "action",
            listOf(manifestStringAttr("name", "android.intent.action.MAIN"))
        ))
        body.write(buildManifestEndElement("action"))
        body.write(buildManifestStartElement(
            "category",
            listOf(manifestStringAttr("name", "android.intent.category.LAUNCHER"))
        ))
        body.write(buildManifestEndElement("category"))
        body.write(buildManifestEndElement("intent-filter"))
        body.write(buildManifestEndElement("activity"))
        body.write(buildManifestEndElement("application"))
        body.write(buildManifestEndElement("manifest"))
        body.write(buildManifestNamespace(XML_END_NAMESPACE_TYPE))

        val bodyBytes = body.toByteArray()
        val output = ByteArrayOutputStream()
        writeManifestChunkHeader(output, XML_TYPE, 8, 8 + bodyBytes.size)
        output.write(bodyBytes)
        val bytes = output.toByteArray()
        require(bytes.size == RIFTPP_V0_BINARY_MANIFEST_BYTES) { "RiftBuild V0 binary manifest size oracle failed" }
        require(sha256(bytes) == RIFTPP_V0_BINARY_MANIFEST_SHA) { "RiftBuild V0 binary manifest SHA-256 oracle failed" }
        return bytes
    }

    private fun buildManifestStringPool(): ByteArray {
        val offsets = ArrayList<Int>(RIFTPP_V0_MANIFEST_STRINGS.size)
        val data = ByteArrayOutputStream()
        for (value in RIFTPP_V0_MANIFEST_STRINGS) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            require(value.length < 0x80 && bytes.size < 0x80) { "RiftBuild V0 manifest string exceeds one-byte UTF-8 pool length" }
            offsets.add(data.size())
            writeManifestLength8(data, value.length)
            writeManifestLength8(data, bytes.size)
            data.write(bytes)
            data.write(0)
        }
        while (data.size() % 4 != 0) data.write(0)

        val stringsStart = 28 + (RIFTPP_V0_MANIFEST_STRINGS.size * 4)
        val dataBytes = data.toByteArray()
        val output = ByteArrayOutputStream()
        writeManifestChunkHeader(output, XML_STRING_POOL_TYPE, 28, stringsStart + dataBytes.size)
        writeManifestU32(output, RIFTPP_V0_MANIFEST_STRINGS.size)
        writeManifestU32(output, 0)
        writeManifestU32(output, XML_UTF8_FLAG)
        writeManifestU32(output, stringsStart)
        writeManifestU32(output, 0)
        for (offset in offsets) writeManifestU32(output, offset)
        output.write(dataBytes)
        return output.toByteArray()
    }

    private fun buildManifestResourceMap(): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestChunkHeader(output, XML_RESOURCE_MAP_TYPE, 8, 8 + (RIFTPP_V0_MANIFEST_RESOURCE_IDS.size * 4))
        for (id in RIFTPP_V0_MANIFEST_RESOURCE_IDS) writeManifestU32(output, id)
        return output.toByteArray()
    }

    private fun buildManifestNamespace(type: Int): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestNodeHeader(output, type, 24)
        writeManifestU32(output, manifestStringIndex("android"))
        writeManifestU32(output, manifestStringIndex("http://schemas.android.com/apk/res/android"))
        return output.toByteArray()
    }

    private fun buildManifestStartElement(name: String, attrs: List<ManifestAttr>): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestNodeHeader(output, XML_START_ELEMENT_TYPE, 36 + (attrs.size * 20))
        writeManifestU32(output, XML_NO_INDEX)
        writeManifestU32(output, manifestStringIndex(name))
        writeManifestU16(output, 20)
        writeManifestU16(output, 20)
        writeManifestU16(output, attrs.size)
        writeManifestU16(output, 0)
        writeManifestU16(output, 0)
        writeManifestU16(output, 0)
        for (attr in attrs) {
            writeManifestU32(output, attr.namespace)
            writeManifestU32(output, attr.name)
            writeManifestU32(output, attr.rawValue)
            writeManifestU16(output, 8)
            output.write(0)
            output.write(attr.dataType)
            writeManifestU32(output, attr.data)
        }
        return output.toByteArray()
    }

    private fun buildManifestEndElement(name: String): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestNodeHeader(output, XML_END_ELEMENT_TYPE, 24)
        writeManifestU32(output, XML_NO_INDEX)
        writeManifestU32(output, manifestStringIndex(name))
        return output.toByteArray()
    }

    private fun manifestStringAttr(name: String, value: String, namespace: Int = manifestStringIndex("http://schemas.android.com/apk/res/android")): ManifestAttr =
        ManifestAttr(namespace, manifestStringIndex(name), manifestStringIndex(value), XML_VALUE_STRING, manifestStringIndex(value))

    private fun manifestIntAttr(name: String, rawValue: String, value: Int): ManifestAttr =
        ManifestAttr(
            manifestStringIndex("http://schemas.android.com/apk/res/android"),
            manifestStringIndex(name),
            manifestStringIndex(rawValue),
            XML_VALUE_INT_DEC,
            value
        )

    private fun manifestBoolAttr(name: String, rawValue: String, value: Boolean): ManifestAttr =
        ManifestAttr(
            manifestStringIndex("http://schemas.android.com/apk/res/android"),
            manifestStringIndex(name),
            manifestStringIndex(rawValue),
            XML_VALUE_INT_BOOLEAN,
            if (value) -1 else 0
        )

    private fun manifestStringIndex(value: String): Int {
        val index = RIFTPP_V0_MANIFEST_STRINGS.indexOf(value)
        require(index >= 0) { "RiftBuild V0 manifest string is not in the frozen pool: " + value }
        return index
    }

    private fun writeManifestNodeHeader(output: ByteArrayOutputStream, type: Int, size: Int) {
        writeManifestChunkHeader(output, type, 16, size)
        writeManifestU32(output, 1)
        writeManifestU32(output, XML_NO_INDEX)
    }

    private fun writeManifestChunkHeader(output: ByteArrayOutputStream, type: Int, headerSize: Int, size: Int) {
        writeManifestU16(output, type)
        writeManifestU16(output, headerSize)
        writeManifestU32(output, size)
    }

    private fun writeManifestLength8(output: ByteArrayOutputStream, value: Int) {
        require(value in 0..0x7f) { "RiftBuild V0 manifest UTF-8 length overflow" }
        output.write(value)
    }

    private fun writeManifestU16(output: ByteArrayOutputStream, value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
    }

    private fun writeManifestU32(output: ByteArrayOutputStream, value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
        output.write((value ushr 16) and 0xff)
        output.write((value ushr 24) and 0xff)
    }

    private fun inspectPrepared(ref: ProjectRef, target: String): JSONObject {
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
        val blockers = JSONArray()
        if (!isBinaryAndroidManifest(manifest)) blockers.put("AndroidManifest.xml must be compiled Android binary XML")
        if ((target == "arm64" || target == "universal") && arm64.isEmpty()) blockers.put("arm64-v8a native library missing")
        if ((target == "arm32" || target == "universal") && arm32.isEmpty()) blockers.put("armeabi-v7a native library missing")
        return JSONObject()
            .put("ready", blockers.length() == 0)
            .put("root", ref.display + "/build/riftbuild/prepared")
            .put("binaryManifest", isBinaryAndroidManifest(manifest))
            .put("arm64Libraries", JSONArray(arm64.map { it.name }))
            .put("arm32Libraries", JSONArray(arm32.map { it.name }))
            .put("blockers", blockers)
    }

    private fun collectPreparedEntries(prepared: File, target: String): List<Pair<String, File>> {
        require(prepared.isDirectory) { "prepared package directory missing" }
        val allowedTop = setOf("AndroidManifest.xml", "resources.arsc", "lib", "assets")
        prepared.listFiles()?.forEach { require(allowedTop.contains(it.name)) { "unsupported prepared APK input: " + it.name } }

        val out = ArrayList<Pair<String, File>>()
        val manifest = File(prepared, "AndroidManifest.xml")
        require(isBinaryAndroidManifest(manifest)) { "AndroidManifest.xml must be compiled Android binary XML" }
        out += "AndroidManifest.xml" to manifest
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
                require(confinedTo(assets, file)) { "prepared asset escaped root" }
                val relative = file.relativeTo(assets).invariantSeparatorsPath
                require(safeZipPath(relative)) { "unsafe prepared asset path: " + relative }
                out += "assets/" + relative to file
            }
        }
        return out
    }

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
            digest.update(file.relativeTo(root).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) digest.update(buffer, 0, read)
                }
            }
            digest.update(0.toByte())
        }
        return hex(digest.digest())
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
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
