package com.riftos.app

import org.json.JSONObject

/** Process-owned RiftShell execution contract. No renderer or UI dependency is permitted here. */
interface RiftShellExecutor {
    fun execute(command: String, cwd: String?, reply: (JSONObject) -> Unit)
    fun close()
}
