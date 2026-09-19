package com.riftos.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Patch 8 candidate-specific documentation/project-state parity.
 *
 * Deterministic repository facts are enforced locally. Arbitrary prose truth remains a structured
 * claim for the independent evaluator; this class never promotes trust or mutates the project.
 */
internal object RiftDocumentationParityV1 {
    const val PLAN_SCHEMA = "rift.documentation-parity-plan/1"
    const val EVIDENCE_SCHEMA = "rift.documentation-parity/1"
    const val VERSION = 1

    private const val MAX_LEDGER_BYTES = 1024L * 1024L
    private const val MAX_LEDGER_ROWS = 5_000
    private const val MAX_OWNER_DOCS = 32
    private const val MAX_CHANGED_ROWS = 4_096
    private const val MAX_SOURCE_REVIEWS = 4_096
    private const val MAX_GOVERNANCE_REVIEWS = 16
    private const val MAX_REASON_CHARS = 2_000

    private val MAINTAINED_CATEGORIES = setOf("source", "build-config", "test")
    private val DISPOSITIONS = setOf("UPDATED", "UNCHANGED_VALID")

    fun plan(
        projectRoot: File,
        projectWorkspacePath: String,
        impact: JSONObject
    ): JSONObject {
        val root = projectRoot.canonicalFile
        require(root.isDirectory) { "Documentation parity project root is missing" }
        val projectPath = normalizeRelative(projectWorkspacePath)
        val candidate = impact.optJSONObject("candidate")
            ?: throw IllegalArgumentException("Documentation parity requires candidate identity")
        val manifestSha = checkedSha(candidate.optString("manifestSha256"), "candidate manifest")
        val semanticSha = checkedSha(impact.optString("semanticImpactSha256"), "semantic impact")

        val changedDocs = jsonPathSet(impact.optJSONArray("changedDocumentation"), projectPath)
        val apiSurfaceChanged = jsonPathSet(impact.optJSONArray("apiSurfaceChangedPaths"), projectPath)
        val issues = JSONArray()

        val ownershipFile = confined(root, "docs/SOURCE_OWNERSHIP.md")
        val ledger = parseOwnershipLedger(root, ownershipFile, issues)

        val changes = impact.optJSONArray("changes") ?: JSONArray()
        require(changes.length() <= MAX_CHANGED_ROWS) {
            "Documentation parity candidate exceeds " + MAX_CHANGED_ROWS + " changed rows"
        }

        val maintained = JSONArray()
        var ownershipUpdateRequired = false
        var maintainedCount = 0
        for (index in 0 until changes.length()) {
            val change = changes.optJSONObject(index) ?: continue
            val category = change.optString("category").trim()
            if (category !in MAINTAINED_CATEGORIES) continue
            val rawPath = change.optString("path")
            val relative = projectRelative(rawPath, projectPath) ?: continue
            maintainedCount++
            val status = change.optString("status").trim().ifBlank { "modified" }
            val deleted = status == "deleted"
            val added = status == "added"
            if (added || deleted) ownershipUpdateRequired = true

            val ownership = ledger.optJSONObject(relative)
            val ownerDocs = linkedSetOf<String>()
            if (deleted) {
                if (ownership != null) {
                    issues.put(issue(
                        "DELETED_SOURCE_STILL_OWNED",
                        relative,
                        "Deleted maintained path still has a current ownership entry."
                    ))
                }
                nearestReadme(root, relative)?.let(ownerDocs::add)
            } else {
                if (ownership == null) {
                    issues.put(issue(
                        "OWNERSHIP_ENTRY_MISSING",
                        relative,
                        "Changed maintained path has no current SOURCE_OWNERSHIP entry."
                    ))
                } else {
                    jsonStrings(ownership.optJSONArray("owners")).forEach(ownerDocs::add)
                }
            }

            val missingOwnerDocs = JSONArray()
            for (doc in ownerDocs) {
                val file = confined(root, doc)
                if (!file.isFile) {
                    missingOwnerDocs.put(doc)
                    issues.put(issue(
                        "OWNER_DOCUMENT_MISSING",
                        relative,
                        "Owner document is missing: " + doc
                    ))
                }
            }

            val semantic = change.optJSONObject("semantic")
            val dependencyChanged =
                (semantic?.optJSONArray("addedDependencies")?.length() ?: 0) > 0 ||
                    (semantic?.optJSONArray("removedDependencies")?.length() ?: 0) > 0
            val apiChanged = relative in apiSurfaceChanged ||
                (semantic?.optBoolean("apiSurfaceChanged", false) == true)

            val updateRequired =
                added ||
                    status == "type-changed" ||
                    category == "build-config" ||
                    (category == "source" && (apiChanged || dependencyChanged)) ||
                    (category == "test" && (added || deleted)) ||
                    (deleted && ownerDocs.isNotEmpty())

            val substantiveOwners = ownerDocs
                .filterNot(::isBookkeepingDocument)
                .sorted()
            val changedOwners = ownerDocs.filter { it in changedDocs }.sorted()
            val changedSubstantiveOwners =
                substantiveOwners.filter { it in changedDocs }.sorted()
            if (!deleted && ownerDocs.isNotEmpty() && substantiveOwners.isEmpty()) {
                issues.put(issue(
                    "SUBSTANTIVE_OWNER_DOCUMENT_MISSING",
                    relative,
                    "Maintained path has only bookkeeping ownership; a substantive README/spec owner is required."
                ))
            }
            if (updateRequired && changedSubstantiveOwners.isEmpty()) {
                issues.put(issue(
                    "OWNER_DOCUMENT_UPDATE_MISSING",
                    relative,
                    "Documentation-significant maintained change has no changed substantive owner document."
                ))
            }

            maintained.put(JSONObject()
                .put("path", relative)
                .put("status", status)
                .put("category", category)
                .put("apiSurfaceChanged", apiChanged)
                .put("dependencySurfaceChanged", dependencyChanged)
                .put("ownerUpdateRequired", updateRequired)
                .put("ownerDocs", JSONArray(ownerDocs.sorted()))
                .put("substantiveOwnerDocs", JSONArray(substantiveOwners))
                .put("changedOwnerDocs", JSONArray(changedOwners))
                .put("changedSubstantiveOwnerDocs", JSONArray(changedSubstantiveOwners))
                .put("missingOwnerDocs", missingOwnerDocs))
        }

        if (maintainedCount > 0 && !ownershipFile.isFile) {
            issues.put(issue(
                "OWNERSHIP_LEDGER_MISSING",
                "docs/SOURCE_OWNERSHIP.md",
                "Maintained candidate changes require a source-ownership ledger."
            ))
        }

        val ownershipChanged = "docs/SOURCE_OWNERSHIP.md" in changedDocs
        if (ownershipUpdateRequired && !ownershipChanged) {
            issues.put(issue(
                "OWNERSHIP_LEDGER_UPDATE_MISSING",
                "docs/SOURCE_OWNERSHIP.md",
                "Added/deleted maintained paths require an ownership-ledger update."
            ))
        }

        val patchHistory = confined(root, "docs/PATCH_HISTORY.md")
        val patchHistoryRequired = maintainedCount > 0 && patchHistory.isFile
        if (patchHistoryRequired && "docs/PATCH_HISTORY.md" !in changedDocs) {
            issues.put(issue(
                "PATCH_HISTORY_UPDATE_MISSING",
                "docs/PATCH_HISTORY.md",
                "Maintained source/build/test changes require a patch-history update."
            ))
        }

        val governance = JSONArray()
        governanceSurface(root, changedDocs, "root-readme", "README.md", false)?.let(governance::put)
        governanceSurface(root, changedDocs, "roadmap", "ROADMAP.md", false)?.let(governance::put)
        governanceSurface(root, changedDocs, "project-status", "docs/PROJECT_STATUS.md", false)?.let(governance::put)
        governanceSurface(
            root,
            changedDocs,
            "source-ownership",
            "docs/SOURCE_OWNERSHIP.md",
            ownershipUpdateRequired
        )?.let(governance::put)
        governanceSurface(
            root,
            changedDocs,
            "patch-history",
            "docs/PATCH_HISTORY.md",
            patchHistoryRequired
        )?.let(governance::put)

        val out = JSONObject()
            .put("schema", PLAN_SCHEMA)
            .put("version", VERSION)
            .put("projectWorkspacePath", projectPath)
            .put("candidateManifestSha256", manifestSha)
            .put("semanticImpactSha256", semanticSha)
            .put("ownershipLedgerPresent", ownershipFile.isFile)
            .put("ownershipRows", ledger.length())
            .put("changedDocumentation", JSONArray(changedDocs.sorted()))
            .put("maintainedChanges", maintained)
            .put("governance", governance)
            .put("hardIssues", issues)
            .put("complete", issues.length() == 0)
        out.put("planSha256", RiftPatchManifestV1.sha256Canonical(out))
        return out
    }

    fun validateEvidence(plan: JSONObject, raw: JSONObject): JSONObject {
        require(plan.optString("schema") == PLAN_SCHEMA) {
            "Documentation parity plan schema mismatch"
        }
        require(raw.optString("schema") == EVIDENCE_SCHEMA) {
            "documentationParity schema must be " + EVIDENCE_SCHEMA
        }
        val expectedPlanSha = checkedSha(plan.optString("planSha256"), "documentation parity plan")
        require(raw.optString("planSha256").trim().lowercase() == expectedPlanSha) {
            "documentationParity planSha256 does not match the current candidate plan"
        }

        val issues = JSONArray()
        val sourceReviewsRaw = raw.optJSONArray("sourceReviews") ?: JSONArray()
        require(sourceReviewsRaw.length() <= MAX_SOURCE_REVIEWS) {
            "Documentation parity exceeds source-review bound"
        }
        val sourceReviews = linkedMapOf<String, JSONObject>()
        for (index in 0 until sourceReviewsRaw.length()) {
            val review = sourceReviewsRaw.optJSONObject(index)
                ?: throw IllegalArgumentException("Documentation source review must be an object")
            val path = normalizeRelative(review.optString("sourcePath"))
            require(sourceReviews.put(path, review) == null) {
                "Duplicate documentation source review: " + path
            }
        }

        val normalizedSources = JSONArray()
        val maintained = plan.optJSONArray("maintainedChanges") ?: JSONArray()
        for (index in 0 until maintained.length()) {
            val item = maintained.getJSONObject(index)
            val path = item.getString("path")
            val expectedOwners = jsonStrings(item.optJSONArray("ownerDocs")).sorted()
            val review = sourceReviews.remove(path)
            if (review == null) {
                issues.put(issue(
                    "SOURCE_REVIEW_MISSING",
                    path,
                    "Documentation parity review is missing for changed maintained path."
                ))
                continue
            }

            val suppliedOwners = jsonStrings(review.optJSONArray("ownerDocs"))
                .map(::normalizeRelative)
                .sorted()
            if (suppliedOwners != expectedOwners) {
                issues.put(issue(
                    "OWNER_DOC_SET_MISMATCH",
                    path,
                    "Source review ownerDocs do not match the deterministic ownership plan."
                ))
            }

            val disposition = review.optString("disposition").trim().uppercase()
            if (disposition !in DISPOSITIONS) {
                issues.put(issue("SOURCE_REVIEW_DISPOSITION_INVALID", path, "Unsupported source review disposition."))
            }
            val reason = review.optString("reason").trim()
            if (reason.length > MAX_REASON_CHARS) {
                issues.put(issue("SOURCE_REVIEW_REASON_OVERSIZED", path, "Source review reason exceeds bound."))
            }
            if (disposition == "UNCHANGED_VALID" && reason.isBlank()) {
                issues.put(issue(
                    "SOURCE_REVIEW_REASON_MISSING",
                    path,
                    "UNCHANGED_VALID source review requires a reason."
                ))
            }
            if (item.optBoolean("ownerUpdateRequired", false) && disposition != "UPDATED") {
                issues.put(issue(
                    "MANDATORY_OWNER_UPDATE_NOT_ACKNOWLEDGED",
                    path,
                    "Deterministic owner update is required; source review must be UPDATED."
                ))
            }
            if (
                disposition == "UPDATED" &&
                item.optBoolean("ownerUpdateRequired", false) &&
                item.optJSONArray("changedSubstantiveOwnerDocs")?.length() == 0
            ) {
                issues.put(issue(
                    "UPDATED_OWNER_DOC_NOT_CHANGED",
                    path,
                    "Source review claims UPDATED but no substantive deterministic owner doc changed."
                ))
            }

            normalizedSources.put(JSONObject()
                .put("sourcePath", path)
                .put("ownerDocs", JSONArray(expectedOwners))
                .put("disposition", disposition)
                .put("reason", reason))
        }
        for (extra in sourceReviews.keys.sorted()) {
            issues.put(issue(
                "UNKNOWN_SOURCE_REVIEW",
                extra,
                "Documentation parity contains a source review outside the candidate plan."
            ))
        }

        val governanceRaw = raw.optJSONArray("governanceReviews") ?: JSONArray()
        require(governanceRaw.length() <= MAX_GOVERNANCE_REVIEWS) {
            "Documentation parity exceeds governance-review bound"
        }
        val governanceReviews = linkedMapOf<String, JSONObject>()
        for (index in 0 until governanceRaw.length()) {
            val review = governanceRaw.optJSONObject(index)
                ?: throw IllegalArgumentException("Governance review must be an object")
            val id = review.optString("id").trim().lowercase()
            require(id.isNotBlank()) { "Governance review id is required" }
            require(governanceReviews.put(id, review) == null) {
                "Duplicate governance review: " + id
            }
        }

        val normalizedGovernance = JSONArray()
        val governance = plan.optJSONArray("governance") ?: JSONArray()
        for (index in 0 until governance.length()) {
            val surface = governance.getJSONObject(index)
            val id = surface.getString("id")
            val path = surface.getString("path")
            val review = governanceReviews.remove(id)
            if (review == null) {
                issues.put(issue(
                    "GOVERNANCE_REVIEW_MISSING",
                    path,
                    "Governance review is missing for " + id + "."
                ))
                continue
            }

            if (normalizeRelative(review.optString("path")) != path) {
                issues.put(issue(
                    "GOVERNANCE_PATH_MISMATCH",
                    path,
                    "Governance review path does not match the deterministic plan."
                ))
            }
            val disposition = review.optString("disposition").trim().uppercase()
            if (disposition !in DISPOSITIONS) {
                issues.put(issue(
                    "GOVERNANCE_DISPOSITION_INVALID",
                    path,
                    "Unsupported governance review disposition."
                ))
            }
            val reason = review.optString("reason").trim()
            if (reason.length > MAX_REASON_CHARS) {
                issues.put(issue(
                    "GOVERNANCE_REASON_OVERSIZED",
                    path,
                    "Governance review reason exceeds bound."
                ))
            }
            if (disposition == "UNCHANGED_VALID" && reason.isBlank()) {
                issues.put(issue(
                    "GOVERNANCE_REASON_MISSING",
                    path,
                    "UNCHANGED_VALID governance review requires a reason."
                ))
            }
            if (surface.optBoolean("changeRequired", false) && disposition != "UPDATED") {
                issues.put(issue(
                    "GOVERNANCE_UPDATE_REQUIRED",
                    path,
                    "Deterministic governance update is required."
                ))
            }
            if (disposition == "UPDATED" && !surface.optBoolean("changed", false)) {
                issues.put(issue(
                    "GOVERNANCE_UPDATED_NOT_CHANGED",
                    path,
                    "Governance review claims UPDATED but file is unchanged."
                ))
            }

            normalizedGovernance.put(JSONObject()
                .put("id", id)
                .put("path", path)
                .put("disposition", disposition)
                .put("reason", reason))
        }
        for (extra in governanceReviews.keys.sorted()) {
            issues.put(issue(
                "UNKNOWN_GOVERNANCE_REVIEW",
                extra,
                "Documentation parity contains an unknown governance review."
            ))
        }

        val planIssues = plan.optJSONArray("hardIssues") ?: JSONArray()
        val complete = plan.optBoolean("complete", false) &&
            planIssues.length() == 0 &&
            issues.length() == 0
        val normalized = JSONObject()
            .put("schema", EVIDENCE_SCHEMA)
            .put("version", VERSION)
            .put("planSha256", expectedPlanSha)
            .put("candidateManifestSha256", plan.optString("candidateManifestSha256"))
            .put("semanticImpactSha256", plan.optString("semanticImpactSha256"))
            .put("sourceReviews", normalizedSources)
            .put("governanceReviews", normalizedGovernance)
            .put("planHardIssues", JSONArray(planIssues.toString()))
            .put("issues", issues)
            .put("complete", complete)
        normalized.put("paritySha256", RiftPatchManifestV1.sha256Canonical(normalized))
        return normalized
    }

    private fun parseOwnershipLedger(
        root: File,
        ledgerFile: File,
        issues: JSONArray
    ): JSONObject {
        val out = JSONObject()
        if (!ledgerFile.isFile) return out
        require(ledgerFile.length() <= MAX_LEDGER_BYTES) {
            "SOURCE_OWNERSHIP exceeds " + MAX_LEDGER_BYTES + " bytes"
        }
        val lines = ledgerFile.readLines(Charsets.UTF_8)
        var rows = 0
        val tick = 96.toChar()
        for (line in lines) {
            val trimmed = line.trimStart()
            if (!trimmed.startsWith("| " + tick)) continue
            val first = trimmed.indexOf(tick)
            val second = trimmed.indexOf(tick, first + 1)
            if (first < 0 || second <= first) continue
            val sourceResult = runCatching {
                normalizeRelative(trimmed.substring(first + 1, second))
            }
            if (sourceResult.isFailure) {
                issues.put(issue(
                    "OWNERSHIP_SOURCE_PATH_INVALID",
                    "docs/SOURCE_OWNERSHIP.md",
                    "Ownership ledger contains an unsafe source path."
                ))
                continue
            }
            val source = sourceResult.getOrThrow()
            rows++
            require(rows <= MAX_LEDGER_ROWS) {
                "SOURCE_OWNERSHIP exceeds " + MAX_LEDGER_ROWS + " rows"
            }
            if (out.has(source)) {
                issues.put(issue(
                    "OWNERSHIP_ENTRY_DUPLICATE",
                    source,
                    "Ownership ledger contains duplicate source rows."
                ))
                continue
            }

            val ownerDocs = linkedSetOf<String>()
            val tail = trimmed.substring(second + 1)
            val tokens = backtickTokens(tail)
            for (token in tokens) {
                if (
                    token.equals("README.md", ignoreCase = true) ||
                    token.lowercase().endsWith(".md")
                ) {
                    val ownerResult = runCatching { normalizeRelative(token) }
                    if (ownerResult.isFailure) {
                        issues.put(issue(
                            "OWNER_DOCUMENT_PATH_INVALID",
                            source,
                            "Ownership ledger contains an unsafe owner-doc path."
                        ))
                        continue
                    }
                    ownerDocs += ownerResult.getOrThrow()
                    require(ownerDocs.size <= MAX_OWNER_DOCS) {
                        "Ownership row exceeds owner-doc bound: " + source
                    }
                }
            }

            out.put(source, JSONObject()
                .put("source", source)
                .put("owners", JSONArray(ownerDocs.sorted())))
        }
        return out
    }

    private fun isBookkeepingDocument(path: String): Boolean =
        path == "docs/PATCH_HISTORY.md" ||
            path == "docs/SOURCE_OWNERSHIP.md"

    private fun governanceSurface(
        root: File,
        changedDocs: Set<String>,
        id: String,
        path: String,
        changeRequired: Boolean
    ): JSONObject? {
        val file = confined(root, path)
        if (!file.isFile) return null
        return JSONObject()
            .put("id", id)
            .put("path", path)
            .put("changed", path in changedDocs)
            .put("changeRequired", changeRequired)
    }

    private fun nearestReadme(root: File, relative: String): String? {
        var parent = relative.substringBeforeLast('/', "")
        while (true) {
            val candidate = if (parent.isBlank()) "README.md" else parent + "/README.md"
            if (confined(root, candidate).isFile) return candidate
            if (parent.isBlank()) break
            parent = parent.substringBeforeLast('/', "")
        }
        return null
    }

    private fun projectRelative(raw: String, projectPath: String): String? {
        val clean = raw.trim().replace('\\', '/').trim('/')
        if (clean == projectPath) return ""
        if (!clean.startsWith(projectPath + "/")) return null
        return normalizeRelative(clean.removePrefix(projectPath + "/"))
    }

    private fun jsonPathSet(array: JSONArray?, projectPath: String): Set<String> {
        if (array == null) return emptySet()
        val out = linkedSetOf<String>()
        for (index in 0 until array.length()) {
            projectRelative(array.optString(index), projectPath)?.takeIf { it.isNotBlank() }?.let(out::add)
        }
        return out
    }

    private fun jsonStrings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optString(index).trim().takeIf { it.isNotBlank() }
        }
    }

    private fun backtickTokens(text: String): List<String> {
        val tick = 96.toChar()
        val out = ArrayList<String>()
        var cursor = 0
        while (cursor < text.length) {
            val start = text.indexOf(tick, cursor)
            if (start < 0) break
            val end = text.indexOf(tick, start + 1)
            if (end < 0) break
            out += text.substring(start + 1, end)
            cursor = end + 1
        }
        return out
    }

    private fun confined(root: File, relative: String): File {
        val safe = normalizeRelative(relative)
        val file = File(root, safe).canonicalFile
        require(file.path.startsWith(root.path + File.separator)) {
            "Documentation parity path escaped project root"
        }
        return file
    }

    private fun normalizeRelative(raw: String): String {
        val clean = raw.trim().replace('\\', '/').trim('/')
        require(clean.isNotBlank()) { "Documentation parity path is required" }
        val parts = clean.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." || it.contains('\u0000') }) {
            "Unsafe documentation parity path"
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

    private fun issue(code: String, path: String, message: String): JSONObject =
        JSONObject()
            .put("code", code)
            .put("path", path)
            .put("message", message)
}
