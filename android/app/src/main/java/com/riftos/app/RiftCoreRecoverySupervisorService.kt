package com.riftos.app

import android.app.ActivityManager
import android.app.Application
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.AtomicFile
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/**
 * Best-effort host-owned Core process observer in its OWN Android process.
 *
 * It never runs Core apps, starts a competing Core, or authorizes a candidate.
 * Android can terminate this Service too; it is not a privileged watchdog.
 * Missing-process events are persisted for the next eligible startup.
 */
class RiftCoreRecoverySupervisorService : Service() {
    companion object {
        const val SCHEMA = "riftos.host.core-supervisor/1"
        private const val TAG = "RiftCoreSupervisor"
        private const val INTERVAL_MS = 5_000L
        private const val MAX_BYTES = 4096

        private fun recordFile(context: Context): File =
            File(RiftBootstrapComponentStore.root(context), "core-supervisor.json")

        internal fun status(context: Context): JSONObject {
            val file = recordFile(context)
            if (!file.exists() && !File(file.path + ".bak").exists()) {
                return JSONObject().put("schema", SCHEMA)
                    .put("observationPresent", false)
                    .put("independentProcessObservation", true)
                    .put("automaticRestartGuaranteed", false)
            }
            return runCatching {
                require(!Files.isSymbolicLink(file.toPath())) {
                    "Core supervisor journal symlinked"
                }
                val bytes = AtomicFile(file).openRead().use { stream ->
                    val buf = ByteArray(MAX_BYTES + 1)
                    val count = stream.read(buf)
                    require(count in 1..MAX_BYTES && stream.read() == -1) {
                        "Core supervisor journal exceeds bounds"
                    }
                    buf.copyOf(count)
                }
                JSONObject(String(bytes, Charsets.UTF_8)).also {
                    require(it.optString("schema") == SCHEMA) {
                        "Core supervisor journal schema mismatch"
                    }
                }.put("independentProcessObservation", true)
                    .put("automaticRestartGuaranteed", false)
            }.getOrElse { JSONObject().put("schema", SCHEMA)
                .put("observationPresent", false)
                .put("journalError", it.javaClass.simpleName)
                .put("automaticRestartGuaranteed", false) }
        }

        private fun persist(context: Context, state: JSONObject) {
            val root = RiftBootstrapComponentStore.root(context)
            require(!Files.isSymbolicLink(root.toPath()) &&
                (root.isDirectory || root.mkdirs())) {
                "Core supervisor storage unavailable"
            }
            val file = recordFile(context)
            for (suffix in listOf("", ".new", ".bak")) {
                require(!Files.isSymbolicLink(File(file.path + suffix).toPath())) {
                    "Core supervisor journal target symlinked"
                }
            }
            val bytes = state.toString().toByteArray(Charsets.UTF_8)
            require(bytes.size in 1..MAX_BYTES) { "Supervisor journal too large" }
            val atomic = AtomicFile(file)
            val stream = atomic.startWrite()
            try {
                stream.write(bytes)
                atomic.finishWrite(stream)
            } catch (failure: Throwable) {
                atomic.failWrite(stream)
                throw failure
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastCorePid = 0
    private var observedCore = false
    private var reportedMissing = false
    private val observer = object : Runnable {
        override fun run() {
            runCatching { inspect() }
                .onFailure { Log.w(TAG, "Core observation unavailable", it) }
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        require(Build.VERSION.SDK_INT < 28 ||
            Application.getProcessName() == packageName + ":riftCoreSupervisor") {
            "Core recovery observer must be out of Core process"
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.removeCallbacks(observer)
        handler.postDelayed(observer, 1000L)
        return START_STICKY
    }

    private fun inspect() {
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val running = manager.runningAppProcesses ?: return
        val current = running.firstOrNull {
            it.uid == applicationInfo.uid && it.processName == packageName
        }?.pid ?: 0
        if (current > 0) {
            if (!observedCore || current != lastCorePid) {
                lastCorePid = current
                observedCore = true
                reportedMissing = false
                persist(this, JSONObject().put("schema", SCHEMA)
                    .put("observationPresent", true)
                    .put("state", "core-observed")
                    .put("lastCorePid", current)
                    .put("observerPid", Process.myPid())
                    .put("lastObservedAt", System.currentTimeMillis()))
            }
        } else if (observedCore && !reportedMissing) {
            persist(this, JSONObject().put("schema", SCHEMA)
                .put("observationPresent", true)
                .put("state", "core-process-missing")
                .put("lastCorePid", lastCorePid)
                .put("observerPid", Process.myPid())
                .put("missingAt", System.currentTimeMillis())
                .put("restartPending", true))
            reportedMissing = true
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(observer)
        super.onDestroy()
    }
}
