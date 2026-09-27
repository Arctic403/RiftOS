package com.riftos.app

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class RiftMcpOperationContext(
    val operationId: String,
    val requestId: String,
    val requestHash: String,
    val modelCallId: String?,
    val tool: String,
    val mutating: Boolean,
    val startSequence: Long
)

class RiftMcpOperationJournal(context: Context) {
    companion object {
        private const val SCHEMA = "rift.mcp-operation-journal/1"
        private const val MAX_ENTRIES = 256
        private const val DEFAULT_QUERY_LIMIT = 20
        private const val MAX_QUERY_LIMIT = 64
        private const val MAX_STORE_BYTES = 1024 * 1024
        private const val MAX_ID_CHARS = 256
        private const val MAX_TOOL_CHARS = 120
        private const val MAX_ERROR_CHARS = 400
        private const val MAX_DETAIL_KEYS = 16
        private const val MAX_DETAIL_KEY_CHARS = 64
        private const val MAX_DETAIL_VALUE_CHARS = 256
        private val TERMINAL = setOf("succeeded", "failed", "interrupted_on_restart")
        private val DELIVERY = setOf("unknown", "queued_to_relay", "response_not_delivered")
        private val SENSITIVE_DETAIL_KEY = Regex("(?i)(token|secret|password|authorization|cookie|credential|key)")
    }

    data class BeginResult(
        val context: RiftMcpOperationContext,
        val deduplicated: Boolean,
        val terminalSnapshot: JSONObject?
    )

    private data class Entry(
        val operationId: String,
        val requestId: String,
        val requestHash: String,
        val modelCallId: String?,
        val tool: String,
        val mutating: Boolean,
        val startedAtMs: Long,
        val startSequence: Long,
        var lastSequence: Long,
        var status: String,
        var completedAtMs: Long?,
        var delivery: String,
        var deliveryAtMs: Long?,
        var error: String?,
        var details: JSONObject?
    )

    private val lock = Any()
    private val file = AtomicFile(
        File(context.applicationContext.filesDir, "rift-mcp-operation-journal").apply { mkdirs() }
            .resolve("journal-v1.json")
    )
    private val entries = LinkedHashMap<String, Entry>()
    private var sequence = 0L
    private var loaded = false

    fun begin(
        requestId: String,
        requestHash: String,
        modelCallId: String?,
        tool: String,
        mutating: Boolean
    ): BeginResult = synchronized(lock) {
        ensureLoadedLocked()
        val cleanRequestId = boundedId(requestId, "requestId")
        require(requestHash.matches(Regex("^[0-9a-f]{64}$"))) { "Invalid MCP request hash" }
        val cleanTool = tool.trim().take(MAX_TOOL_CHARS).ifBlank { "unknown" }
        val cleanModelCallId = modelCallId?.trim()?.take(MAX_ID_CHARS)?.takeIf { it.isNotBlank() }

        entries.values.firstOrNull { it.requestId == cleanRequestId }?.let { existing ->
            require(existing.requestHash == requestHash) {
                "MCP requestId is already bound to a different request hash"
            }
            require(existing.tool == cleanTool) {
                "MCP requestId is already bound to a different tool"
            }
            return@synchronized BeginResult(
                context = context(existing),
                deduplicated = true,
                terminalSnapshot = if (existing.status in TERMINAL) snapshot(existing) else null
            )
        }

        pruneLocked(makeRoom = true)
        require(entries.size < MAX_ENTRIES) {
            "MCP operation journal is full with non-terminal operations"
        }
        val now = System.currentTimeMillis()
        val seq = nextSequenceLocked()
        val entry = Entry(
            operationId = "mcp-${UUID.randomUUID()}",
            requestId = cleanRequestId,
            requestHash = requestHash,
            modelCallId = cleanModelCallId,
            tool = cleanTool,
            mutating = mutating,
            startedAtMs = now,
            startSequence = seq,
            lastSequence = seq,
            status = "running",
            completedAtMs = null,
            delivery = "unknown",
            deliveryAtMs = null,
            error = null,
            details = null
        )
        entries[entry.operationId] = entry
        persistLocked()
        BeginResult(context(entry), deduplicated = false, terminalSnapshot = null)
    }

    fun complete(
        operationId: String,
        ok: Boolean,
        error: String?,
        details: JSONObject? = null
    ): JSONObject = synchronized(lock) {
        ensureLoadedLocked()
        val entry = entries[operationId]
            ?: throw IllegalArgumentException("Unknown MCP operation: $operationId")
        if (entry.status in TERMINAL) return@synchronized snapshot(entry)
        entry.status = if (ok) "succeeded" else "failed"
        entry.completedAtMs = System.currentTimeMillis()
        entry.error = error?.trim()?.take(MAX_ERROR_CHARS)?.takeIf { it.isNotBlank() }
        entry.details = sanitizeDetails(details)
        entry.lastSequence = nextSequenceLocked()
        persistLocked()
        snapshot(entry)
    }

    fun markDelivery(operationId: String, delivery: String): JSONObject? = synchronized(lock) {
        ensureLoadedLocked()
        require(delivery in DELIVERY && delivery != "unknown") { "Invalid MCP delivery state" }
        val entry = entries[operationId] ?: return@synchronized null
        if (entry.delivery == delivery) return@synchronized snapshot(entry)
        if (entry.delivery == "queued_to_relay" && delivery == "response_not_delivered") {
            return@synchronized snapshot(entry)
        }
        entry.delivery = delivery
        entry.deliveryAtMs = System.currentTimeMillis()
        entry.lastSequence = nextSequenceLocked()
        persistLocked()
        snapshot(entry)
    }

    fun query(args: JSONObject = JSONObject()): JSONObject = synchronized(lock) {
        ensureLoadedLocked()
        val hasCursor = args.has("sinceSequence")
        val sinceSequence = args.optLong("sinceSequence", 0L).coerceAtLeast(0L)
        val limit = args.optInt("limit", DEFAULT_QUERY_LIMIT).coerceIn(1, MAX_QUERY_LIMIT)
        val operationId = args.optString("operationId").trim().takeIf { it.isNotBlank() }
        val requestId = args.optString("requestId").trim().takeIf { it.isNotBlank() }

        var rows = entries.values.asSequence()
        if (operationId != null) rows = rows.filter { it.operationId == operationId }
        if (requestId != null) rows = rows.filter { it.requestId == requestId }

        val selected = if (hasCursor) {
            rows.filter { it.lastSequence > sinceSequence }
                .sortedBy { it.lastSequence }
                .take(limit)
                .toList()
        } else {
            rows.sortedByDescending { it.lastSequence }
                .take(limit)
                .toList()
                .reversed()
        }

        val retainedFrom = entries.values.minOfOrNull { it.startSequence } ?: sequence
        JSONObject()
            .put("schema", SCHEMA)
            .put("latestSequence", sequence)
            .put("retainedFromSequence", retainedFrom)
            .put("cursorApplied", hasCursor)
            .put("sinceSequence", if (hasCursor) sinceSequence else JSONObject.NULL)
            .put("returned", selected.size)
            .put("operations", JSONArray().apply { selected.forEach { put(snapshot(it)) } })
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        if (!file.baseFile.isFile) {
            loaded = true
            return
        }
        require(file.baseFile.length() <= MAX_STORE_BYTES) {
            "MCP operation journal exceeds $MAX_STORE_BYTES bytes"
        }
        val root = JSONObject(String(file.readFully(), Charsets.UTF_8))
        require(root.optString("schema") == SCHEMA) { "Unsupported MCP operation journal schema" }
        sequence = root.optLong("latestSequence", 0L).coerceAtLeast(0L)
        val rows = root.optJSONArray("operations") ?: JSONArray()
        require(rows.length() <= MAX_ENTRIES) { "MCP operation journal contains too many entries" }
        val seenRequestIds = LinkedHashSet<String>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index)
                ?: throw IllegalArgumentException("MCP operation journal row $index must be an object")
            val operationId = boundedId(row.optString("operationId"), "operationId")
            val requestId = boundedId(row.optString("requestId"), "requestId")
            require(seenRequestIds.add(requestId)) { "Duplicate MCP journal requestId: $requestId" }
            val requestHash = row.optString("requestHash")
            require(requestHash.matches(Regex("^[0-9a-f]{64}$"))) { "Invalid MCP journal request hash" }
            val tool = row.optString("tool").trim().take(MAX_TOOL_CHARS)
            require(tool.isNotBlank()) { "Invalid MCP journal tool" }
            val status = row.optString("status")
            require(status == "running" || status in TERMINAL) { "Invalid MCP journal status" }
            val delivery = row.optString("delivery", "unknown")
            require(delivery in DELIVERY) { "Invalid MCP journal delivery state" }
            val entry = Entry(
                operationId = operationId,
                requestId = requestId,
                requestHash = requestHash,
                modelCallId = row.optString("modelCallId").trim().takeIf { it.isNotBlank() },
                tool = tool,
                mutating = row.optBoolean("mutating", false),
                startedAtMs = row.optLong("startedAtMs", 0L),
                startSequence = row.optLong("startSequence", 0L),
                lastSequence = row.optLong("lastSequence", 0L),
                status = status,
                completedAtMs = row.optLong("completedAtMs", 0L).takeIf { it > 0L },
                delivery = delivery,
                deliveryAtMs = row.optLong("deliveryAtMs", 0L).takeIf { it > 0L },
                error = row.optString("error").take(MAX_ERROR_CHARS).takeIf { it.isNotBlank() },
                details = row.optJSONObject("details")?.let(::sanitizeDetails)
            )
            require(entry.startSequence > 0L && entry.lastSequence >= entry.startSequence) {
                "Invalid MCP journal sequence"
            }
            sequence = maxOf(sequence, entry.lastSequence)
            require(entries.put(operationId, entry) == null) { "Duplicate MCP journal operationId: $operationId" }
        }

        var changed = false
        entries.values.filter { it.status == "running" }.forEach { entry ->
            entry.status = "interrupted_on_restart"
            entry.completedAtMs = System.currentTimeMillis()
            entry.error = "Execution was in progress when the RiftOS process restarted; effects may have applied. Reconcile before retrying."
            entry.lastSequence = nextSequenceLocked()
            changed = true
        }
        pruneLocked()
        loaded = true
        if (changed) persistLocked()
    }

    private fun persistLocked() {
        val root = JSONObject()
            .put("schema", SCHEMA)
            .put("latestSequence", sequence)
            .put("operations", JSONArray().apply {
                entries.values.sortedBy { it.startSequence }.forEach { put(snapshot(it)) }
            })
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STORE_BYTES) {
            "MCP operation journal exceeds $MAX_STORE_BYTES bytes"
        }
        val output = file.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    private fun pruneLocked(makeRoom: Boolean = false) {
        while (entries.size > MAX_ENTRIES || (makeRoom && entries.size >= MAX_ENTRIES)) {
            val removable = entries.values
                .filter { it.status in TERMINAL }
                .minByOrNull { it.lastSequence }
                ?: break
            entries.remove(removable.operationId)
        }
    }

    private fun nextSequenceLocked(): Long {
        sequence += 1L
        require(sequence > 0L) { "MCP operation journal sequence overflow" }
        return sequence
    }

    private fun context(entry: Entry) = RiftMcpOperationContext(
        operationId = entry.operationId,
        requestId = entry.requestId,
        requestHash = entry.requestHash,
        modelCallId = entry.modelCallId,
        tool = entry.tool,
        mutating = entry.mutating,
        startSequence = entry.startSequence
    )

    private fun snapshot(entry: Entry): JSONObject = JSONObject()
        .put("operationId", entry.operationId)
        .put("requestId", entry.requestId)
        .put("requestHash", entry.requestHash)
        .put("modelCallId", entry.modelCallId ?: JSONObject.NULL)
        .put("tool", entry.tool)
        .put("mutating", entry.mutating)
        .put("status", entry.status)
        .put("terminal", entry.status in TERMINAL)
        .put("startedAtMs", entry.startedAtMs)
        .put("completedAtMs", entry.completedAtMs ?: JSONObject.NULL)
        .put("startSequence", entry.startSequence)
        .put("lastSequence", entry.lastSequence)
        .put("delivery", entry.delivery)
        .put("deliveryAtMs", entry.deliveryAtMs ?: JSONObject.NULL)
        .put("error", entry.error ?: JSONObject.NULL)
        .put("details", entry.details ?: JSONObject.NULL)

    private fun boundedId(value: String, label: String): String {
        val clean = value.trim()
        require(clean.isNotBlank() && clean.length <= MAX_ID_CHARS) { "Invalid MCP $label" }
        require(clean.none { it.code < 0x20 || it.code == 0x7f }) { "Invalid MCP $label" }
        return clean
    }

    private fun sanitizeDetails(details: JSONObject?): JSONObject? {
        if (details == null) return null
        val out = JSONObject()
        val keys = details.keys().asSequence().toList().sorted().take(MAX_DETAIL_KEYS)
        for (rawKey in keys) {
            val key = rawKey.take(MAX_DETAIL_KEY_CHARS)
            if (key.isBlank() || SENSITIVE_DETAIL_KEY.containsMatchIn(key)) continue
            val value = details.opt(rawKey)
            when (value) {
                null, JSONObject.NULL -> out.put(key, JSONObject.NULL)
                is Boolean, is Number -> out.put(key, value)
                else -> out.put(key, value.toString().take(MAX_DETAIL_VALUE_CHARS))
            }
        }
        return out.takeIf { it.length() > 0 }
    }

    internal fun requestHash(request: JSONObject): String {
        val normalized = JSONObject(request.toString()).apply { remove("id") }
        val canonical = canonicalJson(normalized)
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun canonicalJson(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { key ->
            "${JSONObject.quote(key)}:${canonicalJson(value.opt(key))}"
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalJson(value.opt(it)) }
        is String -> JSONObject.quote(value)
        is Boolean, is Number -> value.toString()
        else -> JSONObject.quote(value.toString())
    }
}
