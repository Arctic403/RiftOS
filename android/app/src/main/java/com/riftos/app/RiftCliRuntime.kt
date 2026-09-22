package com.riftos.app

import android.content.Context

/** Process-wide RiftCLI runtime. Transport and event ownership are intentionally separate from MCP. */
object RiftCliRuntime {
    @Volatile private var events: RiftCliEventBus? = null
    @Volatile private var relay: RiftCliRelayClient? = null

    fun events(): RiftCliEventBus {
        events?.let { return it }
        return synchronized(this) {
            events ?: RiftCliEventBus(RiftMcpRuntime.debugHub()).also { events = it }
        }
    }

    fun relayClient(context: Context): RiftCliRelayClient {
        relay?.let { return it }
        return synchronized(this) {
            relay ?: RiftCliRelayClient(
                context.applicationContext,
                RiftMcpRuntime.nativeShell(context),
                events(),
                RiftMcpRuntime.debugHub()
            ).also { relay = it }
        }
    }
}
