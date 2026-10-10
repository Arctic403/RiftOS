package com.riftos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import android.os.Process

/**
 * E0 host-owned ABI. The interface stays in the stable Android APK; a future
 * signed, independently verified Core implementation will implement it.
 *
 * NO dynamic Core/Shell activation is permitted in E0. The only selected
 * implementation is the identical existing embedded Core ownership graph.
 * Authentication, admin consent, IPC bounds and Android components stay host-owned.
 *
 * Data across this seam is only JSON + Android Context. A future ABI revision
 * must narrow the Context into explicit platform services before untrusted code.
 * E0 is a same-UID trusted-component boundary, NOT a security sandbox.
 */
interface RiftCoreComponentV1 {
    fun initialize(context: Context)
    fun snapshot(context: Context, id: String): JSONObject
    fun installed(context: Context): JSONArray
    fun open(context: Context, id: String): JSONObject
    fun reattach(context: Context, id: String, generation: Long): JSONObject
    fun stop(context: Context, id: String, generation: Long): JSONObject
    fun focus(context: Context, id: String?): JSONObject
    fun event(context: Context, id: String, generation: Long, payload: JSONObject): JSONObject
    fun install(context: Context, artifact: String): JSONObject
    fun uninstall(context: Context, id: String): JSONObject
}

/** E0 shell-side portable lifecycle envelope, not activated or connected yet. */
interface RiftShellPresentationV1 {
    fun restore(snapshot: JSONObject): JSONObject
    fun dispatch(method: String, args: JSONObject): JSONObject
    fun snapshot(): JSONObject
    fun close()
}

/** Host-selected Core implementation. E0 intentionally has no setter/loader. */
internal object RiftHostCoreComponents {
    const val CORE_SCHEMA = "riftos.host.core-component/1"
    const val SHELL_SCHEMA = "riftos.host.shell-presentation/1"
    const val ABI_VERSION = 1

    private val embedded: RiftCoreComponentV1 = EmbeddedCoreComponentV1

    fun core(): RiftCoreComponentV1 = embedded

    fun status(): JSONObject = JSONObject()
        .put("schema", CORE_SCHEMA)
        .put("abi", ABI_VERSION)
        .put("selected", "embedded")
        .put("externalCoreEnabled", false)
        .put("externalShellEnabled", false)
}

/** Delegates to the exact production Core graph; no duplicate runtime authority. */
private object EmbeddedCoreComponentV1 : RiftCoreComponentV1 {
    private const val SNAPSHOT_SCHEMA = "riftos.core.surface-ipc/1"
    private const val MAX_FRAME_NODES = 256

    override fun initialize(context: Context) = RiftCoreRuntime.initialize(context)

    override fun snapshot(context: Context, id: String): JSONObject {
        val snapshot = RiftCoreRuntime.surfaces(context).snapshot(id)
        val result = JSONObject()
            .put("schema", SNAPSHOT_SCHEMA)
            .put("owner", "riftos-core")
            .put("corePid", Process.myPid())
            .put("appId", id)
            .put("present", snapshot != null)
        if (snapshot != null) {
            val frame = snapshot.frame
            require(frame.nodes.size <= MAX_FRAME_NODES) { "Core IPC frame node bound" }
            val nodes = JSONArray()
            for (node in frame.nodes) {
                nodes.put(JSONObject()
                    .put("kind", node.kind).put("id", node.id)
                    .put("parentId", node.parentId)
                    .put("x", node.x).put("y", node.y)
                    .put("width", node.width).put("height", node.height)
                    .put("z", node.z).put("flags", node.flags)
                    .put("text", node.text))
            }
            result.put("attachmentGeneration", snapshot.attachmentGeneration)
                .put("revision", snapshot.revision)
                .put("layout", frame.layout)
                .put("nodes", nodes)
        }
        return result
    }

    override fun installed(context: Context): JSONArray =
        RiftCoreRuntime.packages(context).listInstalled()

    override fun open(context: Context, id: String): JSONObject =
        RiftCoreRuntime.lifecycle(context).openForShell(id)

    override fun reattach(context: Context, id: String, generation: Long): JSONObject =
        RiftCoreRuntime.lifecycle(context).reattachForShell(id, generation)

    override fun stop(context: Context, id: String, generation: Long): JSONObject =
        RiftCoreRuntime.lifecycle(context).stopForShell(id, generation)

    override fun focus(context: Context, id: String?): JSONObject =
        RiftCoreRuntime.sessions(context).requestFocusFromShell(id)

    override fun event(
        context: Context, id: String, generation: Long, payload: JSONObject
    ): JSONObject {
        val event = RiftAppAbi.Event(
            kind = payload.getInt("kind"),
            targetId = payload.getInt("targetId"),
            arg0 = payload.getInt("arg0"), arg1 = payload.getInt("arg1"),
            arg2 = payload.getInt("arg2"), arg3 = payload.getInt("arg3"),
            text = payload.getString("text")
        )
        return RiftCoreRuntime.lifecycle(context).offerEvent(id, generation, event)
    }

    override fun install(context: Context, artifact: String): JSONObject =
        RiftCoreRuntime.buildPlatform(context).installRapp(artifact)

    override fun uninstall(context: Context, id: String): JSONObject =
        RiftCoreRuntime.buildPlatform(context).uninstallRapp(id)
}
