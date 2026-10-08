package com.riftos.app

import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * C1.1-B2-B: versioned, bounded Core-to-shell consent and UI-effect channel.
 * A shell responds only to a single-use Core-issued request. The shell cannot
 * directly change persisted Core grants. In-process transport until C1.3.
 */
object RiftCoreShellCapabilityRequests {
    const val CONSENT_SCHEMA = "riftos.core.capability-consent/1"
    const val UI_EFFECT_SCHEMA = "riftos.core.ui-effect/1"
    private const val MAX_PENDING = 64
    private const val MAX_PENDING_AGE_MS = 90_000L

    data class Consent(val ticket: Long, val appId: String, val appName: String, val capability: String)
    data class UiEffect(val ticket: Long, val appId: String, val appName: String, val effect: RiftAppAbi.HostEffect)
    interface Client {
        fun showConsent(request: Consent)
        fun executeUiEffect(request: UiEffect)
    }
    private data class Pending(
        val owner: Long,
        val createdAtMs: Long = SystemClock.elapsedRealtime(),
        val consent: ((Boolean) -> Unit)? = null,
        val ui: ((RiftRappCapabilityBroker.Result) -> Unit)? = null
    )
    private var nextClient = 0L
    private var nextTicket = 0L
    private val clients = LinkedHashMap<Long, Client>()
    private val pending = LinkedHashMap<Long, Pending>()

    private val expiryWorker = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "rift-core-shell-consent-expiry").apply { isDaemon = true }
    }
    init {
        expiryWorker.scheduleAtFixedRate({ expirePending() }, 5, 5, TimeUnit.SECONDS)
    }

    private fun expirePending() {
        val timedOut = synchronized(this) {
            val now = SystemClock.elapsedRealtime()
            val expired = pending.filterValues {
                now - it.createdAtMs >= MAX_PENDING_AGE_MS
            }.values.toList()
            pending.entries.removeAll {
                now - it.value.createdAtMs >= MAX_PENDING_AGE_MS
            }
            expired
        }
        timedOut.forEach {
            runCatching { it.consent?.invoke(false) }
            runCatching {
                it.ui?.invoke(RiftRappCapabilityBroker.Result(ok = false, error = "Shell UI request timed out"))
            }
        }
    }

    @Synchronized
    fun subscribe(client: Client): Long {
        check(nextClient < Long.MAX_VALUE)
        nextClient += 1
        clients[nextClient] = client
        return nextClient
    }

    fun unsubscribe(owner: Long) {
        val cancelled = synchronized(this) {
            clients.remove(owner)
            val removed = pending.filterValues { it.owner == owner }.values.toList()
            pending.entries.removeAll { it.value.owner == owner }
            removed
        }
        cancelled.forEach {
            runCatching { it.consent?.invoke(false) }
            runCatching {
                it.ui?.invoke(RiftRappCapabilityBroker.Result(ok = false, error = "Shell UI detached"))
            }
        }
    }

    private fun allocate(callback: (Long) -> Pending): Pair<Long, Client>? = synchronized(this) {
        val target = clients.entries.lastOrNull() ?: return@synchronized null
        if (pending.size >= MAX_PENDING || nextTicket == Long.MAX_VALUE) return@synchronized null
        nextTicket += 1
        pending[nextTicket] = callback(target.key)
        nextTicket to target.value
    }

    fun requestConsent(
        appId: String, appName: String, capability: String, reply: (Boolean) -> Unit
    ) {
        val assigned = allocate { owner -> Pending(owner, consent = reply) }
        if (assigned == null) {
            reply(false)
            return
        }
        val (ticket, client) = assigned
        runCatching { client.showConsent(Consent(ticket, appId, appName, capability)) }
            .onFailure { fail(ticket) }
    }

    fun requestUiEffect(
        appId: String, appName: String, effect: RiftAppAbi.HostEffect,
        reply: (RiftRappCapabilityBroker.Result) -> Unit
    ) {
        val assigned = allocate { owner -> Pending(owner, ui = reply) }
        if (assigned == null) {
            reply(RiftRappCapabilityBroker.Result(ok = false, token = effect.token, error = "No shell UI client"))
            return
        }
        val (ticket, client) = assigned
        runCatching { client.executeUiEffect(UiEffect(ticket, appId, appName, effect)) }
            .onFailure { fail(ticket) }
    }

    private fun fail(ticket: Long) {
        val value = synchronized(this) { pending.remove(ticket) } ?: return
        value.consent?.invoke(false)
        value.ui?.invoke(RiftRappCapabilityBroker.Result(ok = false, error = "Shell UI request failed"))
    }

    fun respondConsent(owner: Long, ticket: Long, granted: Boolean): Boolean {
        val value = synchronized(this) {
            pending[ticket]?.takeIf { it.owner == owner && it.consent != null }
                ?.also { pending.remove(ticket) }
        } ?: return false
        value.consent?.invoke(granted)
        return true
    }

    fun respondUiEffect(owner: Long, ticket: Long, result: RiftRappCapabilityBroker.Result): Boolean {
        val value = synchronized(this) {
            pending[ticket]?.takeIf { it.owner == owner && it.ui != null }
                ?.also { pending.remove(ticket) }
        } ?: return false
        value.ui?.invoke(result)
        return true
    }
}
