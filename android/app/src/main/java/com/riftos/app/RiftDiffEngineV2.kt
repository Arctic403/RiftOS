package com.riftos.app

/**
 * Deterministic bounded text diff engine used by Workspace Records.
 *
 * Small/medium regions use exact LCS. Large regions use patience-style unique-line anchors and
 * recurse into bounded regions. Huge ambiguous regions fall back to a bounded replacement block
 * instead of allocating an unbounded quadratic matrix on low-memory Android devices.
 */
internal object RiftDiffEngineV2 {
    const val VERSION = 2
    private const val CONTEXT_LINES = 3
    private const val MAX_EXACT_MATRIX_CELLS = 250_000L
    private const val MAX_RECURSION_DEPTH = 64

    data class Descriptor(val size: Long, val sha256: String)

    private enum class Kind { EQUAL, DELETE, INSERT }
    private data class Edit(val kind: Kind, val line: String)
    private data class Anchor(val a: Int, val b: Int)
    private data class Hunk(val start: Int, val endExclusive: Int)

    fun render(
        path: String,
        beforeText: String?,
        afterText: String?,
        before: Descriptor?,
        after: Descriptor?,
        beforePath: String? = null,
        afterPath: String? = null,
        maxChars: Int,
        maxChangedLines: Int
    ): String {
        require(maxChars >= 512) { "Rift diff maxChars is too small" }
        require(maxChangedLines >= 1) { "Rift diff maxChangedLines must be positive" }
        val leftPath = beforePath ?: path
        val rightPath = afterPath ?: path

        if (beforeText == null && afterText == null) {
            val left = before?.let { "${it.size} bytes ${it.sha256.take(12)}" } ?: "missing"
            val right = after?.let { "${it.size} bytes ${it.sha256.take(12)}" } ?: "missing"
            return bounded(
                "diff --rift a/$leftPath b/$rightPath\n" +
                    "Rift-Diff-Version: $VERSION\n" +
                    "Binary/oversized change: $left -> $right",
                maxChars
            )
        }

        val a = lines(beforeText ?: "")
        val b = lines(afterText ?: "")
        val edits = ArrayList<Edit>(a.size + b.size)
        diffSegment(a, 0, a.size, b, 0, b.size, 0, edits)

        val changed = edits.count { it.kind != Kind.EQUAL }
        val out = ArrayList<String>()
        out += "diff --rift a/$leftPath b/$rightPath"
        out += "Rift-Diff-Version: $VERSION"
        out += "Rift-Diff-Strategy: adaptive-lcs-patience"
        out += if (before == null) "--- /dev/null" else "--- a/$leftPath"
        out += if (after == null) "+++ /dev/null" else "+++ b/$rightPath"

        if (changed == 0) {
            val left = before?.let { "${it.size} bytes ${it.sha256.take(12)}" } ?: "missing"
            val right = after?.let { "${it.size} bytes ${it.sha256.take(12)}" } ?: "missing"
            out += when {
                before == null || after == null ->
                    "No textual line delta; file existence changed: $left -> $right"
                before.sha256 != after.sha256 ->
                    "No normalized textual line delta; byte content changed: $left -> $right"
                else -> "No textual changes"
            }
            return bounded(out.joinToString("\n"), maxChars)
        }

        val hunks = hunks(edits)
        var representedChanges = 0
        var truncated = false

        for (hunk in hunks) {
            if (representedChanges >= maxChangedLines) {
                truncated = true
                break
            }
            val slice = edits.subList(hunk.start, hunk.endExclusive)
            val oldBefore = edits.subList(0, hunk.start).count { it.kind != Kind.INSERT }
            val newBefore = edits.subList(0, hunk.start).count { it.kind != Kind.DELETE }
            val oldCount = slice.count { it.kind != Kind.INSERT }
            val newCount = slice.count { it.kind != Kind.DELETE }
            val oldStart = if (oldCount == 0) oldBefore else oldBefore + 1
            val newStart = if (newCount == 0) newBefore else newBefore + 1
            out += "@@ -$oldStart,$oldCount +$newStart,$newCount @@"

            for (edit in slice) {
                if (edit.kind != Kind.EQUAL && representedChanges >= maxChangedLines) {
                    truncated = true
                    break
                }
                when (edit.kind) {
                    Kind.EQUAL -> out += " ${edit.line}"
                    Kind.DELETE -> {
                        out += "-${edit.line}"
                        representedChanges++
                    }
                    Kind.INSERT -> {
                        out += "+${edit.line}"
                        representedChanges++
                    }
                }
            }
            if (truncated) break
        }

        if (truncated || representedChanges < changed) {
            out += "... diff truncated ($representedChanges/$changed changed lines represented)"
        }
        return bounded(out.joinToString("\n"), maxChars)
    }

    private fun lines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val out = ArrayList<String>()
        var start = 0
        for (index in normalized.indices) {
            if (normalized[index] == '\n') {
                out += normalized.substring(start, index)
                start = index + 1
            }
        }
        if (start < normalized.length) out += normalized.substring(start)
        else if (normalized.endsWith("\n")) out += ""
        return out
    }

    private fun diffSegment(
        a: List<String>,
        aStartRaw: Int,
        aEndRaw: Int,
        b: List<String>,
        bStartRaw: Int,
        bEndRaw: Int,
        depth: Int,
        out: MutableList<Edit>
    ) {
        var aStart = aStartRaw
        var bStart = bStartRaw
        var aEnd = aEndRaw
        var bEnd = bEndRaw

        while (aStart < aEnd && bStart < bEnd && a[aStart] == b[bStart]) {
            out += Edit(Kind.EQUAL, a[aStart])
            aStart++
            bStart++
        }

        var suffix = 0
        while (
            aEnd - suffix > aStart &&
            bEnd - suffix > bStart &&
            a[aEnd - 1 - suffix] == b[bEnd - 1 - suffix]
        ) {
            suffix++
        }
        aEnd -= suffix
        bEnd -= suffix

        when {
            aStart == aEnd -> {
                for (index in bStart until bEnd) out += Edit(Kind.INSERT, b[index])
            }
            bStart == bEnd -> {
                for (index in aStart until aEnd) out += Edit(Kind.DELETE, a[index])
            }
            exactMatrixAllowed(aEnd - aStart, bEnd - bStart) -> {
                exactLcs(a, aStart, aEnd, b, bStart, bEnd, out)
            }
            depth < MAX_RECURSION_DEPTH -> {
                val anchors = patienceAnchors(a, aStart, aEnd, b, bStart, bEnd)
                if (anchors.isEmpty()) {
                    replacement(a, aStart, aEnd, b, bStart, bEnd, out)
                } else {
                    var leftA = aStart
                    var leftB = bStart
                    for (anchor in anchors) {
                        diffSegment(a, leftA, anchor.a, b, leftB, anchor.b, depth + 1, out)
                        out += Edit(Kind.EQUAL, a[anchor.a])
                        leftA = anchor.a + 1
                        leftB = anchor.b + 1
                    }
                    diffSegment(a, leftA, aEnd, b, leftB, bEnd, depth + 1, out)
                }
            }
            else -> replacement(a, aStart, aEnd, b, bStart, bEnd, out)
        }

        for (index in 0 until suffix) {
            out += Edit(Kind.EQUAL, a[aEnd + index])
        }
    }

    private fun exactMatrixAllowed(aSize: Int, bSize: Int): Boolean =
        (aSize.toLong() + 1L) * (bSize.toLong() + 1L) <= MAX_EXACT_MATRIX_CELLS

    private fun exactLcs(
        a: List<String>,
        aStart: Int,
        aEnd: Int,
        b: List<String>,
        bStart: Int,
        bEnd: Int,
        out: MutableList<Edit>
    ) {
        val n = aEnd - aStart
        val m = bEnd - bStart
        val dp = Array(n + 1) { IntArray(m + 1) }

        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[aStart + i] == b[bStart + j]) {
                    dp[i + 1][j + 1] + 1
                } else {
                    maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
        }

        var i = 0
        var j = 0
        while (i < n || j < m) {
            when {
                i < n && j < m && a[aStart + i] == b[bStart + j] -> {
                    out += Edit(Kind.EQUAL, a[aStart + i])
                    i++
                    j++
                }
                i < n && (j == m || dp[i + 1][j] >= dp[i][j + 1]) -> {
                    out += Edit(Kind.DELETE, a[aStart + i])
                    i++
                }
                else -> {
                    out += Edit(Kind.INSERT, b[bStart + j])
                    j++
                }
            }
        }
    }

    private fun patienceAnchors(
        a: List<String>,
        aStart: Int,
        aEnd: Int,
        b: List<String>,
        bStart: Int,
        bEnd: Int
    ): List<Anchor> {
        val aCount = HashMap<String, Int>()
        val bCount = HashMap<String, Int>()
        val bIndex = HashMap<String, Int>()

        for (index in aStart until aEnd) aCount[a[index]] = (aCount[a[index]] ?: 0) + 1
        for (index in bStart until bEnd) {
            val line = b[index]
            bCount[line] = (bCount[line] ?: 0) + 1
            bIndex[line] = index
        }

        val candidates = ArrayList<Anchor>()
        for (index in aStart until aEnd) {
            val line = a[index]
            if (aCount[line] == 1 && bCount[line] == 1) {
                candidates += Anchor(index, bIndex.getValue(line))
            }
        }
        if (candidates.isEmpty()) return emptyList()

        // Longest increasing subsequence by B index keeps anchors ordered in both files.
        val tails = IntArray(candidates.size)
        val previous = IntArray(candidates.size) { -1 }
        var length = 0

        for (index in candidates.indices) {
            val value = candidates[index].b
            var low = 0
            var high = length
            while (low < high) {
                val mid = (low + high) ushr 1
                if (candidates[tails[mid]].b < value) low = mid + 1 else high = mid
            }
            if (low > 0) previous[index] = tails[low - 1]
            tails[low] = index
            if (low == length) length++
        }

        val selected = ArrayList<Anchor>(length)
        var cursor = tails[length - 1]
        while (cursor >= 0) {
            selected += candidates[cursor]
            cursor = previous[cursor]
        }
        selected.reverse()
        return selected
    }

    private fun replacement(
        a: List<String>,
        aStart: Int,
        aEnd: Int,
        b: List<String>,
        bStart: Int,
        bEnd: Int,
        out: MutableList<Edit>
    ) {
        for (index in aStart until aEnd) out += Edit(Kind.DELETE, a[index])
        for (index in bStart until bEnd) out += Edit(Kind.INSERT, b[index])
    }

    private fun hunks(edits: List<Edit>): List<Hunk> {
        val changes = edits.indices.filter { edits[it].kind != Kind.EQUAL }
        if (changes.isEmpty()) return emptyList()

        val out = ArrayList<Hunk>()
        var start = maxOf(0, changes.first() - CONTEXT_LINES)
        var lastChange = changes.first()

        for (index in 1 until changes.size) {
            val next = changes[index]
            if (next - lastChange > CONTEXT_LINES * 2 + 1) {
                out += Hunk(start, minOf(edits.size, lastChange + CONTEXT_LINES + 1))
                start = maxOf(0, next - CONTEXT_LINES)
            }
            lastChange = next
        }
        out += Hunk(start, minOf(edits.size, lastChange + CONTEXT_LINES + 1))
        return out
    }

    private fun bounded(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val marker = "\n... diff truncated by character limit"
        return text.take((maxChars - marker.length).coerceAtLeast(0)) + marker
    }
}
