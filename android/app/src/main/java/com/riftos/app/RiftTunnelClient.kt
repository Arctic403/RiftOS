package com.riftos.app

import kotlinx.coroutines.*
import okhttp3.*

/**
 * Outbound-only MCP tunnel client.
 *
 * Keeps Rift devices behind NAT/firewalls by creating an authenticated
 * WebSocket connection to a relay. The relay forwards MCP JSON-RPC messages.
 */
class RiftTunnelClient(
    private val relayUrl: String,
    private val deviceToken: String,
) {
    private val client = OkHttpClient()
    private var socket: WebSocket? = null

    fun connect() {
        val request = Request.Builder()
            .url(relayUrl)
            .addHeader("Authorization", "Bearer $deviceToken")
            .build()

        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"type":"rift_hello"}""")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // TODO: forward MCP JSON-RPC payload to RiftMcpServer
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socket = null
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                socket = null
            }
        })
    }

    fun sendMcpMessage(payload: String) {
        socket?.send(payload)
    }

    fun disconnect() {
        socket?.close(1000, "manual")
        socket = null
    }
}
