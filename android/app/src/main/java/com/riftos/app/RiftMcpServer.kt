package com.riftos.app

import org.json.JSONArray
import org.json.JSONObject

/** Small in-process MCP JSON-RPC server backed by RiftToolHost. */
class RiftMcpServer(private val toolHost: RiftToolHost) {
    companion object {
        private const val PROTOCOL_VERSION = "2025-06-18"
        private const val SERVER_VERSION = "0.15.0-relay-transport"
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

    private fun initializeResult(): JSONObject = JSONObject()
        .put("protocolVersion", PROTOCOL_VERSION)
        .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
        .put(
            "serverInfo",
            JSONObject()
                .put("name", "rift-local-mcp")
                .put("version", SERVER_VERSION)
        )
        .put(
            "instructions",
            "RiftOS workspace tools with Project Intelligence v1. All filesystem capabilities are hard-scoped to workspace/. Prefer rift_workspace_exec for local symbol/reference lookup, surgical reads/patches, dry-run validation and transactional multi-file work. Device-side permissions and audit remain authoritative across local and relay transports; there is no direct model API."
        )

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
