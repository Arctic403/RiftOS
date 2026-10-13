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
 * Optional extension for newly built external graphical Shells.
 * Keeping V1 unchanged preserves already staged Shell v0.1/v0.2 DEXes.
 * Android/Core perform caller authentication, authorization and effects.
 */
interface RiftShellPlatformServicesV2 : RiftShellPlatformServicesV1 {
    fun externalAppRequest(request: JSONObject): JSONObject
    /** Pure reply transport to Core's one-use, authenticated UI-effect ticket. */
    fun respondEffect(ticket: Long, response: JSONObject): Boolean
    /** Host-only Android URI/FileProvider transport; always user-confirmed. */
    fun launchApkInstall(path: String): Boolean
}

/**
 * Host adapter routes external presentation to existing Core-side IPC;
 * no RiftCoreRuntime singleton is constructed in the Shell process.
 */
internal class RiftShellPlatformServicesAdapter(
    private val client: RiftShellCoreClient,
    private val activity: Activity
) : RiftShellPlatformServicesV2 {
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

    override fun launchApkInstall(path: String): Boolean {
        require(path.toByteArray(Charsets.UTF_8).size in 1..2048) {
            "APK artifact path invalid"
        }
        val display = RiftVolumePaths.normalizeDisplay(path)
        require(display.startsWith("/D:/Builds/") &&
            display.endsWith(".apk", ignoreCase = true)) {
            "Android installer accepts only D:/Builds/*.apk artifacts"
        }
        val root = java.io.File(activity.filesDir, "riftfs").canonicalFile
        val builds = java.io.File(root, "documents/builds").canonicalFile
        val file = java.io.File(root, RiftVolumePaths.resolveRelative(display)).canonicalFile
        require(file.path.startsWith(builds.path + java.io.File.separator) &&
            file.isFile && file.length() in 1L..(256L * 1024 * 1024)) {
            "APK artifact is missing, unsafe or oversized"
        }
        var node: java.io.File? = file
        while (node != null && node != root) {
            require(!java.nio.file.Files.isSymbolicLink(node.toPath())) {
                "Symlink APK artifact rejected"
            }
            node = node.parentFile
        }
        val identity = activity.packageManager.getPackageArchiveInfo(file.path, 0)
        require(!identity?.packageName.isNullOrBlank() &&
            identity?.packageName != activity.packageName) {
            "Invalid APK identity or attempted RiftOS host replacement"
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            activity, activity.packageName + ".buildapks", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newUri(
                activity.contentResolver, "Verified APK", uri)
        }
        activity.startActivity(intent)
        return true // Installer opened; user still decides whether to install.
    }

    override fun externalAppRequest(request: JSONObject): JSONObject =
        client.externalAppRequest(request)

    override fun respondEffect(ticket: Long, response: JSONObject): Boolean {
        require(ticket > 0L) { "UI effect reply ticket invalid" }
        require(response.toString().toByteArray(Charsets.UTF_8).size <= 48 * 1024 &&
            response.optString("schema") == "riftos.shell.ui-effect-result/1") {
            "Invalid bounded external Shell UI-effect reply"
        }
        val encoded = response.optString("bytesBase64", "")
        require(encoded.length <= 44_000 &&
            encoded.matches(Regex("^[A-Za-z0-9+/]*={0,2}$"))) {
            "External Shell UI-effect bytes encoding invalid"
        }
        val bytes = if (encoded.isEmpty()) ByteArray(0) else
            android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
        require(bytes.size <= 32 * 1024) { "External Shell UI-effect bytes exceed bound" }
        val text = response.optString("text", "")
        val error = response.optString("error", "")
        require(text.toByteArray(Charsets.UTF_8).size <= 4096 &&
            error.toByteArray(Charsets.UTF_8).size <= 2048) {
            "External Shell UI-effect text exceeds Core IPC bound"
        }
        return client.respondEffect(ticket, RiftRappCapabilityBroker.Result(
            ok = response.optBoolean("ok", false),
            token = response.optInt("token"),
            text = text,
            bytes = bytes,
            error = error.ifBlank { null }
        ))
    }
}
