package com.riftos.app

import org.json.JSONObject

/**
 * Small process-local view/session bus shared by Workspace Live and the MCP tool host.
 * File contents still come from RiftToolSandbox; this only describes what the human is viewing.
 */
object RiftWorkspaceLiveState {
    @Volatile private var state: JSONObject = emptyState()

    @Synchronized
    fun update(input: JSONObject): JSONObject {
        val selection = input.optJSONObject("selection") ?: JSONObject()
        val visible = input.optJSONObject("visible") ?: JSONObject()
        val cursor = input.optJSONObject("cursor") ?: JSONObject()
        state = JSONObject()
            .put("connected", true)
            .put("activeFile", clean(input.optString("activeFile"), 1024))
            .put("cwd", clean(input.optString("cwd"), 1024))
            .put("revision", clean(input.optString("revision"), 160))
            .put("dirty", input.optBoolean("dirty", false))
            .put("conflict", input.optBoolean("conflict", false))
            .put("cursor", JSONObject()
                .put("offset", cursor.optInt("offset", 0).coerceAtLeast(0))
                .put("line", cursor.optInt("line", 1).coerceAtLeast(1)))
            .put("selection", JSONObject()
                .put("start", selection.optInt("start", 0).coerceAtLeast(0))
                .put("end", selection.optInt("end", 0).coerceAtLeast(0))
                .put("text", clean(selection.optString("text"), 12_000)))
            .put("visible", JSONObject()
                .put("startLine", visible.optInt("startLine", 1).coerceAtLeast(1))
                .put("endLine", visible.optInt("endLine", 1).coerceAtLeast(1))
                .put("excerpt", clean(visible.optString("excerpt"), 24_000)))
            .put("at", input.optLong("at", System.currentTimeMillis()))
        return snapshot()
    }

    @Synchronized
    fun clear(): JSONObject {
        state = emptyState()
        return snapshot()
    }

    @Synchronized
    fun snapshot(): JSONObject = JSONObject(state.toString())

    private fun emptyState(): JSONObject = JSONObject()
        .put("connected", false)
        .put("activeFile", "")
        .put("cwd", "")
        .put("revision", "")
        .put("dirty", false)
        .put("conflict", false)
        .put("cursor", JSONObject().put("offset", 0).put("line", 1))
        .put("selection", JSONObject().put("start", 0).put("end", 0).put("text", ""))
        .put("visible", JSONObject().put("startLine", 1).put("endLine", 1).put("excerpt", ""))
        .put("at", 0L)

    private fun clean(value: String?, maxChars: Int): String = value.orEmpty().take(maxChars)
}
