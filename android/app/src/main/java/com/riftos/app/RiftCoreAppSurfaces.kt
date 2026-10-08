package com.riftos.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * C1.2-A: Core-owned immutable frame snapshots and generic surface change feed.
 *
 * No Activity, View, window, desktop or shell implementation is stored here.
 * Every graphical client reads the same typed RiftAppAbi.Frame contract.
 * This is an in-process protocol for now; C1.3 provides external IPC.
 */
class RiftCoreAppSurfaces {
    companion object {
        const val SCHEMA = "riftos.core.app-surfaces/1"
        private const val MAX_SURFACES = 128
        private const val MAX_SUBSCRIBERS = 32
        private const val MAX_SURFACE_TEXT_BYTES = 256 * 1024
        private const val MAX_TOTAL_SURFACE_TEXT_BYTES = 4 * 1024 * 1024
    }

    data class Snapshot(
        val appId: String,
        val attachmentGeneration: Long,
        val revision: Long,
        val frame: RiftAppAbi.Frame
    )

    data class Change(val appId: String, val revision: Long, val operation: String)

    private val surfaces = LinkedHashMap<String, Snapshot>()
    private val surfaceTextBytes = LinkedHashMap<String, Int>()
    private var totalTextBytes = 0
    private val subscribers = LinkedHashMap<Long, (Change) -> Unit>()
    private var nextSubscription = 0L
    private var nextRevision = 0L

    private fun copyFrame(frame: RiftAppAbi.Frame): RiftAppAbi.Frame =
        frame.copy(nodes = frame.nodes.map { it.copy() })

    @Synchronized
    fun snapshot(appId: String): Snapshot? =
        surfaces[appId]?.let { it.copy(frame = copyFrame(it.frame)) }

    @Synchronized
    fun subscribe(observer: (Change) -> Unit): Long {
        require(subscribers.size < MAX_SUBSCRIBERS) { "Core surface subscriber limit reached" }
        check(nextSubscription < Long.MAX_VALUE) { "Core surface subscription IDs exhausted" }
        nextSubscription += 1L
        subscribers[nextSubscription] = observer
        return nextSubscription
    }

    @Synchronized
    fun unsubscribe(subscription: Long) {
        subscribers.remove(subscription)
    }

    fun publish(appId: String, generation: Long, frame: RiftAppAbi.Frame): Long {
        require(appId.isNotBlank() && generation > 0L) { "Invalid Core surface ownership" }
        val bytes = frame.nodes.sumOf { it.text.toByteArray(Charsets.UTF_8).size }
        require(bytes <= MAX_SURFACE_TEXT_BYTES) { "Core surface text exceeds bound" }
        val pair = synchronized(this) {
            require(surfaces.containsKey(appId) || surfaces.size < MAX_SURFACES) {
                "Core surface registry full"
            }
            val nextTotal = totalTextBytes - (surfaceTextBytes[appId] ?: 0) + bytes
            require(nextTotal <= MAX_TOTAL_SURFACE_TEXT_BYTES) {
                "Core surface aggregate text exceeds bound"
            }
            check(nextRevision < Long.MAX_VALUE) { "Core surface revision exhausted" }
            totalTextBytes = nextTotal
            surfaceTextBytes[appId] = bytes
            nextRevision += 1L
            surfaces[appId] = Snapshot(appId, generation, nextRevision, copyFrame(frame))
            Change(appId, nextRevision, "updated") to subscribers.values.toList()
        }
        pair.second.forEach { listener -> runCatching { listener(pair.first) } }
        return pair.first.revision
    }

    fun remove(appId: String): Boolean {
        val pair = synchronized(this) {
            val removed = surfaces.remove(appId) ?: return false
            totalTextBytes -= surfaceTextBytes.remove(appId) ?: 0
            Change(appId, removed.revision, "removed") to subscribers.values.toList()
        }
        pair.second.forEach { listener -> runCatching { listener(pair.first) } }
        return true
    }

    @Synchronized
    fun list(): JSONObject {
        val rows = JSONArray()
        for (surface in surfaces.values) {
            rows.put(
                JSONObject()
                    .put("appId", surface.appId)
                    .put("attachmentGeneration", surface.attachmentGeneration)
                    .put("revision", surface.revision)
                    .put("layout", surface.frame.layout)
                    .put("nodeCount", surface.frame.nodes.size)
            )
        }
        return JSONObject()
            .put("schema", SCHEMA)
            .put("owner", "riftos-core")
            .put("count", surfaces.size)
            .put("storedTextBytes", totalTextBytes)
            .put("headlessExecution", false)
            .put("shellClientProtocol", "in-process")
            .put("surfaces", rows)
    }
}
