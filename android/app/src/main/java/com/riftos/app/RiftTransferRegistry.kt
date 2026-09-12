package com.riftos.app

import java.util.concurrent.Executors

/** Shared transfer service owner for all filesystem subsystems. */
internal object RiftTransferRegistry {
    private val executor = Executors.newSingleThreadExecutor()
    val service = RiftTransferService(executor)
}
