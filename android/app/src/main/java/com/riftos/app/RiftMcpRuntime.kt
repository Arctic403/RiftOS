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
    @Volatile private var codynexBridge: RiftCodynexBridgeClient? = null
    @Volatile private var nativeGit: RiftNativeGit? = null
    @Volatile private var debugHub: RiftDebugHub? = null
    @Volatile private var cliEvents: RiftCliEventBus? = null
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
    fun cliEvents(): RiftCliEventBus {
        cliEvents?.let { return it }
        return synchronized(this) {
            cliEvents ?: RiftCliEventBus(debugHub()).also { cliEvents = it }
        }
    }

    /** Passive process-wide diagnostics. This object owns no execution authority. */
    fun debugHub(): RiftDebugHub {
        debugHub?.let { return it }
        return synchronized(this) {
            debugHub ?: RiftDebugHub().also { debugHub = it }
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
                debugHub()
            ).also { host = it }
        }
    }

    fun server(context: Context): RiftMcpServer {
        server?.let { return it }
        return synchronized(this) {
            server ?: RiftMcpServer(toolHost(context), debugHub()).also { server = it }
        }
    }

    fun relayClient(context: Context): RiftMcpRelayClient {
        relay?.let { return it }
        return synchronized(this) {
            relay ?: RiftMcpRelayClient(
                context.applicationContext,
                server(context),
                cliEvents(),
                debugHub()
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

    /** Process-wide Codynex LR0 Binder client used by the native RiftShell bridge. */
    fun codynexBridge(context: Context): RiftCodynexBridgeClient {
        codynexBridge?.let { return it }
        return synchronized(this) {
            codynexBridge ?: RiftCodynexBridgeClient(context.applicationContext).also { codynexBridge = it }
        }
    }
}
