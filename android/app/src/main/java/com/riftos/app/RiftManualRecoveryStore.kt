package com.riftos.app

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * APK-only recovery slot reader. Does not depend on Core, Shell, RAPP or MCP.
 * Only explicit Recovery UI clicks save or restore verified user-selected
 * revisions. Neither startup nor installation overwrites the backup.
 * The embedded fallback remains user-selectable without starting Core/Shell.
 */
internal object RiftManualRecoveryStore {
    const val SCHEMA = "riftos.host.manual-recovery/1"
    private const val BACKUP_SCHEMA = "riftos.host.working-backup/1"
    private const val MAX_RECORD = 4096
    private const val MAX_DEX = 32L * 1024L * 1024L
    private val SHA = Regex("^[0-9a-f]{64}$")

    private fun requireManualRecoveryProcess(context: Context) {
        require(android.os.Build.VERSION.SDK_INT >= 28 &&
            android.app.Application.getProcessName() ==
                context.packageName + ":riftRecovery") {
            "Only independent Recovery UI may save or restore selected components"
        }
    }

    private fun readBackup(context: Context, component: String): JSONObject? {
        val root = RiftBootstrapComponentStore.root(context)
        require(!Files.isSymbolicLink(root.toPath())) { "Recovery root is symlinked" }
        val file = File(root, "$component.working-backup.json")
        for (suffix in listOf("", ".bak", ".new")) {
            require(!Files.isSymbolicLink(File(file.path + suffix).toPath())) {
                "Recovery backup record path is symlinked"
            }
        }
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        val bytes = AtomicFile(file).openRead().use { input ->
            val bounded = ByteArray(MAX_RECORD + 1)
            val count = input.read(bounded)
            require(count in 1..MAX_RECORD && input.read() == -1) {
                "Recovery backup record out of bounds"
            }
            bounded.copyOf(count)
        }
        val record = JSONObject(String(bytes, Charsets.UTF_8))
        val keys = mutableSetOf<String>()
        val iterator = record.keys()
        while (iterator.hasNext()) keys.add(iterator.next())
        require(keys == setOf("schema", "component", "candidate") &&
            record.getString("schema") == BACKUP_SCHEMA &&
            record.getString("component") == component) {
            "Recovery backup record schema invalid"
        }
        return record.getJSONObject("candidate")
    }

    private fun backupFile(context: Context, component: String): File {
        require(component == "core" || component == "shell") { "Invalid recovery component" }
        val root = RiftBootstrapComponentStore.root(context)
        require(root.isDirectory && !Files.isSymbolicLink(root.toPath())) {
            "Recovery store is unavailable"
        }
        val file = File(root, "$component.working-backup.json")
        for (suffix in listOf("", ".new", ".bak")) {
            require(!Files.isSymbolicLink(File(file.path + suffix).toPath())) {
                "Symlinked recovery backup record"
            }
        }
        return file
    }

    private fun writeBackup(context: Context, component: String, candidate: JSONObject) {
        val output = JSONObject().put("schema", BACKUP_SCHEMA)
            .put("component", component)
            .put("candidate", JSONObject(candidate.toString()))
            .toString().toByteArray(Charsets.UTF_8)
        require(output.size in 1..MAX_RECORD) { "Recovery backup receipt too large" }
        val file = backupFile(context, component)
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(output)
            atomic.finishWrite(stream)
        } catch (failure: Throwable) {
            atomic.failWrite(stream)
            throw failure
        }
    }

    /**
     * Explicit user's Save Backup action from the independent Recovery process.
     * No install, boot, crash, health test or automatic updater may overwrite it.
     * We verify the sealed SHA and structural receipt, NOT feature quality.
     */
    @Synchronized
    fun saveSelectedBackup(context: Context, component: String): JSONObject {
        requireManualRecoveryProcess(context)
        require(component == "core" || component == "shell")
        val selected = RiftBootstrapComponentStore.active(context, component)
            ?: error("Embedded fallback is built into the APK; no external DEX to save")
        val sha = verifyExternal(context, component, selected)
        writeBackup(context, component, selected)
        return JSONObject().put("schema", SCHEMA)
            .put("component", component)
            .put("backupSaved", true)
            .put("sha256", sha)
            .put("selectedUnchanged", true)
    }

    /**
     * Recovery UI calls this only after explicit on-screen user confirmation.
     * Pointer changes are for NEXT process start. Never hot-swap or start Core.
     */
    @Synchronized
    fun restore(context: Context, component: String, selection: String): JSONObject {
        requireManualRecoveryProcess(context)
        require(component == "core" || component == "shell") {
            "Unknown protected recovery component"
        }
        require(selection == "embedded" || selection == "working-backup") {
            "Unknown recovery selection"
        }
        // The embedded fallback must survive a completely broken or malformed
        // external activation pointer, not only a valid failed candidate.
        val active = runCatching {
            RiftBootstrapComponentStore.active(context, component)
        }.getOrNull()
        val sha = if (selection == "working-backup") {
            val backup = readBackup(context, component)
                ?: error("No manually saved backup is available")
            val targetSha = verifyExternal(context, component, backup)
            require(targetSha != active?.optString("sha256")) {
                "Backup is already selected"
            }
            // Recovery changes the NEXT boot pointer only on this explicit
            // Restore click. Never infer health from crashes or revision ledger.
            RiftBootstrapComponentStore.writeProtectedPointer(context, component, backup)
            RiftBootstrapComponentStore.clearProtectedStartupMarker(context, component)
            targetSha
        } else {
            // This signed APK still contains the device-proven migration Core.
            if (component == "core") {
                RiftBootstrapComponentStore.resetCoreToEmbedded(context)
            } else {
                RiftBootstrapComponentStore.resetShellToEmbedded(context)
            }
            null
        }
        return JSONObject().put("schema", SCHEMA)
            .put("component", component)
            .put("selected", if (sha == null) "embedded" else "verified-backup")
            .put("sha256", sha ?: JSONObject.NULL)
            .put("restartRequired", true)
            .put("hotSwapped", false)
    }

    /** Re-check sealed bytes AND exact trusted qualification without loading the candidate. */
    private fun verifyExternal(context: Context, component: String, candidate: JSONObject): String {
        val keys = mutableSetOf<String>()
        val iterator = candidate.keys()
        while (iterator.hasNext()) keys.add(iterator.next())
        require(keys == setOf("schema", "api", "component", "entrypoint", "sha256") &&
            candidate.getString("schema") == RiftBootstrapComponentStore.SCHEMA &&
            candidate.getInt("api") == 1 &&
            candidate.getString("component") == component) {
            "Recovery candidate record invalid"
        }
        val sha = candidate.getString("sha256")
        val entry = candidate.getString("entrypoint")
        require(sha.matches(SHA) &&
            entry.matches(Regex("^com\\.riftos\\.external\\.$component\\.[A-Za-z_][A-Za-z0-9_.]*$"))) {
            "Recovery candidate identity invalid"
        }
        val file = RiftBootstrapComponentStore.dexFile(context, component, sha)
        require(file.isFile && !Files.isSymbolicLink(file.toPath()) &&
            !file.canWrite() && file.length() in 112L..MAX_DEX) {
            "Recovery candidate DEX is absent or unsealed"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(buf)
                if (count < 0) break
                if (count > 0) digest.update(buf, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") {
            "%02x".format(it.toInt() and 255)
        }
        require(actual == sha) { "Recovery backup DEX hash differs from receipt" }
        RiftProtectedRevisionRecovery.verifiedReceipt(context, component, candidate)
        return sha
    }

    private fun inspect(context: Context, component: String): JSONObject {
        require(component == "core" || component == "shell")
        val slot = JSONObject().put("component", component)
            .put("firstInstallFallback", "embedded-migration")
            .put("restoreEnabled", false)

        val active = runCatching {
            RiftBootstrapComponentStore.active(context, component)
        }
        active.fold(onSuccess = { value ->
            if (value == null) slot.put("active", "embedded")
            else slot.put("active", "external")
                .put("activeSha256", value.getString("sha256"))
        }, onFailure = {
            slot.put("active", "invalid")
                .put("activeError", it.javaClass.simpleName)
        })

        val backup = runCatching {
            readBackup(context, component)?.let {
                verifyExternal(context, component, it)
            }
        }
        backup.fold(onSuccess = { sha ->
            if (sha == null) slot.put("backup", "absent")
            else slot.put("backup", "verified-external")
                .put("backupSha256", sha)
        }, onFailure = {
            slot.put("backup", "invalid")
                .put("backupError", it.javaClass.simpleName)
        })
        val root = RiftBootstrapComponentStore.root(context)
        val marker = File(root, "$component.booting")
        val startupMarkerPresent = Files.isSymbolicLink(marker.toPath()) || marker.exists()
        val restoreEmbeddedEnabled =
            slot.optString("active") != "embedded" || startupMarkerPresent
        val restoreWorkingBackupEnabled =
            slot.optString("backup") == "verified-external" &&
                slot.optString("activeSha256") != slot.optString("backupSha256")
        slot.put("startupMarkerPresent", startupMarkerPresent)
            .put("saveBackupEnabled", slot.optString("active") == "external")
            .put("restoreEmbeddedEnabled", restoreEmbeddedEnabled)
            .put("restoreWorkingBackupEnabled", restoreWorkingBackupEnabled)
            .put("restoreEnabled", restoreEmbeddedEnabled || restoreWorkingBackupEnabled)
        return slot
    }

    /** Read-only status; Recovery actions are separately user-confirmed. */
    fun status(context: Context): JSONObject = JSONObject()
        .put("schema", SCHEMA)
        .put("gate", "B")
        .put("recoveryProcess", ":riftRecovery")
        .put("core", inspect(context, "core"))
        .put("shell", inspect(context, "shell"))
}
