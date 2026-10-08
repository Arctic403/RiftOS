package com.riftos.app

/**
 * Generic in-process Core package catalogue change feed.
 * Installation, replacement and removal publish facts; any shell can subscribe.
 * No Android Activity, View or graphical-shell dependency is stored in Core code.
 * Callers MUST unsubscribe on UI teardown; a later process-isolated shell will
 * consume the versioned package change contract over IPC instead.
 */
object RiftCorePackageEvents {
    const val SCHEMA = "riftos.core.packages.change/1"
    data class Change(val id: String, val operation: String, val sequence: Long)

    private var sequence = 0L
    private var nextSubscription = 0L
    private val listeners = LinkedHashMap<Long, (Change) -> Unit>()

    @Synchronized
    fun subscribe(listener: (Change) -> Unit): Long {
        require(nextSubscription < Long.MAX_VALUE) { "Package listener ID exhausted" }
        val id = ++nextSubscription
        listeners[id] = listener
        return id
    }

    @Synchronized
    fun unsubscribe(id: Long) {
        listeners.remove(id)
    }

    fun publish(id: String, operation: String) {
        require(operation == "installed" || operation == "updated" || operation == "uninstalled") {
            "Unknown Core package operation"
        }
        val (change, observers) = synchronized(this) {
            require(sequence < Long.MAX_VALUE) { "Package change sequence exhausted" }
            Change(id, operation, ++sequence) to listeners.values.toList()
        }
        // Never call UI observers while holding the package registry monitor.
        observers.forEach { observer -> runCatching { observer(change) } }
    }
}

/**
 * Generic Core-to-shell launch request channel. It is separate from package
 * catalogue changes and preserves the old "no live shell host" result.
 * A future replaceable shell may implement this behind Binder/IPC.
 */
object RiftCoreAppLaunchRequests {
    const val SCHEMA = "riftos.core.app-launch/1"
    private var nextSubscription = 0L
    private val handlers = LinkedHashMap<Long, (String) -> Boolean>()

    @Synchronized
    fun subscribe(handler: (String) -> Boolean): Long {
        require(nextSubscription < Long.MAX_VALUE) { "Core launch subscriber ID exhausted" }
        val id = ++nextSubscription
        handlers[id] = handler
        return id
    }

    @Synchronized
    fun unsubscribe(id: Long) {
        handlers.remove(id)
    }

    fun requestLaunch(id: String): Boolean {
        val targets = synchronized(this) { handlers.values.toList() }
        return targets.any { handler ->
            runCatching { handler(id) }.getOrDefault(false)
        }
    }
}
