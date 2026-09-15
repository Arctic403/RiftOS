package com.riftos.app

import android.content.Context

/** Process-wide Rift MCP runtime. */
object RiftMcpRuntime {
    @Volatile private var host: RiftToolHost? = null
    @Volatile private var server: RiftMcpServer? = null
    @Volatile private var relay: RiftMcpRelayClient? = null
    @Volatile private var nativeShell: RiftNativeShell? = null
    @Volatile private var compatibilityShell: RiftShellBridge? = null
    @Volatile private var vortexBridge: RiftVortexBridgeClient? = null

    /** Native process-owned shell authority. This remains available without any WebView. */
    fun shellExecutor(): RiftShellExecutor? = nativeShell

    fun nativeShell(context: Context): RiftNativeShell {
        nativeShell?.let { return it }
        return synchronized(this) {
            nativeShell ?: RiftNativeShell(context.applicationContext).also { shell ->
                compatibilityShell?.let(shell::setCompatibilityFallback)
                nativeShell = shell
                host?.setShellExecutor(shell)
            }
        }
    }

    /** Attach the temporary trusted-shell compatibility executor without making MCP depend on it. */
    fun registerShellBridge(context: Context, bridge: RiftShellBridge) {
        synchronized(this) {
            compatibilityShell = bridge
            val shell = nativeShell(context)
            shell.setCompatibilityFallback(bridge)
            host?.setShellExecutor(shell)
        }
    }

    /** Detach only the matching compatibility executor; native RiftShell stays process-owned. */
    fun unregisterShellBridge(bridge: RiftShellBridge) {
        synchronized(this) {
            if (compatibilityShell === bridge) {
                compatibilityShell = null
                nativeShell?.clearCompatibilityFallback(bridge)
            }
        }
    }

    fun toolHost(context: Context): RiftToolHost {
        host?.let { return it }
        return synchronized(this) {
            host ?: RiftToolHost(context.applicationContext, nativeShell(context)).also { host = it }
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

    /** Process-wide Vortex Binder client so Activity recreation cannot tear down a live dev session/job. */
    fun vortexBridge(context: Context): RiftVortexBridgeClient {
        vortexBridge?.let { return it }
        return synchronized(this) {
            vortexBridge ?: RiftVortexBridgeClient(context.applicationContext).also { vortexBridge = it }
        }
    }
}
