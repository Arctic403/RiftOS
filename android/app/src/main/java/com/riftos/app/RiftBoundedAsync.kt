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
internal object RiftBoundedAsync {
    fun <T> submit(
        executor: ExecutorService,
        watchdog: ScheduledExecutorService,
        timeoutMs: Long,
        timeoutValue: () -> T,
        failureValue: (Throwable) -> T,
        work: () -> T,
        reply: (T) -> Unit
    ) {
        require(timeoutMs > 0L) { "timeoutMs must be positive" }
        val terminal = AtomicBoolean(false)
        val taskRef = AtomicReference<Future<*>?>()
        val timeoutRef = AtomicReference<ScheduledFuture<*>?>()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs

        val task = try {
            executor.submit {
                val value = try {
                    RiftDeadline.runUntil(deadline, work)
                } catch (error: Throwable) {
                    failureValue(error)
                }
                if (terminal.compareAndSet(false, true)) {
                    timeoutRef.get()?.cancel(false)
                    runCatching { reply(value) }
                }
            }
        } catch (error: Throwable) {
            if (terminal.compareAndSet(false, true)) runCatching { reply(failureValue(error)) }
            return
        }
        taskRef.set(task)

        val timeout = try {
            watchdog.schedule({
                if (terminal.compareAndSet(false, true)) {
                    taskRef.get()?.cancel(true)
                    runCatching { reply(timeoutValue()) }
                }
            }, timeoutMs, TimeUnit.MILLISECONDS)
        } catch (error: Throwable) {
            taskRef.get()?.cancel(true)
            if (terminal.compareAndSet(false, true)) {
                runCatching { reply(failureValue(error)) }
            }
            return
        }
        timeoutRef.set(timeout)
        if (terminal.get()) timeout.cancel(false)
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
