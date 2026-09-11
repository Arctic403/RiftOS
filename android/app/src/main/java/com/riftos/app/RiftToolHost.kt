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
        const val SCOPE = "riftfs/tool-sandbox"
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
        .put("transferScope", "riftfs/tool-sandbox")
        .put("localOnly", true)
        .put("codeMode", "rift-code-mode-v1")
        .put("readTools", JSONArray(listOf("rift_info", "rift_stat", "rift_list", "rift_read_text", "rift_workspace_exec")))
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
        .put(tool("rift_info", "Inspect the local Rift MCP sandbox and storage limits.", objectSchema()))
        .put(tool(
            "rift_stat",
            "Get metadata for one file or directory in the Rift MCP sandbox.",
            objectSchema(JSONObject().put("path", stringProperty("Sandbox-relative path.")), listOf("path"))
        ))
        .put(tool(
            "rift_list",
            "List files and directories in the Rift MCP sandbox.",
            objectSchema(
                JSONObject()
                    .put("path", stringProperty("Sandbox-relative directory path. Empty means sandbox root."))
                    .put("recursive", booleanProperty("Recursively include descendants."))
            )
        ))
        .put(tool(
            "rift_read_text",
            "Read a UTF-8 text file from the Rift MCP sandbox.",
            objectSchema(JSONObject().put("path", stringProperty("Sandbox-relative file path.")), listOf("path"))
        ))
        .put(tool(
            "rift_write_text",
            "Create or replace a UTF-8 text file in the Rift MCP sandbox. Local write permission must be enabled.",
            objectSchema(
                JSONObject()
                    .put("path", stringProperty("Sandbox-relative file path."))
                    .put("text", stringProperty("Complete UTF-8 file contents.")),
                listOf("path", "text")
            )
        ))
        .put(tool(
            "rift_mkdir",
            "Create a directory in the Rift MCP sandbox. Local write permission must be enabled.",
            objectSchema(JSONObject().put("path", stringProperty("Sandbox-relative directory path.")), listOf("path"))
        ))
        .put(tool(
            "rift_remove",
            "Remove a file or directory in the Rift MCP sandbox. Local write permission must be enabled.",
            objectSchema(JSONObject().put("path", stringProperty("Sandbox-relative path to remove.")), listOf("path"))
        ))
        .put(tool(
            "rift_move",
            "Move or rename a file or directory in the Rift MCP sandbox. Local write permission must be enabled.",
            objectSchema(
                JSONObject()
                    .put("from", stringProperty("Sandbox-relative source path."))
                    .put("to", stringProperty("Sandbox-relative destination path."))
                    .put("overwrite", booleanProperty("Replace an existing destination when true.")),
                listOf("from", "to")
            )
        ))
        .put(tool(
            "rift_copy",
            "Copy a file or directory in the Rift MCP sandbox. Local write permission must be enabled.",
            objectSchema(
                JSONObject()
                    .put("from", stringProperty("Sandbox-relative source path."))
                    .put("to", stringProperty("Sandbox-relative destination path."))
                    .put("overwrite", booleanProperty("Replace an existing destination when true.")),
                listOf("from", "to")
            )
        ))
        .put(tool(
            "rift_workspace_exec",
            "Rift Code Mode: execute many project operations locally in one model-visible call. Supports project, stat, list, search, read, write, replace, patch, mkdir, remove, move/rename, and copy under workspace/. The batch is transactional: if any operation fails, its mutations are rolled back. Read permission is always required; write permission is required only when the batch mutates files.",
            objectSchema(
                JSONObject()
                    .put(
                        "operations",
                        JSONObject()
                            .put("type", "array")
                            .put("minItems", 1)
                            .put("maxItems", 192)
                            .put("description", "Ordered local workspace operations. Each item has op plus the fields required by that operation.")
                            .put("items", JSONObject().put("type", "object"))
                    )
                    .put("finish", booleanProperty("Set true only when this successful mutating batch fully completes the Rift AI task. RiftBrowser may finish locally without a second ChatGPT continuation turn.")),
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

        val mutatingRequest = requiresWrite(name, args)
        if (!isAllowed(name, args)) {
            val error = when {
                name == "rift_workspace_exec" && !allowRead() ->
                    "Rift MCP read access is disabled on this device. Enable it in Rift MCP settings."
                mutatingRequest ->
                    "Rift MCP write access is disabled on this device. Enable it in Rift MCP settings."
                else ->
                    "Rift MCP read access is disabled on this device. Enable it in Rift MCP settings."
            }
            recordAudit(name, args, false, error)
            aiJournal.recordTool(aiSessionId, name, args, "finish", false, error)
            reply(JSONObject().put("ok", false).put("name", name).put("error", error))
            return
        }

        if (mutatingRequest) {
            try {
                aiJournal.captureForTool(aiSessionId, name, args)
            } catch (error: Throwable) {
                val message = error.message ?: "Rift AI rollback snapshot failed"
                recordAudit(name, args, false, message)
                aiJournal.recordTool(aiSessionId, name, args, "finish", false, message)
                reply(JSONObject().put("ok", false).put("name", name).put("error", message))
                return
            }
        }
        aiJournal.recordTool(aiSessionId, name, args, "start")

        val requestId = "tool-${System.currentTimeMillis()}-${System.nanoTime()}"
        val request = JSONObject()
            .put("id", requestId)
            .put("method", method)
            .put("args", args)

        sandbox.handleAsync(request.toString()) { raw ->
            val response = runCatching { JSONObject(raw) }.getOrNull()
            if (response?.optBoolean("ok", false) == true) {
                recordAudit(name, args, true, null)
                aiJournal.recordTool(aiSessionId, name, args, "finish", true, null)
                reply(
                    JSONObject()
                        .put("ok", true)
                        .put("name", name)
                        .put("value", response.opt("value") ?: JSONObject.NULL)
                )
            } else {
                val error = response?.optString("error")?.takeIf { it.isNotBlank() } ?: "Rift sandbox call failed"
                recordAudit(name, args, false, error)
                aiJournal.recordTool(aiSessionId, name, args, "finish", false, error)
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
        else -> null
    }

    private fun isWriteTool(name: String): Boolean = name in setOf(
        "rift_write_text", "rift_mkdir", "rift_remove", "rift_move", "rift_copy"
    )

    private fun workspaceBatchMutates(args: JSONObject): Boolean {
        val operations = args.optJSONArray("operations") ?: return false
        for (index in 0 until operations.length()) {
            val op = operations.optJSONObject(index)?.optString("op")?.trim()?.lowercase().orEmpty()
            if (op in setOf("write", "replace", "patch", "mkdir", "remove", "move", "rename", "copy")) return true
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
