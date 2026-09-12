package com.riftos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Canonical device-side capability registry for the local Rift MCP server. */
class RiftToolHost(context: Context, private val aiJournal: RiftAiJournal) {
    companion object {
        private const val PREFS = "rift-mcp-tools"
        private const val LEGACY_PREFS = "rift-bridge"
        private const val PREF_ALLOW_READ = "allowRead"
        private const val PREF_ALLOW_WRITE = "allowWrite"
        private const val PREF_AUDIT = "audit"
        private const val PREF_MIGRATED = "legacyStateMigrated"
        private const val MAX_AUDIT = 100
        private val WORKSPACE_OPS = setOf("project", "snapshot", "stat", "list", "search", "symbols", "references", "read", "read_range", "read_symbol", "write", "replace", "patch", "patch_range", "apply_hunks", "mkdir", "remove", "move", "rename", "copy", "archive")
        const val SCOPE = "riftfs/workspace"
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val sandbox: RiftToolSandbox

    init {
        migrateLegacyState()
        sandbox = RiftToolSandbox(appContext)
    }

    fun access(): JSONObject = JSONObject()
        .put("sandboxRead", allowRead())
        .put("sandboxWrite", allowWrite())
        .put("scope", SCOPE)
        .put("workspaceScope", "riftfs/workspace")
        .put("workspaceOnly", true)
        .put("localOnly", true)
        .put("codeMode", "rift-code-mode-v1")
        .put("projectIntelligence", "v1")
        .put("readTools", JSONArray(listOf("rift_info", "rift_stat", "rift_list", "rift_read_text", "rift_audit", "rift_scan", "rift_project_export", "rift_workspace_exec")))
        .put("writeTools", JSONArray(listOf("rift_write_text", "rift_mkdir", "rift_remove", "rift_move", "rift_copy")))
        .put("conditionalWriteTools", JSONArray(listOf("rift_workspace_exec")))

    fun setAccess(read: Boolean, write: Boolean): JSONObject {
        prefs.edit()
            .putBoolean(PREF_ALLOW_READ, read)
            .putBoolean(PREF_ALLOW_WRITE, write)
            .apply()
        return access()
    }

    fun tools(): JSONArray = JSONArray()
        .put(tool("rift_info", "Inspect the local Rift MCP workspace sandbox and storage limits.", objectSchema()))
        .put(tool(
            "rift_stat",
            "Get metadata for one file or directory under workspace/.",
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
                    .put("maxBytes", JSONObject().put("type", "integer").put("description", "Target response size in bytes; clamped to 64 KiB..700 KiB."))
            )
        ))
        .put(tool(
            "rift_workspace_exec",
            "Rift Code Mode + Project Intelligence v1: execute many workspace operations locally in one model-visible call. Supports snapshots, incremental symbol search, reference lookup, surgical symbol/range reads, exact/range/hunk patches, dry-run validation, and transactional multi-file edits under workspace/. Read permission is always required; write permission is required only when the batch mutates files.",
            objectSchema(
                JSONObject()
                    .put(
                        "operations",
                        JSONObject()
                            .put("type", "array")
                            .put("minItems", 1)
                            .put("maxItems", 192)
                            .put("description", "Ordered local workspace operations. Canonical form is flat JSON: {\"op\":\"stat\",\"path\":\"workspace/project\"}. Do not nest the operation name.")
                            .put("items", workspaceOperationSchema())
                    )
                    .put("finish", booleanProperty("Set true only when this mutating batch is intended to finish the task. RiftBrowser still returns the confirmed result to ChatGPT before completing the session."))
                    .put("dryRun", booleanProperty("Execute and validate read/content-edit operations transactionally, then restore mutations instead of committing. Structural mkdir/remove/move/copy operations are rejected in dry-run mode."))
                    .put("expectedSnapshot", stringProperty("Optional project/workspace snapshot id. Reject the batch if that snapshot scope changed."))
                    .put("expectedExportSnapshot", stringProperty("Optional snapshotId from rift_project_export. Reject the entire batch if exported source changed after the audit."))
                    .put("snapshotPath", stringProperty("Optional workspace path used for expectedSnapshot/returnSnapshot. Defaults to workspace/."))
                    .put("returnSnapshot", booleanProperty("Return a fresh scoped snapshot after the batch. Disabled by default to avoid rescanning large projects.")),
                listOf("operations")
            )
        ))

    fun callAsync(rawName: String, args: JSONObject, aiSessionId: String? = null, reply: (JSONObject) -> Unit) {
        val name = canonicalName(rawName)
        val method = methodFor(name)
        if (method == null) {
            val error = "Unsupported Rift tool: $rawName"
            recordAudit(rawName.take(120), args, false, error)
            aiJournal.recordTool(aiSessionId, rawName.take(120), args, "finish", false, error)
            reply(JSONObject().put("ok", false).put("name", rawName).put("error", error))
            return
        }

        val normalizedArgs = try {
            normalizeToolArgs(name, args)
        } catch (error: Throwable) {
            val message = error.message ?: "Invalid Rift tool arguments"
            recordAudit(name, args, false, message)
            aiJournal.recordTool(aiSessionId, name, args, "finish", false, message)
            reply(JSONObject().put("ok", false).put("name", name).put("error", message))
            return
        }

        val mutatingRequest = requiresWrite(name, normalizedArgs)
        if (!isAllowed(name, normalizedArgs)) {
            val error = when {
                name == "rift_workspace_exec" && !allowRead() ->
                    "Rift MCP read access is disabled on this device. Enable it in Rift MCP settings."
                mutatingRequest ->
                    "Rift MCP write access is disabled on this device. Enable it in Rift MCP settings."
                else ->
                    "Rift MCP read access is disabled on this device. Enable it in Rift MCP settings."
            }
            recordAudit(name, normalizedArgs, false, error)
            aiJournal.recordTool(aiSessionId, name, normalizedArgs, "finish", false, error)
            reply(JSONObject().put("ok", false).put("name", name).put("error", error))
            return
        }

        if (mutatingRequest) {
            try {
                aiJournal.captureForTool(aiSessionId, name, normalizedArgs)
            } catch (error: Throwable) {
                val message = error.message ?: "Rift AI rollback snapshot failed"
                recordAudit(name, normalizedArgs, false, message)
                aiJournal.recordTool(aiSessionId, name, normalizedArgs, "finish", false, message)
                reply(JSONObject().put("ok", false).put("name", name).put("error", message))
                return
            }
        }
        aiJournal.recordTool(aiSessionId, name, normalizedArgs, "start")

        val requestId = "tool-${System.currentTimeMillis()}-${System.nanoTime()}"
        val request = JSONObject()
            .put("id", requestId)
            .put("method", method)
            .put("args", normalizedArgs)

        sandbox.handleAsync(request.toString()) { raw ->
            val response = runCatching { JSONObject(raw) }.getOrNull()
            if (response?.optBoolean("ok", false) == true) {
                recordAudit(name, normalizedArgs, true, null)
                aiJournal.recordTool(aiSessionId, name, normalizedArgs, "finish", true, null)
                reply(
                    JSONObject()
                        .put("ok", true)
                        .put("name", name)
                        .put("value", response.opt("value") ?: JSONObject.NULL)
                )
            } else {
                val error = response?.optString("error")?.takeIf { it.isNotBlank() } ?: "Rift sandbox call failed"
                recordAudit(name, normalizedArgs, false, error)
                aiJournal.recordTool(aiSessionId, name, normalizedArgs, "finish", false, error)
                reply(JSONObject().put("ok", false).put("name", name).put("error", error))
            }
        }
    }

    fun audit(): JSONArray {
        val raw = prefs.getString(PREF_AUDIT, "[]") ?: "[]"
        return runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    }

    fun clearAudit(): Boolean {
        prefs.edit().putString(PREF_AUDIT, "[]").apply()
        return true
    }

    fun shutdown() {
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
        "info", "rift_info" -> "rift_info"
        "stat", "rift_stat" -> "rift_stat"
        "list", "rift_list" -> "rift_list"
        "readText", "rift_read_text" -> "rift_read_text"
        "writeText", "rift_write_text" -> "rift_write_text"
        "mkdir", "rift_mkdir" -> "rift_mkdir"
        "remove", "rift_remove" -> "rift_remove"
        "move", "rift_move" -> "rift_move"
        "copy", "rift_copy" -> "rift_copy"
        "workspaceExec", "rift_workspace_exec" -> "rift_workspace_exec"
        "audit", "rift_audit" -> "rift_audit"
        "scan", "rift_scan" -> "rift_scan"
        "projectExport", "rift_project_export" -> "rift_project_export"
        else -> raw.trim()
    }

    private fun methodFor(name: String): String? = when (name) {
        "rift_info" -> "sandbox.info"
        "rift_stat" -> "fs.stat"
        "rift_list" -> "fs.list"
        "rift_read_text" -> "fs.readText"
        "rift_write_text" -> "fs.writeText"
        "rift_mkdir" -> "fs.mkdir"
        "rift_remove" -> "fs.remove"
        "rift_move" -> "fs.move"
        "rift_copy" -> "fs.copy"
        "rift_workspace_exec" -> "workspace.exec"
        "rift_audit" -> "workspace.audit"
        "rift_scan" -> "workspace.scan"
        "rift_project_export" -> "workspace.exportProject"
        else -> null
    }

    private fun isWriteTool(name: String): Boolean = name in setOf(
        "rift_write_text", "rift_mkdir", "rift_remove", "rift_move", "rift_copy"
    )

    private fun workspaceBatchMutates(args: JSONObject): Boolean {
        val operations = args.optJSONArray("operations") ?: return false
        for (index in 0 until operations.length()) {
            val op = operations.optJSONObject(index)?.optString("op")?.trim()?.lowercase().orEmpty()
            if (op in setOf("write", "replace", "patch", "patch_range", "apply_hunks", "mkdir", "remove", "move", "rename", "copy", "archive")) return true
        }
        return false
    }

    private fun requiresWrite(name: String, args: JSONObject): Boolean =
        isWriteTool(name) || (name == "rift_workspace_exec" && workspaceBatchMutates(args))

    private fun isAllowed(name: String, args: JSONObject): Boolean = when {
        name == "rift_workspace_exec" -> allowRead() && (!requiresWrite(name, args) || allowWrite())
        isWriteTool(name) -> allowWrite()
        methodFor(name) != null -> allowRead()
        else -> false
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

    private fun auditTarget(name: String, args: JSONObject): String = when (canonicalName(name)) {
        "rift_move", "rift_copy" -> "${args.optString("from")} -> ${args.optString("to")}".take(300)
        "rift_workspace_exec" -> "workspace batch · ${args.optJSONArray("operations")?.length() ?: 0} ops"
        "rift_info" -> "sandbox"
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
