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
 * This gate does NOT authorize/execute any privileged operation. consumeProof
 * only demonstrates the single-use ticket lifecycle. C1.4-C must bind a
 * specific real effect to policy, target/signer validation and rollback.
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
        operation == "system.fs.read" && target == "/C:/System"

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
        require(value.approved && value.operation == operation && value.target == target) {
            "Admin ticket not approved for this exact operation/target"
        }
        RiftCoreSystemCapabilities.recordDecision(
            context, ACTOR, value.operation, "consumed", "no-effect-proof"
        )
        tickets.remove(bearer)
        JSONObject().put("schema", SCHEMA)
            .put("consumed", true)
            .put("executedPrivilegedEffect", false)
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
            .put("grantPersistence", "none")
            .put("scope", "trusted-native-ui-proof-only")
    }
}
