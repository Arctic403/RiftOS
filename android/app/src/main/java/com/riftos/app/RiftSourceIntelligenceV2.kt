package com.riftos.app

/**
 * Shared Project Intelligence V2 source analyzer.
 *
 * Normal PI-v2 indexing and patch semantic-delta analysis use this same parser so language,
 * symbol and dependency interpretation cannot drift between the two evidence paths.
 */
internal object RiftSourceIntelligenceV2 {
    const val VERSION = 3
    const val MAX_SEMANTIC_DELTA_ENTRIES = 1_000
    const val MAX_ANALYSIS_SYMBOLS = 4_096
    const val MAX_ANALYSIS_DEPENDENCIES = 4_096
    const val MAX_SYNTAX_ISSUES = 128

    class AnalysisBoundExceeded(
        val reason: String
    ) : RuntimeException(reason)

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
        val line: Int,
        val localIntent: Boolean? = null
    )

    data class SyntaxIssue(
        val code: String,
        val line: Int,
        val column: Int,
        val detail: String
    )

    data class SyntaxEvidence(
        val mode: String,
        val valid: Boolean,
        val issues: List<SyntaxIssue>
    )

    data class Analysis(
        val path: String,
        val language: String,
        val symbols: List<Symbol>,
        val dependencies: List<Dependency>,
        val syntax: SyntaxEvidence
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
        val normalized = normalize(text)
        val lines = normalized.split('\n')
        return Analysis(
            path = path,
            language = language,
            symbols = extractSymbols(path, language, lines, maxPreviewChars),
            dependencies = extractDependencies(language, lines),
            syntax = analyzeSyntax(language, normalized)
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
            else Analysis(
                path,
                languageForPath(path),
                emptyList(),
                emptyList(),
                SyntaxEvidence("absent", true, emptyList())
            )
        val after = if (afterExists) analyze(path, afterText.orEmpty())
            else Analysis(
                path,
                languageForPath(path),
                emptyList(),
                emptyList(),
                SyntaxEvidence("absent", true, emptyList())
            )

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
        val seen = HashSet<String>()
        fun addSymbol(symbol: Symbol) {
            val key = "${symbol.path}:${symbol.line}:${symbol.name}:${symbol.kind}"
            if (!seen.add(key)) return
            if (out.size >= MAX_ANALYSIS_SYMBOLS) {
                throw AnalysisBoundExceeded("semantic-symbol-bound")
            }
            out += symbol
        }

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
                addSymbol(Symbol(
                    typeMatch.groupValues[2], kind, path, index + 1,
                    symbolEndLine(lines, index, language), compactPreview(line, maxPreviewChars)
                ))
            }

            if (language == "go") {
                Regex("^\\s*type\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+(?:struct|interface)\\b")
                    .find(line)?.let { match ->
                        addSymbol(Symbol(
                            match.groupValues[1], "type", path, index + 1,
                            symbolEndLine(lines, index, language), compactPreview(line, maxPreviewChars)
                        ))
                    }
            }

            patterns.forEach { (kind, pattern) ->
                val match = pattern.find(line) ?: return@forEach
                addSymbol(Symbol(
                    match.groupValues[1], kind, path, index + 1,
                    symbolEndLine(lines, index, language), compactPreview(line, maxPreviewChars)
                ))
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
                    addSymbol(Symbol(
                        name, "method", path, index + 1,
                        symbolEndLine(lines, index, language), compactPreview(line, maxPreviewChars)
                    ))
                }
            }
        }

        return out.distinctBy { "${it.path}:${it.line}:${it.name}:${it.kind}" }
    }

    private fun extractDependencies(language: String, lines: List<String>): List<Dependency> {
        val out = ArrayList<Dependency>()
        val seen = HashSet<String>()
        fun add(specifier: String?, kind: String, line: Int, localIntent: Boolean? = null) {
            val value = specifier?.trim()?.trimEnd(';')?.trim().orEmpty()
            if (value.isBlank() || value.length > 500) return
            val key = "$line:$kind:$value"
            if (!seen.add(key)) return
            if (out.size >= MAX_ANALYSIS_DEPENDENCIES) {
                throw AnalysisBoundExceeded("semantic-dependency-bound")
            }
            out += Dependency(value, kind, line, localIntent)
        }

        lines.forEachIndexed { index, line ->
            when (language) {
                "cpp" -> Regex("^\\s*#\\s*include\\s*([<\"])([^>\"]+)[>\"]")
                    .find(line)?.let {
                        add(
                            it.groupValues[2],
                            "include",
                            index + 1,
                            localIntent = it.groupValues[1] == "\""
                        )
                    }
                "kotlin", "java" -> Regex("^\\s*import\\s+([A-Za-z0-9_.*]+)")
                    .find(line)?.let { add(it.groupValues[1], "import", index + 1) }
                "javascript" -> {
                    Regex("\\bfrom\\s*[\"']([^\"']+)[\"']")
                        .find(line)?.let {
                            val specifier = it.groupValues[1]
                            add(specifier, "import", index + 1, specifier.startsWith("."))
                        }
                    Regex("^\\s*import\\s*[\"']([^\"']+)[\"']")
                        .find(line)?.let {
                            val specifier = it.groupValues[1]
                            add(specifier, "import", index + 1, specifier.startsWith("."))
                        }
                    Regex("\\b(?:require|import)\\s*\\(\\s*[\"']([^\"']+)[\"']")
                        .findAll(line).forEach {
                            val specifier = it.groupValues[1]
                            add(specifier, "require", index + 1, specifier.startsWith("."))
                        }
                }
                "python" -> {
                    Regex("^\\s*from\\s+([A-Za-z0-9_.]+)\\s+import\\b")
                        .find(line)?.let {
                            val specifier = it.groupValues[1]
                            add(specifier, "python", index + 1, specifier.startsWith("."))
                        }
                    Regex("^\\s*import\\s+([A-Za-z0-9_.]+)")
                        .find(line)?.let {
                            val specifier = it.groupValues[1]
                            add(specifier, "python", index + 1, specifier.startsWith("."))
                        }
                }
                "rust" -> {
                    Regex("^\\s*use\\s+([^;]+)").find(line)?.let {
                        val specifier = it.groupValues[1].trim()
                        val localIntent = specifier.startsWith("crate::") ||
                            specifier.startsWith("self::") ||
                            specifier.startsWith("super::")
                        add(specifier, "use", index + 1, localIntent)
                    }
                    Regex("^\\s*mod\\s+([A-Za-z_][A-Za-z0-9_]*)")
                        .find(line)?.let { add(it.groupValues[1], "module", index + 1, true) }
                }
                "csharp" -> Regex("^\\s*using\\s+([A-Za-z0-9_.]+)")
                    .find(line)?.let { add(it.groupValues[1], "import", index + 1) }
                "go" -> Regex("^\\s*import\\s+\"([^\"]+)\"")
                    .find(line)?.let { add(it.groupValues[1], "import", index + 1) }
            }
        }
        return out.distinctBy { "${it.line}:${it.kind}:${it.specifier}" }
    }

    private fun analyzeSyntax(language: String, text: String): SyntaxEvidence {
        if (language == "generic") {
            return SyntaxEvidence("not-applicable", true, emptyList())
        }

        val issues = ArrayList<SyntaxIssue>()
        val delimiters = ArrayList<Triple<Char, Int, Int>>()
        val supportsSlashComments = language != "python"
        val supportsHashComments = language == "python"
        val supportsBlockComments = language != "python"
        val supportsTripleQuotes = language == "python" || language == "kotlin" || language == "java"
        val supportsBacktickStrings = language == "javascript"

        fun addIssue(code: String, line: Int, column: Int, detail: String) {
            if (issues.size >= MAX_SYNTAX_ISSUES) {
                throw AnalysisBoundExceeded("syntax-issue-bound")
            }
            issues += SyntaxIssue(code, line.coerceAtLeast(1), column.coerceAtLeast(1), detail)
        }

        var blockComment = false
        var blockCommentLine = 1
        var blockCommentColumn = 1
        var quote: Char? = null
        var quoteLine = 1
        var quoteColumn = 1
        var tripleQuote: Char? = null
        var tripleLine = 1
        var tripleColumn = 1
        var escaped = false

        text.split('\n').forEachIndexed { lineIndex, sourceLine ->
            val lineNumber = lineIndex + 1
            var index = 0
            while (index < sourceLine.length) {
                val c = sourceLine[index]
                val next = sourceLine.getOrNull(index + 1)
                val column = index + 1

                if (blockComment) {
                    if (c == '*' && next == '/') {
                        blockComment = false
                        index += 2
                    } else {
                        index += 1
                    }
                    continue
                }

                val activeTriple = tripleQuote
                if (activeTriple != null) {
                    if (index + 2 < sourceLine.length &&
                        sourceLine[index] == activeTriple &&
                        sourceLine[index + 1] == activeTriple &&
                        sourceLine[index + 2] == activeTriple
                    ) {
                        tripleQuote = null
                        index += 3
                    } else {
                        index += 1
                    }
                    continue
                }

                val activeQuote = quote
                if (activeQuote != null) {
                    if (escaped) {
                        escaped = false
                        index += 1
                        continue
                    }
                    if (c == '\\') {
                        escaped = true
                        index += 1
                        continue
                    }
                    if (c == activeQuote) {
                        quote = null
                    }
                    index += 1
                    continue
                }

                if (supportsSlashComments && c == '/' && next == '/') break
                if (supportsHashComments && c == '#') break
                if (supportsBlockComments && c == '/' && next == '*') {
                    blockComment = true
                    blockCommentLine = lineNumber
                    blockCommentColumn = column
                    index += 2
                    continue
                }

                if (supportsTripleQuotes &&
                    (c == '"' || c == '\'') &&
                    index + 2 < sourceLine.length &&
                    sourceLine[index + 1] == c &&
                    sourceLine[index + 2] == c
                ) {
                    tripleQuote = c
                    tripleLine = lineNumber
                    tripleColumn = column
                    index += 3
                    continue
                }

                if (c == '"' || c == '\'' || (supportsBacktickStrings && c == 96.toChar())) {
                    quote = c
                    quoteLine = lineNumber
                    quoteColumn = column
                    escaped = false
                    index += 1
                    continue
                }

                if (c == '(' || c == '[' || c == '{') {
                    delimiters += Triple(c, lineNumber, column)
                    index += 1
                    continue
                }

                if (c == ')' || c == ']' || c == '}') {
                    val expectedOpen = when (c) {
                        ')' -> '('
                        ']' -> '['
                        else -> '{'
                    }
                    val top = delimiters.lastOrNull()
                    if (top == null) {
                        addIssue(
                            "unexpected-closing-delimiter",
                            lineNumber,
                            column,
                            "Unexpected closing delimiter " + c
                        )
                    } else if (top.first != expectedOpen) {
                        addIssue(
                            "mismatched-delimiter",
                            lineNumber,
                            column,
                            "Closing delimiter " + c + " does not match opening " + top.first
                        )
                        delimiters.removeAt(delimiters.lastIndex)
                    } else {
                        delimiters.removeAt(delimiters.lastIndex)
                    }
                }
                index += 1
            }
        }

        if (blockComment) {
            addIssue(
                "unterminated-block-comment",
                blockCommentLine,
                blockCommentColumn,
                "Block comment is not terminated"
            )
        }
        tripleQuote?.let {
            addIssue(
                "unterminated-triple-string",
                tripleLine,
                tripleColumn,
                "Triple-quoted string is not terminated"
            )
        }
        quote?.let {
            addIssue(
                "unterminated-string",
                quoteLine,
                quoteColumn,
                "String literal is not terminated"
            )
        }
        delimiters.asReversed().forEach { frame ->
            addIssue(
                "unclosed-delimiter",
                frame.second,
                frame.third,
                "Opening delimiter " + frame.first + " is not closed"
            )
        }

        return SyntaxEvidence(
            mode = "bounded-structural-v1",
            valid = issues.isEmpty(),
            issues = issues
        )
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
