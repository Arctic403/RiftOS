package com.riftos.app

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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
        private const val MAX_RECORDS = 256
        private const val MAX_QUERY_RECORDS = 250
        // Tool results are duplicated in MCP text and structured content, then JSON-escaped
        // again by the relay. Keep the raw query far below its 1 MB WebSocket envelope.
        private const val MAX_QUERY_PAYLOAD_CHARS = 96_000
        private const val MAX_SEMANTIC_SEED_CHANGES = 4_096
        private const val MAX_SEMANTIC_SEED_SOURCE_FILES = 1_024
        private const val MAX_SEMANTIC_SEED_TEXT_BYTES = 8L * 1024L * 1024L
        private const val MAX_SEMANTIC_SEED_OWNERSHIP_PROJECTS = 64
        private const val MAX_CANDIDATE_SESSION_EVIDENCE = 512
        private const val MAX_PENDING_WATCH_EVENTS = 512
        private const val EVENT_SETTLE_MS = 220L
        private const val RECORD_OPERATION_TIMEOUT_MS = 60_000L
        private const val INCOMPLETE_WATCH_RECONCILE_MAX_AGE_MS = 60_000L
        private const val TRACKING_POLICY_VERSION = 2
        private val IGNORED_DIRECTORY_NAMES = setOf(
            ".git", ".gradle", ".idea", ".next", ".cache", ".turbo", ".parcel-cache", ".vortex-bridge",
            "node_modules", "build", "dist", "out", "target", "vendor", "Pods",
            ".venv", "venv", "__pycache__", "coverage", ".pytest_cache", ".mypy_cache"
        )
        private val IGNORED_FILE_NAMES = setOf(".DS_Store", "Thumbs.db")
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
    private val manifestRoot = File(recordsRoot, "manifests").apply { mkdirs() }
    private val stateFile = File(recordsRoot, "state.json")
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val pending = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val sequence = AtomicLong(0L)
    private val observed = LinkedHashMap<String, Entry>()
    private val checkpoint = LinkedHashMap<String, Entry>()
    private val candidateSessionsByPath = LinkedHashMap<String, LinkedHashMap<String, JSONObject>>()
    @Volatile private var initialized = false
    @Volatile private var lastReconcileAt = 0L
    @Volatile private var watcherActive = false
    @Volatile private var watcherCoverageComplete = false
    @Volatile private var treeReconcilePending = false
    private var trackingPolicyVersion = 0
    private var candidateSessionEvidenceComplete = true
    private var checkpointAt = 0L
    private var checkpointSequence = -1L
    private var checkpointReason = "initial"
    private var checkpointGitRoot: String? = null
    private var checkpointGitHeadSha: String? = null
    private var eventChainEpoch = ""
    private var eventChainStartSequence = 0L
    private var eventChainAnchorHash = RiftPatchManifestV1.GENESIS
    private var eventChainLastHash = RiftPatchManifestV1.GENESIS
    private var eventPrunedThroughSequence = 0L
    private var eventPrunedThroughAt = 0L
    private var trustedCheckpointAt = 0L
    private var trustedManifestSha256: String? = null
    private var trustedTreeSha256: String? = null

    fun start() {
        if (initialized) return
        executor.execute {
            runCatching {
                RiftDeadline.runUntil(SystemClock.elapsedRealtime() + RECORD_OPERATION_TIMEOUT_MS) {
                    ensureInitialized()
                }
            }
        }
    }

    fun updateWatcherCoverage(active: Boolean, complete: Boolean) {
        watcherActive = active
        watcherCoverageComplete = active && complete
    }

    fun shouldTrackDirectory(file: File): Boolean {
        val relative = relativePath(file) ?: return false
        return relative.isBlank() || shouldTrackPath(relative)
    }

    fun observe(type: String, file: File, directory: Boolean) {
        val relative = relativePath(file) ?: return
        if (relative.isNotBlank() && !shouldTrackPath(relative)) return
        start()
        if (directory || type in setOf("move-from", "move-to", "delete-self", "move-self")) {
            treeReconcilePending = true
            schedule("__tree__", 420L) { reconcileAll("watch:$type") }
            return
        }
        schedule(relative, EVENT_SETTLE_MS) { capturePath(relative, "watch:$type") }
    }

    fun query(args: JSONObject = JSONObject()): JSONObject =
        runBounded("workspace records query") {
            ensureInitialized()
            queryInternal(args)
        }

    fun checkpoint(args: JSONObject = JSONObject()): JSONObject =
        runBounded("workspace records checkpoint") {
            ensureInitialized()
            createCheckpoint(
                reason = args.optString("reason", "manual").take(80).ifBlank { "manual" },
                gitRoot = args.optString("gitRoot").takeIf { it.isNotBlank() },
                gitHeadSha = args.optString("gitHeadSha").takeIf { it.isNotBlank() }
            )
        }

    /**
     * Internal-only candidate freeze for the future Local Agent gate.
     * No MCP ToolHost mapping exists in OBSERVE construction.
     */
    fun freezeCandidate(): JSONObject =
        runBounded("workspace candidate freeze") {
            ensureInitialized()
            prepareForRead("manifest-freeze", requireCurrent = true)
            val manifest = buildCandidateManifest()
            val receipt = RiftPatchManifestV1.freeze(manifestRoot, manifest)
            receipt.put("recordChain", verifyRecordChain())
        }

    /**
     * Internal-only semantic working set derived from the exact Patch Manifest V1 candidate.
     * The Local Agent/PI-v2 path consumes this; it is not a model-selected scope or MCP tool.
     */
    fun semanticImpactSeed(): JSONObject =
        runBounded("workspace semantic impact") {
            ensureInitialized()
            prepareForRead("semantic-impact-seed", requireCurrent = true)
            buildSemanticImpactSeed(buildCandidateManifest())
        }

    private fun prepareForRead(source: String, requireCurrent: Boolean) {
        flushPendingChanges(source)
        val incompleteCoverage = !watcherActive || !watcherCoverageComplete
        val incompleteNeedsReconcile =
            incompleteCoverage &&
                (requireCurrent ||
                    System.currentTimeMillis() - lastReconcileAt >
                        INCOMPLETE_WATCH_RECONCILE_MAX_AGE_MS)
        if (treeReconcilePending || incompleteNeedsReconcile) reconcileAll(source)
    }

    private fun flushPendingChanges(source: String) {
        val entries = pending.entries.toList()
        if (entries.isEmpty()) return

        if (entries.any { it.key == "__tree__" }) {
            for (entry in entries) {
                if (pending.remove(entry.key, entry.value)) {
                    entry.value.cancel(false)
                }
            }
            treeReconcilePending = true
            reconcileAll("$source:pending-tree")
            return
        }

        for (entry in entries) {
            if (!pending.remove(entry.key, entry.value)) continue
            entry.value.cancel(false)
            if (shouldTrackPath(entry.key)) {
                capturePath(entry.key, "$source:pending-path")
            }
        }
    }

    private fun <T> runBounded(label: String, block: () -> T): T {
        val future = executor.submit<T> {
            RiftDeadline.runUntil(SystemClock.elapsedRealtime() + RECORD_OPERATION_TIMEOUT_MS) {
                checkActive(label)
                block()
            }
        }
        return try {
            future.get(RECORD_OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            future.cancel(true)
            throw IllegalStateException("$label timed out after ${RECORD_OPERATION_TIMEOUT_MS}ms", error)
        } catch (error: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw error
        }
    }

    private fun checkActive(label: String) {
        RiftDeadline.check(label)
    }

    private fun schedule(key: String, delayMs: Long, block: () -> Unit) {
        if (key != "__tree__" && !pending.containsKey(key) && pending.size >= MAX_PENDING_WATCH_EVENTS) {
            pending.entries.toList().forEach { entry ->
                if (entry.key != "__tree__" && pending.remove(entry.key, entry.value)) {
                    entry.value.cancel(false)
                }
            }
            treeReconcilePending = true
            schedule("__tree__", 420L) { reconcileAll("watch:burst") }
            return
        }

        pending.remove(key)?.cancel(false)
        val holder = AtomicReference<ScheduledFuture<*>?>()
        val future = executor.schedule({
            try {
                RiftDeadline.runUntil(SystemClock.elapsedRealtime() + RECORD_OPERATION_TIMEOUT_MS) { block() }
            } finally {
                holder.get()?.let { completed -> pending.remove(key, completed) }
            }
        }, delayMs, TimeUnit.MILLISECONDS)
        holder.set(future)
        pending[key] = future
    }

    private fun ensureInitialized() {
        checkActive("workspace records initialization")
        if (initialized) return
        val hadPersistedState = stateFile.isFile
        loadState()
        if (!hadPersistedState) {
            trackingPolicyVersion = TRACKING_POLICY_VERSION
            ensureEventChain()
            seedInitialState("initial", resetSnapshots = true)
        } else if (trackingPolicyVersion != TRACKING_POLICY_VERSION) {
            migrateTrackingPolicy()
        } else {
            ensureEventChain()
            pruneRecords()
            if (observed.isEmpty() && checkpoint.isEmpty()) {
                seedInitialState("initial", resetSnapshots = true)
            } else {
                reconcileAll("startup")
            }
        }
        initialized = true
    }

    private fun migrateTrackingPolicy() {
        observed.clear()
        checkpoint.clear()
        candidateSessionsByPath.clear()
        candidateSessionEvidenceComplete = true
        eventRoot.listFiles()?.forEach { file ->
            checkActive("workspace record migration")
            if (file.isFile && file.extension == "json") {
                require(file.delete() || !file.exists()) { "Could not compact old workspace record history" }
            }
        }
        sequence.set(0L)
        eventChainEpoch = ""
        eventChainStartSequence = 0L
        eventChainAnchorHash = RiftPatchManifestV1.GENESIS
        eventChainLastHash = RiftPatchManifestV1.GENESIS
        eventPrunedThroughSequence = 0L
        eventPrunedThroughAt = 0L
        trustedCheckpointAt = 0L
        trustedManifestSha256 = null
        trustedTreeSha256 = null
        trackingPolicyVersion = TRACKING_POLICY_VERSION
        resetSnapshotRoot(manifestRoot)
        ensureEventChain()
        seedInitialState("tracking-policy-v2", resetSnapshots = true)
    }

    private fun seedInitialState(reason: String, resetSnapshots: Boolean) {
        observed.clear()
        checkpoint.clear()
        candidateSessionsByPath.clear()
        candidateSessionEvidenceComplete = true
        if (resetSnapshots) {
            resetSnapshotRoot(observedRoot)
            resetSnapshotRoot(checkpointRoot)
        }
        val current = scanCurrentFiles()
        for ((path, file) in current) {
            val snapshot = snapshot(file)
            observed[path] = snapshot.entry
            checkpoint[path] = snapshot.entry
            writeSnapshot(observedRoot, path, snapshot.text)
            writeSnapshot(checkpointRoot, path, snapshot.text)
        }
        checkpointAt = System.currentTimeMillis()
        checkpointSequence = sequence.get()
        checkpointReason = reason
        treeReconcilePending = false
        lastReconcileAt = checkpointAt
        saveState()
    }

    private fun reconcileAll(source: String) {
        checkActive("workspace reconcile")
        val current = scanCurrentFiles()
        val currentPaths = current.keys.toSet()
        val candidates = LinkedHashMap<String, Snapshot>()
        var metadataDirty = false

        for ((path, file) in current) {
            checkActive("workspace reconcile")
            val previous = observed[path]
            if (previous != null && previous.kind == "file" &&
                previous.size == file.length() && previous.modified == file.lastModified()) continue
            candidates[path] = snapshot(file)
        }

        val beforeViews = observed.map { (path, entry) ->
            val candidate = candidates[path]
            val changed = path !in currentPaths || candidate?.entry?.sha256?.let { it != entry.sha256 } == true
            identityView(path, entry, if (changed && entry.textStored) readSnapshot(observedRoot, path) else null)
        }
        val afterViews = current.keys.mapNotNull { path ->
            val next = candidates[path]
            val entry = next?.entry ?: observed[path] ?: return@mapNotNull null
            identityView(path, entry, next?.text)
        }
        val correlation = RiftFileIdentityV2.correlate(beforeViews, afterViews)
        val handledTargets = HashSet<String>()
        val rewriteRelations = correlation.relations
            .filter { it.kind == "rewritten" && it.fromPath == it.toPath }
            .associateBy { it.toPath }

        for (relation in correlation.relations) {
            checkActive("workspace reconcile")
            if (relation.kind !in setOf("renamed", "copied")) continue
            val target = candidates[relation.toPath] ?: continue
            val sourceEntry = observed[relation.fromPath] ?: continue
            val valid = when (relation.kind) {
                "renamed" -> relation.fromPath !in currentPaths && relation.toPath !in observed
                "copied" -> relation.fromPath in currentPaths && relation.toPath !in observed
                else -> false
            }
            if (!valid) continue
            recordIdentityRelation(relation, sourceEntry, target, source)
            handledTargets += relation.toPath
        }

        for ((path, next) in candidates) {
            checkActive("workspace reconcile")
            if (path in handledTargets) continue
            val previous = observed[path]
            if (previous == null || previous.sha256 != next.entry.sha256 || previous.kind != next.entry.kind) {
                recordChange(
                    path,
                    previous,
                    next,
                    source,
                    rewriteRelation = rewriteRelations[path],
                    allowRewriteHeuristic = false
                )
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
        treeReconcilePending = false
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

        if (previous == null && next != null) {
            val exactSource = observed.entries
                .asSequence()
                .filter { (candidatePath, entry) ->
                    candidatePath != path && entry.kind == next.entry.kind && entry.sha256 == next.entry.sha256
                }
                .sortedBy { it.key }
                .firstOrNull()
            if (exactSource != null) {
                recordIdentityRelation(
                    RiftFileIdentityV2.Relation("copied", exactSource.key, path, 100, "sha256", true),
                    exactSource.value,
                    next,
                    source
                )
                return
            }
        }
        recordChange(path, previous, next, source)
    }

    private fun recordChange(
        path: String,
        before: Entry?,
        after: Snapshot?,
        source: String,
        rewriteRelation: RiftFileIdentityV2.Relation? = null,
        allowRewriteHeuristic: Boolean = true
    ) {
        val beforeText = before?.takeIf { it.textStored }?.let { readSnapshot(observedRoot, path) }
        val afterText = after?.text
        val rewriteSimilarity = when {
            rewriteRelation?.kind == "rewritten" -> rewriteRelation.similarity
            allowRewriteHeuristic && before != null && after != null && before.kind == after.entry.kind ->
                RiftFileIdentityV2.majorRewriteSimilarity(beforeText, afterText, before.size, after.entry.size)
            else -> null
        }
        val action = when {
            before == null && after != null -> "created"
            before != null && after == null -> "deleted"
            before?.kind != after?.entry?.kind -> "type-changed"
            rewriteSimilarity != null -> "rewritten"
            else -> "modified"
        }
        val at = System.currentTimeMillis()
        val seq = sequence.incrementAndGet()
        val recordId = "rec-$seq-$at"
        val provenance = RiftPatchSessions.resolve(path, after != null, after?.entry?.sha256)
            ?: RiftPatchSessions.unattributed(recordId, path, source, at)
        val record = JSONObject()
            .put("format", FORMAT)
            .put("id", recordId)
            .put("patchId", provenance.getString("patchId"))
            .put("provenance", provenance)
            .put("sequence", seq)
            .put("at", at)
            .put("path", path)
            .put("action", action)
            .put("source", source)
            .put("before", entryJson(before))
            .put("after", entryJson(after?.entry))
            .put("textDiffAvailable", beforeText != null || afterText != null)
            .put("diff", buildDiff(path, beforeText, afterText, before, after?.entry))
        if (rewriteSimilarity != null) {
            record.put(
                "identity",
                rewriteRelation?.let(::relationJson)
                    ?: JSONObject()
                        .put("version", RiftFileIdentityV2.VERSION)
                        .put("kind", "rewritten")
                        .put("fromPath", path)
                        .put("toPath", path)
                        .put("similarity", rewriteSimilarity)
                        .put("method", "bounded-line-dice")
                        .put("exact", false)
            )
        }
        val file = File(eventRoot, "%012d-%013d.json".format(seq, at))
        val sealedRecord = RiftPatchManifestV1.sealRecord(record, eventChainEpoch, eventChainLastHash)
        writeJsonAtomic(file, sealedRecord)
        eventChainLastHash = sealedRecord.getString("recordHash")

        if (after == null) {
            observed.remove(path)
            snapshotFile(observedRoot, path).delete()
            pruneEmptyParents(snapshotFile(observedRoot, path).parentFile, observedRoot)
        } else {
            observed[path] = after.entry
            writeSnapshot(observedRoot, path, after.text)
        }
        rememberCandidateSession(path, provenance)
        pruneRecords()
        saveState()
    }

    private fun recordIdentityRelation(
        relation: RiftFileIdentityV2.Relation,
        before: Entry,
        after: Snapshot,
        source: String
    ) {
        val beforeText = before.takeIf { it.textStored }?.let { readSnapshot(observedRoot, relation.fromPath) }
        val afterText = after.text
        val at = System.currentTimeMillis()
        val seq = sequence.incrementAndGet()
        val recordId = "rec-$seq-$at"
        val provenance = RiftPatchSessions.resolve(relation.toPath, true, after.entry.sha256)
            ?: RiftPatchSessions.unattributed(recordId, relation.toPath, source, at)
        val record = JSONObject()
            .put("format", FORMAT)
            .put("id", recordId)
            .put("patchId", provenance.getString("patchId"))
            .put("provenance", provenance)
            .put("sequence", seq)
            .put("at", at)
            .put("path", relation.toPath)
            .put("action", relation.kind)
            .put("source", source)
            .put("identity", relationJson(relation))
            .put("before", entryJson(before))
            .put("after", entryJson(after.entry))
            .put("textDiffAvailable", beforeText != null || afterText != null)
            .put(
                "diff",
                buildDiff(
                    relation.toPath,
                    beforeText,
                    afterText,
                    before,
                    after.entry,
                    beforePath = relation.fromPath,
                    afterPath = relation.toPath
                )
            )
        val file = File(eventRoot, "%012d-%013d.json".format(seq, at))
        val sealedRecord = RiftPatchManifestV1.sealRecord(record, eventChainEpoch, eventChainLastHash)
        writeJsonAtomic(file, sealedRecord)
        eventChainLastHash = sealedRecord.getString("recordHash")

        if (relation.kind == "renamed" && relation.fromPath != relation.toPath) {
            observed.remove(relation.fromPath)
            val oldSnapshot = snapshotFile(observedRoot, relation.fromPath)
            oldSnapshot.delete()
            pruneEmptyParents(oldSnapshot.parentFile, observedRoot)
        }
        observed[relation.toPath] = after.entry
        writeSnapshot(observedRoot, relation.toPath, after.text)
        rememberCandidateSession(relation.toPath, provenance)
        pruneRecords()
        saveState()
    }

    private fun rememberCandidateSession(path: String, provenance: JSONObject) {
        if (sameEntry(checkpoint[path], observed[path])) {
            candidateSessionsByPath.remove(path)
            return
        }
        val patchId = provenance.optString("patchId").trim()
        if (patchId.isBlank()) {
            candidateSessionEvidenceComplete = false
            return
        }
        val sessionsForPath = candidateSessionsByPath.getOrPut(path) { LinkedHashMap() }
        if (sessionsForPath.containsKey(patchId)) return
        val totalSessions = candidateSessionsByPath.values.sumOf { it.size }
        if (totalSessions >= MAX_CANDIDATE_SESSION_EVIDENCE) {
            candidateSessionEvidenceComplete = false
            return
        }
        sessionsForPath[patchId] = JSONObject()
            .put("patchId", patchId)
            .put("origin", provenance.optString("origin", "unknown"))
            .put("operation", provenance.optString("operation", "unknown"))
            .put("intent", if (provenance.isNull("intent")) JSONObject.NULL else provenance.optString("intent"))
            .put("requestId", if (provenance.isNull("requestId")) JSONObject.NULL else provenance.optString("requestId"))
            .put("confidence", provenance.optString("confidence", "none"))
            .put("attributed", provenance.optBoolean("attributed", false))
            .put("startedAt", provenance.optLong("startedAt", 0L))
            .put("committedAt", provenance.optLong("committedAt", 0L))
    }

    private fun candidateSessionRows(changedPaths: Set<String>): JSONArray {
        val sessionsById = LinkedHashMap<String, JSONObject>()
        for (path in changedPaths.sorted()) {
            val sessions = candidateSessionsByPath[path] ?: continue
            for ((patchId, session) in sessions.toSortedMap()) {
                if (!sessionsById.containsKey(patchId)) {
                    sessionsById[patchId] = JSONObject(session.toString())
                }
            }
        }
        val rows = JSONArray()
        for (session in sessionsById.toSortedMap().values) rows.put(session)
        return rows
    }

    private fun oldestRetainedEventAt(): Long? =
        eventRoot.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension == "json" }
            ?.mapNotNull { file ->
                file.nameWithoutExtension.substringAfter('-', "").toLongOrNull()
            }
            ?.minOrNull()

    private fun createCheckpoint(reason: String, gitRoot: String?, gitHeadSha: String?): JSONObject {
        reconcileAll("checkpoint")
        val prefix = gitRoot
            ?.let(::normalizePrefix)
            ?.takeIf { it.isNotBlank() }

        if (prefix == null) {
            checkpoint.clear()
            resetSnapshotRoot(checkpointRoot)
            for ((path, entry) in observed) {
                checkpoint[path] = entry
                if (entry.textStored) {
                    val text = readSnapshot(observedRoot, path)
                    writeSnapshot(checkpointRoot, path, text)
                }
            }
            candidateSessionsByPath.clear()
            candidateSessionEvidenceComplete = true
        } else {
            checkpoint.keys
                .filter { path -> matchesPrefix(path, prefix) }
                .forEach { path -> checkpoint.remove(path) }
            resetSnapshotPrefix(checkpointRoot, prefix)
            for ((path, entry) in observed) {
                if (!matchesPrefix(path, prefix)) continue
                checkpoint[path] = entry
                if (entry.textStored) {
                    val text = readSnapshot(observedRoot, path)
                    writeSnapshot(checkpointRoot, path, text)
                }
            }
            candidateSessionsByPath.keys
                .filter { path -> matchesPrefix(path, prefix) }
                .forEach { path -> candidateSessionsByPath.remove(path) }
        }

        checkpointAt = System.currentTimeMillis()
        checkpointSequence = sequence.get()
        checkpointReason = reason
        checkpointGitRoot = gitRoot
        checkpointGitHeadSha = gitHeadSha
        saveState()
        return checkpointSummary()
    }

    private fun queryInternal(args: JSONObject): JSONObject {
        prepareForRead("query", requireCurrent = false)
        val prefix = normalizePrefix(args.optString("path"))
        val includeDiff = !args.has("includeDiff") || args.optBoolean("includeDiff", true)
        val limit = args.optInt("limit", 120).coerceIn(1, MAX_QUERY_RECORDS)
        val allPaths = (checkpoint.keys + observed.keys).toSortedSet()
        val correlation = identityCorrelation(checkpoint, checkpointRoot, observed, observedRoot)
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

        val identityRows = JSONArray()
        var matchingRelations = 0
        var omittedRelations = 0
        for (relation in correlation.relations) {
            if (!matchesPrefix(relation.fromPath, prefix) && !matchesPrefix(relation.toPath, prefix)) continue
            matchingRelations++
            val row = relationJson(relation)
            val size = row.toString().length
            if (identityRows.length() < MAX_QUERY_RECORDS && payloadChars + size <= MAX_QUERY_PAYLOAD_CHARS) {
                identityRows.put(row)
                payloadChars += size
            } else omittedRelations++
        }

        val candidateManifest = buildCandidateManifest(correlation)
        val candidateSummary = candidateSummary(candidateManifest)
        return JSONObject()
            .put("format", FORMAT)
            .put("scope", "riftfs/workspace")
            .put("checkpoint", checkpointSummary())
            .put("trustedCheckpoint", trustedCheckpointSummary())
            .put("recordChain", verifyRecordChain())
            .put("candidate", candidateSummary)
            .put("summary", JSONObject()
                .put("changedFiles", changedCount)
                .put("records", matchingRecords)
                .put("identityRelations", matchingRelations)
                .put("returnedRelations", identityRows.length())
                .put("returnedRecords", records.length())
                .put("returnedFiles", changed.length())
                .put("omittedFiles", omittedFiles)
                .put("omittedRecords", omittedRecords)
                .put("omittedRelations", omittedRelations)
                .put("responseTruncated", omittedFiles > 0 || omittedRecords > 0 || omittedRelations > 0)
                .put("recordLimit", limit))
            .put("identity", JSONObject()
                .put("version", RiftFileIdentityV2.VERSION)
                .put("similarityComparisons", correlation.similarityComparisons)
                .put("similaritySkipped", correlation.similaritySkipped)
                .put("relations", identityRows))
            .put("files", changed)
            .put("records", records)
    }

    private fun checkpointSummary(): JSONObject = JSONObject()
        .put("kind", "operational")
        .put("at", checkpointAt)
        .put("sequence", checkpointSequence)
        .put("reason", checkpointReason)
        .put("gitRoot", checkpointGitRoot ?: JSONObject.NULL)
        .put("gitHeadSha", checkpointGitHeadSha ?: JSONObject.NULL)
        .put("files", checkpoint.size)

    private fun trustedCheckpointSummary(): JSONObject = JSONObject()
        .put("kind", "trusted")
        .put("present", trustedManifestSha256 != null && trustedTreeSha256 != null)
        .put("at", trustedCheckpointAt)
        .put("manifestSha256", trustedManifestSha256 ?: JSONObject.NULL)
        .put("treeSha256", trustedTreeSha256 ?: JSONObject.NULL)

    private fun candidateSummary(manifest: JSONObject): JSONObject {
        val base = manifest.getJSONObject("base")
        val result = manifest.getJSONObject("result")
        val sessions = manifest.getJSONObject("sessionEvidence")
        val sha = manifest.getString("manifestSha256")
        val stateSha = manifest.getString("candidateStateSha256")
        return JSONObject()
            .put("version", RiftPatchManifestV1.VERSION)
            .put("candidateId", manifest.getString("candidateId"))
            .put("candidateStateSha256", stateSha)
            .put("manifestSha256", sha)
            .put("baseTreeSha256", base.getString("treeSha256"))
            .put("resultTreeSha256", result.getString("treeSha256"))
            .put("changeSetSha256", manifest.getString("changeSetSha256"))
            .put("structuralDiffSha256", manifest.getString("structuralDiffSha256"))
            .put("changedFiles", manifest.getJSONArray("changes").length())
            .put("patchSessions", sessions.getJSONArray("sessions").length())
            .put("sessionEvidenceComplete", sessions.getBoolean("complete"))
            .put("frozen", File(manifestRoot, "$sha.json").isFile)
    }

    private fun buildCandidateManifest(
        suppliedCorrelation: RiftFileIdentityV2.Correlation? = null
    ): JSONObject {
        val baseEntries = checkpoint.map { (path, entry) ->
            RiftPatchManifestV1.TreeEntry(path, entry.kind, entry.size, entry.sha256)
        }
        val resultEntries = observed.map { (path, entry) ->
            RiftPatchManifestV1.TreeEntry(path, entry.kind, entry.size, entry.sha256)
        }
        val allPaths = (checkpoint.keys + observed.keys).toSortedSet()
        require(allPaths.size <= 50_000) { "Candidate manifest exceeds 50000 workspace paths" }

        val changes = ArrayList<RiftPatchManifestV1.Change>()
        val changeRows = JSONArray()
        val changedPaths = LinkedHashSet<String>()
        for (path in allPaths) {
            val before = checkpoint[path]
            val after = observed[path]
            if (sameEntry(before, after)) continue
            changedPaths += path
            val beforeTree = before?.let {
                RiftPatchManifestV1.TreeEntry(path, it.kind, it.size, it.sha256)
            }
            val afterTree = after?.let {
                RiftPatchManifestV1.TreeEntry(path, it.kind, it.size, it.sha256)
            }
            val state = status(before, after)
            changes += RiftPatchManifestV1.Change(path, state, beforeTree, afterTree)
            changeRows.put(JSONObject()
                .put("path", path)
                .put("status", state)
                .put("before", manifestEntryJson(before))
                .put("after", manifestEntryJson(after)))
        }

        val correlation = suppliedCorrelation
            ?: identityCorrelation(checkpoint, checkpointRoot, observed, observedRoot)
        val relationRows = JSONArray()
        correlation.relations
            .sortedWith(compareBy({ it.fromPath }, { it.toPath }, { it.kind }))
            .forEach { relationRows.put(relationJson(it)) }

        val oldestRetainedAt = oldestRetainedEventAt()
        val sessionRows = candidateSessionRows(changedPaths)
        val sessionComplete = changedPaths.isEmpty() || candidateSessionEvidenceComplete

        val structural = JSONObject()
            .put("changes", changeRows)
            .put("relations", relationRows)

        val baseTreeSha = RiftPatchManifestV1.treeSha256(baseEntries)
        val resultTreeSha = RiftPatchManifestV1.treeSha256(resultEntries)
        val changeSetSha = RiftPatchManifestV1.changeSetSha256(changes)
        val structuralDiffSha = RiftPatchManifestV1.sha256Canonical(structural)
        val candidateState = JSONObject()
            .put("version", RiftPatchManifestV1.VERSION)
            .put("baseTreeSha256", baseTreeSha)
            .put("resultTreeSha256", resultTreeSha)
            .put("changeSetSha256", changeSetSha)
            .put("structuralDiffSha256", structuralDiffSha)
        val candidateStateSha = RiftPatchManifestV1.sha256Canonical(candidateState)

        val payload = JSONObject()
            .put("format", "rift-patch-manifest-v1")
            .put("version", RiftPatchManifestV1.VERSION)
            .put("mode", "observe-evidence")
            .put("candidateStateSha256", candidateStateSha)
            .put("base", JSONObject()
                .put("checkpointKind", "operational")
                .put("checkpointAt", checkpointAt)
                .put("checkpointSequence", checkpointSequence)
                .put("reason", checkpointReason)
                .put("gitRoot", checkpointGitRoot ?: JSONObject.NULL)
                .put("gitHeadSha", checkpointGitHeadSha ?: JSONObject.NULL)
                .put("treeSha256", baseTreeSha))
            .put("result", JSONObject()
                .put("treeSha256", resultTreeSha))
            .put("changeSetSha256", changeSetSha)
            .put("structuralDiffSha256", structuralDiffSha)
            .put("changes", changeRows)
            .put("identity", JSONObject()
                .put("version", RiftFileIdentityV2.VERSION)
                .put("similarityComparisons", correlation.similarityComparisons)
                .put("similaritySkipped", correlation.similaritySkipped)
                .put("relations", relationRows))
            .put("sessionEvidence", JSONObject()
                .put("complete", sessionComplete)
                .put("checkpointSequence", checkpointSequence)
                .put("prunedThroughSequence", eventPrunedThroughSequence)
                .put("prunedThroughAt", eventPrunedThroughAt)
                .put("oldestRetainedAt", oldestRetainedAt ?: JSONObject.NULL)
                .put("sessions", sessionRows))
            .put("recordChain", verifyRecordChain())
            .put("trustedCheckpoint", trustedCheckpointSummary())

        val sealedManifest = RiftPatchManifestV1.sealManifest(payload)
        sealedManifest.put("candidateId", "candidate-${candidateStateSha.take(24)}")
        return sealedManifest
    }

    private fun manifestEntryJson(entry: Entry?): Any =
        if (entry == null) JSONObject.NULL else JSONObject()
            .put("kind", entry.kind)
            .put("size", entry.size)
            .put("sha256", entry.sha256)

    private fun buildSemanticImpactSeed(manifest: JSONObject): JSONObject {
        val manifestChanges = manifest.getJSONArray("changes")
        val rows = JSONArray()
        val omissions = JSONArray()
        var textBytes = 0L
        var sourceFiles = 0
        var complete = true
        val limit = minOf(manifestChanges.length(), MAX_SEMANTIC_SEED_CHANGES)

        if (manifestChanges.length() > MAX_SEMANTIC_SEED_CHANGES) {
            complete = false
            omissions.put(JSONObject()
                .put("reason", "change-count-bound")
                .put("omitted", manifestChanges.length() - MAX_SEMANTIC_SEED_CHANGES))
        }

        for (index in 0 until limit) {
            val manifestRow = manifestChanges.getJSONObject(index)
            val path = manifestRow.getString("path")
            val before = checkpoint[path]
            val after = observed[path]
            val source = RiftSourceIntelligenceV2.isSourcePath(path)
            var beforeText: String? = null
            var afterText: String? = null
            var semanticTextComplete = true

            if (source) {
                sourceFiles++
                if (sourceFiles > MAX_SEMANTIC_SEED_SOURCE_FILES) {
                    semanticTextComplete = false
                    complete = false
                    omissions.put(JSONObject().put("path", path).put("reason", "source-file-bound"))
                } else {
                    val candidateBefore = before?.takeIf { it.textStored }?.let { readSnapshot(checkpointRoot, path) }
                    val candidateAfter = after?.takeIf { it.textStored }?.let { readSnapshot(observedRoot, path) }
                    if (before != null && candidateBefore == null) semanticTextComplete = false
                    if (after != null && candidateAfter == null) semanticTextComplete = false

                    val requestedBytes =
                        (candidateBefore?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L) +
                        (candidateAfter?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L)
                    if (semanticTextComplete && textBytes + requestedBytes <= MAX_SEMANTIC_SEED_TEXT_BYTES) {
                        beforeText = candidateBefore
                        afterText = candidateAfter
                        textBytes += requestedBytes
                    } else if (before != null || after != null) {
                        semanticTextComplete = false
                    }

                    if (!semanticTextComplete) {
                        complete = false
                        val unavailable =
                            (before != null && !before.textStored) ||
                            (after != null && !after.textStored)
                        val missing =
                            (before != null && before.textStored && candidateBefore == null) ||
                            (after != null && after.textStored && candidateAfter == null)
                        omissions.put(JSONObject()
                            .put("path", path)
                            .put("reason", when {
                                unavailable -> "source-text-unavailable"
                                missing -> "source-text-missing"
                                else -> "source-text-budget"
                            }))
                    }
                }
            }

            rows.put(JSONObject()
                .put("path", path)
                .put("status", manifestRow.getString("status"))
                .put("before", manifestRow.get("before"))
                .put("after", manifestRow.get("after"))
                .put("source", source)
                .put("semanticTextComplete", semanticTextComplete)
                .put("beforeText", beforeText ?: JSONObject.NULL)
                .put("afterText", afterText ?: JSONObject.NULL))
        }

        val ownershipLedgers = JSONArray()
        val projectRoots = linkedSetOf<String>()
        for (index in 0 until limit) {
            val path = manifestChanges.getJSONObject(index).getString("path").trim('/')
            if (path.contains('/')) projectRoots += path.substringBefore('/')
        }
        if (projectRoots.size > MAX_SEMANTIC_SEED_OWNERSHIP_PROJECTS) {
            complete = false
            omissions.put(JSONObject()
                .put("reason", "ownership-project-bound")
                .put("omitted", projectRoots.size - MAX_SEMANTIC_SEED_OWNERSHIP_PROJECTS))
        }
        for (root in projectRoots.sorted().take(MAX_SEMANTIC_SEED_OWNERSHIP_PROJECTS)) {
            val path = "$root/docs/SOURCE_OWNERSHIP.md"
            val before = checkpoint[path]
            val after = observed[path]
            val beforeText = before?.takeIf { it.textStored }?.let { readSnapshot(checkpointRoot, path) }
            val afterText = after?.takeIf { it.textStored }?.let { readSnapshot(observedRoot, path) }
            val ledgerComplete =
                (before == null || beforeText != null) &&
                (after == null || afterText != null)
            if (!ledgerComplete) {
                complete = false
                omissions.put(JSONObject().put("path", path).put("reason", "ownership-ledger-unavailable"))
            }
            ownershipLedgers.put(JSONObject()
                .put("projectRoot", root)
                .put("path", path)
                .put("complete", ledgerComplete)
                .put("beforeText", beforeText ?: JSONObject.NULL)
                .put("afterText", afterText ?: JSONObject.NULL))
        }

        return JSONObject()
            .put("format", "rift-candidate-impact-seed-v1")
            .put("version", 1)
            .put("candidate", candidateSummary(manifest))
            .put("complete", complete)
            .put("changesTotal", manifestChanges.length())
            .put("changesReturned", rows.length())
            .put("sourceFilesSeen", sourceFiles)
            .put("sourceTextBytes", textBytes)
            .put("maxChanges", MAX_SEMANTIC_SEED_CHANGES)
            .put("maxSourceFiles", MAX_SEMANTIC_SEED_SOURCE_FILES)
            .put("maxSourceTextBytes", MAX_SEMANTIC_SEED_TEXT_BYTES)
            .put("maxOwnershipProjects", MAX_SEMANTIC_SEED_OWNERSHIP_PROJECTS)
            .put("identity", manifest.getJSONObject("identity"))
            .put("ownershipLedgers", ownershipLedgers)
            .put("changes", rows)
            .put("omissions", omissions)
    }

    private fun verifyRecordChain(): JSONObject {
        val epoch = eventChainEpoch
        if (epoch.isBlank()) return JSONObject()
            .put("version", RiftPatchManifestV1.RECORD_CHAIN_VERSION)
            .put("ok", false)
            .put("reason", "chain-not-initialized")

        var expectedPrevious = eventChainAnchorHash
        var checked = 0
        var brokenId: String? = null
        var reason: String? = null
        val files = eventRoot.listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.sortedBy { it.name }
            .orEmpty()
        for (file in files) {
            val row = runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull() ?: continue
            if (row.optString("chainEpoch") != epoch) continue
            if (row.optLong("sequence", 0L) <= eventPrunedThroughSequence) continue
            val verification = RiftPatchManifestV1.verifyRecord(row, epoch, expectedPrevious)
            checked++
            if (!verification.ok) {
                brokenId = row.optString("id").takeIf { it.isNotBlank() } ?: file.name
                reason = verification.reason
                break
            }
            expectedPrevious = verification.recordHash ?: expectedPrevious
        }
        if (brokenId == null && expectedPrevious != eventChainLastHash) {
            reason = "state-head-mismatch"
        }
        return JSONObject()
            .put("version", RiftPatchManifestV1.RECORD_CHAIN_VERSION)
            .put("epoch", epoch)
            .put("startSequence", eventChainStartSequence)
            .put("prunedThroughSequence", eventPrunedThroughSequence)
            .put("prunedThroughAt", eventPrunedThroughAt)
            .put("anchorHash", eventChainAnchorHash)
            .put("lastRecordHash", eventChainLastHash)
            .put("checked", checked)
            .put("ok", brokenId == null && reason == null)
            .put("brokenId", brokenId ?: JSONObject.NULL)
            .put("reason", reason ?: JSONObject.NULL)
    }

    private fun ensureEventChain() {
        if (eventChainEpoch.isBlank()) {
            eventChainEpoch = "chain-${UUID.randomUUID()}"
            eventChainStartSequence = sequence.get() + 1L
            eventChainAnchorHash = RiftPatchManifestV1.GENESIS
            eventChainLastHash = RiftPatchManifestV1.GENESIS
            eventPrunedThroughSequence = 0L
            eventPrunedThroughAt = 0L
            saveState()
            return
        }
        recoverEventChainHeadIfSafe()
    }

    private fun recoverEventChainHeadIfSafe() {
        var expectedPrevious = eventChainAnchorHash
        var tail = eventChainAnchorHash
        var persistedHeadWasAncestor = eventChainLastHash == eventChainAnchorHash
        val files = eventRoot.listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.sortedBy { it.name }
            .orEmpty()
        for (file in files) {
            val row = runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull() ?: continue
            if (row.optString("chainEpoch") != eventChainEpoch) continue
            if (row.optLong("sequence", 0L) <= eventPrunedThroughSequence) continue
            val verification = RiftPatchManifestV1.verifyRecord(row, eventChainEpoch, expectedPrevious)
            if (!verification.ok) return
            tail = verification.recordHash ?: return
            expectedPrevious = tail
            if (tail == eventChainLastHash) persistedHeadWasAncestor = true
        }
        if (persistedHeadWasAncestor && tail != eventChainLastHash) {
            eventChainLastHash = tail
            saveState()
        }
    }

    private fun scanCurrentFiles(): LinkedHashMap<String, File> {
        val out = LinkedHashMap<String, File>()
        fun walk(directory: File) {
            val children = directory.listFiles()?.sortedBy { it.name.lowercase() }.orEmpty()
            for (child in children) {
                checkActive("workspace record scan")
                if (child.name in IGNORED_DIRECTORY_NAMES || child.name in IGNORED_FILE_NAMES) continue
                val canonical = runCatching { child.canonicalFile }.getOrNull() ?: continue
                if (!insideWorkspace(canonical)) continue
                val relative = relativePath(canonical) ?: continue
                if (!shouldTrackPath(relative)) continue
                if (canonical.isDirectory) walk(canonical)
                else if (canonical.isFile) out[relative] = canonical
            }
        }
        walk(workspaceRoot)
        return out
    }

    private fun shouldTrackPath(path: String): Boolean {
        val normalized = path.replace('\\', '/').trim('/')
        if (normalized.isBlank()) return true
        val segments = normalized.split('/').filter { it.isNotBlank() }
        if (segments.any { it in IGNORED_DIRECTORY_NAMES }) return false
        return segments.lastOrNull() !in IGNORED_FILE_NAMES
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

    private fun buildDiff(
        path: String,
        before: String?,
        after: String?,
        beforeEntry: Entry?,
        afterEntry: Entry?,
        beforePath: String? = null,
        afterPath: String? = null
    ): String =
        RiftDiffEngineV2.render(
            path = path,
            beforeText = before,
            afterText = after,
            before = beforeEntry?.let { RiftDiffEngineV2.Descriptor(it.size, it.sha256) },
            after = afterEntry?.let { RiftDiffEngineV2.Descriptor(it.size, it.sha256) },
            beforePath = beforePath,
            afterPath = afterPath,
            maxChars = MAX_DIFF_CHARS,
            maxChangedLines = MAX_DIFF_LINES
        )

    private fun identityView(path: String, entry: Entry, text: String?): RiftFileIdentityV2.FileView =
        RiftFileIdentityV2.FileView(path, entry.size, entry.sha256, text)

    private fun identityCorrelation(
        before: Map<String, Entry>,
        beforeRoot: File,
        after: Map<String, Entry>,
        afterRoot: File
    ): RiftFileIdentityV2.Correlation {
        val beforeViews = before.map { (path, entry) ->
            val changed = !sameEntry(entry, after[path])
            identityView(path, entry, if (changed && entry.textStored) readSnapshot(beforeRoot, path) else null)
        }
        val afterViews = after.map { (path, entry) ->
            val changed = !sameEntry(before[path], entry)
            identityView(path, entry, if (changed && entry.textStored) readSnapshot(afterRoot, path) else null)
        }
        return RiftFileIdentityV2.correlate(beforeViews, afterViews)
    }

    private fun relationJson(relation: RiftFileIdentityV2.Relation): JSONObject = JSONObject()
        .put("version", RiftFileIdentityV2.VERSION)
        .put("kind", relation.kind)
        .put("fromPath", relation.fromPath)
        .put("toPath", relation.toPath)
        .put("similarity", relation.similarity)
        .put("method", relation.method)
        .put("exact", relation.exact)

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
        val files = eventRoot.listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.sortedByDescending { it.name }
            .orEmpty()
        val dropped = files.drop(MAX_RECORDS)
        val immediatePredecessor = dropped.firstOrNull()?.let { file ->
            runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull()
        }
        if (immediatePredecessor != null) {
            eventPrunedThroughSequence = maxOf(
                eventPrunedThroughSequence,
                immediatePredecessor.optLong("sequence", 0L)
            )
            eventPrunedThroughAt = maxOf(
                eventPrunedThroughAt,
                immediatePredecessor.optLong("at", 0L)
            )
            if (immediatePredecessor.optString("chainEpoch") == eventChainEpoch) {
                immediatePredecessor.optString("recordHash")
                    .takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
                    ?.let { eventChainAnchorHash = it }
            }
            // Persist the new verification anchor before deleting the predecessor it refers to.
            saveState()
        }
        dropped.forEach { runCatching { it.delete() } }
    }

    private fun loadState() {
        if (!stateFile.isFile) return
        val state = runCatching { JSONObject(stateFile.readText(Charsets.UTF_8)) }.getOrNull() ?: return
        trackingPolicyVersion = state.optInt("trackingPolicyVersion", 0)
        candidateSessionEvidenceComplete = state.optBoolean(
            "candidateSessionEvidenceComplete",
            trackingPolicyVersion == TRACKING_POLICY_VERSION
        )
        sequence.set(state.optLong("sequence", 0L))
        checkpointAt = state.optLong("checkpointAt", 0L)
        checkpointSequence = state.optLong("checkpointSequence", -1L)
        checkpointReason = state.optString("checkpointReason", "initial")
        checkpointGitRoot = if (state.isNull("checkpointGitRoot")) null else state.optString("checkpointGitRoot").takeIf { it.isNotBlank() }
        checkpointGitHeadSha = if (state.isNull("checkpointGitHeadSha")) null else state.optString("checkpointGitHeadSha").takeIf { it.isNotBlank() }
        eventChainEpoch = state.optString("eventChainEpoch")
        eventChainStartSequence = state.optLong("eventChainStartSequence", 0L)
        eventChainAnchorHash = state.optString("eventChainAnchorHash", RiftPatchManifestV1.GENESIS)
        eventChainLastHash = state.optString("eventChainLastHash", RiftPatchManifestV1.GENESIS)
        eventPrunedThroughSequence = state.optLong("eventPrunedThroughSequence", 0L)
        eventPrunedThroughAt = state.optLong("eventPrunedThroughAt", 0L)
        trustedCheckpointAt = state.optLong("trustedCheckpointAt", 0L)
        trustedManifestSha256 = if (state.isNull("trustedManifestSha256")) null else state.optString("trustedManifestSha256").takeIf { it.isNotBlank() }
        trustedTreeSha256 = if (state.isNull("trustedTreeSha256")) null else state.optString("trustedTreeSha256").takeIf { it.isNotBlank() }
        readEntryMap(state.optJSONObject("observed"), observed)
        readEntryMap(state.optJSONObject("checkpoint"), checkpoint)
        readCandidateSessions(state.optJSONObject("candidateSessionsByPath"))
    }

    private fun saveState() {
        val state = JSONObject()
            .put("format", FORMAT)
            .put("trackingPolicyVersion", trackingPolicyVersion)
            .put("candidateSessionEvidenceComplete", candidateSessionEvidenceComplete)
            .put("sequence", sequence.get())
            .put("checkpointAt", checkpointAt)
            .put("checkpointSequence", checkpointSequence)
            .put("checkpointReason", checkpointReason)
            .put("checkpointGitRoot", checkpointGitRoot ?: JSONObject.NULL)
            .put("checkpointGitHeadSha", checkpointGitHeadSha ?: JSONObject.NULL)
            .put("eventChainEpoch", eventChainEpoch)
            .put("eventChainStartSequence", eventChainStartSequence)
            .put("eventChainAnchorHash", eventChainAnchorHash)
            .put("eventChainLastHash", eventChainLastHash)
            .put("eventPrunedThroughSequence", eventPrunedThroughSequence)
            .put("eventPrunedThroughAt", eventPrunedThroughAt)
            .put("trustedCheckpointAt", trustedCheckpointAt)
            .put("trustedManifestSha256", trustedManifestSha256 ?: JSONObject.NULL)
            .put("trustedTreeSha256", trustedTreeSha256 ?: JSONObject.NULL)
            .put("candidateSessionsByPath", candidateSessionsJson())
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

    private fun candidateSessionsJson(): JSONObject {
        val out = JSONObject()
        for (path in candidateSessionsByPath.keys.sorted()) {
            val sessions = candidateSessionsByPath[path] ?: continue
            val rows = JSONObject()
            for ((patchId, session) in sessions.toSortedMap()) {
                rows.put(patchId, JSONObject(session.toString()))
            }
            out.put(path, rows)
        }
        return out
    }

    private fun readCandidateSessions(source: JSONObject?) {
        candidateSessionsByPath.clear()
        if (source == null) {
            if (trackingPolicyVersion == TRACKING_POLICY_VERSION) {
                candidateSessionEvidenceComplete = false
            }
            return
        }
        var total = 0
        for (path in source.keys().asSequence().toList().sorted()) {
            if (!shouldTrackPath(path)) continue
            val rows = source.optJSONObject(path) ?: continue
            val sessions = LinkedHashMap<String, JSONObject>()
            for (patchId in rows.keys().asSequence().toList().sorted()) {
                if (total >= MAX_CANDIDATE_SESSION_EVIDENCE) {
                    candidateSessionEvidenceComplete = false
                    break
                }
                val row = rows.optJSONObject(patchId) ?: continue
                sessions[patchId] = JSONObject(row.toString())
                total++
            }
            if (sessions.isNotEmpty()) candidateSessionsByPath[path] = sessions
            if (total >= MAX_CANDIDATE_SESSION_EVIDENCE) break
        }
    }

    private fun writeJsonAtomic(file: File, json: JSONObject) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.${System.nanoTime()}.tmp")
        tmp.writeText(json.toString(), Charsets.UTF_8)
        if (file.exists()) file.delete()
        check(tmp.renameTo(file)) { "Could not persist workspace records" }
    }

    private fun resetSnapshotRoot(root: File) {
        if (root.exists()) {
            var entries = 0
            fun remove(node: File): Boolean {
                checkActive("workspace record cleanup")
                require(++entries <= 10_000) { "workspace record cleanup exceeds 10,000 entries" }
                if (node.isDirectory) {
                    val children = node.listFiles()
                        ?: throw IllegalStateException("Could not read workspace record snapshot directory")
                    children.forEach { child -> require(remove(child)) { "Could not delete workspace record snapshot" } }
                }
                return node.delete()
            }
            require(remove(root)) { "Could not reset workspace record snapshot root" }
        }
        require(root.mkdirs() || root.isDirectory) { "Could not recreate workspace record snapshot root" }
    }

    private fun resetSnapshotPrefix(root: File, prefix: String) {
        val target = snapshotFile(root, prefix)
        if (!target.exists()) return
        var entries = 0
        fun remove(node: File): Boolean {
            checkActive("workspace record scoped cleanup")
            require(++entries <= 10_000) { "workspace record scoped cleanup exceeds 10,000 entries" }
            if (node.isDirectory) {
                val children = node.listFiles()
                    ?: throw IllegalStateException("Could not read workspace record scoped snapshot directory")
                children.forEach { child ->
                    require(remove(child)) { "Could not delete workspace record scoped snapshot" }
                }
            }
            return node.delete()
        }
        require(remove(target)) { "Could not reset workspace record scoped snapshot: $prefix" }
        pruneEmptyParents(target.parentFile, root)
    }

    private fun digestFile(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkActive("workspace record hash")
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

    private fun normalizePrefix(path: String): String {
        val normalized = path.replace('\\', '/').trim('/')
        require(normalized.split('/').none { segment -> segment == ".." }) { "Invalid workspace record prefix" }
        return when {
            normalized == "workspace" -> ""
            normalized.startsWith("workspace/") -> normalized.removePrefix("workspace/")
            else -> normalized
        }
    }

    private fun matchesPrefix(path: String, prefix: String): Boolean =
        prefix.isBlank() || path == prefix || path.startsWith("$prefix/")
}
