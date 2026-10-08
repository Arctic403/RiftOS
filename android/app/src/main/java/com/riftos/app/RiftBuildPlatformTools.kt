package com.riftos.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Thin platform command surface for external build providers and diagnostics.
 *
 * No build recipe, prepared-tree, APK packing, or APK signing semantics live here.
 */
class RiftBuildPlatformTools(context: Context) {
    data class CommandResult(val output: String, val value: JSONObject)
    private data class ProjectRef(val display: String, val file: File)

    private val appContext = context.applicationContext
    private val riftRoot = File(appContext.filesDir, "riftfs").apply { mkdirs() }.canonicalFile
    private val workspaceRoot = File(riftRoot, "workspace").apply { mkdirs() }.canonicalFile
    private val artifactRoot = File(riftRoot, "documents/builds").apply { mkdirs() }.canonicalFile
    private val buildLocal = RiftLocalBuildCapability(appContext)
    private val rappManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RiftCoreRuntime.packages(appContext)
    }
    private val verifier = RiftApkV2Verifier()
    private val installer = RiftBuildInstaller(appContext)
    private val runtimeProviders = RiftCoreRuntime.runtimes(appContext)

    fun executeShell(args: MutableList<String>, cwd: String): CommandResult {
        val sub = args.removeFirstOrNull()?.lowercase() ?: "help"
        val value = when (sub) {
            "help" -> JSONObject()
                .put("schema", "riftbuild-platform-help-v1")
                .put(
                    "usage",
                    "riftbuild compiler-status <project> | compiler-run <project> <compiler-id> <request.json> | " +
                        "jvm-status | jvm-dex <project> <classes-dir> <output-dir> [minSdk] | runtime-status | " +
                        "pack-rapp <project> | install-rapp <artifact.rapp> | launch-rapp <id> | rapp-list | " +
                        "verify <signed-apk> | install-proof <signed-apk> | install-status | launch-proof"
                )

            "compiler-status" -> buildLocal.compilerStatus(
                args.firstOrNull() ?: error("usage: riftbuild compiler-status <project>"),
                cwd
            )

            "compiler-run" -> buildLocal.compilerRun(
                args.firstOrNull()
                    ?: error("usage: riftbuild compiler-run <project> <compiler-id> <request.json>"),
                args.getOrNull(1)
                    ?: error("usage: riftbuild compiler-run <project> <compiler-id> <request.json>"),
                args.getOrNull(2)
                    ?: error("usage: riftbuild compiler-run <project> <compiler-id> <request.json>"),
                cwd
            )

            "jvm-status" -> buildLocal.jvmToolchainStatus()
            "runtime-status" -> runtimeProviders.status()

            "jvm-dex" -> buildLocal.dexJvmClasses(
                args.firstOrNull()
                    ?: error("usage: riftbuild jvm-dex <project> <classes-dir> <output-dir> [minSdk]"),
                args.getOrNull(1)
                    ?: error("usage: riftbuild jvm-dex <project> <classes-dir> <output-dir> [minSdk]"),
                args.getOrNull(2)
                    ?: error("usage: riftbuild jvm-dex <project> <classes-dir> <output-dir> [minSdk]"),
                args.getOrNull(3)?.toIntOrNull() ?: 26,
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

            "verify" -> verifyArtifact(
                args.firstOrNull() ?: error("usage: riftbuild verify <signed-apk>")
            )

            "install-proof" -> installProof(
                args.firstOrNull() ?: error("usage: riftbuild install-proof <signed-apk>")
            )

            "install-status" -> installer.status()
            "launch-proof" -> installer.launchProof()
            else -> error("unknown riftbuild platform command: $sub")
        }
        return CommandResult(value.toString(2), value)
    }

    @Synchronized
    fun packRapp(project: String, cwd: String = "/D:/Workspace"): JSONObject {
        val ref = resolveProject(project, cwd)
        val packed = rappManager.pack(ref.file)
        return JSONObject(packed.receipt.toString())
            .put("runId", runId())
            .put("project", ref.display)
            .put("artifact", artifactDisplay(packed.artifact))
            .put("createdAt", System.currentTimeMillis())
    }

    @Synchronized
    fun installRapp(rawArtifact: String): JSONObject {
        val artifact = resolveArtifact(rawArtifact)
        return rappManager.install(artifact)
            .put("runId", runId())
            .put("artifact", artifactDisplay(artifact))
            .put("artifactSha256", sha256(artifact))
            .put("createdAt", System.currentTimeMillis())
    }

    fun launchRapp(id: String): JSONObject =
        rappManager.launch(id)
            .put("runId", runId())
            .put("createdAt", System.currentTimeMillis())

    fun verifyArtifact(rawArtifact: String): JSONObject {
        val signedApk = resolveArtifact(rawArtifact)
        require(signedApk.name.endsWith("-signed.apk")) {
            "RiftBuild verify accepts only *-signed.apk artifacts"
        }
        val verified = verifier.verify(signedApk)
        return JSONObject()
            .put("format", "riftbuild-apk-v2-verification-receipt-v1")
            .put("state", "verified-v2")
            .put("runId", runId())
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
            .put("verifiedAt", System.currentTimeMillis())
    }

    @Synchronized
    fun installProof(rawArtifact: String): JSONObject {
        val signedApk = resolveArtifact(rawArtifact)
        require(signedApk.name.endsWith("-signed.apk")) {
            "RiftBuild install-proof accepts only *-signed.apk artifacts"
        }
        val verified = verifier.verify(signedApk)
        return installer.installProof(signedApk, verified)
            .put("format", "riftbuild-install-proof-v1")
            .put("runId", runId())
            .put("artifact", artifactDisplay(signedApk))
            .put("artifactSha256", verified.apkSha256)
            .put("certificateSha256", verified.certificateSha256)
            .put("signatureVerified", true)
    }

    private fun resolveProject(raw: String, cwd: String): ProjectRef {
        require(raw.isNotBlank()) { "RAPP project path is required" }
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
            "RAPP projects must live under D:/Workspace"
        }
        val file = File(riftRoot, RiftVolumePaths.resolveRelative(display)).canonicalFile
        require(confinedTo(workspaceRoot, file)) { "RAPP project escaped workspace" }
        require(file.isDirectory) { "RAPP project is not a directory: $display" }
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

    private fun resolveArtifact(raw: String): File {
        require(raw.isNotBlank()) { "RiftBuild artifact path is required" }
        val value = raw.trim().replace('\\', '/')
        val absolute = when {
            value == "/D:/Builds" || value.startsWith("/D:/Builds/") -> value
            value == "D:/Builds" || value.startsWith("D:/Builds/") -> "/" + value
            else -> "/D:/Builds/" + value.trimStart('/')
        }
        val display = RiftVolumePaths.normalizeDisplay(absolute)
        require(display.startsWith("/D:/Builds/")) {
            "RiftBuild artifact must live under D:/Builds"
        }
        val relative = display.removePrefix("/D:/Builds/").trim('/')
        require(relative.isNotBlank()) { "RiftBuild artifact file is required" }
        val file = File(artifactRoot, relative).canonicalFile
        require(confinedTo(artifactRoot, file)) {
            "RiftBuild artifact escaped D:/Builds"
        }
        require(file.isFile) { "RiftBuild artifact not found: $display" }
        return file
    }

    private fun artifactDisplay(file: File): String {
        val canonical = file.canonicalFile
        require(confinedTo(artifactRoot, canonical)) {
            "artifact escaped D:/Builds"
        }
        val relative = canonical.relativeTo(artifactRoot).invariantSeparatorsPath
        return if (relative.isBlank()) "/D:/Builds" else "/D:/Builds/" + relative
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

    private fun runId(): String =
        "platform-" + System.currentTimeMillis() + "-" +
            java.lang.Long.toHexString(System.nanoTime()).takeLast(10)
}
