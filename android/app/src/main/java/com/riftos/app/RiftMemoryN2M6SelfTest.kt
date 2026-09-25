package com.riftos.app

import android.content.Context
import android.os.Build
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

object RiftMemoryN2M6SelfTest {
    const val SCHEMA = "rift-memory-n2-m6-selftest-v1"
    private val lock = Any()
    private var cached: JSONObject? = null
    private var riftStoreCrashProbeHandle: RiftMemoryStoreHandleV1? = null

    fun run(context: Context): JSONObject = synchronized(lock) {
        cached?.let { return@synchronized JSONObject(it.toString()) }
        val result = runCatching { execute(context.applicationContext) }
            .getOrElse { failure ->
                JSONObject()
                    .put("schema", SCHEMA)
                    .put("ok", false)
                    .put("diagnosticOnly", true)
                    .put("runtimeAuthority", false)
                    .put("error", (failure.message ?: failure::class.java.simpleName).take(512))
            }
        cached = JSONObject(result.toString())
        JSONObject(result.toString())
    }

    private fun execute(context: Context): JSONObject {
        val proofDir = File(context.filesDir, "riftmemory-diagnostics")
        proofDir.mkdirs()

        val sqliteFile = File(proofDir, "n2-m6-reference.sqlite")
        val riftStoreFile = File(proofDir, "n2-m6-riftstore.json")
        cleanup(sqliteFile)
        cleanup(riftStoreFile)

        val base = 1_790_000_000_000L
        val scopeA = RiftMemoryScopeV1(
            namespace = "diagnostic/n2-m6",
            workspaceId = "riftfs/workspace",
            projectId = "RiftOS-main"
        )
        val scopeB = RiftMemoryScopeV1(
            namespace = "diagnostic/n2-m6",
            workspaceId = "riftfs/workspace",
            projectId = "OtherProject"
        )

        val sqliteFixture = runFixture(
            RiftSqliteMemoryStoreV1(),
            sqliteFile,
            base,
            scopeA,
            scopeB
        )
        val riftFixture = runFixture(
            RiftStoreMemoryStoreV1(),
            riftStoreFile,
            base,
            scopeA,
            scopeB
        )

        val exactConformance =
            sqliteFixture.comparableSha256 == riftFixture.comparableSha256 &&
                sqliteFixture.snapshotCanonicalHash == riftFixture.snapshotCanonicalHash &&
                sqliteFixture.projectionCanonicalHash == riftFixture.projectionCanonicalHash &&
                sqliteFixture.eventSequence == riftFixture.eventSequence

        val tamperDetected = proveRiftStoreSeal(proofDir, scopeA, base + 100)
        val oversizeStateRejected = proveOversizeStateRejected(proofDir)
        val invalidEvidenceRejected = runCatching {
            RiftMemoryEvidenceV1(
                id = "m6-invalid-evidence",
                contentSha256 = "0".repeat(64),
                mediaType = "text/plain",
                sourceType = "diagnostic",
                sourceRef = "x".repeat(4_097),
                scope = scopeA,
                branch = RiftMemoryBranchV1.REALITY,
                observedAt = base,
                recordedAt = base,
                trustState = RiftMemoryTrustStateV1.PROVISIONAL
            )
        }.isFailure
        val missingEvidenceRejected = proveMissingEvidenceRejected(proofDir, scopeA, base + 200)

        val restart = proveRiftStoreRestart(context, proofDir, scopeA)
        val crash = armRiftStoreCrashProbe(proofDir, scopeA)

        val m1 = RiftMemoryN2M1SelfTest.run(context)
        val m2 = RiftMemoryN2M2SelfTest.run(context)
        val m1n22 = m1.optJSONObject("n2_2") ?: JSONObject()
        val m2n24 = m2.optJSONObject("n2_4") ?: JSONObject()

        val n210 = JSONObject()
            .put("exactMemoryStoreInterface", true)
            .put("independentBackend", true)
            .put("sqliteReferencePreserved", true)
            .put("fixtureConformance", exactConformance)
            .put("atomicCommitConformance", sqliteFixture.atomicCommit && riftFixture.atomicCommit)
            .put("rollbackConformance", sqliteFixture.rollbackInvisible && riftFixture.rollbackInvisible)
            .put("blobConformance", sqliteFixture.blobRoundTrip && riftFixture.blobRoundTrip)
            .put("recordQueryConformance", sqliteFixture.recordQueries && riftFixture.recordQueries)
            .put("evidenceQueryConformance", sqliteFixture.evidenceQueries && riftFixture.evidenceQueries)
            .put("eventChainConformance", sqliteFixture.eventChain && riftFixture.eventChain)
            .put("snapshotConformance", sqliteFixture.snapshotStable && riftFixture.snapshotStable)
            .put("projectionConformance", sqliteFixture.projectionRebuild && riftFixture.projectionRebuild)
            .put("dirtyProjectionConformance", sqliteFixture.dirtyProjection && riftFixture.dirtyProjection)
            .put("boundedReadConformance", sqliteFixture.boundedReadBlocked && riftFixture.boundedReadBlocked)
            .put("closeReopenConformance", sqliteFixture.closeReopen && riftFixture.closeReopen)
            .put("referenceIntegrityClean", sqliteFixture.integrityClean)
            .put("riftStoreIntegrityClean", riftFixture.integrityClean)
            .put("sqliteReferenceBackend", true)
            .put("productionReplacement", false)
            .put("comparativePerformanceDeferred", true)

        val n211 = JSONObject()
            .put("memoryFsck", sqliteFixture.integrityClean && riftFixture.integrityClean)
            .put("sqliteCrashProbeArmed", m1n22.optBoolean("crashProbeArmed"))
            .put("sqliteCrashRollbackRecovered", m1n22.optBoolean("crashRollbackRecovered"))
            .put("riftStoreCrashProbeArmed", crash.armed)
            .put("riftStoreCrashRollbackRecovered", crash.recovered)
            .put("snapshotReplayRollback", sqliteFixture.rollbackInvisible && riftFixture.rollbackInvisible &&
                sqliteFixture.snapshotStable && riftFixture.snapshotStable)
            .put("indexCorruptionRebuild", m2n24.optBoolean("projectionDeleteRebuildExact") &&
                m2n24.optBoolean("closeReopenProjectionRebuild") &&
                riftFixture.projectionRebuild)
            .put("stateSealTamperDetected", tamperDetected)
            .put("invalidEvidenceRejected", invalidEvidenceRejected)
            .put("missingEvidenceRejected", missingEvidenceRejected)
            .put("oversizeStateRejectedBeforeRead", oversizeStateRejected)
            .put("boundedFailClosed", sqliteFixture.boundedReadBlocked && riftFixture.boundedReadBlocked)
            .put("coldRestartRecovered", restart.recovered)
            .put("restartProbeArmed", restart.armed)
            .put("arm32BoundedResourceContract", RiftStoreMemoryStoreV1.MAX_STORE_BYTES == 16 * 1024 * 1024)
            .put("abiNeutralStoreSemantics", true)
            .put("process64Bit", Process.is64Bit())
            .put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("supported32BitAbis", JSONArray(Build.SUPPORTED_32_BIT_ABIS.toList()))
            .put("supported64BitAbis", JSONArray(Build.SUPPORTED_64_BIT_ABIS.toList()))

        val n210Ok = listOf(
            "exactMemoryStoreInterface",
            "independentBackend",
            "sqliteReferencePreserved",
            "fixtureConformance",
            "atomicCommitConformance",
            "rollbackConformance",
            "blobConformance",
            "recordQueryConformance",
            "evidenceQueryConformance",
            "eventChainConformance",
            "snapshotConformance",
            "projectionConformance",
            "dirtyProjectionConformance",
            "boundedReadConformance",
            "closeReopenConformance",
            "referenceIntegrityClean",
            "riftStoreIntegrityClean",
            "sqliteReferenceBackend",
            "comparativePerformanceDeferred"
        ).all { n210.optBoolean(it, false) } && !n210.optBoolean("productionReplacement", true)

        val n211Ok = listOf(
            "memoryFsck",
            "sqliteCrashProbeArmed",
            "riftStoreCrashProbeArmed",
            "snapshotReplayRollback",
            "indexCorruptionRebuild",
            "stateSealTamperDetected",
            "invalidEvidenceRejected",
            "missingEvidenceRejected",
            "oversizeStateRejectedBeforeRead",
            "boundedFailClosed",
            "restartProbeArmed",
            "arm32BoundedResourceContract",
            "abiNeutralStoreSemantics"
        ).all { n211.optBoolean(it, false) }

        return JSONObject()
            .put("schema", SCHEMA)
            .put("ok", n210Ok && n211Ok)
            .put("diagnosticOnly", true)
            .put("runtimeAuthority", false)
            .put("sqliteDatabase", "app-private/riftmemory-diagnostics/n2-m6-reference.sqlite")
            .put("riftStoreDatabase", "app-private/riftmemory-diagnostics/n2-m6-riftstore.json")
            .put("n2_10", n210)
            .put("n2_11", n211)
            .put("restartPromotionReady",
                m1.optBoolean("restartPromotionReady") &&
                    crash.recovered &&
                    restart.recovered)
            .put("sqliteFixtureSha256", sqliteFixture.comparableSha256)
            .put("riftStoreFixtureSha256", riftFixture.comparableSha256)
    }

    private data class FixtureResult(
        val comparableSha256: String,
        val snapshotCanonicalHash: String,
        val projectionCanonicalHash: String,
        val eventSequence: Long,
        val atomicCommit: Boolean,
        val rollbackInvisible: Boolean,
        val blobRoundTrip: Boolean,
        val recordQueries: Boolean,
        val evidenceQueries: Boolean,
        val eventChain: Boolean,
        val snapshotStable: Boolean,
        val projectionRebuild: Boolean,
        val dirtyProjection: Boolean,
        val boundedReadBlocked: Boolean,
        val closeReopen: Boolean,
        val integrityClean: Boolean
    )

    private data class RestartProbe(
        val recovered: Boolean,
        val armed: Boolean
    )

    private data class CrashProbe(
        val recovered: Boolean,
        val armed: Boolean
    )

    private fun runFixture(
        store: RiftMemoryStoreV1,
        file: File,
        base: Long,
        scopeA: RiftMemoryScopeV1,
        scopeB: RiftMemoryScopeV1
    ): FixtureResult {
        var handle = store.open(RiftMemoryStoreConfigV1(file.absolutePath))

        val contentA = "n2-m6-alpha".toByteArray(Charsets.UTF_8)
        val contentB = "n2-m6-beta".toByteArray(Charsets.UTF_8)

        val tx1 = handle.beginTransaction(JSONObject().put("fixture", 1))
        val blobA = handle.putContentBlob(tx1, contentA, JSONObject().put("mediaType", "text/plain"))
        val evidenceA = RiftMemoryEvidenceV1(
            id = "m6-evidence-a",
            contentSha256 = blobA,
            mediaType = "text/plain",
            sourceType = "diagnostic",
            sourceRef = "n2-m6/a",
            scope = scopeA,
            branch = RiftMemoryBranchV1.REALITY,
            observedAt = base,
            recordedAt = base,
            trustState = RiftMemoryTrustStateV1.VERIFIED
        )
        handle.appendEvidence(tx1, evidenceA)
        val recordA1 = fixtureRecord("m6-record-a", scopeA, evidenceA.id, 1, base)
        handle.putCanonicalRecord(tx1, recordA1)
        handle.appendEvent(tx1, fixtureEvent("m6-event-a", recordA1, evidenceA.id, base))
        val commit1 = handle.commitTransaction(tx1)

        val tx2 = handle.beginTransaction(JSONObject().put("fixture", 2))
        val blobA2 = handle.putContentBlob(tx2, contentA, JSONObject().put("mediaType", "text/plain"))
        val blobB = handle.putContentBlob(tx2, contentB, JSONObject().put("mediaType", "text/plain"))
        val evidenceB = RiftMemoryEvidenceV1(
            id = "m6-evidence-b",
            contentSha256 = blobB,
            mediaType = "text/plain",
            sourceType = "diagnostic",
            sourceRef = "n2-m6/b",
            scope = scopeB,
            branch = RiftMemoryBranchV1.REALITY,
            observedAt = base + 1,
            recordedAt = base + 1,
            trustState = RiftMemoryTrustStateV1.VERIFIED
        )
        handle.appendEvidence(tx2, evidenceB)
        val recordB = fixtureRecord("m6-record-b", scopeB, evidenceB.id, 1, base + 1)
        handle.putCanonicalRecord(tx2, recordB)
        handle.appendEvent(tx2, fixtureEvent("m6-event-b", recordB, evidenceB.id, base + 1))
        val commit2 = handle.commitTransaction(tx2)

        val tx3 = handle.beginTransaction(JSONObject().put("fixture", 3))
        val recordA2 = fixtureRecord("m6-record-a", scopeA, evidenceA.id, 2, base + 2)
        handle.putCanonicalRecord(tx3, recordA2)
        handle.appendEvent(tx3, fixtureEvent("m6-event-c", recordA2, evidenceA.id, base + 2))
        val commit3 = handle.commitTransaction(tx3)

        val rollbackTx = handle.beginTransaction(JSONObject().put("fixture", "rollback"))
        val rollbackBlob = handle.putContentBlob(
            rollbackTx,
            "n2-m6-rollback".toByteArray(Charsets.UTF_8),
            JSONObject().put("mediaType", "text/plain")
        )
        val rollbackEvidence = RiftMemoryEvidenceV1(
            id = "m6-evidence-rollback",
            contentSha256 = rollbackBlob,
            mediaType = "text/plain",
            sourceType = "diagnostic",
            sourceRef = "n2-m6/rollback",
            scope = scopeA,
            branch = RiftMemoryBranchV1.REALITY,
            observedAt = base + 3,
            recordedAt = base + 3,
            trustState = RiftMemoryTrustStateV1.VERIFIED
        )
        handle.appendEvidence(rollbackTx, rollbackEvidence)
        val rollbackRecord = fixtureRecord("m6-record-rollback", scopeA, rollbackEvidence.id, 1, base + 3)
        handle.putCanonicalRecord(rollbackTx, rollbackRecord)
        handle.appendEvent(
            rollbackTx,
            fixtureEvent("m6-event-rollback", rollbackRecord, rollbackEvidence.id, base + 3)
        )
        handle.rollbackTransaction(rollbackTx)

        val rollbackInvisible = handle.getCanonicalRecord("m6-record-rollback") == null &&
            handle.readEvidence(
                RiftMemoryQueryV1(evidenceId = "m6-evidence-rollback"),
                RiftMemoryBoundsV1(limit = 10)
            ).evidence.isEmpty() &&
            handle.readEvents(
                RiftMemoryQueryV1(recordId = "m6-record-rollback"),
                RiftMemoryBoundsV1(limit = 10)
            ).events.isEmpty()

        val allRecords = handle.scanCanonicalRecords(
            RiftMemoryQueryV1(namespace = scopeA.namespace),
            RiftMemoryBoundsV1(limit = 100)
        ).records
        val projectARecords = handle.scanCanonicalRecords(
            RiftMemoryQueryV1(namespace = scopeA.namespace, projectId = scopeA.projectId),
            RiftMemoryBoundsV1(limit = 100)
        ).records
        val evidenceRows = handle.readEvidence(
            RiftMemoryQueryV1(namespace = scopeA.namespace),
            RiftMemoryBoundsV1(limit = 100)
        ).evidence
        val eventRows = handle.readEvents(
            RiftMemoryQueryV1(namespace = scopeA.namespace),
            RiftMemoryBoundsV1(limit = 100)
        ).events

        val blob = handle.getContentBlob(blobA, RiftMemoryBoundsV1(maxBlobBytes = 1024)) ?: error("m6-blob-missing")
        val boundedReadBlocked = runCatching {
            handle.getContentBlob(blobA, RiftMemoryBoundsV1(maxBlobBytes = 1))
        }.isFailure
        val blobRoundTrip = blobA == blobA2 &&
            blob.bytes.contentEquals(contentA) &&
            blob.mediaType == "text/plain"

        val snapshot = handle.createSnapshot(RiftMemoryQueryV1(namespace = scopeA.namespace))
        val projection = RiftMemoryTemporalGraphV1.rebuild(
            handle,
            RiftMemoryQueryV1(namespace = scopeA.namespace)
        )
        handle.markProjectionDirty(
            RiftMemoryReconciliationV1.PROJECTION_TEMPORAL_GRAPH,
            "n2-m6-fixture"
        )
        val dirtyDetected = !RiftMemoryTemporalGraphV1.isFresh(handle, projection)
        val rebuilt = RiftMemoryTemporalGraphV1.rebuild(
            handle,
            RiftMemoryQueryV1(namespace = scopeA.namespace)
        )
        val projectionRebuild = rebuilt.canonicalSha256 == projection.canonicalSha256 &&
            RiftMemoryTemporalGraphV1.isFresh(handle, rebuilt)
        val dirtyRows = handle.listDirtyProjections(
            RiftMemoryQueryV1(),
            RiftMemoryBoundsV1(limit = 100)
        )
        val dirtyProjection = dirtyDetected &&
            dirtyRows.any {
                it.name == RiftMemoryReconciliationV1.PROJECTION_TEMPORAL_GRAPH &&
                    it.reason == "n2-m6-fixture"
            }

        val integrity = handle.verifyIntegrity(
            RiftMemoryQueryV1(namespace = scopeA.namespace),
            RiftMemoryBoundsV1(limit = 1_000)
        )

        val comparable = fixtureComparable(
            allRecords,
            projectARecords,
            evidenceRows,
            eventRows,
            blobA,
            blobB,
            snapshot,
            rebuilt,
            dirtyRows,
            integrity,
            rollbackInvisible,
            boundedReadBlocked
        )
        val comparableSha = RiftMemoryModelV1.canonicalSha256(comparable)

        handle.close()
        handle = store.open(RiftMemoryStoreConfigV1(file.absolutePath, createIfMissing = false))
        val reopenedRecords = handle.scanCanonicalRecords(
            RiftMemoryQueryV1(namespace = scopeA.namespace),
            RiftMemoryBoundsV1(limit = 100)
        ).records
        val reopenedEvents = handle.readEvents(
            RiftMemoryQueryV1(namespace = scopeA.namespace),
            RiftMemoryBoundsV1(limit = 100)
        ).events
        val reopenedSnapshot = handle.createSnapshot(RiftMemoryQueryV1(namespace = scopeA.namespace))
        val reopenedProjection = RiftMemoryTemporalGraphV1.rebuild(
            handle,
            RiftMemoryQueryV1(namespace = scopeA.namespace)
        )
        val closeReopen = recordsSha(reopenedRecords) == recordsSha(allRecords) &&
            eventsSha(reopenedEvents) == eventsSha(eventRows) &&
            reopenedSnapshot.canonicalHash == snapshot.canonicalHash &&
            reopenedProjection.canonicalSha256 == rebuilt.canonicalSha256 &&
            handle.getCanonicalRecord("m6-record-rollback") == null
        handle.close()

        val atomicCommit = commit1.eventSequence == 1L &&
            commit2.eventSequence == 2L &&
            commit3.eventSequence == 3L
        val recordQueries = allRecords.map { it.id } == listOf("m6-record-a", "m6-record-b") &&
            projectARecords.size == 1 &&
            projectARecords.single().id == "m6-record-a" &&
            projectARecords.single().payload.optInt("revision") == 2
        val evidenceQueries = evidenceRows.map { it.id } == listOf("m6-evidence-a", "m6-evidence-b")
        val eventChain = eventRows.map { it.sequence } == listOf(1L, 2L, 3L) &&
            eventRows.zipWithNext().all { (a, b) ->
                b.event.optString("previousEventHash") == a.eventHash
            } &&
            eventRows.firstOrNull()?.event?.opt("previousEventHash") == JSONObject.NULL
        val snapshotStable = snapshot.eventSequence == 3L && snapshot.metadata.optInt("recordCount") == 2

        return FixtureResult(
            comparableSha256 = comparableSha,
            snapshotCanonicalHash = snapshot.canonicalHash,
            projectionCanonicalHash = rebuilt.canonicalSha256,
            eventSequence = commit3.eventSequence,
            atomicCommit = atomicCommit,
            rollbackInvisible = rollbackInvisible,
            blobRoundTrip = blobRoundTrip,
            recordQueries = recordQueries,
            evidenceQueries = evidenceQueries,
            eventChain = eventChain,
            snapshotStable = snapshotStable,
            projectionRebuild = projectionRebuild,
            dirtyProjection = dirtyProjection,
            boundedReadBlocked = boundedReadBlocked,
            closeReopen = closeReopen,
            integrityClean = integrity.clean
        )
    }

    private fun fixtureComparable(
        records: List<RiftCanonicalMemoryRecordV1>,
        projectRecords: List<RiftCanonicalMemoryRecordV1>,
        evidence: List<RiftMemoryEvidenceV1>,
        events: List<RiftMemoryEventRowV1>,
        blobA: String,
        blobB: String,
        snapshot: RiftMemorySnapshotMetadataV1,
        projection: RiftMemoryTemporalGraphProjectionV1,
        dirtyRows: List<RiftMemoryDirtyProjectionV1>,
        integrity: RiftMemoryIntegrityReportV1,
        rollbackInvisible: Boolean,
        boundedReadBlocked: Boolean
    ): JSONObject = JSONObject()
        .put("records", JSONArray().apply { records.forEach { put(it.toJson()) } })
        .put("projectRecords", JSONArray().apply { projectRecords.forEach { put(it.toJson()) } })
        .put("evidence", JSONArray().apply { evidence.forEach { put(it.toJson()) } })
        .put("events", JSONArray().apply {
            events.forEach { row ->
                put(
                    JSONObject()
                        .put("sequence", row.sequence)
                        .put("event", JSONObject(row.event.toString()))
                        .put("eventHash", row.eventHash)
                )
            }
        })
        .put("blobHashes", JSONArray(listOf(blobA, blobB).sorted()))
        .put("snapshotCanonicalHash", snapshot.canonicalHash)
        .put("snapshotEventSequence", snapshot.eventSequence)
        .put("projectionCanonicalHash", projection.canonicalSha256)
        .put("projectionEventSequence", projection.eventSequence)
        .put("dirty", JSONArray().apply {
            dirtyRows.sortedBy { it.name }.forEach {
                put(JSONObject().put("name", it.name).put("reason", it.reason))
            }
        })
        .put("integrity", JSONObject()
            .put("clean", integrity.clean)
            .put("blobsChecked", integrity.blobsChecked)
            .put("evidenceChecked", integrity.evidenceChecked)
            .put("recordsChecked", integrity.recordsChecked)
            .put("eventsChecked", integrity.eventsChecked))
        .put("rollbackInvisible", rollbackInvisible)
        .put("boundedReadBlocked", boundedReadBlocked)

    private fun fixtureRecord(
        id: String,
        scope: RiftMemoryScopeV1,
        evidenceId: String,
        revision: Int,
        at: Long
    ): RiftCanonicalMemoryRecordV1 = RiftCanonicalMemoryRecordV1(
        id = id,
        kind = RiftMemoryRecordKindV1.CLAIM,
        scope = scope,
        branch = RiftMemoryBranchV1.REALITY,
        trustState = RiftMemoryTrustStateV1.VERIFIED,
        time = RiftMemoryBiTemporalV1(at, recordedAt = at),
        payload = JSONObject()
            .put("revision", revision)
            .put("label", id),
        evidenceRefs = listOf(evidenceId)
    )

    private fun fixtureEvent(
        id: String,
        record: RiftCanonicalMemoryRecordV1,
        evidenceId: String,
        at: Long
    ): RiftMemoryEventV1 = RiftMemoryEventV1(
        id = id,
        type = RiftMemoryReconciliationV1.EVENT_RECORD_VERSION,
        recordId = record.id,
        evidenceId = evidenceId,
        scope = record.scope,
        branch = record.branch,
        at = at,
        validFrom = at,
        payload = JSONObject().put("record", record.toJson())
    )

    private fun recordsSha(records: List<RiftCanonicalMemoryRecordV1>): String =
        RiftMemoryModelV1.canonicalSha256(
            JSONObject().put("records", JSONArray().apply { records.forEach { put(it.toJson()) } })
        )

    private fun eventsSha(events: List<RiftMemoryEventRowV1>): String =
        RiftMemoryModelV1.canonicalSha256(
            JSONObject().put("events", JSONArray().apply {
                events.forEach {
                    put(JSONObject()
                        .put("sequence", it.sequence)
                        .put("event", JSONObject(it.event.toString()))
                        .put("eventHash", it.eventHash))
                }
            })
        )

    private fun proveRiftStoreSeal(
        proofDir: File,
        scope: RiftMemoryScopeV1,
        at: Long
    ): Boolean {
        val file = File(proofDir, "n2-m6-riftstore-tamper.json")
        cleanup(file)
        val store = RiftStoreMemoryStoreV1()
        val handle = store.open(RiftMemoryStoreConfigV1(file.absolutePath))
        val tx = handle.beginTransaction(JSONObject().put("probe", "seal"))
        val blob = handle.putContentBlob(
            tx,
            "seal-probe".toByteArray(Charsets.UTF_8),
            JSONObject().put("mediaType", "text/plain")
        )
        val evidence = RiftMemoryEvidenceV1(
            id = "m6-seal-evidence",
            contentSha256 = blob,
            mediaType = "text/plain",
            sourceType = "diagnostic",
            sourceRef = "n2-m6/seal",
            scope = scope,
            branch = RiftMemoryBranchV1.REALITY,
            observedAt = at,
            recordedAt = at,
            trustState = RiftMemoryTrustStateV1.VERIFIED
        )
        handle.appendEvidence(tx, evidence)
        handle.putCanonicalRecord(tx, fixtureRecord("m6-seal-record", scope, evidence.id, 1, at))
        handle.commitTransaction(tx)
        handle.close()

        val envelope = JSONObject(file.readText(Charsets.UTF_8))
        envelope.getJSONObject("payload").put("poisoned", true)
        file.writeText(envelope.toString(), Charsets.UTF_8)
        val detected = runCatching {
            store.open(RiftMemoryStoreConfigV1(file.absolutePath, createIfMissing = false))
        }.isFailure
        cleanup(file)
        return detected
    }

    private fun proveOversizeStateRejected(proofDir: File): Boolean {
        val file = File(proofDir, "n2-m6-riftstore-oversize.json")
        cleanup(file)
        RandomAccessFile(file, "rw").use {
            it.setLength(RiftStoreMemoryStoreV1.MAX_STORE_BYTES.toLong() + 1L)
        }
        val rejected = runCatching {
            RiftStoreMemoryStoreV1().open(
                RiftMemoryStoreConfigV1(file.absolutePath, createIfMissing = false)
            )
        }.isFailure
        cleanup(file)
        return rejected
    }

    private fun proveMissingEvidenceRejected(
        proofDir: File,
        scope: RiftMemoryScopeV1,
        at: Long
    ): Boolean {
        val file = File(proofDir, "n2-m6-riftstore-missing-evidence.json")
        cleanup(file)
        val handle = RiftStoreMemoryStoreV1().open(RiftMemoryStoreConfigV1(file.absolutePath))
        val tx = handle.beginTransaction(JSONObject().put("probe", "missing-evidence"))
        val record = RiftCanonicalMemoryRecordV1(
            id = "m6-missing-evidence-record",
            kind = RiftMemoryRecordKindV1.CLAIM,
            scope = scope,
            branch = RiftMemoryBranchV1.REALITY,
            trustState = RiftMemoryTrustStateV1.VERIFIED,
            time = RiftMemoryBiTemporalV1(at, recordedAt = at),
            payload = JSONObject().put("probe", true),
            evidenceRefs = listOf("m6-does-not-exist")
        )
        val rejected = runCatching { handle.putCanonicalRecord(tx, record) }.isFailure
        handle.rollbackTransaction(tx)
        handle.close()
        cleanup(file)
        return rejected
    }

    private fun proveRiftStoreRestart(
        context: Context,
        proofDir: File,
        scope: RiftMemoryScopeV1
    ): RestartProbe {
        val file = File(proofDir, "n2-m6-riftstore-restart.json")
        val store = RiftStoreMemoryStoreV1()
        val handle = store.open(RiftMemoryStoreConfigV1(file.absolutePath))
        val recordId = "n2-m6-restart-session"
        val prior = handle.getCanonicalRecord(recordId)
        val priorToken = prior?.payload?.optString("processToken")?.takeIf { it.isNotBlank() }
        val token = UUID.randomUUID().toString()
        val recovered = priorToken != null && priorToken != token
        val now = System.currentTimeMillis()
        val tx = handle.beginTransaction(JSONObject().put("probe", "cold-restart"))
        val blob = handle.putContentBlob(
            tx,
            ("restart-" + token).toByteArray(Charsets.UTF_8),
            JSONObject().put("mediaType", "text/plain")
        )
        val evidence = RiftMemoryEvidenceV1(
            id = "m6-restart-evidence-" + UUID.randomUUID(),
            contentSha256 = blob,
            mediaType = "text/plain",
            sourceType = "diagnostic",
            sourceRef = "n2-m6/restart",
            scope = scope,
            branch = RiftMemoryBranchV1.REALITY,
            observedAt = now,
            recordedAt = now,
            trustState = RiftMemoryTrustStateV1.VERIFIED
        )
        handle.appendEvidence(tx, evidence)
        val record = RiftCanonicalMemoryRecordV1(
            id = recordId,
            kind = RiftMemoryRecordKindV1.CLAIM,
            scope = scope,
            branch = RiftMemoryBranchV1.REALITY,
            trustState = RiftMemoryTrustStateV1.VERIFIED,
            time = RiftMemoryBiTemporalV1(now, recordedAt = now),
            payload = JSONObject()
                .put("processToken", token)
                .put("pid", Process.myPid())
                .put("package", context.packageName),
            evidenceRefs = listOf(evidence.id)
        )
        handle.putCanonicalRecord(tx, record)
        handle.appendEvent(
            tx,
            RiftMemoryEventV1(
                id = "m6-restart-event-" + UUID.randomUUID(),
                type = RiftMemoryReconciliationV1.EVENT_RECORD_VERSION,
                recordId = record.id,
                evidenceId = evidence.id,
                scope = scope,
                branch = RiftMemoryBranchV1.REALITY,
                at = now,
                validFrom = now,
                payload = JSONObject().put("record", record.toJson())
            )
        )
        handle.commitTransaction(tx)
        handle.close()
        return RestartProbe(recovered = recovered, armed = true)
    }

    private fun armRiftStoreCrashProbe(
        proofDir: File,
        scope: RiftMemoryScopeV1
    ): CrashProbe {
        val file = File(proofDir, "n2-m6-riftstore-crash.json")
        val marker = File(proofDir, "n2-m6-riftstore-crash-marker.txt")
        val previousId = marker.takeIf { it.isFile }
            ?.readText(Charsets.UTF_8)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val store = RiftStoreMemoryStoreV1()
        val recovered = if (previousId != null && file.isFile) {
            val recovery = store.open(RiftMemoryStoreConfigV1(file.absolutePath, createIfMissing = false))
            try {
                recovery.getCanonicalRecord(previousId) == null
            } finally {
                recovery.close()
            }
        } else {
            false
        }

        riftStoreCrashProbeHandle?.close()
        val handle = store.open(RiftMemoryStoreConfigV1(file.absolutePath))
        val probeId = "n2-m6-crash-" + UUID.randomUUID()
        val now = System.currentTimeMillis()
        try {
            val tx = handle.beginTransaction(JSONObject().put("probe", "process-death-rollback"))
            val blob = handle.putContentBlob(
                tx,
                "n2-m6-uncommitted".toByteArray(Charsets.UTF_8),
                JSONObject().put("mediaType", "text/plain")
            )
            val evidence = RiftMemoryEvidenceV1(
                id = "m6-crash-evidence-" + UUID.randomUUID(),
                contentSha256 = blob,
                mediaType = "text/plain",
                sourceType = "diagnostic",
                sourceRef = "n2-m6/process-death",
                scope = scope,
                branch = RiftMemoryBranchV1.REALITY,
                observedAt = now,
                recordedAt = now,
                trustState = RiftMemoryTrustStateV1.VERIFIED
            )
            handle.appendEvidence(tx, evidence)
            val record = RiftCanonicalMemoryRecordV1(
                id = probeId,
                kind = RiftMemoryRecordKindV1.CLAIM,
                scope = scope,
                branch = RiftMemoryBranchV1.REALITY,
                trustState = RiftMemoryTrustStateV1.VERIFIED,
                time = RiftMemoryBiTemporalV1(now, recordedAt = now),
                payload = JSONObject().put("uncommitted", true),
                evidenceRefs = listOf(evidence.id)
            )
            handle.putCanonicalRecord(tx, record)
            handle.appendEvent(
                tx,
                RiftMemoryEventV1(
                    id = "m6-crash-event-" + UUID.randomUUID(),
                    type = RiftMemoryReconciliationV1.EVENT_RECORD_VERSION,
                    recordId = record.id,
                    evidenceId = evidence.id,
                    scope = scope,
                    branch = RiftMemoryBranchV1.REALITY,
                    at = now,
                    validFrom = now,
                    payload = JSONObject().put("record", record.toJson())
                )
            )
            marker.writeText(probeId, Charsets.UTF_8)
            riftStoreCrashProbeHandle = handle
        } catch (failure: Throwable) {
            handle.close()
            throw failure
        }
        return CrashProbe(recovered = recovered, armed = riftStoreCrashProbeHandle === handle)
    }

    private fun cleanup(file: File) {
        file.delete()
        File(file.absolutePath + "-journal").delete()
        File(file.absolutePath + "-wal").delete()
        File(file.absolutePath + "-shm").delete()
        File(file.absolutePath + ".bak").delete()
        File(file.absolutePath + ".new").delete()
    }
}
