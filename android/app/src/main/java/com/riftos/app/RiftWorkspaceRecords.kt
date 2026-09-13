package com.riftos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Persistent, workspace-wide change recorder.
 *
 * The recorder lives outside riftfs/workspace so project tools cannot rewrite their own audit
 * history. It keeps two bounded text snapshots:
 *  - observed: last state seen by the watcher, used to produce per-event diffs;
 *  - checkpoint: explicit/local baseline used by Workspace Records + rift_workspace_diff.
 *
 * Binary/oversized files are tracked by metadata/hash only. The recorder never mutates workspace
 * content and does not implement accept/deny/rollback semantics.
 */
class RiftWorkspaceRecords private constructor(context: Context) {
    companion object {
        private const val FORMAT = "rift-workspace-records-v1"
        private const val MAX_TEXT_BYTES = 1024L * 1024L
        private const val MAX_DIFF_CHARS = 64_000
        private const val MAX_DIFF_LINES = 420
        private const val MAX_RECORDS = 2_000
        private const val MAX_QUERY_RECORDS = 250
        // Tool results are duplicated in MCP text and structured content, then JSON-escaped
        // again by the relay. Keep the raw query far below its 1 MB WebSocket envelope.
        private const val MAX_QUERY_PAYLOAD_CHARS = 96_000
        private const val EVENT_SETTLE_MS = 220L
        @Volatile private var instance: RiftWorkspaceRecords? = null

        fun get(context: Context): RiftWorkspaceRecords =
            instance ?: synchronized(this) {
                instance ?: RiftWorkspaceRecords(context.applicationContext).also { instance = it }
            }
    }

    private data class Entry(
        val kind: String,
        val size: Long,
        val modified: Long,
        val sha256: String,
        val textStored: Boolean
    )

    private data class Snapshot(val entry: Entry, val text: String?)

    private val appContext = context.applicationContext
    private val workspaceRoot = File(appContext.filesDir, "riftfs/workspace").apply { mkdirs() }.canonicalFile
    private val recordsRoot = File(appContext.filesDir, "rift-workspace-records").apply { mkdirs() }
    private val eventRoot = File(recordsRoot, "events").apply { mkdirs() }
    private val observedRoot = File(recordsRoot, "observed").apply { mkdirs() }
    private val checkpointRoot = File(recordsRoot, "checkpoint").apply { mkdirs() }
    private val stateFile = File(recordsRoot, "state.json")
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val pending = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val sequence = AtomicLong(0L)
    private val observed = LinkedHashMap<String, Entry>()
    private val checkpoint = LinkedHashMap<String, Entry>()
    @Volatile private var initialized = false
    @Volatile private var lastReconcileAt = 0L
    private var checkpointAt = 0L
    private var checkpointReason = "initial"
    private var checkpointGitRoot: String? = null
    private var checkpointGitHeadSha: String? = null

    fun start() {
        if (initialized) return
        executor.execute { ensureInitialized() }
    }

    fun observe(type: String, file: File, directory: Boolean) {
        val relative = relativePath(file) ?: return
        start()
        if (directory || type in setOf("move-from", "move-to", "delete-self", "move-self")) {
            schedule("__tree__", 420L) { reconcileAll("watch:$type") }
            return
        }
        schedule(relative, EVENT_SETTLE_MS) { capturePath(relative, "watch:$type") }
    }

    fun query(args: JSONObject = JSONObject()): JSONObject =
        executor.submit<JSONObject> {
            ensureInitialized()
            queryInternal(args)
        }.get(30, TimeUnit.SECONDS)

    fun checkpoint(args: JSONObject = JSONObject()): JSONObject =
        executor.submit<JSONObject> {
            ensureInitialized()
            createCheckpoint(
                reason = args.optString("reason", "manual").take(80).ifBlank { "manual" },
                gitRoot = args.optString("gitRoot").takeIf { it.isNotBlank() },
                gitHeadSha = args.optString("gitHeadSha").takeIf { it.isNotBlank() }
            )
        }.get(30, TimeUnit.SECONDS)

    private fun schedule(key: String, delayMs: Long, block: () -> Unit) {
        pending.remove(key)?.cancel(false)
        val future = executor.schedule({
            try { block() } finally { pending.remove(key) }
        }, delayMs, TimeUnit.MILLISECONDS)
        pending[key] = future
    }

    private fun ensureInitialized() {
        if (initialized) return
        loadState()
        if (observed.isEmpty() && checkpoint.isEmpty()) {
            seedInitialState()
        } else {
            reconcileAll("startup")
        }
        initialized = true
    }

    private fun seedInitialState() {
        observed.clear()
        checkpoint.clear()
        observedRoot.deleteRecursively(); observedRoot.mkdirs()
        checkpointRoot.deleteRecursively(); checkpointRoot.mkdirs()
        val current = scanCurrentFiles()
        for ((path, file) in current) {
            val snapshot = snapshot(file)
            observed[path] = snapshot.entry
            checkpoint[path] = snapshot.entry
            writeSnapshot(observedRoot, path, snapshot.text)
            writeSnapshot(checkpointRoot, path, snapshot.text)
        }
        checkpointAt = System.currentTimeMillis()
        checkpointReason = "initial"
        saveState()
    }

    private fun reconcileAll(source: String) {
        val current = scanCurrentFiles()
        val currentPaths = current.keys.toSet()
        var metadataDirty = false
        for ((path, file) in current) {
            val previous = observed[path]
            // Watch events capture ordinary edits. A periodic reconciliation should not
            // rehash every byte of an unchanged multi-gigabyte workspace on each query.
            if (previous != null && previous.kind == "file" &&
                previous.size == file.length() && previous.modified == file.lastModified()) continue
            val next = snapshot(file)
            if (previous == null || previous.sha256 != next.entry.sha256 || previous.kind != next.entry.kind) {
                recordChange(path, previous, next, source)
            } else if (previous.modified != next.entry.modified || previous.size != next.entry.size) {
                observed[path] = next.entry
                metadataDirty = true
            }
        }
        for (path in observed.keys.filter { it !in currentPaths }.toList()) {
            val previous = observed[path]
            recordChange(path, previous, null, source)
        }
        if (metadataDirty) saveState()
        lastReconcileAt = System.currentTimeMillis()
    }

    private fun capturePath(path: String, source: String) {
        val file = workspaceFile(path)
        val next = if (file.exists() && file.isFile) snapshot(file) else null
        val previous = observed[path]
        if (previous == null && next == null) return
        if (previous != null && next != null && previous.sha256 == next.entry.sha256 && previous.kind == next.entry.kind) {
            if (previous.modified != next.entry.modified || previous.size != next.entry.size) {
                observed[path] = next.entry
                saveState()
            }
            return
        }
        recordChange(path, previous, next, source)
    }

    private fun recordChange(path: String, before: Entry?, after: Snapshot?, source: String) {
        val beforeText = before?.takeIf { it.textStored }?.let { readSnapshot(observedRoot, path) }
        val afterText = after?.text
        val action = when {
            before == null && after != null -> "created"
            before != null && after == null -> "deleted"
            before?.kind != after?.entry?.kind -> "type-changed"
            else -> "modified"
        }
        val at = System.currentTimeMillis()
        val seq = sequence.incrementAndGet()
        val record = JSONObject()
            .put("format", FORMAT)
            .put("id", "rec-$seq-$at")
            .put("sequence", seq)
            .put("at", at)
            .put("path", path)
            .put("action", action)
            .put("source", source)
            .put("before", entryJson(before))
            .put("after", entryJson(after?.entry))
            .put("textDiffAvailable", beforeText != null || afterText != null)
            .put("diff", buildDiff(path, beforeText, afterText, before, after?.entry))
        val file = File(eventRoot, "%012d-%013d.json".format(seq, at))
        writeJsonAtomic(file, record)

        if (after == null) {
            observed.remove(path)
            snapshotFile(observedRoot, path).delete()
            pruneEmptyParents(snapshotFile(observedRoot, path).parentFile, observedRoot)
        } else {
            observed[path] = after.entry
            writeSnapshot(observedRoot, path, after.text)
        }
        pruneRecords()
        saveState()
    }

    private fun createCheckpoint(reason: String, gitRoot: String?, gitHeadSha: String?): JSONObject {
        reconcileAll("checkpoint")
        checkpoint.clear()
        checkpointRoot.deleteRecursively(); checkpointRoot.mkdirs()
        for ((path, entry) in observed) {
            checkpoint[path] = entry
            if (entry.textStored) {
                val text = readSnapshot(observedRoot, path)
                writeSnapshot(checkpointRoot, path, text)
            }
        }
        checkpointAt = System.currentTimeMillis()
        checkpointReason = reason
        checkpointGitRoot = gitRoot
        checkpointGitHeadSha = gitHeadSha
        saveState()
        return checkpointSummary()
    }

    private fun queryInternal(args: JSONObject): JSONObject {
        if (System.currentTimeMillis() - lastReconcileAt > 30_000L) reconcileAll("query")
        val prefix = normalizePrefix(args.optString("path"))
        val includeDiff = !args.has("includeDiff") || args.optBoolean("includeDiff", true)
        val limit = args.optInt("limit", 120).coerceIn(1, MAX_QUERY_RECORDS)
        val allPaths = (checkpoint.keys + observed.keys).toSortedSet()
        val changed = JSONArray()
        var changedCount = 0
        var payloadChars = 0
        var omittedFiles = 0
        for (path in allPaths) {
            if (!matchesPrefix(path, prefix)) continue
            val before = checkpoint[path]
            val after = observed[path]
            if (sameEntry(before, after)) continue
            changedCount++
            if (changed.length() >= 500 || payloadChars >= MAX_QUERY_PAYLOAD_CHARS) {
                omittedFiles++
                continue
            }
            val beforeText = before?.takeIf { it.textStored }?.let { readSnapshot(checkpointRoot, path) }
            val afterText = after?.takeIf { it.textStored }?.let { readSnapshot(observedRoot, path) }
            val row = JSONObject()
                .put("path", path)
                .put("status", status(before, after))
                .put("before", entryJson(before))
                .put("after", entryJson(after))
            if (includeDiff && payloadChars < MAX_QUERY_PAYLOAD_CHARS - 512) {
                row.put("diff", buildDiff(path, beforeText, afterText, before, after))
            }
            if (payloadChars + row.toString().length > MAX_QUERY_PAYLOAD_CHARS && row.has("diff")) {
                row.remove("diff")
                row.put("diffOmitted", true)
            }
            val size = row.toString().length
            if (payloadChars + size <= MAX_QUERY_PAYLOAD_CHARS) {
                changed.put(row)
                payloadChars += size
            } else omittedFiles++
        }

        val records = JSONArray()
        val files = eventRoot.listFiles()?.filter { it.isFile && it.extension == "json" }?.sortedByDescending { it.name }.orEmpty()
        var matchingRecords = 0
        var omittedRecords = 0
        for (file in files) {
            val row = runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull() ?: continue
            if (!matchesPrefix(row.optString("path"), prefix)) continue
            matchingRecords++
            if (records.length() >= limit || payloadChars >= MAX_QUERY_PAYLOAD_CHARS) {
                omittedRecords++
                continue
            }
            if (!includeDiff) row.remove("diff")
            if (payloadChars + row.toString().length > MAX_QUERY_PAYLOAD_CHARS && row.has("diff")) {
                row.remove("diff")
                row.put("diffOmitted", true)
            }
            val size = row.toString().length
            if (payloadChars + size <= MAX_QUERY_PAYLOAD_CHARS) {
                records.put(row)
                payloadChars += size
            } else omittedRecords++
        }

        return JSONObject()
            .put("format", FORMAT)
            .put("scope", "riftfs/workspace")
            .put("checkpoint", checkpointSummary())
            .put("summary", JSONObject()
                .put("changedFiles", changedCount)
                .put("records", matchingRecords)
                .put("returnedRecords", records.length())
                .put("returnedFiles", changed.length())
                .put("omittedFiles", omittedFiles)
                .put("omittedRecords", omittedRecords)
                .put("responseTruncated", omittedFiles > 0 || omittedRecords > 0)
                .put("recordLimit", limit))
            .put("files", changed)
            .put("records", records)
    }

    private fun checkpointSummary(): JSONObject = JSONObject()
        .put("at", checkpointAt)
        .put("reason", checkpointReason)
        .put("gitRoot", checkpointGitRoot ?: JSONObject.NULL)
        .put("gitHeadSha", checkpointGitHeadSha ?: JSONObject.NULL)
        .put("files", checkpoint.size)

    private fun scanCurrentFiles(): LinkedHashMap<String, File> {
        val out = LinkedHashMap<String, File>()
        fun walk(directory: File) {
            val children = directory.listFiles()?.sortedBy { it.name.lowercase() }.orEmpty()
            for (child in children) {
                val canonical = runCatching { child.canonicalFile }.getOrNull() ?: continue
                if (!insideWorkspace(canonical)) continue
                if (canonical.isDirectory) walk(canonical)
                else if (canonical.isFile) relativePath(canonical)?.let { out[it] = canonical }
            }
        }
        walk(workspaceRoot)
        return out
    }

    private fun snapshot(file: File): Snapshot {
        val size = file.length()
        val sha = digestFile(file, "SHA-256")
        var text: String? = null
        if (size <= MAX_TEXT_BYTES) {
            val bytes = runCatching { file.readBytes() }.getOrNull()
            if (bytes != null && looksText(file.name, bytes)) text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull()
        }
        return Snapshot(
            Entry("file", size, file.lastModified(), sha, text != null),
            text
        )
    }

    private fun looksText(name: String, bytes: ByteArray): Boolean {
        val binaryExt = setOf("png","jpg","jpeg","gif","webp","ico","pdf","zip","gz","tgz","7z","rar","apk","aab","jar","aar","dex","so","dll","exe","bin","class","wasm","woff","woff2","ttf","otf","mp3","wav","ogg","mp4","mov","avi","sqlite","db")
        if (name.substringAfterLast('.', "").lowercase() in binaryExt) return false
        val sample = bytes.take(4096)
        return sample.none { it == 0.toByte() }
    }

    private fun buildDiff(path: String, before: String?, after: String?, beforeEntry: Entry?, afterEntry: Entry?): String {
        if (before == null && after == null) {
            val left = beforeEntry?.let { "${it.size} bytes ${it.sha256.take(12)}" } ?: "missing"
            val right = afterEntry?.let { "${it.size} bytes ${it.sha256.take(12)}" } ?: "missing"
            return "diff --rift a/$path b/$path\nBinary/oversized change: $left -> $right"
        }
        val a = (before ?: "").split('\n')
        val b = (after ?: "").split('\n')
        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix && a[a.size - 1 - suffix] == b[b.size - 1 - suffix]) suffix++
        val removed = a.subList(prefix, a.size - suffix)
        val added = b.subList(prefix, b.size - suffix)
        val contextBefore = a.subList((prefix - 3).coerceAtLeast(0), prefix)
        val contextAfterStart = b.size - suffix
        val contextAfter = b.subList(contextAfterStart, (contextAfterStart + 3).coerceAtMost(b.size))
        val lines = ArrayList<String>()
        lines += "diff --rift a/$path b/$path"
        lines += if (beforeEntry == null) "--- /dev/null" else "--- a/$path"
        lines += if (afterEntry == null) "+++ /dev/null" else "+++ b/$path"
        lines += "@@ -${prefix + 1},${removed.size} +${prefix + 1},${added.size} @@"
        contextBefore.forEach { lines += " $it" }
        removed.take(MAX_DIFF_LINES / 2).forEach { lines += "-$it" }
        added.take(MAX_DIFF_LINES / 2).forEach { lines += "+$it" }
        contextAfter.forEach { lines += " $it" }
        if (removed.size + added.size > MAX_DIFF_LINES) lines += "... diff truncated (${removed.size} removed / ${added.size} added lines)"
        val rendered = lines.joinToString("\n")
        return if (rendered.length <= MAX_DIFF_CHARS) rendered else rendered.take(MAX_DIFF_CHARS) + "\n... diff truncated"
    }

    private fun status(before: Entry?, after: Entry?): String = when {
        before == null && after != null -> "added"
        before != null && after == null -> "deleted"
        before?.kind != after?.kind -> "type-changed"
        else -> "modified"
    }

    private fun sameEntry(a: Entry?, b: Entry?): Boolean = when {
        a == null && b == null -> true
        a == null || b == null -> false
        else -> a.kind == b.kind && a.sha256 == b.sha256
    }

    private fun entryJson(entry: Entry?): Any = if (entry == null) JSONObject.NULL else JSONObject()
        .put("kind", entry.kind)
        .put("size", entry.size)
        .put("modified", entry.modified)
        .put("sha256", entry.sha256)
        .put("textStored", entry.textStored)

    private fun writeSnapshot(root: File, path: String, text: String?) {
        val target = snapshotFile(root, path)
        if (text == null) {
            if (target.exists()) target.delete()
            pruneEmptyParents(target.parentFile, root)
            return
        }
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (target.exists()) target.delete()
        check(tmp.renameTo(target)) { "Could not update workspace record snapshot: $path" }
    }

    private fun readSnapshot(root: File, path: String): String? =
        runCatching { snapshotFile(root, path).takeIf { it.isFile }?.readText(Charsets.UTF_8) }.getOrNull()

    private fun snapshotFile(root: File, path: String): File {
        val candidate = File(root, path).canonicalFile
        val canonicalRoot = root.canonicalFile
        require(candidate == canonicalRoot || candidate.path.startsWith(canonicalRoot.path + File.separator)) { "Invalid record path" }
        return candidate
    }

    private fun pruneEmptyParents(start: File?, root: File) {
        val canonicalRoot = root.canonicalFile
        var current = start
        while (current != null && current.canonicalFile != canonicalRoot) {
            if (!current.isDirectory || !current.listFiles().isNullOrEmpty()) break
            if (!current.delete()) break
            current = current.parentFile
        }
    }

    private fun pruneRecords() {
        val files = eventRoot.listFiles()?.filter { it.isFile && it.extension == "json" }?.sortedByDescending { it.name }.orEmpty()
        files.drop(MAX_RECORDS).forEach { runCatching { it.delete() } }
    }

    private fun loadState() {
        if (!stateFile.isFile) return
        val state = runCatching { JSONObject(stateFile.readText(Charsets.UTF_8)) }.getOrNull() ?: return
        sequence.set(state.optLong("sequence", 0L))
        checkpointAt = state.optLong("checkpointAt", 0L)
        checkpointReason = state.optString("checkpointReason", "initial")
        checkpointGitRoot = if (state.isNull("checkpointGitRoot")) null else state.optString("checkpointGitRoot").takeIf { it.isNotBlank() }
        checkpointGitHeadSha = if (state.isNull("checkpointGitHeadSha")) null else state.optString("checkpointGitHeadSha").takeIf { it.isNotBlank() }
        readEntryMap(state.optJSONObject("observed"), observed)
        readEntryMap(state.optJSONObject("checkpoint"), checkpoint)
    }

    private fun saveState() {
        val state = JSONObject()
            .put("format", FORMAT)
            .put("sequence", sequence.get())
            .put("checkpointAt", checkpointAt)
            .put("checkpointReason", checkpointReason)
            .put("checkpointGitRoot", checkpointGitRoot ?: JSONObject.NULL)
            .put("checkpointGitHeadSha", checkpointGitHeadSha ?: JSONObject.NULL)
            .put("observed", entryMapJson(observed))
            .put("checkpoint", entryMapJson(checkpoint))
        writeJsonAtomic(stateFile, state)
    }

    private fun readEntryMap(source: JSONObject?, target: LinkedHashMap<String, Entry>) {
        target.clear()
        if (source == null) return
        val keys = source.keys().asSequence().toList().sorted()
        for (path in keys) {
            val row = source.optJSONObject(path) ?: continue
            target[path] = Entry(
                row.optString("kind", "file"),
                row.optLong("size", 0L),
                row.optLong("modified", 0L),
                row.optString("sha256"),
                row.optBoolean("textStored", false)
            )
        }
    }

    private fun entryMapJson(source: Map<String, Entry>): JSONObject {
        val out = JSONObject()
        for ((path, entry) in source) out.put(path, entryJson(entry))
        return out
    }

    private fun writeJsonAtomic(file: File, json: JSONObject) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.${System.nanoTime()}.tmp")
        tmp.writeText(json.toString(), Charsets.UTF_8)
        if (file.exists()) file.delete()
        check(tmp.renameTo(file)) { "Could not persist workspace records" }
    }

    private fun digestFile(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun relativePath(file: File): String? {
        val target = runCatching { file.canonicalFile }.getOrNull() ?: return null
        if (!insideWorkspace(target)) return null
        if (target == workspaceRoot) return ""
        return workspaceRoot.toPath().relativize(target.toPath()).toString().replace(File.separatorChar, '/')
    }

    private fun insideWorkspace(file: File): Boolean {
        val target = runCatching { file.canonicalFile }.getOrNull() ?: return false
        return target == workspaceRoot || target.path.startsWith(workspaceRoot.path + File.separator)
    }

    private fun workspaceFile(path: String): File {
        val clean = path.replace('\\', '/').trim('/').split('/').filter { it.isNotBlank() && it != "." }
        require(clean.none { it == ".." }) { "Invalid workspace path" }
        val file = clean.fold(workspaceRoot) { parent, part -> File(parent, part) }.canonicalFile
        require(insideWorkspace(file)) { "Workspace record path escaped workspace" }
        return file
    }

    private fun normalizePrefix(path: String): String = path.replace('\\', '/').trim('/').also {
        require(it.split('/').none { segment -> segment == ".." }) { "Invalid workspace record prefix" }
    }

    private fun matchesPrefix(path: String, prefix: String): Boolean =
        prefix.isBlank() || path == prefix || path.startsWith("$prefix/")
}
