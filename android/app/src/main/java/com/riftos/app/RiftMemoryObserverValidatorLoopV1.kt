package com.riftos.app

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

enum class RiftMemoryVerificationOriginV1 {
    OBSERVER,
    VALIDATOR
}

data class RiftMemoryVerificationSnapshotV1(
    val origin: RiftMemoryVerificationOriginV1,
    val snapshotId: String,
    val scope: RiftMemoryScopeV1,
    val subject: String,
    val value: String,
    val sourceStateSha256: String,
    val sourceSequence: Long,
    val complete: Boolean,
    val passed: Boolean? = null,
    val observedAt: Long,
    val recordedAt: Long = observedAt,
    val details: JSONObject = JSONObject()
) {
    init {
        RiftMemoryModelV1.requireId(snapshotId)
        require(subject.isNotBlank() && subject.length <= RiftMemoryObserverValidatorLoopV1.MAX_SUBJECT_CHARS)
        require(value.isNotBlank() && value.length <= RiftMemoryObserverValidatorLoopV1.MAX_VALUE_CHARS)
        require(RiftMemoryModelV1.SHA256.matches(sourceStateSha256))
        require(sourceSequence >= 0L)
        require(observedAt >= 0L && recordedAt >= 0L)
        require(details.length() <= RiftMemoryObserverValidatorLoopV1.MAX_DETAILS_FIELDS)
        require(details.toString().toByteArray(Charsets.UTF_8).size <= RiftMemoryObserverValidatorLoopV1.MAX_DETAILS_BYTES)
        when (origin) {
            RiftMemoryVerificationOriginV1.OBSERVER -> require(passed == null) {
                "observer-snapshot-must-not-claim-validator-pass-state"
            }
            RiftMemoryVerificationOriginV1.VALIDATOR -> require(passed != null) {
                "validator-snapshot-requires-pass-state"
            }
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("schema", "rift-memory-verification-snapshot-v1")
        .put("origin", origin.name)
        .put("snapshotId", snapshotId)
        .put("scope", scope.toJson())
        .put("subject", subject)
        .put("value", value)
        .put("sourceStateSha256", sourceStateSha256)
        .put("sourceSequence", sourceSequence)
        .put("complete", complete)
        .put("passed", passed ?: JSONObject.NULL)
        .put("observedAt", observedAt)
        .put("recordedAt", recordedAt)
        .put("details", JSONObject(details.toString()))
}

data class RiftMemoryClosedLoopResultV1(
    val observation: RiftMemoryReconciliationResultV1,
    val difference: RiftMemoryReconciliationResultV1?,
    val target: RiftMemoryReconciliationResultV1
) {
    fun toJson(): JSONObject = JSONObject()
        .put("observation", observation.toJson())
        .put("difference", difference?.toJson() ?: JSONObject.NULL)
        .put("target", target.toJson())
}

class RiftMemoryObserverValidatorLoopV1 {
    companion object {
        const val MAX_SUBJECT_CHARS = 256
        const val MAX_VALUE_CHARS = 4_096
        const val MAX_DETAILS_FIELDS = 64
        const val MAX_DETAILS_BYTES = 16_384
        const val MAX_SNAPSHOT_BYTES = 65_536
    }

    fun createObservationCandidate(
        candidateId: String,
        recordId: String,
        evidenceId: String,
        snapshot: RiftMemoryVerificationSnapshotV1
    ): RiftMemoryCandidateV1 {
        RiftMemoryModelV1.requireId(candidateId)
        RiftMemoryModelV1.requireId(recordId)
        RiftMemoryModelV1.requireId(evidenceId)
        val bytes = snapshotBytes(snapshot)
        val hash = sha256(bytes)
        val trust = if (snapshot.complete) RiftMemoryTrustStateV1.VERIFIED else RiftMemoryTrustStateV1.PROVISIONAL
        val evidence = RiftMemoryEvidenceV1(
            id = evidenceId,
            contentSha256 = hash,
            mediaType = "application/json",
            sourceType = when (snapshot.origin) {
                RiftMemoryVerificationOriginV1.OBSERVER -> "rift-observer-evidence-v1"
                RiftMemoryVerificationOriginV1.VALIDATOR -> "rift-validator-evidence-v1"
            },
            sourceRef = "n2-m5/${snapshot.origin.name.lowercase()}/${snapshot.snapshotId}",
            scope = snapshot.scope,
            branch = RiftMemoryBranchV1.REALITY,
            observedAt = snapshot.observedAt,
            recordedAt = snapshot.recordedAt,
            trustState = trust,
            metadata = JSONObject()
                .put("phase", "N2.9")
                .put("evidenceOnly", true)
                .put("complete", snapshot.complete)
                .put("sourceSequence", snapshot.sourceSequence)
                .put("sourceStateSha256", snapshot.sourceStateSha256)
        )
        val payload = verificationPayload(snapshot)
            .put("memoryClass", RiftMemoryCognitiveClassV1.OBSERVATION)
            .put("evidenceOnly", true)
            .put("conflictKey", "closed-loop-evidence:${snapshot.origin.name}:${snapshot.snapshotId}")
        val record = RiftCanonicalMemoryRecordV1(
            id = recordId,
            kind = RiftMemoryRecordKindV1.CLAIM,
            scope = snapshot.scope,
            branch = RiftMemoryBranchV1.REALITY,
            trustState = trust,
            time = RiftMemoryBiTemporalV1(snapshot.observedAt, recordedAt = snapshot.recordedAt),
            payload = payload,
            evidenceRefs = listOf(evidence.id)
        )
        return RiftMemoryCandidateV1(
            id = candidateId,
            record = record,
            evidence = listOf(evidence),
            contentBySha256 = mapOf(hash to bytes),
            authorityClass = if (snapshot.complete) RiftMemoryAuthorityClassV1.VERIFIED_SOURCE else RiftMemoryAuthorityClassV1.ORDINARY,
            metadata = JSONObject()
                .put("phase", "N2.9")
                .put("closedLoop", true)
                .put("directTruthMutation", false)
        )
    }

    fun processState(
        handle: RiftMemoryStoreHandleV1,
        reconcile: RiftMemoryReconciliationV1,
        snapshot: RiftMemoryVerificationSnapshotV1,
        targetRecordId: String,
        observationRecordId: String,
        differenceRecordId: String,
        candidatePrefix: String
    ): RiftMemoryClosedLoopResultV1 {
        require(snapshot.complete) { "closed-loop-source-incomplete" }
        val current = handle.getCanonicalRecord(targetRecordId) ?: throw IllegalArgumentException("closed-loop-target-missing:$targetRecordId")
        require(current.scope == snapshot.scope) { "closed-loop-cross-scope-forbidden" }
        requireFresh(current, snapshot)

        val observationResult = reconcile.reconcile(
            handle,
            createObservationCandidate(
                "$candidatePrefix-observation",
                observationRecordId,
                "$candidatePrefix-evidence",
                snapshot
            )
        )
        require(observationResult.outcome == RiftMemoryTransactionOutcomeV1.COMMITTED) {
            "closed-loop-observation-not-committed:${observationResult.outcome}"
        }
        val observation = handle.getCanonicalRecord(observationRecordId) ?: error("closed-loop-observation-missing-after-commit")

        val differenceResult = createDifferenceCandidate(
            "$candidatePrefix-difference",
            differenceRecordId,
            current,
            observation,
            snapshot.value,
            snapshot.recordedAt
        )?.let { reconcile.reconcile(handle, it) }

        val targetResult = reconcile.reconcile(
            handle,
            verifiedStateCandidate(
                "$candidatePrefix-target",
                targetRecordId,
                snapshot,
                observation.evidenceRefs
            )
        )
        return RiftMemoryClosedLoopResultV1(observationResult, differenceResult, targetResult)
    }

    fun processCommitment(
        handle: RiftMemoryStoreHandleV1,
        reconcile: RiftMemoryReconciliationV1,
        snapshot: RiftMemoryVerificationSnapshotV1,
        targetRecordId: String,
        observationRecordId: String,
        differenceRecordId: String,
        candidatePrefix: String,
        title: String,
        resumeKey: String,
        status: String
    ): RiftMemoryClosedLoopResultV1 {
        require(snapshot.origin == RiftMemoryVerificationOriginV1.VALIDATOR) {
            "commitment-correction-requires-validator"
        }
        require(snapshot.complete) { "closed-loop-source-incomplete" }
        require(title.isNotBlank() && title.length <= 512)
        require(resumeKey.isNotBlank() && resumeKey.length <= 256)
        require(status in setOf("OPEN", "COMPLETED", "CANCELLED"))
        require(snapshot.value == status) { "validator-value-must-match-commitment-status" }

        val current = handle.getCanonicalRecord(targetRecordId) ?: throw IllegalArgumentException("closed-loop-target-missing:$targetRecordId")
        require(current.scope == snapshot.scope) { "closed-loop-cross-scope-forbidden" }
        require(current.payload.optString("memoryClass") == RiftMemorySpecialistClassV1.COMMITMENT) {
            "closed-loop-target-not-commitment"
        }
        require(current.payload.optString("resumeKey") == resumeKey) {
            "closed-loop-resume-key-mismatch"
        }
        requireFresh(current, snapshot)

        val observationResult = reconcile.reconcile(
            handle,
            createObservationCandidate(
                "$candidatePrefix-observation",
                observationRecordId,
                "$candidatePrefix-evidence",
                snapshot
            )
        )
        require(observationResult.outcome == RiftMemoryTransactionOutcomeV1.COMMITTED) {
            "closed-loop-observation-not-committed:${observationResult.outcome}"
        }
        val observation = handle.getCanonicalRecord(observationRecordId) ?: error("closed-loop-observation-missing-after-commit")

        val differenceResult = createDifferenceCandidate(
            "$candidatePrefix-difference",
            differenceRecordId,
            current,
            observation,
            status,
            snapshot.recordedAt
        )?.let { reconcile.reconcile(handle, it) }

        val payload = verificationPayload(snapshot)
            .put("memoryClass", RiftMemorySpecialistClassV1.COMMITMENT)
            .put("title", title)
            .put("resumeKey", resumeKey)
            .put("status", status)
            .put("sourceRecordIds", JSONArray(listOf(observation.id)))
            .put("reversible", true)
            .put("conflictKey", "commitment:$resumeKey")
        val targetResult = reconcile.reconcile(
            handle,
            verifiedTargetCandidate(
                "$candidatePrefix-target",
                targetRecordId,
                snapshot,
                payload,
                observation.evidenceRefs
            )
        )
        return RiftMemoryClosedLoopResultV1(observationResult, differenceResult, targetResult)
    }

    private fun verifiedStateCandidate(
        candidateId: String,
        recordId: String,
        snapshot: RiftMemoryVerificationSnapshotV1,
        evidenceRefs: List<String>
    ): RiftMemoryCandidateV1 {
        val payload = verificationPayload(snapshot)
            .put("memoryClass", RiftMemoryCognitiveClassV1.OBSERVATION)
            .put("conflictKey", "closed-loop:${snapshot.subject}")
        return verifiedTargetCandidate(candidateId, recordId, snapshot, payload, evidenceRefs)
    }

    private fun verifiedTargetCandidate(
        candidateId: String,
        recordId: String,
        snapshot: RiftMemoryVerificationSnapshotV1,
        payload: JSONObject,
        evidenceRefs: List<String>
    ): RiftMemoryCandidateV1 {
        require(snapshot.complete) { "closed-loop-source-incomplete" }
        require(evidenceRefs.isNotEmpty()) { "closed-loop-verified-target-requires-evidence" }
        val record = RiftCanonicalMemoryRecordV1(
            id = recordId,
            kind = RiftMemoryRecordKindV1.CLAIM,
            scope = snapshot.scope,
            branch = RiftMemoryBranchV1.REALITY,
            trustState = RiftMemoryTrustStateV1.VERIFIED,
            time = RiftMemoryBiTemporalV1(snapshot.observedAt, recordedAt = snapshot.recordedAt),
            payload = payload,
            evidenceRefs = evidenceRefs.distinct().sorted()
        )
        return RiftMemoryCandidateV1(
            id = candidateId,
            record = record,
            evidence = emptyList(),
            contentBySha256 = emptyMap(),
            authorityClass = RiftMemoryAuthorityClassV1.VERIFIED_SOURCE,
            metadata = JSONObject()
                .put("phase", "N2.9")
                .put("closedLoop", true)
                .put("reconciledFromEvidence", true)
                .put("directTruthMutation", false)
        )
    }

    private fun createDifferenceCandidate(
        candidateId: String,
        recordId: String,
        expected: RiftCanonicalMemoryRecordV1,
        observed: RiftCanonicalMemoryRecordV1,
        observedValue: String,
        at: Long
    ): RiftMemoryCandidateV1? {
        require(expected.scope == observed.scope) { "closed-loop-difference-cross-scope-forbidden" }
        val expectedValue = expected.payload.optString("value").ifBlank { expected.payload.optString("status") }
        require(expectedValue.isNotBlank()) { "closed-loop-expected-value-missing" }
        if (expectedValue == observedValue) return null
        val payload = JSONObject()
            .put("memoryClass", RiftMemoryCognitiveClassV1.DISCREPANCY)
            .put("status", "UNRESOLVED")
            .put("expectedValue", expectedValue)
            .put("observedValue", observedValue)
            .put("sourceExpectedRecordId", expected.id)
            .put("sourceObservedRecordId", observed.id)
            .put("expectedVersionSha256", RiftMemoryModelV1.canonicalSha256(expected.toJson()))
            .put("observedVersionSha256", RiftMemoryModelV1.canonicalSha256(observed.toJson()))
            .put("conflictKey", "closed-loop-difference:${expected.id}")
        val record = RiftCanonicalMemoryRecordV1(
            id = recordId,
            kind = RiftMemoryRecordKindV1.CLAIM,
            scope = observed.scope,
            branch = RiftMemoryBranchV1.REALITY,
            trustState = RiftMemoryTrustStateV1.PROVISIONAL,
            time = RiftMemoryBiTemporalV1(at, recordedAt = at),
            payload = payload,
            evidenceRefs = observed.evidenceRefs.distinct().sorted()
        )
        return RiftMemoryCandidateV1(
            id = candidateId,
            record = record,
            evidence = emptyList(),
            contentBySha256 = emptyMap(),
            authorityClass = RiftMemoryAuthorityClassV1.ORDINARY,
            metadata = JSONObject()
                .put("phase", "N2.9")
                .put("closedLoop", true)
                .put("stage", "difference")
                .put("derivedAuthority", false)
        )
    }

    private fun requireFresh(
        current: RiftCanonicalMemoryRecordV1,
        snapshot: RiftMemoryVerificationSnapshotV1
    ) {
        val currentOrigin = current.payload.optString("verificationOrigin")
        val currentSequence = if (current.payload.has("sourceSequence")) current.payload.optLong("sourceSequence", -1L) else -1L
        val currentSnapshotId = current.payload.optString("snapshotId")
        if (currentOrigin == snapshot.origin.name && currentSequence >= 0L) {
            require(snapshot.sourceSequence > currentSequence) {
                "verification-snapshot-stale-or-replayed:${snapshot.sourceSequence}:$currentSequence"
            }
        }
        require(currentSnapshotId.isBlank() || currentSnapshotId != snapshot.snapshotId) {
            "verification-snapshot-replayed:${snapshot.snapshotId}"
        }
    }

    private fun verificationPayload(snapshot: RiftMemoryVerificationSnapshotV1): JSONObject =
        JSONObject()
            .put("verificationOrigin", snapshot.origin.name)
            .put("snapshotId", snapshot.snapshotId)
            .put("subject", snapshot.subject)
            .put("value", snapshot.value)
            .put("sourceStateSha256", snapshot.sourceStateSha256)
            .put("sourceSequence", snapshot.sourceSequence)
            .put("complete", snapshot.complete)
            .put("passed", snapshot.passed ?: JSONObject.NULL)
            .put("details", JSONObject(snapshot.details.toString()))

    private fun snapshotBytes(snapshot: RiftMemoryVerificationSnapshotV1): ByteArray {
        val bytes = snapshot.toJson().toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_SNAPSHOT_BYTES) {
            "verification-snapshot-byte-bound-exceeded:$MAX_SNAPSHOT_BYTES"
        }
        return bytes
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
