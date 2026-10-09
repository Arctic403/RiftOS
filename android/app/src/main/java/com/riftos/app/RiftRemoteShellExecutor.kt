package com.riftos.app

import org.json.JSONObject

/**
 * C1.3-D shell-side terminal adapter. The command runs only in the Core
 * process through same-UID, exact-process-verified bounded Binder IPC.
 */
class RiftRemoteShellExecutor(private val ipc: RiftShellCoreClient) : RiftShellExecutor {
    override fun execute(
        command: String, cwd: String?, requestId: String?, reply: (JSONObject) -> Unit
    ) {
        Thread({
            val output = runCatching { ipc.execute(command, cwd) }
                .getOrElse { JSONObject().put("ok", false)
                    .put("error", it.message ?: it.javaClass.simpleName) }
            reply(output)
        }, "rift-shell-remote-terminal").apply { isDaemon = true }.start()
    }
    override fun submit(command: String, cwd: String?, requestId: String?): JSONObject =
        throw UnsupportedOperationException("Remote terminal submits through execute only")
    override fun jobStatus(jobId: String): JSONObject =
        throw UnsupportedOperationException("Core jobs are accessed from Core shell")
    override fun jobResult(jobId: String): JSONObject =
        throw UnsupportedOperationException("Core jobs are accessed from Core shell")
    override fun jobCancel(jobId: String): JSONObject =
        throw UnsupportedOperationException("Core jobs are accessed from Core shell")
    override fun jobList(limit: Int): JSONObject =
        throw UnsupportedOperationException("Core jobs are accessed from Core shell")
    override fun close() = Unit
}
