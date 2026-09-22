package com.riftos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * N1.8 Repository Consistency Observer foundation.
 *
 * This subsystem is deliberately derived from Project Intelligence V2 output. It does not scan
 * source files or maintain a competing symbol/dependency index. The persisted graph is a bounded,
 * rebuildable cache only; repository files and Workspace Records remain the source of truth.
 */
internal class RiftRepositoryConsistencyObserver(context: Context) {
    companion object {
        const val VERSION = 2
        const val FORMAT = "rift-repository-fact-graph-v2"
        const val PHASE = "N1.8.0"

        const val MAX_FACTS = 4_096
        const val MAX_EDGES = 4_096
        const val MAX_FINDINGS = 1_024
        const val MAX_CACHE_BYTES = 4 * 1024 * 1024
        const val MAX_CACHE_FILES = 32
        const val MAX_STABLE_KEY_CHARS = 2_048

        private const val FACT_ID_PREFIX = "fact-"
        private const val EDGE_ID_PREFIX = "edge-"
        private const val FINDING_ID_PREFIX = "finding-"
        private const val GRAPH_ID_PREFIX = "graph-"
    }

    private val appContext = context.applicationContext
    private val cacheRoot = File(appContext.filesDir, "rift-repository-consistency-v2").apply { mkdirs() }

    fun foundationView(projectRoot: String, projectGraph: JSONObject): JSONObject {
        require(projectGraph.optString("projectIntelligence") == "v2") {
            "Repository Consistency Observer requires Project Intelligence V2 evidence"
        }
        require(projectGraph.optString("view") == "graph") {
            "Repository Consistency Observer requires a PI-v2 graph view"
        }

        val normalizedRoot = projectRoot.trim().trimEnd('/')
        require(normalizedRoot.isNotBlank()) { "Repository consistency project root is required" }

        val facts = linkedMapOf<String, JSONObject>()
        val edges = linkedMapOf<String, JSONObject>()
        val findings = linkedMapOf<String, JSONObject>()
        val incompleteReasons = linkedSetOf<String>()

        fun addFact(
            kind: String,
            stableKey: String,
            path: String? = null,
            line: Int? = null,
            evidenceType: String = "project-intelligence-v2",
            attributes: JSONObject = JSONObject()
        ): String {
            if (facts.size >= MAX_FACTS) {
                incompleteReasons += "fact-bound"
                return stableFactId(kind, stableKey)
            }
            val id = stableFactId(kind, stableKey)
            if (facts.containsKey(id)) return id
            val body = JSONObject()
                .put("kind", kind)
                .put("stableKey", boundedStableKey(stableKey))
                .put("path", path ?: JSONObject.NULL)
                .put("line", line ?: JSONObject.NULL)
                .put("evidenceType", evidenceType)
                .put("provenance", "project-intelligence-v2")
                .put("attributes", attributes)
            val row = JSONObject(body.toString())
                .put("id", id)
                .put("contentSha256", RiftPatchManifestV1.sha256Canonical(body))
            facts[id] = row
            return id
        }

        fun addEdge(
            relation: String,
            sourceFactId: String,
            targetFactId: String,
            stableKey: String,
            evidenceType: String = "project-intelligence-v2",
            attributes: JSONObject = JSONObject()
        ): String {
            if (edges.size >= MAX_EDGES) {
                incompleteReasons += "edge-bound"
                return stableEdgeId(relation, sourceFactId, targetFactId, stableKey)
            }
            val id = stableEdgeId(relation, sourceFactId, targetFactId, stableKey)
            if (edges.containsKey(id)) return id
            val body = JSONObject()
                .put("relation", relation)
                .put("sourceFactId", sourceFactId)
                .put("targetFactId", targetFactId)
                .put("stableKey", boundedStableKey(stableKey))
                .put("evidenceType", evidenceType)
                .put("provenance", "project-intelligence-v2")
                .put("attributes", attributes)
            val row = JSONObject(body.toString())
                .put("id", id)
                .put("contentSha256", RiftPatchManifestV1.sha256Canonical(body))
            edges[id] = row
            return id
        }

        val projectFactId = addFact(
            kind = "project",
            stableKey = normalizedRoot,
            path = normalizedRoot,
            attributes = JSONObject()
                .put("projectIntelligence", "v2")
                .put("observerPhase", PHASE)
        )

        val fileEvidenceRows = projectGraph.optJSONArray("repositoryFileEvidence") ?: JSONArray()
        val repositoryFilesMatched = projectGraph.optInt("repositoryFilesMatched", fileEvidenceRows.length())
        val repositoryFilesTruncated = projectGraph.optBoolean(
            "repositoryFilesTruncated",
            repositoryFilesMatched > fileEvidenceRows.length()
        )
        val edgesTruncated = projectGraph.optBoolean("edgesTruncated", false)

        if (repositoryFilesTruncated || repositoryFilesMatched > fileEvidenceRows.length()) {
            incompleteReasons += "pi-v2-file-bound"
        }
        if (edgesTruncated) incompleteReasons += "pi-v2-edge-bound"
        if (!projectGraph.optBoolean("repositoryContentVerified", false)) {
            incompleteReasons += "repository-content-unverified"
        }
        if (!projectGraph.optBoolean("repositoryEvidenceComplete", false)) {
            incompleteReasons += "repository-evidence-incomplete"
        }
        if (!projectGraph.optBoolean("semanticEvidenceComplete", false)) {
            incompleteReasons += "semantic-evidence-incomplete"
        }

        val upstreamIncomplete = projectGraph.optJSONArray("repositoryEvidenceIncompleteReasons") ?: JSONArray()
        for (index in 0 until upstreamIncomplete.length()) {
            upstreamIncomplete.optString(index).trim().takeIf { it.isNotBlank() }?.let(incompleteReasons::add)
        }

        val fileAttributes = linkedMapOf<String, JSONObject>()
        for (index in 0 until fileEvidenceRows.length()) {
            val row = fileEvidenceRows.optJSONObject(index) ?: continue
            val path = row.optString("path").trim()
            if (path.isBlank()) continue
            val sha = row.opt("sha256")
                ?.takeUnless { it == JSONObject.NULL }
                ?.toString()
                ?.trim()
                .orEmpty()
            val size = row.optLong("size", -1L)
            val semanticStatus = row.optString("semanticStatus").trim().ifBlank { "unavailable" }
            val semanticReason = row.opt("semanticReason")
                ?.takeUnless { it == JSONObject.NULL }
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotBlank() }

            if (!sha.matches(Regex("^[0-9a-f]{64}$"))) {
                incompleteReasons += "repository-file-content-unavailable"
            }
            if (size < 0L) incompleteReasons += "repository-file-size-unavailable"

            val attributes = JSONObject()
                .put("size", size)
                .put("sha256", if (sha.isBlank()) JSONObject.NULL else sha)
                .put("semanticStatus", semanticStatus)
                .put("semanticReason", semanticReason ?: JSONObject.NULL)
            fileAttributes[path] = attributes

            val fileFactId = addFact(
                kind = "file",
                stableKey = path,
                path = path,
                evidenceType = "project-intelligence-v2-file-evidence",
                attributes = attributes
            )
            addEdge(
                relation = "contains",
                sourceFactId = projectFactId,
                targetFactId = fileFactId,
                stableKey = path,
                evidenceType = "project-intelligence-v2-file-evidence"
            )
        }

        if (fileEvidenceRows.length() == 0 && (projectGraph.optJSONArray("matchedFiles")?.length() ?: 0) > 0) {
            incompleteReasons += "repository-file-evidence-missing"
            val matchedFiles = projectGraph.optJSONArray("matchedFiles") ?: JSONArray()
            for (index in 0 until matchedFiles.length()) {
                val path = matchedFiles.optString(index).trim()
                if (path.isBlank()) continue
                val fileFactId = addFact("file", path, path = path)
                addEdge(
                    relation = "contains",
                    sourceFactId = projectFactId,
                    targetFactId = fileFactId,
                    stableKey = path
                )
            }
        }

        fun fileFactId(path: String): String {
            val attributes = fileAttributes[path]
            if (attributes == null) {
                incompleteReasons += "repository-file-evidence-missing"
                return addFact("file", path, path = path)
            }
            return addFact(
                kind = "file",
                stableKey = path,
                path = path,
                evidenceType = "project-intelligence-v2-file-evidence",
                attributes = attributes
            )
        }

        val dependencyRows = projectGraph.optJSONArray("edges") ?: JSONArray()
        for (index in 0 until dependencyRows.length()) {
            if (facts.size >= MAX_FACTS || edges.size >= MAX_EDGES) {
                if (facts.size >= MAX_FACTS) incompleteReasons += "fact-bound"
                if (edges.size >= MAX_EDGES) incompleteReasons += "edge-bound"
                break
            }

            val dependency = dependencyRows.optJSONObject(index) ?: continue
            val sourcePath = dependency.optString("source").trim()
            val kind = dependency.optString("kind").trim().ifBlank { "dependency" }
            val specifier = dependency.optString("specifier").trim()
            val line = dependency.optInt("line", 0).takeIf { it > 0 }
            val targetPath = dependency.opt("target")
                ?.takeUnless { it == JSONObject.NULL }
                ?.toString()
                ?.trim()
                .orEmpty()

            if (sourcePath.isBlank() || specifier.isBlank()) continue

            val sourceFactId = fileFactId(sourcePath)
            addEdge(
                relation = "contains",
                sourceFactId = projectFactId,
                targetFactId = sourceFactId,
                stableKey = sourcePath
            )

            val dependencyKey = listOf(
                sourcePath,
                kind,
                line?.toString().orEmpty(),
                specifier
            ).joinToString("|")
            val dependencyFactId = addFact(
                kind = "dependency-specifier",
                stableKey = dependencyKey,
                path = sourcePath,
                line = line,
                attributes = JSONObject()
                    .put("dependencyKind", kind)
                    .put("specifier", specifier)
            )

            addEdge(
                relation = "declares-dependency",
                sourceFactId = sourceFactId,
                targetFactId = dependencyFactId,
                stableKey = dependencyKey,
                attributes = JSONObject()
                    .put("dependencyKind", kind)
                    .put("specifier", specifier)
            )

            if (targetPath.isNotBlank()) {
                val targetFactId = fileFactId(targetPath)
                addEdge(
                    relation = "resolves-to",
                    sourceFactId = dependencyFactId,
                    targetFactId = targetFactId,
                    stableKey = dependencyKey,
                    attributes = JSONObject()
                        .put("dependencyKind", kind)
                        .put("specifier", specifier)
                )
            }
        }

        if (findings.size > MAX_FINDINGS) incompleteReasons += "finding-bound"

        val factRows = JSONArray()
        facts.toSortedMap().values.forEach { factRows.put(it) }
        val edgeRows = JSONArray()
        edges.toSortedMap().values.forEach { edgeRows.put(it) }
        val findingRows = JSONArray()
        findings.toSortedMap().values.forEach { findingRows.put(it) }

        val graphPayload = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("phase", PHASE)
            .put("projectRoot", normalizedRoot)
            .put("projectIntelligence", "v2")
            .put("sourceOfTruth", "project-intelligence-v2-content-verified")
            .put("authoritative", false)
            .put("rebuildableCache", true)
            .put("complete", incompleteReasons.isEmpty())
            .put("incompleteReasons", JSONArray(incompleteReasons.sorted()))
            .put("facts", factRows)
            .put("edges", edgeRows)
            .put("findings", findingRows)

        val graphSha256 = RiftPatchManifestV1.sha256Canonical(graphPayload)
        val snapshot = JSONObject(graphPayload.toString())
            .put("graphSha256", graphSha256)
            .put("graphId", "$GRAPH_ID_PREFIX${graphSha256.take(24)}")

        val cacheState = persistSnapshot(normalizedRoot, snapshot)
        return JSONObject(snapshot.toString())
            .put("schema", schemaSummary())
            .put("bounds", boundsSummary())
            .put("cache", cacheState)
            .put("counts", JSONObject()
                .put("facts", factRows.length())
                .put("edges", edgeRows.length())
                .put("findings", findingRows.length())
                .put("repositoryFiles", fileEvidenceRows.length()))
    }

    fun schemaSummary(): JSONObject = JSONObject()
        .put("format", FORMAT)
        .put("version", VERSION)
        .put("phase", PHASE)
        .put("identity", JSONObject()
            .put("fact", "{kind,stableKey}")
            .put("edge", "{relation,sourceFactId,targetFactId,stableKey}")
            .put("finding", "{ruleId,category,sourceFactId,conflictFactId,stableKey}")
            .put("contentHashSeparateFromIdentity", true)
            .put("fileContentBound", true))
        .put("requiredFields", JSONObject()
            .put("fact", JSONArray(listOf(
                "id", "kind", "stableKey", "path", "line", "evidenceType",
                "provenance", "attributes", "contentSha256"
            )))
            .put("edge", JSONArray(listOf(
                "id", "relation", "sourceFactId", "targetFactId", "stableKey",
                "evidenceType", "provenance", "attributes", "contentSha256"
            )))
            .put("finding", JSONArray(listOf(
                "id", "ruleId", "category", "severity", "deterministic",
                "sourceFactId", "conflictFactId", "stableKey", "graphPath",
                "confidence", "proofSource", "remediationClass", "blocksPromotion",
                "evidenceIncomplete", "contentSha256"
            ))))
        .put("factKindsFoundation", JSONArray(listOf(
            "project",
            "file",
            "dependency-specifier"
        )))
        .put("edgeRelationsFoundation", JSONArray(listOf(
            "contains",
            "declares-dependency",
            "resolves-to"
        )))
        .put("findingSeverities", JSONArray(listOf(
            "critical",
            "error",
            "warning",
            "info"
        )))
        .put("evidencePrecedence", JSONArray(listOf(
            "parser-compiler-build-runtime",
            "manifest-config-schema",
            "symbol-reference-dependency",
            "deterministic-repository-rule",
            "workspace-history",
            "ownership-contract",
            "deterministic-doc-claim",
            "heuristic-structural-link",
            "semantic-model-suggestion"
        )))
        .put("inferenceMayBlockPromotion", false)

    fun boundsSummary(): JSONObject = JSONObject()
        .put("maxFacts", MAX_FACTS)
        .put("maxEdges", MAX_EDGES)
        .put("maxFindings", MAX_FINDINGS)
        .put("maxCacheBytes", MAX_CACHE_BYTES)
        .put("maxCacheFiles", MAX_CACHE_FILES)
        .put("maxStableKeyChars", MAX_STABLE_KEY_CHARS)

    private fun stableFactId(kind: String, stableKey: String): String {
        val identity = JSONObject()
            .put("schema", VERSION)
            .put("kind", kind)
            .put("stableKey", boundedStableKey(stableKey))
        return "$FACT_ID_PREFIX${RiftPatchManifestV1.sha256Canonical(identity).take(32)}"
    }

    private fun stableEdgeId(
        relation: String,
        sourceFactId: String,
        targetFactId: String,
        stableKey: String
    ): String {
        val identity = JSONObject()
            .put("schema", VERSION)
            .put("relation", relation)
            .put("sourceFactId", sourceFactId)
            .put("targetFactId", targetFactId)
            .put("stableKey", boundedStableKey(stableKey))
        return "$EDGE_ID_PREFIX${RiftPatchManifestV1.sha256Canonical(identity).take(32)}"
    }

    @Suppress("unused")
    private fun findingRecord(
        ruleId: String,
        category: String,
        severity: String,
        deterministic: Boolean,
        sourceFactId: String,
        conflictFactId: String?,
        stableKey: String,
        graphPath: JSONArray,
        confidence: Double,
        proofSource: String,
        remediationClass: String,
        blocksPromotion: Boolean,
        evidenceIncomplete: Boolean
    ): JSONObject {
        require(severity in setOf("critical", "error", "warning", "info")) {
            "Unsupported repository consistency finding severity: $severity"
        }
        require(confidence.isFinite() && confidence in 0.0..1.0) {
            "Repository consistency finding confidence must be between 0 and 1"
        }
        val body = JSONObject()
            .put("ruleId", ruleId)
            .put("category", category)
            .put("severity", severity)
            .put("deterministic", deterministic)
            .put("sourceFactId", sourceFactId)
            .put("conflictFactId", conflictFactId ?: JSONObject.NULL)
            .put("stableKey", boundedStableKey(stableKey))
            .put("graphPath", graphPath)
            .put("confidence", confidence)
            .put("proofSource", proofSource)
            .put("remediationClass", remediationClass)
            .put("blocksPromotion", blocksPromotion)
            .put("evidenceIncomplete", evidenceIncomplete)
        val id = stableFindingId(ruleId, category, sourceFactId, conflictFactId, stableKey)
        return JSONObject(body.toString())
            .put("id", id)
            .put("contentSha256", RiftPatchManifestV1.sha256Canonical(body))
    }

    @Suppress("unused")
    private fun stableFindingId(
        ruleId: String,
        category: String,
        sourceFactId: String,
        conflictFactId: String?,
        stableKey: String
    ): String {
        val identity = JSONObject()
            .put("schema", VERSION)
            .put("ruleId", ruleId)
            .put("category", category)
            .put("sourceFactId", sourceFactId)
            .put("conflictFactId", conflictFactId ?: JSONObject.NULL)
            .put("stableKey", boundedStableKey(stableKey))
        return "$FINDING_ID_PREFIX${RiftPatchManifestV1.sha256Canonical(identity).take(32)}"
    }

    private fun boundedStableKey(value: String): String {
        val normalized = value.trim()
        require(normalized.isNotBlank()) { "Repository consistency stable key must not be blank" }
        require(normalized.length <= MAX_STABLE_KEY_CHARS) {
            "Repository consistency stable key exceeds $MAX_STABLE_KEY_CHARS characters"
        }
        return normalized
    }

    private fun persistSnapshot(projectRoot: String, snapshot: JSONObject): JSONObject {
        pruneCache()
        val rootIdentity = JSONObject()
            .put("format", FORMAT)
            .put("projectRoot", projectRoot)
        val fileName = "project-${RiftPatchManifestV1.sha256Canonical(rootIdentity).take(24)}.json"
        val target = File(cacheRoot, fileName)

        val previousSha = readVerifiedSnapshot(target)?.optString("graphSha256")
            ?.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }

        val payload = snapshot.toString()
        val bytes = payload.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_CACHE_BYTES) {
            "Repository consistency snapshot exceeds $MAX_CACHE_BYTES bytes"
        }

        val temporary = File(cacheRoot, ".tmp-${UUID.randomUUID()}.json")
        try {
            temporary.outputStream().buffered().use { stream ->
                stream.write(bytes)
                stream.flush()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: Throwable) {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }

        val verified = readVerifiedSnapshot(target)
            ?: throw IllegalStateException("Repository consistency cache verification failed after write")
        val currentSha = verified.getString("graphSha256")

        return JSONObject()
            .put("privateAppCache", true)
            .put("authoritative", false)
            .put("rebuildable", true)
            .put("file", fileName)
            .put("verified", true)
            .put("graphSha256", currentSha)
            .put("previousGraphSha256", previousSha ?: JSONObject.NULL)
            .put("changed", previousSha == null || previousSha != currentSha)
    }

    private fun readVerifiedSnapshot(file: File): JSONObject? {
        if (!file.isFile || file.length() <= 0L || file.length() > MAX_CACHE_BYTES) return null
        val snapshot = runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull() ?: return null
        if (snapshot.optString("format") != FORMAT || snapshot.optInt("version", -1) != VERSION) return null
        val expected = snapshot.optString("graphSha256")
        if (!expected.matches(Regex("^[0-9a-f]{64}$"))) return null

        val body = JSONObject()
        val keys = snapshot.keys().asSequence().toList().sorted()
        for (key in keys) {
            if (key == "graphSha256" || key == "graphId") continue
            body.put(key, snapshot.get(key))
        }
        return snapshot.takeIf { RiftPatchManifestV1.sha256Canonical(body) == expected }
    }

    private fun pruneCache() {
        val files = cacheRoot.listFiles()
            ?.filter { it.isFile && it.extension == "json" && !it.name.startsWith(".tmp-") }
            ?.sortedWith(compareBy<File>({ it.lastModified() }, { it.name }))
            .orEmpty()
        val removeCount = (files.size - MAX_CACHE_FILES + 1).coerceAtLeast(0)
        files.take(removeCount).forEach { it.delete() }

        cacheRoot.listFiles()
            ?.filter { it.isFile && it.name.startsWith(".tmp-") }
            ?.forEach { it.delete() }
    }
}
