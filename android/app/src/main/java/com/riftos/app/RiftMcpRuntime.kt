package com.riftos.app

import android.content.Context

/** Process-wide local Rift MCP runtime and Rift AI working-tree journal. */
object RiftMcpRuntime {
    @Volatile private var journal: RiftAiJournal? = null
    @Volatile private var host: RiftToolHost? = null
    @Volatile private var server: RiftMcpServer? = null

    fun aiJournal(context: Context): RiftAiJournal {
        journal?.let { return it }
        return synchronized(this) {
            journal ?: RiftAiJournal(context.applicationContext).also { journal = it }
        }
    }

    fun toolHost(context: Context): RiftToolHost {
        host?.let { return it }
        return synchronized(this) {
            host ?: RiftToolHost(context.applicationContext, aiJournal(context)).also { host = it }
        }
    }

    fun server(context: Context): RiftMcpServer {
        server?.let { return it }
        return synchronized(this) {
            server ?: RiftMcpServer(toolHost(context)).also { server = it }
        }
    }
}
