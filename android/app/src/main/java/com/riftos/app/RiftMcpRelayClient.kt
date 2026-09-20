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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.random.Random

/**
 * Outbound-only WSS transport between this device and the public Rift MCP relay.
 * MCP execution remains in RiftMcpServer/RiftToolHost; the relay receives no local authority.
 */
class RiftMcpRelayClient(
    context: Context,
    private val server: RiftMcpServer,
    private val cliEvents: RiftCliEventBus,
    debugHub: RiftDebugHub? = null
) {
    companion object {
        private const val PROTOCOL = "rift-mcp-relay-v1"
        private const val MAX_MESSAGE_BYTES = 1_000_000
        private const val REQUEST_FORWARD_TIMEOUT_MS = 70_000L
    }

    private val settings = RiftRelaySettings(context.applicationContext)
    private val debugSink = debugHub?.sink("mcp.relay")
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
    @Volatile private var lastCliAckSequence = 0L
    private var attempts = 0
    private var socket: WebSocket? = null
    private var reconnect: ScheduledFuture<*>? = null
    private val cliEventListener: (JSONObject) -> Unit = { event -> sendCliEvent(event) }

    init {
        cliEvents.addListener(cliEventListener)
    }

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
            .put("cliLastAckSequence", lastCliAckSequence)
            .put("cliEvents", cliEvents.status())
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
        debug(
            operation = "socket.connect",
            outcome = "attempt",
            attributes = mapOf("attempt" to attempts.toString())
        )
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
            debug(
                operation = "socket.open",
                outcome = "ok",
                attributes = mapOf("httpCode" to response.code.toString())
            )
            webSocket.send(
                JSONObject()
                    .put("type", "device.hello")
                    .put("protocol", PROTOCOL)
                    .put("deviceId", config.deviceId)
                    .put("client", JSONObject()
                        .put("name", "RiftOS")
                        .put("version", BuildConfig.VERSION_NAME)
                        .put("sourceSha", BuildConfig.RIFT_SOURCE_SHA)
                        .put("buildRunId", BuildConfig.RIFT_BUILD_RUN_ID))
                    .toString()
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent(webSocket)) return
            if (text.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_BYTES) {
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
                    val resumeAfter = message.optLong("cliResumeAfter", 0L).coerceAtLeast(0L)
                    debug(
                        operation = "relay.ready",
                        outcome = "ok",
                        attributes = mapOf("resumeAfter" to resumeAfter.toString())
                    )
                    sendCliReplay(webSocket, resumeAfter)
                }
                "relay.ping" -> webSocket.send(
                    JSONObject().put("type", "device.pong").put("at", System.currentTimeMillis()).toString()
                )
                "mcp.request" -> handleMcpRequest(webSocket, message)
                "mcp.notification" -> handleMcpNotification(message)
                "cli.replay.request" -> {
                    val after = message.optLong("after", 0L).coerceAtLeast(0L)
                    debug(
                        operation = "cli.replay.request",
                        outcome = "received",
                        attributes = mapOf("after" to after.toString())
                    )
                    sendCliReplay(webSocket, after)
                }
                "cli.ack" -> {
                    val sequence = message.optLong("sequence", 0L)
                    val advanced = sequence > lastCliAckSequence
                    if (advanced) lastCliAckSequence = sequence
                    debug(
                        operation = "cli.ack",
                        outcome = if (sequence > 0L) "received" else "invalid",
                        attributes = mapOf(
                            "eventSequence" to sequence.toString(),
                            "advanced" to advanced.toString()
                        )
                    )
                }
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
            debug(
                operation = "socket.closed",
                outcome = "closed",
                attributes = mapOf("code" to code.toString())
            )
            if (desiredRunning) scheduleReconnect("Relay closed: $code")
        }

        override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
            if (!clearCurrent(webSocket)) return
            connectedAt = 0L
            debug(
                operation = "socket.failure",
                outcome = "error",
                message = error.javaClass.simpleName,
                attributes = mapOf("httpCode" to (response?.code?.toString() ?: "none"))
            )
            if (desiredRunning) scheduleReconnect(error.message ?: "Relay connection failed")
        }
    }

    private fun handleMcpNotification(envelope: JSONObject) {
        val payload = envelope.optJSONObject("payload") ?: return
        if (payload.has("id") || !payload.optString("method").startsWith("notifications/")) return
        runCatching { server.handleAsync(payload) { } }
    }

    private fun handleMcpRequest(webSocket: WebSocket, envelope: JSONObject) {
        val requestId = envelope.optString("requestId").trim()
        val payload = envelope.optJSONObject("payload")
        if (requestId.isBlank() || payload == null) {
            sendProtocolError(webSocket, requestId.takeIf { it.isNotBlank() }, "mcp.request requires requestId and payload")
            return
        }
        val terminal = AtomicBoolean(false)
        val timeout = scheduler.schedule({
            if (terminal.compareAndSet(false, true) && isCurrent(webSocket)) {
                sendProtocolError(webSocket, requestId, "Local MCP forwarding timed out")
            }
        }, REQUEST_FORWARD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        runCatching {
            server.handleAsync(payload, requestId) { result ->
                if (!terminal.compareAndSet(false, true)) return@handleAsync
                timeout.cancel(false)
                if (!isCurrent(webSocket)) return@handleAsync
                val responseText = JSONObject()
                    .put("type", "mcp.response")
                    .put("requestId", requestId)
                    .put("payload", result)
                    .toString()
                if (responseText.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_BYTES) {
                    sendProtocolError(webSocket, requestId, "Local MCP response exceeds relay message limit")
                    return@handleAsync
                }
                webSocket.send(responseText)
            }
        }.onFailure { error ->
            if (terminal.compareAndSet(false, true)) {
                timeout.cancel(false)
                if (isCurrent(webSocket)) {
                    sendProtocolError(webSocket, requestId, error.message ?: "Local MCP execution failed")
                }
            }
        }
    }

    private fun sendCliEvent(event: JSONObject) {
        val webSocket = synchronized(lock) { socket }
        if (webSocket == null || !isCurrent(webSocket)) {
            debug(
                operation = "cli.event.send",
                outcome = "deferred_no_socket",
                attributes = cliEventDebugAttributes(event)
            )
            return
        }
        sendCliEvent(webSocket, event)
    }

    private fun sendCliEvent(webSocket: WebSocket, event: JSONObject) {
        val envelope = JSONObject()
            .put("type", "cli.event")
            .put("protocol", PROTOCOL)
            .put("event", event)
            .toString()
        val envelopeBytes = envelope.toByteArray(Charsets.UTF_8).size
        if (envelopeBytes > MAX_MESSAGE_BYTES) {
            debug(
                operation = "cli.event.send",
                outcome = "rejected_oversize",
                attributes = cliEventDebugAttributes(event) + mapOf("bytes" to envelopeBytes.toString())
            )
            return
        }
        val queued = webSocket.send(envelope)
        debug(
            operation = "cli.event.send",
            outcome = if (queued) "queued" else "queue_rejected",
            attributes = cliEventDebugAttributes(event) + mapOf("bytes" to envelopeBytes.toString())
        )
    }

    private fun sendCliReplay(webSocket: WebSocket, afterSequence: Long) {
        val replay = cliEvents.replayAfter(afterSequence)
        debug(
            operation = "cli.replay.send",
            outcome = "start",
            attributes = mapOf(
                "after" to afterSequence.toString(),
                "count" to replay.size.toString()
            )
        )
        replay.forEach { event ->
            if (!isCurrent(webSocket)) return
            sendCliEvent(webSocket, event)
        }
    }

    private fun sendProtocolError(webSocket: WebSocket, requestId: String?, message: String) {
        webSocket.send(
            JSONObject()
                .put("type", "mcp.error")
                .put("requestId", requestId ?: JSONObject.NULL)
                .put("message", message.take(240))
                .toString()
        )
    }

    private fun scheduleReconnect(reason: String) {
        val exponent = min(attempts, 6)
        val baseSeconds = 1L shl exponent
        val delayMs = min(60_000L, baseSeconds * 1_000L) + Random.nextLong(0L, 750L)
        attempts += 1
        update("reconnecting", "$reason; retrying shortly")
        debug(
            operation = "socket.reconnect",
            outcome = "scheduled",
            attributes = mapOf(
                "attempt" to attempts.toString(),
                "delayMs" to delayMs.toString()
            )
        )
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

    private fun cliEventDebugAttributes(event: JSONObject): Map<String, String> = linkedMapOf(
        "eventSequence" to event.optLong("sequence", 0L).toString(),
        "eventType" to event.optString("type").take(128),
        "lane" to event.optString("lane").take(64),
        "status" to event.optString("status").take(64),
        "terminal" to event.optBoolean("terminal", false).toString()
    )

    private fun debug(
        operation: String,
        outcome: String? = null,
        message: String? = null,
        attributes: Map<String, String> = emptyMap()
    ) {
        runCatching {
            debugSink?.emit(
                RiftDebugSignal(
                    operation = operation,
                    outcome = outcome,
                    message = message,
                    attributes = attributes
                )
            )
        }
    }

    private fun update(newState: String, newDetail: String) {
        state = newState
        detail = newDetail.take(240)
    }
}
