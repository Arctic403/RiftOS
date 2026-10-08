package com.riftos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * C1.2-C1: process-owned generic RAPP bootstrap and lifecycle, independent of
 * any RiftShell window or Android Activity. The shell is only an optional
 * control client; Core owns package verification, attachment and BOOT.
 *
 * A separate graphical attach-to-running-session protocol is a later gate.
 * This does not imply Android process or native provider isolation.
 */
class RiftCoreAppLifecycle(context: Context) {
    companion object {
        const val SCHEMA = "riftos.core.apps/1"
        private const val MAX_CORE_APPS = 32
    }

    private val app = context.applicationContext
    private val packages = RiftCoreRuntime.packages(app)
    private val sessions = RiftCoreRuntime.sessions(app)
    private val executor = RiftCoreRuntime.appExecutor(app)

    private class Entry(
        val id: String,
        val name: String,
        val attachment: RiftCoreAppSessions.Attachment
    ) {
        var state: String = "starting"
        var error: String? = null
    }

    private val active = LinkedHashMap<String, Entry>()

    init {
        RiftCorePackageEvents.subscribe { change ->
            if (change.operation != "installed") synchronized(this) {
                // Core package manager independently invalidates the session
                // and its published surface. Do not hold stale lifecycle data.
                active.remove(change.id)
            }
        }
    }

    /**
     * Start an installed RAPP entirely in Core. Do not take ownership away
     * from an existing graphical attachment of the same application.
     */
    @Synchronized
    fun start(id: String): JSONObject {
        val prior = active[id]
        if (prior != null && sessions.isAttached(prior.attachment)) {
            return entryJson(prior).put("accepted", false).put("reason", "already-started")
        }
        require(!sessions.hasAttachedApp(id)) {
            "RAPP is already attached to another execution client"
        }
        active.remove(id)
        require(active.size < MAX_CORE_APPS) { "Core-only RAPP lifecycle limit reached" }

        val installed = packages.loadInstalled(id)
        require(installed.abi == RiftAppAbi.SCHEMA) { "Unsupported RAPP ABI" }
        val adapter = RiftAppAdapters.require(installed.adapter)
        require(adapter.presentation == installed.presentation) {
            "Installed RAPP presentation does not match runtime adapter"
        }
        val payload = RiftAppAbi.RuntimePayload(
            id = installed.id,
            name = installed.name,
            abi = installed.abi,
            adapter = installed.adapter,
            presentation = installed.presentation,
            permissions = installed.permissions,
            program = installed.program,
            runtime = installed.runtime
        )
        val attachment = sessions.attach(payload, adapter)
        val entry = Entry(id, installed.name, attachment)
        active[id] = entry
        try {
            executor.executeChained(
                attachment, payload, adapter,
                RiftAppAbi.Event(kind = RiftAppAbi.EventKind.BOOT)
            ) { outcome ->
                synchronized(this) {
                    if (active[id] !== entry) return@synchronized
                    if (outcome.error == null &&
                        outcome.frame != null &&
                        sessions.isAttached(attachment)
                    ) {
                        entry.state = "running"
                        entry.error = null
                    } else {
                        entry.state = "failed"
                        entry.error = outcome.error ?: "Core RAPP boot did not publish a frame"
                        sessions.close(attachment)
                    }
                }
            }
        } catch (error: Throwable) {
            active.remove(id)
            sessions.close(attachment)
            throw error
        }
        return entryJson(entry).put("accepted", true)
    }

    /**
     * Transfer an already-running Core app to a graphical presentation
     * without changing its attachment generation, program, or surface.
     * No new BOOT event is executed on this path.
     *
     * Matching installed program identity is checked before ownership moves.
     */
    @Synchronized
    fun claimForShell(
        payload: RiftAppAbi.RuntimePayload,
        adapter: RiftAppRuntimeAdapter
    ): RiftCoreAppSessions.Attachment? {
        val entry = active[payload.id] ?: return null
        require(entry.state == "running") { "Core RAPP is not ready for shell attachment" }
        require(sessions.matchesExecution(entry.attachment, payload, adapter)) {
            "Core RAPP shell attachment executable identity mismatch"
        }
        val surface = RiftCoreRuntime.surfaces(app).snapshot(payload.id)
        require(surface != null &&
            surface.attachmentGeneration == entry.attachment.token
        ) { "Core RAPP surface is not ready for shell attachment" }
        active.remove(payload.id)
        return entry.attachment
    }

    @Synchronized
    fun stop(id: String): JSONObject {
        val entry = active.remove(id)
        if (entry != null && sessions.isAttached(entry.attachment)) {
            sessions.close(entry.attachment)
        }
        return JSONObject()
            .put("schema", SCHEMA)
            .put("id", id)
            .put("stopped", entry != null)
            .put("state", "stopped")
    }

    @Synchronized
    fun status(): JSONObject {
        val list = JSONArray()
        for (entry in active.values) list.put(entryJson(entry))
        return JSONObject()
            .put("schema", SCHEMA)
            .put("owner", "riftos-core")
            .put("count", active.size)
            .put("apps", list)
            .put("coreOnlyBootSupported", true)
            .put("fullAppExecutionIndependentOfDesktop", false)
            .put("shellClientProtocol", "in-process")
            .put("separateCoreProcess", false)
    }

    private fun entryJson(entry: Entry): JSONObject {
        val attached = sessions.isAttached(entry.attachment)
        return JSONObject()
            .put("schema", SCHEMA)
            .put("id", entry.id)
            .put("name", entry.name)
            .put("state", if (!attached && entry.state == "running") "stale" else entry.state)
            .put("attachmentGeneration", entry.attachment.token)
            .put("attached", attached)
            .put("error", entry.error ?: JSONObject.NULL)
    }
}
