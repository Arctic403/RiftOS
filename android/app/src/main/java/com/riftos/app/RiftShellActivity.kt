package com.riftos.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.os.Handler
import java.util.concurrent.Executors
import android.os.Process
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * C1.3-D production graphical RiftShell in its own :riftShell Android process.
 * It owns desktop, taskbar, windows, graphical RAPP surfaces and Android input.
 * It is NOT the old :riftShellProbe read-only diagnostic process.
 *
 * The Core singleton and Core app execution are forbidden in this process:
 * every RAPP launch/stop/focus/input/frame flows via RiftShellCoreClient.
 * C1.3-E Core recovery claims an old real-shell window snapshot before
 * this process publishes its new desktop; installed-device proof remains pending.
 */
class RiftShellActivity : Activity() {
    companion object {
        const val EXTRA_OPEN_APP_ID = "riftos.shell.open-app-id"
        const val EXTRA_RECOVERY_RESTART = "riftos.shell.recovery-restart"
    }
    private lateinit var core: RiftShellCoreClient
    private lateinit var desktop: RiftNativeDesktop
    private lateinit var rapps: RiftShellRappHost
    private lateinit var uiEffects: RiftRemoteShellUiClient
    private lateinit var systemApps: RiftNativeSystemApps
    private lateinit var workspaceApps: RiftNativeWorkspaceApps
    private lateinit var browser: RiftBrowserWindow
    private lateinit var browserApps: RiftBrowserAppHost
    private lateinit var workspaceWatcher: RiftWorkspaceWatcher
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateReporter = Executors.newSingleThreadExecutor { task ->
        Thread(task, "rift-shell-desktop-state-ipc").apply { isDaemon = true }
    }
    private val reportTick = object : Runnable {
        override fun run() {
            if (ready && active && !isFinishing && !isDestroyed) {
                val state = desktop.handle("desktop.window.state", JSONObject())
                    .put("riftShellForeground", active && hasWindowFocus())
                runCatching { stateReporter.execute {
                    runCatching { core.reportDesktop(state) }
                } }
            }
            if (ready) mainHandler.postDelayed(this, 1000L)
        }
    }
    private var ready = false
    private var active = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        require(android.os.Build.VERSION.SDK_INT < 28 ||
            android.app.Application.getProcessName() == packageName + ":riftShell") {
            "Real graphical shell must be in :riftShell Android process"
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = 0xff0a0d12.toInt()
        window.navigationBarColor = 0xff0a0d12.toInt()

        val host = FrameLayout(this).apply { clipChildren = true; clipToPadding = true }
        ViewCompat.setOnApplyWindowInsetsListener(host) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout())
            if (view.paddingLeft != safe.left || view.paddingTop != safe.top ||
                view.paddingRight != safe.right || view.paddingBottom != safe.bottom) {
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            }
            insets
        }
        val workspaceRecords = RiftWorkspaceRecords.get(this)
        workspaceWatcher = RiftWorkspaceWatcher(this, { _ -> Unit }, workspaceRecords)
        Thread({ runCatching { workspaceWatcher.start() } },
            "rift-shell-workspace-watcher").apply { isDaemon = true }.start()
        core = RiftShellCoreClient(applicationContext)
        // Claim before publishing any state: a new PID must read Core's old
        // window snapshot before the replacement overwrites that lease.
        val restorePlan = runCatching { core.claimRecovery() }.getOrNull()
        desktop = RiftNativeDesktop(
            activity = this, host = host,
            appOpenSink = ::openDesktopApp,
            windowClosedSink = ::onWindowClosed,
            focusRequestSink = { id ->
                // Android focus AND lifecycle are required; Core verifies
                // the live RAPP attachment generation itself.
                runCatching { core.focus(id.takeIf {
                    active && hasWindowFocus() && !isFinishing && !isDestroyed
                }) }
            }
        )
        setContentView(host)
        ViewCompat.requestApplyInsets(host)
        browser = RiftBrowserWindow(this)
        browserApps = RiftBrowserAppHost(this, desktop)
        systemApps = RiftNativeSystemApps(this, desktop,
            RiftRemoteShellExecutor(core), core)
        workspaceApps = RiftNativeWorkspaceApps(this, desktop)
        rapps = RiftShellRappHost(this, desktop, core, ::refreshLauncher)
        uiEffects = RiftRemoteShellUiClient(this, desktop, core,
            ::openDesktopApp, ::dispatchDesktopCommand)
        desktop.handle("desktop.launcher.update",
            JSONObject().put("apps", nativeLauncherEntries()))
        desktop.handle("desktop.window.bootstrap", JSONObject())
        ready = true
        // Recreate graphical windows only; executable RAPPs are reattached
        // to their existing Core-issued generation, NEVER restarted.
        if (restorePlan?.optBoolean("restore") == true) {
            restoreDesktopWindows(restorePlan)
        }
        mainHandler.post(reportTick)
        refreshLauncher()
        intent?.getStringExtra(EXTRA_OPEN_APP_ID)
            ?.takeIf { it.isNotBlank() }?.let(::openDesktopApp)
    }

    private fun nativeLauncherEntries(): JSONArray {
        val result = JSONArray()
        val builtins = listOf(
            Triple("files", "Files", "▣"),
            Triple("workspace-live", "Workspace Records", "◈"),
            Triple("terminal", "RiftShell", ">_"),
            Triple("browser", "RiftBrowser", "◎"),
            Triple("editor", "Editor", "{}"),
            Triple("devlab", "Dev Lab", "◇"),
            Triple("tasks", "Tasks", "≡"),
            Triple("installed-apps", "Installed Apps", "▦"),
            Triple("admin-permissions", "Admin Approvals", "🔒"),
            Triple("settings", "Settings", "⚙"),
            Triple("mcp", "Rift MCP", "⇄")
        )
        for ((id, name, icon) in builtins) {
            result.put(JSONObject().put("id", id)
                .put("name", name).put("icon", icon))
        }
        return result
    }

    private fun refreshLauncher() {
        if (!ready || isFinishing || isDestroyed) return
        Thread({
            val apps = runCatching { core.installed() }.getOrNull() ?: return@Thread
            val entries = nativeLauncherEntries()
            for (index in 0 until apps.length().coerceAtMost(128)) {
                val app = apps.optJSONObject(index) ?: continue
                val id = app.optString("id")
                if (!id.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._-]{1,63}$"))) continue
                entries.put(JSONObject().put("id", id)
                    .put("name", app.optString("name", id).take(64))
                    .put("icon", app.optString("launcherIcon", "□").take(4)))
            }
            runOnUiThread {
                if (ready && !isFinishing && !isDestroyed) {
                    desktop.handle("desktop.launcher.update",
                        JSONObject().put("apps", entries))
                }
            }
        }, "rift-shell-launcher-catalogue").apply { isDaemon = true }.start()
    }

    private fun openDesktopApp(raw: String) {
        val id = raw.trim()
        if (id.isBlank() || !ready) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { openDesktopApp(id) }
            return
        }
        if (systemApps.openFromLauncher(id)) return
        if (workspaceApps.openFromLauncher(id)) return
        if (id == "mcp") {
            startActivity(Intent(this, RiftMcpActivity::class.java))
            return
        }
        if (id == "browser") {
            desktop.handle("desktop.window.open", JSONObject()
                .put("id", "browser").put("title", "RiftBrowser")
                .put("kicker", "RIFTBROWSER"))
            desktop.attachContent("browser", browser.nativeWindowView())
            browser.open("")
            return
        }
        if (rapps.openFromLauncher(id)) return
        // Hosted browser applications remain managed by their separate generic
        // WebView bridge. They do not become executable RAPP sessions.
        runCatching {
            desktop.handle("desktop.window.open", JSONObject()
                .put("id", id).put("title", id)
                .put("kicker", "RIFTBROWSER APP"))
            browserApps.openAsync(JSONObject().put("appId", id)
                .put("windowId", id)) { result ->
                if (!result.optBoolean("ok", false)) {
                    runCatching {
                        desktop.handle("desktop.window.close",
                            JSONObject().put("id", id))
                    }
                }
            }
        }.onFailure {
            runCatching {
                desktop.handle("desktop.window.close", JSONObject().put("id", id))
            }
        }
    }

    private fun onWindowClosed(id: String) {
        if (systemApps.onDesktopClosed(id)) return
        if (workspaceApps.onDesktopClosed(id)) return
        if (rapps.onDesktopClosed(id)) return
        if (id == "browser") { browser.close(); return }
        browserApps.closeWindow(id)
    }

    private fun dispatchDesktopCommand(method: String, argument: String) {
        if (!ready) return
        when (method) {
            "open" -> openDesktopApp(argument)
            "close" -> desktop.handle("desktop.window.close",
                JSONObject().put("id", argument))
            "browser" -> {
                desktop.handle("desktop.window.open", JSONObject()
                    .put("id", "browser").put("title", "RiftBrowser")
                    .put("kicker", "RIFTBROWSER"))
                desktop.attachContent("browser", browser.nativeWindowView())
                browser.open(argument)
            }
        }
    }

    /**
     * C1.3-E bounded native desktop reconstruction after real shell PID loss.
     * The Core process contains the only authoritative RAPP execution state.
     */
    private fun restoreDesktopWindows(plan: JSONObject) {
        val saved = plan.optJSONArray("windows") ?: return
        val items = (0 until saved.length().coerceAtMost(32)).mapNotNull {
            saved.optJSONObject(it)
        }.sortedBy { it.optLong("z") }
        for (item in items) {
            val id = item.optString("id")
            if (id.isBlank() || id.length > 128) continue
            val gen = item.optLong("attachmentGeneration", -1L)
            val opened = if (gen > 0L) {
                // A stale generation is rejected by Core; cannot invoke BOOT.
                rapps.openFromRecovery(id, gen)
            } else {
                runCatching { openDesktopApp(id); true }.getOrDefault(false)
            }
            if (!opened) continue
            runCatching {
                val frame = item.optJSONObject("framePx")
                if (frame != null) desktop.handle("desktop.window.recoverBounds",
                    JSONObject().put("id", id).put("framePx", frame))
                if (item.optBoolean("maximized")) {
                    desktop.handle("desktop.window.maximize", JSONObject().put("id", id))
                }
                if (item.optBoolean("minimized")) {
                    desktop.handle("desktop.window.minimize", JSONObject().put("id", id))
                }
            }
        }
        val selected = plan.optString("activeId")
        if (selected.isNotBlank() && selected != "null") {
            runCatching { desktop.handle("desktop.window.focus",
                JSONObject().put("id", selected)) }
        }
    }

    private fun restoreFocus() {
        if (!ready || !active || !hasWindowFocus()) return
        val state = desktop.handle("desktop.window.state", JSONObject())
        val selected = state.optString("activeId").takeIf {
            it.isNotBlank() && it != "null" && !state.optBoolean("desktopVisible")
        }
        val windows = state.optJSONArray("windows")
        val focused = selected?.takeIf { id ->
            windows != null && (0 until windows.length()).any { index ->
                val w = windows.optJSONObject(index)
                w?.optString("id") == id && w.optBoolean("focused") &&
                    !w.optBoolean("minimized")
            }
        }
        runCatching { core.focus(focused) }
    }

    override fun onResume() {
        super.onResume()
        active = true
        if (ready) {
            browser.onResume()
            browserApps.onResume()
            uiEffects.resume()
            refreshLauncher()
            restoreFocus()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!ready) return
        if (hasFocus) restoreFocus() else runCatching { core.focus(null) }
    }

    override fun onPause() {
        active = false
        if (ready) {
            runCatching { core.focus(null) }
            // A normal Home/app switch is NOT a shell crash. Report its
            // background lifecycle before Core's heartbeat watchdog fires.
            val state = desktop.handle("desktop.window.state", JSONObject())
                .put("riftShellForeground", false)
            runCatching { stateReporter.execute {
                runCatching { core.reportDesktop(state) }
            } }
            browser.onPause()
            browserApps.onPause()
            uiEffects.pause()
        }
        super.onPause()
    }

    @Deprecated("Activity result compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (ready && systemApps.onActivityResult(requestCode, resultCode, data)) return
        if (ready && browser.onActivityResult(requestCode, resultCode, data)) return
        if (ready) workspaceApps.onActivityResult(requestCode, resultCode, data)
    }

    @Deprecated("Back navigation compatibility")
    override fun onBackPressed() {
        if (ready && desktop.handleBack()) return
        super.onBackPressed()
    }

    override fun onDestroy() {
        active = false
        ready = false
        mainHandler.removeCallbacks(reportTick)
        stateReporter.shutdownNow()
        runCatching { core.focus(null) }
        if (::uiEffects.isInitialized) uiEffects.destroy()
        if (::workspaceWatcher.isInitialized) workspaceWatcher.shutdown()
        if (::rapps.isInitialized) rapps.destroy()
        if (::systemApps.isInitialized) systemApps.destroy()
        if (::workspaceApps.isInitialized) workspaceApps.destroy()
        if (::browserApps.isInitialized) browserApps.destroy()
        if (::browser.isInitialized) browser.destroy()
        if (::desktop.isInitialized) desktop.destroy()
        // No Core stop/kill, relay disposal or app execution happens here.
        super.onDestroy()
    }
}
