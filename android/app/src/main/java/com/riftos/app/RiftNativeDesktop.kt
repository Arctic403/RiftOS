package com.riftos.app

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Android-native RiftOS desktop/window authority.
 *
 * The shell WebView is retained only as a compatibility content canvas. Android owns launcher,
 * taskbar, window frames, bounds, focus, z-order, minimize/maximize/restore/close and insets.
 */
class RiftNativeDesktop(
    private val activity: Activity,
    host: FrameLayout,
    compatibilityView: WebView,
    private val stateSink: (JSONObject) -> Unit,
    private val appOpenSink: (String) -> Unit
) {
    companion object {
        private const val BG = 0xff080d12.toInt()
        private const val PANEL = 0xff101922.toInt()
        private const val PANEL_ACTIVE = 0xff173227.toInt()
        private const val BORDER = 0xff324352.toInt()
        private const val ACCENT = 0xff79f6c8.toInt()
        private const val TEXT = 0xffedf5fa.toInt()
        private const val MUTED = 0xff8fa0b1.toInt()
    }

    data class LauncherApp(val id: String, val name: String, val icon: String)

    private data class WindowRecord(
        val id: String,
        var title: String,
        var kicker: String,
        val titleBar: FrameLayout,
        val titleText: TextView,
        val minimizeButton: Button,
        val maximizeButton: Button,
        val closeButton: Button,
        val leftBorder: View,
        val rightBorder: View,
        val bottomBorder: View,
        val resizeHandle: View,
        var bounds: Rect,
        var restoreBounds: Rect? = null,
        var minimized: Boolean = false,
        var maximized: Boolean = false,
        var z: Long = 0L
    )

    val contentHost = FrameLayout(activity).apply {
        clipChildren = true
        clipToPadding = true
        setBackgroundColor(Color.TRANSPARENT)
    }

    private val chromeHost = FrameLayout(activity).apply {
        clipChildren = false
        clipToPadding = false
        setBackgroundColor(Color.TRANSPARENT)
    }
    private val wallpaper = View(activity)
    private val launcher = GridLayout(activity)
    private val startMenu = LinearLayout(activity)
    private val statusBar = LinearLayout(activity)
    private val statusTitle = TextView(activity)
    private val taskbar = LinearLayout(activity)
    private val startButton = Button(activity)
    private val taskStrip = LinearLayout(activity)
    private val clock = TextView(activity)
    private val showDesktopButton = Button(activity)
    private val handler = Handler(Looper.getMainLooper())
    private val windows = LinkedHashMap<String, WindowRecord>()
    private var launcherApps = defaultApps()
    private val taskbarPins = LinkedHashSet<String>()
    private var runtimeReady = false
    private var activeId: String? = null
    private var zCounter = 100L
    private var sequence = 0L
    private var pendingBoundsState = false
    private var pendingReason = "bounds"

    private val statusHeight = dp(34)
    private val taskbarHeight = dp(58)
    private val titleHeight = dp(42)
    private val borderWidth = dp(2).coerceAtLeast(1)
    private val resizeSize = dp(28)
    private val minWindowWidth = dp(300)
    private val minWindowHeight = dp(220)

    private val clockTick = object : Runnable {
        override fun run() {
            val now = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date())
            clock.text = now
            handler.postDelayed(this, 1_000L)
        }
    }

    init {
        host.setBackgroundColor(BG)
        compatibilityView.setBackgroundColor(Color.TRANSPARENT)
        contentHost.addView(
            compatibilityView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        host.addView(wallpaper, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        host.addView(contentHost, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        host.addView(chromeHost, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        buildStatusBar()
        buildLauncher()
        buildTaskbar()
        buildStartMenu()
        setWallpaper("")
        updateLauncherViews()
        setRuntimeReady(false)
        handler.post(clockTick)
        host.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> relayoutForHostChange() }
    }

    fun handle(method: String, args: JSONObject): JSONObject = when (method) {
        "desktop.window.bootstrap" -> {
            setRuntimeReady(true)
            publish("bootstrap")
        }
        "desktop.window.open" -> open(args)
        "desktop.window.focus" -> focus(args.optString("id"), "focus")
        "desktop.window.close" -> close(args.optString("id"), "close")
        "desktop.window.minimize" -> minimize(args.optString("id"), "minimize")
        "desktop.window.maximize" -> maximize(args.optString("id"), "maximize")
        "desktop.window.restore" -> restore(args.optString("id"), "restore")
        "desktop.window.title" -> setTitle(args)
        "desktop.window.showDesktop" -> showDesktop()
        "desktop.window.state" -> stateObject("state", sequence)
        "desktop.launcher.update" -> updateLauncher(args)
        "desktop.wallpaper.set" -> {
            setWallpaper(args.optString("value"))
            publish("wallpaper")
        }
        "desktop.layout.reset" -> resetLayout()
        else -> throw IllegalArgumentException("Unsupported native desktop method: $method")
    }

    fun handleBack(): Boolean {
        if (startMenu.visibility == View.VISIBLE) {
            startMenu.visibility = View.GONE
            return true
        }
        val id = activeId ?: return false
        close(id, "back")
        return true
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        windows.values.toList().forEach(::removeWindowViews)
        windows.clear()
        contentHost.removeAllViews()
        chromeHost.removeAllViews()
    }

    private fun buildStatusBar() {
        statusBar.orientation = LinearLayout.HORIZONTAL
        statusBar.gravity = Gravity.CENTER_VERTICAL
        statusBar.setPadding(dp(12), 0, dp(12), 0)
        statusBar.background = solid(PANEL, 0f)
        val brand = TextView(activity).apply {
            text = "RiftOS"
            setTextColor(ACCENT)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            contentDescription = "RiftOS"
        }
        statusTitle.apply {
            text = "Native desktop"
            setTextColor(MUTED)
            textSize = 11f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        statusBar.addView(brand, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        statusBar.addView(statusTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f))
        chromeHost.addView(statusBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, statusHeight).apply {
            gravity = Gravity.TOP
        })
    }

    private fun buildLauncher() {
        launcher.columnCount = 2
        launcher.rowCount = GridLayout.UNDEFINED
        launcher.setPadding(dp(14), dp(14), dp(14), dp(14))
        launcher.contentDescription = "RiftOS desktop launcher"
        chromeHost.addView(launcher, FrameLayout.LayoutParams(dp(330), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = statusHeight + dp(12)
            leftMargin = dp(8)
        })
    }

    private fun buildTaskbar() {
        taskbar.orientation = LinearLayout.HORIZONTAL
        taskbar.gravity = Gravity.CENTER_VERTICAL
        taskbar.setPadding(dp(6), dp(5), dp(6), dp(5))
        taskbar.background = solid(0xff0d151d.toInt(), 0f, BORDER, dp(1))

        configureButton(startButton, "⊞", "Start")
        startButton.setOnClickListener {
            if (!runtimeReady) return@setOnClickListener
            startMenu.visibility = if (startMenu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            raiseSystemChrome()
        }
        taskbar.addView(startButton, LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.MATCH_PARENT))

        taskStrip.orientation = LinearLayout.HORIZONTAL
        taskStrip.gravity = Gravity.CENTER_VERTICAL
        val scroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            contentDescription = "Open RiftOS windows"
            addView(taskStrip, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        taskbar.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        clock.apply {
            setTextColor(MUTED)
            textSize = 10f
            gravity = Gravity.CENTER
            contentDescription = "Clock"
            setPadding(dp(6), 0, dp(6), 0)
        }
        taskbar.addView(clock, LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.MATCH_PARENT))

        configureButton(showDesktopButton, "▯", "Show desktop")
        showDesktopButton.setOnClickListener { if (runtimeReady) showDesktop() }
        taskbar.addView(showDesktopButton, LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.MATCH_PARENT))

        chromeHost.addView(taskbar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, taskbarHeight).apply {
            gravity = Gravity.BOTTOM
        })
    }

    private fun buildStartMenu() {
        startMenu.orientation = LinearLayout.VERTICAL
        startMenu.setPadding(dp(8), dp(8), dp(8), dp(8))
        startMenu.background = solid(0xff101922.toInt(), dp(10).toFloat(), BORDER, dp(1))
        startMenu.elevation = dp(14).toFloat()
        startMenu.visibility = View.GONE
        startMenu.contentDescription = "Start menu"
        chromeHost.addView(startMenu, FrameLayout.LayoutParams(dp(270), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            leftMargin = dp(6)
            bottomMargin = taskbarHeight + dp(6)
        })
    }

    private fun updateLauncher(args: JSONObject): JSONObject {
        val array = args.optJSONArray("apps") ?: JSONArray()
        val next = ArrayList<LauncherApp>()
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index) ?: continue
            val id = row.optString("id").trim()
            if (id.isBlank()) continue
            val name = row.optString("name", id).trim().ifBlank { id }
            val icon = row.optString("icon", "□").trim().ifBlank { "□" }
            if (next.none { it.id == id }) next += LauncherApp(id, name.take(64), icon.take(4))
        }
        if (next.isNotEmpty()) launcherApps = next
        if (args.has("pins")) {
            taskbarPins.clear()
            val pins = args.optJSONArray("pins") ?: JSONArray()
            for (index in 0 until pins.length()) pins.optString(index).trim().takeIf { it.isNotEmpty() }?.let(taskbarPins::add)
        }
        updateLauncherViews()
        syncTaskbar()
        return publish("launcher")
    }

    private fun updateLauncherViews() {
        launcher.removeAllViews()
        startMenu.removeAllViews()
        for (app in launcherApps) {
            val tile = Button(activity).apply {
                text = "${app.icon}\n${app.name}"
                isAllCaps = false
                textSize = 10f
                setTextColor(TEXT)
                gravity = Gravity.CENTER
                contentDescription = app.name
                background = solid(0xff111d26.toInt(), dp(9).toFloat(), BORDER, dp(1))
                isEnabled = runtimeReady
                setOnClickListener { if (runtimeReady) openApp(app.id) }
            }
            launcher.addView(tile, GridLayout.LayoutParams().apply {
                width = dp(148)
                height = dp(78)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
            val row = Button(activity).apply {
                text = "${app.icon}   ${app.name}"
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                textSize = 11f
                setTextColor(TEXT)
                contentDescription = app.name
                background = ColorDrawable(Color.TRANSPARENT)
                isEnabled = runtimeReady
                setOnClickListener {
                    startMenu.visibility = View.GONE
                    if (runtimeReady) openApp(app.id)
                }
            }
            startMenu.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        }
    }

    private fun openApp(id: String) {
        appOpenSink(id)
    }

    private fun setRuntimeReady(ready: Boolean) {
        runtimeReady = ready
        startButton.isEnabled = ready
        showDesktopButton.isEnabled = ready
        updateLauncherViews()
    }

    private fun open(args: JSONObject): JSONObject {
        val id = args.optString("id").trim()
        require(id.isNotBlank()) { "desktop window id is required" }
        val title = args.optString("title", id).trim().ifBlank { id }.take(96)
        val kicker = args.optString("kicker", "RIFT APP").trim().take(96)
        val existing = windows[id]
        if (existing != null) {
            existing.title = title
            existing.kicker = kicker
            updateTitle(existing)
            existing.minimized = false
            focusInternal(existing)
            return publish("open")
        }
        val record = createWindow(id, title, kicker)
        args.optJSONObject("boundsCss")?.let { saved ->
            val leftCss = saved.optDouble("left", Double.NaN)
            val topCss = saved.optDouble("top", Double.NaN)
            val widthCss = saved.optDouble("width", Double.NaN)
            val heightCss = saved.optDouble("height", Double.NaN)
            if (leftCss.isFinite() && topCss.isFinite() && widthCss.isFinite() && heightCss.isFinite() && widthCss > 0.0 && heightCss > 0.0) {
                val scale = args.optDouble("dpr", activity.resources.displayMetrics.density.toDouble()).coerceIn(0.5, 8.0)
                val left = (leftCss * scale).roundToInt()
                val top = (topCss * scale).roundToInt()
                val width = (widthCss * scale).roundToInt()
                val height = (heightCss * scale).roundToInt()
                record.bounds = clampBounds(Rect(left, top, left + width, top + height))
                applyRecordLayout(record)
            }
        }
        windows[id] = record
        focusInternal(record)
        syncTaskbar()
        return publish("open")
    }

    private fun createWindow(id: String, title: String, kicker: String): WindowRecord {
        val titleBar = FrameLayout(activity).apply {
            isClickable = true
            isFocusable = true
            elevation = dp(8).toFloat()
        }
        val titleText = TextView(activity).apply {
            setTextColor(TEXT)
            textSize = 11f
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 2
            setPadding(dp(10), 0, dp(4), 0)
        }
        titleBar.addView(titleText, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            rightMargin = dp(138)
        })
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleBar.addView(actions, FrameLayout.LayoutParams(dp(138), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
        val min = windowButton("—", "Minimize $title")
        val max = windowButton("□", "Maximize $title")
        val close = windowButton("×", "Close $title")
        actions.addView(min, LinearLayout.LayoutParams(dp(46), ViewGroup.LayoutParams.MATCH_PARENT))
        actions.addView(max, LinearLayout.LayoutParams(dp(46), ViewGroup.LayoutParams.MATCH_PARENT))
        actions.addView(close, LinearLayout.LayoutParams(dp(46), ViewGroup.LayoutParams.MATCH_PARENT))

        val left = View(activity).apply { setBackgroundColor(BORDER) }
        val right = View(activity).apply { setBackgroundColor(BORDER) }
        val bottom = View(activity).apply { setBackgroundColor(BORDER) }
        val resize = View(activity).apply {
            setBackgroundColor(0xff547160.toInt())
            contentDescription = "Resize $title"
            isClickable = true
        }
        chromeHost.addView(titleBar)
        chromeHost.addView(left)
        chromeHost.addView(right)
        chromeHost.addView(bottom)
        chromeHost.addView(resize)

        val record = WindowRecord(
            id = id,
            title = title,
            kicker = kicker,
            titleBar = titleBar,
            titleText = titleText,
            minimizeButton = min,
            maximizeButton = max,
            closeButton = close,
            leftBorder = left,
            rightBorder = right,
            bottomBorder = bottom,
            resizeHandle = resize,
            bounds = defaultBounds()
        )
        updateTitle(record)
        min.setOnClickListener { minimize(id, "minimize") }
        max.setOnClickListener {
            if (record.maximized) restore(id, "restore") else maximize(id, "maximize")
        }
        close.setOnClickListener { close(id, "close") }
        wireDrag(record)
        wireResize(record)
        applyRecordLayout(record)
        return record
    }

    private fun focus(id: String, reason: String): JSONObject {
        val record = windows[id] ?: return stateObject(reason, sequence)
        record.minimized = false
        focusInternal(record)
        syncTaskbar()
        return publish(reason)
    }

    private fun focusInternal(record: WindowRecord) {
        activeId = record.id
        record.minimized = false
        record.z = ++zCounter
        windows.values.forEach { updateFocusStyle(it, it.id == record.id) }
        bringWindowChrome(record)
        statusTitle.text = record.title
        applyRecordLayout(record)
        raiseSystemChrome()
    }

    private fun close(id: String, reason: String): JSONObject {
        val record = windows.remove(id) ?: return stateObject(reason, sequence)
        removeWindowViews(record)
        if (activeId == id) {
            val next = windows.values.filter { !it.minimized }.maxByOrNull { it.z }
            activeId = null
            if (next != null) focusInternal(next) else statusTitle.text = "Native desktop"
        }
        syncTaskbar()
        return publish(reason)
    }

    private fun minimize(id: String, reason: String): JSONObject {
        val record = windows[id] ?: return stateObject(reason, sequence)
        record.minimized = true
        hideWindowChrome(record)
        if (activeId == id) {
            val next = windows.values.filter { it.id != id && !it.minimized }.maxByOrNull { it.z }
            activeId = null
            if (next != null) focusInternal(next) else statusTitle.text = "Native desktop"
        }
        syncTaskbar()
        return publish(reason)
    }

    private fun maximize(id: String, reason: String): JSONObject {
        val record = windows[id] ?: return stateObject(reason, sequence)
        if (!record.maximized) record.restoreBounds = Rect(record.bounds)
        record.maximized = true
        record.minimized = false
        record.bounds = workspaceBounds()
        record.maximizeButton.text = "❐"
        record.maximizeButton.contentDescription = "Restore ${record.title}"
        focusInternal(record)
        syncTaskbar()
        return publish(reason)
    }

    private fun restore(id: String, reason: String): JSONObject {
        val record = windows[id] ?: return stateObject(reason, sequence)
        record.minimized = false
        if (record.maximized) {
            record.maximized = false
            record.bounds = clampBounds(record.restoreBounds ?: defaultBounds())
            record.restoreBounds = null
        }
        record.maximizeButton.text = "□"
        record.maximizeButton.contentDescription = "Maximize ${record.title}"
        focusInternal(record)
        syncTaskbar()
        return publish(reason)
    }

    private fun setTitle(args: JSONObject): JSONObject {
        val id = args.optString("id").trim()
        val record = windows[id] ?: return stateObject("title", sequence)
        record.title = args.optString("title", record.title).trim().ifBlank { record.title }.take(96)
        if (args.has("kicker")) record.kicker = args.optString("kicker").trim().take(96)
        updateTitle(record)
        syncTaskbar()
        if (activeId == id) statusTitle.text = record.title
        return publish("title")
    }

    private fun updateTitle(record: WindowRecord) {
        record.titleText.text = if (record.kicker.isBlank()) record.title else "${record.kicker}\n${record.title}"
        record.titleBar.contentDescription = "${record.title} window"
        record.minimizeButton.contentDescription = "Minimize ${record.title}"
        record.maximizeButton.contentDescription = if (record.maximized) "Restore ${record.title}" else "Maximize ${record.title}"
        record.closeButton.contentDescription = "Close ${record.title}"
        record.resizeHandle.contentDescription = "Resize ${record.title}"
    }

    private fun showDesktop(): JSONObject {
        windows.values.forEach {
            it.minimized = true
            hideWindowChrome(it)
        }
        activeId = null
        statusTitle.text = "Native desktop"
        startMenu.visibility = View.GONE
        syncTaskbar()
        return publish("show-desktop")
    }

    private fun resetLayout(): JSONObject {
        var offset = 0
        windows.values.sortedBy { it.z }.forEach { record ->
            record.maximized = false
            record.minimized = false
            record.restoreBounds = null
            record.bounds = defaultBounds(offset++)
            record.maximizeButton.text = "□"
            applyRecordLayout(record)
        }
        val next = windows.values.maxByOrNull { it.z }
        if (next != null) focusInternal(next)
        syncTaskbar()
        return publish("layout-reset")
    }

    private fun syncTaskbar() {
        launcher.visibility = if (windows.values.any { !it.minimized }) View.GONE else View.VISIBLE
        taskStrip.removeAllViews()
        val represented = LinkedHashSet<String>()
        for (appId in taskbarPins) {
            val app = launcherApps.firstOrNull { it.id == appId } ?: continue
            val record = windows[appId] ?: windows["riftrt:$appId"]
            addTaskButton(appId, app.name, record)
            represented += appId
            if (record != null) represented += record.id
        }
        for (record in windows.values.sortedBy { it.z }) {
            if (represented.contains(record.id)) continue
            addTaskButton(record.id, record.title, record)
            represented += record.id
        }
        raiseSystemChrome()
    }

    private fun addTaskButton(appId: String, label: String, record: WindowRecord?) {
        val active = record != null && record.id == activeId && !record.minimized
        val button = Button(activity).apply {
            text = label.take(24)
            isAllCaps = false
            textSize = 9f
            setTextColor(if (active) ACCENT else TEXT)
            contentDescription = label
            background = solid(if (active) PANEL_ACTIVE else PANEL, dp(7).toFloat(), BORDER, dp(1))
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener {
                val live = windows[appId] ?: windows["riftrt:$appId"] ?: record?.let { windows[it.id] }
                if (live == null) openApp(appId)
                else if (live.id == activeId && !live.minimized) minimize(live.id, "taskbar-minimize")
                else focus(live.id, "taskbar-focus")
            }
        }
        taskStrip.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            setMargins(dp(3), 0, dp(3), 0)
        })
    }

    private fun wireDrag(record: WindowRecord) {
        var startX = 0f
        var startY = 0f
        var startBounds = Rect()
        record.titleBar.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    focus(record.id, "focus")
                    startX = event.rawX
                    startY = event.rawY
                    startBounds = Rect(record.bounds)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!record.maximized) {
                        val dx = (event.rawX - startX).roundToInt()
                        val dy = (event.rawY - startY).roundToInt()
                        record.bounds = clampBounds(Rect(startBounds).apply { offset(dx, dy) })
                        applyRecordLayout(record)
                        scheduleBoundsState("move")
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    publish("bounds")
                    true
                }
                else -> false
            }
        }
    }

    private fun wireResize(record: WindowRecord) {
        var startX = 0f
        var startY = 0f
        var startBounds = Rect()
        record.resizeHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    focus(record.id, "focus")
                    startX = event.rawX
                    startY = event.rawY
                    startBounds = Rect(record.bounds)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!record.maximized) {
                        val dx = (event.rawX - startX).roundToInt()
                        val dy = (event.rawY - startY).roundToInt()
                        val candidate = Rect(startBounds.left, startBounds.top, startBounds.right + dx, startBounds.bottom + dy)
                        record.bounds = clampBounds(candidate)
                        applyRecordLayout(record)
                        scheduleBoundsState("resize")
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    publish("bounds")
                    true
                }
                else -> false
            }
        }
    }

    private fun applyRecordLayout(record: WindowRecord) {
        if (record.minimized) {
            hideWindowChrome(record)
            return
        }
        val bounds = if (record.maximized) workspaceBounds() else clampBounds(record.bounds)
        record.bounds = Rect(bounds)
        val width = bounds.width().coerceAtLeast(1)
        val height = bounds.height().coerceAtLeast(1)
        place(record.titleBar, bounds.left, bounds.top, width, titleHeight)
        place(record.leftBorder, bounds.left, bounds.top + titleHeight, borderWidth, (height - titleHeight).coerceAtLeast(1))
        place(record.rightBorder, bounds.right - borderWidth, bounds.top + titleHeight, borderWidth, (height - titleHeight).coerceAtLeast(1))
        place(record.bottomBorder, bounds.left, bounds.bottom - borderWidth, width, borderWidth)
        place(record.resizeHandle, bounds.right - resizeSize, bounds.bottom - resizeSize, resizeSize, resizeSize)
        val visible = View.VISIBLE
        record.titleBar.visibility = visible
        record.leftBorder.visibility = visible
        record.rightBorder.visibility = visible
        record.bottomBorder.visibility = visible
        record.resizeHandle.visibility = if (record.maximized) View.GONE else visible
        updateFocusStyle(record, record.id == activeId)
    }

    private fun hideWindowChrome(record: WindowRecord) {
        record.titleBar.visibility = View.GONE
        record.leftBorder.visibility = View.GONE
        record.rightBorder.visibility = View.GONE
        record.bottomBorder.visibility = View.GONE
        record.resizeHandle.visibility = View.GONE
    }

    private fun removeWindowViews(record: WindowRecord) {
        runCatching { chromeHost.removeView(record.titleBar) }
        runCatching { chromeHost.removeView(record.leftBorder) }
        runCatching { chromeHost.removeView(record.rightBorder) }
        runCatching { chromeHost.removeView(record.bottomBorder) }
        runCatching { chromeHost.removeView(record.resizeHandle) }
    }

    private fun bringWindowChrome(record: WindowRecord) {
        record.leftBorder.bringToFront()
        record.rightBorder.bringToFront()
        record.bottomBorder.bringToFront()
        record.resizeHandle.bringToFront()
        record.titleBar.bringToFront()
    }

    private fun raiseSystemChrome() {
        statusBar.bringToFront()
        taskbar.bringToFront()
        if (startMenu.visibility == View.VISIBLE) startMenu.bringToFront()
    }

    private fun updateFocusStyle(record: WindowRecord, focused: Boolean) {
        record.titleBar.background = solid(if (focused) PANEL_ACTIVE else PANEL, dp(7).toFloat(), if (focused) ACCENT else BORDER, dp(1))
    }

    private fun relayoutForHostChange() {
        windows.values.forEach { record ->
            record.bounds = if (record.maximized) workspaceBounds() else clampBounds(record.bounds)
            applyRecordLayout(record)
        }
        scheduleBoundsState("host-layout")
    }

    private fun workspaceBounds(): Rect {
        val metrics = activity.resources.displayMetrics
        val width = chromeHost.width.takeIf { it > 0 } ?: metrics.widthPixels
        val height = chromeHost.height.takeIf { it > 0 } ?: metrics.heightPixels
        val bottom = (height - taskbarHeight).coerceAtLeast(statusHeight + 1)
        return Rect(0, statusHeight, width.coerceAtLeast(1), bottom)
    }

    private fun defaultBounds(index: Int = windows.size): Rect {
        val work = workspaceBounds()
        val availableW = work.width().coerceAtLeast(1)
        val availableH = work.height().coerceAtLeast(1)
        val minW = minWindowWidth.coerceAtMost(availableW)
        val minH = minWindowHeight.coerceAtMost(availableH)
        val width = (availableW * 0.78f).roundToInt().coerceIn(minW.coerceAtLeast(1), availableW)
        val height = (availableH * 0.76f).roundToInt().coerceIn(minH.coerceAtLeast(1), availableH)
        val offset = dp(18) * (index % 6)
        val left = (work.left + dp(22) + offset).coerceIn(work.left, (work.right - width).coerceAtLeast(work.left))
        val top = (work.top + dp(18) + offset).coerceIn(work.top, (work.bottom - height).coerceAtLeast(work.top))
        return Rect(left, top, left + width, top + height)
    }

    private fun clampBounds(input: Rect): Rect {
        val work = workspaceBounds()
        val availableW = work.width().coerceAtLeast(1)
        val availableH = work.height().coerceAtLeast(1)
        val minW = minWindowWidth.coerceAtMost(availableW).coerceAtLeast(1)
        val minH = minWindowHeight.coerceAtMost(availableH).coerceAtLeast(1)
        val width = input.width().coerceIn(minW, availableW)
        val height = input.height().coerceIn(minH, availableH)
        val left = input.left.coerceIn(work.left, (work.right - width).coerceAtLeast(work.left))
        val top = input.top.coerceIn(work.top, (work.bottom - height).coerceAtLeast(work.top))
        return Rect(left, top, left + width, top + height)
    }

    private fun contentBounds(record: WindowRecord): Rect {
        val frame = record.bounds
        val top = (frame.top + titleHeight).coerceAtMost(frame.bottom)
        return Rect(
            (frame.left + borderWidth).coerceAtMost(frame.right),
            top,
            (frame.right - borderWidth).coerceAtLeast(frame.left + borderWidth),
            (frame.bottom - borderWidth).coerceAtLeast(top)
        )
    }

    private fun scheduleBoundsState(reason: String) {
        pendingReason = reason
        if (pendingBoundsState) return
        pendingBoundsState = true
        handler.postDelayed({
            pendingBoundsState = false
            publish(pendingReason)
        }, 16L)
    }

    private fun publish(reason: String): JSONObject {
        sequence++
        val state = stateObject(reason, sequence)
        stateSink(state)
        return state
    }

    private fun stateObject(reason: String, sequenceValue: Long): JSONObject {
        val list = JSONArray()
        windows.values.sortedBy { it.z }.forEach { record ->
            val frame = record.bounds
            val content = contentBounds(record)
            list.put(JSONObject()
                .put("id", record.id)
                .put("title", record.title)
                .put("kicker", record.kicker)
                .put("minimized", record.minimized)
                .put("maximized", record.maximized)
                .put("focused", activeId == record.id && !record.minimized)
                .put("z", record.z)
                .put("framePx", rectJson(frame))
                .put("contentPx", rectJson(content)))
        }
        val work = workspaceBounds()
        return JSONObject()
            .put("native", true)
            .put("sequence", sequenceValue)
            .put("reason", reason)
            .put("activeId", activeId ?: JSONObject.NULL)
            .put("desktopVisible", activeId == null)
            .put("density", activity.resources.displayMetrics.density)
            .put("workspacePx", rectJson(work))
            .put("windows", list)
    }

    private fun rectJson(rect: Rect): JSONObject = JSONObject()
        .put("left", rect.left)
        .put("top", rect.top)
        .put("right", rect.right)
        .put("bottom", rect.bottom)
        .put("width", rect.width())
        .put("height", rect.height())

    private fun setWallpaper(raw: String) {
        val value = raw.trim()
        if (value.isBlank()) {
            wallpaper.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xff0b1118.toInt(), 0xff071019.toInt(), 0xff0d1715.toInt()))
            return
        }
        if (value.startsWith("data:image/", ignoreCase = true) && value.contains(";base64,")) {
            val base64 = value.substringAfter(";base64,")
            val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull()
            val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (bitmap != null) {
                wallpaper.background = BitmapDrawable(activity.resources, bitmap).apply { gravity = Gravity.FILL }
                return
            }
        }
        val color = runCatching { Color.parseColor(value) }.getOrNull()
        wallpaper.background = ColorDrawable(color ?: BG)
    }

    private fun place(view: View, left: Int, top: Int, width: Int, height: Int) {
        val params = (view.layoutParams as? FrameLayout.LayoutParams) ?: FrameLayout.LayoutParams(width, height)
        params.width = width.coerceAtLeast(1)
        params.height = height.coerceAtLeast(1)
        params.leftMargin = left
        params.topMargin = top
        params.gravity = Gravity.TOP or Gravity.START
        view.layoutParams = params
    }

    private fun configureButton(button: Button, label: String, description: String) {
        button.text = label
        button.isAllCaps = false
        button.textSize = 12f
        button.setTextColor(TEXT)
        button.contentDescription = description
        button.background = solid(PANEL, dp(7).toFloat(), BORDER, dp(1))
        button.setPadding(dp(6), 0, dp(6), 0)
    }

    private fun windowButton(label: String, description: String): Button = Button(activity).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        setTextColor(TEXT)
        contentDescription = description
        background = ColorDrawable(Color.TRANSPARENT)
        minWidth = 0
        minimumWidth = 0
        setPadding(0, 0, 0, 0)
    }

    private fun solid(color: Int, radius: Float, strokeColor: Int? = null, strokeWidth: Int = 0): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radius
        if (strokeColor != null && strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).roundToInt()

    private fun defaultApps(): List<LauncherApp> = listOf(
        LauncherApp("files", "Files", "▣"),
        LauncherApp("workspace-live", "Workspace Records", "◈"),
        LauncherApp("terminal", "RiftShell", ">_"),
        LauncherApp("browser", "RiftBrowser", "◎"),
        LauncherApp("editor", "Editor", "{}"),
        LauncherApp("tasks", "Tasks", "≡"),
        LauncherApp("settings", "Settings", "⚙")
    )
}
