package com.riftos.app

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * C1.3-D bounded window-state and command mailbox; the desktop, windows and
 * window manager exist ONLY in the :riftShell process. Core retains a
 * read-only last-observed state for native MCP ps/kill/open compatibility.
 *
 * This is NOT an automatic shell restart or replay protocol (C1.3-E).
 */
object RiftCoreShellWindowBridge {
    const val SCHEMA = "riftos.shell.desktop-ipc/1"
    private const val MAX_COMMANDS = 32
    private const val MAX_STATE_BYTES = 48 * 1024
    private const val MAX_AGE_MS = 10_000L
    private const val MAX_COMMAND_AGE_MS = 30_000L
    private data class Command(
        val method: String, val argument: String, val issued: Long
    )
    private val commands = ArrayDeque<Command>()
    private var ownerPid = -1
    private var observedAt = 0L
    private var state = JSONObject()
        .put("windows", JSONArray())
        .put("activeId", JSONObject.NULL)

    @Synchronized
    fun report(callingPid: Int, json: String): JSONObject {
        require(callingPid > 0) { "Invalid shell PID" }
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_STATE_BYTES) {
            "Remote shell desktop state exceeds bound"
        }
        val value = JSONObject(json)
        require(value.optBoolean("native", false)) { "Shell state must be native" }
        val windows = value.getJSONArray("windows")
        require(windows.length() <= 64) { "Too many remote shell windows" }
        for (index in 0 until windows.length()) {
            val id = windows.getJSONObject(index).getString("id")
            require(id.length in 1..128) { "Invalid reported shell window id" }
        }
        // A new authenticated production shell supersedes stale state. Do not
        // replay old-process commands; auto reconstruction is C1.3-E.
        if (callingPid != ownerPid) {
            commands.clear()
            ownerPid = callingPid
        }
        state = JSONObject(value.toString())
        observedAt = SystemClock.elapsedRealtime()
        return status()
    }

    @Synchronized
    fun status(): JSONObject {
        val alive = ownerPid > 0 &&
            SystemClock.elapsedRealtime() - observedAt <= MAX_AGE_MS
        return JSONObject().put("schema", SCHEMA)
            .put("shellPid", if (alive) ownerPid else JSONObject.NULL)
            .put("online", alive)
            .put("windows", if (alive) state.getJSONArray("windows") else JSONArray())
            .put("state", if (alive) JSONObject(state.toString()) else JSONObject())
    }

    @Synchronized
    fun offer(method: String, argument: String): Boolean {
        require(method in setOf("open", "close", "browser")) {
            "Shell command method invalid"
        }
        require(argument.isNotBlank() && argument.length <= 2048) {
            "Shell command argument invalid"
        }
        if (!status().getBoolean("online")) return false
        while (commands.isNotEmpty() &&
            SystemClock.elapsedRealtime() - commands.first().issued > MAX_COMMAND_AGE_MS) {
            commands.removeFirst()
        }
        if (commands.size >= MAX_COMMANDS) return false
        commands.addLast(Command(method, argument, SystemClock.elapsedRealtime()))
        return true
    }

    @Synchronized
    fun drainCommands(callingPid: Int): JSONArray {
        if (!status().getBoolean("online") || ownerPid != callingPid) return JSONArray()
        val result = JSONArray()
        while (commands.isNotEmpty() && result.length() < 8) {
            val item = commands.removeFirst()
            if (SystemClock.elapsedRealtime() - item.issued <= MAX_COMMAND_AGE_MS) {
                result.put(JSONObject()
                    .put("method", item.method)
                    .put("argument", item.argument))
            }
        }
        return result
    }
}
