package com.riftos.app

import android.content.Context
import android.os.StatFs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

/** App-private filesystem capability owned by the local Rift MCP tool host. */
class RiftToolSandbox(context: Context) {
    companion object {
        private const val MAX_TOOL_BYTES = 8 * 1024 * 1024
        private const val MAX_LIST_ENTRIES = 5000
        private const val MAX_WORKSPACE_OPS = 192
        private const val MAX_WORKSPACE_RESULT_BYTES = 700 * 1024
        private const val MAX_WORKSPACE_READ_CHARS = 240_000
        private const val MAX_WORKSPACE_SEARCH_MATCHES = 300
        private const val MAX_PATCH_EDITS = 96
        private const val MAX_WORKSPACE_LIST_ENTRIES = 1200
        private const val MAX_WORKSPACE_SEARCH_FILE_BYTES = 2L * 1024L * 1024L
        private const val MAX_BATCH_ROLLBACK_BYTES = 64L * 1024L * 1024L
        private const val MAX_SEARCH_PREVIEW_CHARS = 320
        private const val LEGACY_ROOT_NAME = "tool-sandbox"
        private const val OLDER_LEGACY_ROOT_NAME = "browser-sandbox"
        private const val WORKSPACE_ROOT = "workspace"
    }

    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val riftFsRoot = File(appContext.filesDir, "riftfs").apply { mkdirs() }
    private val workspaceRoot = prepareCanonicalWorkspace()
    private val transactionRoot = File(appContext.cacheDir, "rift-workspace-transactions").apply {
        deleteRecursively()
        mkdirs()
    }

    fun handleAsync(raw: String, reply: (String) -> Unit) {
        executor.execute {
            val id = runCatching { JSONObject(raw).optString("id") }.getOrDefault("")
            val response = try {
                val request = JSONObject(raw)
                val requestId = request.optString("id")
                val method = request.optString("method")
                require(requestId.isNotBlank()) { "Missing tool request id" }
                require(method.isNotBlank()) { "Missing tool method" }
                val args = request.optJSONObject("args") ?: JSONObject()
                JSONObject()
                    .put("id", requestId)
                    .put("ok", true)
                    .put("value", dispatch(method, args) ?: JSONObject.NULL)
            } catch (error: Throwable) {
                JSONObject()
                    .put("id", id)
                    .put("ok", false)
                    .put("error", error.message ?: error.javaClass.simpleName)
            }
            reply(response.toString())
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    /**
     * The AI-visible filesystem is exactly RiftFS workspace/. Legacy MCP roots are
     * migration sources only and are never addressable by tools.
     */
    private fun prepareCanonicalWorkspace(): File {
        val canonical = File(riftFsRoot, WORKSPACE_ROOT).apply { mkdirs() }
        listOf(LEGACY_ROOT_NAME, OLDER_LEGACY_ROOT_NAME).forEach { legacyName ->
            val legacyRoot = File(riftFsRoot, legacyName)
            val legacyWorkspace = File(legacyRoot, WORKSPACE_ROOT)
            if (legacyWorkspace.exists() && legacyWorkspace.canonicalFile != canonical.canonicalFile) {
                val merged = runCatching { mergeMissingTree(legacyWorkspace, canonical); true }.getOrDefault(false)
                if (merged) runCatching { legacyWorkspace.deleteRecursively() }
            }
        }
        migrateLegacyWorkspaceScaffold(canonical)
        return canonical
    }

    private fun migrateLegacyWorkspaceScaffold(workspace: File) {
        val systemRoot = File(riftFsRoot, "system/riftworkspace").apply { mkdirs() }
        val marker = File(systemRoot, "layout-v2-migrated")
        if (marker.exists()) return

        val legacyMeta = File(workspace, ".rift")
        if (legacyMeta.isDirectory) {
            runCatching { mergeMissingTree(legacyMeta, systemRoot) }
                .onSuccess { runCatching { legacyMeta.deleteRecursively() } }
        }
        listOf("projects", "downloads", "documents", "patches").forEach { name ->
            val directory = File(workspace, name)
            if (directory.isDirectory && directory.listFiles().isNullOrEmpty()) runCatching { directory.delete() }
        }
        runCatching { marker.writeText("1", Charsets.UTF_8) }
    }

    private fun mergeMissingTree(source: File, destination: File) {
        if (source.isFile) {
            if (!destination.exists()) {
                destination.parentFile?.mkdirs()
                source.copyTo(destination, overwrite = false)
            }
            return
        }
        destination.mkdirs()
        source.listFiles()?.forEach { child ->
            mergeMissingTree(child, File(destination, child.name))
        }
    }

    private fun dispatch(method: String, args: JSONObject): Any? = when (method) {
        "sandbox.info" -> info()
        "fs.stat" -> stat(workspacePath(args.optString("path")))
        "fs.list" -> list(workspacePath(args.optString("path")), args.optBoolean("recursive", false))
        "fs.readText" -> readText(workspacePath(args.getString("path")))
        "fs.writeText" -> writeText(workspaceMutationPath(args.getString("path")), args.optString("text"))
        "fs.mkdir" -> mkdir(workspaceMutationPath(args.getString("path")))
        "fs.remove" -> remove(workspaceMutationPath(args.getString("path")))
        "fs.move" -> move(workspaceMutationPath(args.getString("from")), workspaceMutationPath(args.getString("to")), args.optBoolean("overwrite", false))
        "fs.copy" -> copy(workspaceMutationPath(args.getString("from")), workspaceMutationPath(args.getString("to")), args.optBoolean("overwrite", false))
        "workspace.exec" -> workspaceExec(args)
        else -> throw IllegalArgumentException("Unsupported Rift tool sandbox method: $method")
    }

    private fun normalizeSegments(path: String): List<String> {
        val normalized = path.replace('\\', '/').trim('/')
        if (normalized.isBlank()) return emptyList()
        return normalized.split('/').filter { it.isNotBlank() }.map { segment ->
            require(segment != "." && segment != ".." && !segment.contains('\u0000')) { "Invalid path segment" }
            segment
        }
    }

    private fun normalizedPath(path: String): String = normalizeSegments(path).joinToString("/")

    private fun sandboxFile(path: String): File {
        val segments = normalizeSegments(path)
        require(segments.isEmpty() || segments.first() == WORKSPACE_ROOT) {
            "Rift MCP is scoped to $WORKSPACE_ROOT/ only"
        }
        var file = workspaceRoot
        for (segment in segments.drop(if (segments.firstOrNull() == WORKSPACE_ROOT) 1 else 0)) file = File(file, segment)
        val target = file.canonicalFile
        require(isInsideRoot(target)) { "Path escaped Rift MCP workspace" }
        return target
    }

    private fun isInsideRoot(file: File): Boolean {
        val target = file.canonicalFile
        val allowed = workspaceRoot.canonicalFile
        return target == allowed || target.path.startsWith(allowed.path + File.separator)
    }

    private fun workspacePath(raw: String?, defaultRoot: Boolean = true): String {
        val normalized = normalizedPath(raw.orEmpty()).ifBlank { if (defaultRoot) WORKSPACE_ROOT else "" }
        require(normalized == WORKSPACE_ROOT || normalized.startsWith("$WORKSPACE_ROOT/")) {
            "Rift Code Mode is scoped to $WORKSPACE_ROOT/"
        }
        return normalized
    }

    private fun workspaceMutationPath(raw: String): String {
        val normalized = workspacePath(raw, defaultRoot = false)
        require(normalized != WORKSPACE_ROOT) { "Rift Code Mode cannot mutate the workspace root directly" }
        return normalized
    }

    private fun relativePath(file: File): String {
        val target = file.canonicalFile
        val workspace = workspaceRoot.canonicalFile
        require(isInsideRoot(target)) { "Path escaped Rift MCP workspace" }
        if (target == workspace) return WORKSPACE_ROOT
        val suffix = workspace.toPath().relativize(target.toPath()).toString().replace(File.separatorChar, '/')
        return "$WORKSPACE_ROOT/$suffix"
    }

    private fun stat(path: String): JSONObject? {
        val file = sandboxFile(path)
        if (!file.exists()) return null
        return JSONObject()
            .put("path", relativePath(file))
            .put("name", file.name)
            .put("kind", if (file.isDirectory) "directory" else "file")
            .put("size", if (file.isFile) file.length() else 0L)
            .put("modified", file.lastModified())
    }

    private fun list(path: String, recursive: Boolean): JSONArray = listLimited(path, recursive, MAX_LIST_ENTRIES)

    private fun listLimited(path: String, recursive: Boolean, requestedLimit: Int): JSONArray {
        val base = sandboxFile(path)
        require(base.exists() && base.isDirectory) { "Directory not found: $path" }
        val limit = requestedLimit.coerceIn(1, MAX_LIST_ENTRIES)
        val out = JSONArray()
        var count = 0

        fun walk(directory: File) {
            if (count >= limit) return
            directory.listFiles()?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))?.forEach { child ->
                if (count >= limit) return@forEach
                require(isInsideRoot(child)) { "Workspace entry escaped Rift MCP sandbox" }
                count += 1
                out.put(
                    JSONObject()
                        .put("path", relativePath(child))
                        .put("name", child.name)
                        .put("kind", if (child.isDirectory) "directory" else "file")
                        .put("size", if (child.isFile) child.length() else 0L)
                        .put("modified", child.lastModified())
                )
                if (recursive && child.isDirectory) walk(child)
            }
        }

        walk(base)
        return out
    }

    private fun readText(path: String): String {
        val file = sandboxFile(path)
        require(file.isFile) { "File not found: $path" }
        require(file.length() <= MAX_TOOL_BYTES) { "File is too large for Rift MCP" }
        return file.readText(Charsets.UTF_8)
    }

    private fun readTextRange(path: String, startLineRaw: Int, endLineRaw: Int, maxCharsRaw: Int): JSONObject {
        val file = sandboxFile(path)
        require(file.isFile) { "File not found: $path" }
        require(file.length() <= MAX_TOOL_BYTES) { "File is too large for Rift Code Mode" }
        val startLine = startLineRaw.coerceAtLeast(1)
        val maxChars = maxCharsRaw.coerceIn(1_000, MAX_WORKSPACE_READ_CHARS)
        val requestedEnd = if (endLineRaw <= 0) Int.MAX_VALUE else endLineRaw
        require(requestedEnd >= startLine) { "endLine must be >= startLine" }

        val text = StringBuilder()
        var lineNumber = 0
        var lastLine = startLine - 1
        var truncated = false
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                lineNumber += 1
                if (lineNumber < startLine) continue
                if (lineNumber > requestedEnd) break
                val addition = if (text.isEmpty()) line else "\n$line"
                if (text.length + addition.length > maxChars) {
                    val room = (maxChars - text.length).coerceAtLeast(0)
                    if (room > 0) text.append(addition.take(room))
                    truncated = true
                    lastLine = lineNumber
                    break
                }
                text.append(addition)
                lastLine = lineNumber
            }
        }
        if (!truncated && requestedEnd == Int.MAX_VALUE && lastLine >= startLine && text.length >= maxChars) truncated = true
        return JSONObject()
            .put("path", normalizedPath(path))
            .put("startLine", startLine)
            .put("endLine", lastLine)
            .put("text", text.toString())
            .put("truncated", truncated)
    }

    private fun writeText(path: String, text: String): JSONObject {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_TOOL_BYTES) { "Text payload is too large for Rift MCP" }
        val file = sandboxFile(path)
        require(file != workspaceRoot) { "Workspace root is not a file" }
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return stat(path)!!
    }

    private fun replaceText(path: String, find: String, replacement: String, replaceAll: Boolean, expectedCount: Int?): JSONObject {
        require(find.isNotEmpty()) { "replace.find cannot be empty" }
        val file = sandboxFile(path)
        require(file.isFile) { "File not found: $path" }
        require(file.length() <= MAX_TOOL_BYTES) { "File is too large for Rift Code Mode replacement" }
        val source = file.readText(Charsets.UTF_8)
        val count = countOccurrences(source, find)
        if (expectedCount != null) require(count == expectedCount) {
            "Expected $expectedCount occurrence(s) in $path but found $count"
        }
        require(count > 0) { "Text to replace was not found in $path" }
        val output = if (replaceAll) source.replace(find, replacement) else replaceFirstLiteral(source, find, replacement)
        val bytes = output.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_TOOL_BYTES) { "Replacement result is too large for Rift MCP" }
        file.writeBytes(bytes)
        return JSONObject()
            .put("path", normalizedPath(path))
            .put("replacements", if (replaceAll) count else 1)
            .put("size", bytes.size)
            .put("modified", file.lastModified())
    }

    private fun patchText(path: String, edits: JSONArray): JSONObject {
        require(edits.length() in 1..MAX_PATCH_EDITS) { "patch.edits must contain 1..$MAX_PATCH_EDITS exact edits" }
        val file = sandboxFile(path)
        require(file.isFile) { "File not found: $path" }
        require(file.length() <= MAX_TOOL_BYTES) { "File is too large for Rift Code Mode patch" }
        var current = file.readText(Charsets.UTF_8)
        var applied = 0
        for (index in 0 until edits.length()) {
            val edit = edits.optJSONObject(index) ?: throw IllegalArgumentException("patch edit $index must be an object")
            val find = edit.getString("find")
            require(find.isNotEmpty()) { "patch edit $index find cannot be empty" }
            val replacement = edit.optString("replace")
            val expected = if (edit.has("expectedCount")) edit.getInt("expectedCount") else 1
            require(expected >= 1) { "patch edit $index expectedCount must be >= 1" }
            val count = countOccurrences(current, find)
            require(count == expected) { "Patch edit $index expected $expected occurrence(s) in $path but found $count" }
            current = if (edit.optBoolean("all", expected > 1)) {
                current.replace(find, replacement)
            } else {
                replaceFirstLiteral(current, find, replacement)
            }
            applied += if (edit.optBoolean("all", expected > 1)) count else 1
        }
        val bytes = current.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_TOOL_BYTES) { "Patch result is too large for Rift MCP" }
        file.writeBytes(bytes)
        return JSONObject()
            .put("path", normalizedPath(path))
            .put("edits", edits.length())
            .put("replacements", applied)
            .put("size", bytes.size)
            .put("modified", file.lastModified())
    }

    private fun countOccurrences(text: String, needle: String): Int {
        var count = 0
        var cursor = 0
        while (true) {
            val index = text.indexOf(needle, cursor)
            if (index < 0) return count
            count += 1
            cursor = index + needle.length
        }
    }

    private fun replaceFirstLiteral(text: String, find: String, replacement: String): String {
        val index = text.indexOf(find)
        if (index < 0) return text
        return text.substring(0, index) + replacement + text.substring(index + find.length)
    }

    private fun mkdir(path: String): JSONObject {
        val directory = sandboxFile(path)
        require((directory.exists() && directory.isDirectory) || directory.mkdirs()) { "Could not create directory: $path" }
        return stat(path)!!
    }

    private fun remove(path: String): Boolean {
        require(normalizeSegments(path).isNotEmpty()) { "Cannot delete the workspace root" }
        val file = sandboxFile(path)
        if (!file.exists()) return true
        return if (file.isDirectory) file.deleteRecursively() else file.delete()
    }

    private fun move(from: String, to: String, overwrite: Boolean): JSONObject {
        require(normalizeSegments(from).isNotEmpty()) { "Cannot move the workspace root" }
        require(normalizeSegments(to).isNotEmpty()) { "Destination cannot be the workspace root" }
        val source = sandboxFile(from)
        val destination = sandboxFile(to)
        require(source.exists()) { "Source not found: $from" }
        if (destination.exists()) {
            require(overwrite) { "Destination already exists: $to" }
            val deleted = if (destination.isDirectory) destination.deleteRecursively() else destination.delete()
            require(deleted) { "Could not replace destination: $to" }
        }
        destination.parentFile?.mkdirs()
        require(source.renameTo(destination)) { "Could not move $from to $to" }
        return stat(to)!!
    }

    private fun copy(from: String, to: String, overwrite: Boolean): JSONObject {
        require(normalizeSegments(from).isNotEmpty()) { "Cannot copy the workspace root" }
        require(normalizeSegments(to).isNotEmpty()) { "Destination cannot be the workspace root" }
        val source = sandboxFile(from)
        val destination = sandboxFile(to)
        require(source.exists()) { "Source not found: $from" }
        require(source.canonicalFile != destination.canonicalFile) { "Copy source and destination are identical" }
        if (source.isDirectory) {
            require(!destination.canonicalPath.startsWith(source.canonicalPath + File.separator)) { "Cannot copy a directory inside itself" }
        }
        if (destination.exists()) {
            require(overwrite) { "Destination already exists: $to" }
            val deleted = if (destination.isDirectory) destination.deleteRecursively() else destination.delete()
            require(deleted) { "Could not replace destination: $to" }
        }
        destination.parentFile?.mkdirs()
        if (source.isDirectory) {
            require(source.copyRecursively(destination, overwrite = overwrite)) { "Could not copy $from to $to" }
        } else {
            source.copyTo(destination, overwrite = overwrite)
        }
        return stat(to)!!
    }

    /**
     * Rift Code Mode. A model-visible call may contain many local workspace operations.
     * Reads/searches and edits execute on the device in one sandbox turn. Mutations are
     * protected by a lazy copy-on-write rollback transaction: if any operation fails,
     * every mutation performed by this batch is restored before an error is returned.
     */
    private fun workspaceExec(args: JSONObject): JSONObject {
        val operations = args.optJSONArray("operations") ?: throw IllegalArgumentException("operations array is required")
        require(operations.length() in 1..MAX_WORKSPACE_OPS) {
            "Rift Code Mode accepts 1..$MAX_WORKSPACE_OPS operations per batch"
        }

        val transaction = BatchTransaction()
        val results = JSONArray()
        var resultBytes = 0
        var resultTruncated = false
        var currentIndex = -1
        var currentOp = ""

        try {
            for (index in 0 until operations.length()) {
                currentIndex = index
                val operation = operations.optJSONObject(index)
                    ?: throw IllegalArgumentException("Operation $index must be an object")
                currentOp = operation.optString("op").trim().lowercase()
                require(currentOp.isNotBlank()) { "Operation $index is missing op" }

                captureBatchMutation(transaction, currentOp, operation)
                val value = executeWorkspaceOperation(currentOp, operation)
                val row = JSONObject()
                    .put("index", index)
                    .put("op", currentOp)
                    .put("ok", true)
                operation.optString("id").trim().takeIf { it.isNotBlank() }?.let { row.put("id", it.take(120)) }
                row.put("value", value ?: JSONObject.NULL)

                val encoded = row.toString().toByteArray(Charsets.UTF_8).size
                if (resultBytes + encoded <= MAX_WORKSPACE_RESULT_BYTES) {
                    results.put(row)
                    resultBytes += encoded
                } else {
                    resultTruncated = true
                    val summary = JSONObject()
                        .put("index", index)
                        .put("op", currentOp)
                        .put("ok", true)
                        .put("resultOmitted", true)
                    operation.optString("id").trim().takeIf { it.isNotBlank() }?.let { summary.put("id", it.take(120)) }
                    results.put(summary)
                }
            }
        } catch (error: Throwable) {
            val rollbackError = runCatching { transaction.rollback() }.exceptionOrNull()
            transaction.close()
            val detail = error.message ?: error.javaClass.simpleName
            val rollbackDetail = rollbackError?.message?.let { "; rollback error: $it" }.orEmpty()
            throw IllegalArgumentException(
                "Rift Code Mode failed at operation $currentIndex${if (currentOp.isNotBlank()) " ($currentOp)" else ""}; " +
                    "batch mutations were rolled back: $detail$rollbackDetail"
            )
        }

        val mutations = transaction.mutatedPaths()
        transaction.close()
        return JSONObject()
            .put("mode", "rift-code-mode-v1")
            .put("committed", true)
            .put("operations", operations.length())
            .put("mutationTargets", mutations)
            .put("resultTruncated", resultTruncated)
            .put("results", results)
    }

    private fun executeWorkspaceOperation(op: String, args: JSONObject): Any? = when (op) {
        "project" -> projectOverview(workspacePath(args.optString("path", WORKSPACE_ROOT)), args.optInt("limit", 120))
        "stat" -> stat(workspacePath(args.getString("path")))
        "list" -> listLimited(
            workspacePath(args.optString("path", WORKSPACE_ROOT)),
            args.optBoolean("recursive", false),
            args.optInt("limit", 240).coerceIn(1, MAX_WORKSPACE_LIST_ENTRIES)
        )
        "search" -> searchText(
            workspacePath(args.optString("path", WORKSPACE_ROOT)),
            args.getString("query"),
            args.optBoolean("caseSensitive", true),
            args.optInt("maxMatches", 80).coerceIn(1, MAX_WORKSPACE_SEARCH_MATCHES)
        )
        "read" -> readTextRange(
            workspacePath(args.getString("path")),
            args.optInt("startLine", 1),
            args.optInt("endLine", 0),
            args.optInt("maxChars", 100_000)
        )
        "write" -> writeText(workspaceMutationPath(args.getString("path")), args.optString("text"))
        "replace" -> replaceText(
            workspaceMutationPath(args.getString("path")),
            args.getString("find"),
            args.optString("replace"),
            args.optBoolean("all", false),
            if (args.has("expectedCount")) args.getInt("expectedCount") else null
        )
        "patch" -> patchText(
            workspaceMutationPath(args.getString("path")),
            args.optJSONArray("edits") ?: throw IllegalArgumentException("patch.edits array is required")
        )
        "mkdir" -> mkdir(workspaceMutationPath(args.getString("path")))
        "remove" -> remove(workspaceMutationPath(args.getString("path")))
        "move", "rename" -> move(
            workspaceMutationPath(args.getString("from")),
            workspaceMutationPath(args.getString("to")),
            args.optBoolean("overwrite", false)
        )
        "copy" -> copy(
            workspaceMutationPath(args.getString("from")),
            workspaceMutationPath(args.getString("to")),
            args.optBoolean("overwrite", false)
        )
        else -> throw IllegalArgumentException("Unsupported Rift Code Mode operation: $op")
    }

    private fun projectOverview(path: String, requestedLimit: Int): JSONObject {
        val base = sandboxFile(path)
        require(base.exists() && base.isDirectory) { "Workspace directory not found: $path" }
        val limit = requestedLimit.coerceIn(1, 240)
        val children = base.listFiles()?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
        val entries = JSONArray()
        children.take(limit).forEach { child ->
            require(isInsideRoot(child)) { "Workspace entry escaped Rift MCP sandbox" }
            entries.put(
                JSONObject()
                    .put("path", relativePath(child))
                    .put("name", child.name)
                    .put("kind", if (child.isDirectory) "directory" else "file")
                    .put("size", if (child.isFile) child.length() else 0L)
                    .put("modified", child.lastModified())
            )
        }
        return JSONObject()
            .put("root", normalizedPath(path))
            .put("localOnly", true)
            .put("codeMode", "rift-code-mode-v1")
            .put("directChildren", children.size)
            .put("entries", entries)
            .put("truncated", children.size > limit)
            .put("operations", JSONArray(listOf("project", "stat", "list", "search", "read", "write", "replace", "patch", "mkdir", "remove", "move", "rename", "copy")))
    }

    private fun searchText(path: String, query: String, caseSensitive: Boolean, maxMatches: Int): JSONObject {
        require(query.isNotEmpty()) { "search.query cannot be empty" }
        val base = sandboxFile(path)
        require(base.exists()) { "Search path not found: $path" }
        val matches = JSONArray()
        var filesScanned = 0
        var filesSkipped = 0
        var hitLimit = false

        fun scan(file: File) {
            if (hitLimit) return
            require(isInsideRoot(file)) { "Workspace search escaped Rift MCP sandbox" }
            if (!file.isFile) return
            if (file.length() > MAX_WORKSPACE_SEARCH_FILE_BYTES) {
                filesSkipped += 1
                return
            }
            val bytes = runCatching { file.readBytes() }.getOrElse {
                filesSkipped += 1
                return
            }
            if (bytes.take(4096).any { it.toInt() == 0 }) {
                filesSkipped += 1
                return
            }
            filesScanned += 1
            val text = bytes.toString(Charsets.UTF_8)
            val needle = if (caseSensitive) query else query.lowercase()
            val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            for ((lineIndex, line) in lines.withIndex()) {
                val haystack = if (caseSensitive) line else line.lowercase()
                var cursor = 0
                while (cursor <= haystack.length - needle.length) {
                    val column = haystack.indexOf(needle, cursor)
                    if (column < 0) break
                    val preview = line.trim().let { if (it.length <= MAX_SEARCH_PREVIEW_CHARS) it else it.take(MAX_SEARCH_PREVIEW_CHARS) + "…" }
                    matches.put(
                        JSONObject()
                            .put("path", relativePath(file))
                            .put("line", lineIndex + 1)
                            .put("column", column + 1)
                            .put("preview", preview)
                    )
                    if (matches.length() >= maxMatches) {
                        hitLimit = true
                        return
                    }
                    cursor = column + query.length.coerceAtLeast(1)
                }
            }
        }

        if (base.isFile) {
            scan(base)
        } else {
            base.walkTopDown().onEnter { directory ->
                require(isInsideRoot(directory)) { "Workspace search escaped Rift MCP sandbox" }
                true
            }.forEach { file ->
                if (!hitLimit) scan(file)
            }
        }

        return JSONObject()
            .put("path", normalizedPath(path))
            .put("query", query)
            .put("caseSensitive", caseSensitive)
            .put("matches", matches)
            .put("filesScanned", filesScanned)
            .put("filesSkipped", filesSkipped)
            .put("truncated", hitLimit)
    }

    private fun captureBatchMutation(transaction: BatchTransaction, op: String, args: JSONObject) {
        when (op) {
            "write", "replace", "patch", "mkdir", "remove" -> transaction.capture(workspaceMutationPath(args.getString("path")))
            "move", "rename" -> {
                transaction.capture(workspaceMutationPath(args.getString("from")))
                transaction.capture(workspaceMutationPath(args.getString("to")))
            }
            "copy" -> transaction.capture(workspaceMutationPath(args.getString("to")))
        }
    }

    private data class BatchSnapshot(val path: String, val kind: String, val backup: File?)

    private inner class BatchTransaction {
        private val dir = File(transactionRoot, "batch-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}").apply { mkdirs() }
        private val snapshots = LinkedHashMap<String, BatchSnapshot>()
        private var backupBytes = 0L

        fun capture(rawPath: String) {
            val path = workspacePath(rawPath)
            if (snapshots.containsKey(path)) return
            if (snapshots.keys.any { ancestor -> path.startsWith("$ancestor/") }) return
            val source = sandboxFile(path)
            val kind = when {
                !source.exists() -> "missing"
                source.isDirectory -> "directory"
                else -> "file"
            }
            val backup = if (kind == "missing") null else File(dir, sha256(path))
            if (backup != null) {
                val bytes = if (source.isDirectory) treeBytes(source) else source.length()
                backupBytes += bytes
                require(backupBytes <= MAX_BATCH_ROLLBACK_BYTES) {
                    "Rift Code Mode rollback set exceeds ${MAX_BATCH_ROLLBACK_BYTES / (1024 * 1024)} MiB"
                }
                backup.parentFile?.mkdirs()
                if (source.isDirectory) {
                    require(source.copyRecursively(backup, overwrite = true)) { "Could not snapshot directory for batch rollback: $path" }
                } else {
                    source.copyTo(backup, overwrite = true)
                }
            }
            snapshots[path] = BatchSnapshot(path, kind, backup)
        }

        fun mutatedPaths(): JSONArray {
            val out = JSONArray()
            snapshots.keys.forEach(out::put)
            return out
        }

        fun rollback() {
            snapshots.values
                .sortedBy { it.path.count { ch -> ch == '/' } }
                .forEach { snapshot ->
                    val target = sandboxFile(snapshot.path)
                    if (target.exists()) {
                        val removed = if (target.isDirectory) target.deleteRecursively() else target.delete()
                        require(removed) { "Could not clear ${snapshot.path} during batch rollback" }
                    }
                    when (snapshot.kind) {
                        "missing" -> Unit
                        "file" -> {
                            target.parentFile?.mkdirs()
                            snapshot.backup!!.copyTo(target, overwrite = true)
                        }
                        "directory" -> {
                            target.parentFile?.mkdirs()
                            require(snapshot.backup!!.copyRecursively(target, overwrite = true)) {
                                "Could not restore ${snapshot.path} during batch rollback"
                            }
                        }
                    }
                }
        }

        fun close() {
            runCatching { dir.deleteRecursively() }
        }
    }

    private fun treeBytes(dir: File): Long {
        var total = 0L
        dir.walkTopDown().forEach { file ->
            require(isInsideRoot(file)) { "Rollback snapshot escaped Rift MCP sandbox" }
            if (file.isFile) {
                total += file.length()
                require(total <= MAX_BATCH_ROLLBACK_BYTES) { "Rollback snapshot is too large" }
            }
        }
        return total
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun info(): JSONObject {
        val stats = StatFs(workspaceRoot.absolutePath)
        return JSONObject()
            .put("root", "riftfs/$WORKSPACE_ROOT")
            .put("workspaceRoot", "riftfs/$WORKSPACE_ROOT")
            .put("scope", "workspace-only")
            .put("owner", "Rift MCP")
            .put("writable", true)
            .put("maxToolBytes", MAX_TOOL_BYTES)
            .put("freeBytes", stats.availableBytes)
            .put("totalBytes", stats.totalBytes)
            .put("codeMode", JSONObject()
                .put("version", "rift-code-mode-v1")
                .put("workspaceRoot", WORKSPACE_ROOT)
                .put("maxOperations", MAX_WORKSPACE_OPS)
                .put("maxResultBytes", MAX_WORKSPACE_RESULT_BYTES)
                .put("transactional", true))
            .put("capabilities", JSONArray(listOf(
                "stat", "list", "readText", "writeText", "mkdir", "remove", "move", "copy", "workspaceExec"
            )))
    }
}
