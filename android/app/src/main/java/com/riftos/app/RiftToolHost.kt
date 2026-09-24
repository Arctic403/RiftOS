package com.riftos.app

import android.os.SystemClock
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal object RiftCliExecutionGate {
    private val lock = ReentrantLock(true)
    private val outstandingJobId = AtomicReference<String?>(null)

    fun tryReserve(jobId: String): Boolean =
        outstandingJobId.compareAndSet(null, jobId)

    fun release(jobId: String) {
        outstandingJobId.compareAndSet(jobId, null)
    }

    fun outstandingJob(): String? = outstandingJobId.get()

    fun <T> run(block: () -> T): T {
        lock.lockInterruptibly()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }
}

/** Canonical device-side capability registry for the local Rift MCP server. */
class RiftToolHost(
    context: Context,
    initialShellExecutor: RiftShellExecutor? = null,
    private val debugHub: RiftDebugHub = RiftDebugHub()
) {
    companion object {
        private const val PREFS = "rift-mcp-tools"
        private const val LEGACY_PREFS = "rift-bridge"
        private const val PREF_ALLOW_READ = "allowRead"
        private const val PREF_ALLOW_WRITE = "allowWrite"
        private const val PREF_AUDIT = "audit"
        private const val PREF_MIGRATED = "legacyStateMigrated"
        private const val MAX_AUDIT = 100
        private const val MAX_CLI_JOBS = 16
        private const val CLI_JOB_RETENTION_MS = 5 * 60 * 1000L
        private const val MAX_CLI_RETAINED_RESULT_BYTES = 2 * 1024 * 1024
        private const val MAX_CLI_BATCH_TOOL_ARGS_BYTES = 64 * 1024
        private val WORKSPACE_OPS = setOf("project", "snapshot", "stat", "hash", "list", "search", "symbols", "references", "read", "read_range", "read_symbol", "write", "replace", "patch", "patch_range", "apply_hunks", "mkdir", "remove", "move", "rename", "copy", "archive", "extract")
        const val SCOPE = "riftfs/workspace"
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val sandbox: RiftToolSandbox
    private val n2M1Diagnostic: JSONObject
    private val n2M2Diagnostic: JSONObject
    private val n2M3Diagnostic: JSONObject
    private data class CliJob(
        val id: String,
        val requestId: String,
        val name: String,
        val createdAt: Long,
        @Volatile var updatedAt: Long,
        @Volatile var status: String,
        @Volatile var cancelRequested: Boolean = false,
        @Volatile var response: JSONObject? = null,
        @Volatile var future: Future<*>? = null
    )

    private val cliJobs = ConcurrentHashMap<String, CliJob>()

    private fun emitCliJob(
        job: CliJob,
        type: String,
        result: Any? = null,
        message: String? = null
    ) {
        val terminal = job.status in setOf(
            "completed",
            "completed_result_too_large",
            "failed",
            "cancelled",
            "cancelled_may_have_applied",
            "completed_after_cancel_request"
        )
        RiftMcpRuntime.cliEvents().emitJob(
            type = type,
            jobId = job.id,
            requestId = job.requestId,
            lane = "tool",
            status = job.status,
            terminal = terminal,
            result = result,
            message = message
        )
    }
    @Volatile private var shellExecutor: RiftShellExecutor? = initialShellExecutor

    fun setShellExecutor(executor: RiftShellExecutor) {
        shellExecutor = executor
    }

    /**
     * Internal Local Agent evidence path. This is deliberately absent from tools()/methodFor().
     */
    internal fun candidateImpactAsync(reply: (JSONObject) -> Unit) {
        sandbox.candidateImpactAsync(reply)
    }

    init {
        migrateLegacyState()
        sandbox = RiftToolSandbox(appContext)
        n2M1Diagnostic = RiftMemoryN2M1SelfTest.run(appContext)
        n2M2Diagnostic = RiftMemoryN2M2SelfTest.run(appContext)
        n2M3Diagnostic = RiftMemoryN2M3SelfTest.run(appContext)
    }

    fun access(): JSONObject = JSONObject()
        .put("sandboxRead", allowRead())
        .put("sandboxWrite", allowWrite())
        .put("scope", SCOPE)
        .put("workspaceScope", "riftfs/workspace")
        .put("workspaceOnly", true)
        .put("localOnly", true)
        .put("codeMode", "rift-code-mode-v1")
        .put("projectIntelligence", "v2")
        .put("readTools", JSONArray(listOf("rift_info", "rift_stat", "rift_hash", "rift_list", "rift_read_text", "rift_audit", "rift_scan", "rift_project_export", "rift_workspace_diff", "rift_workspace_exec", "rift_debug")))
        .put("writeTools", JSONArray(listOf("rift_write_text", "rift_mkdir", "rift_remove", "rift_move", "rift_copy", "rift_archive", "rift_extract")))
        .put("conditionalWriteTools", JSONArray(listOf("rift_workspace_exec")))

    fun setAccess(read: Boolean, write: Boolean): JSONObject {
        prefs.edit()
            .putBoolean(PREF_ALLOW_READ, read)
            .putBoolean(PREF_ALLOW_WRITE, write)
            .apply()
        return access()
    }

    fun manifest(): JSONObject {
        val definitions = tools()
        val names = JSONArray()
        for (index in 0 until definitions.length()) {
            val name = definitions.optJSONObject(index)?.optString("name")?.trim().orEmpty()
            if (name.isNotBlank()) names.put(name)
        }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(definitions.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return JSONObject()
            .put("count", definitions.length())
            .put("names", names)
            .put("sha256", hash)
            .put("scope", SCOPE)
    }

    fun tools(): JSONArray = JSONArray()
        .put(tool(
            "rift_shell_exec",
            "Execute a RiftShell command through the process-owned native core. Does not expose raw Android shell access or a renderer compatibility fallback. IMPORTANT: RiftShell batch and batch --dry-run are DISABLED due to unresolved hangs; never call batch. Use individual commands or MCP file operations instead.",
            objectSchema(JSONObject()
                .put("command", stringProperty("RiftShell command to execute. The batch command is DISABLED and must not be used."))
                .put("cwd", stringProperty("Optional RiftShell working directory.")), listOf("command"))
        ))
        .put(tool("rift_info", "Inspect the local Rift MCP workspace sandbox and storage limits.", objectSchema()))
        .put(tool(
            "rift_stat",
            "Get metadata for one file or directory under workspace/.",
            objectSchema(JSONObject().put("path", stringProperty("Path under workspace/.")), listOf("path"))
        ))
        .put(tool(
            "rift_hash",
            "Compute a complete SHA-256 digest for a file or a deterministic digest for a directory tree under workspace/.",
            objectSchema(JSONObject().put("path", stringProperty("Path under workspace/.")), listOf("path"))
        ))
        .put(tool(
            "rift_list",
            "List files and directories under workspace/.",
            objectSchema(
                JSONObject()
                    .put("path", stringProperty("Path under workspace/. Empty means workspace/."))
                    .put("recursive", booleanProperty("Recursively include descendants."))
            )
        ))
        .put(tool(
            "rift_read_text",
            "Read a UTF-8 text file under workspace/.",
            objectSchema(JSONObject().put("path", stringProperty("Path under workspace/.")), listOf("path"))
        ))
        .put(tool(
            "rift_write_text",
            "Create or replace a UTF-8 text file under workspace/. Local write permission must be enabled.",
            objectSchema(
                JSONObject()
                    .put("path", stringProperty("Path under workspace/."))
                    .put("text", stringProperty("Complete UTF-8 file contents.")),
                listOf("path", "text")
            )
        ))
        .put(tool(
            "rift_mkdir",
            "Create a directory under workspace/. Local write permission must be enabled.",
            objectSchema(JSONObject().put("path", stringProperty("Path under workspace/.")), listOf("path"))
        ))
        .put(tool(
            "rift_remove",
            "Remove a file or directory under workspace/. Local write permission must be enabled.",
            objectSchema(JSONObject().put("path", stringProperty("Sandbox-relative path to remove.")), listOf("path"))
        ))
        .put(tool(
            "rift_move",
            "Move or rename a file or directory under workspace/. Local write permission must be enabled.",
            objectSchema(
                JSONObject()
                    .put("from", stringProperty("Source path under workspace/."))
                    .put("to", stringProperty("Destination path under workspace/."))
                    .put("overwrite", booleanProperty("Replace an existing destination when true.")),
                listOf("from", "to")
            )
        ))
        .put(tool(
            "rift_copy",
            "Copy a file or directory under workspace/. Local write permission must be enabled.",
            objectSchema(
                JSONObject()
                    .put("from", stringProperty("Source path under workspace/."))
                    .put("to", stringProperty("Destination path under workspace/."))
                    .put("overwrite", booleanProperty("Replace an existing destination when true.")),
                listOf("from", "to")
            )
        ))
        .put(tool(
            "rift_archive",
            "Create a ZIP archive from a file or directory under workspace/. Local write permission must be enabled.",
            objectSchema(
                JSONObject()
                    .put("from", stringProperty("Source path under workspace/."))
                    .put("to", stringProperty("Destination .zip path under workspace/."))
                    .put("overwrite", booleanProperty("Replace an existing destination when true.")),
                listOf("from", "to")
            )
        ))
        .put(tool(
            "rift_extract",
            "Safely extract a ZIP archive into a directory under workspace/. Rejects traversal, duplicate entries, oversized archives, and partial commits.",
            objectSchema(
                JSONObject()
                    .put("from", stringProperty("Source .zip path under workspace/."))
                    .put("to", stringProperty("Destination directory under workspace/."))
                    .put("overwrite", booleanProperty("Replace an existing destination when true.")),
                listOf("from", "to")
            )
        ))
        .put(tool(
            "rift_audit",
            "Run a local RiftOS project health audit. Scans workspace structure, source patterns, and runtime risk indicators without mutating files.",
            objectSchema(JSONObject().put("path", stringProperty("Optional project path under workspace/.")))))
        .put(tool(
            "rift_scan",
            "Run a focused local project scan. Supported modes: security, runtime, architecture, all.",
            objectSchema(
                JSONObject()
                    .put("path", stringProperty("Optional project path under workspace/."))
                    .put("mode", stringProperty("Scan mode: security, runtime, architecture, or all."))
            )))
        .put(tool(
            "rift_project_export",
            "Stream a deterministic RIFT_PROJECT_EXPORT_V2 snapshot containing complete UTF-8 source code for offline full-project audits. Continue with nextCursor and the same snapshotId until done. Binary/build/secret files are excluded and large source files are split safely across pages.",
            objectSchema(
                JSONObject()
                    .put("path", stringProperty("Optional project path under workspace/."))
                    .put("cursor", stringProperty("Opaque nextCursor from the previous export page. Empty starts a new export."))
                    .put("expectedSnapshot", stringProperty("snapshotId from the first page. Reject continuation if any exported source changed."))
                    .put("maxBytes", JSONObject().put("type", "integer").put("description", "Target response size in bytes; clamped to 64 KiB..400 KiB to stay below the relay envelope limit."))
            )
        ))
        .put(tool(
            "rift_workspace_diff",
            "Read the persistent private workspace record store. Returns all files changed since the current local checkpoint plus bounded git-style text diffs and recent writer-agnostic records. This tool never mutates workspace files.",
            objectSchema(
                JSONObject()
                    .put("path", stringProperty("Optional workspace-relative subtree to inspect. Empty means the entire workspace."))
                    .put("limit", JSONObject().put("type", "integer").put("description", "Maximum recent record entries to return; clamped to 1..250."))
                    .put("includeDiff", booleanProperty("Include bounded git-style text diffs for changed text files and record entries."))
            )
        ))
        .put(tool(
            "rift_debug",
            "Read the passive process-wide RiftDebugHub. Actions: status, events, active, components. The debugger has no execution, mutation, cancellation, filesystem, network, or model authority.",
            objectSchema(
                JSONObject()
                    .put("action", JSONObject()
                        .put("type", "string")
                        .put("enum", JSONArray(listOf("status", "events", "active", "components"))))
                    .put("traceId", stringProperty("Optional exact trace identifier filter."))
                    .put("component", stringProperty("Optional exact component filter."))
                    .put("sinceSequence", JSONObject().put("type", "integer").put("description", "Only return events newer than this sequence."))
                    .put("limit", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 200))
            )
        ))
        // Keep the published rift_workspace_exec schema/description stable while Project Intelligence v2 evolves behind it.
        // Changing this model-visible definition changes manifest().sha256 and can force cached MCP clients to rescan actions.
        .put(tool(
            "rift_workspace_exec",
            "Rift Code Mode + Project Intelligence v2. MULTI-OP/BATCH MODE IS DISABLED because it can hang the agent/runtime. Pass exactly one workspace operation per call. Supports snapshots, symbol search, reference lookup, surgical reads, exact/range/hunk patches, and dry-run validation under workspace/. Read permission is always required; write permission is required only when that single operation mutates files.",
            objectSchema(
                JSONObject()
                    .put(
                        "operations",
                        JSONObject()
                            .put("type", "array")
                            .put("minItems", 1)
                            .put("maxItems", 1)
                            .put("description", "Exactly one local workspace operation. Multi-op/batch mode is DISABLED. Canonical form is flat JSON: {\"op\":\"stat\",\"path\":\"workspace/project\"}. Do not nest the operation name.")
                            .put("items", workspaceOperationSchema())
                    )
                    .put("finish", booleanProperty("Set true only when this single mutating operation is intended to finish the task. RiftBrowser still returns the confirmed result to ChatGPT before completing the session."))
                    .put("dryRun", booleanProperty("Execute and validate read/content-edit operations transactionally, then restore mutations instead of committing. Structural mkdir/remove/move/copy/archive/extract operations are rejected in dry-run mode."))
                    .put("intent", stringProperty("Optional bounded human/model intent for local patch-session provenance. It is evidence only and never authorizes a mutation."))
                    .put("expectedSnapshot", stringProperty("Optional project/workspace snapshot id. Reject the operation if that snapshot scope changed."))
                    .put("expectedExportSnapshot", stringProperty("Optional snapshotId from rift_project_export. Reject the operation if exported source changed after the audit."))
                    .put("snapshotPath", stringProperty("Optional workspace path used for expectedSnapshot/returnSnapshot. Defaults to workspace/."))
                    .put("returnSnapshot", booleanProperty("Return a fresh scoped snapshot after the operation. Disabled by default to avoid rescanning large projects.")),
                listOf("operations")
            )
        ))

    fun callAsync(rawName: String, args: JSONObject, reply: (JSONObject) -> Unit) {
        callAsync(rawName, args, debugContext = null, reply = reply)
    }

    fun callAsync(
        rawName: String,
        args: JSONObject,
        debugContext: RiftDebugContext?,
        reply: (JSONObject) -> Unit
    ) {
        callAsyncInternal(rawName, args, bypassAccess = false, debugContext = debugContext, rawReply = reply)
    }

    internal fun callAsyncCancellable(
        rawName: String,
        args: JSONObject,
        debugContext: RiftDebugContext?,
        reply: (JSONObject) -> Unit
    ): RiftAsyncHandle =
        callAsyncInternal(rawName, args, bypassAccess = false, debugContext = debugContext, rawReply = reply)

    internal fun validateCliBatchTool(rawName: String, args: JSONObject): JSONObject {
        val name = canonicalName(rawName)
        val forbidden = setOf(
            "rift_shell_exec",
            "rift_workspace_exec",
            "rift_cli_batch",
            "rift_cli_job_list",
            "rift_cli_job_poll",
            "rift_cli_job_cancel"
        )
        if (name in forbidden) {
            return JSONObject()
                .put("ok", false)
                .put("name", name)
                .put("error", "RiftCLI Batch V2 forbids nested/control tool: $name")
        }
        if (!name.startsWith("rift_")) {
            return JSONObject()
                .put("ok", false)
                .put("name", name)
                .put("error", "RiftCLI Batch V2 tool names must use the rift_* namespace")
        }
        val argsBytes = args.toString().toByteArray(Charsets.UTF_8).size
        if (argsBytes > MAX_CLI_BATCH_TOOL_ARGS_BYTES) {
            return JSONObject()
                .put("ok", false)
                .put("name", name)
                .put("error", "RiftCLI Batch V2 tool args exceed $MAX_CLI_BATCH_TOOL_ARGS_BYTES UTF-8 bytes")
        }
        val normalizedArgs = try {
            normalizeToolArgs(name, args)
        } catch (error: Throwable) {
            return JSONObject()
                .put("ok", false)
                .put("name", name)
                .put("error", error.message ?: "Invalid RiftCLI Batch V2 tool arguments")
        }
        if (name != "rift_debug" && methodFor(name) == null) {
            return JSONObject()
                .put("ok", false)
                .put("name", name)
                .put("error", "Unsupported RiftCLI Batch V2 tool: $rawName")
        }
        return JSONObject()
            .put("ok", true)
            .put("name", name)
            .put("args", normalizedArgs)
    }

    /**
     * Execute one already-authorized CLI Batch V2 tool step synchronously.
     *
     * The batch owner holds the global CLI authority reservation and execution gate for the whole
     * plan. This helper therefore never creates a nested job or reserves the gate again.
     */
    internal fun executeCliBatchTool(
        rawName: String,
        args: JSONObject,
        stepRequestId: String
    ): JSONObject {
        val validation = validateCliBatchTool(rawName, args)
        if (!validation.optBoolean("ok", false)) {
            val name = validation.optString("name", rawName)
            val error = validation.optString("error", "Invalid RiftCLI Batch V2 tool step")
            recordAudit(name, args, false, error)
            return JSONObject(validation.toString())
        }
        val name = validation.optString("name")
        val normalizedArgs = validation.optJSONObject("args") ?: JSONObject()

        if (name == "rift_debug") {
            return try {
                val value = debugHub.query(normalizedArgs)
                recordAudit(name, normalizedArgs, true, null, 0L)
                JSONObject().put("ok", true).put("name", name).put("value", value)
            } catch (error: Throwable) {
                val message = error.message ?: "Invalid rift_debug batch request"
                recordAudit(name, normalizedArgs, false, message, 0L)
                JSONObject().put("ok", false).put("name", name).put("error", message)
            }
        }

        val method = methodFor(name)
            ?: return JSONObject()
                .put("ok", false)
                .put("name", name)
                .put("error", "Unsupported RiftCLI Batch V2 tool: $rawName")

        val started = SystemClock.elapsedRealtime()
        val request = JSONObject()
            .put("id", "cli-batch-$stepRequestId")
            .put("method", method)
            .put("args", JSONObject(normalizedArgs.toString()).put("intent", "rift-cli-batch:$stepRequestId"))

        val raw = sandbox.executeCliBatchRequest(request.toString())
        val response = runCatching { JSONObject(raw) }
            .getOrElse {
                JSONObject()
                    .put("ok", false)
                    .put("error", "RiftCLI Batch V2 sandbox returned invalid JSON")
            }
        val duration = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)

        if (!response.optBoolean("ok", false)) {
            val error = response.optString("error").takeIf { it.isNotBlank() } ?: "RiftCLI Batch V2 tool failed"
            recordAudit(name, normalizedArgs, false, error, duration)
            return JSONObject().put("ok", false).put("name", name).put("error", error).put("durationMs", duration)
        }

        val value = response.opt("value") ?: JSONObject.NULL
        if (name == "rift_info" && value is JSONObject) {
            value.put("mcpManifest", manifest())
            value.put("riftMemoryN2M1", JSONObject(n2M1Diagnostic.toString()))
            value.put("riftMemoryN2M2", JSONObject(n2M2Diagnostic.toString()))
            value.put("riftMemoryN2M3", JSONObject(n2M3Diagnostic.toString()))
        }
        recordAudit(name, normalizedArgs, true, null, duration)
        return JSONObject()
            .put("ok", true)
            .put("name", name)
            .put("durationMs", duration)
            .put("value", value)
    }

    /**
     * Trusted in-process RiftCLI live-poll lane.
     *
     * The native C++ enable gate authorizes entry. Jobs run through the same confined/audited
     * sandbox implementations, but the CLI lane has no fixed wall-clock timeout. The external
     * driver observes progress by polling and may explicitly cancel a job.
     */
    internal fun startCliJob(rawName: String, args: JSONObject, driverRequestId: String): JSONObject {
        pruneCliJobs()
        val name = canonicalName(rawName)
        if (name == "rift_shell_exec" || name == "rift_workspace_exec") {
            val error = "RiftCLI tool lane forbids $name; use one bounded RiftCLI shell dispatch and never workspace-exec/batch."
            recordAudit(name, args, false, error)
            return JSONObject().put("ok", false).put("name", name).put("error", error)
        }

        val method = methodFor(name)
        if (name != "rift_debug" && method == null) {
            val error = "Unsupported Rift tool: $rawName"
            recordAudit(rawName.take(120), args, false, error)
            return JSONObject().put("ok", false).put("name", rawName).put("error", error)
        }

        val normalizedArgs = try {
            normalizeToolArgs(name, args)
        } catch (error: Throwable) {
            val message = error.message ?: "Invalid Rift tool arguments"
            recordAudit(name, args, false, message)
            return JSONObject().put("ok", false).put("name", name).put("error", message)
        }

        if (cliJobs.size >= MAX_CLI_JOBS) {
            pruneCliJobs(forceTerminalTrim = true)
        }
        if (cliJobs.size >= MAX_CLI_JOBS) {
            return JSONObject()
                .put("ok", false)
                .put("name", name)
                .put("error", "RiftCLI live-poll job capacity reached ($MAX_CLI_JOBS); poll/cancel existing jobs first.")
        }

        val now = SystemClock.elapsedRealtime()
        val jobId = "cli-job-" + UUID.randomUUID().toString()
        if (!RiftCliExecutionGate.tryReserve(jobId)) {
            return JSONObject()
                .put("ok", false)
                .put("name", name)
                .put("error", "RiftCLI already has one outstanding authority job")
                .put("outstandingJobId", RiftCliExecutionGate.outstandingJob() ?: JSONObject.NULL)
        }
        val job = CliJob(jobId, driverRequestId, name, now, now, "queued")
        cliJobs[jobId] = job
        emitCliJob(job, "job.submitted")

        if (name == "rift_debug") {
            val response = try {
                val value = debugHub.query(normalizedArgs)
                recordAudit(name, normalizedArgs, true, null)
                JSONObject().put("ok", true).put("name", name).put("value", value)
            } catch (failure: Throwable) {
                val error = failure.message ?: "Invalid rift_debug request"
                recordAudit(name, normalizedArgs, false, error)
                JSONObject().put("ok", false).put("name", name).put("error", error)
            }
            synchronized(job) {
                job.response = response
                job.status = if (response.optBoolean("ok", false)) "completed" else "failed"
                job.updatedAt = SystemClock.elapsedRealtime()
            }
            emitCliJob(
                job,
                if (job.status == "completed") "job.completed" else "job.failed",
                response
            )
            RiftCliExecutionGate.release(jobId)
            return cliJobSnapshot(job)
        }

        val requestId = "cli-tool-$jobId"
        val request = JSONObject()
            .put("id", requestId)
            .put("method", method)
            .put("args", normalizedArgs)

        val future = try {
            sandbox.submitCliJob(
                request.toString(),
                onStart = {
                    var started = false
                    synchronized(job) {
                        if (job.status == "queued") {
                            job.status = "running"
                            job.updatedAt = SystemClock.elapsedRealtime()
                            started = true
                        }
                    }
                    if (started) emitCliJob(job, "job.started")
                }
            ) { raw ->
                try {
                    val response = runCatching { JSONObject(raw) }
                        .getOrElse { JSONObject().put("ok", false).put("error", "RiftCLI sandbox returned invalid JSON") }
                    val terminalResponse = try {
                        if (response.optBoolean("ok", false)) {
                            val value = response.opt("value") ?: JSONObject.NULL
                            if (name == "rift_info" && value is JSONObject) {
                                value.put("mcpManifest", manifest())
                                value.put("connectorRefresh", JSONObject()
                                    .put("toolListStaticForProcess", true)
                                    .put("refreshClientActionsWhenCountDiffers", true)
                                    .put("note", "If a client exposes fewer tools than mcpManifest.count, refresh/rescan that client's MCP app actions; reconnecting the relay alone does not replace a cached client action catalog."))
                                value.put("riftMemoryN2M1", JSONObject(n2M1Diagnostic.toString()))
                                value.put("riftMemoryN2M2", JSONObject(n2M2Diagnostic.toString()))
                                value.put("riftMemoryN2M3", JSONObject(n2M3Diagnostic.toString()))
                            }
                            recordAudit(name, normalizedArgs, true, null)
                            JSONObject().put("ok", true).put("name", name).put("value", value)
                        } else {
                            val error = response.optString("error").takeIf { it.isNotBlank() } ?: "Rift sandbox call failed"
                            recordAudit(name, normalizedArgs, false, error)
                            JSONObject().put("ok", false).put("name", name).put("error", error)
                        }
                    } catch (error: Throwable) {
                        JSONObject()
                            .put("ok", false)
                            .put("name", name)
                            .put("error", error.message ?: error.javaClass.simpleName)
                    }

                    val terminalBytes = terminalResponse.toString().toByteArray(Charsets.UTF_8).size
                    val retainedResponse = if (terminalBytes > MAX_CLI_RETAINED_RESULT_BYTES) {
                        JSONObject()
                            .put("ok", terminalResponse.optBoolean("ok", false))
                            .put("name", name)
                            .put("resultRetained", false)
                            .put("resultBytes", terminalBytes)
                            .put("retentionLimitBytes", MAX_CLI_RETAINED_RESULT_BYTES)
                            .put("note", "RiftCLI completed the operation but did not retain an oversized result; use a smaller bounded read/export.")
                    } else {
                        terminalResponse
                    }

                    synchronized(job) {
                        job.response = retainedResponse
                        job.status = when {
                            job.cancelRequested && terminalResponse.optBoolean("ok", false) -> "completed_after_cancel_request"
                            job.cancelRequested -> "cancelled_may_have_applied"
                            terminalResponse.optBoolean("ok", false) && terminalBytes > MAX_CLI_RETAINED_RESULT_BYTES -> "completed_result_too_large"
                            terminalResponse.optBoolean("ok", false) -> "completed"
                            else -> "failed"
                        }
                        job.updatedAt = SystemClock.elapsedRealtime()
                    }
                    emitCliJob(
                        job,
                        when (job.status) {
                            "completed", "completed_result_too_large", "completed_after_cancel_request" -> "job.completed"
                            "cancelled", "cancelled_may_have_applied" -> "job.cancelled"
                            else -> "job.failed"
                        },
                        retainedResponse
                    )
                } finally {
                    RiftCliExecutionGate.release(jobId)
                }
            }
        } catch (error: Throwable) {
            val message = error.message ?: error.javaClass.simpleName
            recordAudit(name, normalizedArgs, false, message)
            synchronized(job) {
                job.response = JSONObject().put("ok", false).put("name", name).put("error", message)
                job.status = "failed"
                job.updatedAt = SystemClock.elapsedRealtime()
            }
            emitCliJob(job, "job.failed", job.response)
            RiftCliExecutionGate.release(jobId)
            return cliJobSnapshot(job)
        }
        job.future = future
        return cliJobSnapshot(job)
    }

    internal fun listCliJobs(requestId: String? = null): JSONObject {
        pruneCliJobs()
        val rows = JSONArray()
        cliJobs.values
            .filter { requestId.isNullOrBlank() || it.requestId == requestId }
            .sortedBy { it.createdAt }
            .forEach { rows.put(cliJobSnapshot(it, includeResult = false)) }
        return JSONObject().put("ok", true).put("jobs", rows)
    }

    internal fun pollCliJob(jobId: String): JSONObject {
        pruneCliJobs()
        val job = cliJobs[jobId]
            ?: return JSONObject().put("ok", false).put("error", "Unknown or expired RiftCLI job: $jobId")
        return cliJobSnapshot(job)
    }

    internal fun cancelCliJob(jobId: String): JSONObject {
        val job = cliJobs[jobId]
            ?: return JSONObject().put("ok", false).put("error", "Unknown or expired RiftCLI job: $jobId")
        synchronized(job) {
            if (job.status in setOf("completed", "completed_result_too_large", "failed", "cancelled", "cancelled_may_have_applied", "completed_after_cancel_request")) {
                return cliJobSnapshot(job)
            }
            job.cancelRequested = true
            val wasQueued = job.status == "queued"
            val cancelled = job.future?.cancel(true) == true
            job.updatedAt = SystemClock.elapsedRealtime()
            if (wasQueued && cancelled) {
                job.status = "cancelled"
                job.response = JSONObject()
                    .put("ok", false)
                    .put("name", job.name)
                    .put("error", "RiftCLI job cancelled before execution")
                RiftCliExecutionGate.release(job.id)
            } else {
                job.status = "cancelling"
            }
        }
        emitCliJob(
            job,
            if (job.status == "cancelled") "job.cancelled" else "job.cancelling",
            job.response
        )
        return cliJobSnapshot(job)
    }

    internal fun cancelAllCliJobs(reason: String): JSONObject {
        var requested = 0
        val changed = ArrayList<CliJob>()
        cliJobs.values.forEach { job ->
            synchronized(job) {
                if (job.status !in setOf("completed", "completed_result_too_large", "failed", "cancelled", "cancelled_may_have_applied", "completed_after_cancel_request")) {
                    job.cancelRequested = true
                    val wasQueued = job.status == "queued"
                    val cancelled = job.future?.cancel(true) == true
                    job.updatedAt = SystemClock.elapsedRealtime()
                    if (wasQueued && cancelled) {
                        job.status = "cancelled"
                        job.response = JSONObject()
                            .put("ok", false)
                            .put("name", job.name)
                            .put("error", reason)
                        RiftCliExecutionGate.release(job.id)
                    } else {
                        job.status = "cancelling"
                    }
                    changed += job
                    requested++
                }
            }
        }
        changed.forEach { job ->
            emitCliJob(
                job,
                if (job.status == "cancelled") "job.cancelled" else "job.cancelling",
                job.response,
                reason
            )
        }
        return JSONObject().put("ok", true).put("cancellationRequested", requested)
    }

    private fun cliJobSnapshot(job: CliJob, includeResult: Boolean = true): JSONObject = synchronized(job) {
        JSONObject()
            .put("ok", true)
            .put("jobOk", when (job.status) {
                "completed", "completed_result_too_large", "completed_after_cancel_request" -> true
                "failed", "cancelled", "cancelled_may_have_applied" -> false
                else -> JSONObject.NULL
            })
            .put("jobId", job.id)
            .put("requestId", job.requestId)
            .put("tool", job.name)
            .put("status", job.status)
            .put("terminal", job.status in setOf("completed", "completed_result_too_large", "failed", "cancelled", "cancelled_may_have_applied", "completed_after_cancel_request"))
            .put("createdAtElapsedMs", job.createdAt)
            .put("updatedAtElapsedMs", job.updatedAt)
            .put("cancelRequested", job.cancelRequested)
            .put("elapsedMs", (SystemClock.elapsedRealtime() - job.createdAt).coerceAtLeast(0L))
            .also { snapshot ->
                if (includeResult) snapshot.put("result", job.response ?: JSONObject.NULL)
            }
    }

    private fun pruneCliJobs(forceTerminalTrim: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val terminal = cliJobs.values
            .filter { it.status in setOf("completed", "completed_result_too_large", "failed", "cancelled", "cancelled_may_have_applied", "completed_after_cancel_request") }
            .sortedBy { it.updatedAt }

        terminal.forEach { job ->
            if (now - job.updatedAt >= CLI_JOB_RETENTION_MS) cliJobs.remove(job.id, job)
        }

        if (forceTerminalTrim && cliJobs.size >= MAX_CLI_JOBS) {
            terminal.forEach { job ->
                if (cliJobs.size < MAX_CLI_JOBS) return
                cliJobs.remove(job.id, job)
            }
        }
    }

    private fun callAsyncInternal(
        rawName: String,
        args: JSONObject,
        bypassAccess: Boolean,
        debugContext: RiftDebugContext?,
        rawReply: (JSONObject) -> Unit
    ): RiftAsyncHandle {
        val name = canonicalName(rawName)
        val method = methodFor(name)
        val hostSpan = debugHub.start(
            component = "tool.host",
            operation = name.ifBlank { "unknown" },
            parent = debugContext,
            attributes = mapOf("lane" to if (bypassAccess) "trusted-cli" else "mcp")
        )
        val terminal = AtomicBoolean(false)
        val reply: (JSONObject) -> Unit = { response ->
            if (terminal.compareAndSet(false, true)) {
                if (response.optBoolean("ok", false)) {
                    hostSpan.success()
                } else {
                    hostSpan.failure(response.optString("error", "Rift tool failed"))
                }
                rawReply(response)
            }
        }

        if (name == "rift_debug") {
            if (!bypassAccess && !allowRead()) {
                val error = "Rift MCP read access is disabled on this device. Enable it in Rift MCP settings."
                recordAudit(name, args, false, error)
                reply(JSONObject().put("ok", false).put("name", name).put("error", error))
                return RiftAsyncHandle.completed()
            }
            try {
                val value = debugHub.query(args)
                recordAudit(name, args, true, null)
                reply(JSONObject().put("ok", true).put("name", name).put("value", value))
            } catch (failure: Throwable) {
                val error = failure.message ?: "Invalid rift_debug request"
                recordAudit(name, args, false, error)
                reply(JSONObject().put("ok", false).put("name", name).put("error", error))
            }
            return RiftAsyncHandle.completed()
        }
        if (name == "rift_shell_exec") {
            val command = args.optString("command").trim()
            if (command.isBlank()) {
                recordAudit(name, args, false, "command required")
                reply(JSONObject().put("ok", false).put("error", "command required"))
                return RiftAsyncHandle.completed()
            }
            val shellCommandName = command.takeWhile { !it.isWhitespace() }.lowercase()
            if (shellCommandName == "batch") {
                val error = "DISABLED: RiftShell batch commands are disabled because they can hang the agent/runtime. Do not use batch or batch --dry-run; use individual commands or MCP file operations instead."
                recordAudit(name, args, false, error)
                reply(JSONObject().put("ok", false).put("error", error))
                return RiftAsyncHandle.completed()
            }
            if (!bypassAccess && !isAllowed(name, args)) {
                val error = when {
                    !allowRead() && !allowWrite() -> "Rift MCP shell access is disabled on this device. Enable read and write access in Rift MCP settings."
                    !allowRead() -> "Rift MCP shell access is disabled on this device. Enable read access in Rift MCP settings."
                    else -> "Rift MCP shell access is disabled on this device. Enable write access in Rift MCP settings."
                }
                recordAudit(name, args, false, error)
                reply(JSONObject().put("ok", false).put("error", error))
                return RiftAsyncHandle.completed()
            }
            val executor = shellExecutor
            if (executor == null) {
                val error = "RiftShell executor unavailable"
                recordAudit(name, args, false, error)
                reply(JSONObject().put("ok", false).put("error", error))
                return RiftAsyncHandle.completed()
            }
            val startedAt = SystemClock.elapsedRealtime()
            return executor.execute(command, args.optString("cwd", "/")) { result ->
                val ok = result.optBoolean("ok", false)
                val error = if (ok) null else result.optString("error", "RiftShell execution failed")
                recordAudit(name, args, ok, error, SystemClock.elapsedRealtime() - startedAt)
                if (ok) {
                    reply(JSONObject()
                        .put("ok", true)
                        .put("value", JSONObject()
                            .put("output", result.optString("output"))
                            .put("cwd", result.optString("cwd", "/"))
                            .put("result", result.opt("result") ?: JSONObject.NULL)))
                } else {
                    reply(JSONObject().put("ok", false).put("error", error ?: "RiftShell execution failed"))
                }
            }
        }
        if (method == null) {
            val error = "Unsupported Rift tool: $rawName"
            recordAudit(rawName.take(120), args, false, error)
            reply(JSONObject().put("ok", false).put("name", rawName).put("error", error))
            return RiftAsyncHandle.completed()
        }

        val normalizedArgs = try {
            normalizeToolArgs(name, args)
        } catch (error: Throwable) {
            val message = error.message ?: "Invalid Rift tool arguments"
            recordAudit(name, args, false, message)
            reply(JSONObject().put("ok", false).put("name", name).put("error", message))
            return RiftAsyncHandle.completed()
        }

        if (name == "rift_workspace_exec") {
            val operationCount = normalizedArgs.optJSONArray("operations")?.length() ?: 0
            if (operationCount > 1) {
                val error = "DISABLED: Rift Code Mode multi-op/batch execution is disabled because it can hang the agent/runtime. Send exactly one operation per rift_workspace_exec call."
                recordAudit(name, normalizedArgs, false, error)
                reply(JSONObject().put("ok", false).put("name", name).put("error", error))
                return RiftAsyncHandle.completed()
            }
        }

        val mutatingRequest = requiresWrite(name, normalizedArgs)
        if (!bypassAccess && !isAllowed(name, normalizedArgs)) {
            val error = when {
                name == "rift_workspace_exec" && !allowRead() ->
                    "Rift MCP read access is disabled on this device. Enable it in Rift MCP settings."
                mutatingRequest ->
                    "Rift MCP write access is disabled on this device. Enable it in Rift MCP settings."
                else ->
                    "Rift MCP read access is disabled on this device. Enable it in Rift MCP settings."
            }
            recordAudit(name, normalizedArgs, false, error)
            reply(JSONObject().put("ok", false).put("name", name).put("error", error))
            return RiftAsyncHandle.completed()
        }

        val requestId = "tool-${System.currentTimeMillis()}-${System.nanoTime()}"
        val request = JSONObject()
            .put("id", requestId)
            .put("method", method)
            .put("args", normalizedArgs)

        return sandbox.handleAsync(request.toString()) { raw ->
            val response = runCatching { JSONObject(raw) }.getOrNull()
            if (response?.optBoolean("ok", false) == true) {
                recordAudit(name, normalizedArgs, true, null)
                val value = response.opt("value") ?: JSONObject.NULL
                if (name == "rift_info" && value is JSONObject) {
                    value.put("mcpManifest", manifest())
                    value.put("connectorRefresh", JSONObject()
                        .put("toolListStaticForProcess", true)
                        .put("refreshClientActionsWhenCountDiffers", true)
                        .put("note", "If a client exposes fewer tools than mcpManifest.count, refresh/rescan that client's MCP app actions; reconnecting the relay alone does not replace a cached client action catalog."))
                    value.put("riftMemoryN2M1", JSONObject(n2M1Diagnostic.toString()))
                    value.put("riftMemoryN2M2", JSONObject(n2M2Diagnostic.toString()))
                    value.put("riftMemoryN2M3", JSONObject(n2M3Diagnostic.toString()))
                }
                reply(
                    JSONObject()
                        .put("ok", true)
                        .put("name", name)
                        .put("value", value)
                )
            } else {
                val error = response?.optString("error")?.takeIf { it.isNotBlank() } ?: "Rift sandbox call failed"
                recordAudit(name, normalizedArgs, false, error)
                    reply(JSONObject().put("ok", false).put("name", name).put("error", error))
            }
        }
    }

    fun audit(): JSONArray {
        val raw = prefs.getString(PREF_AUDIT, "[]") ?: "[]"
        val parsed = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        if (parsed.length() <= MAX_AUDIT) return parsed
        return JSONArray().apply {
            for (index in parsed.length() - MAX_AUDIT until parsed.length()) put(parsed.opt(index))
        }
    }

    fun clearAudit(): Boolean {
        prefs.edit().putString(PREF_AUDIT, "[]").apply()
        return true
    }

    fun shutdown() {
        cancelAllCliJobs("RiftToolHost shutdown")
        cliJobs.clear()
        sandbox.shutdown()
    }

    private fun migrateLegacyState() {
        if (prefs.getBoolean(PREF_MIGRATED, false)) return
        val legacy = appContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val edit = prefs.edit()
        if (!prefs.contains(PREF_ALLOW_READ) && legacy.contains(PREF_ALLOW_READ)) {
            edit.putBoolean(PREF_ALLOW_READ, legacy.getBoolean(PREF_ALLOW_READ, true))
        }
        if (!prefs.contains(PREF_ALLOW_WRITE) && legacy.contains(PREF_ALLOW_WRITE)) {
            edit.putBoolean(PREF_ALLOW_WRITE, legacy.getBoolean(PREF_ALLOW_WRITE, false))
        }
        if (!prefs.contains(PREF_AUDIT) && legacy.contains(PREF_AUDIT)) {
            edit.putString(PREF_AUDIT, legacy.getString(PREF_AUDIT, "[]"))
        }
        edit.putBoolean(PREF_MIGRATED, true).apply()
        legacy.edit().clear().apply()
        runCatching { RiftSecretStore(appContext).remove("rift.bridge.pairingKey") }
    }

    private fun allowRead(): Boolean = prefs.getBoolean(PREF_ALLOW_READ, true)
    private fun allowWrite(): Boolean = prefs.getBoolean(PREF_ALLOW_WRITE, false)

    private fun normalizeToolArgs(name: String, args: JSONObject): JSONObject =
        if (name == "rift_workspace_exec") normalizeWorkspaceExecArgs(args) else JSONObject(args.toString())

    /**
     * Canonical Code Mode operations are flat objects: {"op":"stat","path":"workspace/..."}.
     * For resilience we also accept the unambiguous legacy/model shorthand
     * {"stat":{"path":"workspace/..."}} and normalize it before permission checks,
     * journaling, audit, or sandbox execution. This prevents shorthand writes from
     * bypassing write classification.
     */
    private fun normalizeWorkspaceExecArgs(args: JSONObject): JSONObject {
        val out = JSONObject(args.toString())
        val operations = out.optJSONArray("operations") ?: return out
        val normalized = JSONArray()
        for (index in 0 until operations.length()) {
            val operation = operations.optJSONObject(index)
            if (operation == null || operation.optString("op").isNotBlank()) {
                normalized.put(operations.opt(index))
                continue
            }
            val keys = mutableListOf<String>()
            val iterator = operation.keys()
            while (iterator.hasNext()) {
                val key = iterator.next()
                if (key != "id") keys += key
            }
            if (keys.size != 1) {
                normalized.put(operation)
                continue
            }
            val key = keys.single()
            val op = key.trim().lowercase()
            val nested = operation.optJSONObject(key)
            if (op !in WORKSPACE_OPS || nested == null) {
                normalized.put(operation)
                continue
            }
            val row = JSONObject(nested.toString()).put("op", op)
            if (operation.has("id") && !row.has("id")) row.put("id", operation.opt("id"))
            normalized.put(row)
        }
        out.put("operations", normalized)
        return out
    }

    private fun canonicalName(raw: String): String = when (raw.trim()) {
        "shell", "rift_shell_exec" -> "rift_shell_exec"
        "info", "rift_info" -> "rift_info"
        "stat", "rift_stat" -> "rift_stat"
        "hash", "rift_hash" -> "rift_hash"
        "list", "rift_list" -> "rift_list"
        "readText", "rift_read_text" -> "rift_read_text"
        "writeText", "rift_write_text" -> "rift_write_text"
        "mkdir", "rift_mkdir" -> "rift_mkdir"
        "remove", "rift_remove" -> "rift_remove"
        "move", "rift_move" -> "rift_move"
        "copy", "rift_copy" -> "rift_copy"
        "archive", "rift_archive" -> "rift_archive"
        "extract", "unzip", "rift_extract" -> "rift_extract"
        "workspaceExec", "rift_workspace_exec" -> "rift_workspace_exec"
        "audit", "rift_audit" -> "rift_audit"
        "scan", "rift_scan" -> "rift_scan"
        "projectExport", "rift_project_export" -> "rift_project_export"
        "workspaceDiff", "rift_workspace_diff" -> "rift_workspace_diff"
        "debug", "rift_debug" -> "rift_debug"
        else -> raw.trim()
    }

    private fun methodFor(name: String): String? = when (name) {
        "rift_shell_exec" -> "shell.exec"
        "rift_info" -> "sandbox.info"
        "rift_stat" -> "fs.stat"
        "rift_hash" -> "fs.hash"
        "rift_list" -> "fs.list"
        "rift_read_text" -> "fs.readText"
        "rift_write_text" -> "fs.writeText"
        "rift_mkdir" -> "fs.mkdir"
        "rift_remove" -> "fs.remove"
        "rift_move" -> "fs.move"
        "rift_copy" -> "fs.copy"
        "rift_archive" -> "fs.archive"
        "rift_extract" -> "fs.extract"
        "rift_workspace_exec" -> "workspace.exec"
        "rift_audit" -> "workspace.audit"
        "rift_scan" -> "workspace.scan"
        "rift_project_export" -> "workspace.exportProject"
        "rift_workspace_diff" -> "workspace.diff"
        else -> null
    }

    private fun isWriteTool(name: String): Boolean = name in setOf(
        "rift_write_text", "rift_mkdir", "rift_remove", "rift_move", "rift_copy", "rift_archive", "rift_extract"
    )

    private fun workspaceBatchMutates(args: JSONObject): Boolean {
        val operations = args.optJSONArray("operations") ?: return false
        for (index in 0 until operations.length()) {
            val op = operations.optJSONObject(index)?.optString("op")?.trim()?.lowercase().orEmpty()
            if (op in setOf("write", "replace", "patch", "patch_range", "apply_hunks", "mkdir", "remove", "move", "rename", "copy", "archive", "extract")) return true
        }
        return false
    }

    private fun requiresWrite(name: String, args: JSONObject): Boolean =
        isWriteTool(name) || name == "rift_shell_exec" || (name == "rift_workspace_exec" && workspaceBatchMutates(args))

    private fun isAllowed(name: String, args: JSONObject): Boolean = when {
        name == "rift_workspace_exec" -> allowRead() && (!requiresWrite(name, args) || allowWrite())
        name == "rift_shell_exec" -> allowRead() && allowWrite()
        isWriteTool(name) -> allowWrite()
        methodFor(name) != null -> allowRead()
        else -> false
    }

    @Synchronized
    private fun recordAudit(name: String, args: JSONObject, ok: Boolean, error: String?, durationMs: Long? = null) {
        val current = audit()
        val next = JSONArray()
        val start = (current.length() - (MAX_AUDIT - 1)).coerceAtLeast(0)
        for (index in start until current.length()) next.put(current.opt(index))
        next.put(
            JSONObject()
                .put("at", System.currentTimeMillis())
                .put("tool", name)
                .put("target", auditTarget(name, args))
                .put("durationMs", durationMs ?: JSONObject.NULL)
                .put("ok", ok)
                .put("error", error ?: JSONObject.NULL)
        )
        prefs.edit().putString(PREF_AUDIT, next.toString()).apply()
    }

    private fun auditTarget(name: String, args: JSONObject): String = when (canonicalName(name)) {
        "rift_move", "rift_copy", "rift_archive", "rift_extract" -> "${args.optString("from")} -> ${args.optString("to")}".take(300)
        "rift_workspace_exec" -> "workspace operation · ${args.optJSONArray("operations")?.length() ?: 0} op"
        "rift_workspace_diff" -> args.optString("path").ifBlank { "workspace" }.take(300)
        "rift_info" -> "sandbox"
        "rift_debug" -> args.optString("action", "status").trim().lowercase().ifBlank { "status" }
        "rift_shell_exec" -> args.optString("command").trim().takeWhile { !it.isWhitespace() }
            .take(48).replace(Regex("[^A-Za-z0-9_-]"), "?") + " [arguments omitted]"
        else -> args.optString("path").take(300)
    }

    private fun tool(name: String, description: String, inputSchema: JSONObject): JSONObject = JSONObject()
        .put("name", name)
        .put("description", description)
        .put("inputSchema", inputSchema)

    private fun workspaceOperationSchema(): JSONObject = JSONObject()
        .put("type", "object")
        .put("description", "Flat Rift Code Mode operation object. Example: {\"op\":\"stat\",\"path\":\"workspace/project\"}.")
        .put("properties", JSONObject()
            .put("op", JSONObject()
                .put("type", "string")
                .put("enum", JSONArray(WORKSPACE_OPS.sorted())))
            .put("id", stringProperty("Optional caller label for this operation result."))
            .put("path", stringProperty("Workspace path used by path-based operations."))
            .put("query", stringProperty("Search/symbol query where applicable."))
            .put("symbol", stringProperty("Identifier for reference/symbol reads."))
            .put("kind", stringProperty("Optional symbol kind filter."))
            .put("startLine", JSONObject().put("type", "integer"))
            .put("endLine", JSONObject().put("type", "integer"))
            .put("line", JSONObject().put("type", "integer"))
            .put("limit", JSONObject().put("type", "integer"))
            .put("maxChars", JSONObject().put("type", "integer"))
            .put("maxMatches", JSONObject().put("type", "integer"))
            .put("recursive", booleanProperty("Recursively list descendants."))
            .put("caseSensitive", booleanProperty("Use case-sensitive text matching."))
            .put("text", stringProperty("Replacement or complete file text."))
            .put("find", stringProperty("Literal text to find."))
            .put("replace", stringProperty("Replacement text."))
            .put("all", booleanProperty("Replace all matching occurrences."))
            .put("expectedCount", JSONObject().put("type", "integer"))
            .put("expectedHash", stringProperty("Expected full-file SHA-256 guard."))
            .put("expectedText", stringProperty("Expected exact selected text guard."))
            .put("expectedRangeHash", stringProperty("Expected selected-range SHA-256 guard."))
            .put("edits", JSONObject().put("type", "array"))
            .put("hunks", JSONObject().put("type", "array"))
            .put("from", stringProperty("Source workspace path."))
            .put("to", stringProperty("Destination workspace path."))
            .put("overwrite", booleanProperty("Replace an existing destination when true.")))
        .put("required", JSONArray(listOf("op")))
        .put("additionalProperties", true)

    private fun objectSchema(properties: JSONObject = JSONObject(), required: List<String> = emptyList()): JSONObject {
        val schema = JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("additionalProperties", false)
        if (required.isNotEmpty()) schema.put("required", JSONArray(required))
        return schema
    }

    private fun stringProperty(description: String): JSONObject = JSONObject()
        .put("type", "string")
        .put("description", description)

    private fun booleanProperty(description: String): JSONObject = JSONObject()
        .put("type", "boolean")
        .put("description", description)
}
