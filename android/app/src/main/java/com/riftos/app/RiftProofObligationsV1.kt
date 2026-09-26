package com.riftos.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * N1.8.5 evidence-only proof planner.
 *
 * Consumes the exact semantic candidate-impact evidence produced by PI-v2 and emits a deterministic
 * verification plan. It never executes checks, never marks a proof as passed, and never substitutes
 * an unrelated/general test for an affected-test obligation.
 */
internal class RiftProofObligationsV1 {
    companion object {
        const val SCHEMA = "rift-proof-obligations-v1"
        const val PHASE = "N1.8.5"
        private const val HASH_SCOPE = "project-local-plan-v1"

        private const val MAX_CHANGES = 1_024
        private const val MAX_SELECTED_TESTS = 512
        private const val MAX_SUPPLEMENTAL_TESTS = 512
        private const val MAX_CHECKS = 128
        private const val MAX_OBLIGATIONS = 1_024
        private const val MAX_PREVIEW_ROWS = 240
    }

    private data class TestProof(
        val path: String,
        val evidence: Set<String>
    )

    private fun isRepositoryMetadataPath(path: String, root: String): Boolean {
        val normalized = path.replace('\\', '/').trim('/')
        val normalizedRoot = root.replace('\\', '/').trim('/')
        return normalized == ".riftgit.json" || normalized == "$normalizedRoot/.riftgit.json"
    }

    private data class Obligation(
        val id: String,
        val kind: String,
        val state: String,
        val subject: String,
        val depth: String,
        val evidence: List<String>,
        val satisfiesBy: List<String>,
        val reason: String? = null
    )

    fun analyze(
        projectPath: String,
        impact: JSONObject,
        validation: JSONObject
    ): JSONObject {
        val root = projectPath.trimEnd('/')
        val incomplete = linkedSetOf<String>()
        val deepReasons = linkedSetOf<String>()

        val impactComplete = impact.optBoolean("complete", false)
        val impactReasons = stringArray(impact.optJSONArray("incompleteReasons"))

        val allChanges = objectArray(impact.optJSONArray("changes"))
            .filter { row -> pathWithin(row.optString("path"), root) }
            .filterNot { row -> isRepositoryMetadataPath(row.optString("path"), root) }
            .sortedBy { it.optString("path") }
        if (allChanges.size > MAX_CHANGES) incomplete += "proof-change-bound"
        val changes = allChanges.take(MAX_CHANGES)
        val hasChanges = changes.isNotEmpty()

        if (hasChanges && !impactComplete) {
            if (impactReasons.isEmpty()) incomplete += "impact-incomplete"
            impactReasons.forEach { incomplete += "impact:" + it }
            deepReasons += "impact-evidence-incomplete"
        }

        val sourceChanges = changes.filter { it.optString("category") == "source" }
        val testChanges = changes.filter { it.optString("category") == "test" }
        val documentationChanges = changes.filter { it.optString("category") == "documentation" }
        val buildChanges = changes.filter { it.optString("category") == "build-config" }
        val authorityChanges = buildChanges.filter {
            RiftSourceIntelligenceV2.isMachineAuthorityPath(it.optString("path"))
        }
        val otherChanges = changes.filter { row ->
            row.optString("category") !in setOf("source", "test", "documentation", "build-config")
        }
        val ownershipSensitive = sourceChanges.any {
            it.optString("status") == "added" || it.optString("status") == "deleted"
        }

        val apiPaths = stringArray(impact.optJSONArray("apiSurfaceChangedPaths"))
            .filter { pathWithin(it, root) }
            .sorted()

        val strongEvidence = linkedMapOf<String, LinkedHashSet<String>>()
        fun addStrong(path: String, reason: String) {
            if (!pathWithin(path, root) || !isTestPath(path)) return
            strongEvidence.getOrPut(path) { linkedSetOf() }.add(reason)
        }

        testChanges.forEach { addStrong(it.optString("path"), "changed-test") }

        objectArray(impact.optJSONArray("directDependents")).forEach { row ->
            val source = row.optString("source")
            val target = row.optString("target")
            if (pathWithin(target, root)) {
                if (row.optString("kind") == "config-read") {
                    if (pathWithin(source, root) && isVerificationScriptPath(source)) {
                        strongEvidence.getOrPut(source) { linkedSetOf() }.add("direct-dependent")
                    }
                } else {
                    addStrong(source, "direct-dependent")
                }
            }
        }

        objectArray(impact.optJSONArray("references")).forEach { row ->
            val path = row.optString("path")
            if (!row.optBoolean("definition", false)) addStrong(path, "changed-symbol-reference")
        }

        val candidateTests = stringArray(impact.optJSONArray("tests"))
            .filter { pathWithin(it, root) && isTestPath(it) }
            .distinct()
            .sorted()

        val selectedTestsAll = strongEvidence.entries
            .sortedBy { it.key }
            .map { TestProof(it.key, it.value.toSortedSet()) }
        if (selectedTestsAll.size > MAX_SELECTED_TESTS) incomplete += "proof-selected-test-bound"
        val selectedTests = selectedTestsAll.take(MAX_SELECTED_TESTS)

        val supplementalAll = candidateTests
            .filterNot { it in strongEvidence }
            .map { TestProof(it, sortedSetOf("path-affinity")) }
        if (supplementalAll.size > MAX_SUPPLEMENTAL_TESTS) incomplete += "proof-supplemental-test-bound"
        val supplementalTests = supplementalAll.take(MAX_SUPPLEMENTAL_TESTS)
        val authorityConsumerTests = selectedTests.filter { "direct-dependent" in it.evidence }

        val projectRows = objectArray(impact.optJSONArray("projects"))
        if (hasChanges && projectRows.size > 1) deepReasons += "multi-project-candidate"
        if (apiPaths.isNotEmpty()) deepReasons += "api-surface-changed"
        if (buildChanges.isNotEmpty()) deepReasons += "build-config-changed"
        if (authorityChanges.isNotEmpty()) deepReasons += "machine-authority-changed"
        if (sourceChanges.any { it.optString("status") == "deleted" }) deepReasons += "source-deletion"
        if (otherChanges.isNotEmpty()) deepReasons += "unclassified-change"

        val obligations = mutableListOf<Obligation>()
        fun addObligation(
            kind: String,
            state: String,
            subject: String,
            depth: String,
            evidence: List<String>,
            satisfiesBy: List<String>,
            reason: String? = null
        ) {
            if (obligations.size >= MAX_OBLIGATIONS) {
                incomplete += "proof-obligation-bound"
                return
            }
            val identity = JSONObject()
                .put("kind", kind)
                .put("subject", subject)
                .put("evidence", JSONArray(evidence.sorted()))
            val id = "proof-" + RiftPatchManifestV1.sha256Canonical(identity).take(20)
            obligations += Obligation(
                id = id,
                kind = kind,
                state = state,
                subject = subject,
                depth = depth,
                evidence = evidence.distinct().sorted(),
                satisfiesBy = satisfiesBy.distinct().sorted(),
                reason = reason
            )
        }

        if (hasChanges) {
            addObligation(
                kind = "repository-consistency",
                state = "required",
                subject = root,
                depth = "focused",
                evidence = changes.map { it.optString("path") },
                satisfiesBy = listOf("project:consistency")
            )
        }

        if (sourceChanges.isNotEmpty()) {
            addObligation(
                kind = "source-integrity",
                state = "required",
                subject = root,
                depth = "focused",
                evidence = sourceChanges.map { it.optString("path") },
                satisfiesBy = listOf("project:integrity")
            )
            addObligation(
                kind = "semantic-propagation",
                state = "required",
                subject = root,
                depth = if (apiPaths.isNotEmpty()) "deep" else "focused",
                evidence = (sourceChanges.map { it.optString("path") } + apiPaths),
                satisfiesBy = listOf("project:propagation")
            )
            if (selectedTests.isEmpty()) {
                deepReasons += "affected-test-evidence-missing"
                addObligation(
                    kind = "affected-tests",
                    state = "unresolved",
                    subject = root,
                    depth = "deep",
                    evidence = sourceChanges.map { it.optString("path") },
                    satisfiesBy = emptyList(),
                    reason = if (supplementalTests.isNotEmpty()) {
                        "Only heuristic/path-affinity tests were found; unrelated or heuristic tests cannot discharge this obligation."
                    } else {
                        "No directly evidenced affected test was found; unrelated/general tests cannot discharge this obligation."
                    }
                )
            } else {
                addObligation(
                    kind = "affected-tests",
                    state = "required",
                    subject = root,
                    depth = if (deepReasons.isEmpty()) "focused" else "deep",
                    evidence = selectedTests.flatMap { proof ->
                        proof.evidence.map { reason -> proof.path + "|" + reason }
                    },
                    satisfiesBy = selectedTests.map { "test:" + it.path }
                )
            }

        }

        if (authorityChanges.isNotEmpty()) {
            val satisfied = authorityConsumerTests.isNotEmpty()
            if (!satisfied) deepReasons += "authority-consumer-evidence-missing"
            addObligation(
                kind = "authority-consumer-tests",
                state = if (satisfied) "required" else "unresolved",
                subject = root,
                depth = "deep",
                evidence = (
                    authorityChanges.map { it.optString("path") } +
                        authorityConsumerTests.flatMap { proof ->
                            proof.evidence.map { reason -> proof.path + "|" + reason }
                        }
                    ).distinct(),
                satisfiesBy = authorityConsumerTests.map { "test:" + it.path },
                reason = if (satisfied) {
                    "Machine-authority changes require every directly evidenced maintained regression consumer."
                } else {
                    "Machine-authority changed but no directly evidenced maintained regression consumer was found."
                }
            )
        }

        if (testChanges.isNotEmpty()) {
            addObligation(
                kind = "changed-tests",
                state = "required",
                subject = root,
                depth = "focused",
                evidence = testChanges.map { it.optString("path") },
                satisfiesBy = testChanges.map { "test:" + it.optString("path") }
            )
        }

        if (documentationChanges.isNotEmpty() || ownershipSensitive) {
            val claimEvidence = (
                documentationChanges.map { it.optString("path") } +
                    if (ownershipSensitive) sourceChanges.map { it.optString("path") } else emptyList()
                ).distinct()
            addObligation(
                kind = "documentation-claims",
                state = "required",
                subject = root,
                depth = "focused",
                evidence = claimEvidence,
                satisfiesBy = listOf("project:claims"),
                reason = if (ownershipSensitive) {
                    "Documentation changed and/or source add/delete can change source-ownership obligations."
                } else {
                    null
                }
            )
        }

        if (sourceChanges.isNotEmpty() || buildChanges.isNotEmpty()) {
            addObligation(
                kind = "cross-boundary-contracts",
                state = "required",
                subject = root,
                depth = if (buildChanges.isNotEmpty()) "deep" else "focused",
                evidence = (
                    sourceChanges.map { it.optString("path") } +
                        buildChanges.map { it.optString("path") }
                    ).distinct(),
                satisfiesBy = listOf("project:contracts")
            )
        }

        val externalChecks = stringArray(validation.optJSONArray("externalChecks"))
        if (buildChanges.isNotEmpty()) {
            val buildSatisfiers = externalChecks.map { "external:" + it }
            if (buildSatisfiers.isEmpty()) {
                deepReasons += "build-verification-unavailable"
                addObligation(
                    kind = "build-pipeline",
                    state = "unresolved",
                    subject = root,
                    depth = "deep",
                    evidence = buildChanges.map { it.optString("path") },
                    satisfiesBy = emptyList(),
                    reason = "Build configuration changed but the project exposes no build-pipeline verification check."
                )
            } else {
                addObligation(
                    kind = "build-pipeline",
                    state = "required",
                    subject = root,
                    depth = "deep",
                    evidence = buildChanges.map { it.optString("path") },
                    satisfiesBy = buildSatisfiers
                )
            }
        }

        if (otherChanges.isNotEmpty()) {
            addObligation(
                kind = "unclassified-change",
                state = "unresolved",
                subject = root,
                depth = "deep",
                evidence = otherChanges.map { it.optString("path") },
                satisfiesBy = emptyList(),
                reason = "Changed files could not be mapped to a proof policy category."
            )
        }

        val commands = objectArray(validation.optJSONArray("commands"))
        val selectedChecks = mutableListOf<JSONObject>()
        fun addCommand(name: String, role: String, reason: String) {
            val row = commands.firstOrNull { it.optString("command") == "npm run " + name } ?: return
            if (selectedChecks.size >= MAX_CHECKS) {
                incomplete += "proof-check-bound"
                return
            }
            selectedChecks += JSONObject()
                .put("command", row.optString("command"))
                .put("source", row.optString("source"))
                .put("scope", row.optString("scope"))
                .put("role", role)
                .put("reason", reason)
                .put("canSatisfyAffectedTestObligation", false)
        }

        if (hasChanges) addCommand("check", "general-check", "Repository-level source/check gate.")
        if (sourceChanges.isNotEmpty() || testChanges.isNotEmpty() || buildChanges.isNotEmpty()) {
            addCommand("lint", "general-check", "Static/style verification for changed executable/build surfaces.")
        }
        if (sourceChanges.isNotEmpty() || buildChanges.isNotEmpty() || apiPaths.isNotEmpty()) {
            addCommand("build", "build-check", "Build verification for changed source/build/API surfaces.")
        }
        if (deepReasons.isNotEmpty() && (sourceChanges.isNotEmpty() || testChanges.isNotEmpty())) {
            addCommand(
                "test",
                "deep-general-suite",
                "General test suite is supplemental deep verification and cannot replace directly affected tests."
            )
        }

        if (sourceChanges.isNotEmpty() && selectedTests.isEmpty() && supplementalTests.isNotEmpty()) {
            deepReasons += "only-heuristic-tests"
        }

        if (incomplete.isNotEmpty()) deepReasons += "proof-plan-incomplete"

        val unresolved = obligations.filter { it.state == "unresolved" }
        val mode = when {
            !hasChanges -> "none"
            deepReasons.isNotEmpty() -> "deep"
            else -> "focused"
        }

        val obligationRows = obligations
            .sortedWith(compareBy<Obligation>({ it.kind }, { it.subject }, { it.id }))
            .map(::obligationJson)
        val selectedTestRows = selectedTests.map(::testJson)
        val supplementalRows = supplementalTests.map(::testJson)
        val checkRows = selectedChecks.sortedBy { it.optString("command") }

        val hashPayload = JSONObject()
            .put("schema", SCHEMA)
            .put("phase", PHASE)
            .put("hashScope", HASH_SCOPE)
            .put("projectRoot", root)
            .put("complete", incomplete.isEmpty())
            .put("incompleteReasons", JSONArray(incomplete.sorted()))
            .put("mode", mode)
            .put("deepReasons", JSONArray(deepReasons.sorted()))
            .put("obligations", JSONArray(obligationRows))
            .put("selectedTests", JSONArray(selectedTestRows))
            .put("supplementalTests", JSONArray(supplementalRows))
            .put("selectedChecks", JSONArray(checkRows))
            .put("externalChecks", JSONArray(externalChecks.sorted()))
            .put("apiSurfaceChangedPaths", JSONArray(apiPaths))

        val proofsSha = RiftPatchManifestV1.sha256Canonical(hashPayload)

        return JSONObject()
            .put("schema", SCHEMA)
            .put("phase", PHASE)
            .put("view", "proofs")
            .put("hashScope", HASH_SCOPE)
            .put("projectRoot", root)
            .put("evidenceOnly", true)
            .put("executesVerification", false)
            .put("complete", incomplete.isEmpty())
            .put("incompleteReasons", JSONArray(incomplete.sorted()))
            .put("verificationRequired", hasChanges)
            .put("mode", mode)
            .put("proofPlanReady", incomplete.isEmpty() && unresolved.isEmpty())
            .put("candidate", impact.optJSONObject("candidate") ?: JSONObject.NULL)
            .put("semanticImpactSha256", impact.optString("semanticImpactSha256"))
            .put("proofsSha256", proofsSha)
            .put("deepVerification", JSONObject()
                .put("required", deepReasons.isNotEmpty())
                .put("reasons", JSONArray(deepReasons.sorted())))
            .put("substitutionPolicy", JSONObject()
                .put("unrelatedTestsCanSatisfyAffectedTest", false)
                .put("heuristicTestsCanSatisfyAffectedTest", false)
                .put("generalSuiteCanSubstituteForAffectedTest", false)
                .put("missingAffectedTestBecomesUnresolvedObligation", true))
            .put("counts", JSONObject()
                .put("changes", changes.size)
                .put("sourceChanges", sourceChanges.size)
                .put("testChanges", testChanges.size)
                .put("documentationChanges", documentationChanges.size)
                .put("buildConfigChanges", buildChanges.size)
                .put("otherChanges", otherChanges.size)
                .put("apiSurfaceChangedPaths", apiPaths.size)
                .put("selectedTests", selectedTests.size)
                .put("supplementalTests", supplementalTests.size)
                .put("selectedChecks", checkRows.size)
                .put("obligations", obligations.size)
                .put("unresolvedObligations", unresolved.size))
            .put("bounds", JSONObject()
                .put("maxChanges", MAX_CHANGES)
                .put("maxSelectedTests", MAX_SELECTED_TESTS)
                .put("maxSupplementalTests", MAX_SUPPLEMENTAL_TESTS)
                .put("maxChecks", MAX_CHECKS)
                .put("maxObligations", MAX_OBLIGATIONS)
                .put("maxPreviewRows", MAX_PREVIEW_ROWS))
            .put("preview", JSONObject()
                .put("obligationsTruncated", obligationRows.size > MAX_PREVIEW_ROWS)
                .put("selectedTestsTruncated", selectedTestRows.size > MAX_PREVIEW_ROWS)
                .put("supplementalTestsTruncated", supplementalRows.size > MAX_PREVIEW_ROWS)
                .put("checksTruncated", checkRows.size > MAX_PREVIEW_ROWS))
            .put("obligations", JSONArray(obligationRows.take(MAX_PREVIEW_ROWS)))
            .put("selectedTests", JSONArray(selectedTestRows.take(MAX_PREVIEW_ROWS)))
            .put("supplementalTests", JSONArray(supplementalRows.take(MAX_PREVIEW_ROWS)))
            .put("selectedChecks", JSONArray(checkRows.take(MAX_PREVIEW_ROWS)))
            .put("externalChecks", JSONArray(externalChecks.sorted()))
    }

    private fun obligationJson(row: Obligation): JSONObject = JSONObject()
        .put("id", row.id)
        .put("kind", row.kind)
        .put("state", row.state)
        .put("subject", row.subject)
        .put("depth", row.depth)
        .put("evidence", JSONArray(row.evidence))
        .put("satisfiesBy", JSONArray(row.satisfiesBy))
        .put("reason", row.reason ?: JSONObject.NULL)

    private fun testJson(row: TestProof): JSONObject = JSONObject()
        .put("path", row.path)
        .put("evidence", JSONArray(row.evidence.sorted()))
        .put("canSatisfyAffectedTestObligation", "path-affinity" !in row.evidence)

    private fun objectArray(array: JSONArray?): List<JSONObject> =
        if (array == null) emptyList() else
            (0 until array.length()).mapNotNull { index -> array.optJSONObject(index) }

    private fun stringArray(array: JSONArray?): List<String> =
        if (array == null) emptyList() else
            (0 until array.length()).mapNotNull { index ->
                array.optString(index).takeIf { it.isNotBlank() }
            }

    private fun pathWithin(path: String, root: String): Boolean =
        path == root || path.startsWith(root + "/")

    private fun isTestPath(path: String): Boolean {
        val lower = path.lowercase()
        return lower.contains("/test/") ||
            lower.contains("/tests/") ||
            lower.contains("__tests__") ||
            lower.contains("test-") ||
            lower.contains("_test.") ||
            lower.contains(".test.") ||
            lower.contains(".spec.")
    }

    private fun isVerificationScriptPath(path: String): Boolean {
        if (isTestPath(path)) return true
        val lower = path.lowercase()
        return (lower.endsWith(".mjs") || lower.endsWith(".js")) &&
            (lower.contains("/scripts/validate-") || lower.contains("/scripts/verify-"))
    }
}
