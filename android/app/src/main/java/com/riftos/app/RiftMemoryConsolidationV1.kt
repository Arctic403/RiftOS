package com.riftos.app

import org.json.JSONArray
import org.json.JSONObject

object RiftMemoryCognitiveClassV1 {
    const val OBSERVATION = "OBSERVATION"
    const val EPISODE = "EPISODE"
    const val EPISODE_CLUSTER = "EPISODE_CLUSTER"
    const val PATTERN = "PATTERN"
    const val SEMANTIC = "SEMANTIC"
    const val BELIEF = "BELIEF"
    const val PREDICTION = "PREDICTION"
    const val REFLECTION = "REFLECTION"
    const val DISCREPANCY = "DISCREPANCY"

    fun classOf(record: RiftCanonicalMemoryRecordV1): String =
        record.payload.optString("memoryClass")
}

class RiftMemoryConsolidationV1 {
    companion object {
        const val MAX_OBSERVATIONS_PER_EPISODE = 64
        const val MAX_EPISODES_PER_CLUSTER = 64
        const val MAX_CLUSTERS_PER_PATTERN = 32
        const val MAX_PATTERNS_PER_SEMANTIC = 32
        private const val MAX_LINEAGE_RECORDS = 64
    }

    fun createEpisodeCandidate(
        handle: RiftMemoryStoreHandleV1,
        candidateId: String,
        recordId: String,
        observationIds: List<String>,
        at: Long
    ): RiftMemoryCandidateV1 {
        val sources = loadSources(
            handle,
            observationIds,
            RiftMemoryCognitiveClassV1.OBSERVATION,
            MAX_OBSERVATIONS_PER_EPISODE,
            minCount = 1
        )
        val subject = commonSubject(sources)
        val values = JSONArray()
        sources.forEach { values.put(it.payload.getString("value")) }
        val payload = JSONObject()
            .put("memoryClass", RiftMemoryCognitiveClassV1.EPISODE)
            .put("subject", subject)
            .put("observedValues", values)
            .put("lineage", lineage(sources, "observation-to-episode"))
            .put("reversible", true)
        return derivedCandidate(
            candidateId,
            recordId,
            sources,
            RiftMemoryRecordKindV1.CLAIM,
            sources.first().branch,
            payload,
            at,
            "N2.5/episode"
        )
    }

    fun createEpisodeClusterCandidate(
        handle: RiftMemoryStoreHandleV1,
        candidateId: String,
        recordId: String,
        episodeIds: List<String>,
        at: Long
    ): RiftMemoryCandidateV1 {
        val sources = loadSources(
            handle,
            episodeIds,
            RiftMemoryCognitiveClassV1.EPISODE,
            MAX_EPISODES_PER_CLUSTER,
            minCount = 1
        )
        val subject = commonSubject(sources)
        val counts = linkedMapOf<String, Int>()
        for (episode in sources) {
            val values = episode.payload.getJSONArray("observedValues")
            for (i in 0 until values.length()) {
                val value = values.getString(i)
                counts[value] = (counts[value] ?: 0) + 1
            }
        }
        val sortedCounts = counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        val dominant = sortedCounts.firstOrNull()
        val uniqueDominant = dominant != null && sortedCounts.drop(1).none { it.value == dominant.value }
        val histogram = JSONObject()
        counts.toSortedMap().forEach { (value, count) -> histogram.put(value, count) }

        val payload = JSONObject()
            .put("memoryClass", RiftMemoryCognitiveClassV1.EPISODE_CLUSTER)
            .put("subject", subject)
            .put("valueHistogram", histogram)
            .put("dominantValue", if (uniqueDominant) dominant!!.key else JSONObject.NULL)
            .put("dominantCount", if (uniqueDominant) dominant!!.value else 0)
            .put("lineage", lineage(sources, "episode-clustering"))
            .put("reversible", true)
        return derivedCandidate(
            candidateId,
            recordId,
            sources,
            RiftMemoryRecordKindV1.CLAIM,
            sources.first().branch,
            payload,
            at,
            "N2.5/cluster"
        )
    }

    fun extractPatternCandidate(
        handle: RiftMemoryStoreHandleV1,
        candidateId: String,
        recordId: String,
        clusterIds: List<String>,
        at: Long
    ): RiftMemoryCandidateV1 {
        val sources = loadSources(
            handle,
            clusterIds,
            RiftMemoryCognitiveClassV1.EPISODE_CLUSTER,
            MAX_CLUSTERS_PER_PATTERN,
            minCount = 1
        )
        val subject = commonSubject(sources)
        val values = sources.map {
            it.payload.optString("dominantValue").takeIf { value -> value.isNotBlank() }
                ?: throw IllegalArgumentException("pattern-source-missing-dominant-value:${it.id}")
        }.distinct()
        require(values.size == 1) { "pattern-source-disagreement:${values.sorted().joinToString(",")}" }

        val payload = JSONObject()
            .put("memoryClass", RiftMemoryCognitiveClassV1.PATTERN)
            .put("subject", subject)
            .put("value", values.single())
            .put("lineage", lineage(sources, "pattern-extraction"))
            .put("reversible", true)
        return derivedCandidate(
            candidateId,
            recordId,
            sources,
            RiftMemoryRecordKindV1.CLAIM,
            sources.first().branch,
            payload,
            at,
            "N2.5/pattern"
        )
    }

    fun createSemanticCandidate(
        handle: RiftMemoryStoreHandleV1,
        candidateId: String,
        recordId: String,
        patternIds: List<String>,
        at: Long
    ): RiftMemoryCandidateV1 {
        val sources = loadSources(
            handle,
            patternIds,
            RiftMemoryCognitiveClassV1.PATTERN,
            MAX_PATTERNS_PER_SEMANTIC,
            minCount = 1
        )
        require(sources.all { it.branch == RiftMemoryBranchV1.REALITY }) {
            "semantic-candidate-requires-reality-patterns"
        }
        val subject = commonSubject(sources)
        val values = sources.map { it.payload.getString("value") }.distinct()
        require(values.size == 1) { "semantic-source-disagreement:${values.sorted().joinToString(",")}" }

        val payload = JSONObject()
            .put("memoryClass", RiftMemoryCognitiveClassV1.SEMANTIC)
            .put("subject", subject)
            .put("value", values.single())
            .put("conflictKey", "semantic:$subject")
            .put("lineage", lineage(sources, "semantic-candidate"))
            .put("reversible", true)
            .put("derivedTrustCeiling", RiftMemoryTrustStateV1.PROVISIONAL.name)
        return derivedCandidate(
            candidateId,
            recordId,
            sources,
            RiftMemoryRecordKindV1.CLAIM,
            RiftMemoryBranchV1.REALITY,
            payload,
            at,
            "N2.5/semantic"
        )
    }

    private fun derivedCandidate(
        candidateId: String,
        recordId: String,
        sources: List<RiftCanonicalMemoryRecordV1>,
        kind: RiftMemoryRecordKindV1,
        branch: RiftMemoryBranchV1,
        payload: JSONObject,
        at: Long,
        stage: String
    ): RiftMemoryCandidateV1 {
        RiftMemoryModelV1.requireId(candidateId)
        RiftMemoryModelV1.requireId(recordId)
        require(at >= 0L)
        val first = sources.first()
        require(sources.all { it.scope == first.scope }) { "cross-scope-consolidation-forbidden" }
        val evidenceRefs = sources
            .flatMap { it.evidenceRefs }
            .distinct()
            .sorted()
        require(evidenceRefs.size <= RiftMemoryModelV1.MAX_EVIDENCE_REFS) {
            "consolidation-evidence-bound-exceeded:${RiftMemoryModelV1.MAX_EVIDENCE_REFS}"
        }
        val record = RiftCanonicalMemoryRecordV1(
            id = recordId,
            kind = kind,
            scope = first.scope,
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
                .put("phase", "N2.5")
                .put("specialist", "consolidation")
                .put("stage", stage)
                .put("derived", true)
        )
    }

    private fun loadSources(
        handle: RiftMemoryStoreHandleV1,
        ids: List<String>,
        expectedClass: String,
        maxCount: Int,
        minCount: Int
    ): List<RiftCanonicalMemoryRecordV1> {
        require(ids.size in minCount..maxCount) { "consolidation-source-count-out-of-range:$minCount..$maxCount" }
        require(ids.distinct().size == ids.size) { "duplicate-consolidation-source-id" }
        ids.forEach(RiftMemoryModelV1::requireId)
        val records = ids.map { id ->
            handle.getCanonicalRecord(id) ?: throw IllegalArgumentException("missing-consolidation-source:$id")
        }.sortedBy { it.id }
        val first = records.first()
        require(records.all { it.scope == first.scope }) { "cross-scope-consolidation-forbidden" }
        require(records.all { it.branch == first.branch }) { "cross-branch-consolidation-forbidden" }
        require(records.all { RiftMemoryCognitiveClassV1.classOf(it) == expectedClass }) {
            "consolidation-source-class-mismatch:$expectedClass"
        }
        require(records.all(::usableSource)) { "non-usable-consolidation-source" }
        return records
    }

    private fun commonSubject(records: List<RiftCanonicalMemoryRecordV1>): String {
        val subjects = records.map { it.payload.optString("subject") }.filter { it.isNotBlank() }.distinct()
        require(subjects.size == 1) { "consolidation-subject-mismatch" }
        require(subjects.single().length <= 256)
        return subjects.single()
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
