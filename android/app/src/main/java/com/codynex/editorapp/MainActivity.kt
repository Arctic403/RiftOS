package com.codynex.editorapp

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.codynex.editor.CodynexEditorController
import com.codynex.editor.EditorDiagnostic
import com.codynex.editor.EditorState
import com.codynex.editor.WorkspaceEntry
import java.io.File

class MainActivity : Activity() {
    private lateinit var controller: CodynexEditorController
    private lateinit var workspacePort: FileWorkspacePort

    private lateinit var statusView: TextView
    private lateinit var candidateView: TextView
    private lateinit var diagnosticsView: TextView
    private lateinit var editorView: EditText
    private lateinit var fileSpinner: Spinner
    private lateinit var fileAdapter: ArrayAdapter<String>
    private lateinit var newFileName: EditText

    private var fileEntries: List<WorkspaceEntry> = emptyList()
    private var rendering = false
    private var spinnerRendering = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val artifacts = BootstrapArtifactLoader.load(this)

            val workspaceRoot =
                File(filesDir, "codynex-workspace").apply {
                    mkdirs()
                }

            workspacePort = FileWorkspacePort(workspaceRoot)

            val starterFile =
                File(workspaceRoot, "compiler.cx0")

            if (!starterFile.exists()) {
                workspacePort.createTextFile(
                    starterFile.canonicalPath,
                    artifacts.starterSource
                )
            }

            val toolchain =
                Source0SelfHostToolchainPort(
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
            render(
                controller.openFile(starterFile.canonicalPath)
            )
        } catch (error: Throwable) {
            setContentView(
                TextView(this).apply {
                    text =
                        "Codynex E0 editor failed to start:\n\n" +
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
            setPadding(20, 12, 20, 8)
            setTextIsSelectable(true)
        }

        candidateView = TextView(this).apply {
            text = "Candidate: NONE"
            setPadding(20, 0, 20, 8)
            setTextIsSelectable(true)
        }

        fileAdapter =
            ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                mutableListOf()
            )

        fileSpinner = Spinner(this).apply {
            adapter = fileAdapter
            onItemSelectedListener =
                object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: android.widget.AdapterView<*>?,
                        view: android.view.View?,
                        position: Int,
                        id: Long
                    ) {
                        if (spinnerRendering) {
                            return
                        }

                        val entry =
                            fileEntries.getOrNull(position)
                                ?: return

                        val current =
                            controller.snapshot().document?.path

                        if (entry.path != current) {
                            render(controller.openFile(entry.path))
                        }
                    }

                    override fun onNothingSelected(
                        parent: android.widget.AdapterView<*>?
                    ) = Unit
                }
        }

        newFileName = EditText(this).apply {
            hint = "new-file.cx"
            isSingleLine = true
            setPadding(16, 4, 16, 4)
        }

        editorView = EditText(this).apply {
            gravity = Gravity.TOP or Gravity.START
            typeface = Typeface.MONOSPACE
            isSingleLine = false
            setHorizontallyScrolling(true)
            inputType =
                InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(20, 16, 20, 16)

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
                        if (!rendering) {
                            val current = s?.toString().orEmpty()
                            renderStatusOnly(
                                controller.editText(current)
                            )
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
            setPadding(20, 12, 20, 16)
        }

        val controls =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL

                addView(
                    actionButton("Save") {
                        runAction("save") {
                            controller.save()
                        }
                    },
                    weightedButtonParams()
                )

                addView(
                    actionButton("Reload") {
                        runAction("reload") {
                            controller.reload()
                        }
                    },
                    weightedButtonParams()
                )

                addView(
                    actionButton("Compile") {
                        runAction("compile") {
                            controller.compile()
                        }
                    },
                    weightedButtonParams()
                )

                addView(
                    actionButton("Preview") {
                        runAction("preview") {
                            val state = controller.snapshot()
                            if (state.document?.dirty == true) {
                                controller.save()
                            }
                            controller.preview()
                        }
                    },
                    weightedButtonParams()
                )
            }

        val newFileRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL

                addView(
                    newFileName,
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                )

                addView(
                    actionButton("New") {
                        val name =
                            newFileName.text.toString().trim()

                        if (name.isBlank()) {
                            renderStatusOnly(
                                controller.snapshot().copy(
                                    status =
                                        "Create file failed: " +
                                            "name is blank"
                                )
                            )
                        } else {
                            val path =
                                File(
                                    workspacePort.rootPath(),
                                    name
                                ).path

                            runAction("create file") {
                                controller.createFile(path)
                            }
                        }
                    }
                )
            }

        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL

                addView(
                    TextView(this@MainActivity).apply {
                        text =
                            "Codynex E0 — reusable external editor\n" +
                                "Active toolchain: frozen MC2-A " +
                                "compiler A via VM1"
                        setPadding(20, 16, 20, 8)
                    }
                )

                addView(statusView)
                addView(candidateView)
                addView(fileSpinner)
                addView(newFileRow)
                addView(controls)

                addView(
                    editorView,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                    )
                )

                addView(
                    diagnosticsView,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }

        setContentView(root)
    }

    private fun render(state: EditorState) {
        rendering = true
        try {
            statusView.text = state.status
            candidateView.text =
                "Candidate: " + state.candidateState.name +
                    (state.candidate?.let {
                        "\n" + it.displayName
                    } ?: "")

            diagnosticsView.text =
                renderDiagnostics(state.diagnostics)

            val files =
                state.entries.filter { !it.directory }

            fileEntries = files

            spinnerRendering = true
            fileAdapter.clear()
            fileAdapter.addAll(files.map { it.name })
            fileAdapter.notifyDataSetChanged()

            val currentPath = state.document?.path
            val selected =
                files.indexOfFirst { it.path == currentPath }

            if (selected >= 0) {
                fileSpinner.setSelection(selected, false)
            }
            spinnerRendering = false

            val document = state.document
            if (
                document != null &&
                editorView.text.toString() != document.text
            ) {
                editorView.setText(document.text)
                editorView.setSelection(editorView.text.length)
            }
        } finally {
            spinnerRendering = false
            rendering = false
        }
    }

    private fun renderStatusOnly(state: EditorState) {
        statusView.text = state.status
        candidateView.text =
            "Candidate: " + state.candidateState.name +
                (state.candidate?.let {
                    "\n" + it.displayName
                } ?: "")
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
                    append(diagnostic.file)
                }
                append("\n")
            }
        }.trimEnd()
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

    private fun runAction(
        label: String,
        action: () -> EditorState
    ) {
        statusView.text = "Running " + label + "..."

        Thread {
            val state =
                try {
                    action()
                } catch (error: Throwable) {
                    controller.snapshot().copy(
                        status =
                            label + " failed: " +
                                (
                                    error.message
                                        ?: error.javaClass.simpleName
                                )
                    )
                }

            runOnUiThread {
                render(state)
            }
        }.start()
    }
}
