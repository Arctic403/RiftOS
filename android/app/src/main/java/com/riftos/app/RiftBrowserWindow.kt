package com.riftos.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

/**
 * RiftOS-owned browser window surface.
 *
 * One desktop browser window owns a bounded set of renderer tabs. Every tab keeps its own
 * RiftBrowserEngine/history/session state. RiftDesktop owns the outer content View's geometry and
 * visibility; this coordinator pauses/resumes renderer children from that actual native visibility.
 */
class RiftBrowserWindow(
    private val activity: Activity
) {
    companion object {
        private const val FILE_CHOOSER_REQUEST = 7002
        private const val MAX_TABS = 8
        private const val DEFAULT_URL = "https://chatgpt.com"
        private const val NEW_TAB_URL = "https://www.google.com"
    }

    private data class BrowserTab(
        val id: String,
        var engine: RiftBrowserEngine,
        var rendererRecoveries: Int = 0,
        var recovering: Boolean = false
    )

    private var lifecycleReady = false
    private val surfaceHost = object : FrameLayout(activity) {
        override fun onVisibilityChanged(changedView: View, visibility: Int) {
            super.onVisibilityChanged(changedView, visibility)
            if (lifecycleReady) syncRendererLifecycle()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (lifecycleReady) syncRendererLifecycle()
        }

        override fun onDetachedFromWindow() {
            if (lifecycleReady) pauseAllRenderers()
            super.onDetachedFromWindow()
        }
    }.apply {
        visibility = View.GONE
        isClickable = false
        isFocusable = false
        clipChildren = true
        clipToPadding = true
        setBackgroundColor(0xff0a0d12.toInt())
    }

    private val tabs = LinkedHashMap<String, BrowserTab>()
    private var activeTabId: String? = null
    private var destroyed = false
    private var resumed = true
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    init {
        createTabInternal(null, select = true, load = false)
        lifecycleReady = true
        surfaceHost.visibility = View.GONE
        syncRendererLifecycle()
    }

    fun nativeWindowView(): View {
        ensureAlive()
        return surfaceHost
    }

    fun open(rawUrl: String?): JSONObject {
        ensureAlive()
        val engine = activeEngine()
        val current = engine.currentUrl()
        val target = normalizeStartUrl(rawUrl).ifBlank {
            current.takeIf { it.isNotBlank() && it != "about:blank" } ?: DEFAULT_URL
        }
        if (current.isBlank() || current == "about:blank") {
            engine.loadUrl(target)
        } else if (rawUrl?.isNotBlank() == true && normalizeStartUrl(rawUrl) != current) {
            engine.loadUrl(target)
        }
        refreshEngineVisibility()
        syncRendererLifecycle()
        return state()
    }

    fun navigate(rawUrl: String?): JSONObject {
        ensureAlive()
        activeEngine().loadUrl(normalizeStartUrl(rawUrl).ifBlank { DEFAULT_URL })
        return state()
    }

    fun back(): JSONObject {
        ensureAlive()
        activeEngine().let { if (it.canGoBack()) it.goBack() }
        return state()
    }

    fun forward(): JSONObject {
        ensureAlive()
        activeEngine().let { if (it.canGoForward()) it.goForward() }
        return state()
    }

    fun reload(): JSONObject {
        ensureAlive()
        activeEngine().reload()
        return state()
    }

    fun setDesktopMode(enabled: Boolean): JSONObject {
        ensureAlive()
        activeEngine().setDesktopMode(enabled)
        return state()
    }

    fun inspect(request: JSONObject, callback: (JSONObject?, Throwable?) -> Unit) {
        ensureAlive()
        activeEngine().inspect(request, callback)
    }

    fun newTab(rawUrl: String?): JSONObject {
        ensureAlive()
        createTabInternal(rawUrl, select = true, load = true)
        refreshEngineVisibility()
        syncRendererLifecycle()
        return state()
    }

    fun selectTab(tabId: String?): JSONObject {
        ensureAlive()
        val id = tabId.orEmpty()
        require(tabs.containsKey(id)) { "Unknown RiftBrowser tab: $id" }
        selectTabInternal(id)
        refreshEngineVisibility()
        syncRendererLifecycle()
        return state()
    }

    fun closeTab(tabId: String?): JSONObject {
        ensureAlive()
        val id = tabId?.takeIf { it.isNotBlank() } ?: activeTabId.orEmpty()
        val tab = tabs[id] ?: throw IllegalArgumentException("Unknown RiftBrowser tab: $id")
        val order = tabs.keys.toList()
        val index = order.indexOf(id)
        val wasActive = activeTabId == id
        tabs.remove(id)
        runCatching { surfaceHost.removeView(tab.engine.view) }
        runCatching { tab.engine.destroy() }

        if (tabs.isEmpty()) {
            createTabInternal(null, select = true, load = false)
        } else if (wasActive) {
            val remaining = tabs.keys.toList()
            val fallback = remaining[(index - 1).coerceIn(0, remaining.lastIndex)]
            selectTabInternal(fallback)
        }
        refreshEngineVisibility()
        syncRendererLifecycle()
        return state()
    }

    fun state(): JSONObject {
        val active = activeTab()
        val state = active.engine.state()
        val tabArray = JSONArray()
        tabs.values.forEach { tab ->
            val rendererState = tab.engine.state()
            tabArray.put(
                JSONObject()
                    .put("id", tab.id)
                    .put("title", rendererState.optString("title", "RiftBrowser"))
                    .put("url", rendererState.optString("url", ""))
                    .put("progress", rendererState.optInt("progress", 0))
                    .put("crashed", rendererState.optBoolean("crashed", false))
                    .put("canGoBack", rendererState.optBoolean("canGoBack", false))
                    .put("canGoForward", rendererState.optBoolean("canGoForward", false))
                    .put("desktopMode", rendererState.optBoolean("desktopMode", false))
                    .put("active", tab.id == active.id)
            )
        }
        state.put("open", !destroyed)
        state.put("visible", !destroyed && surfaceHost.parent != null && surfaceHost.isShown)
        state.put("surface", "rift-window-owned")
        state.put("activeTabId", active.id)
        state.put("tabCount", tabs.size)
        state.put("maxTabs", MAX_TABS)
        state.put("tabs", tabArray)
        return state
    }

    fun close(): Boolean {
        if (destroyed) return true
        surfaceHost.visibility = View.GONE
        pauseAllRenderers()
        return true
    }

    fun onResume() {
        if (destroyed) return
        resumed = true
        refreshEngineVisibility()
        syncRendererLifecycle()
    }

    fun onPause() {
        if (destroyed) return
        resumed = false
        pauseAllRenderers()
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        pauseAllRenderers()
        surfaceHost.visibility = View.GONE
        val existing = tabs.values.toList()
        tabs.clear()
        activeTabId = null
        existing.forEach { tab ->
            runCatching { surfaceHost.removeView(tab.engine.view) }
            runCatching { tab.engine.destroy() }
        }
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        runCatching { (surfaceHost.parent as? ViewGroup)?.removeView(surfaceHost) }
        runCatching { surfaceHost.removeAllViews() }
    }

    private fun createTabInternal(rawUrl: String?, select: Boolean, load: Boolean): BrowserTab {
        check(tabs.size < MAX_TABS) { "RiftBrowser tab limit reached ($MAX_TABS)" }
        val id = "tab-${UUID.randomUUID().toString().take(12)}"
        val engine = createEngine(id)
        val tab = BrowserTab(id, engine)
        tabs[id] = tab
        engine.view.visibility = View.GONE
        engine.view.isClickable = false
        surfaceHost.addView(
            engine.view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        if (select) selectTabInternal(id) else engine.onPause()
        if (load) engine.loadUrl(normalizeStartUrl(rawUrl).ifBlank { NEW_TAB_URL })
        return tab
    }

    private fun createEngine(tabId: String): RiftBrowserEngine = RiftBrowserAndroidWebViewEngine(
        activity = activity,
        launchFileChooser = ::launchFileChooser,
        rendererGone = { lastUrl -> recoverRenderer(tabId, lastUrl) }
    )

    private fun recoverRenderer(tabId: String, lastUrl: String) {
        if (destroyed) return
        surfaceHost.post {
            if (destroyed) return@post
            val tab = tabs[tabId] ?: return@post
            if (tab.recovering || tab.rendererRecoveries >= 1) return@post
            tab.recovering = true
            tab.rendererRecoveries += 1
            val old = tab.engine
            runCatching { surfaceHost.removeView(old.view) }
            runCatching { old.destroy() }
            val replacement = createEngine(tabId)
            tab.engine = replacement
            replacement.view.visibility = View.GONE
            replacement.view.isClickable = false
            surfaceHost.addView(
                replacement.view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            tab.recovering = false
            val target = lastUrl.takeIf { it.startsWith("https://", ignoreCase = true) }
            if (target != null) runCatching { replacement.loadUrl(target) }
            refreshEngineVisibility()
            syncRendererLifecycle()
        }
    }

    private fun launchFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams?
    ): Boolean {
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = callback
        return try {
            val needsUnfilteredPicker = params?.acceptTypes?.any { accept ->
                accept.split(',').any { type ->
                    val value = type.trim()
                    value == "*/*" || value.equals(".rift", ignoreCase = true)
                }
            } == true
            val intent = if (needsUnfilteredPicker) {
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                params?.createIntent() ?: Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
            }
            if (params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            activity.startActivityForResult(intent, FILE_CHOOSER_REQUEST)
            true
        } catch (_: Exception) {
            fileChooserCallback?.onReceiveValue(null)
            fileChooserCallback = null
            false
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != FILE_CHOOSER_REQUEST) return false
        val callback = fileChooserCallback ?: return true
        fileChooserCallback = null
        callback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
        return true
    }

    private fun selectTabInternal(id: String) {
        if (activeTabId == id) return
        activeTabId?.let { previousId -> tabs[previousId]?.engine?.let { runCatching { it.onPause() } } }
        activeTabId = id
        refreshEngineVisibility()
        syncRendererLifecycle()
    }

    private fun activeTab(): BrowserTab = tabs[activeTabId]
        ?: error("RiftBrowser has no active tab")

    private fun activeEngine(): RiftBrowserEngine = activeTab().engine

    private fun ensureAlive() {
        check(!destroyed) { "RiftBrowser window has been destroyed" }
    }

    private fun refreshEngineVisibility() {
        val showActive = !destroyed
        tabs.values.forEach { tab ->
            val active = tab.id == activeTabId && showActive
            tab.engine.view.visibility = if (active) View.VISIBLE else View.GONE
            tab.engine.view.isClickable = active && surfaceHost.isShown
            tab.engine.view.isFocusable = active
            tab.engine.view.isFocusableInTouchMode = active
            if (!active) tab.engine.view.clearFocus()
        }
        if (!destroyed) activeEngine().view.bringToFront()
    }

    private fun pauseAllRenderers() {
        tabs.values.forEach { tab ->
            tab.engine.view.clearFocus()
            tab.engine.view.isClickable = false
            runCatching { tab.engine.onPause() }
        }
    }

    private fun syncRendererLifecycle() {
        if (destroyed || !resumed || surfaceHost.parent == null || !surfaceHost.isShown) {
            pauseAllRenderers()
            return
        }
        tabs.values.forEach { tab ->
            if (tab.id == activeTabId) runCatching { tab.engine.onResume() }
            else runCatching { tab.engine.onPause() }
        }
        refreshEngineVisibility()
    }

    private fun normalizeStartUrl(raw: String?): String {
        val value = raw.orEmpty().trim()
        if (value.isBlank()) return ""
        return when {
            value.startsWith("https://", ignoreCase = true) -> value
            value.startsWith("http://", ignoreCase = true) -> "https://${value.substringAfter("://")}"
            value.contains('.') && !value.contains(' ') -> "https://$value"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(value, "UTF-8")
        }
    }
}
