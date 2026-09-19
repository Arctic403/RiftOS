package com.riftos.app

import android.content.Context
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Process-owned RiftShell foundation that does not depend on Chromium/WebView.
 *
 * All supported command families execute through Android-native services or the bounded trusted
 * headless Rift++ runtime. There is no renderer fallback and no Android/Linux shell escape hatch.
 */
class RiftNativeShell(context: Context) : RiftShellExecutor {
    companion object {
        private const val MAX_TEXT_BYTES = 1024 * 1024L
        private const val MAX_COMMAND_BYTES = 2 * 1024 * 1024
        private const val MAX_ARGUMENTS = 16_384
        private const val MAX_TREE_ROWS = 5_000
        private const val SHELL_TIMEOUT_MS = 60_000L
        private const val WORKSPACE_ROOT = "/workspace/RiftOS-main"
    }

    private val appContext = context.applicationContext
    private val riftRoot = File(appContext.filesDir, "riftfs").apply { mkdirs() }.canonicalFile
    private val worker = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(8)
    )
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    private val headlessJs = RiftHeadlessJsRuntime(appContext)
    private val services = RiftNativeShellServices(appContext)
    private val riftBuild = RiftBuildLocalExecutor(appContext)
    private val nativeGit = RiftMcpRuntime.nativeGit(appContext)
    @Volatile private var closed = false

    private data class ShellOutcome(val output: String, val cwd: String, val result: Any? = null)
    override fun execute(command: String, cwd: String?, reply: (JSONObject) -> Unit) {
        if (closed) {
            reply(errorResult(cwd ?: "/", "Native RiftShell is closed"))
            return
        }
        val requestedCwd = normalizeDisplay(cwd ?: "/")
        RiftBoundedAsync.submit(
            executor = worker,
            watchdog = watchdog,
            timeoutMs = SHELL_TIMEOUT_MS,
            timeoutValue = {
                errorResult(requestedCwd, "Native RiftShell timed out after ${SHELL_TIMEOUT_MS}ms")
            },
            failureValue = { error ->
                errorResult(requestedCwd, error.message ?: error.javaClass.simpleName)
            },
            work = {
                RiftDeadline.check("native shell")
                val operation = runCatching { tokenize(command).firstOrNull()?.lowercase().orEmpty() }.getOrDefault("")
                val patchSession = RiftPatchSessions.begin(
                    appContext,
                    origin = "native-shell",
                    operation = operation.ifBlank { "shell" },
                    intent = operation.takeIf { it.isNotBlank() },
                    requestId = null,
                    rawPaths = shellMutationPaths(command, requestedCwd)
                )
                try {
                    val outcome = executeNative(command, requestedCwd)
                    RiftDeadline.check("native shell")
                    patchSession?.let { runCatching { RiftPatchSessions.commit(appContext, it) } }
                    JSONObject()
                        .put("ok", true)
                        .put("output", outcome.output)
                        .put("cwd", outcome.cwd)
                        .put("result", outcome.result ?: JSONObject.NULL)
                } catch (error: Throwable) {
                    patchSession?.let(RiftPatchSessions::abort)
                    errorResult(requestedCwd, error.message ?: error.javaClass.simpleName)
                }
            },
            reply = reply
        )
    }

    override fun close() {
        closed = true
        worker.shutdownNow()
        watchdog.shutdownNow()
    }

    private fun shellMutationPaths(raw: String, cwd: String): List<String> = runCatching {
        val args = tokenize(raw)
        val command = args.removeFirstOrNull()?.lowercase().orEmpty()
        when (command) {
            "write", "touch", "mkdir", "rm" -> args.firstOrNull()?.let { listOf(resolveDisplay(cwd, it)) }.orEmpty()
            "cp" -> if (args.size >= 2) listOf(resolveDisplay(cwd, args[1])) else emptyList()
            "mv" -> if (args.size >= 2) listOf(resolveDisplay(cwd, args[0]), resolveDisplay(cwd, args[1])) else emptyList()
            "zip" -> if (args.size >= 2) listOf(resolveDisplay(cwd, args[1])) else emptyList()
            "unzip" -> if (args.size >= 2) listOf(resolveDisplay(cwd, args[1])) else emptyList()
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    private fun executeNative(raw: String, cwd: String): ShellOutcome {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_COMMAND_BYTES) { "native shell command exceeds $MAX_COMMAND_BYTES UTF-8 bytes" }
        val args = tokenize(raw)
        val command = args.removeFirstOrNull()?.lowercase().orEmpty()
        if (command.isBlank()) return ShellOutcome("", cwd, nativeResult(command))

        return when (command) {
            "help" -> ShellOutcome(
                "Native RiftShell core\n" +
                    "help  pwd  cd  home  drives  df  sysinfo  native  uptime  version\n" +
                    "ps  kill <window-id>  apps  permissions [list|revoke <app-id> [capability|all]]\n" +
                    "ls [path]  tree [path]  stat <path>  cat <file>  head <file> [n]  tail <file> [n]\n" +
                    "write <file> <text>  touch <file>  mkdir <dir>  cp|mv <from> <to> [--force]  rm <path>\n" +
                    "zip <from> <archive.zip>  unzip <archive.zip> <folder>  open <app-id>  browser [url]\n" +
                    "workspace [cd|info|ls|status|push]\n" +
                    "riftbuild doctor|validate|plan|prepare-riftpp-v0|pack|sign|verify|install-proof|install-status|launch-proof|runs|artifacts   [NATIVE / BOUNDED]\n" +
                    "qjs help|version|eval|run   [BOUNDED HEADLESS QUICKJS / READ-ONLY RIFTFS]\n" +
                    "semx help|version|self-test|check|dump-graph|dump-plan   [SEMNEXIS V0 / HEADLESS QUICKJS]\n" +
                    "riftpp help|version|self-test|check|compile|inspect|run|exec|run-stateful|exec-stateful   [CORE V1 / HEADLESS QUICKJS]\n" +
                    "rift-tool gate0-verify   [ARCHIVAL EXACT-REFERENCE CHECK]\n" +
                    "rift-tool semantic-compat   [ONGOING SEMANTIC COMPATIBILITY CHECK]\n" +
                    "rift-tool text-model-benchmark   [FIXED UTF-16 / UTF-8 DEVICE BENCHMARK]\n" +
                    "rift-cli status|team|architecture|enable|disable|plan|riftpp|ir|tokenizer   [EXPERIMENTAL / OFF BY DEFAULT]\n" +
                    "Legacy shell-only services fail explicitly; no renderer compatibility fallback exists.",
                cwd,
                nativeResult(command)
            )
            "pwd" -> ShellOutcome(cwd, cwd, nativeResult(command))
            "cd" -> cdCommand(cwd, args)
            "home" -> ShellOutcome("/D:/Users/Default", "/D:/Users/Default", nativeResult(command))
            "ps" -> processListCommand(cwd)
            "kill" -> killCommand(cwd, args)
            "apps" -> appsCommand(cwd)
            "permissions" -> permissionsCommand(cwd, args)
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
                    .put("rendererFallback", false)
                ShellOutcome(info.toString(2), cwd, info)
            }
            "native" -> {
                val info = nativeResult(command)
                    .put("webViewRequired", false)
                    .put("rendererFallback", false)
                    .put("nativeCommands", JSONArray(listOf(
                        "help", "pwd", "cd", "home", "drives", "df", "sysinfo", "native", "uptime", "version", "ps", "kill", "apps", "permissions",
                        "ls", "tree", "stat", "cat", "head", "tail", "write", "touch", "mkdir", "cp", "mv", "rm", "zip", "unzip", "open", "browser", "workspace cd", "workspace info",
                        "workspace ls", "workspace status", "workspace push", "git", "chat", "devlab", "vortex", "vortex-agent", "riftos-agent", "riftllm-agent", "riftbuild", "qjs", "semx", "riftpp", "rift-tool", "rift-cli"
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
            "write" -> writeCommand(cwd, args)
            "touch" -> touchCommand(cwd, args)
            "mkdir" -> mkdirCommand(cwd, args)
            "cp" -> copyMoveCommand(cwd, args, move = false)
            "mv" -> copyMoveCommand(cwd, args, move = true)
            "rm" -> removeCommand(cwd, args)
            "zip" -> zipCommand(cwd, args)
            "unzip" -> unzipCommand(cwd, args)
            "browser" -> browserCommand(cwd, args)
            "open" -> openCommand(cwd, args)
            "clear" -> ShellOutcome("", cwd, nativeResult("clear").put("clear", true))
            "workspace" -> workspaceCommand(cwd, args)
            "git" -> nativeGit.execute(args, cwd).let { ShellOutcome(it.output, cwd, it.result) }
            "chat" -> services.chat(args, cwd).let { ShellOutcome(it.output, cwd, it.value) }
            "devlab" -> services.devLab(args, cwd).let { ShellOutcome(it.output, cwd, it.value) }
            "vortex" -> services.vortex(args, cwd).let { ShellOutcome(it.output, cwd, it.value) }
            "vortex-agent" -> services.vortexAgent(args).let { ShellOutcome(it.output, cwd, it.value) }
            "riftos-agent" -> services.riftOsAgent(args, cwd).let { ShellOutcome(it.output, cwd, it.value) }
            "riftllm-agent" -> services.riftLlm(args, cwd).let { ShellOutcome(it.output, cwd, it.value) }
            "riftbuild" -> {
                val value = riftBuild.executeShell(args, cwd)
                ShellOutcome(value.output, cwd, value.value)
            }
            "qjs" -> {
                val value = headlessJs.executeQuickJs(args, cwd)
                ShellOutcome(value.output, cwd, value.result)
            }
            "semx" -> {
                val value = headlessJs.executeSemnexis(args, cwd)
                ShellOutcome(value.output, cwd, value.result)
            }
            "riftpp" -> {
                val value = headlessJs.executeRiftpp(args, cwd)
                ShellOutcome(value.output, cwd, value.result)
            }
            "rift-tool" -> {
                val value = headlessJs.executeDeveloperTool(args)
                ShellOutcome(value.output, cwd, value.result)
            }
            "rift-cli" -> {
                val cli = RiftExperimentalCli.executeShell(appContext, args)
                ShellOutcome(cli.output, cwd, cli.result)
            }
            "mount", "umount" -> throw IllegalStateException("Legacy shell mount entry point is retired during native Files migration; no renderer fallback exists.")
            "rift" -> throw IllegalStateException("Legacy RiftLocalPlatform shell wrapper is retired; use native Git, Workspace Records, Dev Lab and fixed native build/training services.")
            else -> throw IllegalArgumentException("unsupported native RiftShell command: $command")
        }
    }

    private fun cdCommand(cwd: String, args: MutableList<String>): ShellOutcome {
        val target = resolveDisplay(cwd, args.firstOrNull() ?: "/D:/Users/Default")
        val file = resolveFile(target)
        require(file.isDirectory || RiftVolumePaths.isVolumeRoot(target)) { "not a directory: $target" }
        return ShellOutcome(target, target, nativeResult("cd").put("path", target))
    }

    private fun processListCommand(cwd: String): ShellOutcome {
        val rows = ArrayList<String>()
        val processes = JSONArray()
        fun protectedProcess(id: String, name: String) {
            rows += "protected\t$id\t$name"
            processes.put(JSONObject().put("id", id).put("name", name).put("protected", true))
        }
        protectedProcess("kernel", "RiftKernel")
        protectedProcess("desktop", "Rift Desktop")
        protectedProcess("shell", "Native RiftShell")

        val activity = RiftMcpRuntime.activeActivity()
        val state = activity?.nativeDesktopStateForShell()
        val windows = state?.optJSONArray("windows") ?: JSONArray()
        for (index in 0 until windows.length()) {
            val row = windows.optJSONObject(index) ?: continue
            val id = row.optString("id")
            if (id.isBlank()) continue
            val title = row.optString("title", id).ifBlank { id }
            val status = buildList {
                if (row.optBoolean("focused")) add("focused")
                if (row.optBoolean("minimized")) add("minimized")
                if (row.optBoolean("maximized")) add("maximized")
            }.ifEmpty { listOf("running") }.joinToString(",")
            rows += "$status\t$id\t$title"
            processes.put(
                JSONObject()
                    .put("id", id)
                    .put("name", title)
                    .put("protected", false)
                    .put("focused", row.optBoolean("focused"))
                    .put("minimized", row.optBoolean("minimized"))
                    .put("maximized", row.optBoolean("maximized"))
            )
        }
        return ShellOutcome(
            rows.joinToString("\n"),
            cwd,
            nativeResult("ps")
                .put("processes", processes)
                .put("activityAvailable", activity != null)
        )
    }

    private fun killCommand(cwd: String, args: MutableList<String>): ShellOutcome {
        val id = args.firstOrNull()?.trim().orEmpty()
        require(id.isNotBlank()) { "usage: kill <window-id>" }
        require(id !in setOf("kernel", "desktop", "shell")) { "protected native process cannot be terminated: $id" }
        val activity = RiftMcpRuntime.activeActivity()
            ?: throw IllegalStateException("RiftOS activity is not available")
        val before = activity.nativeDesktopStateForShell().optJSONArray("windows") ?: JSONArray()
        require((0 until before.length()).any { before.optJSONObject(it)?.optString("id") == id }) {
            "window task not found: $id"
        }
        activity.closeNativeWindowFromShell(id)
        return ShellOutcome(
            "terminated $id",
            cwd,
            nativeResult("kill").put("id", id).put("terminated", true)
        )
    }

    private fun appsCommand(cwd: String): ShellOutcome {
        val apps = JSONArray()
        val builtins = listOf(
            Triple("files", "Files", "native"),
            Triple("workspace-live", "Workspace Records", "native"),
            Triple("terminal", "RiftShell", "native"),
            Triple("browser", "RiftBrowser", "riftbrowser"),
            Triple("editor", "Editor", "native"),
            Triple("devlab", "Dev Lab", "native"),
            Triple("tasks", "Tasks", "native"),
            Triple("settings", "Settings", "native")
        )
        builtins.forEach { (id, name, owner) ->
            apps.put(JSONObject().put("id", id).put("name", name).put("owner", owner).put("installed", false))
        }

        val programs = resolveFile("/C:/Programs")
        programs.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() }?.forEach { directory ->
            val packageFile = File(directory, "package.json")
            if (!packageFile.isFile || packageFile.length() !in 1..(8L * 1024L * 1024L)) return@forEach
            val manifest = runCatching {
                JSONObject(packageFile.readText(Charsets.UTF_8)).optJSONObject("manifest")
            }.getOrNull() ?: return@forEach
            val id = manifest.optString("id").trim()
            if (id.isBlank() || directory.name != id || !id.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._-]{1,63}$"))) return@forEach
            apps.put(
                JSONObject()
                    .put("id", id)
                    .put("name", manifest.optString("name", id).ifBlank { id })
                    .put("owner", "riftbrowser-app")
                    .put("installed", true)
            )
        }

        val lines = ArrayList<String>()
        for (index in 0 until apps.length()) {
            val app = apps.getJSONObject(index)
            lines += "${app.optString("id")}\t${app.optString("name")}\t${app.optString("owner")}"
        }
        return ShellOutcome(lines.joinToString("\n"), cwd, nativeResult("apps").put("apps", apps))
    }

    private fun permissionsCommand(cwd: String, args: MutableList<String>): ShellOutcome {
        val prefs = appContext.getSharedPreferences("rift-native", Context.MODE_PRIVATE)
        val sub = args.removeFirstOrNull()?.lowercase() ?: "list"
        if (sub == "revoke") {
            val appId = args.removeFirstOrNull()?.trim().orEmpty()
            require(appId.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._-]{1,63}$"))) {
                "usage: permissions revoke <app-id> [capability|all]"
            }
            val capability = args.removeFirstOrNull()?.trim().orEmpty().ifBlank { "all" }
            require(args.isEmpty()) { "usage: permissions revoke <app-id> [capability|all]" }
            val key = "setting:permissions:$appId"
            if (capability.equals("all", ignoreCase = true)) {
                val existed = prefs.contains(key)
                prefs.edit().remove(key).apply()
                RiftMcpRuntime.activeActivity()?.onInstalledAppGrantRevokedFromShell(appId, "all")
                val value = nativeResult("permissions revoke").put("appId", appId).put("capability", "all").put("revoked", existed)
                return ShellOutcome(value.toString(2), cwd, value)
            }
            require(capability.matches(Regex("^[A-Za-z0-9._-]{1,64}$"))) { "Invalid capability name" }
            val raw = prefs.getString(key, null)
            val current = runCatching { JSONObject(raw ?: "{}").optJSONArray("value") }.getOrNull() ?: JSONArray()
            val next = linkedSetOf<String>()
            var removed = false
            for (index in 0 until current.length()) {
                val item = current.optString(index)
                if (item == capability) removed = true else if (item.isNotBlank()) next += item
            }
            if (next.isEmpty()) prefs.edit().remove(key).apply()
            else prefs.edit().putString(key, JSONObject().put("value", JSONArray(next.sorted())).put("modified", System.currentTimeMillis()).toString()).apply()
            if (removed) RiftMcpRuntime.activeActivity()?.onInstalledAppGrantRevokedFromShell(appId, capability)
            val value = nativeResult("permissions revoke").put("appId", appId).put("capability", capability).put("revoked", removed)
            return ShellOutcome(value.toString(2), cwd, value)
        }
        require(sub == "list") { "usage: permissions [list|revoke <app-id> [capability|all]]" }
        require(args.isEmpty()) { "usage: permissions [list|revoke <app-id> [capability|all]]" }
        val grants = JSONArray()
        prefs.all.keys.filter { it.startsWith("setting:permissions:") }.sorted().forEach { key ->
            val appId = key.removePrefix("setting:permissions:")
            val raw = prefs.getString(key, null) ?: return@forEach
            val value = runCatching { JSONObject(raw).optJSONArray("value") }.getOrNull() ?: JSONArray()
            grants.put(JSONObject().put("appId", appId).put("grants", value))
        }
        val value = nativeResult("permissions")
            .put("filesystemScope", "riftfs")
            .put("workspaceMcpScope", "workspace/")
            .put("gitCredentialStore", "android-keystore")
            .put("browserRendererOwner", "RiftBrowser")
            .put("installedAppGrants", grants)
        return ShellOutcome(value.toString(2), cwd, value)
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
            "push" -> {
                val gitArgs = mutableListOf("workspace", "push")
                gitArgs.addAll(args)
                val value = nativeGit.execute(gitArgs, cwd)
                ShellOutcome(value.output, cwd, value.result)
            }
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
            RiftDeadline.check("workspace status")
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
            RiftDeadline.check("shell tree")
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

    private fun writeCommand(cwd: String, args: MutableList<String>): ShellOutcome {
        val raw = args.removeFirstOrNull() ?: throw IllegalArgumentException("usage: write <file> <text>")
        val path = resolveDisplay(cwd, raw)
        val file = resolveFile(path)
        require(file != riftRoot && !RiftVolumePaths.isVolumeRoot(path)) { "write requires a file path" }
        val bytes = args.joinToString(" ").toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_TEXT_BYTES) { "native shell write exceeds $MAX_TEXT_BYTES bytes" }
        atomicWrite(file, bytes)
        return ShellOutcome("wrote $path", cwd, nativeResult("write").put("path", path).put("bytes", bytes.size))
    }

    private fun touchCommand(cwd: String, args: MutableList<String>): ShellOutcome {
        val path = resolveDisplay(cwd, args.firstOrNull() ?: throw IllegalArgumentException("usage: touch <file>"))
        val file = resolveFile(path)
        require(file != riftRoot && !RiftVolumePaths.isVolumeRoot(path)) { "touch requires a file path" }
        if (!file.exists()) atomicWrite(file, ByteArray(0))
        else require(file.isFile) { "touch target is not a file: $path" }
        return ShellOutcome("touched $path", cwd, nativeResult("touch").put("path", path))
    }

    private fun mkdirCommand(cwd: String, args: MutableList<String>): ShellOutcome {
        val path = resolveDisplay(cwd, args.firstOrNull() ?: throw IllegalArgumentException("usage: mkdir <dir>"))
        val file = resolveFile(path)
        require(file == riftRoot || file.mkdirs() || file.isDirectory) { "could not create directory: $path" }
        return ShellOutcome("created $path", cwd, nativeResult("mkdir").put("path", path))
    }

    private fun copyMoveCommand(cwd: String, args: MutableList<String>, move: Boolean): ShellOutcome {
        val force = args.remove("--force") || args.remove("-f")
        require(args.size == 2) { "usage: ${if (move) "mv" else "cp"} <from> <to> [--force]" }
        val fromPath = resolveDisplay(cwd, args[0])
        val toPath = resolveDisplay(cwd, args[1])
        val source = resolveFile(fromPath)
        val target = resolveFile(toPath)
        require(source.exists()) { "source not found: $fromPath" }
        require(source != riftRoot && !RiftVolumePaths.isVolumeRoot(fromPath)) { "cannot move/copy a RiftFS volume root" }
        require(target != riftRoot && !RiftVolumePaths.isVolumeRoot(toPath)) { "destination must not be a RiftFS volume root" }
        val sourceCanonical = source.canonicalFile
        val targetCanonical = target.canonicalFile
        require(sourceCanonical != targetCanonical) { "source and destination are the same path" }
        if (sourceCanonical.isDirectory) {
            require(!targetCanonical.path.startsWith(sourceCanonical.path + File.separator)) { "destination cannot be inside source directory" }
        }

        target.parentFile?.mkdirs()
        var backup: File? = null
        if (target.exists()) {
            require(force) { "destination exists: $toPath (use --force)" }
            backup = File(target.parentFile, ".${target.name}.shell-replace-${System.nanoTime()}")
            require(target.renameTo(backup)) { "could not stage existing destination: $toPath" }
        }

        try {
            if (move && source.renameTo(target)) {
                backup?.let { deleteConfined(it, cooperative = false) }
                return ShellOutcome("moved $fromPath -> $toPath", cwd, nativeResult("mv").put("from", fromPath).put("to", toPath))
            }

            copyConfined(source, target)
            if (move) require(deleteConfined(source)) { "copy succeeded but source cleanup failed" }

            backup?.let { deleteConfined(it, cooperative = false) }
            val verb = if (move) "moved" else "copied"
            return ShellOutcome("$verb $fromPath -> $toPath", cwd, nativeResult(if (move) "mv" else "cp").put("from", fromPath).put("to", toPath))
        } catch (error: Throwable) {
            runCatching {
                if (target.exists()) deleteConfined(target)
                if (backup != null && backup.exists()) {
                    require(backup.renameTo(target)) { "could not restore original destination" }
                }
            }.onFailure { rollback ->
                throw IllegalStateException(
                    "copy/move failed and destination rollback was incomplete: ${rollback.message}",
                    error
                )
            }
            throw error
        }
    }

    private fun removeCommand(cwd: String, args: MutableList<String>): ShellOutcome {
        val path = resolveDisplay(cwd, args.firstOrNull() ?: throw IllegalArgumentException("usage: rm <path>"))
        val file = resolveFile(path)
        require(file != riftRoot && !RiftVolumePaths.isVolumeRoot(path)) { "refusing to remove a RiftFS root" }
        require(file.exists()) { "path not found: $path" }
        require(deleteConfined(file)) { "could not remove $path" }
        return ShellOutcome("removed $path", cwd, nativeResult("rm").put("path", path))
    }

    private fun zipCommand(cwd: String, args: MutableList<String>): ShellOutcome {
        require(args.size >= 2) { "usage: zip <from> <archive.zip>" }
        val fromPath = resolveDisplay(cwd, args[0])
        val archivePath = resolveDisplay(cwd, args[1])
        val source = resolveFile(fromPath)
        val archive = resolveFile(archivePath)
        require(source.exists()) { "source not found: $fromPath" }
        require(source != riftRoot && !RiftVolumePaths.isVolumeRoot(fromPath)) { "archive a directory inside RiftFS, not a volume root" }
        require(archive.extension.equals("zip", true)) { "archive output must end in .zip" }
        require(!archive.exists()) { "archive already exists: $archivePath" }
        if (source.isDirectory) {
            val sourceCanonical = source.canonicalFile
            val archiveCanonical = archive.canonicalFile
            require(!archiveCanonical.path.startsWith(sourceCanonical.path + File.separator)) { "archive output cannot be inside source directory" }
        }
        archive.parentFile?.mkdirs()
        val temp = File(archive.parentFile, ".${archive.name}.tmp-${System.nanoTime()}")
        var entries = 0
        var total = 0L
        try {
            ZipOutputStream(temp.outputStream().buffered()).use { out ->
                val base = if (source.isDirectory) source else source.parentFile
                val files: Sequence<File> = if (source.isDirectory) source.walkTopDown() else sequenceOf(source)
                for (file in files) {
                    RiftDeadline.check("shell archive")
                    if (file == source && file.isDirectory) continue
                    val canonical = file.canonicalFile
                    require(canonical == source.canonicalFile || canonical.path.startsWith(source.canonicalPath + File.separator) || !source.isDirectory) { "archive source escaped root" }
                    val name = canonical.relativeTo(base).invariantSeparatorsPath
                    require(name.isNotBlank() && !name.startsWith("/") && name.split('/').none { it == ".." }) { "unsafe archive entry: $name" }
                    require(++entries <= 10_000) { "archive exceeds 10,000-entry limit" }
                    out.putNextEntry(ZipEntry(if (file.isDirectory) "$name/" else name))
                    if (file.isFile) {
                        total += file.length()
                        require(total <= 256L * 1024L * 1024L) { "archive input exceeds 256 MiB limit" }
                        file.inputStream().buffered().use { input ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                RiftDeadline.check("shell archive")
                                val read = input.read(buffer)
                                if (read <= 0) break
                                out.write(buffer, 0, read)
                            }
                        }
                    }
                    out.closeEntry()
                }
            }
            require(temp.renameTo(archive)) { "could not publish archive" }
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
        return ShellOutcome("archived $fromPath -> $archivePath", cwd, nativeResult("zip").put("from", fromPath).put("to", archivePath).put("entries", entries))
    }

    private fun unzipCommand(cwd: String, args: MutableList<String>): ShellOutcome {
        require(args.size >= 2) { "usage: unzip <archive.zip> <folder>" }
        val archivePath = resolveDisplay(cwd, args[0])
        val folderPath = resolveDisplay(cwd, args[1])
        val archive = resolveFile(archivePath)
        val destination = resolveFile(folderPath)
        require(archive.isFile) { "archive not found: $archivePath" }
        require(!destination.exists()) { "destination exists: $folderPath" }
        val stage = File(destination.parentFile, ".${destination.name}.rift-unzip-${System.nanoTime()}")
        require(stage.mkdirs()) { "could not create extraction stage" }
        var count = 0
        var total = 0L
        val seen = HashSet<String>()
        try {
            ZipFile(archive).use { zip ->
                val enumeration = zip.entries()
                while (enumeration.hasMoreElements()) {
                    RiftDeadline.check("shell extraction")
                    val entry = enumeration.nextElement()
                    val name = entry.name.replace('\\', '/')
                    require(name.isNotBlank() && !name.startsWith("/") && !Regex("^[A-Za-z]:").containsMatchIn(name)) { "unsafe ZIP entry: $name" }
                    val parts = name.split('/').filter { it.isNotBlank() }
                    require(parts.none { it == "." || it == ".." }) { "ZIP traversal rejected: $name" }
                    require(seen.add(name)) { "duplicate ZIP entry rejected: $name" }
                    require(++count <= 10_000) { "ZIP exceeds 10,000-entry limit" }
                    val target = File(stage, parts.joinToString(File.separator)).canonicalFile
                    require(target == stage.canonicalFile || target.path.startsWith(stage.canonicalPath + File.separator)) { "ZIP entry escaped extraction stage" }
                    if (entry.isDirectory) target.mkdirs()
                    else {
                        target.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            target.outputStream().buffered().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    RiftDeadline.check("shell extraction")
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    if (read > 0) {
                                        total += read
                                        require(total <= 256L * 1024L * 1024L) { "ZIP extraction exceeds 256 MiB limit" }
                                        output.write(buffer, 0, read)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            require(stage.renameTo(destination)) { "could not publish extracted folder" }
        } catch (error: Throwable) {
            runCatching { deleteConfined(stage, cooperative = false) }
            throw error
        }
        return ShellOutcome("extracted $archivePath -> $folderPath", cwd, nativeResult("unzip").put("from", archivePath).put("to", folderPath).put("entries", count).put("bytes", total))
    }

    private fun browserCommand(cwd: String, args: MutableList<String>): ShellOutcome {
        val url = args.joinToString(" ").trim().ifBlank { "https://chatgpt.com" }
        val activity = RiftMcpRuntime.activeActivity() ?: throw IllegalStateException("RiftOS activity is not available")
        activity.openBrowserFromNativeShell(url)
        return ShellOutcome("opened RiftBrowser window · $url", cwd, nativeResult("browser").put("url", url))
    }

    private fun openCommand(cwd: String, args: MutableList<String>): ShellOutcome {
        val rawId = args.firstOrNull()?.trim().orEmpty()
        require(rawId.isNotBlank() && args.size == 1) { "usage: open <app-id>" }
        val builtins = setOf("files", "workspace-live", "terminal", "browser", "editor", "devlab", "tasks", "settings")
        val normalized = rawId.lowercase()
        val id = if (normalized in builtins) normalized else rawId
        val activity = RiftMcpRuntime.activeActivity() ?: throw IllegalStateException("RiftOS activity is not available")
        activity.openAppFromNativeShell(id)
        return ShellOutcome("opened $id", cwd, nativeResult("open").put("id", id))
    }

    private fun copyConfined(source: File, target: File) {
        var total = 0L
        var count = 0
        if (source.isFile) {
            target.parentFile?.mkdirs()
            total = source.length()
            require(total <= 256L * 1024L * 1024L) { "copy exceeds 256 MiB limit" }
            copyFileBounded(source, target, overwrite = false)
            return
        }
        require(source.isDirectory) { "unsupported source type" }
        val sourceRoot = source.canonicalFile
        for (file in source.walkTopDown()) {
            RiftDeadline.check("shell copy")
            require(++count <= 10_000) { "copy exceeds 10,000-entry limit" }
            val canonicalSource = file.canonicalFile
            require(canonicalSource == sourceRoot || canonicalSource.path.startsWith(sourceRoot.path + File.separator)) { "copy source escaped root" }
            val relative = file.relativeTo(source)
            val out = File(target, relative.path).canonicalFile
            require(out == target.canonicalFile || out.path.startsWith(target.canonicalPath + File.separator)) { "copy path escaped destination" }
            if (file.isDirectory) out.mkdirs()
            else {
                total += file.length()
                require(total <= 256L * 1024L * 1024L) { "copy exceeds 256 MiB limit" }
                out.parentFile?.mkdirs()
                copyFileBounded(file, out, overwrite = false)
            }
        }
    }

    private fun copyFileBounded(source: File, target: File, overwrite: Boolean) {
        if (target.exists()) require(overwrite) { "destination exists: ${target.path}" }
        target.parentFile?.mkdirs()
        source.inputStream().buffered().use { input ->
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    RiftDeadline.check("shell copy")
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                }
            }
        }
        if (source.lastModified() > 0L) target.setLastModified(source.lastModified())
    }

    private fun deleteConfined(file: File, cooperative: Boolean = true): Boolean {
        val canonical = file.canonicalFile
        require(canonical != riftRoot && canonical.path.startsWith(riftRoot.path + File.separator)) { "refusing to delete outside confined RiftFS" }
        var count = 0
        fun remove(node: File): Boolean {
            if (cooperative) RiftDeadline.check("shell delete")
            require(++count <= 10_000) { "delete exceeds 10,000-entry limit" }
            if (node.isDirectory) {
                val children = node.listFiles() ?: throw IllegalStateException("could not read directory for deletion")
                children.forEach { child -> require(remove(child)) { "could not delete ${child.path}" } }
            }
            return node.delete()
        }
        return !canonical.exists() || remove(canonical)
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.tmp-${System.nanoTime()}")
        val backup = File(target.parentFile, ".${target.name}.backup-${System.nanoTime()}")
        temp.writeBytes(bytes)
        var backedUp = false
        try {
            if (target.exists()) {
                require(target.isFile) { "atomic write target is not a file" }
                require(target.renameTo(backup)) { "could not stage existing file for replacement" }
                backedUp = true
            }
            require(temp.renameTo(target)) { "atomic write publish failed" }
            if (backedUp) backup.delete()
        } catch (error: Throwable) {
            temp.delete()
            if (backedUp && !target.exists()) backup.renameTo(target)
            throw error
        }
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

    private fun normalizeDisplay(raw: String): String =
        RiftVolumePaths.normalizeDisplay(raw)

    private fun resolveFile(displayPath: String): File {
        val display = normalizeDisplay(displayPath)
        val relative = if (
            display.startsWith("/C:", true) ||
            display.startsWith("/D:", true)
        ) RiftVolumePaths.resolveRelative(display)
        else display.trimStart('/')
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
            require(out.size < MAX_ARGUMENTS) { "native shell argument count exceeds $MAX_ARGUMENTS" }
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
                RiftDeadline.check("git status hash")
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
