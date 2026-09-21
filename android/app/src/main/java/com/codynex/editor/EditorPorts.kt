package com.codynex.editor

interface WorkspacePort {
    fun list(root: String): List<WorkspaceEntry>

    fun readText(path: String): String

    fun writeText(path: String, text: String)

    fun createTextFile(path: String, initialText: String = "")

    fun exists(path: String): Boolean
}

interface CompilerPort {
    fun compile(request: CompileRequest): CompileResult
}

interface PreviewPort {
    fun preview(request: PreviewRequest): PreviewResult
}
