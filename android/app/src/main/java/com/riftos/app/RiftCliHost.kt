package com.riftos.app

import org.json.JSONObject

/**
 * Thin Android host for the native C++ RiftCLI core.
 *
 * This file must stay transport-only: no planner, memory, research, verification, model/API,
 * project-graph, or mutation policy belongs in Kotlin. Those capabilities are added to the
 * native RiftCLI core only after their own promotion gates.
 */
internal object RiftCliHost {
    data class CommandResult(
        val output: String,
        val result: JSONObject
    )

    init {
        System.loadLibrary("riftcli")
    }

    private external fun nativeExecute(args: Array<String>, cwd: String): String

    fun executeShell(args: List<String>, cwd: String): CommandResult {
        val envelope = JSONObject(nativeExecute(args.toTypedArray(), cwd))
        val output = envelope.getString("output")
        val result = envelope.getJSONObject("result")
        return CommandResult(output, result)
    }
}
