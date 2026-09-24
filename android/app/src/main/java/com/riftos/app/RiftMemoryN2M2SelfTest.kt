package com.riftos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

object RiftMemoryN2M2SelfTest {
    const val SCHEMA = "rift-memory-n2-m2-selftest-v1"
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
        val dbFile = File(proofDir, "n2-m2-proof.sqlite")
        dbFile.delete()
        File(dbFile.absolutePath + "-journal").delete()

        val store = RiftSqliteMemoryStoreV1()
        val config = RiftMemoryStoreConfigV1(dbFile.absolutePath)
        var handle = store.open(config)
        val reconcile = RiftMemoryReconciliationV1()
        val namespace = "diagnostic/n2-m2"
        val scopeA = RiftMemoryScopeV1(namespace, "riftfs/workspace", "RiftOS-main")
        val scopeB = RiftMemoryScopeV1(namespace, "riftfs/workspace", "OtherProject")
        val base = 1_000_000L

        val weakAlpha = candidate(
            id = "candidate-weak-alpha",
            recordId = "feature-mode",
            scope = scopeA,
            branch = RiftMemoryBranchV1.REALITY,
            evidenceTrust = RiftMemoryTrustStateV1.UNVERIFIED,
            requestedTrust = RiftMemoryTrustStateV1.VERIFIED,
            at = base,
            value = "alpha",
            conflictKey = "feature-mode",
            entityId = "feature",
            dependsOn = listOf("dependency-a")
        )
        val weakAlphaResult = reconcile.reconcile(handle, weakAlpha)
        val weakCurrent = handle.getCanonicalRecord("feature-mode")
        val weakEvidenceNotTruth =
            weakAlphaResult.outcome == RiftMemoryTransactionOutcomeV1.PROVISIONAL &&
            weakCurrent?.trustState == RiftMemoryTrustStateV1.PROVISIONAL

        val repeatedAlpha = candidate(
            id = "candidate-repeat-alpha",
            recordId = "feature-mode",
            scope = scopeA,
            branch = RiftMemoryBranchV1.REALITY,
            evidenceTrust = RiftMemoryTrustStateV1.UNVERIFIED,
            requestedTrust = RiftMemoryTrustStateV1.VERIFIED,
            at = base + 10,
            value = "alpha",
            conflictKey = "feature-mode",
            entityId = "feature",
            dependsOn = listOf("dependency-a")
        )
        val repeatedResult = reconcile.reconcile(handle, repeatedAlpha)
        val repeatedCurrent = handle.getCanonicalRecord("feature-mode")
        val repetitionNoTrustUpgrade =
            repeatedResult.outcome == RiftMemoryTransactionOutcomeV1.PROVISIONAL &&
            repeatedCurrent?.trustState == RiftMemoryTrustStateV1.PROVISIONAL &&
            repeatedCurrent?.evidenceRefs == weakCurrent?.evidenceRefs

        val verifiedBeta = candidate(
            id = "candidate-verified-beta",
            recordId = "feature-mode",
            scope = scopeA,
            branch = RiftMemoryBranchV1.REALITY,
            evidenceTrust = RiftMemoryTrustStateV1.VERIFIED,
            requestedTrust = RiftMemoryTrustStateV1.VERIFIED,
            at = base + 20,
            value = "beta",
            conflictKey = "feature-mode",
            entityId = "feature",
            dependsOn = listOf("dependency-a")
        )
        val supersedeResult = reconcile.reconcile(handle, verifiedBeta)
        val betaCurrent = handle.getCanonicalRecord("feature-mode")
        val strongerSupersession =
            supersedeResult.outcome == RiftMemoryTransactionOutcomeV1.COMMITTED &&
            betaCurrent?.payload?.optString("value") == "beta" &&
            betaCurrent?.trustState == RiftMemoryTrustStateV1.VERIFIED

        val verifiedGamma = candidate(
            id = "candidate-verified-gamma",
            recordId = "feature-mode",
            scope = scopeA,
            branch = RiftMemoryBranchV1.REALITY,
            evidenceTrust = RiftMemoryTrustStateV1.VERIFIED,
            requestedTrust = RiftMemoryTrustStateV1.VERIFIED,
            at = base + 30,
            value = "gamma",
            conflictKey = "feature-mode",
            entityId = "feature",
            dependsOn = listOf("dependency-a")
        )
        val conflictResult = reconcile.reconcile(handle, verifiedGamma)
        val conflictedCurrent = handle.getCanonicalRecord("feature-mode")
        val conflictNoSilentOverwrite =
            conflictResult.outcome == RiftMemoryTransactionOutcomeV1.QUARANTINED &&
            conflictResult.conflictFound &&
            conflictedCurrent?.payload?.optString("value") == "beta" &&
            conflictedCurrent?.trustState == RiftMemoryTrustStateV1.CONFLICTED

        val otherProject = candidate(
            id = "candidate-other-project",
            recordId = "feature-mode-other",
            scope = scopeB,
            branch = RiftMemoryBranchV1.REALITY,
            evidenceTrust = RiftMemoryTrustStateV1.VERIFIED,
            requestedTrust = RiftMemoryTrustStateV1.VERIFIED,
            at = base + 40,
            value = "delta",
            conflictKey = "feature-mode",
            entityId = "feature"
        )
        val otherProjectResult = reconcile.reconcile(handle, otherProject)
        val projectAStillBeta = handle.getCanonicalRecord("feature-mode")?.payload?.optString("value") == "beta"
        val projectBDelta = handle.getCanonicalRecord("feature-mode-other")?.payload?.optString("value") == "delta"
        val crossProjectIsolation =
            otherProjectResult.outcome == RiftMemoryTransactionOutcomeV1.COMMITTED &&
            projectAStillBeta &&
            projectBDelta

        val simulation = candidate(
            id = "candidate-simulation",
            recordId = "simulation-feature-mode",
            scope = scopeA,
            branch = RiftMemoryBranchV1.SIMULATION,
            evidenceTrust = RiftMemoryTrustStateV1.VERIFIED,
            requestedTrust = RiftMemoryTrustStateV1.VERIFIED,
            at = base + 50,
            value = "simulated",
            conflictKey = "feature-mode"
        )
        val simulationResult = reconcile.reconcile(handle, simulation)
        val simulationRecord = handle.getCanonicalRecord("simulation-feature-mode")
        val simulationIsolation =
            simulationResult.outcome == RiftMemoryTransactionOutcomeV1.PROVISIONAL &&
            simulationRecord?.branch == RiftMemoryBranchV1.SIMULATION &&
            simulationRecord?.trustState == RiftMemoryTrustStateV1.PROVISIONAL &&
            handle.getCanonicalRecord("feature-mode")?.payload?.optString("value") == "beta"

        val policy = candidate(
            id = "candidate-policy-authority",
            recordId = "policy-security",
            scope = scopeA,
            branch = RiftMemoryBranchV1.REALITY,
            evidenceTrust = RiftMemoryTrustStateV1.VERIFIED,
            requestedTrust = RiftMemoryTrustStateV1.VERIFIED,
            at = base + 60,
            value = "locked",
            conflictKey = "policy-security",
            kind = RiftMemoryRecordKindV1.POLICY,
            authorityNamespace = "policy/security",
            authorityClass = RiftMemoryAuthorityClassV1.POLICY_AUTHORITY
        )
        val policyResult = reconcile.reconcile(handle, policy)
        val policyBeforeAttack = handle.getCanonicalRecord("policy-security")

        val policyAttack = candidate(
            id = "candidate-policy-attack",
            recordId = "policy-security",
            scope = scopeA,
            branch = RiftMemoryBranchV1.REALITY,
            evidenceTrust = RiftMemoryTrustStateV1.UNVERIFIED,
            requestedTrust = RiftMemoryTrustStateV1.PROVISIONAL,
            at = base + 70,
            value = "disabled",
            conflictKey = "policy-security",
            kind = RiftMemoryRecordKindV1.POLICY,
            authorityNamespace = "policy/security",
            authorityClass = RiftMemoryAuthorityClassV1.ORDINARY
        )
        val policyAttackResult = reconcile.reconcile(handle, policyAttack)
        val policyAfterAttack = handle.getCanonicalRecord("policy-security")
        val protectedPolicyIsolation =
            policyResult.outcome == RiftMemoryTransactionOutcomeV1.COMMITTED &&
            policyAttackResult.outcome == RiftMemoryTransactionOutcomeV1.QUARANTINED &&
            policyBeforeAttack?.payload?.optString("value") == "locked" &&
            policyAfterAttack?.payload?.optString("value") == "locked" &&
            policyAfterAttack?.trustState == RiftMemoryTrustStateV1.VERIFIED

        val malformedBase = candidate(
            id = "candidate-malformed-content",
            recordId = "malformed-record",
            scope = scopeA,
            branch = RiftMemoryBranchV1.REALITY,
            evidenceTrust = RiftMemoryTrustStateV1.VERIFIED,
            requestedTrust = RiftMemoryTrustStateV1.VERIFIED,
            at = base + 75,
            value = "must-not-commit",
            conflictKey = "malformed"
        )
        val malformedHash = malformedBase.contentBySha256.keys.single()
        val malformed = malformedBase.copy(
            contentBySha256 = mapOf(
                malformedHash to "tampered-content".toByteArray(Charsets.UTF_8)
            )
        )
        val malformedResult = reconcile.reconcile(handle, malformed)
        val invalidCandidateRejected =
            malformedResult.outcome == RiftMemoryTransactionOutcomeV1.REJECTED &&
            malformedResult.transactionId != null &&
            handle.getCanonicalRecord("malformed-record") == null

        val obsolete = candidate(
            id = "candidate-obsolete",
            recordId = "obsolete-record",
            scope = scopeA,
            branch = RiftMemoryBranchV1.REALITY,
            evidenceTrust = RiftMemoryTrustStateV1.VERIFIED,
            requestedTrust = RiftMemoryTrustStateV1.VERIFIED,
            at = base + 80,
            value = "present",
            conflictKey = "obsolete"
        )
        val obsoleteResult = reconcile.reconcile(handle, obsolete)
        val invalidate = candidate(
            id = "candidate-invalidate-obsolete",
            recordId = "obsolete-record",
            scope = scopeA,
            branch = RiftMemoryBranchV1.REALITY,
            evidenceTrust = RiftMemoryTrustStateV1.VERIFIED,
            requestedTrust = RiftMemoryTrustStateV1.VERIFIED,
            at = base + 90,
            value = "present",
            conflictKey = "obsolete",
            action = RiftMemoryCandidateActionV1.INVALIDATE
        )
        val invalidateResult = reconcile.reconcile(handle, invalidate)
        val invalidatedCurrent = handle.getCanonicalRecord("obsolete-record")
        val invalidationPreservesHistory =
            obsoleteResult.outcome == RiftMemoryTransactionOutcomeV1.COMMITTED &&
            invalidateResult.outcome == RiftMemoryTransactionOutcomeV1.COMMITTED &&
            invalidatedCurrent?.trustState == RiftMemoryTrustStateV1.INVALIDATED &&
            invalidatedCurrent?.time?.validTo == base + 90

        val projection = RiftMemoryTemporalGraphV1.rebuild(
            handle,
            RiftMemoryQueryV1(namespace = namespace)
        )
        val projectionInitiallyFresh = RiftMemoryTemporalGraphV1.isFresh(handle, projection)
        val edgeTypes = projection.edges.map { it.type }.toSet()
        val graphEdgesComplete = setOf(
            RiftMemoryGraphEdgeTypeV1.ENTITY,
            RiftMemoryGraphEdgeTypeV1.DEPENDENCY,
            RiftMemoryGraphEdgeTypeV1.PROVENANCE,
            RiftMemoryGraphEdgeTypeV1.SUPERSEDES,
            RiftMemoryGraphEdgeTypeV1.CONTRADICTS,
            RiftMemoryGraphEdgeTypeV1.INVALIDATES
        ).all { it in edgeTypes }
        val invalidatedNotCurrent = projection.currentRecords.none { it.id == "obsolete-record" }

        val beforeSupersession = RiftMemoryTemporalGraphV1.reconstructAt(
            handle,
            RiftMemoryQueryV1(namespace = namespace, projectId = scopeA.projectId, branch = RiftMemoryBranchV1.REALITY),
            validAt = base + 5,
            recordedAt = base + 15
        )
        val afterSupersession = RiftMemoryTemporalGraphV1.reconstructAt(
            handle,
            RiftMemoryQueryV1(namespace = namespace, projectId = scopeA.projectId, branch = RiftMemoryBranchV1.REALITY),
            validAt = base + 25,
            recordedAt = base + 25
        )
        val laterKnowledgeOfPast = RiftMemoryTemporalGraphV1.reconstructAt(
            handle,
            RiftMemoryQueryV1(namespace = namespace, projectId = scopeA.projectId, branch = RiftMemoryBranchV1.REALITY),
            validAt = base + 5,
            recordedAt = base + 25
        )
        val pointInTimeCorrect =
            beforeSupersession.records.singleOrNull { it.id == "feature-mode" }?.payload?.optString("value") == "alpha" &&
            afterSupersession.records.singleOrNull { it.id == "feature-mode" }?.payload?.optString("value") == "beta" &&
            laterKnowledgeOfPast.records.singleOrNull { it.id == "feature-mode" }?.trustState == RiftMemoryTrustStateV1.SUPERSEDED

        val emptyScopeA = RiftMemoryQueryV1(
            namespace = namespace,
            projectId = "EmptyProjectA",
            branch = RiftMemoryBranchV1.REALITY
        )
        val emptyScopeB = RiftMemoryQueryV1(
            namespace = namespace,
            projectId = "EmptyProjectB",
            branch = RiftMemoryBranchV1.REALITY
        )
        val emptyProjectionA = RiftMemoryTemporalGraphV1.rebuild(handle, emptyScopeA)
        val emptyProjectionB = RiftMemoryTemporalGraphV1.rebuild(handle, emptyScopeB)
        val scopeHashIsolation =
            emptyProjectionA.currentRecords.isEmpty() &&
            emptyProjectionB.currentRecords.isEmpty() &&
            emptyProjectionA.canonicalSha256 != emptyProjectionB.canonicalSha256

        val emptyPointA = RiftMemoryTemporalGraphV1.reconstructAt(handle, emptyScopeA, base, base)
        val emptyPointB = RiftMemoryTemporalGraphV1.reconstructAt(handle, emptyScopeB, base, base)
        val pointInTimeScopeHashIsolation =
            emptyPointA.records.isEmpty() &&
            emptyPointB.records.isEmpty() &&
            emptyPointA.canonicalSha256 != emptyPointB.canonicalSha256

        val coherentScopeGuard = runCatching {
            RiftMemoryTemporalGraphV1.rebuild(
                handle,
                RiftMemoryQueryV1(namespace = namespace, kind = RiftMemoryRecordKindV1.CLAIM)
            )
        }.isFailure

        handle.markProjectionDirty(
            RiftMemoryReconciliationV1.PROJECTION_TEMPORAL_GRAPH,
            "n2-m2-selftest-after-build"
        )
        val dirtyDetected = !RiftMemoryTemporalGraphV1.isFresh(handle, projection)
        val rebuiltAfterDirty = RiftMemoryTemporalGraphV1.rebuild(
            handle,
            RiftMemoryQueryV1(namespace = namespace)
        )
        val dirtyRebuildExact =
            rebuiltAfterDirty.canonicalSha256 == projection.canonicalSha256 &&
            RiftMemoryTemporalGraphV1.isFresh(handle, rebuiltAfterDirty)

        val projectionHashBeforeClose = rebuiltAfterDirty.canonicalSha256
        handle.close()
        handle = store.open(RiftMemoryStoreConfigV1(dbFile.absolutePath, createIfMissing = false))
        val reopenedProjection = RiftMemoryTemporalGraphV1.rebuild(
            handle,
            RiftMemoryQueryV1(namespace = namespace)
        )
        val closeReopenProjectionRebuild =
            reopenedProjection.canonicalSha256 == projectionHashBeforeClose

        val integrity = handle.verifyIntegrity(
            RiftMemoryQueryV1(namespace = namespace),
            RiftMemoryBoundsV1(limit = 1_000)
        )
        val reconciliationEvents = handle.readEvents(
            RiftMemoryQueryV1(namespace = namespace),
            RiftMemoryBoundsV1(limit = 1_000)
        ).events.count {
            it.event.optString("type") == RiftMemoryReconciliationV1.EVENT_RECONCILIATION
        }

        val n23 = JSONObject()
            .put("candidatePipeline", true)
            .put("allOutcomes", listOf(
                weakAlphaResult.outcome,
                supersedeResult.outcome,
                conflictResult.outcome,
                policyAttackResult.outcome,
                malformedResult.outcome
            ).toSet() == RiftMemoryTransactionOutcomeV1.values().toSet())
            .put("evidenceNotTruth", weakEvidenceNotTruth)
            .put("repetitionNoTrustUpgrade", repetitionNoTrustUpgrade)
            .put("strongerSupersession", strongerSupersession)
            .put("conflictNoSilentOverwrite", conflictNoSilentOverwrite)
            .put("crossProjectIsolation", crossProjectIsolation)
            .put("simulationIsolation", simulationIsolation)
            .put("protectedPolicyIsolation", protectedPolicyIsolation)
            .put("invalidCandidateRejected", invalidCandidateRejected)
            .put("invalidationPreservesHistory", invalidationPreservesHistory)
            .put("projectionDirtySemantics", projectionInitiallyFresh && dirtyDetected && dirtyRebuildExact)
            .put("reconciliationEvents", reconciliationEvents)

        val n24 = JSONObject()
            .put("biTemporalPointInTime", pointInTimeCorrect)
            .put("scopeHashIsolation", scopeHashIsolation)
            .put("pointInTimeScopeHashIsolation", pointInTimeScopeHashIsolation)
            .put("coherentScopeGuard", coherentScopeGuard)
            .put("graphEdges", graphEdgesComplete)
            .put("currentStateProjection", invalidatedNotCurrent)
            .put("projectionDeleteRebuildExact", dirtyRebuildExact)
            .put("closeReopenProjectionRebuild", closeReopenProjectionRebuild)
            .put("projectionSha256", reopenedProjection.canonicalSha256)
            .put("eventSequence", reopenedProjection.eventSequence)
            .put("sourceRecordCount", reopenedProjection.sourceRecordCount)
            .put("sourceEventCount", reopenedProjection.sourceEventCount)
            .put("integrityClean", integrity.clean)
            .put("sqliteIntegrity", integrity.sqliteIntegrity)

        val n23Ok = listOf(
            "candidatePipeline",
            "allOutcomes",
            "evidenceNotTruth",
            "repetitionNoTrustUpgrade",
            "strongerSupersession",
            "conflictNoSilentOverwrite",
            "crossProjectIsolation",
            "simulationIsolation",
            "protectedPolicyIsolation",
            "invalidCandidateRejected",
            "invalidationPreservesHistory",
            "projectionDirtySemantics"
        ).all { n23.optBoolean(it, false) } && reconciliationEvents >= 11
        val n24Ok = listOf(
            "biTemporalPointInTime",
            "scopeHashIsolation",
            "pointInTimeScopeHashIsolation",
            "coherentScopeGuard",
            "graphEdges",
            "currentStateProjection",
            "projectionDeleteRebuildExact",
            "closeReopenProjectionRebuild",
            "integrityClean"
        ).all { n24.optBoolean(it, false) }

        handle.close()

        return JSONObject()
            .put("schema", SCHEMA)
            .put("ok", n23Ok && n24Ok)
            .put("diagnosticOnly", true)
            .put("runtimeAuthority", false)
            .put("database", "app-private/riftmemory-diagnostics/n2-m2-proof.sqlite")
            .put("n2_3", n23)
            .put("n2_4", n24)
            .put("integrityFindings", JSONArray(integrity.findings))
    }

    private fun candidate(
        id: String,
        recordId: String,
        scope: RiftMemoryScopeV1,
        branch: RiftMemoryBranchV1,
        evidenceTrust: RiftMemoryTrustStateV1,
        requestedTrust: RiftMemoryTrustStateV1,
        at: Long,
        value: String,
        conflictKey: String,
        entityId: String? = null,
        dependsOn: List<String> = emptyList(),
        kind: RiftMemoryRecordKindV1 = RiftMemoryRecordKindV1.CLAIM,
        authorityNamespace: String? = null,
        authorityClass: RiftMemoryAuthorityClassV1 = RiftMemoryAuthorityClassV1.VERIFIED_SOURCE,
        action: RiftMemoryCandidateActionV1 = RiftMemoryCandidateActionV1.UPSERT
    ): RiftMemoryCandidateV1 {
        val bytes = "n2-m2|$id|$value|$at".toByteArray(Charsets.UTF_8)
        val hash = sha256(bytes)
        val evidence = RiftMemoryEvidenceV1(
            id = "ev-$id",
            contentSha256 = hash,
            mediaType = "text/plain",
            sourceType = "diagnostic",
            sourceRef = "n2-m2/$id",
            scope = scope,
            branch = branch,
            observedAt = at,
            recordedAt = at,
            trustState = evidenceTrust,
            metadata = JSONObject().put("candidateId", id)
        )
        val payload = JSONObject()
            .put("value", value)
            .put("conflictKey", conflictKey)
        if (entityId != null) payload.put("entityId", entityId)
        if (dependsOn.isNotEmpty()) payload.put("dependsOn", JSONArray(dependsOn))

        val record = RiftCanonicalMemoryRecordV1(
            id = recordId,
            kind = kind,
            scope = scope,
            branch = branch,
            trustState = requestedTrust,
            time = RiftMemoryBiTemporalV1(validFrom = at, recordedAt = at),
            payload = payload,
            evidenceRefs = listOf(evidence.id),
            authorityNamespace = authorityNamespace
        )
        return RiftMemoryCandidateV1(
            id = id,
            record = record,
            evidence = listOf(evidence),
            contentBySha256 = mapOf(hash to bytes),
            authorityClass = authorityClass,
            action = action,
            metadata = JSONObject().put("selfTest", true)
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
