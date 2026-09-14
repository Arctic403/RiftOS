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
    private val appOpenSink: (String) -> Unit,
    private val windowClosedSink: (String) -> Unit
) {
    companion object {
        private const val BG = 0xff07141d.toInt()
        private const val PANEL = 0xe80a1118.toInt()
        private const val WINDOW_BAR = 0xe8101a22.toInt()
        private const val PANEL_ACTIVE = 0x13ffffff
        private const val BORDER = 0x26ffffff
        private const val FOCUS_BORDER = 0x4a78f6c7
        private const val ACCENT = 0xff78f6c7.toInt()
        private const val TEXT = 0xffe7eef5.toInt()
        private const val MUTED = 0xff9aa8b5.toInt()
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
    private val launcherScroll = android.widget.ScrollView(activity)
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
    private var launcherLayoutProfile = ""

    private val statusHeight = 0
    private val taskbarHeight = dp(48)
    private val titleHeight = dp(38)
    private val borderWidth = dp(1).coerceAtLeast(1)
    private val resizeSize = dp(18)
    private val minWindowWidth = dp(300)
    private val minWindowHeight = dp(220)

    private val clockTick = object : Runnable {
        override fun run() {
            val now = java.util.Date()
            val time = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(now)
            val date = java.text.SimpleDateFormat("M/d/yy", java.util.Locale.getDefault()).format(now)
            clock.text = "$time\n$date"
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
        // The permanent RiftDesktop shell intentionally has no top status strip.
        statusBar.visibility = View.GONE
    }

    private fun buildLauncher() {
        launcher.columnCount = desiredLauncherColumns()
        launcher.rowCount = GridLayout.UNDEFINED
        launcher.setPadding(dp(6), dp(6), dp(6), dp(6))
        launcher.setBackgroundColor(Color.TRANSPARENT)
        launcher.contentDescription = "RiftOS desktop launcher"
        launcherScroll.apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            contentDescription = "RiftOS desktop apps"
            addView(launcher, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        chromeHost.addView(launcherScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.TOP or Gravity.START
            bottomMargin = taskbarHeight
        })
    }

    private fun buildTaskbar() {
        taskbar.orientation = LinearLayout.HORIZONTAL
        taskbar.gravity = Gravity.CENTER_VERTICAL
        taskbar.setPadding(dp(6), dp(4), dp(6), dp(4))
        taskbar.background = solid(PANEL, 0f, 0x1cffffff, dp(1))
        taskbar.elevation = dp(18).toFloat()

        configureButton(startButton, "⊞", "Start")
        startButton.textSize = 22f
        startButton.setOnClickListener {
            if (!runtimeReady) return@setOnClickListener
            startMenu.visibility = if (startMenu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            raiseSystemChrome()
        }
        taskbar.addView(startButton, LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.MATCH_PARENT))

        taskStrip.orientation = LinearLayout.HORIZONTAL
        taskStrip.gravity = Gravity.CENTER_VERTICAL
        val scroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            isHorizontalFadingEdgeEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            contentDescription = "Pinned apps and open windows; scroll horizontally for more"
            addView(taskStrip, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        taskbar.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        clock.apply {
            setTextColor(MUTED)
            textSize = 9f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setIncludeFontPadding(false)
            contentDescription = "Clock"
            setPadding(dp(4), 0, dp(4), 0)
        }
        taskbar.addView(clock, LinearLayout.LayoutParams(dp(68), ViewGroup.LayoutParams.MATCH_PARENT))

        configureButton(showDesktopButton, "", "Show desktop")
        showDesktopButton.background = pressable(Color.TRANSPARENT, 0x13ffffff, 0, 0x33ffffff)
        showDesktopButton.setOnClickListener { if (runtimeReady) showDesktop() }
        taskbar.addView(showDesktopButton, LinearLayout.LayoutParams(dp(8), ViewGroup.LayoutParams.MATCH_PARENT))

        chromeHost.addView(taskbar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, taskbarHeight).apply {
            gravity = Gravity.BOTTOM
        })
    }

    private fun buildStartMenu() {
        startMenu.orientation = LinearLayout.VERTICAL
        startMenu.setPadding(dp(18), dp(14), dp(18), dp(18))
        startMenu.background = solid(0xed0c1822.toInt(), dp(10).toFloat(), 0x22ffffff, dp(1))
        startMenu.elevation = dp(24).toFloat()
        startMenu.visibility = View.GONE
        startMenu.contentDescription = "Start menu"
        chromeHost.addView(startMenu, FrameLayout.LayoutParams(desiredStartMenuWidth(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            leftMargin = dp(8)
            bottomMargin = taskbarHeight + dp(8)
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
        launcher.columnCount = desiredLauncherColumns()
        val compact = isCompactDesktop()
        launcherLayoutProfile = "${launcher.columnCount}:$compact"
        val tileWidth = dp(if (compact) 74 else 84)
        val tileHeight = dp(if (compact) 80 else 90)
        for (app in launcherApps) {
            launcher.addView(launcherTile(app), GridLayout.LayoutParams().apply {
                width = tileWidth
                height = tileHeight
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        header.addView(TextView(activity).apply {
            text = "RiftOS"
            setTextColor(TEXT)
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setIncludeFontPadding(false)
        }, LinearLayout.LayoutParams(0, dp(32), 1f))
        header.addView(TextView(activity).apply {
            text = "Apps"
            setTextColor(MUTED)
            textSize = 10f
            gravity = Gravity.END or Gravity.BOTTOM
            setIncludeFontPadding(false)
        }, LinearLayout.LayoutParams(dp(64), dp(32)))
        startMenu.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        startMenu.addView(View(activity).apply { setBackgroundColor(0x16ffffff) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            bottomMargin = dp(10)
        })

        val menuGrid = GridLayout(activity).apply {
            columnCount = 3
            rowCount = GridLayout.UNDEFINED
        }
        val itemWidth = ((desiredStartMenuWidth() - dp(36) - dp(24)) / 3).coerceAtLeast(dp(72))
        for (app in launcherApps) {
            menuGrid.addView(startMenuTile(app), GridLayout.LayoutParams().apply {
                width = itemWidth
                height = dp(78)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }
        startMenu.addView(menuGrid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        resizeStartMenu()
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
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setIncludeFontPadding(false)
            setPadding(dp(12), 0, dp(4), 0)
        }
        titleBar.addView(titleText, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            rightMargin = dp(126)
        })
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleBar.addView(actions, FrameLayout.LayoutParams(dp(126), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
        val min = windowButton("—", "Minimize $title")
        val max = windowButton("□", "Maximize $title")
        val close = windowButton("×", "Close $title", danger = true)
        actions.addView(min, LinearLayout.LayoutParams(dp(42), ViewGroup.LayoutParams.MATCH_PARENT))
        actions.addView(max, LinearLayout.LayoutParams(dp(42), ViewGroup.LayoutParams.MATCH_PARENT))
        actions.addView(close, LinearLayout.LayoutParams(dp(42), ViewGroup.LayoutParams.MATCH_PARENT))

        val left = View(activity).apply { setBackgroundColor(BORDER) }
        val right = View(activity).apply { setBackgroundColor(BORDER) }
        val bottom = View(activity).apply { setBackgroundColor(BORDER) }
        val resize = View(activity).apply {
            setBackgroundColor(Color.TRANSPARENT)
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
        windowClosedSink(id)
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
        record.titleText.text = record.title
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
        launcherScroll.visibility = if (windows.values.any { !it.minimized }) View.GONE else View.VISIBLE
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
        val baseId = appId.removePrefix("riftrt:")
        val pinned = taskbarPins.contains(baseId)
        val showLabel = !isCompactDesktop() && record != null && !pinned
        val itemWidth = if (showLabel) dp(140) else dp(44)
        val item = FrameLayout(activity).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "Taskbar $label"
            background = pressable(if (active) PANEL_ACTIVE else Color.TRANSPARENT, 0x13ffffff, 6)
            setOnClickListener {
                val live = windows[appId] ?: windows["riftrt:$appId"] ?: record?.let { windows[it.id] }
                if (live == null) openApp(appId)
                else if (live.id == activeId && !live.minimized) minimize(live.id, "taskbar-minimize")
                else focus(live.id, "taskbar-focus")
            }
        }
        item.addView(TextView(activity).apply {
            val icon = taskIcon(baseId, label)
            text = if (showLabel) "$icon   ${label.take(18)}" else icon
            setTextColor(TEXT)
            textSize = if (showLabel) 10f else 18f
            gravity = if (showLabel) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER
            setIncludeFontPadding(false)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setPadding(if (showLabel) dp(9) else 0, 0, if (showLabel) dp(6) else 0, 0)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (record != null) {
            item.addView(View(activity).apply {
                background = solid(if (active) ACCENT else 0xff8ba6b7.toInt(), dp(3).toFloat())
            }, FrameLayout.LayoutParams(dp(if (active) 24 else 14), dp(3), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(1)
            })
        }
        taskStrip.addView(item, LinearLayout.LayoutParams(itemWidth, dp(40)).apply {
            setMargins(dp(1), 0, dp(1), 0)
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
        taskbar.bringToFront()
        if (startMenu.visibility == View.VISIBLE) startMenu.bringToFront()
    }

    private fun updateFocusStyle(record: WindowRecord, focused: Boolean) {
        val border = if (focused) FOCUS_BORDER else BORDER
        record.titleBar.background = solid(WINDOW_BAR, dp(8).toFloat(), border, borderWidth)
        record.leftBorder.setBackgroundColor(border)
        record.rightBorder.setBackgroundColor(border)
        record.bottomBorder.setBackgroundColor(border)
    }

    private fun relayoutForHostChange() {
        val columns = desiredLauncherColumns()
        val profile = "$columns:${isCompactDesktop()}"
        if (launcherLayoutProfile != profile) updateLauncherViews() else resizeStartMenu()
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
        val width = (availableW * 0.68f).roundToInt().coerceIn(minW.coerceAtLeast(1), availableW)
        val height = (availableH * 0.72f).roundToInt().coerceIn(minH.coerceAtLeast(1), availableH)
        val offset = dp(24) * (index % 6)
        val left = (work.left + dp(34) + offset).coerceIn(work.left, (work.right - width).coerceAtLeast(work.left))
        val top = (work.top + dp(30) + offset).coerceIn(work.top, (work.bottom - height).coerceAtLeast(work.top))
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
            wallpaper.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xff07141d.toInt(), 0xff0b2530.toInt(), 0xff08161d.toInt()))
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

    private fun isCompactDesktop(): Boolean {
        val width = chromeHost.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        return width < dp(700)
    }

    private fun desiredLauncherColumns(): Int {
        val width = chromeHost.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        val tileWidth = dp(if (isCompactDesktop()) 74 else 84)
        return ((width - dp(12)).coerceAtLeast(tileWidth) / (tileWidth + dp(8))).coerceAtLeast(1)
    }

    private fun desiredStartMenuWidth(): Int {
        val width = chromeHost.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        return dp(420).coerceAtMost((width - dp(16)).coerceAtLeast(dp(240)))
    }

    private fun resizeStartMenu() {
        val params = (startMenu.layoutParams as? FrameLayout.LayoutParams) ?: return
        params.width = desiredStartMenuWidth()
        params.leftMargin = dp(8)
        params.bottomMargin = taskbarHeight + dp(8)
        params.gravity = Gravity.BOTTOM or Gravity.START
        startMenu.layoutParams = params
    }

    private fun launcherTile(app: LauncherApp): View {
        val compact = isCompactDesktop()
        val iconSize = dp(if (compact) 40 else 44)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(7), dp(4), dp(4))
            background = pressable(Color.TRANSPARENT, 0x17ffffff, 7)
            isClickable = true
            isFocusable = true
            isEnabled = runtimeReady
            contentDescription = app.name
            setOnClickListener { if (runtimeReady) openApp(app.id) }
            addView(TextView(activity).apply {
                text = app.icon
                setTextColor(ACCENT)
                textSize = if (compact) 17f else 19f
                gravity = Gravity.CENTER
                setIncludeFontPadding(false)
                setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                background = iconBackground(if (compact) 9 else 10)
            }, LinearLayout.LayoutParams(iconSize, iconSize))
            addView(TextView(activity).apply {
                text = app.name
                setTextColor(TEXT)
                textSize = 11f
                gravity = Gravity.CENTER
                setIncludeFontPadding(false)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setPadding(0, dp(5), 0, 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    private fun startMenuTile(app: LauncherApp): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = pressable(Color.TRANSPARENT, 0x12ffffff, 8)
        isClickable = true
        isFocusable = true
        isEnabled = runtimeReady
        contentDescription = "Start ${app.name}"
        setOnClickListener {
            startMenu.visibility = View.GONE
            if (runtimeReady) openApp(app.id)
        }
        addView(TextView(activity).apply {
            text = app.icon
            setTextColor(ACCENT)
            textSize = 14f
            gravity = Gravity.CENTER
            setIncludeFontPadding(false)
            setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = solid(0xff142b37.toInt(), dp(8).toFloat())
        }, LinearLayout.LayoutParams(dp(34), dp(34)))
        addView(TextView(activity).apply {
            text = app.name
            setTextColor(TEXT)
            textSize = 10f
            gravity = Gravity.CENTER
            setIncludeFontPadding(false)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setPadding(dp(2), dp(6), dp(2), 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun taskIcon(appId: String, label: String): String =
        launcherApps.firstOrNull { it.id == appId }?.icon
            ?: launcherApps.firstOrNull { it.name.equals(label, ignoreCase = true) }?.icon
            ?: "□"

    private fun iconBackground(radiusDp: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xff1c3340.toInt(), 0xff10242d.toInt())).apply {
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), 0x22ffffff)
        }

    private fun pressable(
        normalColor: Int = Color.TRANSPARENT,
        pressedColor: Int = 0x13ffffff,
        radiusDp: Int = 6,
        strokeColor: Int? = null
    ): android.graphics.drawable.StateListDrawable = android.graphics.drawable.StateListDrawable().apply {
        val radius = dp(radiusDp).toFloat()
        val focusStroke = strokeColor ?: 0x24ffffff
        addState(intArrayOf(android.R.attr.state_pressed), solid(pressedColor, radius, focusStroke, dp(1)))
        addState(intArrayOf(android.R.attr.state_focused), solid(pressedColor, radius, focusStroke, dp(1)))
        addState(intArrayOf(), solid(normalColor, radius, strokeColor, if (strokeColor == null) 0 else dp(1)))
    }

    private fun configureButton(button: Button, label: String, description: String) {
        button.text = label
        button.isAllCaps = false
        button.textSize = 12f
        button.setTextColor(TEXT)
        button.setIncludeFontPadding(false)
        button.contentDescription = description
        button.background = pressable()
        button.minWidth = 0
        button.minimumWidth = 0
        button.minHeight = 0
        button.minimumHeight = 0
        button.stateListAnimator = null
        button.setPadding(dp(4), 0, dp(4), 0)
    }

    private fun windowButton(label: String, description: String, danger: Boolean = false): Button = Button(activity).apply {
        text = label
        isAllCaps = false
        textSize = if (danger) 20f else 14f
        setTextColor(TEXT)
        setIncludeFontPadding(false)
        contentDescription = description
        background = pressable(Color.TRANSPARENT, if (danger) 0xffc42b1c.toInt() else 0x12ffffff, 0)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        stateListAnimator = null
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
