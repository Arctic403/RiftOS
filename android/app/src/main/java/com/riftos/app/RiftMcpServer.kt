package com.riftos.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Small in-process MCP JSON-RPC server backed by RiftToolHost.
 *
 * It intentionally has no listening socket. Adapters feed JSON-RPC requests into
 * it in memory, which keeps the tool implementation local and transport-agnostic.
 */
class RiftMcpServer(private val toolHost: RiftToolHost) {
    companion object {
        private const val PROTOCOL_VERSION = "2025-06-18"
        private const val SERVER_VERSION = "0.7.0-rift-mcp-app-alpha"
    }

    fun handleAsync(request: JSONObject, reply: (JSONObject) -> Unit) {
        val id = request.opt("id") ?: JSONObject.NULL
        val method = request.optString("method")
        val params = request.optJSONObject("params") ?: JSONObject()

        when (method) {
            "initialize" -> reply(success(id, initializeResult()))
            "ping" -> reply(success(id, JSONObject()))
            "tools/list" -> reply(success(id, JSONObject().put("tools", toolHost.tools())))
            "tools/call" -> handleToolCall(id, params, reply)
            else -> reply(error(id, -32601, "Method not found: $method"))
        }
    }

    private fun handleToolCall(id: Any, params: JSONObject, reply: (JSONObject) -> Unit) {
        val name = params.optString("name").trim()
        if (name.isBlank()) {
            reply(error(id, -32602, "tools/call requires a tool name"))
            return
        }
        val args = params.optJSONObject("arguments") ?: JSONObject()
        toolHost.callAsync(name, args) { call ->
            val ok = call.optBoolean("ok", false)
            val structured = JSONObject().put("ok", ok)
            if (ok) structured.put("value", call.opt("value") ?: JSONObject.NULL)
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

            val result = JSONObject()
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
                .put("structuredContent", structured)
                .put("isError", !ok)
            reply(success(id, result))
        }
    }

    private fun initializeResult(): JSONObject = JSONObject()
        .put("protocolVersion", PROTOCOL_VERSION)
        .put(
            "capabilities",
            JSONObject().put("tools", JSONObject().put("listChanged", false))
        )
        .put(
            "serverInfo",
            JSONObject()
                .put("name", "rift-bridge-local")
                .put("version", SERVER_VERSION)
        )
        .put(
            "instructions",
            "RiftOS device tools. Device-side permissions and audit are authoritative."
        )

    private fun success(id: Any, result: Any): JSONObject = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id)
        .put("result", result)

    private fun error(id: Any, code: Int, message: String): JSONObject = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id)
        .put("error", JSONObject().put("code", code).put("message", message))
}
