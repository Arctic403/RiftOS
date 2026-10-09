package com.riftos.app

import android.app.Activity
import android.app.AlertDialog
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
 * Android-native system-window bodies for RiftShell Terminal and Task Manager.
 *
 * Window chrome/lifecycle stays in RiftNativeDesktop and command execution stays in the
 * process-owned RiftShellExecutor contract. No WebView/Chromium object is created or referenced
 * here.
 */
class RiftNativeSystemApps(
    private val activity: Activity,
    private val desktop: RiftNativeDesktop,
    private val shell: RiftShellExecutor,
    private val remoteCore: RiftShellCoreClient? = null
) {
    companion object {
        private const val MAX_TERMINAL_CHARS = 200_000
        private val NATIVE_IDS = setOf("terminal", "tasks", "installed-apps", "admin-permissions")
        private const val BG = 0xff0b1118.toInt()
        private const val PANEL = 0xff111a23.toInt()
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
        val handler: Handler
    )

    private data class InstalledAppsState(
        val root: LinearLayout,
        val summary: TextView,
        val rows: LinearLayout,
        val artifact: EditText,
        val install: Button,
        var busy: Boolean = false,
        var listenerId: Long = 0L
    )

    private var terminal: TerminalState? = null
    private var tasks: TaskState? = null
    private var installedApps: InstalledAppsState? = null
    private val adminApprovals by lazy { RiftNativeAdminApprovals(activity, desktop, remoteCore) }
    private fun installedPackages(): JSONArray =
        remoteCore?.installed()
            ?: RiftCoreRuntime.packages(activity.applicationContext).listInstalled()

    private fun handles(id: String): Boolean = id.trim().lowercase() in NATIVE_IDS

    /** Called by the native launcher before browser-backed installed-program dispatch. */
    fun openFromLauncher(id: String): Boolean {
        val normalized = id.trim().lowercase()
        if (!handles(normalized)) return false
        open(normalized)
        return true
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
            "installed-apps" -> {
                stopInstalledApps()
                true
            }
            "admin-permissions" -> {
                adminApprovals.close()
                true
            }
            else -> false
        }
    }

    fun destroy() {
        terminal = null
        stopTasks()
        stopInstalledApps()
        if (adminApprovalsInitialized) adminApprovals.destroy()
    }

    private var adminApprovalsInitialized = false
    private fun open(rawId: String) {
        val id = rawId.trim().lowercase()
        require(handles(id)) { "Native system app is unavailable: $rawId" }
        when (id) {
            "terminal" -> openTerminal()
            "tasks" -> openTasks()
            "installed-apps" -> openInstalledApps()
            "admin-permissions" -> {
                adminApprovalsInitialized = true
                adminApprovals.open()
            }
            else -> throw IllegalArgumentException("Native system app is unavailable: $id")
        }
    }

    private fun openTerminal() {
        openWindow("terminal", "RiftShell", "ANDROID NATIVE SHELL")
        val existing = terminal
        if (existing != null) {
            desktop.attachContent("terminal", existing.root)
            existing.input.requestFocus()
            return
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
            val marker = "… older terminal output trimmed …\n"
            next = marker + next.takeLast((MAX_TERMINAL_CHARS - marker.length).coerceAtLeast(0))
        }
        state.output.text = next
        state.scroll.post { state.scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun openTasks() {
        openWindow("tasks", "Task Manager", "ANDROID NATIVE TASKS")
        val existing = tasks
        if (existing != null) {
            desktop.attachContent("tasks", existing.root)
            refreshTasks(existing)
            return
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
        tasks = taskState
        desktop.attachContent("tasks", root)
        refreshTasks(taskState)
        taskState.handler.postDelayed(refresh, 1_000L)
    }

    private fun refreshTasks(state: TaskState) {
        if (tasks !== state) return
        val desktopState = desktop.handle("desktop.window.state", JSONObject())
        val windows = desktopState.optJSONArray("windows") ?: JSONArray()
        state.summary.text = "Native Task Manager · ${windows.length()} desktop window task(s)"
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
            addTaskRow(
                state,
                title,
                "$id · $flags",
                id.takeUnless { it == "tasks" },
                passiveLabel = if (id == "tasks") "current" else "system"
            )
        }
    }

    private fun addTaskRow(
        state: TaskState,
        title: String,
        detail: String,
        closableId: String?,
        passiveLabel: String = "system"
    ) {
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
                text = passiveLabel
                setTextColor(ACCENT)
                textSize = 10f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(94), dp(40)))
        }

        state.rows.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(5)
        })
    }

    /** Replaceable graphical client of Core's managed-RAPP package APIs. */
    private fun openInstalledApps() {
        openWindow("installed-apps", "Installed Apps", "RIFTOS CORE PACKAGES")
        val previous = installedApps
        if (previous != null) {
            desktop.attachContent("installed-apps", previous.root)
            refreshInstalledApps(previous)
            return
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val summary = TextView(activity).apply {
            setTextColor(TEXT)
            textSize = 12f
            setPadding(dp(4), dp(4), dp(4), dp(8))
            text = "Core application management"
        }
        root.addView(summary)
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val artifact = EditText(activity).apply {
            setSingleLine(true)
            textSize = 12f
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            hint = "D:/Builds/my-app.rapp"
            contentDescription = "RAPP package path in D:/Builds"
        }
        val install = Button(activity).apply {
            text = "Install"
            textSize = 11f
            isAllCaps = false
        }
        row.addView(artifact, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(install, LinearLayout.LayoutParams(dp(94), dp(48)))
        root.addView(row)

        val rows = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            addView(rows, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        val state = InstalledAppsState(root, summary, rows, artifact, install)
        installedApps = state
        if (remoteCore == null) {
            state.listenerId = RiftCorePackageEvents.subscribe {
                activity.runOnUiThread {
                    if (installedApps === state && !activity.isDestroyed) {
                        refreshInstalledApps(state)
                    }
                }
            }
        }
        install.setOnClickListener {
            val path = state.artifact.text?.toString()?.trim().orEmpty()
            if (path.isBlank()) {
                state.summary.text = "Enter a .rapp artifact path under D:/Builds"
            } else {
                operateInstalledApps(state) {
                    remoteCore?.installRapp(path)
                        ?: RiftCoreRuntime.buildPlatform(activity.applicationContext).installRapp(path)
                }
            }
        }
        desktop.attachContent("installed-apps", root)
        refreshInstalledApps(state)
    }

    private fun refreshInstalledApps(state: InstalledAppsState) {
        if (installedApps !== state) return
        val apps = runCatching { installedPackages() }.getOrElse {
            state.summary.text = "Unable to read installed apps: ${it.message}"
            return
        }
        if (!state.busy) state.summary.text =
            "Installed RAPPs: ${apps.length()} · Core-owned packages and permissions"
        state.rows.removeAllViews()
        for (index in 0 until apps.length()) {
            val app = apps.optJSONObject(index) ?: continue
            val id = app.optString("id")
            val name = app.optString("name", id)
            val declared = app.optJSONArray("permissions")
            val item = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(PANEL)
                setPadding(dp(8), dp(6), dp(8), dp(6))
            }
            val labels = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }
            labels.addView(TextView(activity).apply {
                text = name
                setTextColor(TEXT)
                textSize = 12f
                maxLines = 1
            })
            labels.addView(TextView(activity).apply {
                text = "$id · ${declared?.length() ?: 0} declared capabilities"
                setTextColor(MUTED)
                textSize = 10f
                maxLines = 2
            })
            item.addView(labels, LinearLayout.LayoutParams(0, dp(48), 1f))
            val remove = Button(activity).apply {
                text = "Uninstall"
                textSize = 10f
                isAllCaps = false
                isEnabled = !state.busy
                contentDescription = "Uninstall $name $id"
                setOnClickListener {
                    AlertDialog.Builder(activity)
                        .setTitle("Uninstall $name?")
                        .setMessage(
                            "Remove $id from C:/Programs? This also removes its saved " +
                            "state, revokes its grants, and closes its running session."
                        )
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Uninstall") { _, _ ->
                            operateInstalledApps(state) {
                                remoteCore?.uninstallRapp(id)
                                    ?: RiftCoreRuntime.buildPlatform(
                                        activity.applicationContext
                                    ).uninstallRapp(id)
                            }
                        }
                        .show()
                }
            }
            item.addView(remove, LinearLayout.LayoutParams(dp(100), dp(48)))
            state.rows.addView(item, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(5) })
        }
    }

    private fun operateInstalledApps(
        state: InstalledAppsState,
        operation: () -> JSONObject
    ) {
        if (installedApps !== state || state.busy) return
        state.busy = true
        state.install.isEnabled = false
        state.summary.text = "RiftOS Core: managing package…"
        Thread({
            val outcome = runCatching(operation)
            activity.runOnUiThread {
                if (installedApps !== state || activity.isDestroyed) return@runOnUiThread
                state.busy = false
                state.install.isEnabled = true
                refreshInstalledApps(state)
                state.summary.text = outcome.fold(
                    onSuccess = { it.optString("state", "Package operation completed") },
                    onFailure = { "Package operation failed: ${it.message}" }
                )
            }
        }, "rift-core-package-ui").apply { isDaemon = true; start() }
    }

    private fun stopInstalledApps() {
        installedApps?.let { state ->
            RiftCorePackageEvents.unsubscribe(state.listenerId)
        }
        installedApps = null
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
