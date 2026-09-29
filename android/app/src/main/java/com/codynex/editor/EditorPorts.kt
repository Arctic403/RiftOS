package com.codynex.editor

/** TEMPORARY LIVE-PROOF host ports; production editor ownership MUST move to native Codynex/.cx. */
interface WorkspacePort {
    fun list(root: String): List<WorkspaceEntry>
    fun listRecursive(root: String): List<WorkspaceEntry>
    fun readText(path: String): String
    fun writeText(path: String, text: String)
    fun createTextFile(path: String, initialText: String = "")
    fun createDirectory(path: String)
    fun move(fromPath: String, toPath: String)
    fun delete(path: String)
    fun exists(path: String): Boolean
}

interface CompilerPort {
    fun compile(request: CompileRequest): CompileResult
}

interface PreviewPort {
    fun preview(request: PreviewRequest): PreviewResult
}
