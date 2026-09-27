package com.riftos.app

import org.json.JSONObject

/** Process-owned RiftShell execution contract. No renderer or UI dependency is permitted here. */
interface RiftShellExecutor {
    fun execute(command: String, cwd: String?, requestId: String? = null, reply: (JSONObject) -> Unit)
    fun close()
}
