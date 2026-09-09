package com.riftos.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Base64
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
import java.security.SecureRandom
import java.text.DateFormat
import java.util.Date

/** Native settings surface launched from the RiftOS Rift Bridge system app. */
class RiftMcpBridgeActivity : Activity() {
    private lateinit var relay: RiftMcpRelayClient
    private lateinit var urlInput: EditText
    private lateinit var keyInput: EditText
    private lateinit var readToggle: CheckBox
    private lateinit var writeToggle: CheckBox
    private lateinit var statusView: TextView
    private lateinit var endpointView: TextView
    private lateinit var auditView: TextView
    private var accessLoaded = false
    private val handler = Handler(Looper.getMainLooper())
    private val refreshLoop = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Rift Bridge"
        relay = RiftMcpRuntime.get(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 36, 36, 56)
        }
        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)

        content.addView(TextView(this).apply {
            text = "Rift Bridge"
            textSize = 26f
        })
        content.addView(TextView(this).apply {
            text = "Local MCP tools for RiftOS. RiftBrowser uses the in-process MCP App compatibility adapter on ChatGPT Web; the remote relay below is optional for accounts that can register a real custom MCP app. Device permissions and audit apply to both paths."
            textSize = 14f
            setPadding(0, 12, 0, 20)
        })

        urlInput = EditText(this).apply {
            hint = "wss://your-relay.example/device"
            setSingleLine(true)
        }
        content.addView(label("Optional remote adapter WebSocket"))
        content.addView(urlInput, matchWidth())

        keyInput = EditText(this).apply {
            hint = "Pairing key (blank keeps saved key)"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        content.addView(label("Remote pairing key"))
        content.addView(keyInput, matchWidth())

        content.addView(Button(this).apply {
            text = "Generate new pairing key"
            setOnClickListener { keyInput.setText(generatePairingKey()) }
        }, matchWidth())

        content.addView(label("Shared device capabilities"))
        readToggle = CheckBox(this).apply {
            text = "Allow sandbox read tools (info, stat, list, readText)"
        }
        writeToggle = CheckBox(this).apply {
            text = "Allow sandbox write tools (writeText, mkdir, remove, move)"
        }
        content.addView(readToggle, matchWidth())
        content.addView(writeToggle, matchWidth())

        content.addView(Button(this).apply {
            text = "Save permissions + Connect remote adapter"
            setOnClickListener { saveAndConnect() }
        }, matchWidth())

        content.addView(Button(this).apply {
            text = "Disconnect remote adapter"
            setOnClickListener {
                runCatching { relay.disconnect() }
                refresh()
            }
        }, matchWidth())

        endpointView = TextView(this).apply {
            textSize = 13f
            setPadding(0, 24, 0, 8)
        }
        content.addView(endpointView)

        content.addView(Button(this).apply {
            text = "Copy remote ChatGPT MCP endpoint"
            setOnClickListener {
                relay.appEndpoint()?.let { endpoint ->
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("Rift Bridge MCP endpoint", endpoint))
                }
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
                relay.clearAudit()
                refresh()
            }
        }, matchWidth())

        refresh()
    }

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(refreshLoop)
        handler.post(refreshLoop)
    }

    override fun onStop() {
        handler.removeCallbacks(refreshLoop)
        super.onStop()
    }

    private fun saveAndConnect() {
        val args = JSONObject()
            .put("deviceUrl", urlInput.text.toString().trim())
            .put("enabled", true)
            .put("allowRead", readToggle.isChecked)
            .put("allowWrite", writeToggle.isChecked)
        keyInput.text.toString().trim().takeIf { it.isNotBlank() }?.let { args.put("pairingKey", it) }
        runCatching { relay.configure(args) }
            .onSuccess { keyInput.setText("") }
            .onFailure { statusView.text = "Error: ${it.message ?: it.javaClass.simpleName}" }
        refresh()
    }

    private fun refresh() {
        val status = relay.status()
        val access = status.optJSONObject("access") ?: JSONObject()
        if (!urlInput.hasFocus() && urlInput.text.isBlank()) urlInput.setText(status.optString("deviceUrl"))
        if (!accessLoaded) {
            readToggle.isChecked = access.optBoolean("sandboxRead", true)
            writeToggle.isChecked = access.optBoolean("sandboxWrite", false)
            accessLoaded = true
        }
        endpointView.text = relay.appEndpoint()?.let {
            "Optional remote ChatGPT MCP endpoint:\n$it\n\nRiftBrowser MCP App mode does not need this endpoint. Treat this URL like a secret while the alpha uses a pairing key in the path."
        } ?: "RiftBrowser MCP App: built in and local.\nRemote MCP endpoint appears only after an optional relay URL and pairing key are configured."
        statusView.text = buildString {
            append("RiftBrowser MCP App: available · ").append(status.optInt("toolCount", 0)).append(" tools")
            append("\nLocal transport: in-process MCP JSON-RPC")
            append("\nRemote adapter state: ").append(status.optString("state", "off"))
            append("\nRemote connected: ").append(status.optBoolean("connected", false))
            append("\nRemote configured: ").append(status.optBoolean("configured", false))
            append("\nSandbox: ").append(access.optString("scope", RiftToolHost.SCOPE))
            append("\nRead tools: ").append(if (access.optBoolean("sandboxRead", true)) "allowed" else "blocked")
            append("\nWrite tools: ").append(if (access.optBoolean("sandboxWrite", false)) "allowed" else "blocked")
            status.optString("lastError").takeIf { it.isNotBlank() }?.let { append("\nRemote last error: ").append(it) }
            append("\nRemote protocol: ").append(status.optString("protocol"))
        }
        auditView.text = formatAudit(relay.audit())
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

    private fun generatePairingKey(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
