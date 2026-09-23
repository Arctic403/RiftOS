package com.riftos.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.TreeMap

/** Binary writer/validator for the RiftTrainData V2 pack ABI. */
object RiftTrainDataV2Format {
    const val MAGIC = "RIFT_TRAIN_DATA_V2\n"
    const val FORMAT = "rift-train-data-v2"
    const val ARCHITECTURE_ID = "rift-micro-v1-hardware-a"
    const val INDEX_ABI = "rift-train-index-v2-24"
    const val RECORD_ABI = "rift-train-record-v2-112-u16"
    const val INDEX_ENTRY_BYTES = 24
    const val RECORD_HEADER_BYTES = 112
    const val CONTEXT_TOKENS = 2048
    const val MAX_CONTENT_TOKENS = CONTEXT_TOKENS - 2
    const val VOCAB_SIZE = RiftFrozenByteBpeV1.VOCAB_SIZE
    const val BOS_ID = 32760
    const val EOS_ID = 32761
    const val MAX_HEADER_BYTES = 1024 * 1024
    const val MAX_LANGUAGE_COUNT = 65535
    const val MAX_SOURCE_KIND_ID = 12
    const val MAX_ORIGINAL_UTF8_BYTES = 16 * 1024
    const val SOURCE_KIND_TABLE_VERSION = "rift-engineering-source-kinds-v2"
    const val SPLIT_POLICY_ID = "rift-prod-group-split-v1"
    const val VALIDATION_PERMYRIAD = 500
    const val DEDUP_POLICY_ID = "rift-b2-bottomk-v1"
    const val BOUNDARY_POLICY = "bos-content-eos-context-2048-v2"
    const val KNOWN_VERIFICATION_FLAGS = 0x3ffL

    private val REQUIRED_POLICY_KEYS = setOf(
        "source-provenance",
        "licensing-usage",
        "secret-privacy",
        "dedup",
        "split-leakage",
        "rendering",
        "quality-verification"
    )

    private val HEADER_KEYS = setOf(
        "format", "purpose", "splitRole", "splitId",
        "productionPretrainingEligible", "architectureId", "qualifiedContextTokens",
        "tokenizerCandidateId", "tokenizerArtifactSha256", "tokenizerTrainerConfigSha256",
        "vocabularySize", "normalization", "byteFallback", "maxTokenBytes",
        "specialTokenIds", "boundaryPolicy", "payloadEncoding", "indexEncoding",
        "indexEntryBytes", "recordHeaderBytes", "sampleCount", "tokenCount",
        "indexBytes", "indexSha256", "recordRegionBytes", "recordRegionSha256",
        "generationDescriptorSha256", "provenanceSha256", "provenanceCount",
        "policySha256", "languageTable", "sourceKindTableVersion", "splitPolicyId",
        "validationPermyriad", "dedupPolicyId", "nearDedupImplemented"
    )

    const val SPLIT_TRAIN = 1
    const val SPLIT_VALIDATION = 2
    const val SPLIT_CHALLENGE = 3

    data class Metadata(
        val splitRole: String,
        val splitId: Int,
        val architectureId: String,
        val generationDescriptorSha256: String,
        val provenanceSha256: String,
        val provenanceCount: Long,
        val policyHashes: Map<String, String>,
        val languageTable: List<String>,
        val sourceKindTableVersion: String,
        val splitPolicyId: String,
        val validationPermyriad: Int,
        val dedupPolicyId: String,
        val nearDedupImplemented: Boolean,
        val productionPretrainingEligible: Boolean
    )

    data class Record(
        val sampleSha256: ByteArray,
        val contentSha256: ByteArray,
        val sourceGroupSha256: ByteArray,
        val provenanceIndex: Long,
        val originalUtf8Bytes: Long,
        val tokenIds: IntArray,
        val languageId: Int,
        val sourceKindId: Int,
        val verificationFlags: Long
    )

    data class RecordIdentity(
        val sampleSha256: String,
        val contentSha256: String,
        val sourceGroupSha256: String,
        val splitId: Int
    )

    data class PackInfo(
        val file: File,
        val sha256: String,
        val bytes: Long,
        val sampleCount: Long,
        val tokenCount: Long,
        val indexSha256: String,
        val recordRegionSha256: String,
        val header: JSONObject
    )

    class Writer(
        private val directory: File,
        private val fileName: String,
        private val metadata: Metadata
    ) : AutoCloseable {
        private val indexFile = File(directory, ".$fileName.index.tmp")
        private val recordFile = File(directory, ".$fileName.records.tmp")
        private val indexFos: FileOutputStream
        private val recordFos: FileOutputStream
        private val indexOut: BufferedOutputStream
        private val recordOut: BufferedOutputStream
        private var recordBytes = 0L
        private var sampleCount = 0L
        private var tokenCount = 0L
        private var finished = false

        init {
            require(directory.isDirectory || directory.mkdirs()) { "could not create V2 pack stage directory" }
            require(fileName.matches(Regex("[a-z0-9._-]{1,96}\\.rifttok"))) { "invalid V2 pack file name" }
            require(!File(directory, fileName).exists()) { "V2 pack destination already exists" }
            require(metadata.languageTable.isNotEmpty() && metadata.languageTable.first() == "mixed") {
                "V2 language table must start with mixed"
            }
            require(metadata.languageTable.size <= MAX_LANGUAGE_COUNT) { "V2 language table is too large" }
            validateSha(metadata.generationDescriptorSha256, "generation descriptor")
            validateSha(metadata.provenanceSha256, "provenance")
            require(metadata.provenanceCount in 1..0x1_0000_0000L) {
                "V2 provenance row count is invalid"
            }
            require(metadata.policyHashes.keys == REQUIRED_POLICY_KEYS) {
                "V2 policy hash set mismatch"
            }
            metadata.policyHashes.forEach { (name, sha) ->
                require(name.matches(Regex("[a-z0-9._-]{1,96}"))) { "invalid policy id" }
                validateSha(sha, "policy $name")
            }
            require(metadata.architectureId == ARCHITECTURE_ID) {
                "V2 architecture mismatch"
            }
            require(metadata.sourceKindTableVersion == SOURCE_KIND_TABLE_VERSION) {
                "V2 source-kind table version mismatch"
            }
            require(metadata.splitPolicyId == SPLIT_POLICY_ID) { "V2 split policy mismatch" }
            require(metadata.validationPermyriad == VALIDATION_PERMYRIAD) {
                "V2 validation split ratio mismatch"
            }
            require(metadata.dedupPolicyId == DEDUP_POLICY_ID) { "V2 dedup policy mismatch" }
            require(!metadata.productionPretrainingEligible || metadata.nearDedupImplemented) {
                "V2 production eligibility requires implemented near-dedup"
            }
            require(metadata.splitId in SPLIT_TRAIN..SPLIT_CHALLENGE) { "invalid V2 split id" }
            require(roleForSplit(metadata.splitId) == metadata.splitRole) { "V2 split role/id mismatch" }
            listOf(indexFile, recordFile).forEach { runCatching { it.delete() } }
            indexFos = FileOutputStream(indexFile, false)
            recordFos = FileOutputStream(recordFile, false)
            indexOut = BufferedOutputStream(indexFos, 256 * 1024)
            recordOut = BufferedOutputStream(recordFos, 256 * 1024)
        }

        fun append(record: Record) {
            check(!finished) { "V2 pack writer already finished" }
            validateRecord(record, metadata)

            writeU64Le(indexOut, recordBytes)
            writeU32Le(indexOut, record.tokenIds.size.toLong())
            writeU16Le(indexOut, record.languageId)
            indexOut.write(metadata.splitId)
            indexOut.write(record.sourceKindId)
            writeU32Le(indexOut, record.verificationFlags)
            writeU32Le(indexOut, 0L)

            recordOut.write(record.sampleSha256)
            recordOut.write(record.contentSha256)
            recordOut.write(record.sourceGroupSha256)
            writeU32Le(recordOut, record.provenanceIndex)
            writeU32Le(recordOut, record.originalUtf8Bytes)
            writeU32Le(recordOut, record.tokenIds.size.toLong())
            writeU32Le(recordOut, record.verificationFlags)
            record.tokenIds.forEach { writeU16Le(recordOut, it) }

            recordBytes = checkedAdd(
                recordBytes,
                checkedAdd(
                    RECORD_HEADER_BYTES.toLong(),
                    checkedMultiply(record.tokenIds.size.toLong(), 2L)
                )
            )
            sampleCount++
            tokenCount = checkedAdd(tokenCount, record.tokenIds.size.toLong())
        }

        fun finish(): PackInfo {
            check(!finished) { "V2 pack writer already finished" }
            val pack = File(directory, fileName)
            val stage = File(directory, ".$fileName.pack.tmp")
            require(!pack.exists()) { "V2 pack destination already exists" }
            runCatching { stage.delete() }

            try {
                indexOut.flush()
                recordOut.flush()
                indexFos.fd.sync()
                recordFos.fd.sync()
                indexOut.close()
                recordOut.close()

                require(sampleCount > 0L) { "V2 pack may not be empty" }
                require(indexFile.length() == checkedMultiply(sampleCount, INDEX_ENTRY_BYTES.toLong())) {
                    "V2 index byte count drifted"
                }
                require(recordFile.length() == recordBytes) { "V2 record byte count drifted" }

                val indexSha = sha256File(indexFile)
                val recordSha = sha256File(recordFile)
                val header = headerObject(indexSha, recordSha)
                val headerText = canonicalJson(header)
                val headerBytes = headerText.toByteArray(Charsets.UTF_8)
                require(headerBytes.size in 1..MAX_HEADER_BYTES) { "V2 header is out of bounds" }

                FileOutputStream(stage, false).use { fos ->
                    BufferedOutputStream(fos, 256 * 1024).use { out ->
                        out.write(MAGIC.toByteArray(Charsets.US_ASCII))
                        writeU32Le(out, headerBytes.size.toLong())
                        out.write(headerBytes)
                        copyExact(indexFile, out)
                        copyExact(recordFile, out)
                        out.flush()
                        fos.fd.sync()
                    }
                }
                java.nio.file.Files.move(
                    stage.toPath(),
                    pack.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
                )
                val info = validate(pack, metadata.generationDescriptorSha256)
                finished = true
                require(indexFile.delete() || !indexFile.exists()) {
                    "V2 temporary index cleanup failed"
                }
                require(recordFile.delete() || !recordFile.exists()) {
                    "V2 temporary record cleanup failed"
                }
                return info
            } catch (error: Throwable) {
                runCatching { indexOut.close() }
                runCatching { recordOut.close() }
                runCatching { indexFos.close() }
                runCatching { recordFos.close() }
                runCatching { stage.delete() }
                runCatching { pack.delete() }
                throw error
            }
        }

        override fun close() {
            if (!finished) {
                runCatching { indexOut.close() }
                .onFailure { runCatching { indexFos.close() } }
                .getOrNull()
                runCatching { recordOut.close() }
                .onFailure { runCatching { recordFos.close() } }
                .getOrNull()
            }
            runCatching { indexFile.delete() }
            runCatching { recordFile.delete() }
        }

        private fun headerObject(indexSha: String, recordSha: String): JSONObject {
            val policies = JSONObject()
            metadata.policyHashes.toSortedMap().forEach { (name, sha) -> policies.put(name, sha) }
            val languages = JSONArray()
            metadata.languageTable.forEach { languages.put(it) }
            val specials = JSONObject()
            RiftFrozenByteBpeV1.SPECIALS.forEachIndexed { index, literal ->
                specials.put(literal, RiftFrozenByteBpeV1.BYTE_TOKENS + RiftFrozenByteBpeV1.MERGE_COUNT + index)
            }
            return JSONObject()
                .put("format", FORMAT)
                .put("purpose", "software-engineering-pretraining")
                .put("splitRole", metadata.splitRole)
                .put("splitId", metadata.splitId)
                .put("productionPretrainingEligible", metadata.productionPretrainingEligible)
                .put("architectureId", metadata.architectureId)
                .put("qualifiedContextTokens", CONTEXT_TOKENS)
                .put("tokenizerCandidateId", RiftFrozenByteBpeV1.CANDIDATE_ID)
                .put("tokenizerArtifactSha256", RiftFrozenByteBpeV1.ARTIFACT_SHA256)
                .put("tokenizerTrainerConfigSha256", RiftFrozenByteBpeV1.TRAINER_CONFIG_SHA256)
                .put("vocabularySize", VOCAB_SIZE)
                .put("normalization", "identity-utf8")
                .put("byteFallback", true)
                .put("maxTokenBytes", RiftFrozenByteBpeV1.MAX_TOKEN_BYTES)
                .put("specialTokenIds", specials)
                .put("boundaryPolicy", BOUNDARY_POLICY)
                .put("payloadEncoding", RECORD_ABI)
                .put("indexEncoding", INDEX_ABI)
                .put("indexEntryBytes", INDEX_ENTRY_BYTES)
                .put("recordHeaderBytes", RECORD_HEADER_BYTES)
                .put("sampleCount", sampleCount)
                .put("tokenCount", tokenCount)
                .put("indexBytes", indexFile.length())
                .put("indexSha256", indexSha)
                .put("recordRegionBytes", recordBytes)
                .put("recordRegionSha256", recordSha)
                .put("generationDescriptorSha256", metadata.generationDescriptorSha256)
                .put("provenanceSha256", metadata.provenanceSha256)
                .put("provenanceCount", metadata.provenanceCount)
                .put("policySha256", policies)
                .put("languageTable", languages)
                .put("sourceKindTableVersion", metadata.sourceKindTableVersion)
                .put("splitPolicyId", metadata.splitPolicyId)
                .put("validationPermyriad", metadata.validationPermyriad)
                .put("dedupPolicyId", metadata.dedupPolicyId)
                .put("nearDedupImplemented", metadata.nearDedupImplemented)
        }
    }

    fun validate(
        file: File,
        expectedGenerationDescriptorSha256: String? = null,
        recordVisitor: ((RecordIdentity) -> Unit)? = null
    ): PackInfo {
        require(file.isFile) { "V2 pack is missing" }
        require(file.length() > MAGIC.length + 4L) { "V2 pack is too small" }
        RandomAccessFile(file, "r").use { raf ->
            val magic = ByteArray(MAGIC.toByteArray(Charsets.US_ASCII).size)
            raf.readFully(magic)
            require(magic.contentEquals(MAGIC.toByteArray(Charsets.US_ASCII))) { "V2 pack magic mismatch" }

            val headerLength = readU32Le(raf).toInt()
            require(headerLength in 1..MAX_HEADER_BYTES) { "V2 pack header length is invalid" }
            val headerBytes = ByteArray(headerLength)
            raf.readFully(headerBytes)
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val headerText = decoder.decode(ByteBuffer.wrap(headerBytes)).toString()
            val header = JSONObject(headerText)
            require(canonicalJson(header) == headerText) { "V2 pack header is not canonical JSON" }
            require(header.keys().asSequence().toSet() == HEADER_KEYS) {
                "V2 pack header fields are non-canonical"
            }

            require(header.optString("format") == FORMAT) { "V2 pack format mismatch" }
            require(header.optString("purpose") == "software-engineering-pretraining") { "V2 pack purpose mismatch" }
            val splitId = header.getInt("splitId")
            val splitRole = header.getString("splitRole")
            require(splitId in SPLIT_TRAIN..SPLIT_CHALLENGE && splitRole == roleForSplit(splitId)) {
                "V2 pack split contract mismatch"
            }
            require(header.optString("architectureId") == ARCHITECTURE_ID) { "V2 architecture mismatch" }
            require(header.optInt("qualifiedContextTokens") == CONTEXT_TOKENS) { "V2 context contract mismatch" }
            require(header.optString("tokenizerCandidateId") == RiftFrozenByteBpeV1.CANDIDATE_ID) { "V2 tokenizer identity mismatch" }
            require(header.optString("tokenizerArtifactSha256") == RiftFrozenByteBpeV1.ARTIFACT_SHA256) { "V2 tokenizer SHA mismatch" }
            require(header.optString("tokenizerTrainerConfigSha256") == RiftFrozenByteBpeV1.TRAINER_CONFIG_SHA256) { "V2 tokenizer config mismatch" }
            require(header.optInt("vocabularySize") == VOCAB_SIZE) { "V2 vocabulary mismatch" }
            require(header.optString("normalization") == "identity-utf8" && header.optBoolean("byteFallback")) { "V2 tokenizer semantic mismatch" }
            require(header.optInt("maxTokenBytes") == RiftFrozenByteBpeV1.MAX_TOKEN_BYTES) { "V2 max-token-byte mismatch" }

            val specials = header.getJSONObject("specialTokenIds")
            require(specials.keys().asSequence().toSet() == RiftFrozenByteBpeV1.SPECIALS.toSet()) {
                "V2 special-token table fields mismatch"
            }
            RiftFrozenByteBpeV1.SPECIALS.forEachIndexed { index, literal ->
                val expectedId = RiftFrozenByteBpeV1.BYTE_TOKENS + RiftFrozenByteBpeV1.MERGE_COUNT + index
                require(specials.getInt(literal) == expectedId) {
                    "V2 special-token mapping mismatch"
                }
            }
            require(header.getString("boundaryPolicy") == BOUNDARY_POLICY) {
                "V2 boundary policy mismatch"
            }
            require(header.getString("sourceKindTableVersion") == SOURCE_KIND_TABLE_VERSION) {
                "V2 source-kind table version mismatch"
            }
            require(header.getString("splitPolicyId") == SPLIT_POLICY_ID) {
                "V2 split policy mismatch"
            }
            require(header.getInt("validationPermyriad") == VALIDATION_PERMYRIAD) {
                "V2 validation split ratio mismatch"
            }
            require(header.getString("dedupPolicyId") == DEDUP_POLICY_ID) {
                "V2 dedup policy mismatch"
            }
            val nearDedupImplemented = header.getBoolean("nearDedupImplemented")
            val productionEligible = header.getBoolean("productionPretrainingEligible")
            require(!productionEligible || nearDedupImplemented) {
                "V2 production eligibility requires implemented near-dedup"
            }

            require(header.optString("payloadEncoding") == RECORD_ABI && header.optString("indexEncoding") == INDEX_ABI) { "V2 binary ABI mismatch" }
            require(header.optInt("indexEntryBytes") == INDEX_ENTRY_BYTES && header.optInt("recordHeaderBytes") == RECORD_HEADER_BYTES) { "V2 ABI size mismatch" }
            val descriptorSha = header.getString("generationDescriptorSha256")
            validateSha(descriptorSha, "V2 generation descriptor")
            if (expectedGenerationDescriptorSha256 != null) {
                require(descriptorSha == expectedGenerationDescriptorSha256) { "V2 generation descriptor identity mismatch" }
            }
            validateSha(header.getString("provenanceSha256"), "V2 provenance")
            val provenanceCount = header.getLong("provenanceCount")
            require(provenanceCount in 1..0x1_0000_0000L) {
                "V2 provenance row count is invalid"
            }
            val policyHashes = header.getJSONObject("policySha256")
            require(policyHashes.keys().asSequence().toSet() == REQUIRED_POLICY_KEYS) {
                "V2 policy hash set mismatch"
            }
            policyHashes.keys().forEach { validateSha(policyHashes.getString(it), "V2 policy") }

            val languages = header.getJSONArray("languageTable")
            require(languages.length() in 1..MAX_LANGUAGE_COUNT && languages.getString(0) == "mixed") {
                "V2 language table is invalid"
            }
            val seenLanguages = HashSet<String>()
            var priorLanguage: String? = null
            for (i in 0 until languages.length()) {
                val language = languages.getString(i)
                require(language.matches(Regex("[a-z0-9+._-]{1,64}"))) { "V2 language identifier is invalid" }
                require(seenLanguages.add(language)) { "V2 language table contains duplicates" }
                if (i >= 1 && priorLanguage != null) {
                    require(language > priorLanguage!!) { "V2 language table is not sorted" }
                }
                if (i >= 1) priorLanguage = language
            }

            val sampleCount = header.getLong("sampleCount")
            val tokenCount = header.getLong("tokenCount")
            val indexBytes = header.getLong("indexBytes")
            val recordBytes = header.getLong("recordRegionBytes")
            require(sampleCount > 0L) { "V2 pack sample count is invalid" }
            val minimumTokens = checkedMultiply(sampleCount, 3L)
            val maximumTokens = checkedMultiply(sampleCount, CONTEXT_TOKENS.toLong())
            require(tokenCount in minimumTokens..maximumTokens) { "V2 pack token count is invalid" }
            val expectedIndexBytes = checkedMultiply(sampleCount, INDEX_ENTRY_BYTES.toLong())
            require(indexBytes == expectedIndexBytes) { "V2 index byte count mismatch" }
            val minimumRecordBytes = checkedMultiply(
                sampleCount,
                RECORD_HEADER_BYTES.toLong() + 6L
            )
            require(recordBytes >= minimumRecordBytes) { "V2 record region is too small" }
            validateSha(header.getString("indexSha256"), "V2 index")
            validateSha(header.getString("recordRegionSha256"), "V2 record region")

            val dataStart = checkedAdd(
                checkedAdd(MAGIC.toByteArray(Charsets.US_ASCII).size.toLong(), 4L),
                headerLength.toLong()
            )
            val indexStart = dataStart
            val recordStart = checkedAdd(indexStart, indexBytes)
            val expectedFileBytes = checkedAdd(recordStart, recordBytes)
            require(expectedFileBytes == file.length()) { "V2 pack has missing/trailing bytes" }

            require(sha256Range(file, indexStart, indexBytes) == header.getString("indexSha256")) { "V2 index SHA-256 mismatch" }
            require(sha256Range(file, recordStart, recordBytes) == header.getString("recordRegionSha256")) { "V2 record-region SHA-256 mismatch" }

            var expectedRecordOffset = 0L
            var observedTokens = 0L
            for (sample in 0L until sampleCount) {
                raf.seek(
                    checkedAdd(
                        indexStart,
                        checkedMultiply(sample, INDEX_ENTRY_BYTES.toLong())
                    )
                )
                val recordOffset = readU64Le(raf)
                val indexedTokenCount = readU32Le(raf).toInt()
                val languageId = readU16Le(raf)
                val indexedSplit = raf.readUnsignedByte()
                val kindId = raf.readUnsignedByte()
                val indexedFlags = readU32Le(raf)
                val reserved = readU32Le(raf)

                require(recordOffset == expectedRecordOffset) { "V2 record offsets are non-canonical" }
                require(indexedTokenCount in 3..CONTEXT_TOKENS) { "V2 indexed token count is invalid" }
                require(languageId in 0 until languages.length()) { "V2 language id out of range" }
                require(indexedSplit == splitId) { "V2 index split id mismatch" }
                require(kindId in 1..MAX_SOURCE_KIND_ID) { "V2 source-kind id out of range" }
                require((indexedFlags and KNOWN_VERIFICATION_FLAGS.inv()) == 0L) { "V2 verification flags contain unknown bits" }
                require(reserved == 0L) { "V2 index reserved bytes are nonzero" }

                raf.seek(recordStart + recordOffset)
                val hashes = ByteArray(96)
                raf.readFully(hashes)
                val provenanceIndex = readU32Le(raf)
                val originalBytes = readU32Le(raf)
                val recordTokenCount = readU32Le(raf).toInt()
                val recordFlags = readU32Le(raf)
                require(
                    provenanceIndex < provenanceCount &&
                        originalBytes in 1..MAX_ORIGINAL_UTF8_BYTES.toLong()
                ) {
                    "V2 record metadata/provenance index is invalid"
                }
                require(recordTokenCount == indexedTokenCount && recordFlags == indexedFlags) {
                    "V2 index/record metadata disagreement"
                }

                var firstToken = -1
                var lastToken = -1
                repeat(recordTokenCount) { position ->
                    val token = readU16Le(raf)
                    require(token in 0 until VOCAB_SIZE) { "V2 token id out of range" }
                    if (position == 0) firstToken = token
                    lastToken = token
                }
                require(firstToken == BOS_ID && lastToken == EOS_ID) { "V2 record boundary token mismatch" }

                recordVisitor?.invoke(
                    RecordIdentity(
                        hex(hashes.copyOfRange(0, 32)),
                        hex(hashes.copyOfRange(32, 64)),
                        hex(hashes.copyOfRange(64, 96)),
                        splitId
                    )
                )

                val bytes = checkedAdd(
                    RECORD_HEADER_BYTES.toLong(),
                    checkedMultiply(recordTokenCount.toLong(), 2L)
                )
                expectedRecordOffset = checkedAdd(expectedRecordOffset, bytes)
                observedTokens = checkedAdd(observedTokens, recordTokenCount.toLong())
            }
            require(expectedRecordOffset == recordBytes) { "V2 record region is not fully accounted for" }
            require(observedTokens == tokenCount) { "V2 token count mismatch" }

            return PackInfo(
                file,
                sha256File(file),
                file.length(),
                sampleCount,
                tokenCount,
                header.getString("indexSha256"),
                header.getString("recordRegionSha256"),
                header
            )
        }
    }

    fun canonicalJson(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> {
            val keys = value.keys().asSequence().toList().sorted()
            keys.joinToString(prefix = "{", postfix = "}", separator = ",") { key ->
                JSONObject.quote(key) + ":" + canonicalJson(value.get(key))
            }
        }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]", separator = ",") {
            canonicalJson(value.get(it))
        }
        is String -> JSONObject.quote(value)
        is Boolean -> if (value) "true" else "false"
        is Number -> {
            val text = value.toString()
            require(!text.equals("NaN", true) && !text.contains("Infinity", true)) { "non-finite JSON number" }
            text
        }
        else -> JSONObject.quote(value.toString())
    }

    fun sha256Bytes(bytes: ByteArray): String =
        hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(file), 256 * 1024).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    fun sha256Raw(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    fun hexToBytes(value: String): ByteArray {
        validateSha(value, "SHA-256")
        return ByteArray(32) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun validateRecord(record: Record, metadata: Metadata) {
        require(record.sampleSha256.size == 32 && record.contentSha256.size == 32 && record.sourceGroupSha256.size == 32) {
            "V2 record SHA-256 fields must be 32 bytes"
        }
        require(
            record.provenanceIndex in 0..0xffffffffL &&
                record.provenanceIndex < metadata.provenanceCount
        ) { "V2 provenance index out of range" }
        require(record.originalUtf8Bytes in 1..MAX_ORIGINAL_UTF8_BYTES.toLong()) {
            "V2 original UTF-8 byte count out of range"
        }
        require(record.tokenIds.size in 3..CONTEXT_TOKENS) { "V2 record does not fit qualified context" }
        require(record.tokenIds.first() == BOS_ID && record.tokenIds.last() == EOS_ID) { "V2 record must be BOS/content/EOS" }
        record.tokenIds.forEach { require(it in 0 until VOCAB_SIZE) { "V2 token id out of range" } }
        require(record.languageId in metadata.languageTable.indices) { "V2 language id out of range" }
        require(record.sourceKindId in 1..MAX_SOURCE_KIND_ID) { "V2 source-kind id out of range" }
        require((record.verificationFlags and KNOWN_VERIFICATION_FLAGS.inv()) == 0L) {
            "V2 verification flags contain unknown bits"
        }
    }

    private fun copyExact(source: File, out: java.io.OutputStream) {
        val expected = source.length()
        var copied = 0L
        BufferedInputStream(FileInputStream(source), 256 * 1024).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (copied < expected) {
                val request = minOf(buffer.size.toLong(), expected - copied).toInt()
                val read = input.read(buffer, 0, request)
                require(read > 0) { "V2 stage changed while copying" }
                out.write(buffer, 0, read)
                copied += read
            }
            require(input.read() == -1 && source.length() == expected) { "V2 stage size changed while copying" }
        }
    }

    private fun sha256Range(file: File, offset: Long, bytes: Long): String {
        require(offset >= 0L && bytes >= 0L && offset <= file.length() && bytes <= file.length() - offset) {
            "V2 SHA range is out of bounds"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val buffer = ByteArray(256 * 1024)
            var remaining = bytes
            while (remaining > 0L) {
                val request = minOf(buffer.size.toLong(), remaining).toInt()
                val read = raf.read(buffer, 0, request)
                require(read > 0) { "V2 SHA range ended early" }
                digest.update(buffer, 0, read)
                remaining -= read.toLong()
            }
        }
        return hex(digest.digest())
    }

    private fun roleForSplit(splitId: Int): String = when (splitId) {
        SPLIT_TRAIN -> "train"
        SPLIT_VALIDATION -> "validation"
        SPLIT_CHALLENGE -> "challenge"
        else -> error("invalid V2 split")
    }

    private fun validateSha(value: String, label: String) {
        require(value.matches(Regex("[0-9a-f]{64}"))) { "$label SHA-256 must be canonical lowercase hex" }
    }

    private fun checkedAdd(left: Long, right: Long): Long {
        require(left >= 0L && right >= 0L && left <= Long.MAX_VALUE - right) {
            "V2 byte-count overflow"
        }
        return left + right
    }

    private fun checkedMultiply(left: Long, right: Long): Long {
        require(left >= 0L && right >= 0L) { "V2 byte-count overflow" }
        if (left == 0L || right == 0L) return 0L
        require(left <= Long.MAX_VALUE / right) { "V2 byte-count overflow" }
        return left * right
    }

    private fun writeU16Le(out: java.io.OutputStream, value: Int) {
        require(value in 0..0xffff)
        out.write(value and 0xff)
        out.write((value ushr 8) and 0xff)
    }

    private fun writeU32Le(out: java.io.OutputStream, value: Long) {
        require(value in 0..0xffffffffL)
        repeat(4) { shift -> out.write(((value ushr (shift * 8)) and 0xffL).toInt()) }
    }

    private fun writeU64Le(out: java.io.OutputStream, value: Long) {
        require(value >= 0L)
        repeat(8) { shift -> out.write(((value ushr (shift * 8)) and 0xffL).toInt()) }
    }

    private fun readU16Le(raf: RandomAccessFile): Int {
        val a = raf.read()
        val b = raf.read()
        require(a >= 0 && b >= 0) { "unexpected EOF reading V2 u16" }
        return a or (b shl 8)
    }

    private fun readU32Le(raf: RandomAccessFile): Long {
        var out = 0L
        repeat(4) { shift ->
            val value = raf.read()
            require(value >= 0) { "unexpected EOF reading V2 u32" }
            out = out or (value.toLong() shl (shift * 8))
        }
        return out
    }

    private fun readU64Le(raf: RandomAccessFile): Long {
        var out = 0L
        repeat(8) { shift ->
            val value = raf.read()
            require(value >= 0) { "unexpected EOF reading V2 u64" }
            if (shift == 7) require((value and 0x80) == 0) { "V2 u64 exceeds signed Long range" }
            out = out or (value.toLong() shl (shift * 8))
        }
        return out
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
