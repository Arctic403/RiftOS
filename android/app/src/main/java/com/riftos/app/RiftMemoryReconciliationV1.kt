package com.riftos.app

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

enum class RiftMemoryTransactionOutcomeV1 {
    COMMITTED,
    PROVISIONAL,
    QUARANTINED,
    REJECTED
}

enum class RiftMemoryAuthorityClassV1 {
    ORDINARY,
    VERIFIED_SOURCE,
    POLICY_AUTHORITY,
    CONFIG_AUTHORITY
}

enum class RiftMemoryCandidateActionV1 {
    UPSERT,
    INVALIDATE
}

data class RiftMemoryCandidateV1(
    val id: String,
    val record: RiftCanonicalMemoryRecordV1,
    val evidence: List<RiftMemoryEvidenceV1>,
    val contentBySha256: Map<String, ByteArray>,
    val authorityClass: RiftMemoryAuthorityClassV1 = RiftMemoryAuthorityClassV1.ORDINARY,
    val action: RiftMemoryCandidateActionV1 = RiftMemoryCandidateActionV1.UPSERT,
    val metadata: JSONObject = JSONObject()
) {
    init {
        RiftMemoryModelV1.requireId(id)
        require(evidence.size <= RiftMemoryModelV1.MAX_EVIDENCE_REFS)
        require(evidence.map { it.id }.distinct().size == evidence.size)
        require(contentBySha256.size <= RiftMemoryModelV1.MAX_EVIDENCE_REFS)
    }
}

data class RiftMemoryReconciliationResultV1(
    val candidateId: String,
    val outcome: RiftMemoryTransactionOutcomeV1,
    val authorityClass: RiftMemoryAuthorityClassV1,
    val currentChanged: Boolean,
    val conflictFound: Boolean,
    val transactionId: String?,
    val eventSequence: Long?,
    val reasons: List<String>
) {
    fun toJson(): JSONObject = JSONObject()
        .put("candidateId", candidateId)
        .put("outcome", outcome.name)
        .put("authorityClass", authorityClass.name)
        .put("currentChanged", currentChanged)
        .put("conflictFound", conflictFound)
        .put("transactionId", transactionId ?: JSONObject.NULL)
        .put("eventSequence", eventSequence ?: JSONObject.NULL)
        .put("reasons", JSONArray(reasons))
}

class RiftMemoryReconciliationV1 {
    companion object {
        const val EVENT_RECONCILIATION = "memory.reconciliation"
        const val EVENT_RECORD_VERSION = "memory.record-version"
        const val EVENT_SUPERSESSION = "memory.supersession"
        const val EVENT_INVALIDATION = "memory.invalidation"
        const val EVENT_CONTRADICTION = "memory.contradiction"
        const val PROJECTION_CURRENT = "rift-memory-current-v1"
        const val PROJECTION_TEMPORAL_GRAPH = "rift-memory-temporal-graph-v1"
        private const val MAX_CONFLICT_SCAN = 4_096
    }

    fun reconcile(
        handle: RiftMemoryStoreHandleV1,
        candidate: RiftMemoryCandidateV1
    ): RiftMemoryReconciliationResultV1 {
        val validation = validateCandidate(handle, candidate)
        if (validation.isNotEmpty()) {
            return persistRejected(handle, candidate, validation)
        }

        val resolvedEvidence = resolveEvidence(handle, candidate)
        val evidenceCeiling = evidenceTrustCeiling(resolvedEvidence)
        val normalizedCandidate = candidate.record.copy(
            trustState = normalizedTrust(candidate.record, evidenceCeiling)
        )
        val conflicts = findConflicts(handle, normalizedCandidate)
        if (conflicts.size > 1) {
            return persistNoOverwrite(
                handle,
                candidate,
                normalizedCandidate,
                RiftMemoryTransactionOutcomeV1.QUARANTINED,
                listOf("ambiguous-conflict-set:${conflicts.size}"),
                conflicts.firstOrNull()
            )
        }
        val current = conflicts.singleOrNull()

        if (!protectedAuthorityAllowed(normalizedCandidate, candidate.authorityClass)) {
            return persistNoOverwrite(
                handle,
                candidate,
                normalizedCandidate,
                RiftMemoryTransactionOutcomeV1.QUARANTINED,
                listOf("protected-authority-mismatch"),
                current
            )
        }

        if (candidate.action == RiftMemoryCandidateActionV1.INVALIDATE) {
            if (normalizedCandidate.branch != RiftMemoryBranchV1.REALITY) {
                return persistRejected(handle, candidate, listOf("invalidation-requires-reality"))
            }
            if (current == null) {
                return persistRejected(handle, candidate, listOf("invalidation-target-missing"))
            }
            val candidateRank = trustRank(normalizedCandidate.trustState)
            val currentRank = trustRank(current.trustState)
            if (candidateRank < trustRank(RiftMemoryTrustStateV1.TRUSTED) || candidateRank < currentRank) {
                return persistNoOverwrite(
                    handle,
                    candidate,
                    normalizedCandidate,
                    RiftMemoryTransactionOutcomeV1.QUARANTINED,
                    listOf("invalidation-authority-too-weak"),
                    current
                )
            }
            val invalidated = current.copy(
                trustState = RiftMemoryTrustStateV1.INVALIDATED,
                time = current.time.copy(
                    validTo = maxOf(current.time.validFrom, normalizedCandidate.time.validFrom),
                    recordedAt = maxOf(current.time.recordedAt, normalizedCandidate.time.recordedAt)
                ),
                evidenceRefs = mergeEvidenceRefs(current.evidenceRefs, normalizedCandidate.evidenceRefs)
            )
            return persistRecordDecision(
                handle,
                candidate,
                invalidated,
                current,
                RiftMemoryTransactionOutcomeV1.COMMITTED,
                listOf("authorized-invalidation"),
                supersedePrior = false,
                conflict = false,
                invalidation = true
            )
        }

        if (normalizedCandidate.branch != RiftMemoryBranchV1.REALITY) {
            val provisional = normalizedCandidate.copy(trustState = RiftMemoryTrustStateV1.PROVISIONAL)
            return persistRecordDecision(
                handle,
                candidate,
                provisional,
                current?.takeIf { it.id == provisional.id },
                RiftMemoryTransactionOutcomeV1.PROVISIONAL,
                listOf("non-reality-branch-isolated"),
                supersedePrior = false,
                conflict = current != null
            )
        }

        if (current == null) {
            val trusted = trustRank(normalizedCandidate.trustState) >= trustRank(RiftMemoryTrustStateV1.TRUSTED)
            return persistRecordDecision(
                handle,
                candidate,
                normalizedCandidate,
                null,
                if (trusted) RiftMemoryTransactionOutcomeV1.COMMITTED else RiftMemoryTransactionOutcomeV1.PROVISIONAL,
                listOf(if (trusted) "new-supported-reality" else "new-provisional-reality"),
                supersedePrior = false,
                conflict = false
            )
        }

        val samePayload = RiftMemoryModelV1.canonicalSha256(current.payload) ==
            RiftMemoryModelV1.canonicalSha256(normalizedCandidate.payload)
        val currentRank = trustRank(current.trustState)
        val candidateRank = trustRank(normalizedCandidate.trustState)

        if (samePayload) {
            if (candidateRank > currentRank && candidateRank >= trustRank(RiftMemoryTrustStateV1.TRUSTED)) {
                val upgraded = normalizedCandidate.copy(
                    id = current.id,
                    evidenceRefs = mergeEvidenceRefs(current.evidenceRefs, normalizedCandidate.evidenceRefs)
                )
                return persistRecordDecision(
                    handle,
                    candidate,
                    upgraded,
                    current,
                    RiftMemoryTransactionOutcomeV1.COMMITTED,
                    listOf("stronger-evidence-upgrade"),
                    supersedePrior = false,
                    conflict = false
                )
            }
            return persistNoOverwrite(
                handle,
                candidate,
                normalizedCandidate,
                RiftMemoryTransactionOutcomeV1.PROVISIONAL,
                listOf("repetition-does-not-upgrade-trust"),
                current = null
            )
        }

        if (candidateRank > currentRank && candidateRank >= trustRank(RiftMemoryTrustStateV1.TRUSTED)) {
            return persistRecordDecision(
                handle,
                candidate,
                normalizedCandidate,
                current,
                RiftMemoryTransactionOutcomeV1.COMMITTED,
                listOf("stronger-evidence-supersession"),
                supersedePrior = true,
                conflict = true
            )
        }

        if (candidateRank == currentRank && candidateRank >= trustRank(RiftMemoryTrustStateV1.TRUSTED)) {
            val conflicted = current.copy(
                trustState = RiftMemoryTrustStateV1.CONFLICTED,
                time = current.time.copy(recordedAt = maxOf(current.time.recordedAt, normalizedCandidate.time.recordedAt)),
                evidenceRefs = mergeEvidenceRefs(current.evidenceRefs, normalizedCandidate.evidenceRefs)
            )
            return persistRecordDecision(
                handle,
                candidate,
                conflicted,
                current,
                RiftMemoryTransactionOutcomeV1.QUARANTINED,
                listOf("equal-authority-contradiction"),
                supersedePrior = false,
                conflict = true,
                contradictionCandidate = normalizedCandidate
            )
        }

        return persistNoOverwrite(
            handle,
            candidate,
            normalizedCandidate,
            RiftMemoryTransactionOutcomeV1.QUARANTINED,
            listOf("weaker-conflicting-candidate"),
            current
        )
    }

    private fun persistRejected(
        handle: RiftMemoryStoreHandleV1,
        candidate: RiftMemoryCandidateV1,
        reasons: List<String>
    ): RiftMemoryReconciliationResultV1 {
        val sortedReasons = reasons.distinct().sorted()
        val tx = handle.beginTransaction(
            JSONObject()
                .put("phase", "N2.3")
                .put("candidateId", candidate.id)
                .put("outcome", RiftMemoryTransactionOutcomeV1.REJECTED.name)
        )
        return try {
            appendReconciliationEvent(
                handle,
                tx,
                candidate,
                candidate.record,
                RiftMemoryTransactionOutcomeV1.REJECTED,
                sortedReasons,
                false
            )
            val commit = handle.commitTransaction(tx)
            runCatching {
                handle.markProjectionDirty(
                    PROJECTION_TEMPORAL_GRAPH,
                    "reconciliation:${candidate.id}:${RiftMemoryTransactionOutcomeV1.REJECTED.name}"
                )
            }
            RiftMemoryReconciliationResultV1(
                candidate.id,
                RiftMemoryTransactionOutcomeV1.REJECTED,
                candidate.authorityClass,
                false,
                false,
                tx.id,
                commit.eventSequence,
                sortedReasons
            )
        } catch (failure: Throwable) {
            runCatching { handle.rollbackTransaction(tx) }
            throw failure
        }
    }

    private fun validateCandidate(
        handle: RiftMemoryStoreHandleV1,
        candidate: RiftMemoryCandidateV1
    ): List<String> {
        val reasons = mutableListOf<String>()
        val record = candidate.record
        if (candidate.evidence.any { it.scope != record.scope }) reasons += "evidence-scope-mismatch"
        if (candidate.evidence.any { it.branch != record.branch }) reasons += "evidence-branch-mismatch"
        if (candidate.evidence.any { it.id !in record.evidenceRefs }) reasons += "unreferenced-candidate-evidence"
        for (evidence in candidate.evidence) {
            val bytes = candidate.contentBySha256[evidence.contentSha256]
            if (bytes == null) {
                val existing = handle.getContentBlob(
                    evidence.contentSha256,
                    RiftMemoryBoundsV1(limit = 1, maxBlobBytes = 8_388_608)
                )
                if (existing == null) reasons += "missing-evidence-content:${evidence.id}"
            } else if (sha256(bytes) != evidence.contentSha256) {
                reasons += "evidence-content-hash-mismatch:${evidence.id}"
            }
        }
        for (ref in record.evidenceRefs) {
            if (candidate.evidence.none { it.id == ref }) {
                val existing = handle.readEvidence(
                    RiftMemoryQueryV1(evidenceId = ref),
                    RiftMemoryBoundsV1(limit = 2)
                ).evidence
                if (existing.size != 1) reasons += "missing-or-ambiguous-evidence:$ref"
                else if (existing.single().scope != record.scope || existing.single().branch != record.branch) {
                    reasons += "existing-evidence-scope-mismatch:$ref"
                }
            }
        }
        return reasons.distinct().sorted()
    }

    private fun resolveEvidence(
        handle: RiftMemoryStoreHandleV1,
        candidate: RiftMemoryCandidateV1
    ): List<RiftMemoryEvidenceV1> {
        val out = LinkedHashMap<String, RiftMemoryEvidenceV1>()
        candidate.evidence.forEach { out[it.id] = it }
        for (ref in candidate.record.evidenceRefs) {
            if (out.containsKey(ref)) continue
            val existing = handle.readEvidence(
                RiftMemoryQueryV1(evidenceId = ref),
                RiftMemoryBoundsV1(limit = 2)
            ).evidence
            check(existing.size == 1) { "Expected exactly one evidence row for $ref" }
            out[ref] = existing.single()
        }
        return out.values.toList()
    }

    private fun findConflicts(
        handle: RiftMemoryStoreHandleV1,
        record: RiftCanonicalMemoryRecordV1
    ): List<RiftCanonicalMemoryRecordV1> {
        val conflictKey = record.payload.optString("conflictKey").takeIf { it.isNotBlank() }
        val matches = LinkedHashMap<String, RiftCanonicalMemoryRecordV1>()
        var offset = 0
        while (offset < MAX_CONFLICT_SCAN) {
            val limit = minOf(1_000, MAX_CONFLICT_SCAN - offset)
            val page = handle.scanCanonicalRecords(
                RiftMemoryQueryV1(
                    namespace = record.scope.namespace,
                    projectId = record.scope.projectId,
                    kind = record.kind,
                    branch = record.branch
                ),
                RiftMemoryBoundsV1(offset = offset, limit = limit)
            )
            for (current in page.records) {
                val sameId = current.id == record.id
                val sameConflictKey = conflictKey != null &&
                    current.payload.optString("conflictKey") == conflictKey
                if (sameId || sameConflictKey) matches[current.id] = current
            }
            offset += page.records.size
            if (page.records.size < limit) return matches.values.sortedBy { it.id }
        }
        val overflow = handle.scanCanonicalRecords(
            RiftMemoryQueryV1(
                namespace = record.scope.namespace,
                projectId = record.scope.projectId,
                kind = record.kind,
                branch = record.branch
            ),
            RiftMemoryBoundsV1(offset = MAX_CONFLICT_SCAN, limit = 1)
        ).records.isNotEmpty()
        check(!overflow) { "reconciliation-conflict-scan-bound-exceeded:$MAX_CONFLICT_SCAN" }
        return matches.values.sortedBy { it.id }
    }

    private fun protectedAuthorityAllowed(
        record: RiftCanonicalMemoryRecordV1,
        authorityClass: RiftMemoryAuthorityClassV1
    ): Boolean = when (record.kind) {
        RiftMemoryRecordKindV1.POLICY ->
            authorityClass == RiftMemoryAuthorityClassV1.POLICY_AUTHORITY &&
                record.authorityNamespace?.startsWith(RiftMemoryModelV1.POLICY_NAMESPACE_PREFIX) == true
        RiftMemoryRecordKindV1.CONFIG ->
            authorityClass == RiftMemoryAuthorityClassV1.CONFIG_AUTHORITY &&
                record.authorityNamespace?.startsWith(RiftMemoryModelV1.CONFIG_NAMESPACE_PREFIX) == true
        else -> record.authorityNamespace?.let { !RiftMemoryModelV1.isProtectedNamespace(it) } ?: true
    }

    private fun normalizedTrust(
        record: RiftCanonicalMemoryRecordV1,
        evidenceCeiling: RiftMemoryTrustStateV1
    ): RiftMemoryTrustStateV1 {
        if (record.branch != RiftMemoryBranchV1.REALITY) return RiftMemoryTrustStateV1.PROVISIONAL
        val desiredRank = trustRank(record.trustState)
        val ceilingRank = trustRank(evidenceCeiling)
        return when {
            desiredRank >= trustRank(RiftMemoryTrustStateV1.VERIFIED) &&
                ceilingRank >= trustRank(RiftMemoryTrustStateV1.VERIFIED) -> RiftMemoryTrustStateV1.VERIFIED
            desiredRank >= trustRank(RiftMemoryTrustStateV1.TRUSTED) &&
                ceilingRank >= trustRank(RiftMemoryTrustStateV1.TRUSTED) -> RiftMemoryTrustStateV1.TRUSTED
            else -> RiftMemoryTrustStateV1.PROVISIONAL
        }
    }

    private fun evidenceTrustCeiling(evidence: List<RiftMemoryEvidenceV1>): RiftMemoryTrustStateV1 {
        if (evidence.any { it.trustState == RiftMemoryTrustStateV1.VERIFIED }) return RiftMemoryTrustStateV1.VERIFIED
        if (evidence.any { it.trustState == RiftMemoryTrustStateV1.TRUSTED }) return RiftMemoryTrustStateV1.TRUSTED
        return RiftMemoryTrustStateV1.PROVISIONAL
    }

    private fun trustRank(state: RiftMemoryTrustStateV1): Int = when (state) {
        RiftMemoryTrustStateV1.VERIFIED -> 5
        RiftMemoryTrustStateV1.TRUSTED -> 4
        RiftMemoryTrustStateV1.PROVISIONAL -> 2
        RiftMemoryTrustStateV1.UNVERIFIED -> 1
        RiftMemoryTrustStateV1.CONFLICTED,
        RiftMemoryTrustStateV1.QUARANTINED,
        RiftMemoryTrustStateV1.SUPERSEDED,
        RiftMemoryTrustStateV1.INVALIDATED,
        RiftMemoryTrustStateV1.HISTORICAL,
        RiftMemoryTrustStateV1.ARCHIVED -> 0
    }

    private fun persistNoOverwrite(
        handle: RiftMemoryStoreHandleV1,
        candidate: RiftMemoryCandidateV1,
        normalizedCandidate: RiftCanonicalMemoryRecordV1,
        outcome: RiftMemoryTransactionOutcomeV1,
        reasons: List<String>,
        current: RiftCanonicalMemoryRecordV1?
    ): RiftMemoryReconciliationResultV1 {
        val tx = handle.beginTransaction(
            JSONObject()
                .put("phase", "N2.3")
                .put("candidateId", candidate.id)
                .put("outcome", outcome.name)
        )
        return try {
            persistCandidateEvidence(handle, tx, candidate)
            if (current != null &&
                RiftMemoryModelV1.canonicalSha256(current.payload) !=
                RiftMemoryModelV1.canonicalSha256(normalizedCandidate.payload)) {
                appendContradictionEvent(handle, tx, normalizedCandidate, current)
            }
            appendReconciliationEvent(handle, tx, candidate, normalizedCandidate, outcome, reasons, false)
            val commit = handle.commitTransaction(tx)
            runCatching {
                handle.markProjectionDirty(
                    PROJECTION_TEMPORAL_GRAPH,
                    "reconciliation:${candidate.id}:${outcome.name}"
                )
            }
            RiftMemoryReconciliationResultV1(
                candidate.id,
                outcome,
                candidate.authorityClass,
                false,
                current != null,
                tx.id,
                commit.eventSequence,
                reasons
            )
        } catch (failure: Throwable) {
            runCatching { handle.rollbackTransaction(tx) }
            throw failure
        }
    }

    private fun persistRecordDecision(
        handle: RiftMemoryStoreHandleV1,
        candidate: RiftMemoryCandidateV1,
        record: RiftCanonicalMemoryRecordV1,
        prior: RiftCanonicalMemoryRecordV1?,
        outcome: RiftMemoryTransactionOutcomeV1,
        reasons: List<String>,
        supersedePrior: Boolean,
        conflict: Boolean,
        invalidation: Boolean = false,
        contradictionCandidate: RiftCanonicalMemoryRecordV1? = null
    ): RiftMemoryReconciliationResultV1 {
        val tx = handle.beginTransaction(
            JSONObject()
                .put("phase", "N2.3")
                .put("candidateId", candidate.id)
                .put("outcome", outcome.name)
        )
        return try {
            persistCandidateEvidence(handle, tx, candidate)

            if (prior != null && supersedePrior) {
                val superseded = prior.copy(
                    trustState = RiftMemoryTrustStateV1.SUPERSEDED,
                    time = prior.time.copy(
                        validTo = maxOf(prior.time.validFrom, record.time.validFrom),
                        recordedAt = maxOf(prior.time.recordedAt, record.time.recordedAt)
                    )
                )
                handle.putCanonicalRecord(tx, superseded)
                appendRecordVersionEvent(handle, tx, superseded, "SUPERSEDED")
                appendSupersessionEvent(handle, tx, superseded, record)
            }

            handle.putCanonicalRecord(tx, record)
            appendRecordVersionEvent(handle, tx, record, outcome.name)
            if (invalidation && prior != null) appendInvalidationEvent(handle, tx, prior, record)
            if (conflict && contradictionCandidate != null && prior != null) {
                appendContradictionEvent(handle, tx, contradictionCandidate, prior)
            }
            appendReconciliationEvent(handle, tx, candidate, record, outcome, reasons, true)
            val commit = handle.commitTransaction(tx)
            runCatching {
                handle.markProjectionDirty(
                    PROJECTION_CURRENT,
                    "reconciliation:${candidate.id}:${outcome.name}"
                )
            }
            runCatching {
                handle.markProjectionDirty(
                    PROJECTION_TEMPORAL_GRAPH,
                    "reconciliation:${candidate.id}:${outcome.name}"
                )
            }
            RiftMemoryReconciliationResultV1(
                candidate.id,
                outcome,
                candidate.authorityClass,
                true,
                conflict,
                tx.id,
                commit.eventSequence,
                reasons
            )
        } catch (failure: Throwable) {
            runCatching { handle.rollbackTransaction(tx) }
            throw failure
        }
    }

    private fun persistCandidateEvidence(
        handle: RiftMemoryStoreHandleV1,
        tx: RiftMemoryTransactionV1,
        candidate: RiftMemoryCandidateV1
    ) {
        for (evidence in candidate.evidence.sortedBy { it.id }) {
            val existing = handle.readEvidence(
                RiftMemoryQueryV1(evidenceId = evidence.id),
                RiftMemoryBoundsV1(limit = 2)
            ).evidence
            if (existing.isNotEmpty()) {
                check(
                    existing.size == 1 &&
                        RiftMemoryModelV1.canonicalSha256(existing.single().toJson()) ==
                        RiftMemoryModelV1.canonicalSha256(evidence.toJson())
                ) { "Evidence identity collision: ${evidence.id}" }
                continue
            }
            val bytes = candidate.contentBySha256[evidence.contentSha256]
                ?: error("Missing candidate content for evidence ${evidence.id}")
            val storedHash = handle.putContentBlob(
                tx,
                bytes,
                JSONObject().put("mediaType", evidence.mediaType)
            )
            check(storedHash == evidence.contentSha256) {
                "Evidence content hash changed during persistence."
            }
            handle.appendEvidence(tx, evidence)
        }
    }

    private fun appendRecordVersionEvent(
        handle: RiftMemoryStoreHandleV1,
        tx: RiftMemoryTransactionV1,
        record: RiftCanonicalMemoryRecordV1,
        outcome: String
    ) {
        handle.appendEvent(
            tx,
            RiftMemoryEventV1(
                id = "event-" + UUID.randomUUID(),
                type = EVENT_RECORD_VERSION,
                recordId = record.id,
                scope = record.scope,
                branch = record.branch,
                at = record.time.recordedAt,
                validFrom = record.time.validFrom,
                validTo = record.time.validTo,
                payload = JSONObject()
                    .put("outcome", outcome)
                    .put("record", record.toJson())
                    .put("recordHash", RiftMemoryModelV1.canonicalSha256(record.toJson()))
            )
        )
    }

    private fun appendSupersessionEvent(
        handle: RiftMemoryStoreHandleV1,
        tx: RiftMemoryTransactionV1,
        previous: RiftCanonicalMemoryRecordV1,
        next: RiftCanonicalMemoryRecordV1
    ) {
        handle.appendEvent(
            tx,
            RiftMemoryEventV1(
                id = "event-" + UUID.randomUUID(),
                type = EVENT_SUPERSESSION,
                recordId = next.id,
                scope = next.scope,
                branch = next.branch,
                at = next.time.recordedAt,
                validFrom = next.time.validFrom,
                payload = JSONObject()
                    .put("previousRecordId", previous.id)
                    .put("previousVersionNode", versionNode(previous))
                    .put("nextRecordId", next.id)
                    .put("nextVersionNode", versionNode(next))
            )
        )
    }

    private fun appendInvalidationEvent(
        handle: RiftMemoryStoreHandleV1,
        tx: RiftMemoryTransactionV1,
        previous: RiftCanonicalMemoryRecordV1,
        invalidated: RiftCanonicalMemoryRecordV1
    ) {
        handle.appendEvent(
            tx,
            RiftMemoryEventV1(
                id = "event-" + UUID.randomUUID(),
                type = EVENT_INVALIDATION,
                recordId = invalidated.id,
                scope = invalidated.scope,
                branch = invalidated.branch,
                at = invalidated.time.recordedAt,
                validFrom = invalidated.time.validFrom,
                validTo = invalidated.time.validTo,
                payload = JSONObject()
                    .put("previousVersionNode", versionNode(previous))
                    .put("invalidatedVersionNode", versionNode(invalidated))
            )
        )
    }

    private fun appendContradictionEvent(
        handle: RiftMemoryStoreHandleV1,
        tx: RiftMemoryTransactionV1,
        candidate: RiftCanonicalMemoryRecordV1,
        current: RiftCanonicalMemoryRecordV1
    ) {
        handle.appendEvent(
            tx,
            RiftMemoryEventV1(
                id = "event-" + UUID.randomUUID(),
                type = EVENT_CONTRADICTION,
                recordId = current.id,
                scope = candidate.scope,
                branch = candidate.branch,
                at = candidate.time.recordedAt,
                validFrom = candidate.time.validFrom,
                payload = JSONObject()
                    .put("candidateRecordId", candidate.id)
                    .put("candidateVersionNode", versionNode(candidate))
                    .put("currentRecordId", current.id)
                    .put("currentVersionNode", versionNode(current))
            )
        )
    }

    private fun appendReconciliationEvent(
        handle: RiftMemoryStoreHandleV1,
        tx: RiftMemoryTransactionV1,
        candidate: RiftMemoryCandidateV1,
        normalizedRecord: RiftCanonicalMemoryRecordV1,
        outcome: RiftMemoryTransactionOutcomeV1,
        reasons: List<String>,
        currentChanged: Boolean
    ) {
        handle.appendEvent(
            tx,
            RiftMemoryEventV1(
                id = "event-" + UUID.randomUUID(),
                type = EVENT_RECONCILIATION,
                recordId = normalizedRecord.id,
                scope = normalizedRecord.scope,
                branch = normalizedRecord.branch,
                at = normalizedRecord.time.recordedAt,
                validFrom = normalizedRecord.time.validFrom,
                validTo = normalizedRecord.time.validTo,
                payload = JSONObject()
                    .put("candidateId", candidate.id)
                    .put("action", candidate.action.name)
                    .put("outcome", outcome.name)
                    .put("authorityClass", candidate.authorityClass.name)
                    .put("currentChanged", currentChanged)
                    .put("reasons", JSONArray(reasons))
                    .put("candidateRecord", candidate.record.toJson())
                    .put("normalizedRecord", normalizedRecord.toJson())
                    .put("metadata", JSONObject(candidate.metadata.toString()))
            )
        )
    }

    private fun mergeEvidenceRefs(first: List<String>, second: List<String>): List<String> {
        val merged = (first + second).distinct().sorted()
        check(merged.size <= RiftMemoryModelV1.MAX_EVIDENCE_REFS) {
            "Merged evidence reference bound exceeded."
        }
        return merged
    }

    private fun versionNode(record: RiftCanonicalMemoryRecordV1): String =
        "record:${record.id}@" + RiftMemoryModelV1.canonicalSha256(record.toJson())

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
