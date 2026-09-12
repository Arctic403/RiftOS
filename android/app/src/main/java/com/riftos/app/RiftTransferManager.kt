package com.riftos.app

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService

/**
 * Phase 2 transfer registry.
 * Keeps transfer lifecycle independent from normal filesystem RPC calls.
 */
internal class RiftTransferManager(private val executor: ExecutorService) {
    private val jobs = ConcurrentHashMap<String, RiftTransferJob>()

    fun register(job: RiftTransferJob): RiftTransferJob {
        jobs[job.id] = job
        return job
    }

    fun submit(job: RiftTransferJob, task: () -> Unit) {
        register(job)
        executor.execute {
            if (job.isCancelled()) return@execute
            job.phase = "running"
            try {
                task()
                if (!job.isCancelled()) job.phase = "complete"
            } catch (error: Throwable) {
                job.phase = "failed"
                throw error
            }
        }
    }

    fun cancel(id: String): Boolean = jobs[id]?.let { it.cancel(); true } ?: false

    fun get(id: String): RiftTransferJob? = jobs[id]
}
