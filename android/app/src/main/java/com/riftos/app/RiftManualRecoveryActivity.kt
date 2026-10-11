package com.riftos.app

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * Separate launcher/process recovery entrance.
 *
 * No Core/Shell/MCP singletons or protected candidate code are invoked.
 * Patch A is status-only; explicit restore is added and proven in Patch B.
 */
class RiftManualRecoveryActivity : Activity() {
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "RiftOS Recovery"
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(34))
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(screen)
        })

        screen.addView(TextView(this).apply {
            text = "RiftOS Recovery"
            textSize = 26f
        })
        screen.addView(TextView(this).apply {
            text = "APK recovery is independent of RiftOS Core and RiftShell. " +
                "This first gate checks your available startup and backup records. " +
                "Restore/install controls are not enabled until the next device-proven gate."
            textSize = 15f
            setPadding(0, dp(12), 0, dp(20))
        })
        statusText = TextView(this).apply {
            textSize = 16f
            setTextIsSelectable(true)
        }
        screen.addView(statusText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        screen.addView(Button(this).apply {
            text = "Refresh recovery status"
            setOnClickListener { showStatus() }
        })
        screen.addView(Button(this).apply {
            text = "Close Recovery"
            setOnClickListener { finish() }
        })
        showStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::statusText.isInitialized) showStatus()
    }

    private fun showStatus() {
        val result = runCatching { RiftManualRecoveryStore.status(this) }
        statusText.text = result.fold(
            onSuccess = { view ->
                buildString {
                    append("Recovery status: read-only Gate A\n\n")
                    appendSlot(this, "Core", view.getJSONObject("core"))
                    append("\n")
                    appendSlot(this, "RiftShell", view.getJSONObject("shell"))
                    append("\nFirst install: the existing embedded migration Core/Shell remain available.")
                    append("\n\nNo component will be switched or modified from this screen.")
                }
            },
            onFailure = { error ->
                "Could not inspect recovery records: " + error.javaClass.simpleName +
                    "\nNo component was changed. The embedded migration fallback is retained."
            }
        )
    }

    private fun appendSlot(out: StringBuilder, name: String, slot: JSONObject) {
        out.append(name).append("\n")
        out.append("Selected: ").append(slot.optString("active", "unknown")).append("\n")
        if (slot.has("activeSha256")) {
            out.append("Selected SHA: ").append(slot.optString("activeSha256")).append("\n")
        }
        out.append("Stored working backup: ").append(slot.optString("backup", "unknown")).append("\n")
        if (slot.has("backupSha256")) {
            out.append("Backup SHA: ").append(slot.optString("backupSha256")).append("\n")
        }
        if (slot.has("backupError") || slot.has("activeError")) {
            out.append("Record warning: invalid data; not safe to restore\n")
        }
        out.append("Startup marker: ")
            .append(if (slot.optBoolean("startupMarkerPresent", false)) "present" else "absent")
            .append("\n")
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
