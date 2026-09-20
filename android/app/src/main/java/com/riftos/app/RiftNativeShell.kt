package com.riftos.app

import android.content.Context
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
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
        private const val MAX_CLI_SHELL_JOBS = 16
        private const val CLI_SHELL_JOB_RETENTION_MS = 5 * 60 * 1000L
        private const val MAX_CLI_SHELL_RETAINED_RESULT_BYTES = 2 * 1024 * 1024
        private const val MAX_CLI_BATCH_STEPS = 16
        private const val MAX_CLI_BATCH_BYTES = 128 * 1024
        private const val MAX_CLI_BATCH_STEP_BYTES = 64 * 1024
        private const val MAX_CLI_BATCH_STEP_RESULT_BYTES = 128 * 1024
        private val CLI_BATCH_ALLOWED_SHELL_COMMANDS = setOf(
            "help", "pwd", "cd", "home", "ps", "kill", "apps", "permissions",
            "drives", "df", "sysinfo", "native", "uptime", "version",
            "ls", "tree", "stat", "cat", "head", "tail",
            "write", "touch", "mkdir", "cp", "mv", "rm", "zip", "unzip",
            "browser", "open", "clear", "workspace", "git", "chat", "devlab",
            "vortex", "vortex-agent", "riftos-agent", "riftllm-agent", "codynex",
            "riftbuild", "qjs", "semx", "riftpp", "rift-tool"
        )
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
    private val cliWorker = ThreadPoolExecutor(
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
    private data class CliShellJob(
        val id: String,
        val requestId: String,
        val operation: String,
        val cwd: String,
        val createdAt: Long,
        @Volatile var updatedAt: Long,
        @Volatile var status: String,
        @Volatile var cancelRequested: Boolean = false,
        @Volatile var output: String = "",
        @Volatile var result: Any? = null,
        @Volatile var error: String? = null,
        @Volatile var future: Future<*>? = null,
        val lane: String = "shell"
    )

    private data class CliBatchStep(
        val id: String,
        val kind: String,
        val toolName: String? = null,
        val toolArgs: JSONObject? = null,
        val shellCommand: String? = null
    )

    private data class CliBatchPlan(
        val mode: String,
        val failurePolicy: String,
        val steps: List<CliBatchStep>
    )

    private val cliShellJobs = ConcurrentHashMap<String, CliShellJob>()

    private fun emitCliShellJob(
        job: CliShellJob,
        type: String,
        result: Any? = null,
        message: String? = null
    ) {
        val terminal = job.status in setOf(
            "completed",
            "completed_result_too_large",
            "completed_with_failures",
            "failed",
            "cancelled",
            "cancelled_may_have_applied",
            "completed_after_cancel_request"
        )
        RiftMcpRuntime.cliEvents().emitJob(
            type = type,
            jobId = job.id,
            requestId = job.requestId,
            lane = job.lane,
            status = job.status,
            terminal = terminal,
            result = result,
            message = message
        )
    }

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
        cancelAllCliShellJobs("Native RiftShell closed")
        worker.shutdownNow()
        cliWorker.shutdownNow()
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
                    "riftbuild doctor|validate|plan|prepare-riftpp-v0|prepare-codynex-mc0|prepare-codynex-mc1a|prepare-codynex-mc1b|prepare-codynex-m2-vm0|pack|sign|verify|install-proof|install-status|launch-proof|runs|artifacts   [NATIVE / BOUNDED]\n" +
                    "qjs help|version|eval|run   [BOUNDED HEADLESS QUICKJS / READ-ONLY RIFTFS]\n" +
                    "semx help|version|self-test|check|dump-graph|dump-plan|dump-ir|emit-arm32-proof|emit-arm32-runtime   [SEMNEXIS V0 / HEADLESS QUICKJS]\n" +
                    "riftpp help|version|self-test|check|compile|inspect|run|exec|run-stateful|exec-stateful   [CORE V1 / HEADLESS QUICKJS]\n" +
                    "rift-tool gate0-verify   [ARCHIVAL EXACT-REFERENCE CHECK]\n" +
                    "rift-tool semantic-compat   [ONGOING SEMANTIC COMPATIBILITY CHECK]\n" +
                    "rift-tool text-model-benchmark   [FIXED UTF-16 / UTF-8 DEVICE BENCHMARK]\n" +
                    "rift-cli help|status|architecture|enable|disable|driver   [NATIVE N1 / OFF BY DEFAULT]\n" +
                    "codynex status|read-state|call|compile-activate|activate|corrupt|recover|clear|cold-restart   [LOCAL BINDER BRIDGE]\n" +
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
                        "workspace ls", "workspace status", "workspace push", "git", "chat", "devlab", "vortex", "vortex-agent", "riftos-agent", "riftllm-agent", "codynex", "riftbuild", "qjs", "semx", "riftpp", "rift-tool", "rift-cli"
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
            "codynex" -> services.codynex(args, cwd).let { ShellOutcome(it.output, cwd, it.value) }
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
            "rift-cli" -> executeCliCommand(cwd, args)
            "mount", "umount" -> throw IllegalStateException("Legacy shell mount entry point is retired during native Files migration; no renderer fallback exists.")
            "rift" -> throw IllegalStateException("Legacy RiftLocalPlatform shell wrapper is retired; use native Git, Workspace Records, Dev Lab and fixed native build/training services.")
            else -> throw IllegalArgumentException("unsupported native RiftShell command: $command")
        }
    }

    private fun executeCliCommand(cwd: String, args: MutableList<String>): ShellOutcome {
        val cli = RiftCliHost.executeShell(args, cwd)

        if (cli.result.optString("state") == "need_more_info") {
            RiftMcpRuntime.cliEvents().emit(
                type = "driver.need_more_info",
                requestId = cli.result.optString("requestId").takeIf { it.isNotBlank() },
                lane = "driver",
                status = "need_more_info",
                terminal = false,
                message = cli.result.optString("requestMoreInfo").takeIf { it.isNotBlank() },
                extra = JSONObject()
                    .put("sessionId", cli.result.optString("sessionId"))
                    .put("taskId", cli.result.optString("taskId"))
                    .put("projectId", cli.result.optString("projectId"))
                    .put("loop", cli.result.optJSONObject("loop") ?: JSONObject.NULL)
            )
        }
        if (cli.result.optString("command") == "enable" && cli.result.optBoolean("enabled", false)) {
            RiftMcpRuntime.cliEvents().emit(
                type = "cli.enabled",
                lane = "driver",
                status = "enabled",
                terminal = true
            )
        }

        if (cli.result.optString("command") == "disable" && !cli.result.optBoolean("enabled", true)) {
            val reason = "RiftCLI disabled by external driver"
            val shellCancelled = cancelAllCliShellJobs(reason)
            val toolCancellation = RiftMcpRuntime.toolHost(appContext).cancelAllCliJobs(reason)
            val result = JSONObject(cli.result.toString())
                .put("cliShellJobCancellationsRequested", shellCancelled)
                .put("cliToolJobCancellationsRequested", toolCancellation.optInt("cancellationRequested", 0))
            RiftMcpRuntime.cliEvents().emit(
                type = "cli.disabled",
                lane = "driver",
                status = "disabled",
                terminal = true,
                result = JSONObject()
                    .put("shellCancellationsRequested", shellCancelled)
                    .put("toolCancellationsRequested", toolCancellation.optInt("cancellationRequested", 0))
            )
            return ShellOutcome(cli.output, cwd, result)
        }

        val dispatch = cli.result.optJSONObject("dispatch")
            ?: return ShellOutcome(cli.output, cwd, cli.result)

        require(cli.result.optBoolean("accepted", false)) {
            "RiftCLI returned a dispatch without accepting the driver request"
        }

        return when (dispatch.optString("kind")) {
            "rift-shell" -> executeCliShellDispatch(cwd, cli.result, dispatch)
            "rift-tool" -> executeCliToolDispatch(cwd, cli.result, dispatch)
            else -> throw IllegalArgumentException(
                "Unsupported RiftCLI dispatch kind: ${dispatch.optString("kind")}"
            )
        }
    }

    private fun executeCliShellDispatch(
        cwd: String,
        cliResult: JSONObject,
        dispatch: JSONObject
    ): ShellOutcome {
        val rawAction = dispatch.optString("command")
        require(rawAction.isNotBlank()) { "RiftCLI dispatch command is blank" }
        require(rawAction.toByteArray(Charsets.UTF_8).size <= MAX_COMMAND_BYTES) {
            "RiftCLI dispatch exceeds $MAX_COMMAND_BYTES UTF-8 bytes"
        }

        val nestedArgs = tokenize(rawAction)
        val nestedCommand = nestedArgs.firstOrNull()?.lowercase().orEmpty()
        require(nestedCommand.isNotBlank()) { "RiftCLI dispatch command is blank" }
        require(nestedCommand != "rift-cli") {
            "Internal RiftCLI recursion is forbidden; external driver continuation must send the next bounded loop request"
        }

        pruneCliShellJobs()
        if (cliShellJobs.size >= MAX_CLI_SHELL_JOBS) {
            pruneCliShellJobs(forceTerminalTrim = true)
        }
        require(cliShellJobs.size < MAX_CLI_SHELL_JOBS) {
            "RiftCLI shell job capacity reached ($MAX_CLI_SHELL_JOBS); poll/cancel existing jobs first"
        }

        val now = SystemClock.elapsedRealtime()
        val jobId = "cli-shell-job-" + UUID.randomUUID().toString()
        if (!RiftCliExecutionGate.tryReserve(jobId)) {
            val response = JSONObject()
                .put("ok", false)
                .put("error", "RiftCLI already has one outstanding authority job")
                .put("outstandingJobId", RiftCliExecutionGate.outstandingJob() ?: JSONObject.NULL)
            val result = JSONObject(cliResult.toString())
                .put("dispatchSubmitted", false)
                .put("dispatchExecuted", false)
                .put("dispatchOk", false)
                .put("dispatchCommand", nestedCommand)
                .put("dispatchCwd", cwd)
                .put("dispatchOutput", response.toString(2))
                .put("dispatchResult", response)
            return ShellOutcome(response.toString(2), cwd, result)
        }
        val job = CliShellJob(
            jobId,
            cliResult.optString("requestId"),
            nestedCommand,
            cwd,
            now,
            now,
            "queued"
        )
        cliShellJobs[jobId] = job
        emitCliShellJob(job, "job.submitted")

        val future = try {
            cliWorker.submit {
                RiftDeadline.clearInterrupt()
                try {
                    RiftCliExecutionGate.run {
                        var started = false
                        synchronized(job) {
                            if (job.status == "queued") {
                                job.status = "running"
                                job.updatedAt = SystemClock.elapsedRealtime()
                                started = true
                            }
                        }
                        if (started) emitCliShellJob(job, "job.started")
                        val nestedSession = RiftPatchSessions.begin(
                            appContext,
                            origin = "rift-cli-driver",
                            operation = nestedCommand,
                            intent = cliResult.optString("goal").takeIf { it.isNotBlank() },
                            requestId = cliResult.optString("requestId").takeIf { it.isNotBlank() },
                            rawPaths = shellMutationPaths(rawAction, cwd)
                        )
                        try {
                            val nested = executeNative(rawAction, cwd)
                            nestedSession?.let { runCatching { RiftPatchSessions.commit(appContext, it) } }

                            val resultText = when (val value = nested.result) {
                                null -> ""
                                is JSONObject -> value.toString()
                                is JSONArray -> value.toString()
                                else -> value.toString()
                            }
                            val retainedBytes =
                                nested.output.toByteArray(Charsets.UTF_8).size +
                                    resultText.toByteArray(Charsets.UTF_8).size
                            val oversized = retainedBytes > MAX_CLI_SHELL_RETAINED_RESULT_BYTES

                            synchronized(job) {
                                if (oversized) {
                                    job.output = ""
                                    job.result = JSONObject()
                                        .put("resultRetained", false)
                                        .put("resultBytes", retainedBytes)
                                        .put("retentionLimitBytes", MAX_CLI_SHELL_RETAINED_RESULT_BYTES)
                                        .put("note", "RiftCLI completed the shell action but did not retain an oversized result; use a smaller bounded inspection command.")
                                } else {
                                    job.output = nested.output
                                    job.result = nested.result
                                }
                                job.status = when {
                                    job.cancelRequested -> "completed_after_cancel_request"
                                    oversized -> "completed_result_too_large"
                                    else -> "completed"
                                }
                                job.updatedAt = SystemClock.elapsedRealtime()
                            }
                            val terminalResult = JSONObject()
                                .put("output", job.output)
                                .put("result", job.result ?: JSONObject.NULL)
                                .put("error", job.error ?: JSONObject.NULL)
                            emitCliShellJob(
                                job,
                                when (job.status) {
                                    "completed", "completed_result_too_large", "completed_after_cancel_request" -> "job.completed"
                                    "cancelled", "cancelled_may_have_applied" -> "job.cancelled"
                                    else -> "job.failed"
                                },
                                terminalResult
                            )
                        } catch (error: Throwable) {
                            nestedSession?.let(RiftPatchSessions::abort)
                            synchronized(job) {
                                job.error = error.message ?: error.javaClass.simpleName
                                job.status = if (job.cancelRequested || Thread.currentThread().isInterrupted) "cancelled_may_have_applied" else "failed"
                                job.updatedAt = SystemClock.elapsedRealtime()
                            }
                            emitCliShellJob(
                                job,
                                if (job.status == "cancelled_may_have_applied") "job.cancelled" else "job.failed",
                                message = job.error
                            )
                        }
                    }
                } catch (error: Throwable) {
                    var changed = false
                    synchronized(job) {
                        if (job.status !in setOf("completed", "completed_result_too_large", "completed_with_failures", "failed", "cancelled", "cancelled_may_have_applied", "completed_after_cancel_request")) {
                            job.error = error.message ?: error.javaClass.simpleName
                            job.status = if (job.cancelRequested || Thread.currentThread().isInterrupted) "cancelled" else "failed"
                            job.updatedAt = SystemClock.elapsedRealtime()
                            changed = true
                        }
                    }
                    if (changed) {
                        emitCliShellJob(
                            job,
                            if (job.status == "cancelled") "job.cancelled" else "job.failed",
                            message = job.error
                        )
                    }
                } finally {
                    RiftCliExecutionGate.release(jobId)
                    RiftDeadline.clearInterrupt()
                }
            }
        } catch (error: Throwable) {
            synchronized(job) {
                job.error = error.message ?: error.javaClass.simpleName
                job.status = "failed"
                job.updatedAt = SystemClock.elapsedRealtime()
            }
            emitCliShellJob(job, "job.failed", message = job.error)
            RiftCliExecutionGate.release(jobId)
            null
        }
        job.future = future

        val snapshot = cliShellJobSnapshot(job)
        val result = JSONObject(cliResult.toString())
            .put("dispatchSubmitted", true)
            .put("dispatchExecuted", snapshot.optBoolean("terminal", false))
            .put("dispatchOk", snapshot.optBoolean("ok", false))
            .put("dispatchCommand", nestedCommand)
            .put("dispatchCwd", cwd)
            .put("dispatchAsync", true)
            .put("dispatchTerminal", snapshot.optBoolean("terminal", false))
            .put("dispatchStatus", snapshot.optString("status"))
            .put("dispatchOutput", snapshot.toString(2))
            .put("dispatchResult", snapshot)
        return ShellOutcome(snapshot.toString(2), cwd, result)
    }

    private fun cliShellJobSnapshot(job: CliShellJob, includeResult: Boolean = true): JSONObject = synchronized(job) {
        JSONObject()
            .put("ok", true)
            .put("jobOk", when (job.status) {
                "completed", "completed_result_too_large", "completed_after_cancel_request" -> true
                "completed_with_failures", "failed", "cancelled", "cancelled_may_have_applied" -> false
                else -> JSONObject.NULL
            })
            .put("jobId", job.id)
            .put("requestId", job.requestId)
            .put("kind", if (job.lane == "batch") "rift-cli-batch" else "rift-shell")
            .put("operation", job.operation.take(80))
            .put("cwd", job.cwd)
            .put("status", job.status)
            .put("terminal", job.status in setOf("completed", "completed_result_too_large", "completed_with_failures", "failed", "cancelled", "cancelled_may_have_applied", "completed_after_cancel_request"))
            .put("createdAtElapsedMs", job.createdAt)
            .put("updatedAtElapsedMs", job.updatedAt)
            .put("cancelRequested", job.cancelRequested)
            .put("elapsedMs", (SystemClock.elapsedRealtime() - job.createdAt).coerceAtLeast(0L))
            .also { snapshot ->
                if (includeResult) {
                    snapshot
                        .put("output", job.output)
                        .put("result", job.result ?: JSONObject.NULL)
                        .put("error", job.error ?: JSONObject.NULL)
                }
            }
    }

    private fun listCliShellJobs(requestId: String? = null): JSONArray {
        pruneCliShellJobs()
        val rows = JSONArray()
        cliShellJobs.values
            .filter { requestId.isNullOrBlank() || it.requestId == requestId }
            .sortedBy { it.createdAt }
            .forEach { rows.put(cliShellJobSnapshot(it, includeResult = false)) }
        return rows
    }

    private fun pollCliShellJob(jobId: String): JSONObject? {
        pruneCliShellJobs()
        val job = cliShellJobs[jobId] ?: return null
        return cliShellJobSnapshot(job)
    }

    private fun cancelCliShellJob(jobId: String): JSONObject? {
        val job = cliShellJobs[jobId] ?: return null
        synchronized(job) {
            if (job.status in setOf("completed", "completed_result_too_large", "completed_with_failures", "failed", "cancelled", "cancelled_may_have_applied", "completed_after_cancel_request")) {
                return cliShellJobSnapshot(job)
            }
            job.cancelRequested = true
            val wasQueued = job.status == "queued"
            val cancelled = job.future?.cancel(true) == true
            job.updatedAt = SystemClock.elapsedRealtime()
            if (wasQueued && cancelled) {
                job.status = "cancelled"
                job.error = "RiftCLI shell job cancelled before execution"
                RiftCliExecutionGate.release(job.id)
            } else {
                job.status = "cancelling"
            }
            emitCliShellJob(
                job,
                if (job.status == "cancelled") "job.cancelled" else "job.cancelling",
                message = job.error
            )
            return cliShellJobSnapshot(job)
        }
    }

    private fun cancelAllCliShellJobs(reason: String): Int {
        var requested = 0
        val changed = ArrayList<CliShellJob>()
        cliShellJobs.values.forEach { job ->
            synchronized(job) {
                if (job.status !in setOf("completed", "completed_result_too_large", "completed_with_failures", "failed", "cancelled", "cancelled_may_have_applied", "completed_after_cancel_request")) {
                    job.cancelRequested = true
                    val wasQueued = job.status == "queued"
                    val cancelled = job.future?.cancel(true) == true
                    job.updatedAt = SystemClock.elapsedRealtime()
                    if (wasQueued && cancelled) {
                        job.status = "cancelled"
                        job.error = reason
                        RiftCliExecutionGate.release(job.id)
                    } else {
                        job.status = "cancelling"
                    }
                    changed += job
                    requested++
                }
            }
        }
        changed.forEach { job ->
            emitCliShellJob(
                job,
                if (job.status == "cancelled") "job.cancelled" else "job.cancelling",
                message = reason
            )
        }
        return requested
    }

    private fun pruneCliShellJobs(forceTerminalTrim: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val terminal = cliShellJobs.values
            .filter { it.status in setOf("completed", "completed_result_too_large", "completed_with_failures", "failed", "cancelled", "cancelled_may_have_applied", "completed_after_cancel_request") }
            .sortedBy { it.updatedAt }

        terminal.forEach { job ->
            if (now - job.updatedAt >= CLI_SHELL_JOB_RETENTION_MS) cliShellJobs.remove(job.id, job)
        }

        if (forceTerminalTrim && cliShellJobs.size >= MAX_CLI_SHELL_JOBS) {
            terminal.forEach { job ->
                if (cliShellJobs.size < MAX_CLI_SHELL_JOBS) return
                cliShellJobs.remove(job.id, job)
            }
        }
    }

    private fun parseCliBatchPlan(args: JSONObject, toolHost: RiftToolHost): CliBatchPlan {
        val encodedBytes = args.toString().toByteArray(Charsets.UTF_8).size
        require(encodedBytes <= MAX_CLI_BATCH_BYTES) {
            "RiftCLI Batch V2 plan exceeds $MAX_CLI_BATCH_BYTES UTF-8 bytes"
        }
        val mode = args.optString("mode", "execute").trim().lowercase().ifBlank { "execute" }
        require(mode == "execute" || mode == "validate") {
            "RiftCLI Batch V2 mode must be execute or validate"
        }
        val failurePolicy = args.optString("failurePolicy", "stop").trim().lowercase().ifBlank { "stop" }
        require(failurePolicy == "stop" || failurePolicy == "continue") {
            "RiftCLI Batch V2 failurePolicy must be stop or continue"
        }
        val rawSteps = args.optJSONArray("steps")
            ?: throw IllegalArgumentException("RiftCLI Batch V2 requires steps[]")
        require(rawSteps.length() in 1..MAX_CLI_BATCH_STEPS) {
            "RiftCLI Batch V2 requires between 1 and $MAX_CLI_BATCH_STEPS steps"
        }

        val seen = LinkedHashSet<String>()
        val steps = ArrayList<CliBatchStep>(rawSteps.length())
        for (index in 0 until rawSteps.length()) {
            val raw = rawSteps.optJSONObject(index)
                ?: throw IllegalArgumentException("RiftCLI Batch V2 step ${index + 1} must be an object")
            require(raw.toString().toByteArray(Charsets.UTF_8).size <= MAX_CLI_BATCH_STEP_BYTES) {
                "RiftCLI Batch V2 step ${index + 1} exceeds $MAX_CLI_BATCH_STEP_BYTES UTF-8 bytes"
            }
            val id = raw.optString("id").trim()
            require(Regex("[A-Za-z0-9._-]{1,64}").matches(id)) {
                "RiftCLI Batch V2 step ${index + 1} requires id [A-Za-z0-9._-]{1,64}"
            }
            require(seen.add(id)) { "RiftCLI Batch V2 duplicate step id: $id" }
            when (val kind = raw.optString("kind").trim().lowercase()) {
                "tool" -> {
                    val name = raw.optString("name").trim()
                    require(name.isNotBlank()) { "RiftCLI Batch V2 tool step $id requires name" }
                    val rawToolArgs = raw.opt("args")
                    require(rawToolArgs == null || rawToolArgs === JSONObject.NULL || rawToolArgs is JSONObject) {
                        "RiftCLI Batch V2 tool step $id args must be an object"
                    }
                    val toolArgs = rawToolArgs as? JSONObject ?: JSONObject()
                    val validation = toolHost.validateCliBatchTool(name, toolArgs)
                    require(validation.optBoolean("ok", false)) {
                        validation.optString("error", "Invalid RiftCLI Batch V2 tool step $id")
                    }
                    steps += CliBatchStep(
                        id = id,
                        kind = kind,
                        toolName = validation.optString("name"),
                        toolArgs = validation.optJSONObject("args") ?: JSONObject()
                    )
                }
                "shell" -> {
                    val command = raw.optString("command").trim()
                    require(command.isNotBlank()) { "RiftCLI Batch V2 shell step $id requires command" }
                    require(command.toByteArray(Charsets.UTF_8).size <= MAX_CLI_BATCH_STEP_BYTES) {
                        "RiftCLI Batch V2 shell step $id exceeds $MAX_CLI_BATCH_STEP_BYTES UTF-8 bytes"
                    }
                    val tokens = tokenize(command)
                    val commandName = tokens.firstOrNull()?.lowercase().orEmpty()
                    require(commandName.isNotBlank()) { "RiftCLI Batch V2 shell step $id has no command" }
                    require(commandName != "rift-cli") { "RiftCLI Batch V2 forbids recursive rift-cli steps" }
                    require(commandName != "batch") { "RiftCLI Batch V2 does not resurrect retired RiftShell batch" }
                    require(commandName in CLI_BATCH_ALLOWED_SHELL_COMMANDS) {
                        "Unsupported RiftCLI Batch V2 shell command: $commandName"
                    }
                    steps += CliBatchStep(id = id, kind = kind, shellCommand = command)
                }
                else -> throw IllegalArgumentException(
                    "RiftCLI Batch V2 step $id kind must be tool or shell"
                )
            }
        }
        return CliBatchPlan(mode = mode, failurePolicy = failurePolicy, steps = steps)
    }

    private fun cliBatchPlanSummary(plan: CliBatchPlan): JSONObject {
        val rows = JSONArray()
        plan.steps.forEach { step ->
            rows.put(
                JSONObject()
                    .put("id", step.id)
                    .put("kind", step.kind)
                    .put(
                        "operation",
                        if (step.kind == "tool") step.toolName ?: ""
                        else runCatching { tokenize(step.shellCommand.orEmpty()).firstOrNull().orEmpty() }.getOrDefault("")
                    )
            )
        }
        return JSONObject()
            .put("schema", "rift.cli-batch/2")
            .put("mode", plan.mode)
            .put("failurePolicy", plan.failurePolicy)
            .put("stepCount", plan.steps.size)
            .put("steps", rows)
    }

    private fun emitCliBatchStep(
        job: CliShellJob,
        type: String,
        step: CliBatchStep,
        index: Int,
        total: Int,
        status: String,
        result: Any? = null,
        message: String? = null
    ) {
        val operation = if (step.kind == "tool") {
            step.toolName.orEmpty()
        } else {
            runCatching { tokenize(step.shellCommand.orEmpty()).firstOrNull().orEmpty() }.getOrDefault("")
        }
        RiftMcpRuntime.cliEvents().emit(
            type = type,
            requestId = job.requestId,
            jobId = job.id,
            lane = "batch",
            status = status,
            terminal = false,
            message = message,
            result = result,
            extra = JSONObject()
                .put("stepId", step.id)
                .put("stepIndex", index)
                .put("stepCount", total)
                .put("stepKind", step.kind)
                .put("operation", operation.take(80))
        )
    }

    private fun executeCliBatchShellStep(
        rawAction: String,
        cwd: String,
        stepRequestId: String,
        intent: String?
    ): ShellOutcome {
        require(rawAction.toByteArray(Charsets.UTF_8).size <= MAX_CLI_BATCH_STEP_BYTES) {
            "RiftCLI Batch V2 shell step exceeds $MAX_CLI_BATCH_STEP_BYTES UTF-8 bytes"
        }
        val tokens = tokenize(rawAction)
        val commandName = tokens.firstOrNull()?.lowercase().orEmpty()
        require(commandName.isNotBlank()) { "RiftCLI Batch V2 shell step is blank" }
        require(commandName != "rift-cli") { "RiftCLI Batch V2 forbids recursive rift-cli steps" }
        require(commandName != "batch") { "RiftCLI Batch V2 does not resurrect retired RiftShell batch" }
        require(commandName in CLI_BATCH_ALLOWED_SHELL_COMMANDS) {
            "Unsupported RiftCLI Batch V2 shell command: $commandName"
        }
        val patchSession = RiftPatchSessions.begin(
            appContext,
            origin = "rift-cli-batch",
            operation = commandName,
            intent = intent,
            requestId = stepRequestId,
            rawPaths = shellMutationPaths(rawAction, cwd)
        )
        return try {
            val outcome = executeNative(rawAction, cwd)
            patchSession?.let { runCatching { RiftPatchSessions.commit(appContext, it) } }
            outcome
        } catch (error: Throwable) {
            patchSession?.let(RiftPatchSessions::abort)
            throw error
        }
    }

    private fun startCliBatch(
        cwd: String,
        cliResult: JSONObject,
        args: JSONObject,
        toolHost: RiftToolHost
    ): JSONObject {
        val plan = parseCliBatchPlan(args, toolHost)
        val planSummary = cliBatchPlanSummary(plan)
        if (plan.mode == "validate") {
            RiftMcpRuntime.cliEvents().emit(
                type = "batch.validated",
                requestId = cliResult.optString("requestId").takeIf { it.isNotBlank() },
                lane = "batch",
                status = "validated",
                terminal = true,
                result = planSummary
            )
            return JSONObject(planSummary.toString())
                .put("ok", true)
                .put("validated", true)
                .put("terminal", true)
                .put("status", "validated")
        }

        pruneCliShellJobs()
        if (cliShellJobs.size >= MAX_CLI_SHELL_JOBS) pruneCliShellJobs(forceTerminalTrim = true)
        require(cliShellJobs.size < MAX_CLI_SHELL_JOBS) {
            "RiftCLI Batch V2 job capacity reached ($MAX_CLI_SHELL_JOBS); inspect existing jobs first"
        }
        val now = SystemClock.elapsedRealtime()
        val jobId = "cli-batch-job-" + UUID.randomUUID().toString()
        if (!RiftCliExecutionGate.tryReserve(jobId)) {
            return JSONObject()
                .put("ok", false)
                .put("error", "RiftCLI already has one outstanding authority job")
                .put("outstandingJobId", RiftCliExecutionGate.outstandingJob() ?: JSONObject.NULL)
        }
        val job = CliShellJob(
            id = jobId,
            requestId = cliResult.optString("requestId"),
            operation = "batch",
            cwd = cwd,
            createdAt = now,
            updatedAt = now,
            status = "queued",
            lane = "batch"
        )
        cliShellJobs[jobId] = job
        emitCliShellJob(job, "batch.submitted", planSummary)

        val future = try {
            cliWorker.submit {
                RiftDeadline.clearInterrupt()
                try {
                    RiftCliExecutionGate.run {
                        synchronized(job) {
                            if (job.status == "queued") {
                                job.status = "running"
                                job.updatedAt = SystemClock.elapsedRealtime()
                            }
                        }
                        emitCliShellJob(job, "batch.started", planSummary)
                        val stepRows = JSONArray()
                        var currentCwd = cwd
                        var failedSteps = 0
                        var executedSteps = 0
                        var stopped = false

                        for ((index0, step) in plan.steps.withIndex()) {
                            if (job.cancelRequested || Thread.currentThread().isInterrupted) {
                                throw InterruptedException("RiftCLI Batch V2 cancelled before step ${step.id}")
                            }
                            val index = index0 + 1
                            emitCliBatchStep(job, "batch.step.started", step, index, plan.steps.size, "running")
                            val started = SystemClock.elapsedRealtime()
                            val stepResponse = try {
                                if (step.kind == "tool") {
                                    toolHost.executeCliBatchTool(
                                        step.toolName.orEmpty(),
                                        step.toolArgs ?: JSONObject(),
                                        "${job.requestId}:${step.id}"
                                    )
                                } else {
                                    val outcome = executeCliBatchShellStep(
                                        step.shellCommand.orEmpty(),
                                        currentCwd,
                                        "${job.requestId}:${step.id}",
                                        cliResult.optString("goal").takeIf { it.isNotBlank() }
                                    )
                                    currentCwd = outcome.cwd
                                    JSONObject()
                                        .put("ok", true)
                                        .put("operation", runCatching { tokenize(step.shellCommand.orEmpty()).firstOrNull().orEmpty() }.getOrDefault(""))
                                        .put("cwd", outcome.cwd)
                                        .put("output", outcome.output)
                                        .put("result", outcome.result ?: JSONObject.NULL)
                                }
                            } catch (error: InterruptedException) {
                                throw error
                            } catch (error: Exception) {
                                JSONObject()
                                    .put("ok", false)
                                    .put("error", error.message ?: error.javaClass.simpleName)
                            }
                            val duration = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
                            val ok = stepResponse.optBoolean("ok", false)
                            if (!ok) failedSteps++
                            executedSteps++

                            val responseBytes = stepResponse.toString().toByteArray(Charsets.UTF_8).size
                            val retained = if (responseBytes > MAX_CLI_BATCH_STEP_RESULT_BYTES) {
                                JSONObject()
                                    .put("ok", ok)
                                    .put("resultRetained", false)
                                    .put("resultBytes", responseBytes)
                                    .put("retentionLimitBytes", MAX_CLI_BATCH_STEP_RESULT_BYTES)
                            } else {
                                JSONObject(stepResponse.toString())
                            }
                            val stepRow = JSONObject()
                                .put("id", step.id)
                                .put("kind", step.kind)
                                .put("ok", ok)
                                .put("durationMs", duration)
                                .put("result", retained)
                            stepRows.put(stepRow)
                            emitCliBatchStep(
                                job,
                                if (ok) "batch.step.completed" else "batch.step.failed",
                                step,
                                index,
                                plan.steps.size,
                                if (ok) "completed" else "failed",
                                retained,
                                if (ok) null else stepResponse.optString("error").takeIf { it.isNotBlank() }
                            )

                            if (job.cancelRequested || Thread.currentThread().isInterrupted) {
                                throw InterruptedException("RiftCLI Batch V2 cancellation observed after step ${step.id}")
                            }
                            if (!ok && plan.failurePolicy == "stop") {
                                stopped = true
                                break
                            }
                        }

                        val finalResult = JSONObject()
                            .put("schema", "rift.cli-batch/2")
                            .put("mode", "execute")
                            .put("failurePolicy", plan.failurePolicy)
                            .put("stepCount", plan.steps.size)
                            .put("executedSteps", executedSteps)
                            .put("failedSteps", failedSteps)
                            .put("stoppedOnFailure", stopped)
                            .put("cwd", currentCwd)
                            .put("steps", stepRows)
                        val finalBytes = finalResult.toString().toByteArray(Charsets.UTF_8).size
                        val retainedFinal = if (finalBytes > MAX_CLI_SHELL_RETAINED_RESULT_BYTES) {
                            JSONObject()
                                .put("schema", "rift.cli-batch/2")
                                .put("resultRetained", false)
                                .put("resultBytes", finalBytes)
                                .put("retentionLimitBytes", MAX_CLI_SHELL_RETAINED_RESULT_BYTES)
                                .put("stepCount", plan.steps.size)
                                .put("executedSteps", executedSteps)
                                .put("failedSteps", failedSteps)
                        } else finalResult

                        synchronized(job) {
                            job.output = "RiftCLI Batch V2 executed $executedSteps/${plan.steps.size} steps; failures=$failedSteps"
                            job.result = retainedFinal
                            job.status = when {
                                job.cancelRequested -> "completed_after_cancel_request"
                                failedSteps > 0 && plan.failurePolicy == "continue" -> "completed_with_failures"
                                failedSteps > 0 -> "failed"
                                finalBytes > MAX_CLI_SHELL_RETAINED_RESULT_BYTES -> "completed_result_too_large"
                                else -> "completed"
                            }
                            job.error = if (failedSteps > 0 && plan.failurePolicy == "stop") {
                                "RiftCLI Batch V2 stopped after a failed step"
                            } else null
                            job.updatedAt = SystemClock.elapsedRealtime()
                        }
                        emitCliShellJob(
                            job,
                            when (job.status) {
                                "completed", "completed_result_too_large", "completed_with_failures", "completed_after_cancel_request" -> "batch.completed"
                                else -> "batch.failed"
                            },
                            retainedFinal,
                            job.error
                        )
                    }
                } catch (error: Throwable) {
                    var changed = false
                    synchronized(job) {
                        if (job.status !in setOf(
                                "completed",
                                "completed_result_too_large",
                                "completed_with_failures",
                                "failed",
                                "cancelled",
                                "cancelled_may_have_applied",
                                "completed_after_cancel_request"
                            )) {
                            job.error = error.message ?: error.javaClass.simpleName
                            job.status = if (job.cancelRequested || Thread.currentThread().isInterrupted) {
                                "cancelled_may_have_applied"
                            } else {
                                "failed"
                            }
                            job.updatedAt = SystemClock.elapsedRealtime()
                            changed = true
                        }
                    }
                    if (changed) {
                        emitCliShellJob(
                            job,
                            if (job.status == "cancelled_may_have_applied") "batch.cancelled" else "batch.failed",
                            message = job.error
                        )
                    }
                } finally {
                    RiftCliExecutionGate.release(jobId)
                    RiftDeadline.clearInterrupt()
                }
            }
        } catch (error: Throwable) {
            synchronized(job) {
                job.error = error.message ?: error.javaClass.simpleName
                job.status = "failed"
                job.updatedAt = SystemClock.elapsedRealtime()
            }
            emitCliShellJob(job, "batch.failed", message = job.error)
            RiftCliExecutionGate.release(jobId)
            null
        }
        job.future = future
        return cliShellJobSnapshot(job)
    }

    private fun executeCliToolDispatch(
        cwd: String,
        cliResult: JSONObject,
        dispatch: JSONObject
    ): ShellOutcome {
        val toolName = dispatch.optString("name").trim()
        require(toolName.isNotBlank()) { "RiftCLI tool dispatch name is blank" }
        require(toolName != "rift_shell_exec" && toolName != "rift_workspace_exec") {
            "RiftCLI tool dispatch forbids $toolName"
        }

        val argsJson = dispatch.optString("argsJson", "{}")
        require(argsJson.toByteArray(Charsets.UTF_8).size <= MAX_COMMAND_BYTES) {
            "RiftCLI tool args exceed $MAX_COMMAND_BYTES UTF-8 bytes"
        }
        val toolArgs = runCatching { JSONObject(argsJson) }
            .getOrElse { throw IllegalArgumentException("RiftCLI tool args must be a JSON object") }

        val toolHost = RiftMcpRuntime.toolHost(appContext)
        val response = when (toolName) {
            "rift_cli_batch" -> startCliBatch(cwd, cliResult, toolArgs, toolHost)
            "rift_cli_job_list" -> {
                val requestId = toolArgs.optString("requestId").trim().takeIf { it.isNotBlank() }
                val rows = JSONArray()
                val shellRows = listCliShellJobs(requestId)
                for (index in 0 until shellRows.length()) rows.put(shellRows.opt(index))
                val toolRows = toolHost.listCliJobs(requestId).optJSONArray("jobs") ?: JSONArray()
                for (index in 0 until toolRows.length()) rows.put(toolRows.opt(index))
                JSONObject().put("ok", true).put("jobs", rows)
            }
            "rift_cli_job_poll" -> {
                val jobId = toolArgs.optString("jobId").trim()
                require(jobId.isNotBlank()) { "rift_cli_job_poll requires jobId" }
                pollCliShellJob(jobId) ?: toolHost.pollCliJob(jobId)
            }
            "rift_cli_job_cancel" -> {
                val jobId = toolArgs.optString("jobId").trim()
                require(jobId.isNotBlank()) { "rift_cli_job_cancel requires jobId" }
                cancelCliShellJob(jobId) ?: toolHost.cancelCliJob(jobId)
            }
            else -> toolHost.startCliJob(toolName, toolArgs, cliResult.optString("requestId"))
        }

        val ok = response.optBoolean("ok", false)
        val status = response.optString("status")
        val terminal = response.optBoolean("terminal", false)
        val jobControl = toolName == "rift_cli_job_list" ||
            toolName == "rift_cli_job_poll" ||
            toolName == "rift_cli_job_cancel"
        val submitted = !jobControl && response.optString("jobId").isNotBlank()
        val asynchronous = submitted
        val executed = jobControl || terminal
        val output = if (!ok) {
            response.optString("error", "RiftCLI tool dispatch failed")
        } else {
            response.toString(2)
        }

        val result = JSONObject(cliResult.toString())
            .put("dispatchSubmitted", submitted)
            .put("dispatchExecuted", executed)
            .put("dispatchOk", ok)
            .put("dispatchTool", toolName)
            .put("dispatchCwd", cwd)
            .put("dispatchAsync", asynchronous)
            .put("dispatchTerminal", terminal)
            .put("dispatchStatus", status)
            .put("dispatchOutput", output)
            .put("dispatchResult", response)
        return ShellOutcome(output, cwd, result)
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
