package com.riftos.app

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.sqrt

enum class RiftMemoryRetrievalModeV1 {
    NO,
    FAST,
    DEEP,
    FORENSIC
}

enum class RiftMemoryRetrievalLaneV1 {
    EXACT,
    ENTITY,
    PROJECT,
    TEMPORAL,
    GRAPH,
    BM25,
    VECTOR
}

data class RiftMemoryRetrievalRequestV1(
    val namespace: String,
    val projectId: String,
    val query: String = "",
    val exactRecordId: String? = null,
    val entityId: String? = null,
    val validAt: Long? = null,
    val recordedAt: Long? = null,
    val branch: RiftMemoryBranchV1 = RiftMemoryBranchV1.REALITY,
    val mode: RiftMemoryRetrievalModeV1 = RiftMemoryRetrievalModeV1.FAST,
    val maxResults: Int = 16,
    val maxContextTokens: Int = 1_024
) {
    init {
        require(namespace.isNotBlank() && namespace.length <= 256)
        require(projectId.isNotBlank() && projectId.length <= 256)
        require(query.length <= RiftMemoryRetrievalContextV1.MAX_QUERY_CHARS)
        require(exactRecordId == null || exactRecordId.length <= 256)
        require(entityId == null || entityId.length <= 256)
        require((validAt == null) == (recordedAt == null)) {
            "temporal-query-requires-valid-and-recorded-time"
        }
        require(validAt == null || validAt >= 0L)
        require(recordedAt == null || recordedAt >= 0L)
        require(maxResults in 1..RiftMemoryRetrievalContextV1.MAX_RESULTS)
        require(maxContextTokens in 1..RiftMemoryRetrievalContextV1.MAX_CONTEXT_TOKENS)
    }
}

data class RiftMemoryRetrievalHitV1(
    val record: RiftCanonicalMemoryRecordV1,
    val lanes: Set<RiftMemoryRetrievalLaneV1>,
    val requiredEvidence: Boolean,
    val evidenceWeight: Int,
    val lexicalScore: Int,
    val vectorScore: Int
) {
    fun toJson(): JSONObject {
        val laneArray = JSONArray()
        lanes.map { it.name }.sorted().forEach { lane -> laneArray.put(lane) }
        return JSONObject()
            .put("recordId", record.id)
            .put("trustState", record.trustState.name)
            .put("lanes", laneArray)
            .put("requiredEvidence", requiredEvidence)
            .put("evidenceWeight", evidenceWeight)
            .put("lexicalScore", lexicalScore)
            .put("vectorScore", vectorScore)
    }
}

data class RiftMemoryRetrievalResultV1(
    val mode: RiftMemoryRetrievalModeV1,
    val routedLanes: Set<RiftMemoryRetrievalLaneV1>,
    val hits: List<RiftMemoryRetrievalHitV1>,
    val complete: Boolean,
    val incompleteReasons: List<String>
)

data class RiftMemoryCompiledContextV1(
    val entries: List<JSONObject>,
    val estimatedTokens: Int,
    val tokenBudget: Int,
    val complete: Boolean,
    val truncated: Boolean,
    val incompleteReasons: List<String>
) {
    fun toJson(): JSONObject {
        val array = JSONArray()
        entries.forEach { entry -> array.put(entry) }
        return JSONObject()
            .put("entries", array)
            .put("estimatedTokens", estimatedTokens)
            .put("tokenBudget", tokenBudget)
            .put("complete", complete)
            .put("truncated", truncated)
            .put("incompleteReasons", JSONArray(incompleteReasons))
    }
}

class RiftMemoryRetrievalContextV1 {
    companion object {
        const val MAX_QUERY_CHARS = 4_096
        const val MAX_QUERY_TOKENS = 128
        const val MAX_PROJECT_RECORDS = 4_096
        const val MAX_DOCUMENT_CHARS = 8_192
        const val MAX_DOCUMENT_FIELDS = 64
        const val MAX_DOCUMENT_ARRAY_ITEMS = 64
        const val MAX_DOCUMENT_DEPTH = 4
        const val MAX_DOCUMENT_NODES = 512
        const val MAX_DOCUMENT_SCALAR_CHARS = 2_048
        const val MAX_RESULTS = 64
        const val MAX_CONTEXT_TOKENS = 8_192
        private const val VECTOR_DIMENSIONS = 32
    }

    private data class MutableHit(
        val record: RiftCanonicalMemoryRecordV1,
        val lanes: MutableSet<RiftMemoryRetrievalLaneV1> = linkedSetOf(),
        var requiredEvidence: Boolean = false,
        var lexicalScore: Int = 0,
        var vectorScore: Int = 0
    )

    fun route(mode: RiftMemoryRetrievalModeV1): Set<RiftMemoryRetrievalLaneV1> = when (mode) {
        RiftMemoryRetrievalModeV1.NO -> emptySet()
        RiftMemoryRetrievalModeV1.FAST -> linkedSetOf(
            RiftMemoryRetrievalLaneV1.EXACT,
            RiftMemoryRetrievalLaneV1.ENTITY,
            RiftMemoryRetrievalLaneV1.PROJECT,
            RiftMemoryRetrievalLaneV1.BM25
        )
        RiftMemoryRetrievalModeV1.DEEP,
        RiftMemoryRetrievalModeV1.FORENSIC -> linkedSetOf(
            RiftMemoryRetrievalLaneV1.EXACT,
            RiftMemoryRetrievalLaneV1.ENTITY,
            RiftMemoryRetrievalLaneV1.PROJECT,
            RiftMemoryRetrievalLaneV1.TEMPORAL,
            RiftMemoryRetrievalLaneV1.GRAPH,
            RiftMemoryRetrievalLaneV1.BM25,
            RiftMemoryRetrievalLaneV1.VECTOR
        )
    }

    fun retrieve(
        handle: RiftMemoryStoreHandleV1,
        request: RiftMemoryRetrievalRequestV1
    ): RiftMemoryRetrievalResultV1 {
        val lanes = route(request.mode)
        if (lanes.isEmpty()) {
            return RiftMemoryRetrievalResultV1(
                mode = request.mode,
                routedLanes = lanes,
                hits = emptyList(),
                complete = true,
                incompleteReasons = emptyList()
            )
        }

        val allProjectRecords = readProjectRecords(handle, request)
        val records = if (request.mode == RiftMemoryRetrievalModeV1.FORENSIC) {
            allProjectRecords
        } else {
            allProjectRecords.filter(::isVisible)
        }
        check(records.all {
            it.scope.namespace == request.namespace &&
                it.scope.projectId == request.projectId &&
                it.branch == request.branch
        }) { "retrieval-project-isolation-violation" }

        val byId = records.associateBy { it.id }
        val hits = linkedMapOf<String, MutableHit>()

        fun add(
            record: RiftCanonicalMemoryRecordV1,
            lane: RiftMemoryRetrievalLaneV1,
            required: Boolean = false,
            lexical: Int = 0,
            vector: Int = 0
        ) {
            if (record.scope.namespace != request.namespace ||
                record.scope.projectId != request.projectId ||
                record.branch != request.branch
            ) {
                throw IllegalStateException("retrieval-cross-project-hit:" + record.id)
            }
            val row = hits.getOrPut(record.id) { MutableHit(record) }
            row.lanes += lane
            row.requiredEvidence = row.requiredEvidence || required
            row.lexicalScore = maxOf(row.lexicalScore, lexical)
            row.vectorScore = maxOf(row.vectorScore, vector)
        }

        if (RiftMemoryRetrievalLaneV1.EXACT in lanes && request.exactRecordId != null) {
            val record = byId[request.exactRecordId]
            if (record != null) {
                val required = record.trustState == RiftMemoryTrustStateV1.VERIFIED &&
                    record.evidenceRefs.isNotEmpty()
                add(record, RiftMemoryRetrievalLaneV1.EXACT, required = required)
            }
        }

        if (RiftMemoryRetrievalLaneV1.ENTITY in lanes && request.entityId != null) {
            records
                .filter { it.payload.optString("entityId") == request.entityId }
                .forEach { add(it, RiftMemoryRetrievalLaneV1.ENTITY) }
        }

        val projectOnlyFallback =
            RiftMemoryRetrievalLaneV1.PROJECT in lanes &&
                request.query.isBlank() &&
                request.exactRecordId == null &&
                request.entityId == null &&
                request.validAt == null
        if (projectOnlyFallback) {
            records.forEach { add(it, RiftMemoryRetrievalLaneV1.PROJECT) }
        }

        if (RiftMemoryRetrievalLaneV1.TEMPORAL in lanes &&
            request.validAt != null &&
            request.recordedAt != null
        ) {
            val temporal = RiftMemoryTemporalGraphV1.reconstructAt(
                handle,
                RiftMemoryQueryV1(
                    namespace = request.namespace,
                    projectId = request.projectId,
                    branch = request.branch
                ),
                validAt = request.validAt,
                recordedAt = request.recordedAt
            )
            temporal.records
                .filter {
                    it.scope.namespace == request.namespace &&
                        it.scope.projectId == request.projectId &&
                        it.branch == request.branch
                }
                .forEach { add(it, RiftMemoryRetrievalLaneV1.TEMPORAL) }
        }

        if (RiftMemoryRetrievalLaneV1.GRAPH in lanes) {
            addGraphHits(handle, request, records, byId) { record, lane, required, lexical, vector ->
                add(record, lane, required, lexical, vector)
            }
        }

        val queryTokens = tokenize(request.query)
        if (RiftMemoryRetrievalLaneV1.BM25 in lanes && queryTokens.isNotEmpty()) {
            val tokenized = records.associate { it.id to tokenize(documentText(it)) }
            val documentFrequency = linkedMapOf<String, Int>()
            queryTokens.distinct().forEach { token ->
                documentFrequency[token] = tokenized.values.count { token in it }
            }
            val n = maxOf(1, records.size)
            for (record in records) {
                val tokens = tokenized[record.id].orEmpty()
                if (tokens.isEmpty()) continue
                var score = 0.0
                for (token in queryTokens) {
                    val tf = tokens.count { it == token }
                    if (tf == 0) continue
                    val df = documentFrequency[token] ?: 0
                    val idf = ln((n + 1.0) / (df + 1.0)) + 1.0
                    score += tf.toDouble() * idf
                }
                val scaled = (score * 1_000.0).toInt().coerceIn(0, 100_000)
                if (scaled > 0) add(record, RiftMemoryRetrievalLaneV1.BM25, lexical = scaled)
            }
        }

        if (RiftMemoryRetrievalLaneV1.VECTOR in lanes && queryTokens.isNotEmpty()) {
            val queryVector = embed(queryTokens)
            for (record in records) {
                val score = vectorSimilarity(queryVector, embed(tokenize(documentText(record))))
                if (score > 0) add(record, RiftMemoryRetrievalLaneV1.VECTOR, vector = score)
            }
        }

        if (RiftMemoryRetrievalLaneV1.PROJECT in lanes && !projectOnlyFallback) {
            hits.values.forEach { hit -> hit.lanes += RiftMemoryRetrievalLaneV1.PROJECT }
        }

        val ordered = hits.values
            .map { row ->
                RiftMemoryRetrievalHitV1(
                    record = row.record,
                    lanes = row.lanes.toSet(),
                    requiredEvidence = row.requiredEvidence,
                    evidenceWeight = evidenceWeight(row.record),
                    lexicalScore = row.lexicalScore,
                    vectorScore = row.vectorScore
                )
            }
            .sortedWith(
                compareByDescending<RiftMemoryRetrievalHitV1> { it.requiredEvidence }
                    .thenByDescending { trustRank(it.record.trustState) }
                    .thenByDescending { it.evidenceWeight }
                    .thenByDescending { lanePriority(it.lanes) }
                    .thenByDescending { it.lexicalScore }
                    .thenByDescending { it.vectorScore }
                    .thenBy { it.record.id }
            )
            .take(request.maxResults)

        return RiftMemoryRetrievalResultV1(
            mode = request.mode,
            routedLanes = lanes,
            hits = ordered,
            complete = true,
            incompleteReasons = emptyList()
        )
    }

    fun compileContext(
        result: RiftMemoryRetrievalResultV1,
        tokenBudget: Int
    ): RiftMemoryCompiledContextV1 {
        require(tokenBudget in 1..MAX_CONTEXT_TOKENS)
        if (!result.complete) {
            return RiftMemoryCompiledContextV1(
                entries = emptyList(),
                estimatedTokens = 0,
                tokenBudget = tokenBudget,
                complete = false,
                truncated = false,
                incompleteReasons = result.incompleteReasons.ifEmpty { listOf("retrieval-incomplete") }
            )
        }

        val entries = mutableListOf<JSONObject>()
        var used = 0

        for (hit in result.hits.filter { it.requiredEvidence }) {
            val entry = contextEntry(hit)
            val cost = estimateTokens(entry.toString())
            if (used + cost > tokenBudget) {
                return RiftMemoryCompiledContextV1(
                    entries = emptyList(),
                    estimatedTokens = 0,
                    tokenBudget = tokenBudget,
                    complete = false,
                    truncated = false,
                    incompleteReasons = listOf("context-budget-insufficient-for-required-evidence")
                )
            }
            entries += entry
            used += cost
        }

        val requiredIds = result.hits.filter { it.requiredEvidence }.map { it.record.id }.toSet()
        var truncated = false
        for (hit in result.hits) {
            if (hit.record.id in requiredIds) continue
            val entry = contextEntry(hit)
            val cost = estimateTokens(entry.toString())
            if (used + cost > tokenBudget) {
                truncated = true
                continue
            }
            entries += entry
            used += cost
        }

        return RiftMemoryCompiledContextV1(
            entries = entries,
            estimatedTokens = used,
            tokenBudget = tokenBudget,
            complete = true,
            truncated = truncated,
            incompleteReasons = emptyList()
        )
    }

    private fun addGraphHits(
        handle: RiftMemoryStoreHandleV1,
        request: RiftMemoryRetrievalRequestV1,
        records: List<RiftCanonicalMemoryRecordV1>,
        byId: Map<String, RiftCanonicalMemoryRecordV1>,
        add: (RiftCanonicalMemoryRecordV1, RiftMemoryRetrievalLaneV1, Boolean, Int, Int) -> Unit
    ) {
        val projection = RiftMemoryTemporalGraphV1.rebuild(
            handle,
            RiftMemoryQueryV1(
                namespace = request.namespace,
                projectId = request.projectId,
                branch = request.branch
            )
        )
        val seeds = linkedSetOf<String>()
        request.exactRecordId?.let { id ->
            byId[id]?.let { seeds += versionNode(it) }
        }
        request.entityId?.let { entityId ->
            records
                .filter { it.payload.optString("entityId") == entityId }
                .forEach { seeds += versionNode(it) }
        }

        for (edge in projection.edges) {
            if (request.entityId != null && edge.target == "entity:" + request.entityId) {
                parseRecordId(edge.source)?.let { id ->
                    byId[id]?.let { add(it, RiftMemoryRetrievalLaneV1.GRAPH, false, 0, 0) }
                }
            }
            if (edge.source in seeds) {
                parseRecordId(edge.target)?.let { id ->
                    byId[id]?.let { add(it, RiftMemoryRetrievalLaneV1.GRAPH, false, 0, 0) }
                }
            }
            if (edge.target in seeds ||
                (request.exactRecordId != null && edge.target == "record:" + request.exactRecordId)
            ) {
                parseRecordId(edge.source)?.let { id ->
                    byId[id]?.let { add(it, RiftMemoryRetrievalLaneV1.GRAPH, false, 0, 0) }
                }
            }
        }
    }

    private fun readProjectRecords(
        handle: RiftMemoryStoreHandleV1,
        request: RiftMemoryRetrievalRequestV1
    ): List<RiftCanonicalMemoryRecordV1> {
        val out = mutableListOf<RiftCanonicalMemoryRecordV1>()
        var offset = 0
        while (offset < MAX_PROJECT_RECORDS) {
            val limit = minOf(1_000, MAX_PROJECT_RECORDS - offset)
            val page = handle.scanCanonicalRecords(
                RiftMemoryQueryV1(
                    namespace = request.namespace,
                    projectId = request.projectId,
                    branch = request.branch
                ),
                RiftMemoryBoundsV1(offset = offset, limit = limit)
            )
            out += page.records
            offset += page.records.size
            if (page.records.size < limit) return out
        }
        val overflow = handle.scanCanonicalRecords(
            RiftMemoryQueryV1(
                namespace = request.namespace,
                projectId = request.projectId,
                branch = request.branch
            ),
            RiftMemoryBoundsV1(offset = MAX_PROJECT_RECORDS, limit = 1)
        ).records.isNotEmpty()
        check(!overflow) { "retrieval-project-record-bound-exceeded:$MAX_PROJECT_RECORDS" }
        return out
    }

    private fun contextEntry(hit: RiftMemoryRetrievalHitV1): JSONObject {
        val lanes = JSONArray()
        hit.lanes.map { it.name }.sorted().forEach { lane -> lanes.put(lane) }
        return JSONObject()
            .put("recordId", hit.record.id)
            .put("kind", hit.record.kind.name)
            .put("trustState", hit.record.trustState.name)
            .put("payloadProjection", boundedProjection(hit.record.payload))
            .put("evidenceRefs", JSONArray(hit.record.evidenceRefs))
            .put("lanes", lanes)
            .put("requiredEvidence", hit.requiredEvidence)
    }

    private fun estimateTokens(text: String): Int =
        maxOf(1, (text.length + 3) / 4)

    private fun documentText(record: RiftCanonicalMemoryRecordV1): String =
        boundedProjection(
            record.payload,
            listOf(record.id, record.kind.name, record.trustState.name)
        )

    private fun boundedProjection(
        payload: JSONObject,
        prefix: List<String> = emptyList()
    ): String {
        val out = StringBuilder()
        var nodes = 0

        fun appendToken(value: String) {
            check(value.length <= MAX_DOCUMENT_SCALAR_CHARS) {
                "retrieval-document-scalar-bound-exceeded:$MAX_DOCUMENT_SCALAR_CHARS"
            }
            val added = value.length + if (out.length == 0) 0 else 1
            check(out.length + added <= MAX_DOCUMENT_CHARS) {
                "retrieval-document-char-bound-exceeded:$MAX_DOCUMENT_CHARS"
            }
            if (out.length > 0) out.append(' ')
            out.append(value)
        }

        fun visit(value: Any?, depth: Int) {
            check(depth <= MAX_DOCUMENT_DEPTH) {
                "retrieval-document-depth-bound-exceeded:$MAX_DOCUMENT_DEPTH"
            }
            nodes += 1
            check(nodes <= MAX_DOCUMENT_NODES) {
                "retrieval-document-node-bound-exceeded:$MAX_DOCUMENT_NODES"
            }
            when (value) {
                null,
                JSONObject.NULL -> Unit
                is String -> appendToken(value)
                is Number,
                is Boolean -> appendToken(value.toString())
                is JSONObject -> {
                    check(value.length() <= MAX_DOCUMENT_FIELDS) {
                        "retrieval-document-field-bound-exceeded:$MAX_DOCUMENT_FIELDS"
                    }
                    val keys = mutableListOf<String>()
                    val iterator = value.keys()
                    while (iterator.hasNext()) keys += iterator.next()
                    keys.sorted().forEach { key ->
                        appendToken(key)
                        visit(value.opt(key), depth + 1)
                    }
                }
                is JSONArray -> {
                    check(value.length() <= MAX_DOCUMENT_ARRAY_ITEMS) {
                        "retrieval-document-array-bound-exceeded:$MAX_DOCUMENT_ARRAY_ITEMS"
                    }
                    for (index in 0 until value.length()) {
                        visit(value.opt(index), depth + 1)
                    }
                }
                else -> throw IllegalArgumentException(
                    "retrieval-document-value-unsupported:" + value::class.java.simpleName
                )
            }
        }

        prefix.forEach { appendToken(it) }
        visit(payload, 0)
        return out.toString()
    }

    private fun tokenize(text: String): List<String> =
        Regex("[A-Za-z0-9_.:/-]+")
            .findAll(text.lowercase())
            .map { it.value }
            .filter { it.isNotBlank() }
            .take(MAX_QUERY_TOKENS)
            .toList()

    private fun embed(tokens: List<String>): IntArray {
        val vector = IntArray(VECTOR_DIMENSIONS)
        for (token in tokens.take(MAX_QUERY_TOKENS)) {
            val hash = token.hashCode()
            val index = (hash and Int.MAX_VALUE) % VECTOR_DIMENSIONS
            val sign = if (((hash ushr 8) and 1) == 0) 1 else -1
            vector[index] += sign
        }
        return vector
    }

    private fun vectorSimilarity(a: IntArray, b: IntArray): Int {
        var dot = 0L
        var normA = 0L
        var normB = 0L
        for (index in 0 until VECTOR_DIMENSIONS) {
            dot += a[index].toLong() * b[index].toLong()
            normA += a[index].toLong() * a[index].toLong()
            normB += b[index].toLong() * b[index].toLong()
        }
        if (dot <= 0L || normA == 0L || normB == 0L) return 0
        val cosine = dot.toDouble() / (sqrt(normA.toDouble()) * sqrt(normB.toDouble()))
        return (cosine * 1_000.0).toInt().coerceIn(0, 1_000)
    }

    private fun evidenceWeight(record: RiftCanonicalMemoryRecordV1): Int =
        trustRank(record.trustState) * 1_000 + minOf(record.evidenceRefs.size, 999)

    private fun lanePriority(lanes: Set<RiftMemoryRetrievalLaneV1>): Int = lanes.maxOfOrNull {
        when (it) {
            RiftMemoryRetrievalLaneV1.EXACT -> 700
            RiftMemoryRetrievalLaneV1.ENTITY -> 600
            RiftMemoryRetrievalLaneV1.TEMPORAL -> 500
            RiftMemoryRetrievalLaneV1.GRAPH -> 400
            RiftMemoryRetrievalLaneV1.PROJECT -> 300
            RiftMemoryRetrievalLaneV1.BM25 -> 200
            RiftMemoryRetrievalLaneV1.VECTOR -> 100
        }
    } ?: 0

    private fun trustRank(state: RiftMemoryTrustStateV1): Int = when (state) {
        RiftMemoryTrustStateV1.VERIFIED -> 5
        RiftMemoryTrustStateV1.TRUSTED -> 4
        RiftMemoryTrustStateV1.PROVISIONAL -> 2
        RiftMemoryTrustStateV1.UNVERIFIED -> 1
        RiftMemoryTrustStateV1.CONFLICTED,
        RiftMemoryTrustStateV1.QUARANTINED,
        RiftMemoryTrustStateV1.SUPERSEDED,
        RiftMemoryTrustStateV1.INVALIDATED,
        RiftMemoryTrustStateV1.HISTORICAL,
        RiftMemoryTrustStateV1.ARCHIVED -> 0
    }

    private fun versionNode(record: RiftCanonicalMemoryRecordV1): String =
        "record:" + record.id + "@" + RiftMemoryModelV1.canonicalSha256(record.toJson())

    private fun parseRecordId(node: String): String? {
        if (!node.startsWith("record:")) return null
        return node.removePrefix("record:").substringBefore("@").takeIf { it.isNotBlank() }
    }

    private fun isVisible(record: RiftCanonicalMemoryRecordV1): Boolean =
        record.trustState !in setOf(
            RiftMemoryTrustStateV1.CONFLICTED,
            RiftMemoryTrustStateV1.QUARANTINED,
            RiftMemoryTrustStateV1.SUPERSEDED,
            RiftMemoryTrustStateV1.INVALIDATED,
            RiftMemoryTrustStateV1.HISTORICAL,
            RiftMemoryTrustStateV1.ARCHIVED
        )
}
