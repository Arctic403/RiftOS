package com.riftos.app

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Correlation identity only; this object grants no execution or permission authority. */
data class RiftDebugContext(val traceId: String, val spanId: String? = null)

/** Bounded metadata signal emitted by a plugged subsystem. Payload bodies do not belong here. */
data class RiftDebugSignal(
    val operation: String,
    val phase: String = "event",
    val traceId: String? = null,
    val spanId: String? = null,
    val parentSpanId: String? = null,
    val outcome: String? = null,
    val message: String? = null,
    val durationMs: Long? = null,
    val attributes: Map<String, String> = emptyMap()
)

fun interface RiftDebugSink {
    fun emit(signal: RiftDebugSignal)
}

/** Universal plug implemented by any RiftOS subsystem that wants to publish diagnostics. */
interface RiftDebugAdapter {
    val debugComponent: String
    fun attachDebugSink(sink: RiftDebugSink): AutoCloseable
}

/**
 * Process-wide passive debugger. Producers emit beside their existing execution paths.
 *
 * The hub owns no shell, tool, filesystem, network, model, permission, cancellation, or mutation
 * capability. Its in-memory state is bounded and sensitive attribute names are always redacted.
 */
class RiftDebugHub(
    private val maxEvents: Int = 1_024,
    private val maxActiveSpans: Int = 128
) {
    companion object {
        const val FORMAT = "rift-debug-v1"
        private const val MAX_ATTRIBUTES = 16
        private const val MAX_VALUE_CHARS = 256
        private const val MAX_MESSAGE_CHARS = 512
        private val sensitiveKeys = listOf(
            "authorization", "cookie", "credential", "password", "passphrase",
            "privatekey", "private_key", "secret", "token", "apikey", "api_key"
        )
    }

    private data class ActiveSpan(
        val traceId: String,
        val spanId: String,
        val parentSpanId: String?,
        val component: String,
        val operation: String,
        val startedAt: Long,
        val startedElapsedMs: Long,
        val attributes: Map<String, String>
    )

    private data class DebugEvent(
        val sequence: Long,
        val at: Long,
        val elapsedMs: Long,
        val traceId: String,
        val spanId: String?,
        val parentSpanId: String?,
        val component: String,
        val operation: String,
        val phase: String,
        val outcome: String?,
        val durationMs: Long?,
        val message: String?,
        val attributes: Map<String, String>
    )

    private val lock = Any()
    private val ids = AtomicLong()
    private val events = ArrayDeque<DebugEvent>()
    private val active = LinkedHashMap<String, ActiveSpan>()
    private val seenComponents = linkedSetOf("debug.hub")
    private val pluggedComponents = linkedMapOf<String, Int>()
    private var sequence = 0L
    private var droppedEvents = 0L
    private var evictedActiveSpans = 0L

    init {
        require(maxEvents in 64..16_384)
        require(maxActiveSpans in 8..2_048)
    }

    fun start(
        component: String,
        operation: String,
        parent: RiftDebugContext? = null,
        traceId: String? = null,
        attributes: Map<String, String> = emptyMap()
    ): RiftDebugSpan {
        val safeComponent = identifier(component, "unknown")
        val safeOperation = identifier(operation, "operation")
        val safeTrace = identifier(parent?.traceId ?: traceId ?: nextId("trace"), "trace")
        val spanId = nextId("span")
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val safeAttributes = sanitizeAttributes(attributes)
        synchronized(lock) {
            if (active.size >= maxActiveSpans) evictOldestLocked(elapsed)
            val span = ActiveSpan(
                safeTrace, spanId, parent?.spanId, safeComponent, safeOperation,
                now, elapsed, safeAttributes
            )
            active[spanId] = span
            seenComponents += safeComponent
            appendLocked(span, "start", null, null, null, safeAttributes)
        }
        return RiftDebugSpan(this, RiftDebugContext(safeTrace, spanId), elapsed)
    }

    /** Lightweight plug for components that only need to emit standalone events. */
    fun sink(component: String): RiftDebugSink {
        val safeComponent = identifier(component, "unknown")
        return RiftDebugSink { signal -> acceptSignal(safeComponent, signal) }
    }

    /** Attach a reusable adapter. Closing the returned handle detaches its sink. */
    fun plug(adapter: RiftDebugAdapter): AutoCloseable {
        val component = identifier(adapter.debugComponent, "unknown")
        val open = AtomicBoolean(true)
        synchronized(lock) {
            pluggedComponents[component] = (pluggedComponents[component] ?: 0) + 1
            seenComponents += component
            appendEventLocked(
                nextId("trace"), null, null, "debug.hub", "adapter", "event",
                "attached", null, null, mapOf("component" to component)
            )
        }
        val upstream = try {
            adapter.attachDebugSink(RiftDebugSink { signal ->
                if (open.get()) acceptSignal(component, signal)
            })
        } catch (failure: Throwable) {
            open.set(false)
            synchronized(lock) {
                val remaining = (pluggedComponents[component] ?: 1) - 1
                if (remaining <= 0) pluggedComponents.remove(component)
                else pluggedComponents[component] = remaining
                appendEventLocked(
                    nextId("trace"), null, null, "debug.hub", "adapter", "event",
                    "attach_failed", null, failure.message, mapOf("component" to component)
                )
            }
            throw failure
        }
        return AutoCloseable {
            if (open.compareAndSet(true, false)) {
                runCatching { upstream.close() }
                synchronized(lock) {
                    val remaining = (pluggedComponents[component] ?: 1) - 1
                    if (remaining <= 0) pluggedComponents.remove(component)
                    else pluggedComponents[component] = remaining
                    appendEventLocked(
                        nextId("trace"), null, null, "debug.hub", "adapter", "event",
                        "detached", null, null, mapOf("component" to component)
                    )
                }
            }
        }
    }

    fun query(args: JSONObject): JSONObject {
        val action = args.optString("action", "status").trim().lowercase().ifBlank { "status" }
        return when (action) {
            "status" -> status()
            "events" -> eventSnapshot(
                args.optString("traceId").trim().takeIf { it.isNotBlank() },
                args.optString("component").trim().takeIf { it.isNotBlank() },
                args.optLong("sinceSequence", 0L).coerceAtLeast(0L),
                args.optInt("limit", 100).coerceIn(1, 200)
            )
            "active" -> activeSnapshot(
                args.optString("traceId").trim().takeIf { it.isNotBlank() },
                args.optString("component").trim().takeIf { it.isNotBlank() },
                args.optInt("limit", 100).coerceIn(1, 200)
            )
            "components" -> componentSnapshot()
            else -> throw IllegalArgumentException(
                "Unsupported rift_debug action: $action. Use status, events, active, or components."
            )
        }
    }

    fun status(): JSONObject = synchronized(lock) {
        JSONObject()
            .put("format", FORMAT)
            .put("passive", true)
            .put("authority", JSONObject()
                .put("execute", false).put("mutate", false).put("cancel", false)
                .put("network", false).put("filesystem", false).put("model", false))
            .put("events", events.size)
            .put("eventCapacity", maxEvents)
            .put("activeSpans", active.size)
            .put("activeCapacity", maxActiveSpans)
            .put("droppedEvents", droppedEvents)
            .put("evictedActiveSpans", evictedActiveSpans)
            .put("latestSequence", sequence)
            .put("pluggedAdapters", pluggedComponents.values.sum())
            .put("components", JSONArray(seenComponents.sorted()))
    }

    internal fun finish(
        context: RiftDebugContext,
        startedElapsedMs: Long,
        outcome: String,
        message: String?,
        attributes: Map<String, String>
    ) {
        synchronized(lock) {
            val span = active.remove(context.spanId)
            if (span == null) {
                appendEventLocked(
                    identifier(context.traceId, "trace"), context.spanId, null,
                    "debug.hub", "orphan-finish", "event", identifier(outcome, "unknown"),
                    (SystemClock.elapsedRealtime() - startedElapsedMs).coerceAtLeast(0L),
                    message, attributes
                )
                return
            }
            appendLocked(
                span, "end", identifier(outcome, "unknown"),
                (SystemClock.elapsedRealtime() - span.startedElapsedMs).coerceAtLeast(0L),
                message, mergeAttributes(span.attributes, sanitizeAttributes(attributes))
            )
        }
    }

    private fun acceptSignal(component: String, signal: RiftDebugSignal) {
        synchronized(lock) {
            seenComponents += component
            appendEventLocked(
                identifier(signal.traceId ?: nextId("trace"), "trace"),
                signal.spanId?.let { identifier(it, "span") },
                signal.parentSpanId?.let { identifier(it, "parent") },
                component,
                identifier(signal.operation, "event"),
                identifier(signal.phase, "event"),
                signal.outcome?.let { identifier(it, "unknown") },
                signal.durationMs?.coerceAtLeast(0L),
                signal.message,
                signal.attributes
            )
        }
    }

    private fun eventSnapshot(
        traceId: String?,
        component: String?,
        sinceSequence: Long,
        limit: Int
    ): JSONObject = synchronized(lock) {
        val selected = events.asSequence()
            .filter { it.sequence > sinceSequence }
            .filter { traceId == null || it.traceId == traceId }
            .filter { component == null || it.component == component }
            .toList().takeLast(limit)
        JSONObject()
            .put("format", FORMAT)
            .put("latestSequence", sequence)
            .put("returned", selected.size)
            .put("events", JSONArray().apply { selected.forEach { put(eventJson(it)) } })
    }

    private fun activeSnapshot(traceId: String?, component: String?, limit: Int): JSONObject =
        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            val selected = active.values.asSequence()
                .filter { traceId == null || it.traceId == traceId }
                .filter { component == null || it.component == component }
                .toList().takeLast(limit)
            JSONObject()
                .put("format", FORMAT)
                .put("returned", selected.size)
                .put("active", JSONArray().apply {
                    selected.forEach { span ->
                        put(JSONObject()
                            .put("traceId", span.traceId)
                            .put("spanId", span.spanId)
                            .put("parentSpanId", span.parentSpanId ?: JSONObject.NULL)
                            .put("component", span.component)
                            .put("operation", span.operation)
                            .put("startedAt", span.startedAt)
                            .put("ageMs", (now - span.startedElapsedMs).coerceAtLeast(0L))
                            .put("attributes", JSONObject(span.attributes)))
                    }
                })
        }

    private fun componentSnapshot(): JSONObject = synchronized(lock) {
        JSONObject().put("format", FORMAT).put("components", JSONArray().apply {
            seenComponents.sorted().forEach { component ->
                put(JSONObject()
                    .put("name", component)
                    .put("pluggedAdapters", pluggedComponents[component] ?: 0)
                    .put("activeSpans", active.values.count { it.component == component }))
            }
        })
    }

    private fun evictOldestLocked(nowElapsed: Long) {
        val iterator = active.entries.iterator()
        if (!iterator.hasNext()) return
        val span = iterator.next().value
        iterator.remove()
        evictedActiveSpans += 1
        appendLocked(
            span, "end", "evicted",
            (nowElapsed - span.startedElapsedMs).coerceAtLeast(0L),
            "Active span evicted at bounded debugger capacity", span.attributes
        )
    }

    private fun appendLocked(
        span: ActiveSpan,
        phase: String,
        outcome: String?,
        durationMs: Long?,
        message: String?,
        attributes: Map<String, String>
    ) = appendEventLocked(
        span.traceId, span.spanId, span.parentSpanId, span.component, span.operation,
        phase, outcome, durationMs, message, attributes
    )

    private fun appendEventLocked(
        traceId: String,
        spanId: String?,
        parentSpanId: String?,
        component: String,
        operation: String,
        phase: String,
        outcome: String?,
        durationMs: Long?,
        message: String?,
        attributes: Map<String, String>
    ) {
        sequence += 1
        if (events.size >= maxEvents) {
            events.removeFirst()
            droppedEvents += 1
        }
        events.addLast(
            DebugEvent(
                sequence, System.currentTimeMillis(), SystemClock.elapsedRealtime(),
                traceId, spanId, parentSpanId, component, operation, phase, outcome,
                durationMs, sanitizeMessage(message), sanitizeAttributes(attributes)
            )
        )
    }

    private fun eventJson(event: DebugEvent): JSONObject = JSONObject()
        .put("sequence", event.sequence)
        .put("at", event.at)
        .put("elapsedMs", event.elapsedMs)
        .put("traceId", event.traceId)
        .put("spanId", event.spanId ?: JSONObject.NULL)
        .put("parentSpanId", event.parentSpanId ?: JSONObject.NULL)
        .put("component", event.component)
        .put("operation", event.operation)
        .put("phase", event.phase)
        .put("outcome", event.outcome ?: JSONObject.NULL)
        .put("durationMs", event.durationMs ?: JSONObject.NULL)
        .put("message", event.message ?: JSONObject.NULL)
        .put("attributes", JSONObject(event.attributes))

    private fun sanitizeAttributes(input: Map<String, String>): Map<String, String> {
        val output = linkedMapOf<String, String>()
        input.entries.take(MAX_ATTRIBUTES).forEach { (rawKey, rawValue) ->
            val key = identifier(rawKey, "attribute").take(64)
            output[key] = if (sensitiveKeys.any { key.lowercase().contains(it) }) {
                "[REDACTED]"
            } else {
                rawValue.replace(Regex("[\\r\\n\\t]+"), " ").take(MAX_VALUE_CHARS)
            }
        }
        return output
    }

    private fun mergeAttributes(first: Map<String, String>, second: Map<String, String>): Map<String, String> {
        val merged = linkedMapOf<String, String>()
        first.forEach { (key, value) -> if (merged.size < MAX_ATTRIBUTES) merged[key] = value }
        second.forEach { (key, value) ->
            if (merged.size < MAX_ATTRIBUTES || merged.containsKey(key)) merged[key] = value
        }
        return merged
    }

    private fun sanitizeMessage(message: String?): String? {
        var safe = message?.replace(Regex("[\\r\\n\\t]+"), " ") ?: return null
        safe = safe.replace(
            Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]+=*"),
            "Bearer [REDACTED]"
        )
        safe = safe.replace(
            Regex("(?i)\\b(authorization|token|password|passphrase|secret|api[_-]?key)\\s*[:=]\\s*[^\\s,;]+"),
            "$1=[REDACTED]"
        )
        return safe.take(MAX_MESSAGE_CHARS).takeIf { it.isNotBlank() }
    }

    private fun identifier(value: String, fallback: String): String {
        val cleaned = value.trim().replace(Regex("[^A-Za-z0-9._:/-]"), "_").take(128)
        return cleaned.ifBlank { fallback }
    }

    private fun nextId(prefix: String): String =
        prefix + "-" + SystemClock.elapsedRealtime().toString(36) + "-" + ids.incrementAndGet().toString(36)
}

class RiftDebugSpan internal constructor(
    private val hub: RiftDebugHub,
    val context: RiftDebugContext,
    private val startedElapsedMs: Long
) : AutoCloseable {
    private val terminal = AtomicBoolean(false)

    fun success(attributes: Map<String, String> = emptyMap()) = finish("ok", null, attributes)
    fun failure(message: String?, attributes: Map<String, String> = emptyMap()) =
        finish("error", message, attributes)
    fun timeout(message: String? = null, attributes: Map<String, String> = emptyMap()) =
        finish("timeout", message, attributes)
    fun cancelled(message: String? = null, attributes: Map<String, String> = emptyMap()) =
        finish("cancelled", message, attributes)
    override fun close() = success()

    private fun finish(outcome: String, message: String?, attributes: Map<String, String>) {
        if (terminal.compareAndSet(false, true)) {
            hub.finish(context, startedElapsedMs, outcome, message, attributes)
        }
    }
}
