package com.riftos.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Native RiftOS desktop Activity and Android-host composition boundary.
 *
 * MainActivity owns visible Activity lifecycle/composition. Process-wide shell/MCP/Git/bridge
 * services are owned separately by RiftMcpRuntime. Chromium rendering is owned only by explicit
 * RiftBrowser classes; MainActivity does not create or host a renderer.
 */
class MainActivity : Activity() {
    private lateinit var rootView: FrameLayout
    private lateinit var nativeDesktop: RiftNativeDesktop
    private lateinit var browserAppHost: RiftBrowserAppHost
    private lateinit var nativeSystemApps: RiftNativeSystemApps
    private lateinit var nativeWorkspaceApps: RiftNativeWorkspaceApps
    private lateinit var browserWindow: RiftBrowserWindow
    private lateinit var workspaceRecords: RiftWorkspaceRecords
    private lateinit var workspaceWatcher: RiftWorkspaceWatcher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RiftMcpRuntime.registerActivity(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = 0xff0a0d12.toInt()
        window.navigationBarColor = 0xff0a0d12.toInt()

        rootView = FrameLayout(this).apply {
            clipChildren = true
            clipToPadding = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val safe = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            if (
                view.paddingLeft != safe.left || view.paddingTop != safe.top ||
                view.paddingRight != safe.right || view.paddingBottom != safe.bottom
            ) {
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            }
            windowInsets
        }

        workspaceRecords = RiftWorkspaceRecords.get(this)
        workspaceWatcher = RiftWorkspaceWatcher(this, { _ -> Unit }, workspaceRecords)
        workspaceWatcher.start()

        nativeDesktop = RiftNativeDesktop(
            activity = this,
            host = rootView,
            appOpenSink = ::openNativeDesktopApp,
            windowClosedSink = ::closeNativeDesktopApp
        )
        setContentView(rootView)
        ViewCompat.requestApplyInsets(rootView)

        browserWindow = RiftBrowserWindow(
            activity = this
        )
        browserAppHost = RiftBrowserAppHost(this, nativeDesktop)
        nativeSystemApps = RiftNativeSystemApps(this, nativeDesktop, RiftMcpRuntime.nativeShell(this))
        nativeWorkspaceApps = RiftNativeWorkspaceApps(this, nativeDesktop)

        populateNativeLauncher()
        nativeDesktop.handle("desktop.window.bootstrap", JSONObject())

        // MCP/relay is process-owned and independent of renderer lifecycle.
        RiftMcpRuntime.relayClient(this).start()
    }

    private fun populateNativeLauncher() {
        val apps = collectLauncherApps()
        nativeDesktop.handle(
            "desktop.launcher.update",
            JSONObject().put("apps", apps)
        )
    }

    private fun collectLauncherApps(): JSONArray {
        val apps = JSONArray()
        fun add(id: String, name: String, icon: String) {
            apps.put(JSONObject().put("id", id).put("name", name).put("icon", icon))
        }

        add("files", "Files", "▣")
        add("workspace-live", "Workspace Records", "◈")
        add("terminal", "RiftShell", ">_")
        add("browser", "RiftBrowser", "◎")
        add("editor", "Editor", "{}")
        add("devlab", "Dev Lab", "◇")
        add("tasks", "Tasks", "≡")
        add("settings", "Settings", "⚙")

        val used = linkedSetOf(
            "files", "workspace-live", "terminal", "browser",
            "editor", "devlab", "tasks", "settings"
        )
        val riftRoot = File(filesDir, "riftfs").canonicalFile
        val programs = File(riftRoot, RiftVolumePaths.resolveRelative("/C:/Programs")).canonicalFile
        if (programs.isDirectory && programs.path.startsWith(riftRoot.path + File.separator)) {
            programs.listFiles()
                ?.filter { it.isDirectory }
                ?.sortedBy { it.name.lowercase() }
                ?.forEach { directory ->
                    val packageFile = File(directory, "package.json")
                    if (!packageFile.isFile || packageFile.length() !in 1..(8L * 1024L * 1024L)) return@forEach
                    val manifest = runCatching {
                        JSONObject(packageFile.readText(Charsets.UTF_8)).optJSONObject("manifest")
                    }.getOrNull() ?: return@forEach
                    val id = manifest.optString("id").trim()
                    if (
                        id.isBlank() || id in used || directory.name != id ||
                        !id.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._-]{1,63}$"))
                    ) return@forEach
                    used += id
                    add(
                        id,
                        manifest.optString("name", id).trim().ifBlank { id }.take(64),
                        manifest.optString("launcherIcon", "□").trim().ifBlank { "□" }.take(4)
                    )
                }
        }

        return apps
    }

    fun nativeAppsForShell(): JSONArray = JSONArray(collectLauncherApps().toString())

    fun nativeDesktopStateForShell(): JSONObject = onUiSync {
        nativeDesktop.handle("desktop.window.state", JSONObject())
    }

    fun closeNativeWindowFromShell(id: String): JSONObject = onUiSync {
        nativeDesktop.handle("desktop.window.close", JSONObject().put("id", id))
    }

    private fun <T> onUiSync(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        var value: T? = null
        var failure: Throwable? = null
        runOnUiThread {
            try { value = block() } catch (error: Throwable) { failure = error } finally { latch.countDown() }
        }
        require(latch.await(5_000L, TimeUnit.MILLISECONDS)) { "Timed out waiting for native UI authority" }
        failure?.let { throw IllegalStateException(it.message ?: it.javaClass.simpleName, it) }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    fun openAppFromNativeShell(id: String) = openNativeDesktopApp(id)

    fun onInstalledAppGrantRevokedFromShell(appId: String, capability: String) {
        runOnUiThread {
            if (::browserAppHost.isInitialized) browserAppHost.onGrantRevoked(appId, capability)
        }
    }

    fun openBrowserFromNativeShell(url: String = "https://chatgpt.com") {
        runOnUiThread { openBrowserWindow(url) }
    }

    fun openDesktopBrowser(rawUrl: String) = openBrowserFromNativeShell(rawUrl)

    fun openPreviewFromNativeShell(root: String, entry: String = "index.html") {
        runOnUiThread {
            startActivity(
                Intent(this, RiftBrowserPreviewActivity::class.java)
                    .putExtra(RiftBrowserPreviewActivity.EXTRA_ROOT, root)
                    .putExtra(RiftBrowserPreviewActivity.EXTRA_ENTRY, entry)
            )
        }
    }

    private fun openNativeDesktopApp(rawId: String) {
        val id = rawId.trim()
        if (id.isBlank()) return
        runOnUiThread {
            if (::nativeSystemApps.isInitialized && nativeSystemApps.openFromLauncher(id)) return@runOnUiThread
            if (::nativeWorkspaceApps.isInitialized && nativeWorkspaceApps.openFromLauncher(id)) return@runOnUiThread
            if (id == "browser") {
                openBrowserWindow("")
                return@runOnUiThread
            }

            // Anything else in the launcher is an installed Rift program rendered by RiftBrowser.
            runCatching {
                nativeDesktop.handle(
                    "desktop.window.open",
                    JSONObject()
                        .put("id", id)
                        .put("title", id)
                        .put("kicker", "RIFTBROWSER APP")
                )
                browserAppHost.open(JSONObject().put("appId", id).put("windowId", id))
            }.onFailure {
                runCatching {
                    nativeDesktop.handle("desktop.window.close", JSONObject().put("id", id))
                }
            }
        }
    }

    private fun openBrowserWindow(rawUrl: String) {
        nativeDesktop.handle(
            "desktop.window.open",
            JSONObject()
                .put("id", "browser")
                .put("title", "RiftBrowser")
                .put("kicker", "RIFTBROWSER")
        )
        nativeDesktop.attachContent("browser", browserWindow.nativeWindowView())
        browserWindow.open(rawUrl)
    }

    private fun closeNativeDesktopApp(id: String) {
        if (::nativeSystemApps.isInitialized && nativeSystemApps.onDesktopClosed(id)) return
        if (::nativeWorkspaceApps.isInitialized && nativeWorkspaceApps.onDesktopClosed(id)) return
        if (id == "browser") {
            if (::browserWindow.isInitialized) browserWindow.close()
            return
        }
        if (::browserAppHost.isInitialized) browserAppHost.closeWindow(id)
    }

    fun inspectActiveBrowser(request: JSONObject): JSONObject {
        check(::browserWindow.isInitialized) { "RiftBrowser is not initialized" }
        val latch = CountDownLatch(1)
        var result: JSONObject? = null
        var failure: Throwable? = null
        runOnUiThread {
            try {
                browserWindow.inspect(request) { value, error ->
                    result = value
                    failure = error
                    latch.countDown()
                }
            } catch (error: Throwable) {
                failure = error
                latch.countDown()
            }
        }
        require(latch.await(5_000L, TimeUnit.MILLISECONDS)) {
            "Timed out waiting for RiftBrowser inspector"
        }
        failure?.let {
            throw IllegalStateException(it.message ?: "RiftBrowser inspector failed", it)
        }
        return result ?: throw IllegalStateException(
            "RiftBrowser inspector returned no result"
        )
    }

    @Deprecated("Activity result compatibility path")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (::browserWindow.isInitialized && browserWindow.onActivityResult(requestCode, resultCode, data)) return
        if (::nativeWorkspaceApps.isInitialized) nativeWorkspaceApps.onActivityResult(requestCode, resultCode, data)
    }

    override fun onBackPressed() {
        if (::browserWindow.isInitialized) {
            val browser = runCatching { browserWindow.state() }.getOrNull()
            if (
                browser?.optBoolean("visible") == true &&
                browser.optBoolean("canGoBack")
            ) {
                browserWindow.back()
                return
            }
        }
        if (::nativeDesktop.isInitialized && nativeDesktop.handleBack()) return
        super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        RiftMcpRuntime.registerActivity(this)
        if (::browserAppHost.isInitialized) browserAppHost.onResume()
        if (::browserWindow.isInitialized) browserWindow.onResume()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) RiftMcpRuntime.registerActivity(this)
    }

    override fun onPause() {
        if (::browserWindow.isInitialized) browserWindow.onPause()
        if (::browserAppHost.isInitialized) browserAppHost.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        RiftMcpRuntime.unregisterActivity(this)
        if (::workspaceWatcher.isInitialized) workspaceWatcher.shutdown()
        if (::browserWindow.isInitialized) browserWindow.destroy()
        if (::nativeSystemApps.isInitialized) nativeSystemApps.destroy()
        if (::nativeWorkspaceApps.isInitialized) nativeWorkspaceApps.destroy()
        if (::browserAppHost.isInitialized) browserAppHost.destroy()
        if (::nativeDesktop.isInitialized) nativeDesktop.destroy()
        super.onDestroy()
    }
}
