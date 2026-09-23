package com.riftos.app

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * On-device adversarial parser lab for RiftTrainData V2.
 *
 * Builds one valid tiny pack with the real Writer, validates the control, then
 * creates malformed copies and requires the real parser to reject every one.
 * Deep index/record corruptions have their region SHA repaired in the header so
 * the test reaches the ABI invariant rather than stopping at the checksum.
 */
object RiftTrainDataV2AdversarialLab {
    private const val LAB_ID = "rift-train-data-v2-adversarial-parser-v1"
    private const val EVIDENCE_RELATIVE =
        "training/private/production-v2/qualification/adversarial-parser-evidence.json"

    private data class Layout(
        val headerLength: Int,
        val header: JSONObject,
        val indexOffset: Int,
        val indexBytes: Int,
        val recordOffset: Int,
        val recordBytes: Int
    )

    fun status(context: Context): JSONObject {
        val root = projectRoot(context)
        val evidence = exactPath(root, EVIDENCE_RELATIVE)
        if (!evidence.isFile) {
            return JSONObject()
                .put("labId", LAB_ID)
                .put("evidenceExists", false)
                .put("evidenceValidForCurrentApp", false)
                .put("productionPretrainingEligible", false)
        }

        return runCatching {
            val text = evidence.readText(Charsets.UTF_8)
            val obj = JSONObject(text)
            require(RiftTrainDataV2Format.canonicalJson(obj) == text) {
                "V2 adversarial evidence is not canonical JSON"
            }
            val app = appIdentity(context)
            require(obj.getString("labId") == LAB_ID)
            require(obj.getString("parser") == RiftTrainDataV2Format.FORMAT)
            require(obj.getBoolean("controlAccepted"))
            require(obj.getBoolean("allMalformedRejected"))
            require(obj.getInt("casesPassed") == 5)
            require(obj.getString("packageName") == app.getString("packageName"))
            require(obj.getString("versionName") == app.getString("versionName"))
            require(obj.getLong("versionCode") == app.getLong("versionCode"))
            require(obj.getString("apkSha256") == app.getString("apkSha256"))
            val cases = obj.getJSONArray("cases")
            require(cases.length() == 5)
            val expectedCases = listOf(
                "truncate",
                "trailing-byte",
                "header-corruption",
                "index-reserved-byte",
                "record-bos-boundary"
            )
            expectedCases.forEachIndexed { index, name ->
                val item = cases.getJSONObject(index)
                require(item.getString("case") == name)
                require(item.getBoolean("rejected"))
            }

            JSONObject()
                .put("labId", LAB_ID)
                .put("evidenceExists", true)
                .put("evidenceValidForCurrentApp", true)
                .put(
                    "evidenceSha256",
                    RiftTrainDataV2Format.sha256File(evidence)
                )
                .put("casesPassed", obj.getInt("casesPassed"))
                .put("productionPretrainingEligible", false)
        }.getOrElse { error ->
            JSONObject()
                .put("labId", LAB_ID)
                .put("evidenceExists", true)
                .put("evidenceValidForCurrentApp", false)
                .put(
                    "evidenceSha256",
                    RiftTrainDataV2Format.sha256File(evidence)
                )
                .put("error", error.message ?: error.javaClass.simpleName)
                .put("productionPretrainingEligible", false)
        }
    }

    fun run(context: Context): JSONObject {
        val root = File(
            context.cacheDir,
            "rift-train-data-v2-adversarial-" + System.nanoTime().toString(16)
        )
        require(root.mkdirs()) {
            "could not create V2 adversarial lab directory"
        }

        try {
            val descriptorSha = sha256Hex("v2-adversarial-descriptor")
            val metadata = RiftTrainDataV2Format.Metadata(
                splitRole = "train",
                splitId = RiftTrainDataV2Format.SPLIT_TRAIN,
                architectureId = RiftTrainDataV2Format.ARCHITECTURE_ID,
                generationDescriptorSha256 = descriptorSha,
                provenanceSha256 = sha256Hex("v2-adversarial-provenance"),
                provenanceCount = 1L,
                policyHashes = policyHashes(),
                languageTable = listOf("mixed", "text"),
                sourceKindTableVersion =
                    RiftTrainDataV2Format.SOURCE_KIND_TABLE_VERSION,
                splitPolicyId = RiftTrainDataV2Format.SPLIT_POLICY_ID,
                validationPermyriad =
                    RiftTrainDataV2Format.VALIDATION_PERMYRIAD,
                dedupPolicyId = RiftTrainDataV2Format.DEDUP_POLICY_ID,
                nearDedupImplemented = false,
                productionPretrainingEligible = false
            )

            val content = "x".toByteArray(Charsets.UTF_8)
            val record = RiftTrainDataV2Format.Record(
                sampleSha256 = sha256Raw("sample"),
                contentSha256 = RiftTrainDataV2Format.sha256Raw(content),
                sourceGroupSha256 = sha256Raw("group"),
                provenanceIndex = 0L,
                originalUtf8Bytes = content.size.toLong(),
                tokenIds = intArrayOf(
                    RiftTrainDataV2Format.BOS_ID,
                    'x'.code,
                    RiftTrainDataV2Format.EOS_ID
                ),
                languageId = 1,
                sourceKindId = 1,
                verificationFlags = 0L
            )

            val writer = RiftTrainDataV2Format.Writer(
                root,
                "control.rifttok",
                metadata
            )
            val control = try {
                writer.append(record)
                writer.finish().file
            } finally {
                writer.close()
            }

            val controlInfo =
                RiftTrainDataV2Format.validate(control, descriptorSha)
            require(controlInfo.sampleCount == 1L) {
                "V2 adversarial control sample count mismatch"
            }

            val results = JSONArray()
            results.put(
                rejectionCase(
                    root,
                    control,
                    descriptorSha,
                    "truncate"
                ) { file ->
                    val bytes = file.readBytes()
                    require(bytes.size > 1)
                    file.writeBytes(bytes.copyOf(bytes.size - 1))
                }
            )
            results.put(
                rejectionCase(
                    root,
                    control,
                    descriptorSha,
                    "trailing-byte"
                ) { file ->
                    file.appendBytes(byteArrayOf(0x5a))
                }
            )
            results.put(
                rejectionCase(
                    root,
                    control,
                    descriptorSha,
                    "header-corruption"
                ) { file ->
                    val bytes = file.readBytes()
                    val headerStart =
                        RiftTrainDataV2Format.MAGIC
                            .toByteArray(Charsets.US_ASCII).size + 4
                    bytes[headerStart] =
                        if (bytes[headerStart].toInt() == '{'.code) {
                            '['.code.toByte()
                        } else {
                            0x7f
                        }
                    file.writeBytes(bytes)
                }
            )
            results.put(
                rejectionCase(
                    root,
                    control,
                    descriptorSha,
                    "index-reserved-byte"
                ) { file ->
                    val bytes = file.readBytes()
                    val layout = parseLayout(bytes)
                    require(
                        layout.indexBytes >=
                            RiftTrainDataV2Format.INDEX_ENTRY_BYTES
                    )
                    bytes[layout.indexOffset + 20] = 1
                    repairRegionSha(
                        bytes,
                        layout,
                        "indexSha256",
                        layout.indexOffset,
                        layout.indexBytes
                    )
                    file.writeBytes(bytes)
                }
            )
            results.put(
                rejectionCase(
                    root,
                    control,
                    descriptorSha,
                    "record-bos-boundary"
                ) { file ->
                    val bytes = file.readBytes()
                    val layout = parseLayout(bytes)
                    val tokenOffset =
                        layout.recordOffset +
                            RiftTrainDataV2Format.RECORD_HEADER_BYTES
                    val wrongBos = RiftTrainDataV2Format.BOS_ID - 1
                    bytes[tokenOffset] = (wrongBos and 0xff).toByte()
                    bytes[tokenOffset + 1] =
                        ((wrongBos ushr 8) and 0xff).toByte()
                    repairRegionSha(
                        bytes,
                        layout,
                        "recordRegionSha256",
                        layout.recordOffset,
                        layout.recordBytes
                    )
                    file.writeBytes(bytes)
                }
            )

            for (index in 0 until results.length()) {
                require(results.getJSONObject(index).getBoolean("rejected")) {
                    "V2 adversarial parser accepted malformed case: " +
                        results.getJSONObject(index).getString("case")
                }
            }
            require(
                results.getJSONObject(3)
                    .getString("rejectionMessage")
                    .contains("V2 index reserved bytes are nonzero")
            ) {
                "V2 adversarial reserved-byte case did not reach deep invariant"
            }
            require(
                results.getJSONObject(4)
                    .getString("rejectionMessage")
                    .contains("V2 record boundary token mismatch")
            ) {
                "V2 adversarial BOS case did not reach deep boundary invariant"
            }

            val app = appIdentity(context)
            val evidence = JSONObject()
                .put("labId", LAB_ID)
                .put("parser", RiftTrainDataV2Format.FORMAT)
                .put("packageName", app.getString("packageName"))
                .put("versionName", app.getString("versionName"))
                .put("versionCode", app.getLong("versionCode"))
                .put("apkSha256", app.getString("apkSha256"))
                .put(
                    "controlPackSha256",
                    RiftTrainDataV2Format.sha256File(control)
                )
                .put("controlAccepted", true)
                .put("controlSampleCount", controlInfo.sampleCount)
                .put("cases", results)
                .put("casesPassed", results.length())
                .put("allMalformedRejected", true)
                .put("completedAtMs", System.currentTimeMillis())
                .put("productionPretrainingEligible", false)
            val evidenceSha = persistEvidence(context, evidence)
            return evidence
                .put("evidenceSha256", evidenceSha)
                .put(
                    "evidencePath",
                    "/workspace/RiftLLM/" + EVIDENCE_RELATIVE
                )
        } finally {
            require(root.deleteRecursively() || !root.exists()) {
                "V2 adversarial lab cleanup failed"
            }
        }
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
            "invalid fixed V2 adversarial evidence path"
        }
        val raw = File(root, relative).absoluteFile
        val normalized = raw.toPath().normalize().toFile().absoluteFile
        val file = raw.canonicalFile
        require(file.path.startsWith(root.path + File.separator)) {
            "V2 adversarial evidence path escaped project"
        }
        require(file.path == normalized.path) {
            "V2 adversarial evidence fixed path resolves through a symlink"
        }
        return file
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
            "installed APK path is unavailable for adversarial evidence binding"
        }
        return JSONObject()
            .put("packageName", context.packageName)
            .put("versionName", info.versionName ?: "")
            .put("versionCode", versionCode)
            .put("apkSha256", RiftTrainDataV2Format.sha256File(apkFile))
    }

    private fun persistEvidence(
        context: Context,
        evidence: JSONObject
    ): String {
        val root = projectRoot(context)
        val output = exactPath(root, EVIDENCE_RELATIVE)
        val parent = output.parentFile
            ?: throw IllegalArgumentException(
                "V2 adversarial evidence has no parent"
            )
        require(parent.isDirectory || parent.mkdirs()) {
            "V2 adversarial evidence directory is unavailable"
        }

        val stage = File(
            parent,
            ".adversarial-parser-evidence." +
                System.nanoTime().toString(16) +
                ".stage"
        )
        try {
            FileOutputStream(stage, false).use { out ->
                out.write(
                    RiftTrainDataV2Format
                        .canonicalJson(evidence)
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
        return RiftTrainDataV2Format.sha256File(output)
    }

    private fun fsyncDirectory(directory: File) {
        require(directory.isDirectory) {
            "V2 adversarial evidence fsync target is invalid"
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

    private fun rejectionCase(
        root: File,
        control: File,
        descriptorSha: String,
        caseName: String,
        mutate: (File) -> Unit
    ): JSONObject {
        val file = File(root, caseName + ".rifttok")
        Files.copy(control.toPath(), file.toPath())
        mutate(file)

        var rejectionClass = ""
        var rejectionMessage = ""
        val rejected =
            try {
                RiftTrainDataV2Format.validate(file, descriptorSha)
                false
            } catch (error: Throwable) {
                rejectionClass = error.javaClass.simpleName
                rejectionMessage = error.message ?: ""
                true
            }

        return JSONObject()
            .put("case", caseName)
            .put("rejected", rejected)
            .put("rejectionClass", rejectionClass)
            .put("rejectionMessage", rejectionMessage.take(240))
    }

    private fun parseLayout(bytes: ByteArray): Layout {
        val magic = RiftTrainDataV2Format.MAGIC
            .toByteArray(Charsets.US_ASCII)
        require(bytes.size > magic.size + 4)
        require(
            bytes.copyOfRange(0, magic.size).contentEquals(magic)
        ) {
            "V2 adversarial lab control magic drifted"
        }

        val headerLength = readU32Le(bytes, magic.size)
        require(headerLength in 1..RiftTrainDataV2Format.MAX_HEADER_BYTES)
        val headerStart = magic.size + 4
        val headerEnd = headerStart + headerLength
        require(headerEnd <= bytes.size)
        val headerText =
            bytes.copyOfRange(headerStart, headerEnd)
                .toString(Charsets.UTF_8)
        val header = JSONObject(headerText)
        require(
            RiftTrainDataV2Format.canonicalJson(header) == headerText
        ) {
            "V2 adversarial lab control header is not canonical"
        }

        val indexBytes = header.getLong("indexBytes").toInt()
        val recordBytes = header.getLong("recordRegionBytes").toInt()
        val indexOffset = headerEnd
        val recordOffset = indexOffset + indexBytes
        require(
            indexBytes >= RiftTrainDataV2Format.INDEX_ENTRY_BYTES &&
                recordBytes >= RiftTrainDataV2Format.RECORD_HEADER_BYTES &&
                recordOffset + recordBytes == bytes.size
        ) {
            "V2 adversarial lab control layout is invalid"
        }
        return Layout(
            headerLength,
            header,
            indexOffset,
            indexBytes,
            recordOffset,
            recordBytes
        )
    }

    private fun repairRegionSha(
        bytes: ByteArray,
        layout: Layout,
        key: String,
        offset: Int,
        length: Int
    ) {
        require(key == "indexSha256" || key == "recordRegionSha256")
        require(offset >= 0 && length > 0 && offset + length <= bytes.size)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes, offset, length)
        val sha = digest.digest().joinToString("") {
            (it.toInt() and 0xff)
                .toString(16)
                .padStart(2, '0')
        }

        layout.header.put(key, sha)
        val rewritten =
            RiftTrainDataV2Format
                .canonicalJson(layout.header)
                .toByteArray(Charsets.UTF_8)
        require(rewritten.size == layout.headerLength) {
            "V2 adversarial header length changed while repairing SHA"
        }
        val headerStart =
            RiftTrainDataV2Format.MAGIC
                .toByteArray(Charsets.US_ASCII).size + 4
        rewritten.copyInto(bytes, headerStart)
    }

    private fun policyHashes(): Map<String, String> =
        linkedMapOf(
            "source-provenance" to sha256Hex("source-provenance"),
            "licensing-usage" to sha256Hex("licensing-usage"),
            "secret-privacy" to sha256Hex("secret-privacy"),
            "dedup" to sha256Hex("dedup"),
            "split-leakage" to sha256Hex("split-leakage"),
            "rendering" to sha256Hex("rendering"),
            "quality-verification" to sha256Hex("quality-verification")
        )

    private fun sha256Raw(value: String): ByteArray =
        RiftTrainDataV2Format.sha256Raw(
            value.toByteArray(Charsets.UTF_8)
        )

    private fun sha256Hex(value: String): String =
        sha256Raw(value).joinToString("") {
            (it.toInt() and 0xff)
                .toString(16)
                .padStart(2, '0')
        }

    private fun readU32Le(bytes: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset + 4 <= bytes.size)
        return (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
    }
}
