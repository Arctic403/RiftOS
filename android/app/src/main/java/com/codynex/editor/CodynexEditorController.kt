package com.codynex.editor

class CodynexEditorController(
    private val workspace: WorkspacePort,
    private val compiler: CompilerPort,
    private val preview: PreviewPort
) {
    @Volatile
    private var state = EditorState()

    fun snapshot(): EditorState = state

    fun openWorkspace(root: String): EditorState {
        require(root.isNotBlank()) { "workspace root must not be blank" }

        return runState("Open workspace failed") {
            state = state.copy(
                workspaceRoot = root,
                entries = workspace.list(root).stableOrder(),
                document = null,
                diagnostics = emptyList(),
                candidate = null,
                candidateState = CandidateState.NONE,
                status = "Workspace opened: $root"
            )
            state
        }
    }

    fun refreshWorkspace(): EditorState {
        val root = requireWorkspace()

        return runState("Refresh failed") {
            state = state.copy(
                entries = workspace.list(root).stableOrder(),
                status = "Workspace refreshed"
            )
            state
        }
    }

    fun openFile(path: String): EditorState {
        require(path.isNotBlank()) { "file path must not be blank" }

        return runState("Open file failed") {
            val text = workspace.readText(path)
            state = state.copy(
                document = EditorDocument(
                    path = path,
                    text = text,
                    savedText = text
                ),
                diagnostics = emptyList(),
                candidate = null,
                candidateState = CandidateState.NONE,
                status = "Opened: $path"
            )
            state
        }
    }

    fun editText(text: String): EditorState {
        val document = requireDocument()

        state = state.copy(
            document = document.copy(text = text),
            candidate = null,
            candidateState = CandidateState.NONE,
            status = if (text == document.savedText) {
                "No unsaved changes"
            } else {
                "Unsaved changes"
            }
        )
        return state
    }

    fun save(): EditorState {
        val document = requireDocument()

        return runState("Save failed") {
            workspace.writeText(document.path, document.text)
            state = state.copy(
                document = document.copy(savedText = document.text),
                status = "Saved: ${document.path}"
            )
            state
        }
    }

    fun reload(): EditorState {
        val document = requireDocument()
        return openFile(document.path)
    }

    fun createFile(path: String, initialText: String = ""): EditorState {
        require(path.isNotBlank()) { "file path must not be blank" }
        require(!workspace.exists(path)) { "file already exists: $path" }

        return runState("Create file failed") {
            workspace.createTextFile(path, initialText)
            val root = state.workspaceRoot
            val entries = if (root == null) {
                state.entries
            } else {
                workspace.list(root).stableOrder()
            }
            state = state.copy(
                entries = entries,
                document = EditorDocument(
                    path = path,
                    text = initialText,
                    savedText = initialText
                ),
                diagnostics = emptyList(),
                candidate = null,
                candidateState = CandidateState.NONE,
                status = "Created: $path"
            )
            state
        }
    }

    fun compile(): EditorState {
        val root = requireWorkspace()
        val document = requireDocument()
        val sourcePath = document.path
        val sourceText = document.text

        return runState("Compile failed") {
            val result = compiler.compile(
                CompileRequest(
                    workspaceRoot = root,
                    sourcePath = sourcePath,
                    sourceText = sourceText
                )
            )

            val current = state.document
            if (
                current == null ||
                current.path != sourcePath ||
                current.text != sourceText
            ) {
                state = state.copy(
                    diagnostics = listOf(
                        EditorDiagnostic(
                            severity = DiagnosticSeverity.WARNING,
                            message = "Compile result discarded because the document changed",
                            file = sourcePath
                        )
                    ),
                    candidate = null,
                    candidateState = CandidateState.NONE,
                    status = "Compile result became stale"
                )
            } else {
                state = state.copy(
                    diagnostics = result.diagnostics,
                    candidate = result.artifact,
                    candidateState = if (result.success && result.artifact != null) {
                        CandidateState.COMPILED
                    } else {
                        CandidateState.FAILED
                    },
                    status = result.summary
                )
            }
            state
        }
    }

    fun preview(): EditorState {
        val root = requireWorkspace()
        val document = requireDocument()
        val artifact = state.candidate
            ?: return failState("Preview failed", "No compiled candidate exists")

        return runState("Preview failed") {
            val result = preview.preview(
                PreviewRequest(
                    workspaceRoot = root,
                    sourcePath = document.path,
                    artifact = artifact
                )
            )

            state = state.copy(
                diagnostics = result.diagnostics,
                candidateState = if (result.success) {
                    CandidateState.PREVIEWED
                } else {
                    CandidateState.FAILED
                },
                status = result.summary
            )
            state
        }
    }

    fun clearCandidate(): EditorState {
        state = state.copy(
            candidate = null,
            candidateState = CandidateState.NONE,
            diagnostics = emptyList(),
            status = "Candidate cleared"
        )
        return state
    }

    private fun requireWorkspace(): String =
        requireNotNull(state.workspaceRoot) { "no workspace is open" }

    private fun requireDocument(): EditorDocument =
        requireNotNull(state.document) { "no document is open" }

    private inline fun runState(
        failureLabel: String,
        action: () -> EditorState
    ): EditorState =
        try {
            action()
        } catch (error: Throwable) {
            failState(
                failureLabel,
                error.message ?: error.javaClass.simpleName
            )
        }

    private fun failState(label: String, message: String): EditorState {
        state = state.copy(
            diagnostics = listOf(
                EditorDiagnostic(
                    severity = DiagnosticSeverity.ERROR,
                    message = message,
                    file = state.document?.path
                )
            ),
            candidate = null,
            candidateState = CandidateState.FAILED,
            status = "$label: $message"
        )
        return state
    }

    private fun List<WorkspaceEntry>.stableOrder(): List<WorkspaceEntry> =
        sortedWith(
            compareByDescending<WorkspaceEntry> { it.directory }
                .thenBy { it.name.lowercase() }
                .thenBy { it.path }
        )
}
