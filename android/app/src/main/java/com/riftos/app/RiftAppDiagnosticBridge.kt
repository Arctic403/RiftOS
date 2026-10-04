package com.riftos.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RiftOS-owned diagnostic receiver for native proof apps.
 *
 * Target applications own their runtime semantics. This bridge only receives fixed diagnostic
 * packets over localhost and persists bounded evidence into RiftFS. It never parses Rift++
 * source/opcodes, emits target code, or participates in application execution.
 */
object RiftAppDiagnosticBridge {
    const val PORT = 39771
    const val PACKET_BYTES = 32

    private const val SCHEMA = "rift.app-diagnostic-dump/1"
    private const val MAX_EVENTS = 64
    private const val MAX_PACKET_BYTES = 256
    private const val ROOT_RELATIVE = "system/volumes/D/Diagnostics/riftpp"

    private const val RIFTPP_NATIVE_PROOF_PACKAGE = "com.riftpp.nativeproof"
    private const val RIFTPP_EDITOR_PACKAGE = "com.riftpp.editor"
    private const val RIFTPP_NATIVE_EDITOR_V1_PACKAGE = "com.riftpp.editor.nativev1"
    private const val RIFTPP_ADAPTER_R1_PACKAGE = "com.riftpp.editor.adapterr1"
    const val DEFAULT_PACKAGE = RIFTPP_NATIVE_EDITOR_V1_PACKAGE
    private val allowedPackages = setOf(
        RIFTPP_NATIVE_PROOF_PACKAGE,
        RIFTPP_EDITOR_PACKAGE,
        RIFTPP_NATIVE_EDITOR_V1_PACKAGE,
        RIFTPP_ADAPTER_R1_PACKAGE
    )

    fun supports(packageName: String): Boolean =
        packageName in allowedPackages

    private val lock = Any()
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var activePackage: String = ""
    @Volatile private var activeSessionId: String = ""

    fun beginLaunch(
        context: Context,
        packageName: String,
        artifactSha256: String? = null
    ): JSONObject {
        require(packageName in allowedPackages) {
            "Diagnostic bridge package is not allowlisted: $packageName"
        }

        val app = context.applicationContext
        appContext = app
        ensureStarted(app)

        val now = System.currentTimeMillis()
        val sessionId = now.toString() + "-" + packageName.substringAfterLast('.').take(24)
        activePackage = packageName
        activeSessionId = sessionId

        val packageInfo = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                app.packageManager.getPackageInfo(
                    packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                app.packageManager.getPackageInfo(packageName, 0)
            }
        }.getOrNull()

        val dump = JSONObject()
            .put("schema", SCHEMA)
            .put("state", "listening")
            .put("package", packageName)
            .put("sessionId", sessionId)
            .put("bridge", "localhost-udp")
            .put("host", "127.0.0.1")
            .put("port", PORT)
            .put("packetBytes", PACKET_BYTES)
            .put("startedAt", now)
            .put("artifactSha256", artifactSha256 ?: JSONObject.NULL)
            .put("versionCode", packageInfo?.longVersionCode ?: JSONObject.NULL)
            .put("versionName", packageInfo?.versionName ?: JSONObject.NULL)
            .put("events", JSONArray())
            .put("eventCount", 0)
            .put("lastEventAt", JSONObject.NULL)
            .put("listenerRunning", running.get())

        writeDump(app, packageName, dump)
        return dump
    }

    fun capture(context: Context, packageName: String): JSONObject {
        require(packageName in allowedPackages) {
            "Diagnostic bridge package is not allowlisted: $packageName"
        }
        val app = context.applicationContext
        ensureStarted(app)
        val current = readDump(app, packageName)
        return current
            .put("capturedAt", System.currentTimeMillis())
            .put("listenerRunning", running.get())
            .put("bridgePort", PORT)
    }

    fun latest(context: Context, packageName: String): JSONObject {
        require(packageName in allowedPackages) {
            "Diagnostic bridge package is not allowlisted: $packageName"
        }
        val app = context.applicationContext
        return readDump(app, packageName)
            .put("listenerRunning", running.get())
            .put("processExitHistory", historicalProcessExits(app, packageName))
    }

    fun status(context: Context): JSONObject {
        val app = context.applicationContext
        ensureStarted(app)
        return JSONObject()
            .put("schema", "rift.app-diagnostic-bridge-status/1")
            .put("running", running.get())
            .put("host", "127.0.0.1")
            .put("port", PORT)
            .put("packetBytes", PACKET_BYTES)
            .put(
                "activePackage",
                if (activePackage.isBlank()) JSONObject.NULL else activePackage
            )
            .put(
                "activeSessionId",
                if (activeSessionId.isBlank()) JSONObject.NULL else activeSessionId
            )
            .put("root", "/D:/Diagnostics/riftpp")
    }

    fun reset(context: Context, packageName: String): JSONObject {
        require(packageName in allowedPackages) {
            "Diagnostic bridge package is not allowlisted: $packageName"
        }
        val app = context.applicationContext
        val dir = packageDir(app, packageName)
        val deleted = if (!dir.exists()) true else deleteTree(dir)
        if (activePackage == packageName) {
            activePackage = ""
            activeSessionId = ""
        }
        return JSONObject()
            .put("schema", "rift.app-diagnostic-reset/1")
            .put("package", packageName)
            .put("deleted", deleted)
    }

    private fun ensureStarted(context: Context) {
        if (running.get()) return
        synchronized(lock) {
            if (running.get()) return

            val bound = DatagramSocket(PORT, InetAddress.getByName("127.0.0.1")).apply {
                soTimeout = 1000
                receiveBufferSize = 64 * 1024
            }
            socket = bound
            running.set(true)

            executor.execute {
                receiveLoop()
            }
        }
    }

    private fun receiveLoop() {
        val buffer = ByteArray(MAX_PACKET_BYTES)
        while (running.get()) {
            val currentSocket = socket ?: break
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                currentSocket.receive(packet)
                val bytes = packet.data.copyOfRange(
                    packet.offset,
                    packet.offset + packet.length
                )
                recordPacket(bytes, packet.address?.hostAddress.orEmpty())
            } catch (timeout: java.net.SocketTimeoutException) {
                // Poll running flag.
            } catch (error: Throwable) {
                val context = appContext
                val packageName = activePackage
                if (context != null && packageName.isNotBlank()) {
                    synchronized(lock) {
                        val dump = readDump(context, packageName)
                            .put("listenerError", error.message ?: error.javaClass.simpleName)
                            .put("listenerErrorAt", System.currentTimeMillis())
                        writeDump(context, packageName, dump)
                    }
                }
                if (!currentSocket.isClosed) {
                    runCatching { currentSocket.close() }
                }
                socket = null
                running.set(false)
                break
            }
        }
    }

    private fun recordPacket(bytes: ByteArray, remoteHost: String) {
        val context = appContext ?: return
        val packageName = activePackage
        if (packageName.isBlank()) return

        val now = System.currentTimeMillis()
        val event = parsePacket(bytes)
            .put("receivedAt", now)
            .put("remoteHost", remoteHost.take(64))
            .put("packetSha256", sha256(bytes))

        synchronized(lock) {
            val dump = readDump(context, packageName)
            val previous = dump.optJSONArray("events") ?: JSONArray()
            val next = JSONArray()
            val start = (previous.length() - (MAX_EVENTS - 1)).coerceAtLeast(0)
            for (index in start until previous.length()) {
                next.put(previous.opt(index))
            }
            next.put(event)

            dump
                .put("state", "evidence-received")
                .put("sessionId", activeSessionId.ifBlank { dump.optString("sessionId") })
                .put("events", next)
                .put("eventCount", next.length())
                .put("lastEventAt", now)
                .put("lastStage", event.optString("stageName"))
                .put("lastStageCode", event.optInt("stage", -1))
                .put("listenerRunning", running.get())

            writeDump(context, packageName, dump)
        }
    }

    private fun parsePacket(bytes: ByteArray): JSONObject {
        val rawHex = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (bytes.size != PACKET_BYTES) {
            return JSONObject()
                .put("valid", false)
                .put("reason", "packet-size")
                .put("bytes", bytes.size)
                .put("rawHex", rawHex.take(MAX_PACKET_BYTES * 2))
        }

        if (
            bytes[0] != 'R'.code.toByte() ||
            bytes[1] != 'D'.code.toByte() ||
            bytes[2] != 'B'.code.toByte() ||
            bytes[3] != 'G'.code.toByte()
        ) {
            return JSONObject()
                .put("valid", false)
                .put("reason", "packet-magic")
                .put("bytes", bytes.size)
                .put("rawHex", rawHex)
        }

        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(4)
        val version = bb.int
        val stage = bb.int
        val pid = bb.int
        val arg0 = bb.int
        val arg1 = bb.int
        val arg2 = bb.int
        val detail = bb.int

        return JSONObject()
            .put("valid", version == 1)
            .put("version", version)
            .put("stage", stage)
            .put("stageName", stageName(stage))
            .put("pid", pid)
            .put("arg0", hex32(arg0))
            .put("arg1", hex32(arg1))
            .put("arg2", hex32(arg2))
            .put("detail", hex32(detail))
            .put("rawHex", rawHex)
    }

    private fun stageName(stage: Int): String = when (stage) {
        1 -> "entry-begin"
        2 -> "socket-created"
        3 -> "bridge-connected"
        4 -> "before-return"
        5 -> "callback-table-write"
        6 -> "window-callback"
        7 -> "input-callback"
        8 -> "fatal-signal"
        else -> "stage-$stage"
    }

    private fun hex32(value: Int): String =
        "0x" + value.toUInt().toString(16).padStart(8, '0')

    private fun readDump(context: Context, packageName: String): JSONObject {
        val file = latestFile(context, packageName)
        if (!file.isFile || file.length() > 256L * 1024L) {
            return JSONObject()
                .put("schema", SCHEMA)
                .put("state", "none")
                .put("package", packageName)
                .put("events", JSONArray())
                .put("eventCount", 0)
        }
        return runCatching {
            JSONObject(file.readText(Charsets.UTF_8))
        }.getOrElse {
            JSONObject()
                .put("schema", SCHEMA)
                .put("state", "corrupt")
                .put("package", packageName)
                .put("error", it.message ?: it.javaClass.simpleName)
                .put("events", JSONArray())
                .put("eventCount", 0)
        }
    }

    private fun historicalProcessExits(
        context: Context,
        packageName: String
    ): JSONObject {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return JSONObject()
                .put("supported", false)
                .put("reason", "android-version")
        }

        val manager =
            context.getSystemService(
                Context.ACTIVITY_SERVICE
            ) as ActivityManager

        return runCatching {
            val rows =
                manager.getHistoricalProcessExitReasons(
                    packageName,
                    0,
                    8
                )
            JSONObject()
                .put("supported", true)
                .put("available", true)
                .put(
                    "rows",
                    JSONArray().apply {
                        for (row in rows) {
                            put(
                                JSONObject()
                                    .put("timestamp", row.timestamp)
                                    .put("reason", row.reason)
                                    .put("reasonName", exitReasonName(row.reason))
                                    .put("status", row.status)
                                    .put("importance", row.importance)
                                    .put("pssKb", row.pss)
                                    .put("rssKb", row.rss)
                            )
                        }
                    }
                )
        }.getOrElse { failure ->
            JSONObject()
                .put("supported", true)
                .put("available", false)
                .put("error", failure.javaClass.simpleName)
                .put("detail", failure.message ?: JSONObject.NULL)
        }
    }

    private fun exitReasonName(reason: Int): String =
        when (reason) {
            ApplicationExitInfo.REASON_UNKNOWN -> "unknown"
            ApplicationExitInfo.REASON_EXIT_SELF -> "exit-self"
            ApplicationExitInfo.REASON_SIGNALED -> "signaled"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "low-memory"
            ApplicationExitInfo.REASON_CRASH -> "java-crash"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native-crash"
            ApplicationExitInfo.REASON_ANR -> "anr"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization-failure"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission-change"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive-resource-usage"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "user-requested"
            ApplicationExitInfo.REASON_USER_STOPPED -> "user-stopped"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency-died"
            ApplicationExitInfo.REASON_OTHER -> "other"
            ApplicationExitInfo.REASON_FREEZER -> "freezer"
            else -> "reason-$reason"
        }


    private fun writeDump(context: Context, packageName: String, value: JSONObject) {
        val target = latestFile(context, packageName)
        target.parentFile?.mkdirs()
        val bytes = value.toString(2).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 256 * 1024) {
            "Diagnostic dump exceeds 256 KiB"
        }
        val temp = File(target.parentFile, "." + target.name + ".tmp")
        temp.writeBytes(bytes)
        if (target.exists()) {
            require(target.delete()) {
                "Could not replace diagnostic dump"
            }
        }
        require(temp.renameTo(target)) {
            "Could not publish diagnostic dump"
        }
    }

    private fun packageDir(context: Context, packageName: String): File {
        val safe = packageName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(240)
        return File(context.filesDir, ROOT_RELATIVE + "/" + safe)
    }

    private fun latestFile(context: Context, packageName: String): File =
        File(packageDir(context, packageName), "latest.json")

    private fun deleteTree(file: File): Boolean {
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                if (!deleteTree(child)) return false
            }
        }
        return file.delete()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
