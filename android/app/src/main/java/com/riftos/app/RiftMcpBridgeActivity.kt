package com.riftos.app

import android.app.Activity
import android.content.ClipboardManager
import android.content.ClipData
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.security.SecureRandom

/** Minimal native setup surface for the remote ChatGPT MCP bridge. */
class RiftMcpBridgeActivity : Activity() {
    private lateinit var relay: RiftMcpRelayClient
    private lateinit var urlInput: EditText
    private lateinit var keyInput: EditText
    private lateinit var statusView: TextView
    private lateinit var endpointView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Rift MCP Bridge"
        relay = RiftMcpRuntime.get(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 36, 36, 48)
        }
        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)

        content.addView(TextView(this).apply {
            text = "Rift MCP Bridge"
            textSize = 24f
        })
        content.addView(TextView(this).apply {
            text = "Connect ChatGPT first-class tools to the app-private RiftBrowser sandbox. The pairing key is encrypted with Android Keystore."
            textSize = 14f
            setPadding(0, 12, 0, 24)
        })

        urlInput = EditText(this).apply {
            hint = "wss://your-relay.example/device"
            setSingleLine(true)
        }
        content.addView(label("Device WebSocket URL"))
        content.addView(urlInput, matchWidth())

        keyInput = EditText(this).apply {
            hint = "Pairing key (leave blank to keep saved key)"
            setSingleLine(true)
        }
        content.addView(label("Pairing key"))
        content.addView(keyInput, matchWidth())

        val generate = Button(this).apply {
            text = "Generate new pairing key"
            setOnClickListener { keyInput.setText(generatePairingKey()) }
        }
        content.addView(generate, matchWidth())

        val connect = Button(this).apply {
            text = "Save + Connect"
            setOnClickListener { saveAndConnect() }
        }
        content.addView(connect, matchWidth())

        val disconnect = Button(this).apply {
            text = "Disconnect"
            setOnClickListener {
                runCatching { relay.disconnect() }
                refresh()
            }
        }
        content.addView(disconnect, matchWidth())

        endpointView = TextView(this).apply {
            textSize = 13f
            setPadding(0, 24, 0, 8)
        }
        content.addView(endpointView)

        val copyEndpoint = Button(this).apply {
            text = "Copy ChatGPT MCP endpoint"
            setOnClickListener {
                relay.appEndpoint()?.let { endpoint ->
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("Rift MCP endpoint", endpoint))
                }
                refresh()
            }
        }
        content.addView(copyEndpoint, matchWidth())

        statusView = TextView(this).apply {
            textSize = 13f
            setPadding(0, 24, 0, 0)
            gravity = Gravity.START
        }
        content.addView(statusView)

        refresh()
    }

    private fun saveAndConnect() {
        val args = JSONObject()
            .put("deviceUrl", urlInput.text.toString().trim())
            .put("enabled", true)
        keyInput.text.toString().trim().takeIf { it.isNotBlank() }?.let { args.put("pairingKey", it) }
        runCatching { relay.configure(args) }
            .onSuccess { keyInput.setText("") }
            .onFailure { statusView.text = "Error: ${it.message ?: it.javaClass.simpleName}" }
        refresh()
    }

    private fun refresh() {
        val status = relay.status()
        if (!urlInput.hasFocus() && urlInput.text.isBlank()) urlInput.setText(status.optString("deviceUrl"))
        endpointView.text = relay.appEndpoint()?.let { "ChatGPT MCP endpoint:\n$it" }
            ?: "ChatGPT MCP endpoint appears after a relay URL and pairing key are configured."
        statusView.text = buildString {
            append("State: ").append(status.optString("state", "off"))
            append("\nConnected: ").append(status.optBoolean("connected", false))
            append("\nConfigured: ").append(status.optBoolean("configured", false))
            status.optString("lastError").takeIf { it.isNotBlank() }?.let { append("\nLast error: ").append(it) }
            append("\nProtocol: ").append(status.optString("protocol"))
        }
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setPadding(0, 18, 0, 4)
    }

    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun generatePairingKey(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
