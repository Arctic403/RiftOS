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

/** Independent RiftCLI relay settings surface. MCP settings and transport are intentionally separate. */
class RiftCliRelayActivity : Activity() {
    private lateinit var relay: RiftCliRelayClient
    private lateinit var settings: RiftCliRelaySettings
    private lateinit var relayToggle: CheckBox
    private lateinit var endpointInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var statusView: TextView
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() {
            refreshStatus()
            refreshHandler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "RiftCLI Relay"
        relay = RiftCliRuntime.relayClient(this)
        settings = RiftCliRelaySettings(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 36, 36, 56)
        }
        setContentView(ScrollView(this).apply { addView(content) })

        content.addView(TextView(this).apply {
            text = "RiftCLI Relay"
            textSize = 26f
        })
        content.addView(TextView(this).apply {
            text = "Independent outbound connection for RiftCLI. This does not reuse the MCP relay, MCP token, MCP device socket, or MCP lifecycle."
            textSize = 14f
            setPadding(0, 12, 0, 20)
        })

        relayToggle = CheckBox(this).apply {
            text = "Enable independent RiftCLI relay"
        }
        endpointInput = EditText(this).apply {
            hint = "wss://your-cli-relay.example/device"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        tokenInput = EditText(this).apply {
            hint = "CLI relay token (leave blank to keep saved token)"
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
                    settings.save(
                        relayToggle.isChecked,
                        endpointInput.text.toString(),
                        tokenInput.text.toString()
                    )
                    tokenInput.text.clear()
                    relay.reload()
                    refresh()
                } catch (error: IllegalArgumentException) {
                    statusView.text = error.message ?: "Invalid RiftCLI relay settings"
                }
            }
        }, matchWidth())

        content.addView(Button(this).apply {
            text = "Reconnect CLI relay now"
            setOnClickListener {
                relay.reload()
                refreshStatus()
            }
        }, matchWidth())

        content.addView(Button(this).apply {
            text = "Forget CLI relay token"
            setOnClickListener {
                settings.clearToken()
                relay.disconnect()
                loadSettings()
                refreshStatus()
            }
        }, matchWidth())

        statusView = TextView(this).apply {
            textSize = 13f
            setPadding(0, 24, 0, 8)
            gravity = Gravity.START
        }
        content.addView(statusView)

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
        loadSettings()
        refreshStatus()
    }

    private fun loadSettings() {
        val config = settings.load()
        relayToggle.isChecked = config.enabled
        if (endpointInput.text.toString() != config.endpoint) endpointInput.setText(config.endpoint)
        tokenInput.hint = if (config.token.isNullOrBlank()) {
            "CLI relay token"
        } else {
            "CLI relay token saved securely (leave blank to keep it)"
        }
    }

    private fun refreshStatus() {
        if (!::statusView.isInitialized) return
        val status = relay.status()
        statusView.text = buildString {
            append("Protocol: ").append(status.optString("protocol", "rift-cli-relay-v1"))
            append("\nState: ").append(status.optString("state", "unknown"))
            append("\nDetail: ").append(status.optString("detail", ""))
            append("\nDevice ID: ").append(status.optString("deviceId", ""))
            append("\nConfigured: ").append(status.optBoolean("configured", false))
            append("\nLast ACK: ").append(status.optLong("lastAckSequence", 0L))
            val events = status.optJSONObject("events")
            append("\nEvent sequence: ").append(events?.optLong("sequence", 0L) ?: 0L)
            append("\nRetained events: ").append(events?.optInt("retained", 0) ?: 0)
        }
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
}
