package com.riftos.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** Native MCP permissions and relay settings surface. */
class RiftMcpActivity : Activity() {
    private lateinit var host: RiftToolHost
    private lateinit var relay: RiftMcpRelayClient
    private lateinit var relaySettings: RiftRelaySettings
    private lateinit var readToggle: CheckBox
    private lateinit var writeToggle: CheckBox
    private lateinit var relayToggle: CheckBox
    private lateinit var endpointInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var statusView: TextView
    private lateinit var auditView: TextView
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() {
            refreshStatus()
            refreshHandler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Rift MCP"
        host = RiftMcpRuntime.toolHost(this)
        relay = RiftMcpRuntime.relayClient(this)
        relaySettings = RiftRelaySettings(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 36, 36, 56)
        }
        setContentView(ScrollView(this).apply { addView(content) })

        content.addView(TextView(this).apply {
            text = "Rift MCP"
            textSize = 26f
        })
        content.addView(TextView(this).apply {
            text = "RiftOS executes MCP tools locally inside the workspace sandbox. An optional secure outbound relay lets ChatGPT call the same native tools without browser composer automation."
            textSize = 14f
            setPadding(0, 12, 0, 20)
        })

        content.addView(label("Local tool permissions"))
        readToggle = CheckBox(this).apply {
            text = "Allow read tools (info, stat, list, readText, Code Mode project intelligence)"
        }
        writeToggle = CheckBox(this).apply {
            text = "Allow write tools (writeText, mkdir, remove, move, copy, Code Mode surgical edits)"
        }
        content.addView(readToggle, matchWidth())
        content.addView(writeToggle, matchWidth())

        content.addView(Button(this).apply {
            text = "Apply permissions"
            setOnClickListener {
                host.setAccess(readToggle.isChecked, writeToggle.isChecked)
                refresh()
            }
        }, matchWidth())

        content.addView(label("ChatGPT relay"))
        relayToggle = CheckBox(this).apply {
            text = "Enable secure outbound relay"
        }
        endpointInput = EditText(this).apply {
            hint = "wss://your-relay.example/device"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        tokenInput = EditText(this).apply {
            hint = "Pairing token (leave blank to keep saved token)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        content.addView(relayToggle, matchWidth())
        content.addView(endpointInput, matchWidth())
        content.addView(tokenInput, matchWidth())
        content.addView(Button(this).apply {
            text = "Save and connect"
            setOnClickListener {
                try {
                    relaySettings.save(relayToggle.isChecked, endpointInput.text.toString(), tokenInput.text.toString())
                    tokenInput.text.clear()
                    relay.reload()
                    refresh()
                } catch (error: IllegalArgumentException) {
                    statusView.text = error.message ?: "Invalid relay settings"
                }
            }
        }, matchWidth())
        content.addView(Button(this).apply {
            text = "Reconnect relay now"
            setOnClickListener {
                relay.reload()
                refreshStatus()
            }
        }, matchWidth())
        content.addView(TextView(this).apply {
            text = "If ChatGPT shows fewer tools than the manifest count below, refresh/rescan the RiftOS app actions in ChatGPT. Reconnecting the relay only reconnects transport; it does not replace ChatGPT's cached action catalog."
            textSize = 12f
            setPadding(0, 8, 0, 8)
        })
        content.addView(Button(this).apply {
            text = "Forget pairing token"
            setOnClickListener {
                relaySettings.clearToken()
                relay.disconnect()
                loadRelaySettings()
                refresh()
            }
        }, matchWidth())

        statusView = TextView(this).apply {
            textSize = 13f
            setPadding(0, 24, 0, 8)
            gravity = Gravity.START
        }
        content.addView(statusView)

        content.addView(label("Recent tool activity"))
        auditView = TextView(this).apply {
            textSize = 12f
            setPadding(0, 6, 0, 8)
        }
        content.addView(auditView)

        content.addView(Button(this).apply {
            text = "Clear activity log"
            setOnClickListener {
                host.clearAudit()
                refresh()
            }
        }, matchWidth())

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        refreshHandler.removeCallbacks(refreshTask)
        refreshHandler.post(refreshTask)
    }

    override fun onPause() {
        refreshHandler.removeCallbacks(refreshTask)
        super.onPause()
    }

    private fun refresh() {
        val access = host.access()
        readToggle.isChecked = access.optBoolean("sandboxRead", true)
        writeToggle.isChecked = access.optBoolean("sandboxWrite", false)
        loadRelaySettings()
        refreshStatus()
        auditView.text = formatAudit(host.audit())
    }

    private fun loadRelaySettings() {
        val config = relaySettings.load()
        relayToggle.isChecked = config.enabled
        if (endpointInput.text.toString() != config.endpoint) endpointInput.setText(config.endpoint)
        tokenInput.hint = if (config.token.isNullOrBlank()) {
            "Pairing token"
        } else {
            "Pairing token saved securely (leave blank to keep it)"
        }
    }

    private fun refreshStatus() {
        if (!::statusView.isInitialized) return
        val access = host.access()
        val relayStatus = relay.status()
        val manifest = host.manifest()
        statusView.text = buildString {
            append("Mode: native MCP with optional relay")
            append("\nTools: ").append(manifest.optInt("count"))
            append("\nManifest: ").append(manifest.optString("sha256").take(12))
            append("\nWorkspace: ").append(access.optString("workspaceScope", "riftfs/workspace"))
            append("\nScope: workspace only")
            append("\nRead tools: ").append(if (access.optBoolean("sandboxRead", true)) "allowed" else "blocked")
            append("\nWrite tools: ").append(if (access.optBoolean("sandboxWrite", false)) "allowed" else "blocked")
            append("\nRelay: ").append(relayStatus.optString("state", "unknown"))
            append("\nRelay detail: ").append(relayStatus.optString("detail", ""))
            append("\nDevice ID: ").append(relayStatus.optString("deviceId", ""))
            append("\nPairing token: ").append(if (relayStatus.optBoolean("configured", false)) "saved" else "not configured")
        }
    }

    private fun formatAudit(audit: JSONArray): String {
        if (audit.length() == 0) return "No tool calls yet."
        val lines = mutableListOf<String>()
        val start = (audit.length() - 12).coerceAtLeast(0)
        for (index in audit.length() - 1 downTo start) {
            val item = audit.optJSONObject(index) ?: continue
            val at = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(item.optLong("at")))
            val ok = if (item.optBoolean("ok", false)) "✓" else "×"
            val target = item.optString("target").takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            val error = item.optString("error").takeIf { it.isNotBlank() && it != "null" }?.let { " · $it" }.orEmpty()
            lines += "$ok $at · ${item.optString("tool")}$target$error"
        }
        return lines.joinToString("\n")
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setPadding(0, 18, 0, 4)
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
}
