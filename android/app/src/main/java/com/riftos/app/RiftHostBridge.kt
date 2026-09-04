package com.riftos.app

import android.app.ActivityManager
import android.os.Build
import android.os.StatFs
import android.util.Base64
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

class RiftHostBridge(private val activity: MainActivity) {
    private val context = activity.applicationContext
    private val riftRoot = File(context.filesDir, "riftfs")
    private val nativeRoot = File(context.applicationInfo.nativeLibraryDir)
    private val modelProcess = AtomicReference<Process?>(null)
    private val modelLog = StringBuilder()

    init {
        riftRoot.mkdirs()
        listOf("system", "home", "tmp", "apps", "models", "projects").forEach {
            File(riftRoot, it).mkdirs()
        }
    }

    private fun resolveRiftPath(path: String): File {
        val relative = path.trim().removePrefix("/")
        val candidate = File(riftRoot, relative).canonicalFile
        val root = riftRoot.canonicalFile
        require(candidate.path == root.path || candidate.path.startsWith(root.path + File.separator)) {
            "Path escapes RiftFS"
        }
        return candidate
    }

    private fun bundledExecutable(name: String): File {
        val fileName = when (name) {
            "llama-server" -> "libllamaserver.so"
            "codex" -> "libcodex.so"
            else -> error("Unknown bundled executable")
        }
        return File(nativeRoot, fileName)
    }

    private fun errorJson(t: Throwable): String = JSONObject()
        .put("ok", false)
        .put("error", t.message ?: t.javaClass.simpleName)
        .toString()

    private fun isArm64(): Boolean = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

    @JavascriptInterface
    fun deviceInfo(): String = try {
        val activityManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val stat = StatFs(context.filesDir.absolutePath)
        JSONObject()
            .put("ok", true)
            .put("platform", "android")
            .put("sdk", Build.VERSION.SDK_INT)
            .put("model", Build.MODEL)
            .put("manufacturer", Build.MANUFACTURER)
            .put("architecture", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
            .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("cpuCores", Runtime.getRuntime().availableProcessors())
            .put("memoryTotalBytes", mem.totalMem)
            .put("memoryAvailableBytes", mem.availMem)
            .put("storageTotalBytes", stat.totalBytes)
            .put("storageAvailableBytes", stat.availableBytes)
            .toString()
    } catch (t: Throwable) { errorJson(t) }

    @JavascriptInterface
    fun localAiInfo(): String = try {
        val llama = bundledExecutable("llama-server")
        val codex = bundledExecutable("codex")
        JSONObject()
            .put("ok", true)
            .put("llamaServerAvailable", llama.isFile)
            .put("codexAvailable", isArm64() && codex.isFile)
            .put("codexReason", if (isArm64()) null else "Codex upstream has no ARMv7/32-bit release")
            .put("endpoint", "http://127.0.0.1:11434/v1")
            .put("modelAlias", "rift-local")
            .put("running", modelProcess.get()?.isAlive == true)
            .toString()
    } catch (t: Throwable) { errorJson(t) }

    @JavascriptInterface
    fun pickModel(): String = try {
        activity.chooseGgufModel()
        JSONObject().put("ok", true).toString()
    } catch (t: Throwable) { errorJson(t) }

    @JavascriptInterface
    fun startLocalModel(modelPath: String, contextSize: Int, threads: Int): String = try {
        val existing = modelProcess.get()
        if (existing?.isAlive == true) {
            return JSONObject().put("ok", true).put("alreadyRunning", true).toString()
        }

        val model = resolveRiftPath(modelPath)
        require(model.isFile) { "Model does not exist: $modelPath" }
        require(model.extension.equals("gguf", ignoreCase = true)) { "Model must be a GGUF file" }

        val server = bundledExecutable("llama-server")
        require(server.isFile) { "llama-server is not bundled for this ABI" }

        val safeContext = min(max(contextSize, 512), if (isArm64()) 16384 else 4096)
        val safeThreads = min(max(threads, 1), max(Runtime.getRuntime().availableProcessors(), 1))
        val command = listOf(
            server.absolutePath,
            "--model", model.absolutePath,
            "--alias", "rift-local",
            "--host", "127.0.0.1",
            "--port", "11434",
            "--ctx-size", safeContext.toString(),
            "--threads", safeThreads.toString()
        )

        modelLog.setLength(0)
        val process = ProcessBuilder(command)
            .directory(File(riftRoot, "models"))
            .redirectErrorStream(true)
            .start()
        modelProcess.set(process)

        thread(name = "rift-llama-log", isDaemon = true) {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    synchronized(modelLog) {
                        modelLog.append(line).append('\n')
                        if (modelLog.length > 64 * 1024) modelLog.delete(0, modelLog.length - 64 * 1024)
                    }
                }
            }
        }

        JSONObject()
            .put("ok", true)
            .put("pid", if (Build.VERSION.SDK_INT >= 26) process.pid() else -1)
            .put("endpoint", "http://127.0.0.1:11434/v1")
            .put("model", "rift-local")
            .put("contextSize", safeContext)
            .put("threads", safeThreads)
            .toString()
    } catch (t: Throwable) { errorJson(t) }

    @JavascriptInterface
    fun stopLocalModel(): String = try {
        val process = modelProcess.getAndSet(null)
        process?.destroy()
        JSONObject().put("ok", true).toString()
    } catch (t: Throwable) { errorJson(t) }

    @JavascriptInterface
    fun localModelLog(): String = try {
        val text = synchronized(modelLog) { modelLog.toString() }
        JSONObject().put("ok", true).put("log", text).toString()
    } catch (t: Throwable) { errorJson(t) }

    @JavascriptInterface
    fun runCodex(prompt: String, cwd: String): String = try {
        require(isArm64()) { "Codex upstream does not currently ship a 32-bit ARM binary" }
        require(modelProcess.get()?.isAlive == true) { "Start the local model first" }
        require(prompt.isNotBlank()) { "Prompt is empty" }

        val codex = bundledExecutable("codex")
        require(codex.isFile) { "Codex is not bundled in this APK" }
        val workDir = resolveRiftPath(cwd.ifBlank { "/projects" })
        require(workDir.isDirectory) { "Working directory does not exist" }

        val command = listOf(
            codex.absolutePath,
            "exec",
            "--oss",
            "--local-provider", "ollama",
            "--model", "rift-local",
            prompt
        )
        val builder = ProcessBuilder(command)
            .directory(workDir)
            .redirectErrorStream(false)
        builder.environment().apply {
            put("CODEX_OSS_BASE_URL", "http://127.0.0.1:11434/v1")
            put("HOME", File(riftRoot, "home").absolutePath)
            put("SHELL", "/system/bin/sh")
            put("PATH", "/system/bin:/system/xbin")
            put("TERM", "xterm-256color")
        }

        val process = builder.start()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outThread = thread { process.inputStream.bufferedReader().use { stdout.append(it.readText()) } }
        val errThread = thread { process.errorStream.bufferedReader().use { stderr.append(it.readText()) } }
        val exitCode = process.waitFor()
        outThread.join()
        errThread.join()

        JSONObject()
            .put("ok", exitCode == 0)
            .put("exitCode", exitCode)
            .put("stdout", stdout.toString())
            .put("stderr", stderr.toString())
            .toString()
    } catch (t: Throwable) { errorJson(t) }

    @JavascriptInterface
    fun listDir(path: String): String = try {
        val file = resolveRiftPath(path)
        require(file.isDirectory) { "Not a directory: $path" }
        val entries = JSONArray()
        file.listFiles()?.sortedBy { it.name.lowercase() }?.forEach {
            entries.put(JSONObject()
                .put("name", it.name)
                .put("path", "/" + it.relativeTo(riftRoot).invariantSeparatorsPath)
                .put("directory", it.isDirectory)
                .put("size", if (it.isFile) it.length() else 0))
        }
        JSONObject().put("ok", true).put("entries", entries).toString()
    } catch (t: Throwable) { errorJson(t) }

    @JavascriptInterface
    fun mkdir(path: String): String = try {
        val file = resolveRiftPath(path)
        require(file.mkdirs() || file.isDirectory) { "Could not create directory" }
        JSONObject().put("ok", true).toString()
    } catch (t: Throwable) { errorJson(t) }

    @JavascriptInterface
    fun readFileBase64(path: String): String = try {
        val file = resolveRiftPath(path)
        require(file.isFile) { "Not a file: $path" }
        JSONObject()
            .put("ok", true)
            .put("data", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
            .put("size", file.length())
            .toString()
    } catch (t: Throwable) { errorJson(t) }

    @JavascriptInterface
    fun writeFileBase64(path: String, data: String): String = try {
        val file = resolveRiftPath(path)
        file.parentFile?.mkdirs()
        file.writeBytes(Base64.decode(data, Base64.DEFAULT))
        JSONObject().put("ok", true).put("size", file.length()).toString()
    } catch (t: Throwable) { errorJson(t) }

    @JavascriptInterface
    fun remove(path: String, recursive: Boolean): String = try {
        val file = resolveRiftPath(path)
        require(file != riftRoot.canonicalFile) { "Cannot remove RiftFS root" }
        val removed = if (recursive && file.isDirectory) file.deleteRecursively() else file.delete()
        require(removed || !file.exists()) { "Could not remove path" }
        JSONObject().put("ok", true).toString()
    } catch (t: Throwable) { errorJson(t) }
}
