package com.riftos.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Deterministic candidate-manifest and tamper-evidence primitives.
 *
 * Candidate identity is content-derived: volatile freeze timestamps are deliberately excluded from
 * the hashed manifest. Frozen manifests live outside workspace and are immutable by SHA-256 name.
 */
internal object RiftPatchManifestV1 {
    const val VERSION = 1
    const val RECORD_CHAIN_VERSION = 1
    const val GENESIS = "GENESIS"
    const val MAX_FROZEN_MANIFESTS = 512
    const val MAX_MANIFEST_BYTES = 8 * 1024 * 1024

    data class TreeEntry(
        val path: String,
        val kind: String,
        val size: Long,
        val sha256: String
    )

    data class Change(
        val path: String,
        val status: String,
        val before: TreeEntry?,
        val after: TreeEntry?
    )

    data class RecordVerification(
        val ok: Boolean,
        val recordHash: String?,
        val reason: String?
    )

    fun treeSha256(entries: Collection<TreeEntry>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val sorted = entries.sortedBy { it.path }
        updateField(digest, "rift-tree-v1")
        updateField(digest, sorted.size.toString())
        for (entry in sorted) {
            updateField(digest, entry.path)
            updateField(digest, entry.kind)
            updateField(digest, entry.size.toString())
            updateField(digest, entry.sha256.lowercase())
        }
        return hex(digest.digest())
    }

    fun changeSetSha256(changes: Collection<Change>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val sorted = changes.sortedBy { it.path }
        updateField(digest, "rift-change-set-v1")
        updateField(digest, sorted.size.toString())
        for (change in sorted) {
            updateField(digest, change.path)
            updateField(digest, change.status)
            updateTreeState(digest, change.before)
            updateTreeState(digest, change.after)
        }
        return hex(digest.digest())
    }

    fun sha256Canonical(value: Any?): String =
        sha256Text(canonicalJson(value))

    fun sealManifest(payload: JSONObject): JSONObject {
        payload.remove("manifestSha256")
        payload.remove("candidateId")
        val sha = sha256Canonical(payload)
        payload.put("manifestSha256", sha)
        payload.put("candidateId", "candidate-${sha.take(24)}")
        return payload
    }

    fun verifyManifest(manifest: JSONObject): Boolean {
        val expected = manifest.optString("manifestSha256")
        if (!expected.matches(Regex("^[0-9a-f]{64}$"))) return false
        val payload = JSONObject()
        for (key in manifest.keys().asSequence().toList().sorted()) {
            if (key == "manifestSha256" || key == "candidateId") continue
            payload.put(key, manifest.get(key))
        }
        return sha256Canonical(payload) == expected
    }

    fun freeze(root: File, manifest: JSONObject): JSONObject {
        require(verifyManifest(manifest)) { "Candidate manifest hash is invalid" }
        root.mkdirs()
        val sha = manifest.getString("manifestSha256")
        val canonical = canonicalJson(manifest) + "\n"
        val bytes = canonical.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_MANIFEST_BYTES) {
            "Candidate manifest exceeds $MAX_MANIFEST_BYTES bytes"
        }

        val target = File(root, "$sha.json")
        if (target.isFile) {
            require(target.readText(Charsets.UTF_8) == canonical) {
                "Frozen manifest content does not match its SHA-256 identity"
            }
            return JSONObject()
                .put("manifestSha256", sha)
                .put("candidateId", manifest.getString("candidateId"))
                .put("alreadyFrozen", true)
                .put("file", target.name)
        }

        val existing = root.listFiles()?.count { it.isFile && it.extension == "json" } ?: 0
        require(existing < MAX_FROZEN_MANIFESTS) {
            "Frozen manifest store reached $MAX_FROZEN_MANIFESTS entries"
        }

        val tmp = File(root, ".$sha.${System.nanoTime()}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            if (!target.isFile || target.readText(Charsets.UTF_8) != canonical) {
                throw IllegalStateException("Could not freeze candidate manifest")
            }
        }
        return JSONObject()
            .put("manifestSha256", sha)
            .put("candidateId", manifest.getString("candidateId"))
            .put("alreadyFrozen", false)
            .put("file", target.name)
    }

    fun sealRecord(record: JSONObject, epoch: String, previousHash: String): JSONObject {
        require(epoch.isNotBlank()) { "Record-chain epoch is required" }
        require(previousHash == GENESIS || previousHash.matches(Regex("^[0-9a-f]{64}$"))) {
            "Record-chain previous hash is invalid"
        }
        record.put("chainVersion", RECORD_CHAIN_VERSION)
        record.put("chainEpoch", epoch)
        record.put("previousRecordHash", previousHash)
        record.remove("recordHash")
        record.put("recordHash", sha256Canonical(record))
        return record
    }

    fun verifyRecord(record: JSONObject, epoch: String, expectedPreviousHash: String): RecordVerification {
        if (record.optInt("chainVersion", 0) != RECORD_CHAIN_VERSION) {
            return RecordVerification(false, null, "chain-version")
        }
        if (record.optString("chainEpoch") != epoch) {
            return RecordVerification(false, null, "chain-epoch")
        }
        if (record.optString("previousRecordHash") != expectedPreviousHash) {
            return RecordVerification(false, null, "previous-hash")
        }
        val expected = record.optString("recordHash")
        if (!expected.matches(Regex("^[0-9a-f]{64}$"))) {
            return RecordVerification(false, null, "record-hash-format")
        }
        val payload = JSONObject()
        for (key in record.keys().asSequence().toList().sorted()) {
            if (key == "recordHash") continue
            payload.put(key, record.get(key))
        }
        val actual = sha256Canonical(payload)
        if (actual != expected) return RecordVerification(false, expected, "record-hash")
        return RecordVerification(true, expected, null)
    }

    fun canonicalJson(value: Any?): String = when {
        value == null || value === JSONObject.NULL -> "null"
        value is JSONObject -> {
            val keys = value.keys().asSequence().toList().sorted()
            keys.joinToString(prefix = "{", postfix = "}", separator = ",") { key ->
                JSONObject.quote(key) + ":" + canonicalJson(value.get(key))
            }
        }
        value is JSONArray -> {
            (0 until value.length()).joinToString(prefix = "[", postfix = "]", separator = ",") { index ->
                canonicalJson(value.get(index))
            }
        }
        value is String -> JSONObject.quote(value)
        value is Boolean -> if (value) "true" else "false"
        value is Number -> {
            if (value is Double) require(value.isFinite()) { "Non-finite JSON number" }
            if (value is Float) require(value.isFinite()) { "Non-finite JSON number" }
            value.toString()
        }
        else -> JSONObject.quote(value.toString())
    }

    private fun updateTreeState(digest: MessageDigest, entry: TreeEntry?) {
        if (entry == null) {
            updateField(digest, "missing")
            return
        }
        updateField(digest, entry.kind)
        updateField(digest, entry.size.toString())
        updateField(digest, entry.sha256.lowercase())
    }

    private fun updateField(digest: MessageDigest, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val length = bytes.size
        digest.update(byteArrayOf(
            ((length ushr 24) and 0xff).toByte(),
            ((length ushr 16) and 0xff).toByte(),
            ((length ushr 8) and 0xff).toByte(),
            (length and 0xff).toByte()
        ))
        digest.update(bytes)
    }

    private fun sha256Text(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return hex(digest.digest(value.toByteArray(Charsets.UTF_8)))
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
