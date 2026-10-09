package com.riftos.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
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
    private val operation = "system.fs.read"
    private val target = "/C:/System"
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
        addText("C1.4-B proof only. A ticket does not read files, install software, " +
            "register runtimes, or terminate processes.")
        addText("Test scope: $operation on $target\n" +
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
        button("Request scoped administrator test") { request() }
        button("Consume once — no privileged effect") { consume() }
        button("Revoke current approval") { revoke() }
        button("Refresh Core ticket status") { refresh() }
        desktop.attachContent("admin-permissions", root)
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
        // Resolve any earlier approval before issuing a fresh test request;
        // a consumed/expired ticket is already absent in Core.
        val previous = currentTicket
        currentTicket = null
        worker.execute {
            if (previous != null) {
                runCatching { client.adminConsent("revoke", ticket = previous) }
            }
            val response = runCatching {
                client.adminConsent("request", operation = operation, target = target)
            }
            activity.runOnUiThread {
                if (!visible || serial != generation || activity.isFinishing) {
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
                AlertDialog.Builder(activity)
                    .setTitle("RiftOS administrator consent — proof only")
                    .setMessage("Allow ONE no-effect authorization proof?\n" +
                        "Operation: ${value.optString("operation")}\n" +
                        "Target: ${value.optString("target")}\n" +
                        "Expires in 45 seconds. This grants NO system-file access.")
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
                "Core approved ONE no-effect ticket. Consume or revoke it before expiry."
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
        val old = currentTicket
        currentTicket = null
        if (old != null) worker.execute {
            runCatching { core?.adminConsent("revoke", ticket = old) }
        }
    }

    fun destroy() {
        close()
        worker.shutdown()
    }
}
