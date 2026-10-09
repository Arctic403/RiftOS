package com.riftos.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Binder
import android.os.Process
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        const val METHOD_SHELL_APPS = "shell.apps"
        const val METHOD_SHELL_START = "shell.start"
        const val METHOD_SHELL_STOP = "shell.stop"
        const val METHOD_SHELL_FOCUS = "shell.focus"
        const val METHOD_SHELL_EVENT = "shell.event"
        const val METHOD_SHELL_EXECUTE = "shell.execute"
        const val METHOD_SHELL_INSTALL = "shell.install"
        const val METHOD_SHELL_UNINSTALL = "shell.uninstall"
        const val METHOD_SHELL_UI_POLL = "shell.ui.poll"
        const val METHOD_SHELL_UI_REPLY = "shell.ui.reply"
        const val METHOD_SHELL_DESKTOP_REPORT = "shell.desktop.report"
        const val RESULT_JSON = "coreSnapshotJson"
        private const val MAX_APP_ID = 128
        private const val MAX_FRAME_NODES = 256
        private const val MAX_REPLY_BYTES = 256 * 1024
    }

    override fun onCreate(): Boolean {
        val ctx = context?.applicationContext ?: return false
        RiftCoreRuntime.initialize(ctx)
        RiftCoreShellRemoteUiBroker.ensureRegistered()
        return true
    }

    /**
     * C1.3-D: only a same-UID caller from the exact named remote :riftShell
     * process may mutate Core. The older :riftShellProbe snapshot stays
     * explicitly read-only. Caller-reported process names are NEVER trusted.
     */
    private fun requireProductionShellCaller() {
        val ctx = context ?: error("Core IPC unavailable")
        val uid = Binder.getCallingUid()
        val pid = Binder.getCallingPid()
        require(uid == ctx.applicationInfo.uid && pid > 0 && pid != Process.myPid()) {
            "Core IPC shell caller UID/PID rejected"
        }
        val name = runCatching {
            File("/proc/$pid/cmdline").inputStream().use { source ->
                val bytes = ByteArray(256)
                val count = source.read(bytes)
                require(count > 0) { "Core IPC shell identity unreadable" }
                String(bytes, 0, count, Charsets.UTF_8).substringBefore('\u0000')
            }
        }.getOrNull()
        require(name == ctx.packageName + ":riftShell") {
            "Core IPC caller is not the production RiftShell process"
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context?.applicationContext ?: error("Core IPC context unavailable")
        if (method != METHOD_SNAPSHOT) requireProductionShellCaller()
        val id = arg.orEmpty()
        val response = when (method) {
            METHOD_SNAPSHOT -> {
                require(id.isNotBlank() && id.length <= MAX_APP_ID) { "Core IPC app ID invalid" }
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
                result
            }
            METHOD_SHELL_APPS -> JSONObject()
                .put("schema", "riftos.core.shell-control/1")
                .put("corePid", Process.myPid())
                .put("apps", RiftCoreRuntime.packages(ctx).listInstalled())
            METHOD_SHELL_START -> {
                require(id.isNotBlank() && id.length <= MAX_APP_ID) { "Core IPC app ID invalid" }
                val state = RiftCoreRuntime.lifecycle(ctx).openForShell(id)
                JSONObject().put("schema", "riftos.core.shell-control/1")
                    .put("corePid", Process.myPid())
                    .put("app", state)
            }
            METHOD_SHELL_STOP -> {
                require(id.isNotBlank() && id.length <= MAX_APP_ID) { "Core IPC app ID invalid" }
                val expected = extras?.getLong("attachmentGeneration", -1L) ?: -1L
                require(expected > 0L) { "Core IPC shell stop requires generation" }
                JSONObject().put("schema", "riftos.core.shell-control/1")
                    .put("corePid", Process.myPid())
                    .put("app", RiftCoreRuntime.lifecycle(ctx).stopForShell(id, expected))
            }
            METHOD_SHELL_FOCUS -> {
                require(id.length <= MAX_APP_ID) { "Core IPC focus ID invalid" }
                JSONObject().put("schema", "riftos.core.shell-control/1")
                    .put("corePid", Process.myPid())
                    .put("focus", RiftCoreRuntime.sessions(ctx)
                        .requestFocusFromShell(id.takeIf { it.isNotBlank() }))
            }
            METHOD_SHELL_UI_POLL -> RiftCoreShellRemoteUiBroker.poll()
                .put("corePid", Process.myPid())
                .put("launches", RiftCoreShellLaunchQueue.drain())
                .put("commands", RiftCoreShellWindowBridge.drainCommands(Binder.getCallingPid()))
            METHOD_SHELL_DESKTOP_REPORT -> RiftCoreShellWindowBridge.report(
                Binder.getCallingPid(), extras?.getString("desktopState")
                    ?: error("Core IPC desktop state missing"))
                .put("corePid", Process.myPid())
            METHOD_SHELL_UI_REPLY -> RiftCoreShellRemoteUiBroker.respond(
                extras ?: error("Core shell UI reply envelope missing"))
                .put("corePid", Process.myPid())
            METHOD_SHELL_EXECUTE -> {
                val command = id
                require(command.isNotBlank() &&
                    command.toByteArray(Charsets.UTF_8).size <= 1024) {
                    "Core shell command exceeds bound"
                }
                val cwd = extras?.getString("cwd")?.takeIf { it.isNotBlank() }
                require(cwd == null || cwd.length <= 256) {
                    "Core shell working directory invalid"
                }
                val latch = CountDownLatch(1)
                var response: JSONObject? = null
                RiftMcpRuntime.nativeShell(ctx).execute(command, cwd) { outcome ->
                    response = outcome
                    latch.countDown()
                }
                require(latch.await(5, TimeUnit.SECONDS)) {
                    "Core command response timeout; command may still be executing"
                }
                JSONObject().put("schema", "riftos.core.shell-control/1")
                    .put("corePid", Process.myPid())
                    .put("commandResult", response ?: JSONObject().put("ok", false)
                        .put("error", "Core command produced no response"))
            }
            METHOD_SHELL_INSTALL -> {
                require(id.length in 1..256) { "Core package install path invalid" }
                JSONObject().put("schema", "riftos.core.shell-control/1")
                    .put("corePid", Process.myPid())
                    .put("packageResult", RiftCoreRuntime.buildPlatform(ctx).installRapp(id))
            }
            METHOD_SHELL_UNINSTALL -> {
                require(id.isNotBlank() && id.length <= MAX_APP_ID) {
                    "Core package uninstall identifier invalid"
                }
                JSONObject().put("schema", "riftos.core.shell-control/1")
                    .put("corePid", Process.myPid())
                    .put("packageResult", RiftCoreRuntime.buildPlatform(ctx).uninstallRapp(id))
            }
            METHOD_SHELL_EVENT -> {
                require(id.isNotBlank() && id.length <= MAX_APP_ID) { "Core IPC app ID invalid" }
                val b = extras ?: error("Core IPC event envelope missing")
                require(b.getString("schema") == "riftos.shell.event/1") {
                    "Core IPC event schema invalid"
                }
                val kind = b.getInt("kind", -1)
                require(kind in setOf(
                    RiftAppAbi.EventKind.ACTION, RiftAppAbi.EventKind.TEXT_INPUT,
                    RiftAppAbi.EventKind.POINTER_DOWN, RiftAppAbi.EventKind.POINTER_UP,
                    RiftAppAbi.EventKind.POINTER_MOVE, RiftAppAbi.EventKind.KEY_DOWN,
                    RiftAppAbi.EventKind.KEY_UP, RiftAppAbi.EventKind.DISPLAY_RESIZE
                )) { "Core IPC event kind forbidden" }
                val text = b.getString("text").orEmpty()
                require(text.toByteArray(Charsets.UTF_8).size <= 4096) {
                    "Core IPC event input size exceeded"
                }
                val event = RiftAppAbi.Event(
                    kind = kind, targetId = b.getInt("targetId", -1),
                    arg0 = b.getInt("arg0"), arg1 = b.getInt("arg1"),
                    arg2 = b.getInt("arg2"), arg3 = b.getInt("arg3"),
                    text = text
                )
                val generation = b.getLong("attachmentGeneration", -1L)
                require(generation > 0L) { "Core IPC stale generation" }
                JSONObject().put("schema", "riftos.core.shell-control/1")
                    .put("corePid", Process.myPid())
                    .put("receipt", RiftCoreRuntime.lifecycle(ctx).offerEvent(id, generation, event))
            }
            else -> error("Core IPC method unsupported")
        }
        val encoded = response.toString()
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_REPLY_BYTES) {
            if (method == METHOD_SNAPSHOT) {
                "Core IPC snapshot exceeds bounded Binder payload"
            } else "Core IPC response exceeds Binder size limit"
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
