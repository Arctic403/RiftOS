package com.riftos.app

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Small in-process MCP JSON-RPC server backed by RiftToolHost. */
class RiftMcpServer(
    private val toolHost: RiftToolHost,
    private val debugHub: RiftDebugHub = RiftDebugHub(),
    private val operationJournal: RiftMcpOperationJournal
) {
    companion object {
        private const val PROTOCOL_VERSION = "2025-06-18"
        private const val SERVER_VERSION = "0.20.0-operation-journal"
        private const val COMPLETED_TTL_MS = 2 * 60 * 1000L
        private const val REQUEST_TIMEOUT_MS = 65_000L
        private const val MAX_IN_FLIGHT_REQUESTS = 64
        private const val MAX_WAITERS_PER_REQUEST = 8
        private const val MAX_COMPLETED_REQUESTS = 128
        private const val MAX_COMPLETED_BYTES = 8 * 1024 * 1024
    }

    private data class CompletedRequest(val response: String, val bytes: Int, val expiresAt: Long)
    private data class RequestWaiter(val id: Any, val reply: (JSONObject) -> Unit)
    private class InFlightRequest(
        val waiters: MutableList<RequestWaiter>,
        var timeout: ScheduledFuture<*>? = null
    )
    private val requestLock = Any()
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    private val inFlight = mutableMapOf<String, InFlightRequest>()
    private val completed = LinkedHashMap<String, CompletedRequest>()
    private var completedBytes = 0L

    fun handleAsync(request: JSONObject, reply: (JSONObject) -> Unit) = handleAsync(request, null, reply)

    fun handleAsync(request: JSONObject, retryKey: String?, reply: (JSONObject) -> Unit) {
        val method = request.optString("method")
        if (method != "tools/call" || retryKey.isNullOrBlank()) {
            if (!request.has("id") || method.startsWith("notifications/")) {
                dispatch(request, operationContext = null, reply = reply)
            } else {
                dispatchBounded(request, reply)
            }
            return
        }

        val requestHash = requestKey(request)
        val key = "${retryKey.trim()}:$requestHash"
        val id = request.opt("id") ?: JSONObject.NULL
        var cachedResponse: String? = null
        var joinedInFlight = false
        var rejectedResponse: JSONObject? = null
        synchronized(requestLock) {
            pruneCompletedLocked()
            val cached = completed[key]
            if (cached != null) {
                cachedResponse = cached.response
            } else {
                val pending = inFlight[key]
                if (pending != null) {
                    if (pending.waiters.size >= MAX_WAITERS_PER_REQUEST) {
                        rejectedResponse = error(id, -32002, "Too many retry waiters for one local MCP request")
                    } else {
                        pending.waiters.add(RequestWaiter(id, reply))
                        joinedInFlight = true
                    }
                } else if (inFlight.size >= MAX_IN_FLIGHT_REQUESTS) {
                    rejectedResponse = error(id, -32003, "Local MCP request capacity is full")
                } else {
                    inFlight[key] = InFlightRequest(mutableListOf(RequestWaiter(id, reply)))
                }
            }
        }
        cachedResponse?.let {
            reply(JSONObject(it).put("id", id))
            return
        }
        rejectedResponse?.let {
            reply(it)
            return
        }
        if (joinedInFlight) return

        val operation = try {
            prepareOperation(request, retryKey.trim(), requestHash)
        } catch (failure: Throwable) {
            completeRequest(key, error(id, -32603, failure.message ?: "MCP operation journal rejected request"))
            return
        }
        operation?.terminalSnapshot?.let { recovered ->
            completeRequest(key, recoveredToolResponse(id, recovered))
            return
        }

        val timeout = watchdog.schedule({
            completeRequest(
                key,
                error(id, -32001, "Local MCP request timed out after ${REQUEST_TIMEOUT_MS}ms")
            )
        }, REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        synchronized(requestLock) {
            val pending = inFlight[key]
            if (pending != null) pending.timeout = timeout else timeout.cancel(false)
        }

        try {
            dispatch(request, operation?.context) { response -> completeRequest(key, response) }
        } catch (failure: Throwable) {
            completeRequest(key, error(id, -32603, failure.message ?: "Local MCP execution failed"))
        }
    }

    private fun dispatchBounded(request: JSONObject, reply: (JSONObject) -> Unit) {
        val id = request.opt("id") ?: JSONObject.NULL
        val operation = try {
            prepareOperation(request, null, requestKey(request))
        } catch (failure: Throwable) {
            reply(error(id, -32603, failure.message ?: "MCP operation journal rejected request"))
            return
        }
        operation?.terminalSnapshot?.let {
            reply(recoveredToolResponse(id, it))
            return
        }

        val terminal = AtomicBoolean(false)
        val timeout = watchdog.schedule({
            if (terminal.compareAndSet(false, true)) {
                reply(error(id, -32001, "Local MCP request timed out after ${REQUEST_TIMEOUT_MS}ms"))
            }
        }, REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        try {
            dispatch(request, operation?.context) { response ->
                if (terminal.compareAndSet(false, true)) {
                    timeout.cancel(false)
                    reply(response)
                }
            }
        } catch (failure: Throwable) {
            if (terminal.compareAndSet(false, true)) {
                timeout.cancel(false)
                reply(error(id, -32603, failure.message ?: "Local MCP execution failed"))
            }
        }
    }

    private fun dispatch(
        request: JSONObject,
        operationContext: RiftMcpOperationContext?,
        reply: (JSONObject) -> Unit
    ) {
        val id = request.opt("id") ?: JSONObject.NULL
        val method = request.optString("method")
        val params = request.optJSONObject("params") ?: JSONObject()

        when (method) {
            "initialize" -> reply(success(id, initializeResult()))
            "ping" -> reply(success(id, JSONObject()))
            "notifications/initialized" -> Unit
            "tools/list" -> {
                val tools = toolHost.tools()
                val manifest = toolHost.manifest()
                reply(success(id, JSONObject()
                    .put("tools", tools)
                    .put("_meta", JSONObject()
                        .put("riftos/toolCount", manifest.getInt("count"))
                        .put("riftos/toolManifestHash", manifest.getString("sha256")))))
            }
            "tools/call" -> handleToolCall(id, params, operationContext, reply)
            else -> reply(error(id, -32601, "Method not found: $method"))
        }
    }

    private fun prepareOperation(
        request: JSONObject,
        retryKey: String?,
        requestHash: String
    ): RiftMcpOperationJournal.BeginResult? {
        if (request.optString("method") != "tools/call") return null
        val params = request.optJSONObject("params") ?: JSONObject()
        val name = params.optString("name").trim()
        if (name.isBlank() || name == "rift_mcp_reconcile") return null
        val args = params.optJSONObject("arguments") ?: JSONObject()
        val modelCallId = params.optJSONObject("_meta")
            ?.optString("riftos/callId")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val requestId = retryKey?.takeIf { it.isNotBlank() } ?: "local-${UUID.randomUUID()}"
        return operationJournal.begin(
            requestId = requestId,
            requestHash = requestHash,
            modelCallId = modelCallId,
            tool = name,
            mutating = toolHost.isMutatingCall(name, args)
        )
    }

    private fun recoveredToolResponse(id: Any, snapshot: JSONObject): JSONObject {
        val status = snapshot.optString("status")
        val operationId = snapshot.optString("operationId")
        val ok = status == "succeeded"
        val summary = JSONObject()
            .put("recovered", true)
            .put("alreadyExecuted", true)
            .put("operationId", operationId)
            .put("journalSequence", snapshot.optLong("lastSequence"))
            .put("tool", snapshot.optString("tool"))
            .put("mutating", snapshot.optBoolean("mutating"))
            .put("executionStatus", status)
            .put("delivery", snapshot.optString("delivery", "unknown"))
            .put("details", snapshot.opt("details") ?: JSONObject.NULL)
        val message = if (ok) {
            "RiftOS recovered a previously completed MCP operation and did not replay it. Use rift_mcp_reconcile for workspace evidence."
        } else {
            "RiftOS recovered a previous MCP operation in status '$status' and did not replay it. Effects may have applied; use rift_mcp_reconcile before retrying."
        }
        val structured = if (ok) {
            JSONObject().put("ok", true).put("value", summary)
        } else {
            JSONObject().put("ok", false).put("error", message).put("recovery", summary)
        }
        val result = JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", message)))
            .put("structuredContent", structured)
            .put("_meta", JSONObject()
                .put("riftos/operationId", operationId)
                .put("riftos/journalSequence", snapshot.optLong("lastSequence"))
                .put("riftos/recoveredReplay", true))
            .put("isError", !ok)
        return success(id, result)
    }

    private fun journalDetails(name: String, rawValue: Any?): JSONObject? {
        if (rawValue !is JSONObject) return null
        return when (name) {
            "rift_local_agent_batch" -> JSONObject()
                .put("jobId", rawValue.optString("jobId"))
                .put("requestId", rawValue.optString("requestId"))
                .put("status", rawValue.optString("status"))
                .put("terminal", rawValue.optBoolean("terminal", false))
                .put("executedSteps", rawValue.optInt("executedSteps", 0))
            "rift_workspace_exec" -> {
                val changes = rawValue.optJSONObject("changes") ?: JSONObject()
                JSONObject()
                    .put("committed", rawValue.optBoolean("committed", false))
                    .put("dryRun", rawValue.optBoolean("dryRun", false))
                    .put("filesChanged", changes.optInt("filesChanged", 0))
            }
            else -> null
        }
    }

    private fun completeRequest(key: String, response: JSONObject) {
        val serialized = response.toString()
        val waiters = synchronized(requestLock) {
            val pending = inFlight.remove(key) ?: return
            pending.timeout?.cancel(false)
            val responseBytes = serialized.toByteArray(Charsets.UTF_8).size
            completed.remove(key)?.let { completedBytes -= it.bytes.toLong() }
            completed[key] = CompletedRequest(
                serialized,
                responseBytes,
                System.currentTimeMillis() + COMPLETED_TTL_MS
            )
            completedBytes += responseBytes.toLong()
            while (completed.size > MAX_COMPLETED_REQUESTS || completedBytes > MAX_COMPLETED_BYTES) {
                val oldest = completed.entries.iterator()
                if (!oldest.hasNext()) break
                val removed = oldest.next().value
                oldest.remove()
                completedBytes -= removed.bytes.toLong()
            }
            pending.waiters.toList()
        }
        waiters.forEach { waiter ->
            runCatching { waiter.reply(JSONObject(serialized).put("id", waiter.id)) }
        }
    }

    private fun pruneCompletedLocked() {
        val now = System.currentTimeMillis()
        val entries = completed.entries.iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            if (entry.value.expiresAt <= now) {
                completedBytes -= entry.value.bytes.toLong()
                entries.remove()
            }
        }
        if (completedBytes < 0L) completedBytes = 0L
    }

    private fun requestKey(request: JSONObject): String {
        val normalized = JSONObject(request.toString()).apply { remove("id") }
        val canonical = canonicalJson(normalized)
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

    private fun handleToolCall(
        id: Any,
        params: JSONObject,
        operationContext: RiftMcpOperationContext?,
        reply: (JSONObject) -> Unit
    ) {
        val name = params.optString("name").trim()
        if (name.isBlank()) {
            reply(error(id, -32602, "tools/call requires a tool name"))
            return
        }
        val args = params.optJSONObject("arguments") ?: JSONObject()
        val requestMeta = params.optJSONObject("_meta") ?: JSONObject()
        val modelCallId = requestMeta.optString("riftos/callId")
            .trim()
            .takeIf { it.isNotBlank() }
        val mcpSpan = debugHub.start(
            component = "mcp.server",
            operation = "tools.call",
            traceId = modelCallId,
            attributes = mapOf("tool" to name)
        )
        toolHost.callAsync(name, args, mcpSpan.context, operationContext) { call ->
            val ok = call.optBoolean("ok", false)
            val errorText = if (ok) null else call.optString("error", "Rift tool failed")
            val rawValue = if (ok) call.opt("value") else null

            val journalSnapshot = try {
                operationContext?.let {
                    operationJournal.complete(
                        operationId = it.operationId,
                        ok = ok,
                        error = errorText,
                        details = journalDetails(name, rawValue)
                    )
                }
            } catch (failure: Throwable) {
                val warning = "MCP operation completed but reconciliation journal finalization failed; do not replay blindly: ${failure.message ?: failure.javaClass.simpleName}"
                mcpSpan.failure(warning, mapOf("tool" to name))
                val result = JSONObject()
                    .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", warning)))
                    .put("structuredContent", JSONObject().put("ok", false).put("error", warning))
                    .put("_meta", JSONObject()
                        .put("riftos/traceId", mcpSpan.context.traceId)
                        .put("riftos/operationId", operationContext?.operationId ?: JSONObject.NULL))
                    .put("isError", true)
                reply(success(id, result))
                return@callAsync
            }

            val image = if (name == "rift_shell_exec" && rawValue is JSONObject) {
                rawValue.optJSONObject("result")?.optJSONObject("_riftImage")
            } else null
            val safeValue: Any? = when {
                name == "rift_project_export" && rawValue is JSONObject -> exportSummary(rawValue)
                name == "rift_shell_exec" && rawValue is JSONObject -> sanitizeShellValue(rawValue)
                else -> rawValue ?: JSONObject.NULL
            }
            val structured = JSONObject().put("ok", ok)
            if (ok) structured.put("value", safeValue)
            else structured.put("error", errorText ?: "Rift tool failed")

            val text = if (ok) {
                when (safeValue) {
                    null, JSONObject.NULL -> "null"
                    is JSONObject, is JSONArray -> safeValue.toString()
                    else -> safeValue.toString()
                }
            } else {
                errorText ?: "Rift tool failed"
            }

            val resultMeta = JSONObject()
                .put("riftos/traceId", mcpSpan.context.traceId)
            if (modelCallId != null) resultMeta.put("riftos/callId", modelCallId)
            if (operationContext != null) {
                resultMeta
                    .put("riftos/operationId", operationContext.operationId)
                    .put("riftos/journalSequence", journalSnapshot?.optLong("lastSequence") ?: operationContext.startSequence)
                    .put("riftos/executionStatus", journalSnapshot?.optString("status") ?: "running")
            }

            val content = JSONArray().put(JSONObject().put("type", "text").put("text", text))
            if (ok && image != null) {
                val data = image.optString("data")
                if (data.isNotBlank()) {
                    content.put(JSONObject()
                        .put("type", "image")
                        .put("data", data)
                        .put("mimeType", image.optString("mimeType", "image/jpeg")))
                }
            }

            val result = JSONObject()
                .put("content", content)
                .put("structuredContent", structured)
                .put("_meta", resultMeta)
                .put("isError", !ok)
            if (ok) {
                mcpSpan.success(mapOf("tool" to name))
            } else {
                mcpSpan.failure(errorText ?: "Rift tool failed", mapOf("tool" to name))
            }
            reply(success(id, result))
        }
    }

    private fun sanitizeShellValue(value: JSONObject): JSONObject {
        val copy = JSONObject(value.toString())
        val result = copy.optJSONObject("result") ?: return copy
        val image = result.optJSONObject("_riftImage") ?: return copy
        result.put("_riftImage", JSONObject()
            .put("attached", true)
            .put("mimeType", image.optString("mimeType", "image/jpeg"))
            .put("name", image.optString("name", "vortex-preview.jpg"))
            .put("bytes", image.optInt("bytes", 0)))
        return copy
    }

    private fun initializeResult(): JSONObject {
        val manifest = toolHost.manifest()
        val manifestHash = manifest.getString("sha256")
        return JSONObject()
            .put("protocolVersion", PROTOCOL_VERSION)
            .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
            .put(
                "serverInfo",
                JSONObject()
                    .put("name", "rift-local-mcp")
                    .put("version", "$SERVER_VERSION-${manifestHash.take(12)}")
            )
            .put("_meta", JSONObject()
                .put("riftos/toolCount", manifest.getInt("count"))
                .put("riftos/toolManifestHash", manifestHash)
                .put("riftos/sourceSha", BuildConfig.RIFT_SOURCE_SHA)
                .put("riftos/buildRunId", BuildConfig.RIFT_BUILD_RUN_ID)
                .put("riftos/buildRunNumber", BuildConfig.RIFT_BUILD_RUN_NUMBER))
            .put(
                "instructions",
                "RiftOS workspace tools with Project Intelligence v2 behind the existing stable tool surface. All filesystem capabilities are hard-scoped to workspace/. Prefer rift_workspace_exec for local project graph/impact analysis, symbol/reference lookup, surgical reads/patches, dry-run validation and transactional multi-file work. The project operation accepts kind=graph, kind=impact or kind=validation with query for focused analysis. The live manifest hash/count are returned for diagnostics. The tool list is static for this server process; clients that cached an older action catalog must explicitly refresh/rescan their MCP app actions after a RiftOS upgrade. Device-side permissions and audit remain authoritative across local and relay transports; there is no direct model API."
            )
    }

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
