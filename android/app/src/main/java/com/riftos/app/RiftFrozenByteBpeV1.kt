package com.riftos.app

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.PriorityQueue

/**
 * Single runtime authority for the frozen Rift Byte-BPE V1 artifact used by
 * RiftLLM training-data builders.
 *
 * This owns tokenizer identity/parsing and deterministic encode semantics only.
 * It does not own dataset selection, split policy, pack layout, or training.
 */
object RiftFrozenByteBpeV1 {
    const val ARTIFACT_MAGIC = "RIFT_BYTE_BPE_V1"
    const val CANDIDATE_ID = "rift-token-b-balanced-v2"
    const val ARTIFACT_SHA256 = "314e3a732d4cc4c31c40c9b0add3fffcec38c8a4b40e0d228bdc4eed1addbbd1"
    const val TRAINING_CORPUS_SHA256 = "b88b0ab8d3a7dc784e2e4b20d33b5c5fab222542880cea197f529d5c996e9a05"
    const val TRAINER_CONFIG_SHA256 = "9d442860e3ed407fe10f10e72ad41fabc2854cfc3cdcaeb654b35ddad506faba"
    const val VOCAB_SIZE = 32768
    const val BYTE_TOKENS = 256
    const val MERGE_COUNT = 32504
    const val MAX_TOKEN_BYTES = 24

    val SPECIALS = listOf(
        "<|bos|>", "<|eos|>", "<|pad|>", "<|system|>",
        "<|user|>", "<|assistant|>", "<|tool|>", "<|end|>"
    )

    data class Merge(val left: Int, val right: Int)

    data class Artifact internal constructor(
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

    fun loadFrozen(file: File): Artifact {
        require(file.isFile && file.length() in 1..(4L * 1024L * 1024L)) {
            "frozen B2 artifact is missing or out of bounds"
        }
        require(sha256File(file) == ARTIFACT_SHA256) {
            "frozen B2 artifact SHA-256 drifted"
        }

        val lines = file.readText(Charsets.UTF_8)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .toList()
        require(lines.firstOrNull() == ARTIFACT_MAGIC) {
            "frozen tokenizer artifact magic mismatch"
        }

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
                line == "merges_begin" -> {
                    require(!inMerges) { "nested tokenizer merges_begin" }
                    inMerges = true
                }
                line == "merges_end" -> {
                    require(inMerges) { "tokenizer merges_end without merges_begin" }
                    inMerges = false
                }
                inMerges -> {
                    val parts = line.split(Regex("\\s+"))
                    require(parts.size == 2) { "malformed tokenizer merge row" }
                    val left = parts[0].toInt()
                    val right = parts[1].toInt()
                    require(left in 0 until VOCAB_SIZE && right in 0 until VOCAB_SIZE) {
                        "tokenizer merge id is out of range"
                    }
                    merges += Merge(left, right)
                }
                line.startsWith("candidate_id=") ->
                    candidate = line.substringAfter('=').trim()
                line.startsWith("training_corpus_sha256=") ->
                    trainingSha = line.substringAfter('=').trim().lowercase()
                line.startsWith("trainer_config_sha256=") ->
                    configSha = line.substringAfter('=').trim().lowercase()
                line.startsWith("vocab_size=") ->
                    vocab = line.substringAfter('=').toInt()
                line.startsWith("normalization=") ->
                    normalization = line.substringAfter('=').trim()
                line.startsWith("byte_fallback=") ->
                    fallback = line.substringAfter('=').trim().toBooleanStrict()
                line.startsWith("special=") -> {
                    val payload = line.substringAfter('=')
                    val colon = payload.indexOf(':')
                    require(colon > 0) { "malformed tokenizer special row" }
                    val id = payload.substring(0, colon).toInt()
                    val literal = String(
                        hexToBytes(payload.substring(colon + 1)),
                        Charsets.UTF_8
                    )
                    require(specials.put(literal, id) == null) {
                        "duplicate tokenizer special literal"
                    }
                }
                else -> error("unknown tokenizer artifact field: $line")
            }
        }

        require(!inMerges) { "unterminated tokenizer merge table" }
        require(
            candidate == CANDIDATE_ID &&
                trainingSha == TRAINING_CORPUS_SHA256 &&
                configSha == TRAINER_CONFIG_SHA256
        ) { "frozen tokenizer provenance mismatch" }
        require(
            vocab == VOCAB_SIZE &&
                normalization == "identity-utf8" &&
                fallback &&
                merges.size == MERGE_COUNT
        ) { "frozen tokenizer contract mismatch" }
        require(SPECIALS.size == VOCAB_SIZE - BYTE_TOKENS - MERGE_COUNT) {
            "frozen tokenizer special-token count drifted"
        }
        SPECIALS.forEachIndexed { index, literal ->
            require(specials[literal] == BYTE_TOKENS + MERGE_COUNT + index) {
                "frozen special-token mapping mismatch"
            }
        }
        require(specials.size == SPECIALS.size) {
            "unexpected tokenizer special-token rows"
        }

        // Every merge result ID is BYTE_TOKENS + rank, so each pair may only
        // reference bytes or an already-created merge token.
        merges.forEachIndexed { rank, merge ->
            val resultId = BYTE_TOKENS + rank
            require(merge.left < resultId && merge.right < resultId) {
                "tokenizer merge references a token before it exists"
            }
        }

        return Artifact(
            candidate,
            trainingSha,
            configSha,
            vocab,
            normalization,
            fallback,
            merges,
            specials.toMap()
        )
    }

    class Encoder(artifact: Artifact) {
        private val merges = artifact.merges
        private val ranks = HashMap<Long, Int>(merges.size * 2)

        init {
            require(
                artifact.candidateId == CANDIDATE_ID &&
                    artifact.trainingCorpusSha256 == TRAINING_CORPUS_SHA256 &&
                    artifact.trainerConfigSha256 == TRAINER_CONFIG_SHA256 &&
                    artifact.vocabSize == VOCAB_SIZE &&
                    artifact.normalization == "identity-utf8" &&
                    artifact.byteFallback
            ) { "encoder received a non-frozen tokenizer artifact" }
            merges.forEachIndexed { rank, merge ->
                require(ranks.put(pairKey(merge.left, merge.right), rank) == null) {
                    "duplicate tokenizer merge pair"
                }
            }
        }

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
                    if (
                        i + 1 < ids.size &&
                        ids[i] == merge.left &&
                        ids[i + 1] == merge.right
                    ) {
                        nextIds += mergedId
                        i += 2
                    } else {
                        nextIds += ids[i++]
                    }
                }
                ids.clear()
                ids.addAll(nextIds)
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
            val queue = PriorityQueue<Event>(
                compareBy<Event> { it.rank }.thenBy { it.left }
            )

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
                val left = event.left
                val right = event.right
                if (
                    !alive[left] ||
                    !alive[right] ||
                    next[left] != right ||
                    prev[right] != left
                ) continue

                val rank = ranks[pairKey(token[left], token[right])] ?: continue
                if (rank != event.rank) continue

                token[left] = BYTE_TOKENS + rank
                alive[right] = false
                val after = next[right]
                next[left] = after
                if (after >= 0) prev[after] = left
                next[right] = -1

                enqueue(prev[left])
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

        private fun pairKey(left: Int, right: Int): Long =
            (left.toLong() shl 32) or (right.toLong() and 0xffffffffL)
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(file), 256 * 1024).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun hexToBytes(value: String): ByteArray {
        require(value.length % 2 == 0) { "hex value must have even length" }
        return ByteArray(value.length / 2) { i ->
            value.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
