package com.riftos.app

import java.util.concurrent.ExecutorService

/** Shared transfer boundary used by filesystem subsystems. */
internal class RiftTransferService(
    private val executor: ExecutorService
) {
    private val manager = RiftTransferManager(executor)

    fun submit(job: RiftTransferJob, task: () -> Unit) {
        manager.submit(job, task)
    }

    fun cancel(id: String): Boolean = manager.cancel(id)

    fun get(id: String): RiftTransferJob? = manager.get(id)
}
