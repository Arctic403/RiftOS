package com.riftos.app

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fixed-path async threshold qualification for RiftTrainData V2.
 *
 * Input:
 *   training/private/production-v2/qualification/near-dedup-cases.jsonl
 *
 * Output:
 *   training/private/production-v2/qualification/near-dedup-threshold-evidence.json
 *
 * This task measures an admissible threshold interval only. It never freezes
 * or applies a threshold and never marks production pretraining eligible.
 */
object RiftB2ThresholdQualificationTask {
    private const val TOKENIZER_ARTIFACT_RELATIVE =
        "tokenizer/output/rift-token-b-balanced-v2.riftbpe"
    private const val INPUT_RELATIVE =
        "training/private/production-v2/qualification/near-dedup-cases.jsonl"
    private const val OUTPUT_RELATIVE =
        "training/private/production-v2/qualification/near-dedup-threshold-evidence.json"
    private const val MAX_INPUT_BYTES = 256L * 1024L * 1024L
    private const val MAX_CONTENT_BYTES =
        RiftTrainDataV2Format.MAX_ORIGINAL_UTF8_BYTES

    private data class Job(
        val id: String,
        val startedAtMs: Long,
        var updatedAtMs: Long = startedAtMs,
        var state: String = "queued",
        var casesCompleted: Int = 0,
        var message: String = "queued",
        var evidenceSha256: String? = null,
        var error: String? = null
    )

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "rift-v2-dedup-threshold-qualification").apply {
            isDaemon = true
        }
    }
    private val lock = Any()
    private var activeJob: Job? = null
    private var activeCancel: AtomicBoolean? = null

    fun execute(context: Context, args: JSONObject): Any =
        when (val op = args.optString("op", "status").trim().lowercase()) {
            "status" -> status(context.applicationContext)
            "start" -> start(context.applicationContext)
            "job-status" -> jobJson()
            "cancel" -> cancel()
            else -> throw IllegalArgumentException(
                "unsupported fixed V2 threshold qualification operation: " + op
            )
        }

    private fun projectRoot(context: Context): File {
        val riftRoot = File(context.filesDir, "riftfs").canonicalFile
        val root = File(riftRoot, "workspace/RiftLLM").canonicalFile
        require(
            root.path.startsWith(riftRoot.path + File.separator) &&
                root.isDirectory
        ) {
            "RiftLLM workspace project is missing"
        }
        return root
    }

    private fun exactPath(root: File, relative: String): File {
        require(
            relative.isNotBlank() &&
                !relative.startsWith('/') &&
                !relative.contains("..")
        ) {
            "invalid fixed V2 threshold qualification path"
        }
        val raw = File(root, relative).absoluteFile
        val normalized = raw.toPath().normalize().toFile().absoluteFile
        val file = raw.canonicalFile
        require(file.path.startsWith(root.path + File.separator)) {
            "V2 threshold qualification path escaped project"
        }
        require(file.path == normalized.path) {
            "V2 threshold qualification fixed path resolves through a symlink"
        }
        return file
    }

    private fun status(context: Context): JSONObject {
        val root = projectRoot(context)
        val input = exactPath(root, INPUT_RELATIVE)
        val output = exactPath(root, OUTPUT_RELATIVE)
        val tokenizerOk = runCatching {
            RiftFrozenByteBpeV1.loadFrozen(
                exactPath(root, TOKENIZER_ARTIFACT_RELATIVE)
            )
        }.isSuccess
        val inputReady =
            input.isFile && input.length() in 1..MAX_INPUT_BYTES
        val evidenceValidation =
            if (output.isFile && inputReady && tokenizerOk) {
                runCatching {
                    validateEvidence(context, input, output)
                }
            } else {
                null
            }
        val evidenceValid =
            evidenceValidation?.isSuccess == true
        val evidence =
            evidenceValidation?.getOrNull()

        return JSONObject()
            .put("qualificationId", RiftB2ThresholdQualificationV1.QUALIFICATION_ID)
            .put("tokenizerReady", tokenizerOk)
            .put("inputReady", inputReady)
            .put("evidenceExists", output.isFile)
            .put("evidenceValid", evidenceValid)
            .put(
                "evidenceValidationError",
                evidenceValidation?.exceptionOrNull()?.message
                    ?: JSONObject.NULL
            )
            .put(
                "evidenceSha256",
                if (output.isFile) {
                    RiftTrainDataV2Format.sha256File(output)
                } else {
                    JSONObject.NULL
                }
            )
            .put(
                "qualifiedIntervalExists",
                evidence?.getBoolean("qualifiedIntervalExists")
                    ?: JSONObject.NULL
            )
            .put("thresholdFrozen", false)
            .put("productionPretrainingEligible", false)
            .put("job", jobJson())
    }

    private fun start(context: Context): JSONObject = synchronized(lock) {
        require(activeJob?.state != "running" && activeJob?.state != "queued") {
            "V2 threshold qualification is already running"
        }
        val now = System.currentTimeMillis()
        val job = Job(
            id = "v2-dedup-qualify-" + now.toString(16),
            startedAtMs = now
        )
        val cancel = AtomicBoolean(false)
        activeJob = job
        activeCancel = cancel
        executor.execute {
            runJob(context.applicationContext, job, cancel)
        }
        jobJsonLocked(job)
    }

    private fun cancel(): JSONObject = synchronized(lock) {
        val job = activeJob
            ?: return@synchronized JSONObject()
                .put("ok", false)
                .put("message", "no V2 threshold qualification job")
        if (job.state == "queued" || job.state == "running") {
            activeCancel?.set(true)
            job.message = "cancellation requested"
            job.updatedAtMs = System.currentTimeMillis()
        }
        jobJsonLocked(job)
    }

    private fun jobJson(): JSONObject = synchronized(lock) {
        activeJob?.let(::jobJsonLocked)
            ?: JSONObject()
                .put("state", "idle")
                .put("thresholdFrozen", false)
    }

    private fun jobJsonLocked(job: Job): JSONObject =
        JSONObject()
            .put("id", job.id)
            .put("state", job.state)
            .put("startedAtMs", job.startedAtMs)
            .put("updatedAtMs", job.updatedAtMs)
            .put("casesCompleted", job.casesCompleted)
            .put("message", job.message)
            .put(
                "evidenceSha256",
                job.evidenceSha256 ?: JSONObject.NULL
            )
            .put("error", job.error ?: JSONObject.NULL)
            .put("thresholdFrozen", false)

    private fun updateJob(job: Job, block: (Job) -> Unit) = synchronized(lock) {
        block(job)
        job.updatedAtMs = System.currentTimeMillis()
    }

    private fun ensureRunning(cancel: AtomicBoolean) {
        if (cancel.get()) throw CancellationException(
            "V2 threshold qualification cancelled"
        )
    }

    private fun runJob(
        context: Context,
        job: Job,
        cancel: AtomicBoolean
    ) {
        updateJob(job) {
            it.state = "running"
            it.message = "qualifying near-dedup threshold"
        }
        try {
            val sha = runQualification(context, job, cancel)
            updateJob(job) {
                it.state = "succeeded"
                it.message = "threshold qualification evidence written"
                it.evidenceSha256 = sha
            }
        } catch (cancelled: CancellationException) {
            updateJob(job) {
                it.state = "cancelled"
                it.message = "cancelled"
                it.error = null
            }
        } catch (error: Throwable) {
            updateJob(job) {
                it.state = "failed"
                it.message = "qualification failed"
                it.error = error.message ?: error.javaClass.simpleName
            }
        } finally {
            synchronized(lock) {
                if (activeJob === job) activeCancel = null
            }
        }
    }

    private fun runQualification(
        context: Context,
        job: Job,
        cancel: AtomicBoolean
    ): String {
        val root = projectRoot(context)
        val input = exactPath(root, INPUT_RELATIVE)
        require(input.isFile && input.length() in 1..MAX_INPUT_BYTES) {
            "V2 threshold qualification input is missing/out of bounds"
        }
        val inputSha = RiftTrainDataV2Format.sha256File(input)

        val artifact = RiftFrozenByteBpeV1.loadFrozen(
            exactPath(root, TOKENIZER_ARTIFACT_RELATIVE)
        )
        val encoder = RiftFrozenByteBpeV1.Encoder(artifact)
        val scores =
            ArrayList<RiftB2ThresholdQualificationV1.LabeledScore>()

        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        BufferedReader(
            InputStreamReader(FileInputStream(input), decoder),
            64 * 1024
        ).use { reader ->
            var lineNumber = 0
            while (true) {
                val line = reader.readLine() ?: break
                lineNumber++
                ensureRunning(cancel)
                require(line.isNotBlank()) {
                    "V2 threshold qualification contains blank row"
                }
                val obj = JSONObject(line)
                require(
                    obj.keys().asSequence().toSet() ==
                        setOf("id", "label", "left", "right")
                ) {
                    "V2 threshold qualification fields are non-canonical"
                }
                require(RiftTrainDataV2Format.canonicalJson(obj) == line) {
                    "V2 threshold qualification row is not canonical JSON"
                }

                val id = obj.getString("id")
                val label = obj.getString("label")
                val leftBytes = obj.getString("left").toByteArray(Charsets.UTF_8)
                val rightBytes = obj.getString("right").toByteArray(Charsets.UTF_8)
                require(
                    leftBytes.size in 1..MAX_CONTENT_BYTES &&
                        rightBytes.size in 1..MAX_CONTENT_BYTES
                ) {
                    "V2 threshold qualification content is out of bounds"
                }
                require(!leftBytes.contentEquals(rightBytes)) {
                    "V2 threshold qualification may not use exact duplicate pair"
                }

                val leftTokens = encoder.encode(leftBytes)
                val rightTokens = encoder.encode(rightBytes)
                require(
                    leftTokens.size in
                        1..RiftTrainDataV2Format.MAX_CONTENT_TOKENS &&
                        rightTokens.size in
                        1..RiftTrainDataV2Format.MAX_CONTENT_TOKENS
                ) {
                    "V2 threshold qualification pair exceeds token context"
                }
                if (scores.size < 32) {
                    require(
                        leftTokens.contentEquals(
                            encoder.referenceEncode(leftBytes)
                        ) &&
                            rightTokens.contentEquals(
                                encoder.referenceEncode(rightBytes)
                            )
                    ) {
                        "V2 threshold qualification B2 parity failed"
                    }
                }

                val leftSketch = RiftB2BottomKDedupV1.sketch(leftTokens)
                val rightSketch = RiftB2BottomKDedupV1.sketch(rightTokens)
                require(
                    !leftSketch.isExactFallback &&
                        !rightSketch.isExactFallback
                ) {
                    "V2 threshold qualification pair is too short for near-dedup"
                }

                scores += RiftB2ThresholdQualificationV1.LabeledScore(
                    id = id,
                    label = label,
                    similarityPpm =
                        RiftB2BottomKDedupV1.similarityPpm(
                            leftSketch,
                            rightSketch
                        )
                )
                updateJob(job) {
                    it.casesCompleted = scores.size
                    if ((scores.size and 255) == 0) {
                        it.message =
                            "qualified " + scores.size + " near-dedup cases"
                    }
                }
                require(scores.size <= RiftB2ThresholdQualificationV1.MAX_CASES) {
                    "V2 threshold qualification case limit exceeded"
                }
            }
        }

        ensureRunning(cancel)
        require(RiftTrainDataV2Format.sha256File(input) == inputSha) {
            "V2 threshold qualification input changed during run"
        }

        val evidence =
            RiftB2ThresholdQualificationV1.evaluate(scores.asSequence())
        val app = appIdentity(context)
        val outputJson = JSONObject()
            .put("format", "rift-b2-threshold-qualification-evidence-v1")
            .put(
                "qualificationId",
                RiftB2ThresholdQualificationV1.QUALIFICATION_ID
            )
            .put("algorithmId", RiftB2BottomKDedupV1.ALGORITHM_ID)
            .put("similarityId", RiftB2BottomKDedupV1.SIMILARITY_ID)
            .put("tokenizerCandidateId", RiftFrozenByteBpeV1.CANDIDATE_ID)
            .put("tokenizerSha256", RiftFrozenByteBpeV1.ARTIFACT_SHA256)
            .put("inputSha256", inputSha)
            .put("caseCount", evidence.caseCount)
            .put("nearDuplicateCases", evidence.nearDuplicateCases)
            .put("distinctCases", evidence.distinctCases)
            .put(
                "maxDistinctSimilarityPpm",
                evidence.maxDistinctSimilarityPpm
            )
            .put(
                "minNearDuplicateSimilarityPpm",
                evidence.minNearDuplicateSimilarityPpm
            )
            .put(
                "qualifiedIntervalExists",
                evidence.qualifiedIntervalExists
            )
            .put(
                "minimumQualifiedThresholdPpm",
                evidence.minimumQualifiedThresholdPpm ?: JSONObject.NULL
            )
            .put(
                "maximumQualifiedThresholdPpm",
                evidence.maximumQualifiedThresholdPpm ?: JSONObject.NULL
            )
            .put(
                "canonicalScoreStreamSha256",
                evidence.canonicalScoreStreamSha256
            )
            .put("packageName", app.getString("packageName"))
            .put("versionName", app.getString("versionName"))
            .put("versionCode", app.getLong("versionCode"))
            .put("apkSha256", app.getString("apkSha256"))
            .put("thresholdFrozen", false)
            .put("rejectionApplied", false)
            .put("nearDedupImplemented", false)
            .put("productionPretrainingEligible", false)

        val output = exactPath(root, OUTPUT_RELATIVE)
        val parent = output.parentFile
            ?: throw IllegalArgumentException(
                "V2 threshold qualification output has no parent"
            )
        require(parent.isDirectory || parent.mkdirs()) {
            "V2 threshold qualification output directory is unavailable"
        }
        val stage = File(
            parent,
            ".near-dedup-threshold-evidence." +
                System.nanoTime().toString(16) +
                ".stage"
        )
        try {
            FileOutputStream(stage, false).use { out ->
                out.write(
                    RiftTrainDataV2Format
                        .canonicalJson(outputJson)
                        .toByteArray(Charsets.UTF_8)
                )
                out.fd.sync()
            }
            Files.move(
                stage.toPath(),
                output.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            fsyncDirectory(parent)
        } finally {
            if (stage.exists()) stage.delete()
        }

        validateEvidence(context, input, output)
        return RiftTrainDataV2Format.sha256File(output)
    }

    private fun validateEvidence(
        context: Context,
        input: File,
        output: File
    ): JSONObject {
        require(output.isFile && output.length() in 2..(1024L * 1024L)) {
            "V2 threshold qualification evidence is missing/out of bounds"
        }
        require(input.isFile && input.length() in 1..MAX_INPUT_BYTES) {
            "V2 threshold qualification input is missing/out of bounds"
        }
        RiftFrozenByteBpeV1.loadFrozen(
            exactPath(projectRoot(context), TOKENIZER_ARTIFACT_RELATIVE)
        )

        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = decoder.decode(
            java.nio.ByteBuffer.wrap(output.readBytes())
        ).toString()
        val obj = JSONObject(text)
        require(RiftTrainDataV2Format.canonicalJson(obj) == text) {
            "V2 threshold qualification evidence is not canonical JSON"
        }

        val expectedKeys = setOf(
            "format",
            "qualificationId",
            "algorithmId",
            "similarityId",
            "tokenizerCandidateId",
            "tokenizerSha256",
            "inputSha256",
            "caseCount",
            "nearDuplicateCases",
            "distinctCases",
            "maxDistinctSimilarityPpm",
            "minNearDuplicateSimilarityPpm",
            "qualifiedIntervalExists",
            "minimumQualifiedThresholdPpm",
            "maximumQualifiedThresholdPpm",
            "canonicalScoreStreamSha256",
            "packageName",
            "versionName",
            "versionCode",
            "apkSha256",
            "thresholdFrozen",
            "rejectionApplied",
            "nearDedupImplemented",
            "productionPretrainingEligible"
        )
        require(obj.keys().asSequence().toSet() == expectedKeys) {
            "V2 threshold qualification evidence fields are non-canonical"
        }
        require(
            obj.getString("format") ==
                "rift-b2-threshold-qualification-evidence-v1" &&
                obj.getString("qualificationId") ==
                RiftB2ThresholdQualificationV1.QUALIFICATION_ID &&
                obj.getString("algorithmId") ==
                RiftB2BottomKDedupV1.ALGORITHM_ID &&
                obj.getString("similarityId") ==
                RiftB2BottomKDedupV1.SIMILARITY_ID
        ) {
            "V2 threshold qualification evidence identity mismatch"
        }
        require(
            obj.getString("tokenizerCandidateId") ==
                RiftFrozenByteBpeV1.CANDIDATE_ID &&
                obj.getString("tokenizerSha256") ==
                RiftFrozenByteBpeV1.ARTIFACT_SHA256
        ) {
            "V2 threshold qualification tokenizer identity mismatch"
        }
        require(
            obj.getString("inputSha256") ==
                RiftTrainDataV2Format.sha256File(input)
        ) {
            "V2 threshold qualification evidence input SHA mismatch"
        }

        val caseCount = obj.getInt("caseCount")
        val nearCases = obj.getInt("nearDuplicateCases")
        val distinctCases = obj.getInt("distinctCases")
        val maxDistinct = obj.getInt("maxDistinctSimilarityPpm")
        val minNear = obj.getInt("minNearDuplicateSimilarityPpm")
        require(
            caseCount in 2..RiftB2ThresholdQualificationV1.MAX_CASES &&
                nearCases > 0 &&
                distinctCases > 0 &&
                nearCases + distinctCases == caseCount &&
                maxDistinct in 0..RiftB2BottomKDedupV1.SCORE_SCALE &&
                minNear in 0..RiftB2BottomKDedupV1.SCORE_SCALE
        ) {
            "V2 threshold qualification evidence counters/scores are invalid"
        }

        val intervalExists = obj.getBoolean("qualifiedIntervalExists")
        if (intervalExists) {
            require(
                !obj.isNull("minimumQualifiedThresholdPpm") &&
                    !obj.isNull("maximumQualifiedThresholdPpm")
            ) {
                "V2 threshold qualification interval is missing"
            }
            val minimum = obj.getInt("minimumQualifiedThresholdPpm")
            val maximum = obj.getInt("maximumQualifiedThresholdPpm")
            require(
                maxDistinct < minNear &&
                    minimum == maxDistinct + 1 &&
                    maximum == minNear &&
                    minimum <= maximum
            ) {
                "V2 threshold qualification interval is inconsistent"
            }
        } else {
            require(
                obj.isNull("minimumQualifiedThresholdPpm") &&
                    obj.isNull("maximumQualifiedThresholdPpm") &&
                    maxDistinct >= minNear
            ) {
                "V2 non-separable threshold evidence is inconsistent"
            }
        }
        require(
            obj.getString("canonicalScoreStreamSha256")
                .matches(Regex("[0-9a-f]{64}"))
        ) {
            "V2 threshold qualification score-stream SHA is invalid"
        }

        val app = appIdentity(context)
        require(
            obj.getString("packageName") == app.getString("packageName") &&
                obj.getString("versionName") == app.getString("versionName") &&
                obj.getLong("versionCode") == app.getLong("versionCode") &&
                obj.getString("apkSha256") == app.getString("apkSha256")
        ) {
            "V2 threshold qualification evidence app/APK identity mismatch"
        }
        require(
            !obj.getBoolean("thresholdFrozen") &&
                !obj.getBoolean("rejectionApplied") &&
                !obj.getBoolean("nearDedupImplemented") &&
                !obj.getBoolean("productionPretrainingEligible")
        ) {
            "V2 threshold qualification evidence may not claim frozen/production state"
        }
        return obj
    }

    private fun appIdentity(context: Context): JSONObject {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            0
        )
        val versionCode =
            if (Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        val sourceDir = info.applicationInfo?.sourceDir
            ?: context.applicationInfo.sourceDir
        val apkFile = File(sourceDir)
        require(apkFile.isFile) {
            "installed APK path is unavailable for threshold evidence binding"
        }
        return JSONObject()
            .put("packageName", context.packageName)
            .put("versionName", info.versionName ?: "")
            .put("versionCode", versionCode)
            .put(
                "apkSha256",
                RiftTrainDataV2Format.sha256File(apkFile)
            )
    }

    private fun fsyncDirectory(directory: File) {
        require(directory.isDirectory) {
            "V2 threshold qualification fsync target is invalid"
        }
        val fd = Os.open(
            directory.absolutePath,
            OsConstants.O_RDONLY,
            0
        )
        try {
            Os.fsync(fd)
        } finally {
            Os.close(fd)
        }
    }
}
