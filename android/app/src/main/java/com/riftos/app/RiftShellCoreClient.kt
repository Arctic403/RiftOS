package com.riftos.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.ParcelFileDescriptor
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * C1.3-D: production graphical shell's only Core RAPP control transport.
 * Runs in :riftShell, never instantiates a Core runtime, executor or package
 * registry. Android Binder authenticates this exact process on the Core side.
 */
class RiftShellCoreClient(context: Context) {
    companion object {
        const val SCHEMA = "riftos.core.shell-control/1"
        private val uri = Uri.parse("content://" + RiftCoreSurfaceIpcProvider.AUTHORITY)
        private const val MAX_APP_ID = 128
    }
    data class Published(
        val generation: Long,
        val revision: Long,
        val frame: RiftAppAbi.Frame
    )
    private val resolver = context.applicationContext.contentResolver
    private var corePid = -1

    private fun call(method: String, appId: String = "", extras: Bundle? = null): JSONObject {
        val bound = when (method) {
            RiftCoreSurfaceIpcProvider.METHOD_SHELL_EXECUTE -> 1024
            RiftCoreSurfaceIpcProvider.METHOD_SHELL_INSTALL -> 256
            else -> MAX_APP_ID
        }
        require(appId.length <= bound) { "Shell IPC argument exceeds bound" }
        val reply = resolver.call(uri, method, appId, extras)
            ?: error("Core IPC returned no response for $method")
        val encoded = reply.getString(RiftCoreSurfaceIpcProvider.RESULT_JSON)
            ?: error("Core IPC response JSON missing")
        require(encoded.toByteArray(Charsets.UTF_8).size <= 256 * 1024) {
            "Core IPC response exceeds shell client bound"
        }
        val json = JSONObject(encoded)
        val schema = when (method) {
            RiftCoreSurfaceIpcProvider.METHOD_SNAPSHOT -> RiftCoreSurfaceIpcProvider.SCHEMA
            RiftCoreSurfaceIpcProvider.METHOD_SHELL_UI_POLL,
            RiftCoreSurfaceIpcProvider.METHOD_SHELL_UI_REPLY ->
                RiftCoreShellRemoteUiBroker.SCHEMA
            RiftCoreSurfaceIpcProvider.METHOD_SHELL_DESKTOP_REPORT ->
                RiftCoreShellWindowBridge.SCHEMA
            RiftCoreSurfaceIpcProvider.METHOD_SHELL_RECOVERY_CLAIM ->
                RiftCoreShellRecovery.SCHEMA
            // The single real C1.4-C1 transaction returns its own versioned
            // proof schema. Every B consent/status/revoke action retains the
            // original admin-consent schema and all PID checks remain shared.
            RiftCoreSurfaceIpcProvider.METHOD_SHELL_ADMIN_CONSENT ->
                if (extras?.getString("action") == "execute-rollback-proof") {
                    RiftCoreAdminRollbackProof.SCHEMA
                } else if (extras?.getString("action") == "execute-registry-proof") {
                    RiftCoreAdminRegistryProof.SCHEMA
                } else if (extras?.getString("action").orEmpty() in setOf(
                    "execute-probe-stage", "execute-probe-activate")) {
                    RiftCoreAdminConsent.PROBE_SCHEMA
                } else if (extras?.getString("action") ==
                    "execute-module-recovery-proof") {
                    RiftCoreAdminConsent.MODULE_RECOVERY_SCHEMA
                } else if (extras?.getString("action").orEmpty() in setOf(
                    "execute-module-stage", "execute-module-activate")) {
                    RiftCoreAdminConsent.MODULE_OPERATION_SCHEMA
                } else if (extras?.getString("action") == "discover-providers") {
                    "riftos.core.runtime-candidates/1"
                } else {
                    RiftCoreAdminConsent.SCHEMA
                }
            else -> SCHEMA
        }
        require(json.getString("schema") == schema) { "Core IPC schema mismatch" }
        val pid = json.getInt("corePid")
        require(pid > 0 && pid != Process.myPid()) {
            "Shell cannot run in the Core process"
        }
        if (corePid > 0) require(pid == corePid) {
            "Core IPC process identity changed while shell session was active"
        }
        corePid = pid
        return json
    }

    fun corePid(): Int = corePid
    fun installed(): JSONArray =
        call(RiftCoreSurfaceIpcProvider.METHOD_SHELL_APPS).getJSONArray("apps")

    fun start(id: String): JSONObject =
        call(RiftCoreSurfaceIpcProvider.METHOD_SHELL_START, id).getJSONObject("app")

    fun reattach(id: String, generation: Long): JSONObject {
        require(generation > 0L) { "Recovery Core generation invalid" }
        return call(RiftCoreSurfaceIpcProvider.METHOD_SHELL_REATTACH, id,
            Bundle().apply { putLong("attachmentGeneration", generation) })
            .getJSONObject("app")
    }

    fun stop(id: String, generation: Long): JSONObject {
        require(generation > 0L) { "Shell close attachment generation invalid" }
        return call(RiftCoreSurfaceIpcProvider.METHOD_SHELL_STOP, id,
            Bundle().apply { putLong("attachmentGeneration", generation) })
            .getJSONObject("app")
    }

    fun focus(id: String?): JSONObject =
        call(RiftCoreSurfaceIpcProvider.METHOD_SHELL_FOCUS, id.orEmpty())
            .getJSONObject("focus")

    fun pollUi(): JSONObject =
        call(RiftCoreSurfaceIpcProvider.METHOD_SHELL_UI_POLL)

    /**
     * Only called from the trusted native Admin Approvals window, not the
     * terminal/RAPP command surface. All decisions remain Core-authoritative.
     */
    fun adminConsent(
        action: String, ticket: String = "",
        operation: String = "", target: String = "", approved: Boolean = false,
        dexFd: ParcelFileDescriptor? = null, manifestText: String? = null
    ): JSONObject {
        require(action in setOf("request", "decide", "revoke", "consume-proof",
            "execute-rollback-proof", "execute-registry-proof", "discover-providers",
            "execute-probe-stage", "execute-probe-activate",
            "execute-module-stage", "execute-module-activate",
            "execute-module-recovery-proof", "window-closed", "status")) {
            "Invalid trusted admin UI action"
        }
        return call(RiftCoreSurfaceIpcProvider.METHOD_SHELL_ADMIN_CONSENT,
            extras = Bundle().apply {
                putString("schema", "riftos.shell.admin-consent-request/1")
                putString("action", action)
                putString("ticket", ticket)
                putString("operation", operation)
                putString("target", target)
                putBoolean("approved", approved)
                if (dexFd != null) putParcelable("dexFd", dexFd)
                if (manifestText != null) {
                    require(manifestText.toByteArray(Charsets.UTF_8).size in
                        1..RiftCoreModuleManifest.MAX_MANIFEST_BYTES) {
                        "Trusted module manifest exceeds IPC bound"
                    }
                    putString("moduleManifest", manifestText)
                }
            })
    }

    fun claimRecovery(): JSONObject =
        call(RiftCoreSurfaceIpcProvider.METHOD_SHELL_RECOVERY_CLAIM)

    fun reportDesktop(state: JSONObject): JSONObject =
        call(RiftCoreSurfaceIpcProvider.METHOD_SHELL_DESKTOP_REPORT,
            extras = Bundle().apply {
                putString("desktopState", state.toString())
            })

    fun respondConsent(ticket: Long, granted: Boolean): Boolean =
        call(RiftCoreSurfaceIpcProvider.METHOD_SHELL_UI_REPLY, extras = Bundle().apply {
            putString("schema", RiftCoreShellRemoteUiBroker.SCHEMA)
            putLong("ticket", ticket)
            putString("kind", "consent")
            putBoolean("granted", granted)
        }).getBoolean("accepted")

    fun respondEffect(ticket: Long, outcome: RiftRappCapabilityBroker.Result): Boolean {
        require(outcome.bytes.size <= 32 * 1024) { "Shell UI effect bytes exceeded bound" }
        return call(RiftCoreSurfaceIpcProvider.METHOD_SHELL_UI_REPLY,
            extras = Bundle().apply {
                putString("schema", RiftCoreShellRemoteUiBroker.SCHEMA)
                putLong("ticket", ticket)
                putString("kind", "effect")
                putBoolean("ok", outcome.ok)
                putInt("token", outcome.token)
                putString("text", outcome.text.take(4096))
                putString("error", outcome.error?.take(2048))
                putString("bytes", Base64.encodeToString(outcome.bytes, Base64.NO_WRAP))
            }).getBoolean("accepted")
    }

    fun execute(command: String, cwd: String?): JSONObject {
        require(command.isNotBlank() && command.toByteArray(Charsets.UTF_8).size <= 1024) {
            "Shell command size invalid"
        }
        val extras = Bundle().apply { putString("cwd", cwd.orEmpty()) }
        return call(RiftCoreSurfaceIpcProvider.METHOD_SHELL_EXECUTE, command, extras)
            .getJSONObject("commandResult")
    }

    fun installRapp(path: String): JSONObject =
        call(RiftCoreSurfaceIpcProvider.METHOD_SHELL_INSTALL, path)
            .getJSONObject("packageResult")

    fun uninstallRapp(id: String): JSONObject =
        call(RiftCoreSurfaceIpcProvider.METHOD_SHELL_UNINSTALL, id)
            .getJSONObject("packageResult")

    fun offerEvent(id: String, generation: Long, event: RiftAppAbi.Event): JSONObject {
        require(generation > 0L) { "Shell RAPP attachment invalid" }
        // Only ordinary presentation/input events may originate in RiftShell.
        require(event.kind in setOf(
            RiftAppAbi.EventKind.ACTION, RiftAppAbi.EventKind.TEXT_INPUT,
            RiftAppAbi.EventKind.POINTER_DOWN, RiftAppAbi.EventKind.POINTER_UP,
            RiftAppAbi.EventKind.POINTER_MOVE, RiftAppAbi.EventKind.KEY_DOWN,
            RiftAppAbi.EventKind.KEY_UP, RiftAppAbi.EventKind.DISPLAY_RESIZE
        )) { "Shell cannot manufacture Core lifecycle or capability events" }
        require(event.text.toByteArray(Charsets.UTF_8).size <= 4096 &&
            event.bytes.isEmpty()) { "Shell event payload exceeds bounded IPC" }
        val args = Bundle().apply {
            putString("schema", "riftos.shell.event/1")
            putLong("attachmentGeneration", generation)
            putInt("kind", event.kind)
            putInt("targetId", event.targetId)
            putInt("arg0", event.arg0)
            putInt("arg1", event.arg1)
            putInt("arg2", event.arg2)
            putInt("arg3", event.arg3)
            putString("text", event.text)
        }
        return call(RiftCoreSurfaceIpcProvider.METHOD_SHELL_EVENT, id, args)
            .getJSONObject("receipt")
    }

    fun snapshot(id: String): Published? {
        val frame = call(RiftCoreSurfaceIpcProvider.METHOD_SNAPSHOT, id)
        if (!frame.optBoolean("present")) return null
        val nodes = frame.getJSONArray("nodes")
        require(nodes.length() in 1..256) { "Core IPC frame node count invalid" }
        val decoded = ArrayList<RiftAppAbi.Node>(nodes.length())
        for (index in 0 until nodes.length()) {
            val node = nodes.getJSONObject(index)
            decoded.add(RiftAppAbi.Node(
                kind = node.getInt("kind"),
                id = node.getInt("id"), parentId = node.optInt("parentId"),
                x = node.optInt("x"), y = node.optInt("y"),
                width = node.optInt("width"), height = node.optInt("height"),
                z = node.optInt("z"), flags = node.optInt("flags"),
                text = node.optString("text")
            ))
        }
        return Published(
            generation = frame.getLong("attachmentGeneration"),
            revision = frame.getLong("revision"),
            frame = RiftAppAbi.Frame(layout = frame.getInt("layout"), nodes = decoded)
        )
    }
}
