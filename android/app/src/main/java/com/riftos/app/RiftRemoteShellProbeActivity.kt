package com.riftos.app

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File

/**
 * C1.3-A: read-only proof shell Activity in the :riftShellProbe process.
 * It receives frames ONLY through ContentResolver IPC from Core's main
 * process. No Core runtime singleton, RAPP executable or input authority.
 * Polling is bounded proof pending future subscribed IPC and reconnect.
 */
class RiftRemoteShellProbeActivity : Activity() {
    companion object {
        const val EXTRA_APP_ID = "riftos.core.remote-shell-app-id"
        private const val POLL_MS = 1000L
        private val CORE_URI = Uri.parse(
            "content://" + RiftCoreSurfaceIpcProvider.AUTHORITY
        )
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var selectedAppId: String
    private lateinit var state: TextView
    private lateinit var content: LinearLayout
    private lateinit var terminateProbe: Button
    private var lastVerifiedCorePid: Int = -1
    private var visible = false
    private var renderedKey = ""

    private val poll = object : Runnable {
        override fun run() {
            if (!visible) return
            refreshFromCore()
            if (visible) mainHandler.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedAppId = intent.getStringExtra(EXTRA_APP_ID).orEmpty()
        require(selectedAppId.isNotBlank() && selectedAppId.length <= 128) {
            "RiftShell IPC client app ID invalid"
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xff0e1828.toInt())
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        root.addView(TextView(this).apply {
            text = "RiftShell cross-process IPC proof"
            textSize = 20f
            setTextColor(0xffffffff.toInt())
        })
        state = TextView(this).apply {
            text = "Waiting for Core IPC snapshot…"
            textSize = 13f
            setTextColor(0xffc2d4eb.toInt())
            setPadding(0, dp(10), 0, dp(10))
        }
        root.addView(state)
        root.addView(Button(this).apply {
            text = "Close IPC viewer"
            setOnClickListener { finish() }
        })
        terminateProbe = Button(this).apply {
            text = "Terminate isolated shell probe (test)"
            isEnabled = false
            setOnClickListener {
                val pid = Process.myPid()
                // Never terminate main RiftOS/Core or a process not exactly
                // registered as this disposable, isolated proof shell.
                check(lastVerifiedCorePid > 0 && pid != lastVerifiedCorePid &&
                    isExactRemoteProbeProcess()) {
                    "Remote shell termination refused: unverified process boundary"
                }
                Process.killProcess(pid)
            }
        }
        root.addView(terminateProbe)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        visible = true
        mainHandler.post(poll)
    }

    override fun onStop() {
        visible = false
        mainHandler.removeCallbacks(poll)
        super.onStop()
    }

    private fun refreshFromCore() {
        val result = runCatching {
            val reply = contentResolver.call(
                CORE_URI, RiftCoreSurfaceIpcProvider.METHOD_SNAPSHOT,
                selectedAppId, null
            ) ?: error("Core IPC returned no Bundle")
            JSONObject(reply.getString(RiftCoreSurfaceIpcProvider.RESULT_JSON)
                ?: error("Core IPC reply missing JSON"))
        }.getOrElse { failure ->
            lastVerifiedCorePid = -1
            terminateProbe.isEnabled = false
            state.text = "Core IPC unavailable: " +
                (failure.message ?: failure.javaClass.simpleName)
            return
        }
        require(result.optString("schema") == RiftCoreSurfaceIpcProvider.SCHEMA) {
            "Core IPC schema mismatch"
        }
        val corePid = result.optInt("corePid", -1)
        val shellPid = Process.myPid()
        val separate = corePid > 0 && shellPid > 0 && corePid != shellPid
        val present = result.optBoolean("present", false)
        lastVerifiedCorePid = if (separate && present) corePid else -1
        terminateProbe.isEnabled = lastVerifiedCorePid > 0 && isExactRemoteProbeProcess()
        val revision = result.optLong("revision", -1L)
        val generation = result.optLong("attachmentGeneration", -1L)
        state.text = "app=" + selectedAppId + "\n" +
            "Core PID=" + corePid + " · Shell PID=" + shellPid + "\n" +
            "Separate Android processes: " + separate + "\n" +
            "Core generation=" + generation + " · revision=" + revision + "\n" +
            "Read-only Binder snapshot; no input authority"
        val key = corePid.toString() + ":" + shellPid + ":" +
            present + ":" + generation + ":" + revision
        if (key == renderedKey) return
        renderedKey = key
        content.removeAllViews()
        if (!present) {
            content.addView(nodeText("[Core surface not available]"))
            return
        }
        val nodes = result.optJSONArray("nodes") ?: return
        for (index in 0 until nodes.length().coerceAtMost(256)) {
            val node = nodes.optJSONObject(index) ?: continue
            val kind = node.optInt("kind")
            if (kind == RiftAppAbi.NodeKind.ROOT ||
                kind == RiftAppAbi.NodeKind.SURFACE) continue
            val value = node.optString("text")
            val label = when (kind) {
                RiftAppAbi.NodeKind.TEXT -> value
                RiftAppAbi.NodeKind.TEXT_INPUT -> "TEXT INPUT (IPC read-only): " + value
                RiftAppAbi.NodeKind.ACTION -> "ACTION (IPC read-only): " + value
                RiftAppAbi.NodeKind.IMAGE -> "IMAGE (IPC read-only) #" + node.optInt("id")
                else -> "NODE #" + node.optInt("id") + ": " + value
            }
            content.addView(nodeText(label))
        }
    }

    /** Guard against ever terminating the main Core/desktop process. */
    private fun isExactRemoteProbeProcess(): Boolean = runCatching {
        File("/proc/self/cmdline").inputStream().use { stream ->
            val bytes = ByteArray(256)
            val count = stream.read(bytes)
            count > 0 &&
                String(bytes, 0, count, Charsets.UTF_8)
                    .substringBefore('\u0000') == packageName + ":riftShellProbe"
        }
    }.getOrDefault(false)

    private fun nodeText(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 17f
        setTextColor(0xffffffff.toInt())
        setBackgroundColor(0xff25364a.toInt())
        setPadding(dp(12), dp(12), dp(12), dp(12))
        isClickable = false
        isFocusable = false
    }

    private fun dp(size: Int) = (size * resources.displayMetrics.density).toInt()
}
