package com.riftos.app

import android.app.Activity
import android.content.Intent
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject

/**
 * APK-owned graphical Shell seam for separately compiled genuine presentation.
 *
 * The stable Android Activity, process, native input/lifecycle and Core IPC
 * remain in the host. External code MUST implement the actual window manager,
 * desktop and presentation, not delegate execution to the APK's native UI.
 */
interface RiftShellGraphicalComponentV1 {
    fun attach(activity: Activity, container: FrameLayout,
               services: RiftShellPlatformServicesV1, restore: JSONObject?)
    fun onResume()
    fun onPause()
    fun onWindowFocusChanged(focused: Boolean)
    fun onBackPressed(): Boolean
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean
    fun onDestroy()
}

/** Narrow, authenticated Core Binder client exposed to the graphical Shell. */
interface RiftShellPlatformServicesV1 {
    fun installed(): JSONArray
    fun open(id: String): JSONObject
    fun reattach(id: String, generation: Long): JSONObject
    fun stop(id: String, generation: Long): JSONObject
    fun focus(id: String?): JSONObject
    fun snapshot(id: String): JSONObject
    fun event(id: String, generation: Long, payload: JSONObject): JSONObject
    fun installRapp(path: String): JSONObject
    fun uninstallRapp(id: String): JSONObject
    fun pollUi(): JSONObject
    fun respondConsent(ticket: Long, approved: Boolean): Boolean
    fun execute(command: String, cwd: String?): JSONObject
    fun reportDesktop(snapshot: JSONObject): JSONObject
    fun claimRecovery(): JSONObject
}

/**
 * Host adapter routes external presentation to existing Core-side IPC;
 * no RiftCoreRuntime singleton is constructed in the Shell process.
 */
internal class RiftShellPlatformServicesAdapter(
    private val client: RiftShellCoreClient
) : RiftShellPlatformServicesV1 {
    override fun installed(): JSONArray = client.installed()
    override fun open(id: String): JSONObject = client.start(id)
    override fun reattach(id: String, generation: Long): JSONObject =
        client.reattach(id, generation)
    override fun stop(id: String, generation: Long): JSONObject =
        client.stop(id, generation)
    override fun focus(id: String?): JSONObject = client.focus(id)
    override fun snapshot(id: String): JSONObject {
        val published = client.snapshot(id) ?: return JSONObject().put("present", false)
        val nodes = JSONArray()
        for (node in published.frame.nodes.take(256)) {
            nodes.put(JSONObject()
                .put("kind", node.kind).put("id", node.id)
                .put("parentId", node.parentId)
                .put("x", node.x).put("y", node.y)
                .put("width", node.width).put("height", node.height)
                .put("z", node.z).put("flags", node.flags)
                .put("text", node.text))
        }
        return JSONObject().put("present", true)
            .put("appId", id)
            .put("attachmentGeneration", published.generation)
            .put("revision", published.revision)
            .put("layout", published.frame.layout)
            .put("nodes", nodes)
    }
    override fun event(id: String, generation: Long, payload: JSONObject): JSONObject =
        client.offerEvent(id, generation, RiftAppAbi.Event(
            kind = payload.getInt("kind"),
            targetId = payload.getInt("targetId"),
            arg0 = payload.getInt("arg0"), arg1 = payload.getInt("arg1"),
            arg2 = payload.getInt("arg2"), arg3 = payload.getInt("arg3"),
            text = payload.getString("text")))
    override fun installRapp(path: String): JSONObject = client.installRapp(path)
    override fun uninstallRapp(id: String): JSONObject = client.uninstallRapp(id)
    override fun pollUi(): JSONObject = client.pollUi()
    override fun respondConsent(ticket: Long, approved: Boolean): Boolean =
        client.respondConsent(ticket, approved)
    override fun execute(command: String, cwd: String?): JSONObject =
        client.execute(command, cwd)
    override fun reportDesktop(snapshot: JSONObject): JSONObject =
        client.reportDesktop(snapshot)
    override fun claimRecovery(): JSONObject = client.claimRecovery()
}
