package com.riftos.app

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Process
import android.os.SystemClock
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * C1.4-B: Core-owned, ephemeral and single-use trusted administrative consent.
 *
 * ONLY the OS-attested production graphical :riftShell Binder caller may
 * request/decide/revoke/consume a ticket. The caller's installed APK signer,
 * PID, Core-selected actor and exact operation/target are bound to every
 * ticket. NEVER persist bearer tokens; process death revokes them all.
 *
 * The original B scope remains no-effect. C1.4-C1 adds ONLY a distinct
 * exact-target ephemeral Core-owned C: canary write-and-rollback transaction;
 * never an arbitrary system-file write or real installation/registration/kill.
 */
internal object RiftCoreAdminConsent {
    const val SCHEMA = "riftos.core.admin-consent/1"
    private const val ACTOR = "riftos.native-admin-panel"
    private const val TTL_MS = 45_000L
    private const val MAX_TICKETS = 8
    private val random = SecureRandom()
    private val lock = Any()

    private data class Ticket(
        val bearer: String,
        val pid: Int,
        val signer: String,
        val operation: String,
        val target: String,
        val createdAt: Long,
        var approved: Boolean = false
    )
    private val tickets = LinkedHashMap<String, Ticket>()

    // Deliberately one harmless test scope for the B gate. No Core software,
    // runtime or protected-process effect is attached to this ticket.
    private fun validScope(operation: String, target: String): Boolean =
        (operation == "system.fs.read" && target == "/C:/System") ||
            (operation == RiftCoreAdminRollbackProof.OPERATION &&
                target == RiftCoreAdminRollbackProof.TARGET) ||
            (operation == RiftCoreAdminRegistryProof.OPERATION &&
                target == RiftCoreAdminRegistryProof.TARGET)

    @Suppress("DEPRECATION")
    private fun installedSigner(context: Context): String {
        val manager = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 28) {
            manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else info.signatures
        require(!signatures.isNullOrEmpty()) { "Admin Core could not verify installed signer" }
        val fingerprints = signatures.map {
            MessageDigest.getInstance("SHA-256")
                .digest(it.toByteArray())
                .joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
        }.sorted()
        return fingerprints.joinToString(":")
    }

    private fun authenticated(context: Context, pid: Int): String {
        require(Process.myPid() != pid && pid > 0 &&
            Binder.getCallingPid() == pid) {
            "Admin caller must be real remote graphical Binder shell"
        }
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        require(manager.runningAppProcesses?.any {
            it.pid == pid && it.uid == context.applicationInfo.uid &&
                it.processName == context.packageName + ":riftShell"
        } == true) {
            "Admin caller is not OS-attested production graphical shell"
        }
        // Binder provider independently authenticates the exact OS process
        // UID, PID and :riftShell name before dispatching here.
        return installedSigner(context)
    }

    private fun expire(context: Context) {
        val now = SystemClock.elapsedRealtime()
        val expired = tickets.values.filter { now - it.createdAt >= TTL_MS }
        for (entry in expired) {
            RiftCoreSystemCapabilities.recordDecision(
                context, ACTOR, entry.operation, "expired", "ticket-expired"
            )
            tickets.remove(entry.bearer)
        }
    }

    private fun ticket(context: Context, callerPid: Int, bearer: String): Ticket {
        expire(context)
        require(bearer.length == 32 && bearer.all { it in "0123456789abcdef" }) {
            "Admin bearer ticket format invalid"
        }
        val current = tickets[bearer] ?: throw SecurityException("Admin ticket absent or used")
        require(current.pid == callerPid && current.signer == authenticated(context, callerPid)) {
            "Admin ticket caller or installed signer changed"
        }
        return current
    }

    fun request(context: Context, callerPid: Int, operation: String, target: String): JSONObject =
        synchronized(lock) {
            val signer = authenticated(context, callerPid)
            expire(context)
            require(validScope(operation, target)) {
                "Admin test scope is not a recognized privileged effect"
            }
            require(tickets.size < MAX_TICKETS) { "Core admin ticket queue full" }
            val tokenBytes = ByteArray(16)
            random.nextBytes(tokenBytes)
            val bearer = tokenBytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            RiftCoreSystemCapabilities.recordDecision(
                context, ACTOR, operation, "requested", "trusted-ui-only"
            )
            tickets[bearer] = Ticket(
                bearer, callerPid, signer, operation, target,
                SystemClock.elapsedRealtime()
            )
            JSONObject().put("schema", SCHEMA)
                .put("ticket", bearer)
                .put("operation", operation)
                .put("target", target)
                .put("expiresAfterMs", TTL_MS)
                .put("requiresConfirmation", true)
                .put("effectEnabled", false)
        }

    fun decide(
        context: Context, callerPid: Int, bearer: String, approved: Boolean
    ): JSONObject = synchronized(lock) {
        val value = ticket(context, callerPid, bearer)
        require(!value.approved) { "Admin ticket already decided" }
        RiftCoreSystemCapabilities.recordDecision(
            context, ACTOR, value.operation,
            if (approved) "approved" else "denied",
            if (approved) "single-use-proof-only" else "user-denied-or-cancelled"
        )
        if (approved) value.approved = true else tickets.remove(bearer)
        JSONObject().put("schema", SCHEMA)
            .put("accepted", true)
            .put("approved", approved)
            .put("effectEnabled", false)
    }

    fun revoke(context: Context, callerPid: Int, bearer: String): JSONObject =
        synchronized(lock) {
            val value = ticket(context, callerPid, bearer)
            RiftCoreSystemCapabilities.recordDecision(
                context, ACTOR, value.operation, "revoked", "explicit-user-revocation"
            )
            tickets.remove(bearer)
            JSONObject().put("schema", SCHEMA).put("revoked", true)
                .put("effectEnabled", false)
        }

    /**
     * B proof only: atomic, exact scope/signer/pid check and consume.
     * NO filesystem access, process action, runtime registration or install.
     */
    fun consumeProof(
        context: Context, callerPid: Int, bearer: String,
        operation: String, target: String
    ): JSONObject = synchronized(lock) {
        val value = ticket(context, callerPid, bearer)
        require(value.approved && value.operation == operation && value.target == target &&
            operation == "system.fs.read" && target == "/C:/System") {
            "Admin ticket not approved for this exact no-effect operation/target"
        }
        RiftCoreSystemCapabilities.recordDecision(
            context, ACTOR, value.operation, "consumed", "no-effect-proof"
        )
        tickets.remove(bearer)
        JSONObject().put("schema", SCHEMA)
            .put("consumed", true)
            .put("executedPrivilegedEffect", false)
    }

    /**
     * C1.4-C1: one narrowly fixed system-file canary transaction. A real
     * Core-owned C: write+fsync+verify+delete occurs only after a fresh
     * approved exact-scope ticket and an active real-shell foreground lease.
     * Never exposes an arbitrary file mutation or persistent system grant.
     */
    fun executeRollbackProof(
        context: Context, callerPid: Int, bearer: String,
        operation: String, target: String
    ): JSONObject {
        // Lock order matters: recovery.noteReport() holds its monitor then
        // revokes Core admin tickets. Sample its foreground lease BEFORE
        // entering the admin-ticket monitor to avoid a lock inversion.
        val shell = RiftCoreShellRecovery.status()
        require(shell.optInt("shellPid", -1) == callerPid &&
            shell.optBoolean("foregroundLease", false) &&
            shell.optString("phase") == "connected") {
            "Core admin transaction requires active foreground production shell"
        }
        return synchronized(lock) {
            val value = ticket(context, callerPid, bearer)
            require(value.approved &&
                value.operation == operation && value.target == target &&
                operation == RiftCoreAdminRollbackProof.OPERATION &&
                target == RiftCoreAdminRollbackProof.TARGET) {
                "Core rejected admin transaction for unmatched approval scope"
            }
            // Consume BEFORE an effect; audit persistence failure means no effect.
            RiftCoreSystemCapabilities.recordDecision(
                context, ACTOR, operation, "consumed", "isolated-rollback-proof"
            )
            tickets.remove(bearer)
            try {
                val result = RiftCoreAdminRollbackProof.writeAndRollback(context)
                RiftCoreSystemCapabilities.recordDecision(
                    context, ACTOR, operation, "rolled-back", "canary-restored"
                )
                result
            } catch (error: Exception) {
                RiftCoreSystemCapabilities.recordDecision(
                    context, ACTOR, operation, "failed", "transaction-rejected"
                )
                throw error
            }
        }
    }

    /**
     * C1.4-C2-A: exact empty registry format transaction only. This does not
     * register or execute a provider; the existing live registry is protected.
     * Consume first and retain all Core signer, Binder PID and TTL checks.
     */
    fun executeRegistryProof(
        context: Context, callerPid: Int, bearer: String,
        operation: String, target: String
    ): JSONObject {
        // Match C1 lock order: sample Shell recovery BEFORE ticket mutex.
        val shell = RiftCoreShellRecovery.status()
        require(shell.optInt("shellPid", -1) == callerPid &&
            shell.optBoolean("foregroundLease", false) &&
            shell.optString("phase") == "connected") {
            "Core registry proof requires active foreground production shell"
        }
        return synchronized(lock) {
            val value = ticket(context, callerPid, bearer)
            require(value.approved && value.operation == operation &&
                value.target == target &&
                operation == RiftCoreAdminRegistryProof.OPERATION &&
                target == RiftCoreAdminRegistryProof.TARGET) {
                "Core rejected registry proof for unmatched approval scope"
            }
            RiftCoreSystemCapabilities.recordDecision(
                context, ACTOR, operation, "consumed", "isolated-registry-proof"
            )
            tickets.remove(bearer)
            try {
                val result = RiftCoreAdminRegistryProof.writeAndRollback(context)
                RiftCoreSystemCapabilities.recordDecision(
                    context, ACTOR, operation, "rolled-back", "registry-restored"
                )
                result
            } catch (error: Exception) {
                RiftCoreSystemCapabilities.recordDecision(
                    context, ACTOR, operation, "failed", "registry-proof-rejected"
                )
                throw error
            }
        }
    }

    /** Closing the native approval window revokes ALL unused caller tickets. */
    fun revokeForWindowClose(context: Context, callerPid: Int): JSONObject =
        synchronized(lock) {
            val signer = authenticated(context, callerPid)
            expire(context)
            val invalid = tickets.values.filter {
                it.pid == callerPid && it.signer == signer
            }
            for (item in invalid) {
                RiftCoreSystemCapabilities.recordDecision(
                    context, ACTOR, item.operation, "revoked", "window-closed"
                )
                tickets.remove(item.bearer)
            }
            JSONObject().put("schema", SCHEMA)
                .put("revokedCount", invalid.size)
                .put("systemEffectsEnabled", false)
        }

    /** A restarted real shell cannot inherit old PID-bound approvals. */
    fun revokeForShellReplacement(context: Context, oldShellPid: Int) = synchronized(lock) {
        val stale = tickets.values.filter { it.pid == oldShellPid }
        for (item in stale) {
            RiftCoreSystemCapabilities.recordDecision(
                context, ACTOR, item.operation, "revoked", "shell-replaced"
            )
            tickets.remove(item.bearer)
        }
    }

    fun status(context: Context): JSONObject = synchronized(lock) {
        expire(context)
        JSONObject().put("schema", SCHEMA)
            .put("authority", "riftos-core")
            .put("pending", tickets.values.count { !it.approved })
            .put("approvedUnconsumed", tickets.values.count { it.approved })
            .put("maxTickets", MAX_TICKETS)
            .put("expiresAfterMs", TTL_MS)
            .put("systemEffectsEnabled", false)
            .put("isolatedRollbackProofEnabled", true)
            .put("grantPersistence", "none")
            .put("scope", "trusted-native-ui-proof-only")
    }
}
