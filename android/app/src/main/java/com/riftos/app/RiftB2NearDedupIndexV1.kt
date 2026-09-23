package com.riftos.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/**
 * Disk-backed cross-split comparator for rift-b2-bottomk-v1.
 *
 * This is qualification/evidence machinery only. It does not own a rejection
 * threshold and therefore cannot make a dataset production-eligible.
 */
object RiftB2NearDedupIndexV1 {
    const val INDEX_ID = "rift-b2-bottomk-sqlite-cross-split-v1"
    const val HISTOGRAM_BIN_PPM = 10_000
    const val HISTOGRAM_BINS =
        RiftB2BottomKDedupV1.SCORE_SCALE / HISTOGRAM_BIN_PPM + 1
    const val MAX_INDEXED_FINGERPRINTS = 16_000_000L
    const val MAX_CANDIDATE_PAIRS = 5_000_000L

    data class ComparisonEvidence(
        val indexedSketchSamples: Long,
        val indexedFingerprints: Long,
        val candidatePairsCompared: Long,
        val positivePairs: Long,
        val maxSimilarityPpm: Int,
        val maxPairLeftSha256: String?,
        val maxPairRightSha256: String?,
        val maxPairLeftSplit: Int?,
        val maxPairRightSplit: Int?,
        val histogramCounts: LongArray
    ) {
        init {
            require(indexedSketchSamples >= 0L)
            require(indexedFingerprints in 0L..MAX_INDEXED_FINGERPRINTS)
            require(candidatePairsCompared in 0L..MAX_CANDIDATE_PAIRS)
            require(positivePairs in 0L..candidatePairsCompared)
            require(maxSimilarityPpm in 0..RiftB2BottomKDedupV1.SCORE_SCALE)
            require(histogramCounts.size == HISTOGRAM_BINS)
            require(histogramCounts.all { it >= 0L })
            require(histogramCounts.sum() == candidatePairsCompared)

            val hasPair = maxPairLeftSha256 != null
            require(
                hasPair ==
                    (maxPairRightSha256 != null &&
                        maxPairLeftSplit != null &&
                        maxPairRightSplit != null)
            ) {
                "near-dedup max-pair identity is partially populated"
            }
            if (hasPair) {
                require(
                    maxPairLeftSha256!!.matches(Regex("[0-9a-f]{64}")) &&
                        maxPairRightSha256!!.matches(Regex("[0-9a-f]{64}"))
                ) {
                    "near-dedup max-pair SHA-256 is not canonical"
                }
                require(
                    maxPairLeftSplit!! in RiftTrainDataV2Format.SPLIT_TRAIN..
                        RiftTrainDataV2Format.SPLIT_CHALLENGE &&
                        maxPairRightSplit!! in RiftTrainDataV2Format.SPLIT_TRAIN..
                        RiftTrainDataV2Format.SPLIT_CHALLENGE &&
                        maxPairLeftSplit != maxPairRightSplit
                ) {
                    "near-dedup max-pair split identity is invalid"
                }
            } else {
                require(candidatePairsCompared == 0L && maxSimilarityPpm == 0) {
                    "near-dedup missing max pair despite compared candidates"
                }
            }
        }

        fun toJson(): JSONObject {
            val histogram = JSONArray()
            histogramCounts.forEach { histogram.put(it) }

            val maxPair =
                if (maxPairLeftSha256 == null) {
                    JSONObject.NULL
                } else {
                    JSONObject()
                        .put("leftSampleSha256", maxPairLeftSha256)
                        .put("rightSampleSha256", maxPairRightSha256)
                        .put("leftSplitId", maxPairLeftSplit)
                        .put("rightSplitId", maxPairRightSplit)
                }

            return JSONObject()
                .put("indexId", INDEX_ID)
                .put("maxIndexedFingerprints", MAX_INDEXED_FINGERPRINTS)
                .put("maxCandidatePairs", MAX_CANDIDATE_PAIRS)
                .put("indexedSketchSamples", indexedSketchSamples)
                .put("indexedFingerprints", indexedFingerprints)
                .put("candidatePairsCompared", candidatePairsCompared)
                .put("positivePairs", positivePairs)
                .put("maxSimilarityPpm", maxSimilarityPpm)
                .put("maxPair", maxPair)
                .put("histogramBinPpm", HISTOGRAM_BIN_PPM)
                .put("histogramCounts", histogram)
        }
    }

    class Session(
        private val db: SQLiteDatabase,
        private val ensureRunning: () -> Unit = {}
    ) {
        private var indexedSketchSamples = 0L
        private var indexedFingerprints = 0L
        private var candidatePairsCompared = 0L
        private var positivePairs = 0L
        private var maxSimilarityPpm = 0
        private var maxPairLeftSha256: String? = null
        private var maxPairRightSha256: String? = null
        private var maxPairLeftSplit: Int? = null
        private var maxPairRightSplit: Int? = null
        private val histogramCounts = LongArray(HISTOGRAM_BINS)
        private var finished = false

        init {
            db.execSQL(
                "CREATE TABLE near_sample(" +
                    "id INTEGER PRIMARY KEY," +
                    "sample_sha TEXT NOT NULL UNIQUE," +
                    "split_id INTEGER NOT NULL," +
                    "sketch BLOB NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE near_fingerprint(" +
                    "fp TEXT NOT NULL," +
                    "sample_id INTEGER NOT NULL," +
                    "PRIMARY KEY(fp,sample_id)) WITHOUT ROWID"
            )
        }

        fun observe(
            sampleSha256: String,
            splitId: Int,
            sketch: RiftB2BottomKDedupV1.Sketch
        ) {
            ensureRunning()
            check(!finished) {
                "near-dedup comparator session already finished"
            }
            require(sampleSha256.matches(Regex("[0-9a-f]{64}"))) {
                "near-dedup sample SHA-256 is not canonical"
            }
            require(
                splitId in RiftTrainDataV2Format.SPLIT_TRAIN..
                    RiftTrainDataV2Format.SPLIT_CHALLENGE
            ) {
                "near-dedup split id is invalid"
            }

            if (sketch.isExactFallback) {
                return
            }

            compareWithPriorCrossSplit(sampleSha256, splitId, sketch)
            insertSketch(sampleSha256, splitId, sketch)
            indexedSketchSamples++
        }

        fun finish(): ComparisonEvidence {
            check(!finished) {
                "near-dedup comparator session already finished"
            }
            finished = true
            return ComparisonEvidence(
                indexedSketchSamples,
                indexedFingerprints,
                candidatePairsCompared,
                positivePairs,
                maxSimilarityPpm,
                maxPairLeftSha256,
                maxPairRightSha256,
                maxPairLeftSplit,
                maxPairRightSplit,
                histogramCounts.copyOf()
            )
        }

        private fun compareWithPriorCrossSplit(
            sampleSha256: String,
            splitId: Int,
            sketch: RiftB2BottomKDedupV1.Sketch
        ) {
            val placeholders =
                List(sketch.fingerprints.size) { "?" }.joinToString(",")
            val sql =
                "SELECT DISTINCT s.sample_sha,s.split_id,s.sketch " +
                    "FROM near_sample s " +
                    "JOIN near_fingerprint f ON f.sample_id=s.id " +
                    "WHERE s.split_id<>? AND f.fp IN (" +
                    placeholders +
                    ") ORDER BY s.sample_sha"
            val args = ArrayList<String>(sketch.fingerprints.size + 1)
            args += splitId.toString()
            args.addAll(sketch.fingerprints)

            db.rawQuery(sql, args.toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) {
                    if ((candidatePairsCompared and 255L) == 0L) {
                        ensureRunning()
                    }
                    val priorSha = cursor.getString(0)
                    val priorSplit = cursor.getInt(1)
                    val priorSketch = decodeSketch(cursor.getBlob(2))
                    val score =
                        RiftB2BottomKDedupV1.similarityPpm(
                            priorSketch,
                            sketch
                        )
                    recordComparison(
                        priorSha,
                        priorSplit,
                        sampleSha256,
                        splitId,
                        score
                    )
                }
            }
        }

        private fun insertSketch(
            sampleSha256: String,
            splitId: Int,
            sketch: RiftB2BottomKDedupV1.Sketch
        ) {
            val nextFingerprintCount =
                indexedFingerprints + sketch.fingerprints.size.toLong()
            require(nextFingerprintCount <= MAX_INDEXED_FINGERPRINTS) {
                "near-dedup fingerprint index limit exceeded"
            }
            val values = ContentValues(3).apply {
                put("sample_sha", sampleSha256)
                put("split_id", splitId)
                put("sketch", encodeSketch(sketch))
            }
            val sampleId = db.insertOrThrow("near_sample", null, values)
            require(sampleId > 0L) {
                "near-dedup sample insert returned invalid id"
            }
            sketch.fingerprints.forEachIndexed { index, fingerprint ->
                if ((index and 31) == 0) ensureRunning()
                db.execSQL(
                    "INSERT INTO near_fingerprint(fp,sample_id) VALUES(?,?)",
                    arrayOf<Any?>(fingerprint, sampleId)
                )
            }
            indexedFingerprints = nextFingerprintCount
        }

        private fun encodeSketch(
            sketch: RiftB2BottomKDedupV1.Sketch
        ): ByteArray {
            require(!sketch.isExactFallback) {
                "near-dedup indexed sketch may not be empty"
            }
            val out = ByteArray(sketch.fingerprints.size * 8)
            var cursor = 0
            sketch.fingerprints.forEach { fingerprint ->
                require(fingerprint.length == 16)
                for (index in 0 until 8) {
                    out[cursor++] =
                        fingerprint.substring(index * 2, index * 2 + 2)
                            .toInt(16)
                            .toByte()
                }
            }
            return out
        }

        private fun decodeSketch(
            encoded: ByteArray
        ): RiftB2BottomKDedupV1.Sketch {
            require(
                encoded.isNotEmpty() &&
                    encoded.size % 8 == 0 &&
                    encoded.size <= RiftB2BottomKDedupV1.BOTTOM_K * 8
            ) {
                "near-dedup indexed sketch blob is invalid"
            }
            val fingerprints = ArrayList<String>(encoded.size / 8)
            var cursor = 0
            while (cursor < encoded.size) {
                val value = buildString(16) {
                    repeat(8) {
                        append(
                            (encoded[cursor++].toInt() and 0xff)
                                .toString(16)
                                .padStart(2, '0')
                        )
                    }
                }
                fingerprints += value
            }
            return RiftB2BottomKDedupV1.Sketch(fingerprints)
        }

        private fun recordComparison(
            leftSha256: String,
            leftSplit: Int,
            rightSha256: String,
            rightSplit: Int,
            score: Int
        ) {
            require(leftSplit != rightSplit) {
                "near-dedup comparator received same-split pair"
            }
            require(candidatePairsCompared < MAX_CANDIDATE_PAIRS) {
                "near-dedup candidate-pair limit exceeded"
            }
            candidatePairsCompared++
            if (score > 0) positivePairs++

            val bin =
                (score / HISTOGRAM_BIN_PPM)
                    .coerceIn(0, HISTOGRAM_BINS - 1)
            histogramCounts[bin]++

            val canonicalLeft: String
            val canonicalRight: String
            val canonicalLeftSplit: Int
            val canonicalRightSplit: Int
            if (leftSha256 <= rightSha256) {
                canonicalLeft = leftSha256
                canonicalRight = rightSha256
                canonicalLeftSplit = leftSplit
                canonicalRightSplit = rightSplit
            } else {
                canonicalLeft = rightSha256
                canonicalRight = leftSha256
                canonicalLeftSplit = rightSplit
                canonicalRightSplit = leftSplit
            }

            val shouldReplace =
                score > maxSimilarityPpm ||
                    (
                        score == maxSimilarityPpm &&
                            (
                                maxPairLeftSha256 == null ||
                                    canonicalLeft < maxPairLeftSha256!! ||
                                    (
                                        canonicalLeft == maxPairLeftSha256 &&
                                            canonicalRight <
                                            maxPairRightSha256!!
                                        )
                                )
                        )
            if (shouldReplace) {
                maxSimilarityPpm = score
                maxPairLeftSha256 = canonicalLeft
                maxPairRightSha256 = canonicalRight
                maxPairLeftSplit = canonicalLeftSplit
                maxPairRightSplit = canonicalRightSplit
            }
        }
    }
}
