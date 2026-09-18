package com.riftos.app

/**
 * Shared Project Intelligence V2 source analyzer.
 *
 * Normal PI-v2 indexing and patch semantic-delta analysis use this same parser so language,
 * symbol and dependency interpretation cannot drift between the two evidence paths.
 */
internal object RiftSourceIntelligenceV2 {
    const val VERSION = 2
    const val MAX_SEMANTIC_DELTA_ENTRIES = 1_000

    data class Symbol(
        val name: String,
        val kind: String,
        val path: String,
        val line: Int,
        val endLine: Int,
        val signature: String
    )

    data class Dependency(
        val specifier: String,
        val kind: String,
        val line: Int
    )

    data class Analysis(
        val path: String,
        val language: String,
        val symbols: List<Symbol>,
        val dependencies: List<Dependency>
    )

    data class SignatureChange(val before: Symbol, val after: Symbol)

    data class Delta(
        val path: String,
        val languageBefore: String,
        val languageAfter: String,
        val addedSymbols: List<Symbol>,
        val removedSymbols: List<Symbol>,
        val changedSignatures: List<SignatureChange>,
        val addedDependencies: List<Dependency>,
        val removedDependencies: List<Dependency>,
        val apiSurfaceChanged: Boolean,
        val truncated: Boolean
    )

    fun analyze(path: String, text: String, maxPreviewChars: Int = 320): Analysis {
        val language = languageForPath(path)
        val lines = normalize(text).split('\n')
        return Analysis(
            path = path,
            language = language,
            symbols = extractSymbols(path, language, lines, maxPreviewChars),
            dependencies = extractDependencies(language, lines)
        )
    }

    fun diff(
        path: String,
        beforeExists: Boolean,
        beforeText: String?,
        afterExists: Boolean,
        afterText: String?,
        maxEntries: Int = MAX_SEMANTIC_DELTA_ENTRIES
    ): Delta {
        require(maxEntries >= 1) { "Semantic delta maxEntries must be positive" }
        require(!beforeExists || beforeText != null) { "Existing before-state text is required for semantic diff" }
        require(!afterExists || afterText != null) { "Existing after-state text is required for semantic diff" }

        val before = if (beforeExists) analyze(path, beforeText.orEmpty())
            else Analysis(path, languageForPath(path), emptyList(), emptyList())
        val after = if (afterExists) analyze(path, afterText.orEmpty())
            else Analysis(path, languageForPath(path), emptyList(), emptyList())

        val addedSymbols = ArrayList<Symbol>()
        val removedSymbols = ArrayList<Symbol>()
        val changedSignatures = ArrayList<SignatureChange>()

        val beforeGroups = before.symbols.groupBy(::symbolIdentity)
        val afterGroups = after.symbols.groupBy(::symbolIdentity)
        val keys = (beforeGroups.keys + afterGroups.keys).toSortedSet()

        for (key in keys) {
            val oldRows = beforeGroups[key].orEmpty().sortedWith(symbolComparator()).toMutableList()
            val newRows = afterGroups[key].orEmpty().sortedWith(symbolComparator()).toMutableList()

            var oldIndex = oldRows.lastIndex
            while (oldIndex >= 0) {
                val old = oldRows[oldIndex]
                val sameIndex = newRows.indexOfFirst { it.signature == old.signature }
                if (sameIndex >= 0) {
                    oldRows.removeAt(oldIndex)
                    newRows.removeAt(sameIndex)
                }
                oldIndex--
            }

            val paired = minOf(oldRows.size, newRows.size)
            for (index in 0 until paired) changedSignatures += SignatureChange(oldRows[index], newRows[index])
            if (oldRows.size > paired) removedSymbols += oldRows.drop(paired)
            if (newRows.size > paired) addedSymbols += newRows.drop(paired)
        }

        val oldDependencies = before.dependencies.associateBy(::dependencyIdentity)
        val newDependencies = after.dependencies.associateBy(::dependencyIdentity)
        val removedDependencies = (oldDependencies.keys - newDependencies.keys)
            .sorted().mapNotNull(oldDependencies::get)
        val addedDependencies = (newDependencies.keys - oldDependencies.keys)
            .sorted().mapNotNull(newDependencies::get)

        val apiSurfaceChanged =
            addedSymbols.any(::isApiSurfaceSymbol) ||
            removedSymbols.any(::isApiSurfaceSymbol) ||
            changedSignatures.any { isApiSurfaceSymbol(it.before) || isApiSurfaceSymbol(it.after) }

        val truncated =
            addedSymbols.size > maxEntries ||
            removedSymbols.size > maxEntries ||
            changedSignatures.size > maxEntries ||
            addedDependencies.size > maxEntries ||
            removedDependencies.size > maxEntries

        return Delta(
            path = path,
            languageBefore = before.language,
            languageAfter = after.language,
            addedSymbols = addedSymbols.take(maxEntries),
            removedSymbols = removedSymbols.take(maxEntries),
            changedSignatures = changedSignatures.take(maxEntries),
            addedDependencies = addedDependencies.take(maxEntries),
            removedDependencies = removedDependencies.take(maxEntries),
            apiSurfaceChanged = apiSurfaceChanged,
            truncated = truncated
        )
    }

    fun languageForPath(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "js", "jsx", "ts", "tsx", "mjs", "cjs" -> "javascript"
        "py" -> "python"
        "rs" -> "rust"
        "go" -> "go"
        "cs" -> "csharp"
        "c", "cc", "cpp", "cxx", "h", "hh", "hpp", "hxx" -> "cpp"
        else -> "generic"
    }

    fun isSourcePath(path: String): Boolean = languageForPath(path) != "generic"

    fun isDocumentationPath(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".md") || lower.endsWith(".mdx") || lower.endsWith(".rst") ||
            lower.endsWith(".adoc") || lower.endsWith("/readme") || lower.endsWith("/readme.txt")
    }

    fun isBuildConfigPath(path: String): Boolean {
        val lower = path.lowercase()
        val name = lower.substringAfterLast('/')
        return name in setOf(
            "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
            "gradle.properties", "package.json", "package-lock.json", "bun.lock", "bun.lockb",
            "pnpm-lock.yaml", "yarn.lock", "cmakelists.txt", "cargo.toml", "cargo.lock",
            "pyproject.toml", "requirements.txt", "androidmanifest.xml"
        ) || lower.contains("/.github/workflows/") ||
            lower.contains("/gradle/") ||
            lower.endsWith(".pro") ||
            lower.endsWith(".mk")
    }

    fun classifyPath(path: String, test: Boolean = false): String = when {
        test -> "test"
        isDocumentationPath(path) -> "documentation"
        isBuildConfigPath(path) -> "build-config"
        isSourcePath(path) -> "source"
        else -> "other"
    }

    private fun extractSymbols(
        path: String,
        language: String,
        lines: List<String>,
        maxPreviewChars: Int
    ): List<Symbol> {
        val out = ArrayList<Symbol>()
        val typePattern = Regex(
            "^\\s*(?:(?:public|private|protected|internal|open|final|abstract|static|export|default|data|sealed|partial|pub(?:\\([^)]*\\))?)\\s+)*" +
                "(class|interface|object|struct|trait|record|enum(?:\\s+class)?)\\s+([A-Za-z_$][A-Za-z0-9_$]*)"
        )
        val patterns = listOf(
            "function" to Regex("^\\s*(?:export\\s+)?(?:default\\s+)?(?:async\\s+)?function\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\("),
            "function" to Regex("^\\s*(?:async\\s+)?def\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\("),
            "function" to Regex("^\\s*(?:(?:public|private|protected|internal|open|final|override|inline|suspend|operator|tailrec|infix|external)\\s+)*fun\\s+(?:<[^>]+>\\s*)?([A-Za-z_][A-Za-z0-9_]*)\\s*\\("),
            "function" to Regex("^\\s*(?:(?:pub(?:\\([^)]*\\))?|unsafe|async|const|extern\\s+\"[^\"]+\")\\s+)*fn\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\("),
            "function" to Regex("^\\s*func\\s+(?:\\([^)]*\\)\\s*)?([A-Za-z_][A-Za-z0-9_]*)\\s*\\("),
            "function" to Regex("^\\s*(?:export\\s+)?(?:const|let|var)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*(?:async\\s*)?(?:\\([^)]*\\)|[A-Za-z_$][A-Za-z0-9_$]*)\\s*=>")
        )

        lines.forEachIndexed { index, line ->
            val typeMatch = typePattern.find(line)
            if (typeMatch != null) {
                val rawKind = typeMatch.groupValues[1].lowercase()
                val kind = when {
                    rawKind.startsWith("enum") -> "enum"
                    rawKind == "interface" || rawKind == "trait" -> "interface"
                    else -> "type"
                }
                out += Symbol(
                    typeMatch.groupValues[2], kind, path, index + 1,
                    symbolEndLine(lines, index, language), compactPreview(line, maxPreviewChars)
                )
            }

            if (language == "go") {
                Regex("^\\s*type\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+(?:struct|interface)\\b")
                    .find(line)?.let { match ->
                        out += Symbol(
                            match.groupValues[1], "type", path, index + 1,
                            symbolEndLine(lines, index, language), compactPreview(line, maxPreviewChars)
                        )
                    }
            }

            patterns.forEach { (kind, pattern) ->
                val match = pattern.find(line) ?: return@forEach
                out += Symbol(
                    match.groupValues[1], kind, path, index + 1,
                    symbolEndLine(lines, index, language), compactPreview(line, maxPreviewChars)
                )
            }

            if (language in setOf("java", "csharp", "cpp") &&
                line.contains('(') && !line.trimStart().startsWith("//")
            ) {
                val method = Regex(
                    "^\\s*(?:(?:public|private|protected|static|final|virtual|override|abstract|synchronized|native|inline|constexpr|friend|extern)\\s+)*" +
                        "(?:[A-Za-z_][A-Za-z0-9_<>,.?\\[\\]:*&\\s]+\\s+)([A-Za-z_][A-Za-z0-9_]*)\\s*\\([^;]*\\)\\s*(?:\\{|=>)?\\s*$"
                ).find(line)
                val name = method?.groupValues?.getOrNull(1)
                if (!name.isNullOrBlank() && name !in setOf("if", "for", "while", "switch", "catch")) {
                    out += Symbol(
                        name, "method", path, index + 1,
                        symbolEndLine(lines, index, language), compactPreview(line, maxPreviewChars)
                    )
                }
            }
        }

        return out.distinctBy { "${it.path}:${it.line}:${it.name}:${it.kind}" }
    }

    private fun extractDependencies(language: String, lines: List<String>): List<Dependency> {
        val out = ArrayList<Dependency>()
        fun add(specifier: String?, kind: String, line: Int) {
            val value = specifier?.trim()?.trimEnd(';')?.trim().orEmpty()
            if (value.isNotBlank() && value.length <= 500) out += Dependency(value, kind, line)
        }

        lines.forEachIndexed { index, line ->
            when (language) {
                "cpp" -> Regex("^\\s*#\\s*include\\s*[<\"]([^>\"]+)[>\"]")
                    .find(line)?.let { add(it.groupValues[1], "include", index + 1) }
                "kotlin", "java" -> Regex("^\\s*import\\s+([A-Za-z0-9_.*]+)")
                    .find(line)?.let { add(it.groupValues[1], "import", index + 1) }
                "javascript" -> {
                    Regex("\\bfrom\\s*[\"']([^\"']+)[\"']")
                        .find(line)?.let { add(it.groupValues[1], "import", index + 1) }
                    Regex("^\\s*import\\s*[\"']([^\"']+)[\"']")
                        .find(line)?.let { add(it.groupValues[1], "import", index + 1) }
                    Regex("\\b(?:require|import)\\s*\\(\\s*[\"']([^\"']+)[\"']")
                        .findAll(line).forEach { add(it.groupValues[1], "require", index + 1) }
                }
                "python" -> {
                    Regex("^\\s*from\\s+([A-Za-z0-9_.]+)\\s+import\\b")
                        .find(line)?.let { add(it.groupValues[1], "python", index + 1) }
                    Regex("^\\s*import\\s+([A-Za-z0-9_.]+)")
                        .find(line)?.let { add(it.groupValues[1], "python", index + 1) }
                }
                "rust" -> {
                    Regex("^\\s*use\\s+([^;]+)").find(line)?.let { add(it.groupValues[1], "use", index + 1) }
                    Regex("^\\s*mod\\s+([A-Za-z_][A-Za-z0-9_]*)")
                        .find(line)?.let { add(it.groupValues[1], "module", index + 1) }
                }
                "csharp" -> Regex("^\\s*using\\s+([A-Za-z0-9_.]+)")
                    .find(line)?.let { add(it.groupValues[1], "import", index + 1) }
                "go" -> Regex("^\\s*import\\s+\"([^\"]+)\"")
                    .find(line)?.let { add(it.groupValues[1], "import", index + 1) }
            }
        }
        return out.distinctBy { "${it.line}:${it.kind}:${it.specifier}" }
    }

    private fun symbolEndLine(lines: List<String>, startIndex: Int, language: String): Int {
        if (language == "python") {
            val start = lines[startIndex]
            val indent = start.takeWhile { it == ' ' || it == '\t' }.length
            for (index in startIndex + 1 until lines.size) {
                val line = lines[index]
                if (line.isBlank() || line.trimStart().startsWith("#")) continue
                val nextIndent = line.takeWhile { it == ' ' || it == '\t' }.length
                if (nextIndent <= indent) return index
            }
            return lines.size
        }

        val declaration = lines[startIndex].substringBefore("//")
        if (!declaration.contains('{') &&
            (declaration.contains("=") || declaration.trimEnd().endsWith(";"))
        ) return startIndex + 1

        var depth = 0
        var opened = false
        for (index in startIndex until minOf(lines.size, startIndex + 2000)) {
            val line = lines[index].substringBefore("//")
            val opens = line.count { it == '{' }
            val closes = line.count { it == '}' }
            if (opens > 0) opened = true
            depth += opens - closes
            if (opened && depth <= 0) return index + 1
            if (!opened && index > startIndex + 80) return index + 1
        }
        return minOf(lines.size, startIndex + 81)
    }

    private fun symbolIdentity(symbol: Symbol): String = "${symbol.kind}:${symbol.name}"
    private fun dependencyIdentity(dependency: Dependency): String = "${dependency.kind}:${dependency.specifier}"

    private fun symbolComparator(): Comparator<Symbol> =
        compareBy<Symbol>({ it.signature }, { it.line }, { it.endLine }, { it.name })

    private fun isApiSurfaceSymbol(symbol: Symbol): Boolean {
        val signature = symbol.signature.lowercase()
        return !Regex("\\b(private|internal)\\b").containsMatchIn(signature)
    }

    private fun compactPreview(line: String, maxChars: Int): String {
        val compact = line.trim().replace(Regex("\\s+"), " ")
        return if (compact.length <= maxChars) compact else compact.take(maxChars) + "…"
    }

    private fun normalize(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')
}
