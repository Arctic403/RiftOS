package com.riftos.app

/**
 * Shared Project Intelligence V2 source analyzer.
 *
 * Normal PI-v2 indexing and patch semantic-delta analysis use this same parser so language,
 * symbol and dependency interpretation cannot drift between the two evidence paths.
 */
internal object RiftSourceIntelligenceV2 {
    const val VERSION = 7
    const val MAX_SEMANTIC_DELTA_ENTRIES = 1_000
    const val MAX_ANALYSIS_SYMBOLS = 4_096
    const val MAX_ANALYSIS_DEPENDENCIES = 4_096
    const val MAX_SYNTAX_ISSUES = 128
    const val MAX_KOTLIN_NULLABLE_LOCALS = 256

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

    fun referenceCodeMask(path: String, text: String): BooleanArray {
        val normalized = normalize(text)
        return when (val language = languageForPath(path)) {
            "javascript" -> javascriptDependencyCodeMask(normalized)
            "generic" -> BooleanArray(normalized.length) { index -> !normalized[index].isWhitespace() }
            else -> genericReferenceCodeMask(language, normalized)
        }
    }

    fun isDocumentationPath(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".md") || lower.endsWith(".mdx") || lower.endsWith(".rst") ||
            lower.endsWith(".adoc") || lower.endsWith("/readme") || lower.endsWith("/readme.txt")
    }

    fun isMachineAuthorityPath(path: String): Boolean {
        val lower = path.lowercase().replace('\\', '/')
        return listOf(
            "observer/phase-authority.json",
            "riftmemory/n2-contract-v1.json",
            "riftmemory/n2-phase-authority.json",
            "riftarchitecture/n3-contract-v1.json",
            "riftarchitecture/n3-phase-authority.json"
        ).any { suffix -> lower == suffix || lower.endsWith("/$suffix") }
    }

    fun isBuildConfigPath(path: String): Boolean {
        val lower = path.lowercase().replace('\\', '/')
        val name = lower.substringAfterLast('/')
        return isMachineAuthorityPath(path) ||
            name in setOf(
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

    private fun maskRangePreservingNewlines(chars: CharArray, start: Int, endExclusive: Int) {
        var index = start.coerceAtLeast(0)
        val end = endExclusive.coerceAtMost(chars.size)
        while (index < end) {
            if (chars[index] != '\n') chars[index] = ' '
            index += 1
        }
    }

    private fun javascriptRegexMayStart(text: String, slashIndex: Int): Boolean {
        var cursor = slashIndex - 1
        while (cursor >= 0 && text[cursor].isWhitespace()) cursor -= 1
        if (cursor < 0) return true
        if (text[cursor] in "([{:;,=!?&|+-*%^~<>") return true
        val lineStart = text.lastIndexOf('\n', cursor).let { if (it < 0) 0 else it + 1 }
        val prefix = text.substring(lineStart, cursor + 1)
        val token = Regex("([A-Za-z_$][A-Za-z0-9_$]*)\\s*$")
            .find(prefix)?.groupValues?.getOrNull(1)
        return token in setOf(
            "return", "throw", "case", "delete", "typeof", "void", "new",
            "yield", "await", "else", "do", "in", "of"
        )
    }

    private fun javascriptRegexEnd(text: String, slashIndex: Int): Int {
        var cursor = slashIndex + 1
        var escaped = false
        var characterClass = false
        while (cursor < text.length) {
            val value = text[cursor]
            if (value == '\n' || value == '\r') return -1
            if (escaped) {
                escaped = false
            } else if (value == '\\') {
                escaped = true
            } else if (value == '[') {
                characterClass = true
            } else if (value == ']' && characterClass) {
                characterClass = false
            } else if (value == '/' && !characterClass) {
                return cursor + 1
            }
            cursor += 1
        }
        return -1
    }

    private fun javascriptQuotedEnd(text: String, startIndex: Int, quote: Char): Int {
        var cursor = startIndex + 1
        var escaped = false
        while (cursor < text.length) {
            val value = text[cursor]
            if (value == '\n' || value == '\r') return -1
            if (escaped) {
                escaped = false
            } else if (value == '\\') {
                escaped = true
            } else if (value == quote) {
                return cursor + 1
            }
            cursor += 1
        }
        return -1
    }

    private fun javascriptTemplateExpressionEnd(text: String, startIndex: Int): Int {
        var cursor = startIndex
        var depth = 1
        while (cursor < text.length) {
            val value = text[cursor]
            val next = text.getOrNull(cursor + 1)
            if (value == '\'' || value == '"') {
                val end = javascriptQuotedEnd(text, cursor, value)
                if (end < 0) return -1
                cursor = end
                continue
            }
            if (value == 96.toChar()) {
                val end = javascriptTemplateEnd(text, cursor)
                if (end < 0) return -1
                cursor = end
                continue
            }
            if (value == '/' && next == '/') {
                val newline = text.indexOf('\n', cursor + 2)
                cursor = if (newline < 0) text.length else newline + 1
                continue
            }
            if (value == '/' && next == '*') {
                val end = text.indexOf("*/", cursor + 2)
                if (end < 0) return -1
                cursor = end + 2
                continue
            }
            if (value == '/' && next != '/' && next != '*' && javascriptRegexMayStart(text, cursor)) {
                val end = javascriptRegexEnd(text, cursor)
                if (end > cursor) {
                    cursor = end
                    while (cursor < text.length && text[cursor].isLetter()) cursor += 1
                    continue
                }
            }
            if (value == '{') {
                depth += 1
            } else if (value == '}') {
                depth -= 1
                if (depth == 0) return cursor + 1
            }
            cursor += 1
        }
        return -1
    }

    private fun javascriptTemplateEnd(text: String, startIndex: Int): Int {
        var cursor = startIndex + 1
        while (cursor < text.length) {
            val value = text[cursor]
            if (value == '\\') {
                cursor += 2
                continue
            }
            if (value == 96.toChar()) return cursor + 1
            if (value == '$' && text.getOrNull(cursor + 1) == '{') {
                val end = javascriptTemplateExpressionEnd(text, cursor + 2)
                if (end < 0) return -1
                cursor = end
                continue
            }
            cursor += 1
        }
        return -1
    }

    private fun maskJavascriptTemplates(text: String): String {
        val chars = text.toCharArray()
        var cursor = 0
        var blockComment = false
        while (cursor < text.length) {
            val value = text[cursor]
            val next = text.getOrNull(cursor + 1)
            if (blockComment) {
                if (value == '*' && next == '/') {
                    blockComment = false
                    cursor += 2
                } else {
                    cursor += 1
                }
                continue
            }
            if (value == '/' && next == '/') {
                val newline = text.indexOf('\n', cursor + 2)
                cursor = if (newline < 0) text.length else newline + 1
                continue
            }
            if (value == '/' && next == '*') {
                blockComment = true
                cursor += 2
                continue
            }
            if (value == '\'' || value == '"') {
                val end = javascriptQuotedEnd(text, cursor, value)
                cursor = if (end > cursor) end else cursor + 1
                continue
            }
            if (value == '/' && next != '/' && next != '*' && javascriptRegexMayStart(text, cursor)) {
                val end = javascriptRegexEnd(text, cursor)
                if (end > cursor) {
                    cursor = end
                    while (cursor < text.length && text[cursor].isLetter()) cursor += 1
                    continue
                }
            }
            if (value == 96.toChar()) {
                val end = javascriptTemplateEnd(text, cursor)
                if (end > cursor) {
                    maskRangePreservingNewlines(chars, cursor, end)
                    cursor = end
                    continue
                }
                break
            }
            cursor += 1
        }
        return String(chars)
    }

    private fun javascriptDependencyCodeMask(text: String): BooleanArray {
        val masked = maskJavascriptTemplates(text)
        val code = BooleanArray(masked.length)
        var cursor = 0
        var blockComment = false
        while (cursor < masked.length) {
            val value = masked[cursor]
            val next = masked.getOrNull(cursor + 1)
            if (blockComment) {
                if (value == '*' && next == '/') {
                    blockComment = false
                    cursor += 2
                } else {
                    cursor += 1
                }
                continue
            }
            if (value == '/' && next == '/') {
                val newline = masked.indexOf('\n', cursor + 2)
                cursor = if (newline < 0) masked.length else newline + 1
                continue
            }
            if (value == '/' && next == '*') {
                blockComment = true
                cursor += 2
                continue
            }
            if (value == '\'' || value == '"') {
                val end = javascriptQuotedEnd(masked, cursor, value)
                cursor = if (end > cursor) end else cursor + 1
                continue
            }
            if (value == '/' && next != '/' && next != '*' && javascriptRegexMayStart(masked, cursor)) {
                val end = javascriptRegexEnd(masked, cursor)
                if (end > cursor) {
                    cursor = end
                    while (cursor < masked.length && masked[cursor].isLetter()) cursor += 1
                    continue
                }
            }
            if (!value.isWhitespace()) code[cursor] = true
            cursor += 1
        }
        return code
    }


    private fun genericReferenceCodeMask(language: String, text: String): BooleanArray {
        val code = BooleanArray(text.length)
        val slashComments = language != "python"
        val hashComments = language == "python"
        val blockComments = language != "python"
        val tripleQuotes = language == "kotlin" || language == "java" || language == "python"
        val backtickStrings = language == "go"

        fun quotedEnd(startIndex: Int, quote: Char): Int {
            var cursor = startIndex + 1
            var escaped = false
            val csharpVerbatim = language == "csharp" && startIndex > 0 && text[startIndex - 1] == '@'
            while (cursor < text.length) {
                val value = text[cursor]
                if (!csharpVerbatim && (value == '\n' || value == '\r')) return -1
                if (csharpVerbatim && value == '"' && text.getOrNull(cursor + 1) == '"') {
                    cursor += 2
                    continue
                }
                if (escaped) {
                    escaped = false
                } else if (!csharpVerbatim && value == '\\') {
                    escaped = true
                } else if (value == quote) {
                    return cursor + 1
                }
                cursor += 1
            }
            return -1
        }

        fun tripleEnd(startIndex: Int, quote: Char): Int {
            var cursor = startIndex + 3
            while (cursor + 2 < text.length) {
                if (text[cursor] == quote &&
                    text[cursor + 1] == quote &&
                    text[cursor + 2] == quote
                ) {
                    return cursor + 3
                }
                cursor += 1
            }
            return -1
        }

        var cursor = 0
        var blockComment = false
        while (cursor < text.length) {
            val value = text[cursor]
            val next = text.getOrNull(cursor + 1)

            if (blockComment) {
                if (value == '*' && next == '/') {
                    blockComment = false
                    cursor += 2
                } else {
                    cursor += 1
                }
                continue
            }

            if (slashComments && value == '/' && next == '/') {
                val newline = text.indexOf('\n', cursor + 2)
                cursor = if (newline < 0) text.length else newline + 1
                continue
            }

            if (hashComments && value == '#') {
                val newline = text.indexOf('\n', cursor + 1)
                cursor = if (newline < 0) text.length else newline + 1
                continue
            }

            if (blockComments && value == '/' && next == '*') {
                blockComment = true
                cursor += 2
                continue
            }

            if (tripleQuotes &&
                (value == '"' || value == '\'') &&
                text.getOrNull(cursor + 1) == value &&
                text.getOrNull(cursor + 2) == value
            ) {
                val end = tripleEnd(cursor, value)
                cursor = if (end > cursor) end else text.length
                continue
            }

            if (backtickStrings && value == 96.toChar()) {
                val end = text.indexOf(96.toChar(), cursor + 1)
                cursor = if (end < 0) text.length else end + 1
                continue
            }

            if (value == '\'' && language == "rust") {
                val lifetimeNext = text.getOrNull(cursor + 1)
                val lifetimeTerminator = text.getOrNull(cursor + 2)
                if (lifetimeNext != null &&
                    (lifetimeNext.isLetter() || lifetimeNext == '_') &&
                    lifetimeTerminator != '\''
                ) {
                    code[cursor] = true
                    cursor += 1
                    continue
                }
            }

            if (value == '"' || value == '\'') {
                val end = quotedEnd(cursor, value)
                cursor = if (end > cursor) {
                    end
                } else {
                    val newline = text.indexOf('\n', cursor + 1)
                    if (newline < 0) text.length else newline + 1
                }
                continue
            }

            if (!value.isWhitespace()) code[cursor] = true
            cursor += 1
        }
        return code
    }

    private fun kotlinQuotedEnd(text: String, startIndex: Int, quote: Char): Int {
        var cursor = startIndex + 1
        var escaped = false
        while (cursor < text.length) {
            val value = text[cursor]
            if (value == '\n' || value == '\r') return -1
            if (escaped) {
                escaped = false
            } else if (value == '\\') {
                escaped = true
            } else if (value == quote) {
                return cursor + 1
            }
            cursor += 1
        }
        return -1
    }

    private fun kotlinInterpolationEnd(text: String, startIndex: Int): Int {
        var cursor = startIndex
        var depth = 1
        while (cursor < text.length) {
            val value = text[cursor]
            val next = text.getOrNull(cursor + 1)
            if (value == '\'' || value == '"') {
                val end = kotlinQuotedEnd(text, cursor, value)
                if (end < 0) return -1
                cursor = end
                continue
            }
            if (value == '/' && next == '*') {
                val end = text.indexOf("*/", cursor + 2)
                if (end < 0) return -1
                cursor = end + 2
                continue
            }
            if (value == '{') {
                depth += 1
            } else if (value == '}') {
                depth -= 1
                if (depth == 0) return cursor + 1
            }
            cursor += 1
        }
        return -1
    }

    private fun kotlinStringEnd(text: String, startIndex: Int): Int {
        var cursor = startIndex + 1
        var escaped = false
        while (cursor < text.length) {
            val value = text[cursor]
            if (value == '\n' || value == '\r') return -1
            if (escaped) {
                escaped = false
                cursor += 1
                continue
            }
            if (value == '\\') {
                escaped = true
                cursor += 1
                continue
            }
            if (value == '$' && text.getOrNull(cursor + 1) == '{') {
                val end = kotlinInterpolationEnd(text, cursor + 2)
                if (end < 0) return -1
                cursor = end
                continue
            }
            if (value == '"') return cursor + 1
            cursor += 1
        }
        return -1
    }

    private fun maskKotlinInterpolatedStrings(text: String): String {
        val chars = text.toCharArray()
        var cursor = 0
        var blockComment = false
        while (cursor < text.length) {
            val value = text[cursor]
            val next = text.getOrNull(cursor + 1)
            if (blockComment) {
                if (value == '*' && next == '/') {
                    blockComment = false
                    cursor += 2
                } else {
                    cursor += 1
                }
                continue
            }
            if (value == '/' && next == '/') {
                val newline = text.indexOf('\n', cursor + 2)
                cursor = if (newline < 0) text.length else newline + 1
                continue
            }
            if (value == '/' && next == '*') {
                blockComment = true
                cursor += 2
                continue
            }
            if (value == '\'') {
                val end = kotlinQuotedEnd(text, cursor, value)
                cursor = if (end > cursor) end else cursor + 1
                continue
            }
            if (value == '"' && text.getOrNull(cursor + 1) == '"' && text.getOrNull(cursor + 2) == '"') {
                val end = text.indexOf("\"\"\"", cursor + 3)
                cursor = if (end < 0) text.length else end + 3
                continue
            }
            if (value == '"') {
                val end = kotlinStringEnd(text, cursor)
                if (end > cursor) {
                    val body = text.substring(cursor + 1, end - 1)
                    if (body.contains("\${")) maskRangePreservingNewlines(chars, cursor, end)
                    cursor = end
                    continue
                }
            }
            cursor += 1
        }
        return String(chars)
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

        val javascriptText = if (language == "javascript") lines.joinToString("\n") else ""
        val javascriptCodeMask = if (language == "javascript") javascriptDependencyCodeMask(javascriptText) else BooleanArray(0)
        var javascriptOffset = 0

        lines.forEachIndexed { index, line ->
            fun javascriptMatchStartsInCode(match: MatchResult): Boolean {
                if (language != "javascript") return true
                val firstCode = match.value.indexOfFirst { !it.isWhitespace() }
                val localOffset = match.range.first + firstCode.coerceAtLeast(0)
                return javascriptCodeMask.getOrNull(javascriptOffset + localOffset) == true
            }

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
                    Regex("^\\s*(?:import|export)\\b.*\\bfrom\\s*[\"']([^\"']+)[\"']")
                        .find(line)?.takeIf(::javascriptMatchStartsInCode)?.let {
                            val specifier = it.groupValues[1]
                            add(specifier, "import", index + 1, specifier.startsWith("."))
                        }
                    Regex("^\\s*import\\s*[\"']([^\"']+)[\"']")
                        .find(line)?.takeIf(::javascriptMatchStartsInCode)?.let {
                            val specifier = it.groupValues[1]
                            add(specifier, "import", index + 1, specifier.startsWith("."))
                        }
                    Regex("\\b(?:require|import)\\s*\\(\\s*[\"']([^\"']+)[\"']")
                        .findAll(line).filter(::javascriptMatchStartsInCode).forEach {
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
            if (language == "javascript") javascriptOffset += line.length + 1
        }
        return out.distinctBy { "${it.line}:${it.kind}:${it.specifier}" }
    }


    private fun kotlinNullableDereferenceIssues(text: String): List<SyntaxIssue> {
        val codeMask = genericReferenceCodeMask("kotlin", text)
        val masked = CharArray(text.length)
        for (index in text.indices) {
            val value = text[index]
            masked[index] = when {
                value == '\n' || value == '\r' -> value
                codeMask[index] -> value
                else -> ' '
            }
        }
        val lines = String(masked).split('\n')
        val declaration = Regex("""\bval\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.+)""")
        val nullableProducers = listOf(
            "getCanonicalRecord(",
            "getContentBlob("
        )
        val tracked = linkedMapOf<String, Int>()
        for ((index, line) in lines.withIndex()) {
            val match = declaration.find(line) ?: continue
            val name = match.groupValues[1]
            val rhs = match.groupValues[2]
            if (nullableProducers.none { producer -> rhs.contains(producer) }) continue
            if (rhs.contains("?:") || rhs.contains("!!")) continue
            if (!tracked.containsKey(name)) tracked[name] = index
            if (tracked.size > MAX_KOTLIN_NULLABLE_LOCALS) {
                throw AnalysisBoundExceeded("kotlin-nullable-local-bound")
            }
        }
        if (tracked.isEmpty()) return emptyList()

        val depthBefore = IntArray(lines.size)
        var depth = 0
        for (index in lines.indices) {
            depthBefore[index] = depth
            val line = lines[index]
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth < 0) depth = 0
        }

        val issues = ArrayList<SyntaxIssue>()
        for ((name, declarationLine) in tracked) {
            val escaped = Regex.escape(name)
            val unsafeDereference = Regex("""\b$escaped\.""")
            val redeclaration = Regex("""\b(?:val|var)\s+$escaped\b""")
            val sameLineProof = Regex("""\b$escaped\s*!=\s*null\b""")
            val guardBlock = Regex("""\bif\s*\(\s*$escaped\s*!=\s*null\s*\)\s*\{""")
            val earlyExitGuard = Regex(
                """\bif\s*\(\s*$escaped\s*==\s*null\s*\)\s*(?:return\b|throw\b|continue\b|break\b)"""
            )
            val contractGuard = Regex("""\b(?:requireNotNull|checkNotNull)\s*\(\s*$escaped\s*\)""")
            val elvisExitGuard = Regex("""\b$escaped\s*\?:\s*(?:return\b|throw\b)""")

            var permanentlyNonNull = false
            var guardedDepth: Int? = null
            for (lineIndex in declarationLine + 1 until lines.size) {
                val line = lines[lineIndex]
                if (redeclaration.containsMatchIn(line)) break

                val currentDepth = depthBefore[lineIndex]
                if (guardedDepth != null && currentDepth < guardedDepth!!) guardedDepth = null

                if (earlyExitGuard.containsMatchIn(line) ||
                    contractGuard.containsMatchIn(line) ||
                    elvisExitGuard.containsMatchIn(line)
                ) {
                    permanentlyNonNull = true
                }

                val guardMatch = guardBlock.find(line)
                if (guardMatch != null) {
                    guardedDepth = currentDepth + 1
                }

                for (match in unsafeDereference.findAll(line)) {
                    val prefix = line.substring(0, match.range.first)
                    val sameLineGuard = sameLineProof.find(prefix)?.let { proof ->
                        prefix.substring(proof.range.last + 1).contains("&&")
                    } == true
                    if (permanentlyNonNull || guardedDepth != null || sameLineGuard) continue
                    issues += SyntaxIssue(
                        code = "kotlin-nullable-dereference",
                        line = lineIndex + 1,
                        column = match.range.first + 1,
                        detail = "Nullable local '$name' is dereferenced without a recognized non-null proof."
                    )
                    if (issues.size >= MAX_SYNTAX_ISSUES) {
                        throw AnalysisBoundExceeded("syntax-issue-bound")
                    }
                }
            }
        }
        return issues
            .distinctBy { Triple(it.line, it.column, it.detail) }
            .sortedWith(compareBy<SyntaxIssue> { it.line }.thenBy { it.column }.thenBy { it.detail })
    }

    private fun analyzeSyntax(language: String, text: String): SyntaxEvidence {
        if (language == "generic") {
            return SyntaxEvidence("not-applicable", true, emptyList())
        }

        val structuralText = when (language) {
            "javascript" -> maskJavascriptTemplates(text)
            "kotlin" -> maskKotlinInterpolatedStrings(text)
            else -> text
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

        structuralText.split('\n').forEachIndexed { lineIndex, sourceLine ->
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

                if (language == "javascript" &&
                    c == '/' &&
                    next != '/' &&
                    next != '*' &&
                    javascriptRegexMayStart(sourceLine, index)
                ) {
                    val regexEnd = javascriptRegexEnd(sourceLine, index)
                    if (regexEnd > index) {
                        index = regexEnd
                        while (index < sourceLine.length && sourceLine[index].isLetter()) index += 1
                        continue
                    }
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

        if (language == "kotlin") {
            kotlinNullableDereferenceIssues(text).forEach { issue ->
                addIssue(issue.code, issue.line, issue.column, issue.detail)
            }
        }

        return SyntaxEvidence(
            mode = "bounded-structural-v5-conservative",
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
