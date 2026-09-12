package com.riftos.app

import android.app.Activity
import android.net.Uri
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.roundToInt

/**
 * RiftOS-owned browser window surface.
 *
 * The renderer is a child of this surface and is never parked full-screen behind the shell.
 * Hiding/minimizing RiftBrowser removes the native surface from layout while keeping the engine alive.
 */
class RiftBrowserWindow(
    private val activity: Activity,
    private val host: FrameLayout,
    private val launchFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams?) -> Boolean,
    private val stateSink: (JSONObject) -> Unit
) {
    private val surfaceHost = FrameLayout(activity).apply {
        visibility = View.GONE
        isClickable = false
        isFocusable = false
        clipChildren = true
        clipToPadding = true
        setBackgroundColor(0xff0a0d12.toInt())
    }

    private var requestedVisible = false
    private var hasBounds = false
    private var destroyed = false

    private val engine: RiftBrowserEngine = AndroidWebViewBrowserEngine(
        activity = activity,
        launchFileChooser = launchFileChooser,
        stateChanged = ::emitState
    )

    init {
        surfaceHost.addView(
            engine.view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        host.addView(surfaceHost, FrameLayout.LayoutParams(1, 1))
        hideSurface()
    }

    fun open(rawUrl: String?): JSONObject {
        ensureAlive()
        val current = engine.currentUrl()
        val target = normalizeStartUrl(rawUrl).ifBlank {
            current.takeIf { it.isNotBlank() && it != "about:blank" } ?: "https://chatgpt.com"
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
        engine.loadUrl(normalizeStartUrl(rawUrl).ifBlank { "https://chatgpt.com" })
        emitState()
        return state()
    }

    fun back(): JSONObject {
        ensureAlive()
        if (engine.canGoBack()) engine.goBack()
        emitState()
        return state()
    }

    fun forward(): JSONObject {
        ensureAlive()
        if (engine.canGoForward()) engine.goForward()
        emitState()
        return state()
    }

    fun reload(): JSONObject {
        ensureAlive()
        engine.reload()
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
        val state = engine.state()
        state.put("open", !destroyed)
        state.put("visible", !destroyed && requestedVisible && hasBounds && surfaceHost.visibility == View.VISIBLE)
        state.put("surface", "rift-window-owned")
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
        if (!destroyed) engine.onResume()
    }

    fun onPause() {
        if (!destroyed) engine.onPause()
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        requestedVisible = false
        hideSurface()
        runCatching { host.removeView(surfaceHost) }
        runCatching { surfaceHost.removeAllViews() }
        runCatching { engine.destroy() }
    }

    private fun ensureAlive() {
        check(!destroyed) { "RiftBrowser window has been destroyed" }
    }

    private fun applyVisibility() {
        if (requestedVisible && hasBounds) showSurface() else hideSurface()
    }

    private fun showSurface() {
        engine.view.visibility = View.VISIBLE
        engine.view.isClickable = true
        engine.view.isFocusable = true
        engine.view.isFocusableInTouchMode = true
        surfaceHost.visibility = View.VISIBLE
        surfaceHost.bringToFront()
    }

    private fun hideSurface() {
        // Critical invariant: a hidden RiftBrowser renderer is never left as a full-host VISIBLE sibling.
        engine.view.clearFocus()
        engine.view.isClickable = false
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
