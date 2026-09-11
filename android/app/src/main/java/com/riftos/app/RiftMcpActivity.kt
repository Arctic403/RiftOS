package com.riftos.app

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** Native local-only settings surface launched from the RiftOS Rift MCP system app. */
class RiftMcpActivity : Activity() {
    private lateinit var host: RiftToolHost
    private lateinit var readToggle: CheckBox
    private lateinit var writeToggle: CheckBox
    private lateinit var statusView: TextView
    private lateinit var auditView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Rift MCP"
        host = RiftMcpRuntime.toolHost(this)

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
            text = "Local MCP tools for RiftBrowser. Tool execution, permissions and activity stay on this device. No remote relay, pairing key, WebSocket service or public endpoint is used."
            textSize = 14f
            setPadding(0, 12, 0, 20)
        })

        content.addView(label("Local tool permissions"))
        readToggle = CheckBox(this).apply {
            text = "Allow read tools (info, stat, list, readText)"
        }
        writeToggle = CheckBox(this).apply {
            text = "Allow write tools (writeText, mkdir, remove, move)"
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
    }

    private fun refresh() {
        val access = host.access()
        readToggle.isChecked = access.optBoolean("sandboxRead", true)
        writeToggle.isChecked = access.optBoolean("sandboxWrite", false)
        statusView.text = buildString {
            append("Mode: local-only in-process MCP")
            append("\nTools: ").append(host.tools().length())
            append("\nWorkspace: ").append(access.optString("workspaceScope", "riftfs/workspace"))
            append("\nScope: workspace only")
            append("\nRead tools: ").append(if (access.optBoolean("sandboxRead", true)) "allowed" else "blocked")
            append("\nWrite tools: ").append(if (access.optBoolean("sandboxWrite", false)) "allowed" else "blocked")
            append("\nRemote relay: none")
            append("\nPairing: none")
        }
        auditView.text = formatAudit(host.audit())
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
