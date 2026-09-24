package com.riftos.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** N1.8.4 read-only documentation/ROADMAP/TODO claim oracle. */
internal class RiftDocumentationClaimsV1(private val workspaceRoot: File) {
    companion object {
        const val SCHEMA = "rift-documentation-claims-v1"
        const val PHASE = "N1.8.4"

        private const val MAX_FILES = 4_096
        private const val MAX_FILE_BYTES = 2L * 1024L * 1024L
        private const val MAX_TOTAL_BYTES = 64L * 1024L * 1024L
        private const val MAX_CLAIMS = 8_192
        private const val MAX_FINDINGS = 1_024
        private const val MAX_PREVIEW_ROWS = 240

        private val IGNORED_DIRS = setOf(
            ".git", ".gradle", ".idea", "build", "dist", "node_modules", "out", "target",
            "vendor", "venv", ".venv", "__pycache__", ".cache", "coverage"
        )
        private val TEXT_EXTENSIONS = setOf(
            "md", "mdx", "rst", "adoc", "txt", "kt", "java", "kts", "gradle", "xml",
            "cpp", "cc", "cxx", "c", "h", "hpp", "js", "mjs", "json", "jsonc",
            "properties", "css", "html", "yml", "yaml", "toml"
        )
        private val REQUIRED_CURRENT_DOCS = listOf(
            "README.md", "ROADMAP.md", "docs/README.md", "docs/PROJECT_STATUS.md",
            "docs/SOURCE_OWNERSHIP.md",
            "docs/systems/workspace/REPOSITORY_CONSISTENCY_OBSERVER.md"
        )

        private data class PhaseAuthority(
            val phase: String,
            val status: String,
            val sourceSha: String? = null,
            val runNumber: String? = null
        )

        private val PHASE_AUTHORITY = listOf(
            PhaseAuthority("N1.8.0", "promoted", "9d196567e38e781d97a24bb2c808b47cbc2303eb"),
            PhaseAuthority("N1.8.1", "promoted", "198a3f31e22a5d385378fee087aa5f115aed6d5a"),
            PhaseAuthority("N1.8.2", "promoted", "9cc74b25c94fd3e23e93f64d3d132e65e63fe3a6", "317"),
            PhaseAuthority("N1.8.3", "promoted", "e6de353ead6e9377e36e1602e301e3e8231a5e43", "322"),
            PhaseAuthority("N1.8.4", "source-implemented"),
            PhaseAuthority("N1.8.5", "pending"),
            PhaseAuthority("N1.8.6", "pending"),
            PhaseAuthority("N1.8.7", "pending")
        )

        private const val ROADMAP_STATUS =
            "N1.8.0 + N1.8.1 + N1.8.2 + N1.8.3 PROMOTED; N1.8.4 ACTIVE; N1.8.5-N1.8.7 PENDING"
        private const val OBSERVER_STATUS =
            "N1.8.0 + N1.8.1 + N1.8.2 + N1.8.3 PROMOTED ON INSTALLED ARM32-COMPATIBLE ANDROID TARGET; N1.8.4 SOURCE-IMPLEMENTED / PROMOTION PENDING; N1.8.5+ PENDING"
    }

    private data class TextFile(
        val file: File,
        val path: String,
        val text: String,
        val documentation: Boolean,
        val historical: Boolean
    )

    private data class Claim(
        val kind: String,
        val state: String,
        val path: String,
        val line: Int,
        val subject: String,
        val authority: String,
        val expected: String? = null,
        val observed: String? = null
    )

    fun analyze(projectRoot: File): JSONObject {
        val root = projectRoot.canonicalFile
        val workspace = workspaceRoot.canonicalFile
        require(root.isDirectory) { "Project root must be a directory" }
        require(root.toPath().startsWith(workspace.toPath())) { "Project root escaped workspace" }

        val incomplete = linkedSetOf<String>()
        val files = mutableListOf<TextFile>()
        var totalBytes = 0L

        fun visit(directory: File) {
            val children = directory.listFiles()
                ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                ?: run {
                    incomplete += "claims-read-failure"
                    return
                }
            for (child in children) {
                if (child.isDirectory) {
                    if (child.name !in IGNORED_DIRS) visit(child)
                    continue
                }
                if (!isTextCandidate(child)) continue
                if (files.size >= MAX_FILES) {
                    incomplete += "claims-file-bound"
                    continue
                }
                val size = child.length()
                if (size > MAX_FILE_BYTES) {
                    incomplete += "claims-file-size-bound"
                    continue
                }
                if (totalBytes > MAX_TOTAL_BYTES - size) {
                    incomplete += "claims-byte-bound"
                    continue
                }
                val text = runCatching { child.readText(Charsets.UTF_8) }.getOrElse {
                    incomplete += "claims-read-failure"
                    continue
                }
                val path = relative(root, child)
                files += TextFile(
                    child,
                    path,
                    text,
                    isDocumentationPath(path),
                    isHistoricalDocumentation(path)
                )
                totalBytes += size
            }
        }
        visit(root)

        val claims = mutableListOf<Claim>()
        fun add(claim: Claim) {
            if (claims.size >= MAX_CLAIMS) incomplete += "claims-claim-bound"
            else claims += claim
        }

        checkRequiredDocs(root, ::add)
        checkRelativeLinks(root, files, ::add)
        checkOwnership(root, files, ::add)
        checkAuthorityPolicy(files, ::add)
        checkPhaseState(files, ::add)
        checkTodoLifecycle(files, ::add)
        classifyHistorical(files, ::add)

        val ordered = claims.sortedWith(compareBy({ it.path }, { it.line }, { it.kind }, { it.subject }, { it.state }))
        val badStates = setOf("contradicted", "stale", "orphaned")
        val initiallyIncomplete = incomplete.isNotEmpty()
        val bad = if (initiallyIncomplete) emptyList() else ordered.filter { it.state in badStates }
        if (bad.size > MAX_FINDINGS) incomplete += "claims-finding-bound"
        val findings = if (incomplete.isEmpty()) bad.take(MAX_FINDINGS) else emptyList()

        val shaInput = buildString {
            append(SCHEMA).append('\n')
            PHASE_AUTHORITY.forEach {
                append("authority|").append(it.phase).append('|').append(it.status).append('|')
                    .append(it.sourceSha.orEmpty()).append('|').append(it.runNumber.orEmpty()).append('\n')
            }
            incomplete.sorted().forEach { append("incomplete|").append(it).append('\n') }
            ordered.forEach {
                append("claim|").append(it.kind).append('|').append(it.state).append('|')
                    .append(it.path).append('|').append(it.line).append('|')
                    .append(it.subject).append('|').append(it.authority).append('|')
                    .append(it.expected.orEmpty()).append('|').append(it.observed.orEmpty()).append('\n')
            }
        }

        val claimRows = JSONArray()
        ordered.take(MAX_PREVIEW_ROWS).forEach { claimRows.put(claimJson(it)) }
        val findingRows = JSONArray()
        findings.take(MAX_PREVIEW_ROWS).forEach { claim ->
            findingRows.put(JSONObject()
                .put("ruleId", ruleIdFor(claim))
                .put("category", "documentation-claim")
                .put("severity", "error")
                .put("path", claim.path)
                .put("line", claim.line)
                .put("state", claim.state)
                .put("subject", claim.subject)
                .put("authority", claim.authority)
                .put("expected", claim.expected ?: JSONObject.NULL)
                .put("observed", claim.observed ?: JSONObject.NULL))
        }

        fun count(state: String): Int = ordered.count { it.state == state }

        return JSONObject()
            .put("schema", SCHEMA)
            .put("phase", PHASE)
            .put("view", "claims")
            .put("authority", "source-build-runtime-over-documentation")
            .put("projectRoot", relative(workspace, root))
            .put("complete", incomplete.isEmpty())
            .put("clean", incomplete.isEmpty() && bad.isEmpty())
            .put("incompleteReasons", JSONArray(incomplete.sorted()))
            .put("partialFindingsSuppressed", incomplete.isNotEmpty())
            .put("freeFormProseInference", false)
            .put("claimsSha256", sha256(shaInput))
            .put("authorityModel", JSONObject()
                .put("implementation", "source/manifests/build-config/machine-readable-repository-state")
                .put("buildInstallPromotion", "exact-builder-artifacts-and-installed-runtime-evidence")
                .put("documentation", "non-authoritative-claim-surface")
                .put("direction", "authority->claims")
                .put("documentationMayOverrideAuthority", false))
            .put("coverage", JSONArray(listOf(
                "required-current-document-existence",
                "relative-markdown-link-targets",
                "source-ownership-ledger-existence-and-coverage",
                "documentation-authority-policy",
                "n1.8-roadmap-current-state",
                "n1.8-project-status-promoted-source-bindings",
                "canonical-observer-current-state",
                "structured-todo-fixme-discovery",
                "historical-document-classification"
            )))
            .put("counts", JSONObject()
                .put("filesScanned", files.size)
                .put("bytesScanned", totalBytes)
                .put("documentationFiles", files.count { it.documentation })
                .put("historicalDocuments", files.count { it.documentation && it.historical })
                .put("claims", ordered.size)
                .put("verified", count("verified"))
                .put("contradicted", count("contradicted"))
                .put("stale", count("stale"))
                .put("orphaned", count("orphaned"))
                .put("unverified", count("unverified"))
                .put("superseded", count("superseded"))
                .put("heuristicLink", count("heuristic-link"))
                .put("findings", findings.size))
            .put("bounds", JSONObject()
                .put("maxFiles", MAX_FILES)
                .put("maxFileBytes", MAX_FILE_BYTES)
                .put("maxTotalBytes", MAX_TOTAL_BYTES)
                .put("maxClaims", MAX_CLAIMS)
                .put("maxFindings", MAX_FINDINGS)
                .put("maxPreviewRows", MAX_PREVIEW_ROWS))
            .put("preview", JSONObject()
                .put("claimRows", claimRows.length())
                .put("findingRows", findingRows.length())
                .put("claimsTruncated", ordered.size > MAX_PREVIEW_ROWS)
                .put("findingsTruncated", findings.size > MAX_PREVIEW_ROWS))
            .put("phaseAuthority", JSONArray().also { array ->
                PHASE_AUTHORITY.forEach { phase ->
                    array.put(JSONObject()
                        .put("phase", phase.phase)
                        .put("status", phase.status)
                        .put("promotedSourceSha", phase.sourceSha ?: JSONObject.NULL)
                        .put("builderRunNumber", phase.runNumber ?: JSONObject.NULL))
                }
            })
            .put("claims", claimRows)
            .put("findings", findingRows)
    }

    private fun checkRequiredDocs(root: File, add: (Claim) -> Unit) {
        REQUIRED_CURRENT_DOCS.forEach { path ->
            val exists = File(root, path).isFile
            add(Claim(
                "required-document-existence",
                if (exists) "verified" else "contradicted",
                path, 0, path, "repository-filesystem",
                "file-present", if (exists) "file-present" else "missing"
            ))
        }
    }

    private fun checkRelativeLinks(root: File, files: List<TextFile>, add: (Claim) -> Unit) {
        val pattern = Regex("""\[[^]]*]\(([^)]+)\)""")
        files.asSequence()
            .filter { it.documentation && !it.historical && it.path.endsWith(".md", ignoreCase = true) }
            .forEach docLoop@ { doc ->
                pattern.findAll(doc.text).forEach linkLoop@ { match ->
                    val href = match.groupValues[1].trim()
                    if (href.isBlank() || href.startsWith("#") ||
                        Regex("""^(?:https?:|mailto:|tel:)""", RegexOption.IGNORE_CASE).containsMatchIn(href)
                    ) return@linkLoop
                    val targetText = href.substringBefore('#')
                    if (targetText.isBlank()) return@linkLoop
                    val target = File(doc.file.parentFile, targetText).canonicalFile
                    val inside = target.toPath().startsWith(root.canonicalFile.toPath())
                    val exists = inside && target.exists()
                    add(Claim(
                        "relative-markdown-link",
                        if (exists) "verified" else "contradicted",
                        doc.path, lineAt(doc.text, match.range.first), href, "repository-filesystem",
                        "target-present-inside-project",
                        when {
                            !inside -> "target-escapes-project"
                            exists -> "target-present"
                            else -> "target-missing"
                        }
                    ))
                }
            }
    }

    private fun checkOwnership(root: File, files: List<TextFile>, add: (Claim) -> Unit) {
        val ledger = files.firstOrNull { it.path == "docs/SOURCE_OWNERSHIP.md" } ?: return
        val sourceOwners = linkedMapOf<String, List<String>>()
        val rowPattern = Regex("""(?m)^\| `([^`]+)` \| (.+) \|\s*$""")
        rowPattern.findAll(ledger.text).forEach rowLoop@ { match ->
            val source = match.groupValues[1]
            if (source == "Source file") return@rowLoop
            val owners = Regex("""`((?:docs/[^`]+|README)\.md)`""")
                .findAll(match.groupValues[2])
                .map { it.groupValues[1] }
                .distinct().sorted().toList()
            sourceOwners[source] = owners
            val sourceExists = File(root, source).isFile
            add(Claim(
                "ownership-source-existence",
                if (sourceExists) "verified" else "orphaned",
                ledger.path, lineAt(ledger.text, match.range.first), source, "repository-filesystem",
                "owned-source-present", if (sourceExists) "present" else "missing"
            ))
            owners.forEach { owner ->
                val ownerExists = File(root, owner).isFile
                add(Claim(
                    "ownership-document-existence",
                    if (ownerExists) "verified" else "contradicted",
                    ledger.path, lineAt(ledger.text, match.range.first), "$source -> $owner",
                    "repository-filesystem", "owner-document-present",
                    if (ownerExists) "present" else "missing"
                ))
            }
        }
        maintainedSources(root).forEach { source ->
            val covered = source in sourceOwners
            add(Claim(
                "ownership-ledger-coverage",
                if (covered) "verified" else "contradicted",
                ledger.path, 0, source, "maintained-source-discovery",
                "ownership-entry-present", if (covered) "present" else "missing"
            ))
        }
    }

    private fun checkAuthorityPolicy(files: List<TextFile>, add: (Claim) -> Unit) {
        val required = listOf(
            Triple(
                "docs/README.md",
                "Documentation is **UNVERIFIED by default**",
                "documentation-trust-policy"
            ),
            Triple(
                "docs/SOURCE_OWNERSHIP.md",
                "Ownership does **not** imply that a source is packaged, live, verified, trusted or device-proven.",
                "ownership-trust-policy"
            ),
            Triple(
                "docs/systems/workspace/REPOSITORY_CONSISTENCY_OBSERVER.md",
                "documentation agreement cannot prove",
                "observer-authority-policy"
            )
        )
        required.forEach { (path, marker, subject) ->
            val file = files.firstOrNull { it.path == path && !it.historical }
            val present = file?.text?.contains(marker, ignoreCase = true) == true
            add(Claim(
                "documentation-authority-policy",
                if (present) "verified" else "contradicted",
                path,
                file?.text?.lineSequence()?.indexOfFirst { it.contains(marker, ignoreCase = true) }?.let { if (it >= 0) it + 1 else 0 } ?: 0,
                subject,
                "source-authority-policy",
                marker,
                if (present) "present" else "missing"
            ))
        }
    }

    private fun checkPhaseState(files: List<TextFile>, add: (Claim) -> Unit) {
        files.firstOrNull { it.path == "ROADMAP.md" && !it.historical }?.let { roadmap ->
            val observed = roadmap.text.lineSequence()
                .firstOrNull { it.contains("N1.8 Repository Consistency Observer") }
                ?.trim()
            add(Claim(
                "roadmap-phase-state",
                if (roadmap.text.contains(ROADMAP_STATUS)) "verified" else "stale",
                roadmap.path,
                roadmap.text.lineSequence().indexOfFirst { it.contains("N1.8 Repository Consistency Observer") } + 1,
                "N1.8 phase state", "source-phase-registry", ROADMAP_STATUS, observed ?: "missing"
            ))
        }

        files.firstOrNull { it.path == "docs/PROJECT_STATUS.md" && !it.historical }?.let { status ->
            PHASE_AUTHORITY.filter { it.status == "promoted" }.forEach { phase ->
                val lines = status.text.lineSequence().toList()
                val lineIndex = lines.indexOfFirst { it.startsWith("- **${phase.phase}") }
                val line = lines.getOrNull(lineIndex)
                val source = line?.let {
                    Regex("""PROMOTED on installed source `([0-9a-f]{40})`""")
                        .find(it)?.groupValues?.get(1)
                }
                add(Claim(
                    "project-status-promoted-source",
                    when {
                        line == null -> "contradicted"
                        source == phase.sourceSha -> "verified"
                        else -> "stale"
                    },
                    status.path, if (lineIndex >= 0) lineIndex + 1 else 0,
                    phase.phase, "source-phase-registry", phase.sourceSha, source ?: "missing"
                ))
                if (phase.runNumber != null) {
                    val run = line?.let {
                        Regex("""run number `([0-9]+)`""").find(it)?.groupValues?.get(1)
                    }
                    add(Claim(
                        "project-status-promoted-run",
                        when {
                            line == null -> "contradicted"
                            run == phase.runNumber -> "verified"
                            else -> "stale"
                        },
                        status.path, if (lineIndex >= 0) lineIndex + 1 else 0,
                        phase.phase, "source-phase-registry", phase.runNumber, run ?: "missing"
                    ))
                }
            }
            val lines = status.text.lineSequence().toList()
            val activeIndex = lines.indexOfFirst { it.startsWith("- **N1.8.4") }
            val activeLine = lines.getOrNull(activeIndex)
            val sourceImplemented = activeLine?.contains("SOURCE-IMPLEMENTED", ignoreCase = true) == true &&
                activeLine.contains("promotion-pending", ignoreCase = true)
            add(Claim(
                "project-status-active-phase",
                if (sourceImplemented) "verified" else "stale",
                status.path, if (activeIndex >= 0) activeIndex + 1 else 0,
                "N1.8.4", "source-phase-registry", "source-implemented/promotion-pending",
                if (sourceImplemented) "source-implemented/promotion-pending" else "missing-or-wrong-lifecycle"
            ))
        }

        files.firstOrNull {
            it.path == "docs/systems/workspace/REPOSITORY_CONSISTENCY_OBSERVER.md" && !it.historical
        }?.let { observer ->
            val lines = observer.text.lineSequence().toList()
            val lineIndex = lines.indexOfFirst { it.startsWith("Status:") }
            add(Claim(
                "canonical-observer-phase-state",
                if (observer.text.contains(OBSERVER_STATUS)) "verified" else "stale",
                observer.path, if (lineIndex >= 0) lineIndex + 1 else 0,
                "N1.8 canonical status", "source-phase-registry", OBSERVER_STATUS,
                lines.getOrNull(lineIndex)?.trim() ?: "missing"
            ))
        }
    }

    private fun checkTodoLifecycle(files: List<TextFile>, add: (Claim) -> Unit) {
        val commentTodo = Regex("""(?m)^\s*(?://|#|/\*+|<!--)\s*(TODO|FIXME)\s*:\s*(.+?)\s*(?:\*/|-->)?\s*$""")
        val checklist = Regex("""(?m)^\s*-\s*\[ \]\s+(.+?)\s*$""")
        files.filter { !it.historical }.forEach { file ->
            commentTodo.findAll(file.text).forEach { match ->
                add(Claim(
                    "todo-lifecycle", "unverified", file.path,
                    lineAt(file.text, match.range.first),
                    "${match.groupValues[1]}: ${match.groupValues[2].trim()}",
                    "explicit-todo-marker", "open-commitment", "open"
                ))
            }
            if (file.documentation) {
                checklist.findAll(file.text).forEach { match ->
                    add(Claim(
                        "todo-lifecycle", "unverified", file.path,
                        lineAt(file.text, match.range.first), match.groupValues[1].trim(),
                        "explicit-markdown-checklist", "open-commitment", "open"
                    ))
                }
            }
        }
    }

    private fun classifyHistorical(files: List<TextFile>, add: (Claim) -> Unit) {
        files.filter { it.documentation && it.historical }.forEach { file ->
            add(Claim(
                "historical-document-classification", "superseded", file.path, 0, file.path,
                "deterministic-path-classification",
                "excluded-from-current-state-contradictions", "historical"
            ))
        }
    }

    private fun maintainedSources(root: File): List<String> {
        val result = linkedSetOf<String>()
        listOf(
            "index.html", "styles.css", "package.json",
            "android/build.gradle.kts", "android/settings.gradle.kts", "android/gradle.properties",
            "android/app/build.gradle.kts", "android/app/src/main/AndroidManifest.xml",
            "android/app/src/main/res/values/styles.xml",
            "android/app/src/main/res/xml/vortex_agent_accessibility.xml",
            "android/riftos-debug.keystore.b64"
        ).filter { File(root, it).isFile }.forEach(result::add)

        addMatching(root, "src", result) { it.extension.lowercase() in setOf("js", "css") }
        addMatching(root, "android/app/src/main/java/com/riftos/app", result, recursive = false) {
            it.extension.lowercase() == "kt"
        }
        addMatching(root, "android/app/src/main/assets", result) { it.extension.lowercase() == "js" }
        addMatching(root, "workspace-live", result) { it.extension.lowercase() in setOf("js", "html", "css") }
        addMatching(root, "relay", result) {
            it.extension.lowercase() == "js" ||
                relative(root, it) in setOf("relay/package.json", "relay/wrangler.jsonc")
        }
        addMatching(root, "scripts", result) { it.extension.lowercase() == "mjs" }
        return result.sorted()
    }

    private fun addMatching(
        root: File,
        relativeDir: String,
        result: MutableSet<String>,
        recursive: Boolean = true,
        predicate: (File) -> Boolean
    ) {
        val base = File(root, relativeDir)
        if (!base.isDirectory) return
        val sequence = if (recursive) {
            base.walkTopDown().filter { it.isFile }
        } else {
            base.listFiles()?.asSequence()?.filter { it.isFile } ?: emptySequence()
        }
        sequence.filter(predicate).forEach { result += relative(root, it) }
    }

    private fun isTextCandidate(file: File): Boolean =
        file.extension.lowercase() in TEXT_EXTENSIONS ||
            file.name in setOf("CMakeLists.txt", "AndroidManifest.xml")

    private fun isDocumentationPath(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".md") || lower.endsWith(".mdx") || lower.endsWith(".rst") ||
            lower.endsWith(".adoc") || lower.endsWith("/readme") || lower.endsWith("/readme.txt")
    }

    private fun isHistoricalDocumentation(path: String): Boolean {
        val lower = path.lowercase()
        val name = lower.substringAfterLast('/')
        return lower == "docs/patch_history.md" ||
            name in setOf("changelog.md", "history.md", "release_notes.md") ||
            lower.contains("/archive/") || lower.contains("/archives/") || lower.contains("/history/")
    }

    private fun claimJson(claim: Claim): JSONObject = JSONObject()
        .put("kind", claim.kind)
        .put("state", claim.state)
        .put("path", claim.path)
        .put("line", claim.line)
        .put("subject", claim.subject)
        .put("authority", claim.authority)
        .put("expected", claim.expected ?: JSONObject.NULL)
        .put("observed", claim.observed ?: JSONObject.NULL)

    private fun ruleIdFor(claim: Claim): String = when (claim.kind) {
        "required-document-existence" -> "documentation-required-file-missing"
        "relative-markdown-link" -> "documentation-relative-link-broken"
        "ownership-source-existence" -> "documentation-ownership-source-missing"
        "ownership-document-existence" -> "documentation-ownership-owner-missing"
        "ownership-ledger-coverage" -> "documentation-ownership-entry-missing"
        "documentation-authority-policy" -> "documentation-authority-policy-missing"
        "roadmap-phase-state" -> "documentation-roadmap-state-stale"
        "project-status-promoted-source" -> "documentation-promotion-source-stale"
        "project-status-promoted-run" -> "documentation-promotion-run-stale"
        "project-status-active-phase" -> "documentation-active-phase-stale"
        "canonical-observer-phase-state" -> "documentation-canonical-status-stale"
        else -> "documentation-claim-${claim.state}"
    }

    private fun lineAt(text: String, offset: Int): Int {
        if (offset <= 0) return 1
        var line = 1
        for (index in 0 until minOf(offset, text.length)) if (text[index] == '\n') line += 1
        return line
    }

    private fun relative(root: File, file: File): String {
        val rootPath = root.canonicalFile.toPath()
        val filePath = file.canonicalFile.toPath()
        return if (filePath.startsWith(rootPath)) {
            rootPath.relativize(filePath).toString().replace('\\', '/')
        } else {
            file.canonicalPath
        }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
