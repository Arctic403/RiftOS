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
 * C1.1-A is NOT yet headless application execution. In-flight host effects,
 * event queues, and rendering remain attached to RiftRappHost until the
 * separately device-gated execution/effect broker migration.
 */
class RiftCoreAppSessions {
    companion object {
        private const val MAX_SESSIONS = 128
        private const val MAX_PROGRAM_BYTES = 1024 * 1024
        private const val REPORT_SCHEMA = "riftos.core.sessions/1"
    }

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
            return generation
        }

        @Synchronized
        internal fun detach(token: Long) {
            if (generation == token) attached = false
        }

        @Synchronized
        internal fun isAttached(token: Long): Boolean =
            attached && generation == token

        @Synchronized
        internal fun visible(): Boolean = attached

        @Synchronized
        internal fun sequenceValue(): Int = sequence
    }

    data class Attachment(val record: Record, val token: Long)

    private val sessions = LinkedHashMap<String, Record>()

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
            Record(payload, adapter).also { sessions[payload.id] = it }
        }
        return Attachment(record, record.attach())
    }

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

    @Synchronized
    fun detach(attachment: Attachment) {
        if (sessions[attachment.record.id] === attachment.record) {
            attachment.record.detach(attachment.token)
        }
    }

    @Synchronized
    fun close(attachment: Attachment) {
        if (isAttached(attachment)) {
            sessions.remove(attachment.record.id)
        }
    }

    @Synchronized
    fun summary(): JSONObject {
        val attached = sessions.values.count { it.visible() }
        return JSONObject()
            .put("schema", REPORT_SCHEMA)
            .put("count", sessions.size)
            .put("attached", attached)
            .put("detached", sessions.size - attached)
            .put("headlessExecution", false)
            .put("eventExecutorOwner", "riftos-core")
            .put("capabilityEffectsIndependentOfDesktop", false)
            .put("appExecutionIndependentOfDesktop", false)
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
