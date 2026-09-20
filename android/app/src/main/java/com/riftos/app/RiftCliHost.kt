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

    private val nativeLoadFailure: Throwable? =
        runCatching { System.loadLibrary("riftcli") }.exceptionOrNull()

    private external fun nativeExecute(args: Array<String>, cwd: String): String

    fun executeShell(args: List<String>, cwd: String): CommandResult {
        nativeLoadFailure?.let { failure ->
            return hostFailure("RiftCLI native library load failed", failure)
        }

        return try {
            val raw = nativeExecute(args.toTypedArray(), cwd)
            val envelope = JSONObject(raw)
            val output = envelope.optString("output")
            val result = envelope.optJSONObject("result")
                ?: return hostFailure("RiftCLI native envelope is missing result")
            CommandResult(output, result)
        } catch (failure: Throwable) {
            hostFailure("RiftCLI native transport failed", failure)
        }
    }

    private fun hostFailure(message: String, failure: Throwable? = null): CommandResult {
        val detail = failure?.message?.take(512)
        val output = if (detail.isNullOrBlank()) message else "$message: $detail"
        return CommandResult(
            output,
            JSONObject()
                .put("schema", "rift.cli-host-error/1")
                .put("ok", false)
                .put("error", message)
                .put("detail", detail ?: JSONObject.NULL)
                .put("authorityState", "unknown")
        )
    }
}
