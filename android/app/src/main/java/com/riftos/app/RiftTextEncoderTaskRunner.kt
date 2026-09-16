package com.riftos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
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
        val categoryCounts: Map<String, Int>,
        val sourceSha256: String
    )
    private data class TrainingMetadata(
        val sampleCount: Int,
        val initialByteTokens: Long,
        val categoryCounts: Map<String, Int>,
        val sourceSha256: String
    )
    private data class BatchResult(
        val accepted: List<PairKey>,
        val examined: List<PairKey>,
        val totalApplied: Long
    )
    private data class OutputPaths(
        val artifact: File,
        val manifest: File,
        val stagedArtifact: File,
        val stagedManifest: File,
        val backupArtifact: File,
        val backupManifest: File,
        val marker: File
    )
    private data class TrainingJob(
        val id: String,
        val action: String,
        val candidateId: String,
        val scoreMode: String,
        val startedAtMs: Long,
        var updatedAtMs: Long = startedAtMs,
        var state: String = "queued",
        var batchesCompleted: Int = 0,
        var mergesCompleted: Int = 0,
        var sampleCount: Int = 0,
        var initialByteTokens: Long = 0,
        var currentTokens: Long = 0,
        var message: String = "queued",
        var error: String? = null,
        var artifactPath: String? = null,
        var manifestPath: String? = null
    )

    private val trainingExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "rift-tokenizer-train").apply { isDaemon = true }
    }
    private val jobLock = Any()
    private var activeJob: TrainingJob? = null
    private var activeCancel: AtomicBoolean? = null

    fun execute(context: Context, actionRaw: String): JSONObject {
        val action = actionRaw.trim().lowercase().ifBlank { "status" }
        return when (action) {
            "status" -> status(context)
            "self-test" -> selfTest()
            "train-a", "train-b" -> startTraining(context.applicationContext, candidates.getValue(action))
            "train-status" -> trainingJobJson()
            "train-cancel" -> cancelActive("manual tokenizer cancel requested")
            else -> throw IllegalArgumentException("usage: rift-cli tokenizer status|self-test|train-a|train-b|train-status|train-cancel")
        }
    }

    fun cancelActive(reason: String = "tokenizer training cancelled"): JSONObject {
        synchronized(jobLock) {
            val job = activeJob
            if (job != null && (job.state == "queued" || job.state == "running" || job.state == "cancelling")) {
                job.state = "cancelling"
                job.message = reason
                job.updatedAtMs = System.currentTimeMillis()
                activeCancel?.set(true)
            }
        }
        return trainingJobJson()
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
        val metadata = if (trainFile.isFile) scanTrainingMetadata(trainFile) else null
        val value = JSONObject()
            .put("schema", "rift.experimental-tokenizer-task/1")
            .put("experimental", true)
            .put("processExecution", false)
            .put("pythonRuntimeRequired", false)
            .put("statusLoadsTokenArrays", false)
            .put("trainingFile", fileInfo(root, trainFile, metadata?.sourceSha256))
            .put("heldoutFile", fileInfo(root, heldoutFile))
            .put("mergeTarget", MERGE_TARGET)
            .put("vocabularySize", VOCAB_SIZE)

        if (metadata != null) {
            value.put("sampleCount", metadata.sampleCount)
                .put("initialByteTokens", metadata.initialByteTokens)
                .put("categoryCounts", JSONObject(metadata.categoryCounts))
            val minimumBudget = metadata.sampleCount.toLong() + MERGE_TARGET.toLong()
            value.put("minimumByteTokenBudgetForMergeCount", minimumBudget)
                .put("mergeCountLowerBoundSatisfied", metadata.initialByteTokens >= minimumBudget)
        }

        val candidateRows = JSONArray()
        for (candidate in candidates.values.sortedBy { it.action }) {
            val config = exactFile(root, candidate.configRelative, mustExist = false)
            val recovery = recoverOutputPair(root, candidate)
            val paths = outputPaths(root, candidate)
            val pairValid = outputPairValid(candidate, paths.artifact, paths.manifest)
            candidateRows.put(JSONObject()
                .put("action", candidate.action)
                .put("candidateId", candidate.candidateId)
                .put("scoreMode", candidate.scoreMode)
                .put("config", fileInfo(root, config))
                .put("configHashMatchesPinned", config.isFile && sha256File(config) == candidate.configFileSha256)
                .put("artifact", fileInfo(root, paths.artifact))
                .put("manifest", fileInfo(root, paths.manifest))
                .put("artifactManifestPairValid", pairValid)
                .put("outputRecovery", recovery))
        }
        return value.put("candidates", candidateRows)
            .put("trainingJob", trainingJobJson())
    }

    private fun trainingJobJson(): JSONObject = synchronized(jobLock) {
        val job = activeJob ?: return@synchronized JSONObject()
            .put("schema", "rift.experimental-tokenizer-job/1")
            .put("active", false)
            .put("state", "idle")
            .put("mergeTarget", MERGE_TARGET)
        val active = job.state == "queued" || job.state == "running" || job.state == "cancelling"
        val effectiveEnd = if (active) System.currentTimeMillis() else job.updatedAtMs
        JSONObject()
            .put("schema", "rift.experimental-tokenizer-job/1")
            .put("active", active)
            .put("jobId", job.id)
            .put("action", job.action)
            .put("candidateId", job.candidateId)
            .put("scoreMode", job.scoreMode)
            .put("state", job.state)
            .put("batchesCompleted", job.batchesCompleted)
            .put("mergesCompleted", job.mergesCompleted)
            .put("mergeTarget", MERGE_TARGET)
            .put("progressPermyriad", ((job.mergesCompleted.toLong() * 10_000L) / MERGE_TARGET).toInt())
            .put("sampleCount", job.sampleCount)
            .put("initialByteTokens", job.initialByteTokens)
            .put("currentTokens", job.currentTokens)
            .put("startedAtMs", job.startedAtMs)
            .put("updatedAtMs", job.updatedAtMs)
            .put("elapsedMs", (effectiveEnd - job.startedAtMs).coerceAtLeast(0L))
            .put("message", job.message)
            .put("error", job.error ?: JSONObject.NULL)
            .put("artifactPath", job.artifactPath ?: JSONObject.NULL)
            .put("manifestPath", job.manifestPath ?: JSONObject.NULL)
    }

    private fun updateJob(job: TrainingJob, block: (TrainingJob) -> Unit) {
        synchronized(jobLock) {
            if (activeJob !== job) return
            block(job)
            job.updatedAtMs = System.currentTimeMillis()
        }
    }

    private fun startTraining(context: Context, candidate: Candidate): JSONObject {
        synchronized(jobLock) {
            val current = activeJob
            require(current == null || (current.state != "queued" && current.state != "running" && current.state != "cancelling")) {
                "tokenizer training is already active: ${current?.candidateId ?: "unknown"}"
            }
        }
        val root = projectRoot(context)
        recoverOutputPair(root, candidate)
        val existingPaths = outputPaths(root, candidate)
        require(
            !(existingPaths.artifact.exists() || existingPaths.manifest.exists()) ||
                outputPairValid(candidate, existingPaths.artifact, existingPaths.manifest)
        ) { "existing ${candidate.candidateId} output is not a valid artifact/manifest pair; repair or remove the incomplete private output before training" }
        val cancel = AtomicBoolean(false)
        val job: TrainingJob
        synchronized(jobLock) {
            val current = activeJob
            require(current == null || (current.state != "queued" && current.state != "running" && current.state != "cancelling")) {
                "tokenizer training is already active: ${current?.candidateId ?: "unknown"}"
            }
            val now = System.currentTimeMillis()
            job = TrainingJob(
                id = "${candidate.candidateId}-$now",
                action = candidate.action,
                candidateId = candidate.candidateId,
                scoreMode = candidate.scoreMode,
                startedAtMs = now
            )
            activeJob = job
            activeCancel = cancel
        }
        trainingExecutor.execute { runTrainingJob(context, candidate, job, cancel) }
        return trainingJobJson()
    }

    private fun runTrainingJob(context: Context, candidate: Candidate, job: TrainingJob, cancel: AtomicBoolean) {
        try {
            updateJob(job) {
                it.state = "running"
                it.message = "validating pinned config and loading training split"
                it.error = null
            }
            val result = trainCandidateBlocking(context, candidate, job, cancel)
            updateJob(job) {
                it.state = "complete"
                it.mergesCompleted = MERGE_TARGET
                it.message = "tokenizer artifact complete"
                it.artifactPath = result.optString("artifactPath").takeIf { path -> path.isNotBlank() }
                it.manifestPath = result.optString("manifestPath").takeIf { path -> path.isNotBlank() }
            }
        } catch (_: CancellationException) {
            updateJob(job) {
                it.state = "cancelled"
                it.message = "tokenizer training cancelled"
            }
        } catch (error: Throwable) {
            updateJob(job) {
                it.state = "failed"
                it.message = "tokenizer training failed"
                it.error = error.message ?: error::class.java.simpleName
            }
        } finally {
            synchronized(jobLock) {
                if (activeCancel === cancel) activeCancel = null
            }
        }
    }

    private fun ensureNotCancelled(cancel: AtomicBoolean) {
        if (cancel.get() || Thread.currentThread().isInterrupted) throw CancellationException("tokenizer training cancelled")
    }

    private fun trainCandidateBlocking(context: Context, candidate: Candidate, job: TrainingJob, cancel: AtomicBoolean): JSONObject {
        val root = projectRoot(context)
        val trainFile = exactFile(root, "tokenizer/private/build/train.jsonl")
        val configFile = exactFile(root, candidate.configRelative)
        require(sha256File(configFile) == candidate.configFileSha256) {
            "${candidate.candidateId} config changed; update the fixed native tokenizer task only after reviewing the reference config"
        }
        validateConfig(configFile, candidate)
        ensureNotCancelled(cancel)
        val input = loadTraining(trainFile)
        val minimumPairCount = 2
        val minimumBudget = input.sampleCount.toLong() + MERGE_TARGET.toLong()
        require(input.initialByteTokens >= minimumBudget) {
            "training corpus cannot mathematically emit 32,504 merges: ${input.initialByteTokens} byte tokens available, at least $minimumBudget required even before pair-diversity constraints; expand RiftCorpus first"
        }
        updateJob(job) {
            it.sampleCount = input.sampleCount
            it.initialByteTokens = input.initialByteTokens
            it.currentTokens = input.initialByteTokens
            it.message = "training ${candidate.candidateId} with bounded 64-merge batches"
        }

        val merges = train(
            input.sequences,
            candidate.scoreMode,
            minimumPairCount,
            batchSize = 64,
            cancel = cancel
        ) { mergesCompleted, batchesCompleted, currentTokens ->
            updateJob(job) {
                it.mergesCompleted = mergesCompleted
                it.batchesCompleted = batchesCompleted
                it.currentTokens = currentTokens
                it.message = "training ${candidate.candidateId}: $mergesCompleted/$MERGE_TARGET merges"
            }
        }
        ensureNotCancelled(cancel)
        require(merges.size == MERGE_TARGET) { "expected $MERGE_TARGET merges, got ${merges.size}" }
        require(sha256File(trainFile) == input.sourceSha256) {
            "training corpus changed while ${candidate.candidateId} was training; refusing to publish an artifact from a moving input"
        }
        return commitOutputPair(
            root = root,
            trainFile = trainFile,
            candidate = candidate,
            trainingSha = input.sourceSha256,
            merges = merges,
            cancel = cancel
        ).put("nativeTaskRunner", true)
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
        val sourceBytes = readBounded(file, MAX_TRAINING_BYTES)
        val sourceSha256 = sha256Bytes(sourceBytes)
        val text = decodeStrictUtf8(sourceBytes)
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
        return TrainingInput(sequences, sequences.size, byteTokens, categories, sourceSha256)
    }

    private fun scanTrainingMetadata(file: File): TrainingMetadata {
        require(file.length() in 1..MAX_TRAINING_BYTES) { "training split must be 1..$MAX_TRAINING_BYTES bytes" }
        val digest = MessageDigest.getInstance("SHA-256")
        val categories = linkedMapOf("prose" to 0, "code" to 0, "non_ascii" to 0)
        var samples = 0
        var byteTokens = 0L
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        file.inputStream().buffered().use { buffered ->
            DigestInputStream(buffered, digest).use { digested ->
                BufferedReader(InputStreamReader(digested, decoder)).use { reader ->
                    var lineNumber = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        lineNumber++
                        if (line.isBlank()) continue
                        val obj = try { JSONObject(line) } catch (error: Throwable) {
                            throw IllegalArgumentException("line $lineNumber: invalid training JSON: ${error.message}")
                        }
                        val category = obj.optString("category")
                        require(category in REQUIRED_CATEGORIES) { "line $lineNumber: invalid category" }
                        val sample = obj.optString("text")
                        require(sample.isNotEmpty()) { "line $lineNumber: text must be non-empty" }
                        val sampleBytes = sample.toByteArray(Charsets.UTF_8)
                        require(sampleBytes.size <= MAX_SAMPLE_BYTES) { "line $lineNumber: text exceeds $MAX_SAMPLE_BYTES UTF-8 bytes" }
                        categories[category] = categories.getValue(category) + 1
                        samples++
                        byteTokens += sampleBytes.size
                    }
                }
            }
        }
        require(samples > 0) { "training split is empty" }
        for (category in REQUIRED_CATEGORIES) require(categories.getValue(category) > 0) { "training split contains no $category samples" }
        return TrainingMetadata(samples, byteTokens, categories, hex(digest.digest()))
    }

    private fun train(
        sequences: MutableList<TokenSequence>,
        scoreMode: String,
        minimumPairCount: Int,
        batchSize: Int,
        cancel: AtomicBoolean,
        onProgress: (mergesCompleted: Int, batchesCompleted: Int, currentTokens: Long) -> Unit
    ): List<PairKey> {
        val merges = ArrayList<PairKey>(MERGE_TARGET)
        val emitted = HashSet<PairKey>(MERGE_TARGET * 2)
        var currentTokens = sequences.sumOf { it.tokens.size.toLong() }
        var batchesCompleted = 0
        while (merges.size < MERGE_TARGET) {
            ensureNotCancelled(cancel)
            val total = HashMap<PairKey, Int>()
            val balanced = scoreMode == "category_balanced"
            val byCategory = if (balanced) REQUIRED_CATEGORIES.associateWith { HashMap<PairKey, Int>() }.toMutableMap() else null
            val categoryPairTotals = if (balanced) REQUIRED_CATEGORIES.associateWith { 0L }.toMutableMap() else null
            for ((sequenceIndex, sequence) in sequences.withIndex()) {
                if ((sequenceIndex and 127) == 0) ensureNotCancelled(cancel)
                val local = byCategory?.get(sequence.category)
                val tokens = sequence.tokens
                for (index in 0 until tokens.size - 1) {
                    val pair = PairKey(tokens[index], tokens[index + 1])
                    total[pair] = (total[pair] ?: 0) + 1
                    if (balanced) {
                        local!![pair] = (local[pair] ?: 0) + 1
                        categoryPairTotals!![sequence.category] = categoryPairTotals!!.getValue(sequence.category) + 1L
                    }
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
                        val denom = categoryPairTotals!!.getValue(category).coerceAtLeast(1L).toDouble()
                        normalized += (byCategory!!.getValue(category)[pair] ?: 0).toDouble() / denom
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

            val scanLimit = minOf(ranked.size, batchSize * 4)
            val batch = applyRankedBatch(
                sequences = sequences,
                ranked = ranked,
                scanLimit = scanLimit,
                batchSize = minOf(batchSize, MERGE_TARGET - merges.size),
                baseMergeCount = merges.size,
                cancel = cancel
            )
            if (batch.accepted.isEmpty()) throw IllegalStateException("no ranked pair remained applicable; corpus cannot fill the requested vocabulary")
            emitted.addAll(batch.examined)
            merges.addAll(batch.accepted)
            currentTokens -= batch.totalApplied
            batchesCompleted++
            onProgress(merges.size, batchesCompleted, currentTokens)
        }
        return merges
    }
    private fun pairCode(left: Int, right: Int): Long =
        (left.toLong() shl 32) xor (right.toLong() and 0xffffffffL)

    private fun selectBatchEdges(
        tokens: IntArray,
        candidateRanks: Map<Long, Int>,
        scanLimit: Int,
        allowedRanks: BooleanArray? = null
    ): IntArray {
        val selected = IntArray(maxOf(0, tokens.size - 1)) { -1 }
        if (tokens.size < 2) return selected
        val edgeRanks = IntArray(tokens.size - 1) { -1 }
        val bucketCounts = IntArray(scanLimit)
        var candidateEdges = 0
        for (position in edgeRanks.indices) {
            val rank = candidateRanks[pairCode(tokens[position], tokens[position + 1])] ?: -1
            if (rank < 0 || rank >= scanLimit || (allowedRanks != null && !allowedRanks[rank])) continue
            edgeRanks[position] = rank
            bucketCounts[rank]++
            candidateEdges++
        }
        if (candidateEdges == 0) return selected

        val offsets = IntArray(scanLimit + 1)
        for (rank in 0 until scanLimit) offsets[rank + 1] = offsets[rank] + bucketCounts[rank]
        val cursor = offsets.copyOf()
        val orderedPositions = IntArray(candidateEdges)
        for (position in edgeRanks.indices) {
            val rank = edgeRanks[position]
            if (rank >= 0) orderedPositions[cursor[rank]++] = position
        }

        val used = BooleanArray(tokens.size)
        for (rank in 0 until scanLimit) {
            for (orderedIndex in offsets[rank] until offsets[rank + 1]) {
                val position = orderedPositions[orderedIndex]
                if (!used[position] && !used[position + 1]) {
                    used[position] = true
                    used[position + 1] = true
                    selected[position] = rank
                }
            }
        }
        return selected
    }

    private fun applyRankedBatch(
        sequences: MutableList<TokenSequence>,
        ranked: List<RankedPair>,
        scanLimit: Int,
        batchSize: Int,
        baseMergeCount: Int,
        cancel: AtomicBoolean
    ): BatchResult {
        require(scanLimit in 1..ranked.size) { "invalid ranked batch scan limit" }
        require(batchSize >= 1) { "invalid ranked batch size" }
        val candidateRanks = HashMap<Long, Int>(scanLimit * 2)
        for (rank in 0 until scanLimit) {
            val pair = ranked[rank].pair
            candidateRanks[pairCode(pair.left, pair.right)] = rank
        }

        val appliedCounts = IntArray(scanLimit)
        for ((sequenceIndex, sequence) in sequences.withIndex()) {
            if ((sequenceIndex and 127) == 0) ensureNotCancelled(cancel)
            val selected = selectBatchEdges(sequence.tokens, candidateRanks, scanLimit)
            for (rank in selected) if (rank >= 0) appliedCounts[rank]++
        }

        val acceptedRanks = ArrayList<Int>(batchSize)
        for (rank in 0 until scanLimit) {
            if (appliedCounts[rank] > 0) acceptedRanks += rank
            if (acceptedRanks.size >= batchSize) break
        }
        val processedCount = if (acceptedRanks.size >= batchSize) acceptedRanks.last() + 1 else scanLimit
        val examined = (0 until processedCount).map { ranked[it].pair }
        if (acceptedRanks.isEmpty()) return BatchResult(emptyList(), examined, 0L)

        val allowedRanks = BooleanArray(scanLimit)
        val resultIdByRank = IntArray(scanLimit) { -1 }
        acceptedRanks.forEachIndexed { offset, rank ->
            allowedRanks[rank] = true
            resultIdByRank[rank] = BYTE_TOKENS + baseMergeCount + offset
        }

        val verificationCounts = IntArray(scanLimit)
        var totalApplied = 0L
        for ((sequenceIndex, sequence) in sequences.withIndex()) {
            if ((sequenceIndex and 127) == 0) ensureNotCancelled(cancel)
            val tokens = sequence.tokens
            val selected = selectBatchEdges(tokens, candidateRanks, scanLimit, allowedRanks)
            val out = IntArray(tokens.size)
            var input = 0
            var output = 0
            while (input < tokens.size) {
                val rank = if (input < selected.size) selected[input] else -1
                if (rank >= 0) {
                    val resultId = resultIdByRank[rank]
                    require(resultId >= BYTE_TOKENS) { "optimized batch selected an unaccepted rank" }
                    out[output++] = resultId
                    verificationCounts[rank]++
                    totalApplied++
                    input += 2
                } else {
                    out[output++] = tokens[input++]
                }
            }
            sequence.tokens = if (output == out.size) out else out.copyOf(output)
        }
        for (rank in acceptedRanks) {
            require(verificationCounts[rank] == appliedCounts[rank]) {
                "optimized ranked-batch parity failed for rank $rank: expected ${appliedCounts[rank]} applications, got ${verificationCounts[rank]}"
            }
        }
        return BatchResult(
            accepted = acceptedRanks.map { ranked[it].pair },
            examined = examined,
            totalApplied = totalApplied
        )
    }

    private fun referenceRankedBatchForSelfTest(
        sequences: MutableList<TokenSequence>,
        ranked: List<RankedPair>,
        scanLimit: Int,
        batchSize: Int,
        baseMergeCount: Int
    ): BatchResult {
        val accepted = ArrayList<PairKey>(batchSize)
        val examined = ArrayList<PairKey>(scanLimit)
        var totalAppliedAll = 0L
        for (rankedIndex in 0 until scanLimit) {
            if (accepted.size >= batchSize) break
            val pair = ranked[rankedIndex].pair
            val resultId = BYTE_TOKENS + baseMergeCount + accepted.size
            var totalApplied = 0
            for (sequence in sequences) {
                val applied = applyMerge(sequence.tokens, pair, resultId)
                sequence.tokens = applied.first
                totalApplied += applied.second
            }
            examined += pair
            if (totalApplied == 0) continue
            accepted += pair
            totalAppliedAll += totalApplied.toLong()
        }
        return BatchResult(accepted, examined, totalAppliedAll)
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

    private fun outputPaths(root: File, candidate: Candidate): OutputPaths {
        val artifact = exactFile(root, candidate.outputRelative, mustExist = false)
        val parent = artifact.parentFile ?: throw IllegalArgumentException("tokenizer output has no parent")
        require(parent.exists() || parent.mkdirs()) { "could not create tokenizer output directory" }
        require(parent.isDirectory) { "tokenizer output parent is not a directory" }
        val manifest = File(parent, artifact.name + ".manifest.json")
        return OutputPaths(
            artifact = artifact,
            manifest = manifest,
            stagedArtifact = File(parent, ".${artifact.name}.stage"),
            stagedManifest = File(parent, ".${artifact.name}.manifest.stage"),
            backupArtifact = File(parent, ".${artifact.name}.backup"),
            backupManifest = File(parent, ".${artifact.name}.manifest.backup"),
            marker = File(parent, ".${artifact.name}.commit")
        )
    }

    private fun writeSyncedText(file: File, text: String) {
        file.parentFile?.let { require(it.exists() || it.mkdirs()) { "could not create parent for ${file.name}" } }
        val bytes = text.toByteArray(Charsets.UTF_8)
        java.io.FileOutputStream(file, false).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }

    private fun moveAtomic(source: File, destination: File, label: String) {
        require(source.exists()) { "missing staged path for $label" }
        require(!destination.exists()) { "destination already exists for $label" }
        java.nio.file.Files.move(
            source.toPath(),
            destination.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE
        )
        require(destination.exists() && !source.exists()) { "atomic move verification failed for $label" }
    }

    private fun deleteIfExists(file: File, label: String) {
        if (file.exists()) require(file.delete()) { "could not remove $label: ${file.name}" }
    }

    private fun writeAtomicMarker(file: File, text: String) {
        val parent = file.parentFile ?: throw IllegalArgumentException("transaction marker has no parent")
        val temporary = File(parent, ".${file.name}.write-${System.nanoTime()}")
        try {
            writeSyncedText(temporary, text)
            moveAtomic(temporary, file, "tokenizer transaction marker")
        } finally {
            runCatching { temporary.delete() }
        }
    }

    private fun outputPairValid(candidate: Candidate, artifact: File, manifest: File): Boolean = runCatching {
        require(artifact.isFile && manifest.isFile) { "artifact/manifest pair is incomplete" }
        val manifestObject = JSONObject(decodeStrictUtf8(readBounded(manifest, 256L * 1024L)))
        require(manifestObject.optString("format") == "rift-tokenizer-training-result-v1") { "manifest format mismatch" }
        require(manifestObject.optString("candidateId") == candidate.candidateId) { "manifest candidate mismatch" }
        require(manifestObject.optString("trainer") == TRAINER_ID) { "manifest trainer mismatch" }
        require(manifestObject.optString("trainerConfigSha256") == candidate.canonicalConfigSha256) { "manifest config hash mismatch" }
        require(manifestObject.optInt("mergeCount") == MERGE_TARGET) { "manifest merge count mismatch" }
        require(manifestObject.optInt("vocabularySize") == VOCAB_SIZE) { "manifest vocabulary mismatch" }
        val trainingSha = manifestObject.optString("trainingCorpusSha256")
        require(trainingSha.matches(Regex("[0-9a-f]{64}"))) { "manifest training hash is invalid" }
        val artifactSha = manifestObject.optString("artifactSha256")
        require(artifactSha.matches(Regex("[0-9a-f]{64}")) && artifactSha == sha256File(artifact)) { "artifact hash mismatch" }
        val artifactText = decodeStrictUtf8(readBounded(artifact, 4L * 1024L * 1024L))
        require(artifactText.startsWith("$ARTIFACT_MAGIC\n")) { "artifact magic mismatch" }
        require(artifactText.contains("\ncandidate_id=${candidate.candidateId}\n")) { "artifact candidate mismatch" }
        require(artifactText.contains("\ntraining_corpus_sha256=$trainingSha\n")) { "artifact training hash mismatch" }
        require(artifactText.contains("\ntrainer_config_sha256=${candidate.canonicalConfigSha256}\n")) { "artifact config hash mismatch" }
        require(artifactText.contains("\nvocab_size=$VOCAB_SIZE\n")) { "artifact vocabulary mismatch" }
        true
    }.getOrDefault(false)

    private fun cleanupCommittedTransaction(paths: OutputPaths): Boolean {
        var pending = false
        for (file in listOf(paths.stagedArtifact, paths.stagedManifest, paths.backupArtifact, paths.backupManifest)) {
            if (file.exists() && !runCatching { file.delete() }.getOrDefault(false)) pending = true
        }
        if (!pending && paths.marker.exists() && !runCatching { paths.marker.delete() }.getOrDefault(false)) pending = true
        return pending
    }

    private fun recoverOutputPair(root: File, candidate: Candidate): String {
        val paths = outputPaths(root, candidate)
        val finalsValid = outputPairValid(candidate, paths.artifact, paths.manifest)
        if (!paths.marker.exists()) {
            deleteIfExists(paths.stagedArtifact, "stale tokenizer artifact stage")
            deleteIfExists(paths.stagedManifest, "stale tokenizer manifest stage")
            val backupPresent = paths.backupArtifact.exists() || paths.backupManifest.exists()
            if (!backupPresent) {
                return if ((paths.artifact.exists() || paths.manifest.exists()) && !finalsValid) "invalid-pair" else "clean"
            }
            if (finalsValid) {
                deleteIfExists(paths.backupArtifact, "stale tokenizer artifact backup")
                deleteIfExists(paths.backupManifest, "stale tokenizer manifest backup")
                return "cleaned-stale-backups"
            }
            if (paths.backupArtifact.exists()) {
                deleteIfExists(paths.artifact, "invalid tokenizer artifact")
                moveAtomic(paths.backupArtifact, paths.artifact, "restore tokenizer artifact backup")
            }
            if (paths.backupManifest.exists()) {
                deleteIfExists(paths.manifest, "invalid tokenizer manifest")
                moveAtomic(paths.backupManifest, paths.manifest, "restore tokenizer manifest backup")
            }
            require(outputPairValid(candidate, paths.artifact, paths.manifest)) {
                "tokenizer backup recovery could not restore a complete ${candidate.candidateId} pair"
            }
            return "restored-stale-backup-pair"
        }

        if (finalsValid) {
            val pending = cleanupCommittedTransaction(paths)
            return if (pending) "validated-pair-cleanup-pending" else "validated-pair-cleanup-complete"
        }

        val hadArtifactBackup = paths.backupArtifact.exists()
        val hadManifestBackup = paths.backupManifest.exists()
        if (hadArtifactBackup) {
            deleteIfExists(paths.artifact, "interrupted tokenizer artifact")
            moveAtomic(paths.backupArtifact, paths.artifact, "rollback tokenizer artifact")
        }
        if (hadManifestBackup) {
            deleteIfExists(paths.manifest, "interrupted tokenizer manifest")
            moveAtomic(paths.backupManifest, paths.manifest, "rollback tokenizer manifest")
        }
        if (hadArtifactBackup || hadManifestBackup) {
            require(outputPairValid(candidate, paths.artifact, paths.manifest)) {
                "tokenizer transaction rollback could not restore the previous complete ${candidate.candidateId} pair"
            }
            deleteIfExists(paths.stagedArtifact, "interrupted tokenizer artifact stage")
            deleteIfExists(paths.stagedManifest, "interrupted tokenizer manifest stage")
            deleteIfExists(paths.marker, "tokenizer transaction marker")
            return "rolled-back-interrupted-commit"
        }

        deleteIfExists(paths.artifact, "partial first tokenizer artifact")
        deleteIfExists(paths.manifest, "partial first tokenizer manifest")
        deleteIfExists(paths.stagedArtifact, "interrupted tokenizer artifact stage")
        deleteIfExists(paths.stagedManifest, "interrupted tokenizer manifest stage")
        deleteIfExists(paths.marker, "tokenizer transaction marker")
        return "discarded-interrupted-first-commit"
    }

    private fun commitOutputPair(
        root: File,
        trainFile: File,
        candidate: Candidate,
        trainingSha: String,
        merges: List<PairKey>,
        cancel: AtomicBoolean
    ): JSONObject {
        val recoveryBeforeCommit = recoverOutputPair(root, candidate)
        val paths = outputPaths(root, candidate)
        val existingPair = paths.artifact.exists() || paths.manifest.exists()
        require(!existingPair || outputPairValid(candidate, paths.artifact, paths.manifest)) {
            "existing ${candidate.candidateId} output is not a valid artifact/manifest pair; refusing to overwrite it"
        }
        require(!paths.stagedArtifact.exists() && !paths.stagedManifest.exists() && !paths.marker.exists()) {
            "tokenizer transaction staging paths were not clean after recovery"
        }

        writeArtifact(paths.stagedArtifact, candidate, trainingSha, merges)
        val artifactSha = sha256File(paths.stagedArtifact)
        val manifest = JSONObject()
            .put("artifactSha256", artifactSha)
            .put("byteFallback", true)
            .put("candidateId", candidate.candidateId)
            .put("format", "rift-tokenizer-training-result-v1")
            .put("mergeCount", merges.size)
            .put("normalization", "identity-utf8")
            .put("scoreMode", candidate.scoreMode)
            .put("sourceStableAtCommit", true)
            .put("specialTokenCount", SPECIAL_LITERALS.size)
            .put("trainer", TRAINER_ID)
            .put("trainerConfigSha256", candidate.canonicalConfigSha256)
            .put("trainingCorpusSha256", trainingSha)
            .put("transactionalPair", true)
            .put("vocabularySize", VOCAB_SIZE)
        writeSyncedText(paths.stagedManifest, sortedManifest(manifest) + "\n")
        require(outputPairValid(candidate, paths.stagedArtifact, paths.stagedManifest)) { "staged tokenizer artifact/manifest verification failed" }
        ensureNotCancelled(cancel)
        require(sha256File(trainFile) == trainingSha) {
            "training corpus changed before ${candidate.candidateId} commit; staged output discarded"
        }

        val marker = JSONObject()
            .put("artifactSha256", artifactSha)
            .put("candidateId", candidate.candidateId)
            .put("format", "rift-tokenizer-output-transaction-v1")
            .put("trainingCorpusSha256", trainingSha)
        writeAtomicMarker(paths.marker, sortedManifest(marker) + "\n")

        try {
            if (paths.artifact.exists()) {
                moveAtomic(paths.artifact, paths.backupArtifact, "backup existing tokenizer artifact")
                moveAtomic(paths.manifest, paths.backupManifest, "backup existing tokenizer manifest")
            }
            moveAtomic(paths.stagedArtifact, paths.artifact, "publish tokenizer artifact")
            moveAtomic(paths.stagedManifest, paths.manifest, "publish tokenizer manifest")
            require(outputPairValid(candidate, paths.artifact, paths.manifest)) { "committed tokenizer artifact/manifest verification failed" }
            val cleanupPending = cleanupCommittedTransaction(paths)
            return JSONObject(manifest.toString())
                .put("artifactPath", relativePath(root, paths.artifact))
                .put("manifestPath", relativePath(root, paths.manifest))
                .put("outputRecoveryBeforeCommit", recoveryBeforeCommit)
                .put("transactionCleanupPending", cleanupPending)
        } catch (error: Throwable) {
            val recovery = runCatching { recoverOutputPair(root, candidate) }
                .getOrElse { recoveryError ->
                    throw IllegalStateException(
                        "${error.message}; tokenizer output recovery also failed: ${recoveryError.message}",
                        error
                    )
                }
            throw IllegalStateException("${error.message}; tokenizer output transaction recovered as $recovery", error)
        }
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
        writeSyncedText(file, text)
    }

    private fun selfTest(): JSONObject {
        val first = applyMerge(intArrayOf(97, 98, 97, 98), PairKey(97, 98), 256)
        require(first.first.contentEquals(intArrayOf(256, 256)) && first.second == 2) { "native merge self-test failed" }
        require(MERGE_TARGET + BYTE_TOKENS + SPECIAL_LITERALS.size == VOCAB_SIZE) { "vocabulary arithmetic self-test failed" }

        val ranked = listOf(
            RankedPair(3.0, 4, PairKey(97, 97)),
            RankedPair(2.0, 4, PairKey(98, 99)),
            RankedPair(1.0, 4, PairKey(97, 98))
        )
        val originals = listOf(
            intArrayOf(97, 97, 97, 97, 98, 99, 97, 98),
            intArrayOf(97, 98, 99, 98, 99, 97, 98),
            intArrayOf(98, 99, 97, 97, 98, 99)
        )
        fun copies(): MutableList<TokenSequence> = originals.map { TokenSequence("prose", it.copyOf()) }.toMutableList()
        val referenceSequences = copies()
        val optimizedSequences = copies()
        val reference = referenceRankedBatchForSelfTest(referenceSequences, ranked, ranked.size, 3, 0)
        val optimized = applyRankedBatch(optimizedSequences, ranked, ranked.size, 3, 0, AtomicBoolean(false))
        require(reference.accepted == optimized.accepted) { "optimized batch changed accepted merge order" }
        require(reference.examined == optimized.examined) { "optimized batch changed emitted/examined merge order" }
        require(reference.totalApplied == optimized.totalApplied) { "optimized batch changed merge application count" }
        require(referenceSequences.indices.all { referenceSequences[it].tokens.contentEquals(optimizedSequences[it].tokens) }) {
            "optimized batch changed sequential batch semantics"
        }

        return JSONObject()
            .put("schema", "rift.experimental-tokenizer-task/1")
            .put("selfTestPassed", true)
            .put("optimizedBatchParity", true)
            .put("backgroundTraining", true)
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

    private fun sha256Bytes(bytes: ByteArray): String =
        hex(MessageDigest.getInstance("SHA-256").digest(bytes))

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

    private fun fileInfo(root: File, file: File, knownSha256: String? = null): JSONObject = JSONObject()
        .put("path", relativePath(root, file))
        .put("exists", file.isFile)
        .put("size", if (file.isFile) file.length() else 0L)
        .put("sha256", if (file.isFile) (knownSha256 ?: sha256File(file)) else JSONObject.NULL)

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
