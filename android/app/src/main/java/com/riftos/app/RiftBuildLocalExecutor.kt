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
 * No raw process execution lives here. v0.1 validates Android projects, records bounded plans/runs,
 * materializes the fixed Rift++ proof, packages prepared Android artifacts, and routes only bounded
 * D:/Builds APK v2 sign/verify/install-proof operations to dedicated native owners.
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
        private const val MC0_SEED_HEX = "native/mc0/arm32/mc0_seed.hex"
        private const val MC0_SEED_BYTES = 172
        private const val MC0_SEED_SHA256 = "3276dcbf29704b1ba7d9d331e7891ceff10d85b16bb7688c62273aeaa3ca311e"
        private const val MC0_APK_PROJECT = "native/mc0/apk-proof"
        private const val MC0_PACKAGE = "com.codynex.mc0proof"
        private const val MC0_LIBRARY_NAME = "codynex_mc0_host"
        private const val MC0_LIBRARY_FILE = "libcodynex_mc0_host.so"
        private const val MC0_HOST_APK_ENTRY = "lib/armeabi-v7a/libcodynex_mc0_host.so"
        private const val MC0_VERSION_NAME = "0.1.0-mc0-proof"
        private const val MC0_MAX_HOST_BYTES = 4L * 1024L * 1024L
        private const val MC1A_SEED_HEX = "native/mc1/arm32/mc1a_seed.hex"
        private const val MC1A_SEED_BYTES = 236
        private const val MC1A_SEED_SHA256 = "2ef7054e533bfafaefb0fcc14b9cd41cd05aceeec58eeeb335fc6aef4e88ba1a"
        private const val MC1A_APK_PROJECT = "native/mc1/apk-proof"
        private const val MC1A_PACKAGE = "com.codynex.mc1aproof"
        private const val MC1A_LIBRARY_NAME = "codynex_mc1a_host"
        private const val MC1A_LIBRARY_FILE = "libcodynex_mc1a_host.so"
        private const val MC1A_HOST_APK_ENTRY = "lib/armeabi-v7a/libcodynex_mc1a_host.so"
        private const val MC1A_VERSION_NAME = "0.1.0-mc1a-proof"
        private const val MC1A_MAX_HOST_BYTES = 4L * 1024L * 1024L
        private const val MC1B_SEED_HEX = "native/mc1/arm32/mc1b_seed.hex"
        private const val MC1B_SEED_BYTES = 552
        private const val MC1B_SEED_SHA256 = "4f4a7305900547d949831fc4cfc6c6c0f747edd7ab525adfb8a1488a6ca304be"
        private const val MC1B_APK_PROJECT = "native/mc1/apk-proof-b"
        private const val MC1B_PACKAGE = "com.codynex.mc1bproof"
        private const val MC1B_LIBRARY_NAME = "codynex_mc1b_host"
        private const val MC1B_LIBRARY_FILE = "libcodynex_mc1b_host.so"
        private const val MC1B_HOST_APK_ENTRY = "lib/armeabi-v7a/libcodynex_mc1b_host.so"
        private const val MC1B_VERSION_NAME = "0.1.0-mc1b-proof"
        private const val MC1B_MAX_HOST_BYTES = 4L * 1024L * 1024L
        private const val M2_VM0_SEED_HEX = "native/m2/vm0/arm32/vm0_seed.hex"
        private const val M2_VM0_SEED_BYTES = 332
        private const val M2_VM0_SEED_SHA256 = "0577161c8cad09541a998ba44cacd823ce0b0c3a6a5b607960b855483af1dba6"
        private const val M2_VM0_APK_PROJECT = "native/m2/vm0/apk-proof"
        private const val M2_VM0_PACKAGE = "com.codynex.m2vm0proof"
        private const val M2_VM0_LIBRARY_NAME = "codynex_m2_vm0_host"
        private const val M2_VM0_LIBRARY_FILE = "libcodynex_m2_vm0_host.so"
        private const val M2_VM0_HOST_APK_ENTRY = "lib/armeabi-v7a/libcodynex_m2_vm0_host.so"
        private const val M2_VM0_VERSION_NAME = "0.1.0-m2-vm0-proof"
        private const val M2_VM0_MAX_HOST_BYTES = 4L * 1024L * 1024L
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
        private val MC0_MANIFEST_STRINGS = listOf(
            "name", "hasCode", "exported", "value", "minSdkVersion", "versionCode", "versionName", "targetSdkVersion",
            "android", "http://schemas.android.com/apk/res/android", "manifest", "package", MC0_PACKAGE, "1",
            MC0_VERSION_NAME, "uses-sdk", "26", "36", "application", "false", "activity",
            "android.app.NativeActivity", "true", "meta-data", "android.app.lib_name", MC0_LIBRARY_NAME,
            "intent-filter", "action", "android.intent.action.MAIN", "category", "android.intent.category.LAUNCHER"
        )
        private val MC1A_MANIFEST_STRINGS = listOf(
            "name", "hasCode", "exported", "value", "minSdkVersion", "versionCode", "versionName", "targetSdkVersion",
            "android", "http://schemas.android.com/apk/res/android", "manifest", "package", MC1A_PACKAGE, "1",
            MC1A_VERSION_NAME, "uses-sdk", "26", "36", "application", "false", "activity",
            "android.app.NativeActivity", "true", "meta-data", "android.app.lib_name", MC1A_LIBRARY_NAME,
            "intent-filter", "action", "android.intent.action.MAIN", "category", "android.intent.category.LAUNCHER"
        )
        private val MC1B_MANIFEST_STRINGS = listOf(
            "name", "hasCode", "exported", "value", "minSdkVersion", "versionCode", "versionName", "targetSdkVersion",
            "android", "http://schemas.android.com/apk/res/android", "manifest", "package", MC1B_PACKAGE, "1",
            MC1B_VERSION_NAME, "uses-sdk", "26", "36", "application", "false", "activity",
            "android.app.NativeActivity", "true", "meta-data", "android.app.lib_name", MC1B_LIBRARY_NAME,
            "intent-filter", "action", "android.intent.action.MAIN", "category", "android.intent.category.LAUNCHER"
        )
        private val M2_VM0_MANIFEST_STRINGS = listOf(
            "name", "hasCode", "exported", "value", "minSdkVersion", "versionCode", "versionName", "targetSdkVersion",
            "android", "http://schemas.android.com/apk/res/android", "manifest", "package", M2_VM0_PACKAGE, "1",
            M2_VM0_VERSION_NAME, "uses-sdk", "26", "36", "application", "false", "activity",
            "android.app.NativeActivity", "true", "meta-data", "android.app.lib_name", M2_VM0_LIBRARY_NAME,
            "intent-filter", "action", "android.intent.action.MAIN", "category", "android.intent.category.LAUNCHER"
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
    private val apkSigner = RiftApkV2Signer(appContext)
    private val installer = RiftBuildInstaller(appContext)

    fun executeShell(args: MutableList<String>, cwd: String): CommandResult {
        val sub = args.removeFirstOrNull()?.lowercase() ?: "doctor"
        val value = when (sub) {
            "help" -> JSONObject()
                .put("schema", "riftbuild-native-help-v1")
                .put("usage", "riftbuild doctor [project] | validate <project> | plan <project> [arm32|arm64|universal] | prepare-riftpp-v0 <riftpp-root> [target] | prepare-codynex-mc0 <codynex-root> | prepare-codynex-mc1a <codynex-root> | prepare-codynex-mc1b <codynex-root> | prepare-codynex-m2-vm0 <codynex-root> | pack <project> [target] | sign <unsigned-apk> | verify <signed-apk> | install-proof <signed-apk> | install-status | launch-proof | runs [limit] | artifacts [project]")
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
            "prepare-codynex-mc0" -> prepareCodynexMc0(
                args.firstOrNull() ?: error("usage: riftbuild prepare-codynex-mc0 <codynex-root>"),
                cwd
            )
            "prepare-codynex-mc1a" -> prepareCodynexMc1a(
                args.firstOrNull() ?: error("usage: riftbuild prepare-codynex-mc1a <codynex-root>"),
                cwd
            )
            "prepare-codynex-mc1b" -> prepareCodynexMc1b(
                args.firstOrNull() ?: error("usage: riftbuild prepare-codynex-mc1b <codynex-root>"),
                cwd
            )
            "prepare-codynex-m2-vm0" -> prepareCodynexM2Vm0(
                args.firstOrNull() ?: error("usage: riftbuild prepare-codynex-m2-vm0 <codynex-root>"),
                cwd
            )
            "pack" -> pack(
                args.firstOrNull() ?: error("usage: riftbuild pack <project> [arm32|arm64|universal]"),
                args.getOrNull(1) ?: "universal",
                cwd
            )
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
            .put("signingReady", true)
            .put("verificationReady", true)
            .put("installOwnerReady", true)
            .put("installReady", appContext.packageManager.canRequestPackageInstalls())
            .put("ready", false)
            .put("project", projectValue ?: JSONObject.NULL)
            .put("artifactRoot", "/D:/Builds")
            .put("blockers", JSONArray()
                .put("native-compile: general repository native compilation is not wired yet")
                .put("package-install: Android may still require Allow from this source + user confirmation"))
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

        check("settings", settings != null, settings?.name ?: "missing settings.gradle(.kts)")
        check("root-gradle", rootGradle != null, rootGradle?.name ?: "missing build.gradle(.kts)")
        check("app-gradle", appGradle != null, appGradle?.relativeTo(ref.file)?.invariantSeparatorsPath ?: "missing app/build.gradle(.kts)")
        check("manifest", manifest.isFile, if (manifest.isFile) "app/src/main/AndroidManifest.xml" else "missing AndroidManifest.xml")

        var nativeActivity = false
        var nativeLibraryName = ""
        if (manifest.isFile) {
            val text = readTextBounded(manifest)
            nativeActivity = text.contains("android.app.NativeActivity")
            nativeLibraryName = Regex("""android\.app\.lib_name[\s\S]*?android:value\s*=\s*["']([^"']+)["']""")
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
        val nativeLibraryName = validation.optString("nativeLibraryName")
        val prepareHint = when (nativeLibraryName) {
            MC0_LIBRARY_NAME -> "run prepare-codynex-mc0 from the Codynex project root"
            "riftpp_nativeproof" -> "run prepare-riftpp-v0 for the current Rift++ V0 proof"
            else -> "materialize a bounded prepared native package"
        }
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
        val kind = args.optString("kind", "riftpp-v0")
        val project = args.optString("project").ifBlank { args.optString("projectPath") }
        require(project.isNotBlank()) { "build.prepare requires a project root" }
        return when (kind) {
            "riftpp-v0" -> prepareRiftppV0(project, args.optString("target", "universal"), cwd)
            "codynex-mc0" -> prepareCodynexMc0(project, cwd)
            else -> error("build.prepare kind must be riftpp-v0 or codynex-mc0")
        }
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

        if (libRoot.exists()) require(deleteTreeBounded(libRoot, MAX_PROJECT_FILES)) { "Could not clear stale prepared native libraries" }
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

    @Synchronized
    fun prepareCodynexMc0(project: String, cwd: String = "/D:/Workspace"): JSONObject {
        val ref = resolveProject(project, cwd)
        val seedFile = projectFile(ref, MC0_SEED_HEX)
        require(seedFile.isFile) { "Codynex MC0 seed is missing" }
        val seed = decodeHex(readTextBounded(seedFile).trim())
        require(seed.size == MC0_SEED_BYTES) {
            "Codynex MC0 seed byte count drift: " + seed.size
        }
        require(sha256(seed) == MC0_SEED_SHA256) {
            "Codynex MC0 seed SHA-256 drift"
        }

        val apkProject = projectFile(ref, MC0_APK_PROJECT)
        require(apkProject.isDirectory) { "Codynex MC0 apk-proof project is missing" }
        val apkDisplay = projectDisplay(ref, apkProject)
        val sourceValidation = validate(apkDisplay, "/D:/Workspace")
        require(sourceValidation.optBoolean("sourceReady")) {
            "Codynex MC0 apk-proof source validation failed"
        }
        require(sourceValidation.optString("nativeLibraryName") == MC0_LIBRARY_NAME) {
            "Codynex MC0 NativeActivity library declaration drift"
        }

        val sourceManifest = projectFile(
            ref,
            MC0_APK_PROJECT + "/app/src/main/AndroidManifest.xml"
        )
        val sourceManifestText = readTextBounded(sourceManifest)
        require(sourceManifestText.contains("package=\"" + MC0_PACKAGE + "\"")) {
            "Codynex MC0 package declaration drift"
        }
        require(sourceManifestText.contains("android:value=\"" + MC0_LIBRARY_NAME + "\"")) {
            "Codynex MC0 library declaration drift"
        }

        val host = readOwnApkEntry(MC0_HOST_APK_ENTRY, MC0_MAX_HOST_BYTES)
        verifyElfImage(host, 1, 40)

        val buildRoot = File(apkProject, "build/riftbuild").canonicalFile
        require(confinedTo(apkProject, buildRoot)) {
            "Codynex MC0 build root escaped apk-proof"
        }
        val preparedRoot = File(buildRoot, "prepared").canonicalFile
        require(confinedTo(buildRoot, preparedRoot)) {
            "Codynex MC0 prepared root escaped build/riftbuild"
        }
        if (preparedRoot.exists()) {
            require(deleteTreeBounded(preparedRoot, MAX_PROJECT_FILES)) {
                "Could not clear stale Codynex MC0 prepared package"
            }
        }

        val libRoot = File(preparedRoot, "lib/armeabi-v7a").canonicalFile
        val assetRoot = File(preparedRoot, "assets").canonicalFile
        require(confinedTo(preparedRoot, libRoot)) {
            "Codynex MC0 library root escaped prepared package"
        }
        require(confinedTo(preparedRoot, assetRoot)) {
            "Codynex MC0 asset root escaped prepared package"
        }
        require(libRoot.mkdirs() || libRoot.isDirectory) {
            "Could not create Codynex MC0 library directory"
        }
        require(assetRoot.mkdirs() || assetRoot.isDirectory) {
            "Could not create Codynex MC0 asset directory"
        }

        val manifestBytes = buildMc0BinaryManifest()
        val manifestOutput = File(preparedRoot, "AndroidManifest.xml").canonicalFile
        val hostOutput = File(libRoot, MC0_LIBRARY_FILE).canonicalFile
        val seedOutput = File(assetRoot, "mc0_seed.bin").canonicalFile

        atomicWrite(manifestOutput, manifestBytes)
        atomicWrite(hostOutput, host)
        atomicWrite(seedOutput, seed)

        require(isBinaryAndroidManifest(manifestOutput)) {
            "Codynex MC0 binary AndroidManifest.xml failed validation"
        }
        require(sha256(hostOutput) == sha256(host)) {
            "Codynex MC0 host materialization hash mismatch"
        }
        require(sha256(seedOutput) == MC0_SEED_SHA256) {
            "Codynex MC0 seed materialization hash mismatch"
        }

        val runId = runId()
        val result = JSONObject()
            .put("format", "riftbuild-codynex-mc0-materialization-v1")
            .put("runId", runId)
            .put("state", "prepared-native")
            .put("project", ref.display)
            .put("androidProject", apkDisplay)
            .put("target", "arm32")
            .put("package", MC0_PACKAGE)
            .put("libraryName", MC0_LIBRARY_NAME)
            .put("libraryFile", MC0_LIBRARY_FILE)
            .put("hostSource", "self-apk:" + MC0_HOST_APK_ENTRY)
            .put("hostBytes", host.size)
            .put("hostSha256", sha256(host))
            .put("seedSource", projectDisplay(ref, seedFile))
            .put("seedBytes", seed.size)
            .put("seedSha256", sha256(seed))
            .put(
                "manifest",
                JSONObject()
                    .put("path", projectDisplay(ref, manifestOutput))
                    .put("bytes", manifestOutput.length())
                    .put("sha256", sha256(manifestOutput))
            )
            .put("antiContamination", JSONObject()
                .put("hostParsesSource", false)
                .put("hostEmitsInstructions", false)
                .put("compilerAuthority", "assets/mc0_seed.bin"))
            .put("manifestReady", true)
            .put("signed", false)
            .put("installableClaimed", false)
            .put("createdAt", System.currentTimeMillis())

        atomicWrite(
            File(buildRoot, "codynex-mc0-materialization.json"),
            result.toString(2).toByteArray(Charsets.UTF_8)
        )
        writeRun(result)
        return result
    }

fun prepareCodynexMc1a(project: String, cwd: String = "/D:/Workspace"): JSONObject {
        val ref = resolveProject(project, cwd)
        val seedFile = projectFile(ref, MC1A_SEED_HEX)
        require(seedFile.isFile) { "Codynex MC1-A seed is missing" }
        val seed = decodeHex(readTextBounded(seedFile).trim())
        require(seed.size == MC1A_SEED_BYTES) {
            "Codynex MC1-A seed byte count drift: " + seed.size
        }
        require(sha256(seed) == MC1A_SEED_SHA256) {
            "Codynex MC1-A seed SHA-256 drift"
        }

        val apkProject = projectFile(ref, MC1A_APK_PROJECT)
        require(apkProject.isDirectory) { "Codynex MC1-A apk-proof project is missing" }
        val apkDisplay = projectDisplay(ref, apkProject)
        val sourceValidation = validate(apkDisplay, "/D:/Workspace")
        require(sourceValidation.optBoolean("sourceReady")) {
            "Codynex MC1-A apk-proof source validation failed"
        }
        require(sourceValidation.optString("nativeLibraryName") == MC1A_LIBRARY_NAME) {
            "Codynex MC1-A NativeActivity library declaration drift"
        }

        val sourceManifest = projectFile(
            ref,
            MC1A_APK_PROJECT + "/app/src/main/AndroidManifest.xml"
        )
        val sourceManifestText = readTextBounded(sourceManifest)
        require(sourceManifestText.contains("package=\"" + MC1A_PACKAGE + "\"")) {
            "Codynex MC1-A package declaration drift"
        }
        require(sourceManifestText.contains("android:value=\"" + MC1A_LIBRARY_NAME + "\"")) {
            "Codynex MC1-A library declaration drift"
        }

        val host = readOwnApkEntry(MC1A_HOST_APK_ENTRY, MC1A_MAX_HOST_BYTES)
        verifyElfImage(host, 1, 40)

        val buildRoot = File(apkProject, "build/riftbuild").canonicalFile
        require(confinedTo(apkProject, buildRoot)) {
            "Codynex MC1-A build root escaped apk-proof"
        }
        val preparedRoot = File(buildRoot, "prepared").canonicalFile
        require(confinedTo(buildRoot, preparedRoot)) {
            "Codynex MC1-A prepared root escaped build/riftbuild"
        }
        if (preparedRoot.exists()) {
            require(deleteTreeBounded(preparedRoot, MAX_PROJECT_FILES)) {
                "Could not clear stale Codynex MC1-A prepared package"
            }
        }

        val libRoot = File(preparedRoot, "lib/armeabi-v7a").canonicalFile
        val assetRoot = File(preparedRoot, "assets").canonicalFile
        require(confinedTo(preparedRoot, libRoot)) {
            "Codynex MC1-A library root escaped prepared package"
        }
        require(confinedTo(preparedRoot, assetRoot)) {
            "Codynex MC1-A asset root escaped prepared package"
        }
        require(libRoot.mkdirs() || libRoot.isDirectory) {
            "Could not create Codynex MC1-A library directory"
        }
        require(assetRoot.mkdirs() || assetRoot.isDirectory) {
            "Could not create Codynex MC1-A asset directory"
        }

        val manifestBytes = buildMc1aBinaryManifest()
        val manifestOutput = File(preparedRoot, "AndroidManifest.xml").canonicalFile
        val hostOutput = File(libRoot, MC1A_LIBRARY_FILE).canonicalFile
        val seedOutput = File(assetRoot, "mc1a_seed.bin").canonicalFile

        atomicWrite(manifestOutput, manifestBytes)
        atomicWrite(hostOutput, host)
        atomicWrite(seedOutput, seed)

        require(isBinaryAndroidManifest(manifestOutput)) {
            "Codynex MC1-A binary AndroidManifest.xml failed validation"
        }
        require(sha256(hostOutput) == sha256(host)) {
            "Codynex MC1-A host materialization hash mismatch"
        }
        require(sha256(seedOutput) == MC1A_SEED_SHA256) {
            "Codynex MC1-A seed materialization hash mismatch"
        }

        val runId = runId()
        val result = JSONObject()
            .put("format", "riftbuild-codynex-mc1a-materialization-v1")
            .put("runId", runId)
            .put("state", "prepared-native")
            .put("project", ref.display)
            .put("androidProject", apkDisplay)
            .put("target", "arm32")
            .put("package", MC1A_PACKAGE)
            .put("libraryName", MC1A_LIBRARY_NAME)
            .put("libraryFile", MC1A_LIBRARY_FILE)
            .put("hostSource", "self-apk:" + MC1A_HOST_APK_ENTRY)
            .put("hostBytes", host.size)
            .put("hostSha256", sha256(host))
            .put("seedSource", projectDisplay(ref, seedFile))
            .put("seedBytes", seed.size)
            .put("seedSha256", sha256(seed))
            .put(
                "manifest",
                JSONObject()
                    .put("path", projectDisplay(ref, manifestOutput))
                    .put("bytes", manifestOutput.length())
                    .put("sha256", sha256(manifestOutput))
            )
            .put("antiContamination", JSONObject()
                .put("hostParsesSource", false)
                .put("hostEmitsInstructions", false)
                .put("compilerAuthority", "assets/mc1a_seed.bin"))
            .put("manifestReady", true)
            .put("signed", false)
            .put("installableClaimed", false)
            .put("createdAt", System.currentTimeMillis())

        atomicWrite(
            File(buildRoot, "codynex-mc1a-materialization.json"),
            result.toString(2).toByteArray(Charsets.UTF_8)
        )
        writeRun(result)
        return result
    }

fun prepareCodynexMc1b(project: String, cwd: String = "/D:/Workspace"): JSONObject {
        val ref = resolveProject(project, cwd)
        val seedFile = projectFile(ref, MC1B_SEED_HEX)
        require(seedFile.isFile) { "Codynex MC1-B seed is missing" }
        val seed = decodeHex(readTextBounded(seedFile).trim())
        require(seed.size == MC1B_SEED_BYTES) {
            "Codynex MC1-B seed byte count drift: " + seed.size
        }
        require(sha256(seed) == MC1B_SEED_SHA256) {
            "Codynex MC1-B seed SHA-256 drift"
        }

        val apkProject = projectFile(ref, MC1B_APK_PROJECT)
        require(apkProject.isDirectory) { "Codynex MC1-B apk-proof project is missing" }
        val apkDisplay = projectDisplay(ref, apkProject)
        val sourceValidation = validate(apkDisplay, "/D:/Workspace")
        require(sourceValidation.optBoolean("sourceReady")) {
            "Codynex MC1-B apk-proof source validation failed"
        }
        require(sourceValidation.optString("nativeLibraryName") == MC1B_LIBRARY_NAME) {
            "Codynex MC1-B NativeActivity library declaration drift"
        }

        val sourceManifest = projectFile(
            ref,
            MC1B_APK_PROJECT + "/app/src/main/AndroidManifest.xml"
        )
        val sourceManifestText = readTextBounded(sourceManifest)
        require(sourceManifestText.contains("package=\"" + MC1B_PACKAGE + "\"")) {
            "Codynex MC1-B package declaration drift"
        }
        require(sourceManifestText.contains("android:value=\"" + MC1B_LIBRARY_NAME + "\"")) {
            "Codynex MC1-B library declaration drift"
        }

        val host = readOwnApkEntry(MC1B_HOST_APK_ENTRY, MC1B_MAX_HOST_BYTES)
        verifyElfImage(host, 1, 40)

        val buildRoot = File(apkProject, "build/riftbuild").canonicalFile
        require(confinedTo(apkProject, buildRoot)) {
            "Codynex MC1-B build root escaped apk-proof"
        }
        val preparedRoot = File(buildRoot, "prepared").canonicalFile
        require(confinedTo(buildRoot, preparedRoot)) {
            "Codynex MC1-B prepared root escaped build/riftbuild"
        }
        if (preparedRoot.exists()) {
            require(deleteTreeBounded(preparedRoot, MAX_PROJECT_FILES)) {
                "Could not clear stale Codynex MC1-B prepared package"
            }
        }

        val libRoot = File(preparedRoot, "lib/armeabi-v7a").canonicalFile
        val assetRoot = File(preparedRoot, "assets").canonicalFile
        require(confinedTo(preparedRoot, libRoot)) {
            "Codynex MC1-B library root escaped prepared package"
        }
        require(confinedTo(preparedRoot, assetRoot)) {
            "Codynex MC1-B asset root escaped prepared package"
        }
        require(libRoot.mkdirs() || libRoot.isDirectory) {
            "Could not create Codynex MC1-B library directory"
        }
        require(assetRoot.mkdirs() || assetRoot.isDirectory) {
            "Could not create Codynex MC1-B asset directory"
        }

        val manifestBytes = buildMc1bBinaryManifest()
        val manifestOutput = File(preparedRoot, "AndroidManifest.xml").canonicalFile
        val hostOutput = File(libRoot, MC1B_LIBRARY_FILE).canonicalFile
        val seedOutput = File(assetRoot, "mc1b_seed.bin").canonicalFile

        atomicWrite(manifestOutput, manifestBytes)
        atomicWrite(hostOutput, host)
        atomicWrite(seedOutput, seed)

        require(isBinaryAndroidManifest(manifestOutput)) {
            "Codynex MC1-B binary AndroidManifest.xml failed validation"
        }
        require(sha256(hostOutput) == sha256(host)) {
            "Codynex MC1-B host materialization hash mismatch"
        }
        require(sha256(seedOutput) == MC1B_SEED_SHA256) {
            "Codynex MC1-B seed materialization hash mismatch"
        }

        val runId = runId()
        val result = JSONObject()
            .put("format", "riftbuild-codynex-mc1b-materialization-v1")
            .put("runId", runId)
            .put("state", "prepared-native")
            .put("project", ref.display)
            .put("androidProject", apkDisplay)
            .put("target", "arm32")
            .put("package", MC1B_PACKAGE)
            .put("libraryName", MC1B_LIBRARY_NAME)
            .put("libraryFile", MC1B_LIBRARY_FILE)
            .put("hostSource", "self-apk:" + MC1B_HOST_APK_ENTRY)
            .put("hostBytes", host.size)
            .put("hostSha256", sha256(host))
            .put("seedSource", projectDisplay(ref, seedFile))
            .put("seedBytes", seed.size)
            .put("seedSha256", sha256(seed))
            .put(
                "manifest",
                JSONObject()
                    .put("path", projectDisplay(ref, manifestOutput))
                    .put("bytes", manifestOutput.length())
                    .put("sha256", sha256(manifestOutput))
            )
            .put("antiContamination", JSONObject()
                .put("hostParsesSource", false)
                .put("hostEmitsInstructions", false)
                .put("compilerAuthority", "assets/mc1b_seed.bin"))
            .put("manifestReady", true)
            .put("signed", false)
            .put("installableClaimed", false)
            .put("createdAt", System.currentTimeMillis())

        atomicWrite(
            File(buildRoot, "codynex-mc1b-materialization.json"),
            result.toString(2).toByteArray(Charsets.UTF_8)
        )
        writeRun(result)
        return result
    }

    fun prepareCodynexM2Vm0(project: String, cwd: String = "/D:/Workspace"): JSONObject {
        val ref = resolveProject(project, cwd)
        val seedFile = projectFile(ref, M2_VM0_SEED_HEX)
        require(seedFile.isFile) { "Codynex M2-A VM0 seed is missing" }
        val seed = decodeHex(readTextBounded(seedFile).trim())
        require(seed.size == M2_VM0_SEED_BYTES) {
            "Codynex M2-A VM0 seed byte count drift: " + seed.size
        }
        require(sha256(seed) == M2_VM0_SEED_SHA256) {
            "Codynex M2-A VM0 seed SHA-256 drift"
        }

        val apkProject = projectFile(ref, M2_VM0_APK_PROJECT)
        require(apkProject.isDirectory) { "Codynex M2-A VM0 apk-proof project is missing" }
        val apkDisplay = projectDisplay(ref, apkProject)
        val sourceValidation = validate(apkDisplay, "/D:/Workspace")
        require(sourceValidation.optBoolean("sourceReady")) {
            "Codynex M2-A VM0 apk-proof source validation failed"
        }
        require(sourceValidation.optString("nativeLibraryName") == M2_VM0_LIBRARY_NAME) {
            "Codynex M2-A VM0 NativeActivity library declaration drift"
        }

        val sourceManifest = projectFile(
            ref,
            M2_VM0_APK_PROJECT + "/app/src/main/AndroidManifest.xml"
        )
        val sourceManifestText = readTextBounded(sourceManifest)
        require(sourceManifestText.contains("package=\"" + M2_VM0_PACKAGE + "\"")) {
            "Codynex M2-A VM0 package declaration drift"
        }
        require(sourceManifestText.contains("android:value=\"" + M2_VM0_LIBRARY_NAME + "\"")) {
            "Codynex M2-A VM0 library declaration drift"
        }

        val host = readOwnApkEntry(M2_VM0_HOST_APK_ENTRY, M2_VM0_MAX_HOST_BYTES)
        verifyElfImage(host, 1, 40)

        val buildRoot = File(apkProject, "build/riftbuild").canonicalFile
        require(confinedTo(apkProject, buildRoot)) {
            "Codynex M2-A VM0 build root escaped apk-proof"
        }
        val preparedRoot = File(buildRoot, "prepared").canonicalFile
        require(confinedTo(buildRoot, preparedRoot)) {
            "Codynex M2-A VM0 prepared root escaped build/riftbuild"
        }
        if (preparedRoot.exists()) {
            require(deleteTreeBounded(preparedRoot, MAX_PROJECT_FILES)) {
                "Could not clear stale Codynex M2-A VM0 prepared package"
            }
        }

        val libRoot = File(preparedRoot, "lib/armeabi-v7a").canonicalFile
        val assetRoot = File(preparedRoot, "assets").canonicalFile
        require(confinedTo(preparedRoot, libRoot)) {
            "Codynex M2-A VM0 library root escaped prepared package"
        }
        require(confinedTo(preparedRoot, assetRoot)) {
            "Codynex M2-A VM0 asset root escaped prepared package"
        }
        require(libRoot.mkdirs() || libRoot.isDirectory) {
            "Could not create Codynex M2-A VM0 library directory"
        }
        require(assetRoot.mkdirs() || assetRoot.isDirectory) {
            "Could not create Codynex M2-A VM0 asset directory"
        }

        val manifestBytes = buildMc1bBinaryManifest()
        val manifestOutput = File(preparedRoot, "AndroidManifest.xml").canonicalFile
        val hostOutput = File(libRoot, M2_VM0_LIBRARY_FILE).canonicalFile
        val seedOutput = File(assetRoot, "vm0_seed.bin").canonicalFile

        atomicWrite(manifestOutput, manifestBytes)
        atomicWrite(hostOutput, host)
        atomicWrite(seedOutput, seed)

        require(isBinaryAndroidManifest(manifestOutput)) {
            "Codynex M2-A VM0 binary AndroidManifest.xml failed validation"
        }
        require(sha256(hostOutput) == sha256(host)) {
            "Codynex M2-A VM0 host materialization hash mismatch"
        }
        require(sha256(seedOutput) == M2_VM0_SEED_SHA256) {
            "Codynex M2-A VM0 seed materialization hash mismatch"
        }

        val runId = runId()
        val result = JSONObject()
            .put("format", "riftbuild-codynex-m2-vm0-materialization-v1")
            .put("runId", runId)
            .put("state", "prepared-native")
            .put("project", ref.display)
            .put("androidProject", apkDisplay)
            .put("target", "arm32")
            .put("package", M2_VM0_PACKAGE)
            .put("libraryName", M2_VM0_LIBRARY_NAME)
            .put("libraryFile", M2_VM0_LIBRARY_FILE)
            .put("hostSource", "self-apk:" + M2_VM0_HOST_APK_ENTRY)
            .put("hostBytes", host.size)
            .put("hostSha256", sha256(host))
            .put("seedSource", projectDisplay(ref, seedFile))
            .put("seedBytes", seed.size)
            .put("seedSha256", sha256(seed))
            .put(
                "manifest",
                JSONObject()
                    .put("path", projectDisplay(ref, manifestOutput))
                    .put("bytes", manifestOutput.length())
                    .put("sha256", sha256(manifestOutput))
            )
            .put("antiContamination", JSONObject()
                .put("hostParsesSource", false)
                .put("hostEmitsInstructions", false)
                .put("vmAuthority", "assets/vm0_seed.bin"))
            .put("manifestReady", true)
            .put("signed", false)
            .put("installableClaimed", false)
            .put("createdAt", System.currentTimeMillis())

        atomicWrite(
            File(buildRoot, "codynex-m2-vm0-materialization.json"),
            result.toString(2).toByteArray(Charsets.UTF_8)
        )
        writeRun(result)
        return result
    }

    private fun decodeHex(raw: String): ByteArray {
        require(raw.isNotBlank() && raw.length % 2 == 0) {
            "Codynex machine-seed hex must contain complete byte pairs"
        }
        require(raw.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "Codynex machine-seed hex contains non-hex characters"
        }
        return ByteArray(raw.length / 2) { index ->
            raw.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun readOwnApkEntry(entryName: String, maxBytes: Long): ByteArray {
        require(entryName.startsWith("lib/") && safeZipPath(entryName)) {
            "Unsafe RiftOS self-APK entry"
        }
        val apk = File(appContext.applicationInfo.sourceDir).canonicalFile
        require(apk.isFile) { "Installed RiftOS base APK is unavailable" }
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry(entryName)
                ?: throw IllegalStateException(
                    "Installed RiftOS APK does not contain " + entryName +
                        "; rebuild/install RiftOS with the requested native proof host first"
                )
            require(!entry.isDirectory) { "RiftOS native proof host entry is not a file" }
            require(entry.size < 0L || entry.size <= maxBytes) {
                "RiftOS native proof host exceeds bounded extraction limit"
            }
            val output = ByteArrayOutputStream()
            zip.getInputStream(entry).buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    RiftDeadline.check("RiftBuild native proof host extraction")
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                    require(total <= maxBytes) {
                        "RiftOS native proof host exceeds bounded extraction limit"
                    }
                    output.write(buffer, 0, read)
                }
            }
            return output.toByteArray()
        }
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

    private fun buildMc0BinaryManifest(): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(buildMc0ManifestStringPool())
        body.write(buildManifestResourceMap())
        body.write(buildMc0ManifestNamespace(XML_START_NAMESPACE_TYPE))

        body.write(buildMc0ManifestStartElement(
            "manifest",
            listOf(
                mc0ManifestStringAttr("package", MC0_PACKAGE, XML_NO_INDEX),
                mc0ManifestIntAttr("versionCode", "1", 1),
                mc0ManifestStringAttr("versionName", MC0_VERSION_NAME)
            )
        ))
        body.write(buildMc0ManifestStartElement(
            "uses-sdk",
            listOf(
                mc0ManifestIntAttr("minSdkVersion", "26", 26),
                mc0ManifestIntAttr("targetSdkVersion", "36", 36)
            )
        ))
        body.write(buildMc0ManifestEndElement("uses-sdk"))
        body.write(buildMc0ManifestStartElement(
            "application",
            listOf(mc0ManifestBoolAttr("hasCode", "false", false))
        ))
        body.write(buildMc0ManifestStartElement(
            "activity",
            listOf(
                mc0ManifestStringAttr("name", "android.app.NativeActivity"),
                mc0ManifestBoolAttr("exported", "true", true)
            )
        ))
        body.write(buildMc0ManifestStartElement(
            "meta-data",
            listOf(
                mc0ManifestStringAttr("name", "android.app.lib_name"),
                mc0ManifestStringAttr("value", MC0_LIBRARY_NAME)
            )
        ))
        body.write(buildMc0ManifestEndElement("meta-data"))
        body.write(buildMc0ManifestStartElement("intent-filter", emptyList()))
        body.write(buildMc0ManifestStartElement(
            "action",
            listOf(mc0ManifestStringAttr("name", "android.intent.action.MAIN"))
        ))
        body.write(buildMc0ManifestEndElement("action"))
        body.write(buildMc0ManifestStartElement(
            "category",
            listOf(mc0ManifestStringAttr("name", "android.intent.category.LAUNCHER"))
        ))
        body.write(buildMc0ManifestEndElement("category"))
        body.write(buildMc0ManifestEndElement("intent-filter"))
        body.write(buildMc0ManifestEndElement("activity"))
        body.write(buildMc0ManifestEndElement("application"))
        body.write(buildMc0ManifestEndElement("manifest"))
        body.write(buildMc0ManifestNamespace(XML_END_NAMESPACE_TYPE))

        val bodyBytes = body.toByteArray()
        val output = ByteArrayOutputStream()
        writeManifestChunkHeader(output, XML_TYPE, 8, 8 + bodyBytes.size)
        output.write(bodyBytes)
        return output.toByteArray()
    }

    private fun buildMc0ManifestStringPool(): ByteArray {
        val offsets = ArrayList<Int>(MC0_MANIFEST_STRINGS.size)
        val data = ByteArrayOutputStream()
        for (value in MC0_MANIFEST_STRINGS) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            require(value.length < 0x80 && bytes.size < 0x80) {
                "Codynex MC0 manifest string exceeds one-byte UTF-8 pool length"
            }
            offsets.add(data.size())
            writeManifestLength8(data, value.length)
            writeManifestLength8(data, bytes.size)
            data.write(bytes)
            data.write(0)
        }
        while (data.size() % 4 != 0) data.write(0)

        val stringsStart = 28 + (MC0_MANIFEST_STRINGS.size * 4)
        val dataBytes = data.toByteArray()
        val output = ByteArrayOutputStream()
        writeManifestChunkHeader(
            output,
            XML_STRING_POOL_TYPE,
            28,
            stringsStart + dataBytes.size
        )
        writeManifestU32(output, MC0_MANIFEST_STRINGS.size)
        writeManifestU32(output, 0)
        writeManifestU32(output, XML_UTF8_FLAG)
        writeManifestU32(output, stringsStart)
        writeManifestU32(output, 0)
        for (offset in offsets) writeManifestU32(output, offset)
        output.write(dataBytes)
        return output.toByteArray()
    }

    private fun buildMc0ManifestNamespace(type: Int): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestNodeHeader(output, type, 24)
        writeManifestU32(output, mc0ManifestStringIndex("android"))
        writeManifestU32(
            output,
            mc0ManifestStringIndex("http://schemas.android.com/apk/res/android")
        )
        return output.toByteArray()
    }

    private fun buildMc0ManifestStartElement(
        name: String,
        attrs: List<ManifestAttr>
    ): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestNodeHeader(output, XML_START_ELEMENT_TYPE, 36 + (attrs.size * 20))
        writeManifestU32(output, XML_NO_INDEX)
        writeManifestU32(output, mc0ManifestStringIndex(name))
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

    private fun buildMc0ManifestEndElement(name: String): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestNodeHeader(output, XML_END_ELEMENT_TYPE, 24)
        writeManifestU32(output, XML_NO_INDEX)
        writeManifestU32(output, mc0ManifestStringIndex(name))
        return output.toByteArray()
    }

    private fun mc0ManifestStringAttr(
        name: String,
        value: String,
        namespace: Int = mc0ManifestStringIndex(
            "http://schemas.android.com/apk/res/android"
        )
    ): ManifestAttr =
        ManifestAttr(
            namespace,
            mc0ManifestStringIndex(name),
            mc0ManifestStringIndex(value),
            XML_VALUE_STRING,
            mc0ManifestStringIndex(value)
        )

    private fun mc0ManifestIntAttr(
        name: String,
        rawValue: String,
        value: Int
    ): ManifestAttr =
        ManifestAttr(
            mc0ManifestStringIndex("http://schemas.android.com/apk/res/android"),
            mc0ManifestStringIndex(name),
            mc0ManifestStringIndex(rawValue),
            XML_VALUE_INT_DEC,
            value
        )

    private fun mc0ManifestBoolAttr(
        name: String,
        rawValue: String,
        value: Boolean
    ): ManifestAttr =
        ManifestAttr(
            mc0ManifestStringIndex("http://schemas.android.com/apk/res/android"),
            mc0ManifestStringIndex(name),
            mc0ManifestStringIndex(rawValue),
            XML_VALUE_INT_BOOLEAN,
            if (value) -1 else 0
        )

    private fun mc0ManifestStringIndex(value: String): Int {
        val index = MC0_MANIFEST_STRINGS.indexOf(value)
        require(index >= 0) {
            "Codynex MC0 manifest string is not in the frozen pool: " + value
        }
        return index
    }

private fun buildMc1aBinaryManifest(): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(buildMc1aManifestStringPool())
        body.write(buildManifestResourceMap())
        body.write(buildMc1aManifestNamespace(XML_START_NAMESPACE_TYPE))

        body.write(buildMc1aManifestStartElement(
            "manifest",
            listOf(
                mc1aManifestStringAttr("package", MC1A_PACKAGE, XML_NO_INDEX),
                mc1aManifestIntAttr("versionCode", "1", 1),
                mc1aManifestStringAttr("versionName", MC1A_VERSION_NAME)
            )
        ))
        body.write(buildMc1aManifestStartElement(
            "uses-sdk",
            listOf(
                mc1aManifestIntAttr("minSdkVersion", "26", 26),
                mc1aManifestIntAttr("targetSdkVersion", "36", 36)
            )
        ))
        body.write(buildMc1aManifestEndElement("uses-sdk"))
        body.write(buildMc1aManifestStartElement(
            "application",
            listOf(mc1aManifestBoolAttr("hasCode", "false", false))
        ))
        body.write(buildMc1aManifestStartElement(
            "activity",
            listOf(
                mc1aManifestStringAttr("name", "android.app.NativeActivity"),
                mc1aManifestBoolAttr("exported", "true", true)
            )
        ))
        body.write(buildMc1aManifestStartElement(
            "meta-data",
            listOf(
                mc1aManifestStringAttr("name", "android.app.lib_name"),
                mc1aManifestStringAttr("value", MC1A_LIBRARY_NAME)
            )
        ))
        body.write(buildMc1aManifestEndElement("meta-data"))
        body.write(buildMc1aManifestStartElement("intent-filter", emptyList()))
        body.write(buildMc1aManifestStartElement(
            "action",
            listOf(mc1aManifestStringAttr("name", "android.intent.action.MAIN"))
        ))
        body.write(buildMc1aManifestEndElement("action"))
        body.write(buildMc1aManifestStartElement(
            "category",
            listOf(mc1aManifestStringAttr("name", "android.intent.category.LAUNCHER"))
        ))
        body.write(buildMc1aManifestEndElement("category"))
        body.write(buildMc1aManifestEndElement("intent-filter"))
        body.write(buildMc1aManifestEndElement("activity"))
        body.write(buildMc1aManifestEndElement("application"))
        body.write(buildMc1aManifestEndElement("manifest"))
        body.write(buildMc1aManifestNamespace(XML_END_NAMESPACE_TYPE))

        val bodyBytes = body.toByteArray()
        val output = ByteArrayOutputStream()
        writeManifestChunkHeader(output, XML_TYPE, 8, 8 + bodyBytes.size)
        output.write(bodyBytes)
        return output.toByteArray()
    }

    private fun buildMc1aManifestStringPool(): ByteArray {
        val offsets = ArrayList<Int>(MC1A_MANIFEST_STRINGS.size)
        val data = ByteArrayOutputStream()
        for (value in MC1A_MANIFEST_STRINGS) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            require(value.length < 0x80 && bytes.size < 0x80) {
                "Codynex MC1-A manifest string exceeds one-byte UTF-8 pool length"
            }
            offsets.add(data.size())
            writeManifestLength8(data, value.length)
            writeManifestLength8(data, bytes.size)
            data.write(bytes)
            data.write(0)
        }
        while (data.size() % 4 != 0) data.write(0)

        val stringsStart = 28 + (MC1A_MANIFEST_STRINGS.size * 4)
        val dataBytes = data.toByteArray()
        val output = ByteArrayOutputStream()
        writeManifestChunkHeader(
            output,
            XML_STRING_POOL_TYPE,
            28,
            stringsStart + dataBytes.size
        )
        writeManifestU32(output, MC1A_MANIFEST_STRINGS.size)
        writeManifestU32(output, 0)
        writeManifestU32(output, XML_UTF8_FLAG)
        writeManifestU32(output, stringsStart)
        writeManifestU32(output, 0)
        for (offset in offsets) writeManifestU32(output, offset)
        output.write(dataBytes)
        return output.toByteArray()
    }

    private fun buildMc1aManifestNamespace(type: Int): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestNodeHeader(output, type, 24)
        writeManifestU32(output, mc1aManifestStringIndex("android"))
        writeManifestU32(
            output,
            mc1aManifestStringIndex("http://schemas.android.com/apk/res/android")
        )
        return output.toByteArray()
    }

    private fun buildMc1aManifestStartElement(
        name: String,
        attrs: List<ManifestAttr>
    ): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestNodeHeader(output, XML_START_ELEMENT_TYPE, 36 + (attrs.size * 20))
        writeManifestU32(output, XML_NO_INDEX)
        writeManifestU32(output, mc1aManifestStringIndex(name))
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

    private fun buildMc1aManifestEndElement(name: String): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestNodeHeader(output, XML_END_ELEMENT_TYPE, 24)
        writeManifestU32(output, XML_NO_INDEX)
        writeManifestU32(output, mc1aManifestStringIndex(name))
        return output.toByteArray()
    }

    private fun mc1aManifestStringAttr(
        name: String,
        value: String,
        namespace: Int = mc1aManifestStringIndex(
            "http://schemas.android.com/apk/res/android"
        )
    ): ManifestAttr =
        ManifestAttr(
            namespace,
            mc1aManifestStringIndex(name),
            mc1aManifestStringIndex(value),
            XML_VALUE_STRING,
            mc1aManifestStringIndex(value)
        )

    private fun mc1aManifestIntAttr(
        name: String,
        rawValue: String,
        value: Int
    ): ManifestAttr =
        ManifestAttr(
            mc1aManifestStringIndex("http://schemas.android.com/apk/res/android"),
            mc1aManifestStringIndex(name),
            mc1aManifestStringIndex(rawValue),
            XML_VALUE_INT_DEC,
            value
        )

    private fun mc1aManifestBoolAttr(
        name: String,
        rawValue: String,
        value: Boolean
    ): ManifestAttr =
        ManifestAttr(
            mc1aManifestStringIndex("http://schemas.android.com/apk/res/android"),
            mc1aManifestStringIndex(name),
            mc1aManifestStringIndex(rawValue),
            XML_VALUE_INT_BOOLEAN,
            if (value) -1 else 0
        )

    private fun mc1aManifestStringIndex(value: String): Int {
        val index = MC1A_MANIFEST_STRINGS.indexOf(value)
        require(index >= 0) {
            "Codynex MC1-A manifest string is not in the frozen pool: " + value
        }
        return index
    }

private fun buildMc1bBinaryManifest(): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(buildMc1bManifestStringPool())
        body.write(buildManifestResourceMap())
        body.write(buildMc1bManifestNamespace(XML_START_NAMESPACE_TYPE))

        body.write(buildMc1bManifestStartElement(
            "manifest",
            listOf(
                mc1bManifestStringAttr("package", MC1B_PACKAGE, XML_NO_INDEX),
                mc1bManifestIntAttr("versionCode", "1", 1),
                mc1bManifestStringAttr("versionName", MC1B_VERSION_NAME)
            )
        ))
        body.write(buildMc1bManifestStartElement(
            "uses-sdk",
            listOf(
                mc1bManifestIntAttr("minSdkVersion", "26", 26),
                mc1bManifestIntAttr("targetSdkVersion", "36", 36)
            )
        ))
        body.write(buildMc1bManifestEndElement("uses-sdk"))
        body.write(buildMc1bManifestStartElement(
            "application",
            listOf(mc1bManifestBoolAttr("hasCode", "false", false))
        ))
        body.write(buildMc1bManifestStartElement(
            "activity",
            listOf(
                mc1bManifestStringAttr("name", "android.app.NativeActivity"),
                mc1bManifestBoolAttr("exported", "true", true)
            )
        ))
        body.write(buildMc1bManifestStartElement(
            "meta-data",
            listOf(
                mc1bManifestStringAttr("name", "android.app.lib_name"),
                mc1bManifestStringAttr("value", MC1B_LIBRARY_NAME)
            )
        ))
        body.write(buildMc1bManifestEndElement("meta-data"))
        body.write(buildMc1bManifestStartElement("intent-filter", emptyList()))
        body.write(buildMc1bManifestStartElement(
            "action",
            listOf(mc1bManifestStringAttr("name", "android.intent.action.MAIN"))
        ))
        body.write(buildMc1bManifestEndElement("action"))
        body.write(buildMc1bManifestStartElement(
            "category",
            listOf(mc1bManifestStringAttr("name", "android.intent.category.LAUNCHER"))
        ))
        body.write(buildMc1bManifestEndElement("category"))
        body.write(buildMc1bManifestEndElement("intent-filter"))
        body.write(buildMc1bManifestEndElement("activity"))
        body.write(buildMc1bManifestEndElement("application"))
        body.write(buildMc1bManifestEndElement("manifest"))
        body.write(buildMc1bManifestNamespace(XML_END_NAMESPACE_TYPE))

        val bodyBytes = body.toByteArray()
        val output = ByteArrayOutputStream()
        writeManifestChunkHeader(output, XML_TYPE, 8, 8 + bodyBytes.size)
        output.write(bodyBytes)
        return output.toByteArray()
    }

    private fun buildMc1bManifestStringPool(): ByteArray {
        val offsets = ArrayList<Int>(MC1B_MANIFEST_STRINGS.size)
        val data = ByteArrayOutputStream()
        for (value in MC1B_MANIFEST_STRINGS) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            require(value.length < 0x80 && bytes.size < 0x80) {
                "Codynex MC1-B manifest string exceeds one-byte UTF-8 pool length"
            }
            offsets.add(data.size())
            writeManifestLength8(data, value.length)
            writeManifestLength8(data, bytes.size)
            data.write(bytes)
            data.write(0)
        }
        while (data.size() % 4 != 0) data.write(0)

        val stringsStart = 28 + (MC1B_MANIFEST_STRINGS.size * 4)
        val dataBytes = data.toByteArray()
        val output = ByteArrayOutputStream()
        writeManifestChunkHeader(
            output,
            XML_STRING_POOL_TYPE,
            28,
            stringsStart + dataBytes.size
        )
        writeManifestU32(output, MC1B_MANIFEST_STRINGS.size)
        writeManifestU32(output, 0)
        writeManifestU32(output, XML_UTF8_FLAG)
        writeManifestU32(output, stringsStart)
        writeManifestU32(output, 0)
        for (offset in offsets) writeManifestU32(output, offset)
        output.write(dataBytes)
        return output.toByteArray()
    }

    private fun buildMc1bManifestNamespace(type: Int): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestNodeHeader(output, type, 24)
        writeManifestU32(output, mc1bManifestStringIndex("android"))
        writeManifestU32(
            output,
            mc1bManifestStringIndex("http://schemas.android.com/apk/res/android")
        )
        return output.toByteArray()
    }

    private fun buildMc1bManifestStartElement(
        name: String,
        attrs: List<ManifestAttr>
    ): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestNodeHeader(output, XML_START_ELEMENT_TYPE, 36 + (attrs.size * 20))
        writeManifestU32(output, XML_NO_INDEX)
        writeManifestU32(output, mc1bManifestStringIndex(name))
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

    private fun buildMc1bManifestEndElement(name: String): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestNodeHeader(output, XML_END_ELEMENT_TYPE, 24)
        writeManifestU32(output, XML_NO_INDEX)
        writeManifestU32(output, mc1bManifestStringIndex(name))
        return output.toByteArray()
    }

    private fun mc1bManifestStringAttr(
        name: String,
        value: String,
        namespace: Int = mc1bManifestStringIndex(
            "http://schemas.android.com/apk/res/android"
        )
    ): ManifestAttr =
        ManifestAttr(
            namespace,
            mc1bManifestStringIndex(name),
            mc1bManifestStringIndex(value),
            XML_VALUE_STRING,
            mc1bManifestStringIndex(value)
        )

    private fun mc1bManifestIntAttr(
        name: String,
        rawValue: String,
        value: Int
    ): ManifestAttr =
        ManifestAttr(
            mc1bManifestStringIndex("http://schemas.android.com/apk/res/android"),
            mc1bManifestStringIndex(name),
            mc1bManifestStringIndex(rawValue),
            XML_VALUE_INT_DEC,
            value
        )

    private fun mc1bManifestBoolAttr(
        name: String,
        rawValue: String,
        value: Boolean
    ): ManifestAttr =
        ManifestAttr(
            mc1bManifestStringIndex("http://schemas.android.com/apk/res/android"),
            mc1bManifestStringIndex(name),
            mc1bManifestStringIndex(rawValue),
            XML_VALUE_INT_BOOLEAN,
            if (value) -1 else 0
        )

    private fun mc1bManifestStringIndex(value: String): Int {
        val index = MC1B_MANIFEST_STRINGS.indexOf(value)
        require(index >= 0) {
            "Codynex MC1-B manifest string is not in the frozen pool: " + value
        }
        return index
    }

    private fun buildM2Vm0BinaryManifest(): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(buildM2Vm0ManifestStringPool())
        body.write(buildManifestResourceMap())
        body.write(buildM2Vm0ManifestNamespace(XML_START_NAMESPACE_TYPE))

        body.write(buildM2Vm0ManifestStartElement(
            "manifest",
            listOf(
                m2Vm0ManifestStringAttr("package", M2_VM0_PACKAGE, XML_NO_INDEX),
                m2Vm0ManifestIntAttr("versionCode", "1", 1),
                m2Vm0ManifestStringAttr("versionName", M2_VM0_VERSION_NAME)
            )
        ))
        body.write(buildM2Vm0ManifestStartElement(
            "uses-sdk",
            listOf(
                m2Vm0ManifestIntAttr("minSdkVersion", "26", 26),
                m2Vm0ManifestIntAttr("targetSdkVersion", "36", 36)
            )
        ))
        body.write(buildM2Vm0ManifestEndElement("uses-sdk"))
        body.write(buildM2Vm0ManifestStartElement(
            "application",
            listOf(m2Vm0ManifestBoolAttr("hasCode", "false", false))
        ))
        body.write(buildM2Vm0ManifestStartElement(
            "activity",
            listOf(
                m2Vm0ManifestStringAttr("name", "android.app.NativeActivity"),
                m2Vm0ManifestBoolAttr("exported", "true", true)
            )
        ))
        body.write(buildM2Vm0ManifestStartElement(
            "meta-data",
            listOf(
                m2Vm0ManifestStringAttr("name", "android.app.lib_name"),
                m2Vm0ManifestStringAttr("value", M2_VM0_LIBRARY_NAME)
            )
        ))
        body.write(buildM2Vm0ManifestEndElement("meta-data"))
        body.write(buildM2Vm0ManifestStartElement("intent-filter", emptyList()))
        body.write(buildM2Vm0ManifestStartElement(
            "action",
            listOf(m2Vm0ManifestStringAttr("name", "android.intent.action.MAIN"))
        ))
        body.write(buildM2Vm0ManifestEndElement("action"))
        body.write(buildM2Vm0ManifestStartElement(
            "category",
            listOf(m2Vm0ManifestStringAttr("name", "android.intent.category.LAUNCHER"))
        ))
        body.write(buildM2Vm0ManifestEndElement("category"))
        body.write(buildM2Vm0ManifestEndElement("intent-filter"))
        body.write(buildM2Vm0ManifestEndElement("activity"))
        body.write(buildM2Vm0ManifestEndElement("application"))
        body.write(buildM2Vm0ManifestEndElement("manifest"))
        body.write(buildM2Vm0ManifestNamespace(XML_END_NAMESPACE_TYPE))

        val bodyBytes = body.toByteArray()
        val output = ByteArrayOutputStream()
        writeManifestChunkHeader(output, XML_TYPE, 8, 8 + bodyBytes.size)
        output.write(bodyBytes)
        return output.toByteArray()
    }

    private fun buildM2Vm0ManifestStringPool(): ByteArray {
        val offsets = ArrayList<Int>(M2_VM0_MANIFEST_STRINGS.size)
        val data = ByteArrayOutputStream()
        for (value in M2_VM0_MANIFEST_STRINGS) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            require(value.length < 0x80 && bytes.size < 0x80) {
                "Codynex M2-A VM0 manifest string exceeds one-byte UTF-8 pool length"
            }
            offsets.add(data.size())
            writeManifestLength8(data, value.length)
            writeManifestLength8(data, bytes.size)
            data.write(bytes)
            data.write(0)
        }
        while (data.size() % 4 != 0) data.write(0)

        val stringsStart = 28 + (M2_VM0_MANIFEST_STRINGS.size * 4)
        val dataBytes = data.toByteArray()
        val output = ByteArrayOutputStream()
        writeManifestChunkHeader(
            output,
            XML_STRING_POOL_TYPE,
            28,
            stringsStart + dataBytes.size
        )
        writeManifestU32(output, M2_VM0_MANIFEST_STRINGS.size)
        writeManifestU32(output, 0)
        writeManifestU32(output, XML_UTF8_FLAG)
        writeManifestU32(output, stringsStart)
        writeManifestU32(output, 0)
        for (offset in offsets) writeManifestU32(output, offset)
        output.write(dataBytes)
        return output.toByteArray()
    }

    private fun buildM2Vm0ManifestNamespace(type: Int): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestNodeHeader(output, type, 24)
        writeManifestU32(output, m2Vm0ManifestStringIndex("android"))
        writeManifestU32(
            output,
            m2Vm0ManifestStringIndex("http://schemas.android.com/apk/res/android")
        )
        return output.toByteArray()
    }

    private fun buildM2Vm0ManifestStartElement(
        name: String,
        attrs: List<ManifestAttr>
    ): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestNodeHeader(output, XML_START_ELEMENT_TYPE, 36 + (attrs.size * 20))
        writeManifestU32(output, XML_NO_INDEX)
        writeManifestU32(output, m2Vm0ManifestStringIndex(name))
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

    private fun buildM2Vm0ManifestEndElement(name: String): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestNodeHeader(output, XML_END_ELEMENT_TYPE, 24)
        writeManifestU32(output, XML_NO_INDEX)
        writeManifestU32(output, m2Vm0ManifestStringIndex(name))
        return output.toByteArray()
    }

    private fun m2Vm0ManifestStringAttr(
        name: String,
        value: String,
        namespace: Int = m2Vm0ManifestStringIndex(
            "http://schemas.android.com/apk/res/android"
        )
    ): ManifestAttr =
        ManifestAttr(
            namespace,
            m2Vm0ManifestStringIndex(name),
            m2Vm0ManifestStringIndex(value),
            XML_VALUE_STRING,
            m2Vm0ManifestStringIndex(value)
        )

    private fun m2Vm0ManifestIntAttr(
        name: String,
        rawValue: String,
        value: Int
    ): ManifestAttr =
        ManifestAttr(
            m2Vm0ManifestStringIndex("http://schemas.android.com/apk/res/android"),
            m2Vm0ManifestStringIndex(name),
            m2Vm0ManifestStringIndex(rawValue),
            XML_VALUE_INT_DEC,
            value
        )

    private fun m2Vm0ManifestBoolAttr(
        name: String,
        rawValue: String,
        value: Boolean
    ): ManifestAttr =
        ManifestAttr(
            m2Vm0ManifestStringIndex("http://schemas.android.com/apk/res/android"),
            m2Vm0ManifestStringIndex(name),
            m2Vm0ManifestStringIndex(rawValue),
            XML_VALUE_INT_BOOLEAN,
            if (value) -1 else 0
        )

    private fun m2Vm0ManifestStringIndex(value: String): Int {
        val index = M2_VM0_MANIFEST_STRINGS.indexOf(value)
        require(index >= 0) {
            "Codynex M2-A VM0 manifest string is not in the frozen pool: " + value
        }
        return index
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
                RiftDeadline.check("RiftBuild prepared assets")
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
