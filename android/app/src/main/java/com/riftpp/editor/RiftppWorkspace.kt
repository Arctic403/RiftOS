package com.riftpp.editor

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class RiftppWorkspaceEntry(
    val relativePath: String,
    val name: String,
    val directory: Boolean,
    val depth: Int
)

class RiftppWorkspace(rootDirectory: File) {
    companion object {
        private const val MAX_TEXT_BYTES = 1024 * 1024
        private const val MAX_TREE_ENTRIES = 4096
        private const val MAX_TREE_DEPTH = 32
    }

    val root: File =
        rootDirectory
            .apply { mkdirs() }
            .canonicalFile

    fun listRecursive(): List<RiftppWorkspaceEntry> {
        val output =
            mutableListOf<RiftppWorkspaceEntry>()

        fun walk(
            directory: File,
            depth: Int
        ) {
            require(depth <= MAX_TREE_DEPTH) {
                "workspace tree exceeds depth $MAX_TREE_DEPTH"
            }

            val children =
                directory
                    .listFiles()
                    ?.sortedWith(
                        compareByDescending<File> {
                            it.isDirectory
                        }.thenBy {
                            it.name.lowercase()
                        }
                    )
                    .orEmpty()

            for (child in children) {
                require(
                    output.size <
                        MAX_TREE_ENTRIES
                ) {
                    "workspace tree exceeds $MAX_TREE_ENTRIES entries"
                }

                output +=
                    RiftppWorkspaceEntry(
                        relativePath =
                            relativePath(child),
                        name = child.name,
                        directory =
                            child.isDirectory,
                        depth = depth
                    )

                if (child.isDirectory) {
                    walk(
                        child,
                        depth + 1
                    )
                }
            }
        }

        walk(root, 0)
        return output
    }

    fun readText(path: String): String {
        val file = resolve(path)

        require(file.isFile) {
            "file does not exist: $path"
        }
        require(
            file.length() <=
                MAX_TEXT_BYTES
        ) {
            "text file exceeds $MAX_TEXT_BYTES bytes"
        }

        return file.readText(
            Charsets.UTF_8
        )
    }

    fun writeText(
        path: String,
        text: String
    ) {
        val file = resolve(path)
        val bytes =
            text.toByteArray(
                Charsets.UTF_8
            )

        require(
            bytes.size <=
                MAX_TEXT_BYTES
        ) {
            "text file exceeds $MAX_TEXT_BYTES bytes"
        }

        val parent =
            file.parentFile
                ?: error(
                    "workspace file has no parent"
                )

        require(
            parent.mkdirs() ||
                parent.isDirectory
        ) {
            "could not create parent directory"
        }

        val temp =
            File(
                parent,
                "." +
                    file.name +
                    ".riftpp-editor.tmp"
            )

        temp.writeBytes(bytes)

        try {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (
            _: AtomicMoveNotSupportedException
        ) {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    fun createTextFile(
        path: String,
        initialText: String = ""
    ) {
        val file = resolve(path)

        require(!file.exists()) {
            "file already exists: $path"
        }

        writeText(
            path,
            initialText
        )
    }

    fun createDirectory(path: String) {
        val directory = resolve(path)

        require(!directory.exists()) {
            "path already exists: $path"
        }
        require(directory.mkdirs()) {
            "could not create directory: $path"
        }
    }

    fun move(
        fromPath: String,
        toPath: String
    ) {
        val source =
            resolve(fromPath)
        val target =
            resolve(toPath)

        require(source.exists()) {
            "source does not exist: $fromPath"
        }
        require(!target.exists()) {
            "destination already exists: $toPath"
        }

        val parent =
            target.parentFile
                ?: error(
                    "destination has no parent"
                )

        require(
            parent.mkdirs() ||
                parent.isDirectory
        ) {
            "could not create destination parent"
        }

        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (
            _: AtomicMoveNotSupportedException
        ) {
            Files.move(
                source.toPath(),
                target.toPath()
            )
        }
    }

    fun delete(path: String) {
        val target = resolve(path)

        require(target != root) {
            "cannot delete workspace root"
        }
        require(target.exists()) {
            "path does not exist: $path"
        }

        var removed = 0

        fun remove(current: File) {
            if (current.isDirectory) {
                current
                    .listFiles()
                    ?.forEach(::remove)
            }

            removed += 1

            require(
                removed <=
                    MAX_TREE_ENTRIES
            ) {
                "delete exceeds $MAX_TREE_ENTRIES entries"
            }

            require(current.delete()) {
                "could not delete " +
                    relativePath(current)
            }
        }

        remove(target)
    }

    fun exists(path: String): Boolean =
        resolve(path).exists()

    fun file(path: String): File =
        resolve(path)

    fun relativePath(file: File): String {
        val canonical =
            file.canonicalFile

        require(
            canonical == root ||
                canonical.path.startsWith(
                    root.path +
                        File.separator
                )
        ) {
            "path escapes editor workspace"
        }

        return canonical.path
            .removePrefix(root.path)
            .trimStart(
                File.separatorChar
            )
            .replace(
                File.separatorChar,
                '/'
            )
    }

    private fun resolve(path: String): File {
        require(
            path.isNotBlank()
        ) {
            "workspace path is empty"
        }
        require(
            !File(path).isAbsolute
        ) {
            "absolute workspace path is not allowed"
        }

        val candidate =
            File(
                root,
                path
            ).canonicalFile

        val prefix =
            root.path +
                File.separator

        require(
            candidate == root ||
                candidate.path
                    .startsWith(prefix)
        ) {
            "path escapes editor workspace"
        }

        return candidate
    }
}
