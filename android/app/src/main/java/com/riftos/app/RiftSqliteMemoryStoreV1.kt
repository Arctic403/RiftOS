package com.riftos.app

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class RiftSqliteMemoryStoreV1 : RiftMemoryStoreV1 {
    companion object {
        const val FORMAT = "rift-memory-sqlite-v1"
        const val DATABASE_VERSION = 1
    }

    override fun open(config: RiftMemoryStoreConfigV1): RiftMemoryStoreHandleV1 {
        val file = File(config.databasePath)
        if (!config.createIfMissing) require(file.isFile) {
            "Rift MemoryStore database does not exist: ${config.databasePath}"
        }
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        return try {
            configure(db)
            RiftSqliteMemoryStoreHandleV1(db)
        } catch (failure: Throwable) {
            if (db.isOpen) db.close()
            throw failure
        }
    }

    private fun configure(db: SQLiteDatabase) {
        db.execSQL("PRAGMA journal_mode=DELETE")
        db.execSQL("PRAGMA synchronous=FULL")
        db.execSQL("PRAGMA foreign_keys=ON")
        db.execSQL("PRAGMA temp_store=MEMORY")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS store_meta(" +
                "key TEXT PRIMARY KEY," +
                "value TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS transactions(" +
                "id TEXT PRIMARY KEY," +
                "status TEXT NOT NULL," +
                "started_at INTEGER NOT NULL," +
                "committed_at INTEGER," +
                "metadata_json TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS blobs(" +
                "hash TEXT PRIMARY KEY," +
                "size_bytes INTEGER NOT NULL," +
                "media_type TEXT NOT NULL," +
                "bytes BLOB NOT NULL," +
                "created_at INTEGER NOT NULL," +
                "metadata_json TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS evidence(" +
                "id TEXT PRIMARY KEY," +
                "transaction_id TEXT NOT NULL," +
                "content_hash TEXT NOT NULL," +
                "namespace TEXT NOT NULL," +
                "project_id TEXT," +
                "branch TEXT NOT NULL," +
                "recorded_at INTEGER NOT NULL," +
                "evidence_json TEXT NOT NULL," +
                "evidence_hash TEXT NOT NULL," +
                "FOREIGN KEY(content_hash) REFERENCES blobs(hash))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS evidence_namespace_idx ON evidence(namespace, project_id, recorded_at)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS record_versions(" +
                "version_id TEXT PRIMARY KEY," +
                "record_id TEXT NOT NULL," +
                "transaction_id TEXT NOT NULL," +
                "kind TEXT NOT NULL," +
                "namespace TEXT NOT NULL," +
                "project_id TEXT," +
                "branch TEXT NOT NULL," +
                "trust_state TEXT NOT NULL," +
                "valid_from INTEGER NOT NULL," +
                "valid_to INTEGER," +
                "recorded_at INTEGER NOT NULL," +
                "record_json TEXT NOT NULL," +
                "record_hash TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS record_versions_id_idx ON record_versions(record_id, recorded_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS record_versions_scope_idx ON record_versions(namespace, project_id, kind, branch)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS records_current(" +
                "record_id TEXT PRIMARY KEY," +
                "version_id TEXT NOT NULL UNIQUE," +
                "updated_at INTEGER NOT NULL," +
                "FOREIGN KEY(version_id) REFERENCES record_versions(version_id))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS events(" +
                "seq INTEGER PRIMARY KEY AUTOINCREMENT," +
                "id TEXT NOT NULL UNIQUE," +
                "transaction_id TEXT NOT NULL," +
                "type TEXT NOT NULL," +
                "record_id TEXT," +
                "evidence_id TEXT," +
                "namespace TEXT NOT NULL," +
                "project_id TEXT," +
                "branch TEXT NOT NULL," +
                "at INTEGER NOT NULL," +
                "event_json TEXT NOT NULL," +
                "previous_event_hash TEXT," +
                "event_hash TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS events_record_idx ON events(record_id, seq)")
        db.execSQL("CREATE INDEX IF NOT EXISTS events_scope_idx ON events(namespace, project_id, branch, seq)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS snapshots(" +
                "id TEXT PRIMARY KEY," +
                "created_at INTEGER NOT NULL," +
                "event_sequence INTEGER NOT NULL," +
                "canonical_hash TEXT NOT NULL," +
                "metadata_json TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS dirty_projections(" +
                "name TEXT PRIMARY KEY," +
                "reason TEXT NOT NULL," +
                "updated_at INTEGER NOT NULL)"
        )
        db.execSQL("PRAGMA user_version=$DATABASE_VERSION")
        val meta = ContentValues().apply {
            put("key", "format")
            put("value", FORMAT)
        }
        db.insertWithOnConflict("store_meta", null, meta, SQLiteDatabase.CONFLICT_REPLACE)
    }
}

private class RiftSqliteMemoryStoreHandleV1(
    private val db: SQLiteDatabase
) : RiftMemoryStoreHandleV1 {
    private val lock = ReentrantLock(true)
    private var activeTransactionId: String? = null
    private var closed = false

    override fun beginTransaction(metadata: JSONObject): RiftMemoryTransactionV1 = lock.withLock {
        requireOpen()
        check(activeTransactionId == null) { "MemoryStore allows one canonical transaction per handle." }
        val id = "tx-" + UUID.randomUUID().toString()
        db.beginTransaction()
        try {
            activeTransactionId = id
            val values = ContentValues().apply {
                put("id", id)
                put("status", "OPEN")
                put("started_at", System.currentTimeMillis())
                put("metadata_json", JSONObject(metadata.toString()).toString())
            }
            require(db.insertOrThrow("transactions", null, values) >= 0L)
            RiftMemoryTransactionV1(id, JSONObject(metadata.toString()))
        } catch (failure: Throwable) {
            activeTransactionId = null
            runCatching { db.endTransaction() }
            throw failure
        }
    }

    override fun putContentBlob(
        transaction: RiftMemoryTransactionV1,
        bytes: ByteArray,
        metadata: JSONObject
    ): String = lock.withLock {
        requireActive(transaction)
        require(bytes.size <= 8_388_608) { "Single memory content blob exceeds 8 MiB bound." }
        val hash = sha256(bytes)
        val values = ContentValues().apply {
            put("hash", hash)
            put("size_bytes", bytes.size)
            put("media_type", metadata.optString("mediaType", "application/octet-stream").take(128))
            put("bytes", bytes)
            put("created_at", System.currentTimeMillis())
            put("metadata_json", JSONObject(metadata.toString()).toString())
        }
        db.insertWithOnConflict("blobs", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        val stored = queryBlobBytes(hash) ?: error("Content-addressed blob was not persisted.")
        check(stored.contentEquals(bytes)) { "Content-address collision or corrupted existing blob: $hash" }
        hash
    }

    override fun appendEvidence(
        transaction: RiftMemoryTransactionV1,
        evidence: RiftMemoryEvidenceV1
    ): String = lock.withLock {
        requireActive(transaction)
        check(blobExists(evidence.contentSha256)) {
            "Evidence references missing content blob ${evidence.contentSha256}"
        }
        val json = evidence.toJson()
        val values = ContentValues().apply {
            put("id", evidence.id)
            put("transaction_id", transaction.id)
            put("content_hash", evidence.contentSha256)
            put("namespace", evidence.scope.namespace)
            put("project_id", evidence.scope.projectId)
            put("branch", evidence.branch.name)
            put("recorded_at", evidence.recordedAt)
            put("evidence_json", json.toString())
            put("evidence_hash", RiftMemoryModelV1.canonicalSha256(json))
        }
        db.insertOrThrow("evidence", null, values)
        evidence.id
    }

    override fun putCanonicalRecord(
        transaction: RiftMemoryTransactionV1,
        record: RiftCanonicalMemoryRecordV1
    ): String = lock.withLock {
        requireActive(transaction)
        record.evidenceRefs.forEach { ref ->
            check(evidenceExists(ref)) { "Canonical record ${record.id} references missing evidence $ref" }
        }
        val json = record.toJson()
        val recordHash = RiftMemoryModelV1.canonicalSha256(json)
        val versionIdentity = JSONObject()
            .put("recordId", record.id)
            .put("recordHash", recordHash)
            .put("transactionId", transaction.id)
            .put("recordedAt", record.time.recordedAt)
        val versionId = "rv-" + RiftPatchManifestV1.sha256Canonical(versionIdentity).take(40)
        val version = ContentValues().apply {
            put("version_id", versionId)
            put("record_id", record.id)
            put("transaction_id", transaction.id)
            put("kind", record.kind.name)
            put("namespace", record.scope.namespace)
            put("project_id", record.scope.projectId)
            put("branch", record.branch.name)
            put("trust_state", record.trustState.name)
            put("valid_from", record.time.validFrom)
            if (record.time.validTo == null) putNull("valid_to") else put("valid_to", record.time.validTo)
            put("recorded_at", record.time.recordedAt)
            put("record_json", json.toString())
            put("record_hash", recordHash)
        }
        db.insertOrThrow("record_versions", null, version)
        val current = ContentValues().apply {
            put("record_id", record.id)
            put("version_id", versionId)
            put("updated_at", record.time.recordedAt)
        }
        db.insertWithOnConflict("records_current", null, current, SQLiteDatabase.CONFLICT_REPLACE)
        record.id
    }

    override fun appendEvent(
        transaction: RiftMemoryTransactionV1,
        event: RiftMemoryEventV1
    ): String = lock.withLock {
        requireActive(transaction)
        val previous = lastEventHash()
        val json = event.toJson(previous)
        val eventHash = RiftMemoryModelV1.canonicalSha256(json)
        val values = ContentValues().apply {
            put("id", event.id)
            put("transaction_id", transaction.id)
            put("type", event.type)
            put("record_id", event.recordId)
            put("evidence_id", event.evidenceId)
            put("namespace", event.scope.namespace)
            put("project_id", event.scope.projectId)
            put("branch", event.branch.name)
            put("at", event.at)
            put("event_json", json.toString())
            put("previous_event_hash", previous)
            put("event_hash", eventHash)
        }
        db.insertOrThrow("events", null, values)
        event.id
    }

    override fun commitTransaction(transaction: RiftMemoryTransactionV1): RiftMemoryCommitResultV1 =
        lock.withLock {
            requireActive(transaction)
            val committedAt = System.currentTimeMillis()
            val values = ContentValues().apply {
                put("status", "COMMITTED")
                put("committed_at", committedAt)
            }
            db.update("transactions", values, "id=?", arrayOf(transaction.id))
            val sequence = lastEventSequence()
            try {
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
                activeTransactionId = null
            }
            RiftMemoryCommitResultV1(transaction.id, committedAt, sequence)
        }

    override fun rollbackTransaction(transaction: RiftMemoryTransactionV1): RiftMemoryRollbackResultV1 =
        lock.withLock {
            requireActive(transaction)
            db.endTransaction()
            activeTransactionId = null
            RiftMemoryRollbackResultV1(transaction.id, true)
        }

    override fun getCanonicalRecord(id: String): RiftCanonicalMemoryRecordV1? = lock.withLock {
        requireOpen()
        RiftMemoryModelV1.requireId(id)
        db.rawQuery(
            "SELECT v.record_json FROM records_current c " +
                "JOIN record_versions v ON v.version_id=c.version_id WHERE c.record_id=?",
            arrayOf(id)
        ).use { cursor ->
            if (!cursor.moveToFirst()) null
            else RiftCanonicalMemoryRecordV1.fromJson(JSONObject(cursor.getString(0)))
        }
    }

    override fun scanCanonicalRecords(
        query: RiftMemoryQueryV1,
        bounds: RiftMemoryBoundsV1
    ): RiftMemoryRecordPageV1 = lock.withLock {
        requireOpen()
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        query.id?.let { where += "v.record_id=?"; args += it }
        query.namespace?.let { where += "v.namespace=?"; args += it }
        query.projectId?.let { where += "v.project_id=?"; args += it }
        query.kind?.let { where += "v.kind=?"; args += it.name }
        query.branch?.let { where += "v.branch=?"; args += it.name }
        val sql = buildString {
            append("SELECT v.record_json FROM records_current c JOIN record_versions v ON v.version_id=c.version_id")
            if (where.isNotEmpty()) append(" WHERE ").append(where.joinToString(" AND "))
            append(" ORDER BY v.record_id LIMIT ? OFFSET ?")
        }
        args += bounds.limit.toString()
        args += bounds.offset.toString()
        val rows = mutableListOf<RiftCanonicalMemoryRecordV1>()
        db.rawQuery(sql, args.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                rows += RiftCanonicalMemoryRecordV1.fromJson(JSONObject(cursor.getString(0)))
            }
        }
        RiftMemoryRecordPageV1(rows, bounds.offset, bounds.limit)
    }

    override fun readEvidence(
        query: RiftMemoryQueryV1,
        bounds: RiftMemoryBoundsV1
    ): RiftMemoryEvidencePageV1 = lock.withLock {
        requireOpen()
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        query.evidenceId?.let { where += "id=?"; args += it }
        query.namespace?.let { where += "namespace=?"; args += it }
        query.projectId?.let { where += "project_id=?"; args += it }
        query.branch?.let { where += "branch=?"; args += it.name }
        val sql = buildString {
            append("SELECT evidence_json FROM evidence")
            if (where.isNotEmpty()) append(" WHERE ").append(where.joinToString(" AND "))
            append(" ORDER BY recorded_at,id LIMIT ? OFFSET ?")
        }
        args += bounds.limit.toString()
        args += bounds.offset.toString()
        val rows = mutableListOf<RiftMemoryEvidenceV1>()
        db.rawQuery(sql, args.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                rows += RiftMemoryEvidenceV1.fromJson(JSONObject(cursor.getString(0)))
            }
        }
        RiftMemoryEvidencePageV1(rows, bounds.offset, bounds.limit)
    }

    override fun readEvents(
        query: RiftMemoryQueryV1,
        bounds: RiftMemoryBoundsV1
    ): RiftMemoryEventPageV1 = lock.withLock {
        requireOpen()
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        query.recordId?.let { where += "record_id=?"; args += it }
        query.evidenceId?.let { where += "evidence_id=?"; args += it }
        query.namespace?.let { where += "namespace=?"; args += it }
        query.projectId?.let { where += "project_id=?"; args += it }
        query.branch?.let { where += "branch=?"; args += it.name }
        val sql = buildString {
            append("SELECT seq,event_json,event_hash FROM events")
            if (where.isNotEmpty()) append(" WHERE ").append(where.joinToString(" AND "))
            append(" ORDER BY seq LIMIT ? OFFSET ?")
        }
        args += bounds.limit.toString()
        args += bounds.offset.toString()
        val rows = mutableListOf<RiftMemoryEventRowV1>()
        db.rawQuery(sql, args.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                rows += RiftMemoryEventRowV1(
                    sequence = cursor.getLong(0),
                    event = JSONObject(cursor.getString(1)),
                    eventHash = cursor.getString(2)
                )
            }
        }
        RiftMemoryEventPageV1(rows, bounds.offset, bounds.limit)
    }

    override fun getContentBlob(
        hash: String,
        bounds: RiftMemoryBoundsV1
    ): RiftMemoryContentBlobV1? = lock.withLock {
        requireOpen()
        require(RiftMemoryModelV1.SHA256.matches(hash))
        db.rawQuery("SELECT media_type,bytes,size_bytes FROM blobs WHERE hash=?", arrayOf(hash)).use { cursor ->
            if (!cursor.moveToFirst()) return@withLock null
            val size = cursor.getInt(2)
            check(size <= bounds.maxBlobBytes) { "Requested blob exceeds bounded read limit." }
            val bytes = cursor.getBlob(1)
            check(bytes.size == size && sha256(bytes) == hash) { "Content-addressed blob integrity failure: $hash" }
            RiftMemoryContentBlobV1(hash, cursor.getString(0), bytes)
        }
    }

    override fun createSnapshot(scope: RiftMemoryQueryV1): RiftMemorySnapshotMetadataV1 =
        lock.withLock {
            requireOpen()
            val page = scanCanonicalRecords(scope, RiftMemoryBoundsV1(limit = 1_000))
            val total = countCurrent(scope)
            check(total <= 1_000) { "Snapshot scope exceeds bounded N2.2 baseline of 1000 current records." }
            val eventSequence = lastEventSequence()
            val payload = JSONObject()
                .put("schema", "rift-memory-snapshot-v1")
                .put("eventSequence", eventSequence)
                .put("records", JSONArray().apply { page.records.forEach { put(it.toJson()) } })
            val hash = RiftPatchManifestV1.sha256Canonical(payload)
            val createdAt = System.currentTimeMillis()
            val id = "snapshot-" + hash.take(32)
            val metadata = JSONObject()
                .put("recordCount", page.records.size)
                .put("scopeNamespace", scope.namespace ?: JSONObject.NULL)
                .put("scopeProjectId", scope.projectId ?: JSONObject.NULL)
            val values = ContentValues().apply {
                put("id", id)
                put("created_at", createdAt)
                put("event_sequence", eventSequence)
                put("canonical_hash", hash)
                put("metadata_json", metadata.toString())
            }
            db.insertWithOnConflict("snapshots", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            RiftMemorySnapshotMetadataV1(id, createdAt, eventSequence, hash, metadata)
        }

    override fun verifyIntegrity(
        scope: RiftMemoryQueryV1,
        bounds: RiftMemoryBoundsV1
    ): RiftMemoryIntegrityReportV1 = lock.withLock {
        requireOpen()
        val findings = mutableListOf<String>()
        val sqliteIntegrity = db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else "missing-result"
        }
        if (sqliteIntegrity != "ok") findings += "sqlite-integrity:$sqliteIntegrity"

        val blobCount = tableCount("blobs")
        val evidenceCount = tableCount("evidence")
        val recordCount = tableCount("record_versions")
        val eventCount = tableCount("events")
        for ((name, count) in listOf(
            "blobs" to blobCount,
            "evidence" to evidenceCount,
            "record_versions" to recordCount,
            "events" to eventCount
        )) {
            if (count > bounds.limit) findings += "integrity-bound-exceeded:$name:$count>${bounds.limit}"
        }

        var blobsChecked = 0
        db.rawQuery("SELECT hash,bytes,size_bytes FROM blobs ORDER BY hash LIMIT ?", arrayOf(bounds.limit.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                blobsChecked += 1
                val hash = cursor.getString(0)
                val bytes = cursor.getBlob(1)
                val size = cursor.getInt(2)
                if (bytes.size != size || sha256(bytes) != hash) findings += "blob-hash:$hash"
            }
        }

        var evidenceChecked = 0
        db.rawQuery(
            "SELECT id,content_hash,evidence_json,evidence_hash FROM evidence ORDER BY id LIMIT ?",
            arrayOf(bounds.limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                evidenceChecked += 1
                val id = cursor.getString(0)
                val contentHash = cursor.getString(1)
                val json = JSONObject(cursor.getString(2))
                val expectedHash = cursor.getString(3)
                if (!blobExists(contentHash)) findings += "evidence-missing-blob:$id"
                if (RiftMemoryModelV1.canonicalSha256(json) != expectedHash) findings += "evidence-hash:$id"
                runCatching { RiftMemoryEvidenceV1.fromJson(json) }
                    .onFailure { findings += "evidence-schema:$id" }
            }
        }

        var recordsChecked = 0
        db.rawQuery(
            "SELECT version_id,record_json,record_hash FROM record_versions ORDER BY version_id LIMIT ?",
            arrayOf(bounds.limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                recordsChecked += 1
                val versionId = cursor.getString(0)
                val json = JSONObject(cursor.getString(1))
                val expectedHash = cursor.getString(2)
                if (RiftMemoryModelV1.canonicalSha256(json) != expectedHash) findings += "record-hash:$versionId"
                runCatching { RiftCanonicalMemoryRecordV1.fromJson(json) }
                    .onSuccess { record ->
                        record.evidenceRefs.forEach { ref ->
                            if (!evidenceExists(ref)) findings += "record-missing-evidence:$versionId:$ref"
                        }
                    }
                    .onFailure { findings += "record-schema:$versionId" }
            }
        }

        var eventsChecked = 0
        var previous: String? = null
        db.rawQuery(
            "SELECT seq,event_json,previous_event_hash,event_hash FROM events ORDER BY seq LIMIT ?",
            arrayOf(bounds.limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                eventsChecked += 1
                val seq = cursor.getLong(0)
                val json = JSONObject(cursor.getString(1))
                val previousStored = cursor.getStringOrNull(2)
                val expectedHash = cursor.getString(3)
                if (previousStored != previous) findings += "event-chain:$seq"
                val actualHash = RiftMemoryModelV1.canonicalSha256(json)
                if (actualHash != expectedHash) findings += "event-hash:$seq"
                val jsonPrevious = json.opt("previousEventHash").takeUnless { it == null || it == JSONObject.NULL }?.toString()
                if (jsonPrevious != previousStored) findings += "event-json-chain:$seq"
                previous = expectedHash
            }
        }

        RiftMemoryIntegrityReportV1(
            clean = findings.isEmpty(),
            sqliteIntegrity = sqliteIntegrity,
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
        val values = ContentValues().apply {
            put("name", projection)
            put("reason", reason)
            put("updated_at", System.currentTimeMillis())
        }
        db.insertWithOnConflict("dirty_projections", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        Unit
    }

    override fun listDirtyProjections(
        scope: RiftMemoryQueryV1,
        bounds: RiftMemoryBoundsV1
    ): List<RiftMemoryDirtyProjectionV1> = lock.withLock {
        requireOpen()
        val rows = mutableListOf<RiftMemoryDirtyProjectionV1>()
        db.rawQuery(
            "SELECT name,reason,updated_at FROM dirty_projections ORDER BY updated_at,name LIMIT ? OFFSET ?",
            arrayOf(bounds.limit.toString(), bounds.offset.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += RiftMemoryDirtyProjectionV1(cursor.getString(0), cursor.getString(1), cursor.getLong(2))
            }
        }
        rows
    }

    override fun close() = lock.withLock {
        if (closed) return@withLock
        if (db.isOpen && db.inTransaction()) {
            runCatching { db.endTransaction() }
        }
        activeTransactionId = null
        if (db.isOpen) db.close()
        closed = true
    }

    private fun requireOpen() {
        check(!closed && db.isOpen) { "MemoryStore handle is closed." }
    }

    private fun requireActive(transaction: RiftMemoryTransactionV1) {
        requireOpen()
        check(activeTransactionId == transaction.id && db.inTransaction()) {
            "Transaction ${transaction.id} is not the active canonical transaction."
        }
    }

    private fun blobExists(hash: String): Boolean =
        db.rawQuery("SELECT 1 FROM blobs WHERE hash=? LIMIT 1", arrayOf(hash)).use { it.moveToFirst() }

    private fun evidenceExists(id: String): Boolean =
        db.rawQuery("SELECT 1 FROM evidence WHERE id=? LIMIT 1", arrayOf(id)).use { it.moveToFirst() }

    private fun queryBlobBytes(hash: String): ByteArray? =
        db.rawQuery("SELECT bytes FROM blobs WHERE hash=?", arrayOf(hash)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getBlob(0) else null
        }

    private fun lastEventHash(): String? =
        db.rawQuery("SELECT event_hash FROM events ORDER BY seq DESC LIMIT 1", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun lastEventSequence(): Long =
        db.rawQuery("SELECT COALESCE(MAX(seq),0) FROM events", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }

    private fun countCurrent(scope: RiftMemoryQueryV1): Int {
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        scope.namespace?.let { where += "v.namespace=?"; args += it }
        scope.projectId?.let { where += "v.project_id=?"; args += it }
        val sql = buildString {
            append("SELECT COUNT(*) FROM records_current c JOIN record_versions v ON v.version_id=c.version_id")
            if (where.isNotEmpty()) append(" WHERE ").append(where.joinToString(" AND "))
        }
        return db.rawQuery(sql, args.toTypedArray()).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun tableCount(table: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun Cursor.getStringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)
}
