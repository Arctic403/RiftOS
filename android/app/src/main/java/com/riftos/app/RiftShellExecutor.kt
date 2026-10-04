package com.riftos.app

import org.json.JSONObject

/** Process-owned RiftShell execution contract. No renderer, UI, or RiftCLI dependency is permitted here. */
interface RiftShellExecutor {
    fun execute(
        command: String,
        cwd: String?,
        requestId: String? = null,
        reply: (JSONObject) -> Unit
    )

    fun submit(
        command: String,
        cwd: String?,
        requestId: String? = null
    ): JSONObject

    fun jobStatus(jobId: String): JSONObject

    fun jobResult(jobId: String): JSONObject

    fun jobCancel(jobId: String): JSONObject

    fun jobList(limit: Int = 20): JSONObject

    fun close()
}
