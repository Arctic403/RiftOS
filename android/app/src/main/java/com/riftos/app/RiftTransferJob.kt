package com.riftos.app

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 1 transfer primitive.
 * Keeps long-running file operations represented as jobs instead of anonymous work.
 */
internal class RiftTransferJob(
    val operation: String,
    val source: String,
    val destination: String
) {
    val id: String = UUID.randomUUID().toString()
    val cancelled = AtomicBoolean(false)

    @Volatile var phase: String = "queued"
    @Volatile var bytes: Long = 0L
    @Volatile var files: Int = 0
    @Volatile var directories: Int = 0

    fun cancel() {
        cancelled.set(true)
        phase = "cancelled"
    }

    fun isCancelled(): Boolean = cancelled.get()
}
