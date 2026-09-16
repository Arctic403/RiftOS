package com.riftos.app

import android.content.Context
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Process-owned RiftShell foundation that does not depend on Chromium/WebView.
 *
 * Patch-1 intentionally ports the read/control commands needed to diagnose and inspect RiftOS
 * while the legacy JS shell remains an optional compatibility fallback for command families that
 * have not migrated yet. Unsupported commands never execute Android/Linux shell commands.
 */
class RiftNativeShell(context: Context) : RiftShellExecutor {
    companion object {
        private const val MAX_TEXT_BYTES = 1024 * 1024L
        private const val MAX_TREE_ROWS = 5_000
        private const val WORKSPACE_ROOT = "/workspace/RiftOS-main"
    }

    private val appContext = context.applicationContext
    private val riftRoot = File(appContext.filesDir, "riftfs").apply { mkdirs() }.canonicalFile
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var compatibilityFallback: RiftShellExecutor? = null
    @Volatile private var closed = false

    private data class ShellOutcome(val output: String, val cwd: String, val result: Any? = null)
    private class UnsupportedNativeCommand : RuntimeException()

    fun setCompatibilityFallback(executor: RiftShellExecutor) {
        compatibilityFallback = executor
    }

    fun clearCompatibilityFallback(executor: RiftShellExecutor) {
        if (compatibilityFallback === executor) compatibilityFallback = null
    }

    fun compatibilityAvailable(): Boolean = compatibilityFallback != null

    override fun execute(command: String, cwd: String?, reply: (JSONObject) -> Unit) {
        if (closed) {
            reply(errorResult(cwd ?: "/", "Native RiftShell is closed"))
            return
        }
        val requestedCwd = normalizeDisplay(cwd ?: "/")
        worker.execute {
            try {
                val outcome = executeNative(command, requestedCwd)
                reply(JSONObject()
                    .put("ok", true)
                    .put("output", outcome.output)
                    .put("cwd", outcome.cwd)
                    .put("result", outcome.result ?: JSONObject.NULL))
            } catch (_: UnsupportedNativeCommand) {
                val fallback = compatibilityFallback
                if (fallback != null) {
                    fallback.execute(command, requestedCwd, reply)
                } else {
                    reply(errorResult(
                        requestedCwd,
                        "Command is not native yet and the compatibility shell is unavailable. Native RiftShell core remains online."
                    ))
                }
            } catch (error: Throwable) {
                reply(errorResult(requestedCwd, error.message ?: error.javaClass.simpleName))
            }
        }
    }

    override fun close() {
        closed = true
        compatibilityFallback = null
        worker.shutdownNow()
    }

    private fun executeNative(raw: String, cwd: String): ShellOutcome {
        val args = tokenize(raw)
        val command = args.removeFirstOrNull()?.lowercase().orEmpty()
        if (command.isBlank()) return ShellOutcome("", cwd, nativeResult(command))

        return when (command) {
            "help" -> ShellOutcome(
                "Native RiftShell core\n" +
                    "help  pwd  home  drives  df  sysinfo  native  uptime  version\n" +
                    "ls [path]  tree [path]  stat <path>  cat <file>  head <file> [n]  tail <file> [n]\n" +
                    "workspace [cd|info|ls|status]\n" +
                    "riftpp help|version|self-test|check|compile|inspect|run|exec   [CORE V1 / COMPATIBILITY SHELL]\n" +
                    "rift-cli status|team|architecture|enable|disable|plan|riftpp|ir|tokenizer   [EXPERIMENTAL / OFF BY DEFAULT]\n" +
                    "Remaining command families temporarily use the trusted compatibility shell while they migrate.",
                cwd,
                nativeResult(command)
            )
            "pwd" -> ShellOutcome(cwd, cwd, nativeResult(command))
            "home" -> ShellOutcome("/D:/Users/Default", "/D:/Users/Default", nativeResult(command))
            "drives" -> ShellOutcome(
                RiftVolumePaths.volumes.values.joinToString("\n") { "${it.letter}  ${it.label}  /${it.letter}" },
                cwd,
                nativeResult(command).put("volumes", RiftVolumePaths.describe())
            )
            "df" -> {
                val stat = StatFs(riftRoot.absolutePath)
                val total = stat.totalBytes
                val free = stat.availableBytes
                val used = (total - free).coerceAtLeast(0L)
                ShellOutcome(
                    "android-internal\nused $used\nquota $total\nfree $free",
                    cwd,
                    nativeResult(command).put("backend", "android-internal").put("usage", used).put("quota", total).put("free", free)
                )
            }
            "sysinfo" -> {
                val info = JSONObject()
                    .put("mode", "android-native")
                    .put("version", BuildConfig.VERSION_NAME)
                    .put("sourceSha", BuildConfig.RIFT_SOURCE_SHA)
                    .put("processUptimeMs", processUptimeMs())
                    .put("processors", Runtime.getRuntime().availableProcessors())
                    .put("nativeShell", true)
                    .put("webViewRequired", false)
                    .put("compatibilityFallback", compatibilityAvailable())
                ShellOutcome(info.toString(2), cwd, info)
            }
            "native" -> {
                val info = nativeResult(command)
                    .put("webViewRequired", false)
                    .put("compatibilityFallback", compatibilityAvailable())
                    .put("nativeCommands", JSONArray(listOf(
                        "help", "pwd", "home", "drives", "df", "sysinfo", "native", "uptime", "version",
                        "ls", "tree", "stat", "cat", "head", "tail", "workspace cd", "workspace info",
                        "workspace ls", "workspace status", "rift-cli"
                    )))
                ShellOutcome(info.toString(2), cwd, info)
            }
            "uptime" -> ShellOutcome("${processUptimeMs() / 1000L}s", cwd, nativeResult(command).put("milliseconds", processUptimeMs()))
            "version" -> ShellOutcome(
                "${BuildConfig.VERSION_NAME} · ${BuildConfig.RIFT_SOURCE_SHA}",
                cwd,
                nativeResult(command).put("version", BuildConfig.VERSION_NAME).put("sourceSha", BuildConfig.RIFT_SOURCE_SHA)
            )
            "ls" -> listCommand(cwd, args.firstOrNull(), recursive = false)
            "tree" -> listCommand(cwd, args.firstOrNull(), recursive = true)
            "stat" -> {
                val path = resolveDisplay(cwd, args.firstOrNull() ?: throw IllegalArgumentException("usage: stat <path>"))
                val file = resolveFile(path)
                require(file.exists()) { "path not found: $path" }
                val value = fileStat(path, file)
                ShellOutcome(value.toString(2), cwd, nativeResult(command).put("stat", value))
            }
            "cat" -> textCommand(cwd, args, "cat")
            "head" -> textCommand(cwd, args, "head")
            "tail" -> textCommand(cwd, args, "tail")
            "workspace" -> workspaceCommand(cwd, args)
            "rift-cli" -> {
                val cli = RiftExperimentalCli.executeShell(appContext, args)
                ShellOutcome(cli.output, cwd, cli.result)
            }
            else -> throw UnsupportedNativeCommand()
        }
    }

    private fun workspaceCommand(cwd: String, args: MutableList<String>): ShellOutcome {
        val sub = args.removeFirstOrNull()?.lowercase() ?: "info"
        return when (sub) {
            "cd" -> ShellOutcome("/D:/Workspace", "/D:/Workspace", nativeResult("workspace cd"))
            "info" -> {
                val root = resolveFile("/D:/Workspace")
                val project = resolveFile(WORKSPACE_ROOT)
                val value = JSONObject()
                    .put("backend", "native-kotlin")
                    .put("webViewRequired", false)
                    .put("path", "/D:/Workspace")
                    .put("exists", root.isDirectory)
                    .put("riftOsProject", project.isDirectory)
                ShellOutcome(value.toString(2), cwd, value)
            }
            "ls" -> {
                val relative = args.firstOrNull().orEmpty().trim().trimStart('/')
                val path = if (relative.isBlank()) "/D:/Workspace" else resolveDisplay("/D:/Workspace", relative)
                listCommand(cwd, path, recursive = false, alreadyResolved = true)
            }
            "status" -> workspaceStatus(cwd)
            "push" -> throw UnsupportedNativeCommand()
            else -> throw IllegalArgumentException("usage: workspace [cd|info|ls [path]|status]")
        }
    }

    private fun workspaceStatus(cwd: String): ShellOutcome {
        val root = resolveFile(WORKSPACE_ROOT)
        require(root.isDirectory) { "Workspace project folder not found: $WORKSPACE_ROOT" }
        val metaFile = File(root, ".riftgit.json")
        require(metaFile.isFile) { "Workspace Git metadata is missing" }
        val meta = JSONObject(metaFile.readText(Charsets.UTF_8))
        val tracked = meta.optJSONObject("tracked") ?: JSONObject()
        val local = LinkedHashMap<String, File>()
        root.walkTopDown().onEnter { dir -> dir == root || dir.name != ".git" }.forEach { file ->
            if (!file.isFile) return@forEach
            val relative = file.relativeTo(root).invariantSeparatorsPath
            if (relative == ".riftgit.json" || relative == ".git" || relative.startsWith(".git/")) return@forEach
            local[relative] = file
        }

        val modified = ArrayList<String>()
        val deleted = ArrayList<String>()
        val keys = tracked.keys()
        while (keys.hasNext()) {
            val path = keys.next()
            val baseline = tracked.optJSONObject(path)?.optString("blobSha").orEmpty()
            val file = local.remove(path)
            if (file == null) deleted += path
            else if (gitBlobSha(file) != baseline) modified += path
        }
        val untracked = local.keys.toList()
        val output = buildString {
            append("On ").append(meta.optString("full", "Arctic403/RiftOS"))
                .append(" / ").append(meta.optString("branch", "main"))
                .append("\nroot ").append(meta.optString("root", WORKSPACE_ROOT))
            if (modified.isEmpty() && deleted.isEmpty() && untracked.isEmpty()) append("\nworking tree clean")
            modified.forEach { append("\n M ").append(it) }
            deleted.forEach { append("\n D ").append(it) }
            untracked.forEach { append("\n?? ").append(it) }
        }
        val result = nativeResult("workspace status")
            .put("headSha", meta.optString("headSha"))
            .put("modified", JSONArray(modified))
            .put("deleted", JSONArray(deleted))
            .put("untracked", JSONArray(untracked))
            .put("clean", modified.isEmpty() && deleted.isEmpty() && untracked.isEmpty())
        return ShellOutcome(output, cwd, result)
    }

    private fun listCommand(
        cwd: String,
        rawPath: String?,
        recursive: Boolean,
        alreadyResolved: Boolean = false
    ): ShellOutcome {
        val path = if (alreadyResolved) normalizeDisplay(rawPath ?: cwd) else resolveDisplay(cwd, rawPath ?: cwd)
        if (RiftVolumePaths.isVolumeRoot(path)) return listVolumeRoot(cwd, path, recursive)

        val file = resolveFile(path)
        require(file.exists()) { "path not found: $path" }
        val rows = ArrayList<String>()
        if (file.isFile) {
            rows += "-\t$path"
        } else if (recursive) {
            appendTreeRows(rows, path, file)
        } else {
            file.listFiles()?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))?.forEach { child ->
                rows += "${if (child.isDirectory) "d" else "-"}\t${joinDisplay(path, child.name)}"
            }
        }
        return listOutcome(cwd, path, recursive, rows)
    }

    private fun listVolumeRoot(cwd: String, path: String, recursive: Boolean): ShellOutcome {
        val volume = RiftVolumePaths.volume(path) ?: throw IllegalArgumentException("Unknown RiftOS volume: $path")
        val rows = ArrayList<String>()
        val seen = linkedSetOf<String>()

        for (name in volume.roots.keys) {
            if (rows.size >= MAX_TREE_ROWS) break
            val childPath = joinDisplay(path, name)
            if (!seen.add(childPath)) continue
            rows += "d\t$childPath"
            if (recursive && rows.size < MAX_TREE_ROWS) {
                val childFile = resolveFile(childPath)
                if (childFile.isDirectory) appendTreeRows(rows, childPath, childFile)
            }
        }

        val backing = resolveFile(path)
        backing.listFiles()?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))?.forEach { child ->
            if (rows.size >= MAX_TREE_ROWS) return@forEach
            val childPath = joinDisplay(path, child.name)
            if (!seen.add(childPath)) return@forEach
            rows += "${if (child.isDirectory) "d" else "-"}\t$childPath"
            if (recursive && child.isDirectory && rows.size < MAX_TREE_ROWS) appendTreeRows(rows, childPath, child)
        }
        return listOutcome(cwd, path, recursive, rows)
    }

    private fun appendTreeRows(rows: ArrayList<String>, displayRoot: String, root: File) {
        if (!root.isDirectory || rows.size >= MAX_TREE_ROWS) return
        for (child in root.walkTopDown().drop(1)) {
            if (rows.size >= MAX_TREE_ROWS) break
            val relative = child.relativeTo(root).invariantSeparatorsPath
            rows += "${if (child.isDirectory) "d" else "-"}\t${joinDisplay(displayRoot, relative)}"
        }
    }

    private fun listOutcome(cwd: String, path: String, recursive: Boolean, rows: ArrayList<String>): ShellOutcome {
        val result = nativeResult(if (recursive) "tree" else "ls")
            .put("path", path)
            .put("rows", rows.size)
            .put("truncated", recursive && rows.size >= MAX_TREE_ROWS)
            .put("virtualVolumeRoot", RiftVolumePaths.isVolumeRoot(path))
        return ShellOutcome(rows.joinToString("\n").ifBlank { "(empty)" }, cwd, result)
    }

    private fun textCommand(cwd: String, args: MutableList<String>, mode: String): ShellOutcome {
        val raw = args.firstOrNull() ?: throw IllegalArgumentException("usage: $mode <file>${if (mode == "cat") "" else " [count]"}")
        val path = resolveDisplay(cwd, raw)
        val file = resolveFile(path)
        require(file.isFile) { "file not found: $path" }
        require(file.length() <= MAX_TEXT_BYTES) { "File is too large for native RiftShell text output (${file.length()} bytes)" }
        val text = file.readText(Charsets.UTF_8)
        val output = when (mode) {
            "head" -> text.lines().take((args.getOrNull(1)?.toIntOrNull() ?: 10).coerceIn(1, 10_000)).joinToString("\n")
            "tail" -> text.lines().takeLast((args.getOrNull(1)?.toIntOrNull() ?: 10).coerceIn(1, 10_000)).joinToString("\n")
            else -> text
        }
        return ShellOutcome(output, cwd, nativeResult(mode).put("path", path).put("bytes", file.length()))
    }

    private fun fileStat(path: String, file: File): JSONObject = JSONObject()
        .put("path", path)
        .put("kind", if (file.isDirectory) "directory" else "file")
        .put("size", if (file.isFile) file.length() else 0L)
        .put("modified", file.lastModified())
        .put("name", file.name)

    private fun resolveDisplay(cwd: String, raw: String): String {
        var value = raw.trim().replace('\\', '/')
        if (value.isBlank()) return normalizeDisplay(cwd)
        if (value == "~" || value.startsWith("~/")) value = "/D:/Users/Default${value.drop(1)}"
        if (Regex("^[A-Za-z]:($|/)").containsMatchIn(value)) value = "/$value"
        if (!value.startsWith('/')) value = "${normalizeDisplay(cwd).trimEnd('/')}/$value"
        return normalizeDisplay(value)
    }

    private fun normalizeDisplay(raw: String): String {
        var value = raw.trim().replace('\\', '/')
        if (Regex("^[A-Za-z]:($|/)").containsMatchIn(value)) value = "/$value"
        val parts = ArrayList<String>()
        for (piece in value.split('/')) {
            if (piece.isBlank() || piece == ".") continue
            require(!piece.contains('\u0000')) { "Invalid RiftFS path" }
            if (piece == "..") {
                if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                continue
            }
            parts += if (parts.isEmpty() && piece.matches(Regex("[A-Za-z]:"))) piece.uppercase() else piece
        }
        return "/" + parts.joinToString("/")
    }

    private fun resolveFile(displayPath: String): File {
        val normalized = normalizeDisplay(displayPath)
        val relative = when {
            normalized == "/" -> ""
            normalized.startsWith("/C:", ignoreCase = true) || normalized.startsWith("/D:", ignoreCase = true) ->
                RiftVolumePaths.resolveRelative(normalized)
            else -> normalized.trimStart('/')
        }
        val target = if (relative.isBlank()) riftRoot else File(riftRoot, relative).canonicalFile
        require(target == riftRoot || target.path.startsWith(riftRoot.path + File.separator)) { "Path escaped RiftFS" }
        return target
    }

    private fun joinDisplay(base: String, child: String): String {
        val left = normalizeDisplay(base).trimEnd('/')
        return normalizeDisplay("$left/${child.trimStart('/')}")
    }

    private fun tokenize(raw: String): MutableList<String> {
        val out = ArrayList<String>()
        val regex = Regex("\"([^\"]*)\"|'([^']*)'|([^\\s]+)")
        regex.findAll(raw).forEach { match ->
            out += match.groups[1]?.value ?: match.groups[2]?.value ?: match.groups[3]?.value.orEmpty()
        }
        return out
    }

    private fun gitBlobSha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${file.length()}\u0000".toByteArray(Charsets.UTF_8))
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun processUptimeMs(): Long = if (android.os.Build.VERSION.SDK_INT >= 24) {
        (SystemClock.uptimeMillis() - Process.getStartUptimeMillis()).coerceAtLeast(0L)
    } else SystemClock.uptimeMillis()

    private fun nativeResult(command: String): JSONObject = JSONObject()
        .put("backend", "native-kotlin")
        .put("command", command)
        .put("webViewRequired", false)

    private fun errorResult(cwd: String, message: String): JSONObject = JSONObject()
        .put("ok", false)
        .put("output", "")
        .put("cwd", normalizeDisplay(cwd))
        .put("error", message)
        .put("result", nativeResult("error"))
}
