package com.riftos.app

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.PriorityQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Stable fixed-purpose Rift-Micro training-data builder.
 *
 * This runner is deliberately separate from RiftExperimentalCli. It accepts no caller-selected
 * paths, process commands or tokenizer identities. The only supported input is the frozen B2
 * tokenizer plus the frozen V2 training shard set, and the only output is the ignored/private
 * canary training pack under workspace/RiftLLM/training/private/.
 */
object RiftTrainDataTaskRunner {
    private const val PACK_MAGIC = "RIFT_TRAIN_DATA_V1\n"
    private const val PACK_FORMAT = "rift-train-data-v1"
    private const val PURPOSE = "canary"
    private const val ARCHITECTURE_ID = "rift-micro-v1-hardware-a"
    private const val TOKENIZER_ID = "rift-token-b-balanced-v2"
    private const val TOKENIZER_ARTIFACT_SHA = "314e3a732d4cc4c31c40c9b0add3fffcec38c8a4b40e0d228bdc4eed1addbbd1"
    private const val TOKENIZER_TRAINING_SHA = "b88b0ab8d3a7dc784e2e4b20d33b5c5fab222542880cea197f529d5c996e9a05"
    private const val TOKENIZER_CONFIG_SHA = "9d442860e3ed407fe10f10e72ad41fabc2854cfc3cdcaeb654b35ddad506faba"
    private const val TOKENIZER_ARTIFACT_RELATIVE = "tokenizer/output/rift-token-b-balanced-v2.riftbpe"
    private const val TRAIN_RELATIVE = "tokenizer/private/build-v2/train"
    private const val OUTPUT_RELATIVE = "training/private/canary-v1/rift-train-data-v1.rifttok"
    private const val MANIFEST_RELATIVE = "training/private/canary-v1/rift-train-data-v1.manifest.json"
    private const val VOCAB_SIZE = 32768
    private const val BYTE_TOKENS = 256
    private const val MERGE_COUNT = 32504
    private const val MAX_TOKEN_BYTES = 24
    private const val MAX_SAMPLE_BYTES = 16 * 1024
    private const val MAX_PACK_BYTES = 8L * 1024L * 1024L
    private const val MAX_TRAINING_BYTES = 64L * 1024L * 1024L
    private const val MAX_SHARD_BYTES = 3L * 1024L * 1024L
    private const val MAX_SHARDS = 128
    private const val REMOTE_CHUNK_BYTES = 192 * 1024
    private const val SHARD_SET_ID = "rift-shard-set-v1"
    private val SHARD_NAME_RE = Regex("[A-Za-z0-9._-]{1,96}\\.jsonl")
    private val SPECIALS = listOf(
        "<|bos|>", "<|eos|>", "<|pad|>", "<|system|>",
        "<|user|>", "<|assistant|>", "<|tool|>", "<|end|>"
    )

    private data class Merge(val left: Int, val right: Int)
    private data class Artifact(
        val candidateId: String,
        val trainingCorpusSha256: String,
        val trainerConfigSha256: String,
        val vocabSize: Int,
        val normalization: String,
        val byteFallback: Boolean,
        val merges: List<Merge>,
        val specialIds: Map<String, Int>
    )
    private data class Event(val rank: Int, val left: Int, val right: Int)
    private data class ShardInfo(val name: String, val bytes: Long, val sha256: String, var samples: Int = 0)
    private data class BuildJob(
        val id: String,
        val startedAtMs: Long,
        var updatedAtMs: Long = startedAtMs,
        var state: String = "queued",
        var samplesCompleted: Int = 0,
        var inputBytes: Long = 0,
        var tokenCount: Long = 0,
        var packBytes: Long = 0,
        var packSha256: String? = null,
        var message: String = "queued",
        var error: String? = null
    )

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "rift-train-data-build").apply { isDaemon = true }
    }
    private val jobLock = Any()
    private var activeJob: BuildJob? = null
    private var activeCancel: AtomicBoolean? = null

    fun execute(context: Context, client: RiftLlmDevClient, args: JSONObject): Any {
        return when (val op = args.optString("op", "status").trim().lowercase()) {
            "status" -> status(context.applicationContext)
            "build" -> startBuild(context.applicationContext)
            "build-status" -> buildJobJson()
            "build-cancel" -> cancelBuild()
            "upload" -> uploadPack(context.applicationContext, client)
            "remote-status" -> client.execute(JSONObject().put("op", "train_data_status").put("request", JSONObject()))
            "canary-start" -> startCanary(context.applicationContext, client)
            "canary-status" -> client.execute(JSONObject().put("op", "train_canary_status").put("request", JSONObject()))
            else -> throw IllegalArgumentException("unsupported fixed RiftTrainData operation: $op")
        }
    }

    private fun projectRoot(context: Context): File {
        val riftRoot = File(context.filesDir, "riftfs").canonicalFile
        val project = File(riftRoot, "workspace/RiftLLM").canonicalFile
        require(project.path.startsWith(riftRoot.path + File.separator) && project.isDirectory) { "RiftLLM workspace project is missing" }
        return project
    }

    private fun exactPath(root: File, relative: String): File {
        require(relative.isNotBlank() && !relative.startsWith('/') && !relative.contains("..")) { "invalid fixed RiftTrainData path" }
        val file = File(root, relative).canonicalFile
        require(file.path.startsWith(root.path + File.separator)) { "RiftTrainData path escaped RiftLLM project" }
        return file
    }

    private fun status(context: Context): JSONObject {
        val root = projectRoot(context)
        val artifact = exactPath(root, TOKENIZER_ARTIFACT_RELATIVE)
        val pack = exactPath(root, OUTPUT_RELATIVE)
        val manifest = exactPath(root, MANIFEST_RELATIVE)
        val trainDir = exactPath(root, TRAIN_RELATIVE)
        val artifactOk = artifact.isFile && artifact.length() in 1..(4L * 1024L * 1024L) && sha256File(artifact) == TOKENIZER_ARTIFACT_SHA
        val source = runCatching { trainingSource(trainDir) }.getOrNull()
        val manifestObject = runCatching { if (manifest.isFile) JSONObject(manifest.readText(Charsets.UTF_8)) else null }.getOrNull()
        val packSha = if (pack.isFile) sha256File(pack) else null
        val packValid = pack.isFile && pack.length() in 1..MAX_PACK_BYTES && manifestObject != null &&
            manifestObject.optString("format") == PACK_FORMAT &&
            manifestObject.optString("packSha256") == packSha &&
            manifestObject.optString("tokenizerArtifactSha256") == TOKENIZER_ARTIFACT_SHA &&
            manifestObject.optString("trainingCorpusSha256") == TOKENIZER_TRAINING_SHA
        return JSONObject()
            .put("schema", "rift.train-data-status/1")
            .put("stableRoute", true)
            .put("experimentalCliRequired", false)
            .put("purpose", PURPOSE)
            .put("productionPretrainingEligible", false)
            .put("architectureId", ARCHITECTURE_ID)
            .put("tokenizerCandidateId", TOKENIZER_ID)
            .put("tokenizerArtifactSha256", TOKENIZER_ARTIFACT_SHA)
            .put("tokenizerArtifactAvailable", artifactOk)
            .put("trainingCorpusSha256", source?.second ?: JSONObject.NULL)
            .put("trainingCorpusMatchesFrozen", source?.second == TOKENIZER_TRAINING_SHA)
            .put("trainingShardCount", source?.first?.size ?: 0)
            .put("packPath", if (pack.isFile) "/workspace/RiftLLM/$OUTPUT_RELATIVE" else JSONObject.NULL)
            .put("packBytes", if (pack.isFile) pack.length() else 0L)
            .put("packSha256", packSha ?: JSONObject.NULL)
            .put("packValid", packValid)
            .put("manifest", manifestObject ?: JSONObject.NULL)
            .put("buildJob", buildJobJson())
    }

    private fun startBuild(context: Context): JSONObject {
        synchronized(jobLock) {
            val current = activeJob
            require(current == null || current.state !in setOf("queued", "running", "cancelling")) { "RiftTrainData build is already active" }
            val now = System.currentTimeMillis()
            val job = BuildJob("rift-train-data-$now", now)
            val cancel = AtomicBoolean(false)
            activeJob = job
            activeCancel = cancel
            executor.execute { runBuild(context, job, cancel) }
        }
        return buildJobJson()
    }

    private fun cancelBuild(): JSONObject {
        synchronized(jobLock) {
            val job = activeJob
            if (job != null && job.state in setOf("queued", "running", "cancelling")) {
                job.state = "cancelling"
                job.message = "cancel requested"
                job.updatedAtMs = System.currentTimeMillis()
                activeCancel?.set(true)
            }
        }
        return buildJobJson()
    }

    private fun buildJobJson(): JSONObject = synchronized(jobLock) {
        val job = activeJob ?: return@synchronized JSONObject()
            .put("schema", "rift.train-data-job/1").put("active", false).put("state", "idle")
        val active = job.state in setOf("queued", "running", "cancelling")
        val end = if (active) System.currentTimeMillis() else job.updatedAtMs
        JSONObject()
            .put("schema", "rift.train-data-job/1")
            .put("active", active)
            .put("jobId", job.id)
            .put("state", job.state)
            .put("samplesCompleted", job.samplesCompleted)
            .put("inputBytes", job.inputBytes)
            .put("tokenCount", job.tokenCount)
            .put("packBytes", job.packBytes)
            .put("packSha256", job.packSha256 ?: JSONObject.NULL)
            .put("startedAtMs", job.startedAtMs)
            .put("updatedAtMs", job.updatedAtMs)
            .put("elapsedMs", (end - job.startedAtMs).coerceAtLeast(0L))
            .put("message", job.message)
            .put("error", job.error ?: JSONObject.NULL)
    }

    private fun updateJob(job: BuildJob, block: (BuildJob) -> Unit) = synchronized(jobLock) {
        if (activeJob !== job) return@synchronized
        block(job)
        job.updatedAtMs = System.currentTimeMillis()
    }

    private fun ensureNotCancelled(cancel: AtomicBoolean) {
        if (cancel.get() || Thread.currentThread().isInterrupted) throw CancellationException("RiftTrainData build cancelled")
    }

    private fun runBuild(context: Context, job: BuildJob, cancel: AtomicBoolean) {
        try {
            updateJob(job) { it.state = "running"; it.message = "validating frozen B2 artifact and V2 training shards" }
            val result = buildBlocking(context, job, cancel)
            updateJob(job) {
                it.state = "complete"
                it.message = "model-ready canary token pack complete"
                it.packBytes = result.getLong("packBytes")
                it.packSha256 = result.getString("packSha256")
                it.tokenCount = result.getLong("tokenCount")
                it.samplesCompleted = result.getInt("sampleCount")
            }
        } catch (_: CancellationException) {
            updateJob(job) { it.state = "cancelled"; it.message = "RiftTrainData build cancelled" }
        } catch (error: Throwable) {
            updateJob(job) { it.state = "failed"; it.message = "RiftTrainData build failed"; it.error = error.message ?: error.javaClass.simpleName }
        } finally {
            synchronized(jobLock) { if (activeCancel === cancel) activeCancel = null }
        }
    }

    private fun buildBlocking(context: Context, job: BuildJob, cancel: AtomicBoolean): JSONObject {
        val root = projectRoot(context)
        val artifactFile = exactPath(root, TOKENIZER_ARTIFACT_RELATIVE)
        require(artifactFile.isFile && artifactFile.length() in 1..(4L * 1024L * 1024L)) { "frozen B2 artifact is missing" }
        require(sha256File(artifactFile) == TOKENIZER_ARTIFACT_SHA) { "frozen B2 artifact SHA-256 drifted" }
        val artifact = parseArtifact(artifactFile)
        val encoder = ByteBpe(artifact)
        val trainDir = exactPath(root, TRAIN_RELATIVE)
        val (shards, sourceSha) = trainingSource(trainDir)
        require(sourceSha == TOKENIZER_TRAINING_SHA) { "V2 training shard-set SHA-256 drifted" }
        val output = exactPath(root, OUTPUT_RELATIVE)
        val manifestFile = exactPath(root, MANIFEST_RELATIVE)
        val parent = output.parentFile ?: error("training pack has no parent")
        require(parent.isDirectory || parent.mkdirs()) { "could not create training private directory" }
        val payloadStage = File(parent, ".${output.name}.${job.id}.payload")
        val packStage = File(parent, ".${output.name}.${job.id}.stage")
        val manifestStage = File(parent, ".${manifestFile.name}.${job.id}.stage")
        listOf(payloadStage, packStage, manifestStage).forEach { runCatching { it.delete() } }

        var sampleCount = 0
        var tokenCount = 0L
        var sourceBytes = 0L
        try {
            FileOutputStream(payloadStage, false).use { fos ->
                BufferedOutputStream(fos, 256 * 1024).use { out ->
                    for (shard in shards) {
                        ensureNotCancelled(cancel)
                        val file = File(trainDir, shard.name)
                        val decoder = Charsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                        BufferedReader(InputStreamReader(FileInputStream(file), decoder), 128 * 1024).use { reader ->
                            var lineNumber = 0
                            while (true) {
                                val line = reader.readLine() ?: break
                                lineNumber++
                                if (line.isBlank()) continue
                                if ((sampleCount and 255) == 0) ensureNotCancelled(cancel)
                                val obj = try { JSONObject(line) } catch (error: Throwable) {
                                    throw IllegalArgumentException("${shard.name}:$lineNumber invalid JSON: ${error.message}")
                                }
                                val text = obj.optString("text")
                                require(text.isNotEmpty()) { "${shard.name}:$lineNumber text is empty" }
                                val bytes = text.toByteArray(Charsets.UTF_8)
                                require(bytes.size <= MAX_SAMPLE_BYTES) { "${shard.name}:$lineNumber exceeds $MAX_SAMPLE_BYTES UTF-8 bytes" }
                                val encoded = encoder.encode(bytes)
                                if (sampleCount < 32) {
                                    val reference = encoder.referenceEncode(bytes)
                                    require(encoded.contentEquals(reference)) { "fast BPE parity failed at ${shard.name}:$lineNumber" }
                                }
                                val recordCount = encoded.size + 2
                                writeU32Le(out, recordCount.toLong())
                                writeU16Le(out, artifact.specialIds.getValue("<|bos|>"))
                                encoded.forEach { id -> require(id in 0 until VOCAB_SIZE); writeU16Le(out, id) }
                                writeU16Le(out, artifact.specialIds.getValue("<|eos|>"))
                                sampleCount++
                                tokenCount += recordCount.toLong()
                                sourceBytes += bytes.size.toLong()
                                shard.samples++
                                if ((sampleCount and 511) == 0) updateJob(job) {
                                    it.samplesCompleted = sampleCount
                                    it.inputBytes = sourceBytes
                                    it.tokenCount = tokenCount
                                    it.message = "encoding frozen B2 training data: $sampleCount samples"
                                }
                            }
                        }
                    }
                    out.flush()
                    fos.fd.sync()
                }
            }
            require(sampleCount > 0 && tokenCount > sampleCount.toLong() * 2L) { "tokenized training payload is empty" }
            val postBuildSourceSha = trainingSource(trainDir).second
            require(postBuildSourceSha == TOKENIZER_TRAINING_SHA && postBuildSourceSha == sourceSha) {
                "V2 training shard set changed while RiftTrainData was encoding"
            }
            val headerText = packHeader(sampleCount, tokenCount, shards)
            val headerBytes = headerText.toByteArray(Charsets.UTF_8)
            require(headerBytes.size in 1..(512 * 1024)) { "RiftTrainData header is out of bounds" }
            FileOutputStream(packStage, false).use { fos ->
                BufferedOutputStream(fos, 256 * 1024).use { out ->
                    out.write(PACK_MAGIC.toByteArray(Charsets.US_ASCII))
                    writeU32Le(out, headerBytes.size.toLong())
                    out.write(headerBytes)
                    BufferedInputStream(FileInputStream(payloadStage), 256 * 1024).use { input ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            ensureNotCancelled(cancel)
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read > 0) out.write(buffer, 0, read)
                        }
                    }
                    out.flush()
                    fos.fd.sync()
                }
            }
            require(packStage.length() in 1..MAX_PACK_BYTES) { "RiftTrainData pack exceeds $MAX_PACK_BYTES bytes" }
            val packSha = sha256File(packStage)
            val manifest = JSONObject()
                .put("format", PACK_FORMAT)
                .put("purpose", PURPOSE)
                .put("productionPretrainingEligible", false)
                .put("architectureId", ARCHITECTURE_ID)
                .put("tokenizerCandidateId", TOKENIZER_ID)
                .put("tokenizerArtifactSha256", TOKENIZER_ARTIFACT_SHA)
                .put("tokenizerTrainerConfigSha256", TOKENIZER_CONFIG_SHA)
                .put("trainingCorpusHashMode", SHARD_SET_ID)
                .put("trainingCorpusSha256", TOKENIZER_TRAINING_SHA)
                .put("sampleCount", sampleCount)
                .put("tokenCount", tokenCount)
                .put("packBytes", packStage.length())
                .put("packSha256", packSha)
                .put("payloadEncoding", "sample-u32le-count-u16le-token-ids")
                .put("boundaryPolicy", "bos-text-eos-v1")
            writeSynced(manifestStage, manifest.toString(2) + "\n")
            atomicReplace(packStage, output)
            atomicReplace(manifestStage, manifestFile)
            require(sha256File(output) == packSha) { "published RiftTrainData pack hash mismatch" }
            return manifest.put("packPath", "/workspace/RiftLLM/$OUTPUT_RELATIVE")
        } finally {
            runCatching { payloadStage.delete() }
            runCatching { packStage.delete() }
            runCatching { manifestStage.delete() }
        }
    }

    private fun uploadPack(context: Context, client: RiftLlmDevClient): JSONObject {
        val root = projectRoot(context)
        val pack = exactPath(root, OUTPUT_RELATIVE)
        require(pack.isFile && pack.length() in 1..MAX_PACK_BYTES) { "build the fixed RiftTrainData pack before upload" }
        val sha = sha256File(pack)
        val localManifest = exactPath(root, MANIFEST_RELATIVE)
        require(localManifest.isFile) { "RiftTrainData manifest is missing" }
        val manifest = JSONObject(localManifest.readText(Charsets.UTF_8))
        require(manifest.optString("packSha256") == sha && manifest.optString("tokenizerArtifactSha256") == TOKENIZER_ARTIFACT_SHA) { "local RiftTrainData pack/manifest pair is invalid" }
        val begin = client.execute(JSONObject().put("op", "train_data_begin").put("request", JSONObject()
            .put("totalBytes", pack.length()).put("sha256", sha))) as JSONObject
        require(begin.optInt("maxChunkBytes") == REMOTE_CHUNK_BYTES) { "RiftLLM training upload chunk contract mismatch" }
        var offset = 0L
        BufferedInputStream(FileInputStream(pack), REMOTE_CHUNK_BYTES).use { input ->
            val buffer = ByteArray(REMOTE_CHUNK_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                val reply = client.execute(JSONObject().put("op", "train_data_append").put("request", JSONObject()
                    .put("offset", offset).put("dataBase64", Base64.encodeToString(chunk, Base64.NO_WRAP)))) as JSONObject
                offset += read.toLong()
                require(reply.optLong("receivedBytes") == offset) { "RiftLLM training upload acknowledgement drifted" }
            }
        }
        val committed = client.execute(JSONObject().put("op", "train_data_commit").put("request", JSONObject())) as JSONObject
        require(committed.optBoolean("committed") && committed.optString("sha256") == sha) { "RiftLLM training pack commit verification failed" }
        return JSONObject().put("uploaded", true).put("packBytes", pack.length()).put("packSha256", sha).put("remote", committed)
    }

    private fun startCanary(context: Context, client: RiftLlmDevClient): JSONObject {
        val status = status(context)
        require(status.optBoolean("packValid")) { "local RiftTrainData pack is not valid" }
        val sha = status.getString("packSha256")
        val remote = client.execute(JSONObject().put("op", "train_data_status").put("request", JSONObject())) as JSONObject
        require(remote.optBoolean("available") && remote.optString("sha256") == sha) { "upload the exact local RiftTrainData pack before starting the canary" }
        return client.execute(JSONObject().put("op", "train_canary_start").put("request", JSONObject().put("packSha256", sha))) as JSONObject
    }

    private fun trainingSource(trainDir: File): Pair<MutableList<ShardInfo>, String> {
        require(trainDir.isDirectory) { "V2 training shard directory is missing" }
        val files = (trainDir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(".jsonl", ignoreCase = true) }
            .sortedBy { it.name }
        require(files.isNotEmpty() && files.size <= MAX_SHARDS) { "V2 training shard count is invalid" }
        var total = 0L
        val shards = mutableListOf<ShardInfo>()
        files.forEach { file ->
            require(SHARD_NAME_RE.matches(file.name) && file.length() in 1..MAX_SHARD_BYTES) { "invalid V2 training shard: ${file.name}" }
            total += file.length()
            require(total <= MAX_TRAINING_BYTES) { "V2 training source exceeds $MAX_TRAINING_BYTES bytes" }
            shards += ShardInfo(file.name, file.length(), sha256File(file))
        }
        val material = buildString { shards.forEach { append(it.name).append('\t').append(it.bytes).append('\t').append(it.sha256).append('\n') } }
        return shards to sha256Bytes(material.toByteArray(Charsets.UTF_8))
    }

    private fun packHeader(sampleCount: Int, tokenCount: Long, shards: List<ShardInfo>): String {
        val shardRows = JSONArray()
        shards.forEach { shard -> shardRows.put(JSONObject()
            .put("name", shard.name).put("utf8Bytes", shard.bytes).put("sha256", shard.sha256).put("sampleCount", shard.samples)) }
        val specialIds = JSONObject()
        SPECIALS.forEachIndexed { index, literal -> specialIds.put(literal, BYTE_TOKENS + MERGE_COUNT + index) }
        return JSONObject()
            .put("format", PACK_FORMAT)
            .put("purpose", PURPOSE)
            .put("productionPretrainingEligible", false)
            .put("architectureId", ARCHITECTURE_ID)
            .put("tokenizerCandidateId", TOKENIZER_ID)
            .put("tokenizerArtifactSha256", TOKENIZER_ARTIFACT_SHA)
            .put("tokenizerTrainerConfigSha256", TOKENIZER_CONFIG_SHA)
            .put("trainingCorpusHashMode", SHARD_SET_ID)
            .put("trainingCorpusSha256", TOKENIZER_TRAINING_SHA)
            .put("vocabularySize", VOCAB_SIZE)
            .put("normalization", "identity-utf8")
            .put("byteFallback", true)
            .put("maxTokenBytes", MAX_TOKEN_BYTES)
            .put("specialTokenIds", specialIds)
            .put("boundaryPolicy", "bos-text-eos-v1")
            .put("payloadEncoding", "sample-u32le-count-u16le-token-ids")
            .put("sampleCount", sampleCount)
            .put("tokenCount", tokenCount)
            .put("sourceShards", shardRows)
            .toString()
    }

    private fun parseArtifact(file: File): Artifact {
        val lines = file.readText(Charsets.UTF_8).lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith('#') }.toList()
        require(lines.firstOrNull() == "RIFT_BYTE_BPE_V1") { "frozen tokenizer artifact magic mismatch" }
        var candidate = ""
        var trainingSha = ""
        var configSha = ""
        var vocab = -1
        var normalization = ""
        var fallback = false
        val merges = ArrayList<Merge>(MERGE_COUNT)
        val specials = linkedMapOf<String, Int>()
        var inMerges = false
        lines.drop(1).forEach { line ->
            when {
                line == "merges_begin" -> { require(!inMerges); inMerges = true }
                line == "merges_end" -> { require(inMerges); inMerges = false }
                inMerges -> {
                    val p = line.split(Regex("\\s+")); require(p.size == 2); merges += Merge(p[0].toInt(), p[1].toInt())
                }
                line.startsWith("candidate_id=") -> candidate = line.substringAfter('=').trim()
                line.startsWith("training_corpus_sha256=") -> trainingSha = line.substringAfter('=').trim().lowercase()
                line.startsWith("trainer_config_sha256=") -> configSha = line.substringAfter('=').trim().lowercase()
                line.startsWith("vocab_size=") -> vocab = line.substringAfter('=').toInt()
                line.startsWith("normalization=") -> normalization = line.substringAfter('=').trim()
                line.startsWith("byte_fallback=") -> fallback = line.substringAfter('=').trim().toBooleanStrict()
                line.startsWith("special=") -> {
                    val payload = line.substringAfter('='); val colon = payload.indexOf(':'); require(colon > 0)
                    val id = payload.substring(0, colon).toInt(); val literal = String(hexToBytes(payload.substring(colon + 1)), Charsets.UTF_8)
                    specials[literal] = id
                }
                else -> error("unknown tokenizer artifact field: $line")
            }
        }
        require(!inMerges && candidate == TOKENIZER_ID && trainingSha == TOKENIZER_TRAINING_SHA && configSha == TOKENIZER_CONFIG_SHA) { "frozen tokenizer provenance mismatch" }
        require(vocab == VOCAB_SIZE && normalization == "identity-utf8" && fallback && merges.size == MERGE_COUNT) { "frozen tokenizer contract mismatch" }
        require(SPECIALS.all { specials[it] == BYTE_TOKENS + MERGE_COUNT + SPECIALS.indexOf(it) }) { "frozen special-token mapping mismatch" }
        return Artifact(candidate, trainingSha, configSha, vocab, normalization, fallback, merges, specials)
    }

    private class ByteBpe(artifact: Artifact) {
        private val merges = artifact.merges
        private val ranks = HashMap<Long, Int>(merges.size * 2)
        private fun pairKey(left: Int, right: Int): Long = (left.toLong() shl 32) or (right.toLong() and 0xffffffffL)
        init { merges.forEachIndexed { rank, merge -> require(ranks.put(pairKey(merge.left, merge.right), rank) == null) } }

        fun referenceEncode(bytes: ByteArray): IntArray {
            if (bytes.isEmpty()) return IntArray(0)
            val ids = ArrayList<Int>(bytes.size)
            bytes.forEach { ids += it.toInt() and 0xff }
            while (ids.size > 1) {
                var bestRank = Int.MAX_VALUE
                for (i in 0 until ids.lastIndex) {
                    val rank = ranks[pairKey(ids[i], ids[i + 1])] ?: continue
                    if (rank < bestRank) bestRank = rank
                }
                if (bestRank == Int.MAX_VALUE) break
                val merge = merges[bestRank]
                val mergedId = BYTE_TOKENS + bestRank
                val nextIds = ArrayList<Int>(ids.size)
                var i = 0
                while (i < ids.size) {
                    if (i + 1 < ids.size && ids[i] == merge.left && ids[i + 1] == merge.right) {
                        nextIds += mergedId; i += 2
                    } else nextIds += ids[i++]
                }
                ids.clear(); ids.addAll(nextIds)
            }
            return ids.toIntArray()
        }

        fun encode(bytes: ByteArray): IntArray {
            if (bytes.isEmpty()) return IntArray(0)
            val n = bytes.size
            val token = IntArray(n) { bytes[it].toInt() and 0xff }
            val prev = IntArray(n) { it - 1 }
            val next = IntArray(n) { if (it + 1 < n) it + 1 else -1 }
            val alive = BooleanArray(n) { true }
            val queue = PriorityQueue<Event>(compareBy<Event> { it.rank }.thenBy { it.left })
            fun enqueue(left: Int) {
                if (left < 0 || !alive[left]) return
                val right = next[left]
                if (right < 0 || !alive[right]) return
                val rank = ranks[pairKey(token[left], token[right])] ?: return
                queue.add(Event(rank, left, right))
            }
            for (i in 0 until n - 1) enqueue(i)
            while (queue.isNotEmpty()) {
                val event = queue.poll()
                val left = event.left; val right = event.right
                if (!alive[left] || !alive[right] || next[left] != right || prev[right] != left) continue
                val rank = ranks[pairKey(token[left], token[right])] ?: continue
                if (rank != event.rank) continue
                token[left] = BYTE_TOKENS + rank
                alive[right] = false
                val after = next[right]
                next[left] = after
                if (after >= 0) prev[after] = left
                next[right] = -1
                val before = prev[left]
                enqueue(before)
                enqueue(left)
            }
            val out = IntArray(n)
            var count = 0
            var cursor = 0
            while (cursor >= 0) {
                if (alive[cursor]) out[count++] = token[cursor]
                cursor = next[cursor]
            }
            return out.copyOf(count)
        }
    }

    private fun writeU16Le(out: java.io.OutputStream, value: Int) {
        require(value in 0..0xffff); out.write(value and 0xff); out.write((value ushr 8) and 0xff)
    }
    private fun writeU32Le(out: java.io.OutputStream, value: Long) {
        require(value in 0..0xffffffffL)
        repeat(4) { shift -> out.write(((value ushr (shift * 8)) and 0xffL).toInt()) }
    }
    private fun writeSynced(file: File, text: String) {
        file.parentFile?.let { require(it.isDirectory || it.mkdirs()) }
        FileOutputStream(file, false).use { out -> out.write(text.toByteArray(Charsets.UTF_8)); out.fd.sync() }
    }
    private fun atomicReplace(stage: File, target: File) {
        target.parentFile?.let { require(it.isDirectory || it.mkdirs()) }
        Files.move(stage.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        require(target.isFile) { "atomic RiftTrainData publication failed" }
    }
    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(file), 256 * 1024).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) { val read = input.read(buffer); if (read < 0) break; if (read > 0) digest.update(buffer, 0, read) }
        }
        return hex(digest.digest())
    }
    private fun sha256Bytes(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    private fun hexToBytes(value: String): ByteArray {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) { i -> value.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
