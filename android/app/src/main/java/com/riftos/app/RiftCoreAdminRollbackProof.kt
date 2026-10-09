package com.riftos.app

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * C1.4-C1: the ONLY currently executable administrator effect is a disposable
 * Core-owned C: canary which is synchronously deleted in the same transaction.
 *
 * This does NOT grant Android root, modify production RAPPs, install packages,
 * register runtimes, or terminate any process. A durable journal allows
 * Core-process restart to clean up an interrupted canary transaction.
 * The caller supplies NO raw filesystem path or file bytes.
 */
internal object RiftCoreAdminRollbackProof {
    const val SCHEMA = "riftos.core.admin-rollback-proof/1"
    const val OPERATION = "system.fs.write"
    const val TARGET = "/C:/RiftOS/.c14c-rollback.txt"
    private const val PREFS = "rift-core-admin-rollback-proof"
    private const val PENDING = "canaryPending"
    private val MARKER = "RiftOS C1.4-C1 isolated rollback canary\n".toByteArray(Charsets.UTF_8)
    private val lock = Any()

    private fun canary(context: Context): File {
        val riftfs = File(context.applicationContext.filesDir, "riftfs").canonicalFile
        val file = File(riftfs, RiftVolumePaths.resolveRelative(TARGET))
        val absolute = file.absoluteFile
        // Reject symlinks in the parent or leaf. Core owns this fixed target.
        require(absolute.canonicalPath == absolute.path) {
            "Core admin rollback proof target is not a canonical local file"
        }
        require(absolute.path.startsWith(riftfs.canonicalPath + File.separator)) {
            "Core admin rollback target outside RiftFS"
        }
        return absolute
    }

    /**
     * Run in the default Core process on initialization, before accepting new
     * admin effects, so a terminated process cannot leave a temporary canary.
     * NEVER delete unknown paths; only the fixed journalled test artifact.
     */
    fun recover(context: Context): JSONObject = synchronized(lock) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pending = prefs.getBoolean(PENDING, false)
        if (pending) {
            val file = canary(context)
            if (file.exists()) {
                require(file.isFile && file.length() <= MARKER.size.toLong()) {
                    "Interrupted Core admin rollback artifact requires manual inspection"
                }
                check(file.delete()) { "Interrupted Core admin canary rollback failed" }
            }
            check(prefs.edit().remove(PENDING).commit()) {
                "Interrupted Core admin rollback journal could not clear"
            }
        }
        JSONObject().put("schema", SCHEMA)
            .put("recoveredInterruptedTransaction", pending)
            .put("pendingJournal", false)
    }

    /**
     * May be called ONLY after a fresh Core-approved, exact-scope one-use
     * ticket has been consumed in the authenticated Binder admin IPC.
     * Filesystem rollback is mandatory before a successful response.
     */
    fun writeAndRollback(context: Context): JSONObject = synchronized(lock) {
        recover(context)
        val file = canary(context)
        check(!file.exists()) { "Reserved Core admin canary path already exists" }
        val parent = file.parentFile ?: error("No Core admin canary directory")
        // The pre-existing protected RiftOS system root is a prerequisite.
        // Never create directories: rollback must restore the exact tree.
        check(parent.isDirectory) {
            "Core admin canary directory unavailable or uninitialized"
        }
        // Re-check after mkdirs to forbid symlink substitution.
        check(canary(context).absolutePath == file.absolutePath) {
            "Core admin canary directory changed"
        }
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        check(prefs.edit().putBoolean(PENDING, true).commit()) {
            "Core admin rollback journal persistence failed"
        }
        var wroteAndVerified = false
        try {
            check(file.createNewFile()) { "Core admin canary already exists" }
            file.outputStream().use { output ->
                output.write(MARKER)
                output.fd.sync()
            }
            check(file.readBytes().contentEquals(MARKER)) {
                "Core admin canary verification failed"
            }
            wroteAndVerified = true
        } finally {
            // If process dies here, recover() uses the durable pending journal.
            if (file.exists()) {
                check(file.delete()) { "Core admin canary rollback delete failed" }
            }
            check(prefs.edit().remove(PENDING).commit()) {
                "Core admin rollback journal clear failed"
            }
        }
        check(wroteAndVerified && !file.exists() && !prefs.getBoolean(PENDING, false)) {
            "Core admin write/rollback did not complete"
        }
        JSONObject().put("schema", SCHEMA)
            .put("executedPrivilegedEffect", true)
            .put("transactionCommitted", true)
            .put("rolledBack", true)
            .put("target", TARGET)
            .put("bytesWrittenThenRemoved", MARKER.size)
            .put("pendingJournal", false)
    }

    fun status(context: Context): JSONObject = synchronized(lock) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        JSONObject().put("schema", SCHEMA)
            .put("operation", OPERATION)
            .put("target", TARGET)
            .put("generalAdminEffectsEnabled", false)
            .put("isolatedRollbackCanaryEnabled", true)
            .put("pendingJournal", prefs.getBoolean(PENDING, false))
            .put("canaryExists", canary(context).exists())
    }
}
