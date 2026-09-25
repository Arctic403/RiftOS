package com.riftos.app

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

object RiftMemorySpecialistClassV1 {
    const val SKILL = "SKILL"
    const val FAILURE = "FAILURE"
    const val CAUSAL = "CAUSAL"
    const val COMMITMENT = "COMMITMENT"
    const val POLICY = "POLICY"
}

data class RiftMemoryFailureRecurrenceV1(
    val signature: String,
    val occurrences: Int,
    val recordIds: List<String>
) {
    init {
        require(signature.isNotBlank() && signature.length <= 256)
        require(occurrences == recordIds.size)
    }

    fun toJson(): JSONObject {
        val ids = JSONArray()
        recordIds.forEach { id -> ids.put(id) }
        return JSONObject()
            .put("signature", signature)
            .put("occurrences", occurrences)
            .put("recordIds", ids)
    }
}

class RiftMemoryProceduralFailureV1 {
    companion object {
        const val MAX_SOURCE_RECORDS = 32
        const val MAX_SKILL_STEPS = 32
        const val MAX_FAILURE_SCAN = 4_096
        const val MAX_OPEN_COMMITMENTS = 256
        const val MAX_TEXT_CHARS = 2_048
    }

    fun createSkillCandidate(
        handle: RiftMemoryStoreHandleV1,
        candidateId: String,
        recordId: String,
        sourceRecordIds: List<String>,
        name: String,
        steps: List<String>,
        at: Long
    ): RiftMemoryCandidateV1 {
        require(name.isNotBlank() && name.length <= 256)
        require(steps.isNotEmpty() && steps.size <= MAX_SKILL_STEPS)
        steps.forEach { require(it.isNotBlank() && it.length <= 512) }
        val sources = sourceRecords(handle, sourceRecordIds, requireStrongEvidence = false)
        val scope = commonScope(sources)
        val branch = commonRealityBranch(sources)
        val evidenceRefs = collectEvidenceRefs(sources)
        val payload = JSONObject()
            .put("memoryClass", RiftMemorySpecialistClassV1.SKILL)
            .put("name", name)
            .put("steps", stringArray(steps))
            .put("sourceRecordIds", stringArray(sourceRecordIds.sorted()))
            .put("reversible", true)
            .put("derivedTrustCeiling", RiftMemoryTrustStateV1.PROVISIONAL.name)
            .put("conflictKey", "skill:$name")
        return derivedCandidate(candidateId, recordId, scope, branch, payload, evidenceRefs, at)
    }

    fun createFailureCandidate(
        handle: RiftMemoryStoreHandleV1,
        candidateId: String,
        recordId: String,
        sourceRecordIds: List<String>,
        signature: String,
        summary: String,
        at: Long
    ): RiftMemoryCandidateV1 {
        require(signature.isNotBlank() && signature.length <= 256)
        require(summary.isNotBlank() && summary.length <= MAX_TEXT_CHARS)
        val sources = sourceRecords(handle, sourceRecordIds, requireStrongEvidence = false)
        val scope = commonScope(sources)
        val branch = commonRealityBranch(sources)
        val evidenceRefs = collectEvidenceRefs(sources)
        val payload = JSONObject()
            .put("memoryClass", RiftMemorySpecialistClassV1.FAILURE)
            .put("signature", signature)
            .put("summary", summary)
            .put("sourceRecordIds", stringArray(sourceRecordIds.sorted()))
            .put("reversible", true)
            .put("conflictKey", "failure:$recordId")
        return derivedCandidate(candidateId, recordId, scope, branch, payload, evidenceRefs, at)
    }

    fun detectFailureRecurrence(
        handle: RiftMemoryStoreHandleV1,
        namespace: String,
        projectId: String,
        signature: String
    ): RiftMemoryFailureRecurrenceV1 {
        require(namespace.isNotBlank() && namespace.length <= 256)
        require(projectId.isNotBlank() && projectId.length <= 256)
        require(signature.isNotBlank() && signature.length <= 256)
        val matches = readCurrentProjectRecords(handle, namespace, projectId)
            .filter {
                isVisible(it) &&
                    it.payload.optString("memoryClass") == RiftMemorySpecialistClassV1.FAILURE &&
                    it.payload.optString("signature") == signature
            }
            .map { it.id }
            .sorted()
        return RiftMemoryFailureRecurrenceV1(signature, matches.size, matches)
    }

    fun createCausalCandidate(
        handle: RiftMemoryStoreHandleV1,
        candidateId: String,
        recordId: String,
        causeRecordId: String,
        effectRecordId: String,
        confidence: Int,
        at: Long
    ): RiftMemoryCandidateV1 {
        require(causeRecordId != effectRecordId)
        require(confidence in 1..100)
        val sources = sourceRecords(
            handle,
            listOf(causeRecordId, effectRecordId),
            requireStrongEvidence = true
        )
        val scope = commonScope(sources)
        val branch = commonRealityBranch(sources)
        val evidenceRefs = collectEvidenceRefs(sources)
        val payload = JSONObject()
            .put("memoryClass", RiftMemorySpecialistClassV1.CAUSAL)
            .put("causeRecordId", causeRecordId)
            .put("effectRecordId", effectRecordId)
            .put("confidence", confidence)
            .put("explicitEvidenceBacked", true)
            .put("sourceRecordIds", stringArray(listOf(causeRecordId, effectRecordId).sorted()))
            .put("reversible", true)
            .put("derivedTrustCeiling", RiftMemoryTrustStateV1.PROVISIONAL.name)
            .put("conflictKey", "causal:$causeRecordId:$effectRecordId")
        return derivedCandidate(candidateId, recordId, scope, branch, payload, evidenceRefs, at)
    }

    fun createCommitmentCandidate(
        handle: RiftMemoryStoreHandleV1,
        candidateId: String,
        recordId: String,
        sourceRecordIds: List<String>,
        title: String,
        resumeKey: String,
        status: String,
        at: Long
    ): RiftMemoryCandidateV1 {
        require(title.isNotBlank() && title.length <= 512)
        require(resumeKey.isNotBlank() && resumeKey.length <= 256)
        require(status in setOf("OPEN", "COMPLETED", "CANCELLED"))
        val sources = sourceRecords(handle, sourceRecordIds, requireStrongEvidence = false)
        val scope = commonScope(sources)
        val branch = commonRealityBranch(sources)
        val evidenceRefs = collectEvidenceRefs(sources)
        val payload = JSONObject()
            .put("memoryClass", RiftMemorySpecialistClassV1.COMMITMENT)
            .put("title", title)
            .put("resumeKey", resumeKey)
            .put("status", status)
            .put("sourceRecordIds", stringArray(sourceRecordIds.sorted()))
            .put("reversible", true)
            .put("conflictKey", "commitment:$resumeKey")
        return derivedCandidate(candidateId, recordId, scope, branch, payload, evidenceRefs, at)
    }

    fun openCommitments(
        handle: RiftMemoryStoreHandleV1,
        namespace: String,
        projectId: String
    ): List<RiftCanonicalMemoryRecordV1> {
        val open = readCurrentProjectRecords(handle, namespace, projectId)
            .filter {
                isVisible(it) &&
                    it.payload.optString("memoryClass") == RiftMemorySpecialistClassV1.COMMITMENT &&
                    it.payload.optString("status") == "OPEN"
            }
            .sortedWith(compareBy<RiftCanonicalMemoryRecordV1>({ it.time.validFrom }, { it.id }))
        check(open.size <= MAX_OPEN_COMMITMENTS) {
            "commitment-open-bound-exceeded:$MAX_OPEN_COMMITMENTS"
        }
        return open
    }

    fun createPolicyCandidate(
        candidateId: String,
        recordId: String,
        scope: RiftMemoryScopeV1,
        policyKey: String,
        value: String,
        evidenceId: String,
        evidenceBytes: ByteArray,
        authorityClass: RiftMemoryAuthorityClassV1,
        at: Long
    ): RiftMemoryCandidateV1 {
        require(policyKey.isNotBlank() && policyKey.length <= 128)
        require(value.isNotBlank() && value.length <= MAX_TEXT_CHARS)
        require(evidenceBytes.isNotEmpty() && evidenceBytes.size <= 65_536)
        val hash = sha256(evidenceBytes)
        val evidence = RiftMemoryEvidenceV1(
            id = evidenceId,
            contentSha256 = hash,
            mediaType = "text/plain",
            sourceType = "diagnostic-policy-source",
            sourceRef = "n2-m4/policy/$policyKey/$evidenceId",
            scope = scope,
            branch = RiftMemoryBranchV1.REALITY,
            observedAt = at,
            recordedAt = at,
            trustState = RiftMemoryTrustStateV1.VERIFIED,
            metadata = JSONObject()
                .put("policyKey", policyKey)
                .put("authorityClass", authorityClass.name)
        )
        val record = RiftCanonicalMemoryRecordV1(
            id = recordId,
            kind = RiftMemoryRecordKindV1.POLICY,
            scope = scope,
            branch = RiftMemoryBranchV1.REALITY,
            trustState = RiftMemoryTrustStateV1.VERIFIED,
            time = RiftMemoryBiTemporalV1(validFrom = at, recordedAt = at),
            payload = JSONObject()
                .put("memoryClass", RiftMemorySpecialistClassV1.POLICY)
                .put("policyKey", policyKey)
                .put("value", value)
                .put("conflictKey", "policy:$policyKey"),
            evidenceRefs = listOf(evidence.id),
            authorityNamespace = RiftMemoryModelV1.POLICY_NAMESPACE_PREFIX + policyKey
        )
        return RiftMemoryCandidateV1(
            id = candidateId,
            record = record,
            evidence = listOf(evidence),
            contentBySha256 = mapOf(hash to evidenceBytes.copyOf()),
            authorityClass = authorityClass,
            metadata = JSONObject()
                .put("phase", "N2.7")
                .put("specialist", RiftMemorySpecialistClassV1.POLICY)
                .put("authorityMustBeExplicit", true)
        )
    }

    private fun derivedCandidate(
        candidateId: String,
        recordId: String,
        scope: RiftMemoryScopeV1,
        branch: RiftMemoryBranchV1,
        payload: JSONObject,
        evidenceRefs: List<String>,
        at: Long
    ): RiftMemoryCandidateV1 {
        val record = RiftCanonicalMemoryRecordV1(
            id = recordId,
            kind = RiftMemoryRecordKindV1.CLAIM,
            scope = scope,
            branch = branch,
            trustState = RiftMemoryTrustStateV1.PROVISIONAL,
            time = RiftMemoryBiTemporalV1(validFrom = at, recordedAt = at),
            payload = payload,
            evidenceRefs = evidenceRefs
        )
        return RiftMemoryCandidateV1(
            id = candidateId,
            record = record,
            evidence = emptyList(),
            contentBySha256 = emptyMap(),
            authorityClass = RiftMemoryAuthorityClassV1.ORDINARY,
            metadata = JSONObject()
                .put("phase", "N2.7")
                .put("specialist", payload.optString("memoryClass"))
                .put("derivedAuthority", false)
        )
    }

    private fun sourceRecords(
        handle: RiftMemoryStoreHandleV1,
        sourceRecordIds: List<String>,
        requireStrongEvidence: Boolean
    ): List<RiftCanonicalMemoryRecordV1> {
        require(sourceRecordIds.isNotEmpty() && sourceRecordIds.size <= MAX_SOURCE_RECORDS)
        require(sourceRecordIds.distinct().size == sourceRecordIds.size)
        val records = sourceRecordIds.map { id ->
            RiftMemoryModelV1.requireId(id)
            handle.getCanonicalRecord(id)
                ?: throw IllegalArgumentException("specialist-source-missing:$id")
        }
        records.forEach { record ->
            require(isVisible(record)) { "specialist-source-not-current:" + record.id }
            if (requireStrongEvidence) {
                require(record.trustState in setOf(RiftMemoryTrustStateV1.TRUSTED, RiftMemoryTrustStateV1.VERIFIED)) {
                    "causal-source-not-strong:" + record.id
                }
                require(record.evidenceRefs.isNotEmpty()) {
                    "causal-source-evidence-missing:" + record.id
                }
            }
        }
        commonScope(records)
        commonRealityBranch(records)
        return records
    }

    private fun commonScope(records: List<RiftCanonicalMemoryRecordV1>): RiftMemoryScopeV1 {
        val scope = records.first().scope
        require(records.all { it.scope == scope }) { "specialist-cross-scope-forbidden" }
        return scope
    }

    private fun commonRealityBranch(records: List<RiftCanonicalMemoryRecordV1>): RiftMemoryBranchV1 {
        val branch = records.first().branch
        require(records.all { it.branch == branch }) { "specialist-cross-branch-forbidden" }
        require(branch == RiftMemoryBranchV1.REALITY) { "specialist-reality-source-required" }
        return branch
    }

    private fun collectEvidenceRefs(records: List<RiftCanonicalMemoryRecordV1>): List<String> {
        val refs = records.flatMap { it.evidenceRefs }.distinct().sorted()
        check(refs.size <= RiftMemoryModelV1.MAX_EVIDENCE_REFS) {
            "specialist-evidence-bound-exceeded:" + RiftMemoryModelV1.MAX_EVIDENCE_REFS
        }
        return refs
    }

    private fun readCurrentProjectRecords(
        handle: RiftMemoryStoreHandleV1,
        namespace: String,
        projectId: String
    ): List<RiftCanonicalMemoryRecordV1> {
        val out = mutableListOf<RiftCanonicalMemoryRecordV1>()
        var offset = 0
        while (offset < MAX_FAILURE_SCAN) {
            val limit = minOf(1_000, MAX_FAILURE_SCAN - offset)
            val page = handle.scanCanonicalRecords(
                RiftMemoryQueryV1(
                    namespace = namespace,
                    projectId = projectId,
                    branch = RiftMemoryBranchV1.REALITY
                ),
                RiftMemoryBoundsV1(offset = offset, limit = limit)
            )
            out += page.records
            offset += page.records.size
            if (page.records.size < limit) return out
        }
        val overflow = handle.scanCanonicalRecords(
            RiftMemoryQueryV1(
                namespace = namespace,
                projectId = projectId,
                branch = RiftMemoryBranchV1.REALITY
            ),
            RiftMemoryBoundsV1(offset = MAX_FAILURE_SCAN, limit = 1)
        ).records.isNotEmpty()
        check(!overflow) { "specialist-record-scan-bound-exceeded:$MAX_FAILURE_SCAN" }
        return out
    }

    private fun isVisible(record: RiftCanonicalMemoryRecordV1): Boolean =
        record.trustState !in setOf(
            RiftMemoryTrustStateV1.CONFLICTED,
            RiftMemoryTrustStateV1.QUARANTINED,
            RiftMemoryTrustStateV1.SUPERSEDED,
            RiftMemoryTrustStateV1.INVALIDATED,
            RiftMemoryTrustStateV1.HISTORICAL,
            RiftMemoryTrustStateV1.ARCHIVED
        )

    private fun stringArray(values: List<String>): JSONArray =
        JSONArray().also { array -> values.forEach { value -> array.put(value) } }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
