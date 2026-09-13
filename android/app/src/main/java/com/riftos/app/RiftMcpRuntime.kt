package com.riftos.app

import android.content.Context

/** Process-wide Rift MCP runtime. */
object RiftMcpRuntime {
    @Volatile private var host: RiftToolHost? = null
    @Volatile private var server: RiftMcpServer? = null
    @Volatile private var relay: RiftMcpRelayClient? = null
    @Volatile private var shellBridge: RiftShellBridge? = null

    fun shellBridge(): RiftShellBridge? = shellBridge

    fun registerShellBridge(bridge: RiftShellBridge) {
        shellBridge = bridge
        host?.setShellBridge(bridge)
    }

    fun toolHost(context: Context): RiftToolHost {
        host?.let { return it }
        return synchronized(this) {
            host ?: RiftToolHost(context.applicationContext, shellBridge).also { host = it }
        }
    }

    fun server(context: Context): RiftMcpServer {
        server?.let { return it }
        return synchronized(this) {
            server ?: RiftMcpServer(toolHost(context)).also { server = it }
        }
    }

    fun relayClient(context: Context): RiftMcpRelayClient {
        relay?.let { return it }
        return synchronized(this) {
            relay ?: RiftMcpRelayClient(context.applicationContext, server(context)).also { relay = it }
        }
    }
}
