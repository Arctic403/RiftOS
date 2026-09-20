package com.riftos.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-local RiftCLI event stream.
 *
 * This is transport-neutral: producers publish bounded state transitions, while the relay client
 * subscribes and forwards them over the already-open device WebSocket. A small replay ring lets a
 * reconnected driver recover recent events without polling every CLI job.
 */
class RiftCliEventBus {
    companion object {
        const val SCHEMA = "rift.cli-event/1"
        private const val MAX_EVENTS = 256
        private const val MAX_EVENT_BYTES = 96 * 1024
        private const val MAX_INLINE_RESULT_BYTES = 48 * 1024
    }

    private val sequence = AtomicLong(System.currentTimeMillis() * 1000L)
    private val lock = Any()
    private val events = ArrayDeque<JSONObject>(MAX_EVENTS)
    private val listeners = CopyOnWriteArraySet<(JSONObject) -> Unit>()
    private val lastJobState = LinkedHashMap<String, String>()

    fun addListener(listener: (JSONObject) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (JSONObject) -> Unit) {
        listeners -= listener
    }

    fun currentSequence(): Long = sequence.get()

    fun replayAfter(afterSequence: Long): List<JSONObject> = synchronized(lock) {
        events
            .asSequence()
            .filter { it.optLong("sequence") > afterSequence }
            .map { JSONObject(it.toString()) }
            .toList()
    }

    fun status(): JSONObject = synchronized(lock) {
        JSONObject()
            .put("schema", SCHEMA)
            .put("sequence", sequence.get())
            .put("retained", events.size)
            .put("capacity", MAX_EVENTS)
            .put("maxEventBytes", MAX_EVENT_BYTES)
            .put("maxInlineResultBytes", MAX_INLINE_RESULT_BYTES)
    }

    fun emit(
        type: String,
        requestId: String? = null,
        jobId: String? = null,
        lane: String? = null,
        status: String? = null,
        terminal: Boolean? = null,
        message: String? = null,
        result: Any? = null,
        extra: JSONObject? = null
    ): JSONObject? {
        val cleanType = type.trim().take(128)
        if (cleanType.isBlank()) return null

        if (!jobId.isNullOrBlank() && !status.isNullOrBlank()) {
            val stepKey = extra?.optString("stepId").orEmpty()
            val key = "$cleanType|$status|${terminal ?: false}|$stepKey|${message.orEmpty().take(256)}"
            synchronized(lock) {
                if (lastJobState[jobId] == key) return null
                lastJobState[jobId] = key
                if (lastJobState.size > MAX_EVENTS * 2) {
                    val iterator = lastJobState.entries.iterator()
                    repeat(lastJobState.size - MAX_EVENTS) {
                        if (iterator.hasNext()) {
                            iterator.next()
                            iterator.remove()
                        }
                    }
                }
            }
        }

        var event = JSONObject()
            .put("schema", SCHEMA)
            .put("sequence", sequence.incrementAndGet())
            .put("at", System.currentTimeMillis())
            .put("type", cleanType)

        requestId?.takeIf { it.isNotBlank() }?.let { event.put("requestId", it.take(256)) }
        jobId?.takeIf { it.isNotBlank() }?.let { event.put("jobId", it.take(256)) }
        lane?.takeIf { it.isNotBlank() }?.let { event.put("lane", it.take(64)) }
        status?.takeIf { it.isNotBlank() }?.let { event.put("status", it.take(64)) }
        terminal?.let { event.put("terminal", it) }
        message?.takeIf { it.isNotBlank() }?.let { event.put("message", it.take(2048)) }

        if (result != null && result !== JSONObject.NULL) {
            val serialized = when (result) {
                is JSONObject -> result.toString()
                is JSONArray -> result.toString()
                else -> JSONObject.wrap(result)?.toString() ?: result.toString()
            }
            val bytes = serialized.toByteArray(Charsets.UTF_8).size
            event.put("resultAvailable", true)
            event.put("resultBytes", bytes)
            if (bytes <= MAX_INLINE_RESULT_BYTES) {
                val inline = when (result) {
                    is JSONObject -> JSONObject(result.toString())
                    is JSONArray -> JSONArray(result.toString())
                    else -> JSONObject.wrap(result)
                }
                event.put("resultInline", inline)
            }
        }

        val extraKeys = ArrayList<String>()
        extra?.let { source ->
            val iterator = source.keys()
            while (iterator.hasNext()) {
                val key = iterator.next()
                if (key.length in 1..128 && !event.has(key)) {
                    event.put(key, source.opt(key))
                    extraKeys += key
                }
            }
        }

        var encoded = event.toString()
        if (encoded.toByteArray(Charsets.UTF_8).size > MAX_EVENT_BYTES) {
            event.remove("resultInline")
            event.put("resultInlineTruncated", true)
            encoded = event.toString()
        }
        if (encoded.toByteArray(Charsets.UTF_8).size > MAX_EVENT_BYTES) {
            event.remove("message")
            extraKeys.forEach { event.remove(it) }
            event.put("eventTruncated", true)
            encoded = event.toString()
        }
        if (encoded.toByteArray(Charsets.UTF_8).size > MAX_EVENT_BYTES) {
            val sequenceValue = event.optLong("sequence")
            val atValue = event.optLong("at")
            val resultAvailable = event.optBoolean("resultAvailable", false)
            val resultBytes = event.optInt("resultBytes", 0)
            event = JSONObject()
                .put("schema", SCHEMA)
                .put("sequence", sequenceValue)
                .put("at", atValue)
                .put("type", cleanType)
                .put("eventTruncated", true)
            requestId?.takeIf { it.isNotBlank() }?.let { event.put("requestId", it.take(256)) }
            jobId?.takeIf { it.isNotBlank() }?.let { event.put("jobId", it.take(256)) }
            lane?.takeIf { it.isNotBlank() }?.let { event.put("lane", it.take(64)) }
            status?.takeIf { it.isNotBlank() }?.let { event.put("status", it.take(64)) }
            terminal?.let { event.put("terminal", it) }
            if (resultAvailable) {
                event.put("resultAvailable", true)
                event.put("resultBytes", resultBytes)
            }
        }

        val frozen = JSONObject(event.toString())
        synchronized(lock) {
            events.addLast(frozen)
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
        listeners.forEach { listener ->
            runCatching { listener(JSONObject(frozen.toString())) }
        }
        return frozen
    }

    fun emitJob(
        type: String,
        jobId: String,
        requestId: String,
        lane: String,
        status: String,
        terminal: Boolean,
        result: Any? = null,
        message: String? = null
    ): JSONObject? = emit(
        type = type,
        requestId = requestId,
        jobId = jobId,
        lane = lane,
        status = status,
        terminal = terminal,
        result = result,
        message = message
    )
}
