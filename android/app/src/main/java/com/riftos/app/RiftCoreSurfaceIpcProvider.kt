package com.riftos.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject

/**
 * C1.3-A: read-only bounded IPC gateway to Core-owned immutable RAPP frames.
 * Runs in the main RiftOS Core Android process; called from a separate shell
 * proof process through ContentResolver.call (Android Binder transport).
 * No executable, focus, capability, package or input authority is exposed.
 *
 * This is NOT a stable public IPC until signed client authorization, push
 * subscriptions and shell process reconnection are device-proven.
 */
class RiftCoreSurfaceIpcProvider : ContentProvider() {
    companion object {
        const val SCHEMA = "riftos.core.surface-ipc/1"
        const val AUTHORITY = "com.riftos.app.core-surface-ipc"
        const val METHOD_SNAPSHOT = "snapshot"
        const val RESULT_JSON = "coreSnapshotJson"
        private const val MAX_APP_ID = 128
        private const val MAX_FRAME_NODES = 256
        private const val MAX_REPLY_BYTES = 256 * 1024
    }

    override fun onCreate(): Boolean {
        val ctx = context?.applicationContext ?: return false
        RiftCoreRuntime.initialize(ctx)
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        require(method == METHOD_SNAPSHOT) { "Core IPC method unsupported" }
        val id = arg.orEmpty()
        require(id.isNotBlank() && id.length <= MAX_APP_ID) { "Core IPC app ID invalid" }
        val ctx = context?.applicationContext ?: error("Core IPC context unavailable")
        val snapshot = RiftCoreRuntime.surfaces(ctx).snapshot(id)
        val result = JSONObject()
            .put("schema", SCHEMA)
            .put("owner", "riftos-core")
            .put("corePid", Process.myPid())
            .put("appId", id)
            .put("present", snapshot != null)
        if (snapshot != null) {
            val frame = snapshot.frame
            require(frame.nodes.size <= MAX_FRAME_NODES) { "Core IPC frame node bound" }
            val nodes = JSONArray()
            for (node in frame.nodes) {
                nodes.put(
                    JSONObject()
                        .put("kind", node.kind)
                        .put("id", node.id)
                        .put("parentId", node.parentId)
                        .put("x", node.x).put("y", node.y)
                        .put("width", node.width).put("height", node.height)
                        .put("z", node.z).put("flags", node.flags)
                        .put("text", node.text)
                )
            }
            result.put("attachmentGeneration", snapshot.attachmentGeneration)
                .put("revision", snapshot.revision)
                .put("layout", frame.layout)
                .put("nodes", nodes)
        }
        val encoded = result.toString()
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_REPLY_BYTES) {
            "Core IPC snapshot exceeds bounded Binder payload"
        }
        return Bundle().apply { putString(RESULT_JSON, encoded) }
    }

    // All other ContentProvider operations are forbidden. This IPC surface
    // cannot browse filesystem paths, mutate apps or accept user input.
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = throw UnsupportedOperationException("Core IPC query unsupported")

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri, values: ContentValues?
    ): Uri? = throw UnsupportedOperationException("Core IPC insert forbidden")

    override fun delete(
        uri: Uri, selection: String?, selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException("Core IPC delete forbidden")

    override fun update(
        uri: Uri, values: ContentValues?,
        selection: String?, selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException("Core IPC update forbidden")
}
