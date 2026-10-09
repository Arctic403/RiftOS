package com.riftos.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * C1.4-B trusted NATIVE system-window consent, not a RAPP/terminal command.
 * Core checks actual Binder PID + installed signer + exact operation/target;
 * UI merely shows the Core-issued scope and forwards explicit user decisions.
 * No actual high-privilege effects are available until C1.4-C.
 */
internal class RiftNativeAdminApprovals(
    private val activity: Activity,
    private val desktop: RiftNativeDesktop,
    private val core: RiftShellCoreClient?
) {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "rift-admin-consent-ui").apply { isDaemon = true }
    }
    private var operation = "system.fs.read"
    private var target = "/C:/System"
    private var scopeText: TextView? = null
    private var visible = false
    private var currentTicket: String? = null
    private var statusView: TextView? = null
    private var generation = 0L

    fun open() {
        desktop.handle("desktop.window.open", JSONObject()
            .put("id", "admin-permissions").put("title", "Admin Approvals")
            .put("kicker", "CORE AUTHORITY / NO SYSTEM EFFECTS"))
        visible = true
        generation++
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
            setBackgroundColor(0xff101923.toInt())
        }
        fun addText(message: String, size: Float = 14f): TextView =
            TextView(activity).apply {
                text = message
                textSize = size
                setTextColor(0xffe7eef5.toInt())
                setPadding(4, 10, 4, 12)
                root.addView(this, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        addText("RiftOS administrator approvals", 19f).typeface = Typeface.DEFAULT_BOLD
        addText("C1.4-B/C1/C2-A proofs: fixed temporary C: canary or an isolated " +
            "EMPTY runtime registry proof; both always roll back. No provider install, " +
            "real registration, production package change or process termination.")
        scopeText = addText("Test scope: $operation on $target\n" +
            "Core controls the caller, signer, scope, 45-second expiry and one-time use.")
        statusView = addText("No pending administrator request.")
        fun button(label: String, clicked: () -> Unit) {
            root.addView(Button(activity).apply {
                text = label
                isAllCaps = false
                setOnClickListener { clicked() }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        button("Toggle isolated rollback proof scope") {
            if (currentTicket != null) {
                show("Revoke or consume the prior approval before switching scope.")
                return@button
            }
            if (operation == "system.fs.read") {
                operation = RiftCoreAdminRollbackProof.OPERATION
                target = RiftCoreAdminRollbackProof.TARGET
            } else {
                operation = "system.fs.read"
                target = "/C:/System"
            }
            scopeText?.text = "Test scope: $operation on $target\n" +
                "Only the fixed write proof can mutate a temporary C: canary, " +
                "with mandatory journalled rollback."
            show("Selected fixed test operation $operation; no ticket issued yet.")
        }
        button("Select isolated runtime registry proof scope") {
            if (currentTicket != null) {
                show("Revoke or consume the prior approval before switching scope.")
                return@button
            }
            operation = RiftCoreAdminRegistryProof.OPERATION
            target = RiftCoreAdminRegistryProof.TARGET
            scopeText?.text = "Test scope: $operation on $target\n" +
                "Core may briefly create a signer-stamped EMPTY registry, " +
                "then must restore the absent registry. No provider is registered."
            show("Selected isolated empty runtime registry proof. No ticket issued.")
        }
        button("Request scoped administrator test") { request() }
        button("Consume once — no privileged effect") { consume() }
        button("Execute Core write and rollback once") { executeRollbackProof() }
        button("Execute Core empty registry and rollback once") { executeRegistryProof() }
        button("Revoke current approval") { revoke() }
        button("Discover installed runtime candidates (read-only)") { discoverProviders() }
        button("Refresh Core ticket status") { refresh() }
        // More distinct test modes must remain reachable on small phones.
        desktop.attachContent("admin-permissions", ScrollView(activity).apply {
            isFillViewport = true
            addView(root)
        })
        refresh()
    }

    private fun show(message: String) {
        if (!visible) return
        statusView?.text = message.take(800)
    }

    private fun perform(block: () -> String) {
        val serial = generation
        worker.execute {
            val result = runCatching(block).getOrElse { "Core denied: ${it.message}" }
            activity.runOnUiThread {
                if (visible && serial == generation && !activity.isDestroyed) show(result)
            }
        }
    }

    private fun request() {
        if (!activity.hasWindowFocus()) {
            show("Admin test requires the trusted graphical window in foreground.")
            return
        }
        val client = core ?: run {
            show("No authenticated production Core IPC client.")
            return
        }
        val serial = generation
        val requestedOperation = operation
        val requestedTarget = target
        // Resolve any earlier approval before issuing a fresh test request;
        // a consumed/expired ticket is already absent in Core.
        val previous = currentTicket
        currentTicket = null
        worker.execute {
            if (previous != null) {
                runCatching { client.adminConsent("revoke", ticket = previous) }
            }
            val response = runCatching {
                client.adminConsent("request", operation = requestedOperation, target = requestedTarget)
            }
            activity.runOnUiThread {
                if (!visible || serial != generation || activity.isFinishing ||
                    operation != requestedOperation || target != requestedTarget) {
                    response.getOrNull()?.optString("ticket")?.let { ticket ->
                        worker.execute {
                            runCatching { client.adminConsent("revoke", ticket = ticket) }
                        }
                    }
                    return@runOnUiThread
                }
                val value = response.getOrElse { error ->
                    show("Core refused administrator request: ${error.message}")
                    return@runOnUiThread
                }
                val ticket = value.getString("ticket")
                currentTicket = ticket
                show("Core issued a pending test ticket. Confirm the exact scope below.")
                val rollback = requestedOperation == RiftCoreAdminRollbackProof.OPERATION
                val explanation = if (rollback) {
                    "Allow ONE isolated Core C: canary write, verification and " +
                        "mandatory immediate rollback? No production file is changed."
                } else if (requestedOperation == RiftCoreAdminRegistryProof.OPERATION) {
                    "Allow ONE Core-only EMPTY runtime registry creation, signer " +
                        "verification and mandatory rollback? This does NOT " +
                        "register, enable or install any runtime provider."
                } else {
                    "Allow ONE no-effect authorization proof? " +
                        "This grants NO system-file access."
                }
                AlertDialog.Builder(activity)
                    .setTitle("RiftOS administrator consent — fixed-scope only")
                    .setMessage(explanation + "\n" +
                        "Operation: ${value.optString("operation")}\n" +
                        "Target: ${value.optString("target")}\n" +
                        "Expires in 45 seconds. Other privileged operations stay blocked.")
                    .setPositiveButton("Allow once") { _, _ -> decide(ticket, true) }
                    .setNegativeButton("Deny") { _, _ -> decide(ticket, false) }
                    .setNeutralButton("Cancel") { _, _ -> decide(ticket, false) }
                    .setOnCancelListener { decide(ticket, false) }
                    .show()
            }
        }
    }

    private fun decide(ticket: String, approved: Boolean) {
        if (!visible || currentTicket != ticket) return
        val client = core ?: return
        perform {
            val result = client.adminConsent("decide", ticket = ticket, approved = approved)
            if (!approved) activity.runOnUiThread {
                if (currentTicket == ticket) currentTicket = null
            }
            if (result.optBoolean("approved")) {
                if (operation == RiftCoreAdminRollbackProof.OPERATION) {
                    "Core approved ONE fixed C: canary write-and-rollback test. " +
                        "Execute it once before the 45-second expiry, or revoke."
                } else if (operation == RiftCoreAdminRegistryProof.OPERATION) {
                    "Core approved ONE empty registry/rollback proof; no provider installation. " +
                        "Execute before 45-second expiry or revoke."
                } else "Core approved ONE no-effect ticket. Consume or revoke it before expiry."
            } else "Core denied/cancelled the request; zero elevated privileges."
        }
    }

    private fun consume() {
        val ticket = currentTicket ?: run { show("No ticket to consume."); return }
        val client = core ?: return
        perform {
            val result = client.adminConsent("consume-proof", ticket,
                operation, target)
            if (result.optBoolean("consumed") &&
                !result.optBoolean("executedPrivilegedEffect", true)) {
                "One-use ticket consumed. NO privileged effect executed. " +
                    "Repeat Consume to confirm replay denial."
            } else "Core declined consumption."
        }
    }

    private fun executeRollbackProof() {
        if (operation != RiftCoreAdminRollbackProof.OPERATION ||
            target != RiftCoreAdminRollbackProof.TARGET) {
            show("Select the isolated Core rollback test scope first.")
            return
        }
        if (!activity.hasWindowFocus()) {
            show("Core rollback proof requires the trusted window foreground.")
            return
        }
        val bearer = currentTicket ?: run { show("No approved rollback ticket."); return }
        val client = core ?: run { show("No Core IPC available."); return }
        perform {
            val response = client.adminConsent("execute-rollback-proof",
                bearer, RiftCoreAdminRollbackProof.OPERATION,
                RiftCoreAdminRollbackProof.TARGET)
            if (response.optBoolean("transactionCommitted") &&
                response.optBoolean("rolledBack") &&
                !response.optBoolean("pendingJournal", true)) {
                activity.runOnUiThread { if (currentTicket == bearer) currentTicket = null }
                "Core wrote, verified and rolled back the isolated C: test canary. " +
                    "No pending journal, no permanent file, ticket consumed."
            } else {
                "Core did not verify a completed rollback transaction."
            }
        }
    }

    /**
     * C1.4-C2-A: never allows a caller-supplied provider, registry path or
     * signer pin. Only the fixed Core journalled empty registry transaction.
     */
    private fun executeRegistryProof() {
        if (operation != RiftCoreAdminRegistryProof.OPERATION ||
            target != RiftCoreAdminRegistryProof.TARGET) {
            show("Select the isolated empty runtime registry proof scope first.")
            return
        }
        if (!activity.hasWindowFocus()) {
            show("Core registry proof requires trusted window foreground.")
            return
        }
        val bearer = currentTicket ?: run { show("No approved registry ticket."); return }
        val client = core ?: run { show("No Core IPC available."); return }
        perform {
            val response = client.adminConsent("execute-registry-proof", bearer,
                RiftCoreAdminRegistryProof.OPERATION, RiftCoreAdminRegistryProof.TARGET)
            if (response.optBoolean("transactionCommitted") &&
                response.optBoolean("rolledBack") &&
                response.optBoolean("registryRestored") &&
                !response.optBoolean("providerRegistered", true) &&
                !response.optBoolean("pendingJournal", true)) {
                activity.runOnUiThread { if (currentTicket == bearer) currentTicket = null }
                "Core verified its installed signer, wrote and removed the EMPTY " +
                    "registry canary. No provider registered, no journal or registry left."
            } else "Core did not prove complete empty registry rollback."
        }
    }

    private fun revoke() {
        val ticket = currentTicket ?: run { show("No ticket to revoke."); return }
        val client = core ?: return
        perform {
            val result = client.adminConsent("revoke", ticket = ticket)
            if (result.optBoolean("revoked")) {
                activity.runOnUiThread { if (currentTicket == ticket) currentTicket = null }
                "Core revoked the ticket. No privileged effect executed."
            } else "Core rejected revocation."
        }
    }

    /**
     * C2-B1 never requests or approves runtime.register and never edits the
     * registry. This is a Core-attested read-only inventory from Android PM.
     */
    private fun discoverProviders() {
        val client = core ?: run { show("No authenticated Core IPC."); return }
        perform {
            val response = client.adminConsent("discover-providers")
            val candidates = response.getJSONArray("candidates")
            if (candidates.length() == 0) {
                "No eligible external runtime services installed; zero registrations."
            } else {
                buildString {
                    append("Eligible installed runtime services: ")
                    append(candidates.length())
                    append(". No registration performed. ")
                    for (index in 0 until minOf(4, candidates.length())) {
                        val item = candidates.getJSONObject(index)
                        append("\n")
                        append(item.getString("id")).append(" · ")
                        append(item.getString("executorKind")).append(" · ")
                        append(item.getString("package")).append("/")
                        append(item.getString("service"))
                    }
                    if (candidates.length() > 4) append("\n…more available")
                }
            }
        }
    }

    private fun refresh() {
        val client = core ?: run { show("No Core IPC. Administrator effects unavailable."); return }
        perform {
            val s = client.adminConsent("status")
            "Core pending: ${s.optInt("pending")}, approved unused: " +
                "${s.optInt("approvedUnconsumed")}; systemEffectsEnabled: " +
                "${s.optBoolean("systemEffectsEnabled", true)}"
        }
    }

    fun close() {
        visible = false
        generation++
        statusView = null
        scopeText = null
        currentTicket = null
        // Core invalidates ALL unconsumed approvals from the exact OS-attested
        // real shell PID on native window close, even if the UI lost its token.
        worker.execute {
            runCatching { core?.adminConsent("window-closed") }
        }
    }

    fun destroy() {
        close()
        worker.shutdown()
    }
}
