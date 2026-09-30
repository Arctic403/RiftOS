package com.riftpp.editor

import org.json.JSONArray
import org.json.JSONObject

data class RiftppProjectManifest(
    val format: String,
    val name: String,
    val packageName: String,
    val entry: String,
    val sources: List<String>,
    val target: String,
    val presentation: String
)

object RiftppProjectModel {
    private const val MANIFEST_PATH =
        "app.rift.json"
    private const val MAX_SOURCE_FILES = 32
    private const val MAX_COMBINED_SOURCE_BYTES =
        4096

    fun read(
        workspace: RiftppWorkspace
    ): RiftppProjectManifest {
        val json =
            JSONObject(
                workspace.readText(
                    MANIFEST_PATH
                )
            )

        val format =
            json.getString(
                "format"
            )

        require(
            format == "rift.app/2" ||
                format == "rift.app/3"
        ) {
            "unsupported Rift++ project format"
        }

        val name =
            json.getString("name")
                .trim()

        require(name.isNotEmpty()) {
            "project name is empty"
        }

        val packageName =
            json.getString(
                "package"
            ).trim()

        require(
            Regex(
                "^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$"
            ).matches(packageName)
        ) {
            "project package is invalid"
        }

        val entry =
            canonicalSourcePath(
                json.getString(
                    "entry"
                )
            )

        val target =
            json.optString(
                "target",
                "arm32"
            )

        require(
            target == "arm32"
        ) {
            "project compiler proof is ARM32-only for now"
        }

        val presentation =
            json.optString(
                "presentation"
            )

        require(
            presentation == "rui2"
        ) {
            "project requires RUI2 presentation"
        }

        val sources =
            if (format == "rift.app/3") {
                val array =
                    json.getJSONArray(
                        "sources"
                    )

                require(
                    array.length() in
                        1..MAX_SOURCE_FILES
                ) {
                    "project source count is out of bounds"
                }

                buildList {
                    repeat(
                        array.length()
                    ) { index ->
                        add(
                            canonicalSourcePath(
                                array.getString(
                                    index
                                )
                            )
                        )
                    }
                }
            } else {
                listOf(entry)
            }

        require(
            sources.distinct()
                .size ==
                sources.size
        ) {
            "project source paths contain duplicates"
        }

        require(
            sources.first() ==
                entry
        ) {
            "project entry must be first in sources"
        }

        sources.forEach { path ->
            require(
                workspace.file(path)
                    .isFile
            ) {
                "project source is missing: $path"
            }
            require(
                path.endsWith(
                    ".riftpp"
                )
            ) {
                "project source is not .riftpp: $path"
            }
        }

        return RiftppProjectManifest(
            format = format,
            name = name,
            packageName =
                packageName,
            entry = entry,
            sources = sources,
            target = target,
            presentation =
                presentation
        )
    }

    fun buildCompileInput(
        workspace: RiftppWorkspace,
        manifest: RiftppProjectManifest
    ): ByteArray {
        require(
            manifest.format ==
                "rift.app/3"
        ) {
            "multi-file compile requires rift.app/3"
        }

        val output =
            ArrayList<Byte>()

        manifest.sources
            .forEach { path ->
                val source =
                    workspace.readText(
                        path
                    )

                require(
                    source.all {
                        it.code <= 0x7f
                    }
                ) {
                    "Rift++ project source must be canonical ASCII: $path"
                }

                val bytes =
                    source.toByteArray(
                        Charsets.US_ASCII
                    )

                require(
                    bytes.isNotEmpty()
                ) {
                    "project source is empty: $path"
                }
                require(
                    bytes.last() ==
                        '\n'.code
                            .toByte()
                ) {
                    "project source must end with LF: $path"
                }

                require(
                    output.size +
                        bytes.size <=
                        MAX_COMBINED_SOURCE_BYTES
                ) {
                    "combined Rift++ project source exceeds $MAX_COMBINED_SOURCE_BYTES bytes"
                }

                bytes.forEach {
                    output += it
                }
            }

        return output
            .toByteArray()
    }

    fun setEntry(
        workspace: RiftppWorkspace,
        path: String
    ) {
        val canonical =
            canonicalSourcePath(path)

        require(
            workspace.file(canonical)
                .isFile
        ) {
            "entry source is missing"
        }

        val json =
            JSONObject(
                workspace.readText(
                    MANIFEST_PATH
                )
            )

        require(
            json.getString("format") ==
                "rift.app/3"
        ) {
            "Set Entry requires rift.app/3"
        }

        val existing =
            mutableListOf<String>()

        val array =
            json.getJSONArray(
                "sources"
            )

        repeat(
            array.length()
        ) { index ->
            val item =
                canonicalSourcePath(
                    array.getString(
                        index
                    )
                )

            if (item != canonical) {
                existing += item
            }
        }

        val next =
            JSONArray()
                .put(canonical)

        existing.forEach {
            next.put(it)
        }

        json.put(
            "entry",
            canonical
        )
        json.put(
            "sources",
            next
        )

        workspace.writeText(
            MANIFEST_PATH,
            json.toString(2) +
                "\n"
        )
    }

    fun renamePathReferences(
        workspace: RiftppWorkspace,
        fromPath: String,
        toPath: String,
        directory: Boolean
    ) {
        val from =
            fromPath.trim()
                .replace(
                    '\\',
                    '/'
                )
                .trimEnd('/')
        val to =
            toPath.trim()
                .replace(
                    '\\',
                    '/'
                )
                .trimEnd('/')

        require(
            from.isNotEmpty() &&
                to.isNotEmpty() &&
                !from.contains("..") &&
                !to.contains("..")
        ) {
            "invalid project path rename"
        }

        val json =
            JSONObject(
                workspace.readText(
                    MANIFEST_PATH
                )
            )

        if (
            json.getString("format") !=
                "rift.app/3"
        ) {
            return
        }

        fun remap(path: String): String {
            val canonical =
                canonicalSourcePath(
                    path
                )

            return when {
                canonical == from ->
                    to

                directory &&
                    canonical.startsWith(
                        from + "/"
                    ) ->
                    to +
                        canonical.removePrefix(
                            from
                        )

                else ->
                    canonical
            }
        }

        json.put(
            "entry",
            remap(
                json.getString(
                    "entry"
                )
            )
        )

        val array =
            json.getJSONArray(
                "sources"
            )
        val next =
            JSONArray()

        repeat(
            array.length()
        ) { index ->
            next.put(
                remap(
                    array.getString(
                        index
                    )
                )
            )
        }

        json.put(
            "sources",
            next
        )

        workspace.writeText(
            MANIFEST_PATH,
            json.toString(2) +
                "\n"
        )
    }

    fun removePathReferences(
        workspace: RiftppWorkspace,
        path: String,
        directory: Boolean
    ) {
        val canonical =
            path.trim()
                .replace(
                    '\\',
                    '/'
                )
                .trimEnd('/')

        val json =
            JSONObject(
                workspace.readText(
                    MANIFEST_PATH
                )
            )

        if (
            json.getString("format") !=
                "rift.app/3"
        ) {
            return
        }

        val entry =
            canonicalSourcePath(
                json.getString(
                    "entry"
                )
            )

        val matchesEntry =
            entry == canonical ||
                (
                    directory &&
                        entry.startsWith(
                            canonical + "/"
                        )
                    )

        require(!matchesEntry) {
            "cannot delete the active project entry; set another entry first"
        }

        val array =
            json.getJSONArray(
                "sources"
            )
        val next =
            JSONArray()

        repeat(
            array.length()
        ) { index ->
            val item =
                canonicalSourcePath(
                    array.getString(
                        index
                    )
                )

            val remove =
                item == canonical ||
                    (
                        directory &&
                            item.startsWith(
                                canonical + "/"
                            )
                        )

            if (!remove) {
                next.put(item)
            }
        }

        require(next.length() > 0) {
            "project must retain at least one source"
        }

        json.put(
            "sources",
            next
        )

        workspace.writeText(
            MANIFEST_PATH,
            json.toString(2) +
                "\n"
        )
    }

    fun addSource(
        workspace: RiftppWorkspace,
        path: String
    ) {
        val canonical =
            canonicalSourcePath(path)

        require(
            workspace.file(canonical)
                .isFile
        ) {
            "source file is missing"
        }

        val json =
            JSONObject(
                workspace.readText(
                    MANIFEST_PATH
                )
            )

        require(
            json.getString("format") ==
                "rift.app/3"
        ) {
            "project source registration requires rift.app/3"
        }

        val array =
            json.getJSONArray(
                "sources"
            )

        val existing =
            mutableListOf<String>()

        repeat(
            array.length()
        ) { index ->
            existing +=
                canonicalSourcePath(
                    array.getString(
                        index
                    )
                )
        }

        if (canonical in existing) {
            return
        }

        require(
            existing.size <
                MAX_SOURCE_FILES
        ) {
            "project source count is full"
        }

        array.put(canonical)

        workspace.writeText(
            MANIFEST_PATH,
            json.toString(2) +
                "\n"
        )
    }

    private fun canonicalSourcePath(
        raw: String
    ): String {
        val path =
            raw.trim()
                .replace(
                    '\\',
                    '/'
                )

        require(
            path.isNotEmpty() &&
                !path.startsWith("/") &&
                !path.contains("..")
        ) {
            "invalid project source path"
        }

        return path
    }
}
