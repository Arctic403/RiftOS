package com.riftos.app

import android.content.Context
import android.os.StatFs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.BufferedOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
        private const val MAX_SYMBOL_RESULTS = 240
        private const val MAX_REFERENCE_RESULTS = 400
        private const val MAX_INDEX_FILES = 25_000
        private const val MAX_INDEX_FILE_BYTES = 2L * 1024L * 1024L
        private const val MAX_HUNKS = 128
        private const val MAX_SNAPSHOT_FILES = 50_000
        private const val MAX_ARCHIVE_ENTRIES = 50_000
        private const val MAX_ARCHIVE_SOURCE_BYTES = 256L * 1024L * 1024L
        private const val LEGACY_ROOT_NAME = "tool-sandbox"
        private const val OLDER_LEGACY_ROOT_NAME = "browser-sandbox"
        private const val WORKSPACE_ROOT = "workspace"
        private val WORKSPACE_OPS = setOf("project", "snapshot", "stat", "list", "search", "symbols", "references", "read", "read_range", "read_symbol", "write", "replace", "patch", "patch_range", "apply_hunks", "mkdir", "remove", "move", "rename", "copy", "archive")
    }

    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val riftFsRoot = File(appContext.filesDir, "riftfs").apply { mkdirs() }
    private val workspaceRoot = prepareCanonicalWorkspace()
    private val transactionRoot = File(appContext.cacheDir, "rift-workspace-transactions").apply {
        deleteRecursively()
        mkdirs()
    }
    private val symbolIndex = LinkedHashMap<String, IndexedFile>()
    private val ignoredDirectoryNames = setOf(
        ".git", ".gradle", ".idea", ".next", ".cache", ".turbo", ".parcel-cache",
        "node_modules", "build", "dist", "out", "target", "vendor", "Pods",
        ".venv", "venv", "__pycache__", "coverage", ".pytest_cache", ".mypy_cache"
    )
    private val ignoredFileNames = setOf(".DS_Store", "Thumbs.db")
    private val binaryExtensions = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "ico", "pdf", "zip", "gz", "tgz", "7z", "rar",
        "apk", "aab", "jar", "aar", "dex", "so", "dll", "exe", "bin", "class", "wasm", "woff",
        "woff2", "ttf", "otf", "mp3", "wav", "ogg", "mp4", "mov", "avi", "sqlite", "db"
    )

    private data class SymbolRecord(
        val name: String,
        val kind: String,
        val path: String,
        val line: Int,
        val endLine: Int,
        val signature: String
    )

    private data class IndexedFile(
        val modified: Long,
        val size: Long,
        val symbols: List<SymbolRecord>
    )

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
        "workspace.audit" -> audit(args.optString("path"))
        "workspace.scan" -> scan(args.optString("path"), args.optString("mode", "all"))
        "workspace.viewState" -> RiftWorkspaceLiveState.snapshot()
        else -> throw IllegalArgumentException("Unsupported Rift tool sandbox method: $method")
    }

    private fun audit(path: String): JSONObject {
        val root = sandboxFile(workspacePath(path))
        val result = JSONObject()
            .put("ok", true)
            .put("path", relativePath(root))
            .put("scanner", "rift-audit-v1")
        val findings = JSONArray()
        var files = 0
        if (root.exists()) {
            root.walkTopDown().forEach { file ->
                if (file.isFile) {
                    files++
                    val name = file.name.lowercase()
                    if (name.contains("secret") || name.contains("password") || name.contains("token")) {
                        findings.put(JSONObject().put("severity", "medium").put("category", "security").put("file", relativePath(file)).put("issue", "sensitive-looking filename"))
                    }
                }
            }
        }
        return result.put("filesScanned", files).put("findings", findings)
    }

    private fun scan(path: String, mode: String): JSONObject {
        val result = audit(path)
        result.put("mode", mode.lowercase())
        result.put("scanVersion", "rift-scan-v1")
        return result
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
        val out = JSONObject()
            .put("path", relativePath(file))
            .put("name", file.name)
            .put("kind", if (file.isDirectory) "directory" else "file")
            .put("size", if (file.isFile) file.length() else 0L)
            .put("modified", file.lastModified())
        if (file.isFile && file.length() <= MAX_TOOL_BYTES) out.put("sha256", fileSha256(file))
        return out
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
        val selected = text.toString()
        return JSONObject()
            .put("path", normalizedPath(path))
            .put("startLine", startLine)
            .put("endLine", lastLine)
            .put("text", selected)
            .put("truncated", truncated)
            .put("sha256", fileSha256(file))
            .put("rangeSha256", sha256(selected))
    }

    private fun writeText(path: String, text: String): JSONObject {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_TOOL_BYTES) { "Text payload is too large for Rift MCP" }
        val file = sandboxFile(path)
        require(file != workspaceRoot) { "Workspace root is not a file" }
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        invalidateIndex(path)
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
        invalidateIndex(path)
        return JSONObject()
            .put("path", normalizedPath(path))
            .put("replacements", if (replaceAll) count else 1)
            .put("size", bytes.size)
            .put("modified", file.lastModified())
            .put("sha256", fileSha256(file))
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
        invalidateIndex(path)
        return JSONObject()
            .put("path", normalizedPath(path))
            .put("edits", edits.length())
            .put("replacements", applied)
            .put("size", bytes.size)
            .put("modified", file.lastModified())
            .put("sha256", fileSha256(file))
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
        invalidateIndex(path)
        return stat(path)!!
    }

    private fun remove(path: String): Boolean {
        require(normalizeSegments(path).isNotEmpty()) { "Cannot delete the workspace root" }
        val file = sandboxFile(path)
        if (!file.exists()) return true
        val removed = if (file.isDirectory) file.deleteRecursively() else file.delete()
        if (removed) invalidateIndex(path)
        return removed
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
        invalidateIndex(from)
        invalidateIndex(to)
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
        invalidateIndex(to)
        return stat(to)!!
    }

    /**
     * Rift Code Mode. A model-visible call may contain many local workspace operations.
     * Reads/searches and edits execute on the device in one sandbox turn. Mutations are
     * protected by a lazy copy-on-write rollback transaction: if any operation fails,
     * every mutation performed by this batch is restored before an error is returned.
     */
    private fun normalizeWorkspaceOperation(operation: JSONObject): JSONObject {
        if (operation.optString("op").isNotBlank()) return operation
        val keys = mutableListOf<String>()
        val iterator = operation.keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            if (key != "id") keys += key
        }
        if (keys.size != 1) return operation
        val key = keys.single()
        val op = key.trim().lowercase()
        val nested = operation.optJSONObject(key) ?: return operation
        if (op !in WORKSPACE_OPS) return operation
        return JSONObject(nested.toString()).put("op", op).also { row ->
            if (operation.has("id") && !row.has("id")) row.put("id", operation.opt("id"))
        }
    }

    private fun workspaceExec(args: JSONObject): JSONObject {
        val operations = args.optJSONArray("operations") ?: throw IllegalArgumentException("operations array is required")
        require(operations.length() in 1..MAX_WORKSPACE_OPS) {
            "Rift Code Mode accepts 1..$MAX_WORKSPACE_OPS operations per batch"
        }
        val dryRun = args.optBoolean("dryRun", false)
        val snapshotPath = workspacePath(args.optString("snapshotPath", WORKSPACE_ROOT))
        val expectedSnapshot = args.optString("expectedSnapshot").trim()
        if (expectedSnapshot.isNotEmpty()) {
            val actual = projectSnapshot(snapshotPath).getString("id")
            require(actual == expectedSnapshot) { "Workspace snapshot changed at $snapshotPath; expected $expectedSnapshot but found $actual" }
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
                val rawOperation = operations.optJSONObject(index)
                    ?: throw IllegalArgumentException("Operation $index must be an object")
                val operation = normalizeWorkspaceOperation(rawOperation)
                currentOp = operation.optString("op").trim().lowercase()
                require(currentOp.isNotBlank()) {
                    "Operation $index is missing op. Use flat JSON such as {\"op\":\"stat\",\"path\":\"workspace/project\"}."
                }
                if (dryRun && currentOp in setOf("mkdir", "remove", "move", "rename", "copy", "archive")) {
                    throw IllegalArgumentException("dryRun supports reads and content edits only; structural operation '$currentOp' is not allowed")
                }

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
        val changes = runCatching { transaction.changeSummary() }.getOrElse { error ->
            JSONObject().put("filesChanged", mutations.length()).put("summaryError", error.message ?: error.javaClass.simpleName)
        }
        if (dryRun) {
            val rollbackError = runCatching { transaction.rollback() }.exceptionOrNull()
            for (index in 0 until mutations.length()) invalidateIndex(mutations.optString(index))
            transaction.close()
            if (rollbackError != null) {
                throw IllegalStateException("Rift Code Mode dryRun rollback failed: ${rollbackError.message ?: rollbackError.javaClass.simpleName}")
            }
        } else {
            transaction.close()
        }
        val response = JSONObject()
            .put("mode", "rift-code-mode-v1")
            .put("projectIntelligence", "v1")
            .put("committed", !dryRun)
            .put("dryRun", dryRun)
            .put("operations", operations.length())
            .put("mutationTargets", mutations)
            .put("changes", changes)
            .put("resultTruncated", resultTruncated)
            .put("results", results)
        if (args.optBoolean("returnSnapshot", false)) response.put("snapshot", projectSnapshot(snapshotPath))
        return response
    }

    private fun executeWorkspaceOperation(op: String, args: JSONObject): Any? = when (op) {
        "project" -> projectOverview(workspacePath(args.optString("path", WORKSPACE_ROOT)), args.optInt("limit", 120))
        "snapshot" -> projectSnapshot(workspacePath(args.optString("path", WORKSPACE_ROOT)))
        "symbols" -> searchSymbols(
            workspacePath(args.optString("path", WORKSPACE_ROOT)),
            args.optString("query"),
            args.optString("kind"),
            args.optInt("limit", 80).coerceIn(1, MAX_SYMBOL_RESULTS)
        )
        "references" -> findReferences(
            workspacePath(args.optString("path", WORKSPACE_ROOT)),
            args.getString("symbol"),
            args.optInt("limit", 120).coerceIn(1, MAX_REFERENCE_RESULTS)
        )
        "read_symbol" -> readSymbol(
            workspacePath(args.getString("path")),
            args.getString("symbol"),
            args.optInt("line", 0),
            args.optInt("maxChars", 100_000)
        )
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
        "read", "read_range" -> readTextRange(
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
        "patch_range" -> patchRange(
            workspaceMutationPath(args.getString("path")),
            args.getInt("startLine"),
            args.getInt("endLine"),
            args.optString("text"),
            args.optString("expectedHash").takeIf { it.isNotBlank() },
            args.optString("expectedText").takeIf { args.has("expectedText") },
            args.optString("expectedRangeHash").takeIf { it.isNotBlank() }
        )
        "apply_hunks" -> applyHunks(
            workspaceMutationPath(args.getString("path")),
            args.optJSONArray("hunks") ?: throw IllegalArgumentException("apply_hunks.hunks array is required"),
            args.optString("expectedHash").takeIf { it.isNotBlank() }
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
        "archive" -> createArchive(
            workspacePath(args.getString("from")),
            workspaceMutationPath(args.getString("to")),
            args.optBoolean("overwrite", false)
        )
        else -> throw IllegalArgumentException("Unsupported Rift Code Mode operation: $op")
    }

    private fun createArchive(from: String, to: String, overwrite: Boolean): JSONObject {
        val source = sandboxFile(from)
        val destination = sandboxFile(to)
        require(source.exists()) { "Archive source not found: $from" }
        require(to.lowercase().endsWith(".zip")) { "Archive destination must end with .zip" }
        require(source.canonicalFile != destination.canonicalFile) { "Archive source and destination are identical" }
        if (source.isDirectory) {
            require(!destination.canonicalPath.startsWith(source.canonicalPath + File.separator)) {
                "Archive destination cannot be inside its source directory"
            }
        }
        if (destination.exists()) require(overwrite) { "Destination already exists: $to" }
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.tmp")
        var entries = 0
        var sourceBytes = 0L
        try {
            ZipOutputStream(BufferedOutputStream(temporary.outputStream())).use { zip ->
                fun add(node: File, entryName: String) {
                    require(isInsideRoot(node)) { "Archive entry escaped Rift MCP sandbox" }
                    entries += 1
                    require(entries <= MAX_ARCHIVE_ENTRIES) { "Archive exceeds $MAX_ARCHIVE_ENTRIES entries" }
                    if (node.isDirectory) {
                        val directoryName = entryName.trimEnd('/') + "/"
                        zip.putNextEntry(ZipEntry(directoryName))
                        zip.closeEntry()
                        node.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { child ->
                            add(child, "$directoryName${child.name}")
                        }
                    } else {
                        sourceBytes += node.length()
                        require(sourceBytes <= MAX_ARCHIVE_SOURCE_BYTES) { "Archive source exceeds 256 MiB" }
                        zip.putNextEntry(ZipEntry(entryName))
                        node.inputStream().buffered().use { input -> input.copyTo(zip, 256 * 1024) }
                        zip.closeEntry()
                    }
                }
                add(source, source.name)
            }
            if (destination.exists()) {
                val removed = if (destination.isDirectory) destination.deleteRecursively() else destination.delete()
                require(removed) { "Could not replace archive destination: $to" }
            }
            if (!temporary.renameTo(destination)) temporary.copyTo(destination, overwrite = true).also { temporary.delete() }
            invalidateIndex(to)
            return (stat(to) ?: JSONObject()).put("entries", entries).put("sourceBytes", sourceBytes)
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
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
            .put("projectIntelligence", "v1")
            .put("directChildren", children.size)
            .put("entries", entries)
            .put("truncated", children.size > limit)
            .put("operations", JSONArray(listOf("project", "snapshot", "stat", "list", "search", "symbols", "references", "read", "read_range", "read_symbol", "write", "replace", "patch", "patch_range", "apply_hunks", "mkdir", "remove", "move", "rename", "copy")))
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
            if (!isIgnoredFile(base)) scan(base) else filesSkipped += 1
        } else {
            base.walkTopDown().onEnter { directory ->
                require(isInsideRoot(directory)) { "Workspace search escaped Rift MCP sandbox" }
                directory == base || !isIgnoredDirectory(directory)
            }.forEach { file ->
                if (!hitLimit && !isIgnoredFile(file)) scan(file)
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

    private fun projectSnapshot(path: String): JSONObject {
        val base = sandboxFile(path)
        require(base.exists()) { "Snapshot path not found: $path" }
        val digest = MessageDigest.getInstance("SHA-256")
        var files = 0
        var bytes = 0L
        var truncated = false
        val rows = ArrayList<String>()
        fun include(file: File) {
            if (files >= MAX_SNAPSHOT_FILES) { truncated = true; return }
            if (!file.isFile || isIgnoredFile(file)) return
            files += 1
            bytes += file.length()
            rows += "${relativePath(file)}\u0000${file.length()}\u0000${file.lastModified()}"
        }
        if (base.isFile) include(base) else base.walkTopDown().onEnter { directory ->
            directory == base || !isIgnoredDirectory(directory)
        }.forEach { if (!truncated) include(it) }
        rows.sorted().forEach { digest.update(it.toByteArray(Charsets.UTF_8)); digest.update(0) }
        return JSONObject()
            .put("id", digest.digest().joinToString("") { "%02x".format(it) })
            .put("path", normalizedPath(path))
            .put("files", files)
            .put("bytes", bytes)
            .put("truncated", truncated)
            .put("basis", "path-size-mtime")
    }

    private fun searchSymbols(path: String, query: String, kind: String, limit: Int): JSONObject {
        val base = sandboxFile(path)
        require(base.exists()) { "Symbol path not found: $path" }
        val stats = refreshSymbolIndex(base)
        val q = query.trim().lowercase()
        val k = kind.trim().lowercase()
        val rows = JSONArray()
        symbolIndex.values.asSequence().flatMap { it.symbols.asSequence() }
            .filter { symbol -> isPathWithin(symbol.path, path) }
            .filter { symbol -> q.isEmpty() || symbol.name.lowercase().contains(q) }
            .filter { symbol -> k.isEmpty() || symbol.kind.lowercase() == k }
            .sortedWith(compareBy<SymbolRecord>({ if (q.isNotEmpty() && it.name.lowercase() == q) 0 else 1 }, { it.name.lowercase() }, { it.path }, { it.line }))
            .take(limit)
            .forEach { symbol -> rows.put(symbolJson(symbol)) }
        return JSONObject()
            .put("path", normalizedPath(path))
            .put("query", query)
            .put("kind", kind)
            .put("symbols", rows)
            .put("index", stats)
            .put("truncated", rows.length() >= limit)
    }

    private fun findReferences(path: String, symbolName: String, limit: Int): JSONObject {
        val symbol = symbolName.trim()
        require(symbol.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*"))) { "references.symbol must be one identifier" }
        val base = sandboxFile(path)
        require(base.exists()) { "Reference path not found: $path" }
        refreshSymbolIndex(base)
        val definitionLines = HashSet<String>()
        symbolIndex.values.forEach { indexed -> indexed.symbols.filter { it.name == symbol }.forEach { definitionLines += "${it.path}:${it.line}" } }
        val matches = JSONArray()
        var filesScanned = 0
        var filesSkipped = 0
        var hitLimit = false
        val pattern = Regex("(?<![A-Za-z0-9_$])${Regex.escape(symbol)}(?![A-Za-z0-9_$])")
        fun scan(file: File) {
            if (hitLimit || !file.isFile || isIgnoredFile(file)) return
            if (file.length() > MAX_WORKSPACE_SEARCH_FILE_BYTES || !isTextFile(file)) { filesSkipped += 1; return }
            filesScanned += 1
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEachIndexed { index, line ->
                    if (hitLimit) return@forEachIndexed
                    pattern.findAll(line).forEach { match ->
                        val key = "${relativePath(file)}:${index + 1}"
                        matches.put(JSONObject()
                            .put("path", relativePath(file))
                            .put("line", index + 1)
                            .put("column", match.range.first + 1)
                            .put("definition", key in definitionLines)
                            .put("preview", compactPreview(line)))
                        if (matches.length() >= limit) { hitLimit = true; return@forEach }
                    }
                }
            }
        }
        if (base.isFile) scan(base) else base.walkTopDown().onEnter { directory ->
            directory == base || !isIgnoredDirectory(directory)
        }.forEach { if (!hitLimit) scan(it) }
        return JSONObject()
            .put("path", normalizedPath(path))
            .put("symbol", symbol)
            .put("references", matches)
            .put("filesScanned", filesScanned)
            .put("filesSkipped", filesSkipped)
            .put("truncated", hitLimit)
    }

    private fun readSymbol(path: String, symbolName: String, requestedLine: Int, maxChars: Int): JSONObject {
        val file = sandboxFile(path)
        require(file.isFile) { "File not found: $path" }
        val indexed = indexFile(file) ?: throw IllegalArgumentException("File is not indexable text: $path")
        val matches = indexed.symbols.filter { it.name == symbolName }
        require(matches.isNotEmpty()) { "Symbol not found in $path: $symbolName" }
        val symbol = if (requestedLine > 0) {
            matches.firstOrNull { it.line == requestedLine }
                ?: throw IllegalArgumentException("Symbol $symbolName was not found at line $requestedLine in $path")
        } else matches.first()
        val read = readTextRange(path, symbol.line, symbol.endLine, maxChars.coerceIn(1_000, MAX_WORKSPACE_READ_CHARS))
        read.put("symbol", symbolJson(symbol))
        if (matches.size > 1) read.put("ambiguous", true).put("candidates", JSONArray(matches.take(12).map(::symbolJson)))
        return read
    }

    private fun patchRange(path: String, startLine: Int, endLine: Int, replacement: String, expectedHash: String?, expectedText: String?, expectedRangeHash: String?): JSONObject {
        val file = sandboxFile(path)
        require(file.isFile) { "File not found: $path" }
        require(file.length() <= MAX_TOOL_BYTES) { "File is too large for surgical patching" }
        val source = file.readText(Charsets.UTF_8)
        val beforeHash = sha256(source)
        if (expectedHash != null) require(beforeHash.equals(expectedHash, ignoreCase = true)) { "File hash changed for $path" }
        val edited = replaceLineRange(source, startLine, endLine, replacement, expectedText, expectedRangeHash)
        val bytes = edited.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_TOOL_BYTES) { "Surgical patch result is too large" }
        file.writeBytes(bytes)
        invalidateIndex(path)
        return JSONObject()
            .put("path", normalizedPath(path))
            .put("startLine", startLine)
            .put("endLine", endLine)
            .put("beforeSha256", beforeHash)
            .put("sha256", fileSha256(file))
            .put("size", bytes.size)
    }

    private fun applyHunks(path: String, hunks: JSONArray, expectedHash: String?): JSONObject {
        require(hunks.length() in 1..MAX_HUNKS) { "apply_hunks.hunks must contain 1..$MAX_HUNKS hunks" }
        val file = sandboxFile(path)
        require(file.isFile) { "File not found: $path" }
        require(file.length() <= MAX_TOOL_BYTES) { "File is too large for surgical hunk patching" }
        var source = file.readText(Charsets.UTF_8)
        val beforeHash = sha256(source)
        if (expectedHash != null) require(beforeHash.equals(expectedHash, ignoreCase = true)) { "File hash changed for $path" }
        val parsed = ArrayList<Triple<Int, Int, JSONObject>>()
        for (index in 0 until hunks.length()) {
            val hunk = hunks.optJSONObject(index) ?: throw IllegalArgumentException("Hunk $index must be an object")
            val start = hunk.getInt("startLine")
            val end = hunk.getInt("endLine")
            require(start >= 1 && end >= start) { "Invalid hunk $index line range" }
            parsed += Triple(start, end, hunk)
        }
        val sorted = parsed.sortedByDescending { it.first }
        for (index in 0 until sorted.lastIndex) {
            require(sorted[index].first > sorted[index + 1].second) { "apply_hunks contains overlapping line ranges" }
        }
        sorted.forEach { (start, end, hunk) ->
            source = replaceLineRange(
                source, start, end, hunk.optString("text"),
                hunk.optString("expectedText").takeIf { hunk.has("expectedText") },
                hunk.optString("expectedRangeHash").takeIf { it.isNotBlank() }
            )
        }
        val bytes = source.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_TOOL_BYTES) { "Hunk patch result is too large" }
        file.writeBytes(bytes)
        invalidateIndex(path)
        return JSONObject()
            .put("path", normalizedPath(path))
            .put("hunks", hunks.length())
            .put("beforeSha256", beforeHash)
            .put("sha256", fileSha256(file))
            .put("size", bytes.size)
    }

    private fun replaceLineRange(source: String, startLine: Int, endLine: Int, replacement: String, expectedText: String?, expectedRangeHash: String?): String {
        require(startLine >= 1 && endLine >= startLine) { "Invalid line range $startLine..$endLine" }
        val newline = if (source.contains("\r\n")) "\r\n" else "\n"
        val normalized = source.replace("\r\n", "\n").replace('\r', '\n')
        val hadFinalNewline = normalized.endsWith("\n")
        val body = if (hadFinalNewline) normalized.dropLast(1) else normalized
        val lines = if (body.isEmpty()) mutableListOf("") else body.split('\n').toMutableList()
        require(startLine <= lines.size && endLine <= lines.size) { "Line range $startLine..$endLine exceeds file length ${lines.size}" }
        val selected = lines.subList(startLine - 1, endLine).joinToString("\n")
        if (expectedText != null) require(selected == expectedText.replace("\r\n", "\n").replace('\r', '\n')) {
            "Expected line context did not match for $startLine..$endLine"
        }
        if (expectedRangeHash != null) require(sha256(selected).equals(expectedRangeHash, ignoreCase = true)) {
            "Expected range hash did not match for $startLine..$endLine"
        }
        val replacementNormalized = replacement.replace("\r\n", "\n").replace('\r', '\n')
        val replacementBody = if (replacementNormalized.endsWith("\n")) replacementNormalized.dropLast(1) else replacementNormalized
        val replacementLines = if (replacementBody.isEmpty()) emptyList() else replacementBody.split('\n')
        lines.subList(startLine - 1, endLine).clear()
        lines.addAll(startLine - 1, replacementLines)
        return lines.joinToString(newline) + if (hadFinalNewline) newline else ""
    }

    private fun refreshSymbolIndex(base: File): JSONObject {
        var scanned = 0
        var reused = 0
        var skipped = 0
        var truncated = false
        val seen = HashSet<String>()
        fun visit(file: File) {
            if (truncated || !file.isFile || isIgnoredFile(file)) return
            if (seen.size >= MAX_INDEX_FILES) { truncated = true; return }
            val path = relativePath(file)
            seen += path
            val cached = symbolIndex[path]
            if (cached != null && cached.modified == file.lastModified() && cached.size == file.length()) { reused += 1; return }
            val indexed = indexFile(file)
            if (indexed == null) skipped += 1 else scanned += 1
        }
        if (base.isFile) visit(base) else base.walkTopDown().onEnter { directory ->
            directory == base || !isIgnoredDirectory(directory)
        }.forEach(::visit)
        val prefix = relativePath(base).let { if (it == WORKSPACE_ROOT) "$WORKSPACE_ROOT/" else "$it/" }
        symbolIndex.keys.filter { key -> (base.isDirectory && key.startsWith(prefix) || base.isFile && key == relativePath(base)) && key !in seen }
            .toList().forEach(symbolIndex::remove)
        return JSONObject().put("indexed", scanned).put("reused", reused).put("skipped", skipped).put("cachedFiles", symbolIndex.size).put("truncated", truncated)
    }

    private fun indexFile(file: File): IndexedFile? {
        if (!file.isFile || file.length() > MAX_INDEX_FILE_BYTES || isIgnoredFile(file) || !isTextFile(file)) return null
        val path = relativePath(file)
        val cached = symbolIndex[path]
        if (cached != null && cached.modified == file.lastModified() && cached.size == file.length()) return cached
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        val symbols = extractSymbols(file, text)
        return IndexedFile(file.lastModified(), file.length(), symbols).also { symbolIndex[path] = it }
    }

    private fun extractSymbols(file: File, text: String): List<SymbolRecord> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val language = languageFor(file)
        val out = ArrayList<SymbolRecord>()
        val typePattern = Regex("^\\s*(?:(?:public|private|protected|internal|open|final|abstract|static|export|default|data|sealed|partial)\\s+)*(class|interface|object|struct|trait|record|enum(?:\\s+class)?)\\s+([A-Za-z_$][A-Za-z0-9_$]*)")
        val patterns = listOf(
            "function" to Regex("^\\s*(?:export\\s+)?(?:default\\s+)?(?:async\\s+)?function\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\("),
            "function" to Regex("^\\s*(?:async\\s+)?def\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\("),
            "function" to Regex("^\\s*(?:(?:public|private|protected|internal|open|final|override|inline|suspend|operator|tailrec|infix|external)\\s+)*fun\\s+(?:<[^>]+>\\s*)?([A-Za-z_][A-Za-z0-9_]*)\\s*\\("),
            "function" to Regex("^\\s*(?:(?:pub(?:\\([^)]*\\))?|unsafe|async|const|extern\\s+\"[^\"]+\")\\s+)*fn\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\("),
            "function" to Regex("^\\s*func\\s+(?:\\([^)]*\\)\\s*)?([A-Za-z_][A-Za-z0-9_]*)\\s*\\("),
            "function" to Regex("^\\s*(?:export\\s+)?(?:const|let|var)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*(?:async\\s*)?(?:\\([^)]*\\)|[A-Za-z_$][A-Za-z0-9_$]*)\\s*=>")
        )
        lines.forEachIndexed { index, line ->
            val typeMatch = typePattern.find(line)
            if (typeMatch != null) {
                val rawKind = typeMatch.groupValues[1].lowercase()
                val kind = when { rawKind.startsWith("enum") -> "enum"; rawKind == "interface" || rawKind == "trait" -> "interface"; else -> "type" }
                val name = typeMatch.groupValues[2]
                out += SymbolRecord(name, kind, relativePath(file), index + 1, symbolEndLine(lines, index, language), compactPreview(line))
            }
            patterns.forEach { (kind, pattern) ->
                val match = pattern.find(line) ?: return@forEach
                val name = match.groupValues[1]
                out += SymbolRecord(name, kind, relativePath(file), index + 1, symbolEndLine(lines, index, language), compactPreview(line))
            }
            if (language in setOf("java", "csharp", "cpp") && line.contains('(') && !line.trimStart().startsWith("//")) {
                val method = Regex("^\\s*(?:(?:public|private|protected|static|final|virtual|override|abstract|synchronized|native|inline|constexpr|friend|extern)\\s+)*(?:[A-Za-z_][A-Za-z0-9_<>,.?\\[\\]:*&\\s]+\\s+)([A-Za-z_][A-Za-z0-9_]*)\\s*\\([^;]*\\)\\s*(?:\\{|=>)?\\s*$").find(line)
                val name = method?.groupValues?.getOrNull(1)
                if (!name.isNullOrBlank() && name !in setOf("if", "for", "while", "switch", "catch")) {
                    out += SymbolRecord(name, "method", relativePath(file), index + 1, symbolEndLine(lines, index, language), compactPreview(line))
                }
            }
        }
        return out.distinctBy { "${it.path}:${it.line}:${it.name}:${it.kind}" }
    }

    private fun symbolEndLine(lines: List<String>, startIndex: Int, language: String): Int {
        if (language == "python") {
            val start = lines[startIndex]
            val indent = start.takeWhile { it == ' ' || it == '\t' }.length
            for (index in startIndex + 1 until lines.size) {
                val line = lines[index]
                if (line.isBlank() || line.trimStart().startsWith("#")) continue
                val nextIndent = line.takeWhile { it == ' ' || it == '\t' }.length
                if (nextIndent <= indent) return index
            }
            return lines.size
        }
        val declaration = lines[startIndex].substringBefore("//")
        if (!declaration.contains('{') && (declaration.contains("=") || declaration.trimEnd().endsWith(";"))) return startIndex + 1
        var depth = 0
        var opened = false
        for (index in startIndex until minOf(lines.size, startIndex + 2000)) {
            val line = lines[index].substringBefore("//")
            val opens = line.count { it == '{' }
            val closes = line.count { it == '}' }
            if (opens > 0) opened = true
            depth += opens - closes
            if (opened && depth <= 0) return index + 1
            if (!opened && index > startIndex + 80) return index + 1
        }
        return minOf(lines.size, startIndex + 81)
    }

    private fun symbolJson(symbol: SymbolRecord): JSONObject = JSONObject()
        .put("name", symbol.name).put("kind", symbol.kind).put("path", symbol.path)
        .put("line", symbol.line).put("endLine", symbol.endLine).put("signature", symbol.signature)

    private fun languageFor(file: File): String = when (file.extension.lowercase()) {
        "kt", "kts" -> "kotlin"; "java" -> "java"; "js", "jsx", "ts", "tsx", "mjs", "cjs" -> "javascript"
        "py" -> "python"; "rs" -> "rust"; "go" -> "go"; "cs" -> "csharp"
        "c", "cc", "cpp", "cxx", "h", "hh", "hpp", "hxx" -> "cpp"; else -> "generic"
    }

    private fun isIgnoredDirectory(directory: File): Boolean = directory.name in ignoredDirectoryNames
    private fun isIgnoredFile(file: File): Boolean = file.name in ignoredFileNames || file.extension.lowercase() in binaryExtensions || file.parentFile?.let(::isIgnoredDirectory) == true
    private fun isTextFile(file: File): Boolean {
        if (!file.isFile || file.extension.lowercase() in binaryExtensions) return false
        val probe = runCatching { file.inputStream().use { input -> ByteArray(4096).let { buffer -> input.read(buffer).let { count -> if (count <= 0) ByteArray(0) else buffer.copyOf(count) } } } }.getOrNull() ?: return false
        return probe.none { it.toInt() == 0 }
    }

    private fun compactPreview(line: String): String = line.trim().replace(Regex("\\s+"), " ").let { if (it.length <= MAX_SEARCH_PREVIEW_CHARS) it else it.take(MAX_SEARCH_PREVIEW_CHARS) + "…" }
    private fun isPathWithin(candidate: String, root: String): Boolean {
        val normalizedRoot = normalizedPath(root)
        return candidate == normalizedRoot || candidate.startsWith("$normalizedRoot/")
    }
    private fun invalidateIndex(path: String) {
        val normalized = normalizedPath(path)
        symbolIndex.keys.filter { it == normalized || it.startsWith("$normalized/") }.toList().forEach(symbolIndex::remove)
    }
    private fun lineDelta(before: String, after: String): Pair<Int, Int> {
        val a = before.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val b = after.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix && a[a.lastIndex - suffix] == b[b.lastIndex - suffix]) suffix++
        return Pair((b.size - prefix - suffix).coerceAtLeast(0), (a.size - prefix - suffix).coerceAtLeast(0))
    }

    private fun textLineCount(text: String): Int {
        if (text.isEmpty()) return 0
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        return normalized.count { it == '\n' } + if (normalized.endsWith("\n")) 0 else 1
    }

    private fun captureBatchMutation(transaction: BatchTransaction, op: String, args: JSONObject) {
        when (op) {
            "write", "replace", "patch", "patch_range", "apply_hunks", "mkdir", "remove" -> transaction.capture(workspaceMutationPath(args.getString("path")))
            "move", "rename" -> {
                transaction.capture(workspaceMutationPath(args.getString("from")))
                transaction.capture(workspaceMutationPath(args.getString("to")))
            }
            "copy", "archive" -> transaction.capture(workspaceMutationPath(args.getString("to")))
        }
    }

    private data class BatchSnapshot(val path: String, val kind: String, val backup: File?, val modified: Long)

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
            snapshots[path] = BatchSnapshot(path, kind, backup, if (source.exists()) source.lastModified() else 0L)
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
                            if (snapshot.modified > 0L) target.setLastModified(snapshot.modified)
                        }
                        "directory" -> {
                            target.parentFile?.mkdirs()
                            require(snapshot.backup!!.copyRecursively(target, overwrite = true)) {
                                "Could not restore ${snapshot.path} during batch rollback"
                            }
                            if (snapshot.modified > 0L) target.setLastModified(snapshot.modified)
                        }
                    }
                    invalidateIndex(snapshot.path)
                }
        }

        fun changeSummary(): JSONObject {
            val files = JSONArray()
            var addedLines = 0
            var removedLines = 0
            snapshots.values.forEach { snapshot ->
                val target = sandboxFile(snapshot.path)
                val beforeKind = snapshot.kind
                val afterKind = when {
                    !target.exists() -> "missing"
                    target.isDirectory -> "directory"
                    else -> "file"
                }
                val status = when {
                    beforeKind == "missing" && afterKind != "missing" -> "added"
                    beforeKind != "missing" && afterKind == "missing" -> "deleted"
                    beforeKind != afterKind -> "replaced"
                    beforeKind == "file" && afterKind == "file" -> {
                        val backup = snapshot.backup
                        if (backup != null && backup.length() <= MAX_TOOL_BYTES && target.length() <= MAX_TOOL_BYTES) {
                            if (fileSha256(backup) == fileSha256(target)) "unchanged" else "modified"
                        } else {
                            "modified"
                        }
                    }
                    else -> "modified"
                }
                if (status == "unchanged") return@forEach
                val row = JSONObject().put("path", snapshot.path).put("status", status)
                if (beforeKind == "file" && afterKind == "file" && snapshot.backup != null &&
                    snapshot.backup.length() <= MAX_INDEX_FILE_BYTES && target.length() <= MAX_INDEX_FILE_BYTES &&
                    isTextFile(snapshot.backup) && isTextFile(target)) {
                    val delta = lineDelta(snapshot.backup.readText(Charsets.UTF_8), target.readText(Charsets.UTF_8))
                    row.put("addedLines", delta.first).put("removedLines", delta.second)
                    addedLines += delta.first
                    removedLines += delta.second
                } else if (beforeKind == "missing" && afterKind == "file" && target.length() <= MAX_INDEX_FILE_BYTES && isTextFile(target)) {
                    val count = textLineCount(target.readText(Charsets.UTF_8))
                    row.put("addedLines", count).put("removedLines", 0)
                    addedLines += count
                } else if (beforeKind == "file" && afterKind == "missing" && snapshot.backup != null && snapshot.backup.length() <= MAX_INDEX_FILE_BYTES && isTextFile(snapshot.backup)) {
                    val count = textLineCount(snapshot.backup.readText(Charsets.UTF_8))
                    row.put("addedLines", 0).put("removedLines", count)
                    removedLines += count
                }
                if (afterKind == "file" && target.length() <= MAX_TOOL_BYTES) row.put("sha256", fileSha256(target))
                files.put(row)
            }
            return JSONObject()
                .put("filesChanged", files.length())
                .put("addedLines", addedLines)
                .put("removedLines", removedLines)
                .put("files", files)
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

    private fun fileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

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
                .put("projectIntelligence", "v1")
                .put("workspaceRoot", WORKSPACE_ROOT)
                .put("maxOperations", MAX_WORKSPACE_OPS)
                .put("maxResultBytes", MAX_WORKSPACE_RESULT_BYTES)
                .put("transactional", true)
                .put("dryRun", true)
                .put("symbolIndex", "incremental-memory")
                .put("ignoredDirectories", JSONArray(ignoredDirectoryNames.sorted())))
            .put("capabilities", JSONArray(listOf(
                "stat", "list", "readText", "writeText", "mkdir", "remove", "move", "copy", "workspaceExec",
                "snapshot", "symbols", "references", "readSymbol", "patchRange", "applyHunks"
            )))
    }
}
