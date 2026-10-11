package com.riftos.external.shell

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.riftos.app.RiftShellGraphicalComponentV1
import com.riftos.app.RiftShellPlatformServicesV1
import org.json.JSONArray
import org.json.JSONObject

/**
 * Independently owned graphical desktop and RAPP window manager.
 *
 * No APK-owned RiftNativeDesktop/RiftShellActivity/RiftShellRappHost is
 * referenced. Android Activity is only the stable platform envelope.
 * App launch, RAPP frames, events and recovery travel through host Core IPC.
 *
 * Checkpoint: generic RAPP windows and desktop only. Built-in Files, browser,
 * editor, IME, full accessibility and window-layout parity are still TODO.
 * Do not mark feature complete until compared on a real device.
 */
class IndependentGraphicalShellV1 : RiftShellGraphicalComponentV1 {
    private data class Window(
        val id: String,
        val title: String,
        val generation: Long,
        val outer: LinearLayout,
        val content: FrameLayout,
        var revision: Long = -1L,
        var minimized: Boolean = false
    )

    private var owner: Activity? = null
    private var bridge: RiftShellPlatformServicesV1? = null
    private var host: FrameLayout? = null
    private var desktop: FrameLayout? = null
    private var dock: LinearLayout? = null
    private var startMenu: LinearLayout? = null
    private var ticker = Handler(Looper.getMainLooper())
    private var resumed = false
    private var focusedId: String? = null
    private val windows = LinkedHashMap<String, Window>()
    private val launcher = ArrayList<Pair<String, String>>()
    private val poll = object : Runnable {
        override fun run() {
            if (!resumed || owner == null) return
            runCatching { refreshSurfaces() }.onFailure { showStatus(it.message.orEmpty()) }
            ticker.postDelayed(this, 800L)
        }
    }

    private fun activity(): Activity = owner ?: error("Independent graphical Shell not attached")
    private fun services(): RiftShellPlatformServicesV1 =
        bridge ?: error("Core IPC unavailable")
    private fun density() = activity().resources.displayMetrics.density
    private fun dp(px: Int) = (px * density() + 0.5f).toInt()
    private fun color(bg: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(bg)
            cornerRadius = dp(8).toFloat()
        }

    private fun column(): LinearLayout = LinearLayout(activity()).apply {
        orientation = LinearLayout.VERTICAL
    }
    private fun row(): LinearLayout = LinearLayout(activity()).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private fun text(label: String, size: Float = 15f, bold: Boolean = false) =
        TextView(activity()).apply {
            setTextColor(Color.rgb(236, 240, 250))
            text = label
            textSize = size
            setPadding(dp(9), dp(7), dp(9), dp(7))
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
    private fun button(label: String, invoke: () -> Unit): Button =
        Button(activity()).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            minHeight = dp(44)
            setOnClickListener { invoke() }
        }

    override fun attach(
        activity: Activity, container: FrameLayout,
        services: RiftShellPlatformServicesV1, restore: JSONObject?
    ) {
        check(owner == null) { "Graphical Shell already attached" }
        owner = activity
        bridge = services
        host = container
        container.removeAllViews()
        val root = FrameLayout(activity).apply {
            setBackgroundColor(Color.rgb(19, 25, 37))
            contentDescription = "Independent RiftOS graphical desktop"
        }
        desktop = root
        container.addView(root, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val taskbar = row().apply {
            setBackgroundColor(Color.rgb(30, 39, 55))
            setPadding(dp(6), dp(2), dp(6), dp(2))
            contentDescription = "Independent RiftOS taskbar"
        }
        taskbar.addView(button("⊞ Start") { toggleStartMenu() })
        val open = row()
        dock = open
        taskbar.addView(open, LinearLayout.LayoutParams(0, dp(52), 1f))
        taskbar.addView(button("Desktop") { windows.values.forEach {
            it.outer.visibility = View.GONE
            it.minimized = true
        }; updateTaskbar(); report() })
        root.addView(taskbar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(58), Gravity.BOTTOM))

        val menu = column().apply {
            background = color(Color.rgb(37, 47, 66))
            visibility = View.GONE
            contentDescription = "Independent RiftOS start menu"
            elevation = dp(10).toFloat()
        }
        startMenu = menu
        root.addView(menu, FrameLayout.LayoutParams(dp(320),
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.BOTTOM).apply {
            bottomMargin = dp(60)
            leftMargin = dp(8)
        })
        rebuildLauncher()
        restoreWindows(restore)
        report()
    }

    private fun rebuildLauncher() {
        val ui = desktop ?: return
        // Only the graphically owned launcher layer is replaced; existing
        // window objects and Core sessions are not destroyed on refresh.
        val previous = ui.findViewWithTag<View>("external-icons")
        if (previous != null) ui.removeView(previous)
        val icons = column().apply {
            tag = "external-icons"
            contentDescription = "Independent installed RAPP launcher"
            setPadding(dp(8), dp(14), dp(8), dp(8))
        }
        icons.addView(text("RiftOS · External graphical Shell", 18f, true))
        launcher.clear()
        val installed = services().installed()
        for (i in 0 until minOf(installed.length(), 64)) {
            val app = installed.optJSONObject(i) ?: continue
            val id = app.optString("id")
            val name = app.optString("name", id)
            if (id.isBlank() || id.length > 64) continue
            launcher.add(id to name)
            icons.addView(button("▦  " + name.take(36)) { launch(id, name) })
        }
        val scroll = ScrollView(activity()).apply {
            tag = "external-icons"
            addView(icons)
            isFillViewport = false
        }
        ui.addView(scroll, FrameLayout.LayoutParams(
            dp(305), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START).apply {
            bottomMargin = dp(60)
        })
        // Keep Windows and taskbar above desktop icons.
        scroll.elevation = 0f
        refreshMenu()
    }

    private fun refreshMenu() {
        val menu = startMenu ?: return
        menu.removeAllViews()
        menu.addView(text("Installed RAPPs", 16f, true))
        for ((id, name) in launcher) {
            menu.addView(button("Open  ·  " + name.take(30)) {
                menu.visibility = View.GONE
                launch(id, name)
            })
        }
    }

    private fun toggleStartMenu() {
        val m = startMenu ?: return
        m.visibility = if (m.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        if (m.visibility == View.VISIBLE) {
            runCatching { rebuildLauncher() }.onFailure { showStatus(it.message.orEmpty()) }
        }
    }

    private fun launch(id: String, label: String) {
        runCatching {
            val state = services().open(id)
            val generation = state.optLong("attachmentGeneration", -1L)
            require(generation > 0L) {
                "Core did not return a valid attached RAPP generation"
            }
            openWindow(id, label, generation)
        }.onFailure { showStatus("Core launch refused: " + it.message.orEmpty()) }
    }

    private fun restoreWindows(snapshot: JSONObject?) {
        val restored = snapshot?.optJSONArray("windows") ?: return
        for (i in 0 until minOf(restored.length(), 16)) {
            val entry = restored.optJSONObject(i) ?: continue
            val id = entry.optString("id")
            val gen = entry.optLong("attachmentGeneration", -1L)
            if (id.isBlank() || gen < 1L) continue
            runCatching {
                services().reattach(id, gen)
                openWindow(id, entry.optString("title", id), gen)
            }
        }
    }

    private fun openWindow(id: String, title: String, generation: Long) {
        windows[id]?.let {
            require(it.generation == generation) { "Stale RAPP attachment" }
            it.minimized = false
            it.outer.visibility = View.VISIBLE
            raiseWindow(id)
            return
        }
        check(windows.size < 16) { "Independent Shell window limit reached" }
        val desktop = desktop ?: error("Desktop uninitialized")
        val outer = column().apply {
            background = color(Color.rgb(45, 55, 74))
            contentDescription = "External RAPP window " + title.take(60)
            elevation = dp(12).toFloat()
        }
        val caption = row().apply { setBackgroundColor(Color.rgb(51, 72, 107)) }
        val titleLabel = text(title.take(64), 15f, true)
        caption.addView(titleLabel, LinearLayout.LayoutParams(0, dp(49), 1f))
        caption.addView(button("—") { windows[id]?.let {
            it.minimized = true
            it.outer.visibility = View.GONE
            if (focusedId == id) focusedId = null
            updateTaskbar(); report()
        } })
        caption.addView(button("×") { closeWindow(id) })
        outer.addView(caption)
        val content = FrameLayout(activity()).apply {
            setBackgroundColor(Color.rgb(31, 39, 54))
        }
        outer.addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val width = maxOf(dp(260), minOf(desktop.width - dp(15), dp(515)))
        val height = maxOf(dp(250), minOf(desktop.height - dp(80), dp(570)))
        val params = FrameLayout.LayoutParams(width, height).apply {
            leftMargin = dp(20) + (windows.size % 4) * dp(24)
            topMargin = dp(24) + (windows.size % 4) * dp(27)
        }
        desktop.addView(outer, params)
        val win = Window(id, title, generation, outer, content)
        windows[id] = win

        // Dragging moves ONLY the Shell-owned Android window; Core app session
        // and its attachment generation are unchanged.
        caption.setOnTouchListener(object : View.OnTouchListener {
            private var lastX = 0f
            private var lastY = 0f
            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lastX = event.rawX; lastY = event.rawY
                        raiseWindow(id)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val params = outer.layoutParams as FrameLayout.LayoutParams
                        params.leftMargin = (params.leftMargin + event.rawX - lastX).toInt()
                            .coerceIn(0, maxOf(0, desktop.width - dp(80)))
                        params.topMargin = (params.topMargin + event.rawY - lastY).toInt()
                            .coerceIn(0, maxOf(0, desktop.height - dp(100)))
                        outer.layoutParams = params
                        lastX = event.rawX; lastY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_UP -> { report(); return true }
                }
                return false
            }
        })
        raiseWindow(id)
        refreshWindow(win)
        updateTaskbar()
        report()
    }

    private fun raiseWindow(id: String) {
        val win = windows[id] ?: return
        win.outer.bringToFront()
        focusedId = id
        runCatching { services().focus(id) }
        updateTaskbar()
    }

    private fun closeWindow(id: String) {
        val win = windows.remove(id) ?: return
        // Closing a graphical window is explicit user intent to stop the RAPP.
        runCatching { services().stop(id, win.generation) }
        (win.outer.parent as? ViewGroup)?.removeView(win.outer)
        if (focusedId == id) focusedId = null
        updateTaskbar()
        report()
    }

    private fun updateTaskbar() {
        val bar = dock ?: return
        bar.removeAllViews()
        for (win in windows.values) {
            bar.addView(button(win.title.take(11)) {
                win.minimized = false
                win.outer.visibility = View.VISIBLE
                raiseWindow(win.id)
                report()
            })
        }
    }

    private fun refreshSurfaces() {
        for (win in windows.values.toList()) {
            if (win.minimized) continue
            refreshWindow(win)
        }
    }

    private fun refreshWindow(win: Window) {
        val result = services().snapshot(win.id)
        if (!result.optBoolean("present")) return
        if (result.optLong("attachmentGeneration", -1L) != win.generation) {
            showStatus("Stale Core generation; refusing to render " + win.id)
            return
        }
        val revision = result.optLong("revision")
        if (revision == win.revision) return
        val nodes = result.optJSONArray("nodes") ?: return
        require(nodes.length() in 1..256) { "Core surface exceeds bounds" }
        val content = win.content
        content.removeAllViews()
        val absolute = result.optInt("layout") == 2
        val root: ViewGroup = if (absolute) FrameLayout(activity()) else ScrollView(activity()).apply {
            isFillViewport = true
        }
        val flow = if (absolute) null else column().apply {
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        if (flow != null) (root as ScrollView).addView(flow)
        val created = HashSet<Int>()
        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            val kind = node.optInt("kind")
            val nid = node.optInt("id")
            if (nid <= 0 || !created.add(nid)) continue
            if (kind == 1 || kind == 2) continue
            val label = node.optString("text").take(16384)
            val view = when (kind) {
                3 -> text(label)
                4 -> EditText(activity()).apply {
                    setText(label)
                    setSingleLine(false)
                    contentDescription = "RAPP input $nid"
                    setTextColor(Color.WHITE)
                    setOnFocusChangeListener { _, focused ->
                        if (!focused) sendInput(win, nid, text.toString())
                    }
                }
                5 -> button(label.take(96)) { sendEvent(win, 1, nid) }
                6 -> text("Image · " + label.take(36))
                else -> null
            } ?: continue
            if (absolute) {
                val params = FrameLayout.LayoutParams(
                    if (node.optInt("width") > 0) dp(node.optInt("width")) else dp(150),
                    if (node.optInt("height") > 0) dp(node.optInt("height")) else dp(55)
                ).apply {
                    leftMargin = dp(node.optInt("x")).coerceAtLeast(0)
                    topMargin = dp(node.optInt("y")).coerceAtLeast(0)
                }
                root.addView(view, params)
            } else flow?.addView(view, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        content.addView(root, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        win.revision = revision
    }

    private fun sendInput(win: Window, target: Int, value: String) {
        sendEvent(win, 7, target, value.take(65536))
    }

    private fun sendEvent(win: Window, kind: Int, target: Int, text: String = "") {
        runCatching {
            services().event(win.id, win.generation, JSONObject()
                .put("kind", kind).put("targetId", target)
                .put("arg0", 0).put("arg1", 0)
                .put("arg2", 0).put("arg3", 0)
                .put("text", text))
        }.onFailure { showStatus("Core rejected input: " + it.message.orEmpty()) }
    }

    private fun report() {
        val items = JSONArray()
        for (win in windows.values) {
            val p = win.outer.layoutParams as? FrameLayout.LayoutParams
            items.put(JSONObject().put("id", win.id)
                .put("title", win.title)
                .put("attachmentGeneration", win.generation)
                .put("focused", win.id == focusedId)
                .put("minimized", win.minimized)
                .put("x", p?.leftMargin ?: 0).put("y", p?.topMargin ?: 0))
        }
        runCatching { services().reportDesktop(JSONObject()
            .put("schema", "riftos.shell.desktop-state/1")
            .put("windows", items)
            .put("focusedId", focusedId ?: JSONObject.NULL)) }
    }

    private fun showStatus(message: String) {
        val current = owner ?: return
        if (current.isFinishing) return
        // Show truthful, bounded non-modal status; never silently fake
        // successful app launch, Core IPC or frame delivery.
        android.widget.Toast.makeText(current,
            message.take(160), android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        resumed = true
        ticker.removeCallbacks(poll)
        ticker.post(poll)
    }
    override fun onPause() {
        resumed = false
        ticker.removeCallbacks(poll)
        report()
    }
    override fun onWindowFocusChanged(focused: Boolean) {
        if (!focused) report()
    }
    override fun onBackPressed(): Boolean {
        val menu = startMenu
        if (menu?.visibility == View.VISIBLE) {
            menu.visibility = View.GONE
            return true
        }
        val id = focusedId ?: return false
        windows[id]?.let {
            it.minimized = true; it.outer.visibility = View.GONE
            focusedId = null; updateTaskbar(); report()
        }
        return true
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean =
        false

    override fun onDestroy() {
        resumed = false
        ticker.removeCallbacks(poll)
        // The Core owns its sessions. Shell recreation does not kill RAPPs.
        windows.clear()
        host?.removeAllViews()
        owner = null
        bridge = null
        desktop = null
        dock = null
        startMenu = null
        host = null
        focusedId = null
    }
}
