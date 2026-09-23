package com.riftos.app

import java.security.MessageDigest

/**
 * Deterministic threshold-qualification math for rift-b2-bottomk-v1.
 *
 * Rule under qualification:
 *   near duplicate iff similarityPpm >= thresholdPpm
 *
 * This utility never freezes a threshold. It computes the integer interval
 * that simultaneously rejects every labeled near-duplicate pair and retains
 * every labeled distinct pair in the supplied qualification corpus.
 */
object RiftB2ThresholdQualificationV1 {
    const val QUALIFICATION_ID =
        "rift-b2-bottomk-threshold-qualification-v1"
    const val LABEL_NEAR_DUPLICATE = "near_duplicate"
    const val LABEL_DISTINCT = "distinct"
    const val MAX_CASES = 100_000

    private val ID_RE = Regex("[A-Za-z0-9._:-]{1,160}")

    data class LabeledScore(
        val id: String,
        val label: String,
        val similarityPpm: Int
    )

    data class Evidence(
        val caseCount: Int,
        val nearDuplicateCases: Int,
        val distinctCases: Int,
        val maxDistinctSimilarityPpm: Int,
        val minNearDuplicateSimilarityPpm: Int,
        val qualifiedIntervalExists: Boolean,
        val minimumQualifiedThresholdPpm: Int?,
        val maximumQualifiedThresholdPpm: Int?,
        val canonicalScoreStreamSha256: String
    ) {
        init {
            require(caseCount in 2..MAX_CASES)
            require(nearDuplicateCases > 0)
            require(distinctCases > 0)
            require(nearDuplicateCases + distinctCases == caseCount)
            require(
                maxDistinctSimilarityPpm in
                    0..RiftB2BottomKDedupV1.SCORE_SCALE
            )
            require(
                minNearDuplicateSimilarityPpm in
                    0..RiftB2BottomKDedupV1.SCORE_SCALE
            )
            require(
                canonicalScoreStreamSha256.matches(
                    Regex("[0-9a-f]{64}")
                )
            )
            if (qualifiedIntervalExists) {
                require(
                    minimumQualifiedThresholdPpm != null &&
                        maximumQualifiedThresholdPpm != null &&
                        minimumQualifiedThresholdPpm in
                        1..RiftB2BottomKDedupV1.SCORE_SCALE &&
                        maximumQualifiedThresholdPpm in
                        1..RiftB2BottomKDedupV1.SCORE_SCALE &&
                        minimumQualifiedThresholdPpm <=
                        maximumQualifiedThresholdPpm &&
                        minimumQualifiedThresholdPpm >
                        maxDistinctSimilarityPpm &&
                        maximumQualifiedThresholdPpm <=
                        minNearDuplicateSimilarityPpm
                ) {
                    "qualified threshold interval is inconsistent"
                }
            } else {
                require(
                    minimumQualifiedThresholdPpm == null &&
                        maximumQualifiedThresholdPpm == null
                ) {
                    "non-separable qualification may not expose threshold interval"
                }
            }
        }
    }

    fun evaluate(cases: Sequence<LabeledScore>): Evidence {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0
        var nearDuplicateCases = 0
        var distinctCases = 0
        var maxDistinct = 0
        var minNearDuplicate = RiftB2BottomKDedupV1.SCORE_SCALE
        var previousId: String? = null

        cases.forEach { case ->
            require(count < MAX_CASES) {
                "near-dedup threshold qualification case limit exceeded"
            }
            require(ID_RE.matches(case.id)) {
                "near-dedup qualification id is invalid: " + case.id
            }
            require(previousId == null || case.id > previousId!!) {
                "near-dedup qualification ids must be unique and sorted"
            }
            require(
                case.similarityPpm in
                    0..RiftB2BottomKDedupV1.SCORE_SCALE
            ) {
                "near-dedup qualification score is out of range"
            }
            require(
                case.label == LABEL_NEAR_DUPLICATE ||
                    case.label == LABEL_DISTINCT
            ) {
                "near-dedup qualification label is invalid"
            }

            digest.update(
                (
                    case.id +
                        "\t" +
                        case.label +
                        "\t" +
                        case.similarityPpm +
                        "\n"
                    ).toByteArray(Charsets.UTF_8)
            )

            when (case.label) {
                LABEL_NEAR_DUPLICATE -> {
                    nearDuplicateCases++
                    if (case.similarityPpm < minNearDuplicate) {
                        minNearDuplicate = case.similarityPpm
                    }
                }

                LABEL_DISTINCT -> {
                    distinctCases++
                    if (case.similarityPpm > maxDistinct) {
                        maxDistinct = case.similarityPpm
                    }
                }
            }
            previousId = case.id
            count++
        }

        require(nearDuplicateCases > 0 && distinctCases > 0) {
            "near-dedup threshold qualification requires both labels"
        }

        val separable = maxDistinct < minNearDuplicate
        val minimumThreshold =
            if (separable) maxDistinct + 1 else null
        val maximumThreshold =
            if (separable) minNearDuplicate else null

        val sha = digest.digest().joinToString("") {
            (it.toInt() and 0xff)
                .toString(16)
                .padStart(2, '0')
        }

        return Evidence(
            caseCount = count,
            nearDuplicateCases = nearDuplicateCases,
            distinctCases = distinctCases,
            maxDistinctSimilarityPpm = maxDistinct,
            minNearDuplicateSimilarityPpm = minNearDuplicate,
            qualifiedIntervalExists = separable,
            minimumQualifiedThresholdPpm = minimumThreshold,
            maximumQualifiedThresholdPpm = maximumThreshold,
            canonicalScoreStreamSha256 = sha
        )
    }
}
