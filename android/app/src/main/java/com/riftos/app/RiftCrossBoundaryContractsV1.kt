package com.riftos.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** N1.8.3 read-only cross-boundary contract oracle. */
internal class RiftCrossBoundaryContractsV1(
    private val workspaceRoot: File
) {
    companion object {
        const val SCHEMA = "rift-cross-boundary-contracts-v1"
        const val PHASE = "N1.8.3"
        private const val MAX_FILES = 4_096
        private const val MAX_FILE_BYTES = 2L * 1024L * 1024L
        private const val MAX_TOTAL_BYTES = 64L * 1024L * 1024L
        private const val MAX_FINDINGS = 1_024
        private const val MAX_PREVIEW_ROWS = 240
        private val TEXT_EXTENSIONS = setOf(
            "kt", "java", "kts", "gradle", "xml", "cpp", "cc", "cxx", "c", "h", "hpp",
            "js", "mjs", "json", "jsonc", "properties"
        )
        private val IGNORED_DIRS = setOf(
            ".git", ".gradle", ".idea", "build", "dist", "node_modules", "out", "target",
            "vendor", "venv", ".venv", "__pycache__", ".cache"
        )
    }

    private data class TextFile(val file: File, val path: String, val text: String)
    private data class Finding(
        val ruleId: String,
        val category: String,
        val path: String,
        val message: String
    )

    fun analyze(projectRoot: File): JSONObject {
        val root = projectRoot.canonicalFile
        require(root.isDirectory) { "Project root must be a directory" }
        require(root.toPath().startsWith(workspaceRoot.canonicalFile.toPath())) { "Project root escaped workspace" }

        val incomplete = linkedSetOf<String>()
        val files = mutableListOf<TextFile>()
        var totalBytes = 0L

        root.walkTopDown()
            .onEnter { it == root || it.name !in IGNORED_DIRS }
            .filter { it.isFile }
            .forEach { file ->
                if (files.size >= MAX_FILES) {
                    incomplete += "contracts-file-bound"
                    return@forEach
                }
                if (file.extension.lowercase() !in TEXT_EXTENSIONS && file.name !in setOf("CMakeLists.txt", "AndroidManifest.xml")) {
                    return@forEach
                }
                val size = file.length()
                if (size > MAX_FILE_BYTES) {
                    incomplete += "contracts-file-size-bound"
                    return@forEach
                }
                if (totalBytes > MAX_TOTAL_BYTES - size) {
                    incomplete += "contracts-byte-bound"
                    return@forEach
                }
                val text = runCatching { file.readText(Charsets.UTF_8) }.getOrElse {
                    incomplete += "contracts-read-failure"
                    return@forEach
                }
                files += TextFile(file, relative(file), text)
                totalBytes += size
            }

        val scanIncomplete = incomplete.isNotEmpty()
        val findings = mutableListOf<Finding>()
        val evidence = mutableListOf<JSONObject>()

        fun finding(ruleId: String, category: String, path: String, message: String) {
            if (scanIncomplete) return
            if (findings.size >= MAX_FINDINGS) {
                incomplete += "contracts-finding-bound"
            } else {
                findings += Finding(ruleId, category, path, message)
            }
        }

        checkAndroidBuild(files, evidence, ::finding)
        checkManifestComponents(files, evidence, ::finding)
        checkNativeLibraries(files, evidence, ::finding)
        checkJniPairs(files, evidence, ::finding)
        checkToolRegistry(files, evidence, ::finding)
        checkProtocolMirrors(files, evidence, ::finding)
        checkTimeoutChain(files, evidence, ::finding)

        val orderedFindings = findings.sortedWith(compareBy({ it.ruleId }, { it.path }, { it.message }))
        val orderedEvidence = evidence.sortedBy { it.toString() }
        val shaInput = buildString {
            append(SCHEMA).append('\n')
            incomplete.sorted().forEach { append("incomplete|").append(it).append('\n') }
            orderedFindings.forEach {
                append("finding|").append(it.ruleId).append('|').append(it.category).append('|')
                    .append(it.path).append('|').append(it.message).append('\n')
            }
            orderedEvidence.forEach { append("evidence|").append(it.toString()).append('\n') }
        }

        val findingRows = JSONArray()
        orderedFindings.take(MAX_PREVIEW_ROWS).forEach {
            findingRows.put(JSONObject()
                .put("ruleId", it.ruleId)
                .put("category", it.category)
                .put("severity", "error")
                .put("path", it.path)
                .put("message", it.message))
        }
        val evidenceRows = JSONArray()
        orderedEvidence.take(MAX_PREVIEW_ROWS).forEach(evidenceRows::put)

        return JSONObject()
            .put("schema", SCHEMA)
            .put("phase", PHASE)
            .put("view", "contracts")
            .put("authority", "evidence-only")
            .put("projectRoot", relative(root))
            .put("complete", incomplete.isEmpty())
            .put("clean", incomplete.isEmpty() && orderedFindings.isEmpty())
            .put("incompleteReasons", JSONArray(incomplete.sorted()))
            .put("partialFindingsSuppressed", scanIncomplete)
            .put("contractsSha256", sha256(shaInput))
            .put("counts", JSONObject()
                .put("filesScanned", files.size)
                .put("bytesScanned", totalBytes)
                .put("contracts", orderedEvidence.size)
                .put("findings", orderedFindings.size))
            .put("bounds", JSONObject()
                .put("maxFiles", MAX_FILES)
                .put("maxFileBytes", MAX_FILE_BYTES)
                .put("maxTotalBytes", MAX_TOTAL_BYTES)
                .put("maxFindings", MAX_FINDINGS)
                .put("maxPreviewRows", MAX_PREVIEW_ROWS))
            .put("preview", JSONObject()
                .put("findingRows", findingRows.length())
                .put("evidenceRows", evidenceRows.length())
                .put("findingsTruncated", orderedFindings.size > MAX_PREVIEW_ROWS)
                .put("evidenceTruncated", orderedEvidence.size > MAX_PREVIEW_ROWS))
            .put("findings", findingRows)
            .put("evidence", evidenceRows)
    }

    private fun checkAndroidBuild(
        files: List<TextFile>,
        evidence: MutableList<JSONObject>,
        finding: (String, String, String, String) -> Unit
    ) {
        files.filter { it.file.name == "build.gradle.kts" || it.file.name == "build.gradle" }.forEach { gradle ->
            val namespace = Regex("""\bnamespace\s*=\s*["']([^"']+)["']""").find(gradle.text)?.groupValues?.get(1)
            val applicationId = Regex("""\bapplicationId\s*=\s*["']([^"']+)["']""").find(gradle.text)?.groupValues?.get(1)
            if (namespace != null && applicationId != null) {
                evidence += row("android-namespace-application-id", "config-build", "equal",
                    listOf(gradle.path to namespace, gradle.path to applicationId))
                if (namespace != applicationId) {
                    finding("android-namespace-application-id-mismatch", "config-build", gradle.path,
                        "Android namespace '$namespace' does not match applicationId '$applicationId'.")
                }
            }
            Regex("""path\s*=\s*file\(["']([^"']+)["']\)""").findAll(gradle.text).forEach { match ->
                val declared = match.groupValues[1]
                val target = File(gradle.file.parentFile, declared)
                val exists = target.isFile
                evidence += row("android-cmake-path", "manifest-build", "exists",
                    listOf(gradle.path to declared, relative(target) to exists.toString()))
                if (!exists) {
                    finding("android-cmake-path-missing", "manifest-build", gradle.path,
                        "Gradle externalNativeBuild path '$declared' does not resolve to a file.")
                }
            }
        }
    }

    private fun checkManifestComponents(
        files: List<TextFile>,
        evidence: MutableList<JSONObject>,
        finding: (String, String, String, String) -> Unit
    ) {
        val managed = files.filter { it.file.extension.lowercase() in setOf("kt", "java") }
        files.filter { it.file.name == "AndroidManifest.xml" }.forEach { manifest ->
            val moduleDir = manifest.file.parentFile?.parentFile?.parentFile
            val gradle = files.firstOrNull {
                (it.file.name == "build.gradle.kts" || it.file.name == "build.gradle") &&
                    it.file.parentFile?.canonicalFile == moduleDir?.canonicalFile
            }
            val namespace = gradle?.let {
                Regex("""\bnamespace\s*=\s*["']([^"']+)["']""").find(it.text)?.groupValues?.get(1)
            }.orEmpty()
            Regex("""<(activity|service|receiver|provider)\b[^>]*android:name\s*=\s*["']([^"']+)["']""")
                .findAll(manifest.text).forEach { match ->
                    val kind = match.groupValues[1]
                    val raw = match.groupValues[2]
                    val fqcn = when {
                        raw.startsWith(".") && namespace.isNotBlank() -> namespace + raw
                        "." !in raw && namespace.isNotBlank() -> "$namespace.$raw"
                        else -> raw
                    }
                    val pkg = fqcn.substringBeforeLast('.', "")
                    val simple = fqcn.substringAfterLast('.')
                    val resolved = managed.any { source ->
                        val sourcePkg = Regex("""(?m)^\s*package\s+([A-Za-z0-9_.]+)""")
                            .find(source.text)?.groupValues?.get(1).orEmpty()
                        sourcePkg == pkg &&
                            Regex("""\b(class|object)\s+${Regex.escape(simple)}\b""").containsMatchIn(source.text)
                    }
                    evidence += row("android-manifest-component", "manifest-source", "resolves",
                        listOf(manifest.path to "$kind:$fqcn", "resolved" to resolved.toString()))
                    if (!resolved) {
                        finding("android-manifest-component-missing", "manifest-source", manifest.path,
                            "Manifest $kind '$fqcn' has no matching managed class.")
                    }
                }
        }
    }

    private fun checkNativeLibraries(
        files: List<TextFile>,
        evidence: MutableList<JSONObject>,
        finding: (String, String, String, String) -> Unit
    ) {
        val libraries = linkedSetOf<String>()
        files.filter { it.file.name == "CMakeLists.txt" }.forEach { cmake ->
            Regex("""add_library\s*\(\s*([A-Za-z0-9_.+\-]+)\s+(?:SHARED|STATIC|MODULE|OBJECT)\b""", RegexOption.IGNORE_CASE)
                .findAll(cmake.text).forEach { libraries += it.groupValues[1] }
        }
        files.filter { it.file.extension.lowercase() in setOf("kt", "java") }.forEach { source ->
            val codeMask = RiftSourceIntelligenceV2.referenceCodeMask(source.path, source.text)
            Regex("""System\.loadLibrary\(["']([^"']+)["']\)""")
                .findAll(source.text)
                .filter { codeMask.getOrNull(it.range.first) == true }
                .forEach { match ->
                    val library = match.groupValues[1]
                    val resolved = library in libraries
                    evidence += row("native-library-load", "build-native", "resolves",
                        listOf(source.path to library, "resolved" to resolved.toString()))
                    if (!resolved) {
                        finding("native-library-producer-missing", "build-native", source.path,
                            "System.loadLibrary('$library') has no matching CMake add_library producer.")
                    }
                }
        }
    }

    private fun checkJniPairs(
        files: List<TextFile>,
        evidence: MutableList<JSONObject>,
        finding: (String, String, String, String) -> Unit
    ) {
        data class ManagedNative(val path: String, val owner: String, val function: String, val symbol: String)

        val managed = mutableListOf<ManagedNative>()
        val ownerPattern = Regex("""\b(class|object)\s+([A-Za-z_][A-Za-z0-9_]*)\b""")
        files.filter { it.file.extension.lowercase() in setOf("kt", "java") }.forEach { source ->
            val codeMask = RiftSourceIntelligenceV2.referenceCodeMask(source.path, source.text)
            fun isCode(offset: Int): Boolean = codeMask.getOrNull(offset) == true
            val pkg = Regex("""(?m)^\s*package\s+([A-Za-z0-9_.]+)""")
                .findAll(source.text)
                .firstOrNull { isCode(it.range.first) }
                ?.groupValues?.get(1).orEmpty()
            val braceDepth = IntArray(source.text.length + 1)
            var depth = 0
            for (index in source.text.indices) {
                braceDepth[index] = depth
                if (!isCode(index)) continue
                when (source.text[index]) {
                    '{' -> depth += 1
                    '}' -> depth = maxOf(0, depth - 1)
                }
            }
            braceDepth[source.text.length] = depth
            data class Owner(val name: String, val offset: Int, val depth: Int)
            val owners = ownerPattern.findAll(source.text)
                .filter { isCode(it.range.first) }
                .map { Owner(it.groupValues[2], it.range.first, braceDepth[it.range.first]) }
                .toList()
            fun ownerAt(offset: Int): String? {
                val declarationDepth = braceDepth[offset]
                return owners
                    .filter { it.offset < offset && it.depth < declarationDepth }
                    .maxWithOrNull(compareBy<Owner>({ it.depth }, { it.offset }))
                    ?.name
            }

            val declarations = mutableListOf<Pair<Int, String>>()
            Regex("""\bexternal\s+fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
                .findAll(source.text)
                .filter { isCode(it.range.first) }
                .forEach { declarations += it.range.first to it.groupValues[1] }
            Regex("""\bnative\b[^;{}=\n]*?\b([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
                .findAll(source.text)
                .filter { isCode(it.range.first) }
                .forEach { declarations += it.range.first to it.groupValues[1] }

            declarations.distinct().sortedBy { it.first }.forEach declarationLoop@ { (offset, function) ->
                val owner = ownerAt(offset) ?: return@declarationLoop
                val symbol = "Java_${jniMangle(pkg)}_${jniMangle(owner)}_${jniMangle(function)}"
                managed += ManagedNative(source.path, owner, function, symbol)
            }
        }

        val exports = mutableListOf<Pair<String, String>>()
        files.filter { it.file.extension.lowercase() in setOf("c", "cc", "cpp", "cxx", "h", "hpp") }.forEach { source ->
            val codeMask = RiftSourceIntelligenceV2.referenceCodeMask(source.path, source.text)
            Regex("""\b(Java_[A-Za-z0-9_]+)(?:__[^\s(]+)?\s*\(""")
                .findAll(source.text)
                .filter { codeMask.getOrNull(it.range.first) == true }
                .forEach { match -> exports += source.path to match.groupValues[1] }
        }

        managed.sortedWith(compareBy({ it.path }, { it.owner }, { it.function })).forEach { declaration ->
            val resolved = exports.any { (_, export) ->
                export == declaration.symbol || export.startsWith("${declaration.symbol}__")
            }
            evidence += row("jni-managed-native-pair", "jni-native", "resolves",
                listOf(
                    declaration.path to "${declaration.owner}.${declaration.function}",
                    "jni-symbol" to declaration.symbol,
                    "resolved" to resolved.toString()
                ))
            if (!resolved) {
                finding("jni-native-symbol-missing", "jni-native", declaration.path,
                    "Managed native declaration '${declaration.owner}.${declaration.function}' has no matching JNI symbol '${declaration.symbol}'.")
            }
        }

        exports.sortedWith(compareBy({ it.first }, { it.second })).forEach { (path, export) ->
            val resolved = managed.any { declaration ->
                export == declaration.symbol || export.startsWith("${declaration.symbol}__")
            }
            evidence += row("jni-native-managed-pair", "jni-native", "resolves",
                listOf(path to export, "managed-declaration" to resolved.toString()))
            if (!resolved) {
                finding("jni-managed-declaration-missing", "jni-native", path,
                    "JNI export '$export' has no matching Kotlin external or Java native declaration.")
            }
        }
    }

    private fun checkToolRegistry(
        files: List<TextFile>,
        evidence: MutableList<JSONObject>,
        finding: (String, String, String, String) -> Unit
    ) {
        val host = files.firstOrNull { it.file.name == "RiftToolHost.kt" } ?: return
        val definitions = Regex("""\.put\(tool\(\s*["'](rift_[a-z0-9_]+)["']""")
            .findAll(host.text).map { it.groupValues[1] }.toSortedSet()
        val methodBlock = host.text.substringAfter("private fun methodFor(name: String): String? = when (name) {", "")
            .substringBefore("\n    }", "")
        val dispatched = Regex("""["'](rift_[a-z0-9_]+)["']\s*->""")
            .findAll(methodBlock).map { it.groupValues[1] }.toMutableSet()
        val specialDispatched = linkedSetOf<String>()
        if (host.text.contains("if (name == \"rift_debug\")") && host.text.contains("debugHub.query(")) {
            specialDispatched += "rift_debug"
        }
        val covered = dispatched + specialDispatched

        evidence += row("mcp-tool-registry-dispatch", "registry", "set-covered",
            listOf(host.path to "definitions=${definitions.size}", host.path to "covered=${definitions.count { it in covered }}"))
        definitions.filterNot { it in covered }.forEach { tool ->
            finding("mcp-tool-without-dispatch", "registry", host.path,
                "Advertised MCP tool '$tool' has no normal or explicit special dispatch path.")
        }
    }

    private fun checkProtocolMirrors(
        files: List<TextFile>,
        evidence: MutableList<JSONObject>,
        finding: (String, String, String, String) -> Unit
    ) {
        mirror(files, evidence, finding,
            "mcp-relay-protocol", "protocol",
            listOf(
                Triple("relay/src/index.js", "const PROTOCOL = \"", "\""),
                Triple("RiftMcpRelayClient.kt", "private const val PROTOCOL = \"", "\"")
            )
        )
        mirror(files, evidence, finding,
            "cli-event-schema", "protocol",
            listOf(
                Triple("RiftCliEventBus.kt", "const val SCHEMA = \"", "\""),
                Triple("relay/src/index.js", "event.schema !== \"", "\"")
            )
        )
    }

    private fun checkTimeoutChain(
        files: List<TextFile>,
        evidence: MutableList<JSONObject>,
        finding: (String, String, String, String) -> Unit
    ) {
        val specs = listOf(
            Triple("RiftToolSandbox.kt", "private const val REQUEST_TIMEOUT_MS = ", "\n"),
            Triple("RiftNativeShell.kt", "private const val SHELL_TIMEOUT_MS = ", "\n"),
            Triple("RiftMcpServer.kt", "private const val REQUEST_TIMEOUT_MS = ", "\n"),
            Triple("RiftMcpRelayClient.kt", "private const val REQUEST_FORWARD_TIMEOUT_MS = ", "\n"),
            Triple("relay/src/index.js", "const REQUEST_TIMEOUT_MS = ", ";")
        )
        val values = specs.mapNotNull { (suffix, prefix, end) ->
            extract(files, suffix, prefix, end)?.let { it.first to it.second }
        }
        if (values.size != specs.size) {
            finding("async-timeout-contract-missing", "limit", "workspace",
                "Could not resolve all five timeout-chain members.")
            return
        }
        evidence += row("async-timeout-chain", "limit", "strictly-increasing", values)
        val numbers = values.map { parseLong(it.second) }
        if (numbers.any { it == null } || numbers.filterNotNull().zipWithNext().any { (a, b) -> a >= b }) {
            finding("async-timeout-order-mismatch", "limit", "workspace",
                "Timeout chain must be strictly increasing from sandbox to relay.")
        }
    }

    private fun mirror(
        files: List<TextFile>,
        evidence: MutableList<JSONObject>,
        finding: (String, String, String, String) -> Unit,
        id: String,
        category: String,
        specs: List<Triple<String, String, String>>
    ) {
        val values = specs.mapNotNull { (suffix, prefix, end) -> extract(files, suffix, prefix, end) }
        if (values.size != specs.size) {
            finding("contract-member-value-missing", category, "workspace",
                "Contract '$id' could not resolve all members.")
            return
        }
        evidence += row(id, category, "equal", values)
        if (values.map { normalize(it.second) }.distinct().size != 1) {
            finding("contract-mirror-mismatch", category, "workspace",
                "Contract '$id' members do not agree.")
        }
    }

    private fun extract(
        files: List<TextFile>,
        suffix: String,
        prefix: String,
        terminator: String
    ): Pair<String, String>? {
        val file = files.firstOrNull { it.path.endsWith(suffix) } ?: return null
        val start = file.text.indexOf(prefix)
        if (start < 0) return null
        val valueStart = start + prefix.length
        val end = file.text.indexOf(terminator, valueStart)
        if (end < valueStart) return null
        return file.path to file.text.substring(valueStart, end).trim()
    }

    private fun row(id: String, category: String, relation: String, members: List<Pair<String, String>>): JSONObject {
        val rows = JSONArray()
        members.forEach { (path, value) -> rows.put(JSONObject().put("path", path).put("value", value)) }
        return JSONObject().put("id", id).put("category", category).put("relation", relation).put("members", rows)
    }

    private fun normalize(value: String): String =
        value.trim().removeSuffix(";").removeSuffix(",").removeSuffix("L").trim()
            .removeSurrounding("\"").removeSurrounding("'")

    private fun parseLong(value: String): Long? = normalize(value).replace("_", "").toLongOrNull()

    private fun jniMangle(value: String): String = buildString {
        value.forEach { ch ->
            when (ch) {
                '.', '/' -> append('_')
                '_' -> append("_1")
                ';' -> append("_2")
                '[' -> append("_3")
                else -> if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9') {
                    append(ch)
                } else {
                    append("_0%04x".format(ch.code))
                }
            }
        }
    }

    private fun relative(file: File): String {
        val rootPath = workspaceRoot.canonicalFile.toPath()
        val filePath = file.canonicalFile.toPath()
        return if (filePath.startsWith(rootPath)) {
            "workspace/" + rootPath.relativize(filePath).toString().replace(File.separatorChar, '/')
        } else {
            file.canonicalPath.replace(File.separatorChar, '/')
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
