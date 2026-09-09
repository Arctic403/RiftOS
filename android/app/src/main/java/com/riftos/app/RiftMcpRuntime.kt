package com.riftos.app

import android.content.Context

/** Process-wide Rift tool host plus transport adapters. */
object RiftMcpRuntime {
    @Volatile private var host: RiftToolHost? = null
    @Volatile private var client: RiftMcpRelayClient? = null

    fun toolHost(context: Context): RiftToolHost {
        host?.let { return it }
        return synchronized(this) {
            host ?: RiftToolHost(context.applicationContext).also { host = it }
        }
    }

    fun get(context: Context): RiftMcpRelayClient {
        client?.let { return it }
        return synchronized(this) {
            client ?: RiftMcpRelayClient(
                context.applicationContext,
                toolHost(context)
            ).also { client = it }
        }
    }
}
