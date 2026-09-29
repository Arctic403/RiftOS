package com.codynex.editorapp

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.codynex.editor.ArtifactRef
import com.codynex.editor.CodynexEditorController
import com.codynex.editor.EditorDiagnostic
import com.codynex.editor.EditorState
import com.codynex.editor.SearchHit
import com.codynex.editor.WorkspaceEntry
import java.io.File

/**
 * TEMPORARY LIVE-PROOF SCAFFOLDING ONLY.
 * This Android/Kotlin editor UI MUST be replaced by native Codynex/.cx.
 * Missing native editor capabilities must be added/proved in Codynex, not retained here.
 */
class MainActivity : Activity() {
    private lateinit var controller: CodynexEditorController
    private lateinit var workspacePort: FileWorkspacePort
    private lateinit var toolchain: Source0SelfHostToolchainPort

    private lateinit var statusView: TextView
    private lateinit var candidateView: TextView
    private lateinit var diagnosticsView: TextView
    private lateinit var editorView: EditText
    private lateinit var searchInput: EditText

    private lateinit var projectList: ListView
    private lateinit var projectAdapter: ArrayAdapter<String>
    private lateinit var searchList: ListView
    private lateinit var searchAdapter: ArrayAdapter<String>
    private lateinit var tabsRow: LinearLayout

    private lateinit var topInfoContainer: LinearLayout
    private lateinit var projectContainer: LinearLayout
    private lateinit var diagnosticsContainer: LinearLayout

    private var projectEntries: List<WorkspaceEntry> = emptyList()
    private var searchHits: List<SearchHit> = emptyList()
    private var selectedEntryPath: String? = null
    private var rendering = false
    private var fullScreenEditor = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val artifacts = BootstrapArtifactLoader.load(this)

            val workspaceRoot =
                File(filesDir, "codynex-workspace").apply {
                    mkdirs()
                }

            workspacePort = FileWorkspacePort(workspaceRoot)

            val starterFile = File(workspaceRoot, "main.cx")
            if (!starterFile.exists()) {
                workspacePort.createTextFile(
                    starterFile.canonicalPath,
                    artifacts.starterSource
                )
            }

            val notepadFile = File(workspaceRoot, "notepad.cx")
            if (!notepadFile.exists()) {
                workspacePort.createTextFile(
                    notepadFile.canonicalPath,
                    artifacts.notepadSource
                )
            }

            toolchain =
                Source0SelfHostToolchainPort(
                    context = this,
                    artifacts = artifacts,
                    candidateDirectory =
                        File(filesDir, "editor-candidates")
                )

            controller =
                CodynexEditorController(
                    workspace = workspacePort,
                    compiler = toolchain,
                    preview = toolchain
                )

            buildUi()

            controller.openWorkspace(workspacePort.rootPath())
            var initial = controller.openFile(notepadFile.canonicalPath)
            if (notepadFile.name.endsWith(".cx", ignoreCase = true)) {
                initial = controller.setProjectEntry(notepadFile.canonicalPath)
            }
            render(initial)
        } catch (error: Throwable) {
            setContentView(
                TextView(this).apply {
                    text =
                        "Codynex .cx editor failed to start:\n\n" +
                            error.toString()
                    setTextIsSelectable(true)
                    setPadding(32, 32, 32, 32)
                }
            )
        }
    }

    private fun buildUi() {
        statusView = TextView(this).apply {
            text = "Starting editor..."
            setPadding(16, 8, 16, 4)
            setTextIsSelectable(true)
        }

        candidateView = TextView(this).apply {
            text = "Candidate: NONE"
            setPadding(16, 0, 16, 8)
            setTextIsSelectable(true)
        }

        searchInput = EditText(this).apply {
            hint = "Search project"
            isSingleLine = true
            setPadding(12, 4, 12, 4)
        }

        projectAdapter =
            ArrayAdapter(
                this,
                android.R.layout.simple_list_item_activated_1,
                mutableListOf()
            )

        projectList = ListView(this).apply {
            adapter = projectAdapter
            choiceMode = ListView.CHOICE_MODE_SINGLE
            onItemClickListener =
                android.widget.AdapterView.OnItemClickListener {
                        _,
                        _,
                        position,
                        _ ->
                    val entry = projectEntries.getOrNull(position)
                        ?: return@OnItemClickListener

                    selectedEntryPath = entry.path
                    setItemChecked(position, true)

                    if (!entry.directory) {
                        render(controller.openFile(entry.path))
                    }
                }
        }

        searchAdapter =
            ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                mutableListOf()
            )

        searchList = ListView(this).apply {
            adapter = searchAdapter
            visibility = View.GONE
            onItemClickListener =
                android.widget.AdapterView.OnItemClickListener {
                        _,
                        _,
                        position,
                        _ ->
                    val hit = searchHits.getOrNull(position)
                        ?: return@OnItemClickListener
                    render(controller.openFile(hit.path))
                    jumpTo(hit.line, hit.column)
                }
        }

        tabsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        editorView = EditText(this).apply {
            gravity = Gravity.TOP or Gravity.START
            typeface = Typeface.MONOSPACE
            textSize = 14f
            isSingleLine = false
            setHorizontallyScrolling(true)
            inputType =
                InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(16, 12, 16, 12)

            addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(
                        s: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int
                    ) = Unit

                    override fun onTextChanged(
                        s: CharSequence?,
                        start: Int,
                        before: Int,
                        count: Int
                    ) {
                        if (!rendering && controller.snapshot().document != null) {
                            renderStatusOnly(
                                controller.editText(s?.toString().orEmpty())
                            )
                            renderTabs(controller.snapshot())
                        }
                    }

                    override fun afterTextChanged(
                        s: Editable?
                    ) = Unit
                }
            )
        }

        diagnosticsView = TextView(this).apply {
            text = "Diagnostics: none"
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(16, 8, 16, 12)
        }

        val searchRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL

                addView(
                    searchInput,
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                )

                addView(
                    actionButton("Search") {
                        val query = searchInput.text.toString()
                        runAction("search") {
                            controller.search(query)
                        }
                    }
                )

                addView(
                    actionButton("Clear") {
                        searchInput.setText("")
                        render(controller.clearSearch())
                    }
                )
            }

        val projectActionsA =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    actionButton("New File") { promptNewFile() },
                    weightedButtonParams()
                )
                addView(
                    actionButton("New Folder") { promptNewFolder() },
                    weightedButtonParams()
                )
                addView(
                    actionButton("Rename") { promptRename() },
                    weightedButtonParams()
                )
            }

        val projectActionsB =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    actionButton("Delete") { confirmDelete() },
                    weightedButtonParams()
                )
                addView(
                    actionButton("Set Entry") { setSelectedEntry() },
                    weightedButtonParams()
                )
                addView(
                    actionButton("Refresh") {
                        render(controller.refreshWorkspace())
                    },
                    weightedButtonParams()
                )
            }

        projectContainer =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(searchRow)
                addView(
                    searchList,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(120)
                    )
                )
                addView(
                    TextView(this@MainActivity).apply {
                        text = "Project"
                        setPadding(16, 8, 16, 4)
                    }
                )
                addView(
                    projectList,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(190)
                    )
                )
                addView(projectActionsA)
                addView(projectActionsB)
            }

        topInfoContainer =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    TextView(this@MainActivity).apply {
                        text =
                            "Codynex — .cx project editor\n" +
                                "TEMP LIVE-PROOF SCAFFOLD — MUST BECOME NATIVE .cx\n" +
                                "External editor / frozen VM1 preview"
                        setPadding(16, 12, 16, 4)
                    }
                )
                addView(statusView)
                addView(candidateView)
            }

        val actionRowA =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    actionButton("Save") {
                        render(controller.save())
                    },
                    weightedButtonParams()
                )
                addView(
                    actionButton("Save All") {
                        render(controller.saveAll())
                    },
                    weightedButtonParams()
                )
                addView(
                    actionButton("Reload") {
                        render(controller.reload())
                    },
                    weightedButtonParams()
                )
                addView(
                    actionButton("Close") {
                        val path = controller.snapshot().activeDocumentPath
                            ?: return@actionButton
                        safeUiAction("close") {
                            controller.closeFile(path)
                        }
                    },
                    weightedButtonParams()
                )
            }

        val actionRowB =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    actionButton("Compile") {
                        ensureSavedThen("compile") {
                            controller.compile()
                        }
                    },
                    weightedButtonParams()
                )
                addView(
                    actionButton("Preview") {
                        ensureSavedThen(
                            "preview",
                            after = { state ->
                                showLivePreviewIfAvailable(state)
                            }
                        ) {
                            val state = controller.snapshot()
                            if (state.candidate == null) {
                                controller.compile()
                            }
                            controller.preview()
                        }
                    },
                    weightedButtonParams()
                )
                addView(
                    actionButton("Full") {
                        toggleFullScreenEditor()
                    },
                    weightedButtonParams()
                )
            }

        val tabsScroll =
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(
                    tabsRow,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }

        diagnosticsContainer =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(diagnosticsView)
            }

        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(topInfoContainer)
                addView(projectContainer)
                addView(tabsScroll)
                addView(actionRowA)
                addView(actionRowB)
                addView(
                    editorView,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                    )
                )
                addView(diagnosticsContainer)
            }

        setContentView(root)
    }

    private fun render(state: EditorState) {
        rendering = true
        try {
            statusView.text = state.status
            candidateView.text =
                buildString {
                    append("Candidate: ")
                    append(state.candidateState.name)
                    if (state.candidate != null) {
                        append("\n")
                        append(state.candidate.displayName)
                    }
                    append("\nEntry: ")
                    append(
                        state.projectEntryPath
                            ?.let(::relativePath)
                            ?: "not selected"
                    )
                    if (state.dirtyDocumentCount > 0) {
                        append(" | Dirty: ")
                        append(state.dirtyDocumentCount)
                    }
                }

            diagnosticsView.text =
                renderDiagnostics(state.diagnostics)

            projectEntries = state.entries
            projectAdapter.clear()
            projectAdapter.addAll(
                state.entries.map { entry ->
                    val depth =
                        entry.relativePath.count { it == '/' }
                    val indent = "  ".repeat(depth)
                    val marker =
                        if (entry.path == state.projectEntryPath) {
                            "*"
                        } else {
                            " "
                        }
                    val kind = if (entry.directory) "[D]" else "[F]"
                    "$marker$indent$kind ${entry.name}"
                }
            )
            projectAdapter.notifyDataSetChanged()

            selectedEntryPath?.let { selected ->
                val index =
                    projectEntries.indexOfFirst { it.path == selected }
                if (index >= 0) {
                    projectList.setItemChecked(index, true)
                } else {
                    selectedEntryPath = null
                    projectList.clearChoices()
                }
            }

            searchHits = state.searchHits
            searchAdapter.clear()
            searchAdapter.addAll(
                state.searchHits.map { hit ->
                    "${hit.relativePath}:${hit.line}:${hit.column}  ${hit.preview}"
                }
            )
            searchAdapter.notifyDataSetChanged()
            searchList.visibility =
                if (state.searchHits.isEmpty()) View.GONE else View.VISIBLE

            renderTabs(state)

            val document = state.document
            if (document == null) {
                if (editorView.text.isNotEmpty()) {
                    editorView.setText("")
                }
                editorView.isEnabled = false
            } else {
                editorView.isEnabled = true
                if (editorView.text.toString() != document.text) {
                    editorView.setText(document.text)
                    editorView.setSelection(editorView.text.length)
                }
            }
        } finally {
            rendering = false
        }
    }

    private fun renderTabs(state: EditorState) {
        tabsRow.removeAllViews()

        state.documents.forEach { document ->
            val label =
                (if (document.dirty) "*" else "") +
                    File(document.path).name

            tabsRow.addView(
                actionButton(label) {
                    render(controller.openFile(document.path))
                }.apply {
                    isAllCaps = false
                    isEnabled = document.path != state.activeDocumentPath
                }
            )
        }
    }

    private fun renderStatusOnly(state: EditorState) {
        statusView.text = state.status
        candidateView.text =
            buildString {
                append("Candidate: ")
                append(state.candidateState.name)
                append("\nEntry: ")
                append(
                    state.projectEntryPath
                        ?.let(::relativePath)
                        ?: "not selected"
                )
                if (state.dirtyDocumentCount > 0) {
                    append(" | Dirty: ")
                    append(state.dirtyDocumentCount)
                }
            }
        diagnosticsView.text =
            renderDiagnostics(state.diagnostics)
    }

    private fun renderDiagnostics(
        diagnostics: List<EditorDiagnostic>
    ): String {
        if (diagnostics.isEmpty()) {
            return "Diagnostics: none"
        }

        return buildString {
            append("Diagnostics:\n")
            diagnostics.forEach { diagnostic ->
                append("[")
                append(diagnostic.severity.name)
                append("] ")
                append(diagnostic.message)

                if (diagnostic.file != null) {
                    append("\n  ")
                    append(relativePath(diagnostic.file))
                    if (diagnostic.line != null) {
                        append(":")
                        append(diagnostic.line)
                        if (diagnostic.column != null) {
                            append(":")
                            append(diagnostic.column)
                        }
                    }
                }
                append("\n")
            }
        }.trimEnd()
    }

    private fun promptNewFile() {
        promptPath("New file", "src/module.cx") { relative ->
            val path = File(workspacePort.rootPath(), relative).path
            runAction("create file") {
                controller.createFile(path)
            }
        }
    }

    private fun promptNewFolder() {
        promptPath("New folder", "src") { relative ->
            val path = File(workspacePort.rootPath(), relative).path
            runAction("create folder") {
                controller.createDirectory(path)
            }
        }
    }

    private fun promptRename() {
        val selected = selectedEntryPath
        if (selected == null) {
            statusView.text = "Rename failed: select a project entry"
            return
        }

        promptPath(
            "Rename / move",
            relativePath(selected)
        ) { relative ->
            val target = File(workspacePort.rootPath(), relative).path
            runAction("rename") {
                controller.renamePath(selected, target)
            }
        }
    }

    private fun confirmDelete() {
        val selected = selectedEntryPath
        if (selected == null) {
            statusView.text = "Delete failed: select a project entry"
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Delete")
            .setMessage("Delete ${relativePath(selected)}?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                runAction("delete") {
                    controller.deletePath(selected)
                }
            }
            .show()
    }

    private fun setSelectedEntry() {
        val selected = selectedEntryPath
            ?: controller.snapshot().activeDocumentPath

        if (selected == null) {
            statusView.text = "Set entry failed: select a .cx file"
            return
        }

        safeUiAction("set entry") {
            controller.setProjectEntry(selected)
        }
    }

    private fun promptPath(
        title: String,
        initial: String,
        onValue: (String) -> Unit
    ) {
        val input = EditText(this).apply {
            setText(initial)
            isSingleLine = true
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("OK") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isBlank()) {
                    statusView.text = "$title failed: path is blank"
                } else {
                    onValue(value)
                }
            }
            .show()
    }

    /**
     * TEMP LIVE-PROOF primitive renderer only.
     * CXUI app structure and behavior are emitted by compiled .cx code.
     */
    private data class CxUiNode(
        val kind: Int,
        val id: Int,
        val text: String
    )

    private data class CxUiFrame(
        val nodes: List<CxUiNode>
    )

    private fun showLivePreviewIfAvailable(state: EditorState) {
        val artifact = state.candidate ?: return
        val run = toolchain.latestLivePreview() ?: return
        if (!run.success) return

        val frame = parseCxUiFrame(run.output) ?: return
        showCxUiDialog(artifact, frame)
    }

    private fun parseCxUiFrame(output: ByteArray): CxUiFrame? {
        if (output.size < 5) return null
        if ((output[0].toInt() and 0xff) != 67) return null
        if ((output[1].toInt() and 0xff) != 88) return null
        if ((output[2].toInt() and 0xff) != 85) return null
        if ((output[3].toInt() and 0xff) != 49) return null

        val count = output[4].toInt() and 0xff
        if (count !in 1..16) return null

        val nodes = ArrayList<CxUiNode>(count)
        val ids = HashSet<Int>()
        var cursor = 5

        repeat(count) {
            if (cursor + 3 > output.size) return null

            val kind = output[cursor].toInt() and 0xff
            val id = output[cursor + 1].toInt() and 0xff
            val length = output[cursor + 2].toInt() and 0xff
            cursor += 3

            if (kind !in 1..3 || id == 0 || !ids.add(id)) return null
            if (cursor + length > output.size) return null

            val text =
                output.copyOfRange(cursor, cursor + length)
                    .toString(Charsets.UTF_8)
            cursor += length

            nodes.add(CxUiNode(kind = kind, id = id, text = text))
        }

        if (cursor != output.size) return null
        if (nodes.count { it.kind == 2 } > 1) return null

        return CxUiFrame(nodes)
    }

    private fun showCxUiDialog(
        artifact: ArtifactRef,
        frame: CxUiFrame
    ) {
        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(12), dp(20), dp(12))
            }

        var textArea: EditText? = null
        val actionButtons = mutableListOf<Pair<Button, CxUiNode>>()

        frame.nodes.forEach { node ->
            when (node.kind) {
                1 -> {
                    content.addView(
                        TextView(this).apply {
                            text = node.text
                            textSize = 22f
                            setPadding(0, 0, 0, dp(12))
                        }
                    )
                }

                2 -> {
                    val field =
                        EditText(this).apply {
                            setText(node.text)
                            gravity = Gravity.TOP or Gravity.START
                            inputType =
                                InputType.TYPE_CLASS_TEXT or
                                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                            minLines = 10
                            maxLines = 18
                            setPadding(dp(12), dp(12), dp(12), dp(12))
                        }
                    textArea = field
                    content.addView(
                        field,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(320)
                        )
                    )
                }

                3 -> {
                    val button =
                        Button(this).apply {
                            text = node.text
                        }
                    actionButtons.add(button to node)
                    content.addView(button)
                }
            }
        }

        val dialog =
            AlertDialog.Builder(this)
                .setTitle("Live .cx preview")
                .setView(content)
                .setNegativeButton("Close", null)
                .create()

        actionButtons.forEach { (button, node) ->
            button.setOnClickListener {
                val input =
                    encodeCxUiAction(
                        node.id,
                        textArea?.text?.toString().orEmpty()
                    )

                button.isEnabled = false
                statusView.text = "Running live .cx action..."

                Thread {
                    val replay =
                        toolchain.replayLivePreview(
                            artifact = artifact,
                            input = input
                        )
                    val next =
                        if (replay.success) {
                            parseCxUiFrame(replay.output)
                        } else {
                            null
                        }

                    runOnUiThread {
                        button.isEnabled = true
                        if (next == null) {
                            statusView.text =
                                replay.error
                                    ?: "Live .cx preview returned an invalid frame"
                        } else {
                            dialog.dismiss()
                            statusView.text =
                                "Live .cx action ${node.id} passed"
                            showCxUiDialog(artifact, next)
                        }
                    }
                }.start()
            }
        }

        dialog.show()
    }

    private fun encodeCxUiAction(
        controlId: Int,
        currentText: String
    ): ByteArray {
        var boundedText = currentText
        var textBytes = boundedText.toByteArray(Charsets.UTF_8)

        while (textBytes.size > 240 && boundedText.isNotEmpty()) {
            boundedText = boundedText.dropLast(1)
            textBytes = boundedText.toByteArray(Charsets.UTF_8)
        }

        val input = ByteArray(textBytes.size + 3)
        input[0] = 1
        input[1] = (controlId and 0xff).toByte()
        input[2] = textBytes.size.toByte()
        textBytes.copyInto(input, destinationOffset = 3)
        return input
    }

    private fun ensureSavedThen(
        label: String,
        after: (EditorState) -> Unit = {},
        action: () -> EditorState
    ) {
        runAction(label, after) {
            if (controller.snapshot().dirtyDocumentCount > 0) {
                controller.saveAll()
            }
            action()
        }
    }

    private fun safeUiAction(
        label: String,
        action: () -> EditorState
    ) {
        val state =
            try {
                action()
            } catch (error: Throwable) {
                controller.snapshot().copy(
                    status =
                        "$label failed: " +
                            (error.message ?: error.javaClass.simpleName)
                )
            }
        render(state)
    }

    private fun toggleFullScreenEditor() {
        fullScreenEditor = !fullScreenEditor
        val visibility =
            if (fullScreenEditor) View.GONE else View.VISIBLE

        topInfoContainer.visibility = visibility
        projectContainer.visibility = visibility
        diagnosticsContainer.visibility = visibility

        if (fullScreenEditor) {
            editorView.requestFocus()
            statusView.text = "Full-screen editor"
        } else {
            render(controller.snapshot())
        }
    }

    override fun onBackPressed() {
        if (fullScreenEditor) {
            toggleFullScreenEditor()
        } else {
            super.onBackPressed()
        }
    }

    private fun jumpTo(line: Int, column: Int) {
        val text = editorView.text.toString()
        if (text.isEmpty()) return

        var currentLine = 1
        var offset = 0
        while (currentLine < line && offset < text.length) {
            if (text[offset] == '\n') {
                currentLine += 1
            }
            offset += 1
        }

        val target =
            (offset + (column - 1).coerceAtLeast(0))
                .coerceIn(0, text.length)

        editorView.requestFocus()
        editorView.setSelection(target)
    }

    private fun relativePath(path: String): String {
        val root = workspacePort.rootPath().trimEnd(File.separatorChar)
        return File(path).canonicalPath
            .removePrefix(root)
            .trimStart(File.separatorChar)
            .replace(File.separatorChar, '/')
            .ifBlank { "." }
    }

    private fun actionButton(
        label: String,
        action: () -> Unit
    ): Button =
        Button(this).apply {
            text = label
            setOnClickListener { action() }
        }

    private fun weightedButtonParams():
        LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        )

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun runAction(
        label: String,
        after: (EditorState) -> Unit = {},
        action: () -> EditorState
    ) {
        statusView.text = "Running $label..."

        Thread {
            val state =
                try {
                    action()
                } catch (error: Throwable) {
                    controller.snapshot().copy(
                        status =
                            "$label failed: " +
                                (
                                    error.message
                                        ?: error.javaClass.simpleName
                                )
                    )
                }

            runOnUiThread {
                render(state)
                after(state)
            }
        }.start()
    }
}
