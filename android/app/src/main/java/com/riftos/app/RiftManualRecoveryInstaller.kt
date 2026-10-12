package com.riftos.app

import android.app.Application
import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.InputStream

/**
 * APK-owned user-initiated install bridge. Works in Recovery without Core.
 * It reuses the EXISTING protected component parser, sealed staging and DEX
 * verifier. Staging never selects a component; user-confirmed install changes
 * next boot only and never creates/overwrites the Recovery backup.
 * No exported service, shell command or RAPP entry point is added.
 */
internal object RiftManualRecoveryInstaller {
    private fun requireRecoveryProcess(context: Context) {
        require(Build.VERSION.SDK_INT >= 28 &&
            Application.getProcessName() == context.packageName + ":riftRecovery") {
            "Protected installation requires the independent Recovery UI process"
        }
    }

    fun stage(context: Context, manifestBytes: ByteArray, dex: InputStream): JSONObject {
        requireRecoveryProcess(context)
        require(manifestBytes.size in 1..32 * 1024) {
            "Component manifest file must be bounded"
        }
        return RiftProtectedComponentInstaller.stage(context, manifestBytes, dex)
    }

    /**
     * Same protected stage verifier as Android's SAF picker, but read from
     * RiftOS's PRIVATE virtual Builds drive. Android DocumentsUI cannot see
     * this app-private storage on modern Android. No install or select occurs.
     *
     * Only a user-initiated action inside the independent Recovery process
     * can invoke this method. No RAPP/shell/IPC surface is published.
     */
    fun stageLocal(context: Context, virtualDirectory: String): JSONObject {
        requireRecoveryProcess(context)
        val display = RiftVolumePaths.normalizeDisplay(virtualDirectory)
        val prefix = "/D:/Builds/Components/"
        require(display.startsWith(prefix) && display.length <= 240) {
            "Local component must be under RiftOS D:/Builds/Components"
        }
        val parts = display.removePrefix(prefix).split('/')
        require(parts.size in 1..4 &&
            (parts.first() == "core" || parts.first() == "shell") &&
            parts.all { it.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")) }) {
            "Local protected component path invalid"
        }
        val sourceRoot = java.io.File(context.applicationContext.filesDir, "riftfs")
        require(!java.nio.file.Files.isSymbolicLink(sourceRoot.toPath())) {
            "RiftFS root is symlinked"
        }
        val privateRoot = sourceRoot.canonicalFile
        val buildsRoot = java.io.File(privateRoot, "documents/builds/Components")
            .canonicalFile
        val unresolved = java.io.File(privateRoot,
            RiftVolumePaths.resolveRelative(display))
        val directory = unresolved.canonicalFile
        require(buildsRoot.path.startsWith(privateRoot.path + java.io.File.separator) &&
            directory.path.startsWith(buildsRoot.path + java.io.File.separator) &&
            directory.isDirectory) {
            "Local component directory missing or outside Builds"
        }
        var candidate: java.io.File? = unresolved
        while (candidate != null && candidate != privateRoot) {
            require(!java.nio.file.Files.isSymbolicLink(candidate.toPath())) {
                "Local component path contains a symlink"
            }
            candidate = candidate.parentFile
        }
        val meta = java.io.File(directory, "component.json")
        require(meta.isFile && meta.length() in 1L..4096L &&
            !java.nio.file.Files.isSymbolicLink(meta.toPath())) {
            "Local component.json missing or oversized"
        }
        val manifestBytes = meta.readBytes()
        val manifest = RiftProtectedComponentManifest.parse(manifestBytes)
        require(manifest.component == parts.first()) {
            "Local component manifest and directory disagree"
        }
        val dex = java.io.File(directory, manifest.payload)
        require(dex.isFile && dex.length() in 1L..(32L * 1024L * 1024L) &&
            !java.nio.file.Files.isSymbolicLink(dex.toPath()) &&
            dex.canonicalFile.parentFile == directory) {
            "Local component DEX missing or outside selected directory"
        }
        return dex.inputStream().use { stage(context, manifestBytes, it) }
    }

    fun install(context: Context, component: String, sha256: String): JSONObject {
        requireRecoveryProcess(context)
        require(component == "core" || component == "shell") {
            "Only verified external Core and Shell can be selected"
        }
        // Existing protected installer also verifies this SHA, its manifest,
        // class closure and exact verified component pointer; it does not
        // judge boot health, rotate backups or select recovery automatically.
        return RiftProtectedComponentInstaller.activate(context, component, sha256)
    }
}
