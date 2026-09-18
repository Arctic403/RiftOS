package com.riftos.app

/**
 * Deterministic bounded file-identity correlation for Workspace Records.
 *
 * Exact SHA-256 identity is authoritative for content equality. Similarity correlation is
 * heuristic evidence only and is deliberately bounded so large workspaces cannot trigger an
 * unbounded all-pairs comparison.
 */
internal object RiftFileIdentityV2 {
    const val VERSION = 2
    const val MIN_RENAME_SIMILARITY = 60
    const val MAJOR_REWRITE_MAX_SIMILARITY = 25
    private const val MIN_MAJOR_REWRITE_BYTES = 512L
    private const val MAX_SIMILARITY_CANDIDATES_PER_SIDE = 64
    private const val MAX_SIMILARITY_COMPARISONS = 1024

    data class FileView(
        val path: String,
        val size: Long,
        val sha256: String,
        val text: String?
    )

    data class Relation(
        val kind: String,
        val fromPath: String,
        val toPath: String,
        val similarity: Int,
        val method: String,
        val exact: Boolean
    )

    data class Correlation(
        val relations: List<Relation>,
        val similarityComparisons: Int,
        val similaritySkipped: Boolean
    )

    private data class Candidate(val relation: Relation)

    fun correlate(before: Collection<FileView>, after: Collection<FileView>): Correlation {
        val beforeByPath = before.associateBy { it.path }
        val afterByPath = after.associateBy { it.path }
        val removed = before.filter { it.path !in afterByPath }.sortedBy { it.path }
        val added = after.filter { it.path !in beforeByPath }.sortedBy { it.path }
        val relations = ArrayList<Relation>()
        val usedRemoved = HashSet<String>()
        val usedAdded = HashSet<String>()

        val removedByHash = removed.groupBy { it.sha256 }
            .mapValues { (_, items) -> ArrayDeque(items.sortedBy { it.path }) }
            .toMutableMap()

        // Exact byte identity is authoritative evidence that removed/added content is identical.
        for (target in added) {
            val queue = removedByHash[target.sha256] ?: continue
            while (queue.isNotEmpty() && queue.first().path in usedRemoved) queue.removeFirst()
            if (queue.isEmpty()) continue
            val source = queue.removeFirst()
            usedRemoved += source.path
            usedAdded += target.path
            relations += Relation("renamed", source.path, target.path, 100, "sha256", true)
        }

        // A new path with the same SHA as a source that survived is content-copy evidence.
        val survivingByHash = after
            .filter { it.path in beforeByPath && beforeByPath[it.path]?.sha256 == it.sha256 }
            .groupBy { it.sha256 }
            .mapValues { (_, items) -> items.sortedBy { it.path } }

        for (target in added) {
            if (target.path in usedAdded) continue
            val source = survivingByHash[target.sha256]?.firstOrNull { it.path != target.path } ?: continue
            usedAdded += target.path
            relations += Relation("copied", source.path, target.path, 100, "sha256", true)
        }

        val removedLeftAll = removed.filter { it.path !in usedRemoved && it.text != null }
        val addedLeftAll = added.filter { it.path !in usedAdded && it.text != null }
        val candidateLimitExceeded =
            removedLeftAll.size > MAX_SIMILARITY_CANDIDATES_PER_SIDE ||
                addedLeftAll.size > MAX_SIMILARITY_CANDIDATES_PER_SIDE
        val removedLeft = removedLeftAll.take(MAX_SIMILARITY_CANDIDATES_PER_SIDE)
        val addedLeft = addedLeftAll.take(MAX_SIMILARITY_CANDIDATES_PER_SIDE)
        val candidates = ArrayList<Candidate>()
        var comparisons = 0

        outer@ for (source in removedLeft) {
            for (target in addedLeft) {
                if (comparisons >= MAX_SIMILARITY_COMPARISONS) break@outer
                // Cheap size filtering does not consume a line-similarity comparison.
                if (sizeSimilarity(source.size, target.size) < 30) continue
                comparisons++
                val similarity = similarityPercent(source.text, target.text, source.size, target.size) ?: continue
                if (similarity >= MIN_RENAME_SIMILARITY) {
                    candidates += Candidate(
                        Relation("renamed", source.path, target.path, similarity, "bounded-line-dice", false)
                    )
                }
            }
        }

        candidates.sortWith(
            compareByDescending<Candidate> { it.relation.similarity }
                .thenBy { it.relation.fromPath }
                .thenBy { it.relation.toPath }
        )
        for (candidate in candidates) {
            val relation = candidate.relation
            if (relation.fromPath in usedRemoved || relation.toPath in usedAdded) continue
            usedRemoved += relation.fromPath
            usedAdded += relation.toPath
            relations += relation
        }

        // Same-path low-similarity replacement is useful evidence, but not for tiny files.
        for (path in beforeByPath.keys.intersect(afterByPath.keys).sorted()) {
            if (comparisons >= MAX_SIMILARITY_COMPARISONS) break
            val left = beforeByPath.getValue(path)
            val right = afterByPath.getValue(path)
            if (
                left.sha256 == right.sha256 ||
                left.text == null ||
                right.text == null ||
                maxOf(left.size, right.size) < MIN_MAJOR_REWRITE_BYTES
            ) continue
            comparisons++
            val similarity = majorRewriteSimilarity(left.text, right.text, left.size, right.size) ?: continue
            relations += Relation("rewritten", path, path, similarity, "bounded-line-dice", false)
        }

        return Correlation(
            relations = relations.sortedWith(
                compareBy<Relation> { it.toPath }.thenBy { it.fromPath }.thenBy { it.kind }
            ),
            similarityComparisons = comparisons,
            similaritySkipped = candidateLimitExceeded || comparisons >= MAX_SIMILARITY_COMPARISONS
        )
    }

    fun majorRewriteSimilarity(before: String?, after: String?, beforeSize: Long, afterSize: Long): Int? {
        if (maxOf(beforeSize, afterSize) < MIN_MAJOR_REWRITE_BYTES) return null
        val similarity = similarityPercent(before, after, beforeSize, afterSize) ?: return null
        return similarity.takeIf { it <= MAJOR_REWRITE_MAX_SIMILARITY }
    }

    fun similarityPercent(before: String?, after: String?, beforeSize: Long, afterSize: Long): Int? {
        if (before == null || after == null) return null
        val a = lines(before)
        val b = lines(after)
        if (a.isEmpty() && b.isEmpty()) return 100

        val counts = HashMap<String, Int>()
        for (line in a) counts[line] = (counts[line] ?: 0) + 1
        var common = 0
        for (line in b) {
            val remaining = counts[line] ?: 0
            if (remaining > 0) {
                common++
                if (remaining == 1) counts.remove(line) else counts[line] = remaining - 1
            }
        }

        val lineDice = if (a.size + b.size == 0) {
            100
        } else {
            ((200L * common) / (a.size + b.size).toLong()).toInt().coerceIn(0, 100)
        }
        val sizeScore = sizeSimilarity(beforeSize, afterSize)
        return ((lineDice * 80) + (sizeScore * 20)) / 100
    }

    private fun sizeSimilarity(left: Long, right: Long): Int {
        val max = maxOf(left, right)
        if (max <= 0L) return 100
        val min = minOf(left, right)
        return ((min * 100L) / max).toInt().coerceIn(0, 100)
    }

    private fun lines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        return normalized.split('\n')
    }
}
