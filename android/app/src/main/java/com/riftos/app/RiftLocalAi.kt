package com.riftos.app

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

class RiftLocalAi(private val activity: Activity) {
    companion object {
        private const val PORT = 39117
        private const val MODEL_ALIAS = "rift-gpt-oss"
        private const val LLAMA_COMMIT = "304665fe7ac957df95e3ff8c8c4ffdf92dd6ffa3"
        private const val SOURCE_MODEL = "openai/gpt-oss-20b"
        private const val GGUF_MODEL = "ggml-org/gpt-oss-20b-GGUF"
        private const val MODEL_URL = "https://huggingface.co/ggml-org/gpt-oss-20b-GGUF/resolve/main/gpt-oss-20b-MXFP4.gguf?download=true"
        private const val RECOMMENDED_MEMORY_BYTES = 16L * 1024L * 1024L * 1024L
    }

    private data class ChatJob(
        val id: String,
        val created: Long = System.currentTimeMillis(),
        @Volatile var state: String = "queued",
        @Volatile var response: JSONObject? = null,
        @Volatile var error: String? = null
    )

    private val riftRoot = File(activity.filesDir, "riftfs").apply { mkdirs() }
    private val modelRoot = File(riftRoot, "models").apply { mkdirs() }
    private val downloadRoot = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.apply { mkdirs() }
    private val nativeRoot = File(activity.applicationInfo.nativeLibraryDir)
    private val modelProcess = AtomicReference<Process?>(null)
    private val modelLog = StringBuilder()
    private val chatExecutor = Executors.newSingleThreadExecutor()
    private val jobs = ConcurrentHashMap<String, ChatJob>()

    @Volatile
    private var activeModelId: String? = null

    private fun serverFile(): File = File(nativeRoot, "libllamaserver.so")

    private fun safeChild(root: File, relative: String): File {
        val clean = relative.replace('\\', '/').trim('/')
        require(clean.isNotBlank()) { "Model path is empty" }
        require(!clean.split('/').any { it.isBlank() || it == "." || it == ".." }) { "Invalid model path" }
        val rootCanonical = root.canonicalFile
        val candidate = File(rootCanonical, clean).canonicalFile
        require(candidate.path.startsWith(rootCanonical.path + File.separator)) { "Model path escaped its storage root" }
        return candidate
    }

    private fun resolveModel(modelId: String): File {
        val id = modelId.trim()
        return when {
            id.startsWith("riftfs:") -> safeChild(modelRoot, id.removePrefix("riftfs:").removePrefix("models/"))
            id.startsWith("downloads:") -> {
                val root = downloadRoot ?: throw IllegalStateException("Android app downloads are unavailable")
                safeChild(root, id.removePrefix("downloads:"))
            }
            else -> safeChild(modelRoot, id)
        }
    }

    private fun modelId(file: File): String {
        val candidate = file.canonicalFile
        val internal = modelRoot.canonicalFile
        if (candidate.path.startsWith(internal.path + File.separator)) {
            return "riftfs:" + candidate.relativeTo(internal).invariantSeparatorsPath
        }
        val downloads = downloadRoot?.canonicalFile
        if (downloads != null && candidate.path.startsWith(downloads.path + File.separator)) {
            return "downloads:" + candidate.relativeTo(downloads).invariantSeparatorsPath
        }
        throw IllegalArgumentException("Model is outside RiftOS model storage")
    }

    private fun validateGguf(file: File) {
        require(file.isFile) { "Model does not exist: ${file.name}" }
        require(file.extension.equals("gguf", ignoreCase = true)) { "Local model must be a GGUF file" }
        require(file.length() > 4L) { "GGUF model file is empty" }
        val magic = ByteArray(4)
        RandomAccessFile(file, "r").use { raf -> raf.readFully(magic) }
        require(magic.contentEquals(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))) {
            "Selected file is not a GGUF model"
        }
    }

    private fun memoryInfo(): ActivityManager.MemoryInfo {
        val manager = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
    }

    fun info(): JSONObject {
        val mem = memoryInfo()
        val storage = StatFs(activity.filesDir.absolutePath)
        val process = modelProcess.get()
        val server = serverFile()
        return JSONObject()
            .put("available", server.isFile)
            .put("runtime", "llama.cpp")
            .put("llamaCommit", LLAMA_COMMIT)
            .put("sourceModel", SOURCE_MODEL)
            .put("ggufModel", GGUF_MODEL)
            .put("modelDownloadUrl", MODEL_URL)
            .put("inference", "on-device")
            .put("networkRequiredForInference", false)
            .put("serverHost", "127.0.0.1")
            .put("serverPort", PORT)
            .put("modelAlias", MODEL_ALIAS)
            .put("running", process?.isAlive == true)
            .put("ready", process?.isAlive == true && probeReady())
            .put("activeModel", activeModelId ?: JSONObject.NULL)
            .put("memoryTotalBytes", mem.totalMem)
            .put("memoryAvailableBytes", mem.availMem)
            .put("recommendedMemoryBytes", RECOMMENDED_MEMORY_BYTES)
            .put("storageAvailableBytes", storage.availableBytes)
            .put("cpuCores", Runtime.getRuntime().availableProcessors())
            .put("models", models())
    }

    fun models(): JSONArray {
        val out = JSONArray()
        val seen = HashSet<String>()

        fun addRoot(root: File?, source: String) {
            if (root == null || !root.isDirectory) return
            root.walkTopDown().maxDepth(3)
                .filter { it.isFile && it.extension.equals("gguf", ignoreCase = true) }
                .sortedBy { it.name.lowercase() }
                .forEach { file ->
                    val id = runCatching { modelId(file) }.getOrNull() ?: return@forEach
                    if (!seen.add(id)) return@forEach
                    out.put(
                        JSONObject()
                            .put("id", id)
                            .put("name", file.name)
                            .put("source", source)
                            .put("size", file.length())
                            .put("modified", file.lastModified())
                    )
                }
        }

        addRoot(modelRoot, "riftfs")
        addRoot(downloadRoot, "rift-browser-downloads")
        return out
    }

    fun start(args: JSONObject): JSONObject {
        val model = resolveModel(args.getString("model"))
        validateGguf(model)

        val server = serverFile()
        require(server.isFile) { "llama.cpp local runtime is not bundled for this device ABI" }
        require(server.canExecute()) { "Bundled llama.cpp runtime is not executable" }

        val requestedId = modelId(model)
        val current = modelProcess.get()
        if (current?.isAlive == true && activeModelId == requestedId) {
            return JSONObject().put("running", true).put("alreadyRunning", true).put("model", requestedId).put("ready", probeReady())
        }
        if (current?.isAlive == true) stop()

        val cores = max(Runtime.getRuntime().availableProcessors(), 1)
        val defaultThreads = max(1, cores - 1)
        val threads = args.optInt("threads", defaultThreads).coerceIn(1, cores)
        val contextSize = args.optInt("contextSize", 2048).coerceIn(512, 8192)

        val tmp = File(activity.cacheDir, "rift-local-ai").apply { mkdirs() }
        val command = listOf(
            server.absolutePath,
            "--model", model.absolutePath,
            "--alias", MODEL_ALIAS,
            "--host", "127.0.0.1",
            "--port", PORT.toString(),
            "--ctx-size", contextSize.toString(),
            "--threads", threads.toString()
        )

        synchronized(modelLog) { modelLog.setLength(0) }
        val process = ProcessBuilder(command)
            .directory(model.parentFile)
            .redirectErrorStream(true)
            .apply {
                environment()["HOME"] = File(riftRoot, "home").apply { mkdirs() }.absolutePath
                environment()["TMPDIR"] = tmp.absolutePath
                environment()["LD_LIBRARY_PATH"] = nativeRoot.absolutePath
            }
            .start()

        modelProcess.set(process)
        activeModelId = requestedId
        thread(name = "rift-gpt-oss-log", isDaemon = true) {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(modelLog) {
                            modelLog.append(line).append('\n')
                            if (modelLog.length > 128 * 1024) modelLog.delete(0, modelLog.length - 128 * 1024)
                        }
                    }
                }
            }
        }

        return JSONObject()
            .put("running", true)
            .put("starting", true)
            .put("model", requestedId)
            .put("contextSize", contextSize)
            .put("threads", threads)
            .put("ready", false)
    }

    fun stop(): JSONObject {
        val process = modelProcess.getAndSet(null)
        if (process != null) {
            runCatching { process.destroy() }
        }
        activeModelId = null
        jobs.clear()
        return JSONObject().put("running", false)
    }

    fun status(): JSONObject {
        val process = modelProcess.get()
        return JSONObject()
            .put("running", process?.isAlive == true)
            .put("ready", process?.isAlive == true && probeReady())
            .put("model", activeModelId ?: JSONObject.NULL)
    }

    fun submitChat(args: JSONObject): JSONObject {
        val process = modelProcess.get()
        require(process?.isAlive == true) { "Start the local model first" }
        require(probeReady()) { "The local model is still loading" }

        val messages = when {
            args.has("messages") -> JSONArray(args.getJSONArray("messages").toString())
            args.optString("prompt").isNotBlank() -> JSONArray().put(
                JSONObject().put("role", "user").put("content", args.getString("prompt"))
            )
            else -> throw IllegalArgumentException("Provide messages or prompt")
        }
        require(messages.length() in 1..128) { "messages must contain 1 to 128 entries" }

        val payload = JSONObject()
            .put("model", MODEL_ALIAS)
            .put("messages", messages)
            .put("stream", false)

        if (args.has("temperature")) {
            val temperature = args.optDouble("temperature", 0.7).coerceIn(0.0, 2.0)
            payload.put("temperature", temperature)
        }
        if (args.has("maxTokens")) {
            payload.put("max_tokens", args.optInt("maxTokens", 512).coerceIn(1, 4096))
        }

        val id = "rai-${UUID.randomUUID()}"
        val job = ChatJob(id)
        jobs[id] = job
        trimJobs()

        chatExecutor.execute {
            job.state = "running"
            try {
                job.response = postJson("/v1/chat/completions", payload)
                job.state = "completed"
            } catch (error: Throwable) {
                job.error = error.message ?: error.javaClass.simpleName
                job.state = "failed"
            }
        }

        return JSONObject().put("jobId", id).put("state", job.state)
    }

    fun chatResult(args: JSONObject): JSONObject {
        val id = args.getString("jobId")
        val job = jobs[id] ?: throw IllegalArgumentException("Unknown local AI job")
        val result = JSONObject()
            .put("jobId", id)
            .put("state", job.state)
            .put("created", job.created)
        job.response?.let { result.put("response", it) }
        job.error?.let { result.put("error", it) }
        if (args.optBoolean("consume", false) && job.state in setOf("completed", "failed")) jobs.remove(id)
        return result
    }

    fun log(): JSONObject {
        val text = synchronized(modelLog) { modelLog.toString() }
        return JSONObject().put("log", text)
    }

    fun shutdown() {
        stop()
        chatExecutor.shutdownNow()
    }

    private fun trimJobs() {
        if (jobs.size <= 48) return
        jobs.values.sortedBy { it.created }.take(jobs.size - 48).forEach { jobs.remove(it.id) }
    }

    private fun probeReady(): Boolean = runCatching {
        val connection = (URL("http://127.0.0.1:$PORT/health").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 500
            readTimeout = 500
            useCaches = false
        }
        try { connection.responseCode == 200 } finally { connection.disconnect() }
    }.getOrDefault(false)

    private fun postJson(path: String, payload: JSONObject): JSONObject {
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        val connection = (URL("http://127.0.0.1:$PORT$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 2_000
            readTimeout = 15 * 60 * 1000
            useCaches = false
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setFixedLengthStreamingMode(bytes.size)
        }
        return try {
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            require(code in 200..299) { "Local model server HTTP $code: ${text.take(1200)}" }
            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }
}
