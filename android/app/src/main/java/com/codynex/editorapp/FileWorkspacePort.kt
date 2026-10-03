package com.codynex.editorapp

import com.codynex.editor.WorkspaceEntry
import com.codynex.editor.WorkspacePort
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Native Android editor workspace adapter; host tooling only, outside the Codynex runtime. */
class FileWorkspacePort(
    rootDirectory: File
) : WorkspacePort {
    companion object {
        private const val MAX_TEXT_BYTES = 1024 * 1024
        private const val MAX_TREE_ENTRIES = 4096
        private const val MAX_TREE_DEPTH = 32
    }

    private val root = rootDirectory.apply { mkdirs() }.canonicalFile

    override fun list(root: String): List<WorkspaceEntry> {
        val directory = resolve(root)
        require(directory.isDirectory) {
            "workspace path is not a directory: $root"
        }

        return directory.listFiles()
            ?.map { file -> entry(file) }
            ?: emptyList()
    }

    override fun listRecursive(root: String): List<WorkspaceEntry> {
        val directory = resolve(root)
        require(directory.isDirectory) {
            "workspace path is not a directory: $root"
        }

        val output = mutableListOf<WorkspaceEntry>()

        fun walk(current: File, depth: Int) {
            require(depth <= MAX_TREE_DEPTH) {
                "workspace tree exceeds depth $MAX_TREE_DEPTH"
            }
            val children = current.listFiles()
                ?.sortedWith(
                    compareByDescending<File> { it.isDirectory }
                        .thenBy { it.name.lowercase() }
                )
                .orEmpty()

            for (child in children) {
                require(output.size < MAX_TREE_ENTRIES) {
                    "workspace tree exceeds $MAX_TREE_ENTRIES entries"
                }
                output += entry(child)
                if (child.isDirectory) {
                    walk(child, depth + 1)
                }
            }
        }

        walk(directory, 0)
        return output
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

    override fun createDirectory(path: String) {
        val directory = resolve(path)
        require(!directory.exists()) {
            "path already exists: $path"
        }
        require(directory.mkdirs()) {
            "could not create directory: $path"
        }
    }

    override fun move(fromPath: String, toPath: String) {
        val source = resolve(fromPath)
        val target = resolve(toPath)
        require(source.exists()) { "source does not exist: $fromPath" }
        require(!target.exists()) { "destination already exists: $toPath" }
        target.parentFile?.let { parent ->
            require(parent.mkdirs() || parent.isDirectory) {
                "could not create destination parent"
            }
        }

        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath())
        }
    }

    override fun delete(path: String) {
        val target = resolve(path)
        require(target != root) { "cannot delete workspace root" }
        require(target.exists()) { "path does not exist: $path" }

        var removed = 0
        fun remove(current: File) {
            if (current.isDirectory) {
                current.listFiles()?.forEach(::remove)
            }
            removed += 1
            require(removed <= MAX_TREE_ENTRIES) {
                "delete exceeds $MAX_TREE_ENTRIES entries"
            }
            require(current.delete()) {
                "could not delete ${current.path}"
            }
        }

        remove(target)
    }

    override fun exists(path: String): Boolean = resolve(path).exists()

    fun rootPath(): String = root.absolutePath

    private fun entry(file: File): WorkspaceEntry =
        WorkspaceEntry(
            path = file.canonicalPath,
            name = file.name,
            directory = file.isDirectory,
            relativePath = file.canonicalPath
                .removePrefix(root.path)
                .trimStart(File.separatorChar)
                .replace(File.separatorChar, '/')
        )

    private fun resolve(path: String): File {
        val candidate = File(path).canonicalFile
        val prefix = root.path + File.separator
        require(candidate == root || candidate.path.startsWith(prefix)) {
            "path escapes editor workspace"
        }
        return candidate
    }
}
