package com.riftos.app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * C1.3-E: default-process Core-owned real graphical shell recovery lease.
 * No Core app BOOT, executable memory, grant or RAPP event lives here.
 * OS background Activity start restrictions are explicitly observable;
 * an unverified attempt must never be reported as completed recovery.
 */
object RiftCoreShellRecovery {
    const val SCHEMA = "riftos.core.shell-recovery/1"
    private const val STALE_MS = 3_500L
    private const val TICK_MS = 1_000L
    private const val ATTEMPT_COOLDOWN_MS = 5_000L
    private const val MAX_ATTEMPTS = 3
    private const val MAX_WINDOWS = 32
    private var app: Context? = null
    private val handler = Handler(Looper.getMainLooper())
    private var ownerPid = -1
    private var beat = 0L
    private var connectedSince = 0L
    private var foreground = false
    private var hasSeenShell = false
    private var snapshot = JSONObject().put("windows", JSONArray())
    private var previousPid = -1
    private var attempts = 0
    private var lastAttempt = 0L
    private var phase = "idle"
    private var lastError = ""
    private var recoverySerial = 0L
    private val tick = object : Runnable {
        override fun run() {
            runCatching { inspect() }
            handler.postDelayed(this, TICK_MS)
        }
    }

    @Synchronized
    fun initialize(context: Context) {
        if (app != null) return
        app = context.applicationContext
        handler.postDelayed(tick, TICK_MS)
    }

    @Synchronized
    fun noteReport(pid: Int, state: JSONObject) {
        val now = SystemClock.elapsedRealtime()
        if (ownerPid != pid) {
            if (ownerPid > 0) previousPid = ownerPid
            ownerPid = pid
            connectedSince = now
            // A rapidly crashing new shell may not reset the retry budget.
            // Only a stable live shell clears the bounded crash loop budget.
            if (!hasSeenShell) attempts = 0
            phase = if (hasSeenShell) "new-shell-connected" else "connected"
            recoverySerial++
        } else if (now - connectedSince >= 15_000L) {
            attempts = 0
        }
        hasSeenShell = true
        beat = now
        foreground = state.optBoolean("riftShellForeground", false)
        val windows = state.optJSONArray("windows") ?: JSONArray()
        if (windows.length() <= MAX_WINDOWS) snapshot = JSONObject(state.toString())
        if (!foreground) {
            // Expected Android Home/Settings/app switch: not a shell crash.
            phase = "background"
        } else if (phase == "new-shell-connected" || phase == "relaunch-requested") {
            phase = "restored-shell-reporting"
        } else phase = "connected"
    }

    @Synchronized
    private fun processAlive(context: Context, pid: Int): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val processes = manager.runningAppProcesses ?: return true
        return processes.any {
            it.pid == pid && it.uid == context.applicationInfo.uid &&
                it.processName == context.packageName + ":riftShell"
        }
    }

    @Synchronized
    private fun inspect() {
        val context = app ?: return
        if (!hasSeenShell || !foreground || ownerPid <= 0) return
        val now = SystemClock.elapsedRealtime()
        if (now - beat < STALE_MS) return
        // Do not infer death from one Binder failure or from backgrounding.
        // Verify process absence in Android's OS-owned process registry.
        if (processAlive(context, ownerPid)) {
            phase = "heartbeat-late-process-alive"
            return
        }
        if (phase != "relaunch-requested" && phase != "waiting-for-android") {
            phase = "shell-process-gone"
            previousPid = ownerPid
            // The dead graphical owner no longer has authority to receive
            // input; clear the Core lease without stopping any RAPP session.
            RiftCoreRuntime.sessions(context).requestFocusFromShell(null)
        }
        if (attempts >= MAX_ATTEMPTS) {
            phase = "relaunch-attempts-exhausted"
            return
        }
        if (now - lastAttempt < ATTEMPT_COOLDOWN_MS) return
        attempts++
        lastAttempt = now
        try {
            val intent = Intent(context, RiftShellActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(RiftShellActivity.EXTRA_RECOVERY_RESTART, true)
            }
            context.startActivity(intent)
            phase = "relaunch-requested"
            lastError = ""
        } catch (error: Exception) {
            phase = "waiting-for-android"
            lastError = (error.message ?: error.javaClass.simpleName).take(240)
        }
    }

    /**
     * Only the authenticated real shell Binder caller may claim this plan.
     * Reading a snapshot NEVER alters Core-owned RAPP sessions.
     */
    @Synchronized
    fun claim(pid: Int, context: Context): JSONObject {
        require(pid > 0 && pid != Process.myPid()) { "Invalid recovery claimant PID" }
        initialize(context)
        val restore = hasSeenShell && ownerPid > 0 && ownerPid != pid
        val state = if (restore) JSONObject(snapshot.toString()) else JSONObject()
        val apps = RiftCoreRuntime.lifecycle(context).status().getJSONArray("apps")
        val installed = RiftCoreRuntime.packages(context).listInstalled()
        val installedIds = HashSet<String>()
        for (i in 0 until installed.length()) {
            val item = installed.optJSONObject(i) ?: continue
            installedIds.add(item.optString("id"))
        }
        val allowed = HashSet<String>()
        for (i in 0 until apps.length()) {
            val item = apps.optJSONObject(i) ?: continue
            if (item.optString("state") == "running" && item.optBoolean("attached")) {
                allowed.add(item.optString("id"))
            }
        }
        val requested = state.optJSONArray("windows") ?: JSONArray()
        val windows = JSONArray()
        for (i in 0 until requested.length().coerceAtMost(MAX_WINDOWS)) {
            val window = requested.optJSONObject(i) ?: continue
            val id = window.optString("id")
            // Native windows are restorable as views but executable apps only
            // if they still exist as the same Core-owned running session.
            if (id.length !in 1..128) continue
            if (id in installedIds && id !in allowed) continue
            if (id !in installedIds && id !in setOf(
                "files", "workspace-live", "terminal", "browser", "editor",
                "devlab", "tasks", "installed-apps", "settings"
            )) continue
            val copy = JSONObject(window.toString())
            val entry = (0 until apps.length()).mapNotNull { apps.optJSONObject(it) }
                .firstOrNull { it.optString("id") == id }
            if (entry != null) copy.put("attachmentGeneration",
                entry.optLong("attachmentGeneration"))
            windows.put(copy)
        }
        return JSONObject().put("schema", SCHEMA)
            .put("corePid", Process.myPid()).put("restore", restore)
            .put("previousShellPid", if (restore) ownerPid else JSONObject.NULL)
            .put("currentShellPid", pid).put("serial", recoverySerial)
            .put("windows", windows)
            .put("activeId", state.opt("activeId"))
            .put("wasForeground", foreground)
    }

    @Synchronized
    fun status(): JSONObject = JSONObject()
        .put("schema", SCHEMA).put("corePid", Process.myPid())
        .put("shellPid", if (ownerPid > 0) ownerPid else JSONObject.NULL)
        .put("previousShellPid", if (previousPid > 0) previousPid else JSONObject.NULL)
        .put("phase", phase).put("foregroundLease", foreground)
        .put("attempts", attempts).put("lastError", lastError)
        .put("serial", recoverySerial)
        .put("snapshotWindowCount", snapshot.optJSONArray("windows")?.length() ?: 0)

    /**
     * QA only: deterministic actual process-death test, never Core PID/probe
     * or a protected production RAPP. Called after a user manually installs
     * the independently gated signed C1.3-E build.
     */
    @Synchronized
    fun killShellForProof(context: Context, disposableId: String): JSONObject {
        require(disposableId == "c12b2a-input-probe-20261008") {
            "Recovery QA requires the known disposable input probe"
        }
        val core = RiftCoreRuntime.lifecycle(context).status()
        val apps = core.getJSONArray("apps")
        require((0 until apps.length()).any {
            val item = apps.optJSONObject(it)
            item?.optString("id") == disposableId &&
                item.optString("state") == "running" && item.optBoolean("attached")
        }) { "Recovery QA requires a running Core-owned disposable RAPP" }
        require(ownerPid > 0 && ownerPid != Process.myPid() && foreground) {
            "Real production graphical shell must be connected and foreground"
        }
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        require(manager.runningAppProcesses?.any {
            it.pid == ownerPid && it.uid == context.applicationInfo.uid &&
                it.processName == context.packageName + ":riftShell"
        } == true) {
            "QA requires exact OS-attested production shell PID; no guessing"
        }
        val windows = snapshot.optJSONArray("windows") ?: JSONArray()
        require((0 until windows.length()).any {
            windows.optJSONObject(it)?.optString("id") == disposableId
        }) { "Disposable graphical window must be reported before shell death test" }
        val old = ownerPid
        // Explicit QA action only. The Core process is NEVER a test target.
        Process.killProcess(old)
        return JSONObject().put("schema", SCHEMA)
            .put("requested", true).put("oldShellPid", old)
            .put("corePid", Process.myPid())
            .put("appId", disposableId)
    }
}
