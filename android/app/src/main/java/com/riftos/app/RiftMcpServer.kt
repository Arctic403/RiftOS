package com.riftos.app

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Small in-process MCP JSON-RPC server backed by RiftToolHost. */
class RiftMcpServer(private val toolHost: RiftToolHost) {
    companion object {
        private const val PROTOCOL_VERSION = "2025-06-18"
        private const val SERVER_VERSION = "0.17.0-tool-manifest-refresh"
        private const val COMPLETED_TTL_MS = 2 * 60 * 1000L
        private const val MAX_COMPLETED_REQUESTS = 128
    }

    private data class CompletedRequest(val response: String, val expiresAt: Long)
    private val requestLock = Any()
    private val inFlight = mutableMapOf<String, MutableList<(JSONObject) -> Unit>>()
    private val completed = LinkedHashMap<String, CompletedRequest>()

    fun handleAsync(request: JSONObject, reply: (JSONObject) -> Unit) {
        if (request.optString("method") != "tools/call") {
            dispatch(request, reply)
            return
        }

        // The HTTP relay may retry after losing a response. Coalesce identical JSON-RPC
        // tool calls on-device so a local mutation is never executed twice.
        val key = requestKey(request)
        var cachedResponse: String? = null
        var joinedInFlight = false
        synchronized(requestLock) {
            pruneCompletedLocked()
            val cached = completed[key]
            if (cached != null) {
                cachedResponse = cached.response
            } else {
                val waiters = inFlight[key]
                if (waiters != null) {
                    waiters.add(reply)
                    joinedInFlight = true
                } else {
                    inFlight[key] = mutableListOf(reply)
                }
            }
        }
        cachedResponse?.let {
            reply(JSONObject(it))
            return
        }
        if (joinedInFlight) return

        try {
            dispatch(request) { response -> completeRequest(key, response) }
        } catch (failure: Throwable) {
            val id = request.opt("id") ?: JSONObject.NULL
            completeRequest(key, error(id, -32603, failure.message ?: "Local MCP execution failed"))
        }
    }

    private fun dispatch(request: JSONObject, reply: (JSONObject) -> Unit) {
        val id = request.opt("id") ?: JSONObject.NULL
        val method = request.optString("method")
        val params = request.optJSONObject("params") ?: JSONObject()

        when (method) {
            "initialize" -> reply(success(id, initializeResult()))
            "ping" -> reply(success(id, JSONObject()))
            "tools/list" -> {
                val tools = toolHost.tools()
                reply(success(id, JSONObject()
                    .put("tools", tools)
                    .put("_meta", JSONObject()
                        .put("riftos/toolCount", tools.length())
                        .put("riftos/toolManifestHash", toolManifestHash(tools)))))
            }
            "tools/call" -> handleToolCall(id, params, reply)
            else -> reply(error(id, -32601, "Method not found: $method"))
        }
    }

    private fun completeRequest(key: String, response: JSONObject) {
        val serialized = response.toString()
        val waiters = synchronized(requestLock) {
            completed[key] = CompletedRequest(serialized, System.currentTimeMillis() + COMPLETED_TTL_MS)
            while (completed.size > MAX_COMPLETED_REQUESTS) {
                val oldest = completed.entries.iterator()
                if (oldest.hasNext()) {
                    oldest.next()
                    oldest.remove()
                }
            }
            inFlight.remove(key).orEmpty()
        }
        waiters.forEach { waiter -> runCatching { waiter(JSONObject(serialized)) } }
    }

    private fun pruneCompletedLocked() {
        val now = System.currentTimeMillis()
        val entries = completed.entries.iterator()
        while (entries.hasNext()) {
            if (entries.next().value.expiresAt <= now) entries.remove()
        }
    }

    private fun requestKey(request: JSONObject): String {
        val canonical = canonicalJson(request)
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun canonicalJson(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { key ->
            "${JSONObject.quote(key)}:${canonicalJson(value.opt(key))}"
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalJson(value.opt(it)) }
        is String -> JSONObject.quote(value)
        is Boolean, is Number -> value.toString()
        else -> JSONObject.quote(value.toString())
    }

    private fun handleToolCall(id: Any, params: JSONObject, reply: (JSONObject) -> Unit) {
        val name = params.optString("name").trim()
        if (name.isBlank()) {
            reply(error(id, -32602, "tools/call requires a tool name"))
            return
        }
        val args = params.optJSONObject("arguments") ?: JSONObject()
        val requestMeta = params.optJSONObject("_meta") ?: JSONObject()
        val aiSessionId = requestMeta.optString("riftos/aiSessionId")
            .trim()
            .takeIf { it.isNotBlank() }
        val modelCallId = requestMeta.optString("riftos/callId")
            .trim()
            .takeIf { it.isNotBlank() }
        toolHost.callAsync(name, args, aiSessionId) { call ->
            val ok = call.optBoolean("ok", false)
            val structured = JSONObject().put("ok", ok)
            if (ok) {
                val value = call.opt("value")
                structured.put(
                    "value",
                    if (name == "rift_project_export" && value is JSONObject) exportSummary(value)
                    else value ?: JSONObject.NULL
                )
            }
            else structured.put("error", call.optString("error", "Rift tool failed"))

            val text = if (ok) {
                val value = call.opt("value")
                when (value) {
                    null, JSONObject.NULL -> "null"
                    is JSONObject, is JSONArray -> value.toString()
                    else -> value.toString()
                }
            } else {
                call.optString("error", "Rift tool failed")
            }

            val resultMeta = JSONObject()
            if (aiSessionId != null) resultMeta.put("riftos/aiSessionId", aiSessionId)
            if (modelCallId != null) resultMeta.put("riftos/callId", modelCallId)

            val result = JSONObject()
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
                .put("structuredContent", structured)
                .put("_meta", resultMeta)
                .put("isError", !ok)
            reply(success(id, result))
        }
    }

    private fun initializeResult(): JSONObject {
        val tools = toolHost.tools()
        val manifestHash = toolManifestHash(tools)
        return JSONObject()
            .put("protocolVersion", PROTOCOL_VERSION)
            .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", true)))
            .put(
                "serverInfo",
                JSONObject()
                    .put("name", "rift-local-mcp")
                    .put("version", "$SERVER_VERSION-${manifestHash.take(12)}")
            )
            .put("_meta", JSONObject()
                .put("riftos/toolCount", tools.length())
                .put("riftos/toolManifestHash", manifestHash))
            .put(
                "instructions",
                "RiftOS workspace tools with Project Intelligence v1. All filesystem capabilities are hard-scoped to workspace/. Prefer rift_workspace_exec for local symbol/reference lookup, surgical reads/patches, dry-run validation and transactional multi-file work. The server version is fingerprinted to the live tool manifest so reconnecting clients can invalidate stale tool-schema caches. Device-side permissions and audit remain authoritative across local and relay transports; there is no direct model API."
            )
    }

    private fun toolManifestHash(tools: JSONArray = toolHost.tools()): String =
        MessageDigest.getInstance("SHA-256")
            .digest(tools.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun exportSummary(value: JSONObject): JSONObject = JSONObject()
        .put("format", value.optString("format"))
        .put("snapshotId", value.optString("snapshotId"))
        .put("source", value.optString("source"))
        .put("cursor", value.optString("cursor"))
        .put("nextCursor", value.opt("nextCursor") ?: JSONObject.NULL)
        .put("done", value.optBoolean("done"))
        .put("fileCount", value.optInt("fileCount"))
        .put("sourceBytes", value.optLong("sourceBytes"))
        .put("returnedEntries", value.optInt("returnedEntries"))
        .put("responseBytes", value.optInt("responseBytes"))

    private fun success(id: Any, result: Any): JSONObject = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id)
        .put("result", result)

    private fun error(id: Any, code: Int, message: String): JSONObject = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id)
        .put("error", JSONObject().put("code", code).put("message", message))
}
