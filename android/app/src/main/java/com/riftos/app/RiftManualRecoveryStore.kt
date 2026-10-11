package com.riftos.app

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * APK-only recovery slot reader. Does not depend on Core, Shell, RAPP or MCP.
 * Patch A is observation only: no activation, grant, update or restore writer.
 * Patch B may add a trusted user-confirmed writer once device-tested.
 */
internal object RiftManualRecoveryStore {
    const val SCHEMA = "riftos.host.manual-recovery/1"
    private const val BACKUP_SCHEMA = "riftos.host.working-backup/1"
    private const val MAX_RECORD = 4096
    private const val MAX_DEX = 32L * 1024L * 1024L
    private val SHA = Regex("^[0-9a-f]{64}$")

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
        slot.put("startupMarkerPresent", if (Files.isSymbolicLink(marker.toPath())) {
            true // Unsafe marker is reported but never followed.
        } else marker.exists())
        return slot
    }

    /** Read-only, process-independent snapshot; never initializes Core or Shell. */
    fun status(context: Context): JSONObject = JSONObject()
        .put("schema", SCHEMA)
        .put("readOnlyGate", "A")
        .put("recoveryProcess", ":riftRecovery")
        .put("core", inspect(context, "core"))
        .put("shell", inspect(context, "shell"))
}
