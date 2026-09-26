package com.riftos.app

import java.util.UUID

/**
 * Process-local fail-closed mutation fencing for model/MCP writes.
 *
 * One mutating request may own each canonical workspace project root at a time.
 * Different project roots can be written concurrently. Cancellation is retained
 * briefly so a late-arriving worker cannot commit after the transport/UI request
 * has already been cancelled.
 */
internal object RiftMutationFence {
    private const val CANCEL_RETENTION_MS = 10 * 60 * 1000L
    private const val MAX_CANCELLED_REQUESTS = 512
    private const val MAX_REPO_KEYS = 16
    private const val MAX_ID_CHARS = 256

    data class Context(
        val leaseId: String,
        val holderId: String,
        val transportRequestId: String?,
        val modelCallId: String?,
        val traceId: String?,
        val repoKeys: List<String>,
        val acquiredAt: Long
    )

    private data class Lease(
        val holderId: String,
        val modelCallId: String?,
        val traceId: String?,
        val acquiredAt: Long
    )

    private val lock = Any()
    private val leases = LinkedHashMap<String, Lease>()
    private val cancelledTransport = LinkedHashMap<String, Long>()
    private val cancelledModelCalls = LinkedHashMap<String, Long>()
    private val local = ThreadLocal<Context?>()

    fun begin(
        transportRequestId: String?,
        modelCallId: String?,
        traceId: String?,
        fallbackRequestId: String,
        rawPaths: Collection<String>
    ): Context? {
        val repoKeys = rawPaths.mapNotNull(::repoKey).distinct().sorted()
        if (repoKeys.isEmpty()) return null
        require(repoKeys.size <= MAX_REPO_KEYS) {
            "Mutation request spans too many workspace roots: ${repoKeys.size} > $MAX_REPO_KEYS"
        }

        val transport = boundedId(transportRequestId)
        val model = boundedId(modelCallId)
        val trace = boundedId(traceId)
        val holder = transport ?: model ?: boundedId(fallbackRequestId)
            ?: throw IllegalArgumentException("Mutation request is missing a bounded writer identity")
        val now = System.currentTimeMillis()

        synchronized(lock) {
            pruneLocked(now)
            require(!isCancelledLocked(transport, model)) {
                "Mutation request was cancelled before writer lease acquisition"
            }

            val conflict = repoKeys.firstNotNullOfOrNull { key ->
                leases[key]?.takeIf { it.holderId != holder }?.let { key to it }
            }
            require(conflict == null) {
                val (key, lease) = conflict!!
                "Workspace writer lease is already held for $key by " +
                    (lease.modelCallId ?: lease.traceId ?: lease.holderId).take(96)
            }

            val lease = Lease(holder, model, trace, now)
            repoKeys.forEach { leases[it] = lease }
        }

        val context = Context(
            leaseId = "writer-${UUID.randomUUID()}",
            holderId = holder,
            transportRequestId = transport,
            modelCallId = model,
            traceId = trace,
            repoKeys = repoKeys,
            acquiredAt = now
        )
        local.set(context)
        return context
    }

    fun requireActive(label: String = "workspace mutation commit") {
        val context = local.get() ?: return
        synchronized(lock) {
            pruneLocked(System.currentTimeMillis())
            require(!isCancelledLocked(context.transportRequestId, context.modelCallId)) {
                "$label rejected because the originating request was cancelled"
            }
            context.repoKeys.forEach { key ->
                val lease = leases[key]
                require(lease != null && lease.holderId == context.holderId) {
                    "$label rejected because the writer lease for $key is stale"
                }
            }
        }
    }

    fun commit(context: Context?, label: String = "workspace mutation commit") {
        if (context == null) return
        synchronized(lock) {
            pruneLocked(System.currentTimeMillis())
            require(!isCancelledLocked(context.transportRequestId, context.modelCallId)) {
                "$label rejected because the originating request was cancelled"
            }
            context.repoKeys.forEach { key ->
                val lease = leases[key]
                require(lease != null && lease.holderId == context.holderId) {
                    "$label rejected because the writer lease for $key is stale"
                }
            }
        }
    }

    fun cancelTransport(requestId: String): Boolean {
        val id = boundedId(requestId) ?: return false
        synchronized(lock) {
            val now = System.currentTimeMillis()
            pruneLocked(now)
            cancelledTransport[id] = now + CANCEL_RETENTION_MS
            trimCancelledLocked(cancelledTransport)
            return leases.values.any { it.holderId == id }
        }
    }

    fun cancelModelCall(callId: String): Boolean {
        val id = boundedId(callId) ?: return false
        synchronized(lock) {
            val now = System.currentTimeMillis()
            pruneLocked(now)
            cancelledModelCalls[id] = now + CANCEL_RETENTION_MS
            trimCancelledLocked(cancelledModelCalls)
            return leases.values.any { it.modelCallId == id }
        }
    }

    fun end(context: Context?) {
        if (context == null) {
            local.remove()
            return
        }
        synchronized(lock) {
            context.repoKeys.forEach { key ->
                if (leases[key]?.holderId == context.holderId) leases.remove(key)
            }
        }
        if (local.get()?.leaseId == context.leaseId) local.remove()
    }

    internal fun repoKey(rawPath: String): String? {
        var value = rawPath.trim().replace('\\', '/')
        if (value.isBlank()) return null
        value = value.removePrefix("/")
        if (value.startsWith("workspace/")) value = value.removePrefix("workspace/")
        val first = value.substringBefore('/').trim()
        if (first.isBlank() || first == "." || first == "..") return null
        return "workspace/$first"
    }

    private fun isCancelledLocked(transportRequestId: String?, modelCallId: String?): Boolean =
        (transportRequestId != null && cancelledTransport.containsKey(transportRequestId)) ||
            (modelCallId != null && cancelledModelCalls.containsKey(modelCallId))

    private fun pruneLocked(now: Long) {
        cancelledTransport.entries.removeAll { it.value <= now }
        cancelledModelCalls.entries.removeAll { it.value <= now }
    }

    private fun trimCancelledLocked(map: LinkedHashMap<String, Long>) {
        while (map.size > MAX_CANCELLED_REQUESTS) {
            val iterator = map.entries.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
    }

    private fun boundedId(raw: String?): String? =
        raw?.trim()?.take(MAX_ID_CHARS)?.takeIf { it.isNotBlank() }
}
