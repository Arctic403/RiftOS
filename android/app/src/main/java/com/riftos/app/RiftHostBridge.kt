package com.riftos.app

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.util.Base64
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class RiftHostBridge(private val context: Context) {
    private val riftRoot = File(context.filesDir, "riftfs")
    private val binRoot = File(context.filesDir, "riftbin")

    init {
        riftRoot.mkdirs()
        binRoot.mkdirs()
        listOf("system", "home", "tmp", "apps").forEach { File(riftRoot, it).mkdirs() }
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

    private fun errorJson(t: Throwable): String = JSONObject()
        .put("ok", false)
        .put("error", t.message ?: t.javaClass.simpleName)
        .toString()

    @JavascriptInterface
    fun deviceInfo(): String = try {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val stat = StatFs(context.filesDir.absolutePath)
        JSONObject()
            .put("ok", true)
            .put("platform", "android")
            .put("sdk", Build.VERSION.SDK_INT)
            .put("model", Build.MODEL)
            .put("manufacturer", Build.MANUFACTURER)
            .put("architecture", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
            .put("cpuCores", Runtime.getRuntime().availableProcessors())
            .put("memoryTotalBytes", mem.totalMem)
            .put("memoryAvailableBytes", mem.availMem)
            .put("storageTotalBytes", stat.totalBytes)
            .put("storageAvailableBytes", stat.availableBytes)
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

    @JavascriptInterface
    fun runBundledProcess(executable: String, argsJson: String): String = try {
        val executableFile = File(binRoot, executable.removePrefix("/")).canonicalFile
        val root = binRoot.canonicalFile
        require(executableFile.path.startsWith(root.path + File.separator)) { "Executable escapes Rift bin" }
        require(executableFile.isFile && executableFile.canExecute()) { "Bundled executable not available" }

        val argsArray = JSONArray(argsJson)
        val command = mutableListOf(executableFile.absolutePath)
        for (i in 0 until argsArray.length()) command += argsArray.getString(i)

        val process = ProcessBuilder(command)
            .directory(riftRoot)
            .redirectErrorStream(false)
            .start()
        val exitCode = process.waitFor()
        JSONObject()
            .put("ok", true)
            .put("exitCode", exitCode)
            .put("stdout", process.inputStream.bufferedReader().readText())
            .put("stderr", process.errorStream.bufferedReader().readText())
            .toString()
    } catch (t: Throwable) { errorJson(t) }
}
