package com.riftos.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit local Binder client for the debug-only Vortex3D development bridge. */
class RiftVortexBridgeClient(context: Context) {
    companion object {
        private const val VORTEX_PACKAGE = "com.vortex3d.app"
        private const val VORTEX_SERVICE = "com.vortex3d.app.VortexDevBridgeService"
        private const val DESCRIPTOR = "com.vortex3d.app.devbridge.v1"
        private const val TRANSACTION_EXECUTE = IBinder.FIRST_CALL_TRANSACTION
        private const val BIND_TIMEOUT_MS = 8_000L
        private const val REMOTE_CHUNK_BYTES = 192 * 1024
        private const val MAX_IMAGE_BYTES = 512 * 1024
        private const val MAX_PULL_BYTES = 128L * 1024L * 1024L
        private const val SESSION_READY_TIMEOUT_MS = 12_000L
        private const val SESSION_TIMEOUT_MS = 85_000L
        private const val SESSION_POLL_MS = 150L
        private const val SESSION_REASSERT_MS = 1_000L
    }

    private val appContext = context.applicationContext
    private val lock = Any()
    private val bridgeOutputRoot = File(appContext.filesDir, "riftfs/workspace/.vortex-bridge").apply { mkdirs() }
    @Volatile private var remote: IBinder? = null
    @Volatile private var bound = false
    @Volatile private var bindLatch: CountDownLatch? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            remote = service
            bindLatch?.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            remote = null
        }

        override fun onBindingDied(name: ComponentName) {
            remote = null
            bindLatch?.countDown()
        }

        override fun onNullBinding(name: ComponentName) {
            remote = null
            bindLatch?.countDown()
        }
    }

    fun execute(rawArgs: JSONObject): JSONObject {
        val args = JSONObject(rawArgs.toString())
        val op = args.optString("op").trim().lowercase()
        require(op.isNotBlank()) { "vortex.bridge op is required" }
        if (op == "pull_artifact") return pullArtifact(args)
        val includeImage = args.optBoolean("includeImage", false)
        args.remove("includeImage")
        val response = transactWithReconnect(args)
        if (includeImage && response.optBoolean("ok", false)) attachImageIfPresent(response)
        return response
    }

    /**
     * One-call foreground session used by RiftShell test-wait/script-wait.
     * Vortex is activated before the job starts and periodically re-asserted while the native
     * dispatcher waits, so ChatGPT does not regain foreground between queue and poll calls.
     */
    fun executeSession(rawArgs: JSONObject): JSONObject {
        val args = JSONObject(rawArgs.toString())
        val kind = args.optString("kind").trim().lowercase()
        require(kind == "validation" || kind == "script") { "vortex.session kind must be validation or script" }
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + SESSION_TIMEOUT_MS

        RiftVortexLocalAgent.ensureActiveForSession(appContext)
        val ready = awaitSessionReady(deadline)
        val processSession = ready.optJSONObject("value")?.optString("process_session").orEmpty()

        val request = JSONObject(args.toString())
        request.remove("kind")
        request.remove("includeImage")
        request.put("op", if (kind == "validation") "validate" else "script")
        val queued = execute(request)
        if (!queued.optBoolean("ok", false)) return annotateSession(queued, kind, "", processSession, startedAt, false)
        val jobId = queued.optJSONObject("value")?.optString("id").orEmpty()
        require(jobId.isNotBlank()) { "Vortex session did not return a job id" }

        var last = queued
        var lastReassert = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() < deadline) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastReassert >= SESSION_REASSERT_MS) {
                RiftVortexLocalAgent.ensureActiveForSession(appContext)
                lastReassert = now
            }
            val poll = execute(JSONObject().put("op", "job").put("id", jobId))
            last = poll
            if (!poll.optBoolean("ok", false)) return annotateSession(poll, kind, jobId, processSession, startedAt, true)
            val state = poll.optJSONObject("value")?.optString("state").orEmpty()
            if (state != "queued" && state != "running") {
                val terminal = if (state == "complete") {
                    execute(JSONObject().put("op", "job").put("id", jobId).put("includeImage", true))
                } else poll
                return annotateSession(terminal, kind, jobId, processSession, startedAt, true)
            }
            Thread.sleep(SESSION_POLL_MS)
        }

        return annotateSession(last, kind, jobId, processSession, startedAt, false)
            .put("ok", false)
            .put("op", "session")
            .put("error", "Vortex foreground session timed out after ${SESSION_TIMEOUT_MS / 1000}s; job remains $jobId")
    }

    private fun awaitSessionReady(deadline: Long): JSONObject {
        val readyDeadline = minOf(deadline, SystemClock.elapsedRealtime() + SESSION_READY_TIMEOUT_MS)
        var last: JSONObject? = null
        while (SystemClock.elapsedRealtime() < readyDeadline) {
            val status = transactWithReconnect(JSONObject().put("op", "status"))
            last = status
            val value = status.optJSONObject("value")
            if (status.optBoolean("ok", false) && value?.optBoolean("activity_alive", false) == true && value.optBoolean("renderer_ready", false)) {
                return status
            }
            Thread.sleep(SESSION_POLL_MS)
        }
        val value = last?.optJSONObject("value")
        throw IllegalStateException(
            "Vortex foreground session could not obtain a live renderer; activity_alive=${value?.optBoolean("activity_alive", false) ?: false}, renderer_ready=${value?.optBoolean("renderer_ready", false) ?: false}"
        )
    }

    private fun annotateSession(response: JSONObject, kind: String, jobId: String, processSession: String, startedAt: Long, terminal: Boolean): JSONObject {
        val value = response.optJSONObject("value")
        val state = value?.optString("state").orEmpty()
        response.put("rift_session", JSONObject()
            .put("mode", "foreground_wait")
            .put("scope", "com.vortex3d.app")
            .put("kind", kind)
            .put("job_id", jobId)
            .put("process_session", processSession)
            .put("terminal", terminal)
            .put("state", state)
            .put("elapsed_ms", SystemClock.elapsedRealtime() - startedAt))
        return response
    }

    fun close() {
        synchronized(lock) {
            remote = null
            bindLatch = null
            if (bound) runCatching { appContext.unbindService(connection) }
            bound = false
        }
    }

    private fun ensureRemote(): IBinder {
        remote?.takeIf { it.isBinderAlive }?.let { return it }
        val latch: CountDownLatch
        synchronized(lock) {
            remote?.takeIf { it.isBinderAlive }?.let { return it }
            if (bound) {
                runCatching { appContext.unbindService(connection) }
                bound = false
            }
            latch = CountDownLatch(1)
            bindLatch = latch
            val intent = Intent().setComponent(ComponentName(VORTEX_PACKAGE, VORTEX_SERVICE))
            bound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
            if (!bound) {
                bindLatch = null
                throw IllegalStateException("Vortex3D debug bridge is unavailable. Install the debug APK and open Vortex3D.")
            }
        }
        if (!latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            synchronized(lock) {
                bindLatch = null
                if (bound) runCatching { appContext.unbindService(connection) }
                bound = false
                remote = null
            }
            throw IllegalStateException("Timed out binding to Vortex3D debug bridge")
        }
        bindLatch = null
        return remote?.takeIf { it.isBinderAlive }
            ?: throw IllegalStateException("Vortex3D debug bridge returned no live Binder")
    }

    private fun transactWithReconnect(request: JSONObject): JSONObject {
        return try {
            transact(ensureRemote(), request)
        } catch (error: Throwable) {
            synchronized(lock) {
                remote = null
                if (bound) runCatching { appContext.unbindService(connection) }
                bound = false
            }
            transact(ensureRemote(), request)
        }
    }

    private fun transact(service: IBinder, request: JSONObject): JSONObject {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(request.toString())
            require(service.transact(TRANSACTION_EXECUTE, data, reply, 0)) { "Vortex3D bridge rejected Binder transaction" }
            reply.readException()
            val raw = reply.readString() ?: throw IllegalStateException("Vortex3D bridge returned an empty response")
            return JSONObject(raw)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun attachImageIfPresent(response: JSONObject) {
        val value = response.optJSONObject("value") ?: return
        val artifact = value.optJSONObject("preview")
            ?: value.optJSONObject("result")?.optJSONObject("preview")
            ?: return
        val size = artifact.optLong("size", -1L)
        if (size <= 0L || size > MAX_IMAGE_BYTES) return
        val id = artifact.optString("id").trim()
        if (id.isBlank()) return
        val bytes = readArtifactBytes(id, MAX_IMAGE_BYTES)
        val image = JSONObject()
            .put("mimeType", artifact.optString("mime", "image/jpeg"))
            .put("name", artifact.optString("name", "vortex-preview.jpg"))
            .put("bytes", bytes.size)
            .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
        response.put("_riftImage", image)
        value.optJSONObject("result")?.let { result ->
            if (result.has("report_artifact")) result.remove("report")
        }
    }

    private fun readArtifactBytes(id: String, maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        var offset = 0L
        while (true) {
            val chunk = transactWithReconnect(JSONObject()
                .put("op", "artifact_read")
                .put("id", id)
                .put("offset", offset)
                .put("maxBytes", REMOTE_CHUNK_BYTES))
            require(chunk.optBoolean("ok", false)) { chunk.optString("error", "Vortex artifact read failed") }
            val value = chunk.getJSONObject("value")
            val decoded = Base64.decode(value.optString("data"), Base64.DEFAULT)
            require(output.size() + decoded.size <= maxBytes) { "Vortex image exceeded the RiftOS MCP image limit" }
            output.write(decoded)
            offset = value.optLong("next_offset", offset + decoded.size)
            if (value.optBoolean("eof", false)) break
            require(decoded.isNotEmpty()) { "Vortex artifact read made no progress" }
        }
        return output.toByteArray()
    }

    private fun pullArtifact(args: JSONObject): JSONObject {
        val id = args.optString("id").trim()
        require(id.isNotBlank()) { "pull_artifact.id is required" }
        val requestedName = args.optString("name").trim()
        val fallback = id.substringAfterLast('/').ifBlank { "vortex-artifact.bin" }
        val name = safeName(requestedName.ifBlank { fallback })
        val root = bridgeOutputRoot.canonicalFile
        val destination = uniqueDestination(root, name)
        val committedName = destination.name
        val temporary = File(root, ".${committedName}.tmp-${UUID.randomUUID()}")
        var offset = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            FileOutputStream(temporary, false).use { output ->
                while (true) {
                    val chunk = transactWithReconnect(JSONObject()
                        .put("op", "artifact_read")
                        .put("id", id)
                        .put("offset", offset)
                        .put("maxBytes", REMOTE_CHUNK_BYTES))
                    require(chunk.optBoolean("ok", false)) { chunk.optString("error", "Vortex artifact read failed") }
                    val value = chunk.getJSONObject("value")
                    val total = value.optLong("size", -1L)
                    require(total >= 0L && total <= MAX_PULL_BYTES) { "Vortex artifact exceeds ${MAX_PULL_BYTES / 1048576} MiB pull limit" }
                    val decoded = Base64.decode(value.optString("data"), Base64.DEFAULT)
                    output.write(decoded)
                    digest.update(decoded)
                    offset = value.optLong("next_offset", offset + decoded.size)
                    require(offset <= MAX_PULL_BYTES) { "Vortex artifact exceeded pull limit" }
                    if (value.optBoolean("eof", false)) break
                    require(decoded.isNotEmpty()) { "Vortex artifact read made no progress" }
                }
            }
            if (!temporary.renameTo(destination)) throw IllegalStateException("Could not commit Vortex artifact: $committedName")
            return JSONObject()
                .put("ok", true)
                .put("op", "pull_artifact")
                .put("value", JSONObject()
                    .put("path", "workspace/.vortex-bridge/$committedName")
                    .put("size", destination.length())
                    .put("sha256", digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }))
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun uniqueDestination(root: File, requested: String): File {
        fun candidate(name: String): File = File(root, name).canonicalFile.also {
            require(it.parentFile == root) { "Vortex artifact destination escaped bridge output root" }
        }
        val direct = candidate(requested)
        if (!direct.exists()) return direct
        val dot = requested.lastIndexOf('.')
        val stem = if (dot > 0) requested.substring(0, dot) else requested
        val extension = if (dot > 0) requested.substring(dot) else ""
        for (index in 1..9999) {
            val next = candidate("$stem-$index$extension")
            if (!next.exists()) return next
        }
        throw IllegalStateException("Could not allocate a duplicate-safe Vortex artifact name")
    }

    private fun safeName(value: String): String {
        val cleaned = value.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.').take(120)
        return cleaned.ifBlank { "vortex-artifact.bin" }
    }
}
