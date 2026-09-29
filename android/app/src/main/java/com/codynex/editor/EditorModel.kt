package com.codynex.editor

enum class DiagnosticSeverity {
    INFO,
    WARNING,
    ERROR
}

data class EditorDiagnostic(
    val severity: DiagnosticSeverity,
    val message: String,
    val file: String? = null,
    val line: Int? = null,
    val column: Int? = null
)

data class WorkspaceEntry(
    val path: String,
    val name: String,
    val directory: Boolean,
    val relativePath: String = name
)

data class EditorDocument(
    val path: String,
    val text: String,
    val savedText: String
) {
    val dirty: Boolean
        get() = text != savedText
}

data class SearchHit(
    val path: String,
    val relativePath: String,
    val line: Int,
    val column: Int,
    val preview: String
)

data class ArtifactRef(
    val id: String,
    val kind: String,
    val displayName: String
)

enum class CandidateState {
    NONE,
    COMPILED,
    PREVIEWED,
    FAILED
}

data class EditorState(
    val workspaceRoot: String? = null,
    val entries: List<WorkspaceEntry> = emptyList(),
    val documents: List<EditorDocument> = emptyList(),
    val activeDocumentPath: String? = null,
    val projectEntryPath: String? = null,
    val diagnostics: List<EditorDiagnostic> = emptyList(),
    val searchQuery: String = "",
    val searchHits: List<SearchHit> = emptyList(),
    val candidate: ArtifactRef? = null,
    val candidateState: CandidateState = CandidateState.NONE,
    val status: String = "Editor ready"
) {
    val document: EditorDocument?
        get() = documents.firstOrNull { it.path == activeDocumentPath }

    val dirtyDocumentCount: Int
        get() = documents.count { it.dirty }
}

data class CompileRequest(
    val workspaceRoot: String,
    val sourcePath: String,
    val sourceText: String,
    val moduleSources: Map<String, String> = emptyMap()
)

data class CompileResult(
    val success: Boolean,
    val artifact: ArtifactRef? = null,
    val diagnostics: List<EditorDiagnostic> = emptyList(),
    val summary: String = if (success) "Compile succeeded" else "Compile failed"
)

data class PreviewRequest(
    val workspaceRoot: String,
    val sourcePath: String,
    val artifact: ArtifactRef
)

data class PreviewResult(
    val success: Boolean,
    val diagnostics: List<EditorDiagnostic> = emptyList(),
    val summary: String = if (success) "Preview succeeded" else "Preview failed"
)
