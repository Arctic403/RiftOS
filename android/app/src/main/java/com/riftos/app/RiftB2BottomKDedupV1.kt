package com.riftos.app

import java.security.MessageDigest
import java.util.PriorityQueue

/**
 * Candidate RiftTrainData V2 near-dedup sketch.
 *
 * This is the exact semantic sketch defined by rift-b2-bottomk-v1:
 * 13-token frozen-B2 shingles, SHA-256, first unsigned 64 bits, retain the
 * 128 smallest unique fingerprints. It does not choose/freeze a rejection
 * threshold and does not by itself claim production near-dedup completion.
 */
object RiftB2BottomKDedupV1 {
    const val ALGORITHM_ID = "rift-b2-bottomk-v1"
    const val SIMILARITY_ID = "bottom-k-union-jaccard-v1"
    const val SHINGLE_TOKENS = 13
    const val BOTTOM_K = 128
    const val SCORE_SCALE = 1_000_000

    data class Sketch(
        val fingerprints: List<String>
    ) {
        init {
            require(fingerprints.size <= BOTTOM_K) {
                "bottom-k sketch exceeds contract"
            }
            require(fingerprints == fingerprints.distinct().sorted()) {
                "bottom-k sketch is not canonical"
            }
            require(
                fingerprints.all {
                    it.matches(Regex("[0-9a-f]{16}"))
                }
            ) {
                "bottom-k fingerprint is not canonical unsigned-64 hex"
            }
        }

        val isExactFallback: Boolean
            get() = fingerprints.isEmpty()
    }

    fun sketch(contentTokenIds: IntArray): Sketch {
        require(
            contentTokenIds.all {
                it in 0 until RiftFrozenByteBpeV1.VOCAB_SIZE
            }
        ) {
            "bottom-k input token id out of range"
        }
        if (contentTokenIds.size < SHINGLE_TOKENS) {
            return Sketch(emptyList())
        }

        val maxHeap = PriorityQueue<String>(BOTTOM_K, reverseOrder())
        val retained = HashSet<String>(BOTTOM_K * 2)
        val shingleBytes = ByteArray(SHINGLE_TOKENS * 2)
        val digest = MessageDigest.getInstance("SHA-256")

        for (start in 0..contentTokenIds.size - SHINGLE_TOKENS) {
            var out = 0
            for (offset in 0 until SHINGLE_TOKENS) {
                val token = contentTokenIds[start + offset]
                shingleBytes[out++] = (token and 0xff).toByte()
                shingleBytes[out++] = ((token ushr 8) and 0xff).toByte()
            }

            digest.reset()
            val hash = digest.digest(shingleBytes)
            val fingerprint = buildString(16) {
                for (index in 0 until 8) {
                    append(
                        (hash[index].toInt() and 0xff)
                            .toString(16)
                            .padStart(2, '0')
                    )
                }
            }

            if (fingerprint in retained) continue
            if (maxHeap.size < BOTTOM_K) {
                maxHeap.add(fingerprint)
                retained.add(fingerprint)
                continue
            }

            val largest = maxHeap.peek()
            if (fingerprint < largest) {
                maxHeap.poll()
                retained.remove(largest)
                maxHeap.add(fingerprint)
                retained.add(fingerprint)
            }
        }

        return Sketch(maxHeap.toList().sorted())
    }

    /**
     * Deterministic bottom-k union estimator.
     *
     * Merge both retained sketches, take the BOTTOM_K smallest unique
     * fingerprints of that union, and measure what fraction are present in
     * both sketches. Returns an integer score in [0, SCORE_SCALE].
     *
     * Empty sketches are exact-hash fallback cases and are not comparable here.
     */
    fun similarityPpm(left: Sketch, right: Sketch): Int {
        require(!left.isExactFallback && !right.isExactFallback) {
            "bottom-k similarity requires two non-empty sketches"
        }

        val leftSet = left.fingerprints.toHashSet()
        val rightSet = right.fingerprints.toHashSet()
        val unionBottom = ArrayList<String>(BOTTOM_K)
        var li = 0
        var ri = 0
        var previous: String? = null

        while (
            unionBottom.size < BOTTOM_K &&
            (li < left.fingerprints.size || ri < right.fingerprints.size)
        ) {
            val next = when {
                li >= left.fingerprints.size ->
                    right.fingerprints[ri++]
                ri >= right.fingerprints.size ->
                    left.fingerprints[li++]
                left.fingerprints[li] < right.fingerprints[ri] ->
                    left.fingerprints[li++]
                right.fingerprints[ri] < left.fingerprints[li] ->
                    right.fingerprints[ri++]
                else -> {
                    val same = left.fingerprints[li]
                    li++
                    ri++
                    same
                }
            }
            if (next != previous) {
                unionBottom += next
                previous = next
            }
        }

        require(unionBottom.isNotEmpty()) {
            "bottom-k union unexpectedly empty"
        }
        val shared = unionBottom.count {
            it in leftSet && it in rightSet
        }
        return ((shared.toLong() * SCORE_SCALE) / unionBottom.size.toLong())
            .toInt()
    }

    fun canonicalSketchLine(
        sampleSha256: String,
        sketch: Sketch
    ): String {
        require(sampleSha256.matches(Regex("[0-9a-f]{64}"))) {
            "sample SHA-256 is not canonical"
        }
        return buildString {
            append(sampleSha256)
            append('\t')
            append(sketch.fingerprints.size)
            append('\t')
            sketch.fingerprints.forEachIndexed { index, value ->
                if (index > 0) append(',')
                append(value)
            }
            append('\n')
        }
    }
}
