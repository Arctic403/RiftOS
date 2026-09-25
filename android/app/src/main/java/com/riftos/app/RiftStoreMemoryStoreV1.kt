package com.riftos.app

import android.util.AtomicFile
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class RiftStoreMemoryStoreV1 : RiftMemoryStoreV1 {
    companion object {
        const val FORMAT = "rift-memory-riftstore-v1"
        const val STORE_VERSION = 1
        const val MAX_STORE_BYTES = 16 * 1024 * 1024
    }

    override fun open(config: RiftMemoryStoreConfigV1): RiftMemoryStoreHandleV1 {
        val file = File(config.databasePath)
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        if (!config.createIfMissing) {
            require(atomic.exists()) { "RiftStore database does not exist: ${config.databasePath}" }
        }
        val state = if (atomic.exists()) {
            RiftStoreCodecV1.read(atomic)
        } else {
            RiftStoreStateV1()
        }
        if (!atomic.exists()) {
            RiftStoreCodecV1.write(atomic, state)
        }
        return RiftStoreMemoryStoreHandleV1(atomic, state)
    }
}

private data class RiftStoreBlobV1(
    val mediaType: String,
    val bytes: ByteArray
)

private data class RiftStoreRecordVersionV1(
    val versionId: String,
    val record: RiftCanonicalMemoryRecordV1,
    val recordHash: String
)

private data class RiftStoreEventV1(
    val sequence: Long,
    val event: JSONObject,
    val eventHash: String
)

private data class RiftStoreStateV1(
    val blobs: LinkedHashMap<String, RiftStoreBlobV1> = linkedMapOf(),
    val evidence: LinkedHashMap<String, RiftMemoryEvidenceV1> = linkedMapOf(),
    val recordVersions: MutableList<RiftStoreRecordVersionV1> = mutableListOf(),
    val currentVersions: LinkedHashMap<String, String> = linkedMapOf(),
    val events: MutableList<RiftStoreEventV1> = mutableListOf(),
    val snapshots: LinkedHashMap<String, RiftMemorySnapshotMetadataV1> = linkedMapOf(),
    val dirtyProjections: LinkedHashMap<String, RiftMemoryDirtyProjectionV1> = linkedMapOf()
)

private data class RiftStorePendingV1(
    val transaction: RiftMemoryTransactionV1,
    val blobs: LinkedHashMap<String, RiftStoreBlobV1> = linkedMapOf(),
    val evidence: LinkedHashMap<String, RiftMemoryEvidenceV1> = linkedMapOf(),
    val recordVersions: MutableList<RiftStoreRecordVersionV1> = mutableListOf(),
    val currentVersions: LinkedHashMap<String, String> = linkedMapOf(),
    val events: MutableList<RiftStoreEventV1> = mutableListOf()
)

private object RiftStoreCodecV1 {
    fun read(file: AtomicFile): RiftStoreStateV1 {
        val base = file.baseFile
        require(base.length() <= RiftStoreMemoryStoreV1.MAX_STORE_BYTES.toLong()) {
            "RiftStore state exceeds ${RiftStoreMemoryStoreV1.MAX_STORE_BYTES} byte bound"
        }
        val bytes = file.readFully()
        require(bytes.size <= RiftStoreMemoryStoreV1.MAX_STORE_BYTES) {
            "RiftStore state exceeds ${RiftStoreMemoryStoreV1.MAX_STORE_BYTES} byte bound"
        }
        val envelope = JSONObject(bytes.toString(Charsets.UTF_8))
        require(envelope.getString("schema") == RiftStoreMemoryStoreV1.FORMAT)
        require(envelope.getInt("version") == RiftStoreMemoryStoreV1.STORE_VERSION)
        val payload = envelope.getJSONObject("payload")
        val expected = envelope.getString("payloadSha256")
        val actual = RiftMemoryModelV1.canonicalSha256(payload)
        require(expected == actual) { "RiftStore state seal mismatch" }
        return decodePayload(payload)
    }

    fun write(file: AtomicFile, state: RiftStoreStateV1) {
        val payload = encodePayload(state)
        val envelope = JSONObject()
            .put("schema", RiftStoreMemoryStoreV1.FORMAT)
            .put("version", RiftStoreMemoryStoreV1.STORE_VERSION)
            .put("payload", payload)
            .put("payloadSha256", RiftMemoryModelV1.canonicalSha256(payload))
        val bytes = envelope.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= RiftStoreMemoryStoreV1.MAX_STORE_BYTES) {
            "RiftStore state exceeds ${RiftStoreMemoryStoreV1.MAX_STORE_BYTES} byte bound"
        }
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
            file.finishWrite(stream)
        } catch (failure: Throwable) {
            file.failWrite(stream)
            throw failure
        }
    }

    fun cloneState(state: RiftStoreStateV1): RiftStoreStateV1 =
        decodePayload(encodePayload(state))

    private fun encodePayload(state: RiftStoreStateV1): JSONObject {
        val blobs = JSONObject()
        state.blobs.keys.sorted().forEach { hash ->
            val blob = state.blobs.getValue(hash)
            blobs.put(
                hash,
                JSONObject()
                    .put("mediaType", blob.mediaType)
                    .put("bytesBase64", Base64.encodeToString(blob.bytes, Base64.NO_WRAP))
            )
        }
        val evidence = JSONObject()
        state.evidence.keys.sorted().forEach { id -> evidence.put(id, state.evidence.getValue(id).toJson()) }
        val versions = JSONArray()
        state.recordVersions.forEach { version ->
            versions.put(
                JSONObject()
                    .put("versionId", version.versionId)
                    .put("record", version.record.toJson())
                    .put("recordHash", version.recordHash)
            )
        }
        val current = JSONObject()
        state.currentVersions.keys.sorted().forEach { id -> current.put(id, state.currentVersions.getValue(id)) }
        val events = JSONArray()
        state.events.sortedBy { it.sequence }.forEach { event ->
            events.put(
                JSONObject()
                    .put("sequence", event.sequence)
                    .put("event", JSONObject(event.event.toString()))
                    .put("eventHash", event.eventHash)
            )
        }
        val snapshots = JSONObject()
        state.snapshots.keys.sorted().forEach { id ->
            val snapshot = state.snapshots.getValue(id)
            snapshots.put(
                id,
                JSONObject()
                    .put("id", snapshot.id)
                    .put("createdAt", snapshot.createdAt)
                    .put("eventSequence", snapshot.eventSequence)
                    .put("canonicalHash", snapshot.canonicalHash)
                    .put("metadata", JSONObject(snapshot.metadata.toString()))
            )
        }
        val dirty = JSONObject()
        state.dirtyProjections.keys.sorted().forEach { name ->
            val row = state.dirtyProjections.getValue(name)
            dirty.put(
                name,
                JSONObject()
                    .put("name", row.name)
                    .put("reason", row.reason)
                    .put("updatedAt", row.updatedAt)
            )
        }
        return JSONObject()
            .put("blobs", blobs)
            .put("evidence", evidence)
            .put("recordVersions", versions)
            .put("currentVersions", current)
            .put("events", events)
            .put("snapshots", snapshots)
            .put("dirtyProjections", dirty)
    }

    private fun decodePayload(payload: JSONObject): RiftStoreStateV1 {
        val state = RiftStoreStateV1()
        val blobs = payload.optJSONObject("blobs") ?: JSONObject()
        blobs.keys().asSequence().sorted().forEach { hash ->
            val row = blobs.getJSONObject(hash)
            val bytes = Base64.decode(row.getString("bytesBase64"), Base64.DEFAULT)
            require(sha256(bytes) == hash) { "RiftStore blob hash mismatch: $hash" }
            state.blobs[hash] = RiftStoreBlobV1(row.getString("mediaType"), bytes)
        }
        val evidence = payload.optJSONObject("evidence") ?: JSONObject()
        evidence.keys().asSequence().sorted().forEach { id ->
            val row = RiftMemoryEvidenceV1.fromJson(evidence.getJSONObject(id))
            require(row.id == id)
            state.evidence[id] = row
        }
        val versions = payload.optJSONArray("recordVersions") ?: JSONArray()
        for (index in 0 until versions.length()) {
            val row = versions.getJSONObject(index)
            val record = RiftCanonicalMemoryRecordV1.fromJson(row.getJSONObject("record"))
            val hash = row.getString("recordHash")
            require(RiftMemoryModelV1.canonicalSha256(record.toJson()) == hash)
            state.recordVersions += RiftStoreRecordVersionV1(row.getString("versionId"), record, hash)
        }
        val current = payload.optJSONObject("currentVersions") ?: JSONObject()
        current.keys().asSequence().sorted().forEach { id ->
            state.currentVersions[id] = current.getString(id)
        }
        val events = payload.optJSONArray("events") ?: JSONArray()
        for (index in 0 until events.length()) {
            val row = events.getJSONObject(index)
            val event = JSONObject(row.getJSONObject("event").toString())
            val hash = row.getString("eventHash")
            require(RiftMemoryModelV1.canonicalSha256(event) == hash)
            state.events += RiftStoreEventV1(row.getLong("sequence"), event, hash)
        }
        val snapshots = payload.optJSONObject("snapshots") ?: JSONObject()
        snapshots.keys().asSequence().sorted().forEach { id ->
            val row = snapshots.getJSONObject(id)
            state.snapshots[id] = RiftMemorySnapshotMetadataV1(
                id = row.getString("id"),
                createdAt = row.getLong("createdAt"),
                eventSequence = row.getLong("eventSequence"),
                canonicalHash = row.getString("canonicalHash"),
                metadata = JSONObject(row.getJSONObject("metadata").toString())
            )
        }
        val dirty = payload.optJSONObject("dirtyProjections") ?: JSONObject()
        dirty.keys().asSequence().sorted().forEach { name ->
            val row = dirty.getJSONObject(name)
            state.dirtyProjections[name] = RiftMemoryDirtyProjectionV1(
                name = row.getString("name"),
                reason = row.getString("reason"),
                updatedAt = row.getLong("updatedAt")
            )
        }
        return state
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

private class RiftStoreMemoryStoreHandleV1(
    private val file: AtomicFile,
    initialState: RiftStoreStateV1
) : RiftMemoryStoreHandleV1 {
    private val lock = ReentrantLock(true)
    private var state = initialState
    private var pending: RiftStorePendingV1? = null
    private var closed = false

    override fun beginTransaction(metadata: JSONObject): RiftMemoryTransactionV1 = lock.withLock {
        requireOpen()
        check(pending == null) { "MemoryStore allows one canonical transaction per handle." }
        val transaction = RiftMemoryTransactionV1(
            id = "tx-" + UUID.randomUUID().toString(),
            metadata = JSONObject(metadata.toString())
        )
        pending = RiftStorePendingV1(transaction)
        transaction
    }

    override fun putContentBlob(
        transaction: RiftMemoryTransactionV1,
        bytes: ByteArray,
        metadata: JSONObject
    ): String = lock.withLock {
        val active = requireActive(transaction)
        require(bytes.size <= 8_388_608) { "Single memory content blob exceeds 8 MiB bound." }
        val hash = sha256(bytes)
        val existing = active.blobs[hash] ?: state.blobs[hash]
        if (existing != null) {
            check(existing.bytes.contentEquals(bytes)) { "Content-address collision or corrupted existing blob: $hash" }
            return@withLock hash
        }
        active.blobs[hash] = RiftStoreBlobV1(
            mediaType = metadata.optString("mediaType", "application/octet-stream").take(128),
            bytes = bytes.copyOf()
        )
        hash
    }

    override fun appendEvidence(
        transaction: RiftMemoryTransactionV1,
        evidence: RiftMemoryEvidenceV1
    ): String = lock.withLock {
        val active = requireActive(transaction)
        check(active.blobs.containsKey(evidence.contentSha256) || state.blobs.containsKey(evidence.contentSha256)) {
            "Evidence references missing content blob ${evidence.contentSha256}"
        }
        check(!active.evidence.containsKey(evidence.id) && !state.evidence.containsKey(evidence.id)) {
            "Evidence already exists: ${evidence.id}"
        }
        active.evidence[evidence.id] = RiftMemoryEvidenceV1.fromJson(evidence.toJson())
        evidence.id
    }

    override fun putCanonicalRecord(
        transaction: RiftMemoryTransactionV1,
        record: RiftCanonicalMemoryRecordV1
    ): String = lock.withLock {
        val active = requireActive(transaction)
        record.evidenceRefs.forEach { ref ->
            check(active.evidence.containsKey(ref) || state.evidence.containsKey(ref)) {
                "Canonical record ${record.id} references missing evidence $ref"
            }
        }
        val copy = RiftCanonicalMemoryRecordV1.fromJson(record.toJson())
        val recordHash = RiftMemoryModelV1.canonicalSha256(copy.toJson())
        val identity = JSONObject()
            .put("recordId", copy.id)
            .put("recordHash", recordHash)
            .put("transactionId", transaction.id)
            .put("recordedAt", copy.time.recordedAt)
        val versionId = "rv-" + RiftPatchManifestV1.sha256Canonical(identity).take(40)
        check(state.recordVersions.none { it.versionId == versionId } && active.recordVersions.none { it.versionId == versionId }) {
            "Record version already exists: $versionId"
        }
        active.recordVersions += RiftStoreRecordVersionV1(versionId, copy, recordHash)
        active.currentVersions[copy.id] = versionId
        copy.id
    }

    override fun appendEvent(
        transaction: RiftMemoryTransactionV1,
        event: RiftMemoryEventV1
    ): String = lock.withLock {
        val active = requireActive(transaction)
        val existingIds = state.events.asSequence().map { it.event.getString("id") }
        val pendingIds = active.events.asSequence().map { it.event.getString("id") }
        check(event.id !in existingIds && event.id !in pendingIds) { "Event already exists: ${event.id}" }
        val previous = active.events.lastOrNull()?.eventHash ?: state.events.lastOrNull()?.eventHash
        val json = event.toJson(previous)
        val hash = RiftMemoryModelV1.canonicalSha256(json)
        val sequence = (active.events.lastOrNull()?.sequence ?: state.events.lastOrNull()?.sequence ?: 0L) + 1L
        active.events += RiftStoreEventV1(sequence, json, hash)
        event.id
    }

    override fun commitTransaction(transaction: RiftMemoryTransactionV1): RiftMemoryCommitResultV1 = lock.withLock {
        val active = requireActive(transaction)
        val next = RiftStoreCodecV1.cloneState(state)
        active.blobs.forEach { (hash, blob) -> next.blobs[hash] = RiftStoreBlobV1(blob.mediaType, blob.bytes.copyOf()) }
        active.evidence.forEach { (id, evidence) -> next.evidence[id] = RiftMemoryEvidenceV1.fromJson(evidence.toJson()) }
        active.recordVersions.forEach { version ->
            next.recordVersions += RiftStoreRecordVersionV1(
                version.versionId,
                RiftCanonicalMemoryRecordV1.fromJson(version.record.toJson()),
                version.recordHash
            )
        }
        active.currentVersions.forEach { (id, versionId) -> next.currentVersions[id] = versionId }
        active.events.forEach { event ->
            next.events += RiftStoreEventV1(event.sequence, JSONObject(event.event.toString()), event.eventHash)
        }
        RiftStoreCodecV1.write(file, next)
        state = next
        pending = null
        RiftMemoryCommitResultV1(
            transactionId = transaction.id,
            committedAt = System.currentTimeMillis(),
            eventSequence = state.events.lastOrNull()?.sequence ?: 0L
        )
    }

    override fun rollbackTransaction(transaction: RiftMemoryTransactionV1): RiftMemoryRollbackResultV1 = lock.withLock {
        requireActive(transaction)
        pending = null
        RiftMemoryRollbackResultV1(transaction.id, true)
    }

    override fun getCanonicalRecord(id: String): RiftCanonicalMemoryRecordV1? = lock.withLock {
        requireOpen()
        RiftMemoryModelV1.requireId(id)
        val versionId = state.currentVersions[id] ?: return@withLock null
        val version = state.recordVersions.lastOrNull { it.versionId == versionId } ?: return@withLock null
        RiftCanonicalMemoryRecordV1.fromJson(version.record.toJson())
    }

    override fun scanCanonicalRecords(
        query: RiftMemoryQueryV1,
        bounds: RiftMemoryBoundsV1
    ): RiftMemoryRecordPageV1 = lock.withLock {
        requireOpen()
        val records = state.currentVersions.entries.asSequence()
            .mapNotNull { (_, versionId) -> state.recordVersions.lastOrNull { it.versionId == versionId }?.record }
            .filter { record ->
                (query.id == null || record.id == query.id) &&
                    (query.namespace == null || record.scope.namespace == query.namespace) &&
                    (query.projectId == null || record.scope.projectId == query.projectId) &&
                    (query.kind == null || record.kind == query.kind) &&
                    (query.branch == null || record.branch == query.branch)
            }
            .sortedBy { it.id }
            .drop(bounds.offset)
            .take(bounds.limit)
            .map { RiftCanonicalMemoryRecordV1.fromJson(it.toJson()) }
            .toList()
        RiftMemoryRecordPageV1(records, bounds.offset, bounds.limit)
    }

    override fun readEvidence(
        query: RiftMemoryQueryV1,
        bounds: RiftMemoryBoundsV1
    ): RiftMemoryEvidencePageV1 = lock.withLock {
        requireOpen()
        val rows = state.evidence.values.asSequence()
            .filter { evidence ->
                (query.evidenceId == null || evidence.id == query.evidenceId) &&
                    (query.namespace == null || evidence.scope.namespace == query.namespace) &&
                    (query.projectId == null || evidence.scope.projectId == query.projectId) &&
                    (query.branch == null || evidence.branch == query.branch)
            }
            .sortedWith(compareBy<RiftMemoryEvidenceV1> { it.recordedAt }.thenBy { it.id })
            .drop(bounds.offset)
            .take(bounds.limit)
            .map { RiftMemoryEvidenceV1.fromJson(it.toJson()) }
            .toList()
        RiftMemoryEvidencePageV1(rows, bounds.offset, bounds.limit)
    }

    override fun readEvents(
        query: RiftMemoryQueryV1,
        bounds: RiftMemoryBoundsV1
    ): RiftMemoryEventPageV1 = lock.withLock {
        requireOpen()
        val rows = state.events.asSequence()
            .filter { row ->
                val event = row.event
                val scope = event.getJSONObject("scope")
                val recordId = event.opt("recordId").takeUnless { it == null || it == JSONObject.NULL }?.toString()
                val evidenceId = event.opt("evidenceId").takeUnless { it == null || it == JSONObject.NULL }?.toString()
                (query.recordId == null || recordId == query.recordId) &&
                    (query.evidenceId == null || evidenceId == query.evidenceId) &&
                    (query.namespace == null || scope.getString("namespace") == query.namespace) &&
                    (query.projectId == null || scope.opt("projectId").takeUnless { it == null || it == JSONObject.NULL }?.toString() == query.projectId) &&
                    (query.branch == null || event.getString("branch") == query.branch.name)
            }
            .sortedBy { it.sequence }
            .drop(bounds.offset)
            .take(bounds.limit)
            .map { RiftMemoryEventRowV1(it.sequence, JSONObject(it.event.toString()), it.eventHash) }
            .toList()
        RiftMemoryEventPageV1(rows, bounds.offset, bounds.limit)
    }

    override fun getContentBlob(
        hash: String,
        bounds: RiftMemoryBoundsV1
    ): RiftMemoryContentBlobV1? = lock.withLock {
        requireOpen()
        require(RiftMemoryModelV1.SHA256.matches(hash))
        val blob = state.blobs[hash] ?: return@withLock null
        check(blob.bytes.size <= bounds.maxBlobBytes) { "Requested blob exceeds bounded read limit." }
        check(sha256(blob.bytes) == hash) { "Content-addressed blob integrity failure: $hash" }
        RiftMemoryContentBlobV1(hash, blob.mediaType, blob.bytes.copyOf())
    }

    override fun createSnapshot(scope: RiftMemoryQueryV1): RiftMemorySnapshotMetadataV1 = lock.withLock {
        requireOpen()
        val records = scanCanonicalRecords(scope, RiftMemoryBoundsV1(limit = 1_000)).records
        val total = currentRecords(scope).count()
        check(total <= 1_000) { "Snapshot scope exceeds bounded N2.2 baseline of 1000 current records." }
        val eventSequence = state.events.lastOrNull()?.sequence ?: 0L
        val payload = JSONObject()
            .put("schema", "rift-memory-snapshot-v1")
            .put("eventSequence", eventSequence)
            .put("records", JSONArray().apply { records.forEach { put(it.toJson()) } })
        val hash = RiftPatchManifestV1.sha256Canonical(payload)
        val createdAt = System.currentTimeMillis()
        val id = "snapshot-" + hash.take(32)
        val metadata = JSONObject()
            .put("recordCount", records.size)
            .put("scopeNamespace", scope.namespace ?: JSONObject.NULL)
            .put("scopeProjectId", scope.projectId ?: JSONObject.NULL)
        val snapshot = RiftMemorySnapshotMetadataV1(id, createdAt, eventSequence, hash, metadata)
        val next = RiftStoreCodecV1.cloneState(state)
        next.snapshots[id] = snapshot
        RiftStoreCodecV1.write(file, next)
        state = next
        RiftMemorySnapshotMetadataV1(id, createdAt, eventSequence, hash, JSONObject(metadata.toString()))
    }

    override fun verifyIntegrity(
        scope: RiftMemoryQueryV1,
        bounds: RiftMemoryBoundsV1
    ): RiftMemoryIntegrityReportV1 = lock.withLock {
        requireOpen()
        val findings = mutableListOf<String>()
        val blobCount = state.blobs.size
        val evidenceCount = state.evidence.size
        val recordCount = state.recordVersions.size
        val eventCount = state.events.size
        for ((name, count) in listOf(
            "blobs" to blobCount,
            "evidence" to evidenceCount,
            "record_versions" to recordCount,
            "events" to eventCount
        )) {
            if (count > bounds.limit) findings += "integrity-bound-exceeded:$name:$count>${bounds.limit}"
        }

        var blobsChecked = 0
        state.blobs.keys.sorted().take(bounds.limit).forEach { hash ->
            blobsChecked += 1
            val blob = state.blobs.getValue(hash)
            if (sha256(blob.bytes) != hash) findings += "blob-hash:$hash"
        }

        var evidenceChecked = 0
        state.evidence.keys.sorted().take(bounds.limit).forEach { id ->
            evidenceChecked += 1
            val evidence = state.evidence.getValue(id)
            if (!state.blobs.containsKey(evidence.contentSha256)) findings += "evidence-missing-blob:$id"
            runCatching { RiftMemoryEvidenceV1.fromJson(evidence.toJson()) }
                .onFailure { findings += "evidence-schema:$id" }
        }

        var recordsChecked = 0
        state.recordVersions.sortedBy { it.versionId }.take(bounds.limit).forEach { version ->
            recordsChecked += 1
            if (RiftMemoryModelV1.canonicalSha256(version.record.toJson()) != version.recordHash) {
                findings += "record-hash:${version.versionId}"
            }
            version.record.evidenceRefs.forEach { ref ->
                if (!state.evidence.containsKey(ref)) findings += "record-missing-evidence:${version.versionId}:$ref"
            }
        }

        var eventsChecked = 0
        var previous: String? = null
        state.events.sortedBy { it.sequence }.take(bounds.limit).forEach { row ->
            eventsChecked += 1
            val previousStored = row.event.opt("previousEventHash").takeUnless { it == null || it == JSONObject.NULL }?.toString()
            if (previousStored != previous) findings += "event-chain:${row.sequence}"
            if (RiftMemoryModelV1.canonicalSha256(row.event) != row.eventHash) findings += "event-hash:${row.sequence}"
            previous = row.eventHash
        }
        state.currentVersions.forEach { (recordId, versionId) ->
            if (state.recordVersions.none { it.versionId == versionId && it.record.id == recordId }) {
                findings += "current-version-missing:$recordId"
            }
        }

        RiftMemoryIntegrityReportV1(
            clean = findings.isEmpty(),
            sqliteIntegrity = "not-applicable",
            blobsChecked = blobsChecked,
            evidenceChecked = evidenceChecked,
            recordsChecked = recordsChecked,
            eventsChecked = eventsChecked,
            findings = findings
        )
    }

    override fun markProjectionDirty(projection: String, reason: String) = lock.withLock {
        requireOpen()
        require(projection.isNotBlank() && projection.length <= 256)
        require(reason.isNotBlank() && reason.length <= 1024)
        val next = RiftStoreCodecV1.cloneState(state)
        next.dirtyProjections[projection] = RiftMemoryDirtyProjectionV1(
            name = projection,
            reason = reason,
            updatedAt = System.currentTimeMillis()
        )
        RiftStoreCodecV1.write(file, next)
        state = next
        Unit
    }

    override fun listDirtyProjections(
        scope: RiftMemoryQueryV1,
        bounds: RiftMemoryBoundsV1
    ): List<RiftMemoryDirtyProjectionV1> = lock.withLock {
        requireOpen()
        state.dirtyProjections.values.asSequence()
            .sortedWith(compareBy<RiftMemoryDirtyProjectionV1> { it.updatedAt }.thenBy { it.name })
            .drop(bounds.offset)
            .take(bounds.limit)
            .toList()
    }

    override fun close() = lock.withLock {
        if (closed) return@withLock
        pending = null
        closed = true
    }

    private fun currentRecords(query: RiftMemoryQueryV1): Sequence<RiftCanonicalMemoryRecordV1> =
        state.currentVersions.entries.asSequence()
            .mapNotNull { (_, versionId) -> state.recordVersions.lastOrNull { it.versionId == versionId }?.record }
            .filter { record ->
                (query.id == null || record.id == query.id) &&
                    (query.namespace == null || record.scope.namespace == query.namespace) &&
                    (query.projectId == null || record.scope.projectId == query.projectId) &&
                    (query.kind == null || record.kind == query.kind) &&
                    (query.branch == null || record.branch == query.branch)
            }

    private fun requireOpen() {
        check(!closed) { "MemoryStore handle is closed." }
    }

    private fun requireActive(transaction: RiftMemoryTransactionV1): RiftStorePendingV1 {
        requireOpen()
        val active = pending
        check(active != null && active.transaction.id == transaction.id) {
            "Transaction ${transaction.id} is not the active canonical transaction."
        }
        return active
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
