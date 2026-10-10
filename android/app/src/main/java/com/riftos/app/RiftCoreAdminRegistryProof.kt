package com.riftos.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * C1.4-C2-A: signer-stamped, journalled Core-only runtime REGISTRY transaction.
 *
 * This is only a safe filesystem/registry foundation for a later provider
 * admission gate. It writes an EMPTY provider registry for one synchronous
 * transaction, checks its exact bytes, then removes it. No external provider
 * is registered, enabled or executed. An already configured registry is
 * NEVER overwritten or modified. Only a trusted approved Core ticket can
 * invoke this through the authenticated production RiftShell Binder.
 */
internal object RiftCoreAdminRegistryProof {
    const val SCHEMA = "riftos.core.admin-registry-proof/1"
    const val OPERATION = "runtime.register"
    const val TARGET = "core://runtime-providers/registry.json#empty-c2a"
    private const val PREFS = "rift-core-admin-registry-proof"
    private const val PENDING = "registryPending"
    private const val DIR_CREATED = "registryDirectoryCreated"
    private val lock = Any()
    // Core-only bounded diagnostics: no paths, signing material or bearer
    // tokens are exposed to RAPPs or persisted in the runtime registry.
    private var lastFailureStage = "none"
    private var lastFailureType = "none"
    private var lastFailureErrno = 0

    private fun root(context: Context): File =
        File(context.applicationContext.filesDir, "riftfs").canonicalFile

    private fun registry(context: Context): File {
        val base = root(context)
        val destination = File(base, "system/runtime-providers/registry.json").absoluteFile
        require(destination.canonicalPath == destination.path &&
            destination.path.startsWith(base.path + File.separator)) {
            "Core runtime registry path escaped its private filesystem"
        }
        return destination
    }

    private fun temp(context: Context): File {
        val file = File(registry(context).parentFile, ".c14c2-registry-proof.tmp").absoluteFile
        require(file.canonicalPath == file.path) {
            "Core registry temporary path is not canonical"
        }
        return file
    }

    @Suppress("DEPRECATION")
    private fun ownSigner(context: Context): String {
        val pm = context.packageManager
        val info = pm.getPackageInfo(
            context.packageName,
            if (Build.VERSION.SDK_INT >= 28)
                PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        )
        val certificates = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
        } else info.signatures?.map { it.toByteArray() }.orEmpty()
        require(certificates.size == 1) {
            "Core C2 registry proof requires one Android-verified installed APK signer"
        }
        return MessageDigest.getInstance("SHA-256").digest(certificates.single())
            .joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
    }

    private fun marker(context: Context): ByteArray =
        (JSONObject().put("schema", RiftExternalRuntimeProviders.REGISTRY_SCHEMA)
            .put("providers", JSONArray())
            .put("c14c2Proof", "empty-registry-only")
            .put("installedCoreSignerSha256", ownSigner(context))
            .toString() + "\n").toByteArray(Charsets.UTF_8)

    private fun pending(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Core bootstrap cleanup is fail-closed: a pre-existing production
     * registry is never deleted. If a crash left OUR exact marked empty
     * registry, remove only that known artifact and its private temp.
     */
    fun recover(context: Context): JSONObject = synchronized(lock) {
        val prefs = pending(context)
        val wasPending = prefs.getBoolean(PENDING, false)
        if (wasPending) {
            val target = registry(context)
            val scratch = temp(context)
            val expected = marker(context)
            for (item in listOf(target, scratch)) {
                if (item.exists()) {
                    // Interrupted exclusive-create writes may leave a partial
                    // target; never remove an unverified path or file.
                    val bounded = item.isFile &&
                        item.length() <= expected.size.toLong()
                    val bytes = if (bounded) item.readBytes() else ByteArray(0)
                    // Under Android's private-app filesystem, hard links may
                    // be denied. The exclusive-create target can be interrupted
                    // mid-write. Only accept its prefix when OUR fully verified
                    // journalled scratch is still present. Never delete an
                    // unknown/replaced production registry.
                    val scratchExact = scratch.isFile &&
                        scratch.length() == expected.size.toLong() &&
                        scratch.readBytes().contentEquals(expected)
                    val expectedPrefix = bounded &&
                        bytes.contentEquals(expected.copyOfRange(0, bytes.size)) &&
                        (item == scratch || (item == target && scratchExact))
                    check(bounded &&
                        (bytes.contentEquals(expected) || expectedPrefix)) {
                        "Core C2 interrupted registry proof contains unknown data; manual inspection required"
                    }
                    check(item.delete()) { "Core C2 interrupted registry cleanup failed" }
                }
            }
            val parent = target.parentFile ?: error("Core C2 registry directory absent")
            if (prefs.getBoolean(DIR_CREATED, false) && parent.exists()) {
                check(parent.isDirectory && (parent.list()?.isEmpty() == true) &&
                    parent.delete()) {
                    "Core C2 created registry directory could not be restored"
                }
            }
            check(prefs.edit().remove(PENDING).remove(DIR_CREATED).commit()) {
                "Core C2 registry recovery journal clear failed"
            }
        }
        JSONObject().put("schema", SCHEMA)
            .put("recoveredInterruptedTransaction", wasPending)
            .put("pendingJournal", false)
    }

    fun writeAndRollback(context: Context): JSONObject = synchronized(lock) {
        lastFailureStage = "none"
        lastFailureType = "none"
        lastFailureErrno = 0
        var stage = "recover"
        var transactionFailureStage: String? = null
        try {
        recover(context)
        stage = "resolve-registry"
        val target = registry(context)
        val scratch = temp(context)
        // A real installed runtime registry is protected even during QA.
        check(!target.exists() && !scratch.exists()) {
            "Core C2 cannot touch an existing runtime registry or temporary file"
        }
        val parent = target.parentFile ?: error("Core C2 parent directory absent")
        val created = !parent.exists()
        // Resolve installed Core signing identity BEFORE creating a journal.
        stage = "verify-installed-signer"
        val expected = marker(context)
        // Persist directory ownership intent BEFORE any filesystem change;
        // recovery must also handle death after mkdir but before file write.
        val prefs = pending(context)
        stage = "journal-begin"
        check(prefs.edit().putBoolean(PENDING, true)
            .putBoolean(DIR_CREATED, created).commit()) {
            "Core C2 registry journal persistence failed"
        }
        var verified = false
        try {
            stage = "prepare-directory"
            if (created) check(parent.mkdirs()) { "Core C2 registry directory unavailable" }
            check(parent.isDirectory && registry(context).path == target.path) {
                "Core C2 registry directory changed"
            }
            stage = "create-scratch"
            check(scratch.createNewFile()) { "Core C2 temporary registry exists" }
            stage = "write-and-fsync-scratch"
            FileOutputStream(scratch).use { stream ->
                stream.write(expected)
                stream.fd.sync()
            }
            stage = "verify-scratch"
            check(scratch.readBytes().contentEquals(expected)) {
                "Core C2 temporary registry bytes differ"
            }
            // Signed #678 proved Android rejects both hard-link APIs with
            // EACCES (errno=13). Use a kernel-enforced O_CREAT|O_EXCL open
            // instead: this MUST fail if the final registry already exists,
            // and must never follow a symlink or replace production content.
            // Unlike rename/link this is not pathname-atomic during the
            // write; all Core registry readers therefore share the lock below
            // and cannot observe partial transaction bytes.
            stage = "exclusive-create-only-publish"
            val flags = android.system.OsConstants.O_WRONLY or
                android.system.OsConstants.O_CREAT or
                android.system.OsConstants.O_EXCL or
                android.system.OsConstants.O_NOFOLLOW
            FileOutputStream(android.system.Os.open(target.absolutePath, flags, 0x180))
                .use { stream ->
                stream.write(expected)
                stream.fd.sync()
            }
            stage = "verify-published-registry"
            check(target.readBytes().contentEquals(expected)) {
                "Core C2 published registry verification failed"
            }
            val published = JSONObject(target.readText(Charsets.UTF_8))
            check(published.getString("schema") ==
                RiftExternalRuntimeProviders.REGISTRY_SCHEMA &&
                published.getJSONArray("providers").length() == 0 &&
                published.getString("installedCoreSignerSha256") == ownSigner(context)) {
                "Core C2 registry schema, empty set or signer mismatched"
            }
            verified = true
        } catch (failure: Exception) {
            // Preserve the ORIGINAL failure even after mandatory cleanup.
            transactionFailureStage = stage
            throw failure
        } finally {
            stage = "restore-absent-registry"
            // Never remove unverified production registry data. A failed
            // O_EXCL write can leave a prefix, but only while OUR verified
            // scratch file and persisted pending journal prove ownership.
            val scratchExact = scratch.isFile &&
                scratch.length() == expected.size.toLong() &&
                scratch.readBytes().contentEquals(expected)
            for (file in listOf(target, scratch)) {
                if (file.exists()) {
                    check(file.canonicalPath == file.absolutePath && file.isFile &&
                        file.length() <= expected.size.toLong()) {
                        "Core C2 rollback refuses unknown registry file"
                    }
                    val bytes = file.readBytes()
                    val knownPrefix = bytes.contentEquals(
                        expected.copyOfRange(0, bytes.size)
                    ) && (file == scratch || (file == target && scratchExact))
                    check(bytes.contentEquals(expected) || knownPrefix) {
                        "Core C2 rollback refuses to remove unknown registry data"
                    }
                    check(file.delete()) { "Core C2 registry rollback removal failed" }
                }
            }
            if (created && parent.exists()) {
                check(parent.isDirectory && parent.list()?.isEmpty() == true &&
                    parent.delete()) {
                    "Core C2 registry rollback directory cleanup failed"
                }
            }
            stage = "journal-clear"
            check(prefs.edit().remove(PENDING).remove(DIR_CREATED).commit()) {
                "Core C2 registry rollback journal clear failed"
            }
        }
        stage = "verify-restored-state"
        check(verified && !target.exists() && !scratch.exists() &&
            !prefs.getBoolean(PENDING, false)) {
            "Core C2 registry proof did not restore its original absent state"
        }
        JSONObject().put("schema", SCHEMA)
            .put("transactionCommitted", true)
            .put("rolledBack", true)
            .put("executedPrivilegedEffect", true)
            .put("providerRegistered", false)
            .put("registryRestored", true)
            .put("pendingJournal", false)
        } catch (failure: Exception) {
            // Sanitized, exact failing stage and exception type are visible
            // only through the existing trusted Core admin/status boundary.
            lastFailureStage = transactionFailureStage ?: stage
            lastFailureType = failure.javaClass.simpleName.take(48)
            // Android syscall errno is numeric and safe to expose; never
            // return arbitrary exception messages or private disk paths.
            lastFailureErrno =
                (failure as? android.system.ErrnoException)?.errno ?: 0
            throw failure
        }
    }

    /**
     * Guard the entire Core registry read while the transient C2-A target can
     * be written through O_EXCL. Other readers block until rollback finishes,
     * and a crash journal prevents exposing incomplete registry bytes.
     */
    fun <T> withSafeRegistryRead(context: Context, read: () -> T): T =
        synchronized(lock) {
            check(!pending(context).getBoolean(PENDING, false)) {
                "Core temporary registry transaction is pending recovery"
            }
            read()
        }

    fun status(context: Context): JSONObject = synchronized(lock) {
        // Read-only diagnostics must still work when the path guard itself
        // rejected the transaction. Unknown filesystem state fails closed.
        val files = runCatching { registry(context) to temp(context) }.getOrNull()
        JSONObject().put("schema", SCHEMA)
            .put("operation", OPERATION)
            .put("target", TARGET)
            .put("availableOnlyWhenUnconfigured", true)
            .put("generalRuntimeRegistrationEnabled", false)
            .put("pendingJournal", pending(context).getBoolean(PENDING, false))
            .put("pathStatusAvailable", files != null)
            .put("registryExists", files?.first?.exists() ?: true)
            .put("temporaryRegistryExists", files?.second?.exists() ?: true)
            .put("lastFailureStage", lastFailureStage)
            .put("lastFailureType", lastFailureType)
            .put("lastFailureErrno", lastFailureErrno)
    }
}
