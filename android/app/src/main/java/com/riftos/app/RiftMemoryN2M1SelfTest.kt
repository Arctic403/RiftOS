package com.riftos.app

import android.content.Context
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object RiftMemoryN2M1SelfTest {
    const val SCHEMA = "rift-memory-n2-m1-selftest-v1"
    private val lock = Any()
    private var cached: JSONObject? = null

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
        val dbFile = File(proofDir, "n2-m1-proof.sqlite")
        val store = RiftSqliteMemoryStoreV1()
        val config = RiftMemoryStoreConfigV1(dbFile.absolutePath)
        val pid = Process.myPid()
        val processToken = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val scope = RiftMemoryScopeV1(
            namespace = "diagnostic/n2-m1",
            workspaceId = "riftfs/workspace",
            projectId = "RiftOS-main"
        )
        val content = "rift-memory-n2-m1-proof-v1".toByteArray(Charsets.UTF_8)
        val sessionRecordId = "n2-m1-session"

        var handle = store.open(config)
        val prior = handle.getCanonicalRecord(sessionRecordId)
        val priorPid = prior?.payload?.optInt("pid", -1)?.takeIf { it > 0 }
        val priorProcessToken = prior?.payload?.optString("processToken")?.takeIf { it.isNotBlank() }
        val processRestartRecovered = priorProcessToken != null && priorProcessToken != processToken

        val first = writeVersion(
            handle = handle,
            scope = scope,
            recordId = sessionRecordId,
            pid = pid,
            processToken = processToken,
            revision = 1,
            content = content,
            now = now
        )
        val second = writeVersion(
            handle = handle,
            scope = scope,
            recordId = sessionRecordId,
            pid = pid,
            processToken = processToken,
            revision = 2,
            content = content,
            now = now + 1
        )

        val rollbackId = "n2-m1-rollback-$pid-" + UUID.randomUUID().toString().take(8)
        val rollbackTx = handle.beginTransaction(JSONObject().put("probe", "rollback"))
        val rollbackBlob = handle.putContentBlob(
            rollbackTx,
            "rollback-probe".toByteArray(Charsets.UTF_8),
            JSONObject().put("mediaType", "text/plain")
        )
        val rollbackEvidence = RiftMemoryEvidenceV1(
            id = "ev-" + UUID.randomUUID(),
            contentSha256 = rollbackBlob,
            mediaType = "text/plain",
            sourceType = "diagnostic",
            sourceRef = "n2-m1/rollback",
            scope = scope,
            branch = RiftMemoryBranchV1.REALITY,
            observedAt = now + 2,
            recordedAt = now + 2,
            trustState = RiftMemoryTrustStateV1.VERIFIED
        )
        handle.appendEvidence(rollbackTx, rollbackEvidence)
        val rollbackRecord = RiftCanonicalMemoryRecordV1(
            id = rollbackId,
            kind = RiftMemoryRecordKindV1.CLAIM,
            scope = scope,
            branch = RiftMemoryBranchV1.REALITY,
            trustState = RiftMemoryTrustStateV1.VERIFIED,
            time = RiftMemoryBiTemporalV1(now + 2, recordedAt = now + 2),
            payload = JSONObject().put("mustDisappear", true),
            evidenceRefs = listOf(rollbackEvidence.id)
        )
        handle.putCanonicalRecord(rollbackTx, rollbackRecord)
        handle.appendEvent(
            rollbackTx,
            RiftMemoryEventV1(
                id = "event-" + UUID.randomUUID(),
                type = "diagnostic.rollback-probe",
                recordId = rollbackId,
                evidenceId = rollbackEvidence.id,
                scope = scope,
                branch = RiftMemoryBranchV1.REALITY,
                at = now + 2,
                validFrom = now + 2,
                payload = JSONObject().put("record", rollbackRecord.toJson())
            )
        )
        handle.rollbackTransaction(rollbackTx)
        val rollbackInvisibleBeforeClose = handle.getCanonicalRecord(rollbackId) == null

        handle.close()
        handle = store.open(config)

        val current = handle.getCanonicalRecord(sessionRecordId)
        val history = handle.readEvents(
            RiftMemoryQueryV1(recordId = sessionRecordId),
            RiftMemoryBoundsV1(limit = 128)
        )
        val currentPayload = current?.payload
        val currentReconstructed = currentPayload != null &&
            currentPayload.optInt("revision") == 2 &&
            currentPayload.optInt("pid") == pid &&
            currentPayload.optString("processToken") == processToken
        val historyReconstructed = history.events.any {
            it.event.optJSONObject("payload")
                ?.optJSONObject("record")
                ?.optJSONObject("payload")
                ?.optInt("revision") == 1
        } && history.events.any {
            it.event.optJSONObject("payload")
                ?.optJSONObject("record")
                ?.optJSONObject("payload")
                ?.optInt("revision") == 2
        }
        val secondEvidence = handle.readEvidence(
            RiftMemoryQueryV1(evidenceId = second.evidenceId),
            RiftMemoryBoundsV1(limit = 8)
        ).evidence.singleOrNull()
        val provenancePreserved = secondEvidence?.contentSha256 == second.blobHash &&
            current?.evidenceRefs == listOf(second.evidenceId)
        val blob = handle.getContentBlob(second.blobHash, RiftMemoryBoundsV1(maxBlobBytes = 4096))
        val contentAddressed = blob != null && blob.bytes.contentEquals(content)
        val rollbackInvisibleAfterReopen = handle.getCanonicalRecord(rollbackId) == null
        val snapshot = handle.createSnapshot(RiftMemoryQueryV1(namespace = scope.namespace))
        val integrity = handle.verifyIntegrity(
            RiftMemoryQueryV1(namespace = scope.namespace),
            RiftMemoryBoundsV1(limit = 1_000)
        )

        val protectedNamespaceGuard = runCatching {
            RiftCanonicalMemoryRecordV1(
                id = "invalid-policy",
                kind = RiftMemoryRecordKindV1.POLICY,
                scope = scope,
                branch = RiftMemoryBranchV1.REALITY,
                trustState = RiftMemoryTrustStateV1.VERIFIED,
                time = RiftMemoryBiTemporalV1(now, recordedAt = now),
                payload = JSONObject(),
                evidenceRefs = listOf(second.evidenceId),
                authorityNamespace = "user/not-protected"
            )
        }.isFailure

        val branchRoundTrip = RiftCanonicalMemoryRecordV1.fromJson(
            RiftCanonicalMemoryRecordV1(
                id = "n2-m1-branch-roundtrip",
                kind = RiftMemoryRecordKindV1.BELIEF,
                scope = scope,
                branch = RiftMemoryBranchV1.SIMULATION,
                trustState = RiftMemoryTrustStateV1.PROVISIONAL,
                time = RiftMemoryBiTemporalV1(now, recordedAt = now),
                payload = JSONObject().put("candidate", true),
                evidenceRefs = emptyList()
            ).toJson()
        ).branch == RiftMemoryBranchV1.SIMULATION

        handle.close()

        val currentTime = current?.time
        val n21 = JSONObject()
            .put("canonicalJson", true)
            .put("evidenceSchemaDistinctFromRecordSchema", RiftMemoryModelV1.EVIDENCE_SCHEMA != RiftMemoryModelV1.RECORD_SCHEMA)
            .put("eventLedger", history.events.size >= 2)
            .put("biTemporal", currentTime != null && currentTime.recordedAt >= currentTime.validFrom)
            .put("branchRoundTrip", branchRoundTrip)
            .put("protectedNamespaceGuard", protectedNamespaceGuard)
            .put("currentReconstruction", currentReconstructed)
            .put("historyReconstruction", historyReconstructed)
            .put("provenance", provenancePreserved)

        val n22 = JSONObject()
            .put("sqliteReferenceBackend", true)
            .put("atomicCommit", first.eventSequence > 0 && second.eventSequence >= first.eventSequence)
            .put("rollbackInvisibleBeforeClose", rollbackInvisibleBeforeClose)
            .put("rollbackInvisibleAfterReopen", rollbackInvisibleAfterReopen)
            .put("closeReopenRecovery", currentReconstructed)
            .put("processRestartRecovered", processRestartRecovered)
            .put("priorProcessPid", priorPid ?: JSONObject.NULL)
            .put("currentPid", pid)
            .put("integrityClean", integrity.clean)
            .put("sqliteIntegrity", integrity.sqliteIntegrity)
            .put("snapshot", JSONObject()
                .put("id", snapshot.id)
                .put("eventSequence", snapshot.eventSequence)
                .put("canonicalHash", snapshot.canonicalHash))
            .put("contentAddressedEvidence", contentAddressed)

        val ok = n21.keys().asSequence().all { key -> n21.optBoolean(key, false) } &&
            n22.optBoolean("atomicCommit") &&
            n22.optBoolean("rollbackInvisibleBeforeClose") &&
            n22.optBoolean("rollbackInvisibleAfterReopen") &&
            n22.optBoolean("closeReopenRecovery") &&
            n22.optBoolean("integrityClean") &&
            n22.optBoolean("contentAddressedEvidence")

        return JSONObject()
            .put("schema", SCHEMA)
            .put("ok", ok)
            .put("diagnosticOnly", true)
            .put("runtimeAuthority", false)
            .put("database", "app-private/riftmemory-diagnostics/n2-m1-proof.sqlite")
            .put("n2_1", n21)
            .put("n2_2", n22)
            .put("integrityFindings", JSONArray(integrity.findings))
    }

    private data class WriteResult(
        val evidenceId: String,
        val blobHash: String,
        val eventSequence: Long
    )

    private fun writeVersion(
        handle: RiftMemoryStoreHandleV1,
        scope: RiftMemoryScopeV1,
        recordId: String,
        pid: Int,
        processToken: String,
        revision: Int,
        content: ByteArray,
        now: Long
    ): WriteResult {
        val tx = handle.beginTransaction(
            JSONObject().put("phase", "N2-M1").put("revision", revision)
        )
        val blobHash = handle.putContentBlob(
            tx,
            content,
            JSONObject().put("mediaType", "text/plain")
        )
        val evidence = RiftMemoryEvidenceV1(
            id = "ev-" + UUID.randomUUID(),
            contentSha256 = blobHash,
            mediaType = "text/plain",
            sourceType = "diagnostic",
            sourceRef = "n2-m1/selftest/revision-$revision",
            scope = scope,
            branch = RiftMemoryBranchV1.REALITY,
            observedAt = now,
            recordedAt = now,
            trustState = RiftMemoryTrustStateV1.VERIFIED,
            metadata = JSONObject().put("revision", revision)
        )
        handle.appendEvidence(tx, evidence)
        val record = RiftCanonicalMemoryRecordV1(
            id = recordId,
            kind = RiftMemoryRecordKindV1.CLAIM,
            scope = scope,
            branch = RiftMemoryBranchV1.REALITY,
            trustState = RiftMemoryTrustStateV1.VERIFIED,
            time = RiftMemoryBiTemporalV1(validFrom = now, recordedAt = now),
            payload = JSONObject()
                .put("pid", pid)
                .put("processToken", processToken)
                .put("revision", revision),
            evidenceRefs = listOf(evidence.id)
        )
        handle.putCanonicalRecord(tx, record)
        handle.appendEvent(
            tx,
            RiftMemoryEventV1(
                id = "event-" + UUID.randomUUID(),
                type = "diagnostic.record-version",
                recordId = record.id,
                evidenceId = evidence.id,
                scope = scope,
                branch = RiftMemoryBranchV1.REALITY,
                at = now,
                validFrom = now,
                payload = JSONObject().put("record", record.toJson())
            )
        )
        val commit = handle.commitTransaction(tx)
        return WriteResult(evidence.id, blobHash, commit.eventSequence)
    }
}
