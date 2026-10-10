package com.riftos.app

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Stable APK-owned Core startup/failure evidence. Read-only RAPP visibility;
 * only main-process bootstrap calls the writers. This is not a watchdog.
 *
 * Android may not expose native tombstones/LMK diagnostics. A failure record
 * reports whether actual stack/trace bytes were accessible, never invents them.
 */
internal object RiftCoreRecoveryDiagnostics {
    const val SCHEMA = "riftos.core.recovery-diagnostics/1"
    private const val MAX_RECORD = 24 * 1024
    private const val MAX_TRACE = 16 * 1024
    private const val MAX_STACK = 12 * 1024
    private val lock = Any()

    private fun file(app: Application): File {
        val root = RiftBootstrapComponentStore.root(app)
        require(!root.exists() || (root.isDirectory &&
            !java.nio.file.Files.isSymbolicLink(root.toPath()))) {
            "Core diagnostics directory invalid"
        }
        require(root.isDirectory || root.mkdirs()) { "Core diagnostics directory unavailable" }
        return File(root, "core-failure-last.json")
    }

    private fun read(app: Application): JSONObject? {
        val path = file(app)
        if (!path.exists() && !File(path.path + ".bak").exists()) return null
        require(!java.nio.file.Files.isSymbolicLink(path.toPath())) {
            "Core diagnostics record symlinked"
        }
        val stream = AtomicFile(path).openRead()
        val data = stream.use {
            val buffer = ByteArray(MAX_RECORD + 1)
            val count = it.read(buffer)
            require(count in 1..MAX_RECORD && it.read() == -1) {
                "Core diagnostics record too large"
            }
            buffer.copyOf(count)
        }
        return JSONObject(String(data, Charsets.UTF_8)).also {
            require(it.optString("schema") == SCHEMA) { "Core diagnostics schema invalid" }
        }
    }

    private fun write(app: Application, entry: JSONObject) {
        val path = file(app)
        require(!java.nio.file.Files.isSymbolicLink(path.toPath()) &&
            !java.nio.file.Files.isSymbolicLink(File(path.path + ".bak").toPath()) &&
            !java.nio.file.Files.isSymbolicLink(File(path.path + ".new").toPath())) {
            "Core diagnostics write path invalid"
        }
        val data = entry.toString().toByteArray(Charsets.UTF_8)
        require(data.size in 1..MAX_RECORD) { "Core diagnostics record size invalid" }
        val atomic = AtomicFile(path)
        val output = atomic.startWrite()
        try {
            output.write(data)
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    @Volatile private var crashHandlerInstalled = false

    /**
     * Records uncaught Java exceptions for the selected external Core and
     * delegates to Android's ORIGINAL crash handler. It does not swallow a
     * fatal crash or pretend the terminated Core can supervise itself.
     */
    @Synchronized
    fun installExternalCrashObserver(app: Application) {
        if (crashHandlerInstalled) return
        val previous = Thread.getDefaultUncaughtExceptionHandler() ?: return
        val observer = Thread.UncaughtExceptionHandler { thread, failure ->
            if (RiftHostCoreComponents.status().optBoolean("externalCoreEnabled", false)) {
                runCatching { recordCandidateFailure(app, "uncaught-" +
                    thread.name.take(50), failure) }
            }
            previous.uncaughtException(thread, failure)
        }
        Thread.setDefaultUncaughtExceptionHandler(observer)
        crashHandlerInstalled = true
    }

    /** Executed before Core candidate selection; never blocks normal boot. */
    fun recoverHistoricalExit(app: Application) {
        if (Build.VERSION.SDK_INT < 30) return
        synchronized(lock) {
            val events = runCatching {
                (app.getSystemService(Application.ACTIVITY_SERVICE) as ActivityManager)
                    .getHistoricalProcessExitReasons(app.packageName, 0, 8)
            }.getOrDefault(emptyList())
            val exit = events.firstOrNull {
                it.processName == app.packageName &&
                    it.reason != android.app.ApplicationExitInfo.REASON_EXIT_SELF &&
                    it.reason != android.app.ApplicationExitInfo.REASON_USER_REQUESTED
            } ?: return
            val previous = runCatching { read(app) }.getOrNull()
            if (previous != null &&
                previous.optLong("exitTimestamp", -1L) == exit.timestamp &&
                previous.optInt("exitPid", -1) == exit.pid) return

            // Platform trace can be absent, unavailable or larger than our
            // private bounded journal. Preserve an honest digest and excerpt.
            val traceBytes = runCatching {
                exit.traceInputStream?.use { stream ->
                    val data = ByteArray(MAX_TRACE + 1)
                    val n = stream.read(data)
                    if (n > 0) data.copyOf(minOf(n, MAX_TRACE)) else null
                }
            }.getOrNull()
            val record = JSONObject()
                .put("schema", SCHEMA)
                .put("kind", "historical-android-process-exit")
                .put("exitPid", exit.pid)
                .put("exitTimestamp", exit.timestamp)
                .put("exitReason", exit.reason)
                .put("exitImportance", exit.importance)
                .put("exitDescription", exit.description.orEmpty().take(2048))
                .put("tracePresent", traceBytes != null)
                .put("traceSha256", traceBytes?.let(::sha) ?: JSONObject.NULL)
                .put("traceExcerpt", traceBytes?.let {
                    String(it, Charsets.UTF_8).take(8192)
                } ?: JSONObject.NULL)
                .put("traceTruncatedOrUnknown", traceBytes?.size == MAX_TRACE)
                .put("recordedAt", System.currentTimeMillis())
            runCatching { write(app, record) }
        }
    }

    /** Called when the host catches a Core candidate failure on this process. */
    fun recordCandidateFailure(app: Application, stage: String, failure: Throwable) {
        synchronized(lock) {
            val trace = failure.stackTraceToString().take(MAX_STACK)
            val record = JSONObject()
                .put("schema", SCHEMA)
                .put("kind", "core-candidate-exception")
                .put("stage", stage.take(80))
                .put("exceptionClass", failure.javaClass.name)
                .put("stackTrace", trace)
                .put("stackTruncated", trace.length == MAX_STACK)
                .put("pid", Process.myPid())
                .put("recordedAt", System.currentTimeMillis())
            runCatching { write(app, record) }
        }
    }

    fun status(context: android.content.Context): JSONObject {
        val app = context.applicationContext as? Application
        val record = if (app == null) null else synchronized(lock) {
            runCatching { read(app) }.getOrNull()
        }
        return JSONObject()
            .put("schema", SCHEMA)
            .put("recordPresent", record != null)
            .put("lastKind", record?.optString("kind") ?: "none")
            .put("lastStage", record?.optString("stage") ?: "none")
            .put("lastExitReason", record?.optInt("exitReason", -1) ?: -1)
            .put("lastExitTimestamp", record?.optLong("exitTimestamp", 0L) ?: 0L)
            .put("fullPlatformTraceGuaranteed", false)
    }
}
