package com.riftos.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Patch 9 candidate-bound verification planning for tests, security and dependencies.
 *
 * This owner plans and validates evidence only. It never executes arbitrary processes, installs
 * dependencies, publishes source or promotes trust.
 */
internal object RiftVerificationPlannerV1 {
    const val PLAN_SCHEMA = "rift.verification-plan/1"
    const val EVIDENCE_SCHEMA = "rift.verification-evidence/1"
    const val VERSION = 1

    private const val MAX_CHANGED_ROWS = 4_096
    private const val MAX_SECURITY_TARGETS = 2_048
    private const val MAX_DEPENDENCY_CHANGES = 2_048
    private const val MAX_BUILD_MANIFESTS = 512
    private const val MAX_TEST_TARGETS = 512
    private const val MAX_CHECKS_PER_SECTION = 256
    private const val MAX_COMMAND_CHARS = 1_000
    private const val MAX_PACKAGE_BYTES = 1024L * 1024L

    fun plan(
        projectRoot: File,
        projectWorkspacePath: String,
        impact: JSONObject,
        buildManifests: Set<String>
    ): JSONObject {
        val root = projectRoot.canonicalFile
        require(root.isDirectory) { "Verification planner project root is missing" }
        val projectPath = normalizeRelative(projectWorkspacePath)
        val candidate = impact.optJSONObject("candidate")
            ?: throw IllegalArgumentException("Verification planner requires candidate identity")
        val manifestSha = checkedSha(candidate.optString("manifestSha256"), "candidate manifest")
        val semanticSha = checkedSha(impact.optString("semanticImpactSha256"), "semantic impact")
        val issues = JSONArray()

        if (!impact.optBoolean("complete", false)) {
            issues.put(issue(
                "SEMANTIC_IMPACT_INCOMPLETE",
                "Project Intelligence semantic impact is incomplete: " +
                    (impact.optJSONArray("incompleteReasons") ?: JSONArray()).toString()
            ))
        }

        val changes = impact.optJSONArray("changes") ?: JSONArray()
        if (changes.length() > MAX_CHANGED_ROWS) {
            issues.put(issue(
                "VERIFICATION_CHANGE_BOUND",
                "Candidate exceeds " + MAX_CHANGED_ROWS + " changed rows."
            ))
        }

        val changeRows = linkedMapOf<String, JSONObject>()
        var sourceOrBuildChanged = false
        var testRelevantChanged = false
        var buildConfigChanged = false
        for (index in 0 until minOf(changes.length(), MAX_CHANGED_ROWS)) {
            val row = changes.optJSONObject(index) ?: continue
            val relative = projectRelative(row.optString("path"), projectPath) ?: continue
            changeRows[relative] = row
            when (row.optString("category")) {
                "source" -> {
                    sourceOrBuildChanged = true
                    testRelevantChanged = true
                }
                "build-config" -> {
                    sourceOrBuildChanged = true
                    testRelevantChanged = true
                    buildConfigChanged = true
                }
                "test" -> testRelevantChanged = true
            }
        }

        val apiChanged = jsonPathSet(
            impact.optJSONArray("apiSurfaceChangedPaths"),
            projectPath
        )

        val dependencyChanges = ArrayList<JSONObject>()
        val dependencySources = linkedSetOf<String>()
        val dependencyRows = impact.optJSONArray("directDependencies") ?: JSONArray()
        for (index in 0 until dependencyRows.length()) {
            val row = dependencyRows.optJSONObject(index) ?: continue
            val relation = row.optString("relation").trim().lowercase()
            if (relation !in setOf("added", "removed")) continue
            if (dependencyChanges.size >= MAX_DEPENDENCY_CHANGES) {
                issues.put(issue(
                    "DEPENDENCY_CHANGE_BOUND",
                    "Dependency delta exceeds " + MAX_DEPENDENCY_CHANGES + " rows."
                ))
                break
            }
            val source = projectRelative(row.optString("source"), projectPath) ?: continue
            val kind = bounded(row.optString("kind"), 120, "dependency kind")
            val specifier = bounded(row.optString("specifier"), 1_000, "dependency specifier")
            val target = if (row.isNull("target")) {
                null
            } else {
                projectRelative(row.optString("target"), projectPath)
                    ?: bounded(row.optString("target"), 2_000, "dependency target")
            }
            dependencySources += source
            dependencyChanges += JSONObject()
                .put("source", source)
                .put("relation", relation)
                .put("kind", kind)
                .put("specifier", specifier)
                .put("target", target ?: JSONObject.NULL)
        }

        val securityTargets = linkedMapOf<String, LinkedHashSet<String>>()
        fun securityReason(path: String, reason: String) {
            if (securityTargets.size >= MAX_SECURITY_TARGETS && path !in securityTargets) {
                issues.put(issue(
                    "SECURITY_TARGET_BOUND",
                    "Security target scope exceeds " + MAX_SECURITY_TARGETS + " paths."
                ))
                return
            }
            securityTargets.getOrPut(path) { linkedSetOf() } += reason
        }

        for ((path, row) in changeRows) {
            when (row.optString("category")) {
                "source" -> securityReason(path, "CHANGED_SOURCE")
                "build-config" -> securityReason(path, "CHANGED_BUILD_CONFIG")
            }
            if (path in apiChanged) securityReason(path, "API_SURFACE_CHANGED")
            val semantic = row.optJSONObject("semantic")
            val dependencyChanged =
                (semantic?.optJSONArray("addedDependencies")?.length() ?: 0) > 0 ||
                    (semantic?.optJSONArray("removedDependencies")?.length() ?: 0) > 0
            if (dependencyChanged) securityReason(path, "DEPENDENCY_SURFACE_CHANGED")
        }
        for (source in dependencySources) {
            securityReason(source, "DEPENDENCY_SURFACE_CHANGED")
        }

        val dependentRows = impact.optJSONArray("directDependents") ?: JSONArray()
        for (index in 0 until dependentRows.length()) {
            val row = dependentRows.optJSONObject(index) ?: continue
            val source = projectRelative(row.optString("source"), projectPath) ?: continue
            if (!isTestPath(source)) securityReason(source, "DIRECT_DEPENDENT")
        }

        val securityChecks = JSONArray()
        val securityTargetArray = JSONArray()
        for ((path, reasons) in securityTargets.toSortedMap()) {
            securityTargetArray.put(path)
            securityChecks.put(check(
                type = "security-target-review",
                target = path,
                detail = reasons.sorted().joinToString(","),
                reason = "Review security impact for " + reasons.sorted().joinToString(", ")
            ))
        }
        if (sourceOrBuildChanged) {
            securityChecks.put(check(
                type = "rift-audit",
                target = null,
                detail = projectPath,
                reason = "Run repository audit for source/build candidate."
            ))
            securityChecks.put(check(
                type = "rift-scan",
                target = null,
                detail = projectPath,
                reason = "Run repository scan for source/build candidate."
            ))
        }
        if (securityChecks.length() == 0) {
            securityChecks.put(check(
                type = "security-no-change",
                target = null,
                detail = manifestSha,
                reason = "No source/build security surfaces changed."
            ))
        }

        val dependencyTargetSet = linkedSetOf<String>()
        val manifestList = buildManifests.map(::normalizeRelative).distinct().sorted()
        if (manifestList.size > MAX_BUILD_MANIFESTS) {
            issues.put(issue(
                "BUILD_MANIFEST_BOUND",
                "Dependency/build manifest scope exceeds " + MAX_BUILD_MANIFESTS + " paths."
            ))
        }
        manifestList.take(MAX_BUILD_MANIFESTS).forEach { manifest ->
            val file = confined(root, manifest)
            if (!file.isFile) {
                issues.put(issue("BUILD_MANIFEST_MISSING", "Build manifest is missing: " + manifest))
            } else {
                dependencyTargetSet += manifest
            }
        }
        for ((path, row) in changeRows) {
            if (row.optString("category") == "build-config") dependencyTargetSet += path
        }
        dependencySources.forEach(dependencyTargetSet::add)

        val dependencyChecks = JSONArray()
        dependencyChecks.put(check(
            type = "supply-chain-review",
            target = null,
            detail = manifestSha,
            reason = "Verify lockfile/SBOM/license/provenance evidence."
        ))
        for (manifest in manifestList.take(MAX_BUILD_MANIFESTS)) {
            dependencyChecks.put(check(
                type = "build-manifest-review",
                target = manifest,
                detail = manifest,
                reason = "Review dependency/build manifest."
            ))
        }
        for ((path, row) in changeRows) {
            if (row.optString("category") != "build-config") continue
            dependencyChecks.put(check(
                type = "build-config-dependency-review",
                target = path,
                detail = row.optString("status"),
                reason = "Review changed build/dependency configuration."
            ))
        }
        val dependencyChangeArray = JSONArray()
        for (delta in dependencyChanges.sortedWith(compareBy<JSONObject>(
            { it.optString("source") },
            { it.optString("relation") },
            { it.optString("kind") },
            { it.optString("specifier") }
        ))) {
            dependencyChangeArray.put(delta)
            dependencyChecks.put(check(
                type = "dependency-change-review",
                target = delta.getString("source"),
                detail = listOf(
                    delta.getString("relation"),
                    delta.getString("kind"),
                    delta.getString("specifier"),
                    if (delta.isNull("target")) "" else delta.optString("target")
                ).joinToString("|"),
                reason = "Review exact added/removed dependency delta."
            ))
        }

        val testTargets = linkedSetOf<String>()
        val impactTests = impact.optJSONArray("tests") ?: JSONArray()
        for (index in 0 until impactTests.length()) {
            val relative = projectRelative(impactTests.optString(index), projectPath) ?: continue
            val row = changeRows[relative]
            if (row?.optString("status") == "deleted") continue
            val file = confined(root, relative)
            if (!file.isFile) {
                issues.put(issue("TEST_TARGET_MISSING", "Planned test target is missing: " + relative))
                continue
            }
            if (testTargets.size >= MAX_TEST_TARGETS) {
                issues.put(issue(
                    "TEST_TARGET_BOUND",
                    "Test target scope exceeds " + MAX_TEST_TARGETS + " paths."
                ))
                break
            }
            testTargets += relative
        }
        for ((path, row) in changeRows) {
            if (row.optString("category") != "test" || row.optString("status") == "deleted") continue
            val file = confined(root, path)
            if (file.isFile && testTargets.size < MAX_TEST_TARGETS) testTargets += path
        }

        val testChecks = JSONArray()
        val testTargetArray = JSONArray()
        for (test in testTargets.sorted()) {
            testTargetArray.put(test)
            val command = when {
                test.endsWith(".mjs", ignoreCase = true) ||
                    test.endsWith(".js", ignoreCase = true) -> "node " + test
                else -> null
            }
            testChecks.put(check(
                type = "test-target",
                target = test,
                detail = test,
                reason = "Run impacted test target.",
                command = command
            ))
        }

        val repositoryCheck = packageCheckCommand(root)
        if (testRelevantChanged && (testChecks.length() == 0 || buildConfigChanged)) {
            if (repositoryCheck != null) {
                if ("package.json" !in testTargets) testTargetArray.put("package.json")
                testChecks.put(check(
                    type = "repository-check",
                    target = "package.json",
                    detail = repositoryCheck,
                    reason = if (buildConfigChanged) {
                        "Run repository check because build configuration changed."
                    } else {
                        "No impact test was discovered; run bounded repository validation fallback."
                    },
                    command = repositoryCheck
                ))
            } else if (testChecks.length() == 0) {
                issues.put(issue(
                    "NO_TEST_OR_VALIDATION_TARGET",
                    "Source/build/test changed but no impacted test or repository validation command was derived."
                ))
            }
        }

        if (securityChecks.length() > MAX_CHECKS_PER_SECTION) {
            issues.put(issue("SECURITY_CHECK_BOUND", "Security check scope exceeds bound."))
        }
        if (dependencyChecks.length() > MAX_CHECKS_PER_SECTION) {
            issues.put(issue("DEPENDENCY_CHECK_BOUND", "Dependency check scope exceeds bound."))
        }
        if (testChecks.length() > MAX_CHECKS_PER_SECTION) {
            issues.put(issue("TEST_CHECK_BOUND", "Test check scope exceeds bound."))
        }

        val out = JSONObject()
            .put("schema", PLAN_SCHEMA)
            .put("version", VERSION)
            .put("projectWorkspacePath", projectPath)
            .put("candidateManifestSha256", manifestSha)
            .put("semanticImpactSha256", semanticSha)
            .put("security", JSONObject()
                .put("targets", securityTargetArray)
                .put("checks", securityChecks))
            .put("dependencies", JSONObject()
                .put("targets", JSONArray(dependencyTargetSet.sorted()))
                .put("dependencyChanges", dependencyChangeArray)
                .put("checks", dependencyChecks))
            .put("tests", JSONObject()
                .put("targets", testTargetArray)
                .put("checks", testChecks))
            .put("hardIssues", issues)
            .put("complete", issues.length() == 0)
        out.put("planSha256", RiftPatchManifestV1.sha256Canonical(out))
        return out
    }

    fun validateEvidence(plan: JSONObject, kind: String, payload: JSONObject): JSONObject {
        require(plan.optString("schema") == PLAN_SCHEMA) {
            "Verification plan schema mismatch"
        }
        require(kind in setOf("security", "dependencies", "tests")) {
            "Unsupported verification evidence kind: " + kind
        }
        val evidence = payload.optJSONObject("verificationPlan")
            ?: throw IllegalArgumentException(
                kind + " evidence requires verificationPlan from lifecycle verification-plan"
            )
        require(evidence.optString("schema") == EVIDENCE_SCHEMA) {
            "verificationPlan schema must be " + EVIDENCE_SCHEMA
        }
        val expectedPlanSha = checkedSha(plan.optString("planSha256"), "verification plan")
        require(evidence.optString("planSha256").trim().lowercase() == expectedPlanSha) {
            "verificationPlan planSha256 does not match the current candidate plan"
        }
        require(evidence.optString("kind").trim().lowercase() == kind) {
            "verificationPlan kind does not match evidence kind"
        }

        val section = plan.getJSONObject(kind)
        val requiredChecks = section.getJSONArray("checks")
        val requiredCheckIds = linkedSetOf<String>()
        for (index in 0 until requiredChecks.length()) {
            requiredCheckIds += requiredChecks.getJSONObject(index).getString("id")
        }
        val passedCheckIds = linkedSetOf<String>()
        val checks = payload.optJSONArray("checks") ?: JSONArray()
        for (index in 0 until checks.length()) {
            val row = checks.optJSONObject(index) ?: continue
            if (row.optString("status").trim().uppercase() == "PASS") {
                passedCheckIds += row.optString("id").trim()
            }
        }
        val missingChecks = requiredCheckIds.filter { it !in passedCheckIds }

        val requiredTargets = jsonStrings(section.optJSONArray("targets")).toSet()
        val suppliedTargets = jsonStrings(payload.optJSONArray("targets"))
            .map(::normalizeRelative)
            .toSet()
        val missingTargets = requiredTargets.filter { it !in suppliedTargets }

        val planIssues = plan.optJSONArray("hardIssues") ?: JSONArray()
        val complete =
            plan.optBoolean("complete", false) &&
                planIssues.length() == 0 &&
                missingChecks.isEmpty() &&
                missingTargets.isEmpty()

        val normalized = JSONObject()
            .put("schema", EVIDENCE_SCHEMA)
            .put("version", VERSION)
            .put("kind", kind)
            .put("planSha256", expectedPlanSha)
            .put("candidateManifestSha256", plan.optString("candidateManifestSha256"))
            .put("semanticImpactSha256", plan.optString("semanticImpactSha256"))
            .put("requiredCheckIds", JSONArray(requiredCheckIds.sorted()))
            .put("missingCheckIds", JSONArray(missingChecks.sorted()))
            .put("requiredTargets", JSONArray(requiredTargets.sorted()))
            .put("missingTargets", JSONArray(missingTargets.sorted()))
            .put("planHardIssues", JSONArray(planIssues.toString()))
            .put("complete", complete)
        normalized.put("verificationEvidenceSha256", RiftPatchManifestV1.sha256Canonical(normalized))
        return normalized
    }

    private fun packageCheckCommand(root: File): String? {
        val file = confined(root, "package.json")
        if (!file.isFile || file.length() > MAX_PACKAGE_BYTES) return null
        return runCatching {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val script = json.optJSONObject("scripts")?.optString("check")?.trim().orEmpty()
            if (script.isBlank()) null else "npm run check"
        }.getOrNull()
    }

    private fun check(
        type: String,
        target: String?,
        detail: String,
        reason: String,
        command: String? = null
    ): JSONObject {
        val seed = JSONObject()
            .put("type", type)
            .put("target", target ?: JSONObject.NULL)
            .put("detail", detail)
        val id = type.take(48) + "-" + RiftPatchManifestV1.sha256Canonical(seed).take(16)
        val out = JSONObject()
            .put("id", id)
            .put("type", type)
            .put("target", target ?: JSONObject.NULL)
            .put("reason", bounded(reason, 2_000, "verification reason"))
        if (command != null) {
            out.put("command", bounded(command, MAX_COMMAND_CHARS, "verification command"))
        }
        return out
    }

    private fun jsonPathSet(array: JSONArray?, projectPath: String): Set<String> {
        if (array == null) return emptySet()
        val out = linkedSetOf<String>()
        for (index in 0 until array.length()) {
            projectRelative(array.optString(index), projectPath)?.let(out::add)
        }
        return out
    }

    private fun jsonStrings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optString(index).trim().takeIf { it.isNotBlank() }
        }
    }

    private fun projectRelative(raw: String, projectPath: String): String? {
        val clean = raw.trim().replace('\\', '/').trim('/')
        if (clean == projectPath) return ""
        if (!clean.startsWith(projectPath + "/")) return null
        return normalizeRelative(clean.removePrefix(projectPath + "/"))
    }

    private fun isTestPath(path: String): Boolean {
        val lower = path.lowercase()
        val name = lower.substringAfterLast('/')
        return lower.contains("/test/") ||
            lower.contains("/tests/") ||
            lower.contains("__tests__") ||
            name.startsWith("test-") ||
            name.endsWith(".test.js") ||
            name.endsWith(".test.mjs") ||
            name.endsWith(".spec.js") ||
            name.endsWith(".spec.mjs") ||
            name.endsWith("test.kt") ||
            name.endsWith("tests.kt")
    }

    private fun confined(root: File, relative: String): File {
        val safe = normalizeRelative(relative)
        val file = File(root, safe).canonicalFile
        require(file.path.startsWith(root.path + File.separator)) {
            "Verification planner path escaped project root"
        }
        return file
    }

    private fun normalizeRelative(raw: String): String {
        val clean = raw.trim().replace('\\', '/').trim('/')
        require(clean.isNotBlank()) { "Verification planner path is required" }
        val parts = clean.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." || it.contains('\u0000') }) {
            "Unsafe verification planner path"
        }
        return parts.joinToString("/")
    }

    private fun checkedSha(raw: String, label: String): String {
        val sha = raw.trim().lowercase()
        require(sha.matches(Regex("^[0-9a-f]{64}$"))) {
            "Invalid " + label + " SHA-256"
        }
        return sha
    }

    private fun bounded(raw: String, maxChars: Int, label: String): String {
        val value = raw.trim().replace("\u0000", "")
        require(value.length <= maxChars) { label + " exceeds " + maxChars + " characters" }
        return value
    }

    private fun issue(code: String, message: String): JSONObject =
        JSONObject().put("code", code).put("message", message)
}
