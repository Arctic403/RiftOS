package com.riftos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Local working-tree journal for Rift AI sessions.
 *
 * The journal lives outside the MCP-visible sandbox. Before a mutating MCP tool
 * runs, the original target is lazily captured. This enables a lightweight
 * review/revert workflow without cloning the whole project or exposing rollback
 * data to ChatGPT Web.
 */
class RiftAiJournal(context: Context) {
    companion object {
        private const val PREFS = "rift-ai"
        private const val PREF_ACTIVE_SESSION = "activeSession"
        private const val MAX_SNAPSHOT_BYTES = 64L * 1024L * 1024L
        private const val MAX_DIFF_FILE_BYTES = 2L * 1024L * 1024L
        private const val MAX_DIFF_LINES = 600
        private const val MAX_TREE_ENTRIES = 4000
        private const val MAX_PROJECT_CONTEXT_CHARS = 7000
        private const val MAX_EVENT_LINES = 2000
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val root = File(appContext.filesDir, "rift-ai").apply { mkdirs() }
    private val sessionsRoot = File(root, "sessions").apply { mkdirs() }
    private val riftFsRoot = File(appContext.filesDir, "riftfs").apply { mkdirs() }
    private val workspaceRoot = File(riftFsRoot, "workspace").apply { mkdirs() }

    init {
        recoverInterruptedSession()
    }

    @Synchronized
    fun beginSession(task: String): JSONObject {
        require(task.isNotBlank()) { "Rift AI task is empty" }
        activeSessionId()?.let { previousId ->
            if (transportActiveFor(previousId)) {
                throw IllegalStateException("The current Rift AI task is still running. Stop it before starting another task.")
            }
            if (changesForSession(previousId).length() > 0) {
                throw IllegalStateException("Review the current Rift AI changes before starting another task. Accept or revert them first.")
            }
        }

        val id = "ai-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        val dir = sessionDir(id).apply { mkdirs() }
        File(dir, "originals").mkdirs()
        writeJson(File(dir, "snapshots.json"), JSONArray())
        writeJson(
            File(dir, "meta.json"),
            JSONObject()
                .put("id", id)
                .put("task", task.take(20_000))
                .put("startedAt", System.currentTimeMillis())
                .put("updatedAt", System.currentTimeMillis())
                .put("nextSeq", 1L)
                .put("status", "starting")
                .put("transportActive", true)
                .put("reviewState", "pending")
        )
        File(dir, "events.jsonl").writeText("", Charsets.UTF_8)
        File(dir, "assistant.json").delete()
        prefs.edit().putString(PREF_ACTIVE_SESSION, id).apply()
        pruneOldSessions(id)
        recordEvent("session", "AI session started", JSONObject().put("phase", "starting"))
        return state()
    }

    @Synchronized
    fun state(): JSONObject {
        val id = activeSessionId()
        if (id == null) {
            return JSONObject()
                .put("active", false)
                .put("transportActive", false)
                .put("status", "idle")
                .put("localOnly", true)
                .put("transport", "ChatGPT Web")
                .put("scope", RiftToolHost.SCOPE)
        }
        val meta = sessionMeta(id) ?: JSONObject().put("id", id)
        return JSONObject()
            .put("active", true)
            .put("transportActive", meta.optBoolean("transportActive", false))
            .put("status", meta.optString("status", "review"))
            .put("reviewState", meta.optString("reviewState", "pending"))
            .put("localOnly", true)
            .put("transport", "ChatGPT Web")
            .put("scope", RiftToolHost.SCOPE)
            .put("session", meta)
            .put("latestAssistant", readObject(File(sessionDir(id), "assistant.json")) ?: JSONObject.NULL)
    }

    @Synchronized
    fun transportActive(): Boolean = activeSessionId()?.let(::transportActiveFor) == true

    @Synchronized
    fun abortSession(sessionId: String, reason: String): JSONObject {
        val id = activeSessionId()
        if (id != sessionId) return state()
        updateSessionState(id, "error", false)
        recordEvent("error", reason.take(1000), JSONObject().put("phase", "error"))
        return state()
    }

    @Synchronized
    fun compactProjectContext(path: String = "workspace"): String {
        val base = sandboxFile(path)
        val children = if (base.isDirectory) {
            base.listFiles()?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
        } else emptyList()
        val text = buildString {
            append("[RIFT_PROJECT_V2]\n")
            append("Project root: ").append(path).append('\n')
            append("The full project is locally reachable through rift_workspace_exec (Rift Code Mode). ")
            append("Prefer one batched workspace call over many small tool calls. Only return source text to ChatGPT when reasoning needs it.\n")
            append("Project Intelligence v1 operations: project, snapshot, stat, list, search, symbols, references, read/read_range, read_symbol, write, replace, patch, patch_range, apply_hunks, mkdir, remove, move, rename, copy. ")
            append("Use symbol/reference lookup and surgical reads/patches for large codebases. Batches execute locally, support dryRun/expectedSnapshot safety, and roll back all mutations if any operation fails.\n")
            append("Top-level entries")
            if (children.size > 120) append(" (first 120 of ").append(children.size).append(')')
            append(":\n")
            for (child in children.take(120)) {
                append(if (child.isDirectory) "d " else "f ")
                append(relativeToolPath(child))
                if (child.isFile) append("  ").append(child.length()).append(" B")
                append('\n')
                if (length >= MAX_PROJECT_CONTEXT_CHARS) break
            }
        }
        return text.take(MAX_PROJECT_CONTEXT_CHARS)
    }

    @Synchronized
    fun projectTree(path: String = "workspace"): JSONArray {
        val base = sandboxFile(path)
        val out = JSONArray()
        if (!base.exists()) return out
        var count = 0

        fun walk(node: File) {
            if (count >= MAX_TREE_ENTRIES) return
            node.listFiles()?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))?.forEach { child ->
                if (count >= MAX_TREE_ENTRIES) return@forEach
                count += 1
                out.put(
                    JSONObject()
                        .put("path", relativeToolPath(child))
                        .put("name", child.name)
                        .put("kind", if (child.isDirectory) "directory" else "file")
                        .put("size", if (child.isFile) child.length() else 0L)
                        .put("modified", child.lastModified())
                )
                if (child.isDirectory) walk(child)
            }
        }
        if (base.isDirectory) walk(base)
        return out
    }

    @Synchronized
    fun captureForTool(sessionId: String?, name: String, args: JSONObject) {
        val id = trackedSession(sessionId) ?: return
        when (name) {
            "rift_write_text", "rift_mkdir", "rift_remove" -> capturePath(id, args.optString("path"))
            "rift_move" -> {
                capturePath(id, args.optString("from"))
                capturePath(id, args.optString("to"))
            }
            "rift_copy" -> capturePath(id, args.optString("to"))
            "rift_workspace_exec" -> captureWorkspaceBatch(id, args)
        }
    }

    private fun captureWorkspaceBatch(id: String, args: JSONObject) {
        val operations = args.optJSONArray("operations") ?: return
        fun mutationPath(raw: String): String {
            val path = normalizePath(raw)
            require(path.startsWith("workspace/")) { "Rift Code Mode mutations must target a path inside workspace/" }
            return path
        }
        for (index in 0 until operations.length()) {
            val operation = operations.optJSONObject(index) ?: continue
            when (operation.optString("op").trim().lowercase()) {
                "write", "replace", "patch", "patch_range", "apply_hunks", "mkdir", "remove" -> capturePath(id, mutationPath(operation.optString("path")))
                "move", "rename" -> {
                    capturePath(id, mutationPath(operation.optString("from")))
                    capturePath(id, mutationPath(operation.optString("to")))
                }
                "copy" -> capturePath(id, mutationPath(operation.optString("to")))
            }
        }
    }

    private fun toolMutates(name: String, args: JSONObject): Boolean {
        if (name in setOf("rift_write_text", "rift_mkdir", "rift_remove", "rift_move", "rift_copy")) return true
        if (name != "rift_workspace_exec") return false
        val operations = args.optJSONArray("operations") ?: return false
        for (index in 0 until operations.length()) {
            val op = operations.optJSONObject(index)?.optString("op")?.trim()?.lowercase().orEmpty()
            if (op in setOf("write", "replace", "patch", "patch_range", "apply_hunks", "mkdir", "remove", "move", "rename", "copy")) return true
        }
        return false
    }

    @Synchronized
    fun recordTool(sessionId: String?, name: String, args: JSONObject, phase: String, ok: Boolean? = null, error: String? = null) {
        val id = trackedSession(sessionId) ?: return
        if (phase == "start") updateSessionState(id, "running", true)
        val data = JSONObject()
            .put("sessionId", id)
            .put("tool", name)
            .put("target", toolTarget(name, args))
            .put("phase", phase)
            .put("mutating", toolMutates(name, args))
        if (ok != null) data.put("ok", ok)
        if (!error.isNullOrBlank()) data.put("error", error.take(500))
        val message = when (phase) {
            "start" -> "$name · ${toolTarget(name, args)}"
            "finish" -> if (ok == true) "$name completed" else "$name failed"
            else -> name
        }
        recordEvent("tool", message, data)
    }

    @Synchronized
    fun ingestBrowserEvent(event: JSONObject) {
        val id = activeSessionId() ?: return
        val type = event.optString("type", "transport")
        val data = event.optJSONObject("data") ?: JSONObject()
        val eventSessionId = data.optString("sessionId").trim()
        if (eventSessionId.isNotBlank() && eventSessionId != id) return
        if (eventSessionId.isBlank() && !transportActiveFor(id)) return

        val phase = data.optString("phase").trim().lowercase()
        when {
            type == "error" || phase == "error" -> updateSessionState(id, "error", false)
            phase in setOf("opening", "waiting", "submitted", "running") -> updateSessionState(id, "running", true)
            phase == "complete" -> updateSessionState(id, "review", false)
            phase == "stopped" -> updateSessionState(id, "stopped", false)
        }

        if (type == "assistant") {
            if (sessionMeta(id)?.optString("status") == "starting") updateSessionState(id, "running", true)
            writeJson(
                File(sessionDir(id), "assistant.json"),
                JSONObject()
                    .put("at", System.currentTimeMillis())
                    .put("messageId", data.optString("messageId"))
                    .put("text", data.optString("text").take(200_000))
            )
            touchMeta(id)
            return
        }
        val message = event.optString("message").ifBlank { type }
        recordEvent(type, message, data)
    }

    @Synchronized
    fun events(afterSeq: Long = 0L): JSONObject {
        val id = activeSessionId() ?: return JSONObject().put("events", JSONArray()).put("lastSeq", afterSeq)
        val file = File(sessionDir(id), "events.jsonl")
        val out = JSONArray()
        var last = afterSeq
        if (file.isFile) {
            file.useLines { lines ->
                lines.forEach { line ->
                    val item = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                    val seq = item.optLong("seq", 0L)
                    if (seq > afterSeq) {
                        out.put(item)
                        last = maxOf(last, seq)
                    }
                }
            }
        }
        return JSONObject().put("events", out).put("lastSeq", last)
    }

    @Synchronized
    fun changes(): JSONArray {
        val id = activeSessionId() ?: return JSONArray()
        return changesForSession(id)
    }

    @Synchronized
    fun diff(path: String): JSONObject {
        val id = activeSessionId() ?: throw IllegalStateException("No active Rift AI session")
        val snapshot = findSnapshot(id, normalizePath(path)) ?: throw IllegalArgumentException("Path has no AI change snapshot: $path")
        val originalKind = snapshot.optString("kind")
        val current = sandboxFile(path)
        if (originalKind == "directory" || (current.exists() && current.isDirectory)) {
            return JSONObject()
                .put("path", normalizePath(path))
                .put("binary", false)
                .put("diff", "Directory change: ${normalizePath(path)}")
                .put("additions", 0)
                .put("deletions", 0)
        }
        val before = if (originalKind == "file") originalFile(id, snapshot).takeIf { it.isFile } else null
        val after = current.takeIf { it.isFile }
        if ((before?.length() ?: 0L) > MAX_DIFF_FILE_BYTES || (after?.length() ?: 0L) > MAX_DIFF_FILE_BYTES) {
            return JSONObject()
                .put("path", normalizePath(path))
                .put("binary", true)
                .put("diff", "Diff omitted: file exceeds ${MAX_DIFF_FILE_BYTES} bytes")
                .put("additions", 0)
                .put("deletions", 0)
        }
        val beforeBytes = before?.readBytes() ?: ByteArray(0)
        val afterBytes = after?.readBytes() ?: ByteArray(0)
        if (looksBinary(beforeBytes) || looksBinary(afterBytes)) {
            return JSONObject()
                .put("path", normalizePath(path))
                .put("binary", true)
                .put("diff", "Binary file changed")
                .put("additions", 0)
                .put("deletions", 0)
        }
        return buildTextDiff(normalizePath(path), beforeBytes.toString(Charsets.UTF_8), afterBytes.toString(Charsets.UTF_8))
    }

    @Synchronized
    fun acceptAll(): JSONObject {
        val id = activeSessionId() ?: return JSONObject().put("accepted", true).put("changes", 0)
        require(!transportActiveFor(id)) { "The Rift AI task is still running. Stop it or wait for completion before accepting changes." }
        val count = changesForSession(id).length()
        File(sessionDir(id), "originals").deleteRecursively()
        File(sessionDir(id), "originals").mkdirs()
        writeJson(File(sessionDir(id), "snapshots.json"), JSONArray())
        updateSessionState(id, "accepted", false, "accepted")
        recordEvent("review", "Accepted $count change${if (count == 1) "" else "s"}", JSONObject().put("changes", count).put("phase", "accepted"))
        return JSONObject().put("accepted", true).put("changes", count)
    }

    @Synchronized
    fun revertAll(): JSONObject {
        val id = activeSessionId() ?: return JSONObject().put("reverted", true).put("changes", 0)
        require(!transportActiveFor(id)) { "The Rift AI task is still running. Stop it or wait for completion before reverting changes." }
        val snapshots = snapshots(id)
        val list = mutableListOf<JSONObject>()
        for (index in 0 until snapshots.length()) snapshots.optJSONObject(index)?.let(list::add)
        val count = changesForSession(id).length()
        list.sortedBy { normalizePath(it.optString("path")).count { ch -> ch == '/' } }.forEach { snapshot ->
            restoreSnapshot(id, snapshot)
        }
        File(sessionDir(id), "originals").deleteRecursively()
        File(sessionDir(id), "originals").mkdirs()
        writeJson(File(sessionDir(id), "snapshots.json"), JSONArray())
        updateSessionState(id, "reverted", false, "reverted")
        recordEvent("review", "Reverted $count change${if (count == 1) "" else "s"}", JSONObject().put("changes", count).put("phase", "reverted"))
        return JSONObject().put("reverted", true).put("changes", count)
    }

    private fun capturePath(id: String, rawPath: String) {
        val path = normalizePath(rawPath)
        require(path.isNotBlank()) { "Rift AI cannot snapshot the sandbox root" }
        if (findSnapshot(id, path) != null || hasSnapshotAncestor(id, path)) return

        val source = sandboxFile(path)
        val snapshot = JSONObject()
            .put("path", path)
            .put("at", System.currentTimeMillis())
            .put("key", sha256(path))
        when {
            !source.exists() -> snapshot.put("kind", "missing")
            source.isFile -> {
                require(source.length() <= MAX_SNAPSHOT_BYTES) { "Rift AI rollback snapshot exceeds ${MAX_SNAPSHOT_BYTES} bytes: $path" }
                snapshot.put("kind", "file")
                val dest = originalFile(id, snapshot)
                dest.parentFile?.mkdirs()
                source.copyTo(dest, overwrite = true)
            }
            source.isDirectory -> {
                val bytes = treeBytes(source)
                require(bytes <= MAX_SNAPSHOT_BYTES) { "Rift AI rollback snapshot exceeds ${MAX_SNAPSHOT_BYTES} bytes: $path" }
                snapshot.put("kind", "directory")
                val dest = originalFile(id, snapshot)
                check(source.copyRecursively(dest, overwrite = true)) { "Could not create Rift AI rollback snapshot: $path" }
            }
            else -> snapshot.put("kind", "missing")
        }
        val snapshots = snapshots(id)
        snapshots.put(snapshot)
        writeJson(File(sessionDir(id), "snapshots.json"), snapshots)
        touchMeta(id)
    }

    private fun restoreSnapshot(id: String, snapshot: JSONObject) {
        val path = snapshot.optString("path")
        val target = sandboxFile(path)
        if (target.exists()) {
            val removed = if (target.isDirectory) target.deleteRecursively() else target.delete()
            require(removed) { "Could not clear current path during Rift AI revert: $path" }
        }
        when (snapshot.optString("kind")) {
            "missing" -> Unit
            "file" -> {
                target.parentFile?.mkdirs()
                originalFile(id, snapshot).copyTo(target, overwrite = true)
            }
            "directory" -> {
                target.parentFile?.mkdirs()
                check(originalFile(id, snapshot).copyRecursively(target, overwrite = true)) { "Could not restore Rift AI directory snapshot: $path" }
            }
        }
    }

    private fun changeForSnapshot(id: String, snapshot: JSONObject): JSONObject? {
        val path = snapshot.optString("path")
        val originalKind = snapshot.optString("kind")
        val current = sandboxFile(path)
        val currentKind = when {
            !current.exists() -> "missing"
            current.isDirectory -> "directory"
            else -> "file"
        }
        val changed = when {
            originalKind != currentKind -> true
            originalKind == "missing" -> false
            originalKind == "file" -> !sameFile(originalFile(id, snapshot), current)
            originalKind == "directory" -> treeFingerprint(originalFile(id, snapshot)) != treeFingerprint(current)
            else -> false
        }
        if (!changed) return null
        val status = when {
            originalKind == "missing" && currentKind != "missing" -> "added"
            originalKind != "missing" && currentKind == "missing" -> "deleted"
            originalKind != currentKind -> "type-changed"
            else -> "modified"
        }
        val result = JSONObject().put("path", path).put("status", status).put("kind", currentKind)
            .put("additions", 0).put("deletions", 0).put("binary", false)
        if (originalKind == "missing" && current.isFile && current.length() <= MAX_DIFF_FILE_BYTES) {
            val bytes = current.readBytes()
            if (!looksBinary(bytes)) result.put("additions", splitLines(bytes.toString(Charsets.UTF_8)).size)
            else result.put("binary", true)
        } else if (currentKind == "missing" && originalKind == "file") {
            val original = originalFile(id, snapshot)
            if (original.length() <= MAX_DIFF_FILE_BYTES) {
                val bytes = original.readBytes()
                if (!looksBinary(bytes)) result.put("deletions", splitLines(bytes.toString(Charsets.UTF_8)).size)
                else result.put("binary", true)
            }
        } else if (originalKind == "file" && currentKind == "file") {
            val original = originalFile(id, snapshot)
            if (original.length() <= MAX_DIFF_FILE_BYTES && current.length() <= MAX_DIFF_FILE_BYTES) {
                val before = original.readBytes()
                val after = current.readBytes()
                if (!looksBinary(before) && !looksBinary(after)) {
                    val stats = buildTextDiff(path, before.toString(Charsets.UTF_8), after.toString(Charsets.UTF_8))
                    result.put("additions", stats.optInt("additions", 0))
                    result.put("deletions", stats.optInt("deletions", 0))
                } else result.put("binary", true)
            } else result.put("binary", true)
        }
        return result
    }

    private fun buildTextDiff(path: String, before: String, after: String): JSONObject {
        val a = splitLines(before)
        val b = splitLines(after)
        var additions = 0
        var deletions = 0
        val lines = mutableListOf<String>()
        lines += "--- a/$path"
        lines += "+++ b/$path"

        if (a.size > MAX_DIFF_LINES || b.size > MAX_DIFF_LINES) {
            deletions = a.size
            additions = b.size
            lines += "@@ full-file diff (large text file) @@"
            a.take(MAX_DIFF_LINES).forEach { lines += "-$it" }
            if (a.size > MAX_DIFF_LINES) lines += "-… ${a.size - MAX_DIFF_LINES} more lines omitted"
            b.take(MAX_DIFF_LINES).forEach { lines += "+$it" }
            if (b.size > MAX_DIFF_LINES) lines += "+… ${b.size - MAX_DIFF_LINES} more lines omitted"
        } else {
            val dp = Array(a.size + 1) { IntArray(b.size + 1) }
            for (i in a.size - 1 downTo 0) {
                for (j in b.size - 1 downTo 0) {
                    dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
            lines += "@@ -1,${a.size} +1,${b.size} @@"
            var i = 0
            var j = 0
            while (i < a.size || j < b.size) {
                when {
                    i < a.size && j < b.size && a[i] == b[j] -> { lines += " ${a[i]}"; i++; j++ }
                    j < b.size && (i == a.size || dp[i][j + 1] >= dp[i + 1][j]) -> { lines += "+${b[j]}"; additions++; j++ }
                    i < a.size -> { lines += "-${a[i]}"; deletions++; i++ }
                }
            }
        }
        return JSONObject()
            .put("path", path)
            .put("binary", false)
            .put("diff", lines.joinToString("\n"))
            .put("additions", additions)
            .put("deletions", deletions)
    }

    private fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        return text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    }

    private fun looksBinary(bytes: ByteArray): Boolean = bytes.take(4096).any { it.toInt() == 0 }

    private fun sameFile(a: File, b: File): Boolean {
        if (!a.isFile || !b.isFile || a.length() != b.length()) return false
        return sha256File(a) == sha256File(b)
    }

    private fun treeFingerprint(dir: File): String {
        if (!dir.isDirectory) return "missing"
        val digest = MessageDigest.getInstance("SHA-256")
        fun walk(node: File, prefix: String) {
            node.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { child ->
                val path = if (prefix.isBlank()) child.name else "$prefix/${child.name}"
                digest.update(path.toByteArray(Charsets.UTF_8))
                digest.update((if (child.isDirectory) 1 else 2).toByte())
                if (child.isDirectory) walk(child, path) else child.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        digest.update(buffer, 0, read)
                    }
                }
            }
        }
        walk(dir, "")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun treeBytes(dir: File): Long {
        var total = 0L
        dir.walkTopDown().forEach { file ->
            if (file.isFile) {
                total += file.length()
                require(total <= MAX_SNAPSHOT_BYTES) { "Rift AI rollback snapshot is too large" }
            }
        }
        return total
    }

    private fun recordEvent(type: String, message: String, data: JSONObject = JSONObject()) {
        val id = activeSessionId() ?: return
        val dir = sessionDir(id)
        val metaFile = File(dir, "meta.json")
        val meta = readObject(metaFile) ?: JSONObject().put("id", id).put("nextSeq", 1L)
        val seq = meta.optLong("nextSeq", 1L)
        val eventData = runCatching { JSONObject(data.toString()) }.getOrElse { JSONObject() }.put("sessionId", id)
        val event = JSONObject()
            .put("seq", seq)
            .put("at", System.currentTimeMillis())
            .put("type", type.take(80))
            .put("message", message.take(1000))
            .put("data", eventData)
        val eventsFile = File(dir, "events.jsonl")
        eventsFile.appendText(event.toString() + "\n", Charsets.UTF_8)
        trimEvents(eventsFile)
        meta.put("nextSeq", seq + 1L).put("updatedAt", System.currentTimeMillis())
        writeJson(metaFile, meta)
    }

    private fun trimEvents(file: File) {
        val lines = file.readLines(Charsets.UTF_8)
        if (lines.size <= MAX_EVENT_LINES) return
        file.writeText(lines.takeLast(MAX_EVENT_LINES).joinToString("\n", postfix = "\n"), Charsets.UTF_8)
    }

    private fun touchMeta(id: String) {
        val file = File(sessionDir(id), "meta.json")
        val meta = readObject(file) ?: return
        meta.put("updatedAt", System.currentTimeMillis())
        writeJson(file, meta)
    }

    private fun pruneOldSessions(activeId: String) {
        sessionsRoot.listFiles()?.filter { it.isDirectory && it.name != activeId }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(7)
            ?.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun recoverInterruptedSession() {
        val id = activeSessionId() ?: return
        if (!transportActiveFor(id)) return
        updateSessionState(id, "interrupted", false)
        recordEvent(
            "transport",
            "Previous ChatGPT Web task was interrupted when RiftOS stopped",
            JSONObject().put("phase", "interrupted")
        )
    }

    private fun changesForSession(id: String): JSONArray {
        val items = snapshots(id)
        val out = JSONArray()
        for (index in 0 until items.length()) {
            val snapshot = items.optJSONObject(index) ?: continue
            changeForSnapshot(id, snapshot)?.let(out::put)
        }
        return out
    }

    private fun sessionMeta(id: String): JSONObject? = readObject(File(sessionDir(id), "meta.json"))

    private fun transportActiveFor(id: String): Boolean = sessionMeta(id)?.optBoolean("transportActive", false) == true

    private fun trackedSession(sessionId: String?): String? {
        val requested = sessionId?.trim().orEmpty()
        if (requested.isBlank()) return null
        val active = activeSessionId() ?: return null
        if (requested != active || !transportActiveFor(active)) return null
        return active
    }

    private fun updateSessionState(id: String, status: String, transportActive: Boolean, reviewState: String? = null) {
        val file = File(sessionDir(id), "meta.json")
        val meta = readObject(file) ?: JSONObject().put("id", id).put("nextSeq", 1L)
        meta.put("status", status)
            .put("transportActive", transportActive)
            .put("updatedAt", System.currentTimeMillis())
        if (reviewState != null) meta.put("reviewState", reviewState)
        writeJson(file, meta)
    }

    private fun hasSnapshotAncestor(id: String, path: String): Boolean {
        val items = snapshots(id)
        for (index in 0 until items.length()) {
            val candidate = normalizePath(items.optJSONObject(index)?.optString("path").orEmpty())
            if (candidate.isNotBlank() && path.startsWith("$candidate/")) return true
        }
        return false
    }

    private fun activeSessionId(): String? = prefs.getString(PREF_ACTIVE_SESSION, null)?.takeIf { it.isNotBlank() && sessionDir(it).isDirectory }
    private fun sessionDir(id: String): File = File(sessionsRoot, id.replace(Regex("[^A-Za-z0-9._-]"), "_"))
    private fun snapshots(id: String): JSONArray = readArray(File(sessionDir(id), "snapshots.json")) ?: JSONArray()

    private fun findSnapshot(id: String, path: String): JSONObject? {
        val items = snapshots(id)
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            if (item.optString("path") == path) return item
        }
        return null
    }

    private fun originalFile(id: String, snapshot: JSONObject): File = File(File(sessionDir(id), "originals"), snapshot.optString("key"))

    private fun sandboxFile(rawPath: String): File {
        val path = normalizePath(rawPath)
        val segments = if (path.isBlank()) emptyList() else path.split('/')
        require(segments.isEmpty() || segments.first() == "workspace") { "Rift AI is scoped to workspace/ only" }
        var file = workspaceRoot
        segments.drop(if (segments.firstOrNull() == "workspace") 1 else 0).forEach { file = File(file, it) }
        val allowedRoot = workspaceRoot.canonicalFile
        val target = file.canonicalFile
        require(target == allowedRoot || target.path.startsWith(allowedRoot.path + File.separator)) { "Path escaped Rift AI workspace" }
        return target
    }

    private fun normalizePath(raw: String): String {
        val parts = raw.replace('\\', '/').trim('/').split('/').filter { it.isNotBlank() }
        parts.forEach { require(it != "." && it != ".." && !it.contains('\u0000')) { "Invalid path segment" } }
        return parts.joinToString("/")
    }

    private fun relativeToolPath(file: File): String {
        val target = file.canonicalFile
        val workspace = workspaceRoot.canonicalFile
        require(target == workspace || target.path.startsWith(workspace.path + File.separator)) { "Path escaped Rift AI workspace" }
        if (target == workspace) return "workspace"
        val suffix = workspace.toPath().relativize(target.toPath()).toString().replace(File.separatorChar, '/')
        return "workspace/$suffix"
    }

    private fun toolTarget(name: String, args: JSONObject): String = when (name) {
        "rift_move", "rift_copy" -> "${args.optString("from")} → ${args.optString("to")}".take(500)
        "rift_workspace_exec" -> "workspace batch · ${args.optJSONArray("operations")?.length() ?: 0} ops"
        "rift_info" -> "sandbox"
        else -> args.optString("path").take(500)
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun writeJson(file: File, value: Any) {
        file.parentFile?.mkdirs()
        file.writeText(value.toString(), Charsets.UTF_8)
    }

    private fun readObject(file: File): JSONObject? = if (file.isFile) runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull() else null
    private fun readArray(file: File): JSONArray? = if (file.isFile) runCatching { JSONArray(file.readText(Charsets.UTF_8)) }.getOrNull() else null
}
