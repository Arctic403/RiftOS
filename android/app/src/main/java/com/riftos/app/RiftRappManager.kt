package com.riftos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * RiftOS-native application package manager.
 *
 * This is deliberately parallel to APK packaging. Compilers emit their normal
 * artifacts into a workspace project, riftapp.json points at those artifacts,
 * and this class packs/installs them without invoking Android APK signing.
 */
class RiftRappManager(context: Context) {
    data class PackResult(
        val artifact: File,
        val receipt: JSONObject
    )

    data class InstalledApp(
        val id: String,
        val name: String,
        val launcherIcon: String,
        val abi: String,
        val adapter: String,
        val presentation: String,
        val permissions: Set<String>,
        val program: ByteArray,
        val runtime: ByteArray
    )

    private data class ProjectSpec(
        val id: String,
        val name: String,
        val launcherIcon: String,
        val abi: String,
        val adapter: String,
        val presentation: String,
        val permissions: Set<String>,
        val entry: String,
        val runtime: String
    )

    private data class PackagePayload(
        val manifest: JSONObject,
        val program: ByteArray,
        val runtime: ByteArray
    )

    companion object {
        const val PROJECT_SCHEMA = "riftos.rapp-project/1"
        const val PACKAGE_SCHEMA = "riftos.rapp/1"
        const val APP_ABI_SCHEMA = RiftAppAbi.SCHEMA

        private const val PROJECT_MANIFEST = "riftapp.json"
        private const val PACKAGE_MANIFEST = "manifest.json"
        private const val PROGRAM_ENTRY = "program.bin"
        private const val RUNTIME_ENTRY = "runtime.bin"
        private const val STATE_ENTRY = "state.bin"
        private const val MAX_MANIFEST_BYTES = 64 * 1024
        private const val MAX_PROGRAM_BYTES = 1024 * 1024
        // Executable RAPP source may be larger than its separately bounded state.
        // 8 MiB runtime plus 1 MiB program fits within a 16 MiB ZIP cap.
        private const val MAX_RUNTIME_BYTES = 8 * 1024 * 1024
        private const val MAX_PACKAGE_BYTES = 16L * 1024L * 1024L
        private const val MAX_INSTALLED_APPS = 128
        private val SAFE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{1,63}$")
        private val SAFE_TOKEN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
        private val RESERVED_IDS = setOf(
            "files",
            "workspace-live",
            "terminal",
            "browser",
            "editor",
            "devlab",
            "tasks",
            "installed-apps",
            "settings",
            "mcp"
        )
    }

    private val appContext = context.applicationContext
    private val riftRoot =
        File(appContext.filesDir, "riftfs")
            .apply { mkdirs() }
            .canonicalFile
    private val workspaceRoot =
        File(riftRoot, "workspace")
            .apply { mkdirs() }
            .canonicalFile
    private val artifactRoot =
        File(riftRoot, "documents/builds")
            .apply { mkdirs() }
            .canonicalFile
    private val programsRoot =
        File(
            riftRoot,
            RiftVolumePaths.resolveRelative("/C:/Programs")
        )
            .apply { mkdirs() }
            .canonicalFile
    private val stageRoot =
        File(riftRoot, "system/rapp/stage")
            .apply { mkdirs() }
            .canonicalFile

    fun pack(projectRoot: File): PackResult {
        val project = projectRoot.canonicalFile
        require(confinedTo(workspaceRoot, project) && project.isDirectory) {
            "RAPP project must live under D:/Workspace"
        }

        val spec = readProjectSpec(project)
        val programFile = resolveProjectFile(project, spec.entry)
        val runtimeFile = resolveProjectFile(project, spec.runtime)
        val program = readBounded(programFile, MAX_PROGRAM_BYTES)
        val runtime = readBounded(runtimeFile, MAX_RUNTIME_BYTES)
        val programSha = sha256(program)
        val runtimeSha = sha256(runtime)

        val manifest =
            JSONObject()
                .put("schema", PACKAGE_SCHEMA)
                .put("id", spec.id)
                .put("name", spec.name)
                .put("launcherIcon", spec.launcherIcon)
                .put("abi", spec.abi)
                .put("adapter", spec.adapter)
                .put("engine", spec.adapter)
                .put("presentation", spec.presentation)
                .put(
                    "permissions",
                    JSONArray(spec.permissions.sorted())
                )
                .put("programSha256", programSha)
                .put("runtimeSha256", runtimeSha)

        val manifestBytes =
            manifest.toString()
                .toByteArray(Charsets.UTF_8)
        require(manifestBytes.size in 1..MAX_MANIFEST_BYTES) {
            "RAPP manifest is out of bounds"
        }

        val contentKey =
            sha256(
                manifestBytes +
                    programSha.toByteArray(Charsets.US_ASCII) +
                    runtimeSha.toByteArray(Charsets.US_ASCII)
            )
                .take(16)
        val target =
            File(
                artifactRoot,
                "${spec.id}-$contentKey.rapp"
            )
                .canonicalFile
        require(confinedTo(artifactRoot, target)) {
            "RAPP artifact escaped D:/Builds"
        }

        val temp =
            File(
                artifactRoot,
                ".${target.name}.tmp-${System.nanoTime()}"
            )
                .canonicalFile
        require(confinedTo(artifactRoot, temp)) {
            "RAPP temporary artifact escaped D:/Builds"
        }

        ZipOutputStream(temp.outputStream().buffered()).use { output ->
            putZip(output, PACKAGE_MANIFEST, manifestBytes)
            putZip(output, PROGRAM_ENTRY, program)
            putZip(output, RUNTIME_ENTRY, runtime)
        }

        require(temp.length() in 1..MAX_PACKAGE_BYTES) {
            temp.delete()
            "RAPP package is out of bounds"
        }

        if (target.exists()) {
            if (sha256(target) == sha256(temp)) {
                temp.delete()
            } else {
                require(target.delete()) {
                    temp.delete()
                    "Could not replace existing RAPP artifact"
                }
                require(temp.renameTo(target)) {
                    "Could not commit RAPP artifact"
                }
            }
        } else {
            require(temp.renameTo(target)) {
                "Could not commit RAPP artifact"
            }
        }

        val receipt =
            JSONObject()
                .put("schema", "riftbuild-rapp-pack-v1")
                .put("packageSchema", PACKAGE_SCHEMA)
                .put("id", spec.id)
                .put("name", spec.name)
                .put("abi", spec.abi)
                .put("adapter", spec.adapter)
                .put("engine", spec.adapter)
                .put("presentation", spec.presentation)
                .put(
                    "permissions",
                    JSONArray(spec.permissions.sorted())
                )
                .put("entry", spec.entry)
                .put("runtime", spec.runtime)
                .put("programBytes", program.size)
                .put("programSha256", programSha)
                .put("runtimeBytes", runtime.size)
                .put("runtimeSha256", runtimeSha)
                .put("artifactBytes", target.length())
                .put("artifactSha256", sha256(target))
                .put("state", "packed-rapp")

        return PackResult(target, receipt)
    }

    @Synchronized
    fun install(artifact: File): JSONObject {
        val file = artifact.canonicalFile
        require(confinedTo(artifactRoot, file) && file.isFile) {
            "RAPP install artifact must live under D:/Builds"
        }
        require(file.name.endsWith(".rapp")) {
            "RAPP install accepts only .rapp artifacts"
        }
        require(file.length() in 1..MAX_PACKAGE_BYTES) {
            "RAPP artifact size is out of bounds"
        }

        val payload = readPackage(file)
        val manifest = payload.manifest
        val id = manifest.getString("id")
        val name = manifest.getString("name")
        val launcherIcon = manifest.getString("launcherIcon")
        val abi = manifest.optString("abi", APP_ABI_SCHEMA).trim()
        val adapter =
            manifest.optString(
                "adapter",
                manifest.optString("engine")
            ).trim()
        val presentation = manifest.getString("presentation").trim()
        val permissions =
            readPermissions(
                manifest
            )

        validateIdentity(id, name, launcherIcon)
        validateRuntimeContract(abi, adapter, presentation)

        val target = File(programsRoot, id).canonicalFile
        val replacing = target.exists()
        require(confinedTo(programsRoot, target)) {
            "RAPP install target escaped C:/Programs"
        }

        val stage =
            File(
                stageRoot,
                "install-$id-${System.nanoTime()}"
            )
                .canonicalFile
        require(confinedTo(stageRoot, stage)) {
            "RAPP stage escaped stage root"
        }
        require(stage.mkdirs()) {
            "Could not create RAPP install stage"
        }

        var backupCleanupComplete = true
        try {
            writeAtomic(
                File(stage, PROGRAM_ENTRY),
                payload.program
            )
            writeAtomic(
                File(stage, RUNTIME_ENTRY),
                payload.runtime
            )

            val installedRuntime =
                JSONObject(manifest.toString())
                    .put("program", PROGRAM_ENTRY)
                    .put("runtime", RUNTIME_ENTRY)

            val packageJson =
                JSONObject()
                    .put("format", "rift-program-package-v1")
                    .put(
                        "manifest",
                        JSONObject()
                            .put("id", id)
                            .put("name", name)
                            .put("launcherIcon", launcherIcon)
                    )
                    .put("riftApp", installedRuntime)

            writeAtomic(
                File(stage, "package.json"),
                packageJson
                    .toString(2)
                    .toByteArray(Charsets.UTF_8)
            )

            val backup =
                File(
                    stageRoot,
                    "backup-$id-${System.nanoTime()}"
                )
                    .canonicalFile
            var backedUp = false

            if (target.exists()) {
                require(isManagedRapp(target, id)) {
                    "Refusing to replace non-RAPP program: $id"
                }
                require(target.renameTo(backup)) {
                    "Could not stage existing RAPP for replacement"
                }
                backedUp = true
            }

            if (!stage.renameTo(target)) {
                if (backedUp) {
                    runCatching { backup.renameTo(target) }
                }
                error("Could not commit installed RAPP")
            }

            if (backedUp) {
                // Revocation must succeed before a replacement is accepted.
                try {
                    RiftCorePackageGrants.revokeAll(appContext, id)
                } catch (error: Throwable) {
                    val rejected = File(
                        stageRoot, "rejected-$id-${System.nanoTime()}"
                    ).canonicalFile
                    check(confinedTo(stageRoot, rejected) && target.renameTo(rejected)) {
                        "Cannot quarantine replacement after grant failure: $id"
                    }
                    check(backup.renameTo(target)) {
                        "Cannot restore previous RAPP after grant failure: $id"
                    }
                    runCatching { deleteTreeBounded(rejected) }
                    throw error
                }
            }
            if (backedUp && backup.exists()) {
                backupCleanupComplete = runCatching {
                    deleteTreeBounded(backup)
                }.isSuccess
            }
        } catch (error: Throwable) {
            if (stage.exists()) {
                runCatching { deleteTreeBounded(stage) }
            }
            throw error
        }

        if (replacing) RiftCoreRuntime.sessions(appContext).invalidateInstalled(id)
        RiftCorePackageEvents.publish(id, if (replacing) "updated" else "installed")

        return JSONObject()
            .put("schema", "riftbuild-rapp-install-v1")
            .put("packageSchema", PACKAGE_SCHEMA)
            .put("id", id)
            .put("name", name)
            .put("abi", abi)
            .put("adapter", adapter)
            .put("engine", adapter)
            .put("presentation", presentation)
            .put(
                "permissions",
                JSONArray(permissions.sorted())
            )
            .put("programSha256", manifest.getString("programSha256"))
            .put("runtimeSha256", manifest.getString("runtimeSha256"))
            .put("installedPath", "/C:/Programs/$id")
            .put("cleanupComplete", backupCleanupComplete)
            .put("state", if (backupCleanupComplete) "installed-rapp" else "installed-rapp-backup-cleanup-pending")
    }

    /**
     * Core-managed RAPP uninstall. Only validated packages under C:/Programs
     * may be removed; all package-owned state.bin data is removed by default.
     * Removing the directory from C:/Programs is the visibility commit point.
     * If the grant write fails, restore the package before publishing a change.
     */
    @Synchronized
    fun uninstall(id: String): JSONObject {
        require(SAFE_ID.matches(id) && id !in RESERVED_IDS) {
            "Invalid managed RAPP uninstall identity"
        }
        val existing = readInstalledMetadata(id)
            ?: error("Installed managed RAPP not found: $id")
        val target = existing.first
        require(confinedTo(programsRoot, target) && isManagedRapp(target, id)) {
            "Refusing to uninstall a non-RAPP program"
        }
        val quarantine = File(
            stageRoot, "uninstall-$id-${System.nanoTime()}"
        ).canonicalFile
        require(confinedTo(stageRoot, quarantine) && !quarantine.exists()) {
            "RAPP uninstall staging path is invalid"
        }
        require(target.renameTo(quarantine)) {
            "Could not stage RAPP uninstall"
        }

        val grantsRevoked = try {
            RiftCorePackageGrants.revokeAll(appContext, id)
        } catch (error: Throwable) {
            check(quarantine.renameTo(target)) {
                "RAPP grant cleanup failed and package restore failed: $id"
            }
            throw error
        }

        // A package cannot keep running with its old identity after uninstall.
        val sessionStopped = RiftCoreRuntime.sessions(appContext).invalidateInstalled(id)
        RiftCorePackageEvents.publish(id, "uninstalled")

        val cleanup = runCatching { deleteTreeBounded(quarantine) }
        return JSONObject()
            .put("schema", "riftos.core.package-uninstall/1")
            .put("changeSchema", RiftCorePackageEvents.SCHEMA)
            .put("id", id)
            .put("installedPath", "/C:/Programs/$id")
            .put("state", if (cleanup.isSuccess) "uninstalled" else "uninstalled-cleanup-pending")
            .put("appData", "removed-with-package")
            .put("grantsRevoked", grantsRevoked)
            .put("sessionStopped", sessionStopped)
            .put("cleanupComplete", cleanup.isSuccess)
            .put("cleanupError", cleanup.exceptionOrNull()?.message.orEmpty())
    }

    fun launch(id: String): JSONObject {
        require(SAFE_ID.matches(id)) {
            "RAPP id is invalid"
        }
        require(handlesInstalled(id)) {
            "Installed RAPP not found: $id"
        }
        // Execution is Core-owned, including when no RiftShell Activity is
        // alive. Opening a graphical window is a best-effort presentation
        // request; failure to deliver it cannot prevent Core from running.
        val core = RiftCoreRuntime.lifecycle(appContext).start(id)
        val attached = core.getBoolean("attached")
        val presented = attached && RiftCoreAppLaunchRequests.requestLaunch(id)
        val queuedForRemoteShell = attached && !presented &&
            RiftCoreShellLaunchQueue.offer(id)
        return JSONObject()
            .put("schema", "riftbuild-rapp-launch-v1")
            .put("requestSchema", RiftCoreAppLaunchRequests.SCHEMA)
            .put("id", id)
            .put("accepted", attached)
            .put("core", core)
            .put("presentationDispatched", presented)
            .put("presentationQueuedForRemoteShell", queuedForRemoteShell)
            .put("state", if (presented) "launch-dispatched" else "core-running")
    }

    @Synchronized
    fun listInstalled(): JSONArray {
        val out = JSONArray()
        val directories =
            programsRoot.listFiles()
                ?.filter { it.isDirectory }
                ?.sortedBy { it.name.lowercase() }
                .orEmpty()

        var count = 0
        for (directory in directories) {
            if (++count > MAX_INSTALLED_APPS) break
            val packageFile = File(directory, "package.json")
            if (!packageFile.isFile || packageFile.length() !in 1..MAX_MANIFEST_BYTES.toLong()) {
                continue
            }

            val root =
                runCatching {
                    JSONObject(
                        packageFile.readText(Charsets.UTF_8)
                    )
                }.getOrNull()
                    ?: continue
            val manifest = root.optJSONObject("manifest") ?: continue
            val runtime = root.optJSONObject("riftApp") ?: continue
            if (runtime.optString("schema") != PACKAGE_SCHEMA) continue

            val id = manifest.optString("id").trim()
            if (
                id != directory.name ||
                !SAFE_ID.matches(id)
            ) {
                continue
            }

            out.put(
                JSONObject()
                    .put("id", id)
                    .put(
                        "name",
                        manifest
                            .optString("name", id)
                            .trim()
                            .ifBlank { id }
                    )
                    .put(
                        "launcherIcon",
                        manifest
                            .optString("launcherIcon", "□")
                    )
                    .put(
                        "abi",
                        runtime.optString("abi", APP_ABI_SCHEMA)
                    )
                    .put(
                        "adapter",
                        runtime.optString(
                            "adapter",
                            runtime.optString("engine")
                        )
                    )
                    .put(
                        "engine",
                        runtime.optString(
                            "adapter",
                            runtime.optString("engine")
                        )
                    )
                    .put(
                        "presentation",
                        runtime.optString("presentation")
                    )
                    .put(
                        "permissions",
                        runtime.optJSONArray("permissions")
                            ?: JSONArray()
                    )
                    .put(
                        "path",
                        "/C:/Programs/$id"
                    )
            )
        }
        return out
    }

    fun handlesInstalled(id: String): Boolean =
        runCatching {
            readInstalledMetadata(id) != null
        }.getOrDefault(false)

    fun loadInstalled(id: String): InstalledApp {
        val metadata =
            readInstalledMetadata(id)
                ?: error("Installed RAPP not found: $id")

        val root = metadata.first
        val packageJson = metadata.second
        val manifest =
            packageJson.getJSONObject("manifest")
        val runtime =
            packageJson.getJSONObject("riftApp")

        val abi = runtime.optString("abi", APP_ABI_SCHEMA).trim()
        val adapter =
            runtime.optString(
                "adapter",
                runtime.optString("engine")
            ).trim()
        val presentation = runtime.getString("presentation").trim()
        val permissions =
            readPermissions(
                runtime
            )
        validateRuntimeContract(abi, adapter, presentation)

        val programName = runtime.optString("program", PROGRAM_ENTRY)
        val runtimeName = runtime.optString("runtime", RUNTIME_ENTRY)
        require(programName == PROGRAM_ENTRY && runtimeName == RUNTIME_ENTRY) {
            "Installed RAPP payload paths are invalid"
        }

        val program =
            readBounded(
                File(root, programName),
                MAX_PROGRAM_BYTES
            )
        val runtimeBytes =
            readBounded(
                File(root, runtimeName),
                MAX_RUNTIME_BYTES
            )

        val expectedProgram =
            runtime.getString("programSha256")
        val expectedRuntime =
            runtime.getString("runtimeSha256")
        require(
            SHA256_HEX.matches(expectedProgram) &&
                sha256(program) == expectedProgram
        ) {
            "Installed RAPP program hash mismatch"
        }
        require(
            SHA256_HEX.matches(expectedRuntime) &&
                sha256(runtimeBytes) == expectedRuntime
        ) {
            "Installed RAPP runtime hash mismatch"
        }

        val stateFile =
            File(root, STATE_ENTRY)
                .canonicalFile
        require(confinedTo(root, stateFile)) {
            "Installed RAPP state escaped program root"
        }
        val effectiveProgram =
            if (stateFile.exists()) {
                require(stateFile.isFile) {
                    "Installed RAPP state is not a file"
                }
                readBounded(
                    stateFile,
                    MAX_PROGRAM_BYTES
                )
            } else {
                program
            }

        return InstalledApp(
            id = manifest.getString("id"),
            name = manifest
                .optString("name", id)
                .trim()
                .ifBlank { id },
            launcherIcon = manifest
                .optString("launcherIcon", "□"),
            abi = abi,
            adapter = adapter,
            presentation = presentation,
            permissions = permissions,
            program = effectiveProgram,
            runtime = runtimeBytes
        )
    }

    fun persistState(
        id: String,
        state: ByteArray
    ) {
        require(SAFE_ID.matches(id)) {
            "RAPP id is invalid"
        }
        require(state.size in 1..MAX_PROGRAM_BYTES) {
            "RAPP persisted state is out of bounds"
        }
        val metadata =
            readInstalledMetadata(id)
                ?: error("Installed RAPP not found: $id")
        val root = metadata.first
        val stateFile =
            File(root, STATE_ENTRY)
                .canonicalFile
        require(confinedTo(root, stateFile)) {
            "RAPP persisted state escaped program root"
        }
        writeAtomic(
            stateFile,
            state.copyOf()
        )
    }

    private fun readProjectSpec(project: File): ProjectSpec {
        val file =
            File(project, PROJECT_MANIFEST)
                .canonicalFile
        require(confinedTo(project, file) && file.isFile) {
            "RAPP project manifest missing: $PROJECT_MANIFEST"
        }
        require(file.length() in 1..MAX_MANIFEST_BYTES.toLong()) {
            "RAPP project manifest is out of bounds"
        }

        val json =
            JSONObject(
                file.readText(Charsets.UTF_8)
            )
        require(json.optString("schema") == PROJECT_SCHEMA) {
            "Unsupported RAPP project schema"
        }

        val id = json.optString("id").trim()
        val name =
            json.optString("name", id)
                .trim()
                .ifBlank { id }
        val launcherIcon =
            json.optString("launcherIcon", "□")
                .trim()
                .ifBlank { "□" }
        validateIdentity(id, name, launcherIcon)

        val abi =
            json.optString("abi", APP_ABI_SCHEMA)
                .trim()
        val adapter =
            json.optString(
                "adapter",
                json.optString("engine")
            ).trim()
        val presentation =
            json.optString("presentation")
                .trim()
        val permissions =
            readPermissions(
                json
            )
        validateRuntimeContract(abi, adapter, presentation)

        val entry = json.optString("entry").trim()
        val runtime = json.optString("runtime").trim()
        validateRelativePath(entry, "entry")
        validateRelativePath(runtime, "runtime")

        return ProjectSpec(
            id,
            name,
            launcherIcon,
            abi,
            adapter,
            presentation,
            permissions,
            entry,
            runtime
        )
    }

    private fun readPackage(file: File): PackagePayload {
        var manifestBytes: ByteArray? = null
        var program: ByteArray? = null
        var runtime: ByteArray? = null
        val seen = HashSet<String>()
        var totalBytes = 0L

        ZipFile(file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                require(!entry.isDirectory) {
                    "RAPP package contains a directory entry"
                }
                require(
                    entry.name == PACKAGE_MANIFEST ||
                        entry.name == PROGRAM_ENTRY ||
                        entry.name == RUNTIME_ENTRY
                ) {
                    "RAPP package contains unexpected entry: ${entry.name}"
                }
                require(seen.add(entry.name)) {
                    "RAPP package contains duplicate entry: ${entry.name}"
                }

                val limit =
                    when (entry.name) {
                        PACKAGE_MANIFEST -> MAX_MANIFEST_BYTES
                        PROGRAM_ENTRY -> MAX_PROGRAM_BYTES
                        RUNTIME_ENTRY -> MAX_RUNTIME_BYTES
                        else -> error("unreachable")
                    }
                val bytes =
                    readZipEntry(
                        zip,
                        entry,
                        limit
                    )
                totalBytes += bytes.size.toLong()
                require(totalBytes <= MAX_PACKAGE_BYTES) {
                    "RAPP package content exceeds bound"
                }

                when (entry.name) {
                    PACKAGE_MANIFEST -> manifestBytes = bytes
                    PROGRAM_ENTRY -> program = bytes
                    RUNTIME_ENTRY -> runtime = bytes
                }
            }
        }

        require(seen == setOf(PACKAGE_MANIFEST, PROGRAM_ENTRY, RUNTIME_ENTRY)) {
            "RAPP package is incomplete"
        }

        val manifest =
            JSONObject(
                manifestBytes!!
                    .toString(Charsets.UTF_8)
            )
        require(manifest.optString("schema") == PACKAGE_SCHEMA) {
            "Unsupported RAPP package schema"
        }

        val id = manifest.optString("id").trim()
        val name =
            manifest.optString("name", id)
                .trim()
                .ifBlank { id }
        val launcherIcon =
            manifest.optString("launcherIcon", "□")
                .trim()
                .ifBlank { "□" }
        validateIdentity(id, name, launcherIcon)
        val abi = manifest.optString("abi", APP_ABI_SCHEMA).trim()
        val adapter =
            manifest.optString(
                "adapter",
                manifest.optString("engine")
            ).trim()
        val presentation = manifest.optString("presentation").trim()
        readPermissions(
            manifest
        )
        validateRuntimeContract(abi, adapter, presentation)

        val programBytes = program!!
        val runtimeBytes = runtime!!
        val expectedProgram =
            manifest.optString("programSha256")
        val expectedRuntime =
            manifest.optString("runtimeSha256")
        require(
            SHA256_HEX.matches(expectedProgram) &&
                sha256(programBytes) == expectedProgram
        ) {
            "RAPP program hash mismatch"
        }
        require(
            SHA256_HEX.matches(expectedRuntime) &&
                sha256(runtimeBytes) == expectedRuntime
        ) {
            "RAPP runtime hash mismatch"
        }

        return PackagePayload(
            manifest,
            programBytes,
            runtimeBytes
        )
    }

    private fun readInstalledMetadata(
        id: String
    ): Pair<File, JSONObject>? {
        if (!SAFE_ID.matches(id)) return null
        val root =
            File(programsRoot, id)
                .canonicalFile
        if (
            !confinedTo(programsRoot, root) ||
            !root.isDirectory
        ) {
            return null
        }

        val packageFile =
            File(root, "package.json")
                .canonicalFile
        if (
            !confinedTo(root, packageFile) ||
            !packageFile.isFile ||
            packageFile.length() !in
                1..MAX_MANIFEST_BYTES.toLong()
        ) {
            return null
        }

        val json =
            JSONObject(
                packageFile.readText(Charsets.UTF_8)
            )
        val manifest =
            json.optJSONObject("manifest")
                ?: return null
        val runtime =
            json.optJSONObject("riftApp")
                ?: return null

        if (
            manifest.optString("id") != id ||
            runtime.optString("schema") != PACKAGE_SCHEMA
        ) {
            return null
        }

        return root to json
    }

    private fun isManagedRapp(
        root: File,
        id: String
    ): Boolean =
        runCatching {
            val packageFile =
                File(root, "package.json")
            if (
                !packageFile.isFile ||
                packageFile.length() !in
                    1..MAX_MANIFEST_BYTES.toLong()
            ) {
                return@runCatching false
            }
            val json =
                JSONObject(
                    packageFile.readText(Charsets.UTF_8)
                )
            json
                .optJSONObject("manifest")
                ?.optString("id") == id &&
                json
                    .optJSONObject("riftApp")
                    ?.optString("schema") == PACKAGE_SCHEMA
        }.getOrDefault(false)

    private fun readPermissions(
        json: JSONObject
    ): Set<String> {
        val array =
            json.optJSONArray(
                "permissions"
            )
                ?: JSONArray()

        require(array.length() <= 32) {
            "RAPP permission declaration count is out of bounds"
        }

        val out =
            linkedSetOf<String>()

        for (
            index in
                0 until array.length()
        ) {
            val capability =
                array.optString(
                    index
                )
                    .trim()

            require(
                capability in
                    RiftAppAbi.Capability.DECLARABLE
            ) {
                "RAPP declares unsupported capability: $capability"
            }
            require(
                out.add(
                    capability
                )
            ) {
                "RAPP declares duplicate capability: $capability"
            }
        }

        return out
    }

    private fun validateRuntimeContract(
        abi: String,
        adapter: String,
        presentation: String
    ) {
        require(abi == APP_ABI_SCHEMA) {
            "Unsupported RiftOS app ABI: $abi"
        }
        require(SAFE_TOKEN.matches(adapter)) {
            "RAPP adapter id is invalid"
        }
        require(SAFE_TOKEN.matches(presentation)) {
            "RAPP presentation id is invalid"
        }

        val runtimeAdapter =
            RiftAppAdapters.find(
                adapter
            )
                ?: error(
                    "Unsupported RiftOS app adapter: $adapter"
                )

        require(
            runtimeAdapter.presentation ==
                presentation
        ) {
            "RAPP presentation does not match adapter"
        }
    }

    private fun validateIdentity(
        id: String,
        name: String,
        launcherIcon: String
    ) {
        require(
            SAFE_ID.matches(id) &&
                id !in RESERVED_IDS
        ) {
            "RAPP id is invalid or reserved"
        }
        require(
            name.isNotBlank() &&
                name.length <= 64 &&
                !name.contains('\u0000')
        ) {
            "RAPP name is invalid"
        }
        require(
            launcherIcon.isNotBlank() &&
                launcherIcon.length <= 4 &&
                !launcherIcon.contains('\u0000')
        ) {
            "RAPP launcher icon is invalid"
        }
    }

    private fun resolveProjectFile(
        project: File,
        raw: String
    ): File {
        validateRelativePath(raw, "payload")
        val file =
            File(project, raw)
                .canonicalFile
        require(
            confinedTo(project, file) &&
                file.isFile
        ) {
            "RAPP payload not found: $raw"
        }
        return file
    }

    private fun validateRelativePath(
        raw: String,
        label: String
    ) {
        val value =
            raw.trim()
                .replace('\\', '/')
        require(
            value.isNotBlank() &&
                !value.startsWith("/") &&
                !value.contains(':')
        ) {
            "RAPP $label path must be project-relative"
        }
        val parts =
            value.split('/')
        require(
            parts.all {
                it.isNotBlank() &&
                    it != "." &&
                    it != ".."
            }
        ) {
            "RAPP $label path is invalid"
        }
    }

    private fun readBounded(
        file: File,
        maxBytes: Int
    ): ByteArray {
        require(file.isFile) {
            "RAPP payload is missing: ${file.name}"
        }
        require(file.length() in 1..maxBytes.toLong()) {
            "RAPP payload size is out of bounds: ${file.name}"
        }
        val bytes = file.readBytes()
        require(bytes.size in 1..maxBytes) {
            "RAPP payload read exceeded bound: ${file.name}"
        }
        return bytes
    }

    private fun readZipEntry(
        zip: ZipFile,
        entry: ZipEntry,
        maxBytes: Int
    ): ByteArray {
        if (entry.size >= 0L) {
            require(entry.size <= maxBytes.toLong()) {
                "RAPP entry exceeds bound: ${entry.name}"
            }
        }

        val output = ByteArrayOutputStream()
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                require(total <= maxBytes) {
                    "RAPP entry exceeds bound while reading: ${entry.name}"
                }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun putZip(
        output: ZipOutputStream,
        name: String,
        bytes: ByteArray
    ) {
        val entry =
            ZipEntry(name).apply {
                time = 0L
            }
        output.putNextEntry(entry)
        output.write(bytes)
        output.closeEntry()
    }

    private fun writeAtomic(
        file: File,
        bytes: ByteArray
    ) {
        val parent =
            file.parentFile
                ?: error("RAPP output has no parent")
        require(parent.mkdirs() || parent.isDirectory) {
            "Could not create RAPP output directory"
        }
        val temp =
            File(
                parent,
                ".${file.name}.tmp-${System.nanoTime()}"
            )
        temp.writeBytes(bytes)
        if (file.exists()) {
            require(file.delete()) {
                temp.delete()
                "Could not replace RAPP output"
            }
        }
        require(temp.renameTo(file)) {
            temp.delete()
            "Could not commit RAPP output"
        }
    }

    private fun deleteTreeBounded(root: File) {
        var count = 0
        fun remove(node: File) {
            require(++count <= 64) {
                "RAPP cleanup exceeded entry bound"
            }
            val link = Files.isSymbolicLink(node.toPath())
            if (!link) require(confinedTo(root, node)) {
                "RAPP cleanup escaped staging root"
            }
            if (node.isDirectory && !link) {
                node.listFiles()?.forEach(::remove)
            }
            require(node.delete() || !node.exists()) {
                "Could not remove RAPP staging path"
            }
        }
        remove(root)
    }

    private fun confinedTo(
        root: File,
        child: File
    ): Boolean {
        val rootPath = root.canonicalFile.path
        val childPath = child.canonicalFile.path
        return childPath == rootPath ||
            childPath.startsWith(
                rootPath + File.separator
            )
    }

    private fun sha256(
        bytes: ByteArray
    ): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") {
                "%02x".format(
                    it.toInt() and 0xff
                )
            }

    private fun sha256(
        file: File
    ): String =
        file.inputStream().use { input ->
            val digest =
                MessageDigest
                    .getInstance("SHA-256")
            val buffer =
                ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                digest.update(
                    buffer,
                    0,
                    count
                )
            }
            digest.digest()
                .joinToString("") {
                    "%02x".format(
                        it.toInt() and 0xff
                    )
                }
        }
}
