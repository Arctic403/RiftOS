package com.riftos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlin.math.ln

/**
 * Fixed-purpose native executor for RiftTokenizer V1 development tasks.
 *
 * This is deliberately not a Python/process runner. It mirrors the source-controlled
 * RiftTokenizer V1 reference algorithm while confining reads/writes to exact RiftLLM
 * tokenizer paths inside RiftFS.
 */
object RiftTextEncoderTaskRunner {
    private const val ARTIFACT_MAGIC = "RIFT_BYTE_BPE_V1"
    private const val TRAINER_ID = "rift-batch-bpe-v1"
    private const val VOCAB_SIZE = 32768
    private const val BYTE_TOKENS = 256
    private const val MERGE_TARGET = 32504
    private const val MAX_TRAINING_BYTES = 64L * 1024L * 1024L
    private const val MAX_SAMPLE_BYTES = 16 * 1024
    private val REQUIRED_CATEGORIES = listOf("prose", "code", "non_ascii")
    private val SPECIAL_LITERALS = listOf(
        "<|bos|>", "<|eos|>", "<|pad|>", "<|system|>",
        "<|user|>", "<|assistant|>", "<|tool|>", "<|end|>"
    ).map { it.toByteArray(Charsets.UTF_8) }

    private data class Candidate(
        val action: String,
        val candidateId: String,
        val configRelative: String,
        val outputRelative: String,
        val configFileSha256: String,
        val canonicalConfigSha256: String,
        val scoreMode: String
    )

    private val candidates = mapOf(
        "train-a" to Candidate(
            action = "train-a",
            candidateId = "rift-token-a-frequency-v1",
            configRelative = "tokenizer/configs/rift-text-a-frequency-v1.json",
            outputRelative = "tokenizer/output/rift-token-a-frequency-v1.riftbpe",
            configFileSha256 = "ccf9e36e135da05beb9203a2496391ac3ab6b58c32dc697ee8b1c36174602606",
            canonicalConfigSha256 = "463bb9e8af82e1094b50702888986e144eec504cc7939cbedcbfad50503669b3",
            scoreMode = "frequency"
        ),
        "train-b" to Candidate(
            action = "train-b",
            candidateId = "rift-token-b-balanced-v1",
            configRelative = "tokenizer/configs/rift-text-b-balanced-v1.json",
            outputRelative = "tokenizer/output/rift-token-b-balanced-v1.riftbpe",
            configFileSha256 = "eed655f1902cf021b5f7faf5a070a2f5085913053908c1f2d4b2a67ee18cbeb2",
            canonicalConfigSha256 = "9ff5eefb11e76086c244a90d67630c7ca0092deed3d47bfa1f1a9aa3160accf6",
            scoreMode = "category_balanced"
        )
    )

    private data class TokenSequence(val category: String, var tokens: IntArray)
    private data class PairKey(val left: Int, val right: Int)
    private data class RankedPair(val score: Double, val rawCount: Int, val pair: PairKey)
    private data class TrainingInput(
        val sequences: MutableList<TokenSequence>,
        val sampleCount: Int,
        val initialByteTokens: Long,
        val categoryCounts: Map<String, Int>
    )

    fun execute(context: Context, actionRaw: String): JSONObject {
        val action = actionRaw.trim().lowercase().ifBlank { "status" }
        return when (action) {
            "status" -> status(context)
            "self-test" -> selfTest()
            "train-a", "train-b" -> trainCandidate(context, candidates.getValue(action))
            else -> throw IllegalArgumentException("usage: rift-cli tokenizer status|self-test|train-a|train-b")
        }
    }

    private fun projectRoot(context: Context): File {
        val riftRoot = File(context.applicationContext.filesDir, "riftfs").canonicalFile
        val project = File(riftRoot, "workspace/RiftLLM").canonicalFile
        require(project.path.startsWith(riftRoot.path + File.separator)) { "RiftLLM path escaped RiftFS" }
        require(project.isDirectory) { "RiftLLM workspace project is missing" }
        return project
    }

    private fun exactFile(root: File, relative: String, mustExist: Boolean = true): File {
        require(relative.isNotBlank() && !relative.startsWith('/') && !relative.contains("..")) { "invalid fixed tokenizer path" }
        val file = File(root, relative).canonicalFile
        require(file.path.startsWith(root.path + File.separator)) { "tokenizer path escaped RiftLLM project" }
        if (mustExist) require(file.isFile) { "required tokenizer file is missing: $relative" }
        return file
    }

    private fun status(context: Context): JSONObject {
        val root = projectRoot(context)
        val trainFile = exactFile(root, "tokenizer/private/build/train.jsonl", mustExist = false)
        val heldoutFile = exactFile(root, "tokenizer/private/build/heldout.tsv", mustExist = false)
        val value = JSONObject()
            .put("schema", "rift.experimental-tokenizer-task/1")
            .put("experimental", true)
            .put("processExecution", false)
            .put("pythonRuntimeRequired", false)
            .put("trainingFile", fileInfo(root, trainFile))
            .put("heldoutFile", fileInfo(root, heldoutFile))
            .put("mergeTarget", MERGE_TARGET)
            .put("vocabularySize", VOCAB_SIZE)

        if (trainFile.isFile) {
            val input = loadTraining(trainFile)
            value.put("sampleCount", input.sampleCount)
                .put("initialByteTokens", input.initialByteTokens)
                .put("categoryCounts", JSONObject(input.categoryCounts))
            val minimumBudget = input.sampleCount.toLong() + MERGE_TARGET.toLong()
            value.put("minimumByteTokenBudgetForMergeCount", minimumBudget)
                .put("mergeCountLowerBoundSatisfied", input.initialByteTokens >= minimumBudget)
        }

        val candidateRows = JSONArray()
        for (candidate in candidates.values.sortedBy { it.action }) {
            val config = exactFile(root, candidate.configRelative, mustExist = false)
            val output = exactFile(root, candidate.outputRelative, mustExist = false)
            candidateRows.put(JSONObject()
                .put("action", candidate.action)
                .put("candidateId", candidate.candidateId)
                .put("scoreMode", candidate.scoreMode)
                .put("config", fileInfo(root, config))
                .put("configHashMatchesPinned", config.isFile && sha256File(config) == candidate.configFileSha256)
                .put("artifact", fileInfo(root, output)))
        }
        return value.put("candidates", candidateRows)
    }

    private fun trainCandidate(context: Context, candidate: Candidate): JSONObject {
        val root = projectRoot(context)
        val trainFile = exactFile(root, "tokenizer/private/build/train.jsonl")
        val configFile = exactFile(root, candidate.configRelative)
        require(sha256File(configFile) == candidate.configFileSha256) {
            "${candidate.candidateId} config changed; update the fixed native tokenizer task only after reviewing the reference config"
        }
        validateConfig(configFile, candidate)
        val input = loadTraining(trainFile)
        val minimumPairCount = 2
        val minimumBudget = input.sampleCount.toLong() + MERGE_TARGET.toLong()
        require(input.initialByteTokens >= minimumBudget) {
            "training corpus cannot mathematically emit 32,504 merges: ${input.initialByteTokens} byte tokens available, at least $minimumBudget required even before pair-diversity constraints; expand RiftCorpus first"
        }

        val merges = train(input.sequences, candidate.scoreMode, minimumPairCount, batchSize = 64)
        require(merges.size == MERGE_TARGET) { "expected $MERGE_TARGET merges, got ${merges.size}" }
        val trainingSha = sha256File(trainFile)
        val output = exactFile(root, candidate.outputRelative, mustExist = false)
        output.parentFile?.mkdirs()
        writeArtifact(output, candidate, trainingSha, merges)
        val artifactSha = sha256File(output)
        val manifest = JSONObject()
            .put("artifactSha256", artifactSha)
            .put("byteFallback", true)
            .put("candidateId", candidate.candidateId)
            .put("format", "rift-tokenizer-training-result-v1")
            .put("mergeCount", merges.size)
            .put("normalization", "identity-utf8")
            .put("scoreMode", candidate.scoreMode)
            .put("specialTokenCount", SPECIAL_LITERALS.size)
            .put("trainer", TRAINER_ID)
            .put("trainerConfigSha256", candidate.canonicalConfigSha256)
            .put("trainingCorpusSha256", trainingSha)
            .put("vocabularySize", VOCAB_SIZE)
        val manifestFile = File(output.parentFile, output.name + ".manifest.json")
        manifestFile.writeText(sortedManifest(manifest) + "\n", Charsets.UTF_8)
        return JSONObject(manifest.toString())
            .put("artifactPath", relativePath(root, output))
            .put("manifestPath", relativePath(root, manifestFile))
            .put("nativeTaskRunner", true)
    }

    private fun validateConfig(file: File, candidate: Candidate) {
        val obj = JSONObject(decodeStrictUtf8(readBounded(file, 256 * 1024L)))
        require(obj.optString("candidate_id") == candidate.candidateId) { "candidate_id mismatch" }
        require(obj.optString("trainer") == TRAINER_ID) { "trainer must be $TRAINER_ID" }
        require(obj.optInt("vocab_size") == VOCAB_SIZE) { "vocab_size must be $VOCAB_SIZE" }
        require(obj.optString("normalization") == "identity-utf8") { "normalization must be identity-utf8" }
        require(obj.optBoolean("byte_fallback", false)) { "byte_fallback must be true" }
        require(obj.optString("score_mode") == candidate.scoreMode) { "score_mode mismatch" }
        require(obj.optInt("batch_merges") == 64) { "batch_merges must be 64 for V1 native parity" }
        require(obj.optInt("minimum_pair_count", 2) == 2) { "minimum_pair_count must be 2 for V1 native parity" }
        if (candidate.scoreMode == "category_balanced") {
            val weights = obj.optJSONObject("category_weights") ?: throw IllegalArgumentException("category_weights missing")
            require(REQUIRED_CATEGORIES.all { weights.optDouble(it, -1.0) == 1.0 }) { "V1 balanced category weights must all equal 1.0" }
        }
    }

    private fun loadTraining(file: File): TrainingInput {
        require(file.length() in 1..MAX_TRAINING_BYTES) { "training split must be 1..$MAX_TRAINING_BYTES bytes" }
        val text = decodeStrictUtf8(readBounded(file, MAX_TRAINING_BYTES))
        val sequences = mutableListOf<TokenSequence>()
        val categories = linkedMapOf("prose" to 0, "code" to 0, "non_ascii" to 0)
        var byteTokens = 0L
        text.lineSequence().forEachIndexed { index, line ->
            if (line.isBlank()) return@forEachIndexed
            val obj = try { JSONObject(line) } catch (error: Throwable) {
                throw IllegalArgumentException("line ${index + 1}: invalid training JSON: ${error.message}")
            }
            val category = obj.optString("category")
            require(category in REQUIRED_CATEGORIES) { "line ${index + 1}: invalid category" }
            val sample = obj.optString("text")
            require(sample.isNotEmpty()) { "line ${index + 1}: text must be non-empty" }
            val bytes = sample.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_SAMPLE_BYTES) { "line ${index + 1}: text exceeds $MAX_SAMPLE_BYTES UTF-8 bytes" }
            val tokens = IntArray(bytes.size) { bytes[it].toInt() and 0xff }
            sequences += TokenSequence(category, tokens)
            categories[category] = categories.getValue(category) + 1
            byteTokens += bytes.size
        }
        require(sequences.isNotEmpty()) { "training split is empty" }
        for (category in REQUIRED_CATEGORIES) require(categories.getValue(category) > 0) { "training split contains no $category samples" }
        return TrainingInput(sequences, sequences.size, byteTokens, categories)
    }

    private fun train(
        sequences: MutableList<TokenSequence>,
        scoreMode: String,
        minimumPairCount: Int,
        batchSize: Int
    ): List<PairKey> {
        val merges = ArrayList<PairKey>(MERGE_TARGET)
        val emitted = HashSet<PairKey>(MERGE_TARGET * 2)
        while (merges.size < MERGE_TARGET) {
            val total = HashMap<PairKey, Int>()
            val byCategory = REQUIRED_CATEGORIES.associateWith { HashMap<PairKey, Int>() }.toMutableMap()
            val categoryPairTotals = REQUIRED_CATEGORIES.associateWith { 0L }.toMutableMap()
            for (sequence in sequences) {
                val local = byCategory.getValue(sequence.category)
                val tokens = sequence.tokens
                for (index in 0 until tokens.size - 1) {
                    val pair = PairKey(tokens[index], tokens[index + 1])
                    total[pair] = (total[pair] ?: 0) + 1
                    local[pair] = (local[pair] ?: 0) + 1
                    categoryPairTotals[sequence.category] = categoryPairTotals.getValue(sequence.category) + 1L
                }
            }
            val ranked = ArrayList<RankedPair>()
            for ((pair, rawCount) in total) {
                if (rawCount < minimumPairCount || pair in emitted) continue
                val score = if (scoreMode == "frequency") {
                    rawCount.toDouble()
                } else {
                    var normalized = 0.0
                    for (category in REQUIRED_CATEGORIES) {
                        val denom = categoryPairTotals.getValue(category).coerceAtLeast(1L).toDouble()
                        normalized += (byCategory.getValue(category)[pair] ?: 0).toDouble() / denom
                    }
                    normalized * (ln(rawCount + 1.0) / ln(2.0))
                }
                ranked += RankedPair(score, rawCount, pair)
            }
            ranked.sortWith(compareByDescending<RankedPair> { it.score }
                .thenByDescending { it.rawCount }
                .thenBy { it.pair.left }
                .thenBy { it.pair.right })
            if (ranked.isEmpty()) throw IllegalStateException("training corpus exhausted after ${merges.size} merges; add more diverse custom data before producing a 32K artifact")

            var accepted = 0
            val scanLimit = minOf(ranked.size, batchSize * 4)
            for (rankedIndex in 0 until scanLimit) {
                if (merges.size >= MERGE_TARGET || accepted >= batchSize) break
                val pair = ranked[rankedIndex].pair
                val resultId = BYTE_TOKENS + merges.size
                var totalApplied = 0
                for (sequence in sequences) {
                    val applied = applyMerge(sequence.tokens, pair, resultId)
                    sequence.tokens = applied.first
                    totalApplied += applied.second
                }
                emitted += pair
                if (totalApplied == 0) continue
                merges += pair
                accepted++
            }
            if (accepted == 0) throw IllegalStateException("no ranked pair remained applicable; corpus cannot fill the requested vocabulary")
        }
        return merges
    }

    private fun applyMerge(tokens: IntArray, pair: PairKey, resultId: Int): Pair<IntArray, Int> {
        if (tokens.size < 2) return tokens to 0
        val out = IntArray(tokens.size)
        var input = 0
        var output = 0
        var merged = 0
        while (input < tokens.size) {
            if (input + 1 < tokens.size && tokens[input] == pair.left && tokens[input + 1] == pair.right) {
                out[output++] = resultId
                merged++
                input += 2
            } else {
                out[output++] = tokens[input++]
            }
        }
        return if (output == out.size) out to merged else out.copyOf(output) to merged
    }

    private fun writeArtifact(file: File, candidate: Candidate, trainingSha: String, merges: List<PairKey>) {
        val specialStart = BYTE_TOKENS + merges.size
        val text = buildString {
            append(ARTIFACT_MAGIC).append('\n')
            append("candidate_id=").append(candidate.candidateId).append('\n')
            append("training_corpus_sha256=").append(trainingSha).append('\n')
            append("trainer_config_sha256=").append(candidate.canonicalConfigSha256).append('\n')
            append("vocab_size=").append(VOCAB_SIZE).append('\n')
            append("normalization=identity-utf8\n")
            append("byte_fallback=true\n")
            SPECIAL_LITERALS.forEachIndexed { offset, literal ->
                append("special=").append(specialStart + offset).append(':').append(hex(literal)).append('\n')
            }
            append("merges_begin\n")
            for (merge in merges) append(merge.left).append(' ').append(merge.right).append('\n')
            append("merges_end\n")
        }
        file.writeText(text, Charsets.UTF_8)
    }

    private fun selfTest(): JSONObject {
        val first = applyMerge(intArrayOf(97, 98, 97, 98), PairKey(97, 98), 256)
        require(first.first.contentEquals(intArrayOf(256, 256)) && first.second == 2) { "native merge self-test failed" }
        require(MERGE_TARGET + BYTE_TOKENS + SPECIAL_LITERALS.size == VOCAB_SIZE) { "vocabulary arithmetic self-test failed" }
        return JSONObject()
            .put("schema", "rift.experimental-tokenizer-task/1")
            .put("selfTestPassed", true)
            .put("artifactMagic", ARTIFACT_MAGIC)
            .put("trainer", TRAINER_ID)
            .put("vocabularySize", VOCAB_SIZE)
            .put("mergeTarget", MERGE_TARGET)
            .put("processExecution", false)
    }

    private fun readBounded(file: File, limit: Long): ByteArray {
        require(file.length() <= limit) { "file exceeds bounded tokenizer task limit: ${file.name}" }
        return file.inputStream().buffered().use { it.readBytes() }
    }

    private fun decodeStrictUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun relativePath(root: File, file: File): String = file.relativeTo(root).invariantSeparatorsPath

    private fun fileInfo(root: File, file: File): JSONObject = JSONObject()
        .put("path", relativePath(root, file))
        .put("exists", file.isFile)
        .put("size", if (file.isFile) file.length() else 0L)
        .put("sha256", if (file.isFile) sha256File(file) else JSONObject.NULL)

    private fun sortedManifest(manifest: JSONObject): String {
        val keys = manifest.keys().asSequence().toList().sorted()
        return buildString {
            append("{\n")
            keys.forEachIndexed { index, key ->
                append("  ").append(JSONObject.quote(key)).append(": ")
                val value = manifest.get(key)
                append(when (value) {
                    is String -> JSONObject.quote(value)
                    is Boolean, is Number -> value.toString()
                    JSONObject.NULL -> "null"
                    else -> JSONObject.quote(value.toString())
                })
                if (index != keys.lastIndex) append(',')
                append('\n')
            }
            append('}')
        }
    }
}
