package com.riftos.app

import android.content.Context
import java.lang.ref.WeakReference

/** Process-wide Rift MCP runtime. */
object RiftMcpRuntime {
    @Volatile private var host: RiftToolHost? = null
    @Volatile private var server: RiftMcpServer? = null
    @Volatile private var relay: RiftMcpRelayClient? = null
    @Volatile private var nativeShell: RiftNativeShell? = null
    @Volatile private var vortexBridge: RiftVortexBridgeClient? = null
    @Volatile private var nativeGit: RiftNativeGit? = null
    @Volatile private var debugHub: RiftDebugHub? = null
    @Volatile private var operationJournal: RiftMcpOperationJournal? = null
    @Volatile private var mcpEvents: RiftMcpEventBus? = null
    @Volatile private var activityRef: WeakReference<MainActivity>? = null

    fun registerActivity(activity: MainActivity) {
        activityRef = WeakReference(activity)
    }

    fun unregisterActivity(activity: MainActivity) {
        val current = activityRef?.get()
        if (current === activity) activityRef = null
    }

    fun activeActivity(): MainActivity? = activityRef?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    /** Process-wide bounded RiftCLI event stream. This owns no execution authority. */
    fun mcpEvents(): RiftMcpEventBus {
        mcpEvents?.let { return it }
        return synchronized(this) {
            mcpEvents ?: RiftMcpEventBus(debugHub()).also { mcpEvents = it }
        }
    }

    /** Passive process-wide diagnostics. This object owns no execution authority. */
    fun debugHub(): RiftDebugHub {
        debugHub?.let { return it }
        return synchronized(this) {
            debugHub ?: RiftDebugHub().also { debugHub = it }
        }
    }

    /** Persistent device-owned MCP operation/reconciliation journal. */
    fun operationJournal(context: Context): RiftMcpOperationJournal {
        operationJournal?.let { return it }
        return synchronized(this) {
            operationJournal ?: RiftMcpOperationJournal(context.applicationContext).also { operationJournal = it }
        }
    }

    /** Native process-owned shell authority. This remains available without any WebView. */
    fun shellExecutor(): RiftShellExecutor? = nativeShell

    fun nativeShell(context: Context): RiftNativeShell {
        nativeShell?.let { return it }
        return synchronized(this) {
            nativeShell ?: RiftNativeShell(context.applicationContext).also { shell ->
                nativeShell = shell
                host?.setShellExecutor(shell)
            }
        }
    }

    fun toolHost(context: Context): RiftToolHost {
        host?.let { return it }
        return synchronized(this) {
            host ?: RiftToolHost(
                context.applicationContext,
                nativeShell(context),
                debugHub(),
                operationJournal(context)
            ).also { host = it }
        }
    }

    fun server(context: Context): RiftMcpServer {
        server?.let { return it }
        return synchronized(this) {
            server ?: RiftMcpServer(
                toolHost(context),
                debugHub(),
                operationJournal(context)
            ).also { server = it }
        }
    }

    fun relayClient(context: Context): RiftMcpRelayClient {
        relay?.let { return it }
        return synchronized(this) {
            relay ?: RiftMcpRelayClient(
                context.applicationContext,
                server(context),
                mcpEvents(),
                debugHub(),
                operationJournal(context)
            ).also { relay = it }
        }
    }

    fun nativeGit(context: Context): RiftNativeGit {
        nativeGit?.let { return it }
        return synchronized(this) {
            nativeGit ?: RiftNativeGit(context.applicationContext).also { nativeGit = it }
        }
    }

    /** Process-wide Vortex Binder client so same-process Activity recreation cannot tear down a live dev session/job. Android process death still resets this singleton. */
    fun vortexBridge(context: Context): RiftVortexBridgeClient {
        vortexBridge?.let { return it }
        return synchronized(this) {
            vortexBridge ?: RiftVortexBridgeClient(context.applicationContext).also { vortexBridge = it }
        }
    }

}
