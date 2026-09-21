package com.codynex.editorapp

import com.codynex.editor.WorkspaceEntry
import com.codynex.editor.WorkspacePort
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class FileWorkspacePort(
    rootDirectory: File
) : WorkspacePort {
    companion object {
        private const val MAX_TEXT_BYTES = 1024 * 1024
    }

    private val root = rootDirectory.apply { mkdirs() }.canonicalFile

    override fun list(root: String): List<WorkspaceEntry> {
        val directory = resolve(root)
        require(directory.isDirectory) { "workspace path is not a directory: $root" }

        return directory.listFiles()
            ?.map { file ->
                WorkspaceEntry(
                    path = file.canonicalPath,
                    name = file.name,
                    directory = file.isDirectory
                )
            }
            ?: emptyList()
    }

    override fun readText(path: String): String {
        val file = resolve(path)
        require(file.isFile) { "file does not exist: $path" }
        require(file.length() <= MAX_TEXT_BYTES) {
            "text file exceeds $MAX_TEXT_BYTES bytes"
        }
        return file.readText(Charsets.UTF_8)
    }

    override fun writeText(path: String, text: String) {
        val file = resolve(path)
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_TEXT_BYTES) {
            "text file exceeds $MAX_TEXT_BYTES bytes"
        }

        file.parentFile?.let { parent ->
            require(parent.mkdirs() || parent.isDirectory) {
                "could not create parent directory"
            }
        }

        val temp = File(file.parentFile, ".${file.name}.codynex-editor.tmp")
        temp.writeBytes(bytes)

        try {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    override fun createTextFile(path: String, initialText: String) {
        val file = resolve(path)
        require(!file.exists()) { "file already exists: $path" }
        writeText(path, initialText)
    }

    override fun exists(path: String): Boolean = resolve(path).exists()

    fun rootPath(): String = root.absolutePath

    private fun resolve(path: String): File {
        val candidate = File(path).canonicalFile
        val prefix = root.path + File.separator
        require(candidate == root || candidate.path.startsWith(prefix)) {
            "path escapes editor workspace"
        }
        return candidate
    }
}
