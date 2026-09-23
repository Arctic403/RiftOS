package com.riftos.app

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.TreeSet
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Candidate production RiftTrainData V2 builder.
 *
 * V1 canary remains frozen in RiftTrainDataTaskRunner. This runner publishes
 * immutable candidate generations and hard-codes production eligibility false
 * while near-dedup/policy/device qualification remains open.
 */
object RiftTrainDataV2TaskRunner {
    private const val ARCHITECTURE_ID = RiftTrainDataV2Format.ARCHITECTURE_ID
    private const val TOKENIZER_ARTIFACT_RELATIVE = "tokenizer/output/rift-token-b-balanced-v2.riftbpe"
    private const val ROOT_RELATIVE = "training/private/production-v2"
    private const val INPUT_RELATIVE = "$ROOT_RELATIVE/input"
    private const val SOURCE_RELATIVE = "$INPUT_RELATIVE/source"
    private const val CHALLENGE_RELATIVE = "$INPUT_RELATIVE/challenge"
    private const val PROVENANCE_RELATIVE = "$INPUT_RELATIVE/provenance.jsonl"
    private const val POLICIES_RELATIVE = "training/policies/production-v2"
    private const val GENERATIONS_RELATIVE = "$ROOT_RELATIVE/generations"
    private const val CURRENT_RELATIVE = "$ROOT_RELATIVE/CURRENT.json"

    private const val GENERATION_DESCRIPTOR = "generation-descriptor.json"
    private const val PROVENANCE_OUTPUT = "provenance.jsonl"
    private const val TRAIN_PACK = "train.rifttok"
    private const val VALIDATION_PACK = "validation.rifttok"
    private const val CHALLENGE_PACK = "challenge.rifttok"
    private const val DATASET_MANIFEST = "dataset-manifest.json"
    private const val DEEP_VALIDATION_CONTRACT =
        "rift-train-data-v2-deep-validation-v1"

    private const val SOURCE_KIND_TABLE_VERSION = RiftTrainDataV2Format.SOURCE_KIND_TABLE_VERSION
    private const val SPLIT_POLICY_ID = RiftTrainDataV2Format.SPLIT_POLICY_ID
    private const val VALIDATION_PERMYRIAD = RiftTrainDataV2Format.VALIDATION_PERMYRIAD
    private const val DEDUP_POLICY_ID = RiftTrainDataV2Format.DEDUP_POLICY_ID
    private const val NEAR_DEDUP_IMPLEMENTED = false
    private const val PRODUCTION_ELIGIBLE = false
    private const val GENERATION_CONTRACT_REVISION = 1

    private const val MAX_SOURCE_UTF8_BYTES = RiftTrainDataV2Format.MAX_ORIGINAL_UTF8_BYTES
    private const val MAX_SOURCE_SHARDS = 4096
    private const val MAX_SOURCE_SHARD_BYTES = 64L * 1024L * 1024L
    private const val MAX_PROVENANCE_BYTES = 256L * 1024L * 1024L
    private const val MAX_RECORDS = 5_000_000L
    private const val DB_BATCH_RECORDS = 256

    private val ID_RE = Regex("[A-Za-z0-9._:-]{1,160}")
    private val GROUP_RE = Regex("[A-Za-z0-9._:/@+-]{1,240}")
    private val LANGUAGE_RE = Regex("[a-z0-9+._-]{1,64}")
    private val SHARD_RE = Regex("[A-Za-z0-9._-]{1,96}\\.jsonl")
    private val SHA_RE = Regex("[0-9a-f]{64}")

    private val ORIGINS = setOf(
        "rift_owned",
        "synthetic",
        "public_reference",
        "continual_verified"
    )

    private val SOURCE_KINDS = linkedMapOf(
        "source_code" to 1,
        "documentation" to 2,
        "requirements" to 3,
        "issue_discussion" to 4,
        "patch_diff" to 5,
        "code_review" to 6,
        "build_log" to 7,
        "test_log" to 8,
        "debug_trace" to 9,
        "tool_trace" to 10,
        "architecture" to 11,
        "verified_repair" to 12
    )

    private const val POLICY_FORMAT = "rift-training-policy-v2"
    private const val POLICY_REVISION = 1

    private val POLICY_FILES = linkedMapOf(
        "source-provenance" to "source-provenance-policy.json",
        "licensing-usage" to "licensing-usage-policy.json",
        "secret-privacy" to "secret-privacy-policy.json",
        "dedup" to "dedup-policy.json",
        "split-leakage" to "split-leakage-policy.json",
        "rendering" to "rendering-policy.json",
        "quality-verification" to "quality-verification-policy.json"
    )

    private val POLICY_IDS = linkedMapOf(
        "source-provenance" to "rift-source-provenance-v2",
        "licensing-usage" to "rift-licensing-usage-v2",
        "secret-privacy" to "rift-secret-privacy-v2",
        "dedup" to "rift-dedup-v2",
        "split-leakage" to "rift-split-leakage-v2",
        "rendering" to "rift-rendering-v2",
        "quality-verification" to "rift-quality-verification-v2"
    )

    private const val FLAG_PROVENANCE = 1L shl 0
    private const val FLAG_LICENSE = 1L shl 1
    private const val FLAG_SECRET_SCAN = 1L shl 2
    private const val FLAG_BUILD_VERIFIED = 1L shl 3
    private const val FLAG_TEST_VERIFIED = 1L shl 4
    private const val FLAG_SUCCESS = 1L shl 5
    private const val FLAG_FAILED = 1L shl 6
    private const val FLAG_PAIRED_REPAIR = 1L shl 7
    private const val FLAG_SYNTHETIC = 1L shl 8
    private const val FLAG_HUMAN = 1L shl 9

    private data class Shard(val name: String, val bytes: Long, val sha256: String)
    private data class Policy(
        val key: String,
        val fileName: String,
        val policyId: String,
        val sha256: String,
        val frozen: Boolean
    )
    private data class Provenance(
        val id: String,
        val origin: String,
        val sourceGroupId: String,
        val sourceRevision: String,
        val canonical: String
    )
    private data class SourceRecord(
        val id: String,
        val sourceGroupId: String,
        val kind: String,
        val language: String,
        val origin: String,
        val content: String,
        val sourceRevision: String,
        val provenanceId: String,
        val verificationFlags: Long,
        val canonical: String
    )
    private data class BuildJob(
        val id: String,
        val startedAtMs: Long,
        var updatedAtMs: Long = startedAtMs,
        var state: String = "queued",
        var recordsCompleted: Long = 0L,
        var tokensCompleted: Long = 0L,
        var message: String = "queued",
        var generationDescriptorSha256: String? = null,
        var datasetManifestSha256: String? = null,
        var error: String? = null
    )
    private data class GenerationInputs(
        val sourceShards: List<Shard>,
        val challengeShards: List<Shard>,
        val sourceSetSha256: String,
        val challengeSetSha256: String,
        val provenanceInputSha256: String,
        val provenanceOutputSha256: String,
        val policies: List<Policy>,
        val languages: List<String>,
        val provenanceCount: Long
    )

    private data class ValidatedPacks(
        val train: RiftTrainDataV2Format.PackInfo,
        val validation: RiftTrainDataV2Format.PackInfo,
        val challenge: RiftTrainDataV2Format.PackInfo
    )

    private data class NearDedupEvidence(
        val samplesScanned: Long,
        val sketchSamples: Long,
        val exactFallbackSamples: Long,
        val totalFingerprints: Long,
        val canonicalSketchStreamSha256: String
    ) {
        fun toJson(
            comparison: RiftB2NearDedupIndexV1.ComparisonEvidence
        ): JSONObject =
            JSONObject()
                .put("algorithmId", RiftB2BottomKDedupV1.ALGORITHM_ID)
                .put("similarityId", RiftB2BottomKDedupV1.SIMILARITY_ID)
                .put("shingleTokens", RiftB2BottomKDedupV1.SHINGLE_TOKENS)
                .put("bottomK", RiftB2BottomKDedupV1.BOTTOM_K)
                .put("scoreScale", RiftB2BottomKDedupV1.SCORE_SCALE)
                .put("thresholdFrozen", false)
                .put("globalComparisonImplemented", true)
                .put("rejectionApplied", false)
                .put("comparison", comparison.toJson())
                .put("samplesScanned", samplesScanned)
                .put("sketchSamples", sketchSamples)
                .put("exactFallbackSamples", exactFallbackSamples)
                .put("totalFingerprints", totalFingerprints)
                .put(
                    "canonicalSketchStreamSha256",
                    canonicalSketchStreamSha256
                )
    }

    private class NearDedupEvidenceAccumulator {
        private val digest = MessageDigest.getInstance("SHA-256")
        private var samplesScanned = 0L
        private var sketchSamples = 0L
        private var exactFallbackSamples = 0L
        private var totalFingerprints = 0L
        private var finished = false

        fun record(
            splitId: Int,
            sampleSha256: String,
            sketch: RiftB2BottomKDedupV1.Sketch
        ) {
            check(!finished) {
                "V2 near-dedup evidence accumulator already finished"
            }
            require(
                splitId in RiftTrainDataV2Format.SPLIT_TRAIN..
                    RiftTrainDataV2Format.SPLIT_CHALLENGE
            ) {
                "V2 near-dedup evidence split id is invalid"
            }

            val canonical =
                splitId.toString() +
                    "\t" +
                    RiftB2BottomKDedupV1.canonicalSketchLine(
                        sampleSha256,
                        sketch
                    )
            digest.update(canonical.toByteArray(Charsets.UTF_8))

            samplesScanned++
            if (sketch.isExactFallback) {
                exactFallbackSamples++
            } else {
                sketchSamples++
                totalFingerprints += sketch.fingerprints.size.toLong()
            }
        }

        fun finish(): NearDedupEvidence {
            check(!finished) {
                "V2 near-dedup evidence accumulator already finished"
            }
            finished = true
            require(
                samplesScanned > 0L &&
                    sketchSamples + exactFallbackSamples == samplesScanned
            ) {
                "V2 near-dedup evidence counters are inconsistent"
            }
            val sha = digest.digest().joinToString("") {
                (it.toInt() and 0xff)
                    .toString(16)
                    .padStart(2, '0')
            }
            return NearDedupEvidence(
                samplesScanned,
                sketchSamples,
                exactFallbackSamples,
                totalFingerprints,
                sha
            )
        }
    }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "rift-train-data-v2-build").apply { isDaemon = true }
    }
    private val lock = Any()
    private var activeJob: BuildJob? = null
    private var activeCancel: AtomicBoolean? = null

    fun execute(context: Context, args: JSONObject): Any =
        when (val op = args.optString("op", "status").trim().lowercase()) {
            "status" -> status(context.applicationContext)
            "build" -> startBuild(context.applicationContext)
            "build-status" -> jobJson()
            "build-cancel" -> cancelBuild()
            else -> throw IllegalArgumentException("unsupported fixed RiftTrainData V2 operation: " + op)
        }

    private fun projectRoot(context: Context): File {
        val riftRoot = File(context.filesDir, "riftfs").canonicalFile
        val root = File(riftRoot, "workspace/RiftLLM").canonicalFile
        require(root.path.startsWith(riftRoot.path + File.separator) && root.isDirectory) {
            "RiftLLM workspace project is missing"
        }
        return root
    }

    private fun exactPath(root: File, relative: String): File {
        require(relative.isNotBlank() && !relative.startsWith('/') && !relative.contains("..")) {
            "invalid fixed RiftTrainData V2 path"
        }
        val raw = File(root, relative).absoluteFile
        val normalized = raw.toPath().normalize().toFile().absoluteFile
        val file = raw.canonicalFile
        require(file.path.startsWith(root.path + File.separator)) {
            "RiftTrainData V2 path escaped project"
        }
        require(file.path == normalized.path) {
            "RiftTrainData V2 fixed path resolves through a symlink"
        }
        return file
    }

    private fun requireDirectRegularFile(
        directory: File,
        entry: File,
        label: String
    ): File {
        val canonicalDirectory = directory.canonicalFile
        require(!Files.isSymbolicLink(entry.toPath())) {
            label + " may not be a symlink"
        }
        val canonicalEntry = entry.canonicalFile
        require(
            canonicalEntry.isFile &&
                canonicalEntry.parentFile == canonicalDirectory &&
                canonicalEntry.path == entry.absoluteFile.toPath().normalize().toFile().path
        ) {
            label + " escaped its fixed directory"
        }
        return canonicalEntry
    }

    private fun status(context: Context): JSONObject {
        val root = projectRoot(context)
        val tokenizerOk = runCatching {
            RiftFrozenByteBpeV1.loadFrozen(exactPath(root, TOKENIZER_ARTIFACT_RELATIVE))
        }.isSuccess
        val sourceDir = exactPath(root, SOURCE_RELATIVE)
        val challengeDir = exactPath(root, CHALLENGE_RELATIVE)
        val provenanceFile = exactPath(root, PROVENANCE_RELATIVE)
        val policyDir = exactPath(root, POLICIES_RELATIVE)
        val currentFile = exactPath(root, CURRENT_RELATIVE)
        val current = runCatching {
            if (!currentFile.isFile) {
                null
            } else {
                val text = currentFile.readText(Charsets.UTF_8)
                val obj = JSONObject(text)
                require(RiftTrainDataV2Format.canonicalJson(obj) == text) {
                    "V2 CURRENT pointer is not canonical JSON"
                }
                require(
                    obj.keys().asSequence().toSet() ==
                        setOf(
                            "format",
                            "generationDescriptorSha256",
                            "datasetManifestSha256",
                            "deepValidated",
                            "deepValidationContract",
                            "productionPretrainingEligible"
                        )
                ) {
                    "V2 CURRENT pointer fields are non-canonical"
                }
                require(
                    obj.getString("format") ==
                        "rift-train-data-v2-current" &&
                        obj.getBoolean("deepValidated") &&
                        obj.getString("deepValidationContract") ==
                        DEEP_VALIDATION_CONTRACT &&
                        !obj.getBoolean("productionPretrainingEligible")
                ) {
                    "V2 CURRENT pointer validation contract mismatch"
                }
                obj
            }
        }.getOrNull()
        val descriptorSha = current?.optString("generationDescriptorSha256")
            ?.takeIf { SHA_RE.matches(it) }
        val manifestSha = current?.optString("datasetManifestSha256")
            ?.takeIf { SHA_RE.matches(it) }
        val generationDir = descriptorSha?.let {
            exactPath(root, GENERATIONS_RELATIVE + "/" + it)
        }
        val manifest = generationDir?.let {
            val file = File(it, DATASET_MANIFEST)
            runCatching {
                if (file.isFile) JSONObject(file.readText(Charsets.UTF_8)) else null
            }.getOrNull()
        }
        val currentDeepValidationPerformed = current != null
        val currentPointerValid =
            currentDeepValidationPerformed &&
                generationDir?.isDirectory == true &&
                manifest != null &&
                descriptorSha != null &&
                manifestSha != null &&
                RiftTrainDataV2Format.sha256File(File(generationDir, DATASET_MANIFEST)) == manifestSha &&
                manifest.optString("generationDescriptorSha256") == descriptorSha

        val sourceInputReady = runCatching {
            sourceShards(sourceDir).isNotEmpty()
        }.getOrDefault(false)
        val challengeInputReady = runCatching {
            sourceShards(challengeDir).isNotEmpty()
        }.getOrDefault(false)
        val provenanceInputReady =
            provenanceFile.isFile &&
                provenanceFile.length() in 1..MAX_PROVENANCE_BYTES
        val policies = runCatching { loadPolicies(policyDir) }.getOrNull()
        val policyArtifactsValid = policies != null
        val allPoliciesFrozen =
            policies?.let { items ->
                items.isNotEmpty() && items.all { it.frozen }
            } ?: false
        val policySummary = JSONArray()
        policies?.forEach { policy ->
            policySummary.put(
                JSONObject()
                    .put("key", policy.key)
                    .put("file", policy.fileName)
                    .put("policyId", policy.policyId)
                    .put("sha256", policy.sha256)
                    .put("frozen", policy.frozen)
            )
        }
        val thresholdStatus = runCatching {
            RiftB2ThresholdQualificationTask.execute(
                context,
                JSONObject().put("op", "status")
            ) as JSONObject
        }.getOrElse { error ->
            JSONObject()
                .put("statusError", error.message ?: error.javaClass.simpleName)
                .put("thresholdFrozen", false)
                .put("productionPretrainingEligible", false)
        }
        val adversarialStatus = runCatching {
            RiftTrainDataV2AdversarialLab.status(context)
        }.getOrElse { error ->
            JSONObject()
                .put("evidenceExists", false)
                .put("evidenceValidForCurrentApp", false)
                .put("statusError", error.message ?: error.javaClass.simpleName)
                .put("productionPretrainingEligible", false)
        }
        val gates = JSONObject()
            .put("tokenizerReady", tokenizerOk)
            .put("sourceInputReady", sourceInputReady)
            .put("challengeInputReady", challengeInputReady)
            .put("provenanceInputReady", provenanceInputReady)
            .put("policyArtifactsValid", policyArtifactsValid)
            .put("allPoliciesFrozen", allPoliciesFrozen)
            .put(
                "thresholdEvidenceExists",
                thresholdStatus.optBoolean("evidenceExists", false)
            )
            .put(
                "thresholdEvidenceValid",
                thresholdStatus.optBoolean("evidenceValid", false)
            )
            .put(
                "thresholdQualifiedIntervalExists",
                thresholdStatus.optBoolean("qualifiedIntervalExists", false)
            )
            .put("thresholdFrozen", false)
            .put(
                "adversarialEvidenceValidForCurrentApp",
                adversarialStatus.optBoolean(
                    "evidenceValidForCurrentApp",
                    false
                )
            )
            .put("currentGenerationPointerValid", currentPointerValid)
            .put(
                "currentGenerationDeepValidationPerformed",
                currentDeepValidationPerformed
            )
            .put("hardwareTargetAEvidenceValid", false)
            .put("productionPretrainingEligible", false)

        return JSONObject()
            .put("schema", "rift.train-data-v2-status/1")
            .put("purpose", "software-engineering-pretraining")
            .put("format", RiftTrainDataV2Format.FORMAT)
            .put("productionPretrainingEligible", false)
            .put("nearDedupImplemented", NEAR_DEDUP_IMPLEMENTED)
            .put("tokenizerAvailable", tokenizerOk)
            .put("sourceAvailable", sourceDir.isDirectory)
            .put("challengeAvailable", challengeDir.isDirectory)
            .put("provenanceAvailable", provenanceFile.isFile)
            .put("policyDirectoryAvailable", policyDir.isDirectory)
            .put("policyArtifactsValid", policyArtifactsValid)
            .put("allPoliciesFrozen", allPoliciesFrozen)
            .put("policyArtifacts", policySummary)
            .put("thresholdQualification", thresholdStatus)
            .put("adversarialParser", adversarialStatus)
            .put("gates", gates)
            .put("currentPointerValid", currentPointerValid)
            .put(
                "currentDeepValidationPerformed",
                currentDeepValidationPerformed
            )
            .put("currentGenerationDescriptorSha256", descriptorSha ?: JSONObject.NULL)
            .put("currentDatasetManifestSha256", manifestSha ?: JSONObject.NULL)
            .put("currentManifest", manifest ?: JSONObject.NULL)
            .put("buildJob", jobJson())
    }

    private fun startBuild(context: Context): JSONObject {
        synchronized(lock) {
            val current = activeJob
            require(current == null || current.state !in setOf("queued", "running", "cancelling")) {
                "RiftTrainData V2 build is already active"
            }
            val now = System.currentTimeMillis()
            val job = BuildJob("rift-train-data-v2-" + now, now)
            val cancel = AtomicBoolean(false)
            activeJob = job
            activeCancel = cancel
            executor.execute { runBuild(context, job, cancel) }
        }
        return jobJson()
    }

    private fun cancelBuild(): JSONObject {
        synchronized(lock) {
            activeJob?.let { job ->
                if (job.state in setOf("queued", "running", "cancelling")) {
                    job.state = "cancelling"
                    job.message = "cancel requested"
                    job.updatedAtMs = System.currentTimeMillis()
                    activeCancel?.set(true)
                }
            }
        }
        return jobJson()
    }

    private fun jobJson(): JSONObject = synchronized(lock) {
        val job = activeJob ?: return@synchronized JSONObject()
            .put("schema", "rift.train-data-v2-job/1")
            .put("active", false)
            .put("state", "idle")
        JSONObject()
            .put("schema", "rift.train-data-v2-job/1")
            .put("active", job.state in setOf("queued", "running", "cancelling"))
            .put("jobId", job.id)
            .put("state", job.state)
            .put("recordsCompleted", job.recordsCompleted)
            .put("tokensCompleted", job.tokensCompleted)
            .put("generationDescriptorSha256", job.generationDescriptorSha256 ?: JSONObject.NULL)
            .put("datasetManifestSha256", job.datasetManifestSha256 ?: JSONObject.NULL)
            .put("message", job.message)
            .put("error", job.error ?: JSONObject.NULL)
            .put("startedAtMs", job.startedAtMs)
            .put("updatedAtMs", job.updatedAtMs)
    }

    private fun updateJob(job: BuildJob, block: (BuildJob) -> Unit) = synchronized(lock) {
        if (activeJob !== job) return@synchronized
        block(job)
        job.updatedAtMs = System.currentTimeMillis()
    }

    private fun ensureRunning(cancel: AtomicBoolean) {
        if (cancel.get() || Thread.currentThread().isInterrupted) {
            throw CancellationException("RiftTrainData V2 build cancelled")
        }
    }

    private fun runBuild(context: Context, job: BuildJob, cancel: AtomicBoolean) {
        try {
            updateJob(job) {
                it.state = "running"
                it.message = "validating production V2 inputs"
            }
            val result = buildBlocking(context, job, cancel)
            updateJob(job) {
                it.state = "complete"
                it.message = "RiftTrainData V2 candidate generation published"
                it.generationDescriptorSha256 = result.getString("generationDescriptorSha256")
                it.datasetManifestSha256 = result.getString("datasetManifestSha256")
            }
        } catch (_: CancellationException) {
            updateJob(job) {
                it.state = "cancelled"
                it.message = "RiftTrainData V2 build cancelled"
            }
        } catch (error: Throwable) {
            updateJob(job) {
                it.state = "failed"
                it.message = "RiftTrainData V2 build failed"
                it.error = error.message ?: error.javaClass.simpleName
            }
        } finally {
            synchronized(lock) {
                if (activeCancel === cancel) activeCancel = null
            }
        }
    }

    private fun buildBlocking(
        context: Context,
        job: BuildJob,
        cancel: AtomicBoolean
    ): JSONObject {
        val root = projectRoot(context)
        val artifact = RiftFrozenByteBpeV1.loadFrozen(
            exactPath(root, TOKENIZER_ARTIFACT_RELATIVE)
        )
        val encoder = RiftFrozenByteBpeV1.Encoder(artifact)

        val rootDir = exactPath(root, ROOT_RELATIVE)
        require(rootDir.isDirectory || rootDir.mkdirs()) {
            "could not create production V2 root"
        }
        val generationsDir = exactPath(root, GENERATIONS_RELATIVE)
        require(generationsDir.isDirectory || generationsDir.mkdirs()) {
            "could not create V2 generations directory"
        }

        val stageRaw = File(rootDir, "." + job.id + ".stage").absoluteFile
        val stage = stageRaw.canonicalFile
        require(
            stage.parentFile == rootDir.canonicalFile &&
                stage.path == stageRaw.toPath().normalize().toFile().path
        ) {
            "invalid V2 stage path"
        }
        require(!stage.exists()) { "V2 stage path already exists" }
        require(stage.mkdirs()) { "could not create V2 stage directory" }

        val dbFile = File(stage, ".build-index.sqlite")
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            configureDb(db)
            val sourceDir = exactPath(root, SOURCE_RELATIVE)
            val challengeDir = exactPath(root, CHALLENGE_RELATIVE)
            val provenanceInput = exactPath(root, PROVENANCE_RELATIVE)
            val provenanceInputSha = requireStableInputFile(provenanceInput)
            val policyDir = exactPath(root, POLICIES_RELATIVE)
            val provenanceOutput = File(stage, PROVENANCE_OUTPUT)

            val policies = loadPolicies(policyDir)
            val sourceShards = sourceShards(sourceDir)
            val challengeShards = sourceShards(challengeDir)
            val sourceSetSha = shardSetSha(sourceShards)
            val challengeSetSha = shardSetSha(challengeShards)
            val provenanceCount = canonicalizeProvenance(
                provenanceInput,
                provenanceOutput,
                db,
                cancel
            )
            require(RiftTrainDataV2Format.sha256File(provenanceInput) == provenanceInputSha) {
                "V2 provenance input changed during canonicalization"
            }
            val provenanceOutputSha = RiftTrainDataV2Format.sha256File(provenanceOutput)
            val languages = preScanLanguages(
                sourceDir,
                sourceShards,
                challengeDir,
                challengeShards,
                db,
                cancel
            )
            val inputs = GenerationInputs(
                sourceShards,
                challengeShards,
                sourceSetSha,
                challengeSetSha,
                provenanceInputSha,
                provenanceOutputSha,
                policies,
                languages,
                provenanceCount
            )

            val descriptor = generationDescriptor(inputs)
            val descriptorText = RiftTrainDataV2Format.canonicalJson(descriptor)
            val descriptorBytes = descriptorText.toByteArray(Charsets.UTF_8)
            val descriptorSha = RiftTrainDataV2Format.sha256Bytes(descriptorBytes)
            val descriptorFile = File(stage, GENERATION_DESCRIPTOR)
            writeSynced(descriptorFile, descriptorBytes)
            updateJob(job) {
                it.generationDescriptorSha256 = descriptorSha
                it.message = "encoding V2 train/validation/challenge packs"
            }

            val policyHashes = inputs.policies.associate { it.key to it.sha256 }
            fun metadata(role: String, splitId: Int) = RiftTrainDataV2Format.Metadata(
                role,
                splitId,
                ARCHITECTURE_ID,
                descriptorSha,
                provenanceOutputSha,
                inputs.provenanceCount,
                policyHashes,
                languages,
                SOURCE_KIND_TABLE_VERSION,
                SPLIT_POLICY_ID,
                VALIDATION_PERMYRIAD,
                DEDUP_POLICY_ID,
                NEAR_DEDUP_IMPLEMENTED,
                PRODUCTION_ELIGIBLE
            )

            val trainWriter = RiftTrainDataV2Format.Writer(
                stage,
                TRAIN_PACK,
                metadata("train", RiftTrainDataV2Format.SPLIT_TRAIN)
            )
            val validationWriter = RiftTrainDataV2Format.Writer(
                stage,
                VALIDATION_PACK,
                metadata("validation", RiftTrainDataV2Format.SPLIT_VALIDATION)
            )
            val challengeWriter = RiftTrainDataV2Format.Writer(
                stage,
                CHALLENGE_PACK,
                metadata("challenge", RiftTrainDataV2Format.SPLIT_CHALLENGE)
            )
            val nearDedupEvidenceAccumulator = NearDedupEvidenceAccumulator()
            val nearDedupComparator =
                RiftB2NearDedupIndexV1.Session(db) {
                    ensureRunning(cancel)
                }

            try {
                processRecords(
                    sourceDir,
                    sourceShards,
                    false,
                    db,
                    languages,
                    artifact,
                    encoder,
                    trainWriter,
                    validationWriter,
                    challengeWriter,
                    nearDedupEvidenceAccumulator,
                    nearDedupComparator,
                    cancel
                ) { tokens ->
                    updateJob(job) {
                        it.recordsCompleted++
                        it.tokensCompleted += tokens.toLong()
                        if ((it.recordsCompleted and 511L) == 0L) {
                            it.message = "encoded " + it.recordsCompleted + " production V2 records"
                        }
                    }
                }
                processRecords(
                    challengeDir,
                    challengeShards,
                    true,
                    db,
                    languages,
                    artifact,
                    encoder,
                    trainWriter,
                    validationWriter,
                    challengeWriter,
                    nearDedupEvidenceAccumulator,
                    nearDedupComparator,
                    cancel
                ) { tokens ->
                    updateJob(job) {
                        it.recordsCompleted++
                        it.tokensCompleted += tokens.toLong()
                    }
                }

                ensureRunning(cancel)
                val nearDedupEvidence = nearDedupEvidenceAccumulator.finish()
                val nearDedupComparison = nearDedupComparator.finish()
                require(
                    nearDedupComparison.indexedSketchSamples ==
                        nearDedupEvidence.sketchSamples
                ) {
                    "V2 near-dedup comparator indexed-sample count mismatch"
                }
                val train = trainWriter.finish()
                val validation = validationWriter.finish()
                val challenge = challengeWriter.finish()
                require(
                    train.sampleCount > 0L &&
                        validation.sampleCount > 0L &&
                        challenge.sampleCount > 0L
                ) {
                    "V2 requires non-empty train, validation and challenge packs"
                }
                require(
                    nearDedupEvidence.samplesScanned ==
                        train.sampleCount +
                        validation.sampleCount +
                        challenge.sampleCount
                ) {
                    "V2 near-dedup evidence sample count does not match packs"
                }

                verifyInputSet(sourceDir, sourceShards, sourceSetSha)
                verifyInputSet(challengeDir, challengeShards, challengeSetSha)
                require(RiftTrainDataV2Format.sha256File(provenanceInput) == provenanceInputSha) {
                    "V2 provenance input changed during build"
                }
                verifyPolicies(policyDir, policies)
                RiftFrozenByteBpeV1.loadFrozen(exactPath(root, TOKENIZER_ARTIFACT_RELATIVE))

                val manifest = datasetManifest(
                    descriptorSha,
                    descriptorFile,
                    provenanceOutput,
                    inputs,
                    nearDedupEvidence,
                    nearDedupComparison,
                    train,
                    validation,
                    challenge
                )
                val manifestText = RiftTrainDataV2Format.canonicalJson(manifest)
                val manifestFile = File(stage, DATASET_MANIFEST)
                writeSynced(
                    manifestFile,
                    manifestText.toByteArray(Charsets.UTF_8)
                )
                val manifestSha = RiftTrainDataV2Format.sha256File(manifestFile)

                db.close()
                runCatching { dbFile.delete() }
                File(stage, ".build-index.sqlite-journal").delete()
                File(stage, ".build-index.sqlite-wal").delete()
                File(stage, ".build-index.sqlite-shm").delete()
                fsyncDirectory(stage)

                validateGeneration(stage, descriptorSha, manifestSha)
                val generationDir = exactPath(
                    root,
                    GENERATIONS_RELATIVE + "/" + descriptorSha
                )
                if (generationDir.exists()) {
                    require(generationDir.isDirectory) {
                        "existing V2 generation path is not a directory"
                    }
                    validateGeneration(
                        generationDir,
                        descriptorSha,
                        manifestSha
                    )
                    require(stage.deleteRecursively()) {
                        "V2 duplicate stage cleanup failed"
                    }
                } else {
                    Files.move(
                        stage.toPath(),
                        generationDir.toPath(),
                        StandardCopyOption.ATOMIC_MOVE
                    )
                    fsyncDirectory(generationsDir)
                    validateGeneration(
                        generationDir,
                        descriptorSha,
                        manifestSha
                    )
                }

                publishCurrent(root, descriptorSha, manifestSha)
                updateJob(job) {
                    it.datasetManifestSha256 = manifestSha
                }
                return JSONObject()
                    .put("generationDescriptorSha256", descriptorSha)
                    .put("datasetManifestSha256", manifestSha)
                    .put("productionPretrainingEligible", false)
                    .put(
                        "generationPath",
                        "/workspace/RiftLLM/" +
                            GENERATIONS_RELATIVE +
                            "/" +
                            descriptorSha
                    )
            } finally {
                runCatching { trainWriter.close() }
                runCatching { validationWriter.close() }
                runCatching { challengeWriter.close() }
            }
        } finally {
            if (db.isOpen) db.close()
            if (stage.exists()) stage.deleteRecursively()
        }
    }

    private fun configureDb(db: SQLiteDatabase) {
        db.execSQL("PRAGMA journal_mode=DELETE")
        db.execSQL("PRAGMA synchronous=FULL")
        db.execSQL(
            "CREATE TABLE provenance(" +
                "id TEXT PRIMARY KEY," +
                "idx INTEGER NOT NULL UNIQUE," +
                "origin TEXT NOT NULL," +
                "group_id TEXT NOT NULL," +
                "revision TEXT NOT NULL)"
        )
        db.execSQL("CREATE TABLE seen_source_id(hash TEXT PRIMARY KEY)")
        db.execSQL("CREATE TABLE seen_content(hash TEXT PRIMARY KEY)")
        db.execSQL("CREATE TABLE seen_sample(hash TEXT PRIMARY KEY)")
        db.execSQL(
            "CREATE TABLE groups(" +
                "hash TEXT PRIMARY KEY," +
                "role INTEGER NOT NULL," +
                "split INTEGER NOT NULL)"
        )
    }

    private fun canonicalizeProvenance(
        input: File,
        output: File,
        db: SQLiteDatabase,
        cancel: AtomicBoolean
    ): Long {
        require(input.isFile && input.length() in 1..MAX_PROVENANCE_BYTES) {
            "V2 provenance input is missing/out of bounds"
        }
        var count = 0L
        FileOutputStream(output, false).use { fos ->
            bufferedUtf8Reader(input).use { reader ->
                var lineNumber = 0L
                while (true) {
                    val line = reader.readLine() ?: break
                    lineNumber++
                    if (line.isBlank()) continue
                    if ((count and 255L) == 0L) ensureRunning(cancel)
                    val row = parseProvenance(
                        parseObject(line, input.name, lineNumber)
                    )
                    try {
                        db.execSQL(
                            "INSERT INTO provenance(" +
                                "id,idx,origin,group_id,revision) " +
                                "VALUES(?,?,?,?,?)",
                            arrayOf(
                                row.id,
                                count,
                                row.origin,
                                row.sourceGroupId,
                                row.sourceRevision
                            )
                        )
                    } catch (error: SQLiteConstraintException) {
                        throw IllegalArgumentException(
                            "duplicate provenance id: " + row.id,
                            error
                        )
                    }
                    fos.write(row.canonical.toByteArray(Charsets.UTF_8))
                    fos.write('\n'.code)
                    count++
                    require(count <= MAX_RECORDS) {
                        "V2 provenance row count exceeds limit"
                    }
                }
            }
            fos.fd.sync()
        }
        require(count > 0L) { "V2 provenance is empty" }
        return count
    }

    private fun preScanLanguages(
        sourceDir: File,
        sourceShards: List<Shard>,
        challengeDir: File,
        challengeShards: List<Shard>,
        db: SQLiteDatabase,
        cancel: AtomicBoolean
    ): List<String> {
        val languages = TreeSet<String>()

        fun scan(dir: File, shards: List<Shard>) {
            streamObjects(dir, shards, cancel) { obj, fileName, lineNumber ->
                val record = parseSource(obj, fileName, lineNumber)
                validateProvenanceLink(db, record)
                languages += record.language
            }
        }

        scan(sourceDir, sourceShards)
        scan(challengeDir, challengeShards)
        languages.remove("mixed")
        require(languages.size < RiftTrainDataV2Format.MAX_LANGUAGE_COUNT) {
            "V2 language table is too large"
        }
        return listOf("mixed") + languages.toList()
    }

    private fun processRecords(
        directory: File,
        shards: List<Shard>,
        challenge: Boolean,
        db: SQLiteDatabase,
        languages: List<String>,
        artifact: RiftFrozenByteBpeV1.Artifact,
        encoder: RiftFrozenByteBpeV1.Encoder,
        trainWriter: RiftTrainDataV2Format.Writer,
        validationWriter: RiftTrainDataV2Format.Writer,
        challengeWriter: RiftTrainDataV2Format.Writer,
        nearDedupEvidence: NearDedupEvidenceAccumulator,
        nearDedupComparator: RiftB2NearDedupIndexV1.Session,
        cancel: AtomicBoolean,
        progress: (Int) -> Unit
    ) {
        val languageIds = languages.withIndex().associate { it.value to it.index }
        var parityChecks = 0
        var batchedRecords = 0

        db.beginTransaction()
        try {
            streamObjects(directory, shards, cancel) { obj, fileName, lineNumber ->
            val record = parseSource(obj, fileName, lineNumber)
            val provenanceIndex = validateProvenanceLink(db, record)
            val contentBytes = record.content.toByteArray(Charsets.UTF_8)
            require(contentBytes.size in 1..MAX_SOURCE_UTF8_BYTES) {
                fileName + ":" + lineNumber + " source content is out of bounds"
            }
            val encoded = encoder.encode(contentBytes)
            require(
                encoded.size in 1..RiftTrainDataV2Format.MAX_CONTENT_TOKENS
            ) {
                fileName +
                    ":" +
                    lineNumber +
                    " B2 content does not fit the 2048-token qualified context"
            }
            if (parityChecks < 32) {
                require(
                    encoded.contentEquals(
                        encoder.referenceEncode(contentBytes)
                    )
                ) {
                    fileName +
                        ":" +
                        lineNumber +
                        " B2 fast/reference parity failed"
                }
                parityChecks++
            }

            insertUnique(
                db,
                "seen_source_id",
                record.id,
                fileName + ":" + lineNumber + " duplicate source id"
            )

            val contentShaRaw =
                RiftTrainDataV2Format.sha256Raw(contentBytes)
            val contentSha = hex(contentShaRaw)
            insertUnique(
                db,
                "seen_content",
                contentSha,
                fileName + ":" + lineNumber + " duplicate content"
            )

            val sampleShaRaw = RiftTrainDataV2Format.sha256Raw(
                record.canonical.toByteArray(Charsets.UTF_8)
            )
            val sampleSha = hex(sampleShaRaw)
            insertUnique(
                db,
                "seen_sample",
                sampleSha,
                fileName + ":" + lineNumber + " duplicate sample identity"
            )

            val groupShaRaw = groupSha(record.sourceGroupId)
            val groupSha = hex(groupShaRaw)
            val splitId = assignGroup(db, groupSha, challenge)
            val expectedSplit =
                if (challenge) {
                    RiftTrainDataV2Format.SPLIT_CHALLENGE
                } else {
                    splitForGroup(groupShaRaw)
                }
            require(splitId == expectedSplit) {
                fileName + ":" + lineNumber + " group split drifted"
            }

            val nearDedupSketch = RiftB2BottomKDedupV1.sketch(encoded)
            nearDedupEvidence.record(
                splitId,
                sampleSha,
                nearDedupSketch
            )
            nearDedupComparator.observe(
                sampleSha,
                splitId,
                nearDedupSketch
            )

            val tokenIds = IntArray(encoded.size + 2)
            tokenIds[0] = artifact.specialIds.getValue("<|bos|>")
            encoded.copyInto(tokenIds, 1)
            tokenIds[tokenIds.lastIndex] =
                artifact.specialIds.getValue("<|eos|>")

            val packed = RiftTrainDataV2Format.Record(
                sampleShaRaw,
                contentShaRaw,
                groupShaRaw,
                provenanceIndex,
                contentBytes.size.toLong(),
                tokenIds,
                languageIds.getValue(record.language),
                SOURCE_KINDS.getValue(record.kind),
                record.verificationFlags
            )

            when (splitId) {
                RiftTrainDataV2Format.SPLIT_TRAIN ->
                    trainWriter.append(packed)
                RiftTrainDataV2Format.SPLIT_VALIDATION ->
                    validationWriter.append(packed)
                RiftTrainDataV2Format.SPLIT_CHALLENGE ->
                    challengeWriter.append(packed)
                else -> error("invalid V2 split")
            }
            progress(tokenIds.size)
            batchedRecords++
            if (batchedRecords >= DB_BATCH_RECORDS) {
                db.setTransactionSuccessful()
                db.endTransaction()
                db.beginTransaction()
                batchedRecords = 0
            }
        }
            db.setTransactionSuccessful()
        } finally {
            if (db.inTransaction()) db.endTransaction()
        }
    }

    private fun parseSource(
        obj: JSONObject,
        fileName: String,
        lineNumber: Long
    ): SourceRecord {
        requireExactKeys(
            obj,
            setOf(
                "id",
                "sourceGroupId",
                "kind",
                "language",
                "origin",
                "content",
                "sourceRevision",
                "provenanceId",
                "verificationFlags"
            ),
            fileName + ":" + lineNumber + " source"
        )

        val id = requiredString(obj, "id")
        require(ID_RE.matches(id)) {
            fileName + ":" + lineNumber + " invalid source id"
        }
        val group = requiredString(obj, "sourceGroupId")
        require(GROUP_RE.matches(group)) {
            fileName + ":" + lineNumber + " invalid sourceGroupId"
        }
        val kind = requiredString(obj, "kind")
        require(SOURCE_KINDS.containsKey(kind)) {
            fileName + ":" + lineNumber + " unknown source kind"
        }
        val language = requiredString(obj, "language")
        require(
            LANGUAGE_RE.matches(language) &&
                language == language.lowercase()
        ) {
            fileName +
                ":" +
                lineNumber +
                " invalid/case-variant language id"
        }
        val origin = requiredString(obj, "origin")
        require(origin in ORIGINS) {
            fileName + ":" + lineNumber + " unsupported origin"
        }
        require(obj.has("content") && !obj.isNull("content") && obj.get("content") is String) {
            fileName + ":" + lineNumber + " content must be a string"
        }
        val content = obj.getString("content")
        require(content.isNotEmpty()) {
            fileName + ":" + lineNumber + " content is empty"
        }
        require('\u0000' !in content) {
            fileName + ":" + lineNumber + " content contains NUL"
        }
        val revision = requiredString(obj, "sourceRevision")
        require(revision.length <= 240) {
            fileName + ":" + lineNumber + " sourceRevision is too long"
        }
        val provenanceId = requiredString(obj, "provenanceId")
        require(ID_RE.matches(provenanceId)) {
            fileName + ":" + lineNumber + " invalid provenanceId"
        }
        val flags = obj.getLong("verificationFlags")
        validateFlags(
            kind,
            origin,
            flags,
            fileName + ":" + lineNumber
        )
        return SourceRecord(
            id,
            group,
            kind,
            language,
            origin,
            content,
            revision,
            provenanceId,
            flags,
            RiftTrainDataV2Format.canonicalJson(obj)
        )
    }

    private fun parseProvenance(obj: JSONObject): Provenance {
        requireExactKeys(
            obj,
            setOf(
                "id",
                "origin",
                "sourceGroupId",
                "sourceRevision",
                "usagePolicyId",
                "evidenceSha256",
                "sourceUri",
                "licenseId",
                "notes"
            ),
            "provenance",
            optional = setOf("sourceUri", "licenseId", "notes")
        )
        val id = requiredString(obj, "id")
        require(ID_RE.matches(id)) { "invalid provenance id" }
        val origin = requiredString(obj, "origin")
        require(origin in ORIGINS) { "unsupported provenance origin" }
        val group = requiredString(obj, "sourceGroupId")
        require(GROUP_RE.matches(group)) {
            "invalid provenance sourceGroupId"
        }
        val revision = requiredString(obj, "sourceRevision")
        require(revision.length <= 240) {
            "provenance sourceRevision is too long"
        }
        val usagePolicyId = requiredString(obj, "usagePolicyId")
        require(usagePolicyId.length <= 160) {
            "usagePolicyId is too long"
        }
        val evidenceSha = requiredString(obj, "evidenceSha256")
        require(SHA_RE.matches(evidenceSha)) {
            "provenance evidenceSha256 is invalid"
        }

        listOf("sourceUri", "licenseId", "notes").forEach { key ->
            if (obj.has(key) && !obj.isNull(key)) {
                require(obj.getString(key).length <= 4096) {
                    "provenance " + key + " is too long"
                }
            }
        }
        return Provenance(
            id,
            origin,
            group,
            revision,
            RiftTrainDataV2Format.canonicalJson(obj)
        )
    }

    private fun validateFlags(
        kind: String,
        origin: String,
        flags: Long,
        label: String
    ) {
        require(flags in 0..0xffffffffL) {
            label + " verificationFlags out of u32 range"
        }
        require(
            (flags and
                RiftTrainDataV2Format.KNOWN_VERIFICATION_FLAGS.inv()) == 0L
        ) {
            label + " verificationFlags contain unknown bits"
        }

        val required =
            FLAG_PROVENANCE or FLAG_LICENSE or FLAG_SECRET_SCAN
        require((flags and required) == required) {
            label + " provenance/license/secret gates are required"
        }

        val synthetic = (flags and FLAG_SYNTHETIC) != 0L
        val human = (flags and FLAG_HUMAN) != 0L
        require(synthetic.xor(human)) {
            label +
                " must identify exactly one synthetic/human authorship class"
        }
        if (origin == "synthetic") {
            require(synthetic) {
                label + " synthetic origin requires synthetic authorship flag"
            }
        }

        require(
            !(
                (flags and FLAG_SUCCESS) != 0L &&
                    (flags and FLAG_FAILED) != 0L
                )
        ) {
            label + " cannot be both successful and failed"
        }

        require(
            (flags and FLAG_PAIRED_REPAIR) == 0L ||
                (flags and FLAG_FAILED) != 0L ||
                kind == "verified_repair"
        ) {
            label +
                " paired-repair flag requires failed source or verified repair"
        }

        if (kind == "build_log") {
            require((flags and FLAG_BUILD_VERIFIED) != 0L) {
                label + " build_log must have verified build outcome"
            }
            require(
                ((flags and FLAG_SUCCESS) != 0L).xor(
                    (flags and FLAG_FAILED) != 0L
                )
            ) {
                label + " build_log must identify success or failure"
            }
        }
        if (kind == "test_log") {
            require((flags and FLAG_TEST_VERIFIED) != 0L) {
                label + " test_log must have verified test outcome"
            }
            require(
                ((flags and FLAG_SUCCESS) != 0L).xor(
                    (flags and FLAG_FAILED) != 0L
                )
            ) {
                label + " test_log must identify success or failure"
            }
        }
        if (kind == "verified_repair") {
            require(
                (flags and FLAG_SUCCESS) != 0L &&
                    (flags and FLAG_PAIRED_REPAIR) != 0L
            ) {
                label +
                    " verified_repair requires successful + paired-repair flags"
            }
        }
    }

    private fun validateProvenanceLink(
        db: SQLiteDatabase,
        record: SourceRecord
    ): Long {
        db.rawQuery(
            "SELECT idx,origin,group_id,revision " +
                "FROM provenance WHERE id=?",
            arrayOf(record.provenanceId)
        ).use { cursor ->
            require(cursor.moveToFirst()) {
                "missing provenance row for " + record.provenanceId
            }
            val index = cursor.getLong(0)
            require(cursor.getString(1) == record.origin) {
                "source/provenance origin mismatch"
            }
            require(cursor.getString(2) == record.sourceGroupId) {
                "source/provenance group mismatch"
            }
            require(cursor.getString(3) == record.sourceRevision) {
                "source/provenance revision mismatch"
            }
            return index
        }
    }

    private fun assignGroup(
        db: SQLiteDatabase,
        hash: String,
        challenge: Boolean
    ): Int {
        val role = if (challenge) 1 else 0
        val split =
            if (challenge) {
                RiftTrainDataV2Format.SPLIT_CHALLENGE
            } else {
                splitForGroup(
                    RiftTrainDataV2Format.hexToBytes(hash)
                )
            }

        db.rawQuery(
            "SELECT role,split FROM groups WHERE hash=?",
            arrayOf(hash)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                require(cursor.getInt(0) == role) {
                    "V2 challenge/train-validation source-group leakage detected"
                }
                require(cursor.getInt(1) == split) {
                    "V2 source-group split disagreement"
                }
                return split
            }
        }

        db.execSQL(
            "INSERT INTO groups(hash,role,split) VALUES(?,?,?)",
            arrayOf(hash, role, split)
        )
        return split
    }

    private fun splitForGroup(groupSha: ByteArray): Int {
        require(groupSha.size == 32)
        val prefix =
            "rift-prod-group-split-v1\u0000".toByteArray(Charsets.UTF_8)
        val material = ByteArray(prefix.size + groupSha.size)
        prefix.copyInto(material)
        groupSha.copyInto(material, prefix.size)
        val digest = RiftTrainDataV2Format.sha256Raw(material)
        val firstU32 =
            ((digest[0].toLong() and 0xffL) shl 24) or
                ((digest[1].toLong() and 0xffL) shl 16) or
                ((digest[2].toLong() and 0xffL) shl 8) or
                (digest[3].toLong() and 0xffL)
        val bucket = (firstU32 % 10000L).toInt()
        return if (bucket < VALIDATION_PERMYRIAD) {
            RiftTrainDataV2Format.SPLIT_VALIDATION
        } else {
            RiftTrainDataV2Format.SPLIT_TRAIN
        }
    }

    private fun groupSha(sourceGroupId: String): ByteArray {
        val prefix =
            "rift-source-group-v1\u0000".toByteArray(Charsets.UTF_8)
        val group = sourceGroupId.toByteArray(Charsets.UTF_8)
        val material = ByteArray(prefix.size + group.size)
        prefix.copyInto(material)
        group.copyInto(material, prefix.size)
        return RiftTrainDataV2Format.sha256Raw(material)
    }

    private fun insertUnique(
        db: SQLiteDatabase,
        table: String,
        hash: String,
        message: String
    ) {
        require(
            table == "seen_source_id" ||
                table == "seen_content" ||
                table == "seen_sample"
        )
        try {
            db.execSQL(
                "INSERT INTO " + table + "(hash) VALUES(?)",
                arrayOf(hash)
            )
        } catch (error: SQLiteConstraintException) {
            throw IllegalArgumentException(message, error)
        }
    }

    private fun validatePolicyArtifact(
        key: String,
        fileName: String,
        obj: JSONObject
    ) {
        require(
            obj.keys().asSequence().toSet() ==
                setOf(
                    "format",
                    "policyKey",
                    "policyId",
                    "version",
                    "reviewStatus",
                    "frozen",
                    "rules"
                )
        ) {
            "V2 policy artifact fields are non-canonical: " + fileName
        }
        require(obj.getString("format") == "rift-train-data-v2-policy") {
            "V2 policy artifact format mismatch: " + fileName
        }
        require(
            obj.getString("policyKey") == key &&
                POLICY_FILES[key] == fileName
        ) {
            "V2 policy artifact identity mismatch: " + fileName
        }
        require(
            obj.getString("policyId")
                .matches(Regex("[a-z0-9._:-]{1,160}"))
        ) {
            "V2 policy artifact ID is invalid: " + fileName
        }
        require(obj.getInt("version") == 1) {
            "V2 policy artifact version mismatch: " + fileName
        }
        val reviewStatus = obj.getString("reviewStatus")
        require(reviewStatus == "draft" || reviewStatus == "reviewed") {
            "V2 policy artifact review status is invalid: " + fileName
        }
        val frozen = obj.getBoolean("frozen")
        require(!frozen || reviewStatus == "reviewed") {
            "V2 frozen policy artifact must be reviewed: " + fileName
        }
        val rules = obj.getJSONObject("rules")
        require(rules.length() in 1..256) {
            "V2 policy artifact rules are empty/too large: " + fileName
        }
        rules.keys().asSequence().forEach { ruleKey ->
            require(ruleKey.matches(Regex("[a-z0-9._-]{1,96}"))) {
                "V2 policy artifact rule key is invalid: " + fileName
            }
        }
        require(
            RiftTrainDataV2Format
                .canonicalJson(rules)
                .toByteArray(Charsets.UTF_8)
                .size <= 512 * 1024
        ) {
            "V2 policy artifact rules exceed canonical size limit: " + fileName
        }
    }

    private fun loadPolicies(dir: File): List<Policy> {
        require(dir.isDirectory) { "V2 policy directory is missing" }
        val entries = dir.listFiles() ?: emptyArray()
        require(
            entries.size == POLICY_FILES.size &&
                entries.all { it.isFile } &&
                entries.map { it.name }.toSet() ==
                POLICY_FILES.values.toSet()
        ) {
            "V2 policy directory contains unexpected/missing entries"
        }

        return POLICY_FILES.map { entry ->
            val key = entry.key
            val name = entry.value
            val file = requireDirectRegularFile(
                dir,
                File(dir, name),
                "V2 policy file " + name
            )
            require(file.length() in 2..(1024L * 1024L)) {
                "V2 policy file missing/out of bounds: " + name
            }
            val obj = readCanonicalObject(
                file,
                "V2 policy artifact " + name
            )
            validatePolicyArtifact(key, name, obj)
            val policyId = obj.getString("policyId")
            Policy(
                key,
                name,
                policyId,
                RiftTrainDataV2Format.sha256File(file),
                obj.getBoolean("frozen")
            )
        }
    }

    private fun verifyPolicies(dir: File, expected: List<Policy>) {
        val current = loadPolicies(dir)
        require(current == expected) {
            "V2 policy artifacts changed during build"
        }
    }

    private fun sourceShards(dir: File): List<Shard> {
        require(dir.isDirectory) {
            "V2 source directory is missing: " + dir.name
        }
        val entries = (dir.listFiles() ?: emptyArray())
        require(entries.all { entry ->
            entry.isFile && SHARD_RE.matches(entry.name)
        }) {
            "V2 source directory contains unexpected entries: " + dir.name
        }
        val files = entries.sortedBy { it.name }

        require(
            files.isNotEmpty() &&
                files.size <= MAX_SOURCE_SHARDS
        ) {
            "V2 source shard count is invalid"
        }

        return files.map { entry ->
            val file = requireDirectRegularFile(
                dir,
                entry,
                "V2 source shard " + entry.name
            )
            require(
                SHARD_RE.matches(file.name) &&
                    file.length() in 1..MAX_SOURCE_SHARD_BYTES
            ) {
                "invalid V2 source shard: " + file.name
            }
            Shard(
                file.name,
                file.length(),
                RiftTrainDataV2Format.sha256File(file)
            )
        }
    }

    private fun shardSetSha(shards: List<Shard>): String {
        val material = buildString {
            shards.forEach { shard ->
                append(shard.name)
                    .append('\t')
                    .append(shard.bytes)
                    .append('\t')
                    .append(shard.sha256)
                    .append('\n')
            }
        }
        return RiftTrainDataV2Format.sha256Bytes(
            material.toByteArray(Charsets.UTF_8)
        )
    }

    private fun verifyInputSet(
        dir: File,
        original: List<Shard>,
        expectedSha: String
    ) {
        val current = sourceShards(dir)
        require(
            current == original &&
                shardSetSha(current) == expectedSha
        ) {
            "V2 source shard set changed during build"
        }
    }

    private fun requireStableInputFile(file: File): String {
        require(
            file.isFile &&
                file.length() in 1..MAX_PROVENANCE_BYTES
        ) {
            "V2 provenance input is missing/out of bounds"
        }
        return RiftTrainDataV2Format.sha256File(file)
    }

    private fun streamObjects(
        dir: File,
        shards: List<Shard>,
        cancel: AtomicBoolean,
        block: (JSONObject, String, Long) -> Unit
    ) {
        var records = 0L
        for (shard in shards) {
            val file = File(dir, shard.name)
            require(
                file.length() == shard.bytes &&
                    RiftTrainDataV2Format.sha256File(file) ==
                    shard.sha256
            ) {
                "V2 source shard changed before read: " +
                    shard.name
            }

            bufferedUtf8Reader(file).use { reader ->
                var lineNumber = 0L
                while (true) {
                    val line = reader.readLine() ?: break
                    lineNumber++
                    if (line.isBlank()) continue
                    if ((records and 255L) == 0L) {
                        ensureRunning(cancel)
                    }
                    block(
                        parseObject(
                            line,
                            shard.name,
                            lineNumber
                        ),
                        shard.name,
                        lineNumber
                    )
                    records++
                    require(records <= MAX_RECORDS) {
                        "V2 source record count exceeds limit"
                    }
                }
            }

            require(
                file.length() == shard.bytes &&
                    RiftTrainDataV2Format.sha256File(file) ==
                    shard.sha256
            ) {
                "V2 source shard changed during read: " +
                    shard.name
            }
        }
    }

    private fun bufferedUtf8Reader(file: File): BufferedReader {
        val decoder =
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        return BufferedReader(
            InputStreamReader(
                FileInputStream(file),
                decoder
            ),
            128 * 1024
        )
    }

    private fun parseObject(
        line: String,
        file: String,
        lineNumber: Long
    ): JSONObject =
        try {
            JSONObject(line)
        } catch (error: Throwable) {
            throw IllegalArgumentException(
                file +
                    ":" +
                    lineNumber +
                    " invalid JSON: " +
                    error.message,
                error
            )
        }

    private fun requireExactKeys(
        obj: JSONObject,
        allowed: Set<String>,
        label: String,
        optional: Set<String> = emptySet()
    ) {
        val keys = obj.keys().asSequence().toSet()
        val required = allowed - optional
        require(keys.containsAll(required)) {
            label +
                " missing required fields: " +
                (required - keys).sorted()
        }
        require(keys.all { it in allowed }) {
            label +
                " contains unknown fields: " +
                (keys - allowed).sorted()
        }
    }

    private fun requiredString(
        obj: JSONObject,
        key: String
    ): String {
        require(obj.has(key) && !obj.isNull(key) && obj.get(key) is String) {
            key + " must be a string"
        }
        val value = obj.getString(key)
        require(value.isNotEmpty()) {
            key + " must be a non-empty string"
        }
        require(value == value.trim()) {
            key + " may not contain surrounding whitespace"
        }
        return value
    }

    private fun generationDescriptor(
        inputs: GenerationInputs
    ): JSONObject {
        val policies = JSONObject()
        inputs.policies
            .sortedBy { it.key }
            .forEach { policy ->
                policies.put(
                    policy.key,
                    JSONObject()
                        .put("file", policy.fileName)
                        .put("policyId", policy.policyId)
                        .put("sha256", policy.sha256)
                        .put("frozen", policy.frozen)
                )
            }

        val languages = JSONArray()
        inputs.languages.forEach { languages.put(it) }

        val kinds = JSONObject()
        SOURCE_KINDS.forEach { entry ->
            kinds.put(entry.key, entry.value)
        }

        return JSONObject()
            .put(
                "format",
                "rift-train-data-v2-generation-descriptor"
            )
            .put("contractRevision", GENERATION_CONTRACT_REVISION)
            .put(
                "purpose",
                "software-engineering-pretraining"
            )
            .put(
                "productionPretrainingEligible",
                PRODUCTION_ELIGIBLE
            )
            .put("architectureId", ARCHITECTURE_ID)
            .put(
                "qualifiedContextTokens",
                RiftTrainDataV2Format.CONTEXT_TOKENS
            )
            .put(
                "tokenizerCandidateId",
                RiftFrozenByteBpeV1.CANDIDATE_ID
            )
            .put(
                "tokenizerArtifactSha256",
                RiftFrozenByteBpeV1.ARTIFACT_SHA256
            )
            .put(
                "tokenizerTrainerConfigSha256",
                RiftFrozenByteBpeV1.TRAINER_CONFIG_SHA256
            )
            .put(
                "sourceSetSha256",
                inputs.sourceSetSha256
            )
            .put(
                "challengeSetSha256",
                inputs.challengeSetSha256
            )
            .put(
                "provenanceInputSha256",
                inputs.provenanceInputSha256
            )
            .put(
                "provenanceSha256",
                inputs.provenanceOutputSha256
            )
            .put(
                "provenanceCount",
                inputs.provenanceCount
            )
            .put(
                "sourceKindTableVersion",
                SOURCE_KIND_TABLE_VERSION
            )
            .put("sourceKinds", kinds)
            .put("languageTable", languages)
            .put("splitPolicyId", SPLIT_POLICY_ID)
            .put(
                "validationPermyriad",
                VALIDATION_PERMYRIAD
            )
            .put("dedupPolicyId", DEDUP_POLICY_ID)
            .put(
                "nearDedupImplemented",
                NEAR_DEDUP_IMPLEMENTED
            )
            .put("nearDedupFrozen", false)
            .put(
                "renderingId",
                "raw-content-v2-candidate"
            )
            .put("policyArtifacts", policies)
    }

    private fun datasetManifest(
        descriptorSha: String,
        descriptorFile: File,
        provenanceFile: File,
        inputs: GenerationInputs,
        nearDedupEvidence: NearDedupEvidence,
        nearDedupComparison: RiftB2NearDedupIndexV1.ComparisonEvidence,
        train: RiftTrainDataV2Format.PackInfo,
        validation: RiftTrainDataV2Format.PackInfo,
        challenge: RiftTrainDataV2Format.PackInfo
    ): JSONObject {
        fun pack(
            info: RiftTrainDataV2Format.PackInfo
        ): JSONObject =
            JSONObject()
                .put("file", info.file.name)
                .put("bytes", info.bytes)
                .put("sha256", info.sha256)
                .put(
                    "sampleCount",
                    info.sampleCount
                )
                .put(
                    "tokenCount",
                    info.tokenCount
                )
                .put(
                    "indexSha256",
                    info.indexSha256
                )
                .put(
                    "recordRegionSha256",
                    info.recordRegionSha256
                )

        return JSONObject()
            .put(
                "format",
                "rift-train-data-v2-dataset-manifest"
            )
            .put(
                "purpose",
                "software-engineering-pretraining"
            )
            .put(
                "productionPretrainingEligible",
                false
            )
            .put(
                "generationDescriptorSha256",
                descriptorSha
            )
            .put(
                "generationDescriptorBytes",
                descriptorFile.length()
            )
            .put(
                "provenanceSha256",
                RiftTrainDataV2Format.sha256File(
                    provenanceFile
                )
            )
            .put(
                "provenanceBytes",
                provenanceFile.length()
            )
            .put(
                "sourceSetSha256",
                inputs.sourceSetSha256
            )
            .put(
                "challengeSetSha256",
                inputs.challengeSetSha256
            )
            .put(
                "nearDedupEvidence",
                nearDedupEvidence.toJson(nearDedupComparison)
            )
            .put("train", pack(train))
            .put(
                "validation",
                pack(validation)
            )
            .put("challenge", pack(challenge))
    }

    private fun validateGeneration(
        stage: File,
        descriptorSha: String,
        manifestSha: String
    ) {
        val expectedFiles = setOf(
            GENERATION_DESCRIPTOR,
            PROVENANCE_OUTPUT,
            TRAIN_PACK,
            VALIDATION_PACK,
            CHALLENGE_PACK,
            DATASET_MANIFEST
        )
        val entries = stage.listFiles() ?: emptyArray()
        val canonicalStage = stage.canonicalFile
        require(
            entries.size == expectedFiles.size &&
                entries.all { entry ->
                    entry.isFile &&
                        !Files.isSymbolicLink(entry.toPath()) &&
                        entry.canonicalFile.parentFile == canonicalStage &&
                        entry.canonicalFile.path ==
                        entry.absoluteFile.toPath().normalize().toFile().path
                } &&
                entries.map { it.name }.toSet() == expectedFiles
        ) {
            "V2 immutable generation contains unexpected/missing/symlinked entries"
        }

        val descriptorFile = File(stage, GENERATION_DESCRIPTOR)
        require(
            RiftTrainDataV2Format.sha256File(descriptorFile) == descriptorSha
        ) {
            "V2 generation descriptor SHA mismatch"
        }
        val descriptor = readCanonicalObject(
            descriptorFile,
            "V2 generation descriptor"
        )

        val provenanceFile = File(stage, PROVENANCE_OUTPUT)
        validateDescriptorAndProvenance(
            descriptor,
            provenanceFile
        )

        val packs = validatePacksWithGlobalIdentities(
            stage,
            descriptorSha
        )
        val train = packs.train
        val validation = packs.validation
        val challenge = packs.challenge

        validatePackAgainstDescriptor(
            train,
            descriptor,
            "train"
        )
        validatePackAgainstDescriptor(
            validation,
            descriptor,
            "validation"
        )
        validatePackAgainstDescriptor(
            challenge,
            descriptor,
            "challenge"
        )

        val manifestFile = File(stage, DATASET_MANIFEST)
        require(
            RiftTrainDataV2Format.sha256File(manifestFile) == manifestSha
        ) {
            "V2 dataset manifest SHA mismatch"
        }
        val manifest = readCanonicalObject(
            manifestFile,
            "V2 dataset manifest"
        )
        validateManifest(
            manifest,
            descriptor,
            descriptorSha,
            descriptorFile,
            provenanceFile,
            train,
            validation,
            challenge
        )
    }

    private fun validatePacksWithGlobalIdentities(
        stage: File,
        descriptorSha: String
    ): ValidatedPacks {
        val parent = stage.parentFile?.canonicalFile
            ?: throw IllegalArgumentException("V2 generation has no parent directory")
        val dbFile = File(
            parent,
            ".v2-validation-" +
                descriptorSha.take(16) +
                "-" +
                System.nanoTime() +
                ".sqlite"
        )
        require(
            dbFile.parentFile?.canonicalFile == parent &&
                !dbFile.exists()
        ) {
            "invalid V2 validation-index path"
        }

        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            db.execSQL("PRAGMA journal_mode=DELETE")
            db.execSQL("PRAGMA synchronous=OFF")
            db.execSQL(
                "CREATE TABLE sample_identity(" +
                    "hash TEXT PRIMARY KEY)"
            )
            db.execSQL(
                "CREATE TABLE content_identity(" +
                    "hash TEXT PRIMARY KEY)"
            )
            db.execSQL(
                "CREATE TABLE group_identity(" +
                    "hash TEXT PRIMARY KEY," +
                    "role INTEGER NOT NULL," +
                    "split INTEGER NOT NULL)"
            )

            db.beginTransaction()
            try {
                val visitor: (RiftTrainDataV2Format.RecordIdentity) -> Unit = {
                    identity ->
                    recordValidatedIdentity(db, identity)
                }

                val train = RiftTrainDataV2Format.validate(
                    File(stage, TRAIN_PACK),
                    descriptorSha,
                    visitor
                )
                val validation = RiftTrainDataV2Format.validate(
                    File(stage, VALIDATION_PACK),
                    descriptorSha,
                    visitor
                )
                val challenge = RiftTrainDataV2Format.validate(
                    File(stage, CHALLENGE_PACK),
                    descriptorSha,
                    visitor
                )

                db.setTransactionSuccessful()
                return ValidatedPacks(
                    train,
                    validation,
                    challenge
                )
            } finally {
                db.endTransaction()
            }
        } finally {
            if (db.isOpen) db.close()
            runCatching { dbFile.delete() }
            runCatching { File(dbFile.path + "-journal").delete() }
            runCatching { File(dbFile.path + "-wal").delete() }
            runCatching { File(dbFile.path + "-shm").delete() }
        }
    }

    private fun recordValidatedIdentity(
        db: SQLiteDatabase,
        identity: RiftTrainDataV2Format.RecordIdentity
    ) {
        fun insertUnique(
            table: String,
            hash: String,
            message: String
        ) {
            require(
                table == "sample_identity" ||
                    table == "content_identity"
            )
            try {
                db.execSQL(
                    "INSERT INTO " + table + "(hash) VALUES(?)",
                    arrayOf(hash)
                )
            } catch (error: SQLiteConstraintException) {
                throw IllegalArgumentException(message, error)
            }
        }

        insertUnique(
            "sample_identity",
            identity.sampleSha256,
            "V2 duplicate sample identity across packs"
        )
        insertUnique(
            "content_identity",
            identity.contentSha256,
            "V2 duplicate content identity across packs"
        )

        val role =
            if (identity.splitId == RiftTrainDataV2Format.SPLIT_CHALLENGE) {
                1
            } else {
                0
            }

        db.rawQuery(
            "SELECT role,split FROM group_identity WHERE hash=?",
            arrayOf(identity.sourceGroupSha256)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                require(
                    cursor.getInt(0) == role &&
                        cursor.getInt(1) == identity.splitId
                ) {
                    "V2 source-group leakage across dataset splits"
                }
                return
            }
        }

        db.execSQL(
            "INSERT INTO group_identity(hash,role,split) VALUES(?,?,?)",
            arrayOf(
                identity.sourceGroupSha256,
                role,
                identity.splitId
            )
        )
    }

    private fun readCanonicalObject(
        file: File,
        label: String
    ): JSONObject {
        require(file.isFile && file.length() in 2..(4L * 1024L * 1024L)) {
            label + " is missing/out of bounds"
        }
        val bytes = file.readBytes()
        val decoder =
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
        val value = JSONObject(text)
        require(RiftTrainDataV2Format.canonicalJson(value) == text) {
            label + " is not canonical JSON"
        }
        return value
    }

    private fun validateDescriptorAndProvenance(
        descriptor: JSONObject,
        provenanceFile: File
    ) {
        val expectedKeys = setOf(
            "format",
            "contractRevision",
            "purpose",
            "productionPretrainingEligible",
            "architectureId",
            "qualifiedContextTokens",
            "tokenizerCandidateId",
            "tokenizerArtifactSha256",
            "tokenizerTrainerConfigSha256",
            "sourceSetSha256",
            "challengeSetSha256",
            "provenanceInputSha256",
            "provenanceSha256",
            "provenanceCount",
            "sourceKindTableVersion",
            "sourceKinds",
            "languageTable",
            "splitPolicyId",
            "validationPermyriad",
            "dedupPolicyId",
            "nearDedupImplemented",
            "nearDedupFrozen",
            "renderingId",
            "policyArtifacts"
        )
        require(
            descriptor.keys().asSequence().toSet() == expectedKeys
        ) {
            "V2 generation descriptor fields are non-canonical"
        }
        require(
            descriptor.getString("format") ==
                "rift-train-data-v2-generation-descriptor"
        ) {
            "V2 generation descriptor format mismatch"
        }
        require(
            descriptor.getInt("contractRevision") ==
                GENERATION_CONTRACT_REVISION
        ) {
            "V2 generation descriptor contract revision mismatch"
        }
        require(
            descriptor.getString("purpose") ==
                "software-engineering-pretraining"
        ) {
            "V2 generation descriptor purpose mismatch"
        }
        require(!descriptor.getBoolean("productionPretrainingEligible")) {
            "candidate V2 generation may not be production-eligible"
        }
        require(descriptor.getString("architectureId") == ARCHITECTURE_ID) {
            "V2 descriptor architecture mismatch"
        }
        require(
            descriptor.getInt("qualifiedContextTokens") ==
                RiftTrainDataV2Format.CONTEXT_TOKENS
        ) {
            "V2 descriptor context mismatch"
        }
        require(
            descriptor.getString("tokenizerCandidateId") ==
                RiftFrozenByteBpeV1.CANDIDATE_ID &&
                descriptor.getString("tokenizerArtifactSha256") ==
                RiftFrozenByteBpeV1.ARTIFACT_SHA256 &&
                descriptor.getString("tokenizerTrainerConfigSha256") ==
                RiftFrozenByteBpeV1.TRAINER_CONFIG_SHA256
        ) {
            "V2 descriptor tokenizer identity mismatch"
        }

        listOf(
            "sourceSetSha256",
            "challengeSetSha256",
            "provenanceInputSha256",
            "provenanceSha256"
        ).forEach { key ->
            require(SHA_RE.matches(descriptor.getString(key))) {
                "V2 descriptor SHA-256 field is invalid: " + key
            }
        }

        require(
            descriptor.getString("sourceKindTableVersion") ==
                RiftTrainDataV2Format.SOURCE_KIND_TABLE_VERSION
        ) {
            "V2 descriptor source-kind table mismatch"
        }
        require(
            descriptor.getString("splitPolicyId") ==
                RiftTrainDataV2Format.SPLIT_POLICY_ID &&
                descriptor.getInt("validationPermyriad") ==
                RiftTrainDataV2Format.VALIDATION_PERMYRIAD
        ) {
            "V2 descriptor split policy mismatch"
        }
        require(
            descriptor.getString("dedupPolicyId") ==
                RiftTrainDataV2Format.DEDUP_POLICY_ID
        ) {
            "V2 descriptor dedup policy mismatch"
        }
        require(
            !descriptor.getBoolean("nearDedupImplemented") &&
                !descriptor.getBoolean("nearDedupFrozen")
        ) {
            "candidate V2 near-dedup state mismatch"
        }
        require(
            descriptor.getString("renderingId") ==
                "raw-content-v2-candidate"
        ) {
            "V2 descriptor rendering identity mismatch"
        }

        val sourceKinds = descriptor.getJSONObject("sourceKinds")
        require(
            sourceKinds.keys().asSequence().toSet() == SOURCE_KINDS.keys
        ) {
            "V2 descriptor source-kind table fields mismatch"
        }
        SOURCE_KINDS.forEach { (name, id) ->
            require(sourceKinds.getInt(name) == id) {
                "V2 descriptor source-kind mapping mismatch"
            }
        }

        val languages = descriptor.getJSONArray("languageTable")
        validateLanguageTable(languages)

        val policyArtifacts = descriptor.getJSONObject("policyArtifacts")
        require(
            policyArtifacts.keys().asSequence().toSet() ==
                POLICY_FILES.keys
        ) {
            "V2 descriptor policy set mismatch"
        }
        POLICY_FILES.forEach { (key, fileName) ->
            val policy = policyArtifacts.getJSONObject(key)
            require(
                policy.keys().asSequence().toSet() ==
                    setOf("file", "policyId", "sha256", "frozen")
            ) {
                "V2 descriptor policy fields mismatch: " + key
            }
            require(policy.getString("file") == fileName) {
                "V2 descriptor policy filename mismatch: " + key
            }
            require(
                policy.getString("policyId").isNotBlank() &&
                    policy.getString("policyId").length <= 160
            ) {
                "V2 descriptor policy ID is invalid: " + key
            }
            require(SHA_RE.matches(policy.getString("sha256"))) {
                "V2 descriptor policy SHA-256 is invalid: " + key
            }
            policy.getBoolean("frozen")
        }

        val provenanceSha = descriptor.getString("provenanceSha256")
        require(
            RiftTrainDataV2Format.sha256File(provenanceFile) ==
                provenanceSha
        ) {
            "V2 canonical provenance SHA mismatch"
        }

        val expectedRows = descriptor.getLong("provenanceCount")
        require(expectedRows in 1..MAX_RECORDS) {
            "V2 descriptor provenance count is invalid"
        }
        var observedRows = 0L
        bufferedUtf8Reader(provenanceFile).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                require(line.isNotBlank()) {
                    "V2 canonical provenance contains a blank row"
                }
                val row = JSONObject(line)
                require(
                    RiftTrainDataV2Format.canonicalJson(row) == line
                ) {
                    "V2 canonical provenance row is not canonical JSON"
                }
                val parsed = parseProvenance(row)
                require(parsed.canonical == line) {
                    "V2 canonical provenance row failed schema round-trip"
                }
                observedRows++
                require(observedRows <= expectedRows) {
                    "V2 canonical provenance has extra rows"
                }
            }
        }
        require(observedRows == expectedRows) {
            "V2 canonical provenance row count mismatch"
        }
    }

    private fun validateLanguageTable(
        languages: JSONArray
    ) {
        require(
            languages.length() in 1..RiftTrainDataV2Format.MAX_LANGUAGE_COUNT &&
                languages.getString(0) == "mixed"
        ) {
            "V2 language table is invalid"
        }
        val seen = HashSet<String>()
        var previous: String? = null
        for (index in 0 until languages.length()) {
            val language = languages.getString(index)
            require(
                LANGUAGE_RE.matches(language) &&
                    language == language.lowercase()
            ) {
                "V2 language identifier is invalid"
            }
            require(seen.add(language)) {
                "V2 language table contains duplicates"
            }
            if (index > 0 && previous != null) {
                require(language > previous!!) {
                    "V2 language table is not sorted"
                }
            }
            if (index > 0) previous = language
        }
    }

    private fun validatePackAgainstDescriptor(
        info: RiftTrainDataV2Format.PackInfo,
        descriptor: JSONObject,
        expectedRole: String
    ) {
        val header = info.header
        require(header.getString("splitRole") == expectedRole) {
            "V2 pack role mismatch: " + expectedRole
        }
        require(
            header.getBoolean("productionPretrainingEligible") ==
                descriptor.getBoolean("productionPretrainingEligible")
        ) {
            "V2 pack/descriptor eligibility mismatch"
        }
        require(
            header.getString("provenanceSha256") ==
                descriptor.getString("provenanceSha256") &&
                header.getLong("provenanceCount") ==
                descriptor.getLong("provenanceCount")
        ) {
            "V2 pack/descriptor provenance mismatch"
        }
        require(
            header.getString("sourceKindTableVersion") ==
                descriptor.getString("sourceKindTableVersion") &&
                header.getString("splitPolicyId") ==
                descriptor.getString("splitPolicyId") &&
                header.getInt("validationPermyriad") ==
                descriptor.getInt("validationPermyriad") &&
                header.getString("dedupPolicyId") ==
                descriptor.getString("dedupPolicyId") &&
                header.getBoolean("nearDedupImplemented") ==
                descriptor.getBoolean("nearDedupImplemented")
        ) {
            "V2 pack/descriptor policy identity mismatch"
        }
        require(
            RiftTrainDataV2Format.canonicalJson(
                header.getJSONArray("languageTable")
            ) ==
                RiftTrainDataV2Format.canonicalJson(
                    descriptor.getJSONArray("languageTable")
                )
        ) {
            "V2 pack/descriptor language table mismatch"
        }

        val expectedPolicyHashes = JSONObject()
        val descriptorPolicies =
            descriptor.getJSONObject("policyArtifacts")
        POLICY_FILES.keys.sorted().forEach { key ->
            expectedPolicyHashes.put(
                key,
                descriptorPolicies
                    .getJSONObject(key)
                    .getString("sha256")
            )
        }
        require(
            RiftTrainDataV2Format.canonicalJson(
                header.getJSONObject("policySha256")
            ) ==
                RiftTrainDataV2Format.canonicalJson(
                    expectedPolicyHashes
                )
        ) {
            "V2 pack/descriptor policy hash mismatch"
        }
    }

    private fun validateManifest(
        manifest: JSONObject,
        descriptor: JSONObject,
        descriptorSha: String,
        descriptorFile: File,
        provenanceFile: File,
        train: RiftTrainDataV2Format.PackInfo,
        validation: RiftTrainDataV2Format.PackInfo,
        challenge: RiftTrainDataV2Format.PackInfo
    ) {
        val expectedKeys = setOf(
            "format",
            "purpose",
            "productionPretrainingEligible",
            "generationDescriptorSha256",
            "generationDescriptorBytes",
            "provenanceSha256",
            "provenanceBytes",
            "sourceSetSha256",
            "challengeSetSha256",
            "nearDedupEvidence",
            "train",
            "validation",
            "challenge"
        )
        require(
            manifest.keys().asSequence().toSet() == expectedKeys
        ) {
            "V2 dataset manifest fields are non-canonical"
        }
        require(
            manifest.getString("format") ==
                "rift-train-data-v2-dataset-manifest" &&
                manifest.getString("purpose") ==
                "software-engineering-pretraining"
        ) {
            "V2 dataset manifest identity mismatch"
        }
        require(
            manifest.getBoolean("productionPretrainingEligible") ==
                descriptor.getBoolean("productionPretrainingEligible")
        ) {
            "V2 manifest/descriptor eligibility mismatch"
        }
        require(
            manifest.getString("generationDescriptorSha256") ==
                descriptorSha &&
                manifest.getLong("generationDescriptorBytes") ==
                descriptorFile.length()
        ) {
            "V2 manifest descriptor identity mismatch"
        }
        require(
            manifest.getString("provenanceSha256") ==
                descriptor.getString("provenanceSha256") &&
                manifest.getLong("provenanceBytes") ==
                provenanceFile.length()
        ) {
            "V2 manifest provenance identity mismatch"
        }
        require(
            manifest.getString("sourceSetSha256") ==
                descriptor.getString("sourceSetSha256") &&
                manifest.getString("challengeSetSha256") ==
                descriptor.getString("challengeSetSha256")
        ) {
            "V2 manifest source-set identity mismatch"
        }

        validateNearDedupEvidence(
            manifest.getJSONObject("nearDedupEvidence"),
            train.sampleCount + validation.sampleCount + challenge.sampleCount
        )

        validateManifestPack(
            manifest.getJSONObject("train"),
            train,
            TRAIN_PACK
        )
        validateManifestPack(
            manifest.getJSONObject("validation"),
            validation,
            VALIDATION_PACK
        )
        validateManifestPack(
            manifest.getJSONObject("challenge"),
            challenge,
            CHALLENGE_PACK
        )
    }

    private fun validateNearDedupEvidence(
        evidence: JSONObject,
        expectedSamples: Long
    ) {
        val expectedKeys = setOf(
            "algorithmId",
            "similarityId",
            "shingleTokens",
            "bottomK",
            "scoreScale",
            "thresholdFrozen",
            "globalComparisonImplemented",
            "rejectionApplied",
            "samplesScanned",
            "sketchSamples",
            "exactFallbackSamples",
            "totalFingerprints",
            "canonicalSketchStreamSha256",
            "comparison"
        )
        require(evidence.keys().asSequence().toSet() == expectedKeys) {
            "V2 near-dedup evidence fields are non-canonical"
        }
        require(
            evidence.getString("algorithmId") ==
                RiftB2BottomKDedupV1.ALGORITHM_ID &&
                evidence.getString("similarityId") ==
                RiftB2BottomKDedupV1.SIMILARITY_ID &&
                evidence.getInt("shingleTokens") ==
                RiftB2BottomKDedupV1.SHINGLE_TOKENS &&
                evidence.getInt("bottomK") ==
                RiftB2BottomKDedupV1.BOTTOM_K &&
                evidence.getInt("scoreScale") ==
                RiftB2BottomKDedupV1.SCORE_SCALE
        ) {
            "V2 near-dedup evidence algorithm contract mismatch"
        }
        require(
            !evidence.getBoolean("thresholdFrozen") &&
                evidence.getBoolean("globalComparisonImplemented") &&
                !evidence.getBoolean("rejectionApplied")
        ) {
            "candidate V2 near-dedup evidence comparison/freeze state mismatch"
        }

        val samples = evidence.getLong("samplesScanned")
        val sketches = evidence.getLong("sketchSamples")
        val fallback = evidence.getLong("exactFallbackSamples")
        val fingerprints = evidence.getLong("totalFingerprints")
        require(
            samples == expectedSamples &&
                samples > 0L &&
                sketches >= 0L &&
                fallback >= 0L &&
                sketches + fallback == samples
        ) {
            "V2 near-dedup evidence sample counters mismatch"
        }
        require(
            fingerprints >= sketches &&
                fingerprints <=
                sketches * RiftB2BottomKDedupV1.BOTTOM_K.toLong()
        ) {
            "V2 near-dedup evidence fingerprint count is invalid"
        }
        require(
            SHA_RE.matches(
                evidence.getString("canonicalSketchStreamSha256")
            )
        ) {
            "V2 near-dedup evidence stream SHA-256 is invalid"
        }
        validateNearDedupComparisonEvidence(
            evidence.getJSONObject("comparison"),
            sketches
        )
    }

    private fun validateNearDedupComparisonEvidence(
        comparison: JSONObject,
        expectedIndexedSketches: Long
    ) {
        val expectedKeys = setOf(
            "indexId",
            "maxIndexedFingerprints",
            "maxCandidatePairs",
            "indexedSketchSamples",
            "indexedFingerprints",
            "candidatePairsCompared",
            "positivePairs",
            "maxSimilarityPpm",
            "maxPair",
            "histogramBinPpm",
            "histogramCounts"
        )
        require(comparison.keys().asSequence().toSet() == expectedKeys) {
            "V2 near-dedup comparison fields are non-canonical"
        }
        require(
            comparison.getString("indexId") ==
                RiftB2NearDedupIndexV1.INDEX_ID &&
                comparison.getLong("maxIndexedFingerprints") ==
                RiftB2NearDedupIndexV1.MAX_INDEXED_FINGERPRINTS &&
                comparison.getLong("maxCandidatePairs") ==
                RiftB2NearDedupIndexV1.MAX_CANDIDATE_PAIRS &&
                comparison.getLong("indexedSketchSamples") ==
                expectedIndexedSketches &&
                comparison.getInt("histogramBinPpm") ==
                RiftB2NearDedupIndexV1.HISTOGRAM_BIN_PPM
        ) {
            "V2 near-dedup comparison index contract mismatch"
        }

        val indexedFingerprints = comparison.getLong("indexedFingerprints")
        val candidates = comparison.getLong("candidatePairsCompared")
        val positive = comparison.getLong("positivePairs")
        val maxScore = comparison.getInt("maxSimilarityPpm")
        require(
            indexedFingerprints >= expectedIndexedSketches &&
                indexedFingerprints <=
                expectedIndexedSketches * RiftB2BottomKDedupV1.BOTTOM_K.toLong() &&
                indexedFingerprints <=
                RiftB2NearDedupIndexV1.MAX_INDEXED_FINGERPRINTS &&
                candidates in 0L..RiftB2NearDedupIndexV1.MAX_CANDIDATE_PAIRS &&
                positive in 0L..candidates &&
                maxScore in 0..RiftB2BottomKDedupV1.SCORE_SCALE
        ) {
            "V2 near-dedup comparison counters are invalid"
        }

        val histogram = comparison.getJSONArray("histogramCounts")
        require(histogram.length() == RiftB2NearDedupIndexV1.HISTOGRAM_BINS) {
            "V2 near-dedup comparison histogram size mismatch"
        }
        var histogramTotal = 0L
        for (index in 0 until histogram.length()) {
            val count = histogram.getLong(index)
            require(count >= 0L) {
                "V2 near-dedup comparison histogram contains negative count"
            }
            histogramTotal += count
        }
        require(histogramTotal == candidates) {
            "V2 near-dedup comparison histogram total mismatch"
        }

        if (candidates == 0L) {
            require(comparison.isNull("maxPair") && maxScore == 0) {
                "V2 near-dedup comparison empty max-pair state mismatch"
            }
        } else {
            val maxPair = comparison.getJSONObject("maxPair")
            require(
                maxPair.keys().asSequence().toSet() ==
                    setOf(
                        "leftSampleSha256",
                        "rightSampleSha256",
                        "leftSplitId",
                        "rightSplitId"
                    )
            ) {
                "V2 near-dedup comparison max-pair fields mismatch"
            }
            require(
                SHA_RE.matches(maxPair.getString("leftSampleSha256")) &&
                    SHA_RE.matches(maxPair.getString("rightSampleSha256"))
            ) {
                "V2 near-dedup comparison max-pair SHA-256 is invalid"
            }
            val leftSplit = maxPair.getInt("leftSplitId")
            val rightSplit = maxPair.getInt("rightSplitId")
            require(
                leftSplit in RiftTrainDataV2Format.SPLIT_TRAIN..
                    RiftTrainDataV2Format.SPLIT_CHALLENGE &&
                    rightSplit in RiftTrainDataV2Format.SPLIT_TRAIN..
                    RiftTrainDataV2Format.SPLIT_CHALLENGE &&
                    leftSplit != rightSplit
            ) {
                "V2 near-dedup comparison max-pair split identity is invalid"
            }
        }
    }

    private fun validateManifestPack(
        entry: JSONObject,
        info: RiftTrainDataV2Format.PackInfo,
        expectedFileName: String
    ) {
        require(
            entry.keys().asSequence().toSet() ==
                setOf(
                    "file",
                    "bytes",
                    "sha256",
                    "sampleCount",
                    "tokenCount",
                    "indexSha256",
                    "recordRegionSha256"
                )
        ) {
            "V2 manifest pack fields are non-canonical"
        }
        require(
            entry.getString("file") == expectedFileName &&
                entry.getLong("bytes") == info.bytes &&
                entry.getString("sha256") == info.sha256 &&
                entry.getLong("sampleCount") == info.sampleCount &&
                entry.getLong("tokenCount") == info.tokenCount &&
                entry.getString("indexSha256") == info.indexSha256 &&
                entry.getString("recordRegionSha256") ==
                info.recordRegionSha256
        ) {
            "V2 manifest pack identity mismatch: " +
                expectedFileName
        }
    }

    private fun publishCurrent(
        root: File,
        descriptorSha: String,
        manifestSha: String
    ) {
        val target = exactPath(root, CURRENT_RELATIVE)
        target.parentFile?.let {
            require(it.isDirectory || it.mkdirs())
        }
        val stage = File(
            target.parentFile,
            ".CURRENT." +
                System.currentTimeMillis() +
                ".stage"
        )
        val current =
            JSONObject()
                .put(
                    "format",
                    "rift-train-data-v2-current"
                )
                .put(
                    "generationDescriptorSha256",
                    descriptorSha
                )
                .put(
                    "datasetManifestSha256",
                    manifestSha
                )
                .put("deepValidated", true)
                .put(
                    "deepValidationContract",
                    DEEP_VALIDATION_CONTRACT
                )
                .put(
                    "productionPretrainingEligible",
                    false
                )
        writeSynced(
            stage,
            RiftTrainDataV2Format
                .canonicalJson(current)
                .toByteArray(Charsets.UTF_8)
        )
        Files.move(
            stage.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
        fsyncDirectory(target.parentFile)
    }

    private fun fsyncDirectory(directory: File?) {
        require(directory != null && directory.isDirectory) {
            "V2 directory fsync target is invalid"
        }
        val fd = Os.open(
            directory.absolutePath,
            OsConstants.O_RDONLY or OsConstants.O_DIRECTORY,
            0
        )
        try {
            Os.fsync(fd)
        } finally {
            Os.close(fd)
        }
    }

    private fun writeSynced(
        file: File,
        bytes: ByteArray
    ) {
        file.parentFile?.let {
            require(it.isDirectory || it.mkdirs())
        }
        FileOutputStream(file, false).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") {
            (it.toInt() and 0xff)
                .toString(16)
                .padStart(2, '0')
        }
}
