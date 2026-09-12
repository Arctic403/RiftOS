package com.riftos.app

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local RiftOS transport health probe.
 *
 * This intentionally does not connect to MCP or expose tools yet.
 * It only proves RiftOS can host a local socket endpoint.
 */
class RiftLocalWebSocket {
    companion object {
        private const val PORT = 8787
    }

    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    private var server: ServerSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            try {
                server = ServerSocket(PORT, 10, java.net.InetAddress.getByName("127.0.0.1"))
                while (running.get()) {
                    val socket = server?.accept() ?: break
                    handle(socket)
                }
            } catch (_: Exception) {
                if (running.get()) running.set(false)
            }
        }
    }

    fun status(): String {
        return """{"status":"ok","riftos":true,"service":"local-websocket-test","port":$PORT}"""
    }

    private fun handle(socket: Socket) {
        socket.use {
            val out = it.getOutputStream()
            val response = status()
            // Temporary health transport. MCP framing is intentionally not attached.
            out.write(response.toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }

    fun stop() {
        running.set(false)
        try { server?.close() } catch (_: Exception) {}
        server = null
    }
}
