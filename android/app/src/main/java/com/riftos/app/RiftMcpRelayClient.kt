package com.riftos.app

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

/**
 * Outbound-only WSS transport between this device and the public Rift MCP relay.
 * MCP execution remains in RiftMcpServer/RiftToolHost; the relay receives no local authority.
 */
class RiftMcpRelayClient(
    context: Context,
    private val server: RiftMcpServer
) {
    companion object {
        private const val PROTOCOL = "rift-mcp-relay-v1"
        private const val MAX_MESSAGE_CHARS = 1_000_000
    }

    private val settings = RiftRelaySettings(context.applicationContext)
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val http = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val lock = Any()

    @Volatile private var desiredRunning = false
    @Volatile private var state = "disabled"
    @Volatile private var detail = "Relay is disabled"
    @Volatile private var connectedAt = 0L
    private var attempts = 0
    private var socket: WebSocket? = null
    private var reconnect: ScheduledFuture<*>? = null

    fun start() {
        val config = settings.load()
        if (!config.enabled) {
            desiredRunning = false
            update("disabled", "Relay is disabled")
            return
        }
        desiredRunning = true
        open(config)
    }

    fun reload() {
        synchronized(lock) {
            desiredRunning = false
            reconnect?.cancel(false)
            reconnect = null
            socket?.close(1000, "Configuration changed")
            socket = null
        }
        start()
    }

    fun disconnect() {
        synchronized(lock) {
            desiredRunning = false
            reconnect?.cancel(false)
            reconnect = null
            socket?.close(1000, "Disconnected by user")
            socket = null
        }
        update("disconnected", "Disconnected until reconnect or app restart")
    }

    fun status(): JSONObject {
        val config = settings.load()
        return JSONObject()
            .put("state", state)
            .put("detail", detail)
            .put("enabled", config.enabled)
            .put("configured", config.configured)
            .put("endpoint", config.endpoint)
            .put("deviceId", config.deviceId)
            .put("connectedAt", connectedAt)
            .put("attempts", attempts)
    }

    private fun open(config: RiftRelayConfig) {
        if (!desiredRunning) return
        synchronized(lock) {
            if (socket != null) return
        }
        if (!config.configured) {
            update("needs-setup", "Relay URL or pairing token is missing")
            return
        }
        val token = config.token ?: return
        update("connecting", "Opening secure relay connection")
        val request = Request.Builder()
            .url(config.endpoint)
            .header("Authorization", "Bearer $token")
            .header("X-Rift-Device-Id", config.deviceId)
            .header("X-Rift-Protocol", PROTOCOL)
            .build()
        val listener = Listener(config)
        val newSocket = http.newWebSocket(request, listener)
        synchronized(lock) {
            if (desiredRunning && socket == null) socket = newSocket
            else newSocket.close(1000, "Connection no longer needed")
        }
    }

    private inner class Listener(private val config: RiftRelayConfig) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isCurrent(webSocket)) return
            update("authenticating", "Secure socket open; waiting for relay")
            webSocket.send(
                JSONObject()
                    .put("type", "device.hello")
                    .put("protocol", PROTOCOL)
                    .put("deviceId", config.deviceId)
                    .put("client", JSONObject().put("name", "RiftOS").put("version", "0.11.0"))
                    .toString()
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent(webSocket)) return
            if (text.length > MAX_MESSAGE_CHARS) {
                webSocket.close(1009, "Message too large")
                return
            }
            val message = runCatching { JSONObject(text) }.getOrElse {
                sendProtocolError(webSocket, null, "Invalid relay JSON")
                return
            }
            when (message.optString("type")) {
                "relay.ready" -> {
                    attempts = 0
                    connectedAt = System.currentTimeMillis()
                    update("connected", "ChatGPT relay connected")
                }
                "relay.ping" -> webSocket.send(
                    JSONObject().put("type", "device.pong").put("at", System.currentTimeMillis()).toString()
                )
                "mcp.request" -> handleMcpRequest(webSocket, message)
                "relay.error" -> update("relay-error", message.optString("message", "Relay rejected the connection"))
                else -> sendProtocolError(webSocket, message.optString("requestId").takeIf { it.isNotBlank() }, "Unknown relay message")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!clearCurrent(webSocket)) return
            connectedAt = 0L
            if (desiredRunning) scheduleReconnect("Relay closed: $code")
        }

        override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
            if (!clearCurrent(webSocket)) return
            connectedAt = 0L
            if (desiredRunning) scheduleReconnect(error.message ?: "Relay connection failed")
        }
    }

    private fun handleMcpRequest(webSocket: WebSocket, envelope: JSONObject) {
        val requestId = envelope.optString("requestId").trim()
        val payload = envelope.optJSONObject("payload")
        if (requestId.isBlank() || payload == null) {
            sendProtocolError(webSocket, requestId.takeIf { it.isNotBlank() }, "mcp.request requires requestId and payload")
            return
        }
        runCatching {
            server.handleAsync(payload) { result ->
                if (!isCurrent(webSocket)) return@handleAsync
                webSocket.send(
                    JSONObject()
                        .put("type", "mcp.response")
                        .put("requestId", requestId)
                        .put("payload", result)
                        .toString()
                )
            }
        }.onFailure { error ->
            sendProtocolError(webSocket, requestId, error.message ?: "Local MCP execution failed")
        }
    }

    private fun sendProtocolError(webSocket: WebSocket, requestId: String?, message: String) {
        webSocket.send(
            JSONObject()
                .put("type", "mcp.error")
                .put("requestId", requestId ?: JSONObject.NULL)
                .put("message", message)
                .toString()
        )
    }

    private fun scheduleReconnect(reason: String) {
        val exponent = min(attempts, 6)
        val baseSeconds = 1L shl exponent
        val delayMs = min(60_000L, baseSeconds * 1_000L) + Random.nextLong(0L, 750L)
        attempts += 1
        update("reconnecting", "$reason; retrying shortly")
        synchronized(lock) {
            reconnect?.cancel(false)
            reconnect = scheduler.schedule({
                if (desiredRunning) open(settings.load())
            }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun isCurrent(candidate: WebSocket): Boolean = synchronized(lock) { socket === candidate }

    private fun clearCurrent(candidate: WebSocket): Boolean = synchronized(lock) {
        if (socket !== candidate) return@synchronized false
        socket = null
        true
    }

    private fun update(newState: String, newDetail: String) {
        state = newState
        detail = newDetail.take(240)
    }
}
