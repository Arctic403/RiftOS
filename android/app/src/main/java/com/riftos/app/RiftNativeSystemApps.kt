package com.riftos.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Android-owned built-in RiftOS surfaces that no longer need the trusted shell WebView.
 *
 * Patch 2A migrates RiftShell Terminal and Task Manager first. The host is deliberately small:
 * window chrome/lifecycle stays in RiftNativeDesktop and shell execution stays in the process-owned
 * RiftNativeShell contract. No WebView/Chromium object is created or referenced here.
 */
class RiftNativeSystemApps(
    private val activity: Activity,
    private val desktop: RiftNativeDesktop,
    private val shell: RiftShellExecutor
) {
    companion object {
        private const val MAX_TERMINAL_CHARS = 200_000
        private val MIGRATED_IDS = setOf("terminal", "tasks")
        private const val BG = 0xff0b1118.toInt()
        private const val PANEL = 0xff111a23.toInt()
        private const val BORDER = 0xff2a3640.toInt()
        private const val TEXT = 0xffe7eef5.toInt()
        private const val MUTED = 0xff9aa8b5.toInt()
        private const val ACCENT = 0xff78f6c7.toInt()
    }

    private data class TerminalState(
        val root: LinearLayout,
        val scroll: ScrollView,
        val output: TextView,
        val prompt: TextView,
        val input: EditText,
        var cwd: String = "/",
        var busy: Boolean = false
    )

    private data class TaskState(
        val root: LinearLayout,
        val summary: TextView,
        val rows: LinearLayout,
        val handler: Handler,
        var refresh: Runnable? = null
    )

    private var terminal: TerminalState? = null
    private var tasks: TaskState? = null

    fun handles(id: String): Boolean = id.trim().lowercase() in MIGRATED_IDS

    /** Called by the native launcher before any compatibility-WebView dispatch. */
    fun openFromLauncher(id: String): Boolean {
        val normalized = id.trim().lowercase()
        if (!handles(normalized)) return false
        open(normalized)
        return true
    }

    fun handle(method: String, args: JSONObject): JSONObject = when (method) {
        "system.app.open" -> open(args.optString("id"))
        "system.app.close" -> close(args.optString("id"))
        "system.app.state" -> state()
        else -> throw IllegalArgumentException("Unsupported native system-app method: $method")
    }

    fun onDesktopClosed(id: String): Boolean {
        return when (id.trim().lowercase()) {
            "terminal" -> {
                terminal = null
                true
            }
            "tasks" -> {
                stopTasks()
                true
            }
            else -> false
        }
    }

    fun destroy() {
        terminal = null
        stopTasks()
    }

    private fun open(rawId: String): JSONObject {
        val id = rawId.trim().lowercase()
        require(handles(id)) { "Native system app is not migrated: $rawId" }
        return when (id) {
            "terminal" -> openTerminal()
            "tasks" -> openTasks()
            else -> throw IllegalArgumentException("Native system app is not migrated: $id")
        }
    }

    private fun close(rawId: String): JSONObject {
        val id = rawId.trim().lowercase()
        require(handles(id)) { "Native system app is not migrated: $rawId" }
        desktop.handle("desktop.window.close", JSONObject().put("id", id))
        onDesktopClosed(id)
        return state()
    }

    private fun state(): JSONObject = JSONObject()
        .put("backend", "android-native-system-apps")
        .put("webViewRequired", false)
        .put("migrated", JSONArray(MIGRATED_IDS.toList()))
        .put("terminalOpen", terminal != null)
        .put("tasksOpen", tasks != null)

    private fun openTerminal(): JSONObject {
        openWindow("terminal", "RiftShell", "ANDROID NATIVE SHELL")
        val existing = terminal
        if (existing != null) {
            desktop.attachContent("terminal", existing.root)
            existing.input.requestFocus()
            return state()
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val output = TextView(activity).apply {
            setTextColor(TEXT)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(4), dp(4), dp(4), dp(8))
            text = "RiftShell ${BuildConfig.VERSION_NAME}\nAndroid-native terminal UI · process-owned shell ready. Type help."
        }
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            addView(output, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), 0)
        }
        val prompt = TextView(activity).apply {
            setTextColor(ACCENT)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            text = "/ $"
            gravity = Gravity.CENTER_VERTICAL
        }
        val input = EditText(activity).apply {
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setSingleLine(true)
            hint = "command"
            imeOptions = EditorInfo.IME_ACTION_DONE
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setBackgroundColor(PANEL)
            setPadding(dp(8), 0, dp(8), 0)
            contentDescription = "RiftShell command"
        }
        form.addView(prompt, LinearLayout.LayoutParams(dp(92), dp(42)))
        form.addView(input, LinearLayout.LayoutParams(0, dp(42), 1f))
        root.addView(form, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))

        val terminalState = TerminalState(root, scroll, output, prompt, input)
        terminal = terminalState
        input.setOnEditorActionListener { _, actionId, event ->
            val submit = actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (submit) {
                submitTerminal(terminalState)
                true
            } else false
        }

        desktop.attachContent("terminal", root)
        input.post { input.requestFocus() }
        return state()
    }

    private fun submitTerminal(state: TerminalState) {
        if (terminal !== state || state.busy) return
        val raw = state.input.text?.toString().orEmpty()
        state.input.setText("")
        if (raw.isBlank()) return

        appendTerminal(state, "${state.cwd} $ $raw")
        if (raw.trim().equals("clear", ignoreCase = true)) {
            state.output.text = ""
            return
        }

        state.busy = true
        state.input.isEnabled = false
        shell.execute(raw, state.cwd) { result ->
            activity.runOnUiThread {
                if (terminal !== state) return@runOnUiThread
                if (result.optBoolean("ok", false)) {
                    val nextCwd = result.optString("cwd", state.cwd).ifBlank { state.cwd }
                    state.cwd = nextCwd
                    val output = result.optString("output")
                    if (output.isNotEmpty()) appendTerminal(state, output)
                } else {
                    appendTerminal(state, "error: ${result.optString("error", "RiftShell command failed")}")
                }
                state.prompt.text = "${state.cwd} $"
                state.busy = false
                state.input.isEnabled = true
                state.input.requestFocus()
            }
        }
    }

    private fun appendTerminal(state: TerminalState, value: String) {
        val before = state.output.text?.toString().orEmpty()
        var next = if (before.isBlank()) value else "$before\n$value"
        if (next.length > MAX_TERMINAL_CHARS) {
            next = "… older terminal output trimmed …\n" + next.takeLast(MAX_TERMINAL_CHARS)
        }
        state.output.text = next
        state.scroll.post { state.scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun openTasks(): JSONObject {
        openWindow("tasks", "Task Manager", "ANDROID NATIVE TASKS")
        val existing = tasks
        if (existing != null) {
            desktop.attachContent("tasks", existing.root)
            refreshTasks(existing)
            return state()
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val summary = TextView(activity).apply {
            setTextColor(TEXT)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(4), dp(4), dp(8))
            text = "RiftOS native tasks"
        }
        root.addView(summary, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val rows = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            addView(rows, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val taskState = TaskState(root, summary, rows, Handler(Looper.getMainLooper()))
        val refresh = object : Runnable {
            override fun run() {
                if (tasks !== taskState) return
                refreshTasks(taskState)
                taskState.handler.postDelayed(this, 1_000L)
            }
        }
        taskState.refresh = refresh
        tasks = taskState
        desktop.attachContent("tasks", root)
        refreshTasks(taskState)
        taskState.handler.postDelayed(refresh, 1_000L)
        return state()
    }

    private fun refreshTasks(state: TaskState) {
        if (tasks !== state) return
        val desktopState = desktop.handle("desktop.window.state", JSONObject())
        val windows = desktopState.optJSONArray("windows") ?: JSONArray()
        state.summary.text = "RiftOS runtime · ${windows.length()} window task(s) · WebView not required"
        state.rows.removeAllViews()

        addTaskRow(state, "RiftKernel", "system · protected", null)
        addTaskRow(state, "Rift Desktop", "native window authority · protected", null)
        addTaskRow(state, "Native RiftShell", "process-owned control plane · protected", null)

        for (index in 0 until windows.length()) {
            val row = windows.optJSONObject(index) ?: continue
            val id = row.optString("id")
            val title = row.optString("title", id).ifBlank { id }
            val flags = buildList {
                add(row.optString("kicker", "window").ifBlank { "window" })
                if (row.optBoolean("focused")) add("focused")
                if (row.optBoolean("minimized")) add("minimized")
                if (row.optBoolean("maximized")) add("maximized")
            }.joinToString(" · ")
            addTaskRow(state, title, "$id · $flags", id.takeUnless { it == "tasks" })
        }
    }

    private fun addTaskRow(state: TaskState, title: String, detail: String, closableId: String?) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(7), dp(8), dp(7))
            setBackgroundColor(PANEL)
        }
        val labels = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        labels.addView(TextView(activity).apply {
            text = title
            setTextColor(TEXT)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        labels.addView(TextView(activity).apply {
            text = detail
            setTextColor(MUTED)
            textSize = 10f
            maxLines = 2
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (closableId != null) {
            row.addView(Button(activity).apply {
                text = "End task"
                textSize = 10f
                isAllCaps = false
                contentDescription = "End task $title $closableId"
                setOnClickListener {
                    runCatching {
                        desktop.handle("desktop.window.close", JSONObject().put("id", closableId))
                    }
                    if (tasks === state) refreshTasks(state)
                }
            }, LinearLayout.LayoutParams(dp(94), dp(40)))
        } else {
            row.addView(TextView(activity).apply {
                text = "system"
                setTextColor(ACCENT)
                textSize = 10f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(94), dp(40)))
        }

        state.rows.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(5)
        })
    }

    private fun stopTasks() {
        tasks?.handler?.removeCallbacksAndMessages(null)
        tasks = null
    }

    private fun openWindow(id: String, title: String, kicker: String) {
        desktop.handle(
            "desktop.window.open",
            JSONObject()
                .put("id", id)
                .put("title", title)
                .put("kicker", kicker)
        )
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).roundToInt()
}
