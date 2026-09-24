package com.riftos.app

import org.json.JSONArray
import org.json.JSONObject

data class RiftMemoryDifferenceResultV1(
    val matched: Boolean,
    val surpriseScore: Int,
    val importanceScore: Int,
    val discrepancyCandidate: RiftMemoryCandidateV1?
) {
    fun toJson(): JSONObject = JSONObject()
        .put("matched", matched)
        .put("surpriseScore", surpriseScore)
        .put("importanceScore", importanceScore)
        .put("hasDiscrepancyCandidate", discrepancyCandidate != null)
}

class RiftMemoryBeliefDifferenceV1 {
    companion object {
        const val MAX_CLAIMS_PER_BELIEF = 32
        const val MAX_STATEMENT_CHARS = 4_096
        const val MAX_EXPECTATION_CHARS = 4_096
        private const val MAX_LINEAGE_RECORDS = 32
    }

    fun createBeliefCandidate(
        handle: RiftMemoryStoreHandleV1,
        candidateId: String,
        recordId: String,
        claimIds: List<String>,
        statement: String,
        at: Long
    ): RiftMemoryCandidateV1 {
        RiftMemoryModelV1.requireId(candidateId)
        RiftMemoryModelV1.requireId(recordId)
        require(statement.isNotBlank() && statement.length <= MAX_STATEMENT_CHARS)
        require(claimIds.size in 1..MAX_CLAIMS_PER_BELIEF)
        require(claimIds.distinct().size == claimIds.size)
        val claims = claimIds.map { id ->
            val record = requireRecord(handle, id)
            require(record.kind == RiftMemoryRecordKindV1.CLAIM) { "belief-source-must-be-claim:$id" }
            require(record.branch == RiftMemoryBranchV1.REALITY) { "belief-source-must-be-reality:$id" }
            require(usableSource(record)) { "belief-source-not-usable:$id" }
            record
        }.sortedBy { it.id }
        val scope = commonScope(claims)
        val evidenceRefs = boundedEvidenceRefs(claims)
        val payload = JSONObject()
            .put("memoryClass", RiftMemoryCognitiveClassV1.BELIEF)
            .put("statement", statement)
            .put("lineage", lineage(claims, "claim-to-belief"))
            .put("probabilistic", true)
            .put("conflictState", "OPEN")
        return candidate(
            candidateId = candidateId,
            record = RiftCanonicalMemoryRecordV1(
                id = recordId,
                kind = RiftMemoryRecordKindV1.BELIEF,
                scope = scope,
                branch = RiftMemoryBranchV1.REALITY,
                trustState = RiftMemoryTrustStateV1.PROVISIONAL,
                time = RiftMemoryBiTemporalV1(validFrom = at, recordedAt = at),
                payload = payload,
                evidenceRefs = evidenceRefs
            ),
            stage = "belief"
        )
    }

    fun createPredictionCandidate(
        handle: RiftMemoryStoreHandleV1,
        candidateId: String,
        recordId: String,
        beliefId: String,
        expectedKey: String,
        expectedValue: String,
        importance: Int,
        at: Long
    ): RiftMemoryCandidateV1 {
        RiftMemoryModelV1.requireId(candidateId)
        RiftMemoryModelV1.requireId(recordId)
        require(expectedKey.isNotBlank() && expectedKey.length <= 256)
        require(expectedValue.isNotBlank() && expectedValue.length <= MAX_EXPECTATION_CHARS)
        require(importance in 0..100)
        val belief = requireRecord(handle, beliefId)
        require(belief.kind == RiftMemoryRecordKindV1.BELIEF)
        require(RiftMemoryCognitiveClassV1.classOf(belief) == RiftMemoryCognitiveClassV1.BELIEF) {
            "prediction-source-must-be-belief"
        }
        require(usableSource(belief)) { "prediction-source-not-usable" }

        val payload = JSONObject()
            .put("memoryClass", RiftMemoryCognitiveClassV1.PREDICTION)
            .put("expectedKey", expectedKey)
            .put("expectedValue", expectedValue)
            .put("importance", importance)
            .put("sourceBeliefId", belief.id)
            .put("sourceBeliefSha256", RiftMemoryModelV1.canonicalSha256(belief.toJson()))
            .put("predictionState", "OPEN")
        return candidate(
            candidateId = candidateId,
            record = RiftCanonicalMemoryRecordV1(
                id = recordId,
                kind = RiftMemoryRecordKindV1.BELIEF,
                scope = belief.scope,
                branch = RiftMemoryBranchV1.HYPOTHESIS,
                trustState = RiftMemoryTrustStateV1.PROVISIONAL,
                time = RiftMemoryBiTemporalV1(validFrom = at, recordedAt = at),
                payload = payload,
                evidenceRefs = emptyList()
            ),
            stage = "prediction"
        )
    }

    fun comparePredictionToObservation(
        handle: RiftMemoryStoreHandleV1,
        candidateId: String,
        discrepancyRecordId: String,
        predictionId: String,
        observationId: String,
        at: Long
    ): RiftMemoryDifferenceResultV1 {
        RiftMemoryModelV1.requireId(candidateId)
        RiftMemoryModelV1.requireId(discrepancyRecordId)
        val prediction = requireRecord(handle, predictionId)
        val observation = requireRecord(handle, observationId)
        require(RiftMemoryCognitiveClassV1.classOf(prediction) == RiftMemoryCognitiveClassV1.PREDICTION) {
            "difference-source-must-be-prediction"
        }
        require(RiftMemoryCognitiveClassV1.classOf(observation) == RiftMemoryCognitiveClassV1.OBSERVATION) {
            "difference-source-must-be-observation"
        }
        require(prediction.scope == observation.scope) { "difference-cross-scope-forbidden" }
        require(observation.branch == RiftMemoryBranchV1.REALITY) { "difference-observation-must-be-reality" }
        require(usableSource(prediction) && usableSource(observation)) { "difference-source-not-usable" }

        val expectedValue = prediction.payload.getString("expectedValue")
        val observedValue = observation.payload.getString("value")
        val importance = prediction.payload.optInt("importance", 0).coerceIn(0, 100)
        val matched = expectedValue == observedValue
        val surprise = if (matched) 0 else minOf(100, 50 + importance / 2)
        val importanceScore = maxOf(importance, surprise)
        if (matched) {
            return RiftMemoryDifferenceResultV1(
                matched = true,
                surpriseScore = surprise,
                importanceScore = importanceScore,
                discrepancyCandidate = null
            )
        }

        val payload = JSONObject()
            .put("memoryClass", RiftMemoryCognitiveClassV1.DISCREPANCY)
            .put("expectedKey", prediction.payload.getString("expectedKey"))
            .put("expectedValue", expectedValue)
            .put("observedValue", observedValue)
            .put("status", "UNRESOLVED")
            .put("surpriseScore", surprise)
            .put("importanceScore", importanceScore)
            .put("sourcePredictionId", prediction.id)
            .put("sourceObservationId", observation.id)
            .put("predictionVersionSha256", RiftMemoryModelV1.canonicalSha256(prediction.toJson()))
            .put("observationVersionSha256", RiftMemoryModelV1.canonicalSha256(observation.toJson()))
            .put("conflictKey", "discrepancy:${prediction.id}")
        val discrepancy = candidate(
            candidateId = candidateId,
            record = RiftCanonicalMemoryRecordV1(
                id = discrepancyRecordId,
                kind = RiftMemoryRecordKindV1.CLAIM,
                scope = observation.scope,
                branch = RiftMemoryBranchV1.REALITY,
                trustState = RiftMemoryTrustStateV1.PROVISIONAL,
                time = RiftMemoryBiTemporalV1(validFrom = at, recordedAt = at),
                payload = payload,
                evidenceRefs = observation.evidenceRefs.sorted()
            ),
            stage = "difference"
        )
        return RiftMemoryDifferenceResultV1(
            matched = false,
            surpriseScore = surprise,
            importanceScore = importanceScore,
            discrepancyCandidate = discrepancy
        )
    }

    fun createReflectionCandidate(
        handle: RiftMemoryStoreHandleV1,
        candidateId: String,
        recordId: String,
        beliefId: String,
        discrepancyId: String,
        statement: String,
        at: Long
    ): RiftMemoryCandidateV1 {
        require(statement.isNotBlank() && statement.length <= MAX_STATEMENT_CHARS)
        val belief = requireRecord(handle, beliefId)
        val discrepancy = requireRecord(handle, discrepancyId)
        require(RiftMemoryCognitiveClassV1.classOf(belief) == RiftMemoryCognitiveClassV1.BELIEF)
        require(RiftMemoryCognitiveClassV1.classOf(discrepancy) == RiftMemoryCognitiveClassV1.DISCREPANCY)
        require(discrepancy.payload.optString("status") == "UNRESOLVED") {
            "reflection-requires-unresolved-discrepancy"
        }
        require(belief.scope == discrepancy.scope) { "reflection-cross-scope-forbidden" }
        require(usableSource(belief) && usableSource(discrepancy)) { "reflection-source-not-usable" }

        val payload = JSONObject()
            .put("memoryClass", RiftMemoryCognitiveClassV1.REFLECTION)
            .put("statement", statement)
            .put("sourceBeliefId", belief.id)
            .put("sourceDiscrepancyId", discrepancy.id)
            .put("lineage", lineage(listOf(belief, discrepancy), "belief-discrepancy-to-reflection"))
            .put("conflictState", "OPEN")
        return candidate(
            candidateId = candidateId,
            record = RiftCanonicalMemoryRecordV1(
                id = recordId,
                kind = RiftMemoryRecordKindV1.BELIEF,
                scope = belief.scope,
                branch = RiftMemoryBranchV1.REALITY,
                trustState = RiftMemoryTrustStateV1.PROVISIONAL,
                time = RiftMemoryBiTemporalV1(validFrom = at, recordedAt = at),
                payload = payload,
                evidenceRefs = discrepancy.evidenceRefs.sorted()
            ),
            stage = "reflection"
        )
    }

    fun resolveDiscrepancyCandidate(
        handle: RiftMemoryStoreHandleV1,
        candidateId: String,
        discrepancyId: String,
        strongerObservationId: String,
        at: Long
    ): RiftMemoryCandidateV1 {
        val discrepancy = requireRecord(handle, discrepancyId)
        val observation = requireRecord(handle, strongerObservationId)
        require(RiftMemoryCognitiveClassV1.classOf(discrepancy) == RiftMemoryCognitiveClassV1.DISCREPANCY)
        require(discrepancy.payload.optString("status") == "UNRESOLVED") {
            "discrepancy-already-resolved"
        }
        require(RiftMemoryCognitiveClassV1.classOf(observation) == RiftMemoryCognitiveClassV1.OBSERVATION)
        require(discrepancy.scope == observation.scope) { "discrepancy-resolution-cross-scope-forbidden" }
        require(observation.branch == RiftMemoryBranchV1.REALITY)
        require(observation.trustState == RiftMemoryTrustStateV1.VERIFIED ||
            observation.trustState == RiftMemoryTrustStateV1.TRUSTED) {
            "discrepancy-resolution-requires-stronger-observation"
        }
        val expected = discrepancy.payload.getString("expectedValue")
        require(observation.payload.getString("value") == expected) {
            "stronger-observation-does-not-resolve-expectation"
        }
        require(observation.evidenceRefs.isNotEmpty())

        val payload = JSONObject(discrepancy.payload.toString())
            .put("observedValue", expected)
            .put("status", "RESOLVED")
            .put("surpriseScore", 0)
            .put("resolvedByObservationId", observation.id)
            .put("resolvedByObservationSha256", RiftMemoryModelV1.canonicalSha256(observation.toJson()))
            .put("previousDiscrepancyVersionSha256", RiftMemoryModelV1.canonicalSha256(discrepancy.toJson()))
        return RiftMemoryCandidateV1(
            id = candidateId,
            record = RiftCanonicalMemoryRecordV1(
                id = discrepancy.id,
                kind = RiftMemoryRecordKindV1.CLAIM,
                scope = discrepancy.scope,
                branch = RiftMemoryBranchV1.REALITY,
                trustState = observation.trustState,
                time = RiftMemoryBiTemporalV1(validFrom = at, recordedAt = at),
                payload = payload,
                evidenceRefs = observation.evidenceRefs.sorted()
            ),
            evidence = emptyList(),
            contentBySha256 = emptyMap(),
            authorityClass = RiftMemoryAuthorityClassV1.VERIFIED_SOURCE,
            metadata = JSONObject()
                .put("phase", "N2.6")
                .put("specialist", "difference")
                .put("stage", "reconcile-discrepancy")
                .put("derived", true)
        )
    }

    private fun candidate(
        candidateId: String,
        record: RiftCanonicalMemoryRecordV1,
        stage: String
    ): RiftMemoryCandidateV1 {
        RiftMemoryModelV1.requireId(candidateId)
        return RiftMemoryCandidateV1(
            id = candidateId,
            record = record,
            evidence = emptyList(),
            contentBySha256 = emptyMap(),
            authorityClass = RiftMemoryAuthorityClassV1.ORDINARY,
            metadata = JSONObject()
                .put("phase", "N2.6")
                .put("specialist", "belief-difference")
                .put("stage", stage)
                .put("derived", true)
        )
    }

    private fun requireRecord(
        handle: RiftMemoryStoreHandleV1,
        id: String
    ): RiftCanonicalMemoryRecordV1 {
        RiftMemoryModelV1.requireId(id)
        return handle.getCanonicalRecord(id)
            ?: throw IllegalArgumentException("missing-cognitive-source:$id")
    }

    private fun commonScope(records: List<RiftCanonicalMemoryRecordV1>): RiftMemoryScopeV1 {
        require(records.isNotEmpty())
        val scope = records.first().scope
        require(records.all { it.scope == scope }) { "cross-scope-cognitive-derivation-forbidden" }
        return scope
    }

    private fun boundedEvidenceRefs(records: List<RiftCanonicalMemoryRecordV1>): List<String> {
        val refs = records.flatMap { it.evidenceRefs }.distinct().sorted()
        require(refs.size <= RiftMemoryModelV1.MAX_EVIDENCE_REFS) {
            "cognitive-evidence-bound-exceeded:${RiftMemoryModelV1.MAX_EVIDENCE_REFS}"
        }
        return refs
    }

    private fun lineage(
        records: List<RiftCanonicalMemoryRecordV1>,
        transformation: String
    ): JSONObject {
        require(records.size <= MAX_LINEAGE_RECORDS)
        val rows = JSONArray()
        records.sortedBy { it.id }.forEach { record ->
            rows.put(
                JSONObject()
                    .put("recordId", record.id)
                    .put("recordSha256", RiftMemoryModelV1.canonicalSha256(record.toJson()))
                    .put("memoryClass", RiftMemoryCognitiveClassV1.classOf(record))
                    .put("trustState", record.trustState.name)
            )
        }
        return JSONObject()
            .put("transformation", transformation)
            .put("sourceRecords", rows)
    }

    private fun usableSource(record: RiftCanonicalMemoryRecordV1): Boolean =
        record.trustState !in setOf(
            RiftMemoryTrustStateV1.CONFLICTED,
            RiftMemoryTrustStateV1.QUARANTINED,
            RiftMemoryTrustStateV1.SUPERSEDED,
            RiftMemoryTrustStateV1.INVALIDATED,
            RiftMemoryTrustStateV1.HISTORICAL,
            RiftMemoryTrustStateV1.ARCHIVED
        )
}
