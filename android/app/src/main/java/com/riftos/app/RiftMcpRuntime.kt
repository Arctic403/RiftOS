package com.riftos.app

import android.content.Context

object RiftMcpRuntime {
    @Volatile private var client: RiftMcpRelayClient? = null

    fun get(context: Context): RiftMcpRelayClient {
        client?.let { return it }
        return synchronized(this) {
            client ?: RiftMcpRelayClient(context.applicationContext).also { client = it }
        }
    }
}
