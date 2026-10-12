package com.riftos.app

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * APK-owned, cross-process graphical Shell crash evidence. The separate Shell
 * writes its Java fatal exception before Android's original handler runs.
 * Core reads the same app-private RiftFS record and independently imports
 * available Android process-exit evidence (including native crashes).
 *
 * Diagnostics NEVER alter a component selector, Recovery backup or process
 * lifecycle. Android may withhold native tombstones; do not claim full dumps.
 */
internal object RiftShellCrashDiagnostics {
    const val SCHEMA = "riftos.shell.crash-diagnostics/1"
    const val CRASH_PATH = "/D:/Diagnostics/RiftShell/last-crash.json"
    const val EXIT_PATH = "/D:/Diagnostics/RiftShell/last-exit.json"
    private const val MAX_RECORD = 24 * 1024
    private const val MAX_STACK_CHARS = 9_000
    private const val MAX_TRACE = 8 * 1024
    private const val MAX_MESSAGE = 800
    private val lock = Any()
    @Volatile private var installed = false
    @Volatile private var lastCapturedFailure: Throwable? = null

    private fun directory(context: Context, create: Boolean): File {
        val base = File(context.applicationContext.filesDir, "riftfs")
        val relative = RiftVolumePaths.resolveRelative("/D:/Diagnostics/RiftShell")
        val dir = File(base, relative)
        // Never traverse a symlink, even if a malicious writer replaced a
        // parent path after an earlier report.
        var part: File? = dir
        while (part != null && part != base.parentFile) {
            require(!Files.isSymbolicLink(part.toPath())) {
                "Shell crash evidence path is symlinked"
            }
            part = part.parentFile
        }
        require(dir.canonicalPath.startsWith(base.canonicalPath + File.separator)) {
            "Shell crash evidence escaped RiftFS"
        }
        if (create) require(dir.isDirectory || dir.mkdirs()) {
            "Shell crash evidence directory unavailable"
        }
        return dir
    }

    private fun recordFile(context: Context, name: String, create: Boolean): File {
        require(name == "last-crash.json" || name == "last-exit.json")
        val path = File(directory(context, create), name)
        for (suffix in listOf("", ".bak", ".new")) {
            require(!Files.isSymbolicLink(File(path.path + suffix).toPath())) {
                "Shell crash evidence record is symlinked"
            }
        }
        return path
    }

    private fun read(context: Context, name: String): JSONObject? {
        val file = recordFile(context, name, false)
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        val bytes = AtomicFile(file).openRead().use { input ->
            val bounded = ByteArray(MAX_RECORD + 1)
            val n = input.read(bounded)
            require(n in 1..MAX_RECORD && input.read() == -1) {
                "Shell crash evidence record oversized"
            }
            bounded.copyOf(n)
        }
        return JSONObject(String(bytes, Charsets.UTF_8)).also {
            require(it.optString("schema") == SCHEMA) {
                "Shell crash evidence schema mismatch"
            }
        }
    }

    private fun write(context: Context, name: String, entry: JSONObject) {
        val path = recordFile(context, name, true)
        val data = entry.toString().toByteArray(Charsets.UTF_8)
        require(data.size in 1..MAX_RECORD) { "Shell crash evidence exceeds bound" }
        val atomic = AtomicFile(path)
        val stream = atomic.startWrite()
        try {
            stream.write(data)
            atomic.finishWrite(stream)
        } catch (failure: Throwable) {
            atomic.failWrite(stream)
            throw failure
        }
    }

    private fun selectedSha(context: Context): String? = runCatching {
        RiftBootstrapComponentStore.active(context, "shell")?.optString("sha256")
    }.getOrNull()?.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }

    /** Run in :riftShell Application.onCreate BEFORE WebView or DEX loading. */
    @Synchronized
    fun install(app: Application) {
        require(Build.VERSION.SDK_INT >= 28 &&
            Application.getProcessName() == app.packageName + ":riftShell") {
            "Shell crash observer must run only in the graphical Shell process"
        }
        if (installed) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
            ?: error("Android original fatal handler unavailable")
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Preserve the precise startup stage for the SAME Throwable,
            // but always capture a distinct failure, even in the same PID.
            runCatching {
                if (lastCapturedFailure !== error) {
                    recordFailure(app, "uncaught-" + thread.name.take(48), error)
                }
            }
            previous.uncaughtException(thread, error)
        }
        installed = true
    }

    /** Captures synchronous selected DEX load / attach failure before rethrow. */
    fun recordFailure(context: Context, stage: String, error: Throwable) {
        runCatching {
            synchronized(lock) {
                val rawStack = error.stackTraceToString()
                val stack = rawStack.take(MAX_STACK_CHARS)
                val record = JSONObject()
                    .put("schema", SCHEMA)
                    .put("kind", "java-exception")
                    .put("process", context.packageName + ":riftShell")
                    .put("stage", stage.take(80))
                    .put("pid", Process.myPid())
                    .put("thread", Thread.currentThread().name.take(64))
                    .put("shellSha256", selectedSha(context) ?: JSONObject.NULL)
                    .put("exceptionClass", error.javaClass.name)
                    .put("message", error.message.orEmpty().take(MAX_MESSAGE))
                    .put("stackTrace", stack)
                    .put("stackTruncated", rawStack.length > stack.length)
                    .put("recordedAt", System.currentTimeMillis())
                    .put("fullNativeTombstone", false)
                write(context, "last-crash.json", record)
                lastCapturedFailure = error
            }
        }
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 255)
        }

    /**
     * Core-side import. Android's historical reasons catch native signals and
     * crashes that never reach the Java handler. Exit descriptions/trace may be
     * missing; a forced stop is not automatically classified as a crash.
     */
    fun refreshHistoricalExit(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            synchronized(lock) {
                val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val entry = manager.getHistoricalProcessExitReasons(
                    context.packageName, 0, 16
                ).firstOrNull {
                    it.processName == context.packageName + ":riftShell" &&
                        it.reason != ApplicationExitInfo.REASON_USER_REQUESTED &&
                        it.reason != ApplicationExitInfo.REASON_EXIT_SELF
                } ?: return@synchronized
                val existing = read(context, "last-exit.json")
                if (existing?.optLong("exitTimestamp", -1L) == entry.timestamp &&
                    existing.optInt("pid", -1) == entry.pid) return@synchronized

                val trace = runCatching {
                    entry.traceInputStream?.use { input ->
                        val bytes = ByteArray(MAX_TRACE + 1)
                        val n = input.read(bytes)
                        if (n > 0) bytes.copyOf(minOf(n, MAX_TRACE)) else null
                    }
                }.getOrNull()
                val record = JSONObject()
                    .put("schema", SCHEMA)
                    .put("kind", "android-process-exit")
                    .put("process", entry.processName)
                    .put("pid", entry.pid)
                    .put("exitTimestamp", entry.timestamp)
                    .put("exitReason", entry.reason)
                    .put("exitImportance", entry.importance)
                    .put("description", entry.description.orEmpty().take(1400))
                    .put("tracePresent", trace != null)
                    .put("traceSha256", trace?.let(::digest) ?: JSONObject.NULL)
                    .put("traceExcerpt", trace?.let {
                        String(it, Charsets.UTF_8).take(6_000)
                    } ?: JSONObject.NULL)
                    .put("traceTruncatedOrUnknown", trace == null || trace.size == MAX_TRACE)
                    .put("fullNativeTombstone", false)
                    .put("recordedAt", System.currentTimeMillis())
                write(context, "last-exit.json", record)
            }
        }
    }

    /** Small, safe Core status summary; no full stack in routine status. */
    fun status(context: Context): JSONObject {
        val crash = runCatching { synchronized(lock) { read(context, "last-crash.json") } }.getOrNull()
        val exit = runCatching { synchronized(lock) { read(context, "last-exit.json") } }.getOrNull()
        return JSONObject()
            .put("schema", SCHEMA)
            .put("javaCrashPresent", crash != null)
            .put("lastStage", crash?.optString("stage") ?: "none")
            .put("lastExceptionClass", crash?.optString("exceptionClass") ?: "none")
            .put("lastCrashPid", crash?.optInt("pid", -1) ?: -1)
            .put("lastCrashAt", crash?.optLong("recordedAt", 0L) ?: 0L)
            .put("androidExitPresent", exit != null)
            .put("lastExitPid", exit?.optInt("pid", -1) ?: -1)
            .put("lastExitReason", exit?.optInt("exitReason", -1) ?: -1)
            .put("lastExitTimestamp", exit?.optLong("exitTimestamp", 0L) ?: 0L)
            .put("crashDumpPath", CRASH_PATH)
            .put("exitDumpPath", EXIT_PATH)
            .put("fullNativeTombstoneGuaranteed", false)
            .put("automaticRollbackEnabled", false)
    }

    /** Full bounded evidence, accessible from Core's headless RiftShell CLI. */
    fun report(context: Context): JSONObject {
        refreshHistoricalExit(context)
        val crash = runCatching { synchronized(lock) { read(context, "last-crash.json") } }.getOrNull()
        val exit = runCatching { synchronized(lock) { read(context, "last-exit.json") } }.getOrNull()
        return JSONObject()
            .put("schema", SCHEMA)
            .put("status", status(context))
            .put("javaCrash", crash ?: JSONObject.NULL)
            .put("androidExit", exit ?: JSONObject.NULL)
    }
}
