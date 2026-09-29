package com.codynex.editor

/**
 * TEMPORARY LIVE-PROOF editor/project controller.
 * Production ownership belongs in native Codynex/.cx; do not promote this Kotlin layer.
 */
class CodynexEditorController(
    private val workspace: WorkspacePort,
    private val compiler: CompilerPort,
    private val preview: PreviewPort
) {
    companion object {
        private const val MAX_SEARCH_HITS = 200
        private const val MAX_SEARCH_QUERY_CHARS = 256
        private const val MAX_PROJECT_MODULES = 64
        private const val MAX_IMPORT_QUEUE = 4096

        private val modulePattern = Regex(
            "(?m)^\\s*module\\s+" +
                "([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)" +
                "\\s*;"
        )

        private val usePattern = Regex(
            "(?m)^\\s*use\\s+" +
                "([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)" +
                "(?:\\s+as\\s+[A-Za-z_][A-Za-z0-9_]*)?\\s*;"
        )
    }

    @Volatile
    private var state = EditorState()

    fun snapshot(): EditorState = state

    fun openWorkspace(root: String): EditorState {
        require(root.isNotBlank()) { "workspace root must not be blank" }

        return runState("Open workspace failed") {
            state = EditorState(
                workspaceRoot = root,
                entries = workspace.listRecursive(root).stableOrder(),
                status = "Workspace opened: $root"
            )
            state
        }
    }

    fun refreshWorkspace(): EditorState {
        val root = requireWorkspace()

        return runState("Refresh failed") {
            state = state.copy(
                entries = workspace.listRecursive(root).stableOrder(),
                status = "Workspace refreshed"
            )
            state
        }
    }

    fun openFile(path: String): EditorState {
        require(path.isNotBlank()) { "file path must not be blank" }

        return runState("Open file failed") {
            val existing = state.documents.firstOrNull { it.path == path }
            val documents =
                if (existing != null) {
                    state.documents
                } else {
                    val text = workspace.readText(path)
                    state.documents + EditorDocument(
                        path = path,
                        text = text,
                        savedText = text
                    )
                }

            state = state.copy(
                documents = documents,
                activeDocumentPath = path,
                diagnostics = emptyList(),
                candidate = null,
                candidateState = CandidateState.NONE,
                status = "Opened: $path"
            )
            state
        }
    }

    fun closeFile(path: String): EditorState {
        val document = state.documents.firstOrNull { it.path == path }
            ?: return state

        require(!document.dirty) {
            "save or discard changes before closing ${document.path}"
        }

        val remaining = state.documents.filterNot { it.path == path }
        val nextActive =
            if (state.activeDocumentPath == path) {
                remaining.lastOrNull()?.path
            } else {
                state.activeDocumentPath
            }

        state = state.copy(
            documents = remaining,
            activeDocumentPath = nextActive,
            status = "Closed: $path"
        )
        return state
    }

    fun editText(text: String): EditorState {
        val document = requireDocument()
        val updated = document.copy(text = text)

        state = state.copy(
            documents = state.documents.replaceDocument(updated),
            candidate = null,
            candidateState = CandidateState.NONE,
            status = if (updated.dirty) "Unsaved changes" else "No unsaved changes"
        )
        return state
    }

    fun save(): EditorState {
        val document = requireDocument()

        return runState("Save failed") {
            workspace.writeText(document.path, document.text)
            val saved = document.copy(savedText = document.text)
            state = state.copy(
                documents = state.documents.replaceDocument(saved),
                status = "Saved: ${document.path}"
            )
            state
        }
    }

    fun saveAll(): EditorState {
        return runState("Save all failed") {
            var savedCount = 0
            val saved = state.documents.map { document ->
                if (document.dirty) {
                    workspace.writeText(document.path, document.text)
                    savedCount += 1
                    document.copy(savedText = document.text)
                } else {
                    document
                }
            }
            state = state.copy(
                documents = saved,
                status = "Saved $savedCount document(s)"
            )
            state
        }
    }

    fun reload(): EditorState {
        val document = requireDocument()

        return runState("Reload failed") {
            val text = workspace.readText(document.path)
            val reloaded = document.copy(text = text, savedText = text)
            state = state.copy(
                documents = state.documents.replaceDocument(reloaded),
                candidate = null,
                candidateState = CandidateState.NONE,
                diagnostics = emptyList(),
                status = "Reloaded: ${document.path}"
            )
            state
        }
    }

    fun createFile(path: String, initialText: String = ""): EditorState {
        require(path.isNotBlank()) { "file path must not be blank" }
        require(!workspace.exists(path)) { "file already exists: $path" }

        return runState("Create file failed") {
            workspace.createTextFile(path, initialText)
            val document = EditorDocument(path, initialText, initialText)
            state = state.copy(
                entries = refreshEntries(),
                documents = state.documents + document,
                activeDocumentPath = path,
                diagnostics = emptyList(),
                candidate = null,
                candidateState = CandidateState.NONE,
                status = "Created: $path"
            )
            state
        }
    }

    fun createDirectory(path: String): EditorState {
        require(path.isNotBlank()) { "directory path must not be blank" }

        return runState("Create directory failed") {
            workspace.createDirectory(path)
            state = state.copy(
                entries = refreshEntries(),
                status = "Created directory: $path"
            )
            state
        }
    }

    fun renamePath(fromPath: String, toPath: String): EditorState {
        require(fromPath.isNotBlank() && toPath.isNotBlank()) {
            "rename paths must not be blank"
        }
        require(!workspace.exists(toPath)) {
            "destination already exists: $toPath"
        }
        requireNoDirtyDocumentsUnder(fromPath)

        return runState("Rename failed") {
            workspace.move(fromPath, toPath)
            val prefix = fromPath.trimEnd('/') + "/"
            val documents = state.documents.map { document ->
                when {
                    document.path == fromPath ->
                        document.copy(path = toPath)
                    document.path.startsWith(prefix) ->
                        document.copy(
                            path = toPath.trimEnd('/') +
                                "/" +
                                document.path.removePrefix(prefix)
                        )
                    else -> document
                }
            }
            state = state.copy(
                entries = refreshEntries(),
                documents = documents,
                activeDocumentPath = remapPath(
                    state.activeDocumentPath,
                    fromPath,
                    toPath
                ),
                projectEntryPath = remapPath(
                    state.projectEntryPath,
                    fromPath,
                    toPath
                ),
                status = "Renamed: $fromPath -> $toPath"
            )
            state
        }
    }

    fun deletePath(path: String): EditorState {
        require(path.isNotBlank()) { "delete path must not be blank" }
        requireNoDirtyDocumentsUnder(path)

        return runState("Delete failed") {
            workspace.delete(path)
            val prefix = path.trimEnd('/') + "/"
            val documents = state.documents.filterNot {
                it.path == path || it.path.startsWith(prefix)
            }
            val active =
                state.activeDocumentPath?.takeUnless {
                    it == path || it.startsWith(prefix)
                } ?: documents.lastOrNull()?.path
            val entry =
                state.projectEntryPath?.takeUnless {
                    it == path || it.startsWith(prefix)
                }
            state = state.copy(
                entries = refreshEntries(),
                documents = documents,
                activeDocumentPath = active,
                projectEntryPath = entry,
                candidate = null,
                candidateState = CandidateState.NONE,
                status = "Deleted: $path"
            )
            state
        }
    }

    fun setProjectEntry(path: String): EditorState {
        require(path.endsWith(".cx", ignoreCase = true)) {
            "project entry must be a .cx file"
        }
        require(workspace.exists(path)) { "project entry does not exist: $path" }

        state = state.copy(
            projectEntryPath = path,
            status = "Project entry: $path"
        )
        return state
    }

    fun search(query: String): EditorState {
        val normalized = query.trim()
        require(normalized.isNotEmpty()) { "search query must not be blank" }
        require(normalized.length <= MAX_SEARCH_QUERY_CHARS) {
            "search query exceeds $MAX_SEARCH_QUERY_CHARS characters"
        }

        return runState("Search failed") {
            val hits = mutableListOf<SearchHit>()

            for (entry in state.entries) {
                if (entry.directory || hits.size >= MAX_SEARCH_HITS) continue

                val text = state.documents.firstOrNull { it.path == entry.path }?.text
                    ?: try {
                        workspace.readText(entry.path)
                    } catch (_: Throwable) {
                        continue
                    }

                text.lineSequence().forEachIndexed { index, line ->
                    if (hits.size >= MAX_SEARCH_HITS) return@forEachIndexed
                    val column = line.indexOf(normalized, ignoreCase = true)
                    if (column >= 0) {
                        hits += SearchHit(
                            path = entry.path,
                            relativePath = entry.relativePath,
                            line = index + 1,
                            column = column + 1,
                            preview = line.trim().take(200)
                        )
                    }
                }
            }

            state = state.copy(
                searchQuery = normalized,
                searchHits = hits,
                status = "Search: ${hits.size} hit(s) for '$normalized'"
            )
            state
        }
    }

    fun clearSearch(): EditorState {
        state = state.copy(
            searchQuery = "",
            searchHits = emptyList(),
            status = "Search cleared"
        )
        return state
    }

    fun compile(): EditorState {
        val root = requireWorkspace()
        val sourcePath = state.projectEntryPath ?: requireDocument().path
        val project = buildProject(sourcePath)

        return runState("Compile failed") {
            val result = compiler.compile(
                CompileRequest(
                    workspaceRoot = root,
                    sourcePath = sourcePath,
                    sourceText = project.rootSource,
                    moduleSources = project.modules
                )
            )

            val current = buildProject(sourcePath)
            if (current != project) {
                state = state.copy(
                    diagnostics = listOf(
                        EditorDiagnostic(
                            severity = DiagnosticSeverity.WARNING,
                            message = "Compile result discarded because project sources changed",
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
                    candidateState =
                        if (result.success && result.artifact != null) {
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
        val sourcePath = state.projectEntryPath ?: requireDocument().path
        val artifact = state.candidate
            ?: return failState("Preview failed", "No compiled candidate exists")

        return runState("Preview failed") {
            val result = preview.preview(
                PreviewRequest(
                    workspaceRoot = root,
                    sourcePath = sourcePath,
                    artifact = artifact
                )
            )

            state = state.copy(
                diagnostics = result.diagnostics,
                candidateState =
                    if (result.success) CandidateState.PREVIEWED
                    else CandidateState.FAILED,
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

    private data class ProjectSources(
        val rootSource: String,
        val modules: Map<String, String>
    )

    private data class IndexedModule(
        val source: String,
        val imports: List<String>
    )

    private fun buildProject(rootPath: String): ProjectSources {
        val rootEntry = state.entries.firstOrNull {
            !it.directory && it.path == rootPath
        } ?: throw IllegalArgumentException(
            "project entry is not in workspace: $rootPath"
        )

        val sourceEntries = state.entries.filter {
            !it.directory && it.path.endsWith(".cx", ignoreCase = true)
        }
        require(sourceEntries.size <= MAX_PROJECT_MODULES) {
            "project exceeds $MAX_PROJECT_MODULES .cx modules"
        }

        val byModule = linkedMapOf<String, Pair<String, IndexedModule>>()
        for (entry in sourceEntries.sortedBy { it.relativePath }) {
            val source = sourceForPath(entry.path)
            val module = modulePattern.find(source)?.groupValues?.get(1)
                ?: throw IllegalArgumentException(
                    "Codynex source is missing module declaration: ${entry.relativePath}"
                )
            require(module !in byModule) {
                "duplicate Codynex module identity: $module"
            }
            byModule[module] =
                entry.path to IndexedModule(
                    source = source,
                    imports = usePattern.findAll(source)
                        .map { it.groupValues[1] }
                        .toList()
                )
        }

        val rootPair = byModule.values.firstOrNull { it.first == rootEntry.path }
            ?: throw IllegalStateException("project entry module was not indexed")

        val selected = linkedMapOf<String, String>()
        val queue = rootPair.second.imports.toMutableList()
        val visited = mutableSetOf<String>()
        var index = 0

        while (index < queue.size) {
            val module = queue[index++]
            if (!visited.add(module)) continue

            val dependency = byModule[module]
                ?: throw IllegalArgumentException(
                    "Codynex import has no project source: $module"
                )
            if (dependency.first == rootEntry.path) continue

            selected[module] = dependency.second.source
            require(selected.size < MAX_PROJECT_MODULES) {
                "project dependency graph exceeds $MAX_PROJECT_MODULES modules"
            }
            queue.addAll(dependency.second.imports)
            require(queue.size <= MAX_IMPORT_QUEUE) {
                "project import traversal exceeded $MAX_IMPORT_QUEUE references"
            }
        }

        return ProjectSources(
            rootSource = rootPair.second.source,
            modules = selected
        )
    }

    private fun sourceForPath(path: String): String =
        state.documents.firstOrNull { it.path == path }?.text
            ?: workspace.readText(path)

    private fun refreshEntries(): List<WorkspaceEntry> =
        state.workspaceRoot?.let { workspace.listRecursive(it).stableOrder() }
            ?: state.entries

    private fun requireNoDirtyDocumentsUnder(path: String) {
        val prefix = path.trimEnd('/') + "/"
        val dirty = state.documents.firstOrNull {
            it.dirty && (it.path == path || it.path.startsWith(prefix))
        }
        require(dirty == null) {
            "save or discard changes before modifying ${dirty?.path}"
        }
    }

    private fun remapPath(
        value: String?,
        fromPath: String,
        toPath: String
    ): String? {
        if (value == null) return null
        if (value == fromPath) return toPath
        val prefix = fromPath.trimEnd('/') + "/"
        return if (value.startsWith(prefix)) {
            toPath.trimEnd('/') + "/" + value.removePrefix(prefix)
        } else {
            value
        }
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

    private fun List<EditorDocument>.replaceDocument(
        document: EditorDocument
    ): List<EditorDocument> =
        map { if (it.path == document.path) document else it }

    private fun List<WorkspaceEntry>.stableOrder(): List<WorkspaceEntry> =
        sortedWith(
            compareBy<WorkspaceEntry> { it.relativePath.lowercase() }
                .thenByDescending { it.directory }
                .thenBy { it.path }
        )
}
