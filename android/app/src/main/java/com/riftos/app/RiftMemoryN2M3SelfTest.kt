package com.riftos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

object RiftMemoryN2M3SelfTest {
    const val SCHEMA = "rift-memory-n2-m3-selftest-v1"
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
        val dbFile = File(proofDir, "n2-m3-proof.sqlite")
        dbFile.delete()
        File(dbFile.absolutePath + "-journal").delete()

        val store = RiftSqliteMemoryStoreV1()
        val config = RiftMemoryStoreConfigV1(dbFile.absolutePath)
        var handle = store.open(config)
        val reconcile = RiftMemoryReconciliationV1()
        val consolidate = RiftMemoryConsolidationV1()
        val cognitive = RiftMemoryBeliefDifferenceV1()
        val namespace = "diagnostic/n2-m3"
        val scope = RiftMemoryScopeV1(namespace, "riftfs/workspace", "RiftOS-main")
        val otherScope = RiftMemoryScopeV1(namespace, "riftfs/workspace", "OtherProject")
        val base = 2_000_000L

        val observationIds = listOf("obs-green-1", "obs-green-2", "obs-green-3", "obs-green-4")
        val observationResults = observationIds.mapIndexed { index, id ->
            reconcile.reconcile(
                handle,
                observationCandidate(
                    candidateId = "candidate-$id",
                    recordId = id,
                    scope = scope,
                    subject = "builder.status",
                    value = "green",
                    at = base + index * 10L
                )
            )
        }
        val observationsVerified = observationResults.all {
            it.outcome == RiftMemoryTransactionOutcomeV1.COMMITTED
        } && observationIds.all {
            handle.getCanonicalRecord(it)?.trustState == RiftMemoryTrustStateV1.VERIFIED
        }

        val conflictedObservationA = reconcile.reconcile(
            handle,
            observationCandidate(
                candidateId = "candidate-conflicted-observation-a",
                recordId = "obs-conflicted",
                scope = scope,
                subject = "builder.status",
                value = "green",
                at = base + 31
            )
        )
        val conflictedObservationB = reconcile.reconcile(
            handle,
            observationCandidate(
                candidateId = "candidate-conflicted-observation-b",
                recordId = "obs-conflicted",
                scope = scope,
                subject = "builder.status",
                value = "red",
                at = base + 32
            )
        )
        val conflictedSourceRejected =
            conflictedObservationA.outcome == RiftMemoryTransactionOutcomeV1.COMMITTED &&
            conflictedObservationB.outcome == RiftMemoryTransactionOutcomeV1.QUARANTINED &&
            handle.getCanonicalRecord("obs-conflicted")?.trustState == RiftMemoryTrustStateV1.CONFLICTED &&
            runCatching {
                consolidate.createEpisodeCandidate(
                    handle,
                    "candidate-conflicted-episode",
                    "conflicted-episode",
                    listOf("obs-conflicted"),
                    base + 33
                )
            }.isFailure

        val otherObservation = observationCandidate(
            candidateId = "candidate-other-project",
            recordId = "obs-other-project",
            scope = otherScope,
            subject = "builder.status",
            value = "green",
            at = base + 35
        )
        val otherObservationResult = reconcile.reconcile(handle, otherObservation)
        val projectIsolation = otherObservationResult.outcome == RiftMemoryTransactionOutcomeV1.COMMITTED &&
            runCatching {
                consolidate.createEpisodeCandidate(
                    handle,
                    "candidate-cross-project-episode",
                    "cross-project-episode",
                    listOf("obs-green-1", "obs-other-project"),
                    base + 36
                )
            }.isFailure

        val sourceBoundFailClosed = runCatching {
            consolidate.createEpisodeCandidate(
                handle,
                "candidate-too-many-observations",
                "too-many-observations",
                List(RiftMemoryConsolidationV1.MAX_OBSERVATIONS_PER_EPISODE + 1) { "overflow-observation-$it" },
                base + 37
            )
        }.isFailure

        val episodeAResult = reconcile.reconcile(
            handle,
            consolidate.createEpisodeCandidate(
                handle,
                "candidate-episode-a",
                "episode-a",
                listOf("obs-green-1", "obs-green-2"),
                base + 40
            )
        )
        val episodeBResult = reconcile.reconcile(
            handle,
            consolidate.createEpisodeCandidate(
                handle,
                "candidate-episode-b",
                "episode-b",
                listOf("obs-green-3", "obs-green-4"),
                base + 50
            )
        )
        val episodeA = handle.getCanonicalRecord("episode-a")
        val episodeB = handle.getCanonicalRecord("episode-b")
        val observationToEpisode =
            episodeAResult.outcome == RiftMemoryTransactionOutcomeV1.PROVISIONAL &&
            episodeBResult.outcome == RiftMemoryTransactionOutcomeV1.PROVISIONAL &&
            episodeA?.payload?.optString("memoryClass") == RiftMemoryCognitiveClassV1.EPISODE &&
            episodeB?.payload?.optString("memoryClass") == RiftMemoryCognitiveClassV1.EPISODE

        val clusterResult = reconcile.reconcile(
            handle,
            consolidate.createEpisodeClusterCandidate(
                handle,
                "candidate-cluster-a",
                "cluster-a",
                listOf("episode-a", "episode-b"),
                base + 60
            )
        )
        val cluster = handle.getCanonicalRecord("cluster-a")
        val episodeClustering =
            clusterResult.outcome == RiftMemoryTransactionOutcomeV1.PROVISIONAL &&
            cluster?.payload?.optString("memoryClass") == RiftMemoryCognitiveClassV1.EPISODE_CLUSTER &&
            cluster?.payload?.optString("dominantValue") == "green"

        val patternResult = reconcile.reconcile(
            handle,
            consolidate.extractPatternCandidate(
                handle,
                "candidate-pattern-a",
                "pattern-a",
                listOf("cluster-a"),
                base + 70
            )
        )
        val pattern = handle.getCanonicalRecord("pattern-a")
        val patternExtraction =
            patternResult.outcome == RiftMemoryTransactionOutcomeV1.PROVISIONAL &&
            pattern?.payload?.optString("memoryClass") == RiftMemoryCognitiveClassV1.PATTERN &&
            pattern?.payload?.optString("value") == "green"

        val semanticResult = reconcile.reconcile(
            handle,
            consolidate.createSemanticCandidate(
                handle,
                "candidate-semantic-a",
                "semantic-builder-status",
                listOf("pattern-a"),
                base + 80
            )
        )
        val semantic = handle.getCanonicalRecord("semantic-builder-status")
        val semanticCandidate =
            semanticResult.outcome == RiftMemoryTransactionOutcomeV1.PROVISIONAL &&
            semantic?.payload?.optString("memoryClass") == RiftMemoryCognitiveClassV1.SEMANTIC &&
            semantic?.payload?.optString("value") == "green"
        val unsupportedTrustBlocked =
            semantic?.trustState == RiftMemoryTrustStateV1.PROVISIONAL &&
            semantic?.payload?.optString("derivedTrustCeiling") == RiftMemoryTrustStateV1.PROVISIONAL.name
        val expectedEvidence = observationIds.map { "ev-candidate-$it" }.sorted()
        val provenancePreserved = semantic?.evidenceRefs?.sorted() == expectedEvidence
        val reversibleLineage =
            lineageIds(episodeA) == listOf("obs-green-1", "obs-green-2") &&
            lineageIds(episodeB) == listOf("obs-green-3", "obs-green-4") &&
            lineageIds(cluster) == listOf("episode-a", "episode-b") &&
            lineageIds(pattern) == listOf("cluster-a") &&
            lineageIds(semantic) == listOf("pattern-a") &&
            listOfNotNull(episodeA, episodeB, cluster, pattern, semantic)
                .all { it.payload.optBoolean("reversible", false) }

        val beliefResult = reconcile.reconcile(
            handle,
            cognitive.createBeliefCandidate(
                handle,
                "candidate-belief-a",
                "belief-builder-green",
                listOf("semantic-builder-status"),
                "Builder status is likely to remain green.",
                base + 90
            )
        )
        val belief = handle.getCanonicalRecord("belief-builder-green")

        val predictionResult = reconcile.reconcile(
            handle,
            cognitive.createPredictionCandidate(
                handle,
                "candidate-prediction-a",
                "prediction-builder-green",
                "belief-builder-green",
                "builder.status",
                "green",
                importance = 80,
                at = base + 100
            )
        )
        val prediction = handle.getCanonicalRecord("prediction-builder-green")
        val objectSeparation =
            semantic?.kind == RiftMemoryRecordKindV1.CLAIM &&
            belief?.kind == RiftMemoryRecordKindV1.BELIEF &&
            belief?.payload?.optString("memoryClass") == RiftMemoryCognitiveClassV1.BELIEF &&
            prediction?.kind == RiftMemoryRecordKindV1.BELIEF &&
            prediction?.branch == RiftMemoryBranchV1.HYPOTHESIS &&
            prediction?.payload?.optString("memoryClass") == RiftMemoryCognitiveClassV1.PREDICTION &&
            beliefResult.outcome == RiftMemoryTransactionOutcomeV1.PROVISIONAL &&
            predictionResult.outcome == RiftMemoryTransactionOutcomeV1.PROVISIONAL &&
            prediction?.evidenceRefs?.isEmpty() == true

        val redObservationResult = reconcile.reconcile(
            handle,
            observationCandidate(
                candidateId = "candidate-obs-red",
                recordId = "obs-red",
                scope = scope,
                subject = "builder.status",
                value = "red",
                at = base + 110
            )
        )
        val difference = cognitive.comparePredictionToObservation(
            handle,
            candidateId = "candidate-discrepancy-a",
            discrepancyRecordId = "discrepancy-builder-status",
            predictionId = "prediction-builder-green",
            observationId = "obs-red",
            at = base + 115
        )
        val discrepancyResult = reconcile.reconcile(
            handle,
            difference.discrepancyCandidate
                ?: throw IllegalStateException("expected mismatch discrepancy candidate")
        )
        val unresolvedDiscrepancy = handle.getCanonicalRecord("discrepancy-builder-status")
        val discrepancyCreated =
            redObservationResult.outcome == RiftMemoryTransactionOutcomeV1.COMMITTED &&
            !difference.matched &&
            discrepancyResult.outcome == RiftMemoryTransactionOutcomeV1.PROVISIONAL &&
            unresolvedDiscrepancy?.payload?.optString("memoryClass") == RiftMemoryCognitiveClassV1.DISCREPANCY &&
            unresolvedDiscrepancy?.payload?.optString("status") == "UNRESOLVED"
        val surpriseImportanceScored =
            difference.surpriseScore in 1..100 &&
            difference.importanceScore in difference.surpriseScore..100 &&
            unresolvedDiscrepancy?.payload?.optInt("surpriseScore") == difference.surpriseScore

        val reflectionResult = reconcile.reconcile(
            handle,
            cognitive.createReflectionCandidate(
                handle,
                "candidate-reflection-a",
                "reflection-builder-belief",
                "belief-builder-green",
                "discrepancy-builder-status",
                "The earlier belief may not explain the observed builder state.",
                base + 120
            )
        )
        val reflection = handle.getCanonicalRecord("reflection-builder-belief")
        val reflectionCreated =
            reflectionResult.outcome == RiftMemoryTransactionOutcomeV1.PROVISIONAL &&
            reflection?.kind == RiftMemoryRecordKindV1.BELIEF &&
            reflection?.payload?.optString("memoryClass") == RiftMemoryCognitiveClassV1.REFLECTION

        val matchingObservationResult = reconcile.reconcile(
            handle,
            observationCandidate(
                candidateId = "candidate-obs-green-stronger",
                recordId = "obs-green-stronger",
                scope = scope,
                subject = "builder.status",
                value = "green",
                at = base + 130
            )
        )
        val matchingDifference = cognitive.comparePredictionToObservation(
            handle,
            candidateId = "candidate-unused-match",
            discrepancyRecordId = "unused-match-discrepancy",
            predictionId = "prediction-builder-green",
            observationId = "obs-green-stronger",
            at = base + 131
        )
        val matchingPredictionNoDiscrepancy =
            matchingObservationResult.outcome == RiftMemoryTransactionOutcomeV1.COMMITTED &&
            matchingDifference.matched &&
            matchingDifference.surpriseScore == 0 &&
            matchingDifference.discrepancyCandidate == null

        val beforeResolution = RiftMemoryTemporalGraphV1.reconstructAt(
            handle,
            RiftMemoryQueryV1(
                namespace = namespace,
                projectId = scope.projectId,
                branch = RiftMemoryBranchV1.REALITY
            ),
            validAt = base + 120,
            recordedAt = base + 120
        )
        val resolutionResult = reconcile.reconcile(
            handle,
            cognitive.resolveDiscrepancyCandidate(
                handle,
                candidateId = "candidate-resolve-discrepancy",
                discrepancyId = "discrepancy-builder-status",
                strongerObservationId = "obs-green-stronger",
                at = base + 140
            )
        )
        val resolvedDiscrepancy = handle.getCanonicalRecord("discrepancy-builder-status")
        val strongerObservationReconciles =
            resolutionResult.outcome == RiftMemoryTransactionOutcomeV1.COMMITTED &&
            resolvedDiscrepancy?.payload?.optString("status") == "RESOLVED" &&
            resolvedDiscrepancy?.trustState == RiftMemoryTrustStateV1.VERIFIED
        val historicalAfterResolution = RiftMemoryTemporalGraphV1.reconstructAt(
            handle,
            RiftMemoryQueryV1(
                namespace = namespace,
                projectId = scope.projectId,
                branch = RiftMemoryBranchV1.REALITY
            ),
            validAt = base + 120,
            recordedAt = base + 120
        )
        val nonDestructiveHistory =
            beforeResolution.records.singleOrNull { it.id == "discrepancy-builder-status" }
                ?.payload?.optString("status") == "UNRESOLVED" &&
            historicalAfterResolution.records.singleOrNull { it.id == "discrepancy-builder-status" }
                ?.payload?.optString("status") == "UNRESOLVED" &&
            resolvedDiscrepancy?.payload?.optString("previousDiscrepancyVersionSha256")?.isNotBlank() == true

        val semanticBeforeClose = semantic?.let { RiftMemoryModelV1.canonicalSha256(it.toJson()) }
        val resolvedBeforeClose = resolvedDiscrepancy?.let { RiftMemoryModelV1.canonicalSha256(it.toJson()) }
        handle.close()
        handle = store.open(RiftMemoryStoreConfigV1(dbFile.absolutePath, createIfMissing = false))
        val reopenedSemantic = handle.getCanonicalRecord("semantic-builder-status")
        val reopenedDiscrepancy = handle.getCanonicalRecord("discrepancy-builder-status")
        val closeReopenCognitiveState =
            semanticBeforeClose != null &&
            resolvedBeforeClose != null &&
            reopenedSemantic?.let { RiftMemoryModelV1.canonicalSha256(it.toJson()) } == semanticBeforeClose &&
            reopenedDiscrepancy?.let { RiftMemoryModelV1.canonicalSha256(it.toJson()) } == resolvedBeforeClose

        val integrity = handle.verifyIntegrity(
            RiftMemoryQueryV1(namespace = namespace),
            RiftMemoryBoundsV1(limit = 1_000)
        )
        handle.close()

        val n25 = JSONObject()
            .put("observationsVerified", observationsVerified)
            .put("observationToEpisode", observationToEpisode)
            .put("episodeClustering", episodeClustering)
            .put("patternExtraction", patternExtraction)
            .put("semanticCandidate", semanticCandidate)
            .put("unsupportedTrustBlocked", unsupportedTrustBlocked)
            .put("provenancePreserved", provenancePreserved)
            .put("reversibleLineage", reversibleLineage)
            .put("sourceBoundFailClosed", sourceBoundFailClosed)
            .put("projectIsolation", projectIsolation)
            .put("conflictedSourceRejected", conflictedSourceRejected)

        val n26 = JSONObject()
            .put("objectSeparation", objectSeparation)
            .put("discrepancyCreated", discrepancyCreated)
            .put("surpriseImportanceScored", surpriseImportanceScored)
            .put("unresolvedConflictState", unresolvedDiscrepancy?.payload?.optString("status") == "UNRESOLVED")
            .put("reflectionCreated", reflectionCreated)
            .put("matchingPredictionNoDiscrepancy", matchingPredictionNoDiscrepancy)
            .put("strongerObservationReconciles", strongerObservationReconciles)
            .put("nonDestructiveHistory", nonDestructiveHistory)
            .put("closeReopenCognitiveState", closeReopenCognitiveState)
            .put("integrityClean", integrity.clean)
            .put("sqliteIntegrity", integrity.sqliteIntegrity)

        val n25Ok = listOf(
            "observationsVerified",
            "observationToEpisode",
            "episodeClustering",
            "patternExtraction",
            "semanticCandidate",
            "unsupportedTrustBlocked",
            "provenancePreserved",
            "reversibleLineage",
            "sourceBoundFailClosed",
            "projectIsolation",
            "conflictedSourceRejected"
        ).all { n25.optBoolean(it, false) }

        val n26Ok = listOf(
            "objectSeparation",
            "discrepancyCreated",
            "surpriseImportanceScored",
            "unresolvedConflictState",
            "reflectionCreated",
            "matchingPredictionNoDiscrepancy",
            "strongerObservationReconciles",
            "nonDestructiveHistory",
            "closeReopenCognitiveState",
            "integrityClean"
        ).all { n26.optBoolean(it, false) } && integrity.sqliteIntegrity == "ok"

        return JSONObject()
            .put("schema", SCHEMA)
            .put("ok", n25Ok && n26Ok)
            .put("diagnosticOnly", true)
            .put("runtimeAuthority", false)
            .put("database", "app-private/riftmemory-diagnostics/n2-m3-proof.sqlite")
            .put("n2_5", n25)
            .put("n2_6", n26)
            .put("integrityFindings", JSONArray(integrity.findings))
    }

    private fun observationCandidate(
        candidateId: String,
        recordId: String,
        scope: RiftMemoryScopeV1,
        subject: String,
        value: String,
        at: Long
    ): RiftMemoryCandidateV1 {
        val bytes = "n2-m3|$candidateId|$subject|$value|$at".toByteArray(Charsets.UTF_8)
        val hash = sha256(bytes)
        val evidence = RiftMemoryEvidenceV1(
            id = "ev-$candidateId",
            contentSha256 = hash,
            mediaType = "text/plain",
            sourceType = "diagnostic-observation",
            sourceRef = "n2-m3/$candidateId",
            scope = scope,
            branch = RiftMemoryBranchV1.REALITY,
            observedAt = at,
            recordedAt = at,
            trustState = RiftMemoryTrustStateV1.VERIFIED,
            metadata = JSONObject()
                .put("memoryClass", RiftMemoryCognitiveClassV1.OBSERVATION)
                .put("subject", subject)
        )
        val record = RiftCanonicalMemoryRecordV1(
            id = recordId,
            kind = RiftMemoryRecordKindV1.CLAIM,
            scope = scope,
            branch = RiftMemoryBranchV1.REALITY,
            trustState = RiftMemoryTrustStateV1.VERIFIED,
            time = RiftMemoryBiTemporalV1(validFrom = at, recordedAt = at),
            payload = JSONObject()
                .put("memoryClass", RiftMemoryCognitiveClassV1.OBSERVATION)
                .put("subject", subject)
                .put("value", value)
                .put("conflictKey", "observation:$recordId"),
            evidenceRefs = listOf(evidence.id)
        )
        return RiftMemoryCandidateV1(
            id = candidateId,
            record = record,
            evidence = listOf(evidence),
            contentBySha256 = mapOf(hash to bytes),
            authorityClass = RiftMemoryAuthorityClassV1.VERIFIED_SOURCE,
            metadata = JSONObject()
                .put("phase", "N2.5")
                .put("selfTest", true)
        )
    }

    private fun lineageIds(record: RiftCanonicalMemoryRecordV1?): List<String> {
        if (record == null) return emptyList()
        val array = record.payload.optJSONObject("lineage")
            ?.optJSONArray("sourceRecords") ?: return emptyList()
        return List(array.length()) { index ->
            array.getJSONObject(index).getString("recordId")
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
