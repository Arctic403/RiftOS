package com.riftos.app

import android.os.SystemClock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Shared bounded-async primitive for local RiftOS request paths.
 *
 * A caller receives exactly one terminal callback. The deadline starts when work is submitted,
 * not when a queued worker eventually begins. Timeout interrupts the worker and cooperative
 * long-running code can call RiftDeadline.check() to stop promptly.
 */
class RiftAsyncHandle internal constructor(
    private val terminal: AtomicBoolean = AtomicBoolean(false),
    private val taskRef: AtomicReference<Future<*>?> = AtomicReference(null),
    private val timeoutRef: AtomicReference<ScheduledFuture<*>?> = AtomicReference(null)
) {
    fun cancel(): Boolean {
        if (!terminal.compareAndSet(false, true)) return false
        timeoutRef.get()?.cancel(false)
        taskRef.get()?.cancel(true)
        return true
    }

    internal fun attachTask(task: Future<*>) {
        taskRef.set(task)
        if (terminal.get()) task.cancel(true)
    }

    internal fun attachTimeout(timeout: ScheduledFuture<*>) {
        timeoutRef.set(timeout)
        if (terminal.get()) timeout.cancel(false)
    }

    internal fun tryComplete(): Boolean = terminal.compareAndSet(false, true)

    internal fun cancelTimeout() {
        timeoutRef.get()?.cancel(false)
    }

    companion object {
        fun completed(): RiftAsyncHandle = RiftAsyncHandle(AtomicBoolean(true))
    }
}

internal object RiftBoundedAsync {
    fun <T> submit(
        executor: ExecutorService,
        watchdog: ScheduledExecutorService,
        timeoutMs: Long,
        timeoutValue: () -> T,
        failureValue: (Throwable) -> T,
        work: () -> T,
        reply: (T) -> Unit
    ): RiftAsyncHandle {
        require(timeoutMs > 0L) { "timeoutMs must be positive" }
        val handle = RiftAsyncHandle()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs

        val task = try {
            executor.submit {
                val value = try {
                    RiftDeadline.runUntil(deadline, work)
                } catch (error: Throwable) {
                    failureValue(error)
                }
                if (handle.tryComplete()) {
                    handle.cancelTimeout()
                    runCatching { reply(value) }
                }
            }
        } catch (error: Throwable) {
            if (handle.tryComplete()) runCatching { reply(failureValue(error)) }
            return handle
        }
        handle.attachTask(task)

        val timeout = try {
            watchdog.schedule({
                if (handle.tryComplete()) {
                    task.cancel(true)
                    runCatching { reply(timeoutValue()) }
                }
            }, timeoutMs, TimeUnit.MILLISECONDS)
        } catch (error: Throwable) {
            task.cancel(true)
            if (handle.tryComplete()) {
                runCatching { reply(failureValue(error)) }
            }
            return handle
        }
        handle.attachTimeout(timeout)
        return handle
    }
}

/** Cooperative deadline visible to deep filesystem/runtime loops on the active worker thread. */
internal object RiftDeadline {
    private val deadlineMs = ThreadLocal<Long?>()

    fun <T> runUntil(deadlineElapsedRealtimeMs: Long, block: () -> T): T {
        val previous = deadlineMs.get()
        val effective = previous?.let { minOf(it, deadlineElapsedRealtimeMs) } ?: deadlineElapsedRealtimeMs
        deadlineMs.set(effective)
        return try {
            check("request")
            block()
        } finally {
            if (previous == null) deadlineMs.remove() else deadlineMs.set(previous)
        }
    }

    fun check(label: String = "operation") {
        val expired = deadlineMs.get()?.let { SystemClock.elapsedRealtime() >= it } == true
        if (Thread.currentThread().isInterrupted || expired) {
            throw InterruptedException("$label cancelled or timed out")
        }
    }

    fun clearInterrupt() {
        Thread.interrupted()
    }
}
