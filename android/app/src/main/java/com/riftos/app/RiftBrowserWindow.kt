package com.riftos.app

import android.app.Activity
import android.net.Uri
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID
import kotlin.math.roundToInt

/**
 * RiftOS-owned browser window surface.
 *
 * One desktop browser window owns a bounded set of renderer tabs. Every tab keeps its own
 * RiftBrowserEngine/history/session state, but only the selected tab is attached as VISIBLE.
 * Hiding/minimizing RiftBrowser removes every native renderer from layout.
 */
class RiftBrowserWindow(
    private val activity: Activity,
    private val host: FrameLayout,
    private val launchFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams?) -> Boolean,
    private val stateSink: (JSONObject) -> Unit
) {
    companion object {
        private const val MAX_TABS = 8
        private const val DEFAULT_URL = "https://chatgpt.com"
        private const val NEW_TAB_URL = "https://www.google.com"
    }

    private data class BrowserTab(
        val id: String,
        val engine: RiftBrowserEngine
    )

    private val surfaceHost = FrameLayout(activity).apply {
        visibility = View.GONE
        isClickable = false
        isFocusable = false
        clipChildren = true
        clipToPadding = true
        setBackgroundColor(0xff0a0d12.toInt())
    }

    private val tabs = LinkedHashMap<String, BrowserTab>()
    private var activeTabId: String? = null
    private var requestedVisible = false
    private var hasBounds = false
    private var destroyed = false
    private var resumed = true

    init {
        host.addView(surfaceHost, FrameLayout.LayoutParams(1, 1))
        createTabInternal(null, select = true, load = false)
        hideSurface()
    }

    fun open(rawUrl: String?): JSONObject {
        ensureAlive()
        val engine = activeEngine()
        val current = engine.currentUrl()
        val target = normalizeStartUrl(rawUrl).ifBlank {
            current.takeIf { it.isNotBlank() && it != "about:blank" } ?: DEFAULT_URL
        }
        requestedVisible = true
        if (current.isBlank() || current == "about:blank") {
            engine.loadUrl(target)
        } else if (rawUrl?.isNotBlank() == true && normalizeStartUrl(rawUrl) != current) {
            engine.loadUrl(target)
        }
        applyVisibility()
        emitState()
        return state()
    }

    fun navigate(rawUrl: String?): JSONObject {
        ensureAlive()
        activeEngine().loadUrl(normalizeStartUrl(rawUrl).ifBlank { DEFAULT_URL })
        emitState()
        return state()
    }

    fun back(): JSONObject {
        ensureAlive()
        activeEngine().let { if (it.canGoBack()) it.goBack() }
        emitState()
        return state()
    }

    fun forward(): JSONObject {
        ensureAlive()
        activeEngine().let { if (it.canGoForward()) it.goForward() }
        emitState()
        return state()
    }

    fun reload(): JSONObject {
        ensureAlive()
        activeEngine().reload()
        emitState()
        return state()
    }

    fun setDesktopMode(enabled: Boolean): JSONObject {
        ensureAlive()
        activeEngine().setDesktopMode(enabled)
        emitState()
        return state()
    }

    fun inspect(request: JSONObject, callback: (JSONObject?, Throwable?) -> Unit) {
        ensureAlive()
        activeEngine().inspect(request, callback)
    }

    fun newTab(rawUrl: String?): JSONObject {
        ensureAlive()
        createTabInternal(rawUrl, select = true, load = true)
        applyVisibility()
        emitState()
        return state()
    }

    fun selectTab(tabId: String?): JSONObject {
        ensureAlive()
        val id = tabId.orEmpty()
        require(tabs.containsKey(id)) { "Unknown RiftBrowser tab: $id" }
        selectTabInternal(id)
        applyVisibility()
        emitState()
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
        applyVisibility()
        emitState()
        return state()
    }

    fun setVisible(visible: Boolean): JSONObject {
        ensureAlive()
        requestedVisible = visible
        applyVisibility()
        emitState()
        return state()
    }

    fun setBounds(args: JSONObject): JSONObject {
        ensureAlive()
        val dpr = args.optDouble("dpr", 1.0).coerceIn(0.5, 8.0)
        val left = (args.optDouble("left", 0.0) * dpr).roundToInt().coerceAtLeast(0)
        val top = (args.optDouble("top", 0.0) * dpr).roundToInt().coerceAtLeast(0)
        val width = (args.optDouble("width", 1.0) * dpr).roundToInt().coerceAtLeast(1)
        val height = (args.optDouble("height", 1.0) * dpr).roundToInt().coerceAtLeast(1)
        val hostWidth = host.width.takeIf { it > 0 } ?: Int.MAX_VALUE
        val hostHeight = host.height.takeIf { it > 0 } ?: Int.MAX_VALUE
        val safeLeft = left.coerceAtMost((hostWidth - 1).coerceAtLeast(0))
        val safeTop = top.coerceAtMost((hostHeight - 1).coerceAtLeast(0))
        val safeWidth = width.coerceAtMost((hostWidth - safeLeft).coerceAtLeast(1))
        val safeHeight = height.coerceAtMost((hostHeight - safeTop).coerceAtLeast(1))
        val params = (surfaceHost.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(safeWidth, safeHeight)
        params.width = safeWidth
        params.height = safeHeight
        params.leftMargin = safeLeft
        params.topMargin = safeTop
        surfaceHost.layoutParams = params
        hasBounds = true
        applyVisibility()
        emitState()
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
        state.put("visible", !destroyed && requestedVisible && hasBounds && surfaceHost.visibility == View.VISIBLE)
        state.put("surface", "rift-window-owned")
        state.put("activeTabId", active.id)
        state.put("tabCount", tabs.size)
        state.put("maxTabs", MAX_TABS)
        state.put("tabs", tabArray)
        return state
    }

    fun close(): Boolean {
        if (destroyed) return true
        requestedVisible = false
        hideSurface()
        emitState()
        return true
    }

    fun onResume() {
        if (destroyed) return
        resumed = true
        if (requestedVisible && hasBounds) activeEngine().onResume()
        tabs.values.filter { it.id != activeTabId }.forEach { runCatching { it.engine.onPause() } }
        applyVisibility()
    }

    fun onPause() {
        if (destroyed) return
        resumed = false
        tabs.values.forEach { runCatching { it.engine.onPause() } }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        requestedVisible = false
        hideSurface()
        val existing = tabs.values.toList()
        tabs.clear()
        activeTabId = null
        existing.forEach { tab ->
            runCatching { surfaceHost.removeView(tab.engine.view) }
            runCatching { tab.engine.destroy() }
        }
        runCatching { host.removeView(surfaceHost) }
        runCatching { surfaceHost.removeAllViews() }
    }

    private fun createTabInternal(rawUrl: String?, select: Boolean, load: Boolean): BrowserTab {
        check(tabs.size < MAX_TABS) { "RiftBrowser tab limit reached ($MAX_TABS)" }
        val id = "tab-${UUID.randomUUID().toString().take(12)}"
        val engine = AndroidWebViewBrowserEngine(
            activity = activity,
            launchFileChooser = launchFileChooser,
            stateChanged = { onEngineStateChanged(id) }
        )
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

    private fun selectTabInternal(id: String) {
        if (activeTabId == id) return
        activeTabId?.let { previousId -> tabs[previousId]?.engine?.let { runCatching { it.onPause() } } }
        activeTabId = id
        if (resumed && requestedVisible && hasBounds) tabs[id]?.engine?.onResume()
        refreshEngineVisibility()
    }

    private fun activeTab(): BrowserTab = tabs[activeTabId]
        ?: error("RiftBrowser has no active tab")

    private fun activeEngine(): RiftBrowserEngine = activeTab().engine

    private fun onEngineStateChanged(tabId: String) {
        if (destroyed || !tabs.containsKey(tabId)) return
        emitState()
    }

    private fun ensureAlive() {
        check(!destroyed) { "RiftBrowser window has been destroyed" }
    }

    private fun applyVisibility() {
        if (requestedVisible && hasBounds) showSurface() else hideSurface()
    }

    private fun refreshEngineVisibility() {
        val showActive = requestedVisible && hasBounds && !destroyed
        tabs.values.forEach { tab ->
            val active = tab.id == activeTabId && showActive
            tab.engine.view.visibility = if (active) View.VISIBLE else View.GONE
            tab.engine.view.isClickable = active
            tab.engine.view.isFocusable = active
            tab.engine.view.isFocusableInTouchMode = active
            if (!active) tab.engine.view.clearFocus()
        }
    }

    private fun showSurface() {
        refreshEngineVisibility()
        surfaceHost.visibility = View.VISIBLE
        activeEngine().view.bringToFront()
        surfaceHost.bringToFront()
        if (resumed) activeEngine().onResume()
    }

    private fun hideSurface() {
        tabs.values.forEach { tab ->
            tab.engine.view.clearFocus()
            tab.engine.view.isClickable = false
            tab.engine.view.visibility = View.GONE
            runCatching { tab.engine.onPause() }
        }
        surfaceHost.isClickable = false
        surfaceHost.visibility = View.GONE
    }

    private fun emitState() {
        if (destroyed) return
        stateSink(state())
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
