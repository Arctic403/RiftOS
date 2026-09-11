package com.riftos.app

import android.content.Context

/** Process-wide local Rift MCP runtime. Direct MCP and Workspace Live share one workspace core. */
object RiftMcpRuntime {
    @Volatile private var journal: RiftAiJournal? = null
    @Volatile private var workspaceCore: RiftToolSandbox? = null
    @Volatile private var host: RiftToolHost? = null
    @Volatile private var server: RiftMcpServer? = null

    fun aiJournal(context: Context): RiftAiJournal {
        journal?.let { return it }
        return synchronized(this) {
            journal ?: RiftAiJournal(context.applicationContext).also { journal = it }
        }
    }

    /**
     * Canonical native workspace core. Both the ChatGPT-facing MCP tools and the
     * trusted Workspace Live HTML bridge execute through this exact instance, so
     * file operations share path validation, transaction ordering and revisions.
     */
    fun workspaceCore(context: Context): RiftToolSandbox {
        workspaceCore?.let { return it }
        return synchronized(this) {
            workspaceCore ?: RiftToolSandbox(context.applicationContext).also { workspaceCore = it }
        }
    }

    fun toolHost(context: Context): RiftToolHost {
        host?.let { return it }
        return synchronized(this) {
            host ?: RiftToolHost(context.applicationContext, aiJournal(context), workspaceCore(context)).also { host = it }
        }
    }

    fun server(context: Context): RiftMcpServer {
        server?.let { return it }
        return synchronized(this) {
            server ?: RiftMcpServer(toolHost(context)).also { server = it }
        }
    }
}
