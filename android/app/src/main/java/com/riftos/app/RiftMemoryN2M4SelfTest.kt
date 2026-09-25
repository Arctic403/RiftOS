package com.riftos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

object RiftMemoryN2M4SelfTest {
    const val SCHEMA = "rift-memory-n2-m4-selftest-v1"
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
        val dbFile = File(proofDir, "n2-m4-proof.sqlite")
        dbFile.delete()
        File(dbFile.absolutePath + "-journal").delete()
        File(dbFile.absolutePath + "-wal").delete()
        File(dbFile.absolutePath + "-shm").delete()

        val store = RiftSqliteMemoryStoreV1()
        var handle = store.open(RiftMemoryStoreConfigV1(dbFile.absolutePath))
        val reconcile = RiftMemoryReconciliationV1()
        val specialist = RiftMemoryProceduralFailureV1()
        val retrieval = RiftMemoryRetrievalContextV1()
        val namespace = "diagnostic/n2-m4"
        val scope = RiftMemoryScopeV1(namespace, "riftfs/workspace", "RiftOS-main")
        val otherScope = RiftMemoryScopeV1(namespace, "riftfs/workspace", "OtherProject")
        val boundScope = RiftMemoryScopeV1(namespace, "riftfs/workspace", "BoundProject")
        val base = 3_000_000L

        val initialSources = listOf(
            verifiedObservationCandidate(
                "candidate-procedure-1",
                "source-procedure-1",
                scope,
                "procedure.step",
                "open workspace and inspect exact failure",
                base + 10
            ),
            verifiedObservationCandidate(
                "candidate-procedure-2",
                "source-procedure-2",
                scope,
                "procedure.step",
                "run focused checks before rebuild",
                base + 20
            ),
            verifiedObservationCandidate(
                "candidate-failure-1",
                "source-failure-1",
                scope,
                "failure.kind",
                "kotlin-nullable-contract",
                base + 30
            ),
            verifiedObservationCandidate(
                "candidate-failure-2",
                "source-failure-2",
                scope,
                "failure.kind",
                "kotlin-nullable-contract",
                base + 40
            ),
            verifiedObservationCandidate(
                "candidate-cause",
                "source-cause",
                scope,
                "cause",
                "stale direct source assertion",
                base + 50
            ),
            verifiedObservationCandidate(
                "candidate-effect",
                "source-effect",
                scope,
                "effect",
                "builder source gate failed",
                base + 60
            ),
            verifiedObservationCandidate(
                "candidate-commitment-source",
                "source-commitment",
                scope,
                "task",
                "finish N2-M4",
                base + 70
            )
        )
        val initialResults = initialSources.map { reconcile.reconcile(handle, it) }
        val sourceEvidenceCommitted = initialResults.all {
            it.outcome == RiftMemoryTransactionOutcomeV1.COMMITTED
        }

        val skillResult = reconcile.reconcile(
            handle,
            specialist.createSkillCandidate(
                handle = handle,
                candidateId = "candidate-skill-recovery",
                recordId = "skill-recovery",
                sourceRecordIds = listOf("source-procedure-1", "source-procedure-2"),
                name = "recover-builder-regression",
                steps = listOf(
                    "inspect the exact failing source gate",
                    "check Observer before mutation",
                    "repair the generalized failure class",
                    "rerun focused proof obligations"
                ),
                at = base + 100
            )
        )
        val skill = handle.getCanonicalRecord("skill-recovery")
        val proceduralTransfer =
            skillResult.outcome == RiftMemoryTransactionOutcomeV1.PROVISIONAL &&
            skill?.payload?.optString("memoryClass") == RiftMemorySpecialistClassV1.SKILL &&
            skill?.payload?.optJSONArray("steps")?.length() == 4 &&
            skill?.payload?.optJSONArray("sourceRecordIds")?.length() == 2 &&
            skill?.payload?.optBoolean("reversible", false) == true

        val failureA = reconcile.reconcile(
            handle,
            specialist.createFailureCandidate(
                handle,
                "candidate-failure-event-a",
                "failure-event-a",
                listOf("source-failure-1"),
                "kotlin-nullable-contract",
                "Nullable canonical source was dereferenced without a proof.",
                base + 110
            )
        )
        val failureB = reconcile.reconcile(
            handle,
            specialist.createFailureCandidate(
                handle,
                "candidate-failure-event-b",
                "failure-event-b",
                listOf("source-failure-2"),
                "kotlin-nullable-contract",
                "A later source gate repeated the same failure class.",
                base + 120
            )
        )
        val recurrence = specialist.detectFailureRecurrence(
            handle,
            namespace,
            scope.projectId ?: error("missing project"),
            "kotlin-nullable-contract"
        )
        val failureRecurrence =
            failureA.outcome == RiftMemoryTransactionOutcomeV1.PROVISIONAL &&
            failureB.outcome == RiftMemoryTransactionOutcomeV1.PROVISIONAL &&
            recurrence.occurrences == 2 &&
            recurrence.recordIds == listOf("failure-event-a", "failure-event-b")

        val causalResult = reconcile.reconcile(
            handle,
            specialist.createCausalCandidate(
                handle,
                "candidate-causal-stale-gate",
                "causal-stale-gate",
                "source-cause",
                "source-effect",
                confidence = 85,
                at = base + 130
            )
        )
        val causal = handle.getCanonicalRecord("causal-stale-gate")
        val causalConfidence =
            causalResult.outcome == RiftMemoryTransactionOutcomeV1.PROVISIONAL &&
            causal?.payload?.optString("memoryClass") == RiftMemorySpecialistClassV1.CAUSAL &&
            causal?.payload?.optInt("confidence") == 85 &&
            causal?.payload?.optBoolean("explicitEvidenceBacked", false) == true &&
            causal?.trustState == RiftMemoryTrustStateV1.PROVISIONAL
        val causalWeakSourceBlocked = runCatching {
            specialist.createCausalCandidate(
                handle,
                "candidate-causal-weak",
                "causal-weak",
                "skill-recovery",
                "source-effect",
                confidence = 90,
                at = base + 131
            )
        }.isFailure

        val commitmentResult = reconcile.reconcile(
            handle,
            specialist.createCommitmentCandidate(
                handle,
                "candidate-commitment-m4",
                "commitment-m4",
                listOf("source-commitment"),
                "Finish N2-M4 correctness gate",
                "n2-m4-correctness-gate",
                "OPEN",
                base + 140
            )
        )

        val policyAuthorized = reconcile.reconcile(
            handle,
            specialist.createPolicyCandidate(
                candidateId = "candidate-policy-authorized",
                recordId = "policy-observer-required",
                scope = scope,
                policyKey = "observer-required",
                value = "required",
                evidenceId = "ev-policy-authorized",
                evidenceBytes = "policy authority says observer required".toByteArray(Charsets.UTF_8),
                authorityClass = RiftMemoryAuthorityClassV1.POLICY_AUTHORITY,
                at = base + 150
            )
        )
        val policyLearned = reconcile.reconcile(
            handle,
            specialist.createPolicyCandidate(
                candidateId = "candidate-policy-learned",
                recordId = "policy-observer-required",
                scope = scope,
                policyKey = "observer-required",
                value = "optional",
                evidenceId = "ev-policy-learned",
                evidenceBytes = "learned memory suggests observer optional".toByteArray(Charsets.UTF_8),
                authorityClass = RiftMemoryAuthorityClassV1.ORDINARY,
                at = base + 160
            )
        )
        val policyAfterLearned = handle.getCanonicalRecord("policy-observer-required")
        val protectedPolicy =
            policyAuthorized.outcome == RiftMemoryTransactionOutcomeV1.COMMITTED &&
            policyLearned.outcome == RiftMemoryTransactionOutcomeV1.QUARANTINED &&
            policyLearned.reasons.contains("protected-authority-mismatch") &&
            policyAfterLearned?.payload?.optString("value") == "required" &&
            policyAfterLearned?.trustState == RiftMemoryTrustStateV1.VERIFIED

        val derivedTrustCeiling = listOf(
            "skill-recovery",
            "failure-event-a",
            "failure-event-b",
            "causal-stale-gate",
            "commitment-m4"
        ).all { id ->
            handle.getCanonicalRecord(id)?.trustState == RiftMemoryTrustStateV1.PROVISIONAL
        }

        handle.close()
        handle = store.open(RiftMemoryStoreConfigV1(dbFile.absolutePath, createIfMissing = false))
        val openAfterRestart = specialist.openCommitments(
            handle,
            namespace,
            scope.projectId ?: error("missing project")
        )
        val unfinishedTaskRestart =
            commitmentResult.outcome == RiftMemoryTransactionOutcomeV1.PROVISIONAL &&
            openAfterRestart.map { it.id } == listOf("commitment-m4") &&
            openAfterRestart.singleOrNull()?.payload?.optString("resumeKey") == "n2-m4-correctness-gate"

        val dependencyResult = reconcile.reconcile(
            handle,
            verifiedObservationCandidate(
                "candidate-dependency-gradle",
                "dependency-gradle",
                scope,
                "gradle.status",
                "green",
                base + 500,
                entityId = "gradle"
            )
        )
        val directResult = reconcile.reconcile(
            handle,
            verifiedObservationCandidate(
                "candidate-direct-builder-green",
                "direct-builder-green",
                scope,
                "builder.status",
                "green",
                base + 510,
                entityId = "builder",
                dependsOn = listOf("dependency-gradle")
            )
        )
        val guessResult = reconcile.reconcile(
            handle,
            provisionalClaimCandidate(
                "candidate-guess-builder-red",
                "guess-builder-red",
                scope,
                JSONObject()
                    .put("memoryClass", "SEMANTIC_GUESS")
                    .put("entityId", "builder")
                    .put("subject", "builder.status")
                    .put("value", "red")
                    .put("text", "builder status red failure red red similar")
                    .put("conflictKey", "guess:builder:red"),
                base + 520
            )
        )
        val otherProjectResult = reconcile.reconcile(
            handle,
            verifiedObservationCandidate(
                "candidate-other-builder-red",
                "other-builder-red",
                otherScope,
                "builder.status",
                "red",
                base + 530,
                entityId = "builder"
            )
        )
        val retrievalSourcesReady =
            dependencyResult.outcome == RiftMemoryTransactionOutcomeV1.COMMITTED &&
            directResult.outcome == RiftMemoryTransactionOutcomeV1.COMMITTED &&
            guessResult.outcome == RiftMemoryTransactionOutcomeV1.PROVISIONAL &&
            otherProjectResult.outcome == RiftMemoryTransactionOutcomeV1.COMMITTED

        val routerModes =
            retrieval.route(RiftMemoryRetrievalModeV1.NO).isEmpty() &&
            retrieval.route(RiftMemoryRetrievalModeV1.FAST) == setOf(
                RiftMemoryRetrievalLaneV1.EXACT,
                RiftMemoryRetrievalLaneV1.ENTITY,
                RiftMemoryRetrievalLaneV1.PROJECT,
                RiftMemoryRetrievalLaneV1.BM25
            ) &&
            retrieval.route(RiftMemoryRetrievalModeV1.DEEP) == setOf(
                RiftMemoryRetrievalLaneV1.EXACT,
                RiftMemoryRetrievalLaneV1.ENTITY,
                RiftMemoryRetrievalLaneV1.PROJECT,
                RiftMemoryRetrievalLaneV1.TEMPORAL,
                RiftMemoryRetrievalLaneV1.GRAPH,
                RiftMemoryRetrievalLaneV1.BM25,
                RiftMemoryRetrievalLaneV1.VECTOR
            ) &&
            retrieval.route(RiftMemoryRetrievalModeV1.FORENSIC).size == 7

        val deepRequest = RiftMemoryRetrievalRequestV1(
            namespace = namespace,
            projectId = scope.projectId ?: error("missing project"),
            query = "builder status red green gradle failure",
            exactRecordId = "direct-builder-green",
            entityId = "builder",
            validAt = base + 600,
            recordedAt = base + 600,
            mode = RiftMemoryRetrievalModeV1.DEEP,
            maxResults = 32,
            maxContextTokens = 1_024
        )
        val deep = retrieval.retrieve(handle, deepRequest)
        val actualLanes = deep.hits.flatMap { it.lanes }.toSet()
        val directHit = deep.hits.firstOrNull()
        val guessHit = deep.hits.singleOrNull { it.record.id == "guess-builder-red" }
        val dependencyHit = deep.hits.singleOrNull { it.record.id == "dependency-gradle" }
        val specialistIndexes =
            actualLanes.containsAll(
                setOf(
                    RiftMemoryRetrievalLaneV1.EXACT,
                    RiftMemoryRetrievalLaneV1.ENTITY,
                    RiftMemoryRetrievalLaneV1.PROJECT,
                    RiftMemoryRetrievalLaneV1.TEMPORAL,
                    RiftMemoryRetrievalLaneV1.GRAPH,
                    RiftMemoryRetrievalLaneV1.BM25,
                    RiftMemoryRetrievalLaneV1.VECTOR
                )
            ) &&
            dependencyHit?.lanes?.contains(RiftMemoryRetrievalLaneV1.GRAPH) == true &&
            guessHit?.lanes?.any {
                it == RiftMemoryRetrievalLaneV1.BM25 || it == RiftMemoryRetrievalLaneV1.VECTOR
            } == true

        val evidencePrecedence =
            directHit != null &&
            directHit.record.id == "direct-builder-green" &&
            directHit.requiredEvidence &&
            directHit.record.trustState == RiftMemoryTrustStateV1.VERIFIED &&
            guessHit != null &&
            guessHit.record.trustState == RiftMemoryTrustStateV1.PROVISIONAL

        val crossProjectExact = retrieval.retrieve(
            handle,
            RiftMemoryRetrievalRequestV1(
                namespace = namespace,
                projectId = scope.projectId ?: error("missing project"),
                query = "builder red",
                exactRecordId = "other-builder-red",
                mode = RiftMemoryRetrievalModeV1.FAST,
                maxResults = 32
            )
        )
        val projectIsolation =
            deep.hits.all { it.record.scope.projectId == scope.projectId } &&
            deep.hits.none { it.record.id == "other-builder-red" } &&
            crossProjectExact.hits.none { it.record.id == "other-builder-red" }

        val context = retrieval.compileContext(deep, deepRequest.maxContextTokens)
        val contextIds = context.entries.map { it.getString("recordId") }
        val minimalSufficientContext =
            context.complete &&
            context.estimatedTokens in 1..deepRequest.maxContextTokens &&
            contextIds.firstOrNull() == "direct-builder-green" &&
            "other-builder-red" !in contextIds

        val contextBudgetFailClosed = retrieval.compileContext(deep, 1).let {
            !it.complete &&
                it.entries.isEmpty() &&
                it.incompleteReasons == listOf("context-budget-insufficient-for-required-evidence")
        }

        val noMode = retrieval.retrieve(
            handle,
            RiftMemoryRetrievalRequestV1(
                namespace = namespace,
                projectId = scope.projectId ?: error("missing project"),
                mode = RiftMemoryRetrievalModeV1.NO
            )
        ).let {
            it.complete && it.hits.isEmpty() && it.routedLanes.isEmpty()
        }

        val boundedFailClosed =
            runCatching {
                RiftMemoryRetrievalRequestV1(
                    namespace = namespace,
                    projectId = scope.projectId ?: error("missing project"),
                    query = "x".repeat(RiftMemoryRetrievalContextV1.MAX_QUERY_CHARS + 1)
                )
            }.isFailure &&
            runCatching {
                RiftMemoryRetrievalRequestV1(
                    namespace = namespace,
                    projectId = scope.projectId ?: error("missing project"),
                    maxResults = RiftMemoryRetrievalContextV1.MAX_RESULTS + 1
                )
            }.isFailure

        val oversizedProjectionResult = reconcile.reconcile(
            handle,
            provisionalClaimCandidate(
                "candidate-oversized-projection",
                "oversized-projection",
                boundScope,
                JSONObject()
                    .put("memoryClass", "BOUND_FIXTURE")
                    .put("text", "x".repeat(RiftMemoryRetrievalContextV1.MAX_DOCUMENT_SCALAR_CHARS + 1))
                    .put("conflictKey", "bound:oversized-projection"),
                base + 700
            )
        )
        val documentProjectionFailClosed =
            oversizedProjectionResult.outcome == RiftMemoryTransactionOutcomeV1.PROVISIONAL &&
            runCatching {
                retrieval.retrieve(
                    handle,
                    RiftMemoryRetrievalRequestV1(
                        namespace = namespace,
                        projectId = boundScope.projectId ?: error("missing bound project"),
                        query = "x",
                        mode = RiftMemoryRetrievalModeV1.FAST,
                        maxResults = 4
                    )
                )
            }.isFailure

        val integrity = handle.verifyIntegrity(
            RiftMemoryQueryV1(namespace = namespace),
            RiftMemoryBoundsV1(limit = 1_000)
        )
        handle.close()

        val n27 = JSONObject()
            .put("sourceEvidenceCommitted", sourceEvidenceCommitted)
            .put("proceduralTransfer", proceduralTransfer)
            .put("failureRecurrence", failureRecurrence)
            .put("causalConfidence", causalConfidence)
            .put("causalWeakSourceBlocked", causalWeakSourceBlocked)
            .put("unfinishedTaskRestart", unfinishedTaskRestart)
            .put("protectedPolicy", protectedPolicy)
            .put("derivedTrustCeiling", derivedTrustCeiling)

        val n28 = JSONObject()
            .put("retrievalSourcesReady", retrievalSourcesReady)
            .put("routerModes", routerModes)
            .put("specialistIndexes", specialistIndexes)
            .put("evidencePrecedence", evidencePrecedence)
            .put("projectIsolation", projectIsolation)
            .put("minimalSufficientContext", minimalSufficientContext)
            .put("contextBudgetFailClosed", contextBudgetFailClosed)
            .put("noMode", noMode)
            .put("boundedFailClosed", boundedFailClosed)
            .put("documentProjectionFailClosed", documentProjectionFailClosed)
            .put("integrityClean", integrity.clean)
            .put("sqliteIntegrity", integrity.sqliteIntegrity)

        val n27Ok = listOf(
            "sourceEvidenceCommitted",
            "proceduralTransfer",
            "failureRecurrence",
            "causalConfidence",
            "causalWeakSourceBlocked",
            "unfinishedTaskRestart",
            "protectedPolicy",
            "derivedTrustCeiling"
        ).all { n27.optBoolean(it, false) }

        val n28Ok = listOf(
            "retrievalSourcesReady",
            "routerModes",
            "specialistIndexes",
            "evidencePrecedence",
            "projectIsolation",
            "minimalSufficientContext",
            "contextBudgetFailClosed",
            "noMode",
            "boundedFailClosed",
            "documentProjectionFailClosed",
            "integrityClean"
        ).all { n28.optBoolean(it, false) } && integrity.sqliteIntegrity == "ok"

        return JSONObject()
            .put("schema", SCHEMA)
            .put("ok", n27Ok && n28Ok)
            .put("diagnosticOnly", true)
            .put("runtimeAuthority", false)
            .put("database", "app-private/riftmemory-diagnostics/n2-m4-proof.sqlite")
            .put("n2_7", n27)
            .put("n2_8", n28)
            .put("integrityFindings", JSONArray(integrity.findings))
    }

    private fun verifiedObservationCandidate(
        candidateId: String,
        recordId: String,
        scope: RiftMemoryScopeV1,
        subject: String,
        value: String,
        at: Long,
        entityId: String? = null,
        dependsOn: List<String> = emptyList()
    ): RiftMemoryCandidateV1 {
        require(dependsOn.size <= RiftMemoryTemporalGraphV1.MAX_DEPENDENCIES_PER_RECORD)
        val bytes = "n2-m4|$candidateId|$subject|$value|$at".toByteArray(Charsets.UTF_8)
        val hash = sha256(bytes)
        val evidence = RiftMemoryEvidenceV1(
            id = "ev-$candidateId",
            contentSha256 = hash,
            mediaType = "text/plain",
            sourceType = "diagnostic-observation",
            sourceRef = "n2-m4/$candidateId",
            scope = scope,
            branch = RiftMemoryBranchV1.REALITY,
            observedAt = at,
            recordedAt = at,
            trustState = RiftMemoryTrustStateV1.VERIFIED,
            metadata = JSONObject()
                .put("memoryClass", RiftMemoryCognitiveClassV1.OBSERVATION)
                .put("subject", subject)
        )
        val payload = JSONObject()
            .put("memoryClass", RiftMemoryCognitiveClassV1.OBSERVATION)
            .put("subject", subject)
            .put("value", value)
            .put("conflictKey", "observation:$recordId")
        entityId?.let { payload.put("entityId", it) }
        if (dependsOn.isNotEmpty()) payload.put("dependsOn", JSONArray(dependsOn))
        val record = RiftCanonicalMemoryRecordV1(
            id = recordId,
            kind = RiftMemoryRecordKindV1.CLAIM,
            scope = scope,
            branch = RiftMemoryBranchV1.REALITY,
            trustState = RiftMemoryTrustStateV1.VERIFIED,
            time = RiftMemoryBiTemporalV1(validFrom = at, recordedAt = at),
            payload = payload,
            evidenceRefs = listOf(evidence.id)
        )
        return RiftMemoryCandidateV1(
            id = candidateId,
            record = record,
            evidence = listOf(evidence),
            contentBySha256 = mapOf(hash to bytes),
            authorityClass = RiftMemoryAuthorityClassV1.VERIFIED_SOURCE,
            metadata = JSONObject()
                .put("phase", "N2.7/N2.8")
                .put("selfTest", true)
        )
    }

    private fun provisionalClaimCandidate(
        candidateId: String,
        recordId: String,
        scope: RiftMemoryScopeV1,
        payload: JSONObject,
        at: Long
    ): RiftMemoryCandidateV1 {
        val record = RiftCanonicalMemoryRecordV1(
            id = recordId,
            kind = RiftMemoryRecordKindV1.CLAIM,
            scope = scope,
            branch = RiftMemoryBranchV1.REALITY,
            trustState = RiftMemoryTrustStateV1.PROVISIONAL,
            time = RiftMemoryBiTemporalV1(validFrom = at, recordedAt = at),
            payload = payload,
            evidenceRefs = emptyList()
        )
        return RiftMemoryCandidateV1(
            id = candidateId,
            record = record,
            evidence = emptyList(),
            contentBySha256 = emptyMap(),
            authorityClass = RiftMemoryAuthorityClassV1.ORDINARY,
            metadata = JSONObject()
                .put("phase", "N2.8")
                .put("selfTest", true)
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
