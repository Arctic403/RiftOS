package com.riftos.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * C1.2-D1: second, read-only in-process shell renderer.
 *
 * Independent of RiftRappHost, RiftNativeDesktop, Activity and View. This
 * terminal client subscribes to the generic Core surface feed and projects
 * the same immutable typed frame as the graphical shell. It never claims
 * sessions, mints focus leases or sends input events.
 *
 * C1.3 will move the protocol to a separately running shell process.
 */
class RiftAlternateShellClient(private val surfaces: RiftCoreAppSurfaces) {
    companion object {
        const val SCHEMA = "riftos.shell.client.terminal/1"
        private const val MAX_ATTACHED = 8
        private const val MAX_RENDER_CHARS = 32_768
    }

    private class Watch(
        val id: String,
        var frame: RiftCoreAppSurfaces.Snapshot?,
        var observedRevision: Long,
        var updates: Long = 0L,
        var lastOperation: String = "attached"
    )
    private val watched = LinkedHashMap<String, Watch>()
    private var subscription: Long? = null

    /** The only Core interaction is subscribe/snapshot/unsubscribe. */
    @Synchronized
    fun attach(id: String): JSONObject {
        require(id.isNotBlank() && id.length <= 128) { "Invalid alternate shell app ID" }
        val snap = surfaces.snapshot(id)
            ?: error("No Core application surface to attach: $id")
        if (!watched.containsKey(id)) {
            require(watched.size < MAX_ATTACHED) { "Alternate shell attachment limit exceeded" }
            if (subscription == null) {
                subscription = surfaces.subscribe { change -> onChange(change) }
            }
            watched[id] = Watch(id, snap, snap.revision)
        } else {
            watched.getValue(id).apply {
                frame = snap
                observedRevision = snap.revision
            }
        }
        return describe(watched.getValue(id))
    }

    private fun onChange(change: RiftCoreAppSurfaces.Change) {
        synchronized(this) {
            val watch = watched[change.appId] ?: return
            watch.frame = if (change.operation == "removed") null
                else surfaces.snapshot(change.appId)
            watch.observedRevision = change.revision
            watch.lastOperation = change.operation
            if (watch.updates < Long.MAX_VALUE) watch.updates += 1L
        }
    }

    @Synchronized
    fun detach(id: String): JSONObject {
        val removed = watched.remove(id) != null
        if (watched.isEmpty()) {
            subscription?.let(surfaces::unsubscribe)
            subscription = null
        }
        return JSONObject()
            .put("schema", SCHEMA)
            .put("appId", id)
            .put("detached", removed)
            .put("attachedCount", watched.size)
    }

    @Synchronized
    fun close() {
        watched.clear()
        subscription?.let(surfaces::unsubscribe)
        subscription = null
    }

    @Synchronized
    fun status(): JSONObject {
        val apps = JSONArray()
        for (watch in watched.values) apps.put(describe(watch))
        return JSONObject()
            .put("schema", SCHEMA)
            .put("mode", "read-only-terminal")
            .put("attachedCount", watched.size)
            .put("subscribed", subscription != null)
            .put("apps", apps)
            .put("shellProcessIndependent", false)
    }

    @Synchronized
    fun render(id: String): JSONObject {
        val watch = watched[id] ?: error("Alternate shell is not attached: $id")
        // Refresh the current immutable Core snapshot even if change
        // notifications were coalesced. Never render cached removed state.
        val current = surfaces.snapshot(id)
        watch.frame = current
        if (current == null) {
            watch.lastOperation = "removed"
            return describe(watch)
                .put("renderedText", "[Core surface removed]")
                .put("nodes", JSONArray())
        }
        watch.observedRevision = current.revision
        val nodes = JSONArray()
        val frame = current.frame
        val ids = frame.nodes.associateBy { it.id }
        val lines = ArrayList<String>(frame.nodes.size + 1)
        lines.add("Core app=$id generation=${current.attachmentGeneration} revision=${current.revision} layout=${frame.layout}")
        for (node in frame.nodes) {
            val depth = depthOf(node, ids)
            val kind = when (node.kind) {
                RiftAppAbi.NodeKind.ROOT -> "ROOT"
                RiftAppAbi.NodeKind.SURFACE -> "SURFACE"
                RiftAppAbi.NodeKind.TEXT -> "TEXT"
                RiftAppAbi.NodeKind.TEXT_INPUT -> "TEXT_INPUT"
                RiftAppAbi.NodeKind.ACTION -> "ACTION"
                RiftAppAbi.NodeKind.IMAGE -> "IMAGE"
                else -> "UNKNOWN"
            }
            // Terminal projection sanitizes control characters, retains
            // structure and text but never constructs a UI event target.
            val cleanText = node.text.map {
                if (it.code < 0x20 || it.code == 0x7f) ' ' else it
            }.joinToString("")
            lines.add("${"  ".repeat(depth)}$kind #${node.id} parent=${node.parentId} @(${node.x},${node.y}) ${node.width}x${node.height}: $cleanText")
            nodes.put(JSONObject()
                .put("kind", node.kind).put("id", node.id)
                .put("parentId", node.parentId)
                .put("text", node.text)
                .put("x", node.x).put("y", node.y)
                .put("width", node.width).put("height", node.height)
                .put("z", node.z).put("flags", node.flags))
        }
        val rendered = lines.joinToString("\n")
        return describe(watch)
            .put("renderedText", rendered.take(MAX_RENDER_CHARS))
            .put("truncated", rendered.length > MAX_RENDER_CHARS)
            .put("layout", frame.layout)
            .put("nodes", nodes)
    }

    private fun depthOf(
        node: RiftAppAbi.Node,
        ids: Map<Int, RiftAppAbi.Node>
    ): Int {
        var parent = node.parentId
        var depth = 0
        val seen = HashSet<Int>()
        while (parent > 0 && depth < 16 && seen.add(parent)) {
            depth++
            parent = ids[parent]?.parentId ?: break
        }
        return depth
    }

    private fun describe(watch: Watch): JSONObject {
        val frame = watch.frame
        return JSONObject()
            .put("schema", SCHEMA)
            .put("appId", watch.id)
            .put("attachmentGeneration", frame?.attachmentGeneration ?: JSONObject.NULL)
            .put("revision", frame?.revision ?: JSONObject.NULL)
            .put("observedRevision", watch.observedRevision)
            .put("surfacePresent", frame != null)
            .put("updates", watch.updates)
            .put("lastOperation", watch.lastOperation)
    }
}
