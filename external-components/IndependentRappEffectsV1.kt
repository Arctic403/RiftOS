package com.riftos.external.core

import android.content.Context
import android.util.AtomicFile
import com.riftos.app.RiftHostCapabilityUiV1
import com.riftos.app.RiftHostBuildLocalV1
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

/**
 * External Core owns RAPP identity, declaration checks and inherited explicit
 * user grants. NEVER grants a permission automatically. Operations requiring
 * new Android consent/host bridges deny until a versioned bridge is qualified.
 */
internal class IndependentRappEffects(
    context: Context,
    private val installed: (String) -> Boolean,
    private val authorizeGrant: (String, () -> Boolean, () -> Unit) -> Boolean
) {
    data class Result(
        val ok: Boolean, val token: Int, val text: String = "",
        val bytes: ByteArray = ByteArray(0), val error: String = ""
    )

    private val app = context.applicationContext
    private val root = File(app.filesDir, "riftfs").canonicalFile
    private val prefs = app.getSharedPreferences("rift-native", Context.MODE_PRIVATE)
    private val appId = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{1,63}$")
    private val publicData = listOf(
        "/D:/Workspace", "/D:/Projects", "/D:/Packages", "/D:/Builds",
        "/D:/Documents", "/D:/Downloads", "/D:/Temp"
    )
    private val worker = Executors.newFixedThreadPool(2) { job ->
        Thread(job, "rift-external-core-effect").apply { isDaemon = true }
    }
    private val timeout = Executors.newSingleThreadScheduledExecutor { job ->
        Thread(job, "rift-external-core-effect-timeout").apply { isDaemon = true }
    }
    private val maxEffectResult = 256 * 1024
    private val maxChunk = 256 * 1024
    private val maxFile = 256L * 1024 * 1024

    private fun granted(id: String, capability: String): Boolean {
        val raw = prefs.getString("setting:permissions:$id", null) ?: return false
        require(raw.length <= 16 * 1024) { "Stored grant record oversized" }
        val values = JSONObject(raw).getJSONArray("value")
        require(values.length() <= RiftAppAbi.Capability.DECLARABLE.size) {
            "Stored grant count invalid"
        }
        return (0 until values.length()).any { values.getString(it) == capability }
    }

    private fun path(id: String, requested: String, write: Boolean): File {
        require(requested.toByteArray(Charsets.UTF_8).size <= 4096)
        val display = RiftVolumePaths.normalizeDisplay(requested)
        val ownData = "/D:/Users/Default/AppData/$id"
        val ownProgram = "/C:/Programs/$id"
        val allowed = display == ownData || display.startsWith("$ownData/") ||
            publicData.any { display == it || display.startsWith("$it/") } ||
            (!write && (display == ownProgram || display.startsWith("$ownProgram/")))
        require(allowed) { "RAPP path is not within its permitted data scope" }
        val target = File(root, RiftVolumePaths.resolveRelative(display)).canonicalFile
        require(target.path.startsWith(root.path + File.separator)) {
            "RAPP path escapes RiftFS"
        }
        var node: File? = target
        while (node != null && node != root) {
            require(!Files.isSymbolicLink(node.toPath())) {
                "RAPP path contains a symlink"
            }
            node = node.parentFile
        }
        return target
    }

    fun execute(id: String, declared: Set<String>, effect: RiftAppAbi.HostEffect): Result {
        val token = effect.token
        fun denied(reason: String) = Result(false, token, error = reason)
        if (!appId.matches(id) || !installed(id)) return denied("Stale Core RAPP package")
        if (effect.capability !in declared) return denied("Capability not declared by this RAPP")
        if (effect.capability !in RiftAppAbi.Capability.DECLARABLE) {
            return denied("Unknown RAPP capability")
        }
        if (effect.capability != RiftAppAbi.Capability.WINDOW_TITLE &&
            !runCatching { granted(id, effect.capability) }.getOrDefault(false)) {
            return denied("Core RAPP capability requires explicit user approval")
        }
        return runCatching {
            when (effect.capability) {
                RiftAppAbi.Capability.WINDOW_TITLE -> {
                    require(effect.operation == "set" &&
                        effect.text.length in 1..96)
                    Result(true, token, text = effect.text)
                }
                RiftAppAbi.Capability.FS_READ -> read(id, effect)
                RiftAppAbi.Capability.FS_WRITE -> write(id, effect)
                RiftAppAbi.Capability.SIGNING_IDENTITY ->
                    IndependentRappSigning.execute(effect)
                else -> denied("Independent platform capability bridge not qualified")
            }
        }.getOrElse {
            denied(it.message?.take(120) ?: "External Core capability rejected")
        }
    }

    /**
     * External Core policy and session-liveness gate. User consent runs via
     * the existing Shell's single-use ticket channel, never on the serial
     * event executor. Grants are committed inside package install/uninstall
     * lock only while the originating session remains active.
     */
    fun executeAsync(id: String, name: String, declared: Set<String>,
                     effect: RiftAppAbi.HostEffect, stillValid: () -> Boolean,
                     done: (Result) -> Unit) {
        val settled = AtomicBoolean(false)
        val token = effect.token
        fun complete(result: Result) {
            if (settled.compareAndSet(false, true)) {
                val checked = if (!stillValid() || !installed(id)) {
                    Result(false, token, error = "Stale Core RAPP session")
                } else if (result.bytes.size > maxEffectResult ||
                    result.text.toByteArray(Charsets.UTF_8).size > maxEffectResult) {
                    Result(false, token, error = "RAPP capability output oversized")
                } else result.copy(token = token)
                done(checked)
            }
        }
        val expire = timeout.schedule({
            complete(Result(false, token, error = "Core capability timed out"))
        }, 180, TimeUnit.SECONDS)
        fun finish(result: Result) {
            expire.cancel(false)
            complete(result)
        }
        if (!stillValid() || !installed(id) ||
            effect.capability !in declared ||
            effect.capability !in RiftAppAbi.Capability.DECLARABLE) {
            finish(Result(false, token, error = "Missing or stale RAPP capability declaration"))
            return
        }
        fun runEffect() {
            if (!stillValid() || !installed(id)) {
                finish(Result(false, token, error = "Stale RAPP effect request"))
                return
            }
            if (effect.capability in setOf(
                    RiftAppAbi.Capability.CLIPBOARD_READ,
                    RiftAppAbi.Capability.CLIPBOARD_WRITE,
                    RiftAppAbi.Capability.SHARE,
                    RiftAppAbi.Capability.WINDOW_TITLE)) {
                runCatching {
                    RiftHostCapabilityUiV1.uiEffect(id, name,
                        effect.capability, effect.operation, effect.requestId,
                        token, effect.text, effect.bytes) { ok, _, text, bytes, error ->
                        finish(Result(ok, token, text, bytes, error))
                    }
                }.onFailure { finish(Result(false, token,
                    error = it.message ?: "Shell capability transport unavailable")) }
                return
            }
            worker.execute {
                val result = runCatching {
                    if (effect.capability == RiftAppAbi.Capability.BUILD_LOCAL) {
                        check(RiftHostBuildLocalV1.abiVersion() == 1) {
                            "Unsupported host build.local API version"
                        }
                        val data = RiftHostBuildLocalV1.execute(
                            app, effect.operation, effect.text)
                        Result(true, token, text = data)
                    } else execute(id, declared, effect)
                }.getOrElse { Result(false, token,
                    error = it.message ?: it.javaClass.simpleName) }
                finish(result)
            }
        }
        if (effect.capability == RiftAppAbi.Capability.WINDOW_TITLE ||
            granted(id, effect.capability)) {
            runEffect()
            return
        }
        runCatching {
            check(RiftHostCapabilityUiV1.abiVersion() == 1) {
                "Unsupported host permission UI API version"
            }
            RiftHostCapabilityUiV1.consent(id, name, effect.capability) { approved ->
                if (!approved || !stillValid()) {
                    finish(Result(false, token, error = "RAPP permission denied or expired"))
                } else {
                    val persisted = runCatching {
                        authorizeGrant(id, stillValid) {
                            val key = "setting:permissions:$id"
                            val existing = prefs.getString(key, null)
                            val current = if (existing == null) mutableSetOf<String>()
                                else {
                                    val values = JSONObject(existing).getJSONArray("value")
                                    (0 until values.length()).map { values.getString(it) }
                                        .toMutableSet()
                                }
                            require(current.size <= RiftAppAbi.Capability.DECLARABLE.size)
                            current.add(effect.capability)
                            require(prefs.edit().putString(key, JSONObject()
                                .put("value", JSONArray(current.sorted()))
                                .put("modified", System.currentTimeMillis())
                                .toString()).commit()) {
                                "Cannot persist Core user permission"
                            }
                        }
                    }.getOrDefault(false)
                    if (persisted) runEffect()
                    else finish(Result(false, token, error = "RAPP changed before grant approval"))
                }
            }
        }.onFailure { finish(Result(false, token,
            error = it.message ?: "Shell permission transport unavailable")) }
    }

    private fun read(id: String, effect: RiftAppAbi.HostEffect): Result {
        val token = effect.token
        return when (effect.operation) {
            "readText" -> {
                val file = path(id, effect.text, false)
                require(file.isFile && file.length() in 0..maxChunk.toLong())
                Result(true, token, bytes = file.readBytes())
            }
            "readBytes" -> {
                val options = JSONObject(effect.text)
                val file = path(id, options.getString("path"), false)
                val offset = options.optLong("offset", 0L)
                val count = options.optInt("length", maxChunk)
                require(file.isFile && file.length() <= maxFile &&
                    offset >= 0 && count in 1..maxChunk && offset <= file.length())
                val available = minOf(count.toLong(), file.length() - offset).toInt()
                val bytes = ByteArray(available)
                RandomAccessFile(file, "r").use {
                    it.seek(offset)
                    it.readFully(bytes)
                }
                Result(true, token, bytes = bytes)
            }
            "stat" -> {
                val file = path(id, effect.text, false)
                require(file.exists()) { "RAPP file does not exist" }
                Result(true, token, text = stat(file, effect.text).toString())
            }
            "list" -> {
                val dir = path(id, effect.text, false)
                require(dir.isDirectory)
                val entries = JSONArray()
                val files = dir.listFiles()?.sortedWith(
                    compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }
                ) ?: emptyList()
                require(files.size <= 5000) { "RAPP list bound exceeded" }
                for (child in files) {
                    entries.put(stat(child, effect.text.trimEnd('/') + "/" + child.name))
                }
                Result(true, token, bytes = entries.toString().toByteArray(Charsets.UTF_8))
            }
            else -> Result(false, token, error = "Unsupported Core fs.read operation")
        }
    }

    private fun stat(file: File, display: String): JSONObject = JSONObject()
        .put("path", RiftVolumePaths.normalizeDisplay(display))
        .put("name", file.name)
        .put("kind", if (file.isDirectory) "directory" else "file")
        .put("size", if (file.isFile) file.length() else 0L)
        .put("modified", file.lastModified())
        .put("backend", "android-internal")

    private fun write(id: String, effect: RiftAppAbi.HostEffect): Result {
        val token = effect.token
        return when (effect.operation) {
            "writeText" -> {
                val file = path(id, effect.text, true)
                val content = effect.bytes
                require(content.size <= maxChunk &&
                    String(content, Charsets.UTF_8).toByteArray(Charsets.UTF_8)
                        .contentEquals(content)) { "Text write must be valid bounded UTF-8" }
                val parent = requireNotNull(file.parentFile)
                require(parent.isDirectory && !Files.isSymbolicLink(parent.toPath()))
                val atomic = AtomicFile(file)
                val stream = atomic.startWrite()
                try {
                    stream.write(content)
                    atomic.finishWrite(stream)
                } catch (failure: Throwable) {
                    atomic.failWrite(stream)
                    throw failure
                }
                Result(true, token, text = stat(file, effect.text).toString())
            }
            "mkdir" -> {
                val dir = path(id, effect.text, true)
                require(dir.isDirectory || dir.mkdirs())
                Result(true, token, text = stat(dir, effect.text).toString())
            }
            "move" -> {
                val request = JSONObject(effect.text)
                val fromDisplay = request.getString("from").trim()
                val toDisplay = request.getString("to").trim()
                require(fromDisplay.isNotBlank() && toDisplay.isNotBlank())
                val from = path(id, fromDisplay, true)
                val to = path(id, toDisplay, true)
                require(from.exists() && from != to) {
                    "RAPP move source missing or identical"
                }
                val targetParent = requireNotNull(to.parentFile)
                require(targetParent.isDirectory || targetParent.mkdirs())
                require(!Files.isSymbolicLink(targetParent.toPath()))
                if (to.exists()) {
                    require(request.optBoolean("replace", false) &&
                        to.isFile && to.delete()) {
                        "RAPP move target exists and replace not allowed"
                    }
                }
                require(from.renameTo(to)) { "RAPP move operation failed" }
                Result(true, token, text = stat(to, toDisplay).toString())
            }
            "writeBytes" -> {
                val options = JSONObject(effect.text)
                val file = path(id, options.getString("path"), true)
                val offset = options.optLong("offset", 0L)
                val truncate = options.optBoolean("truncate", false)
                require(effect.bytes.size <= maxChunk && offset >= 0 &&
                    offset <= maxFile - effect.bytes.size &&
                    (!truncate || offset == 0L))
                val parent = requireNotNull(file.parentFile)
                require(parent.isDirectory && !Files.isSymbolicLink(parent.toPath()))
                RandomAccessFile(file, "rw").use {
                    if (truncate) it.setLength(0)
                    it.seek(offset)
                    it.write(effect.bytes)
                }
                Result(true, token, text = stat(file, options.getString("path")).toString())
            }
            else -> Result(false, token, error = "Unsupported Core fs.write operation")
        }
    }
}
