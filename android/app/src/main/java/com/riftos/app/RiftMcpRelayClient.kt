package com.riftos.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Outbound-only bridge from the app-private RiftBrowser sandbox to a remote
 * MCP relay. The relay can call only the same sandbox methods already exposed
 * to ChatGPT inside RiftBrowser; it never receives access to SAF mounts,
 * Android intents, secrets, clipboard, or the wider RiftFS.
 */
class RiftMcpRelayClient(context: Context) {
    companion object {
        private const val PREFS = "rift-mcp-relay"
        private const val PREF_DEVICE_URL = "deviceUrl"
        private const val PREF_ENABLED = "enabled"
        private const val SECRET_PAIRING_KEY = "rift.mcp.pairingKey"
        private const val PROTOCOL = "rift-mcp-device-v1"
        private const val RECONNECT_MS = 3000L
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val secrets = RiftSecretStore(appContext)
    private val sandbox = RiftBrowserSandbox(appContext)
    private val handler = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var state = "off"
    @Volatile private var lastError = ""
    @Volatile private var connectedAt = 0L
    @Volatile private var desired = prefs.getBoolean(PREF_ENABLED, false)

    private val reconnect = Runnable {
        if (desired && socket == null) runCatching { openSocket() }
    }

    init {
        if (desired && configured()) runCatching { openSocket() }
    }

    fun configure(args: JSONObject): JSONObject {
        if (args.has("deviceUrl")) {
            val url = args.optString("deviceUrl").trim()
            if (url.isNotBlank()) require(url.startsWith("wss://")) { "Rift MCP deviceUrl must use wss://" }
            prefs.edit().putString(PREF_DEVICE_URL, url).apply()
        }
        if (args.has("pairingKey")) {
            val key = args.optString("pairingKey").trim()
            if (key.isBlank()) secrets.remove(SECRET_PAIRING_KEY)
            else {
                require(key.matches(Regex("[A-Za-z0-9_-]{24,128}"))) { "Pairing key must be 24-128 base64url-style characters" }
                secrets.set(SECRET_PAIRING_KEY, key)
            }
        }
        if (args.has("enabled")) {
            desired = args.optBoolean("enabled", false)
            prefs.edit().putBoolean(PREF_ENABLED, desired).apply()
        }

        closeSocket("Reconfigure")
        if (desired) {
            require(configured()) { "Rift MCP relay is not fully configured" }
            openSocket()
        } else {
            state = "off"
        }
        return status()
    }

    fun connect(): JSONObject {
        require(configured()) { "Rift MCP relay is not fully configured" }
        desired = true
        prefs.edit().putBoolean(PREF_ENABLED, true).apply()
        if (socket == null) openSocket()
        return status()
    }

    fun disconnect(): JSONObject {
        desired = false
        prefs.edit().putBoolean(PREF_ENABLED, false).apply()
        handler.removeCallbacks(reconnect)
        closeSocket("Disconnected")
        state = "off"
        return status()
    }

    fun appEndpoint(): String? {
        val key = pairingKey() ?: return null
        val base = deviceUrl()
        if (!base.startsWith("wss://")) return null
        val withoutQuery = base.substringBefore('?')
        val root = if (withoutQuery.endsWith("/device")) withoutQuery.removeSuffix("/device") else withoutQuery.trimEnd('/')
        return "https://${root.removePrefix("wss://")}/mcp/$key"
    }

    fun status(): JSONObject = JSONObject()
        .put("enabled", desired)
        .put("configured", configured())
        .put("paired", !pairingKey().isNullOrBlank())
        .put("deviceUrl", deviceUrl())
        .put("state", state)
        .put("connected", state == "connected")
        .put("connectedAt", connectedAt)
        .put("lastError", lastError)
        .put("protocol", PROTOCOL)

    fun shutdown() {
        desired = false
        handler.removeCallbacks(reconnect)
        closeSocket("App shutdown")
        sandbox.shutdown()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private fun configured(): Boolean = deviceUrl().isNotBlank() && !pairingKey().isNullOrBlank()
    private fun deviceUrl(): String = prefs.getString(PREF_DEVICE_URL, "")?.trim().orEmpty()
    private fun pairingKey(): String? = secrets.get(SECRET_PAIRING_KEY)?.trim()?.takeIf { it.isNotBlank() }

    private fun openSocket() {
        val base = deviceUrl()
        val key = pairingKey() ?: throw IllegalStateException("Rift MCP pairing key is missing")
        require(base.startsWith("wss://")) { "Rift MCP deviceUrl must use wss://" }
        val separator = if (base.contains('?')) '&' else '?'
        val encodedKey = URLEncoder.encode(key, Charsets.UTF_8.name())
        val request = Request.Builder().url("$base${separator}key=$encodedKey").build()
        state = "connecting"
        lastError = ""
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                state = "connected"
                connectedAt = System.currentTimeMillis()
                lastError = ""
                webSocket.send(
                    JSONObject()
                        .put("type", "device_ready")
                        .put("protocol", PROTOCOL)
                        .put("sandbox", "riftfs/browser-sandbox")
                        .toString()
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (message.optString("type")) {
                    "relay_ready" -> Unit
                    "tool_call" -> handleToolCall(webSocket, message)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket === webSocket) socket = null
                state = if (desired) "reconnecting" else "off"
                if (desired) scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (socket === webSocket) socket = null
                lastError = t.message ?: t.javaClass.simpleName
                state = if (desired) "reconnecting" else "off"
                if (desired) scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        handler.removeCallbacks(reconnect)
        handler.postDelayed(reconnect, RECONNECT_MS)
    }

    private fun closeSocket(reason: String) {
        val current = socket
        socket = null
        current?.close(1000, reason.take(120))
    }

    private fun handleToolCall(webSocket: WebSocket, message: JSONObject) {
        val id = message.optString("id")
        val name = message.optString("name")
        if (id.isBlank() || name.isBlank()) return
        val method = when (name) {
            "info" -> "sandbox.info"
            "stat" -> "fs.stat"
            "list" -> "fs.list"
            "readText" -> "fs.readText"
            "writeText" -> "fs.writeText"
            "mkdir" -> "fs.mkdir"
            "remove" -> "fs.remove"
            "move" -> "fs.move"
            else -> {
                webSocket.send(
                    JSONObject()
                        .put("type", "tool_result")
                        .put("id", id)
                        .put("ok", false)
                        .put("error", "Unsupported Rift MCP tool: $name")
                        .toString()
                )
                return
            }
        }
        val request = JSONObject()
            .put("id", id)
            .put("method", method)
            .put("args", message.optJSONObject("args") ?: JSONObject())

        sandbox.handleAsync(request.toString()) { raw ->
            val response = runCatching { JSONObject(raw) }.getOrNull()
            val output = JSONObject()
                .put("type", "tool_result")
                .put("id", id)
            if (response?.optBoolean("ok", false) == true) {
                output.put("ok", true)
                output.put("value", response.opt("value") ?: JSONObject.NULL)
            } else {
                output.put("ok", false)
                output.put("error", response?.optString("error")?.takeIf { it.isNotBlank() } ?: "Rift sandbox call failed")
            }
            webSocket.send(output.toString())
        }
    }
}
