package com.riftos.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Android-owned Files, Editor and Settings surfaces. No WebView/DOM/JS bridge is used. */
class RiftNativeWorkspaceApps(
    private val activity: Activity,
    private val desktop: RiftNativeDesktop
) {
    companion object {
        private const val MAX_EDITOR_BYTES = 1024 * 1024L
        private const val MAX_FILES_ROWS = 5_000
        private const val MAX_RENDERED_FILE_ROWS = 400
        private const val ANDROID_FILES_ROOT = "/Android"
        private const val ANDROID_FILES_REQUEST = 7101
        private const val SETTINGS_TASK_TIMEOUT_MS = 60_000L
        private const val EDITOR_IO_TIMEOUT_MS = 30_000L
        private const val FILES_IO_TIMEOUT_MS = 20_000L
        private val NATIVE_IDS = setOf("files", "editor", "devlab", "workspace-live", "settings")
        private const val BG = 0xff0b1118.toInt()
        private const val PANEL = 0xff111a23.toInt()
        private const val TEXT = 0xffe7eef5.toInt()
        private const val MUTED = 0xff9aa8b5.toInt()
        private const val ACCENT = 0xff78f6c7.toInt()
    }

    private data class FilesState(val root: LinearLayout, val path: TextView, val rows: LinearLayout, var displayPath: String)
    private data class EditorState(
        val root: LinearLayout,
        val pathInput: EditText,
        val body: EditText,
        val status: TextView,
        var displayPath: String? = null
    )
    private data class DevLabState(val root: LinearLayout, val pathInput: EditText, val body: EditText, val status: TextView, val output: TextView)
    private data class WorkspaceRecordsState(val root: LinearLayout, val status: TextView, val output: TextView)
    private data class EditorLoadResult(val display: String, val text: String, val size: Long, val name: String)
    private data class EditorSaveResult(val display: String, val size: Long)
    private data class NativeTaskOutcome<T>(val value: T? = null, val error: Throwable? = null)
    private data class DisplayEntry(
        val display: String,
        val isDirectory: Boolean,
        val size: Long,
        val label: String? = null
    )
    private data class FilesListing(val path: String, val entries: List<DisplayEntry>)
    private data class AndroidMount(val id: String, val name: String, val uri: Uri, val root: DocumentFile)

    private var files: FilesState? = null
    private var editor: EditorState? = null
    private var devLab: DevLabState? = null
    private var workspaceRecords: WorkspaceRecordsState? = null
    private var settings: View? = null
    private val riftRoot = File(activity.filesDir, "riftfs").apply { mkdirs() }.canonicalFile
    private val mountPrefs = activity.getSharedPreferences("rift-native", Context.MODE_PRIVATE)
    private val settingsExecutor = ThreadPoolExecutor(
        0,
        2,
        30L,
        TimeUnit.SECONDS,
        SynchronousQueue<Runnable>()
    )
    private val settingsWatchdog = Executors.newSingleThreadScheduledExecutor()
    private val nativeGit = RiftMcpRuntime.nativeGit(activity)
    private val riftLlm = RiftLlmDevClient(activity)

    fun handles(id: String): Boolean = id.trim().lowercase() in NATIVE_IDS

    fun openFromLauncher(id: String): Boolean {
        val normalized = id.trim().lowercase()
        if (!handles(normalized)) return false
        open(normalized)
        return true
    }

    fun handle(method: String, args: JSONObject): JSONObject = when (method) {
        "workspace.app.open" -> open(args.optString("id"))
        "workspace.app.close" -> close(args.optString("id"))
        "workspace.app.state" -> state()
        "workspace.editor.open" -> openEditor(args.optString("path", "/D:/Workspace"))
        else -> throw IllegalArgumentException("Unsupported native workspace-app method: $method")
    }

    fun onDesktopClosed(id: String): Boolean = when (id.trim().lowercase()) {
        "files" -> { files = null; true }
        "editor" -> { editor = null; true }
        "devlab" -> { devLab = null; true }
        "workspace-live" -> { workspaceRecords = null; true }
        "settings" -> { settings = null; true }
        else -> false
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != ANDROID_FILES_REQUEST) return false
        if (resultCode != Activity.RESULT_OK) return true
        val resultIntent = data ?: return true
        val uri = resultIntent.data ?: return true
        runCatching {
            val flags = resultIntent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            require(flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) { "Android folder did not grant read access" }
            require(flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) { "Android folder did not grant write access" }
            activity.contentResolver.takePersistableUriPermission(uri, flags)
            val root = DocumentFile.fromTreeUri(activity, uri)
                ?: throw IllegalArgumentException("Selected Android folder is unavailable")
            require(root.isDirectory) { "Selected Android item is not a folder" }
            require(root.canWrite()) { "Selected Android folder is not writable" }
            val existing = mountedAndroidRoots().firstOrNull { it.uri == uri }
            val id = existing?.id ?: "android-${UUID.randomUUID()}"
            val name = root.name?.takeIf { it.isNotBlank() } ?: "Android Files"
            mountPrefs.edit().putString(
                "mount:$id",
                JSONObject().put("uri", uri.toString()).put("name", name).put("persistent", true).toString()
            ).apply()
            files?.let { state -> state.displayPath = ANDROID_FILES_ROOT; refreshFiles(state) }
        }.onFailure { Toast.makeText(activity, "Mount failed: ${it.message}", Toast.LENGTH_LONG).show() }
        return true
    }

    fun destroy() {
        files = null
        editor = null
        devLab = null
        workspaceRecords = null
        settings = null
        settingsExecutor.shutdownNow()
        settingsWatchdog.shutdownNow()
    }

    private fun open(id: String): JSONObject = when (id) {
        "files" -> openFiles(files?.displayPath ?: "/D:/Workspace")
        "editor" -> openEditor(editor?.displayPath ?: "/D:/Workspace")
        "devlab" -> openDevLab()
        "workspace-live" -> openWorkspaceRecords()
        "settings" -> openSettings()
        else -> throw IllegalArgumentException("Native workspace app is not migrated: $id")
    }

    private fun close(id: String): JSONObject {
        require(handles(id)) { "Native workspace app is not migrated: $id" }
        desktop.handle("desktop.window.close", JSONObject().put("id", id))
        onDesktopClosed(id)
        return state()
    }

    private fun state(): JSONObject = JSONObject()
        .put("backend", "android-native-workspace-apps")
        .put("webViewRequired", false)
        .put("nativeIds", JSONArray(NATIVE_IDS.toList()))
        .put("filesOpen", files != null)
        .put("editorOpen", editor != null)
        .put("devLabOpen", devLab != null)
        .put("workspaceRecordsOpen", workspaceRecords != null)
        .put("settingsOpen", settings != null)

    private fun openFiles(rawPath: String): JSONObject {
        openWindow("files", "Files", "ANDROID NATIVE FILES")
        val display = normalizeExistingDirectory(rawPath)
        files?.let {
            it.displayPath = display
            desktop.attachContent("files", it.root)
            refreshFiles(it)
            return state()
        }

        val root = column()
        val toolbar = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val path = TextView(activity).apply {
            setTextColor(ACCENT); textSize = 12f; typeface = Typeface.MONOSPACE
            setTextIsSelectable(true); setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        toolbar.addView(actionButton("Up") {
            files?.let { state -> state.displayPath = parentDisplay(state.displayPath); refreshFiles(state) }
        }, LinearLayout.LayoutParams(dp(74), dp(42)))
        toolbar.addView(actionButton("Refresh") { files?.let(::refreshFiles) }, LinearLayout.LayoutParams(dp(82), dp(42)))
        toolbar.addView(actionButton("Android") {
            files?.let { state -> state.displayPath = ANDROID_FILES_ROOT; refreshFiles(state) }
        }, LinearLayout.LayoutParams(dp(78), dp(42)))
        toolbar.addView(actionButton("Mount") { launchAndroidMountPicker() }, LinearLayout.LayoutParams(dp(70), dp(42)))
        toolbar.addView(HorizontalScrollView(activity).apply {
            addView(path, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))

        val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(activity).apply {
            isFillViewport = true
            addView(rows, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val state = FilesState(root, path, rows, display)
        files = state
        desktop.attachContent("files", root)
        refreshFiles(state)
        return state()
    }

    private fun refreshFiles(state: FilesState) {
        val requestedPath = state.displayPath
        state.path.text = requestedPath
        state.rows.removeAllViews()
        state.rows.addView(label("Loading…", MUTED))
        runNativeTask(
            timeoutMs = FILES_IO_TIMEOUT_MS,
            work = { FilesListing(requestedPath, listDisplay(requestedPath)) },
            complete = { outcome ->
                if (files !== state || state.displayPath != requestedPath) return@runNativeTask
                state.rows.removeAllViews()
                val listing = outcome.value
                if (listing == null) {
                    state.rows.addView(label("Error: ${outcome.error?.message ?: "unknown error"}", MUTED))
                    return@runNativeTask
                }
                val entries = listing.entries
                if (entries.isEmpty()) {
                    state.rows.addView(label("(empty)", MUTED))
                    return@runNativeTask
                }
                for (entry in entries.take(MAX_RENDERED_FILE_ROWS)) {
                    val display = entry.display
                    val isDirectory = entry.isDirectory
                    val row = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(8), dp(6), dp(8), dp(6)); setBackgroundColor(PANEL)
                    }
                    row.addView(TextView(activity).apply {
                        text = (if (isDirectory) "▣  " else "·  ") + (entry.label ?: display.substringAfterLast('/'))
                        setTextColor(TEXT); textSize = 12f
                        typeface = if (isDirectory) Typeface.DEFAULT_BOLD else Typeface.MONOSPACE
                        maxLines = 1
                    }, LinearLayout.LayoutParams(0, dp(40), 1f))
                    if (!isDirectory) {
                        row.addView(TextView(activity).apply {
                            text = humanBytes(entry.size); setTextColor(MUTED); textSize = 10f; gravity = Gravity.CENTER_VERTICAL
                        }, LinearLayout.LayoutParams(dp(84), dp(40)))
                    }
                    if (isAndroidMountRoot(display)) {
                        row.addView(actionButton("Unmount") {
                            runCatching { unmountAndroid(display) }
                                .onSuccess {
                                    state.displayPath = ANDROID_FILES_ROOT
                                    refreshFiles(state)
                                }
                                .onFailure { Toast.makeText(activity, "Unmount failed: ${it.message}", Toast.LENGTH_LONG).show() }
                        }, LinearLayout.LayoutParams(dp(86), dp(40)))
                    }
                    row.setOnClickListener {
                        if (isDirectory) { state.displayPath = display; refreshFiles(state) } else openEditor(display)
                    }
                    state.rows.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { bottomMargin = dp(3) })
                }
                if (entries.size > MAX_RENDERED_FILE_ROWS) {
                    state.rows.addView(label("Showing first $MAX_RENDERED_FILE_ROWS of ${entries.size} items", MUTED))
                }
            }
        )
    }

    private fun openEditor(rawPath: String): JSONObject {
        openWindow("editor", "Editor", "ANDROID NATIVE EDITOR")
        editor?.let {
            desktop.attachContent("editor", it.root)
            if (rawPath.isNotBlank() && rawPath != "/D:/Workspace") loadEditor(it, rawPath)
            return state()
        }

        val root = column()
        val pathInput = EditText(activity).apply {
            setTextColor(TEXT); setHintTextColor(MUTED); textSize = 11f; typeface = Typeface.MONOSPACE
            hint = "/D:/Workspace/file"; setSingleLine(true); setBackgroundColor(PANEL); setPadding(dp(8), 0, dp(8), 0)
        }
        val toolbar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(pathInput, LinearLayout.LayoutParams(0, dp(42), 1f))
        }
        val body = EditText(activity).apply {
            setTextColor(TEXT); setHintTextColor(MUTED); textSize = 12f; typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.START; setHorizontallyScrolling(true); isVerticalScrollBarEnabled = true
            setPadding(dp(10), dp(10), dp(10), dp(10)); setBackgroundColor(BG)
            hint = "Open a text file from Files or enter a RiftFS path above."
        }
        val status = TextView(activity).apply {
            setTextColor(MUTED); textSize = 10f; setPadding(dp(8), dp(6), dp(8), dp(6))
            text = "Native editor · max 1 MiB"
        }
        val state = EditorState(root, pathInput, body, status)
        toolbar.addView(actionButton("Load") { loadEditor(state, pathInput.text.toString()) }, LinearLayout.LayoutParams(dp(72), dp(42)))
        toolbar.addView(actionButton("Save") { saveEditor(state) }, LinearLayout.LayoutParams(dp(72), dp(42)))
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)))
        editor = state
        desktop.attachContent("editor", root)
        if (rawPath.isNotBlank() && rawPath != "/D:/Workspace") loadEditor(state, rawPath)
        return state()
    }

    private fun loadEditor(state: EditorState, rawPath: String) {
        state.status.text = "Loading…"
        runNativeTask(
            timeoutMs = EDITOR_IO_TIMEOUT_MS,
            work = {
                val display = RiftVolumePaths.normalizeDisplay(rawPath)
                val (text, size, name) = if (isAndroidPath(display)) {
                    val document = resolveDocument(display)
                    require(document.isFile) { "file not found: $display" }
                    val bytes = readDocumentBytes(document, display)
                    Triple(bytes.toString(Charsets.UTF_8), bytes.size.toLong(), document.name ?: display.substringAfterLast('/'))
                } else {
                    val file = resolveFile(display)
                    require(file.isFile) { "file not found: $display" }
                    require(file.length() <= MAX_EDITOR_BYTES) { "file exceeds native editor limit" }
                    Triple(file.readText(Charsets.UTF_8), file.length(), file.name)
                }
                EditorLoadResult(display, text, size, name)
            },
            complete = { outcome ->
                if (editor !== state) return@runNativeTask
                val value = outcome.value
                if (value == null) {
                    state.status.text = "Load error: ${outcome.error?.message ?: "unknown error"}"
                } else {
                    state.displayPath = value.display
                    state.pathInput.setText(value.display)
                    state.body.setText(value.text)
                    state.body.setSelection(0)
                    state.status.text = "Loaded ${value.display} · ${humanBytes(value.size)}"
                    desktop.handle("desktop.window.title", JSONObject().put("id", "editor").put("title", "Editor — ${value.name}"))
                }
            }
        )
    }

    private fun saveEditor(state: EditorState) {
        val rawDisplay = state.pathInput.text.toString()
        val bytes = state.body.text.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_EDITOR_BYTES) {
            state.status.text = "Save error: editor content exceeds native editor limit"
            return
        }
        state.status.text = "Saving…"
        runNativeTask(
            timeoutMs = EDITOR_IO_TIMEOUT_MS,
            work = {
                val display = RiftVolumePaths.normalizeDisplay(rawDisplay)
                require(display != "/" && !RiftVolumePaths.isVolumeRoot(display) && display != ANDROID_FILES_ROOT) { "A file path is required" }
                var patchSession: RiftPatchSessions.Handle? = null
                try {
                    if (!isAndroidPath(display)) {
                        patchSession = RiftPatchSessions.begin(
                            activity,
                            origin = "native-editor",
                            operation = "save",
                            intent = "editor-save",
                            requestId = null,
                            rawPaths = listOf(display)
                        )
                    }
                    if (isAndroidPath(display)) {
                        val document = resolveDocument(display)
                        require(document.isFile && document.canWrite()) { "Android file is not writable: $display" }
                        val original = readDocumentBytes(document, display)
                        try {
                            writeDocumentBytes(document, display, bytes)
                            val published = readDocumentBytes(document, display)
                            require(published.contentEquals(bytes)) { "Android provider write verification failed: $display" }
                        } catch (error: Throwable) {
                            val rollback = runCatching {
                                writeDocumentBytes(document, display, original)
                                val restored = readDocumentBytes(document, display)
                                require(restored.contentEquals(original)) { "Android rollback verification failed: $display" }
                            }
                            if (rollback.isFailure) {
                                throw IllegalStateException(
                                    "Android save failed and rollback was incomplete for $display: ${rollback.exceptionOrNull()?.message}",
                                    error
                                )
                            }
                            throw error
                        }
                    } else {
                        val file = resolveFile(display)
                        file.parentFile?.mkdirs()
                        val temp = File(file.parentFile, ".${file.name}.rift-write-${System.nanoTime()}")
                        val backup = File(file.parentFile, ".${file.name}.rift-backup-${System.nanoTime()}")
                        temp.writeBytes(bytes)
                        var backedUp = false
                        try {
                            if (file.exists()) {
                                require(file.isFile) { "Editor target is not a file: $display" }
                                require(file.renameTo(backup)) { "Could not stage existing file for replacement: $display" }
                                backedUp = true
                            }
                            require(temp.renameTo(file)) { "Atomic editor publish failed: $display" }
                            if (backedUp) backup.delete()
                        } catch (error: Throwable) {
                            temp.delete()
                            if (backedUp && !file.exists() && !backup.renameTo(file)) {
                                throw IllegalStateException(
                                    "Editor save failed and previous file could not be restored: ${backup.absolutePath}",
                                    error
                                )
                            }
                            throw error
                        }
                    }
                    patchSession?.let { runCatching { RiftPatchSessions.commit(activity, it) } }
                    patchSession = null
                    EditorSaveResult(display, bytes.size.toLong())
                } catch (error: Throwable) {
                    patchSession?.let(RiftPatchSessions::abort)
                    throw error
                }
            },
            complete = { outcome ->
                if (editor !== state) return@runNativeTask
                val value = outcome.value
                if (value == null) {
                    state.status.text = "Save error: ${outcome.error?.message ?: "unknown error"}"
                } else {
                    state.displayPath = value.display
                    state.status.text = "Saved ${value.display} · ${humanBytes(value.size)}"
                }
            }
        )
    }


    private fun openDevLab(): JSONObject {
        openWindow("devlab", "Dev Lab", "ANDROID NATIVE DEV LAB")
        devLab?.let {
            desktop.attachContent("devlab", it.root)
            refreshDevLab(it)
            return state()
        }

        val root = column().apply { setPadding(dp(10), dp(8), dp(10), dp(8)) }
        val pathInput = EditText(activity).apply {
            setTextColor(TEXT); setHintTextColor(MUTED); textSize = 11f; typeface = Typeface.MONOSPACE
            setSingleLine(true); setText("styles.css"); hint = "project-relative source path"
            setBackgroundColor(PANEL); setPadding(dp(8), 0, dp(8), 0)
        }
        val body = EditText(activity).apply {
            setTextColor(TEXT); setHintTextColor(MUTED); textSize = 11f; typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.START; setHorizontallyScrolling(true); isVerticalScrollBarEnabled = true
            setBackgroundColor(BG); hint = "Load a project source file, edit it, then stage it."
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val status = TextView(activity).apply { setTextColor(ACCENT); textSize = 10f; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        val output = TextView(activity).apply {
            setTextColor(MUTED); textSize = 10f; typeface = Typeface.MONOSPACE
            setTextIsSelectable(true); setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val controls = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val state = DevLabState(root, pathInput, body, status, output)
        controls.addView(actionButton("Load") { devLabLoad(state) }, LinearLayout.LayoutParams(0, dp(42), 1f))
        controls.addView(actionButton("Stage") { devLabStage(state) }, LinearLayout.LayoutParams(0, dp(42), 1f))
        controls.addView(actionButton("Snapshot") { devLabSnapshot(state) }, LinearLayout.LayoutParams(0, dp(42), 1f))
        controls.addView(actionButton("Publish") { devLabPublish(state) }, LinearLayout.LayoutParams(0, dp(42), 1f))
        controls.addView(actionButton("Reset") { devLabReset(state) }, LinearLayout.LayoutParams(0, dp(42), 1f))
        root.addView(pathInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
        root.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)))
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(ScrollView(activity).apply {
            addView(output, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)))
        devLab = state
        desktop.attachContent("devlab", root)
        refreshDevLab(state)
        devLabLoad(state)
        return state()
    }

    private fun refreshDevLab(state: DevLabState) {
        runCatching {
            val value = RiftNativeDevLab.execute(activity, JSONObject().put("action", "status"))
            state.status.text = "Native Dev Lab · ${value.optInt("staged")} staged · ${value.optInt("snapshots")} snapshots · web execution → RiftBrowser"
        }.onFailure { state.status.text = "Dev Lab status error: ${it.message}" }
    }

    private fun devLabLoad(state: DevLabState) {
        runCatching {
            val value = RiftNativeDevLab.execute(activity, JSONObject().put("action", "load").put("path", state.pathInput.text.toString()))
            state.pathInput.setText(value.optString("path"))
            state.body.setText(value.optString("content"))
            state.body.setSelection(0)
            state.output.text = value.toString(2)
            refreshDevLab(state)
        }.onFailure { state.output.text = "Load error: ${it.message}" }
    }

    private fun devLabStage(state: DevLabState) {
        runCatching {
            val value = RiftNativeDevLab.execute(
                activity,
                JSONObject().put("action", "stage").put("path", state.pathInput.text.toString())
                    .put("text", state.body.text.toString()).put("reason", "Native Dev Lab UI")
            )
            state.output.text = value.toString(2)
            refreshDevLab(state)
        }.onFailure { state.output.text = "Stage error: ${it.message}" }
    }

    private fun devLabSnapshot(state: DevLabState) {
        runCatching {
            val value = RiftNativeDevLab.execute(activity, JSONObject().put("action", "snapshot").put("note", "Native Dev Lab UI snapshot"))
            state.output.text = value.toString(2)
            refreshDevLab(state)
        }.onFailure { state.output.text = "Snapshot error: ${it.message}" }
    }

    private fun devLabPublish(state: DevLabState) {
        runCatching {
            val preview = RiftNativeDevLab.execute(activity, JSONObject().put("action", "preview").put("snapshotId", "latest"))
            require(preview.optBoolean("safeToPublish")) { "Snapshot has ${preview.optJSONArray("conflicts")?.length() ?: 0} conflict(s)" }
            val value = RiftNativeDevLab.execute(activity, JSONObject().put("action", "publish").put("snapshotId", "latest"))
            state.output.text = JSONObject().put("preview", preview).put("result", value).toString(2)
            refreshDevLab(state)
        }.onFailure { state.output.text = "Publish aborted: ${it.message}" }
    }

    private fun devLabReset(state: DevLabState) {
        runCatching {
            val value = RiftNativeDevLab.execute(activity, JSONObject().put("action", "reset"))
            state.output.text = value.toString(2)
            refreshDevLab(state)
        }.onFailure { state.output.text = "Reset error: ${it.message}" }
    }

    private fun openWorkspaceRecords(): JSONObject {
        openWindow("workspace-live", "Workspace Records", "PRIVATE LOCAL WORKSPACE RECORDS")
        workspaceRecords?.let {
            desktop.attachContent("workspace-live", it.root)
            refreshWorkspaceRecords(it)
            return state()
        }

        val root = column().apply { setPadding(dp(10), dp(8), dp(10), dp(8)) }
        val toolbar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val status = TextView(activity).apply {
            setTextColor(ACCENT)
            textSize = 10f
            setPadding(dp(6), dp(4), dp(6), dp(4))
            text = "Loading local workspace records…"
        }
        val output = TextView(activity).apply {
            setTextColor(TEXT)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val viewState = WorkspaceRecordsState(root, status, output)
        toolbar.addView(actionButton("Refresh") { refreshWorkspaceRecords(viewState) }, LinearLayout.LayoutParams(dp(96), dp(42)))
        toolbar.addView(status, LinearLayout.LayoutParams(0, dp(42), 1f))
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
        root.addView(ScrollView(activity).apply {
            addView(output, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        workspaceRecords = viewState
        desktop.attachContent("workspace-live", root)
        refreshWorkspaceRecords(viewState)
        return state()
    }

    private fun <T> runNativeTask(
        timeoutMs: Long,
        work: () -> T,
        complete: (NativeTaskOutcome<T>) -> Unit
    ) {
        RiftBoundedAsync.submit(
            executor = settingsExecutor,
            watchdog = settingsWatchdog,
            timeoutMs = timeoutMs,
            timeoutValue = { NativeTaskOutcome<T>(error = IllegalStateException("Native I/O task timed out after ${timeoutMs}ms")) },
            failureValue = { error -> NativeTaskOutcome<T>(error = error) },
            work = {
                RiftDeadline.check("native I/O task")
                NativeTaskOutcome(value = work())
            },
            reply = { outcome ->
                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed) complete(outcome)
                }
            }
        )
    }

    private fun runSettingsTask(block: () -> Unit) {
        RiftBoundedAsync.submit<String?>(
            executor = settingsExecutor,
            watchdog = settingsWatchdog,
            timeoutMs = SETTINGS_TASK_TIMEOUT_MS,
            timeoutValue = { "Native Settings task timed out after ${SETTINGS_TASK_TIMEOUT_MS}ms" },
            failureValue = { error -> error.message ?: error.javaClass.simpleName },
            work = {
                RiftDeadline.check("native Settings task")
                block()
                null
            },
            reply = { error ->
                if (error != null) android.util.Log.w("RiftNativeWorkspaceApps", error)
            }
        )
    }

    private fun refreshWorkspaceRecords(viewState: WorkspaceRecordsState) {
        viewState.status.text = "Refreshing local records…"
        runSettingsTask {
            val result = runCatching {
                RiftWorkspaceRecords.get(activity).query(
                    JSONObject().put("limit", 80).put("includeDiff", false)
                )
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = { value ->
                        val summary = value.optJSONObject("summary") ?: JSONObject()
                        val checkpoint = value.optJSONObject("checkpoint") ?: JSONObject()
                        val lines = ArrayList<String>()
                        lines += "WORKSPACE RECORDS · LOCAL ONLY"
                        lines += "changed files: ${summary.optInt("changedFiles")} · records: ${summary.optInt("records")} · returned: ${summary.optInt("returnedRecords")}"
                        val checkpointAt = checkpoint.optLong("at")
                        if (checkpointAt > 0L) {
                            val formatted = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(checkpointAt))
                            lines += "baseline: $formatted · ${checkpoint.optString("reason", "local")}"
                        }
                        lines += ""
                        lines += "CHANGED FILES"
                        val files = value.optJSONArray("files") ?: JSONArray()
                        if (files.length() == 0) lines += "(none)"
                        for (index in 0 until files.length()) {
                            val row = files.optJSONObject(index) ?: continue
                            lines += "${row.optString("status").uppercase()}  ${row.optString("path")}"
                        }
                        lines += ""
                        lines += "RECENT EVENTS"
                        val records = value.optJSONArray("records") ?: JSONArray()
                        if (records.length() == 0) lines += "(none)"
                        for (index in 0 until records.length()) {
                            val row = records.optJSONObject(index) ?: continue
                            val at = row.optLong("at", row.optLong("timestamp"))
                            val time = if (at > 0L) java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(at)) else "time-unknown"
                            val action = row.optString("action", row.optString("type", row.optString("event", "change")))
                            lines += "$time  $action  ${row.optString("path")}"
                        }
                        viewState.output.text = lines.joinToString("\n")
                        viewState.status.text = "${summary.optInt("changedFiles")} changed · ${summary.optInt("records")} recorded"
                    },
                    onFailure = { error ->
                        viewState.status.text = "Workspace Records error"
                        viewState.output.text = error.message ?: error.javaClass.simpleName
                    }
                )
            }
        }
    }

    private fun openSettings(): JSONObject {
        openWindow("settings", "Settings", "ANDROID NATIVE SETTINGS")
        settings?.let { desktop.attachContent("settings", it); return state() }

        val root = column().apply { setPadding(dp(14), dp(14), dp(14), dp(14)) }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        content.addView(label("RiftOS Native Settings", TEXT, 18f, true))
        content.addView(label("Build ${BuildConfig.VERSION_NAME}", TEXT))
        content.addView(label("Source ${BuildConfig.RIFT_SOURCE_SHA}", MUTED))
        content.addView(label("Desktop / Files / Editor / Dev Lab / Settings: Android-native", ACCENT))
        content.addView(label("RiftBrowser: sole approved WebView owner after migration", TEXT))
        content.addView(label("GitHub authentication", TEXT, 15f, true))

        val gitStatus = TextView(activity).apply {
            setTextColor(MUTED)
            textSize = 11f
            setPadding(dp(4), dp(6), dp(4), dp(8))
            text = "Checking GitHub authentication…"
        }
        content.addView(gitStatus)

        val tokenInput = EditText(activity).apply {
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            textSize = 11f
            hint = "GitHub token"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setBackgroundColor(PANEL)
            setPadding(dp(8), 0, dp(8), 0)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        content.addView(tokenInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))

        val gitButtons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        gitButtons.addView(actionButton("Save + Verify") {
            val token = tokenInput.text.toString().trim()
            tokenInput.setText("")
            if (token.isBlank()) {
                gitStatus.text = "Enter a GitHub token first."
            } else {
                gitStatus.text = "Verifying GitHub credential…"
                runSettingsTask {
                    val result = runCatching { nativeGit.storeToken(token) }
                    activity.runOnUiThread {
                        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                        gitStatus.text = result.fold(
                            onSuccess = { value -> "Authenticated as ${value.optString("login")} · stored with Android Keystore" },
                            onFailure = { error -> "GitHub authentication failed: ${error.message}" }
                        )
                    }
                }
            }
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        gitButtons.addView(actionButton("Clear") {
            tokenInput.setText("")
            gitStatus.text = "Clearing GitHub credential…"
            runSettingsTask {
                val result = runCatching { nativeGit.clearToken() }
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    gitStatus.text = result.fold(
                        onSuccess = { value -> if (value.optBoolean("cleared")) "GitHub credential cleared." else "No stored GitHub credential." },
                        onFailure = { error -> "GitHub credential clear failed: ${error.message}" }
                    )
                }
            }
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        gitButtons.addView(actionButton("Refresh") { refreshGitSettings(gitStatus) }, LinearLayout.LayoutParams(0, dp(44), 1f))
        content.addView(gitButtons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        content.addView(label(
            "Security: the GitHub token is never echoed to RiftShell, QuickJS, JavaScript, or logs. Plaintext is not stored in preferences; Git reads the encrypted value only through RiftSecretStore.",
            MUTED
        ))

        content.addView(label("RiftLLM Dev API pairing", TEXT, 15f, true))
        val llmStatus = TextView(activity).apply {
            setTextColor(MUTED)
            textSize = 11f
            setPadding(dp(4), dp(6), dp(4), dp(8))
            text = "Checking RiftLLM pairing…"
        }
        content.addView(llmStatus)
        val llmTokenInput = EditText(activity).apply {
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            textSize = 11f
            hint = "64-character RiftLLM Dev Lab token"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setBackgroundColor(PANEL)
            setPadding(dp(8), 0, dp(8), 0)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        content.addView(llmTokenInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        val llmButtons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        llmButtons.addView(actionButton("Pair + Verify") {
            val token = llmTokenInput.text.toString().trim()
            llmTokenInput.setText("")
            if (token.isBlank()) {
                llmStatus.text = "Enter the 64-character token shown by RiftLLM Dev Lab."
            } else {
                llmStatus.text = "Verifying RiftLLM provider and token…"
                runSettingsTask {
                    val result = runCatching { riftLlm.execute(JSONObject().put("op", "pair").put("token", token)) as JSONObject }
                    activity.runOnUiThread {
                        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                        llmStatus.text = result.fold(
                            onSuccess = { "RiftLLM paired · provider verified · token stored with Android Keystore" },
                            onFailure = { error -> "RiftLLM pairing failed: ${error.message}" }
                        )
                    }
                }
            }
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        llmButtons.addView(actionButton("Unpair") {
            llmTokenInput.setText("")
            runSettingsTask {
                val result = runCatching { riftLlm.execute(JSONObject().put("op", "unpair")) as JSONObject }
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    llmStatus.text = result.fold(
                        onSuccess = { "RiftLLM pairing cleared." },
                        onFailure = { error -> "RiftLLM unpair failed: ${error.message}" }
                    )
                }
            }
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        llmButtons.addView(actionButton("Refresh") { refreshRiftLlmSettings(llmStatus) }, LinearLayout.LayoutParams(0, dp(44), 1f))
        content.addView(llmButtons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        content.addView(label(
            "Security: RiftLLM pairing is accepted only here, validated against the fixed com.riftllm.app provider, and stored through RiftSecretStore. RiftShell intentionally rejects pairing tokens.",
            MUTED
        ))

        content.addView(label("Gate 6D.3 remains blocked until native migration + validation completes.", MUTED))

        root.addView(ScrollView(activity).apply {
            isFillViewport = true
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        settings = root
        desktop.attachContent("settings", root)
        refreshGitSettings(gitStatus)
        refreshRiftLlmSettings(llmStatus)
        return state()
    }

    private fun refreshGitSettings(status: TextView) {
        status.text = "Checking GitHub authentication…"
        runSettingsTask {
            val result = runCatching { nativeGit.authStatus() }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                status.text = result.fold(
                    onSuccess = { value ->
                        if (value.optBoolean("authenticated")) {
                            "Authenticated as ${value.optString("login")} · Android Keystore"
                        } else {
                            val detail = value.optString("error").takeIf { it.isNotBlank() }
                            if (detail == null) "GitHub authentication is not configured."
                            else "Stored GitHub credential could not be verified: $detail"
                        }
                    },
                    onFailure = { error -> "GitHub authentication check failed: ${error.message}" }
                )
            }
        }
    }

    private fun refreshRiftLlmSettings(status: TextView) {
        status.text = "Checking RiftLLM pairing…"
        runSettingsTask {
            val result = runCatching { riftLlm.execute(JSONObject().put("op", "status")) as JSONObject }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                status.text = result.fold(
                    onSuccess = { value ->
                        when {
                            !value.optBoolean("installed") -> "RiftLLM provider is not installed or visible."
                            !value.optBoolean("paired") -> "RiftLLM is installed but not paired."
                            value.optBoolean("apiReachable") -> "RiftLLM paired · Dev API reachable · Android Keystore"
                            else -> "RiftLLM token is stored but Dev API is unreachable: ${value.optString("error", "unknown error")}"
                        }
                    },
                    onFailure = { error -> "RiftLLM status check failed: ${error.message}" }
                )
            }
        }
    }

    private fun launchAndroidMountPicker() {
        runCatching {
            activity.startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                },
                ANDROID_FILES_REQUEST
            )
        }.onFailure { Toast.makeText(activity, "Could not open Android folder picker: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    private fun mountedAndroidRoots(): List<AndroidMount> {
        val granted = activity.contentResolver.persistedUriPermissions.associateBy { it.uri }
        return mountPrefs.all.keys.filter { it.startsWith("mount:") }.sorted().mapNotNull { key ->
            val id = key.removePrefix("mount:")
            val raw = mountPrefs.getString(key, null) ?: return@mapNotNull null
            val record = runCatching { JSONObject(raw) }.getOrNull() ?: return@mapNotNull null
            val uri = runCatching { Uri.parse(record.getString("uri")) }.getOrNull() ?: return@mapNotNull null
            val permission = granted[uri] ?: return@mapNotNull null
            if (!permission.isReadPermission || !permission.isWritePermission) return@mapNotNull null
            val root = DocumentFile.fromTreeUri(activity, uri) ?: return@mapNotNull null
            if (!root.isDirectory) return@mapNotNull null
            AndroidMount(id, record.optString("name", root.name ?: "Android Files"), uri, root)
        }
    }

    private fun mountIdFromDisplay(rawPath: String): String? {
        val parts = RiftVolumePaths.normalizeDisplay(rawPath).split('/').filter { it.isNotBlank() }
        return if (parts.firstOrNull()?.equals("Android", ignoreCase = true) == true) parts.getOrNull(1) else null
    }

    private fun isAndroidPath(rawPath: String): Boolean {
        val normalized = RiftVolumePaths.normalizeDisplay(rawPath)
        return normalized.equals(ANDROID_FILES_ROOT, ignoreCase = true) || normalized.startsWith("$ANDROID_FILES_ROOT/", ignoreCase = true)
    }

    private fun isAndroidMountRoot(rawPath: String): Boolean {
        val parts = RiftVolumePaths.normalizeDisplay(rawPath).split('/').filter { it.isNotBlank() }
        return parts.size == 2 && parts[0].equals("Android", ignoreCase = true)
    }

    private fun resolveDocument(rawPath: String): DocumentFile {
        val normalized = RiftVolumePaths.normalizeDisplay(rawPath)
        val parts = normalized.split('/').filter { it.isNotBlank() }
        require(parts.firstOrNull()?.equals("Android", ignoreCase = true) == true && parts.size >= 2) {
            "Not an Android mount path: $normalized"
        }
        val mount = mountedAndroidRoots().firstOrNull { it.id == parts[1] }
            ?: throw IllegalArgumentException("Android mount is unavailable: ${parts[1]}")
        var current = mount.root
        for (segment in parts.drop(2)) current = current.findFile(segment)
            ?: throw IllegalArgumentException("Android file not found: $normalized")
        return current
    }

    private fun unmountAndroid(rawPath: String) {
        val id = mountIdFromDisplay(rawPath) ?: return
        val key = "mount:$id"
        val record = runCatching { JSONObject(mountPrefs.getString(key, null) ?: "{}") }.getOrNull()
        record?.optString("uri")?.takeIf { it.isNotBlank() }?.let { raw ->
            activity.contentResolver.releasePersistableUriPermission(
                Uri.parse(raw),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        mountPrefs.edit().remove(key).apply()
    }

    private fun readDocumentBytes(document: DocumentFile, display: String): ByteArray {
        val input = activity.contentResolver.openInputStream(document.uri)
            ?: throw IllegalStateException("Android provider could not open $display")
        return input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                RiftDeadline.check("native editor provider read")
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= MAX_EDITOR_BYTES) { "file exceeds native editor limit" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun writeDocumentBytes(document: DocumentFile, display: String, bytes: ByteArray) {
        var lastError: Throwable? = null
        for (mode in listOf("rwt", "wt", "w")) {
            try {
                val output = activity.contentResolver.openOutputStream(document.uri, mode) ?: continue
                output.use { it.write(bytes); it.flush() }
                return
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw IllegalStateException("Android provider could not write $display", lastError)
    }

    private fun listDisplay(rawPath: String): List<DisplayEntry> {
        val path = RiftVolumePaths.normalizeDisplay(rawPath)
        RiftDeadline.check("native Files listing")
        if (path.equals(ANDROID_FILES_ROOT, ignoreCase = true)) {
            return mountedAndroidRoots().map { mount ->
                DisplayEntry("$ANDROID_FILES_ROOT/${mount.id}", true, 0L, mount.name)
            }
        }
        if (isAndroidPath(path)) {
            val directory = resolveDocument(path)
            require(directory.isDirectory) { "not a directory: $path" }
            RiftDeadline.check("native Files provider listing")
            val children = directory.listFiles()
            require(children.size <= MAX_FILES_ROWS) { "directory exceeds native Files row limit" }
            return children
                .mapNotNull { document ->
                    RiftDeadline.check("native Files provider listing")
                    val name = document.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val isDirectory = document.isDirectory
                    DisplayEntry(
                        RiftVolumePaths.normalizeDisplay("$path/$name"),
                        isDirectory,
                        if (isDirectory) 0L else document.length(),
                        name
                    )
                }
                .sortedWith(compareBy<DisplayEntry>({ !it.isDirectory }, { (it.label ?: "").lowercase() }))
        }
        if (RiftVolumePaths.isVolumeRoot(path)) {
            val volume = RiftVolumePaths.volume(path) ?: return emptyList()
            val entries = ArrayList<DisplayEntry>()
            for (name in volume.roots.keys) {
                RiftDeadline.check("native Files volume listing")
                val child = RiftVolumePaths.normalizeDisplay("$path/$name")
                entries += DisplayEntry(child, true, 0L)
            }
            val backing = resolveFile(path)
            val backingRows = backing.listFiles().orEmpty()
            require(entries.size + backingRows.size <= MAX_FILES_ROWS) { "directory exceeds native Files row limit" }
            backingRows.sortedBy { it.name.lowercase() }.forEach { file ->
                RiftDeadline.check("native Files volume listing")
                val child = RiftVolumePaths.normalizeDisplay("$path/${file.name}")
                if (entries.none { it.display.equals(child, ignoreCase = true) }) {
                    entries += DisplayEntry(child, file.isDirectory, if (file.isFile) file.length() else 0L)
                }
            }
            return entries
        }
        val directory = resolveFile(path)
        require(directory.isDirectory) { "not a directory: $path" }
        val children = directory.listFiles().orEmpty()
        require(children.size <= MAX_FILES_ROWS) { "directory exceeds native Files row limit" }
        return children.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            .map { file ->
                RiftDeadline.check("native Files local listing")
                DisplayEntry(
                    RiftVolumePaths.normalizeDisplay("$path/${file.name}"),
                    file.isDirectory,
                    if (file.isFile) file.length() else 0L
                )
            }
    }

    private fun normalizeExistingDirectory(rawPath: String): String {
        val candidate = runCatching { RiftVolumePaths.normalizeDisplay(rawPath) }.getOrDefault("/D:/Workspace")
        if (candidate.equals(ANDROID_FILES_ROOT, ignoreCase = true)) return ANDROID_FILES_ROOT
        if (isAndroidPath(candidate)) return candidate
        return if (runCatching { resolveFile(candidate).isDirectory || RiftVolumePaths.isVolumeRoot(candidate) }.getOrDefault(false)) candidate else "/D:/Workspace"
    }

    private fun resolveFile(displayPath: String): File {
        val normalized = RiftVolumePaths.normalizeDisplay(displayPath)
        val relative = when {
            normalized == "/" -> ""
            normalized.startsWith("/C:", true) || normalized.startsWith("/D:", true) -> RiftVolumePaths.resolveRelative(normalized)
            else -> normalized.trimStart('/')
        }
        val target = if (relative.isBlank()) riftRoot else File(riftRoot, relative).canonicalFile
        require(target == riftRoot || target.path.startsWith(riftRoot.path + File.separator)) { "Path escaped RiftFS" }
        return target
    }

    private fun parentDisplay(displayPath: String): String {
        val normalized = RiftVolumePaths.normalizeDisplay(displayPath)
        val parts = normalized.split('/').filter { it.isNotBlank() }
        if (parts.size <= 1) return normalized
        return "/" + parts.dropLast(1).joinToString("/")
    }

    private fun openWindow(id: String, title: String, kicker: String) {
        desktop.handle("desktop.window.open", JSONObject().put("id", id).put("title", title).put("kicker", kicker))
    }

    private fun column(): LinearLayout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }

    private fun actionButton(value: String, action: () -> Unit): Button = Button(activity).apply {
        text = value; isAllCaps = false; textSize = 11f; setTextColor(TEXT); setOnClickListener { action() }
    }

    private fun label(value: String, color: Int, size: Float = 12f, bold: Boolean = false): TextView = TextView(activity).apply {
        text = value; setTextColor(color); textSize = size
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(4), dp(6), dp(4), dp(6))
    }

    private fun humanBytes(value: Long): String = when {
        value < 1024 -> "$value B"
        value < 1024 * 1024 -> "${value / 1024} KiB"
        else -> "${value / (1024 * 1024)} MiB"
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).roundToInt()
}
