package com.riftos.app

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Process
import android.os.ParcelFileDescriptor
import android.content.Intent
import java.io.FileInputStream
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
    const val PROBE_STAGE = "bootstrap.probe.stage"
    const val PROBE_ACTIVATE = "bootstrap.probe.activate"
    const val PROBE_TARGET = "bootstrap://probe/ProbeV1"
    const val PROBE_ENTRYPOINT = "com.riftos.bootstrap.proof.ProbeV1"
    const val PROBE_SCHEMA = "riftos.bootstrap.probe-operation/1"

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
                target == RiftCoreAdminRegistryProof.TARGET) ||
            (operation == PROBE_STAGE && target == PROBE_TARGET) ||
            (operation == PROBE_ACTIVATE &&
                target.matches(Regex("^bootstrap://probe/[0-9a-f]{64}$")))

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
                val diagnostic = RiftCoreAdminRegistryProof.status(context)
                // A failed transaction must NEVER be reported as complete.
                // Only return bounded failure diagnostics when the registry
                // and journal are safely absent; otherwise preserve the
                // exception and stop rather than masking cleanup failure.
                if (diagnostic.optBoolean("pendingJournal", true) ||
                    diagnostic.optBoolean("registryExists", true) ||
                    diagnostic.optBoolean("temporaryRegistryExists", true)) throw error
                JSONObject().put("schema", RiftCoreAdminRegistryProof.SCHEMA)
                    .put("transactionCommitted", false)
                    .put("rolledBack", false)
                    .put("registryRestored", true)
                    .put("providerRegistered", false)
                    .put("pendingJournal", false)
                    .put("failureStage", diagnostic.optString("lastFailureStage", "unknown"))
                    .put("failureType", diagnostic.optString("lastFailureType", "unknown"))
                    .put("failureErrno", diagnostic.optInt("lastFailureErrno", 0))

            }
        }
    }

    /**
     * Trusted fixed-proof import: only real foreground RiftShell, installed
     * APK signer, approved one-use ticket and selected SAF read descriptor.
     * No arbitrary app or runtime registration is ever granted.
     */
    fun executeProbeStage(
        context: Context, callerPid: Int, bearer: String,
        operation: String, target: String, descriptor: ParcelFileDescriptor
    ): JSONObject {
        val shell = RiftCoreShellRecovery.status()
        require(shell.optInt("shellPid", -1) == callerPid &&
            shell.optBoolean("foregroundLease", false) &&
            shell.optString("phase") == "connected") {
            "Probe import requires foreground trusted RiftShell"
        }
        return synchronized(lock) {
            val value = ticket(context, callerPid, bearer)
            require(value.approved && value.operation == PROBE_STAGE &&
                value.target == PROBE_TARGET &&
                operation == value.operation && target == value.target) {
                "Probe import requires exact approved scope"
            }
            RiftCoreSystemCapabilities.recordDecision(
                context, ACTOR, operation, "consumed", "fixed-probe-stage"
            )
            tickets.remove(bearer)
            try {
                // Only this fixed probe entrypoint can be imported through
                // this limited proof action, never an arbitrary class/path.
                val staged = FileInputStream(descriptor.fileDescriptor).use { input ->
                    RiftBootstrapComponentStore.stage(
                        context, "probe", PROBE_ENTRYPOINT, input)
                }
                RiftCoreSystemCapabilities.recordDecision(
                    context, ACTOR, operation, "staged", "verified-dex-only"
                )
                JSONObject().put("schema", PROBE_SCHEMA)
                    .put("staged", true)
                    .put("sha256", staged.getString("sha256"))
                    .put("activated", false)
            } catch (error: Exception) {
                RiftCoreSystemCapabilities.recordDecision(
                    context, ACTOR, operation, "failed", "probe-stage-rejected"
                )
                throw error
            }
        }
    }

    /**
     * Digest-bound activation is separately approved AFTER staging. Launch
     * only the inert, nonexported Android :riftBootstrapProbe service.
     * It does not replace or restart Core/Shell or change the RAPP registry.
     */
    fun executeProbeActivate(
        context: Context, callerPid: Int, bearer: String,
        operation: String, target: String
    ): JSONObject {
        val shell = RiftCoreShellRecovery.status()
        require(shell.optInt("shellPid", -1) == callerPid &&
            shell.optBoolean("foregroundLease", false) &&
            shell.optString("phase") == "connected") {
            "Probe activation requires foreground trusted RiftShell"
        }
        return synchronized(lock) {
            val value = ticket(context, callerPid, bearer)
            require(value.approved && value.operation == PROBE_ACTIVATE &&
                value.target == target && operation == value.operation &&
                validScope(operation, target)) {
                "Probe activation requires approved matching SHA-256"
            }
            RiftCoreSystemCapabilities.recordDecision(
                context, ACTOR, operation, "consumed", "digest-bound-probe"
            )
            tickets.remove(bearer)
            val sha = target.removePrefix("bootstrap://probe/")
            try {
                // Prior proof must not be mistaken for a new execution.
                val marker = android.util.AtomicFile(java.io.File(
                    RiftBootstrapComponentStore.root(context), "probe-proof.json"))
                val result = RiftBootstrapComponentStore.activateProbe(
                    context, sha, PROBE_ENTRYPOINT)
                marker.delete()
                context.startService(Intent(context, RiftBootstrapProbeService::class.java))
                    ?: error("Probe service did not start")
                RiftCoreSystemCapabilities.recordDecision(
                    context, ACTOR, operation, "activated", "isolated-probe-process"
                )
                JSONObject().put("schema", PROBE_SCHEMA)
                    .put("activated", true)
                    .put("sha256", result.getString("sha256"))
                    .put("probeProcessRequested", true)
            } catch (error: Exception) {
                // Any activation written before launch failure must be
                // restored without needing to kill production Core/Shell.
                runCatching { RiftBootstrapComponentStore.rollbackProbe(context) }
                    .onFailure { error.addSuppressed(it) }
                RiftCoreSystemCapabilities.recordDecision(
                    context, ACTOR, operation, "failed", "probe-activation-rejected"
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

    /**
     * C1.4-C2-B1 diagnostic only: the exact remote production Shell asks Core
     * to enumerate Android-attested runtime candidates. No token is granted,
     * no package or runtime is installed, and no registry file is changed.
     */
    fun discoverProviderCandidates(context: Context, callerPid: Int): JSONObject {
        authenticated(context, callerPid)
        return RiftCoreRuntime.runtimes(context).discoverCandidates()
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
