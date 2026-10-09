package com.riftos.app

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Core-owned in-process installed-RAPP session registry.
 *
 * This owns session identity, opaque program state and event sequence across
 * desktop Activity recreation. A graphical host only ATTACHES to a record.
 * No Activity, View, RiftShell or Android GUI references are stored here.
 *
 * C1.3-C: a process-owned lifecycle controller now drives queued events and
 * execution; UI clients only submit requests and consume immutable surfaces.
 * This does not imply independent Android process survival or recovery.
 */
class RiftCoreAppSessions(private val surfaces: RiftCoreAppSurfaces) {
    companion object {
        private const val MAX_SESSIONS = 128
        private const val MAX_PENDING_EVENTS = 64
        private const val MAX_PENDING_EVENT_BYTES = 1024 * 1024
        private const val MAX_PROGRAM_BYTES = 1024 * 1024
        private const val REPORT_SCHEMA = "riftos.core.sessions/1"
    }

    data class EventTicket(
        val id: Long,
        val event: RiftAppAbi.Event,
        val admittedFocusRevision: Long? = null
    )
    data class OfferedEvent(val ticket: EventTicket, val startNow: Boolean)

    class Record internal constructor(
        payload: RiftAppAbi.RuntimePayload,
        val adapter: RiftAppRuntimeAdapter
    ) {
        val id: String = payload.id
        val name: String = payload.name
        internal val abi = payload.abi
        internal val adapterId = payload.adapter
        internal val presentation = payload.presentation
        internal val permissions = payload.permissions.toSet()
        internal val runtimeDigest =
            MessageDigest.getInstance("SHA-256").digest(payload.runtime)

        private var state = payload.program.copyOf()
        private var sequence = 1
        private var generation = 0L
        private var attached = false
        private var runningEvent: Long? = null
        private val waitingEvents = ArrayDeque<EventTicket>()
        private var pendingBytes = 0
        private var nextTicketId = 1L

        private fun eventBytes(event: RiftAppAbi.Event) =
            event.text.toByteArray(Charsets.UTF_8).size + event.bytes.size

        // Core owns event ordering; never retain UI callbacks or Views here.
        internal fun offer(event: RiftAppAbi.Event, admittedFocusRevision: Long?): OfferedEvent {
            require(nextTicketId < Long.MAX_VALUE) {
                "RAPP Core event ticket sequence exhausted"
            }
            val copied = event.copy(bytes = event.bytes.copyOf())
            if (runningEvent == null) {
                val ticket = EventTicket(nextTicketId++, copied, admittedFocusRevision)
                runningEvent = ticket.id
                return OfferedEvent(ticket, true)
            }
            require(waitingEvents.size < MAX_PENDING_EVENTS) {
                "RAPP pending event queue exceeded bound"
            }
            val bytes = eventBytes(copied)
            require(bytes <= MAX_PENDING_EVENT_BYTES - pendingBytes) {
                "RAPP Core pending event bytes exceeded bound"
            }
            val ticket = EventTicket(nextTicketId++, copied, admittedFocusRevision)
            waitingEvents.addLast(ticket)
            pendingBytes += bytes
            return OfferedEvent(ticket, false)
        }

        internal fun finish(ticket: EventTicket): EventTicket? {
            if (runningEvent != ticket.id) return null
            val next = waitingEvents.removeFirstOrNull()
            if (next != null) pendingBytes -= eventBytes(next.event)
            runningEvent = next?.id
            return next
        }

        internal fun resetPendingEvents() {
            runningEvent = null
            waitingEvents.clear()
            pendingBytes = 0
        }

        internal fun queuedCount(): Int =
            waitingEvents.size + if (runningEvent == null) 0 else 1

        @Synchronized
        fun programSnapshot(): ByteArray = state.copyOf()

        @Synchronized
        fun commitState(value: ByteArray) {
            require(value.size in 1..MAX_PROGRAM_BYTES) {
                "RAPP Core program state exceeds maximum size"
            }
            state = value.copyOf()
        }

        @Synchronized
        fun nextEventSequence(): Int {
            require(sequence in 1 until Int.MAX_VALUE) {
                "RAPP Core event sequence exhausted"
            }
            return sequence++
        }

        @Synchronized
        internal fun attach(): Long {
            require(generation < Long.MAX_VALUE) {
                "RAPP Core attachment generation exhausted"
            }
            generation++
            attached = true
            resetPendingEvents()
            return generation
        }

        @Synchronized
        internal fun detach(token: Long) {
            if (generation == token) {
                attached = false
                resetPendingEvents()
            }
        }

        @Synchronized
        internal fun isAttached(token: Long): Boolean =
            attached && generation == token

        @Synchronized
        internal fun activeGeneration(): Long? = generation.takeIf { attached }

        @Synchronized
        internal fun visible(): Boolean = attached

        @Synchronized
        internal fun sequenceValue(): Int = sequence
    }

    data class Attachment(val record: Record, val token: Long)

    private val sessions = LinkedHashMap<String, Record>()
    private val inputFocus = RiftCoreInputFocus()

    @Synchronized
    fun attach(
        payload: RiftAppAbi.RuntimePayload,
        adapter: RiftAppRuntimeAdapter
    ): Attachment {
        require(payload.id.isNotBlank()) { "RAPP Core session ID is missing" }
        require(payload.program.size in 1..MAX_PROGRAM_BYTES) {
            "RAPP Core initial program state is out of bounds"
        }
        val previous = sessions[payload.id]
        // A new installed program/runtime invalidates previous in-memory state.
        // Persisted program state is still loaded and verified by RiftRappManager.
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.runtime)
        val compatible = previous != null &&
            previous.abi == payload.abi &&
            previous.adapterId == payload.adapter &&
            previous.presentation == payload.presentation &&
            previous.name == payload.name &&
            previous.permissions == payload.permissions &&
            previous.runtimeDigest.contentEquals(digest)
        val record = if (compatible) {
            previous!!
        } else {
            require(previous != null || sessions.size < MAX_SESSIONS) {
                "RAPP Core session registry is full"
            }
            Record(payload, adapter).also {
                surfaces.remove(payload.id)
                sessions[payload.id] = it
            }
        }
        inputFocus.revoke(payload.id)
        return Attachment(record, record.attach())
    }

    /**
     * Replaceable shell requests focus for its foreground window ID. Core
     * resolves only live attached sessions; unregistered/system windows clear
     * the previous RAPP focus lease. A shell cannot supply its own generation.
     */
    @Synchronized
    fun requestFocusFromShell(windowId: String?): JSONObject {
        val record = windowId?.let { sessions[it] }
        inputFocus.requestVerified(record?.id, record?.activeGeneration())
        return inputFocus.status()
    }

    @Synchronized
    fun focusStatus(): JSONObject = inputFocus.status()

    /** Prevent Core-only starts from replacing a live graphical attachment. */
    @Synchronized
    fun hasAttachedApp(id: String): Boolean = sessions[id]?.activeGeneration() != null

    @Synchronized
    fun isAttached(attachment: Attachment): Boolean =
        sessions[attachment.record.id] === attachment.record &&
            attachment.record.isAttached(attachment.token)

    /**
     * Bounded commit only for the current UI attachment. Persist before memory
     * promotion; hold the registry lock across both so a late worker cannot
     * commit state after the UI has detached/replaced its generation.
     */
    @Synchronized
    fun commitFromExecution(
        attachment: Attachment,
        value: ByteArray,
        persist: (ByteArray) -> Unit
    ) {
        require(isAttached(attachment)) {
            "Detached RAPP event cannot commit Core state"
        }
        require(value.size in 1..MAX_PROGRAM_BYTES) {
            "RAPP Core program state exceeds maximum size"
        }
        if (!value.contentEquals(attachment.record.programSnapshot())) {
            val copy = value.copyOf()
            persist(copy)
            attachment.record.commitState(copy)
        }
    }

    /** Core never trusts a UI client to supply an unrelated executable. */
    @Synchronized
    fun matchesExecution(
        attachment: Attachment,
        payload: RiftAppAbi.RuntimePayload,
        adapter: RiftAppRuntimeAdapter
    ): Boolean {
        if (!isAttached(attachment)) return false
        val record = attachment.record
        return record.id == payload.id &&
            record.name == payload.name &&
            record.abi == payload.abi &&
            record.adapterId == payload.adapter &&
            record.adapterId == adapter.id &&
            record.presentation == payload.presentation &&
            record.permissions == payload.permissions &&
            MessageDigest.isEqual(
                record.runtimeDigest,
                MessageDigest.getInstance("SHA-256").digest(payload.runtime)
            )
    }

    /** Publish only for the currently installed executable + attachment generation. */
    @Synchronized
    fun publishSurfaceFromExecution(
        attachment: Attachment,
        payload: RiftAppAbi.RuntimePayload,
        adapter: RiftAppRuntimeAdapter,
        frame: RiftAppAbi.Frame
    ): Long {
        require(matchesExecution(attachment, payload, adapter)) {
            "Stale Core session cannot publish a surface"
        }
        return surfaces.publish(attachment.record.id, attachment.token, frame)
    }

    /**
     * C1.2-B2-A: input targets are validated against Core's current immutable
     * surface and the current executable generation, not shell View tags.
     * Pointer canvas input and global keyboard input may target id=0; named
     * ACTION/TEXT_INPUT events must target their matching published node kind.
     * Core separately checks the current focus lease for user input.
     * This is NOT headless execution or separate-shell process proof.
     */
    private fun authorizeInputTarget(attachment: Attachment, event: RiftAppAbi.Event) {
        require(event.kind != RiftAppAbi.EventKind.HOST_EFFECT_RESULT) {
            "Host effect result events are Core-internal"
        }
        require(attachment.record.adapter.supportsEventKind(event.kind)) {
            "App runtime adapter does not accept requested event kind"
        }
        val expected = when (event.kind) {
            RiftAppAbi.EventKind.ACTION -> RiftAppAbi.NodeKind.ACTION
            RiftAppAbi.EventKind.TEXT_INPUT -> RiftAppAbi.NodeKind.TEXT_INPUT
            else -> null
        }
        val isNamedKeyboard = (event.kind == RiftAppAbi.EventKind.KEY_DOWN ||
            event.kind == RiftAppAbi.EventKind.KEY_UP) && event.targetId != 0
        if (expected == null && !isNamedKeyboard) return
        require(event.targetId > 0) { "RAPP named input target is missing" }
        val surface = surfaces.snapshot(attachment.record.id)
        require(surface != null && surface.attachmentGeneration == attachment.token) {
            "RAPP Core input surface is stale or missing"
        }
        require(surface.frame.nodes.any {
            it.id == event.targetId && (expected == null || it.kind == expected)
        }) {
            "RAPP Core input target is absent or has the wrong kind"
        }
    }

    /** Focus is required only for real user input, never boot/resize/lifecycle. */
    private fun isFocusedInput(kind: Int): Boolean = when (kind) {
        RiftAppAbi.EventKind.ACTION,
        RiftAppAbi.EventKind.POINTER_DOWN,
        RiftAppAbi.EventKind.POINTER_UP,
        RiftAppAbi.EventKind.POINTER_MOVE,
        RiftAppAbi.EventKind.KEY_DOWN,
        RiftAppAbi.EventKind.KEY_UP,
        RiftAppAbi.EventKind.TEXT_INPUT -> true
        else -> false
    }

    private fun authorizeInputFocus(attachment: Attachment, event: RiftAppAbi.Event) {
        if (isFocusedInput(event.kind)) {
            inputFocus.requireCurrentLease(attachment.record.id, attachment.token)
        }
    }

    /** Called again as a queued event is dispatched, after any focus switch. */
    @Synchronized
    fun authorizeQueuedEventDispatch(attachment: Attachment, ticket: EventTicket) {
        require(isAttached(attachment)) { "RAPP Core event attachment is stale" }
        val event = ticket.event
        authorizeInputTarget(attachment, event)
        authorizeInputFocus(attachment, event)
        if (isFocusedInput(event.kind)) {
            require(ticket.admittedFocusRevision == inputFocus.current()?.revision) {
                "RAPP Core queued input belongs to an expired focus lease"
            }
        }
    }

    /**
     * Core-owned bounded FIFO: graphical shell submits an input request, but
     * the Core session registry authorizes its kind/target before queueing.
     */
    @Synchronized
    fun offerEvent(attachment: Attachment, event: RiftAppAbi.Event): OfferedEvent {
        require(isAttached(attachment)) { "RAPP Core event attachment is stale" }
        authorizeInputTarget(attachment, event)
        authorizeInputFocus(attachment, event)
        val focusRevision = if (isFocusedInput(event.kind)) {
            inputFocus.current()?.revision
        } else null
        return attachment.record.offer(event, focusRevision)
    }

    @Synchronized
    fun finishEvent(attachment: Attachment, ticket: EventTicket): EventTicket? {
        if (!isAttached(attachment)) return null
        return attachment.record.finish(ticket)
    }

    @Synchronized
    fun detach(attachment: Attachment) {
        if (sessions[attachment.record.id] === attachment.record) {
            if (attachment.record.isAttached(attachment.token)) {
                inputFocus.revoke(attachment.record.id)
            }
            attachment.record.detach(attachment.token)
        }
    }

    @Synchronized
    fun close(attachment: Attachment) {
        if (isAttached(attachment)) {
            sessions.remove(attachment.record.id)
            inputFocus.revoke(attachment.record.id)
            surfaces.remove(attachment.record.id)
        }
    }

    /** Package update/removal invalidates every attachment and queued event. */
    @Synchronized
    fun invalidateInstalled(id: String): Boolean {
        val record = sessions.remove(id) ?: return false
        record.resetPendingEvents()
        inputFocus.revoke(id)
        surfaces.remove(id)
        return true
    }

    @Synchronized
    fun summary(): JSONObject {
        val attached = sessions.values.count { it.visible() }
        return JSONObject()
            .put("schema", REPORT_SCHEMA)
            .put("count", sessions.size)
            .put("attached", attached)
            .put("detached", sessions.size - attached)
            .put("queuedEvents", sessions.values.sumOf { it.queuedCount() })
            .put("eventQueueOwner", "riftos-core")
            .put("headlessExecution", true)
            .put("eventExecutorOwner", "riftos-core")
            .put("capabilityEffectsIndependentOfDesktop", true)
            .put("appExecutionIndependentOfDesktop", true)
    }

    @Synchronized
    fun list(): JSONObject {
        val rows = JSONArray()
        for (record in sessions.values) {
            rows.put(
                JSONObject()
                    .put("id", record.id)
                    .put("name", record.name)
                    .put("adapter", record.adapter.id)
                    .put("attached", record.visible())
                    .put("nextEventSequence", record.sequenceValue())
            )
        }
        return summary().put("sessions", rows)
    }
}
