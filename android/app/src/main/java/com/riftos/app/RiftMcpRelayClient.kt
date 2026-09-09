package com.riftos.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Rift Bridge device adapter.
 *
 * The phone owns the capability boundary. Remote adapters (currently MCP) can
 * request only tools exposed here, and every call is checked against local
 * read/write policy before it reaches the app-private sandbox.
 */
class RiftMcpRelayClient(context: Context) {
    companion object {
        private const val PREFS = "rift-bridge"
        private const val PREF_DEVICE_URL = "deviceUrl"
        private const val PREF_ENABLED = "enabled"
        private const val PREF_ALLOW_READ = "allowRead"
        private const val PREF_ALLOW_WRITE = "allowWrite"
        private const val PREF_AUDIT = "audit"
        private const val SECRET_PAIRING_KEY = "rift.bridge.pairingKey"
        private const val PROTOCOL = "rift-bridge-device-v1"
        private const val RECONNECT_MS = 3000L
        private const val MAX_AUDIT = 100
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
            if (url.isNotBlank()) require(url.startsWith("wss://")) { "Rift Bridge device URL must use wss://" }
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
        if (args.has("allowRead") || args.has("allowWrite")) {
            setAccess(
                args.optBoolean("allowRead", allowRead()),
                args.optBoolean("allowWrite", allowWrite())
            )
        }

        closeSocket("Reconfigure")
        if (desired) {
            require(configured()) { "Rift Bridge relay is not fully configured" }
            openSocket()
        } else {
            state = "off"
        }
        return status()
    }

    fun setAccess(read: Boolean, write: Boolean): JSONObject {
        prefs.edit()
            .putBoolean(PREF_ALLOW_READ, read)
            .putBoolean(PREF_ALLOW_WRITE, write)
            .apply()
        return access()
    }

    fun connect(): JSONObject {
        require(configured()) { "Rift Bridge relay is not fully configured" }
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

    fun access(): JSONObject = JSONObject()
        .put("sandboxRead", allowRead())
        .put("sandboxWrite", allowWrite())
        .put("scope", "riftfs/browser-sandbox")
        .put("readTools", JSONArray(listOf("info", "stat", "list", "readText")))
        .put("writeTools", JSONArray(listOf("writeText", "mkdir", "remove", "move")))

    fun audit(): JSONArray {
        val raw = prefs.getString(PREF_AUDIT, "[]") ?: "[]"
        return runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    }

    fun clearAudit(): Boolean {
        prefs.edit().putString(PREF_AUDIT, "[]").apply()
        return true
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
        .put("access", access())
        .put("auditEntries", audit().length())

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
    private fun allowRead(): Boolean = prefs.getBoolean(PREF_ALLOW_READ, true)
    private fun allowWrite(): Boolean = prefs.getBoolean(PREF_ALLOW_WRITE, false)

    private fun openSocket() {
        val base = deviceUrl()
        val key = pairingKey() ?: throw IllegalStateException("Rift Bridge pairing key is missing")
        require(base.startsWith("wss://")) { "Rift Bridge device URL must use wss://" }
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
                        .put("access", access())
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

    private fun isAllowed(name: String): Boolean = when (name) {
        "info", "stat", "list", "readText" -> allowRead()
        "writeText", "mkdir", "remove", "move" -> allowWrite()
        else -> false
    }

    private fun methodFor(name: String): String? = when (name) {
        "info" -> "sandbox.info"
        "stat" -> "fs.stat"
        "list" -> "fs.list"
        "readText" -> "fs.readText"
        "writeText" -> "fs.writeText"
        "mkdir" -> "fs.mkdir"
        "remove" -> "fs.remove"
        "move" -> "fs.move"
        else -> null
    }

    private fun handleToolCall(webSocket: WebSocket, message: JSONObject) {
        val id = message.optString("id")
        val name = message.optString("name")
        if (id.isBlank() || name.isBlank()) return
        val args = message.optJSONObject("args") ?: JSONObject()
        val method = methodFor(name)
        if (method == null) {
            val error = "Unsupported Rift Bridge tool: $name"
            recordAudit(name, args, false, error)
            sendToolError(webSocket, id, error)
            return
        }
        if (!isAllowed(name)) {
            val error = if (name in setOf("writeText", "mkdir", "remove", "move")) {
                "Rift Bridge sandbox write access is disabled on this device"
            } else {
                "Rift Bridge sandbox read access is disabled on this device"
            }
            recordAudit(name, args, false, error)
            sendToolError(webSocket, id, error)
            return
        }

        val request = JSONObject()
            .put("id", id)
            .put("method", method)
            .put("args", args)

        sandbox.handleAsync(request.toString()) { raw ->
            val response = runCatching { JSONObject(raw) }.getOrNull()
            val output = JSONObject()
                .put("type", "tool_result")
                .put("id", id)
            if (response?.optBoolean("ok", false) == true) {
                output.put("ok", true)
                output.put("value", response.opt("value") ?: JSONObject.NULL)
                recordAudit(name, args, true, null)
            } else {
                val error = response?.optString("error")?.takeIf { it.isNotBlank() } ?: "Rift sandbox call failed"
                output.put("ok", false)
                output.put("error", error)
                recordAudit(name, args, false, error)
            }
            webSocket.send(output.toString())
        }
    }

    private fun sendToolError(webSocket: WebSocket, id: String, error: String) {
        webSocket.send(
            JSONObject()
                .put("type", "tool_result")
                .put("id", id)
                .put("ok", false)
                .put("error", error)
                .toString()
        )
    }

    @Synchronized
    private fun recordAudit(name: String, args: JSONObject, ok: Boolean, error: String?) {
        val current = audit()
        val next = JSONArray()
        val start = (current.length() - (MAX_AUDIT - 1)).coerceAtLeast(0)
        for (index in start until current.length()) next.put(current.opt(index))
        next.put(
            JSONObject()
                .put("at", System.currentTimeMillis())
                .put("tool", name)
                .put("target", auditTarget(name, args))
                .put("ok", ok)
                .put("error", error ?: JSONObject.NULL)
        )
        prefs.edit().putString(PREF_AUDIT, next.toString()).apply()
    }

    private fun auditTarget(name: String, args: JSONObject): String = when (name) {
        "move" -> "${args.optString("from")} -> ${args.optString("to")}".take(300)
        "info" -> "sandbox"
        else -> args.optString("path").take(300)
    }
}
