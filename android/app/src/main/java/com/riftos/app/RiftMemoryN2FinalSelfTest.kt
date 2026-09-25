package com.riftos.app

import android.os.Build
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject

object RiftMemoryN2FinalSelfTest {
    const val SCHEMA = "rift-memory-n2-final-selftest-v1"
    const val FROZEN_CONTRACT_FILE_SHA256 = "4317788b26d1dd8ddd28959e026376434c50d950f0988b22c78b74fdd3a9d794"
    const val TERMINOLOGY_SHA256 = "bf18c5f2db272c5a66723879ab021a3ffc42483c4cd4fc6597d10bd96fc949d8"
    const val MEMORY_STORE_SHA256 = "68c46266e926e546e95543eb7a2092576c91499f810bcc5d483671d706823d16"
    const val CORRECTNESS_CORPUS_SHA256 = "4ec6cd3e133de7c9a4f5f4d91c48b0873df02fb79a7b19727c96256b6e4687c4"
    const val CORRECTNESS_THRESHOLDS_SHA256 = "a6307031907834e0bb4060d24209a98706df4d0bfe39a7936cc5750fb06f6801"
    const val CONTRACT_PAYLOAD_SHA256 = "bf70f093f303f745b2e861431f2890be87367160c7cfbbd1c1a22004c49b4bb2"
    const val BENCHMARK_RULE = "NO_PERFORMANCE_OR_COMPARATIVE_BENCHMARKS_UNTIL_FULL_RIFTCLI_COMPLETE_AND_LIVE"

    private val lock = Any()
    private var cached: JSONObject? = null

    fun run(context: android.content.Context): JSONObject = synchronized(lock) {
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

    private fun execute(context: android.content.Context): JSONObject {
        val m1 = RiftMemoryN2M1SelfTest.run(context)
        val m2 = RiftMemoryN2M2SelfTest.run(context)
        val m3 = RiftMemoryN2M3SelfTest.run(context)
        val m4 = RiftMemoryN2M4SelfTest.run(context)
        val m5 = RiftMemoryN2M5SelfTest.run(context)
        val m6 = RiftMemoryN2M6SelfTest.run(context)

        val n21 = m1.optJSONObject("n2_1") ?: error("n2-final-missing-n2-1")
        val n22 = m1.optJSONObject("n2_2") ?: error("n2-final-missing-n2-2")
        val n23 = m2.optJSONObject("n2_3") ?: error("n2-final-missing-n2-3")
        val n24 = m2.optJSONObject("n2_4") ?: error("n2-final-missing-n2-4")
        val n25 = m3.optJSONObject("n2_5") ?: error("n2-final-missing-n2-5")
        val n26 = m3.optJSONObject("n2_6") ?: error("n2-final-missing-n2-6")
        val n27 = m4.optJSONObject("n2_7") ?: error("n2-final-missing-n2-7")
        val n28 = m4.optJSONObject("n2_8") ?: error("n2-final-missing-n2-8")
        val n29 = m5.optJSONObject("n2_9") ?: error("n2-final-missing-n2-9")
        val n210 = m6.optJSONObject("n2_10") ?: error("n2-final-missing-n2-10")
        val n211 = m6.optJSONObject("n2_11") ?: error("n2-final-missing-n2-11")

        val scenarios = JSONArray()
        scenario(scenarios, "evidence-not-truth",
            n23.optBoolean("evidenceNotTruth"),
            "M2:n2_3.evidenceNotTruth")
        scenario(scenarios, "repetition-no-trust-upgrade",
            n23.optBoolean("repetitionNoTrustUpgrade"),
            "M2:n2_3.repetitionNoTrustUpgrade")
        scenario(scenarios, "supersession-retains-history",
            n23.optBoolean("strongerSupersession") &&
                n23.optBoolean("invalidationPreservesHistory") &&
                n21.optBoolean("provenance"),
            "M2:strongerSupersession+invalidationPreservesHistory;M1:provenance")
        scenario(scenarios, "conflict-no-fabricated-consensus",
            n23.optBoolean("conflictNoSilentOverwrite") &&
                n26.optBoolean("unresolvedConflictState"),
            "M2:conflictNoSilentOverwrite;M3:unresolvedConflictState")
        scenario(scenarios, "cross-project-isolation",
            n23.optBoolean("crossProjectIsolation") &&
                n25.optBoolean("projectIsolation") &&
                n28.optBoolean("projectIsolation") &&
                n29.optBoolean("crossProjectBlocked"),
            "M2+M3+M4+M5 project isolation")
        scenario(scenarios, "protected-policy-overwrite",
            n23.optBoolean("protectedPolicyIsolation") &&
                n27.optBoolean("protectedPolicy"),
            "M2:protectedPolicyIsolation;M4:protectedPolicy")
        scenario(scenarios, "projection-loss-rebuild",
            n24.optBoolean("projectionDeleteRebuildExact") &&
                n24.optBoolean("closeReopenProjectionRebuild") &&
                n211.optBoolean("indexCorruptionRebuild"),
            "M2:exact projection rebuild;M6:indexCorruptionRebuild")
        scenario(scenarios, "partial-transaction-crash",
            n22.optBoolean("crashRollbackRecovered") &&
                n211.optBoolean("riftStoreCrashRollbackRecovered"),
            "M1:SQLite crash rollback;M6:RiftStore crash rollback")
        scenario(scenarios, "committed-restart-reconstruction",
            n22.optBoolean("processRestartRecovered") &&
                n211.optBoolean("coldRestartRecovered") &&
                m6.optBoolean("restartPromotionReady"),
            "M1:processRestartRecovered;M6:coldRestartRecovered")
        scenario(scenarios, "commitment-restart",
            n27.optBoolean("unfinishedTaskRestart") &&
                n29.optBoolean("restartRecovery"),
            "M4:unfinishedTaskRestart;M5:restartRecovery")
        scenario(scenarios, "simulation-isolation",
            n23.optBoolean("simulationIsolation"),
            "M2:n2_3.simulationIsolation")
        scenario(scenarios, "content-hash-corruption",
            n22.optBoolean("corruptionDetected") &&
                n211.optBoolean("stateSealTamperDetected") &&
                n211.optBoolean("memoryFsck"),
            "M1:content corruption;M6:state seal + fsck")

        val scenarioPassCount = (0 until scenarios.length())
            .count { scenarios.getJSONObject(it).optBoolean("passed") }
        val corpusAllPass = scenarioPassCount == 12

        val weakestLinks = JSONObject()
            .put("canonicalTruthIntegrity",
                n22.optBoolean("integrityClean") &&
                    n24.optBoolean("integrityClean") &&
                    n210.optBoolean("referenceIntegrityClean") &&
                    n210.optBoolean("riftStoreIntegrityClean") &&
                    n211.optBoolean("memoryFsck"))
            .put("projectIsolation",
                n23.optBoolean("crossProjectIsolation") &&
                    n25.optBoolean("projectIsolation") &&
                    n28.optBoolean("projectIsolation") &&
                    n29.optBoolean("crossProjectBlocked"))
            .put("provenance",
                n21.optBoolean("provenance") &&
                    n25.optBoolean("provenancePreserved") &&
                    n25.optBoolean("reversibleLineage"))
            .put("reconciliation",
                n23.optBoolean("candidatePipeline") &&
                    n23.optBoolean("allOutcomes") &&
                    n26.optBoolean("strongerObservationReconciles") &&
                    n29.optBoolean("failedValidationReconciled"))
            .put("crashRestartSafety",
                m1.optBoolean("restartPromotionReady") &&
                    m6.optBoolean("restartPromotionReady"))
            .put("poisoningResistance",
                n22.optBoolean("corruptionDetected") &&
                    n23.optBoolean("protectedPolicyIsolation") &&
                    n25.optBoolean("unsupportedTrustBlocked") &&
                    n211.optBoolean("stateSealTamperDetected") &&
                    n211.optBoolean("invalidEvidenceRejected") &&
                    n211.optBoolean("missingEvidenceRejected"))
            .put("proceduralLearning",
                n27.optBoolean("proceduralTransfer") &&
                    n27.optBoolean("failureRecurrence") &&
                    n27.optBoolean("causalConfidence") &&
                    n27.optBoolean("causalWeakSourceBlocked"))
            .put("projectionRebuild",
                n24.optBoolean("projectionDeleteRebuildExact") &&
                    n24.optBoolean("closeReopenProjectionRebuild") &&
                    n211.optBoolean("indexCorruptionRebuild"))
            .put("boundedResourceSafety",
                n28.optBoolean("contextBudgetFailClosed") &&
                    n28.optBoolean("boundedFailClosed") &&
                    n28.optBoolean("documentProjectionFailClosed") &&
                    n29.optBoolean("boundedFailClosed") &&
                    n211.optBoolean("oversizeStateRejectedBeforeRead") &&
                    n211.optBoolean("boundedFailClosed") &&
                    n211.optBoolean("arm32BoundedResourceContract"))
            .put("falseCompletionProtection",
                n29.optBoolean("falseCompletionReopened") &&
                    n29.optBoolean("evidenceOnlyAuthority"))
        val weakestLinksAllPass = weakestLinks.keys().asSequence()
            .all { weakestLinks.optBoolean(it) }

        val thresholds = JSONObject()
            .put("falseTrustedMemory", if (
                n23.optBoolean("evidenceNotTruth") &&
                n23.optBoolean("repetitionNoTrustUpgrade") &&
                n25.optBoolean("unsupportedTrustBlocked")
            ) 0 else 1)
            .put("crossProjectContamination", if (
                n23.optBoolean("crossProjectIsolation") &&
                n25.optBoolean("projectIsolation") &&
                n28.optBoolean("projectIsolation") &&
                n29.optBoolean("crossProjectBlocked")
            ) 0 else 1)
            .put("unsupportedTrustedClaims", if (
                n23.optBoolean("repetitionNoTrustUpgrade") &&
                n25.optBoolean("unsupportedTrustBlocked") &&
                n27.optBoolean("derivedTrustCeiling")
            ) 0 else 1)
            .put("successfulPolicyOrMemoryPoisoning", if (
                n23.optBoolean("protectedPolicyIsolation") &&
                n27.optBoolean("protectedPolicy") &&
                n211.optBoolean("stateSealTamperDetected") &&
                n211.optBoolean("invalidEvidenceRejected") &&
                n211.optBoolean("missingEvidenceRejected")
            ) 0 else 1)
            .put("lostRequiredPersistentMemory", if (
                n22.optBoolean("processRestartRecovered") &&
                n27.optBoolean("unfinishedTaskRestart") &&
                n29.optBoolean("restartRecovery") &&
                n211.optBoolean("coldRestartRecovered")
            ) 0 else 1)
            .put("brokenProvenance", if (
                n21.optBoolean("provenance") &&
                n25.optBoolean("provenancePreserved") &&
                n25.optBoolean("reversibleLineage")
            ) 0 else 1)
            .put("falseCompletionCausedByMemory", if (
                n29.optBoolean("falseCompletionReopened") &&
                n29.optBoolean("failedValidationReconciled")
            ) 0 else 1)
            .put("irrecoverableProjectionCorruption", if (
                n24.optBoolean("projectionDeleteRebuildExact") &&
                n24.optBoolean("closeReopenProjectionRebuild") &&
                n211.optBoolean("indexCorruptionRebuild")
            ) 0 else 1)
            .put("performanceComparativeBenchmarks",
                "DEFERRED_UNTIL_FULL_RIFTCLI_COMPLETE_AND_LIVE")
        val zeroTolerancePass = listOf(
            "falseTrustedMemory",
            "crossProjectContamination",
            "unsupportedTrustedClaims",
            "successfulPolicyOrMemoryPoisoning",
            "lostRequiredPersistentMemory",
            "brokenProvenance",
            "falseCompletionCausedByMemory",
            "irrecoverableProjectionCorruption"
        ).all { thresholds.optInt(it, 1) == 0 }

        val priorDiagnostics = JSONObject()
            .put("M1", m1.optBoolean("ok"))
            .put("M2", m2.optBoolean("ok"))
            .put("M3", m3.optBoolean("ok"))
            .put("M4", m4.optBoolean("ok"))
            .put("M5", m5.optBoolean("ok"))
            .put("M6", m6.optBoolean("ok"))
        val priorDiagnosticsAllGreen = priorDiagnostics.keys().asSequence()
            .all { priorDiagnostics.optBoolean(it) }

        val sourceSha = BuildConfig.RIFT_SOURCE_SHA.trim().lowercase()
        val runId = BuildConfig.RIFT_BUILD_RUN_ID.trim()
        val runNumber = BuildConfig.RIFT_BUILD_RUN_NUMBER.trim()
        val sourceBuildRuntimeContinuity =
            Regex("^[0-9a-f]{40}$").matches(sourceSha) &&
                runId.matches(Regex("^[0-9]+$")) &&
                runNumber.matches(Regex("^[0-9]+$"))

        val abiEvidence = JSONObject()
            .put("installedProcess64Bit", Process.is64Bit())
            .put("installedArm32", !Process.is64Bit() &&
                Build.SUPPORTED_32_BIT_ABIS.isNotEmpty())
            .put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("supported32BitAbis", JSONArray(Build.SUPPORTED_32_BIT_ABIS.toList()))
            .put("supported64BitAbis", JSONArray(Build.SUPPORTED_64_BIT_ABIS.toList()))
            .put("abiNeutralStoreSemantics", n211.optBoolean("abiNeutralStoreSemantics"))
            .put("arm64BuildParitySourceGated", true)
            .put("arm64InstalledDeviceExecutionClaimed", false)
        val arm32InstalledProof = abiEvidence.optBoolean("installedArm32")
        val abiCorrectnessReady =
            arm32InstalledProof &&
                abiEvidence.optBoolean("abiNeutralStoreSemantics") &&
                abiEvidence.optBoolean("arm64BuildParitySourceGated") &&
                !abiEvidence.optBoolean("arm64InstalledDeviceExecutionClaimed")

        val frozenIdentities = JSONObject()
            .put("contractFileSha256", FROZEN_CONTRACT_FILE_SHA256)
            .put("terminologySha256", TERMINOLOGY_SHA256)
            .put("memoryStoreSha256", MEMORY_STORE_SHA256)
            .put("correctnessCorpusSha256", CORRECTNESS_CORPUS_SHA256)
            .put("correctnessThresholdsSha256", CORRECTNESS_THRESHOLDS_SHA256)
            .put("contractPayloadSha256", CONTRACT_PAYLOAD_SHA256)
            .put("benchmarkRule", BENCHMARK_RULE)

        val evidenceTable = JSONObject()
            .put("frozenIdentities", frozenIdentities)
            .put("priorDiagnostics", priorDiagnostics)
            .put("scenarios", scenarios)
            .put("scenarioPassCount", scenarioPassCount)
            .put("scenarioCount", 12)
            .put("zeroTolerance", thresholds)
            .put("weakestLinks", weakestLinks)
            .put("sqliteReferenceIntegrity", n210.optBoolean("referenceIntegrityClean"))
            .put("riftStoreConformance", n210.optBoolean("fixtureConformance"))
            .put("riftStoreIntegrity", n210.optBoolean("riftStoreIntegrityClean"))
            .put("riftStoreRestartReady", m6.optBoolean("restartPromotionReady"))
            .put("arm32InstalledDeviceProof", arm32InstalledProof)
            .put("arm64CorrectnessBuildParity", abiCorrectnessReady)
            .put("sourceSha", sourceSha)
            .put("builderRunId", runId)
            .put("builderRunNumber", runNumber)
            .put("sourceBuildRuntimeContinuity", sourceBuildRuntimeContinuity)
            .put("comparativePerformanceBenchmarksExecuted", false)
            .put("benchmarkDeferredUntilFullRiftCliComplete", true)
            .put("knownLimitation",
                "ARM64 build/correctness parity is source/Builder-gated; no ARM64 installed-device execution is claimed by this ARM32 proof device.")

        val ok = priorDiagnosticsAllGreen &&
            corpusAllPass &&
            zeroTolerancePass &&
            weakestLinksAllPass &&
            sourceBuildRuntimeContinuity &&
            abiCorrectnessReady &&
            n210.optBoolean("sqliteReferenceBackend") &&
            !n210.optBoolean("productionReplacement") &&
            n210.optBoolean("comparativePerformanceDeferred") &&
            !evidenceTable.optBoolean("comparativePerformanceBenchmarksExecuted")

        return JSONObject()
            .put("schema", SCHEMA)
            .put("ok", ok)
            .put("diagnosticOnly", true)
            .put("runtimeAuthority", false)
            .put("phase", "N2.12")
            .put("frozenContractValidatedBySourceGate", true)
            .put("corpusAllPass", corpusAllPass)
            .put("zeroTolerancePass", zeroTolerancePass)
            .put("weakestLinksAllPass", weakestLinksAllPass)
            .put("priorDiagnosticsAllGreen", priorDiagnosticsAllGreen)
            .put("sourceBuildRuntimeContinuity", sourceBuildRuntimeContinuity)
            .put("arm32InstalledDeviceProof", arm32InstalledProof)
            .put("arm64CorrectnessBuildParity", abiCorrectnessReady)
            .put("evidenceTable", evidenceTable)
    }

    private fun scenario(
        rows: JSONArray,
        id: String,
        passed: Boolean,
        evidence: String
    ) {
        rows.put(
            JSONObject()
                .put("id", id)
                .put("passed", passed)
                .put("evidence", evidence)
        )
    }
}
