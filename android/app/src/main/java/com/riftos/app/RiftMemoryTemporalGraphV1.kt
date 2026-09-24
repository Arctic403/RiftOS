package com.riftos.app

import org.json.JSONArray
import org.json.JSONObject

enum class RiftMemoryGraphEdgeTypeV1 {
    ENTITY,
    DEPENDENCY,
    SUPERSEDES,
    INVALIDATES,
    CONTRADICTS,
    PROVENANCE
}

data class RiftMemoryGraphEdgeV1(
    val type: RiftMemoryGraphEdgeTypeV1,
    val source: String,
    val target: String,
    val at: Long
) {
    init {
        require(source.isNotBlank() && source.length <= 512)
        require(target.isNotBlank() && target.length <= 512)
        require(at >= 0L)
    }

    fun stableKey(): String = "${type.name}|$source|$target|$at"

    fun toJson(): JSONObject = JSONObject()
        .put("type", type.name)
        .put("source", source)
        .put("target", target)
        .put("at", at)
}

data class RiftMemoryProjectionScopeV1(
    val namespace: String?,
    val projectId: String?,
    val branch: RiftMemoryBranchV1?
) {
    fun toJson(): JSONObject = JSONObject()
        .put("namespace", namespace ?: JSONObject.NULL)
        .put("projectId", projectId ?: JSONObject.NULL)
        .put("branch", branch?.name ?: JSONObject.NULL)

    fun toQuery(): RiftMemoryQueryV1 = RiftMemoryQueryV1(
        namespace = namespace,
        projectId = projectId,
        branch = branch
    )

    companion object {
        fun fromQuery(query: RiftMemoryQueryV1): RiftMemoryProjectionScopeV1 {
            require(query.id == null && query.recordId == null && query.evidenceId == null && query.kind == null) {
                "Temporal projection scope only supports namespace/projectId/branch."
            }
            return RiftMemoryProjectionScopeV1(
                namespace = query.namespace,
                projectId = query.projectId,
                branch = query.branch
            )
        }
    }
}

data class RiftMemoryTemporalGraphProjectionV1(
    val scope: RiftMemoryProjectionScopeV1,
    val currentRecords: List<RiftCanonicalMemoryRecordV1>,
    val edges: List<RiftMemoryGraphEdgeV1>,
    val eventSequence: Long,
    val builtAt: Long,
    val sourceRecordCount: Int,
    val sourceEventCount: Int,
    val dirtyProjectionStateSha256: String,
    val canonicalSha256: String
) {
    fun toJson(): JSONObject {
        val recordsJson = JSONArray()
        currentRecords
            .sortedWith(compareBy<RiftCanonicalMemoryRecordV1>({ it.scope.namespace }, { it.scope.projectId ?: "" }, { it.branch.name }, { it.id }))
            .forEach { recordsJson.put(it.toJson()) }
        val edgesJson = JSONArray()
        edges.sortedBy { it.stableKey() }.forEach { edgesJson.put(it.toJson()) }
        return JSONObject()
            .put("schema", RiftMemoryTemporalGraphV1.PROJECTION_SCHEMA)
            .put("scope", scope.toJson())
            .put("currentRecords", recordsJson)
            .put("edges", edgesJson)
            .put("eventSequence", eventSequence)
            .put("sourceRecordCount", sourceRecordCount)
            .put("sourceEventCount", sourceEventCount)
            .put("dirtyProjectionStateSha256", dirtyProjectionStateSha256)
            .put("canonicalSha256", canonicalSha256)
    }
}

data class RiftMemoryPointInTimeResultV1(
    val scope: RiftMemoryProjectionScopeV1,
    val validAt: Long,
    val recordedAt: Long,
    val records: List<RiftCanonicalMemoryRecordV1>,
    val sourceEventCount: Int,
    val canonicalSha256: String
) {
    fun toJson(): JSONObject {
        val array = JSONArray()
        records
            .sortedWith(compareBy<RiftCanonicalMemoryRecordV1>({ it.scope.namespace }, { it.scope.projectId ?: "" }, { it.branch.name }, { it.id }))
            .forEach { array.put(it.toJson()) }
        return JSONObject()
            .put("schema", RiftMemoryTemporalGraphV1.POINT_IN_TIME_SCHEMA)
            .put("scope", scope.toJson())
            .put("validAt", validAt)
            .put("recordedAt", recordedAt)
            .put("records", array)
            .put("sourceEventCount", sourceEventCount)
            .put("canonicalSha256", canonicalSha256)
    }
}

object RiftMemoryTemporalGraphV1 {
    const val PROJECTION_SCHEMA = "rift-memory-temporal-graph-v1"
    const val POINT_IN_TIME_SCHEMA = "rift-memory-point-in-time-v1"
    const val MAX_CURRENT_RECORDS = 4_096
    const val MAX_EVENTS = 8_192
    const val MAX_DIRTY_PROJECTIONS = 4_096
    const val MAX_DEPENDENCIES_PER_RECORD = 256

    fun rebuild(
        handle: RiftMemoryStoreHandleV1,
        scope: RiftMemoryQueryV1 = RiftMemoryQueryV1()
    ): RiftMemoryTemporalGraphProjectionV1 {
        val projectionScope = RiftMemoryProjectionScopeV1.fromQuery(scope)
        val scopedQuery = projectionScope.toQuery()
        val allCurrent = readAllCurrent(handle, scopedQuery)
        val events = readAllEvents(handle, scopedQuery)
        val visibleCurrent = allCurrent.filter(::isCurrentVisible)
        val edges = LinkedHashMap<String, RiftMemoryGraphEdgeV1>()

        for (row in events) {
            val event = row.event
            when (event.optString("type")) {
                RiftMemoryReconciliationV1.EVENT_RECORD_VERSION -> {
                    val recordJson = event.optJSONObject("payload")?.optJSONObject("record") ?: continue
                    val record = RiftCanonicalMemoryRecordV1.fromJson(recordJson)
                    addRecordEdges(edges, record)
                }
                RiftMemoryReconciliationV1.EVENT_SUPERSESSION -> {
                    val payload = event.optJSONObject("payload") ?: continue
                    addEdge(
                        edges,
                        RiftMemoryGraphEdgeV1(
                            RiftMemoryGraphEdgeTypeV1.SUPERSEDES,
                            payload.getString("previousVersionNode"),
                            payload.getString("nextVersionNode"),
                            event.getLong("at")
                        )
                    )
                }
                RiftMemoryReconciliationV1.EVENT_INVALIDATION -> {
                    val payload = event.optJSONObject("payload") ?: continue
                    addEdge(
                        edges,
                        RiftMemoryGraphEdgeV1(
                            RiftMemoryGraphEdgeTypeV1.INVALIDATES,
                            payload.getString("previousVersionNode"),
                            payload.getString("invalidatedVersionNode"),
                            event.getLong("at")
                        )
                    )
                }
                RiftMemoryReconciliationV1.EVENT_CONTRADICTION -> {
                    val payload = event.optJSONObject("payload") ?: continue
                    val currentNode = payload.optString("currentVersionNode").takeIf { it.isNotBlank() }
                    val candidateNode = payload.optString("candidateVersionNode").takeIf { it.isNotBlank() }
                    if (currentNode != null && candidateNode != null) {
                        addEdge(
                            edges,
                            RiftMemoryGraphEdgeV1(
                                RiftMemoryGraphEdgeTypeV1.CONTRADICTS,
                                candidateNode,
                                currentNode,
                                event.getLong("at")
                            )
                        )
                    }
                }
            }
        }

        val eventSequence = events.maxOfOrNull { it.sequence } ?: 0L
        val canonical = canonicalProjectionPayload(projectionScope, visibleCurrent, edges.values.toList(), eventSequence, allCurrent.size, events.size)
        val hash = RiftMemoryModelV1.canonicalSha256(canonical)
        val dirtyStateHash = dirtyProjectionStateSha256(handle)
        val builtAt = System.currentTimeMillis()
        return RiftMemoryTemporalGraphProjectionV1(
            scope = projectionScope,
            currentRecords = visibleCurrent,
            edges = edges.values.sortedBy { it.stableKey() },
            eventSequence = eventSequence,
            builtAt = builtAt,
            sourceRecordCount = allCurrent.size,
            sourceEventCount = events.size,
            dirtyProjectionStateSha256 = dirtyStateHash,
            canonicalSha256 = hash
        )
    }

    fun reconstructAt(
        handle: RiftMemoryStoreHandleV1,
        scope: RiftMemoryQueryV1,
        validAt: Long,
        recordedAt: Long
    ): RiftMemoryPointInTimeResultV1 {
        require(validAt >= 0L && recordedAt >= 0L)
        val projectionScope = RiftMemoryProjectionScopeV1.fromQuery(scope)
        val events = readAllEvents(handle, projectionScope.toQuery())
        data class VersionRow(
            val sequence: Long,
            val record: RiftCanonicalMemoryRecordV1
        )
        val selected = LinkedHashMap<String, VersionRow>()
        for (row in events) {
            val event = row.event
            if (event.optString("type") != RiftMemoryReconciliationV1.EVENT_RECORD_VERSION) continue
            val recordJson = event.optJSONObject("payload")?.optJSONObject("record") ?: continue
            val record = RiftCanonicalMemoryRecordV1.fromJson(recordJson)
            if (record.time.recordedAt > recordedAt) continue
            if (record.time.validFrom > validAt) continue
            if (record.time.validTo?.let { validAt >= it } == true) continue
            val identity = logicalIdentity(record)
            val prior = selected[identity]
            if (prior == null ||
                record.time.recordedAt > prior.record.time.recordedAt ||
                (record.time.recordedAt == prior.record.time.recordedAt && row.sequence > prior.sequence)) {
                selected[identity] = VersionRow(row.sequence, record)
            }
        }
        val records = selected.values.map { it.record }
        val canonical = canonicalPointInTimePayload(projectionScope, validAt, recordedAt, records, events.size)
        return RiftMemoryPointInTimeResultV1(
            scope = projectionScope,
            validAt = validAt,
            recordedAt = recordedAt,
            records = records,
            sourceEventCount = events.size,
            canonicalSha256 = RiftMemoryModelV1.canonicalSha256(canonical)
        )
    }

    fun isFresh(
        handle: RiftMemoryStoreHandleV1,
        projection: RiftMemoryTemporalGraphProjectionV1
    ): Boolean {
        val rebuilt = rebuild(handle, projection.scope.toQuery())
        return rebuilt.canonicalSha256 == projection.canonicalSha256 &&
            rebuilt.eventSequence == projection.eventSequence &&
            rebuilt.sourceRecordCount == projection.sourceRecordCount &&
            rebuilt.sourceEventCount == projection.sourceEventCount &&
            rebuilt.dirtyProjectionStateSha256 == projection.dirtyProjectionStateSha256
    }

    private fun dirtyProjectionStateSha256(handle: RiftMemoryStoreHandleV1): String {
        val rows = mutableListOf<RiftMemoryDirtyProjectionV1>()
        var offset = 0
        var exhausted = false
        while (offset < MAX_DIRTY_PROJECTIONS) {
            val limit = minOf(1_000, MAX_DIRTY_PROJECTIONS - offset)
            val page = handle.listDirtyProjections(
                RiftMemoryQueryV1(),
                RiftMemoryBoundsV1(offset = offset, limit = limit)
            )
            rows += page
            offset += page.size
            if (page.size < limit) {
                exhausted = true
                break
            }
        }
        if (!exhausted && offset == MAX_DIRTY_PROJECTIONS) {
            val overflow = handle.listDirtyProjections(
                RiftMemoryQueryV1(),
                RiftMemoryBoundsV1(offset = MAX_DIRTY_PROJECTIONS, limit = 1)
            ).isNotEmpty()
            check(!overflow) { "temporal-dirty-projection-bound-exceeded:$MAX_DIRTY_PROJECTIONS" }
        }
        val relevant = rows
            .filter {
                it.name == RiftMemoryReconciliationV1.PROJECTION_CURRENT ||
                    it.name == RiftMemoryReconciliationV1.PROJECTION_TEMPORAL_GRAPH
            }
            .sortedBy { it.name }
        val array = JSONArray()
        relevant.forEach {
            array.put(
                JSONObject()
                    .put("name", it.name)
                    .put("reason", it.reason)
                    .put("updatedAt", it.updatedAt)
            )
        }
        return RiftMemoryModelV1.canonicalSha256(
            JSONObject()
                .put("schema", "rift-memory-projection-dirty-state-v1")
                .put("rows", array)
        )
    }

    private fun readAllCurrent(
        handle: RiftMemoryStoreHandleV1,
        scope: RiftMemoryQueryV1
    ): List<RiftCanonicalMemoryRecordV1> {
        val rows = mutableListOf<RiftCanonicalMemoryRecordV1>()
        var offset = 0
        while (offset < MAX_CURRENT_RECORDS) {
            val limit = minOf(1_000, MAX_CURRENT_RECORDS - offset)
            val page = handle.scanCanonicalRecords(scope, RiftMemoryBoundsV1(offset = offset, limit = limit))
            rows += page.records
            offset += page.records.size
            if (page.records.size < limit) return rows
        }
        val overflow = handle.scanCanonicalRecords(
            scope,
            RiftMemoryBoundsV1(offset = MAX_CURRENT_RECORDS, limit = 1)
        ).records.isNotEmpty()
        check(!overflow) { "temporal-current-record-bound-exceeded:$MAX_CURRENT_RECORDS" }
        return rows
    }

    private fun readAllEvents(
        handle: RiftMemoryStoreHandleV1,
        scope: RiftMemoryQueryV1
    ): List<RiftMemoryEventRowV1> {
        val rows = mutableListOf<RiftMemoryEventRowV1>()
        var offset = 0
        while (offset < MAX_EVENTS) {
            val limit = minOf(1_000, MAX_EVENTS - offset)
            val page = handle.readEvents(scope, RiftMemoryBoundsV1(offset = offset, limit = limit))
            rows += page.events
            offset += page.events.size
            if (page.events.size < limit) return rows
        }
        val overflow = handle.readEvents(
            scope,
            RiftMemoryBoundsV1(offset = MAX_EVENTS, limit = 1)
        ).events.isNotEmpty()
        check(!overflow) { "temporal-event-bound-exceeded:$MAX_EVENTS" }
        return rows
    }

    private fun isCurrentVisible(record: RiftCanonicalMemoryRecordV1): Boolean =
        record.trustState !in setOf(
            RiftMemoryTrustStateV1.SUPERSEDED,
            RiftMemoryTrustStateV1.INVALIDATED,
            RiftMemoryTrustStateV1.HISTORICAL,
            RiftMemoryTrustStateV1.ARCHIVED,
            RiftMemoryTrustStateV1.QUARANTINED
        )

    private fun addRecordEdges(
        edges: MutableMap<String, RiftMemoryGraphEdgeV1>,
        record: RiftCanonicalMemoryRecordV1
    ) {
        val source = versionNode(record)
        for (evidenceId in record.evidenceRefs.sorted()) {
            addEdge(
                edges,
                RiftMemoryGraphEdgeV1(
                    RiftMemoryGraphEdgeTypeV1.PROVENANCE,
                    source,
                    "evidence:$evidenceId",
                    record.time.recordedAt
                )
            )
        }
        val entityId = record.payload.optString("entityId").takeIf { it.isNotBlank() }
        if (entityId != null) {
            require(entityId.length <= 256)
            addEdge(
                edges,
                RiftMemoryGraphEdgeV1(
                    RiftMemoryGraphEdgeTypeV1.ENTITY,
                    source,
                    "entity:$entityId",
                    record.time.recordedAt
                )
            )
        }
        val dependencies = record.payload.optJSONArray("dependsOn")
        if (dependencies != null) {
            check(dependencies.length() <= MAX_DEPENDENCIES_PER_RECORD) {
                "temporal-dependency-bound-exceeded:$MAX_DEPENDENCIES_PER_RECORD"
            }
            for (i in 0 until dependencies.length()) {
                val target = dependencies.getString(i)
                RiftMemoryModelV1.requireId(target)
                addEdge(
                    edges,
                    RiftMemoryGraphEdgeV1(
                        RiftMemoryGraphEdgeTypeV1.DEPENDENCY,
                        source,
                        "record:$target",
                        record.time.recordedAt
                    )
                )
            }
        }
    }

    private fun addEdge(
        edges: MutableMap<String, RiftMemoryGraphEdgeV1>,
        edge: RiftMemoryGraphEdgeV1
    ) {
        edges[edge.stableKey()] = edge
    }

    private fun versionNode(record: RiftCanonicalMemoryRecordV1): String =
        "record:${record.id}@" + RiftMemoryModelV1.canonicalSha256(record.toJson())

    private fun logicalIdentity(record: RiftCanonicalMemoryRecordV1): String =
        listOf(
            record.scope.namespace,
            record.scope.workspaceId ?: "",
            record.scope.projectId ?: "",
            record.branch.name,
            record.id
        ).joinToString("|")

    private fun canonicalProjectionPayload(
        scope: RiftMemoryProjectionScopeV1,
        current: List<RiftCanonicalMemoryRecordV1>,
        edges: List<RiftMemoryGraphEdgeV1>,
        eventSequence: Long,
        sourceRecordCount: Int,
        sourceEventCount: Int
    ): JSONObject {
        val recordsJson = JSONArray()
        current
            .sortedWith(compareBy<RiftCanonicalMemoryRecordV1>({ it.scope.namespace }, { it.scope.projectId ?: "" }, { it.branch.name }, { it.id }))
            .forEach { recordsJson.put(it.toJson()) }
        val edgesJson = JSONArray()
        edges.sortedBy { it.stableKey() }.forEach { edgesJson.put(it.toJson()) }
        return JSONObject()
            .put("schema", PROJECTION_SCHEMA)
            .put("scope", scope.toJson())
            .put("currentRecords", recordsJson)
            .put("edges", edgesJson)
            .put("eventSequence", eventSequence)
            .put("sourceRecordCount", sourceRecordCount)
            .put("sourceEventCount", sourceEventCount)
    }

    private fun canonicalPointInTimePayload(
        scope: RiftMemoryProjectionScopeV1,
        validAt: Long,
        recordedAt: Long,
        records: List<RiftCanonicalMemoryRecordV1>,
        sourceEventCount: Int
    ): JSONObject {
        val array = JSONArray()
        records
            .sortedWith(compareBy<RiftCanonicalMemoryRecordV1>({ it.scope.namespace }, { it.scope.projectId ?: "" }, { it.branch.name }, { it.id }))
            .forEach { array.put(it.toJson()) }
        return JSONObject()
            .put("schema", POINT_IN_TIME_SCHEMA)
            .put("scope", scope.toJson())
            .put("validAt", validAt)
            .put("recordedAt", recordedAt)
            .put("records", array)
            .put("sourceEventCount", sourceEventCount)
    }
}
