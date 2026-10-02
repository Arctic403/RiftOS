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
            "riftbuild", "riftcrash", "qjs", "semx", "riftpp", "riftpp-host", "riftpp-editor", "rift-tool"
        )
        private const val WORKSPACE_ROOT = "/workspace/RiftOS-main"
        private const val S2_DIAGNOSTIC_ARM32_TRANSPORT_SHA256 =
            "f22eb8c092afa5351ea2cc7659c1db7130f837f8f3d596b1e20080bdeea4aa99"
        private const val S2_DIAGNOSTIC_ARM64_TRANSPORT_SHA256 =
            "ee7cf4f44a41d94abb0fde99146029734bcc8a845adda2e7f5ed41f2f0afef3a"
        private const val S2_PROMOTED_C_ARM32_TRANSPORT_SHA256 =
            "eac55e1428e8921c807777ffdc91033427f3fc18e12b0abf9cfdc9884f03cd4c"
        private const val S2_PROMOTED_C_ARM64_TRANSPORT_SHA256 =
            "9c39416943e0f9736fd064c46b2ac8e71c679b189a20e97c75776bd7bbbc5d0c"
        private const val S3_CANDIDATE_B_ARM32_TRANSPORT_SHA256 =
            "1f0351db5c3fd0a913dfb04572192435f3ca1d116012bea4f99e8176b9a4c5ef"
        private const val S3_CANDIDATE_B_ARM64_TRANSPORT_SHA256 =
            "03eb5937570ce71e398384be1c9e803df59ae04ae90c9e42a641af1151705cf8"
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

    override fun execute(command: String, cwd: String?, requestId: String?, reply: (JSONObject) -> Unit) {
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
                    requestId = requestId,
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
                    "riftbuild doctor|validate|plan|toolchain-status|toolchain-install-bundled|compile-native|prepare-native-app|prepare-riftpp-v0|prepare-riftpp-seed0-arm64|prepare-riftpp-app0|prepare-riftpp-editor|prepare-codynex-mc0|prepare-codynex-mc1a|prepare-codynex-mc1b|prepare-codynex-m2-vm0|prepare-codynex-m2b|prepare-codynex-mc2a|prepare-codynex-editor|prepare-codynex-app|pack|sign|verify|install-proof|install-status|launch-proof|runs|artifacts   [NATIVE / BOUNDED]\n" +
                    "riftcrash help|status|start|capture|latest|reset [package]   [LOCALHOST DIAGNOSTIC BRIDGE]\n" +
                    "qjs help|version|eval|run   [BOUNDED HEADLESS QUICKJS / READ-ONLY RIFTFS]\n" +
                    "semx help|version|self-test|check|dump-graph|dump-plan|dump-ir|emit-arm32-proof|emit-arm32-runtime   [SEMNEXIS V0 / HEADLESS QUICKJS]\n" +
                    "riftpp help|version|self-test|check|compile|inspect|run|exec|run-stateful|exec-stateful   [CORE V1 / HEADLESS QUICKJS]\n" +
                    "riftpp-host help|status|compile|prove <riftpp-root> <source-file> [output-capacity]   [APPROVED MACHINE-CODE HOST]\n" +
                    "riftpp-editor help|status|ls|stat|cat|write|push|pull|mkdir|mv|rm|compile|preflight|build-debug|build-production|native-compile|native-run|native-preflight|native-build-debug   [LEGACY EDITOR BINDER BRIDGE]\n" +
                    "codynex-editor help|status|ls|stat|cat|write|push|pull|push-dir|pull-dir|mkdir|mv|rm|compile|preview|native-proof|build-apk   [CODYNEX EDITOR BINDER BRIDGE]\n" +
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
                        "workspace ls", "workspace status", "workspace push", "git", "chat", "devlab", "vortex", "vortex-agent", "riftos-agent", "riftllm-agent", "codynex", "riftbuild", "riftcrash", "qjs", "semx", "riftpp", "riftpp-host", "riftpp-editor", "rift-tool", "rift-cli"
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
            "riftcrash" -> executeRiftCrashCommand(cwd, args)
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
            "riftpp-host" -> executeRiftppHostCommand(cwd, args)
            "riftpp-editor" -> executeRiftppEditorCommand(cwd, args)
            "codynex-editor" -> executeCodynexEditorCommand(cwd, args)
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


    private fun executeRiftppEditorCommand(
        cwd: String,
        args: MutableList<String>
    ): ShellOutcome {
        val action =
            args.removeFirstOrNull()
                ?.lowercase()
                ?: "help"

        if (action == "help") {
            val output =
                "Rift++ legacy editor development bridge\n" +
                    "riftpp-editor status\n" +
                    "riftpp-editor ls\n" +
                    "riftpp-editor stat <editor-path>\n" +
                    "riftpp-editor cat <editor-path>\n" +
                    "riftpp-editor write <editor-path> <text>\n" +
                    "riftpp-editor push <riftfs-source> <editor-path>\n" +
                    "riftpp-editor pull <editor-path> <riftfs-destination>\n" +
                    "riftpp-editor mkdir <editor-path>\n" +
                    "riftpp-editor mv <from> <to>\n" +
                    "riftpp-editor rm <editor-path>\n" +
                    "riftpp-editor compile\n" +
                    "riftpp-editor preflight\n" +
                    "riftpp-editor build-debug\n" +
                    "riftpp-editor build-production\n" +
                    "riftpp-editor native-compile <source-path> [output-path] [compiler-path]\n" +
                    "riftpp-editor native-run <program-path> <input-path> [output-path] [output-capacity]\n" +
                    "riftpp-editor native-preflight <elf-path>\n" +
                    "riftpp-editor native-build-debug <elf-path> [package] [library]"

            return ShellOutcome(
                output,
                cwd,
                nativeResult(
                    "riftpp-editor"
                ).put(
                    "action",
                    "help"
                )
            )
        }

        val bridge =
            RiftppEditorBridgeClient(
                appContext
            )

        if (action == "cat") {
            require(args.size == 1) {
                "usage: riftpp-editor cat <editor-path>"
            }

            val text =
                bridge.readText(
                    args[0]
                )

            return ShellOutcome(
                text,
                cwd,
                nativeResult(
                    "riftpp-editor"
                )
                    .put(
                        "action",
                        "cat"
                    )
                    .put(
                        "path",
                        args[0]
                    )
                    .put(
                        "bytes",
                        text.toByteArray(
                            Charsets.UTF_8
                        ).size
                    )
            )
        }

        val value =
            when (action) {
                "status" -> {
                    require(args.isEmpty()) {
                        "usage: riftpp-editor status"
                    }

                    bridge.execute(
                        JSONObject()
                            .put(
                                "op",
                                "status"
                            )
                    )
                }

                "ls", "tree" -> {
                    require(args.isEmpty()) {
                        "usage: riftpp-editor ls"
                    }

                    bridge.execute(
                        JSONObject()
                            .put(
                                "op",
                                "list"
                            )
                    )
                }

                "stat" -> {
                    require(args.size == 1) {
                        "usage: riftpp-editor stat <editor-path>"
                    }

                    bridge.execute(
                        JSONObject()
                            .put(
                                "op",
                                "stat"
                            )
                            .put(
                                "path",
                                args[0]
                            )
                    )
                }

                "write" -> {
                    require(args.size >= 2) {
                        "usage: riftpp-editor write <editor-path> <text>"
                    }

                    val path =
                        args.removeFirst()
                    val text =
                        args.joinToString(
                            " "
                        )

                    bridge.writeText(
                        path,
                        text
                    )
                }

                "push" -> {
                    require(args.size == 2) {
                        "usage: riftpp-editor push <riftfs-source> <editor-path>"
                    }

                    val localDisplay =
                        resolveDisplay(
                            cwd,
                            args[0]
                        )
                    val localFile =
                        resolveFile(
                            localDisplay
                        )

                    require(localFile.isFile) {
                        "local push source is not a file: $localDisplay"
                    }

                    bridge.push(
                        localFile,
                        args[1]
                    )
                        .put(
                            "localPath",
                            localDisplay
                        )
                }

                "pull" -> {
                    require(args.size == 2) {
                        "usage: riftpp-editor pull <editor-path> <riftfs-destination>"
                    }

                    val localDisplay =
                        resolveDisplay(
                            cwd,
                            args[1]
                        )
                    val localFile =
                        resolveFile(
                            localDisplay
                        )

                    require(
                        localFile != riftRoot &&
                            !localFile.isDirectory
                    ) {
                        "local pull destination must be a file path"
                    }

                    bridge.pull(
                        args[0],
                        localFile
                    )
                        .put(
                            "localPath",
                            localDisplay
                        )
                }

                "mkdir" -> {
                    require(args.size == 1) {
                        "usage: riftpp-editor mkdir <editor-path>"
                    }

                    bridge.execute(
                        JSONObject()
                            .put(
                                "op",
                                "mkdir"
                            )
                            .put(
                                "path",
                                args[0]
                            )
                    )
                }

                "mv" -> {
                    require(args.size == 2) {
                        "usage: riftpp-editor mv <from> <to>"
                    }

                    bridge.execute(
                        JSONObject()
                            .put(
                                "op",
                                "move"
                            )
                            .put(
                                "from",
                                args[0]
                            )
                            .put(
                                "to",
                                args[1]
                            )
                    )
                }

                "rm" -> {
                    require(args.size == 1) {
                        "usage: riftpp-editor rm <editor-path>"
                    }

                    bridge.execute(
                        JSONObject()
                            .put(
                                "op",
                                "delete"
                            )
                            .put(
                                "path",
                                args[0]
                            )
                    )
                }

                "compile" -> {
                    require(args.isEmpty()) {
                        "usage: riftpp-editor compile"
                    }

                    bridge.execute(
                        JSONObject()
                            .put(
                                "op",
                                "compile"
                            )
                    )
                }

                "preflight" -> {
                    require(args.isEmpty()) {
                        "usage: riftpp-editor preflight"
                    }

                    bridge.execute(
                        JSONObject()
                            .put(
                                "op",
                                "preflight"
                            )
                    )
                }

                "build-debug" -> {
                    require(args.isEmpty()) {
                        "usage: riftpp-editor build-debug"
                    }

                    bridge.execute(
                        JSONObject()
                            .put(
                                "op",
                                "build-debug"
                            )
                    )
                }

                "build-production",
                "build-prod" -> {
                    require(args.isEmpty()) {
                        "usage: riftpp-editor build-production"
                    }

                    bridge.execute(
                        JSONObject()
                            .put(
                                "op",
                                "build-production"
                            )
                    )
                }

                "native-compile" -> {
                    require(
                        args.size in 1..3
                    ) {
                        "usage: riftpp-editor native-compile <source-path> [output-path] [compiler-path]"
                    }

                    val request =
                        JSONObject()
                            .put(
                                "op",
                                "native-compile"
                            )
                            .put(
                                "sourcePath",
                                args[0]
                            )

                    if (args.size >= 2) {
                        request.put(
                            "outputPath",
                            args[1]
                        )
                    }
                    if (args.size >= 3) {
                        request.put(
                            "compilerPath",
                            args[2]
                        )
                    }

                    bridge.execute(
                        request
                    )
                }

                "native-run" -> {
                    require(
                        args.size in 2..4
                    ) {
                        "usage: riftpp-editor native-run <program-path> <input-path> [output-path] [output-capacity]"
                    }

                    val request =
                        JSONObject()
                            .put(
                                "op",
                                "native-run"
                            )
                            .put(
                                "programPath",
                                args[0]
                            )
                            .put(
                                "inputPath",
                                args[1]
                            )

                    if (args.size >= 3) {
                        request.put(
                            "outputPath",
                            args[2]
                        )
                    }
                    if (args.size >= 4) {
                        val capacity =
                            args[3]
                                .toIntOrNull()
                                ?: throw IllegalArgumentException(
                                    "output-capacity must be an integer"
                                )
                        require(
                            capacity > 0
                        ) {
                            "output-capacity must be positive"
                        }
                        request.put(
                            "outputCapacity",
                            capacity
                        )
                    }

                    bridge.execute(
                        request
                    )
                }

                "native-preflight" -> {
                    require(
                        args.size == 1
                    ) {
                        "usage: riftpp-editor native-preflight <elf-path>"
                    }

                    bridge.execute(
                        JSONObject()
                            .put(
                                "op",
                                "native-preflight"
                            )
                            .put(
                                "elfPath",
                                args[0]
                            )
                    )
                }

                "native-build-debug" -> {
                    require(
                        args.size in 1..3
                    ) {
                        "usage: riftpp-editor native-build-debug <elf-path> [package] [library]"
                    }

                    val request =
                        JSONObject()
                            .put(
                                "op",
                                "native-build-debug"
                            )
                            .put(
                                "elfPath",
                                args[0]
                            )

                    if (args.size >= 2) {
                        request.put(
                            "package",
                            args[1]
                        )
                    }
                    if (args.size >= 3) {
                        request.put(
                            "library",
                            args[2]
                        )
                    }

                    bridge.execute(
                        request
                    )
                }

                else ->
                    throw IllegalArgumentException(
                        "unsupported riftpp-editor action: $action"
                    )
            }

        value
            .put(
                "command",
                "riftpp-editor"
            )
            .put(
                "action",
                action
            )

        return ShellOutcome(
            value.toString(2),
            cwd,
            value
        )
    }


    private fun executeCodynexEditorCommand(
        cwd: String,
        args: MutableList<String>
    ): ShellOutcome {
        val action =
            args.removeFirstOrNull()
                ?.lowercase()
                ?: "help"

        if (action == "help") {
            val output =
                "Codynex Editor development bridge\n" +
                    "codynex-editor status\n" +
                    "codynex-editor ls\n" +
                    "codynex-editor stat <editor-path>\n" +
                    "codynex-editor cat <editor-path>\n" +
                    "codynex-editor write <editor-path> <text>\n" +
                    "codynex-editor push <riftfs-source> <editor-path>\n" +
                    "codynex-editor pull <editor-path> <riftfs-destination>\n" +
                    "codynex-editor push-dir <riftfs-folder> <editor-folder>\n" +
                    "codynex-editor pull-dir <editor-folder> <riftfs-folder>\n" +
                    "codynex-editor mkdir <editor-path>\n" +
                    "codynex-editor mv <from> <to>\n" +
                    "codynex-editor rm <editor-path>\n" +
                    "codynex-editor compile <entry.cx>\n" +
                    "codynex-editor preview <entry.cx>\n" +
                    "codynex-editor native-proof\n" +
                    "codynex-editor build-apk <entry.cx>"

            return ShellOutcome(
                output,
                cwd,
                nativeResult("codynex-editor")
                    .put("action", "help")
            )
        }

        val bridge =
            RiftCodynexEditorBridgeClient(
                appContext
            )

        if (action == "cat") {
            require(args.size == 1) {
                "usage: codynex-editor cat <editor-path>"
            }
            val text =
                bridge.readText(
                    args[0]
                )
            return ShellOutcome(
                text,
                cwd,
                nativeResult("codynex-editor")
                    .put("action", "cat")
                    .put("path", args[0])
                    .put(
                        "bytes",
                        text.toByteArray(
                            Charsets.UTF_8
                        ).size
                    )
            )
        }

        val value =
            when (action) {
                "status" -> {
                    require(args.isEmpty()) {
                        "usage: codynex-editor status"
                    }
                    bridge.execute(
                        JSONObject()
                            .put("op", "status")
                    )
                }

                "ls", "tree" -> {
                    require(args.isEmpty()) {
                        "usage: codynex-editor ls"
                    }
                    bridge.execute(
                        JSONObject()
                            .put("op", "list")
                    )
                }

                "stat" -> {
                    require(args.size == 1) {
                        "usage: codynex-editor stat <editor-path>"
                    }
                    bridge.execute(
                        JSONObject()
                            .put("op", "stat")
                            .put("path", args[0])
                    )
                }

                "write" -> {
                    require(args.size >= 2) {
                        "usage: codynex-editor write <editor-path> <text>"
                    }
                    val path =
                        args.removeFirst()
                    bridge.writeText(
                        path,
                        args.joinToString(" ")
                    )
                }

                "push" -> {
                    require(args.size == 2) {
                        "usage: codynex-editor push <riftfs-source> <editor-path>"
                    }
                    val localDisplay =
                        resolveDisplay(
                            cwd,
                            args[0]
                        )
                    val localFile =
                        resolveFile(
                            localDisplay
                        )
                    require(localFile.isFile) {
                        "local push source is not a file: $localDisplay"
                    }
                    bridge.push(
                        localFile,
                        args[1]
                    )
                        .put(
                            "localPath",
                            localDisplay
                        )
                }

                "pull" -> {
                    require(args.size == 2) {
                        "usage: codynex-editor pull <editor-path> <riftfs-destination>"
                    }
                    val localDisplay =
                        resolveDisplay(
                            cwd,
                            args[1]
                        )
                    val localFile =
                        resolveFile(
                            localDisplay
                        )
                    require(
                        localFile != riftRoot &&
                            !localFile.isDirectory
                    ) {
                        "local pull destination must be a file path"
                    }
                    bridge.pull(
                        args[0],
                        localFile
                    )
                        .put(
                            "localPath",
                            localDisplay
                        )
                }

                "push-dir" -> {
                    require(args.size == 2) {
                        "usage: codynex-editor push-dir <riftfs-folder> <editor-folder>"
                    }
                    val localDisplay =
                        resolveDisplay(
                            cwd,
                            args[0]
                        )
                    val localFolder =
                        resolveFile(
                            localDisplay
                        )
                    require(localFolder.isDirectory) {
                        "local push source is not a directory: $localDisplay"
                    }
                    bridge.pushDirectory(
                        localFolder,
                        args[1]
                    )
                        .put(
                            "localPath",
                            localDisplay
                        )
                }

                "pull-dir" -> {
                    require(args.size == 2) {
                        "usage: codynex-editor pull-dir <editor-folder> <riftfs-folder>"
                    }
                    val localDisplay =
                        resolveDisplay(
                            cwd,
                            args[1]
                        )
                    val localFolder =
                        resolveFile(
                            localDisplay
                        )
                    require(
                        localFolder != riftRoot &&
                            (!localFolder.exists() || localFolder.isDirectory)
                    ) {
                        "local pull destination must be a directory path"
                    }
                    bridge.pullDirectory(
                        args[0],
                        localFolder
                    )
                        .put(
                            "localPath",
                            localDisplay
                        )
                }

                "mkdir" -> {
                    require(args.size == 1) {
                        "usage: codynex-editor mkdir <editor-path>"
                    }
                    bridge.execute(
                        JSONObject()
                            .put("op", "mkdir")
                            .put("path", args[0])
                    )
                }

                "mv" -> {
                    require(args.size == 2) {
                        "usage: codynex-editor mv <from> <to>"
                    }
                    bridge.execute(
                        JSONObject()
                            .put("op", "move")
                            .put("from", args[0])
                            .put("to", args[1])
                    )
                }

                "rm" -> {
                    require(args.size == 1) {
                        "usage: codynex-editor rm <editor-path>"
                    }
                    bridge.execute(
                        JSONObject()
                            .put("op", "delete")
                            .put("path", args[0])
                    )
                }

                "compile" -> {
                    require(args.size == 1) {
                        "usage: codynex-editor compile <entry.cx>"
                    }
                    bridge.execute(
                        JSONObject()
                            .put("op", "compile")
                            .put("entry", args[0])
                    )
                }

                "preview" -> {
                    require(args.size == 1) {
                        "usage: codynex-editor preview <entry.cx>"
                    }
                    bridge.execute(
                        JSONObject()
                            .put("op", "preview")
                            .put("entry", args[0])
                    )
                }

                "native-proof" -> {
                    require(args.isEmpty()) {
                        "usage: codynex-editor native-proof"
                    }
                    bridge.execute(
                        JSONObject()
                            .put("op", "native-proof")
                    )
                }

                "build-apk" -> {
                    require(args.size == 1) {
                        "usage: codynex-editor build-apk <entry.cx>"
                    }
                    bridge.execute(
                        JSONObject()
                            .put("op", "build-apk")
                            .put("entry", args[0])
                    )
                }

                else ->
                    throw IllegalArgumentException(
                        "unsupported codynex-editor action: $action"
                    )
            }

        value
            .put(
                "command",
                "codynex-editor"
            )
            .put(
                "action",
                action
            )

        return ShellOutcome(
            value.toString(2),
            cwd,
            value
        )
    }

    private fun executeRiftCrashCommand(
        cwd: String,
        args: MutableList<String>
    ): ShellOutcome {
        val action = args.removeFirstOrNull()?.lowercase() ?: "help"
        val packageName = args.removeFirstOrNull()
            ?: RiftBuildInstaller.RIFTPP_NATIVE_EDITOR_V1_TARGET_PACKAGE

        require(args.isEmpty()) {
            "usage: riftcrash help|status|start|capture|latest|reset [package]"
        }

        val value = when (action) {
            "help" -> JSONObject()
                .put("schema", "rift.app-diagnostic-help/1")
                .put("usage", "riftcrash help|status|start|capture|latest|reset [package]")
                .put("defaultPackage", RiftBuildInstaller.RIFTPP_NATIVE_EDITOR_V1_TARGET_PACKAGE)
                .put("dumpRoot", "/D:/Diagnostics/riftpp")
                .put("transport", "localhost-udp")
                .put("port", RiftAppDiagnosticBridge.PORT)
                .put("packetBytes", RiftAppDiagnosticBridge.PACKET_BYTES)

            "status" -> RiftAppDiagnosticBridge.status(appContext)

            "start" -> RiftAppDiagnosticBridge.beginLaunch(
                appContext,
                packageName,
                null
            )

            "capture" -> RiftAppDiagnosticBridge.capture(
                appContext,
                packageName
            )

            "latest" -> RiftAppDiagnosticBridge.latest(
                appContext,
                packageName
            )

            "reset" -> RiftAppDiagnosticBridge.reset(
                appContext,
                packageName
            )

            else -> throw IllegalArgumentException(
                "usage: riftcrash help|status|start|capture|latest|reset [package]"
            )
        }

        return ShellOutcome(
            value.toString(2),
            cwd,
            value
        )
    }


    private fun executeRiftppHostCommand(cwd: String, args: MutableList<String>): ShellOutcome {
        val action = args.removeFirstOrNull()?.lowercase() ?: "help"
        if (action == "help") {
            require(args.isEmpty()) {
                "usage: riftpp-host help|status|compile|prove|stage1-selfhost|s2-bootstrap|s2-selfhost|s2-vectors|s3-selfhost|s3-android-r1|s3-android-r3|s3-android-r4|s3-android-r41|s3-android-r5|s3-android-r6|s3-android-r7|s3-android-r8 <riftpp-root> [source-file] [output-capacity]"
            }
            val text =
                "Rift++ approved machine-code compiler host\n" +
                    "riftpp-host status\n" +
                    "riftpp-host compile <riftpp-root> <source-file> [output-capacity]\n" +
                    "riftpp-host prove <riftpp-root> <source-file> [output-capacity]\n" +
                    "riftpp-host stage1-selfhost <riftpp-root>\n" +
                    "riftpp-host s2-bootstrap <riftpp-root>\n" +
                    "riftpp-host s2-selfhost <riftpp-root>\n" +
                    "riftpp-host s2-vectors <riftpp-root>\n" +
                    "riftpp-host s3-selfhost <riftpp-root>\n" +
                    "riftpp-host s3-android-r1 <riftpp-root>\n" +
                    "riftpp-host s3-android-r3 <riftpp-root>\n" +
                    "riftpp-host s3-android-r4 <riftpp-root>\n" +
                    "riftpp-host s3-android-r41 <riftpp-root>\n" +
                    "riftpp-host s3-android-r5 <riftpp-root>\n" +
                    "riftpp-host s3-android-r6 <riftpp-root>\n" +
                    "riftpp-host s3-android-r7 <riftpp-root>\n" +
                    "riftpp-host s3-android-r8 <riftpp-root>"
            return ShellOutcome(text, cwd, nativeResult("riftpp-host").put("action", "help"))
        }
        val hostAbi = if (Process.is64Bit()) "arm64-v8a" else "armeabi-v7a"
        val compilerName =
            if (Process.is64Bit()) "compiler.arm64.hex" else "compiler.arm32.hex"

        if (action == "status") {
            require(args.isEmpty()) { "usage: riftpp-host status" }
            val value = nativeResult("riftpp-host")
                .put("action", "status")
                .put("hostAbi", hostAbi)
                .put("compilerRelativePath", "compiler/$compilerName")
                .put("executionProcess", ":riftppCompiler")
                .put("compilerAuthority", "rift++-machine-code-artifact")
                .put("riftOsCompilerSemantics", false)
            return ShellOutcome(value.toString(2), cwd, value)
        }

        if (action == "stage1-selfhost") {
            require(args.size == 1) {
                "usage: riftpp-host stage1-selfhost <riftpp-root>"
            }
            val rootPath = resolveDisplay(cwd, args[0])
            val root = resolveFile(rootPath)
            require(root.isDirectory) { "Rift++ root is not a directory: $rootPath" }

            val compilerPath = joinDisplay(rootPath, "compiler/$compilerName")
            val compilerFile = resolveFile(compilerPath)
            require(compilerFile.isFile) {
                "Rift++ compiler artifact is missing: $compilerPath"
            }
            require(compilerFile.length() <= 1024L) {
                "Rift++ compiler hex file is unexpectedly large"
            }

            val arm32SourcePath = joinDisplay(rootPath, "stage1/stage1.arm32.rpp")
            val arm64SourcePath = joinDisplay(rootPath, "stage1/stage1.arm64.rpp")
            val arm32SourceFile = resolveFile(arm32SourcePath)
            val arm64SourceFile = resolveFile(arm64SourcePath)
            require(arm32SourceFile.isFile && arm64SourceFile.isFile) {
                "Rift++ Stage1 canonical source files are missing"
            }
            require(
                arm32SourceFile.length() <= 4096L &&
                    arm64SourceFile.length() <= 4096L
            ) {
                "Rift++ Stage1 source exceeds host bound"
            }

            val compilerBytes =
                decodeRiftppCompilerHex(compilerFile.readText(Charsets.UTF_8))
            val value = RiftppCompilerClient.executeStage1SelfHost(
                appContext,
                compilerBytes,
                arm32SourceFile.readBytes(),
                arm64SourceFile.readBytes()
            )
                .put("command", "riftpp-host")
                .put("action", action)
                .put("compilerPath", compilerPath)
                .put("stage1Arm32SourcePath", arm32SourcePath)
                .put("stage1Arm64SourcePath", arm64SourcePath)

            return ShellOutcome(value.toString(2), cwd, value)
        }


        if (action == "s2-bootstrap") {
            require(args.size == 1) {
                "usage: riftpp-host s2-bootstrap <riftpp-root>"
            }
            val rootPath = resolveDisplay(cwd, args[0])
            val root = resolveFile(rootPath)
            require(root.isDirectory) { "Rift++ root is not a directory: $rootPath" }

            val compilerPath = joinDisplay(rootPath, "compiler/$compilerName")
            val compilerFile = resolveFile(compilerPath)
            require(compilerFile.isFile) {
                "Rift++ compiler artifact is missing: $compilerPath"
            }
            require(compilerFile.length() <= 1024L) {
                "Rift++ compiler hex file is unexpectedly large"
            }

            val stage1Arm32SourcePath = joinDisplay(rootPath, "stage1/stage1.arm32.rpp")
            val stage1Arm64SourcePath = joinDisplay(rootPath, "stage1/stage1.arm64.rpp")
            val genAArm32SourcePath =
                joinDisplay(rootPath, "s2/bootstrap/compiler.gena.arm32.rpp")
            val genAArm64SourcePath =
                joinDisplay(rootPath, "s2/bootstrap/compiler.gena.arm64.rpp")
            val proofArm32SourcePath = joinDisplay(rootPath, "s2/ret42.arm32.r2.hex")
            val proofArm64SourcePath = joinDisplay(rootPath, "s2/ret42.arm64.r2.hex")

            val stage1Arm32SourceFile = resolveFile(stage1Arm32SourcePath)
            val stage1Arm64SourceFile = resolveFile(stage1Arm64SourcePath)
            val genAArm32SourceFile = resolveFile(genAArm32SourcePath)
            val genAArm64SourceFile = resolveFile(genAArm64SourcePath)
            val proofArm32SourceFile = resolveFile(proofArm32SourcePath)
            val proofArm64SourceFile = resolveFile(proofArm64SourcePath)

            require(stage1Arm32SourceFile.isFile && stage1Arm64SourceFile.isFile) {
                "Rift++ Stage1 canonical source files are missing"
            }
            require(genAArm32SourceFile.isFile && genAArm64SourceFile.isFile) {
                "Rift++ S2 Generation-A Stage1 source files are missing"
            }
            require(proofArm32SourceFile.isFile && proofArm64SourceFile.isFile) {
                "Rift++ S2 proof source files are missing"
            }
            require(
                stage1Arm32SourceFile.length() <= 4096L &&
                    stage1Arm64SourceFile.length() <= 4096L
            ) {
                "Rift++ Stage1 source exceeds fixed bootstrap bound"
            }
            require(
                genAArm32SourceFile.length() <= 32768L &&
                    genAArm64SourceFile.length() <= 32768L
            ) {
                "Rift++ S2 Generation-A source exceeds fixed bootstrap bound"
            }
            require(
                proofArm32SourceFile.length() <= 128L &&
                    proofArm64SourceFile.length() <= 128L
            ) {
                "Rift++ S2 proof source exceeds fixed bootstrap bound"
            }

            val compilerBytes =
                decodeRiftppCompilerHex(compilerFile.readText(Charsets.UTF_8))
            val proofArm32Source =
                decodeRiftppFixedRecordHex(proofArm32SourceFile.readText(Charsets.UTF_8))
            val proofArm64Source =
                decodeRiftppFixedRecordHex(proofArm64SourceFile.readText(Charsets.UTF_8))

            val value = RiftppCompilerClient.executeS2Bootstrap(
                appContext,
                compilerBytes,
                stage1Arm32SourceFile.readBytes(),
                stage1Arm64SourceFile.readBytes(),
                genAArm32SourceFile.readBytes(),
                genAArm64SourceFile.readBytes(),
                proofArm32Source,
                proofArm64Source
            )
                .put("command", "riftpp-host")
                .put("action", action)
                .put("compilerPath", compilerPath)
                .put("stage1Arm32SourcePath", stage1Arm32SourcePath)
                .put("stage1Arm64SourcePath", stage1Arm64SourcePath)
                .put("genAArm32SourcePath", genAArm32SourcePath)
                .put("genAArm64SourcePath", genAArm64SourcePath)
                .put("proofArm32SourcePath", proofArm32SourcePath)
                .put("proofArm64SourcePath", proofArm64SourcePath)

            return ShellOutcome(value.toString(2), cwd, value)
        }


        if (action == "s2-selfhost") {
            require(args.size == 1) {
                "usage: riftpp-host s2-selfhost <riftpp-root>"
            }
            val rootPath = resolveDisplay(cwd, args[0])
            val root = resolveFile(rootPath)
            require(root.isDirectory) { "Rift++ root is not a directory: $rootPath" }

            val genAName =
                if (Process.is64Bit()) "compiler.gena.arm64.hex" else "compiler.gena.arm32.hex"
            val genAExpectedBytes = if (Process.is64Bit()) 2884 else 3128
            val genAPath = joinDisplay(rootPath, "s2/bootstrap/$genAName")
            val compilerArm32SourcePath =
                joinDisplay(rootPath, "s2/compiler.arm32.r2.hex")
            val compilerArm64SourcePath =
                joinDisplay(rootPath, "s2/compiler.arm64.r2.hex")
            val diagnosticSourcePath =
                if (Process.is64Bit()) {
                    joinDisplay(rootPath, "s2/diagnostics/compiler.reject-offset.arm64.r2.hex")
                } else {
                    joinDisplay(rootPath, "s2/diagnostics/compiler.reject-offset.arm32.r2.hex")
                }
            val proofArm32SourcePath =
                joinDisplay(rootPath, "s2/ret42.arm32.r2.hex")
            val proofArm64SourcePath =
                joinDisplay(rootPath, "s2/ret42.arm64.r2.hex")

            val genAFile = resolveFile(genAPath)
            val compilerArm32SourceFile = resolveFile(compilerArm32SourcePath)
            val compilerArm64SourceFile = resolveFile(compilerArm64SourcePath)
            val diagnosticSourceFile = resolveFile(diagnosticSourcePath)
            val proofArm32SourceFile = resolveFile(proofArm32SourcePath)
            val proofArm64SourceFile = resolveFile(proofArm64SourcePath)

            require(genAFile.isFile) {
                "Rift++ Generation-A artifact is missing: $genAPath"
            }
            require(
                compilerArm32SourceFile.isFile &&
                    compilerArm64SourceFile.isFile
            ) {
                "Rift++ canonical S2 compiler sources are missing"
            }
            require(diagnosticSourceFile.isFile) {
                "Rift++ fixed S2 reject-offset diagnostic source is missing"
            }
            require(
                proofArm32SourceFile.isFile &&
                    proofArm64SourceFile.isFile
            ) {
                "Rift++ S2 proof sources are missing"
            }
            require(genAFile.length() <= 8192L) {
                "Rift++ Generation-A hex file exceeds fixed self-host bound"
            }
            require(
                compilerArm32SourceFile.length() == 23528L &&
                    compilerArm64SourceFile.length() == 23528L
            ) {
                "Rift++ canonical S2 compiler source transport size mismatch"
            }
            require(diagnosticSourceFile.length() == 23528L) {
                "Rift++ fixed S2 reject-offset diagnostic transport size mismatch"
            }
            val diagnosticText = diagnosticSourceFile.readText(Charsets.UTF_8)
            val expectedDiagnosticTransportSha =
                if (Process.is64Bit()) {
                    S2_DIAGNOSTIC_ARM64_TRANSPORT_SHA256
                } else {
                    S2_DIAGNOSTIC_ARM32_TRANSPORT_SHA256
                }
            require(
                sha256Hex(diagnosticText.toByteArray(Charsets.UTF_8)) ==
                    expectedDiagnosticTransportSha
            ) {
                "Rift++ fixed S2 reject-offset diagnostic transport identity mismatch"
            }
            require(
                proofArm32SourceFile.length() <= 128L &&
                    proofArm64SourceFile.length() <= 128L
            ) {
                "Rift++ S2 proof source exceeds fixed self-host bound"
            }

            val genACompiler = decodeRiftppExactRawHex(
                genAFile.readText(Charsets.UTF_8),
                genAExpectedBytes
            )
            val compilerArm32Source =
                decodeRiftppFixedRecordHex(
                    compilerArm32SourceFile.readText(Charsets.UTF_8)
                )
            val compilerArm64Source =
                decodeRiftppFixedRecordHex(
                    compilerArm64SourceFile.readText(Charsets.UTF_8)
                )
            val diagnosticSource = decodeRiftppFixedRecordHex(diagnosticText)
            val proofArm32Source =
                decodeRiftppFixedRecordHex(proofArm32SourceFile.readText(Charsets.UTF_8))
            val proofArm64Source =
                decodeRiftppFixedRecordHex(proofArm64SourceFile.readText(Charsets.UTF_8))

            val value = RiftppCompilerClient.executeS2SelfHost(
                appContext,
                genACompiler,
                compilerArm32Source,
                compilerArm64Source,
                diagnosticSource,
                proofArm32Source,
                proofArm64Source
            )
                .put("command", "riftpp-host")
                .put("action", action)
                .put("genAPath", genAPath)
                .put("compilerArm32SourcePath", compilerArm32SourcePath)
                .put("compilerArm64SourcePath", compilerArm64SourcePath)
                .put("diagnosticSourcePath", diagnosticSourcePath)
                .put("proofArm32SourcePath", proofArm32SourcePath)
                .put("proofArm64SourcePath", proofArm64SourcePath)

            return ShellOutcome(value.toString(2), cwd, value)
        }

        if (action == "s3-selfhost") {
            require(args.size == 1) {
                "usage: riftpp-host s3-selfhost <riftpp-root>"
            }
            val rootPath = resolveDisplay(cwd, args[0])
            val root = resolveFile(rootPath)
            require(root.isDirectory) { "Rift++ root is not a directory: $rootPath" }

            val promotedName =
                if (Process.is64Bit()) "compiler.genc.arm64.hex" else "compiler.genc.arm32.hex"
            val promotedPath = joinDisplay(rootPath, "s2/promoted/$promotedName")
            val source32Path = joinDisplay(rootPath, "s3/candidate-b/compiler.arm32.r3.hex")
            val source64Path = joinDisplay(rootPath, "s3/candidate-b/compiler.arm64.r3.hex")
            val proof32Path = joinDisplay(rootPath, "s2/ret42.arm32.r2.hex")
            val proof64Path = joinDisplay(rootPath, "s2/ret42.arm64.r2.hex")

            val promotedFile = resolveFile(promotedPath)
            val source32File = resolveFile(source32Path)
            val source64File = resolveFile(source64Path)
            val proof32File = resolveFile(proof32Path)
            val proof64File = resolveFile(proof64Path)
            require(promotedFile.isFile) { "Rift++ promoted S2 Generation-C artifact is missing" }
            require(source32File.isFile && source64File.isFile) {
                "Rift++ S3 Candidate-B compiler sources are missing"
            }
            require(proof32File.isFile && proof64File.isFile) {
                "Rift++ S3 fixed proof sources are missing"
            }
            require(promotedFile.length() == 88577L) {
                "Rift++ promoted S2 Generation-C transport size mismatch"
            }
            require(source32File.length() == 17544L && source64File.length() == 17544L) {
                "Rift++ S3 Candidate-B transport size mismatch"
            }
            require(proof32File.length() <= 128L && proof64File.length() <= 128L) {
                "Rift++ S3 proof source exceeds fixed bound"
            }

            val promotedText = promotedFile.readText(Charsets.UTF_8)
            val expectedPromotedTransportSha =
                if (Process.is64Bit()) S2_PROMOTED_C_ARM64_TRANSPORT_SHA256
                else S2_PROMOTED_C_ARM32_TRANSPORT_SHA256
            require(
                sha256Hex(promotedText.toByteArray(Charsets.UTF_8)) == expectedPromotedTransportSha
            ) {
                "Rift++ promoted S2 Generation-C transport identity mismatch"
            }
            val source32Text = source32File.readText(Charsets.UTF_8)
            val source64Text = source64File.readText(Charsets.UTF_8)
            require(
                sha256Hex(source32Text.toByteArray(Charsets.UTF_8)) ==
                    S3_CANDIDATE_B_ARM32_TRANSPORT_SHA256 &&
                    sha256Hex(source64Text.toByteArray(Charsets.UTF_8)) ==
                    S3_CANDIDATE_B_ARM64_TRANSPORT_SHA256
            ) {
                "Rift++ S3 Candidate-B transport identity mismatch"
            }

            val promotedCompiler = decodeRiftppExactRawHex(promotedText, 44288)
            val source32 = decodeRiftppFixedRecordHex(source32Text)
            val source64 = decodeRiftppFixedRecordHex(source64Text)
            val proof32 = decodeRiftppFixedRecordHex(proof32File.readText(Charsets.UTF_8))
            val proof64 = decodeRiftppFixedRecordHex(proof64File.readText(Charsets.UTF_8))

            val value = RiftppCompilerClient.executeS3SelfHost(
                appContext,
                promotedCompiler,
                source32,
                source64,
                proof32,
                proof64
            )
                .put("command", "riftpp-host")
                .put("action", action)
                .put("promotedS2CompilerPath", promotedPath)
                .put("compilerArm32SourcePath", source32Path)
                .put("compilerArm64SourcePath", source64Path)
                .put("proofArm32SourcePath", proof32Path)
                .put("proofArm64SourcePath", proof64Path)

            return ShellOutcome(value.toString(2), cwd, value)
        }

        if (action == "s3-android-r1") {
            require(args.size == 1) {
                "usage: riftpp-host s3-android-r1 <riftpp-root>"
            }

            require(!Process.is64Bit()) {
                "Rift++ Android Native R1 currently requires the ARM32 host lane"
            }

            val rootPath =
                resolveDisplay(
                    cwd,
                    args[0]
                )
            val root =
                resolveFile(
                    rootPath
                )

            require(root.isDirectory) {
                "Rift++ root is not a directory: $rootPath"
            }

            val compilerPath =
                joinDisplay(
                    rootPath,
                    "s3/frozen/compiler.arm32.native.hex"
                )
            val entryPath =
                joinDisplay(
                    rootPath,
                    "standalone/android-native-r1/entry.arm32.r3.hex"
                )
            val emitterPath =
                joinDisplay(
                    rootPath,
                    "standalone/android-native-r1/elf32-emitter.arm32.r3.hex"
                )
            val outputPath =
                joinDisplay(
                    rootPath,
                    "standalone/android-native-r1/libriftpp_editor_native_r1.so"
                )

            val compilerFile =
                resolveFile(
                    compilerPath
                )
            val entryFile =
                resolveFile(
                    entryPath
                )
            val emitterFile =
                resolveFile(
                    emitterPath
                )
            val outputFile =
                resolveFile(
                    outputPath
                )

            require(
                compilerFile.isFile &&
                    compilerFile.length() ==
                        33057L
            ) {
                "Rift++ frozen S3 ARM32 compiler transport is missing or drifted"
            }
            require(
                entryFile.isFile &&
                    entryFile.length() ==
                        2074L
            ) {
                "Rift++ Android R1 entry source is missing or drifted"
            }
            require(
                emitterFile.isFile &&
                    emitterFile.length() ==
                        3621L
            ) {
                "Rift++ Android R1 ELF emitter source is missing or drifted"
            }

            val compilerTransport =
                compilerFile.readBytes()
            val entryTransport =
                entryFile.readBytes()
            val emitterTransport =
                emitterFile.readBytes()

            require(
                sha256Hex(
                    compilerTransport
                ) ==
                    "950e4ad52cb57b73c1348282903529488619373921c1bd37b73f6ddfa93b103a"
            ) {
                "Rift++ frozen S3 ARM32 compiler transport identity mismatch"
            }
            require(
                sha256Hex(
                    entryTransport
                ) ==
                    "5ce665811c7753f1b55d8d0cfe0cac3a1cafcd8d9f8b43e35acbb0b03d6643c6"
            ) {
                "Rift++ Android R1 entry transport identity mismatch"
            }
            require(
                sha256Hex(
                    emitterTransport
                ) ==
                    "43641344176878c30c116d0e1c4c67f9631a8773a35171beaa57857a8306267a"
            ) {
                "Rift++ Android R1 ELF emitter transport identity mismatch"
            }

            val compilerBytes =
                decodeRiftppExactRawHex(
                    compilerTransport
                        .toString(
                            Charsets.UTF_8
                        ),
                    16528
                )
            val entrySource =
                decodeRiftppFixedRecordHex(
                    entryTransport
                        .toString(
                            Charsets.UTF_8
                        )
                )
            val emitterSource =
                decodeRiftppFixedRecordHex(
                    emitterTransport
                        .toString(
                            Charsets.UTF_8
                        )
                )

            val value =
                RiftppCompilerClient
                    .executeS3Emit(
                        appContext,
                        compilerBytes,
                        entrySource,
                        emitterSource
                    )
                    .put(
                        "command",
                        "riftpp-host"
                    )
                    .put(
                        "action",
                        action
                    )
                    .put(
                        "compilerPath",
                        compilerPath
                    )
                    .put(
                        "entrySourcePath",
                        entryPath
                    )
                    .put(
                        "emitterSourcePath",
                        emitterPath
                    )

            if (
                value.optString(
                    "status"
                ) ==
                    "success"
            ) {
                val elfHex =
                    value.optString(
                        "elfHex"
                    )

                val elfBytes =
                    decodeRiftppExactRawHex(
                        elfHex,
                        972
                    )

                require(
                    sha256Hex(
                        elfBytes
                    ) ==
                        value.optString(
                            "elfSha256"
                        )
                ) {
                    "Rift++ Android R1 emitted output transport hash mismatch"
                }

                atomicWrite(
                    outputFile,
                    elfBytes
                )

                require(
                    outputFile.isFile &&
                        outputFile.length() ==
                            972L &&
                        sha256Hex(
                            outputFile.readBytes()
                        ) ==
                            value.optString(
                                "elfSha256"
                            )
                ) {
                    "Rift++ Android R1 output publish verification failed"
                }

                value
                    .put(
                        "outputPath",
                        outputPath
                    )
                    .put(
                        "outputPublished",
                        true
                    )
            }

            return ShellOutcome(
                value.toString(2),
                cwd,
                value
            )
        }


        if (action == "s3-android-r3") {
            require(args.size == 1) {
                "usage: riftpp-host s3-android-r3 <riftpp-root>"
            }

            require(!Process.is64Bit()) {
                "Rift++ Android Native R3 currently requires the ARM32 host lane"
            }

            val rootPath =
                resolveDisplay(
                    cwd,
                    args[0]
                )
            val root =
                resolveFile(
                    rootPath
                )

            require(root.isDirectory) {
                "Rift++ root is not a directory: $rootPath"
            }

            val compilerPath =
                joinDisplay(
                    rootPath,
                    "s3/frozen/compiler.arm32.native.hex"
                )
            val entryPath =
                joinDisplay(
                    rootPath,
                    "standalone/android-native-r1/entry.arm32.r3.hex"
                )
            val emitterPath =
                joinDisplay(
                    rootPath,
                    "standalone/android-native-r1/elf32-emitter.arm32.r3.hex"
                )
            val linkerPath =
                joinDisplay(
                    rootPath,
                    "standalone/android-native-r1/elf32-r3-frame-linker.arm32.r3.hex"
                )
            val outputPath =
                joinDisplay(
                    rootPath,
                    "standalone/android-native-r1/libriftpp_editor_native_r3.so"
                )

            val compilerFile =
                resolveFile(
                    compilerPath
                )
            val entryFile =
                resolveFile(
                    entryPath
                )
            val emitterFile =
                resolveFile(
                    emitterPath
                )
            val linkerFile =
                resolveFile(
                    linkerPath
                )
            val outputFile =
                resolveFile(
                    outputPath
                )

            require(
                compilerFile.isFile &&
                    compilerFile.length() ==
                        33057L
            ) {
                "Rift++ frozen S3 ARM32 compiler transport is missing or drifted"
            }
            require(
                entryFile.isFile &&
                    entryFile.length() ==
                        2074L
            ) {
                "Rift++ Android R2 entry source is missing or drifted"
            }
            require(
                emitterFile.isFile &&
                    emitterFile.length() ==
                        3621L
            ) {
                "Rift++ Android R2 ELF emitter source is missing or drifted"
            }
            require(
                linkerFile.isFile &&
                    linkerFile.length() ==
                        4437L
            ) {
                "Rift++ Android R3 frame-linker source is missing or drifted"
            }

            val compilerTransport =
                compilerFile.readBytes()
            val entryTransport =
                entryFile.readBytes()
            val emitterTransport =
                emitterFile.readBytes()
            val linkerTransport =
                linkerFile.readBytes()

            require(
                sha256Hex(
                    compilerTransport
                ) ==
                    "950e4ad52cb57b73c1348282903529488619373921c1bd37b73f6ddfa93b103a"
            ) {
                "Rift++ frozen S3 ARM32 compiler transport identity mismatch"
            }
            require(
                sha256Hex(
                    entryTransport
                ) ==
                    "5ce665811c7753f1b55d8d0cfe0cac3a1cafcd8d9f8b43e35acbb0b03d6643c6"
            ) {
                "Rift++ Android R2 entry transport identity mismatch"
            }
            require(
                sha256Hex(
                    emitterTransport
                ) ==
                    "43641344176878c30c116d0e1c4c67f9631a8773a35171beaa57857a8306267a"
            ) {
                "Rift++ Android R2 ELF emitter transport identity mismatch"
            }
            require(
                sha256Hex(
                    linkerTransport
                ) ==
                    "18782a0cb8719b04fcac338667ca99f22e58d0e3c52b5a09d18ea4773c0173b6"
            ) {
                "Rift++ Android R3 frame-linker transport identity mismatch"
            }

            val compilerBytes =
                decodeRiftppExactRawHex(
                    compilerTransport
                        .toString(
                            Charsets.UTF_8
                        ),
                    16528
                )
            val entrySource =
                decodeRiftppFixedRecordHex(
                    entryTransport
                        .toString(
                            Charsets.UTF_8
                        )
                )
            val emitterSource =
                decodeRiftppFixedRecordHex(
                    emitterTransport
                        .toString(
                            Charsets.UTF_8
                        )
                )
            val linkerSource =
                decodeRiftppFixedRecordHex(
                    linkerTransport
                        .toString(
                            Charsets.UTF_8
                        )
                )

            val base =
                RiftppCompilerClient
                    .executeS3Emit(
                        appContext,
                        compilerBytes,
                        entrySource,
                        emitterSource
                    )

            if (
                base.optString(
                    "status"
                ) !=
                    "success"
            ) {
                return ShellOutcome(
                    base
                        .put(
                            "command",
                            "riftpp-host"
                        )
                        .put(
                            "action",
                            action
                        )
                        .put(
                            "stage",
                            "r2-base"
                        )
                        .toString(2),
                    cwd,
                    base
                )
            }

            val baseElf =
                decodeRiftppExactRawHex(
                    base.optString(
                        "elfHex"
                    ),
                    972
                )

            require(
                sha256Hex(
                    baseElf
                ) ==
                    "d9669d97c6f0f0225b8624818dc9f2f0dad4611ded757ac48dfea1b9cba06d46" &&
                    sha256Hex(
                        baseElf
                    ) ==
                        base.optString(
                            "elfSha256"
                        )
            ) {
                "Rift++ Android R3 base ELF identity mismatch"
            }

            val value =
                RiftppCompilerClient
                    .executeS3FrameLink(
                        appContext,
                        compilerBytes,
                        linkerSource,
                        baseElf
                    )
                    .put(
                        "command",
                        "riftpp-host"
                    )
                    .put(
                        "action",
                        action
                    )
                    .put(
                        "compilerPath",
                        compilerPath
                    )
                    .put(
                        "entrySourcePath",
                        entryPath
                    )
                    .put(
                        "emitterSourcePath",
                        emitterPath
                    )
                    .put(
                        "linkerSourcePath",
                        linkerPath
                    )
                    .put(
                        "r2BaseElfSha256",
                        base.optString(
                            "elfSha256"
                        )
                    )

            if (
                value.optString(
                    "status"
                ) ==
                    "success"
            ) {
                val elfBytes =
                    decodeRiftppExactRawHex(
                        value.optString(
                            "elfHex"
                        ),
                        1196
                    )

                require(
                    sha256Hex(
                        elfBytes
                    ) ==
                        value.optString(
                            "elfSha256"
                        )
                ) {
                    "Rift++ Android R3 emitted output transport hash mismatch"
                }

                atomicWrite(
                    outputFile,
                    elfBytes
                )

                require(
                    outputFile.isFile &&
                        outputFile.length() ==
                            1196L &&
                        sha256Hex(
                            outputFile.readBytes()
                        ) ==
                            value.optString(
                                "elfSha256"
                            )
                ) {
                    "Rift++ Android R3 output publish verification failed"
                }

                value
                    .put(
                        "outputPath",
                        outputPath
                    )
                    .put(
                        "outputPublished",
                        true
                    )
            }

            return ShellOutcome(
                value.toString(2),
                cwd,
                value
            )
        }


        if (action == "s3-android-r4") {
            require(args.size == 1) {
                "usage: riftpp-host s3-android-r4 <riftpp-root>"
            }

            require(!Process.is64Bit()) {
                "Rift++ Android Native R4 currently requires the ARM32 host lane"
            }

            val rootPath =
                resolveDisplay(
                    cwd,
                    args[0]
                )
            val root =
                resolveFile(
                    rootPath
                )

            require(root.isDirectory) {
                "Rift++ root is not a directory: $rootPath"
            }

            val compilerPath =
                joinDisplay(
                    rootPath,
                    "s3/frozen/compiler.arm32.native.hex"
                )
            val patcherPath =
                joinDisplay(
                    rootPath,
                    "standalone/android-native-r1/elf32-r4-topbar-patcher.arm32.r3.hex"
                )
            val basePath =
                joinDisplay(
                    rootPath,
                    "standalone/android-native-r1/libriftpp_editor_native_r3.first-frame-proven.so"
                )
            val outputPath =
                joinDisplay(
                    rootPath,
                    "standalone/android-native-r1/libriftpp_editor_native_r4.so"
                )

            val compilerFile =
                resolveFile(
                    compilerPath
                )
            val patcherFile =
                resolveFile(
                    patcherPath
                )
            val baseFile =
                resolveFile(
                    basePath
                )
            val outputFile =
                resolveFile(
                    outputPath
                )

            require(
                compilerFile.isFile &&
                    compilerFile.length() ==
                        33057L
            ) {
                "Rift++ frozen S3 ARM32 compiler transport is missing or drifted"
            }
            require(
                patcherFile.isFile &&
                    patcherFile.length() ==
                        1122L
            ) {
                "Rift++ Android R4 UI patcher source is missing or drifted"
            }
            require(
                baseFile.isFile &&
                    baseFile.length() ==
                        1196L
            ) {
                "Rift++ promoted R3 base ELF is missing or drifted"
            }

            val compilerTransport =
                compilerFile.readBytes()
            val patcherTransport =
                patcherFile.readBytes()
            val baseElf =
                baseFile.readBytes()

            require(
                sha256Hex(
                    compilerTransport
                ) ==
                    "950e4ad52cb57b73c1348282903529488619373921c1bd37b73f6ddfa93b103a"
            ) {
                "Rift++ frozen S3 ARM32 compiler transport identity mismatch"
            }
            require(
                sha256Hex(
                    patcherTransport
                ) ==
                    "09afaeda7cbf30281718ec6e354838e75be3d6228297f0b4b70f815253610706"
            ) {
                "Rift++ Android R4 UI patcher transport identity mismatch"
            }
            require(
                sha256Hex(
                    baseElf
                ) ==
                    "44f8b266aef910acaaedfe3a5f6cf7b9f1e029acd6e1c2e310410a22568be28e"
            ) {
                "Rift++ promoted R3 base ELF identity mismatch"
            }

            val compilerBytes =
                decodeRiftppExactRawHex(
                    compilerTransport
                        .toString(
                            Charsets.UTF_8
                        ),
                    16528
                )
            val patcherSource =
                decodeRiftppFixedRecordHex(
                    patcherTransport
                        .toString(
                            Charsets.UTF_8
                        )
                )

            require(
                patcherSource.size == 528 &&
                    sha256Hex(
                        patcherSource
                    ) ==
                        "9cf4f6c7670d50f2b6caaeca2f24d20949811291fbe0dfdcbad3a104c78332af"
            ) {
                "Rift++ Android R4 decoded UI patcher identity mismatch"
            }

            val value =
                RiftppCompilerClient
                    .executeS3UiPatch(
                        appContext,
                        compilerBytes,
                        patcherSource,
                        baseElf
                    )
                    .put(
                        "command",
                        "riftpp-host"
                    )
                    .put(
                        "action",
                        action
                    )
                    .put(
                        "compilerPath",
                        compilerPath
                    )
                    .put(
                        "patcherSourcePath",
                        patcherPath
                    )
                    .put(
                        "r3BaseElfPath",
                        basePath
                    )
                    .put(
                        "r3BaseElfSha256",
                        sha256Hex(
                            baseElf
                        )
                    )

            if (
                value.optString(
                    "status"
                ) ==
                    "success"
            ) {
                val elfBytes =
                    decodeRiftppExactRawHex(
                        value.optString(
                            "elfHex"
                        ),
                        1196
                    )

                require(
                    sha256Hex(
                        elfBytes
                    ) ==
                        value.optString(
                            "elfSha256"
                        )
                ) {
                    "Rift++ Android R4 emitted output transport hash mismatch"
                }

                atomicWrite(
                    outputFile,
                    elfBytes
                )

                require(
                    outputFile.isFile &&
                        outputFile.length() ==
                            1196L &&
                        sha256Hex(
                            outputFile.readBytes()
                        ) ==
                            value.optString(
                                "elfSha256"
                            )
                ) {
                    "Rift++ Android R4 output publish verification failed"
                }

                value
                    .put(
                        "outputPath",
                        outputPath
                    )
                    .put(
                        "outputPublished",
                        true
                    )
            }

            return ShellOutcome(
                value.toString(2),
                cwd,
                value
            )
        }


        if (action == "s3-android-r41") {
            require(args.size == 1) {
                "usage: riftpp-host s3-android-r41 <riftpp-root>"
            }

            require(!Process.is64Bit()) {
                "Rift++ Android Native R4.1 currently requires the ARM32 host lane"
            }

            val rootPath =
                resolveDisplay(
                    cwd,
                    args[0]
                )
            val root =
                resolveFile(
                    rootPath
                )

            require(root.isDirectory) {
                "Rift++ root is not a directory: $rootPath"
            }

            val compilerPath =
                joinDisplay(
                    rootPath,
                    "s3/frozen/compiler.arm32.native.hex"
                )
            val patcherPath =
                joinDisplay(
                    rootPath,
                    "standalone/android-native-r1/elf32-r41-inputqueue-patcher.arm32.r3.hex"
                )
            val basePath =
                joinDisplay(
                    rootPath,
                    "standalone/android-native-r1/libriftpp_editor_native_r4.first-surface-proven.so"
                )
            val outputPath =
                joinDisplay(
                    rootPath,
                    "standalone/android-native-r1/libriftpp_editor_native_r41.so"
                )

            val compilerFile =
                resolveFile(
                    compilerPath
                )
            val patcherFile =
                resolveFile(
                    patcherPath
                )
            val baseFile =
                resolveFile(
                    basePath
                )
            val outputFile =
                resolveFile(
                    outputPath
                )

            require(
                compilerFile.isFile &&
                    compilerFile.length() ==
                        33057L
            ) {
                "Rift++ frozen S3 ARM32 compiler transport is missing or drifted"
            }
            require(
                patcherFile.isFile &&
                    patcherFile.length() ==
                        10693L
            ) {
                "Rift++ Android R4.1 input patcher source is missing or drifted"
            }
            require(
                baseFile.isFile &&
                    baseFile.length() ==
                        1196L
            ) {
                "Rift++ promoted R4 base ELF is missing or drifted"
            }

            val compilerTransport =
                compilerFile.readBytes()
            val patcherTransport =
                patcherFile.readBytes()
            val baseElf =
                baseFile.readBytes()

            require(
                sha256Hex(
                    compilerTransport
                ) ==
                    "950e4ad52cb57b73c1348282903529488619373921c1bd37b73f6ddfa93b103a"
            ) {
                "Rift++ frozen S3 ARM32 compiler transport identity mismatch"
            }
            require(
                sha256Hex(
                    patcherTransport
                ) ==
                    "66c8949f80ac23b729bf92e5188e1f3b4e5058dce81cca94f979602e25e18551"
            ) {
                "Rift++ Android R4.1 input patcher transport identity mismatch"
            }
            require(
                sha256Hex(
                    baseElf
                ) ==
                    "90dc170d574f9d3b0947cccb013b82f5a2bf0b3c3b65013e0fc5050f4da76f9a"
            ) {
                "Rift++ promoted R4 base ELF identity mismatch"
            }

            val compilerBytes =
                decodeRiftppExactRawHex(
                    compilerTransport
                        .toString(
                            Charsets.UTF_8
                        ),
                    16528
                )
            val patcherSource =
                decodeRiftppFixedRecordHex(
                    patcherTransport
                        .toString(
                            Charsets.UTF_8
                        )
                )

            require(
                patcherSource.size == 5032 &&
                    sha256Hex(
                        patcherSource
                    ) ==
                        "59a3ae7494697f83acf34d6310686b7e1b8d6ec54c8d25643f4bc917e58ee6a5"
            ) {
                "Rift++ Android R4.1 decoded input patcher identity mismatch"
            }

            val value =
                RiftppCompilerClient
                    .executeS3InputHarden(
                        appContext,
                        compilerBytes,
                        patcherSource,
                        baseElf
                    )
                    .put(
                        "command",
                        "riftpp-host"
                    )
                    .put(
                        "action",
                        action
                    )
                    .put(
                        "compilerPath",
                        compilerPath
                    )
                    .put(
                        "patcherSourcePath",
                        patcherPath
                    )
                    .put(
                        "r4BaseElfPath",
                        basePath
                    )
                    .put(
                        "r4BaseElfSha256",
                        sha256Hex(
                            baseElf
                        )
                    )

            if (
                value.optString(
                    "status"
                ) ==
                    "success"
            ) {
                val elfBytes =
                    decodeRiftppExactRawHex(
                        value.optString(
                            "elfHex"
                        ),
                        1880
                    )

                require(
                    sha256Hex(
                        elfBytes
                    ) ==
                        value.optString(
                            "elfSha256"
                        )
                ) {
                    "Rift++ Android R4.1 emitted output transport hash mismatch"
                }

                atomicWrite(
                    outputFile,
                    elfBytes
                )

                require(
                    outputFile.isFile &&
                        outputFile.length() ==
                            1880L &&
                        sha256Hex(
                            outputFile.readBytes()
                        ) ==
                            value.optString(
                                "elfSha256"
                            )
                ) {
                    "Rift++ Android R4.1 output publish verification failed"
                }

                value
                    .put(
                        "outputPath",
                        outputPath
                    )
                    .put(
                        "outputPublished",
                        true
                    )
            }

            return ShellOutcome(
                value.toString(2),
                cwd,
                value
            )
        }


        if (action == "s3-android-r5") {
            require(args.size == 1) {
                "usage: riftpp-host s3-android-r5 <riftpp-root>"
            }

            require(!Process.is64Bit()) {
                "Rift++ Android Native R5 currently requires the ARM32 host lane"
            }

            val rootPath =
                resolveDisplay(
                    cwd,
                    args[0]
                )
            val root =
                resolveFile(
                    rootPath
                )

            require(root.isDirectory) {
                "Rift++ root is not a directory: $rootPath"
            }

            val compilerPath =
                joinDisplay(
                    rootPath,
                    "s3/frozen/compiler.arm32.native.hex"
                )
            val patcherPath =
                joinDisplay(
                    rootPath,
                    "standalone/android-native-r1/elf32-r5-glyph-patcher.arm32.r3.hex"
                )
            val basePath =
                joinDisplay(
                    rootPath,
                    "standalone/android-native-r1/libriftpp_editor_native_r41.stability-proven.so"
                )
            val outputPath =
                joinDisplay(
                    rootPath,
                    "standalone/android-native-r1/libriftpp_editor_native_r5.so"
                )

            val compilerFile =
                resolveFile(
                    compilerPath
                )
            val patcherFile =
                resolveFile(
                    patcherPath
                )
            val baseFile =
                resolveFile(
                    basePath
                )
            val outputFile =
                resolveFile(
                    outputPath
                )

            require(
                compilerFile.isFile &&
                    compilerFile.length() ==
                        33057L
            ) {
                "Rift++ frozen S3 ARM32 compiler transport is missing or drifted"
            }
            require(
                patcherFile.isFile &&
                    patcherFile.length() ==
                        4692L
            ) {
                "Rift++ Android R5 glyph patcher source is missing or drifted"
            }
            require(
                baseFile.isFile &&
                    baseFile.length() ==
                        1880L
            ) {
                "Rift++ promoted R4.1 stability base ELF is missing or drifted"
            }

            val compilerTransport =
                compilerFile.readBytes()
            val patcherTransport =
                patcherFile.readBytes()
            val baseElf =
                baseFile.readBytes()

            require(
                sha256Hex(
                    compilerTransport
                ) ==
                    "950e4ad52cb57b73c1348282903529488619373921c1bd37b73f6ddfa93b103a"
            ) {
                "Rift++ frozen S3 ARM32 compiler transport identity mismatch"
            }
            require(
                sha256Hex(
                    patcherTransport
                ) ==
                    "ba8f4978c05c0421591fde9ec406cfff1c03373b67341c35838a32a06af29818"
            ) {
                "Rift++ Android R5 glyph patcher transport identity mismatch"
            }
            require(
                sha256Hex(
                    baseElf
                ) ==
                    "abf2b0789f72fbc885a5c73eeb10cf6199fb9c5d6b29e4f8024b50b3a9fec610"
            ) {
                "Rift++ promoted R4.1 stability base ELF identity mismatch"
            }

            val compilerBytes =
                decodeRiftppExactRawHex(
                    compilerTransport
                        .toString(
                            Charsets.UTF_8
                        ),
                    16528
                )
            val patcherSource =
                decodeRiftppFixedRecordHex(
                    patcherTransport
                        .toString(
                            Charsets.UTF_8
                        )
                )

            require(
                patcherSource.size == 2208 &&
                    sha256Hex(
                        patcherSource
                    ) ==
                        "3510e1dccfe1025f2cfbab7c9723b90b5cf8274c12d0bd6975928d8bd85c5f34"
            ) {
                "Rift++ Android R5 decoded glyph patcher identity mismatch"
            }

            val value =
                RiftppCompilerClient
                    .executeS3GlyphRender(
                        appContext,
                        compilerBytes,
                        patcherSource,
                        baseElf
                    )
                    .put(
                        "command",
                        "riftpp-host"
                    )
                    .put(
                        "action",
                        action
                    )
                    .put(
                        "compilerPath",
                        compilerPath
                    )
                    .put(
                        "patcherSourcePath",
                        patcherPath
                    )
                    .put(
                        "r41BaseElfPath",
                        basePath
                    )
                    .put(
                        "r41BaseElfSha256",
                        sha256Hex(
                            baseElf
                        )
                    )

            if (
                value.optString(
                    "status"
                ) ==
                    "success"
            ) {
                val elfBytes =
                    decodeRiftppExactRawHex(
                        value.optString(
                            "elfHex"
                        ),
                        2368
                    )

                require(
                    sha256Hex(
                        elfBytes
                    ) ==
                        value.optString(
                            "elfSha256"
                        )
                ) {
                    "Rift++ Android R5 emitted output transport hash mismatch"
                }

                atomicWrite(
                    outputFile,
                    elfBytes
                )

                require(
                    outputFile.isFile &&
                        outputFile.length() ==
                            2368L &&
                        sha256Hex(
                            outputFile.readBytes()
                        ) ==
                            value.optString(
                                "elfSha256"
                            )
                ) {
                    "Rift++ Android R5 output publish verification failed"
                }

                value
                    .put(
                        "outputPath",
                        outputPath
                    )
                    .put(
                        "outputPublished",
                        true
                    )
            }

            return ShellOutcome(
                value.toString(2),
                cwd,
                value
            )
        }


        if (action == "s3-android-r6") {
            require(args.size == 1) {
                "usage: riftpp-host s3-android-r6 <riftpp-root>"
            }

            require(!Process.is64Bit()) {
                "Rift++ Android Native R6 currently requires the ARM32 host lane"
            }

            val rootPath =
                resolveDisplay(
                    cwd,
                    args[0]
                )
            val root =
                resolveFile(
                    rootPath
                )

            require(root.isDirectory) {
                "Rift++ root is not a directory: $rootPath"
            }

            val compilerPath =
                joinDisplay(
                    rootPath,
                    "s3/frozen/compiler.arm32.native.hex"
                )
            val patcherPath =
                joinDisplay(
                    rootPath,
                    "standalone/android-native-r1/elf32-r6-focus-patcher.arm32.r3.hex"
                )
            val basePath =
                joinDisplay(
                    rootPath,
                    "standalone/android-native-r1/libriftpp_editor_native_r5.glyph-proven.so"
                )
            val outputPath =
                joinDisplay(
                    rootPath,
                    "standalone/android-native-r1/libriftpp_editor_native_r6.so"
                )

            val compilerFile =
                resolveFile(
                    compilerPath
                )
            val patcherFile =
                resolveFile(
                    patcherPath
                )
            val baseFile =
                resolveFile(
                    basePath
                )
            val outputFile =
                resolveFile(
                    outputPath
                )

            require(
                compilerFile.isFile &&
                    compilerFile.length() ==
                        33057L
            ) {
                "Rift++ frozen S3 ARM32 compiler transport is missing or drifted"
            }
            require(
                patcherFile.isFile &&
                    patcherFile.length() ==
                        4080L
            ) {
                "Rift++ Android R6 focus patcher source is missing or drifted"
            }
            require(
                baseFile.isFile &&
                    baseFile.length() ==
                        2368L
            ) {
                "Rift++ promoted R5 glyph base ELF is missing or drifted"
            }

            val compilerTransport =
                compilerFile.readBytes()
            val patcherTransport =
                patcherFile.readBytes()
            val baseElf =
                baseFile.readBytes()

            require(
                sha256Hex(
                    compilerTransport
                ) ==
                    "950e4ad52cb57b73c1348282903529488619373921c1bd37b73f6ddfa93b103a"
            ) {
                "Rift++ frozen S3 ARM32 compiler transport identity mismatch"
            }
            require(
                sha256Hex(
                    patcherTransport
                ) ==
                    "340d192199408411775baeb3be8a2d20b18c42bdfcb19253a2941da1ddd3f40f"
            ) {
                "Rift++ Android R6 focus patcher transport identity mismatch"
            }
            require(
                sha256Hex(
                    baseElf
                ) ==
                    "1702e86b8672697f1139eb105b6c69e9ce455222f90a31d77123bac860b7c2bc"
            ) {
                "Rift++ promoted R5 glyph base ELF identity mismatch"
            }

            val compilerBytes =
                decodeRiftppExactRawHex(
                    compilerTransport
                        .toString(
                            Charsets.UTF_8
                        ),
                    16528
                )
            val patcherSource =
                decodeRiftppFixedRecordHex(
                    patcherTransport
                        .toString(
                            Charsets.UTF_8
                        )
                )

            require(
                patcherSource.size == 1920 &&
                    sha256Hex(
                        patcherSource
                    ) ==
                        "10ffd04fb115c6229c1114ccef4f1d71ab5b36a58f11a993159a0e9fe2c200b4"
            ) {
                "Rift++ Android R6 decoded focus patcher identity mismatch"
            }

            val value =
                RiftppCompilerClient
                    .executeS3FocusSemantic(
                        appContext,
                        compilerBytes,
                        patcherSource,
                        baseElf
                    )
                    .put(
                        "command",
                        "riftpp-host"
                    )
                    .put(
                        "action",
                        action
                    )
                    .put(
                        "compilerPath",
                        compilerPath
                    )
                    .put(
                        "patcherSourcePath",
                        patcherPath
                    )
                    .put(
                        "r5BaseElfPath",
                        basePath
                    )
                    .put(
                        "r5BaseElfSha256",
                        sha256Hex(
                            baseElf
                        )
                    )

            if (
                value.optString(
                    "status"
                ) ==
                    "success"
            ) {
                val elfBytes =
                    decodeRiftppExactRawHex(
                        value.optString(
                            "elfHex"
                        ),
                        2768
                    )

                require(
                    sha256Hex(
                        elfBytes
                    ) ==
                        value.optString(
                            "elfSha256"
                        )
                ) {
                    "Rift++ Android R6 emitted output transport hash mismatch"
                }

                atomicWrite(
                    outputFile,
                    elfBytes
                )

                require(
                    outputFile.isFile &&
                        outputFile.length() ==
                            2768L &&
                        sha256Hex(
                            outputFile.readBytes()
                        ) ==
                            value.optString(
                                "elfSha256"
                            )
                ) {
                    "Rift++ Android R6 output publish verification failed"
                }

                value
                    .put(
                        "outputPath",
                        outputPath
                    )
                    .put(
                        "outputPublished",
                        true
                    )
            }

            return ShellOutcome(
                value.toString(2),
                cwd,
                value
            )
        }


        if (action == "s3-android-r7") {
            require(args.size == 1) {
                "usage: riftpp-host s3-android-r7 <riftpp-root>"
            }

            require(!Process.is64Bit()) {
                "Rift++ Android Native R7 currently requires the ARM32 host lane"
            }

            val rootPath =
                resolveDisplay(
                    cwd,
                    args[0]
                )
            val root =
                resolveFile(
                    rootPath
                )

            require(root.isDirectory) {
                "Rift++ root is not a directory: $rootPath"
            }

            val compilerPath =
                joinDisplay(
                    rootPath,
                    "s3/frozen/compiler.arm32.native.hex"
                )
            val patcherPath =
                joinDisplay(
                    rootPath,
                    "standalone/android-native-r1/elf32-r7-textbuffer-patcher.arm32.r3.hex"
                )
            val basePath =
                joinDisplay(
                    rootPath,
                    "standalone/android-native-r1/libriftpp_editor_native_r6.semantic-input-proven.so"
                )
            val outputPath =
                joinDisplay(
                    rootPath,
                    "standalone/android-native-r1/libriftpp_editor_native_r7.so"
                )

            val compilerFile =
                resolveFile(
                    compilerPath
                )
            val patcherFile =
                resolveFile(
                    patcherPath
                )
            val baseFile =
                resolveFile(
                    basePath
                )
            val outputFile =
                resolveFile(
                    outputPath
                )

            require(
                compilerFile.isFile &&
                    compilerFile.length() ==
                        33057L
            ) {
                "Rift++ frozen S3 ARM32 compiler transport is missing or drifted"
            }
            require(
                patcherFile.isFile &&
                    patcherFile.length() ==
                        4624L
            ) {
                "Rift++ Android R7 text-buffer patcher source is missing or drifted"
            }
            require(
                baseFile.isFile &&
                    baseFile.length() ==
                        2768L
            ) {
                "Rift++ promoted R6 semantic-input base ELF is missing or drifted"
            }

            val compilerTransport =
                compilerFile.readBytes()
            val patcherTransport =
                patcherFile.readBytes()
            val baseElf =
                baseFile.readBytes()

            require(
                sha256Hex(
                    compilerTransport
                ) ==
                    "950e4ad52cb57b73c1348282903529488619373921c1bd37b73f6ddfa93b103a"
            ) {
                "Rift++ frozen S3 ARM32 compiler transport identity mismatch"
            }
            require(
                sha256Hex(
                    patcherTransport
                ) ==
                    "b4cd99191c66136d03a2232d941db327e733ebc24cb253ee85efa23dfbb108ac"
            ) {
                "Rift++ Android R7 text-buffer patcher transport identity mismatch"
            }
            require(
                sha256Hex(
                    baseElf
                ) ==
                    "32f7824d6dd4b2f31c4ec30d93cb46995c242fe62263bcf009eb384a7cd8f5e9"
            ) {
                "Rift++ promoted R6 semantic-input base ELF identity mismatch"
            }

            val compilerBytes =
                decodeRiftppExactRawHex(
                    compilerTransport
                        .toString(
                            Charsets.UTF_8
                        ),
                    16528
                )
            val patcherSource =
                decodeRiftppFixedRecordHex(
                    patcherTransport
                        .toString(
                            Charsets.UTF_8
                        )
                )

            require(
                patcherSource.size == 2176 &&
                    sha256Hex(
                        patcherSource
                    ) ==
                        "8b8f5df2cef4364ebfd0ea51450e89728a29f3b8a56a191dc24a7200fa23a6d7"
            ) {
                "Rift++ Android R7 decoded text-buffer patcher identity mismatch"
            }

            val value =
                RiftppCompilerClient
                    .executeS3TextBuffer(
                        appContext,
                        compilerBytes,
                        patcherSource,
                        baseElf
                    )
                    .put(
                        "command",
                        "riftpp-host"
                    )
                    .put(
                        "action",
                        action
                    )
                    .put(
                        "compilerPath",
                        compilerPath
                    )
                    .put(
                        "patcherSourcePath",
                        patcherPath
                    )
                    .put(
                        "r6BaseElfPath",
                        basePath
                    )
                    .put(
                        "r6BaseElfSha256",
                        sha256Hex(
                            baseElf
                        )
                    )

            if (
                value.optString(
                    "status"
                ) ==
                    "success"
            ) {
                val elfBytes =
                    decodeRiftppExactRawHex(
                        value.optString(
                            "elfHex"
                        ),
                        3248
                    )

                require(
                    sha256Hex(
                        elfBytes
                    ) ==
                        value.optString(
                            "elfSha256"
                        )
                ) {
                    "Rift++ Android R7 emitted output transport hash mismatch"
                }

                atomicWrite(
                    outputFile,
                    elfBytes
                )

                require(
                    outputFile.isFile &&
                        outputFile.length() ==
                            3248L &&
                        sha256Hex(
                            outputFile.readBytes()
                        ) ==
                            value.optString(
                                "elfSha256"
                            )
                ) {
                    "Rift++ Android R7 output publish verification failed"
                }

                value
                    .put(
                        "outputPath",
                        outputPath
                    )
                    .put(
                        "outputPublished",
                        true
                    )
            }

            return ShellOutcome(
                value.toString(2),
                cwd,
                value
            )
        }


        if (action == "s3-android-r8") {
            require(args.size == 1) {
                "usage: riftpp-host s3-android-r8 <riftpp-root>"
            }

            require(!Process.is64Bit()) {
                "Rift++ Android Native R8 currently requires the ARM32 host lane"
            }

            val rootPath =
                resolveDisplay(
                    cwd,
                    args[0]
                )
            val root =
                resolveFile(
                    rootPath
                )

            require(root.isDirectory) {
                "Rift++ root is not a directory: $rootPath"
            }

            val compilerPath =
                joinDisplay(
                    rootPath,
                    "s3/frozen/compiler.arm32.native.hex"
                )
            val patcherPath =
                joinDisplay(
                    rootPath,
                    "standalone/android-native-r1/elf32-r8-keysemantics-patcher.arm32.r3.hex"
                )
            val basePath =
                joinDisplay(
                    rootPath,
                    "standalone/android-native-r1/libriftpp_editor_native_r7.buffer-proven.so"
                )
            val outputPath =
                joinDisplay(
                    rootPath,
                    "standalone/android-native-r1/libriftpp_editor_native_r8.so"
                )

            val compilerFile =
                resolveFile(
                    compilerPath
                )
            val patcherFile =
                resolveFile(
                    patcherPath
                )
            val baseFile =
                resolveFile(
                    basePath
                )
            val outputFile =
                resolveFile(
                    outputPath
                )

            require(
                compilerFile.isFile &&
                    compilerFile.length() ==
                        33057L
            ) {
                "Rift++ frozen S3 ARM32 compiler transport is missing or drifted"
            }
            require(
                patcherFile.isFile &&
                    patcherFile.length() ==
                        17680L
            ) {
                "Rift++ Android R8 key-semantics patcher source is missing or drifted"
            }
            require(
                baseFile.isFile &&
                    baseFile.length() ==
                        3248L
            ) {
                "Rift++ promoted R7 buffer base ELF is missing or drifted"
            }

            val compilerTransport =
                compilerFile.readBytes()
            val patcherTransport =
                patcherFile.readBytes()
            val baseElf =
                baseFile.readBytes()

            require(
                sha256Hex(
                    compilerTransport
                ) ==
                    "950e4ad52cb57b73c1348282903529488619373921c1bd37b73f6ddfa93b103a"
            ) {
                "Rift++ frozen S3 ARM32 compiler transport identity mismatch"
            }
            require(
                sha256Hex(
                    patcherTransport
                ) ==
                    "c5dc2e8959a9aa2e4b380e292db9c744be07e2b91a10d038acb25e9ea0de1ef4"
            ) {
                "Rift++ Android R8 key-semantics patcher transport identity mismatch"
            }
            require(
                sha256Hex(
                    baseElf
                ) ==
                    "c489adfd62155b3f916819726cde543eee91e54faefdd371c48c7413c5d6b49a"
            ) {
                "Rift++ promoted R7 buffer base ELF identity mismatch"
            }

            val compilerBytes =
                decodeRiftppExactRawHex(
                    compilerTransport
                        .toString(
                            Charsets.UTF_8
                        ),
                    16528
                )
            val patcherSource =
                decodeRiftppFixedRecordHex(
                    patcherTransport
                        .toString(
                            Charsets.UTF_8
                        )
                )

            require(
                patcherSource.size == 8320 &&
                    sha256Hex(
                        patcherSource
                    ) ==
                        "2617091d17d2425dac4dc47ae4d928792da79fda239ea75e30c8ad0a1ff31431"
            ) {
                "Rift++ Android R8 decoded key-semantics patcher identity mismatch"
            }

            val value =
                RiftppCompilerClient
                    .executeS3KeySemantics(
                        appContext,
                        compilerBytes,
                        patcherSource,
                        baseElf
                    )
                    .put(
                        "command",
                        "riftpp-host"
                    )
                    .put(
                        "action",
                        action
                    )
                    .put(
                        "compilerPath",
                        compilerPath
                    )
                    .put(
                        "patcherSourcePath",
                        patcherPath
                    )
                    .put(
                        "r7BaseElfPath",
                        basePath
                    )
                    .put(
                        "r7BaseElfSha256",
                        sha256Hex(
                            baseElf
                        )
                    )

            if (
                value.optString(
                    "status"
                ) ==
                    "success"
            ) {
                val elfBytes =
                    decodeRiftppExactRawHex(
                        value.optString(
                            "elfHex"
                        ),
                        4388
                    )

                require(
                    sha256Hex(
                        elfBytes
                    ) ==
                        value.optString(
                            "elfSha256"
                        )
                ) {
                    "Rift++ Android R8 emitted output transport hash mismatch"
                }

                atomicWrite(
                    outputFile,
                    elfBytes
                )

                require(
                    outputFile.isFile &&
                        outputFile.length() ==
                            4388L &&
                        sha256Hex(
                            outputFile.readBytes()
                        ) ==
                            value.optString(
                                "elfSha256"
                            )
                ) {
                    "Rift++ Android R8 output publish verification failed"
                }

                value
                    .put(
                        "outputPath",
                        outputPath
                    )
                    .put(
                        "outputPublished",
                        true
                    )
            }

            return ShellOutcome(
                value.toString(2),
                cwd,
                value
            )
        }


        if (action == "s2-vectors") {
            require(args.size == 1) {
                "usage: riftpp-host s2-vectors <riftpp-root>"
            }
            val rootPath = resolveDisplay(cwd, args[0])
            val root = resolveFile(rootPath)
            require(root.isDirectory) { "Rift++ root is not a directory: $rootPath" }

            val genAName =
                if (Process.is64Bit()) "compiler.gena.arm64.hex" else "compiler.gena.arm32.hex"
            val genAExpectedBytes = if (Process.is64Bit()) 2884 else 3128
            val genAPath = joinDisplay(rootPath, "s2/bootstrap/$genAName")
            val arm32SourcePath =
                joinDisplay(rootPath, "s2/vectors/emitter-corpus.arm32.r2.hex")
            val arm64SourcePath =
                joinDisplay(rootPath, "s2/vectors/emitter-corpus.arm64.r2.hex")
            val genAFile = resolveFile(genAPath)
            val arm32SourceFile = resolveFile(arm32SourcePath)
            val arm64SourceFile = resolveFile(arm64SourcePath)
            require(genAFile.isFile) { "Rift++ Generation-A artifact is missing: $genAPath" }
            require(arm32SourceFile.isFile && arm64SourceFile.isFile) {
                "Rift++ S2 vector corpus files are missing"
            }
            require(genAFile.length() <= 8192L) {
                "Rift++ Generation-A hex file exceeds fixed vector bound"
            }
            require(arm32SourceFile.length() <= 2048L && arm64SourceFile.length() <= 2048L) {
                "Rift++ S2 vector corpus exceeds fixed bound"
            }

            val genACompiler = decodeRiftppExactRawHex(
                genAFile.readText(Charsets.UTF_8),
                genAExpectedBytes
            )
            val arm32Source =
                decodeRiftppFixedRecordHex(arm32SourceFile.readText(Charsets.UTF_8))
            val arm64Source =
                decodeRiftppFixedRecordHex(arm64SourceFile.readText(Charsets.UTF_8))
            val value = RiftppCompilerClient.executeS2Vectors(
                appContext,
                genACompiler,
                arm32Source,
                arm64Source
            )
                .put("command", "riftpp-host")
                .put("action", action)
                .put("genAPath", genAPath)
                .put("arm32SourcePath", arm32SourcePath)
                .put("arm64SourcePath", arm64SourcePath)
            return ShellOutcome(value.toString(2), cwd, value)
        }

        require(action == "compile" || action == "prove") {
            "unknown riftpp-host command: $action"
        }
        require(args.size in 2..3) {
            "usage: riftpp-host $action <riftpp-root> <source-file> [output-capacity]"
        }

        val rootPath = resolveDisplay(cwd, args[0])
        val root = resolveFile(rootPath)
        require(root.isDirectory) { "Rift++ root is not a directory: $rootPath" }

        val compilerPath = joinDisplay(rootPath, "compiler/$compilerName")
        val compilerFile = resolveFile(compilerPath)
        require(compilerFile.isFile) { "Rift++ compiler artifact is missing: $compilerPath" }
        require(compilerFile.length() <= 1024L) { "Rift++ compiler hex file is unexpectedly large" }

        val sourcePath = resolveDisplay(cwd, args[1])
        val sourceFile = resolveFile(sourcePath)
        require(sourceFile.isFile) { "Rift++ source file is missing: $sourcePath" }
        require(sourceFile.length() <= 64L * 1024L) { "Rift++ source exceeds 64 KiB host bound" }

        val proveGeneratedPayload = action == "prove"
        val outputCapacity =
            args.getOrNull(2)?.toIntOrNull() ?: if (proveGeneratedPayload) 32 else 64 * 1024
        require(outputCapacity in 1..(64 * 1024)) { "output-capacity must be between 1 and 65536" }

        val compilerBytes = decodeRiftppCompilerHex(compilerFile.readText(Charsets.UTF_8))
        val sourceBytes = sourceFile.readBytes()
        val value = RiftppCompilerClient.execute(
            appContext,
            compilerBytes,
            sourceBytes,
            outputCapacity,
            proveGeneratedPayload
        )
            .put("command", "riftpp-host")
            .put("action", action)
            .put("compilerPath", compilerPath)
            .put("sourcePath", sourcePath)
            .put("requestedOutputCapacity", outputCapacity)

        return ShellOutcome(value.toString(2), cwd, value)
    }


    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun decodeRiftppExactRawHex(text: String, expectedBytes: Int): ByteArray {
        require(expectedBytes > 0) { "expected Rift++ raw hex size must be positive" }
        val normalized = text.trim()
        require(normalized.length == expectedBytes * 2) {
            "Rift++ raw hex length does not match fixed identity"
        }
        require(normalized.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Rift++ raw hex must use canonical lowercase hexadecimal"
        }
        return ByteArray(expectedBytes) { index ->
            normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun decodeRiftppFixedRecordHex(text: String): ByteArray {
        val body = if (text.endsWith("\n")) text.dropLast(1) else text
        require(text == body || text == "$body\n") {
            "fixed record hex has non-canonical trailing bytes"
        }
        require(body.isNotEmpty()) { "fixed record hex is empty" }
        val records = body.split('\n')
        require(records.all { record ->
            record.length == 16 &&
                record.all { it in '0'..'9' || it in 'a'..'f' }
        }) {
            "fixed record hex must contain canonical 8-byte lowercase records"
        }

        return ByteArray(records.size * 8) { index ->
            val record = records[index / 8]
            val byteIndex = index % 8
            record.substring(byteIndex * 2, byteIndex * 2 + 2).toInt(16).toByte()
        }
    }

    private fun decodeRiftppCompilerHex(text: String): ByteArray {
        val body = if (text.endsWith("\n")) text.dropLast(1) else text
        require(text == body || text == "$body\n") { "compiler hex has non-canonical trailing bytes" }
        require(body.length == 552) { "compiler hex must encode exactly 276 bytes" }
        require(body.all { it in '0'..'9' || it in 'a'..'f' }) {
            "compiler hex must be canonical lowercase hexadecimal"
        }

        return ByteArray(276) { index ->
            body.substring(index * 2, index * 2 + 2).toInt(16).toByte()
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
            "rift_cli_batch" -> throw IllegalStateException(
                "RiftCLI Batch V2 is retired; use the direct RiftOS Local Agent batch authority"
            )
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
