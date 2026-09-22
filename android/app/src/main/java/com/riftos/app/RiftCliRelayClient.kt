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
 * Independent outbound-only RiftCLI transport.
 *
 * This client owns no MCP authority and accepts only commands rooted at "rift-cli".
 */
class RiftCliRelayClient(
    context: Context,
    private val shell: RiftNativeShell,
    private val cliEvents: RiftCliEventBus,
    debugHub: RiftDebugHub? = null
) {
    companion object {
        private const val PROTOCOL = "rift-cli-relay-v1"
        private const val MAX_MESSAGE_BYTES = 1_000_000
        private const val MAX_COMMAND_BYTES = 128 * 1024
    }

    private val settings = RiftCliRelaySettings(context.applicationContext)
    private val debugSink = debugHub?.sink("cli.relay")
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val http = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val lock = Any()

    @Volatile private var desiredRunning = false
    @Volatile private var state = "disabled"
    @Volatile private var detail = "RiftCLI relay is disabled"
    @Volatile private var connectedAt = 0L
    @Volatile private var lastAckSequence = settings.loadAckSequence()
    private var attempts = 0
    private var socket: WebSocket? = null
    private var reconnect: ScheduledFuture<*>? = null
    private val eventListener: (JSONObject) -> Unit = { event -> sendEvent(event) }

    init {
        cliEvents.addListener(eventListener)
    }

    fun start() {
        val config = settings.load()
        if (!config.enabled) {
            desiredRunning = false
            update("disabled", "RiftCLI relay is disabled")
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
            .put("protocol", PROTOCOL)
            .put("state", state)
            .put("detail", detail)
            .put("enabled", config.enabled)
            .put("configured", config.configured)
            .put("endpoint", config.endpoint)
            .put("deviceId", config.deviceId)
            .put("connectedAt", connectedAt)
            .put("attempts", attempts)
            .put("lastAckSequence", lastAckSequence)
            .put("events", cliEvents.status())
    }

    private fun open(config: RiftCliRelayConfig) {
        if (!desiredRunning) return
        synchronized(lock) {
            if (socket != null) return
        }
        if (!config.configured) {
            update("needs-setup", "RiftCLI relay URL or token is missing")
            return
        }

        val token = config.token ?: return
        update("connecting", "Opening independent RiftCLI relay connection")
        debug("socket.connect", "attempt", attributes = mapOf("attempt" to attempts.toString()))

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

    private inner class Listener(private val config: RiftCliRelayConfig) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isCurrent(webSocket)) return
            update("authenticating", "Secure CLI socket open; waiting for relay")
            debug("socket.open", "ok", attributes = mapOf("httpCode" to response.code.toString()))
            webSocket.send(
                JSONObject()
                    .put("type", "device.hello")
                    .put("protocol", PROTOCOL)
                    .put("deviceId", config.deviceId)
                    .put("ackSequence", lastAckSequence)
                    .put("client", JSONObject()
                        .put("name", "RiftCLI")
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
                sendError(webSocket, null, "Invalid CLI relay JSON")
                return
            }

            when (message.optString("type")) {
                "relay.ready" -> {
                    attempts = 0
                    connectedAt = System.currentTimeMillis()
                    update("connected", "RiftCLI relay connected")
                    val resumeAfter = message.optLong("resumeAfter", 0L).coerceAtLeast(0L)
                    debug("relay.ready", "ok", attributes = mapOf("resumeAfter" to resumeAfter.toString()))
                    sendReplay(webSocket, resumeAfter)
                }
                "relay.ping" -> webSocket.send(
                    JSONObject().put("type", "device.pong").put("at", System.currentTimeMillis()).toString()
                )
                "cli.request" -> handleCliRequest(webSocket, message)
                "cli.replay.request" -> {
                    val after = message.optLong("after", 0L).coerceAtLeast(0L)
                    debug("cli.replay.request", "received", attributes = mapOf("after" to after.toString()))
                    sendReplay(webSocket, after)
                }
                "cli.ack" -> {
                    val sequence = message.optLong("sequence", 0L)
                    val advanced = sequence > lastAckSequence
                    if (advanced) {
                        lastAckSequence = sequence
                        settings.saveAckSequence(sequence)
                    }
                    debug(
                        "cli.ack",
                        if (sequence > 0L) "received" else "invalid",
                        attributes = mapOf(
                            "eventSequence" to sequence.toString(),
                            "advanced" to advanced.toString()
                        )
                    )
                }
                "relay.error" -> update("relay-error", message.optString("message", "CLI relay rejected the connection"))
                else -> sendError(webSocket, message.optString("requestId").takeIf { it.isNotBlank() }, "Unknown CLI relay message")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!clearCurrent(webSocket)) return
            connectedAt = 0L
            debug("socket.closed", "closed", attributes = mapOf("code" to code.toString()))
            if (desiredRunning) scheduleReconnect("RiftCLI relay closed: $code")
        }

        override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
            if (!clearCurrent(webSocket)) return
            connectedAt = 0L
            debug(
                "socket.failure",
                "error",
                message = error.javaClass.simpleName,
                attributes = mapOf("httpCode" to (response?.code?.toString() ?: "none"))
            )
            if (desiredRunning) scheduleReconnect(error.message ?: "RiftCLI relay connection failed")
        }
    }

    private fun handleCliRequest(webSocket: WebSocket, envelope: JSONObject) {
        val requestId = envelope.optString("requestId").trim()
        val command = envelope.optString("command").trim()
        val cwd = envelope.optString("cwd", "/").trim().ifBlank { "/" }

        if (requestId.isBlank()) {
            sendError(webSocket, null, "cli.request requires requestId")
            return
        }
        if (command.isBlank() || !(command == "rift-cli" || command.startsWith("rift-cli "))) {
            sendError(webSocket, requestId, "CLI relay only accepts commands rooted at rift-cli")
            return
        }
        if (command.toByteArray(Charsets.UTF_8).size > MAX_COMMAND_BYTES) {
            sendError(webSocket, requestId, "CLI request exceeds $MAX_COMMAND_BYTES bytes")
            return
        }

        debug("cli.request", "received", attributes = mapOf("requestId" to requestId.take(128)))
        shell.execute(command, cwd) { result ->
            if (!isCurrent(webSocket)) return@execute
            val response = JSONObject()
                .put("type", "cli.response")
                .put("requestId", requestId)
                .put("payload", result)
                .toString()
            if (response.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_BYTES) {
                sendError(webSocket, requestId, "CLI response exceeds relay message limit")
            } else {
                webSocket.send(response)
            }
        }
    }

    private fun sendEvent(event: JSONObject) {
        val webSocket = synchronized(lock) { socket }
        if (webSocket == null || !isCurrent(webSocket)) {
            debug("cli.event.send", "deferred_no_socket", attributes = eventAttributes(event))
            return
        }
        sendEvent(webSocket, event)
    }

    private fun sendEvent(webSocket: WebSocket, event: JSONObject) {
        val envelope = JSONObject()
            .put("type", "cli.event")
            .put("protocol", PROTOCOL)
            .put("event", event)
            .toString()
        val bytes = envelope.toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_MESSAGE_BYTES) {
            debug("cli.event.send", "rejected_oversize", attributes = eventAttributes(event) + mapOf("bytes" to bytes.toString()))
            return
        }
        val queued = webSocket.send(envelope)
        debug(
            "cli.event.send",
            if (queued) "queued" else "queue_rejected",
            attributes = eventAttributes(event) + mapOf("bytes" to bytes.toString())
        )
    }

    private fun sendReplay(webSocket: WebSocket, afterSequence: Long) {
        val replay = cliEvents.replayAfter(afterSequence)
        debug(
            "cli.replay.send",
            "start",
            attributes = mapOf("after" to afterSequence.toString(), "count" to replay.size.toString())
        )
        replay.forEach { event ->
            if (!isCurrent(webSocket)) return
            sendEvent(webSocket, event)
        }
    }

    private fun sendError(webSocket: WebSocket, requestId: String?, message: String) {
        webSocket.send(
            JSONObject()
                .put("type", "cli.error")
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
            "socket.reconnect",
            "scheduled",
            attributes = mapOf("attempt" to attempts.toString(), "delayMs" to delayMs.toString())
        )
        synchronized(lock) {
            reconnect?.cancel(false)
            reconnect = scheduler.schedule({
                if (desiredRunning) open(settings.load())
            }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun eventAttributes(event: JSONObject): Map<String, String> = linkedMapOf(
        "eventSequence" to event.optLong("sequence", 0L).toString(),
        "eventType" to event.optString("type").take(128),
        "lane" to event.optString("lane").take(64),
        "status" to event.optString("status").take(64),
        "terminal" to event.optBoolean("terminal", false).toString()
    )

    private fun isCurrent(candidate: WebSocket): Boolean = synchronized(lock) { socket === candidate }

    private fun clearCurrent(candidate: WebSocket): Boolean = synchronized(lock) {
        if (socket !== candidate) return@synchronized false
        socket = null
        true
    }

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
