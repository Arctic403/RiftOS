package com.riftos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

object RiftMemoryN2M5SelfTest {
    const val SCHEMA = "rift-memory-n2-m5-selftest-v1"
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
        val dbFile = File(proofDir, "n2-m5-proof.sqlite")
        dbFile.delete()
        File(dbFile.absolutePath + "-journal").delete()
        File(dbFile.absolutePath + "-wal").delete()
        File(dbFile.absolutePath + "-shm").delete()

        val store = RiftSqliteMemoryStoreV1()
        var handle = store.open(RiftMemoryStoreConfigV1(dbFile.absolutePath))
        val reconcile = RiftMemoryReconciliationV1()
        val loop = RiftMemoryObserverValidatorLoopV1()
        val specialist = RiftMemoryProceduralFailureV1()
        val namespace = "diagnostic/n2-m5"
        val scope = RiftMemoryScopeV1(namespace, "riftfs/workspace", "RiftOS-main")
        val otherScope = RiftMemoryScopeV1(namespace, "riftfs/workspace", "OtherProject")
        val base = 4_000_000L

        val initialRepo = reconcile.reconcile(
            handle,
            provisionalStateCandidate(
                "candidate-initial-repo",
                "repo-state",
                scope,
                "workspace.state",
                "clean",
                base + 10
            )
        )
        val initialValidation = reconcile.reconcile(
            handle,
            provisionalStateCandidate(
                "candidate-initial-validation",
                "validation-state",
                scope,
                "build.validation",
                "passed",
                base + 20
            )
        )
        val initialCommitment = reconcile.reconcile(
            handle,
            specialist.createCommitmentCandidate(
                handle = handle,
                candidateId = "candidate-initial-commitment",
                recordId = "task-m5",
                sourceRecordIds = listOf("repo-state"),
                title = "Finish N2-M5 closed-loop proof",
                resumeKey = "n2-m5-closed-loop",
                status = "COMPLETED",
                at = base + 30
            )
        )
        val initialStateReady =
            initialRepo.outcome == RiftMemoryTransactionOutcomeV1.PROVISIONAL &&
            initialValidation.outcome == RiftMemoryTransactionOutcomeV1.PROVISIONAL &&
            initialCommitment.outcome == RiftMemoryTransactionOutcomeV1.PROVISIONAL

        val incompleteSnapshot = RiftMemoryVerificationSnapshotV1(
            origin = RiftMemoryVerificationOriginV1.OBSERVER,
            snapshotId = "observer-incomplete",
            scope = scope,
            subject = "workspace.state",
            value = "dirty",
            sourceStateSha256 = sha256("observer-incomplete"),
            sourceSequence = 10,
            complete = false,
            observedAt = base + 40
        )
        val incompleteObservation = reconcile.reconcile(
            handle,
            loop.createObservationCandidate(
                "candidate-incomplete-observation",
                "observer-incomplete-record",
                "ev-observer-incomplete",
                incompleteSnapshot
            )
        )
        val incompleteEvidenceProvisional =
            incompleteObservation.outcome == RiftMemoryTransactionOutcomeV1.PROVISIONAL &&
            handle.getCanonicalRecord("observer-incomplete-record")?.trustState == RiftMemoryTrustStateV1.PROVISIONAL

        val incompleteCannotMutateTarget = runCatching {
            loop.processState(
                handle,
                reconcile,
                incompleteSnapshot,
                "repo-state",
                "observer-incomplete-target-observation",
                "difference-incomplete",
                "m5-incomplete"
            )
        }.isFailure &&
            handle.getCanonicalRecord("repo-state")?.payload?.optString("value") == "clean"

        val freshObserver = RiftMemoryVerificationSnapshotV1(
            origin = RiftMemoryVerificationOriginV1.OBSERVER,
            snapshotId = "observer-workspace-20",
            scope = scope,
            subject = "workspace.state",
            value = "dirty",
            sourceStateSha256 = sha256("observer-workspace-20"),
            sourceSequence = 20,
            complete = true,
            observedAt = base + 50
        )
        val freshLoop = loop.processState(
            handle,
            reconcile,
            freshObserver,
            "repo-state",
            "observer-workspace-20-record",
            "difference-workspace-20",
            "m5-observer-fresh"
        )
        val repoAfterFresh = handle.getCanonicalRecord("repo-state") ?: error("missing repo-state after fresh observer")
        val staleStateReconciled =
            freshLoop.observation.outcome == RiftMemoryTransactionOutcomeV1.COMMITTED &&
            freshLoop.difference?.outcome == RiftMemoryTransactionOutcomeV1.PROVISIONAL &&
            freshLoop.target.outcome == RiftMemoryTransactionOutcomeV1.COMMITTED &&
            freshLoop.target.reasons.contains("stronger-evidence-supersession") &&
            repoAfterFresh?.payload?.optString("value") == "dirty" &&
            repoAfterFresh.trustState == RiftMemoryTrustStateV1.VERIFIED

        val staleObserver = RiftMemoryVerificationSnapshotV1(
            origin = RiftMemoryVerificationOriginV1.OBSERVER,
            snapshotId = "observer-workspace-19",
            scope = scope,
            subject = "workspace.state",
            value = "clean",
            sourceStateSha256 = sha256("observer-workspace-19"),
            sourceSequence = 19,
            complete = true,
            observedAt = base + 60
        )
        val staleObserverBlocked = runCatching {
            loop.processState(
                handle,
                reconcile,
                staleObserver,
                "repo-state",
                "observer-workspace-19-record",
                "difference-workspace-19",
                "m5-observer-stale"
            )
        }.isFailure &&
            handle.getCanonicalRecord("repo-state")?.payload?.optString("value") == "dirty"

        val failedValidator = RiftMemoryVerificationSnapshotV1(
            origin = RiftMemoryVerificationOriginV1.VALIDATOR,
            snapshotId = "validator-build-30",
            scope = scope,
            subject = "build.validation",
            value = "failed",
            sourceStateSha256 = sha256("validator-build-30"),
            sourceSequence = 30,
            complete = true,
            passed = false,
            observedAt = base + 70,
            details = JSONObject().put("gate", "source-checks")
        )
        val failedLoop = loop.processState(
            handle,
            reconcile,
            failedValidator,
            "validation-state",
            "validator-build-30-record",
            "difference-build-30",
            "m5-validator-failed"
        )
        val validationAfterFailure = handle.getCanonicalRecord("validation-state") ?: error("missing validation-state after validator failure")
        val failedValidationReconciled =
            failedLoop.observation.outcome == RiftMemoryTransactionOutcomeV1.COMMITTED &&
            failedLoop.difference?.outcome == RiftMemoryTransactionOutcomeV1.PROVISIONAL &&
            failedLoop.target.outcome == RiftMemoryTransactionOutcomeV1.COMMITTED &&
            validationAfterFailure?.payload?.optString("value") == "failed" &&
            validationAfterFailure.payload.opt("passed") == false &&
            validationAfterFailure.trustState == RiftMemoryTrustStateV1.VERIFIED

        val falseCompletionValidator = RiftMemoryVerificationSnapshotV1(
            origin = RiftMemoryVerificationOriginV1.VALIDATOR,
            snapshotId = "validator-task-40",
            scope = scope,
            subject = "task.m5.status",
            value = "OPEN",
            sourceStateSha256 = sha256("validator-task-40"),
            sourceSequence = 40,
            complete = true,
            passed = false,
            observedAt = base + 80,
            details = JSONObject().put("reason", "required-validation-failed")
        )
        val reopenLoop = loop.processCommitment(
            handle = handle,
            reconcile = reconcile,
            snapshot = falseCompletionValidator,
            targetRecordId = "task-m5",
            observationRecordId = "validator-task-40-record",
            differenceRecordId = "difference-task-40",
            candidatePrefix = "m5-false-completion",
            title = "Finish N2-M5 closed-loop proof",
            resumeKey = "n2-m5-closed-loop",
            status = "OPEN"
        )
        val taskAfterReopen = handle.getCanonicalRecord("task-m5") ?: error("missing task-m5 after reopen")
        val falseCompletionReopened =
            reopenLoop.observation.outcome == RiftMemoryTransactionOutcomeV1.COMMITTED &&
            reopenLoop.difference?.outcome == RiftMemoryTransactionOutcomeV1.PROVISIONAL &&
            reopenLoop.target.outcome == RiftMemoryTransactionOutcomeV1.COMMITTED &&
            taskAfterReopen?.payload?.optString("status") == "OPEN" &&
            taskAfterReopen.trustState == RiftMemoryTrustStateV1.VERIFIED

        val crossProjectBlocked = runCatching {
            loop.processState(
                handle,
                reconcile,
                RiftMemoryVerificationSnapshotV1(
                    origin = RiftMemoryVerificationOriginV1.OBSERVER,
                    snapshotId = "observer-other-project",
                    scope = otherScope,
                    subject = "workspace.state",
                    value = "clean",
                    sourceStateSha256 = sha256("observer-other-project"),
                    sourceSequence = 50,
                    complete = true,
                    observedAt = base + 90
                ),
                "repo-state",
                "observer-other-project-record",
                "difference-other-project",
                "m5-cross-project"
            )
        }.isFailure

        val boundedFailClosed = runCatching {
            RiftMemoryVerificationSnapshotV1(
                origin = RiftMemoryVerificationOriginV1.OBSERVER,
                snapshotId = "observer-oversized",
                scope = scope,
                subject = "workspace.state",
                value = "x".repeat(RiftMemoryObserverValidatorLoopV1.MAX_VALUE_CHARS + 1),
                sourceStateSha256 = sha256("observer-oversized"),
                sourceSequence = 60,
                complete = true,
                observedAt = base + 100
            )
        }.isFailure

        val differenceRecordsPersisted =
            handle.getCanonicalRecord("difference-workspace-20")?.payload?.optString("status") == "UNRESOLVED" &&
            handle.getCanonicalRecord("difference-build-30")?.payload?.optString("status") == "UNRESOLVED" &&
            handle.getCanonicalRecord("difference-task-40")?.payload?.optString("status") == "UNRESOLVED"

        handle.close()
        handle = store.open(RiftMemoryStoreConfigV1(dbFile.absolutePath, createIfMissing = false))

        val repoAfterRestart = handle.getCanonicalRecord("repo-state") ?: error("missing repo-state after restart")
        val validationAfterRestart = handle.getCanonicalRecord("validation-state") ?: error("missing validation-state after restart")
        val taskAfterRestart = handle.getCanonicalRecord("task-m5") ?: error("missing task-m5 after restart")
        val openCommitments = specialist.openCommitments(handle, namespace, scope.projectId ?: error("missing project"))
        val restartRecovery =
            repoAfterRestart?.payload?.optString("value") == "dirty" &&
            repoAfterRestart.trustState == RiftMemoryTrustStateV1.VERIFIED &&
            validationAfterRestart?.payload?.optString("value") == "failed" &&
            validationAfterRestart.trustState == RiftMemoryTrustStateV1.VERIFIED &&
            taskAfterRestart?.payload?.optString("status") == "OPEN" &&
            taskAfterRestart.trustState == RiftMemoryTrustStateV1.VERIFIED &&
            openCommitments.map { it.id } == listOf("task-m5")

        val evidenceOnlyAuthority =
            repoAfterRestart?.evidenceRefs?.isNotEmpty() == true &&
            validationAfterRestart?.evidenceRefs?.isNotEmpty() == true &&
            taskAfterRestart?.evidenceRefs?.isNotEmpty() == true &&
            repoAfterRestart.payload.optString("verificationOrigin") == RiftMemoryVerificationOriginV1.OBSERVER.name &&
            validationAfterRestart.payload.optString("verificationOrigin") == RiftMemoryVerificationOriginV1.VALIDATOR.name &&
            taskAfterRestart.payload.optString("verificationOrigin") == RiftMemoryVerificationOriginV1.VALIDATOR.name

        val integrity = handle.verifyIntegrity(
            RiftMemoryQueryV1(namespace = namespace),
            RiftMemoryBoundsV1(limit = 1_000)
        )
        handle.close()

        val n29 = JSONObject()
            .put("initialStateReady", initialStateReady)
            .put("incompleteEvidenceProvisional", incompleteEvidenceProvisional)
            .put("incompleteCannotMutateTarget", incompleteCannotMutateTarget)
            .put("staleStateReconciled", staleStateReconciled)
            .put("staleObserverBlocked", staleObserverBlocked)
            .put("failedValidationReconciled", failedValidationReconciled)
            .put("falseCompletionReopened", falseCompletionReopened)
            .put("crossProjectBlocked", crossProjectBlocked)
            .put("boundedFailClosed", boundedFailClosed)
            .put("differenceRecordsPersisted", differenceRecordsPersisted)
            .put("restartRecovery", restartRecovery)
            .put("evidenceOnlyAuthority", evidenceOnlyAuthority)
            .put("integrityClean", integrity.clean)
            .put("sqliteIntegrity", integrity.sqliteIntegrity)

        val ok = listOf(
            "initialStateReady",
            "incompleteEvidenceProvisional",
            "incompleteCannotMutateTarget",
            "staleStateReconciled",
            "staleObserverBlocked",
            "failedValidationReconciled",
            "falseCompletionReopened",
            "crossProjectBlocked",
            "boundedFailClosed",
            "differenceRecordsPersisted",
            "restartRecovery",
            "evidenceOnlyAuthority",
            "integrityClean"
        ).all { n29.optBoolean(it, false) } && integrity.sqliteIntegrity == "ok"

        return JSONObject()
            .put("schema", SCHEMA)
            .put("ok", ok)
            .put("diagnosticOnly", true)
            .put("runtimeAuthority", false)
            .put("database", "app-private/riftmemory-diagnostics/n2-m5-proof.sqlite")
            .put("n2_9", n29)
            .put("integrityFindings", JSONArray(integrity.findings))
    }

    private fun provisionalStateCandidate(
        candidateId: String,
        recordId: String,
        scope: RiftMemoryScopeV1,
        subject: String,
        value: String,
        at: Long
    ): RiftMemoryCandidateV1 {
        val record = RiftCanonicalMemoryRecordV1(
            id = recordId,
            kind = RiftMemoryRecordKindV1.CLAIM,
            scope = scope,
            branch = RiftMemoryBranchV1.REALITY,
            trustState = RiftMemoryTrustStateV1.PROVISIONAL,
            time = RiftMemoryBiTemporalV1(at, recordedAt = at),
            payload = JSONObject()
                .put("memoryClass", "PLANNER_STATE")
                .put("subject", subject)
                .put("value", value)
                .put("conflictKey", "closed-loop:$subject"),
            evidenceRefs = emptyList()
        )
        return RiftMemoryCandidateV1(
            id = candidateId,
            record = record,
            evidence = emptyList(),
            contentBySha256 = emptyMap(),
            authorityClass = RiftMemoryAuthorityClassV1.ORDINARY,
            metadata = JSONObject()
                .put("phase", "N2.9")
                .put("source", "planner-tools")
                .put("selfTest", true)
        )
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
