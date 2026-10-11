package com.riftos.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Embedded-Core-only RAPP install/uninstall migration fallback.
 *
 * This retains the original signed APK's safe package operations while the
 * separately installed external Core is under device qualification.
 * No project-specific RiftBuild shell, compiler, APK pack/sign/verify or
 * proof command dispatch belongs to this fallback.
 */
class RiftBuildPlatformTools(context: Context) {
    private val appContext = context.applicationContext
    private val riftRoot = File(appContext.filesDir, "riftfs").apply { mkdirs() }.canonicalFile
    private val artifactRoot = File(riftRoot, "documents/builds").apply { mkdirs() }.canonicalFile
    private val rappManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RiftCoreRuntime.packages(appContext)
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

    @Synchronized
    fun uninstallRapp(id: String): JSONObject =
        rappManager.uninstall(id)
            .put("runId", runId())
            .put("createdAt", System.currentTimeMillis())

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