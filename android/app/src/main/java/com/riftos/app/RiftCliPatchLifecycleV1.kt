package com.riftos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * OBSERVE-only CLI -> AI patch lifecycle.
 *
 * This subsystem orchestrates evidence. It does not widen MCP/Local Agent authority, does not
 * publish source, does not promote a trusted checkpoint, and cannot turn an AI verdict into trust.
 */
internal object RiftCliPatchLifecycleV1 {
    const val SCHEMA = "rift.cli-patch-lifecycle/1"
    const val SESSION_SCHEMA = "rift.cli-patch-session/1"
    const val EVIDENCE_SCHEMA = "rift.cli-evidence/1"
    const val EVALUATION_SCHEMA = "rift.cli-ai-evaluation/1"
    const val VERSION = 1

    private const val MAX_SESSIONS = 64
    private const val MAX_GOAL_CHARS = 8_000
    private const val MAX_EVIDENCE_BYTES = 512 * 1024
    private const val MAX_EVALUATION_PACKET_BYTES = 700 * 1024
    private const val MAX_EVALUATION_DEFECTS = 256
    private const val MAX_EVIDENCE_RECORDS = 96
    private const val MAX_CHECKS = 256
    private const val MAX_EVIDENCE_TARGETS = 2_000
    private const val MAX_SUMMARY_CHARS = 8_000
    private const val MAX_ACTOR_CHARS = 160
    private const val MAX_INVENTORY_FILES = 50_000
    private const val MAX_INVENTORY_BYTES = 512L * 1024L * 1024L
    private const val MAX_GOVERNANCE_DOCS = 400
    private const val CANDIDATE_TIMEOUT_MS = 30_000L
    private val PROCESS_EPOCH = "process-" + UUID.randomUUID().toString()

    private val VERIFICATION_EVIDENCE = setOf("security", "dependencies", "tests")

    private val POST_PATCH_EVIDENCE = setOf(
        "documentation",
        "code-audit",
        "security",
        "dependencies",
        "tests",
        "build",
        "e2e",
        "rollback"
    )
    private val POST_EVIDENCE_ORDER = listOf(
        "documentation",
        "code-audit",
        "security",
        "dependencies",
        "tests",
        "build",
        "e2e",
        "rollback"
    )
    private val ALL_EVIDENCE = setOf("understanding", "research", "design") + POST_PATCH_EVIDENCE

    data class CommandResult(val output: String, val result: JSONObject)

    private data class ProjectTarget(
        val displayPath: String,
        val workspacePath: String,
        val file: File
    )

    fun execute(context: Context, args: List<String>): CommandResult {
        val sub = args.firstOrNull()?.trim()?.lowercase().orEmpty().ifBlank { "help" }
        val tail = args.drop(1)
        val value = when (sub) {
            "help" -> help()
            "contract" -> contract()
            "begin" -> {
                require(tail.size >= 2) { "usage: rift-cli lifecycle begin <project> <goal...>" }
                begin(context, tail.first(), tail.drop(1).joinToString(" "), sync = false)
            }
            "begin-sync" -> {
                require(tail.size >= 2) { "usage: rift-cli lifecycle begin-sync <project> <goal...>" }
                begin(context, tail.first(), tail.drop(1).joinToString(" "), sync = true)
            }
            "status" -> {
                require(tail.size == 1) { "usage: rift-cli lifecycle status <sessionId>" }
                status(context, tail.single())
            }
            "request" -> {
                require(tail.size == 1) { "usage: rift-cli lifecycle request <sessionId>" }
                requestPacket(context, tail.single())
            }
            "import" -> {
                require(tail.size == 3) {
                    "usage: rift-cli lifecycle import <sessionId> <understanding|research|design|documentation|code-audit|security|dependencies|tests|build|e2e|rollback> <D:/Documents|D:/Temp json>"
                }
                importEvidence(context, tail[0], tail[1], tail[2])
            }
            "documentation-plan" -> {
                require(tail.size == 1) { "usage: rift-cli lifecycle documentation-plan <sessionId>" }
                documentationPlan(context, tail.single())
            }
            "verification-plan" -> {
                require(tail.size == 1) { "usage: rift-cli lifecycle verification-plan <sessionId>" }
                verificationPlan(context, tail.single())
            }
            "evaluation" -> {
                require(tail.size == 1) { "usage: rift-cli lifecycle evaluation <sessionId>" }
                evaluationPacket(context, tail.single())
            }
            "verify" -> {
                require(tail.size == 2) { "usage: rift-cli lifecycle verify <sessionId> <D:/Documents|D:/Temp evaluation.json>" }
                verifyEvaluation(context, tail[0], tail[1])
            }
            "clear" -> {
                require(tail.size == 1) { "usage: rift-cli lifecycle clear <sessionId>" }
                clear(context, tail.single())
            }
            else -> throw IllegalArgumentException("unknown lifecycle command: " + sub)
        }
        return CommandResult(value.toString(2), value)
    }

    fun contract(): JSONObject {
        val stages = JSONArray()
        stage(stages, 1, "ACQUIRE", "CLI", listOf(
            "Use native Git to synchronize the full repository before analysis.",
            "Require a clean local tree before synchronized acquisition.",
            "Capture repository, branch, immutable HEAD, Project Export snapshot and file-layout inventory."
        ))
        stage(stages, 2, "UNDERSTAND", "AI", listOf(
            "Map the full file tree and ownership boundaries before selecting files.",
            "Trace imports, exports, callers, dependencies, tests, CI/build files, generated/vendor boundaries and public surfaces.",
            "Read README/docs/TODO/task/roadmap/patch-history/source-ownership surfaces before patching."
        ))
        stage(stages, 3, "RESEARCH", "AI + independent verification", listOf(
            "Research external APIs/specifications/platform behavior before relying on assumptions.",
            "Record source URI, publisher, retrieval time, supported claim and version/date.",
            "Critical claims require an authoritative source; collection alone is not trust."
        ))
        stage(stages, 4, "DOCUMENT_INTENT", "AI", listOf(
            "Document exact problem, affected systems, implementation plan, tests, risks, compatibility and rollback.",
            "Update design/README/TODO/roadmap surfaces intended to describe the coming change before code mutation."
        ))
        stage(stages, 5, "PATCH", "AI via existing tools", listOf(
            "Apply guarded, transactional, narrow-owner patches only.",
            "Do not widen authority, bypass staging, or silently edit generated/vendor output."
        ))
        stage(stages, 6, "DOCUMENT_AUDIT", "CLI/AI independent pass", listOf(
            "Re-read source then verify README/docs/TODO/roadmap/project status/source ownership/patch history against actual bytes.",
            "Patch notes must state what/where/why/how/effects/compatibility/tests/risks/rollback."
        ))
        stage(stages, 7, "CODE_AUDIT", "CLI/AI independent pass", listOf(
            "Re-audit changed code and affected callers/references/imports end-to-end.",
            "Check duplicate functionality, dead paths, error handling, bounds, concurrency, security and compatibility."
        ))
        stage(stages, 8, "SUPPLY_CHAIN_SECURITY", "CLI/AI", listOf(
            "Review dependency/lockfile changes, source provenance, licenses, SBOM/SCA evidence when applicable.",
            "Check secrets, permissions, capability boundaries and generated artifacts."
        ))
        stage(stages, 9, "TEST_BUILD", "CLI/Builder", listOf(
            "Run impact-derived focused tests first, then required project/build/package validation.",
            "Capture commands, results, environment identity and artifact digests rather than a bare green/red statement."
        ))
        stage(stages, 10, "END_TO_END_VERIFY", "CLI/AI independent pass", listOf(
            "Re-evaluate the original request against the final tree.",
            "Verify affected systems, docs, tests, runtime paths and negative cases with no unexplained files."
        ))
        stage(stages, 11, "FREEZE", "CLI", listOf(
            "Freeze the exact Patch Manifest V1 candidate and semantic-impact identity.",
            "Any source/candidate/evidence change makes prior post-patch evidence stale."
        ))
        stage(stages, 12, "AI_EVALUATION", "independent AI", listOf(
            "Send the bounded verification bundle, not a self-authored completion claim.",
            "Evaluator independently inspects source/evidence and returns exact defects or an acceptable-candidate result bound to subject hashes."
        ))
        stage(stages, 13, "LOCAL_VERIFY", "CLI/Local Agent", listOf(
            "Verify evaluator response subject hashes and independence fields against the still-current candidate.",
            "OBSERVE may report WOULD_ACCEPT/WOULD_DENY only; it cannot promote trust or publish."
        ))

        return JSONObject()
            .put("schema", SCHEMA)
            .put("version", VERSION)
            .put("mode", "OBSERVE")
            .put("productionReady", false)
            .put("stages", stages)
            .put("research", RiftResearchLedgerV1.contract())
            .put("mandatoryEvidence", JSONArray(ALL_EVIDENCE.sorted()))
            .put("standardsAlignment", standardsAlignment())
            .put("invariants", JSONArray(listOf(
                "AI never chooses the authoritative impact scope; Patch Manifest V1 + Project Intelligence V2 do.",
                "AI may propose and reason, but Local Agent/CLI owns evidence correlation and mutation/trust boundaries.",
                "Research collection is not independent verification.",
                "Post-patch evidence is valid only for the exact candidate manifest it was imported against.",
                "Final AI evaluation must echo the exact source/evidence subject hashes.",
                "Evaluation never advances trustedCheckpoint in V1.",
                "No new MCP tools, raw Android shell, hidden network backend or persistent experimental enable flag are added.",
                "No silent override exists; exceptions remain explicit future policy work."
            )))
    }

    private fun help(): JSONObject = JSONObject()
        .put("schema", SCHEMA)
        .put("commands", JSONArray(listOf(
            "rift-cli lifecycle contract",
            "rift-cli lifecycle begin <project> <goal...>",
            "rift-cli lifecycle begin-sync <project> <goal...>",
            "rift-cli lifecycle status <sessionId>",
            "rift-cli lifecycle request <sessionId>",
            "rift-cli lifecycle documentation-plan <sessionId>",
            "rift-cli lifecycle verification-plan <sessionId>",
            "rift-cli lifecycle import <sessionId> <kind> <D:/Documents|D:/Temp json>",
            "rift-cli lifecycle evaluation <sessionId>",
            "rift-cli lifecycle verify <sessionId> <D:/Documents|D:/Temp evaluation.json>",
            "rift-cli lifecycle clear <sessionId>"
        )))
        .put("mode", "OBSERVE")
        .put("newMcpTools", 0)
        .put("trustedPromotionAllowed", false)

    private fun begin(context: Context, rawProject: String, rawGoal: String, sync: Boolean): JSONObject {
        val goal = rawGoal.trim()
        require(goal.isNotBlank()) { "Lifecycle goal is required" }
        require(goal.length <= MAX_GOAL_CHARS) { "Lifecycle goal exceeds " + MAX_GOAL_CHARS + " characters" }
        val target = projectTarget(context, rawProject)
        val git = RiftMcpRuntime.nativeGit(context)
        var status = gitStatus(git, target.displayPath)
        require(isGitClean(status)) { "Lifecycle acquisition requires a clean repository before begin" }

        var pullResult: JSONObject? = null
        if (sync) {
            pullResult = git.execute(
                listOf("-C", target.displayPath, "pull"),
                "/D:/Workspace"
            ).result ?: JSONObject()
            status = gitStatus(git, target.displayPath)
            require(isGitClean(status)) { "Repository is not clean after synchronized acquisition" }
        }

        val sourceSnapshot = RiftProjectExporter.snapshotId(target.file)
        val inventory = inventory(target.file)
        require(!inventory.optBoolean("truncated", true)) {
            "Lifecycle acquisition inventory exceeded its bounded full-repo scan; refuse incomplete acquisition"
        }
        val meta = status.getJSONObject("meta")
        val head = meta.optString("headSha").trim()
        require(head.matches(Regex("^[0-9a-f]{40}$"))) { "Lifecycle acquisition requires a 40-hex Git HEAD" }
        val preCandidate = currentCandidate(context)
        require(preCandidate.optInt("changedFiles", 0) == 0) {
            "Workspace contains uncheckpointed changes outside the clean acquired repo; refusing a global operational checkpoint"
        }
        val checkpoint = RiftWorkspaceRecords.get(context).checkpoint(
            JSONObject()
                .put("reason", "cli:lifecycle-begin")
                .put("gitRoot", target.workspacePath)
                .put("gitHeadSha", head)
        )

        val root = sessionRoot(context)
        val sessions = root.listFiles()?.count { it.isDirectory } ?: 0
        require(sessions < MAX_SESSIONS) { "Lifecycle session store reached " + MAX_SESSIONS + " sessions" }

        val now = System.currentTimeMillis()
        val id = "lifecycle-" + now + "-" + UUID.randomUUID().toString().take(12)
        val sessionDir = File(root, id).apply { mkdirs() }
        File(sessionDir, "evidence").mkdirs()
        val base = JSONObject()
            .put("repository", meta.optString("full"))
            .put("branch", meta.optString("branch"))
            .put("gitHead", head)
            .put("gitRoot", meta.optString("root"))
            .put("projectDisplay", target.displayPath)
            .put("projectWorkspacePath", target.workspacePath)
            .put("sourceSnapshotId", sourceSnapshot)
            .put("clean", true)
            .put("synchronizedByCli", sync)
            .put("pullResult", pullResult ?: JSONObject.NULL)
            .put("operationalCheckpoint", checkpoint)
            .put("inventory", inventory)

        val session = JSONObject()
            .put("schema", SESSION_SCHEMA)
            .put("version", VERSION)
            .put("sessionId", id)
            .put("createdAt", now)
            .put("mode", "OBSERVE")
            .put("goal", goal)
            .put("base", base)
            .put("evidence", JSONArray())
            .put("processEpoch", PROCESS_EPOCH)
            .put("lastObservedSourceSnapshotId", sourceSnapshot)
            .put("lastObservedCandidateManifestSha256", preCandidate.optString("manifestSha256"))
            .put("restartDriftDetected", false)
            .put("trustedPromotionAllowed", false)
            .put("publishAllowed", false)
        writeJsonAtomic(File(sessionDir, "session.json"), session)
        return requestPacket(context, id)
    }

    private fun requestPacket(context: Context, sessionId: String): JSONObject {
        val session = loadSession(context, sessionId)
        val status = currentProjectState(context, session)
        val base = session.getJSONObject("base")
        val baseStillCurrent =
            status.optString("gitHead") == base.optString("gitHead") &&
            status.optString("sourceSnapshotId") == base.optString("sourceSnapshotId")
        val out = JSONObject()
            .put("schema", "rift.cli-ai-work-request/1")
            .put("version", 1)
            .put("sessionId", sessionId)
            .put("mode", "OBSERVE")
            .put("goal", session.getString("goal"))
            .put("base", JSONObject(base.toString()))
            .put("current", status)
            .put("baseStillCurrent", baseStillCurrent)
            .put("contract", contract())
            .put("aiInstructions", JSONArray(listOf(
                "Do not patch yet. First map the full repository layout and ownership/docs/test/build surfaces.",
                "Research external assumptions that can materially affect implementation and record them using rift.research-ledger/1.",
                "Before research, import understanding evidence showing the repository layout/ownership/build/governance surfaces were mapped.",
                "Before code mutation, import research evidence and a design evidence record covering README/docs/TODO/roadmap/patch-history changes.",
                "Patch only after acquisition, understanding, research and documented intent are complete.",
                "After patching, call lifecycle documentation-plan and use its exact planSha256/source/governance scope in documentation evidence.",
                "Before security/dependencies/tests evidence, call lifecycle verification-plan and use its exact planSha256, targets and check ids.",
                "After patching, independently audit documents first, then code, then security/dependencies/tests/build/end-to-end.",
                "Re-run any audit whose subject became stale after another source edit.",
                "Ask the CLI for the final evaluation packet; do not self-declare completion."
            )))
        out.put("requestSha256", RiftPatchManifestV1.sha256Canonical(out))
        return out
    }

    private fun documentationPlan(context: Context, sessionId: String): JSONObject {
        val session = loadSession(context, sessionId)
        require(!session.optBoolean("restartDriftDetected", false)) {
            "Lifecycle session detected workspace drift across process restart; start a fresh lifecycle from a clean base"
        }
        val base = session.getJSONObject("base")
        val target = projectTarget(context, base.getString("projectDisplay"))
        val impact = candidateImpact(context)
        return RiftDocumentationParityV1.plan(
            target.file,
            base.getString("projectWorkspacePath"),
            impact
        )
    }

    private fun verificationPlan(context: Context, sessionId: String): JSONObject {
        val session = loadSession(context, sessionId)
        require(!session.optBoolean("restartDriftDetected", false)) {
            "Lifecycle session detected workspace drift across process restart; start a fresh lifecycle from a clean base"
        }
        val impact = candidateImpact(context)
        return verificationPlanFor(context, session, impact)
    }

    private fun verificationPlanFor(
        context: Context,
        session: JSONObject,
        impact: JSONObject
    ): JSONObject {
        val base = session.getJSONObject("base")
        val target = projectTarget(context, base.getString("projectDisplay"))
        val currentInventory = inventory(target.file)
        require(!currentInventory.optBoolean("truncated", true)) {
            "Current repository inventory exceeded the bounded scan; verification scope is incomplete"
        }
        val buildManifests = jsonStringSet(
            currentInventory.optJSONArray("dependencyAndBuildManifests")
        )
        return RiftVerificationPlannerV1.plan(
            target.file,
            base.getString("projectWorkspacePath"),
            impact,
            buildManifests
        )
    }

    private fun status(context: Context, sessionId: String): JSONObject {
        val session = loadSession(context, sessionId)
        val current = currentProjectState(context, session)
        val evidence = loadEvidence(context, session)
        val base = session.getJSONObject("base")
        return JSONObject()
            .put("schema", "rift.cli-patch-lifecycle-status/1")
            .put("sessionId", sessionId)
            .put("goal", session.getString("goal"))
            .put("base", JSONObject(base.toString()))
            .put("current", current)
            .put("gitHeadChanged", current.optString("gitHead") != base.optString("gitHead"))
            .put("sourceChanged", current.optString("sourceSnapshotId") != base.optString("sourceSnapshotId"))
            .put("restartDriftDetected", session.optBoolean("restartDriftDetected", false))
            .put("restartDrift", session.optJSONObject("restartDrift") ?: JSONObject.NULL)
            .put("sessionStorage", "riftfs/system/rift-cli-patch-lifecycle-v1")
            .put("evidence", evidenceSummary(evidence))
            .put("trustedPromotionAllowed", false)
    }

    private fun importEvidence(context: Context, sessionId: String, rawKind: String, rawPath: String): JSONObject {
        val kind = rawKind.trim().lowercase()
        require(kind in ALL_EVIDENCE) { "Unsupported lifecycle evidence kind: " + kind }
        val session = loadSession(context, sessionId)
        require(!session.optBoolean("restartDriftDetected", false)) {
            "Lifecycle session detected workspace drift across process restart; start a fresh lifecycle from a clean base"
        }
        val external = evidenceInputFile(context, rawPath)
        require(external.length() <= MAX_EVIDENCE_BYTES) { "Evidence file exceeds " + MAX_EVIDENCE_BYTES + " bytes" }
        val payload = JSONObject(external.readText(Charsets.UTF_8))
        require(payload.optString("schema") == EVIDENCE_SCHEMA) { "Evidence schema must be " + EVIDENCE_SCHEMA }
        require(payload.optString("kind").trim().lowercase() == kind) { "Evidence kind does not match import command" }
        rejectSensitiveKeys(payload)

        val actor = payload.optString("actor").trim()
        require(actor.isNotBlank() && actor.length <= MAX_ACTOR_CHARS) { "Evidence actor is required and bounded" }
        val summary = payload.optString("summary").trim()
        require(summary.isNotBlank() && summary.length <= MAX_SUMMARY_CHARS) { "Evidence summary is required and bounded" }
        val checks = payload.optJSONArray("checks") ?: JSONArray()
        require(checks.length() <= MAX_CHECKS) { "Evidence exceeds " + MAX_CHECKS + " checks" }
        var nonPassChecks = 0
        for (index in 0 until checks.length()) {
            val check = checks.optJSONObject(index)
                ?: throw IllegalArgumentException("Evidence check " + index + " must be an object")
            val id = check.optString("id").trim()
            val state = check.optString("status").trim().uppercase()
            require(id.isNotBlank() && id.length <= 160) { "Evidence check id is invalid" }
            require(state in setOf("PASS", "FAIL", "WARN", "NOT_RUN")) { "Evidence check status is invalid: " + state }
            if (state != "PASS") nonPassChecks++
        }
        val targetsRaw = payload.optJSONArray("targets") ?: JSONArray()
        require(targetsRaw.length() <= MAX_EVIDENCE_TARGETS) {
            "Evidence exceeds " + MAX_EVIDENCE_TARGETS + " targets"
        }
        val targets = LinkedHashSet<String>()
        for (index in 0 until targetsRaw.length()) {
            val target = targetsRaw.optString(index).trim().replace('\\', '/').trim('/')
            require(target.isNotBlank() && !target.split('/').contains("..")) { "Evidence target is invalid" }
            targets += target
        }

        var complete = payload.optBoolean("complete", false) && nonPassChecks == 0
        if (kind != "research" && complete && checks.length() == 0) {
            complete = false
        }
        var normalizedResearch: JSONObject? = null
        var normalizedDocumentationParity: JSONObject? = null
        var normalizedVerificationPlanEvidence: JSONObject? = null
        if (kind == "research") {
            normalizedResearch = RiftResearchLedgerV1.validate(
                payload.optJSONObject("researchLedger")
                    ?: throw IllegalArgumentException("Research evidence requires researchLedger")
            )
            complete = complete && normalizedResearch.optBoolean("complete", false)
        }

        val projectState = currentProjectState(context, session)
        val impactAtImport = candidateImpact(context)
        val candidate = impactAtImport.optJSONObject("candidate") ?: currentCandidate(context)
        val activeVerificationPlan =
            if (kind in VERIFICATION_EVIDENCE) {
                verificationPlanFor(context, session, impactAtImport)
            } else {
                null
            }
        val requiredTargets = expectedTargetsForEvidence(
            context,
            kind,
            session,
            impactAtImport,
            activeVerificationPlan
        )
        val missingTargets = requiredTargets.filter { it !in targets }
        if (missingTargets.isNotEmpty()) complete = false
        if (kind == "documentation") {
            val base = session.getJSONObject("base")
            val target = projectTarget(context, base.getString("projectDisplay"))
            val parityPlan = RiftDocumentationParityV1.plan(
                target.file,
                base.getString("projectWorkspacePath"),
                impactAtImport
            )
            normalizedDocumentationParity = RiftDocumentationParityV1.validateEvidence(
                parityPlan,
                payload.optJSONObject("documentationParity")
                    ?: throw IllegalArgumentException(
                        "Documentation evidence requires documentationParity from lifecycle documentation-plan"
                    )
            )
            complete = complete && normalizedDocumentationParity.optBoolean("complete", false)
        }
        if (activeVerificationPlan != null) {
            normalizedVerificationPlanEvidence = RiftVerificationPlannerV1.validateEvidence(
                activeVerificationPlan,
                kind,
                payload
            )
            complete =
                complete &&
                    normalizedVerificationPlanEvidence.optBoolean("complete", false)
        }
        val existingEvidence = latestEvidenceByKind(loadEvidence(context, session))
        if (kind == "understanding") {
            val base = session.getJSONObject("base")
            if (projectState.optString("sourceSnapshotId") != base.optString("sourceSnapshotId")) complete = false
            if (candidate.optInt("changedFiles", 0) != 0) complete = false
        }
        if (kind == "design") {
            val research = existingEvidence["research"]
            if (research == null || !research.optBoolean("complete", false)) complete = false
            val changes = impactAtImport.optJSONArray("changes") ?: JSONArray()
            val codeAlreadyChanged = (0 until changes.length()).any { index ->
                val category = changes.optJSONObject(index)?.optString("category").orEmpty()
                category == "source" || category == "build-config"
            }
            if (codeAlreadyChanged) complete = false
        }
        if (kind == "research") {
            val base = session.getJSONObject("base")
            val understanding = existingEvidence["understanding"]
            if (understanding == null || !understanding.optBoolean("complete", false)) complete = false
            if (projectState.optString("sourceSnapshotId") != base.optString("sourceSnapshotId")) complete = false
        }
        if (kind == "dependencies" && complete) validateSupplyChainEvidence(payload)
        if (kind == "build" && complete) validateBuildEvidence(payload)
        if (kind == "rollback" && complete) {
            val plan = payload.optString("rollbackPlan").trim()
            require(plan.isNotBlank() && plan.length <= MAX_SUMMARY_CHARS) {
                "Complete rollback evidence requires a bounded rollbackPlan"
            }
        }
        val evidenceSha = RiftPatchManifestV1.sha256Canonical(payload)
        val now = System.currentTimeMillis()
        val wrapper = JSONObject()
            .put("schema", "rift.cli-imported-evidence/1")
            .put("version", 1)
            .put("sessionId", sessionId)
            .put("kind", kind)
            .put("actor", actor)
            .put("summary", summary)
            .put("complete", complete)
            .put("importedAt", now)
            .put("payloadSha256", evidenceSha)
            .put("sourceSnapshotIdAtImport", projectState.optString("sourceSnapshotId"))
            .put("gitHeadAtImport", projectState.optString("gitHead"))
            .put("candidateManifestSha256AtImport", candidate.optString("manifestSha256"))
            .put("semanticImpactSha256AtImport", impactAtImport.optString("semanticImpactSha256"))
            .put("requiredTargets", JSONArray(requiredTargets.sorted()))
            .put("missingTargets", JSONArray(missingTargets.sorted()))
            .put("payload", payload)
        if (normalizedResearch != null) wrapper.put("normalizedResearchLedger", normalizedResearch)
        if (normalizedDocumentationParity != null) {
            wrapper.put("normalizedDocumentationParity", normalizedDocumentationParity)
        }
        if (normalizedVerificationPlanEvidence != null) {
            wrapper.put(
                "normalizedVerificationPlanEvidence",
                normalizedVerificationPlanEvidence
            )
        }

        val sessionDir = sessionDirectory(context, sessionId)
        val evidenceDir = File(sessionDir, "evidence").apply { mkdirs() }
        val file = File(evidenceDir, "%013d-%s-%s.json".format(now, kind, evidenceSha.take(12)))
        writeJsonAtomic(file, wrapper)

        val evidenceRows = session.optJSONArray("evidence") ?: JSONArray()
        require(evidenceRows.length() < MAX_EVIDENCE_RECORDS) {
            "Lifecycle session reached " + MAX_EVIDENCE_RECORDS + " evidence records"
        }
        evidenceRows.put(JSONObject()
            .put("kind", kind)
            .put("file", file.name)
            .put("payloadSha256", evidenceSha)
            .put("complete", complete)
            .put("importedAt", now))
        session.put("evidence", evidenceRows)
        writeJsonAtomic(File(sessionDir, "session.json"), session)

        return JSONObject()
            .put("imported", true)
            .put("sessionId", sessionId)
            .put("kind", kind)
            .put("complete", complete)
            .put("payloadSha256", evidenceSha)
            .put("subjectSourceSnapshotId", projectState.optString("sourceSnapshotId"))
            .put("subjectManifestSha256", candidate.optString("manifestSha256"))
            .put("missingTargets", JSONArray(missingTargets.sorted()))
            .put(
                "documentationParityPlanSha256",
                normalizedDocumentationParity?.optString("planSha256") ?: JSONObject.NULL
            )
            .put(
                "verificationPlanSha256",
                normalizedVerificationPlanEvidence?.optString("planSha256")
                    ?: JSONObject.NULL
            )
    }

    private fun evaluationPacket(context: Context, sessionId: String): JSONObject {
        val session = loadSession(context, sessionId)
        val current = currentProjectState(context, session)
        val base = session.getJSONObject("base")
        require(!session.optBoolean("restartDriftDetected", false)) {
            "Lifecycle session detected workspace drift across process restart; start a fresh lifecycle from a clean base"
        }
        val impact = candidateImpact(context)
        val candidate = impact.optJSONObject("candidate")
            ?: throw IllegalStateException("Semantic impact did not return candidate identity")
        val records = RiftWorkspaceRecords.get(context)
        val freeze = records.freezeCandidate()
        require(freeze.optString("manifestSha256") == candidate.optString("manifestSha256")) {
            "Candidate changed between semantic impact and freeze"
        }
        val parityTarget = projectTarget(context, base.getString("projectDisplay"))
        val documentationParityPlan = RiftDocumentationParityV1.plan(
            parityTarget.file,
            base.getString("projectWorkspacePath"),
            impact
        )
        val verificationPlan = verificationPlanFor(context, session, impact)

        val allEvidence = loadEvidence(context, session)
        val latest = latestEvidenceByKind(allEvidence)
        val finalManifest = candidate.optString("manifestSha256")
        val requiredKinds = requiredEvidenceKinds(impact).toMutableSet()
        if (latest.containsKey("build")) requiredKinds += "build"
        val issues = JSONArray()

        if (current.optString("gitHead") != base.optString("gitHead")) {
            issues.put(issue("BASE_REVISION_CHANGED", "Git HEAD changed after lifecycle acquisition. Restart from synchronized acquisition."))
        }
        if (base.getJSONObject("inventory").optBoolean("truncated", true)) {
            issues.put(issue("ACQUISITION_INCOMPLETE", "Full repository inventory was truncated."))
        }
        if (!impact.optBoolean("complete", false)) {
            issues.put(issue(
                "SEMANTIC_IMPACT_INCOMPLETE",
                impact.optJSONArray("incompleteReasons")?.toString() ?: "Semantic impact is incomplete."
            ))
        }
        val recordChain = freeze.optJSONObject("recordChain")
        if (recordChain != null && !recordChain.optBoolean("ok", false)) {
            issues.put(issue("RECORD_CHAIN_INVALID", "Workspace Records hash chain is not valid."))
        }

        val understanding = latest["understanding"]
        if (understanding == null || !understanding.optBoolean("complete", false)) {
            issues.put(issue("UNDERSTANDING_EVIDENCE_MISSING", "Complete base-bound repository-understanding evidence is mandatory before research."))
        } else if (understanding.optString("sourceSnapshotIdAtImport") != base.optString("sourceSnapshotId")) {
            issues.put(issue("UNDERSTANDING_NOT_BASE_BOUND", "Understanding evidence was not imported against the acquired base snapshot."))
        }

        val research = latest["research"]
        if (research == null || !research.optBoolean("complete", false)) {
            issues.put(issue("RESEARCH_EVIDENCE_MISSING", "Complete research/not-required evidence is mandatory before patch evaluation."))
        } else if (research.optString("sourceSnapshotIdAtImport") != base.optString("sourceSnapshotId")) {
            issues.put(issue("RESEARCH_NOT_PRE_PATCH", "Research evidence was not imported against the acquired base snapshot."))
        }

        val design = latest["design"]
        if (design == null || !design.optBoolean("complete", false)) {
            issues.put(issue("DESIGN_EVIDENCE_MISSING", "Pre-patch design/documentation-intent evidence is mandatory."))
        } else if (research != null && design.optLong("importedAt") < research.optLong("importedAt")) {
            issues.put(issue("DESIGN_BEFORE_RESEARCH", "Design evidence predates research evidence."))
        }

        val parityHardIssues = documentationParityPlan.optJSONArray("hardIssues") ?: JSONArray()
        for (index in 0 until parityHardIssues.length()) {
            val row = parityHardIssues.optJSONObject(index) ?: continue
            issues.put(issue(
                row.optString("code", "DOCUMENTATION_PARITY_HARD_FAIL"),
                row.optString("path") + ": " + row.optString("message"),
                "documentation"
            ))
        }
        val documentationEvidence = latest["documentation"]
        val normalizedParity = documentationEvidence?.optJSONObject("normalizedDocumentationParity")
        if (normalizedParity == null) {
            issues.put(issue(
                "DOCUMENTATION_PARITY_EVIDENCE_MISSING",
                "Documentation evidence does not contain normalized Patch-8 parity evidence.",
                "documentation"
            ))
        } else {
            if (
                normalizedParity.optString("planSha256") !=
                documentationParityPlan.optString("planSha256")
            ) {
                issues.put(issue(
                    "DOCUMENTATION_PARITY_STALE",
                    "Documentation parity evidence belongs to an earlier candidate plan.",
                    "documentation"
                ))
            }
            if (!normalizedParity.optBoolean("complete", false)) {
                issues.put(issue(
                    "DOCUMENTATION_PARITY_INCOMPLETE",
                    "Documentation parity review is incomplete.",
                    "documentation"
                ))
                val parityIssues = normalizedParity.optJSONArray("issues") ?: JSONArray()
                for (index in 0 until parityIssues.length()) {
                    val row = parityIssues.optJSONObject(index) ?: continue
                    issues.put(issue(
                        row.optString("code", "DOCUMENTATION_PARITY_REVIEW_FAIL"),
                        row.optString("path") + ": " + row.optString("message"),
                        "documentation"
                    ))
                }
            }
        }

        val verificationHardIssues = verificationPlan.optJSONArray("hardIssues") ?: JSONArray()
        for (index in 0 until verificationHardIssues.length()) {
            val row = verificationHardIssues.optJSONObject(index) ?: continue
            issues.put(issue(
                row.optString("code", "VERIFICATION_PLAN_HARD_FAIL"),
                row.optString("message"),
                "verification-plan"
            ))
        }
        for (kind in requiredKinds.filter { it in VERIFICATION_EVIDENCE }.sorted()) {
            val evidence = latest[kind]
            val normalized =
                evidence?.optJSONObject("normalizedVerificationPlanEvidence")
            if (normalized == null) {
                issues.put(issue(
                    "VERIFICATION_PLAN_EVIDENCE_MISSING",
                    kind + " evidence does not contain normalized Patch-9 verification evidence.",
                    kind
                ))
            } else {
                if (
                    normalized.optString("planSha256") !=
                    verificationPlan.optString("planSha256")
                ) {
                    issues.put(issue(
                        "VERIFICATION_PLAN_STALE",
                        kind + " evidence belongs to an earlier verification plan.",
                        kind
                    ))
                }
                if (!normalized.optBoolean("complete", false)) {
                    issues.put(issue(
                        "VERIFICATION_PLAN_INCOMPLETE",
                        kind + " verification evidence is incomplete. Missing checks=" +
                            (normalized.optJSONArray("missingCheckIds") ?: JSONArray()).toString() +
                            ", missing targets=" +
                            (normalized.optJSONArray("missingTargets") ?: JSONArray()).toString(),
                        kind
                    ))
                }
            }
        }

        for (kind in requiredKinds.filter { it in POST_PATCH_EVIDENCE }.sorted()) {
            val evidence = latest[kind]
            if (evidence == null || !evidence.optBoolean("complete", false)) {
                issues.put(issue("EVIDENCE_MISSING", "Missing complete " + kind + " evidence.", kind))
            } else if (evidence.optString("candidateManifestSha256AtImport") != finalManifest) {
                issues.put(issue("STALE_EVIDENCE", kind + " evidence belongs to an earlier candidate.", kind))
            }
        }
        var previousEvidenceAt = 0L
        for (kind in POST_EVIDENCE_ORDER.filter { it in requiredKinds }) {
            val evidence = latest[kind] ?: continue
            val importedAt = evidence.optLong("importedAt", 0L)
            if (importedAt < previousEvidenceAt) {
                issues.put(issue(
                    "EVIDENCE_ORDER_INVALID",
                    kind + " evidence was imported before the required prior verification stage.",
                    kind
                ))
            }
            previousEvidenceAt = maxOf(previousEvidenceAt, importedAt)
        }

        val projectWorkspace = base.optString("projectWorkspacePath")
        val projects = impact.optJSONArray("projects") ?: JSONArray()
        val projectRoots = (0 until projects.length()).mapNotNull { index ->
            projects.optJSONObject(index)?.optString("root")?.takeIf { it.isNotBlank() }
        }
        val outside = projectRoots.filter { root ->
            root != projectWorkspace && !root.startsWith(projectWorkspace + "/")
        }
        if (outside.isNotEmpty()) {
            issues.put(issue("OUT_OF_SCOPE_PROJECT_CHANGES", "Candidate also changes: " + outside.joinToString()))
        }

        val evidenceRows = JSONArray()
        val evidenceSubjects = JSONArray()
        for ((kind, row) in latest.toSortedMap()) {
            evidenceRows.put(evaluationEvidenceRow(row))
            evidenceSubjects.put(JSONObject()
                .put("kind", kind)
                .put("payloadSha256", row.optString("payloadSha256"))
                .put("complete", row.optBoolean("complete", false))
                .put("candidateManifestSha256AtImport", row.optString("candidateManifestSha256AtImport"))
                .put("sourceSnapshotIdAtImport", row.optString("sourceSnapshotIdAtImport")))
        }
        val evidenceBundleSha = RiftPatchManifestV1.sha256Canonical(evidenceSubjects)
        val policy = JSONObject()
            .put("mode", "OBSERVE")
            .put("wouldDeny", issues.length() > 0)
            .put("issues", issues)
            .put("requiredEvidence", JSONArray(requiredKinds.sorted()))
            .put("trustedPromotionAllowed", false)
        val policySha = RiftPatchManifestV1.sha256Canonical(policy)
        val evaluationSubject = JSONObject()
            .put("sessionId", sessionId)
            .put("baseGitHead", base.optString("gitHead"))
            .put("baseSourceSnapshotId", base.optString("sourceSnapshotId"))
            .put("currentSourceSnapshotId", current.optString("sourceSnapshotId"))
            .put("candidateId", candidate.optString("candidateId"))
            .put("manifestSha256", finalManifest)
            .put("baseTreeSha256", candidate.optString("baseTreeSha256"))
            .put("resultTreeSha256", candidate.optString("resultTreeSha256"))
            .put("semanticImpactSha256", impact.optString("semanticImpactSha256"))
            .put(
                "documentationParityPlanSha256",
                documentationParityPlan.optString("planSha256")
            )
            .put(
                "verificationPlanSha256",
                verificationPlan.optString("planSha256")
            )
            .put("evidenceBundleSha256", evidenceBundleSha)
            .put("policySha256", policySha)
        val bundleSha = RiftPatchManifestV1.sha256Canonical(evaluationSubject)

        val packet = JSONObject()
            .put("schema", "rift.cli-ai-evaluation-request/1")
            .put("version", 1)
            .put("generatedAt", System.currentTimeMillis())
            .put("mode", "OBSERVE")
            .put("goal", session.getString("goal"))
            .put("subject", evaluationSubject)
            .put("evaluationBundleSha256", bundleSha)
            .put("candidate", candidate)
            .put("semanticImpact", impact)
            .put("documentationParityPlan", documentationParityPlan)
            .put("verificationPlan", verificationPlan)
            .put("freezeReceipt", freeze)
            .put("repository", current)
            .put(
                "governanceDocuments",
                inventory(projectTarget(context, base.getString("projectDisplay")).file)
                    .getJSONArray("governanceDocuments")
            )
            .put("evidence", evidenceRows)
            .put("policy", policy)
            .put("standardsAlignment", standardsAlignment())
            .put("evaluatorInstructions", JSONArray(listOf(
                "Independently inspect the exact candidate; do not accept the patch author's completion claim as evidence.",
                "Re-check critical external research claims against their cited sources.",
                "Verify the Patch-8 documentationParityPlan against exact source and the normalized documentation parity evidence.",
                "Verify the Patch-9 verificationPlan against exact PI-v2 impact and normalized security/dependencies/tests verification evidence.",
                "Verify docs/README/TODO/roadmap/patch-history/source-ownership parity against source.",
                "Verify semantic impact, callers/dependents, security/dependencies/tests/build/end-to-end evidence and negative cases.",
                "Return RETURN_DEFECTS for any unresolved, stale, missing, contradictory or incomplete evidence.",
                "Echo sessionId, evaluationBundleSha256, manifestSha256, semanticImpactSha256, documentationParityPlanSha256, verificationPlanSha256, evidenceBundleSha256 and policySha256 exactly.",
                "Use schema rift.cli-ai-evaluation/1. Include evaluatorId, patchActorId, independent=true, verdict and structured defects.",
                "Each defect must include code, severity, reason and fix; optional path identifies the affected file/surface.",
                "An ACCEPTABLE_CANDIDATE response is advisory; Local Agent still verifies hashes and cannot promote trust in V1."
            )))
        val packetBytes = packet.toString().toByteArray(Charsets.UTF_8).size
        require(packetBytes <= MAX_EVALUATION_PACKET_BYTES) {
            "Evaluation packet exceeds " + MAX_EVALUATION_PACKET_BYTES +
                " bytes; refuse to truncate verification evidence"
        }
        packet.put("packetBytes", packetBytes)
        val finalPacketBytes = packet.toString().toByteArray(Charsets.UTF_8).size
        require(finalPacketBytes <= MAX_EVALUATION_PACKET_BYTES) {
            "Evaluation packet metadata crossed the final size bound"
        }
        packet.put("packetBytes", finalPacketBytes)
        return packet
    }

    private fun verifyEvaluation(context: Context, sessionId: String, rawPath: String): JSONObject {
        val packet = evaluationPacket(context, sessionId)
        val external = evidenceInputFile(context, rawPath)
        require(external.length() <= MAX_EVIDENCE_BYTES) { "Evaluation file exceeds " + MAX_EVIDENCE_BYTES + " bytes" }
        val response = JSONObject(external.readText(Charsets.UTF_8))
        rejectSensitiveKeys(response)
        require(response.optString("schema") == EVALUATION_SCHEMA) { "Evaluation schema must be " + EVALUATION_SCHEMA }

        val subject = packet.getJSONObject("subject")
        val expectedBundle = packet.getString("evaluationBundleSha256")
        val evaluatorId = response.optString("evaluatorId").trim()
        val patchActorId = response.optString("patchActorId").trim()
        val independent = response.optBoolean("independent", false)
        val verdict = response.optString("verdict").trim().uppercase()
        require(verdict in setOf("ACCEPTABLE_CANDIDATE", "RETURN_DEFECTS")) { "Unsupported evaluation verdict" }

        val matches =
            response.optString("sessionId") == sessionId &&
            response.optString("evaluationBundleSha256") == expectedBundle &&
            response.optString("manifestSha256") == subject.optString("manifestSha256") &&
            response.optString("semanticImpactSha256") == subject.optString("semanticImpactSha256") &&
            response.optString("documentationParityPlanSha256") ==
                subject.optString("documentationParityPlanSha256") &&
            response.optString("verificationPlanSha256") ==
                subject.optString("verificationPlanSha256") &&
            response.optString("evidenceBundleSha256") == subject.optString("evidenceBundleSha256") &&
            response.optString("policySha256") == subject.optString("policySha256")
        val identitySeparated =
            independent &&
            evaluatorId.isNotBlank() &&
            patchActorId.isNotBlank() &&
            evaluatorId != patchActorId
        val policyWouldDeny = packet.getJSONObject("policy").optBoolean("wouldDeny", true)
        val defects = response.optJSONArray("defects") ?: JSONArray()
        require(defects.length() <= MAX_EVALUATION_DEFECTS) {
            "Evaluation exceeds " + MAX_EVALUATION_DEFECTS + " defects"
        }
        val severities = setOf("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO")
        for (index in 0 until defects.length()) {
            val defect = defects.optJSONObject(index)
                ?: throw IllegalArgumentException("Evaluation defect " + index + " must be an object")
            val code = defect.optString("code").trim()
            val severity = defect.optString("severity").trim().uppercase()
            val reason = defect.optString("reason").trim()
            val fix = defect.optString("fix").trim()
            val path = defect.optString("path").trim()
            require(code.isNotBlank() && code.length <= 160) { "Evaluation defect code is invalid" }
            require(severity in severities) { "Evaluation defect severity is invalid: " + severity }
            require(reason.isNotBlank() && reason.length <= MAX_SUMMARY_CHARS) {
                "Evaluation defect reason is required and bounded"
            }
            require(fix.isNotBlank() && fix.length <= MAX_SUMMARY_CHARS) {
                "Evaluation defect fix is required and bounded"
            }
            require(path.length <= 2_000 && !path.replace('\\', '/').split('/').contains("..")) {
                "Evaluation defect path is unsafe or oversized"
            }
        }
        val wouldAccept =
            matches &&
            identitySeparated &&
            verdict == "ACCEPTABLE_CANDIDATE" &&
            defects.length() == 0 &&
            !policyWouldDeny

        return JSONObject()
            .put("schema", "rift.cli-ai-evaluation-verification/1")
            .put("sessionId", sessionId)
            .put("subjectMatches", matches)
            .put("identitySeparated", identitySeparated)
            .put("independenceAuthenticated", false)
            .put("verdict", verdict)
            .put("policyWouldDeny", policyWouldDeny)
            .put("wouldAccept", wouldAccept)
            .put("wouldDeny", !wouldAccept)
            .put("trustedCheckpointPromoted", false)
            .put("published", false)
            .put("note", "OBSERVE only. Verification cannot advance trust or publish source.")
    }

    private fun clear(context: Context, sessionId: String): JSONObject {
        val dir = sessionDirectory(context, sessionId)
        val removed = dir.deleteRecursively()
        return JSONObject()
            .put("sessionId", sessionId)
            .put("cleared", removed)
            .put("workspaceChanged", false)
    }

    private fun evaluationEvidenceRow(row: JSONObject): JSONObject {
        val payload = row.optJSONObject("payload") ?: JSONObject()
        val out = JSONObject()
            .put("kind", row.optString("kind"))
            .put("actor", row.optString("actor"))
            .put("summary", row.optString("summary"))
            .put("complete", row.optBoolean("complete", false))
            .put("importedAt", row.optLong("importedAt"))
            .put("payloadSha256", row.optString("payloadSha256"))
            .put("sourceSnapshotIdAtImport", row.optString("sourceSnapshotIdAtImport"))
            .put("candidateManifestSha256AtImport", row.optString("candidateManifestSha256AtImport"))
            .put("semanticImpactSha256AtImport", row.optString("semanticImpactSha256AtImport"))
            .put("requiredTargets", row.optJSONArray("requiredTargets") ?: JSONArray())
            .put("missingTargets", row.optJSONArray("missingTargets") ?: JSONArray())
            .put("checks", payload.optJSONArray("checks") ?: JSONArray())
            .put("targets", payload.optJSONArray("targets") ?: JSONArray())
        if (row.has("normalizedResearchLedger")) {
            out.put("researchLedger", row.getJSONObject("normalizedResearchLedger"))
        }
        if (row.has("normalizedDocumentationParity")) {
            out.put(
                "documentationParity",
                row.getJSONObject("normalizedDocumentationParity")
            )
        }
        if (row.has("normalizedVerificationPlanEvidence")) {
            out.put(
                "verificationPlan",
                row.getJSONObject("normalizedVerificationPlanEvidence")
            )
        }
        if (payload.has("environment")) out.put("environment", payload.get("environment"))
        if (payload.has("artifacts")) out.put("artifacts", payload.get("artifacts"))
        if (payload.has("supplyChain")) out.put("supplyChain", payload.get("supplyChain"))
        if (payload.has("rollbackPlan")) out.put("rollbackPlan", payload.get("rollbackPlan"))
        return out
    }

    private fun validateSupplyChainEvidence(payload: JSONObject) {
        val supply = payload.optJSONObject("supplyChain")
            ?: throw IllegalArgumentException("Complete dependency evidence requires supplyChain")
        for (field in listOf("lockfileStatus", "sbomStatus", "licenseStatus", "provenanceStatus")) {
            val status = supply.optString(field).trim().uppercase()
            require(status in setOf("PASS", "NOT_APPLICABLE")) {
                "Supply-chain field " + field + " must be PASS or NOT_APPLICABLE"
            }
        }
    }

    private fun validateBuildEvidence(payload: JSONObject) {
        val environment = payload.optJSONObject("environment")
            ?: throw IllegalArgumentException("Complete build evidence requires environment identity")
        for (field in listOf("builder", "toolchain", "sourceRevision")) {
            require(environment.optString(field).trim().isNotBlank()) {
                "Build environment field is required: " + field
            }
        }
        val artifacts = payload.optJSONArray("artifacts")
            ?: throw IllegalArgumentException("Complete build evidence requires artifact digests")
        require(artifacts.length() in 1..64) { "Build evidence requires 1..64 artifacts" }
        for (index in 0 until artifacts.length()) {
            val artifact = artifacts.optJSONObject(index)
                ?: throw IllegalArgumentException("Build artifact " + index + " must be an object")
            require(artifact.optString("name").trim().isNotBlank()) { "Build artifact name is required" }
            require(artifact.optString("sha256").trim().lowercase().matches(Regex("^[0-9a-f]{64}$"))) {
                "Build artifact sha256 is invalid"
            }
        }
    }

    private fun expectedTargetsForEvidence(
        context: Context,
        kind: String,
        session: JSONObject,
        impact: JSONObject,
        verificationPlan: JSONObject? = null
    ): Set<String> {
        val base = session.getJSONObject("base")
        val projectRoot = base.getString("projectWorkspacePath").trim('/')
        val baseInventory = base.getJSONObject("inventory")
        val baseGovernance = jsonStringSet(baseInventory.optJSONArray("governanceDocuments"))
        val baseBuildManifests = jsonStringSet(baseInventory.optJSONArray("dependencyAndBuildManifests"))
        val currentInventory = inventory(projectTarget(context, base.getString("projectDisplay")).file)
        require(!currentInventory.optBoolean("truncated", true)) {
            "Current repository inventory exceeded the bounded scan; evidence scope is incomplete"
        }
        val currentGovernance = jsonStringSet(currentInventory.optJSONArray("governanceDocuments"))
        val currentBuildManifests = jsonStringSet(currentInventory.optJSONArray("dependencyAndBuildManifests"))
        val governance = baseGovernance + currentGovernance
        val buildManifests = baseBuildManifests + currentBuildManifests
        val changed = LinkedHashSet<String>()
        val sourceChanged = LinkedHashSet<String>()
        val impactChanges = impact.optJSONArray("changes") ?: JSONArray()
        for (index in 0 until impactChanges.length()) {
            val row = impactChanges.optJSONObject(index) ?: continue
            val path = toProjectRelative(row.optString("path"), projectRoot) ?: continue
            changed += path
            if (row.optString("category") == "source") sourceChanged += path
        }
        val affectedDocs = jsonStringSet(impact.optJSONArray("documentation"))
            .mapNotNull { toProjectRelative(it, projectRoot) }
            .toSet()
        val changedDocs = jsonStringSet(impact.optJSONArray("changedDocumentation"))
            .mapNotNull { toProjectRelative(it, projectRoot) }
            .toSet()
        val changedBuildConfig = jsonStringSet(impact.optJSONArray("changedBuildConfig"))
            .mapNotNull { toProjectRelative(it, projectRoot) }
            .toSet()
        val dependentSources = LinkedHashSet<String>()
        val dependents = impact.optJSONArray("directDependents") ?: JSONArray()
        for (index in 0 until dependents.length()) {
            val path = dependents.optJSONObject(index)?.optString("source").orEmpty()
            toProjectRelative(path, projectRoot)?.let { dependentSources += it }
        }

        val expected = when (kind) {
            "understanding" -> baseGovernance + baseBuildManifests
            "research" -> emptySet()
            "design" -> baseGovernance + baseBuildManifests
            "documentation" -> governance + affectedDocs + changedDocs
            "code-audit" -> changed + sourceChanged + dependentSources
            "security", "dependencies", "tests" -> {
                val plan = verificationPlan
                    ?: verificationPlanFor(context, session, impact)
                jsonStringSet(
                    plan.getJSONObject(kind).optJSONArray("targets")
                )
            }
            "build" -> buildManifests + changedBuildConfig
            "e2e", "rollback" -> changed
            else -> emptySet()
        }
        return expected.sorted().take(MAX_EVIDENCE_TARGETS).toSet()
    }

    private fun jsonStringSet(array: JSONArray?): Set<String> {
        if (array == null) return emptySet()
        val out = LinkedHashSet<String>()
        for (index in 0 until array.length()) {
            array.optString(index).trim().replace('\\', '/').trim('/')
                .takeIf { it.isNotBlank() }
                ?.let(out::add)
        }
        return out
    }

    private fun toProjectRelative(path: String, projectRoot: String): String? {
        val clean = path.trim().replace('\\', '/').trim('/')
        if (clean == projectRoot) return ""
        if (!clean.startsWith(projectRoot + "/")) return null
        return clean.removePrefix(projectRoot + "/")
    }

    private fun requiredEvidenceKinds(impact: JSONObject): Set<String> {
        val required = linkedSetOf(
            "understanding",
            "research",
            "design",
            "documentation",
            "code-audit",
            "security",
            "dependencies",
            "e2e",
            "rollback"
        )
        val changes = impact.optJSONArray("changes") ?: JSONArray()
        var testRelevantChange = false
        for (index in 0 until changes.length()) {
            val category = changes.optJSONObject(index)?.optString("category").orEmpty()
            if (category == "source" || category == "build-config" || category == "test") {
                testRelevantChange = true
            }
        }
        if (testRelevantChange) {
            required += "tests"
        }
        return required
    }

    private fun candidateImpact(context: Context): JSONObject {
        val latch = CountDownLatch(1)
        var value: JSONObject? = null
        RiftMcpRuntime.toolHost(context).candidateImpactAsync {
            value = it
            latch.countDown()
        }
        require(latch.await(CANDIDATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            "Timed out waiting for Project Intelligence candidate impact"
        }
        return value ?: throw IllegalStateException("Project Intelligence candidate impact returned no result")
    }

    private fun currentCandidate(context: Context): JSONObject {
        val query = RiftWorkspaceRecords.get(context).query(
            JSONObject().put("limit", 1).put("includeDiff", false)
        )
        return query.optJSONObject("candidate") ?: JSONObject()
    }

    private fun currentProjectState(context: Context, session: JSONObject): JSONObject {
        val base = session.getJSONObject("base")
        val target = projectTarget(context, base.getString("projectDisplay"))
        val status = gitStatus(RiftMcpRuntime.nativeGit(context), target.displayPath)
        val meta = status.getJSONObject("meta")
        return JSONObject()
            .put("repository", meta.optString("full"))
            .put("branch", meta.optString("branch"))
            .put("gitHead", meta.optString("headSha"))
            .put("clean", isGitClean(status))
            .put("modified", status.getJSONArray("modified"))
            .put("deleted", status.getJSONArray("deleted"))
            .put("untracked", status.getJSONArray("untracked"))
            .put("sourceSnapshotId", RiftProjectExporter.snapshotId(target.file))
    }

    private fun gitStatus(git: RiftNativeGit, displayPath: String): JSONObject =
        git.execute(listOf("-C", displayPath, "status"), "/D:/Workspace").result
            ?: throw IllegalStateException("Native Git status returned no structured result")

    private fun isGitClean(status: JSONObject): Boolean =
        status.optJSONArray("modified")?.length() == 0 &&
            status.optJSONArray("deleted")?.length() == 0 &&
            status.optJSONArray("untracked")?.length() == 0

    private fun inventory(root: File): JSONObject {
        val top = JSONArray()
        root.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { entry ->
            top.put(JSONObject()
                .put("name", entry.name)
                .put("kind", if (entry.isDirectory) "directory" else "file")
                .put("size", if (entry.isFile) entry.length() else 0L))
        }

        val governanceNames = setOf(
            "readme.md", "roadmap.md", "todo.md", "todos.md", "tasks.md",
            "patch_history.md", "project_status.md", "source_ownership.md", "contributing.md",
            "security.md", "license", "license.md"
        )
        val dependencyNames = setOf(
            "package.json", "package-lock.json", "pnpm-lock.yaml", "yarn.lock", "bun.lock", "bun.lockb",
            "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
            "gradle.properties", "cargo.toml", "cargo.lock", "pyproject.toml", "requirements.txt",
            "cmakelists.txt", "androidmanifest.xml"
        )
        val generatedDirs = setOf(
            ".gradle", "node_modules", "build", "dist", "out", "target", "vendor", "generated",
            ".cache", ".idea", "coverage"
        )
        val governance = ArrayList<String>()
        val dependencies = ArrayList<String>()
        val generated = LinkedHashSet<String>()
        var files = 0
        var bytes = 0L
        var truncated = false

        root.walkTopDown()
            .onEnter { directory ->
                if (directory != root && directory.name == ".git") return@onEnter false
                true
            }
            .forEach { file ->
                if (truncated) return@forEach
                if (file.isDirectory) {
                    if (file != root && file.name.lowercase() in generatedDirs) {
                        generated += file.relativeTo(root).invariantSeparatorsPath
                    }
                    return@forEach
                }
                files++
                bytes += file.length()
                if (files > MAX_INVENTORY_FILES || bytes > MAX_INVENTORY_BYTES) {
                    truncated = true
                    return@forEach
                }
                val relative = file.relativeTo(root).invariantSeparatorsPath
                val name = file.name.lowercase()
                if ((name in governanceNames || name.startsWith("todo")) && governance.size < MAX_GOVERNANCE_DOCS) {
                    governance += relative
                }
                if (name in dependencyNames || relative.startsWith(".github/workflows/")) {
                    dependencies += relative
                }
            }

        return JSONObject()
            .put("topLevel", top)
            .put("filesScanned", files)
            .put("bytesScanned", bytes)
            .put("truncated", truncated)
            .put("governanceDocuments", JSONArray(governance.distinct().sorted()))
            .put("dependencyAndBuildManifests", JSONArray(dependencies.distinct().sorted()))
            .put("generatedVendorBoundaries", JSONArray(generated.sorted()))
    }

    private fun evidenceSummary(rows: List<JSONObject>): JSONArray {
        val out = JSONArray()
        rows.sortedBy { it.optLong("importedAt") }.forEach { row ->
            out.put(JSONObject()
                .put("kind", row.optString("kind"))
                .put("complete", row.optBoolean("complete", false))
                .put("actor", row.optString("actor"))
                .put("importedAt", row.optLong("importedAt"))
                .put("payloadSha256", row.optString("payloadSha256"))
                .put("sourceSnapshotIdAtImport", row.optString("sourceSnapshotIdAtImport"))
                .put("candidateManifestSha256AtImport", row.optString("candidateManifestSha256AtImport")))
        }
        return out
    }

    private fun latestEvidenceByKind(rows: List<JSONObject>): Map<String, JSONObject> {
        val out = LinkedHashMap<String, JSONObject>()
        rows.sortedBy { it.optLong("importedAt") }.forEach { out[it.optString("kind")] = it }
        return out
    }

    private fun loadEvidence(context: Context, session: JSONObject): List<JSONObject> {
        val dir = File(sessionDirectory(context, session.getString("sessionId")), "evidence")
        return dir.listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.sortedBy { it.name }
            ?.mapNotNull { file -> runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull() }
            .orEmpty()
    }

    private fun projectTarget(context: Context, raw: String): ProjectTarget {
        val normalized = RiftVolumePaths.normalizeDisplay(raw)
        val relative = RiftVolumePaths.resolveRelative(normalized).trim('/')
        require(relative.startsWith("workspace/")) { "Lifecycle project must be under D:/Workspace" }
        require(relative != "workspace") { "Lifecycle project must name one workspace project" }
        val riftFs = File(context.applicationContext.filesDir, "riftfs").canonicalFile
        val workspace = File(riftFs, "workspace").apply { mkdirs() }.canonicalFile
        val target = File(riftFs, relative).canonicalFile
        require(target.path.startsWith(workspace.path + File.separator)) { "Lifecycle project escaped workspace" }
        require(target.isDirectory) { "Lifecycle project directory does not exist" }
        val display = "/D:/Workspace/" + relative.removePrefix("workspace/")
        return ProjectTarget(display, relative, target)
    }

    private fun evidenceInputFile(context: Context, raw: String): File {
        val normalized = RiftVolumePaths.normalizeDisplay(raw)
        val relative = RiftVolumePaths.resolveRelative(normalized).trim('/')
        require(relative.startsWith("documents/") || relative.startsWith("home/temp/")) {
            "Lifecycle evidence input must live under D:/Documents or D:/Temp, never inside Workspace"
        }
        val root = File(context.applicationContext.filesDir, "riftfs").canonicalFile
        val file = File(root, relative).canonicalFile
        require(file.path.startsWith(root.path + File.separator)) { "Evidence input escaped RiftFS" }
        require(file.isFile) { "Evidence input file does not exist" }
        return file
    }

    private fun sessionRoot(context: Context): File =
        File(context.applicationContext.filesDir, "riftfs/system/rift-cli-patch-lifecycle-v1")
            .apply { mkdirs() }
            .canonicalFile

    private fun legacySessionRoot(context: Context): File =
        File(context.applicationContext.filesDir, "rift-cli-patch-lifecycle-v1").canonicalFile

    private fun sessionDirectory(context: Context, sessionId: String): File {
        require(sessionId.matches(Regex("^lifecycle-[0-9]+-[A-Za-z0-9-]{6,20}$"))) { "Invalid lifecycle session id" }
        val root = sessionRoot(context)
        var dir = File(root, sessionId).canonicalFile
        require(dir.path.startsWith(root.path + File.separator)) { "Lifecycle session escaped private root" }
        if (!dir.isDirectory) {
            val legacyRoot = legacySessionRoot(context)
            val legacy = File(legacyRoot, sessionId).canonicalFile
            if (
                legacyRoot.exists() &&
                legacy.path.startsWith(legacyRoot.path + File.separator) &&
                legacy.isDirectory
            ) {
                val migrated = runCatching {
                    legacy.copyRecursively(dir, overwrite = false)
                    true
                }.getOrDefault(false)
                require(migrated && dir.isDirectory) {
                    "Lifecycle session migration from legacy storage failed: " + sessionId
                }
            }
        }
        require(dir.isDirectory) { "Lifecycle session not found: " + sessionId }
        return dir
    }

    private fun loadSession(context: Context, sessionId: String): JSONObject {
        val file = File(sessionDirectory(context, sessionId), "session.json")
        require(file.isFile && file.length() <= MAX_EVIDENCE_BYTES) {
            "Lifecycle session metadata is missing or oversized"
        }
        val session = JSONObject(file.readText(Charsets.UTF_8))
        require(session.optString("schema") == SESSION_SCHEMA) { "Lifecycle session schema mismatch" }
        require(session.optString("sessionId") == sessionId) { "Lifecycle session id mismatch" }
        observeSessionContinuity(context, file, session)
        return session
    }

    private fun observeSessionContinuity(context: Context, file: File, session: JSONObject) {
        val previousEpoch = session.optString("processEpoch")
        val current = currentProjectState(context, session)
        val candidate = currentCandidate(context)
        val currentSnapshot = current.optString("sourceSnapshotId")
        val currentManifest = candidate.optString("manifestSha256")
        val previousSnapshot = session.optString("lastObservedSourceSnapshotId")
        val previousManifest = session.optString("lastObservedCandidateManifestSha256")
        val processChanged = previousEpoch.isNotBlank() && previousEpoch != PROCESS_EPOCH

        if (
            processChanged &&
            (
                (previousSnapshot.isNotBlank() && previousSnapshot != currentSnapshot) ||
                (previousManifest.isNotBlank() && previousManifest != currentManifest)
            )
        ) {
            session.put("restartDriftDetected", true)
                .put("restartDrift", JSONObject()
                    .put("previousProcessEpoch", previousEpoch)
                    .put("currentProcessEpoch", PROCESS_EPOCH)
                    .put("previousSourceSnapshotId", previousSnapshot)
                    .put("currentSourceSnapshotId", currentSnapshot)
                    .put("previousManifestSha256", previousManifest)
                    .put("currentManifestSha256", currentManifest)
                    .put("detectedAt", System.currentTimeMillis())
                    .put("modified", current.optJSONArray("modified") ?: JSONArray())
                    .put("deleted", current.optJSONArray("deleted") ?: JSONArray())
                    .put("untracked", current.optJSONArray("untracked") ?: JSONArray()))
        }

        session.put("processEpoch", PROCESS_EPOCH)
            .put("lastObservedSourceSnapshotId", currentSnapshot)
            .put("lastObservedCandidateManifestSha256", currentManifest)
        writeJsonAtomic(file, session)
    }

    private fun standardsAlignment(): JSONArray = JSONArray(listOf(
        JSONObject()
            .put("name", "SLSA")
            .put("version", "1.2")
            .put("use", "Immutable source revision identity, source provenance/verification summaries, build provenance and continuous controls.")
            .put("uri", "https://slsa.dev/spec/v1.2/"),
        JSONObject()
            .put("name", "in-toto")
            .put("version", "Attestation Framework / stable specification")
            .put("use", "Bind claims/evidence to exact subjects rather than trusting unbound prose.")
            .put("uri", "https://in-toto.io/docs/specs/"),
        JSONObject()
            .put("name", "NIST SSDF")
            .put("version", "SP 800-218 v1.1 + v1.2 initial public draft")
            .put("use", "Lifecycle-integrated secure development, protection, verification and response practices.")
            .put("uri", "https://csrc.nist.gov/projects/ssdf")
    ))

    private fun stage(out: JSONArray, order: Int, id: String, owner: String, tasks: List<String>) {
        out.put(JSONObject()
            .put("order", order)
            .put("id", id)
            .put("owner", owner)
            .put("tasks", JSONArray(tasks)))
    }

    private fun issue(code: String, message: String, evidenceKind: String? = null): JSONObject =
        JSONObject()
            .put("code", code)
            .put("message", message)
            .put("evidenceKind", evidenceKind ?: JSONObject.NULL)

    private fun rejectSensitiveKeys(value: Any?) {
        when (value) {
            is JSONObject -> {
                val keys = value.keys().asSequence().toList()
                for (key in keys) {
                    val normalized = key.lowercase().replace(Regex("[^a-z0-9]"), "")
                    require(
                        normalized !in setOf(
                            "password", "passwd", "token", "accesstoken", "refreshtoken",
                            "authorization", "cookie", "privatekey", "secret", "apikey"
                        )
                    ) { "Evidence payload must not contain secret-bearing field: " + key }
                    rejectSensitiveKeys(value.get(key))
                }
            }
            is JSONArray -> for (index in 0 until value.length()) rejectSensitiveKeys(value.get(index))
        }
    }

    private fun writeJsonAtomic(file: File, value: JSONObject) {
        file.parentFile?.mkdirs()
        val tmp = File(
            file.parentFile,
            "." + file.name + "." + UUID.randomUUID().toString() + ".tmp"
        )
        tmp.writeText(value.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IllegalStateException("Could not publish lifecycle metadata: " + file.name)
        }
    }
}
