package com.riftos.app

import android.content.Context
import android.os.StatFs
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** App-private filesystem capability owned by the local Rift MCP tool host. */
internal class RiftToolSandbox(context: Context) {
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
        private const val MAX_SEARCH_TOTAL_BYTES = 64L * 1024L * 1024L
        private const val MAX_HASH_TOTAL_BYTES = 256L * 1024L * 1024L
        private const val MAX_BATCH_ROLLBACK_BYTES = 64L * 1024L * 1024L
        private const val MAX_BATCH_ROLLBACK_ENTRIES = 50_000
        private const val REQUEST_TIMEOUT_MS = 45_000L
        private const val LEGACY_MIGRATION_TIMEOUT_MS = 15_000L
        private const val MAX_LEGACY_MIGRATION_ENTRIES = 20_000
        private const val MAX_LEGACY_MIGRATION_BYTES = 256L * 1024L * 1024L
        private const val MAX_SEARCH_PREVIEW_CHARS = 320
        private const val MAX_SYMBOL_RESULTS = 240
        private const val MAX_REFERENCE_RESULTS = 400
        private const val MAX_INDEX_FILES = 25_000
        private const val MAX_INDEX_FILE_BYTES = 2L * 1024L * 1024L
        private const val MAX_INDEX_TOTAL_BYTES = 128L * 1024L * 1024L
        private const val MAX_GRAPH_FILES_PREVIEW = 120
        private const val MAX_GRAPH_EDGES = 600
        private const val MAX_CONSISTENCY_INPUT_FILES = 1_024
        private const val MAX_CONSISTENCY_INPUT_EDGES = 1_024
        private const val MAX_PERSISTED_INDEX_FILES = 4000
        private const val MAX_PERSISTED_INDEX_BYTES = 8L * 1024L * 1024L
        private const val PROJECT_INTELLIGENCE_CACHE_VERSION = 5
        private const val MAX_HUNKS = 128
        private const val MAX_SNAPSHOT_FILES = 50_000
        private const val MAX_ARCHIVE_ENTRIES = 50_000
        private const val MAX_ARCHIVE_SOURCE_BYTES = 256L * 1024L * 1024L
        private const val MAX_ARCHIVE_EXTRACTED_BYTES = 512L * 1024L * 1024L
        private const val MAX_CANDIDATE_PROJECTS = 32
        private const val MAX_CANDIDATE_CHANGED_FILES = 4_096
        private const val MAX_CANDIDATE_CHANGED_SYMBOLS = 1_000
        private const val MAX_CANDIDATE_REFERENCE_SYMBOLS = 80
        private const val MAX_CANDIDATE_REFERENCES = 800
        private const val MAX_CANDIDATE_DEPENDENCIES = 800
        private const val MAX_CANDIDATE_DEPENDENTS = 800
        private const val MAX_CANDIDATE_TESTS = 300
        private const val MAX_CANDIDATE_DOCS = 300
        private const val MAX_CANDIDATE_AFFINITY_TARGETS = 128
        private const val LEGACY_ROOT_NAME = "tool-sandbox"
        private const val OLDER_LEGACY_ROOT_NAME = "browser-sandbox"
        private const val WORKSPACE_ROOT = "workspace"
        private val WORKSPACE_OPS = setOf("project", "snapshot", "stat", "hash", "list", "search", "symbols", "references", "read", "read_range", "read_symbol", "write", "replace", "patch", "patch_range", "apply_hunks", "mkdir", "remove", "move", "rename", "copy", "archive", "extract")
    }

    private val appContext = context.applicationContext
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(16)
    )
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    private val riftFsRoot = File(appContext.filesDir, "riftfs").apply { mkdirs() }
    private val workspaceRoot = prepareCanonicalWorkspace()
    private val workspaceRecords = RiftWorkspaceRecords.get(appContext).also { it.start() }
    private val repositoryConsistencyObserver = RiftRepositoryConsistencyObserver(appContext)
    private val projectIntelligenceCache = File(appContext.filesDir, "rift-project-intelligence-v2.json")
    private val transactionRoot = File(appContext.cacheDir, "rift-workspace-transactions").apply {
        // Never recursively purge the whole transaction cache on MCP construction: a large
        // crash remnant must not block startup. Each transaction owns and bounds its own cleanup.
        mkdirs()
    }
    private val symbolIndex = LinkedHashMap<String, IndexedFile>()
    private val repositoryFileIndex = LinkedHashMap<String, RepositoryFileEvidence>()
    private val pendingIndexInvalidations = LinkedHashSet<String>()
    private var persistentIndexLoaded = false
    private var persistentIndexDirty = false
    private var persistentIndexLoadStatus = "not-loaded"
    private var persistentIndexRejectedReason: String? = null
    @Volatile private var batchInvalidationDepth = 0
    private val ignoredDirectoryNames = setOf(
        ".git", ".gradle", ".idea", ".next", ".cache", ".turbo", ".parcel-cache", ".vortex-bridge",
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

    private data class DependencyRecord(
        val specifier: String,
        val kind: String,
        val line: Int
    )

    private data class RepositoryFileEvidence(
        val modified: Long,
        val size: Long,
        val sha256: String?,
        val semanticStatus: String,
        val semanticReason: String?
    )

    private data class IndexedFile(
        val modified: Long,
        val size: Long,
        val sha256: String,
        val language: String,
        val symbols: List<SymbolRecord>,
        val dependencies: List<DependencyRecord>
    )

    private fun executeRequest(raw: String, origin: String): String {
        val fallbackId = runCatching { JSONObject(raw).optString("id") }.getOrDefault("")
        var patchSession: RiftPatchSessions.Handle? = null
        return try {
            RiftDeadline.check("$origin sandbox request")
            val request = JSONObject(raw)
            val requestId = request.optString("id")
            val method = request.optString("method")
            require(requestId.isNotBlank()) { "Missing tool request id" }
            require(method.isNotBlank()) { "Missing tool method" }
            val args = request.optJSONObject("args") ?: JSONObject()
            patchSession = RiftPatchSessions.begin(
                appContext,
                origin = origin,
                operation = method,
                intent = args.optString("intent").takeIf { it.isNotBlank() },
                requestId = requestId,
                rawPaths = provenanceMutationPaths(method, args)
            )
            val value = dispatch(method, args) ?: JSONObject.NULL
            if (origin != "rift-cli") RiftDeadline.check("$origin sandbox request")
            patchSession?.let { runCatching { RiftPatchSessions.commit(appContext, it) } }
            JSONObject()
                .put("id", requestId)
                .put("ok", true)
                .put("value", value)
                .toString()
        } catch (error: Throwable) {
            patchSession?.let(RiftPatchSessions::abort)
            JSONObject()
                .put("id", fallbackId)
                .put("ok", false)
                .put("error", error.message ?: error.javaClass.simpleName)
                .toString()
        } finally {
            RiftDeadline.clearInterrupt()
        }
    }

    fun handleAsync(raw: String, reply: (String) -> Unit) {
        val id = runCatching { JSONObject(raw).optString("id") }.getOrDefault("")
        RiftBoundedAsync.submit(
            executor = executor,
            watchdog = watchdog,
            timeoutMs = REQUEST_TIMEOUT_MS,
            timeoutValue = {
                JSONObject()
                    .put("id", id)
                    .put("ok", false)
                    .put("error", "Rift MCP local operation timed out after ${REQUEST_TIMEOUT_MS}ms")
                    .toString()
            },
            failureValue = { error ->
                JSONObject()
                    .put("id", id)
                    .put("ok", false)
                    .put("error", error.message ?: error.javaClass.simpleName)
                    .toString()
            },
            work = { executeRequest(raw, "mcp") },
            reply = reply
        )
    }

    /**
     * RiftCLI live-poll lane.
     *
     * No fixed wall-clock timeout is imposed here. The returned Future is the cancellation
     * authority; deep filesystem/runtime loops remain cooperative through RiftDeadline.check(),
     * which also observes thread interruption. Normal MCP calls retain their bounded timeout.
     */
    /**
     * Synchronous RiftCLI Batch V2 tool step.
     *
     * The batch owner already holds RiftCliExecutionGate for the entire plan, so this path must
     * not reserve or queue a nested CLI job. Per-step provenance is still recorded by executeRequest.
     */
    internal fun executeCliBatchRequest(raw: String): String =
        executeRequest(raw, "rift-cli-batch")

    internal fun submitCliJob(
        raw: String,
        onStart: () -> Unit,
        reply: (String) -> Unit
    ): Future<*> = executor.submit {
        val id = runCatching { JSONObject(raw).optString("id") }.getOrDefault("")
        val response = try {
            RiftCliExecutionGate.run {
                runCatching { onStart() }
                executeRequest(raw, "rift-cli")
            }
        } catch (error: Throwable) {
            RiftDeadline.clearInterrupt()
            JSONObject()
                .put("id", id)
                .put("ok", false)
                .put("error", error.message ?: error.javaClass.simpleName)
                .toString()
        }
        runCatching { reply(response) }
    }

    internal fun candidateImpactAsync(reply: (JSONObject) -> Unit) {
        RiftBoundedAsync.submit(
            executor = executor,
            watchdog = watchdog,
            timeoutMs = REQUEST_TIMEOUT_MS,
            timeoutValue = {
                JSONObject()
                    .put("format", "rift-semantic-impact-v1")
                    .put("version", 1)
                    .put("complete", false)
                    .put("error", "Candidate impact timed out")
            },
            failureValue = { error ->
                JSONObject()
                    .put("format", "rift-semantic-impact-v1")
                    .put("version", 1)
                    .put("complete", false)
                    .put("error", error.message ?: error.javaClass.simpleName)
            },
            work = { candidateImpact() },
            reply = reply
        )
    }

    fun shutdown() {
        runCatching { persistProjectIntelligence() }
        executor.shutdownNow()
        watchdog.shutdownNow()
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
                if (merged) runCatching { deleteLegacyTree(legacyWorkspace) }
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
                .onSuccess { runCatching { deleteLegacyTree(legacyMeta) } }
        }
        listOf("projects", "downloads", "documents", "patches").forEach { name ->
            val directory = File(workspace, name)
            if (directory.isDirectory && directory.listFiles().isNullOrEmpty()) runCatching { directory.delete() }
        }
        runCatching { marker.writeText("1", Charsets.UTF_8) }
    }

    private fun mergeMissingTree(source: File, destination: File) {
        RiftDeadline.runUntil(SystemClock.elapsedRealtime() + LEGACY_MIGRATION_TIMEOUT_MS) {
            var entries = 0
            var bytes = 0L
            fun mergeNode(from: File, to: File) {
                RiftDeadline.check("legacy workspace migration")
                require(++entries <= MAX_LEGACY_MIGRATION_ENTRIES) {
                    "Legacy workspace migration exceeds $MAX_LEGACY_MIGRATION_ENTRIES entries"
                }
                if (from.isFile) {
                    bytes += from.length()
                    require(bytes <= MAX_LEGACY_MIGRATION_BYTES) {
                        "Legacy workspace migration exceeds ${MAX_LEGACY_MIGRATION_BYTES / (1024 * 1024)} MiB"
                    }
                    if (!to.exists()) {
                        to.parentFile?.let { parent ->
                            require(parent.exists() || parent.mkdirs()) { "Could not create legacy migration parent" }
                        }
                        from.inputStream().buffered().use { input ->
                            to.outputStream().buffered().use { output ->
                                val buffer = ByteArray(256 * 1024)
                                while (true) {
                                    RiftDeadline.check("legacy workspace migration")
                                    val read = input.read(buffer)
                                    if (read <= 0) break
                                    output.write(buffer, 0, read)
                                }
                            }
                        }
                    }
                    return
                }
                require((to.exists() && to.isDirectory) || to.mkdirs()) {
                    "Could not create legacy migration directory"
                }
                val children = from.listFiles()
                    ?: throw IllegalStateException("Could not read legacy migration directory")
                children.sortedBy { it.name.lowercase() }.forEach { child ->
                    mergeNode(child, File(to, child.name))
                }
            }
            mergeNode(source, destination)
        }
    }

    private fun deleteLegacyTree(root: File) {
        RiftDeadline.runUntil(SystemClock.elapsedRealtime() + LEGACY_MIGRATION_TIMEOUT_MS) {
            require(deleteTreeBounded(root, MAX_LEGACY_MIGRATION_ENTRIES, cooperative = true)) {
                "Could not remove migrated legacy workspace"
            }
        }
    }

    private fun provenanceMutationPaths(method: String, args: JSONObject): List<String> {
        val paths = LinkedHashSet<String>()
        fun add(value: String) { value.trim().takeIf { it.isNotBlank() }?.let(paths::add) }
        when (method) {
            "fs.writeText", "fs.mkdir", "fs.remove" -> add(args.optString("path"))
            "fs.move" -> { add(args.optString("from")); add(args.optString("to")) }
            "fs.copy", "fs.archive", "fs.extract" -> add(args.optString("to"))
            "workspace.exec" -> {
                if (args.optBoolean("dryRun", false)) return emptyList()
                val operations = args.optJSONArray("operations") ?: return emptyList()
                for (index in 0 until operations.length()) {
                    val raw = operations.optJSONObject(index) ?: continue
                    val operation = normalizeWorkspaceOperation(raw)
                    when (operation.optString("op").trim().lowercase()) {
                        "write", "replace", "patch", "patch_range", "apply_hunks", "mkdir", "remove" -> add(operation.optString("path"))
                        "move", "rename" -> { add(operation.optString("from")); add(operation.optString("to")) }
                        "copy", "archive", "extract" -> add(operation.optString("to"))
                    }
                }
            }
        }
        return paths.toList()
    }

    private fun dispatch(method: String, args: JSONObject): Any? = when (method) {
        "sandbox.info" -> info()
        "fs.stat" -> stat(workspacePath(args.optString("path")))
        "fs.hash" -> hashPath(workspacePath(args.getString("path")))
        "fs.list" -> list(workspacePath(args.optString("path")), args.optBoolean("recursive", false))
        "fs.readText" -> readText(workspacePath(args.getString("path")))
        "fs.writeText" -> writeText(workspaceMutationPath(args.getString("path")), args.optString("text"))
        "fs.mkdir" -> mkdir(workspaceMutationPath(args.getString("path")))
        "fs.remove" -> remove(workspaceMutationPath(args.getString("path")))
        "fs.move" -> move(workspaceMutationPath(args.getString("from")), workspaceMutationPath(args.getString("to")), args.optBoolean("overwrite", false))
        "fs.copy" -> copy(workspaceMutationPath(args.getString("from")), workspaceMutationPath(args.getString("to")), args.optBoolean("overwrite", false))
        "fs.archive" -> createArchive(workspacePath(args.getString("from")), workspaceMutationPath(args.getString("to")), args.optBoolean("overwrite", false))
        "fs.extract" -> extractArchive(workspacePath(args.getString("from")), workspaceMutationPath(args.getString("to")), args.optBoolean("overwrite", false))
        "workspace.exec" -> workspaceExec(args)
        "workspace.audit" -> audit(args.optString("path"))
        "workspace.scan" -> scan(args.optString("path"), args.optString("mode", "all"))
        "workspace.exportProject" -> exportProject(args)
        "workspace.diff" -> workspaceRecords.query(args)
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
            root.walkTopDown()
                .onEnter { directory ->
                    RiftDeadline.check("workspace audit")
                    directory == root || !isIgnoredDirectory(directory)
                }
                .forEach { file ->
                RiftDeadline.check("workspace audit")
                if (file.isFile) {
                    files++
                    require(files <= MAX_SNAPSHOT_FILES) { "Workspace audit exceeds $MAX_SNAPSHOT_FILES files" }
                    val name = file.name.lowercase()
                    if (name.contains("secret") || name.contains("password") || name.contains("token")) {
                        findings.put(JSONObject().put("severity", "medium").put("category", "security").put("file", relativePath(file)).put("issue", "sensitive-looking filename"))
                    }
                }
            }
        }
        return result.put("filesScanned", files).put("findings", findings)
    }

    private fun exportProject(args: JSONObject): JSONObject {
        val root = sandboxFile(workspacePath(args.optString("path")))
        require(root.exists() && root.isDirectory) { "Export path must be a workspace directory" }
        return RiftProjectExporter.export(
            root = root,
            rawCursor = args.optString("cursor"),
            requestedPageBytes = args.optInt("maxBytes", 320 * 1024),
            expectedSnapshot = args.optString("expectedSnapshot")
        )
            .put("source", relativePath(root))
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

    private fun hashPath(path: String): JSONObject {
        val target = sandboxFile(path)
        require(target.exists()) { "Path not found: $path" }
        if (target.isFile) {
            return JSONObject()
                .put("path", relativePath(target))
                .put("kind", "file")
                .put("size", target.length())
                .put("sha256", fileSha256(target))
        }

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("RIFT_TREE_SHA256_V1\u0000".toByteArray(Charsets.UTF_8))
        var entries = 0
        var files = 0
        var directories = 1
        var bytes = 0L

        fun update(value: String) = digest.update(value.toByteArray(Charsets.UTF_8))
        fun visit(directory: File, prefix: String) {
            val children = directory.listFiles()
                ?: throw IllegalStateException("Could not read directory while hashing: ${relativePath(directory)}")
            children.sortedBy { it.name }.forEach { child ->
                RiftDeadline.check("workspace hash")
                require(isInsideRoot(child)) { "Hash traversal escaped Rift MCP sandbox" }
                entries += 1
                require(entries <= MAX_SNAPSHOT_FILES) { "Directory hash exceeds $MAX_SNAPSHOT_FILES entries" }
                val relative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                if (child.isDirectory) {
                    directories += 1
                    update("D\u0000$relative\u0000")
                    visit(child, relative)
                } else {
                    files += 1
                    bytes += child.length()
                    require(bytes <= MAX_HASH_TOTAL_BYTES) { "Directory hash exceeds ${MAX_HASH_TOTAL_BYTES / (1024 * 1024)} MiB" }
                    update("F\u0000$relative\u0000${child.length()}\u0000")
                    child.inputStream().buffered().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            RiftDeadline.check("workspace hash")
                            val read = input.read(buffer)
                            if (read <= 0) break
                            digest.update(buffer, 0, read)
                        }
                    }
                    digest.update(0.toByte())
                }
            }
        }
        visit(target, "")
        return JSONObject()
            .put("path", relativePath(target))
            .put("kind", "directory")
            .put("entries", entries)
            .put("files", files)
            .put("directories", directories)
            .put("bytes", bytes)
            .put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
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
            val children = directory.listFiles()
                ?: throw IllegalStateException("Could not read directory: ${relativePath(directory)}")
            children.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })).forEach { child ->
                RiftDeadline.check("workspace list")
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
                RiftDeadline.check("workspace text read")
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

    private fun writeBytesAtomic(file: File, bytes: ByteArray, label: String) {
        val parent = file.parentFile ?: throw IllegalArgumentException("Destination has no parent: $label")
        require(parent.exists() || parent.mkdirs()) { "Could not create destination parent: $label" }
        val staged = File(parent, ".${file.name}.${UUID.randomUUID()}.writing")
        try {
            staged.writeBytes(bytes)
            commitStaged(staged, file, label)
        } catch (error: Throwable) {
            deletePath(staged)
            throw error
        }
    }

    private fun writeText(path: String, text: String): JSONObject {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_TOOL_BYTES) { "Text payload is too large for Rift MCP" }
        val file = sandboxFile(path)
        require(file != workspaceRoot) { "Workspace root is not a file" }
        require(!file.exists() || file.isFile) { "Destination is a directory: $path" }
        writeBytesAtomic(file, bytes, path)
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
        writeBytesAtomic(file, bytes, path)
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
        writeBytesAtomic(file, bytes, path)
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
            RiftDeadline.check("text occurrence scan")
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
        val removed = if (file.isDirectory) deleteTreeBounded(file, MAX_ARCHIVE_ENTRIES, cooperative = true) else file.delete()
        require(removed && !file.exists()) { "Could not remove: $path" }
        invalidateIndex(path)
        return true
    }

    private fun move(from: String, to: String, overwrite: Boolean): JSONObject = batchIndexInvalidations {
        require(normalizeSegments(from).isNotEmpty()) { "Cannot move the workspace root" }
        require(normalizeSegments(to).isNotEmpty()) { "Destination cannot be the workspace root" }
        val source = sandboxFile(from)
        val destination = sandboxFile(to)
        require(source.exists()) { "Source not found: $from" }
        require(source.canonicalFile != destination.canonicalFile) { "Move source and destination are identical" }
        if (source.isDirectory) {
            require(!destination.canonicalPath.startsWith(source.canonicalPath + File.separator)) { "Cannot move a directory inside itself" }
        }
        if (destination.exists()) require(overwrite) { "Destination already exists: $to" }
        val parent = destination.parentFile ?: throw IllegalArgumentException("Destination has no parent: $to")
        require(parent.exists() || parent.mkdirs()) { "Could not create destination parent: $to" }
        val backup = File(parent, ".${destination.name}.${UUID.randomUUID()}.move-backup")
        var backedUp = false
        try {
            if (destination.exists()) {
                require(destination.renameTo(backup)) { "Could not stage existing destination: $to" }
                backedUp = true
            }
            require(source.renameTo(destination)) { "Could not move $from to $to" }
            if (backedUp) require(deletePath(backup)) { "Could not clear replaced destination backup: $to" }
        } catch (error: Throwable) {
            if (!source.exists() && destination.exists()) {
                require(destination.renameTo(source)) { "Could not restore move source after failure: $from" }
            }
            if (backedUp && backup.exists()) {
                require(backup.renameTo(destination)) { "Could not restore move destination after failure: $to" }
            }
            throw error
        }
        invalidateIndex(from)
        invalidateIndex(to)
        return@batchIndexInvalidations stat(to)!!
    }

    private fun copy(from: String, to: String, overwrite: Boolean): JSONObject = batchIndexInvalidations {
        require(normalizeSegments(from).isNotEmpty()) { "Cannot copy the workspace root" }
        require(normalizeSegments(to).isNotEmpty()) { "Destination cannot be the workspace root" }
        val source = sandboxFile(from)
        val destination = sandboxFile(to)
        require(source.exists()) { "Source not found: $from" }
        require(source.canonicalFile != destination.canonicalFile) { "Copy source and destination are identical" }
        if (source.isDirectory) {
            require(!destination.canonicalPath.startsWith(source.canonicalPath + File.separator)) { "Cannot copy a directory inside itself" }
        }
        if (destination.exists()) require(overwrite) { "Destination already exists: $to" }
        val parent = destination.parentFile ?: throw IllegalArgumentException("Destination has no parent: $to")
        require(parent.exists() || parent.mkdirs()) { "Could not create destination parent: $to" }
        val staged = File(parent, ".${destination.name}.${UUID.randomUUID()}.copying")
        try {
            if (source.isDirectory) {
                require(copyTreeBounded(source, staged, overwrite = false, maxEntries = MAX_ARCHIVE_ENTRIES, maxBytes = MAX_ARCHIVE_SOURCE_BYTES, cooperative = true)) { "Could not stage copy $from to $to" }
            } else {
                source.copyTo(staged, overwrite = false)
            }
            commitStaged(staged, destination, to)
        } catch (error: Throwable) {
            deletePath(staged)
            throw error
        }
        invalidateIndex(to)
        return@batchIndexInvalidations stat(to)!!
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

    private fun workspaceExec(args: JSONObject): JSONObject = batchIndexInvalidations {
        workspaceExecInternal(args)
    }

    private fun workspaceExecInternal(args: JSONObject): JSONObject {
        RiftDeadline.check("Rift Code Mode batch")
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
        val expectedExportSnapshot = args.optString("expectedExportSnapshot").trim()
        if (expectedExportSnapshot.isNotEmpty()) {
            val actual = RiftProjectExporter.snapshotId(sandboxFile(snapshotPath))
            require(actual == expectedExportSnapshot) {
                "Exported source changed at $snapshotPath; expected $expectedExportSnapshot but found $actual"
            }
        }

        val transaction = BatchTransaction()
        val results = JSONArray()
        var resultBytes = 0
        var resultTruncated = false
        var currentIndex = -1
        var currentOp = ""

        try {
            for (index in 0 until operations.length()) {
                RiftDeadline.check("Rift Code Mode operation $index")
                currentIndex = index
                val rawOperation = operations.optJSONObject(index)
                    ?: throw IllegalArgumentException("Operation $index must be an object")
                val operation = normalizeWorkspaceOperation(rawOperation)
                currentOp = operation.optString("op").trim().lowercase()
                require(currentOp.isNotBlank()) {
                    "Operation $index is missing op. Use flat JSON such as {\"op\":\"stat\",\"path\":\"workspace/project\"}."
                }
                if (dryRun && currentOp in setOf("mkdir", "remove", "move", "rename", "copy", "archive", "extract")) {
                    throw IllegalArgumentException("dryRun supports reads and content edits only; structural operation '$currentOp' is not allowed")
                }

                captureBatchMutation(transaction, currentOp, operation)
                RiftDeadline.check("Rift Code Mode operation $index")
                val value = executeWorkspaceOperation(currentOp, operation)
                RiftDeadline.check("Rift Code Mode operation $index")
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
            // Timeout interrupts the worker. Clear that flag before restoring the transaction;
            // rollback must finish even though the model-visible request has already timed out.
            RiftDeadline.clearInterrupt()
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
            .put("projectIntelligence", "v2")
            .put("committed", !dryRun)
            .put("dryRun", dryRun)
            .put("operations", operations.length())
            .put("mutationTargets", mutations)
            .put("changes", changes)
            .put("resultTruncated", resultTruncated)
            .put("results", results)
        if (args.optBoolean("returnSnapshot", false)) {
            RiftDeadline.check("Rift Code Mode return snapshot")
            response.put("snapshot", projectSnapshot(snapshotPath))
        }
        return response
    }

    private fun executeWorkspaceOperation(op: String, args: JSONObject): Any? = when (op) {
        "project" -> projectOverview(
            workspacePath(args.optString("path", WORKSPACE_ROOT)),
            args.optInt("limit", 120),
            args.optString("kind"),
            args.optString("query")
        )
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
        "hash" -> hashPath(workspacePath(args.getString("path")))
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
        "extract" -> extractArchive(
            workspacePath(args.getString("from")),
            workspaceMutationPath(args.getString("to")),
            args.optBoolean("overwrite", false)
        )
        else -> throw IllegalArgumentException("Unsupported Rift Code Mode operation: $op")
    }

    private fun copyTreeBounded(
        source: File,
        destination: File,
        overwrite: Boolean,
        maxEntries: Int,
        maxBytes: Long,
        cooperative: Boolean
    ): Boolean {
        var entries = 0
        var bytes = 0L
        fun check() {
            if (cooperative) RiftDeadline.check("filesystem copy")
        }
        fun copyNode(from: File, to: File) {
            check()
            entries += 1
            require(entries <= maxEntries) { "Filesystem copy exceeds $maxEntries entries" }
            if (from.isDirectory) {
                if (to.exists()) require(to.isDirectory && overwrite) { "Copy destination already exists: ${to.path}" }
                else require(to.mkdirs()) { "Could not create copy directory: ${to.path}" }
                val children = from.listFiles()
                    ?: throw IllegalStateException("Could not read copy source directory: ${from.path}")
                children.sortedBy { it.name.lowercase() }.forEach { child ->
                    copyNode(child, File(to, child.name))
                }
                if (from.lastModified() > 0L) to.setLastModified(from.lastModified())
            } else {
                bytes += from.length()
                require(bytes <= maxBytes) { "Filesystem copy exceeds ${maxBytes / (1024 * 1024)} MiB" }
                to.parentFile?.let { parent ->
                    require(parent.exists() || parent.mkdirs()) { "Could not create copy parent: ${parent.path}" }
                }
                if (to.exists()) require(overwrite) { "Copy destination already exists: ${to.path}" }
                from.inputStream().buffered().use { input ->
                    to.outputStream().buffered().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            check()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (from.lastModified() > 0L) to.setLastModified(from.lastModified())
            }
        }
        copyNode(source, destination)
        return true
    }

    private fun deleteTreeBounded(root: File, maxEntries: Int, cooperative: Boolean): Boolean {
        var entries = 0
        fun removeNode(node: File): Boolean {
            if (cooperative) RiftDeadline.check("filesystem delete")
            entries += 1
            require(entries <= maxEntries) { "Filesystem delete exceeds $maxEntries entries" }
            if (node.isDirectory) {
                val children = node.listFiles()
                    ?: throw IllegalStateException("Could not read directory for deletion: ${node.path}")
                children.forEach { child ->
                    require(removeNode(child)) { "Could not delete ${child.path}" }
                }
            }
            return node.delete()
        }
        return !root.exists() || removeNode(root)
    }

    private fun deletePath(file: File): Boolean =
        !file.exists() || if (file.isDirectory) deleteTreeBounded(file, MAX_ARCHIVE_ENTRIES, cooperative = false) else file.delete()


    private fun commitStaged(staged: File, destination: File, label: String) {
        val parent = destination.parentFile ?: throw IllegalArgumentException("Destination has no parent: $label")
        val backup = File(parent, ".${destination.name}.${UUID.randomUUID()}.backup")
        var backedUp = false
        try {
            if (destination.exists()) {
                require(destination.renameTo(backup)) { "Could not stage existing destination: $label" }
                backedUp = true
            }
            if (!staged.renameTo(destination)) {
                if (staged.isDirectory) {
                    require(copyTreeBounded(staged, destination, overwrite = true, maxEntries = MAX_ARCHIVE_ENTRIES, maxBytes = MAX_ARCHIVE_EXTRACTED_BYTES, cooperative = true)) { "Could not commit staged directory: $label" }
                } else {
                    staged.copyTo(destination, overwrite = true)
                }
                require(deletePath(staged)) { "Could not clear staging path: $label" }
            }
        } catch (error: Throwable) {
            // Only an install failure warrants rollback. Backup cleanup is intentionally
            // outside this block: recursive deletion can fail after removing some files.
            require(deletePath(destination)) { "Could not clear incomplete destination: $label; original backup retained at ${backup.path}" }
            if (backedUp && backup.exists()) {
                require(backup.renameTo(destination)) { "Could not restore existing destination after failure: $label" }
            }
            throw error
        }
        // A failed cleanup must never delete the successfully installed destination.
        // Keep any remaining backup for manual cleanup instead of treating it as rollback.
        if (backedUp && !runCatching { deletePath(backup) }.getOrDefault(false)) {
            android.util.Log.w("RiftToolSandbox", "Backup cleanup incomplete after committing $label; retained at ${backup.path}")
        }
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
                    RiftDeadline.check("archive creation")
                    require(isInsideRoot(node)) { "Archive entry escaped Rift MCP sandbox" }
                    entries += 1
                    require(entries <= MAX_ARCHIVE_ENTRIES) { "Archive exceeds $MAX_ARCHIVE_ENTRIES entries" }
                    if (node.isDirectory) {
                        val directoryName = entryName.trimEnd('/') + "/"
                        zip.putNextEntry(ZipEntry(directoryName))
                        zip.closeEntry()
                        val children = node.listFiles()
                            ?: throw IllegalStateException("Could not read archive source directory: ${relativePath(node)}")
                        children.sortedBy { it.name.lowercase() }.forEach { child ->
                            add(child, "$directoryName${child.name}")
                        }
                    } else {
                        sourceBytes += node.length()
                        require(sourceBytes <= MAX_ARCHIVE_SOURCE_BYTES) { "Archive source exceeds 256 MiB" }
                        zip.putNextEntry(ZipEntry(entryName))
                        node.inputStream().buffered().use { input ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                RiftDeadline.check("archive creation")
                                val read = input.read(buffer)
                                if (read <= 0) break
                                zip.write(buffer, 0, read)
                            }
                        }
                        zip.closeEntry()
                    }
                }
                add(source, source.name)
            }
            commitStaged(temporary, destination, to)
            batchIndexInvalidations {
                invalidateIndex(to)
            }
            return (stat(to) ?: JSONObject()).put("entries", entries).put("sourceBytes", sourceBytes)
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    private fun extractArchive(from: String, to: String, overwrite: Boolean): JSONObject {
        val source = sandboxFile(from)
        val destination = sandboxFile(to)
        require(source.isFile) { "ZIP archive not found: $from" }
        require(from.lowercase().endsWith(".zip")) { "Archive source must end with .zip" }
        require(destination.canonicalFile != workspaceRoot.canonicalFile) { "Cannot extract over the workspace root" }
        if (destination.exists()) require(overwrite) { "Destination already exists: $to" }
        val parent = destination.parentFile ?: throw IllegalArgumentException("Destination has no parent: $to")
        require(parent.exists() || parent.mkdirs()) { "Could not create destination parent: $to" }
        val temporary = File(parent, ".${destination.name}.${UUID.randomUUID()}.extracting")
        require(temporary.mkdirs()) { "Could not prepare extraction directory: $to" }
        var entries = 0
        var extractedBytes = 0L
        val seen = HashSet<String>()

        try {
            ZipInputStream(BufferedInputStream(source.inputStream())).use { zip ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    RiftDeadline.check("archive extraction")
                    val entry = zip.nextEntry ?: break
                    entries += 1
                    require(entries <= MAX_ARCHIVE_ENTRIES) { "Archive exceeds $MAX_ARCHIVE_ENTRIES entries" }
                    val rawName = entry.name.replace('\\', '/')
                    require(rawName.isNotBlank() && !rawName.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(rawName)) {
                        "Archive contains an invalid absolute entry path"
                    }
                    val segments = normalizeSegments(rawName)
                    require(segments.isNotEmpty()) { "Archive contains an empty entry path" }
                    val normalized = segments.joinToString("/")
                    require(seen.add(normalized)) { "Archive contains duplicate entry: $normalized" }
                    val output = File(temporary, normalized).canonicalFile
                    require(output == temporary.canonicalFile || output.path.startsWith(temporary.canonicalPath + File.separator)) {
                        "Archive entry escaped destination: $normalized"
                    }
                    if (entry.isDirectory) {
                        require((output.exists() && output.isDirectory) || output.mkdirs()) {
                            "Could not create extracted directory: $normalized"
                        }
                    } else {
                        val outputParent = output.parentFile
                        require(outputParent != null && ((outputParent.exists() && outputParent.isDirectory) || outputParent.mkdirs())) {
                            "Could not create parent for extracted file: $normalized"
                        }
                        require(!output.exists()) { "Archive entry conflicts with an existing path: $normalized" }
                        BufferedOutputStream(output.outputStream()).use { sink ->
                            while (true) {
                                RiftDeadline.check("archive extraction")
                                val read = zip.read(buffer)
                                if (read <= 0) break
                                require(extractedBytes + read <= MAX_ARCHIVE_EXTRACTED_BYTES) {
                                    "Expanded archive exceeds ${MAX_ARCHIVE_EXTRACTED_BYTES / (1024 * 1024)} MiB"
                                }
                                sink.write(buffer, 0, read)
                                extractedBytes += read
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }

            commitStaged(temporary, destination, to)
            invalidateIndex(to)
            return (stat(to) ?: JSONObject())
                .put("entries", entries)
                .put("extractedBytes", extractedBytes)
        } catch (error: Throwable) {
            deletePath(temporary)
            throw error
        } finally {
            deletePath(temporary)
        }
    }

    private fun projectOverview(path: String, requestedLimit: Int, requestedKind: String = "", query: String = ""): JSONObject {
        val base = sandboxFile(path)
        require(base.exists() && base.isDirectory) { "Workspace directory not found: $path" }
        val limit = requestedLimit.coerceIn(1, 240)
        val kind = requestedKind.trim().lowercase()
        if (kind == "graph") return projectGraph(path, query, requestedLimit)
        if (kind == "impact") return projectImpact(path, query, requestedLimit)
        if (kind == "validation") return projectValidation(path, query)
        if (kind == "consistency") return projectConsistency(path, query, requestedLimit)
        val indexStats = refreshSymbolIndex(base)

        val children = (base.listFiles()
            ?: throw IllegalStateException("Could not read workspace directory: ${relativePath(base)}"))
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
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
        val languageCounts = linkedMapOf<String, Int>()
        var dependencyEdges = 0
        symbolIndex.forEach { (indexedPath, indexed) ->
            if (!isPathWithin(indexedPath, path)) return@forEach
            languageCounts[indexed.language] = (languageCounts[indexed.language] ?: 0) + 1
            dependencyEdges += indexed.dependencies.size
        }
        val languages = JSONObject()
        languageCounts.toSortedMap().forEach { (language, count) -> languages.put(language, count) }
        return JSONObject()
            .put("root", normalizedPath(path))
            .put("localOnly", true)
            .put("codeMode", "rift-code-mode-v1")
            .put("projectIntelligence", "v2")
            .put("directChildren", children.size)
            .put("entries", entries)
            .put("truncated", children.size > limit)
            .put("intelligence", JSONObject()
                .put("index", indexStats)
                .put("languages", languages)
                .put("dependencyEdges", dependencyEdges)
                .put("views", JSONArray(listOf("graph", "impact", "validation", "consistency")))
                .put("viewUsage", "project kind=graph|impact|validation|consistency; query is used by focused graph/impact/validation views"))
            .put("operations", JSONArray(listOf("project", "snapshot", "stat", "hash", "list", "search", "symbols", "references", "read", "read_range", "read_symbol", "write", "replace", "patch", "patch_range", "apply_hunks", "mkdir", "remove", "move", "rename", "copy", "archive", "extract")))
    }

    private fun projectGraph(path: String, query: String, requestedLimit: Int): JSONObject =
        buildProjectGraph(
            path = path,
            query = query,
            fileLimit = MAX_GRAPH_FILES_PREVIEW,
            edgeLimit = requestedLimit.coerceIn(1, MAX_GRAPH_EDGES),
            includeFileEvidence = false,
            verifyRepositoryContent = false
        )

    private fun buildProjectGraph(
        path: String,
        query: String,
        fileLimit: Int,
        edgeLimit: Int,
        includeFileEvidence: Boolean,
        verifyRepositoryContent: Boolean
    ): JSONObject {
        val base = sandboxFile(path)
        val indexStats = refreshSymbolIndex(base, verifyContent = verifyRepositoryContent)
        val boundedFileLimit = fileLimit.coerceAtLeast(1)
        val boundedEdgeLimit = edgeLimit.coerceAtLeast(1)
        val q = query.trim().lowercase()
        val indexed = symbolIndex.filterKeys { isPathWithin(it, path) }.toSortedMap()
        val repositoryFiles = repositoryFileIndex.filterKeys { isPathWithin(it, path) }.toSortedMap()
        val resolutionPaths = if (includeFileEvidence) {
            repositoryFiles.keys.toSortedSet()
        } else {
            indexed.keys.toSortedSet()
        }
        val selected = indexed.filter { (sourcePath, file) ->
            q.isEmpty() || sourcePath.lowercase().contains(q) || file.symbols.any { it.name.lowercase().contains(q) }
        }.toSortedMap()

        val edges = JSONArray()
        var resolved = 0
        var unresolved = 0
        var total = 0
        selected.forEach { (sourcePath, file) ->
            total += file.dependencies.size
            val dependenciesForResolution = if (includeFileEvidence) {
                val remaining = (boundedEdgeLimit - edges.length()).coerceAtLeast(0)
                file.dependencies.take(remaining)
            } else {
                file.dependencies
            }
            dependenciesForResolution.forEach { dependency ->
                val target = resolveDependency(path, sourcePath, dependency, resolutionPaths)
                if (target == null) unresolved += 1 else resolved += 1
                if (edges.length() < boundedEdgeLimit) {
                    edges.put(JSONObject()
                        .put("source", sourcePath)
                        .put("kind", dependency.kind)
                        .put("specifier", dependency.specifier)
                        .put("line", dependency.line)
                        .put("target", target ?: JSONObject.NULL))
                }
            }
        }

        val files = JSONArray()
        selected.keys.take(boundedFileLimit).forEach { files.put(it) }
        val filesTruncated = selected.size > boundedFileLimit
        val edgesTruncated = total > boundedEdgeLimit

        val out = JSONObject()
            .put("root", normalizedPath(path))
            .put("projectIntelligence", "v2")
            .put("view", "graph")
            .put("query", query)
            .put("index", indexStats)
            .put("filesIndexed", indexed.size)
            .put("filesMatched", selected.size)
            .put("matchedFiles", files)
            .put("dependencyEdges", total)
            .put("resolvedEdges", resolved)
            .put("unresolvedEdges", unresolved)
            .put("edges", edges)
            .put("fileLimit", boundedFileLimit)
            .put("edgeLimit", boundedEdgeLimit)
            .put("filesTruncated", filesTruncated)
            .put("edgesTruncated", edgesTruncated)

        if (includeFileEvidence) {
            val evidenceRows = JSONArray()
            repositoryFiles.entries.sortedBy { it.key }.take(boundedFileLimit).forEach { (filePath, evidence) ->
                evidenceRows.put(JSONObject()
                    .put("path", filePath)
                    .put("size", evidence.size)
                    .put("sha256", evidence.sha256 ?: JSONObject.NULL)
                    .put("semanticStatus", evidence.semanticStatus)
                    .put("semanticReason", evidence.semanticReason ?: JSONObject.NULL))
            }
            val repositoryFilesTruncated = repositoryFiles.size > boundedFileLimit
            out.put("repositoryFilesMatched", repositoryFiles.size)
                .put("repositoryFileEvidence", evidenceRows)
                .put("repositoryFilesTruncated", repositoryFilesTruncated)
                .put("repositoryContentVerified", indexStats.optBoolean("contentVerified", false))
                .put("repositoryEvidenceComplete", indexStats.optBoolean("repositoryEvidenceComplete", false))
                .put("semanticEvidenceComplete", indexStats.optBoolean("semanticEvidenceComplete", false))
                .put("repositoryEvidenceIncompleteReasons", indexStats.optJSONArray("incompleteReasons") ?: JSONArray())
                .put("truncated", filesTruncated || edgesTruncated || repositoryFilesTruncated)
        } else {
            out.put("truncated", filesTruncated || edgesTruncated)
        }
        return out
    }

    private fun projectConsistency(path: String, query: String, requestedLimit: Int): JSONObject {
        val graph = buildProjectGraph(
            path = path,
            query = "",
            fileLimit = MAX_CONSISTENCY_INPUT_FILES,
            edgeLimit = MAX_CONSISTENCY_INPUT_EDGES,
            includeFileEvidence = true,
            verifyRepositoryContent = true
        )
        val result = repositoryConsistencyObserver.foundationView(
            projectRoot = normalizedPath(path),
            projectGraph = graph
        )
            .put("view", "consistency")
            .put("projectIntelligence", "v2")
            .put("observerAuthority", "evidence-only")

        val mode = query.trim().lowercase()
        if (mode == "full") {
            return result.put("responseMode", "full")
        }

        val previewLimit = requestedLimit.coerceIn(1, 40)
        fun preview(name: String): JSONArray {
            val source = result.optJSONArray(name) ?: JSONArray()
            val out = JSONArray()
            for (index in 0 until minOf(source.length(), previewLimit)) out.put(source.get(index))
            return out
        }

        return JSONObject()
            .put("format", result.getString("format"))
            .put("version", result.getInt("version"))
            .put("phase", result.getString("phase"))
            .put("projectRoot", result.getString("projectRoot"))
            .put("projectIntelligence", "v2")
            .put("sourceOfTruth", result.getString("sourceOfTruth"))
            .put("authoritative", result.getBoolean("authoritative"))
            .put("rebuildableCache", result.getBoolean("rebuildableCache"))
            .put("complete", result.getBoolean("complete"))
            .put("incompleteReasons", result.getJSONArray("incompleteReasons"))
            .put("graphSha256", result.getString("graphSha256"))
            .put("graphId", result.getString("graphId"))
            .put("schema", result.getJSONObject("schema"))
            .put("bounds", result.getJSONObject("bounds"))
            .put("cache", result.getJSONObject("cache"))
            .put("counts", result.getJSONObject("counts"))
            .put("view", "consistency")
            .put("observerAuthority", "evidence-only")
            .put("responseMode", "compact")
            .put("previewLimit", previewLimit)
            .put("factPreview", preview("facts"))
            .put("edgePreview", preview("edges"))
            .put("findingPreview", preview("findings"))
            .put("fullResultAvailable", true)
            .put("fullResultUsage", "project kind=consistency query=full")
    }

    private fun projectImpact(path: String, query: String, requestedLimit: Int): JSONObject {
        val needle = query.trim()
        require(needle.isNotEmpty()) { "project impact view requires query" }
        val base = sandboxFile(path)
        val indexStats = refreshSymbolIndex(base)
        val indexed = symbolIndex.filterKeys { isPathWithin(it, path) }
        val allPaths = indexed.keys.toSet()
        val identifier = needle.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*"))
        val exactDefinitions = if (identifier) indexed.values.flatMap { file -> file.symbols.filter { it.name == needle } } else emptyList()
        val fuzzyDefinitions = if (exactDefinitions.isEmpty() && identifier) indexed.values.flatMap { file -> file.symbols.filter { it.name.contains(needle, ignoreCase = true) } }.take(40) else exactDefinitions
        val pathMatches = indexed.keys.filter { it.contains(needle, ignoreCase = true) }.take(40)
        val targetPaths = linkedSetOf<String>()
        fuzzyDefinitions.forEach { targetPaths += it.path }
        pathMatches.forEach { targetPaths += it }
        val definitions = JSONArray()
        fuzzyDefinitions.take(requestedLimit.coerceIn(1, 120)).forEach { definitions.put(symbolJson(it)) }

        val dependents = linkedSetOf<String>()
        val dependencies = JSONArray()
        indexed.forEach { (sourcePath, file) ->
            file.dependencies.forEach { dependency ->
                val target = resolveDependency(path, sourcePath, dependency, allPaths)
                if (sourcePath in targetPaths && dependencies.length() < 160) {
                    dependencies.put(JSONObject()
                        .put("source", sourcePath)
                        .put("specifier", dependency.specifier)
                        .put("kind", dependency.kind)
                        .put("target", target ?: JSONObject.NULL))
                }
                if (target != null && target in targetPaths) dependents += sourcePath
            }
        }

        val references = if (identifier) findReferences(path, needle, requestedLimit.coerceIn(1, 200))
            else JSONObject().put("references", JSONArray()).put("truncated", false)
        val referenceRows = references.optJSONArray("references") ?: JSONArray()
        val tests = linkedSetOf<String>()
        dependents.filterTo(tests) { isTestPath(it) }
        for (index in 0 until referenceRows.length()) {
            val refPath = referenceRows.optJSONObject(index)?.optString("path").orEmpty()
            if (refPath.isNotBlank() && isTestPath(refPath)) tests += refPath
        }
        val docs = linkedSetOf<String>()
        targetPaths.forEach { target -> findOwningReadme(path, target)?.let { docs.add(it) } }

        val dependentJson = JSONArray(); dependents.take(160).forEach { dependentJson.put(it) }
        val targetJson = JSONArray(); targetPaths.take(80).forEach { targetJson.put(it) }
        val testJson = JSONArray(); tests.take(80).forEach { testJson.put(it) }
        val docJson = JSONArray(); docs.take(40).forEach { docJson.put(it) }
        return JSONObject()
            .put("root", normalizedPath(path))
            .put("projectIntelligence", "v2")
            .put("view", "impact")
            .put("query", needle)
            .put("index", indexStats)
            .put("targets", targetJson)
            .put("definitions", definitions)
            .put("references", referenceRows)
            .put("referencesTruncated", references.optBoolean("truncated", false))
            .put("directDependencies", dependencies)
            .put("directDependents", dependentJson)
            .put("tests", testJson)
            .put("documentation", docJson)
            .put("validation", projectValidation(path, targetPaths.firstOrNull().orEmpty()))
    }

    private fun candidateImpact(): JSONObject {
        val seed = workspaceRecords.semanticImpactSeed()
        val candidateSeed = seed.getJSONObject("candidate")
        val candidate = JSONObject()
            .put("version", candidateSeed.getInt("version"))
            .put("candidateId", candidateSeed.getString("candidateId"))
            .put("manifestSha256", candidateSeed.getString("manifestSha256"))
            .put("baseTreeSha256", candidateSeed.getString("baseTreeSha256"))
            .put("resultTreeSha256", candidateSeed.getString("resultTreeSha256"))
            .put("changeSetSha256", candidateSeed.getString("changeSetSha256"))
            .put("structuralDiffSha256", candidateSeed.getString("structuralDiffSha256"))
            .put("changedFiles", candidateSeed.getInt("changedFiles"))

        val incompleteReasons = linkedSetOf<String>()
        if (!seed.optBoolean("complete", false)) incompleteReasons += "semantic-seed-incomplete"

        val indexStats = refreshSymbolIndex(workspaceRoot)
        if (indexStats.optBoolean("truncated", false)) incompleteReasons += "project-index-truncated"

        val changes = seed.getJSONArray("changes")
        val projectRoots = linkedSetOf<String>()
        val sourceTargets = linkedSetOf<String>()
        val changedTests = linkedSetOf<String>()
        val changedDocs = linkedSetOf<String>()
        val changedBuildConfigs = linkedSetOf<String>()
        val semanticRows = JSONArray()
        val semanticDeltas = LinkedHashMap<String, RiftSourceIntelligenceV2.Delta>()
        val changedSymbolNames = linkedSetOf<String>()
        val apiChangedPaths = linkedSetOf<String>()

        for (index in 0 until changes.length()) {
            val row = changes.getJSONObject(index)
            val rawPath = row.getString("path").trim('/')
            val path = "$WORKSPACE_ROOT/$rawPath"
            val projectRoot = candidateProjectRoot(path)
            projectRoots += projectRoot
            val testPath = isTestPath(path)
            val category = RiftSourceIntelligenceV2.classifyPath(path, testPath)
            if (testPath) changedTests += path
            if (category == "documentation") changedDocs += path
            if (category == "build-config") changedBuildConfigs += path

            val out = JSONObject()
                .put("path", path)
                .put("status", row.getString("status"))
                .put("category", category)
                .put("projectRoot", projectRoot)
                .put("source", row.optBoolean("source", false))

            if (row.optBoolean("source", false)) {
                sourceTargets += path
                if (!row.optBoolean("semanticTextComplete", false)) {
                    incompleteReasons += "source-text-incomplete"
                    out.put("semanticComplete", false)
                } else {
                    val beforeExists = !row.isNull("before")
                    val afterExists = !row.isNull("after")
                    val beforeText = if (row.isNull("beforeText")) null else row.optString("beforeText")
                    val afterText = if (row.isNull("afterText")) null else row.optString("afterText")
                    val delta = try {
                        RiftSourceIntelligenceV2.diff(
                            path = path,
                            beforeExists = beforeExists,
                            beforeText = beforeText,
                            afterExists = afterExists,
                            afterText = afterText
                        )
                    } catch (bound: RiftSourceIntelligenceV2.AnalysisBoundExceeded) {
                        incompleteReasons += bound.reason
                        out.put("semanticComplete", false)
                            .put("semanticReason", bound.reason)
                        semanticRows.put(out)
                        continue
                    }
                    semanticDeltas[path] = delta
                    if (delta.truncated) incompleteReasons += "semantic-delta-truncated"
                    if (delta.apiSurfaceChanged) apiChangedPaths += path
                    delta.addedSymbols.forEach { changedSymbolNames += it.name }
                    delta.removedSymbols.forEach { changedSymbolNames += it.name }
                    delta.changedSignatures.forEach {
                        changedSymbolNames += it.before.name
                        changedSymbolNames += it.after.name
                    }
                    out.put("semanticComplete", !delta.truncated)
                        .put("semantic", semanticDeltaJson(delta))
                }
            }
            semanticRows.put(out)
        }

        if (projectRoots.size > MAX_CANDIDATE_PROJECTS) incompleteReasons += "project-root-bound"
        val selectedProjectRoots = projectRoots.sorted().take(MAX_CANDIDATE_PROJECTS).toSet()
        val indexed = symbolIndex.filterKeys { path ->
            selectedProjectRoots.any { root -> isPathWithin(path, root) }
        }
        val resolutionPaths = (indexed.keys + sourceTargets).toSet()

        val dependencyRows = ArrayList<JSONObject>()
        val dependencyKeys = HashSet<String>()
        fun addDependency(
            sourcePath: String,
            dependency: DependencyRecord,
            relation: String
        ) {
            if (dependencyRows.size >= MAX_CANDIDATE_DEPENDENCIES) {
                incompleteReasons += "dependency-bound"
                return
            }
            val projectRoot = candidateProjectRoot(sourcePath)
            val target = resolveDependency(projectRoot, sourcePath, dependency, resolutionPaths)
            val key = "$relation|$sourcePath|${dependency.kind}|${dependency.specifier}|${target.orEmpty()}"
            if (!dependencyKeys.add(key)) return
            dependencyRows += JSONObject()
                .put("relation", relation)
                .put("source", sourcePath)
                .put("kind", dependency.kind)
                .put("specifier", dependency.specifier)
                .put("target", target ?: JSONObject.NULL)
        }

        for (sourcePath in sourceTargets.sorted()) {
            indexed[sourcePath]?.dependencies
                ?.sortedWith(compareBy({ it.kind }, { it.specifier }, { it.line }))
                ?.forEach { addDependency(sourcePath, it, "current") }
            val delta = semanticDeltas[sourcePath] ?: continue
            delta.addedDependencies.forEach {
                addDependency(sourcePath, DependencyRecord(it.specifier, it.kind, it.line), "added")
            }
            delta.removedDependencies.forEach {
                addDependency(sourcePath, DependencyRecord(it.specifier, it.kind, it.line), "removed")
            }
        }

        val dependentRows = ArrayList<JSONObject>()
        val dependentKeys = HashSet<String>()
        outer@ for ((sourcePath, indexedFile) in indexed.toSortedMap()) {
            val projectRoot = candidateProjectRoot(sourcePath)
            for (dependency in indexedFile.dependencies.sortedWith(compareBy({ it.kind }, { it.specifier }, { it.line }))) {
                val target = resolveDependency(projectRoot, sourcePath, dependency, resolutionPaths) ?: continue
                if (target !in sourceTargets) continue
                val key = "$sourcePath|$target|${dependency.kind}|${dependency.specifier}"
                if (!dependentKeys.add(key)) continue
                if (dependentRows.size >= MAX_CANDIDATE_DEPENDENTS) {
                    incompleteReasons += "dependent-bound"
                    break@outer
                }
                dependentRows += JSONObject()
                    .put("source", sourcePath)
                    .put("target", target)
                    .put("kind", dependency.kind)
                    .put("specifier", dependency.specifier)
                if (isTestPath(sourcePath)) changedTests += sourcePath
            }
        }

        val changedNames = changedSymbolNames.sorted()
        if (changedNames.size > MAX_CANDIDATE_CHANGED_SYMBOLS) incompleteReasons += "changed-symbol-bound"
        val referenceNames = changedNames.take(MAX_CANDIDATE_REFERENCE_SYMBOLS)
        if (changedNames.size > referenceNames.size) incompleteReasons += "reference-symbol-bound"
        val referenceRows = candidateReferences(indexed, referenceNames)
        if (referenceRows.second) incompleteReasons += "reference-bound"
        referenceRows.first.forEach { row ->
            if (isTestPath(row.getString("path"))) changedTests += row.getString("path")
        }

        val testPaths = indexed.keys.filter(::isTestPath).sorted()
        val affinityTargets = sourceTargets.sorted().take(MAX_CANDIDATE_AFFINITY_TARGETS)
        if (sourceTargets.size > affinityTargets.size) incompleteReasons += "test-affinity-target-bound"
        for (target in affinityTargets) {
            val projectRoot = candidateProjectRoot(target)
            val relative = if (target.startsWith("$projectRoot/")) target.removePrefix("$projectRoot/") else target
            val tokens = validationQueryTokens(relative)
            if (tokens.isEmpty()) continue
            testPaths.asSequence()
                .filter { isPathWithin(it, projectRoot) }
                .map { it to validationTestAffinity(it, relative, tokens) }
                .filter { it.second > 0 }
                .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
                .take(8)
                .forEach { changedTests += it.first }
        }

        val owningDocs = linkedSetOf<String>()
        for (path in semanticRowsToPaths(semanticRows)) {
            val projectRoot = candidateProjectRoot(path)
            ownershipDocsFor(projectRoot, path).forEach { owningDocs += it }
            findOwningReadme(projectRoot, path)?.let { owningDocs += it }
        }
        for (root in selectedProjectRoots.sorted()) {
            listOf(
                "$root/docs/PATCH_HISTORY.md",
                "$root/ROADMAP.md",
                "$root/docs/PROJECT_STATUS.md",
                "$root/docs/SOURCE_OWNERSHIP.md"
            ).forEach { candidatePath ->
                if (sandboxFile(candidatePath).isFile) owningDocs += candidatePath
            }
        }

        if (changedTests.size > MAX_CANDIDATE_TESTS) incompleteReasons += "test-bound"
        if (owningDocs.size > MAX_CANDIDATE_DOCS) incompleteReasons += "documentation-bound"

        val projectRows = JSONArray()
        for (root in selectedProjectRoots.sorted()) {
            val projectChanges = semanticRowsToPaths(semanticRows).filter { isPathWithin(it, root) }
            projectRows.put(JSONObject()
                .put("root", root)
                .put("changedFiles", projectChanges.size)
                .put("sourceFiles", projectChanges.count { it in sourceTargets })
                .put("testFiles", projectChanges.count(::isTestPath))
                .put("documentationFiles", projectChanges.count(RiftSourceIntelligenceV2::isDocumentationPath))
                .put("buildConfigFiles", projectChanges.count(RiftSourceIntelligenceV2::isBuildConfigPath)))
        }

        val reasons = incompleteReasons.sorted()
        val payload = JSONObject()
            .put("format", "rift-semantic-impact-v1")
            .put("version", 1)
            .put("projectIntelligence", "v2")
            .put("candidate", candidate)
            .put("complete", reasons.isEmpty())
            .put("incompleteReasons", JSONArray(reasons))
            .put("projects", projectRows)
            .put("changes", semanticRows)
            .put("changedSymbols", JSONArray(changedNames.take(MAX_CANDIDATE_CHANGED_SYMBOLS)))
            .put("apiSurfaceChangedPaths", JSONArray(apiChangedPaths.sorted().take(MAX_CANDIDATE_CHANGED_FILES)))
            .put("directDependencies", JSONArray(dependencyRows))
            .put("directDependents", JSONArray(dependentRows))
            .put("references", JSONArray(referenceRows.first))
            .put("tests", JSONArray(changedTests.sorted().take(MAX_CANDIDATE_TESTS)))
            .put("documentation", JSONArray(owningDocs.sorted().take(MAX_CANDIDATE_DOCS)))
            .put("changedDocumentation", JSONArray(changedDocs.sorted().take(MAX_CANDIDATE_CHANGED_FILES)))
            .put("changedBuildConfig", JSONArray(changedBuildConfigs.sorted().take(MAX_CANDIDATE_CHANGED_FILES)))
            .put("seedComplete", seed.optBoolean("complete", false))

        val semanticSha = RiftPatchManifestV1.sha256Canonical(payload)
        payload.put("semanticImpactSha256", semanticSha)
        payload.put("indexDiagnostics", indexStats)
        return payload
    }

    private fun candidateReferences(
        indexed: Map<String, IndexedFile>,
        symbolNames: List<String>
    ): Pair<List<JSONObject>, Boolean> {
        if (symbolNames.isEmpty()) return Pair(emptyList(), false)
        val escaped = symbolNames.distinct().sorted().map { Regex.escape(it) }
        val pattern = Regex("(?<![A-Za-z0-9_$])(" + escaped.joinToString("|") + ")(?![A-Za-z0-9_$])")
        val definitionLines = HashSet<String>()
        for ((_, file) in indexed) {
            file.symbols.forEach { definitionLines += "${it.name}@${it.path}:${it.line}" }
        }

        val out = ArrayList<JSONObject>()
        var truncated = false
        outer@ for (path in indexed.keys.sorted()) {
            val file = sandboxFile(path)
            if (!file.isFile || file.length() > MAX_WORKSPACE_SEARCH_FILE_BYTES || !isTextFile(file)) continue
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEachIndexed { lineIndex, line ->
                    if (truncated) return@forEachIndexed
                    for (match in pattern.findAll(line)) {
                        if (out.size >= MAX_CANDIDATE_REFERENCES) {
                            truncated = true
                            break
                        }
                        val symbol = match.value
                        val key = "$symbol@$path:${lineIndex + 1}"
                        out += JSONObject()
                            .put("symbol", symbol)
                            .put("path", path)
                            .put("line", lineIndex + 1)
                            .put("column", match.range.first + 1)
                            .put("definition", key in definitionLines)
                            .put("preview", compactPreview(line))
                    }
                }
            }
            if (truncated) break@outer
        }
        return Pair(out, truncated)
    }

    private fun ownershipDocsFor(projectRoot: String, sourcePath: String): Set<String> {
        val ledger = sandboxFile("$projectRoot/docs/SOURCE_OWNERSHIP.md")
        if (!ledger.isFile || ledger.length() > MAX_INDEX_FILE_BYTES) return emptySet()
        val repoRelative = if (sourcePath.startsWith("$projectRoot/")) {
            sourcePath.removePrefix("$projectRoot/")
        } else {
            sourcePath
        }
        val prefix = "| " + '`' + repoRelative + '`' + " |"
        val out = linkedSetOf<String>()
        ledger.useLines { lines ->
            lines.filter { it.trimStart().startsWith(prefix) }.forEach { line ->
                Regex("`([^`]+)`").findAll(line)
                    .map { it.groupValues[1] }
                    .drop(1)
                    .filter { it.startsWith("docs/") || it.equals("README.md", ignoreCase = true) }
                    .forEach { doc -> out += "$projectRoot/$doc" }
            }
        }
        return out
    }

    private fun semanticDeltaJson(delta: RiftSourceIntelligenceV2.Delta): JSONObject {
        val added = JSONArray()
        delta.addedSymbols.forEach { added.put(sourceSymbolJson(it)) }
        val removed = JSONArray()
        delta.removedSymbols.forEach { removed.put(sourceSymbolJson(it)) }
        val signatures = JSONArray()
        delta.changedSignatures.forEach {
            signatures.put(JSONObject()
                .put("before", sourceSymbolJson(it.before))
                .put("after", sourceSymbolJson(it.after)))
        }
        val addedDependencies = JSONArray()
        delta.addedDependencies.forEach { addedDependencies.put(sourceDependencyJson(it)) }
        val removedDependencies = JSONArray()
        delta.removedDependencies.forEach { removedDependencies.put(sourceDependencyJson(it)) }
        return JSONObject()
            .put("languageBefore", delta.languageBefore)
            .put("languageAfter", delta.languageAfter)
            .put("addedSymbols", added)
            .put("removedSymbols", removed)
            .put("changedSignatures", signatures)
            .put("addedDependencies", addedDependencies)
            .put("removedDependencies", removedDependencies)
            .put("apiSurfaceChanged", delta.apiSurfaceChanged)
            .put("truncated", delta.truncated)
    }

    private fun sourceSymbolJson(symbol: RiftSourceIntelligenceV2.Symbol): JSONObject = JSONObject()
        .put("name", symbol.name)
        .put("kind", symbol.kind)
        .put("path", symbol.path)
        .put("line", symbol.line)
        .put("endLine", symbol.endLine)
        .put("signature", symbol.signature)

    private fun sourceDependencyJson(dependency: RiftSourceIntelligenceV2.Dependency): JSONObject = JSONObject()
        .put("specifier", dependency.specifier)
        .put("kind", dependency.kind)
        .put("line", dependency.line)

    private fun semanticRowsToPaths(rows: JSONArray): List<String> =
        (0 until rows.length()).mapNotNull { index ->
            rows.optJSONObject(index)?.optString("path")?.takeIf { it.isNotBlank() }
        }.distinct().sorted()

    private fun candidateProjectRoot(path: String): String {
        val normalized = normalizedPath(path)
        if (normalized == WORKSPACE_ROOT) return WORKSPACE_ROOT
        val relative = normalized.removePrefix("$WORKSPACE_ROOT/")
        if (!relative.contains('/')) return WORKSPACE_ROOT
        return "$WORKSPACE_ROOT/${relative.substringBefore('/')}"
    }

    private fun projectValidation(path: String, query: String): JSONObject {
        val base = sandboxFile(path)
        require(base.exists() && base.isDirectory) { "Workspace directory not found: $path" }
        val commands = JSONArray()
        val packageJson = File(base, "package.json")
        if (packageJson.isFile && packageJson.length() <= MAX_INDEX_FILE_BYTES) {
            val pkg = runCatching { JSONObject(packageJson.readText(Charsets.UTF_8)) }.getOrNull()
            val scripts = pkg?.optJSONObject("scripts")
            listOf("check", "test", "lint", "build").forEach { name ->
                if (scripts?.optString(name)?.isNotBlank() == true) {
                    commands.put(JSONObject().put("command", "npm run $name").put("source", relativePath(packageJson)).put("scope", "local-source"))
                }
            }
        }
        val external = JSONArray()
        if (File(base, "android").isDirectory || File(base, "build.gradle.kts").isFile || File(base, "build.gradle").isFile) {
            external.put("Android/Gradle compile after source checks when the project build pipeline provides it")
        }
        if (File(base, "CMakeLists.txt").isFile) external.put("Run the configured CMake/native build and affected native tests")
        val relevantTests = JSONArray()
        val projectRootPath = normalizedPath(path)
        val normalizedQuery = normalizedPath(query)
        val projectRelativeQuery = when {
            normalizedQuery == projectRootPath -> ""
            normalizedQuery.startsWith("$projectRootPath/") -> normalizedQuery.removePrefix("$projectRootPath/")
            else -> normalizedQuery
        }
        val queryTokens = validationQueryTokens(projectRelativeQuery)
        if (queryTokens.isNotEmpty()) {
            base.walkTopDown().onEnter { directory ->
                RiftDeadline.check("project validation")
                directory == base || !isIgnoredDirectory(directory)
            }
                .onEach { RiftDeadline.check("project validation") }
                .filter { it.isFile && isTestPath(relativePath(it)) }
                .map { relativePath(it) }
                .map { testPath -> testPath to validationTestAffinity(testPath, projectRelativeQuery, queryTokens) }
                .filter { (_, score) -> score > 0 }
                .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
                .take(60)
                .forEach { (testPath, _) -> relevantTests.put(testPath) }
        }
        return JSONObject()
            .put("root", normalizedPath(path))
            .put("projectIntelligence", "v2")
            .put("view", "validation")
            .put("query", query)
            .put("commands", commands)
            .put("relevantTests", relevantTests)
            .put("externalChecks", external)
    }

    private fun findOwningReadme(projectPath: String, sourcePath: String): String? {
        val projectRoot = sandboxFile(projectPath).canonicalFile
        var cursor = sandboxFile(sourcePath).let { if (it.isDirectory) it else it.parentFile }?.canonicalFile
        while (cursor != null && isInsideRoot(cursor)) {
            val readme = File(cursor, "README.md")
            if (readme.isFile) return relativePath(readme)
            if (cursor == projectRoot) break
            cursor = cursor.parentFile
        }
        return null
    }

    private fun isTestPath(path: String): Boolean {
        val lower = path.lowercase()
        return lower.contains("/test/") || lower.contains("/tests/") || lower.contains("__tests__") ||
            lower.contains("test-") || lower.contains("_test.") || lower.contains(".test.") || lower.contains(".spec.")
    }

    private fun validationQueryTokens(query: String): Set<String> {
        val stopWords = setOf("workspace", "src", "source", "include", "includes", "android", "app", "main", "java", "cpp", "test", "tests", "vortex", "com")
        val camelSplit = query.replace(Regex("([a-z0-9])([A-Z])"), "\$1_\$2")
        return camelSplit.lowercase()
            .split(Regex("[/._\\-]+"))
            .asSequence()
            .map { it.trim() }
            .filter { token -> token.length >= 3 && token !in stopWords && token.all { it.isLetterOrDigit() } }
            .toCollection(linkedSetOf())
    }

    private fun validationTestAffinity(testPath: String, projectRelativeQuery: String, queryTokens: Set<String>): Int {
        val lowerPath = testPath.lowercase()
        val testName = lowerPath.substringAfterLast('/').substringBeforeLast('.')
        val queryName = projectRelativeQuery.lowercase().substringAfterLast('/').substringBeforeLast('.')
        var score = 0
        if (queryName.isNotBlank() && (testName.contains(queryName) || queryName.contains(testName))) score += 8
        val pathSegments = lowerPath.split('/')
        queryTokens.forEach { token ->
            if (testName.contains(token)) score += 4
            if (pathSegments.any { segment -> segment == token || segment.contains(token) }) score += 2
        }
        return score
    }

    private fun uniqueDependencyCandidate(candidates: Sequence<String>): String? {
        val distinct = candidates.distinct().take(2).toList()
        return distinct.singleOrNull()
    }

    private fun resolveDependency(projectPath: String, sourcePath: String, dependency: DependencyRecord, allPaths: Set<String>): String? {
        val specifier = dependency.specifier.trim().replace('\\', '/')
        if (specifier.isBlank()) return null
        val sourceFile = sandboxFile(sourcePath)
        val parent = sourceFile.parentFile ?: return null
        fun existingCandidate(candidate: File): String? = runCatching { relativePath(candidate) }.getOrNull()?.takeIf { it in allPaths }
        fun tryRelative(raw: String): String? {
            val direct = File(parent, raw)
            val candidates = listOf(
                direct,
                File("${direct.path}.kt"), File("${direct.path}.java"), File("${direct.path}.js"), File("${direct.path}.ts"),
                File("${direct.path}.cpp"), File("${direct.path}.cc"), File("${direct.path}.c"), File("${direct.path}.h"), File("${direct.path}.hpp"), File("${direct.path}.py"),
                File(direct, "index.js"), File(direct, "index.ts")
            )
            return candidates.firstNotNullOfOrNull(::existingCandidate)
        }
        if (specifier.startsWith(".")) tryRelative(specifier)?.let { return it }
        if (dependency.kind == "include") {
            tryRelative(specifier)?.let { return it }
            val suffixMatches = allPaths.filter { it.endsWith("/$specifier") }
            if (suffixMatches.size == 1) return suffixMatches.first()
            if (suffixMatches.size > 1) {
                val includeMatches = suffixMatches.filter { it.contains("/include/") }
                if (includeMatches.size == 1) return includeMatches.first()
            }
            return null
        }
        if (dependency.kind == "python") {
            val module = specifier.substringBefore(' ').replace('.', '/')
            return uniqueDependencyCandidate(
                allPaths.asSequence().filter {
                    it.endsWith("/$module.py") || it.endsWith("/$module/__init__.py")
                }
            )
        }
        if (dependency.kind == "import" && !specifier.startsWith(".") && !specifier.contains('/')) {
            val qualified = specifier.removeSuffix(".*").trimEnd('.').replace('.', '/')
            if (qualified.isNotBlank()) {
                uniqueDependencyCandidate(
                    allPaths.asSequence().filter {
                        it.endsWith("/$qualified.kt") || it.endsWith("/$qualified.java")
                    }
                )?.let { return it }
            }
        }
        val tail = specifier.substringAfterLast('.').substringAfterLast('/').substringAfterLast(':').trim('*')
        if (tail.isNotBlank()) {
            return uniqueDependencyCandidate(
                symbolIndex.entries.asSequence()
                    .filter { (candidatePath, _) -> isPathWithin(candidatePath, projectPath) }
                    .filter { (_, indexed) -> indexed.symbols.any { it.name == tail } }
                    .map { it.key }
            )
        }
        return null
    }

    private fun searchText(path: String, query: String, caseSensitive: Boolean, maxMatches: Int): JSONObject {
        require(query.isNotEmpty()) { "search.query cannot be empty" }
        val base = sandboxFile(path)
        require(base.exists()) { "Search path not found: $path" }
        val matches = JSONArray()
        var filesScanned = 0
        var filesSkipped = 0
        var hitLimit = false
        var bytesScanned = 0L

        fun scan(file: File) {
            RiftDeadline.check("workspace search")
            if (hitLimit) return
            require(isInsideRoot(file)) { "Workspace search escaped Rift MCP sandbox" }
            if (!file.isFile) return
            val fileBytes = file.length()
            if (fileBytes > MAX_WORKSPACE_SEARCH_FILE_BYTES) {
                filesSkipped += 1
                return
            }
            require(bytesScanned + fileBytes <= MAX_SEARCH_TOTAL_BYTES) {
                "Workspace search exceeds ${MAX_SEARCH_TOTAL_BYTES / (1024 * 1024)} MiB scan budget"
            }
            bytesScanned += fileBytes
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
            .put("bytesScanned", bytesScanned)
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
            RiftDeadline.check("workspace snapshot")
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
        var bytesScanned = 0L
        val pattern = Regex("(?<![A-Za-z0-9_$])${Regex.escape(symbol)}(?![A-Za-z0-9_$])")
        fun scan(file: File) {
            RiftDeadline.check("reference search")
            if (hitLimit || !file.isFile || isIgnoredFile(file)) return
            val fileBytes = file.length()
            if (fileBytes > MAX_WORKSPACE_SEARCH_FILE_BYTES || !isTextFile(file)) { filesSkipped += 1; return }
            require(bytesScanned + fileBytes <= MAX_SEARCH_TOTAL_BYTES) {
                "Reference search exceeds ${MAX_SEARCH_TOTAL_BYTES / (1024 * 1024)} MiB scan budget"
            }
            bytesScanned += fileBytes
            filesScanned += 1
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEachIndexed { index, line ->
                    if (index % 128 == 0) RiftDeadline.check("reference search")
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
            .put("bytesScanned", bytesScanned)
            .put("truncated", hitLimit)
    }

    private fun readSymbol(path: String, symbolName: String, requestedLine: Int, maxChars: Int): JSONObject {
        val file = sandboxFile(path)
        require(file.isFile) { "File not found: $path" }
        val indexed = indexFile(file) ?: throw IllegalArgumentException("File is not indexable text: $path")
        if (persistentIndexDirty) persistProjectIntelligence()
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
        writeBytesAtomic(file, bytes, path)
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
        writeBytesAtomic(file, bytes, path)
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

    private fun trustedProjectIntelligenceProducerSourceSha(): String? =
        BuildConfig.RIFT_SOURCE_SHA.trim().lowercase()
            .takeIf { it.matches(Regex("^(?:[0-9a-f]{40}|[0-9a-f]{64})$")) }

    private fun projectIntelligenceCacheIntegrityFailure(root: JSONObject): String? {
        val expected = root.optString("cacheSha256").trim().lowercase()
        if (!expected.matches(Regex("^[0-9a-f]{64}$"))) return "cache-integrity-missing"

        val payload = JSONObject()
        root.keys().asSequence().toList().sorted().forEach { key ->
            if (key != "cacheSha256") payload.put(key, root.get(key))
        }
        return if (RiftPatchManifestV1.sha256Canonical(payload) == expected) {
            null
        } else {
            "cache-integrity-mismatch"
        }
    }

    private fun ensurePersistentIndexLoaded() {
        if (persistentIndexLoaded) return
        persistentIndexLoaded = true
        persistentIndexLoadStatus = "missing"
        persistentIndexRejectedReason = null

        if (!projectIntelligenceCache.isFile) return
        if (projectIntelligenceCache.length() > MAX_PERSISTED_INDEX_BYTES) {
            persistentIndexLoadStatus = "rejected"
            persistentIndexRejectedReason = "cache-size-bound"
            return
        }

        val root = runCatching { JSONObject(projectIntelligenceCache.readText(Charsets.UTF_8)) }.getOrNull()
        if (root == null) {
            persistentIndexLoadStatus = "rejected"
            persistentIndexRejectedReason = "cache-malformed"
            return
        }

        if (root.optInt("version", 0) != PROJECT_INTELLIGENCE_CACHE_VERSION) {
            persistentIndexLoadStatus = "rejected"
            persistentIndexRejectedReason = "cache-schema-version"
            return
        }

        val integrityFailure = projectIntelligenceCacheIntegrityFailure(root)
        if (integrityFailure != null) {
            persistentIndexLoadStatus = "rejected"
            persistentIndexRejectedReason = integrityFailure
            return
        }

        val producer = root.optJSONObject("producer")
        val trustedSourceSha = trustedProjectIntelligenceProducerSourceSha()
        if (producer == null) {
            persistentIndexLoadStatus = "rejected"
            persistentIndexRejectedReason = "producer-missing"
            return
        }
        if (producer.optInt("sourceIntelligenceVersion", -1) != RiftSourceIntelligenceV2.VERSION) {
            persistentIndexLoadStatus = "rejected"
            persistentIndexRejectedReason = "producer-analyzer-version"
            return
        }
        if (trustedSourceSha == null) {
            persistentIndexLoadStatus = "rejected"
            persistentIndexRejectedReason = "producer-source-untrusted"
            return
        }
        if (producer.optString("sourceSha").trim().lowercase() != trustedSourceSha) {
            persistentIndexLoadStatus = "rejected"
            persistentIndexRejectedReason = "producer-source-sha"
            return
        }

        val files = root.optJSONArray("files")
        if (files == null) {
            persistentIndexLoadStatus = "rejected"
            persistentIndexRejectedReason = "cache-files-missing"
            return
        }
        persistentIndexLoadStatus = "loaded"
        for (index in 0 until files.length()) {
            val row = files.optJSONObject(index) ?: continue
            val path = row.optString("path").trim()
            if (!path.startsWith("$WORKSPACE_ROOT/")) continue
            val sha = row.optString("sha256").trim().takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
            val semanticStatus = row.optString("semanticStatus").trim().ifBlank { "unavailable" }
            val semanticReason = row.opt("semanticReason")
                ?.takeUnless { it == JSONObject.NULL }
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val modified = row.optLong("modified")
            val size = row.optLong("size")
            repositoryFileIndex[path] = RepositoryFileEvidence(
                modified = modified,
                size = size,
                sha256 = sha,
                semanticStatus = semanticStatus,
                semanticReason = semanticReason
            )
            if (semanticStatus != "indexed" || sha == null) continue

            val symbols = ArrayList<SymbolRecord>()
            val symbolRows = row.optJSONArray("symbols") ?: JSONArray()
            for (symbolIndex in 0 until symbolRows.length()) {
                val symbol = symbolRows.optJSONObject(symbolIndex) ?: continue
                val name = symbol.optString("name").trim()
                if (name.isBlank()) continue
                symbols += SymbolRecord(
                    name,
                    symbol.optString("kind"),
                    path,
                    symbol.optInt("line", 1),
                    symbol.optInt("endLine", symbol.optInt("line", 1)),
                    symbol.optString("signature")
                )
            }
            val dependencies = ArrayList<DependencyRecord>()
            val dependencyRows = row.optJSONArray("dependencies") ?: JSONArray()
            for (dependencyIndex in 0 until dependencyRows.length()) {
                val dependency = dependencyRows.optJSONObject(dependencyIndex) ?: continue
                val specifier = dependency.optString("specifier").trim()
                if (specifier.isBlank()) continue
                dependencies += DependencyRecord(specifier, dependency.optString("kind"), dependency.optInt("line", 1))
            }
            symbolIndex[path] = IndexedFile(
                modified = modified,
                size = size,
                sha256 = sha,
                language = row.optString("language", "generic"),
                symbols = symbols,
                dependencies = dependencies
            )
        }
    }

    private fun persistProjectIntelligence() {
        if (!persistentIndexLoaded || !persistentIndexDirty) return
        val files = JSONArray()
        repositoryFileIndex.entries.sortedBy { it.key }.take(MAX_PERSISTED_INDEX_FILES).forEach { (path, evidence) ->
            val indexed = symbolIndex[path]
            val symbols = JSONArray()
            indexed?.symbols?.forEach { symbol ->
                symbols.put(JSONObject()
                    .put("name", symbol.name)
                    .put("kind", symbol.kind)
                    .put("line", symbol.line)
                    .put("endLine", symbol.endLine)
                    .put("signature", symbol.signature))
            }
            val dependencies = JSONArray()
            indexed?.dependencies?.forEach { dependency ->
                dependencies.put(JSONObject()
                    .put("specifier", dependency.specifier)
                    .put("kind", dependency.kind)
                    .put("line", dependency.line))
            }
            files.put(JSONObject()
                .put("path", path)
                .put("modified", evidence.modified)
                .put("size", evidence.size)
                .put("sha256", evidence.sha256 ?: JSONObject.NULL)
                .put("semanticStatus", evidence.semanticStatus)
                .put("semanticReason", evidence.semanticReason ?: JSONObject.NULL)
                .put("language", indexed?.language ?: JSONObject.NULL)
                .put("symbols", symbols)
                .put("dependencies", dependencies))
        }
        val payloadObject = JSONObject()
            .put("version", PROJECT_INTELLIGENCE_CACHE_VERSION)
            .put("producer", JSONObject()
                .put("sourceIntelligenceVersion", RiftSourceIntelligenceV2.VERSION)
                .put("sourceSha", trustedProjectIntelligenceProducerSourceSha() ?: BuildConfig.RIFT_SOURCE_SHA))
            .put("generatedAt", System.currentTimeMillis())
            .put("files", files)
        payloadObject.put("cacheSha256", RiftPatchManifestV1.sha256Canonical(payloadObject))
        val payload = payloadObject.toString()
        val bytes = payload.toByteArray(Charsets.UTF_8)
        if (bytes.size.toLong() > MAX_PERSISTED_INDEX_BYTES) {
            runCatching { projectIntelligenceCache.delete() }
            persistentIndexDirty = false
            return
        }
        val parent = projectIntelligenceCache.parentFile ?: return
        parent.mkdirs()
        val temporary = File(parent, "${projectIntelligenceCache.name}.tmp-${UUID.randomUUID()}")
        runCatching {
            temporary.writeBytes(bytes)
            if (projectIntelligenceCache.exists() && !projectIntelligenceCache.delete()) throw IllegalStateException("Could not replace project intelligence cache")
            if (!temporary.renameTo(projectIntelligenceCache)) throw IllegalStateException("Could not commit project intelligence cache")
            persistentIndexDirty = false
        }.onFailure { temporary.delete() }
    }

    private fun refreshSymbolIndex(base: File, verifyContent: Boolean = false): JSONObject {
        ensurePersistentIndexLoaded()
        var scanned = 0
        var reused = 0
        var skipped = 0
        var removed = 0
        var metadataOnly = 0
        var truncated = false
        var bytesScanned = 0L
        var semanticBytesAccounted = 0L
        var hashBytes = 0L
        var hashFailures = 0
        val incompleteReasons = linkedSetOf<String>()
        val seen = HashSet<String>()
        var changed = false

        fun removeSemantic(path: String) {
            if (symbolIndex.remove(path) != null) changed = true
        }

        fun updateEvidence(path: String, evidence: RepositoryFileEvidence) {
            if (repositoryFileIndex[path] != evidence) {
                repositoryFileIndex[path] = evidence
                changed = true
            }
        }

        fun visit(file: File) {
            RiftDeadline.check("project index")
            if (truncated || !file.isFile || isPolicyExcludedFile(file)) return
            if (seen.size >= MAX_INDEX_FILES) {
                truncated = true
                incompleteReasons += "repository-file-bound"
                return
            }

            val path = relativePath(file)
            val size = file.length()
            val modified = file.lastModified()
            seen += path

            val previousEvidence = repositoryFileIndex[path]
            val mustVerifyHash =
                verifyContent ||
                previousEvidence == null ||
                previousEvidence.modified != modified ||
                previousEvidence.size != size ||
                previousEvidence.sha256 == null

            var contentSha = previousEvidence?.sha256
            var hashFailureReason: String? = null
            if (mustVerifyHash) {
                if (size > MAX_HASH_TOTAL_BYTES - hashBytes) {
                    contentSha = null
                    hashFailureReason = "content-hash-byte-bound"
                    incompleteReasons += "repository-content-hash-byte-bound"
                } else {
                    hashBytes += size
                    contentSha = runCatching { fileSha256(file) }.getOrElse {
                        hashFailures += 1
                        hashFailureReason = "content-hash-failure"
                        incompleteReasons += "repository-content-hash-failure"
                        null
                    }
                }
            }

            if (contentSha == null) {
                updateEvidence(
                    path,
                    RepositoryFileEvidence(
                        modified = modified,
                        size = size,
                        sha256 = null,
                        semanticStatus = "unavailable",
                        semanticReason = hashFailureReason ?: "content-hash-unavailable"
                    )
                )
                removeSemantic(path)
                skipped += 1
                return
            }

            val classification = semanticClassification(file)
            if (classification.first != "indexed") {
                updateEvidence(
                    path,
                    RepositoryFileEvidence(
                        modified = modified,
                        size = size,
                        sha256 = contentSha,
                        semanticStatus = classification.first,
                        semanticReason = classification.second
                    )
                )
                if (classification.second == "semantic-file-size-bound") {
                    incompleteReasons += "semantic-file-size-bound"
                }
                removeSemantic(path)
                metadataOnly += 1
                skipped += 1
                return
            }

            if (size > MAX_INDEX_TOTAL_BYTES - semanticBytesAccounted) {
                updateEvidence(
                    path,
                    RepositoryFileEvidence(
                        modified = modified,
                        size = size,
                        sha256 = contentSha,
                        semanticStatus = "metadata-only",
                        semanticReason = "semantic-total-byte-bound"
                    )
                )
                removeSemantic(path)
                incompleteReasons += "semantic-total-byte-bound"
                skipped += 1
                return
            }
            semanticBytesAccounted += size

            val cached = symbolIndex[path]
            if (cached != null && cached.sha256 == contentSha) {
                val refreshed = if (cached.modified != modified || cached.size != size) {
                    cached.copy(modified = modified, size = size)
                } else cached
                if (refreshed !== cached) {
                    symbolIndex[path] = refreshed
                    changed = true
                }
                updateEvidence(
                    path,
                    RepositoryFileEvidence(
                        modified = modified,
                        size = size,
                        sha256 = contentSha,
                        semanticStatus = "indexed",
                        semanticReason = null
                    )
                )
                reused += 1
                return
            }

            bytesScanned += size
            val text = runCatching { file.readText(Charsets.UTF_8) }.getOrElse {
                updateEvidence(
                    path,
                    RepositoryFileEvidence(
                        modified = modified,
                        size = size,
                        sha256 = contentSha,
                        semanticStatus = "metadata-only",
                        semanticReason = "semantic-read-failure"
                    )
                )
                removeSemantic(path)
                incompleteReasons += "semantic-read-failure"
                skipped += 1
                return
            }
            val analysis = try {
                RiftSourceIntelligenceV2.analyze(path, text, MAX_SEARCH_PREVIEW_CHARS)
            } catch (bound: RiftSourceIntelligenceV2.AnalysisBoundExceeded) {
                updateEvidence(
                    path,
                    RepositoryFileEvidence(
                        modified = modified,
                        size = size,
                        sha256 = contentSha,
                        semanticStatus = "metadata-only",
                        semanticReason = bound.reason
                    )
                )
                removeSemantic(path)
                incompleteReasons += bound.reason
                skipped += 1
                return
            } catch (_: Throwable) {
                updateEvidence(
                    path,
                    RepositoryFileEvidence(
                        modified = modified,
                        size = size,
                        sha256 = contentSha,
                        semanticStatus = "metadata-only",
                        semanticReason = "semantic-analysis-failure"
                    )
                )
                removeSemantic(path)
                incompleteReasons += "semantic-analysis-failure"
                skipped += 1
                return
            }
            val symbols = analysis.symbols.map { symbol ->
                SymbolRecord(symbol.name, symbol.kind, symbol.path, symbol.line, symbol.endLine, symbol.signature)
            }
            val dependencies = analysis.dependencies.map { dependency ->
                DependencyRecord(dependency.specifier, dependency.kind, dependency.line)
            }
            val indexed = IndexedFile(
                modified = modified,
                size = size,
                sha256 = contentSha,
                language = analysis.language,
                symbols = symbols,
                dependencies = dependencies
            )
            if (symbolIndex[path] != indexed) {
                symbolIndex[path] = indexed
                changed = true
            }
            updateEvidence(
                path,
                RepositoryFileEvidence(
                    modified = modified,
                    size = size,
                    sha256 = contentSha,
                    semanticStatus = "indexed",
                    semanticReason = null
                )
            )
            scanned += 1
        }

        if (base.isFile) {
            visit(base)
        } else {
            base.walkTopDown().onEnter { directory ->
                directory == base || !isIgnoredDirectory(directory)
            }.forEach(::visit)
        }

        val prefix = relativePath(base).let { if (it == WORKSPACE_ROOT) "$WORKSPACE_ROOT/" else "$it/" }
        val inScope: (String) -> Boolean = { key ->
            base.isDirectory && key.startsWith(prefix) || base.isFile && key == relativePath(base)
        }

        symbolIndex.keys.filter { key -> inScope(key) && key !in seen }
            .toList().forEach { key ->
                symbolIndex.remove(key)
                removed += 1
                changed = true
            }
        repositoryFileIndex.keys.filter { key -> inScope(key) && key !in seen }
            .toList().forEach { key ->
                repositoryFileIndex.remove(key)
                changed = true
            }

        if (truncated) incompleteReasons += "repository-file-bound"
        if (changed) {
            persistentIndexDirty = true
            persistProjectIntelligence()
        }

        return JSONObject()
            .put("indexed", scanned)
            .put("reused", reused)
            .put("skipped", skipped)
            .put("metadataOnly", metadataOnly)
            .put("removed", removed)
            .put("bytesScanned", bytesScanned)
            .put("repositoryHashBytes", hashBytes)
            .put("repositoryHashFailures", hashFailures)
            .put("cachedFiles", symbolIndex.size)
            .put("repositoryFiles", repositoryFileIndex.size)
            .put("persistence", "app-private-v5")
            .put("cacheSchemaVersion", PROJECT_INTELLIGENCE_CACHE_VERSION)
            .put("cacheLoadStatus", persistentIndexLoadStatus)
            .put("cacheRejectedReason", persistentIndexRejectedReason ?: JSONObject.NULL)
            .put("semanticProducerVersion", RiftSourceIntelligenceV2.VERSION)
            .put("semanticProducerSourceSha", trustedProjectIntelligenceProducerSourceSha() ?: JSONObject.NULL)
            .put("semanticProducerTrusted", trustedProjectIntelligenceProducerSourceSha() != null)
            .put("contentVerified", verifyContent)
            .put("repositoryEvidenceComplete", incompleteReasons.none { it.startsWith("repository-") })
            .put("semanticEvidenceComplete", incompleteReasons.none { it.startsWith("semantic-") })
            .put("incompleteReasons", JSONArray(incompleteReasons.sorted()))
            .put("truncated", truncated)
    }

    private fun indexFile(file: File): IndexedFile? {
        ensurePersistentIndexLoaded()
        if (!file.isFile || isPolicyExcludedFile(file)) return null
        refreshSymbolIndex(file, verifyContent = true)
        return symbolIndex[relativePath(file)]
    }

    private fun symbolJson(symbol: SymbolRecord): JSONObject = JSONObject()
        .put("name", symbol.name).put("kind", symbol.kind).put("path", symbol.path)
        .put("line", symbol.line).put("endLine", symbol.endLine).put("signature", symbol.signature)
    private fun isIgnoredDirectory(directory: File): Boolean = directory.name in ignoredDirectoryNames

    private fun isPolicyExcludedFile(file: File): Boolean {
        if (file.name in ignoredFileNames) return true
        var parent = file.parentFile
        while (parent != null) {
            if (parent == workspaceRoot) break
            if (isIgnoredDirectory(parent)) return true
            parent = parent.parentFile
        }
        return false
    }

    private fun semanticClassification(file: File): Pair<String, String?> {
        if (file.extension.lowercase() in binaryExtensions) return "metadata-only" to "binary-extension"
        if (file.length() > MAX_INDEX_FILE_BYTES) return "metadata-only" to "semantic-file-size-bound"
        if (!isTextFile(file)) return "metadata-only" to "non-text"
        return "indexed" to null
    }

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
        ensurePersistentIndexLoaded()
        val normalized = normalizedPath(path)
        if (batchInvalidationDepth > 0) {
            pendingIndexInvalidations.add(normalized)
            return
        }
        val semanticRemoved = symbolIndex.keys.filter { it == normalized || it.startsWith("$normalized/") }.toList()
        val evidenceRemoved = repositoryFileIndex.keys.filter { it == normalized || it.startsWith("$normalized/") }.toList()
        semanticRemoved.forEach(symbolIndex::remove)
        evidenceRemoved.forEach(repositoryFileIndex::remove)
        if (semanticRemoved.isNotEmpty() || evidenceRemoved.isNotEmpty()) {
            persistentIndexDirty = true
            persistProjectIntelligence()
        }
    }

    private fun flushIndexInvalidations() {
        ensurePersistentIndexLoaded()
        val pending = pendingIndexInvalidations.toList()
        pendingIndexInvalidations.clear()
        var removedAny = false
        pending.forEach { path ->
            val semanticRemoved = symbolIndex.keys.filter { it == path || it.startsWith("$path/") }.toList()
            val evidenceRemoved = repositoryFileIndex.keys.filter { it == path || it.startsWith("$path/") }.toList()
            semanticRemoved.forEach(symbolIndex::remove)
            evidenceRemoved.forEach(repositoryFileIndex::remove)
            if (semanticRemoved.isNotEmpty() || evidenceRemoved.isNotEmpty()) removedAny = true
        }
        if (removedAny) {
            persistentIndexDirty = true
            persistProjectIntelligence()
        }
    }

    private inline fun <T> batchIndexInvalidations(block: () -> T): T {
        batchInvalidationDepth++
        try {
            return block()
        } finally {
            batchInvalidationDepth--
            if (batchInvalidationDepth == 0) flushIndexInvalidations()
        }
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
            "copy", "archive", "extract" -> transaction.capture(workspaceMutationPath(args.getString("to")))
        }
    }

    private data class BatchSnapshot(val path: String, val kind: String, val backup: File?, val modified: Long)

    private inner class BatchTransaction {
        private val dir = File(transactionRoot, "batch-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}").apply { mkdirs() }
        private val snapshots = LinkedHashMap<String, BatchSnapshot>()
        private var backupBytes = 0L
        private var backupEntries = 0

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
                val stats = if (source.isDirectory) treeStats(source) else TreeStats(source.length(), 1)
                backupBytes += stats.bytes
                backupEntries += stats.entries
                require(backupBytes <= MAX_BATCH_ROLLBACK_BYTES) {
                    "Rift Code Mode rollback set exceeds ${MAX_BATCH_ROLLBACK_BYTES / (1024 * 1024)} MiB"
                }
                require(backupEntries <= MAX_BATCH_ROLLBACK_ENTRIES) {
                    "Rift Code Mode rollback set exceeds $MAX_BATCH_ROLLBACK_ENTRIES entries"
                }
                backup.parentFile?.mkdirs()
                if (source.isDirectory) {
                    require(copyTreeBounded(
                        source,
                        backup,
                        overwrite = true,
                        maxEntries = MAX_BATCH_ROLLBACK_ENTRIES,
                        maxBytes = MAX_BATCH_ROLLBACK_BYTES,
                        cooperative = true
                    )) { "Could not snapshot directory for batch rollback: $path" }
                } else {
                    RiftDeadline.check("batch rollback snapshot")
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
                        val removed = if (target.isDirectory) deleteTreeBounded(target, MAX_BATCH_ROLLBACK_ENTRIES, cooperative = false) else target.delete()
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
                            require(copyTreeBounded(
                                snapshot.backup!!,
                                target,
                                overwrite = true,
                                maxEntries = MAX_BATCH_ROLLBACK_ENTRIES,
                                maxBytes = MAX_BATCH_ROLLBACK_BYTES,
                                cooperative = false
                            )) {
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
            runCatching {
                deleteTreeBounded(
                    dir,
                    MAX_BATCH_ROLLBACK_ENTRIES + MAX_WORKSPACE_OPS + 1,
                    cooperative = false
                )
            }
        }
    }

    private data class TreeStats(val bytes: Long, val entries: Int)

    private fun treeStats(dir: File): TreeStats {
        var bytes = 0L
        var entries = 0
        dir.walkTopDown().forEach { file ->
            RiftDeadline.check("batch rollback preflight")
            require(isInsideRoot(file)) { "Rollback snapshot escaped Rift MCP sandbox" }
            entries += 1
            require(entries <= MAX_BATCH_ROLLBACK_ENTRIES) { "Rollback snapshot has too many entries" }
            if (file.isFile) {
                bytes += file.length()
                require(bytes <= MAX_BATCH_ROLLBACK_BYTES) { "Rollback snapshot is too large" }
            }
        }
        return TreeStats(bytes, entries)
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun fileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                RiftDeadline.check("file hash")
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
            .put("build", JSONObject()
                .put("sourceSha", BuildConfig.RIFT_SOURCE_SHA)
                .put("runId", BuildConfig.RIFT_BUILD_RUN_ID)
                .put("runNumber", BuildConfig.RIFT_BUILD_RUN_NUMBER))
            .put("codeMode", JSONObject()
                .put("version", "rift-code-mode-v1")
                .put("projectIntelligence", "v2")
                .put("workspaceRoot", WORKSPACE_ROOT)
                .put("maxOperations", 1)
                .put("multiOperationBatch", "DISABLED")
                .put("maxResultBytes", MAX_WORKSPACE_RESULT_BYTES)
                .put("transactional", true)
                .put("dryRun", true)
                .put("symbolIndex", "persistent-incremental")
                .put("dependencyGraph", true)
                .put("projectViews", JSONArray(listOf("graph", "impact", "validation")))
                .put("ignoredDirectories", JSONArray(ignoredDirectoryNames.sorted())))
            .put("capabilities", JSONArray(listOf(
                "stat", "hash", "list", "readText", "writeText", "mkdir", "remove", "move", "copy", "archive", "extract", "workspaceExec",
                "snapshot", "search", "symbols", "references", "readSymbol", "replace", "patch", "patchRange", "applyHunks",
                "audit", "scan", "projectExport"
            )))
    }
}
