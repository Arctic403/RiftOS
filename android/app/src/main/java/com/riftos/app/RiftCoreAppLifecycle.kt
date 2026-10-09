package com.riftos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * C1.3-C: the process-owned Core application lifecycle AND event dispatcher.
 * A shell can start/observe/send bounded input but never owns BOOT, the queue,
 * effect continuation, program state or execution. No Android Activity/View.
 * Process isolation and remote shell transport are separate C1.3-D/E gates.
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
        val payload: RiftAppAbi.RuntimePayload,
        val adapter: RiftAppRuntimeAdapter,
        val attachment: RiftCoreAppSessions.Attachment
    ) {
        var state: String = "starting"
        var error: String? = null
    }

    private val active = LinkedHashMap<String, Entry>()
    data class Change(val appId: String, val state: String, val error: String?)
    private val subscribers = LinkedHashMap<Long, (Change) -> Unit>()
    private var nextSubscriber = 0L

    @Synchronized
    fun subscribe(observer: (Change) -> Unit): Long {
        require(subscribers.size < 32) { "Core app state subscription bound reached" }
        check(nextSubscriber < Long.MAX_VALUE) { "Core app state subscription IDs exhausted" }
        nextSubscriber += 1
        subscribers[nextSubscriber] = observer
        return nextSubscriber
    }

    @Synchronized
    fun unsubscribe(subscription: Long) {
        subscribers.remove(subscription)
    }

    private fun notifyState(entry: Entry) {
        val change = Change(entry.id, entry.state, entry.error)
        val listeners = synchronized(this) { subscribers.values.toList() }
        listeners.forEach { listener -> runCatching { listener(change) } }
    }

    init {
        RiftCorePackageEvents.subscribe { change ->
            if (change.operation != "installed") synchronized(this) {
                // Package manager revokes executable sessions on update/uninstall.
                active.remove(change.id)
            }
        }
    }

    /** Core BOOT is identical whether or not a graphical client exists. */
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
        require(active.size < MAX_CORE_APPS) { "Core RAPP lifecycle limit reached" }

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
        val entry = Entry(id, installed.name, payload, adapter, attachment)
        active[id] = entry
        try {
            // Core itself offers/dispatches BOOT. There is no shell callback or
            // window requirement, and later input uses this same FIFO.
            val offered = sessions.offerEvent(
                attachment, RiftAppAbi.Event(kind = RiftAppAbi.EventKind.BOOT)
            )
            if (offered.startNow) dispatch(entry, offered.ticket)
        } catch (error: Throwable) {
            active.remove(id)
            sessions.close(attachment)
            throw error
        }
        return entryJson(entry).put("accepted", true)
    }

    /**
     * A shell requests a presentation of an existing Core app. It cannot
     * supply executable bytes, change generation, claim execution or re-BOOT.
     */
    @Synchronized
    fun openForShell(id: String): JSONObject = start(id)

    /**
     * Core-only input endpoint for an attached generation. All authorization,
     * event ordering, queued-lease rechecks and execution remain within Core.
     * No caller-supplied callbacks, Activity, View or desktop state are kept.
     */
    @Synchronized
    fun offerEvent(id: String, generation: Long, event: RiftAppAbi.Event): JSONObject {
        val entry = active[id] ?: error("Core RAPP is not running")
        require(entry.attachment.token == generation && sessions.isAttached(entry.attachment)) {
            "Core RAPP attachment generation is stale"
        }
        require(entry.state != "failed") { "Core RAPP has failed" }
        val offered = sessions.offerEvent(entry.attachment, event)
        if (offered.startNow) dispatch(entry, offered.ticket)
        return JSONObject()
            .put("schema", SCHEMA)
            .put("accepted", true)
            .put("id", id)
            .put("attachmentGeneration", generation)
            .put("ticketId", offered.ticket.id)
    }

    private fun dispatch(entry: Entry, ticket: RiftCoreAppSessions.EventTicket) {
        if (active[entry.id] !== entry || !sessions.isAttached(entry.attachment)) return
        val denial = runCatching {
            sessions.authorizeQueuedEventDispatch(entry.attachment, ticket)
        }.exceptionOrNull()
        if (denial != null) {
            finish(entry, ticket, denial.message ?: "Core rejected queued application input")
            return
        }
        try {
            executor.executeChained(
                entry.attachment, entry.payload, entry.adapter, ticket.event
            ) { outcome ->
                finish(entry, ticket, outcome.error)
            }
        } catch (error: Throwable) {
            finish(entry, ticket, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun finish(
        entry: Entry,
        ticket: RiftCoreAppSessions.EventTicket,
        error: String?
    ) {
        synchronized(this) {
            if (active[entry.id] !== entry || !sessions.isAttached(entry.attachment)) return
            // An interpreter BOOT that returns without a published frame
            // cannot be presented as a healthy running application.
            val missingBootFrame = ticket.event.kind == RiftAppAbi.EventKind.BOOT &&
                RiftCoreRuntime.surfaces(app).snapshot(entry.id)?.attachmentGeneration !=
                    entry.attachment.token
            val outcomeError = error ?: if (missingBootFrame) {
                "Core RAPP BOOT did not publish a frame"
            } else null
            if (outcomeError == null) {
                entry.state = "running"
                entry.error = null
            } else {
                entry.error = outcomeError
                if (ticket.event.kind == RiftAppAbi.EventKind.BOOT) {
                    entry.state = "failed"
                    sessions.close(entry.attachment)
                }
            }
            // A stale focus ticket is a denied input, not a crashed window.
            // Only terminal BOOT failure needs a shell error presentation;
            // subsequent successful frames arrive through Core surfaces.
            if (ticket.event.kind == RiftAppAbi.EventKind.BOOT && outcomeError != null) {
                notifyState(entry)
            }
            val next = sessions.finishEvent(entry.attachment, ticket)
            if (next != null) dispatch(entry, next)
        }
    }

    /** An explicit user close/stop is a Core operation, not UI destruction. */
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
            .put("fullAppExecutionIndependentOfDesktop", true)
            .put("eventDispatchOwner", "riftos-core")
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
