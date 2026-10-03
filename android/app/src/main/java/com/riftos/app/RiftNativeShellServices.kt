package com.riftos.app

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import com.codynex.editorapp.CodynexRuntimeBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Argument parsing only. All authority remains in existing Android-native services. */
class RiftNativeShellServices(context: Context) {
    data class Result(val output: String, val value: JSONObject? = null)

    private data class CodynexC0Source(
        val file: File,
        val module: String,
        val source: String,
        val imports: List<String>
    )

    private data class CodynexC0Project(
        val projectDisplay: String,
        val rootDisplay: String,
        val rootSource: String,
        val modules: Map<String, String>,
        val sourceFiles: Int,
        val totalSourceBytes: Int,
        val projectRoot: File,
        val rootFile: File
    )

    private fun executeCodynexC0(
        sub: String,
        args: MutableList<String>,
        cwd: String
    ): Result {
        if (sub == "c0-status" || sub == "c0_status") {
            require(args.isEmpty()) { "usage: codynex c0-status" }
            val compiler = resolveFile(
                "/D:/Workspace/Codynex/external/language/l0/compiler/c0_reference.js"
            )
            val vm1 = resolveFile(
                "/D:/Workspace/Codynex/native/m2/vm1/arm32/vm1_seed.hex"
            )
            val value = JSONObject()
                .put("schema", "codynex-c0-host-status/1")
                .put(
                    "compiler",
                    "codynex-c0-ref/0.11.0|0.12.0 transition"
                )
                .put("compilerAvailable", compiler.isFile)
                .put("vm1Available", vm1.isFile)
                .put("projectCompile", true)
                .put("nativeVm1", true)
                .put("nativeVm1Abi", "armeabi-v7a")
                .put("hostSnapshot", true)
                .put("hostSnapshotSchema", "riftosplus-host-snapshot/1")
                .put("hostSnapshotBytes", 24)
                .put("hostSnapshotCapabilities", 7)
                .put("hostCallTurns", true)
                .put("hostCallSchema", "riftosplus-host-call/1")
                .put("hostCallMaxTurns", 2)
                .put("workspaceProbe", true)
                .put("maxModules", 64)
                .put("maxSourceBytes", 256 * 1024)
                .put("maxProjectBytes", 1024 * 1024)
            return Result(value.toString(2), value)
        }

        require(
            sub in setOf(
                "c0-compile",
                "c0_compile",
                "c0-run",
                "c0_run",
                "c0-run-host",
                "c0_run_host",
                "c0-run-host-call",
                "c0_run_host_call"
            )
        ) {
            "unknown Codynex C0 command: $sub"
        }
        require(args.size in 1..2) {
            "usage: codynex " + sub.replace('_', '-') +
                " <project> [root-relative]"
        }

        val hostCallRun =
            sub == "c0-run-host-call" || sub == "c0_run_host_call"
        val hostRun =
            hostCallRun || sub == "c0-run-host" || sub == "c0_run_host"
        val nativeRun =
            hostRun || sub == "c0-run" || sub == "c0_run"

        val project = loadCodynexC0Project(
            projectRaw = args[0],
            rootRelative = args.getOrNull(1) ?: "src/main.cx",
            cwd = cwd
        )
        val compiled = codynexC0Runtime.compileCodynexC0Project(
            project.rootSource,
            project.modules
        )

        val buildRoot = File(
            project.projectRoot,
            "build/codynex-c0"
        ).canonicalFile
        require(
            buildRoot.path.startsWith(
                project.projectRoot.path + File.separator
            )
        ) {
            "Codynex C0 build root escaped project"
        }
        require(buildRoot.mkdirs() || buildRoot.isDirectory) {
            "Could not create Codynex C0 build directory"
        }
        val artifact = File(
            buildRoot,
            project.rootFile.nameWithoutExtension + ".vm1"
        ).canonicalFile
        require(artifact.parentFile == buildRoot) {
            "Codynex C0 artifact escaped build directory"
        }
        atomicWriteCodynexC0(artifact, compiled.vm1)

        val value = JSONObject()
            .put(
                "schema",
                if (hostCallRun) {
                    "codynex-c0-project-host-call-run/1"
                } else if (hostRun) {
                    "codynex-c0-project-host-run/1"
                } else if (nativeRun) {
                    "codynex-c0-project-run/1"
                } else {
                    "codynex-c0-project-compile/1"
                }
            )
            .put("project", project.projectDisplay)
            .put("root", project.rootDisplay)
            .put("compiler", compiled.compiler)
            .put("sourceFiles", project.sourceFiles)
            .put("modules", compiled.moduleCount)
            .put("totalSourceBytes", project.totalSourceBytes)
            .put("vm1Bytes", compiled.vm1.size)
            .put("vm1Sha256", codynexC0Sha256(compiled.vm1))
            .put(
                "artifact",
                project.projectDisplay.trimEnd('/') +
                    "/build/codynex-c0/" + artifact.name
            )

        if (nativeRun) {
            val vm1 = loadCodynexC0Vm1()
            val initialSource = if (hostRun) {
                buildCodynexC0HostSnapshot()
            } else {
                ByteArray(0)
            }
            val stepBudget = (
                compiled.vm1.size * 256 + 20_000
            ).coerceIn(20_000, 5_000_000)
            val firstOutput = ByteArray(64 * 1024)
            val firstRun = CodynexRuntimeBridge.run(
                vm = vm1,
                program = compiled.vm1,
                source = initialSource,
                output = firstOutput,
                stepBudget = stepBudget
            )
            require(firstRun.size >= 2) {
                "Codynex native VM1 bridge returned malformed result"
            }

            var finalRun = firstRun
            var finalSource = initialSource
            var workspaceProbeOk: Boolean? = null

            if (hostCallRun) {
                require(firstRun[0] == 0) {
                    "Codynex R1.1 host-call request turn failed"
                }
                val requestBytes = Integer.toUnsignedLong(firstRun[1])
                require(requestBytes == 8L) {
                    "Codynex R1.1 host-call request must be exactly 8 bytes"
                }
                requireCodynexC0WorkspaceProbeRequest(firstOutput)

                val probeOk = resolveFile("/D:/Workspace").isDirectory
                workspaceProbeOk = probeOk
                finalSource =
                    initialSource +
                        buildCodynexC0WorkspaceProbeResponse(probeOk)
                val secondOutput = ByteArray(64 * 1024)
                finalRun = CodynexRuntimeBridge.run(
                    vm = vm1,
                    program = compiled.vm1,
                    source = finalSource,
                    output = secondOutput,
                    stepBudget = stepBudget
                )
                require(finalRun.size >= 2) {
                    "Codynex R1.1 host-call response turn malformed"
                }

                value
                    .put("hostCallSchema", "riftosplus-host-call/1")
                    .put("hostCallTurns", 2)
                    .put("hostRequestSchema", "riftosplus-host-request/1")
                    .put("hostRequestBytes", 8)
                    .put("hostOperation", "workspace-probe")
                    .put("hostOperationOk", workspaceProbeOk)
                    .put("hostResponseSchema", "riftosplus-host-response/1")
                    .put("hostResponseBytes", 12)
                    .put("requestTurnStatus", firstRun[0])
                    .put(
                        "requestTurnResult",
                        Integer.toUnsignedLong(firstRun[1])
                    )
            }

            value
                .put("vmBackend", "codynex_editor_vm")
                .put("vm1AuthorityBytes", vm1.size)
                .put("vm1AuthoritySha256", codynexC0Sha256(vm1))
                .put("stepBudget", stepBudget)
                .put("status", finalRun[0])
                .put("programResult", Integer.toUnsignedLong(finalRun[1]))
                .put(
                    "ok",
                    finalRun[0] == 0 &&
                        (!hostCallRun || finalRun[1] == 0)
                )

            if (hostRun) {
                value
                    .put("hostSnapshotSchema", "riftosplus-host-snapshot/1")
                    .put("hostSnapshotBytes", initialSource.size)
                    .put(
                        "hostSnapshotSha256",
                        codynexC0Sha256(initialSource)
                    )
                    .put("hostLifecycle", 1)
                    .put("hostAbi", "armeabi-v7a")
                    .put("hostCapabilities", 7)
                    .put(
                        "hostMonotonicLow",
                        initialSource[16].toInt() and 0xff
                    )
                    .put(
                        "hostUnixSecondLow",
                        initialSource[20].toInt() and 0xff
                    )
            }

            if (hostCallRun) {
                value.put(
                    "hostExchangeSha256",
                    codynexC0Sha256(finalSource)
                )
            }
        } else {
            value.put("ok", true)
        }

        return Result(value.toString(2), value)
    }

    private fun requireCodynexC0WorkspaceProbeRequest(
        output: ByteArray
    ) {
        val expected = byteArrayOf(
            82.toByte(),
            43.toByte(),
            81.toByte(),
            49.toByte(),
            1.toByte(),
            1.toByte(),
            0.toByte(),
            0.toByte()
        )
        for (index in expected.indices) {
            require(output[index] == expected[index]) {
                "Codynex R1.1 host-call request packet mismatch"
            }
        }
    }

    private fun buildCodynexC0WorkspaceProbeResponse(
        available: Boolean
    ): ByteArray {
        val response = ByteArray(12)
        response[0] = 82.toByte()
        response[1] = 43.toByte()
        response[2] = 65.toByte()
        response[3] = 49.toByte()
        response[4] = 1.toByte()
        response[5] = 1.toByte()
        response[6] = 0.toByte()
        response[7] = 0.toByte()
        putCodynexC0U32Le(
            response,
            8,
            if (available) 1 else 0
        )
        return response
    }

    private fun buildCodynexC0HostSnapshot(): ByteArray {
        val snapshot = ByteArray(24)
        snapshot[0] = 82.toByte()
        snapshot[1] = 43.toByte()
        snapshot[2] = 72.toByte()
        snapshot[3] = 49.toByte()
        snapshot[4] = 1.toByte()
        snapshot[5] = 1.toByte()
        snapshot[6] = 1.toByte()
        snapshot[7] = 0.toByte()
        putCodynexC0U32Le(snapshot, 8, 24)
        putCodynexC0U32Le(snapshot, 12, 7)
        putCodynexC0U32Le(
            snapshot,
            16,
            (SystemClock.elapsedRealtime() and 0xffff_ffffL).toInt()
        )
        putCodynexC0U32Le(
            snapshot,
            20,
            ((System.currentTimeMillis() / 1000L) and 0xffff_ffffL).toInt()
        )
        return snapshot
    }

    private fun putCodynexC0U32Le(
        target: ByteArray,
        offset: Int,
        value: Int
    ) {
        require(offset >= 0 && offset + 4 <= target.size) {
            "Codynex C0 host snapshot write escaped packet"
        }
        target[offset] = (value and 0xff).toByte()
        target[offset + 1] = ((value ushr 8) and 0xff).toByte()
        target[offset + 2] = ((value ushr 16) and 0xff).toByte()
        target[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }

    private fun loadCodynexC0Project(
        projectRaw: String,
        rootRelative: String,
        cwd: String
    ): CodynexC0Project {
        val projectDisplay = resolveDisplay(cwd, projectRaw)
        val projectRoot = resolveFile(projectDisplay)
        require(projectRoot.isDirectory) {
            "Codynex C0 project directory not found: $projectDisplay"
        }

        val sourceRoot = File(projectRoot, "src").canonicalFile
        require(
            sourceRoot.isDirectory &&
                sourceRoot.path.startsWith(projectRoot.path + File.separator)
        ) {
            "Codynex C0 project must contain a confined src directory"
        }

        val cleanRoot = rootRelative.trim().replace('\\', '/')
        require(
            cleanRoot.isNotBlank() &&
                !cleanRoot.startsWith("/") &&
                !Regex("^[A-Za-z]:").containsMatchIn(cleanRoot) &&
                cleanRoot.split('/').none { it == ".." }
        ) {
            "Codynex C0 root path must be project-relative"
        }
        val rootFile = File(projectRoot, cleanRoot).canonicalFile
        require(
            rootFile.isFile &&
                rootFile.extension.equals("cx", ignoreCase = true) &&
                rootFile.path.startsWith(sourceRoot.path + File.separator)
        ) {
            "Codynex C0 root must be a .cx file inside project/src"
        }

        val sourceFiles = mutableListOf<File>()
        val directories = mutableListOf(sourceRoot)
        var directoryIndex = 0
        var entries = 0
        while (directoryIndex < directories.size) {
            require(directories.size <= 128) {
                "Codynex C0 source tree exceeds directory bound"
            }
            val directory = directories[directoryIndex++]
            val children = directory.listFiles()?.sortedBy { it.name }
                ?: throw IllegalStateException(
                    "Could not enumerate Codynex C0 source directory"
                )
            for (child in children) {
                entries += 1
                require(entries <= 2048) {
                    "Codynex C0 source tree exceeds entry bound"
                }
                val canonical = child.canonicalFile
                require(
                    canonical == sourceRoot ||
                        canonical.path.startsWith(
                            sourceRoot.path + File.separator
                        )
                ) {
                    "Codynex C0 source tree escaped project/src"
                }
                if (canonical.isDirectory) {
                    directories += canonical
                } else if (
                    canonical.isFile &&
                    canonical.extension.equals("cx", ignoreCase = true)
                ) {
                    sourceFiles += canonical
                    require(sourceFiles.size <= 64) {
                        "Codynex C0 project exceeds 64 .cx files"
                    }
                }
            }
        }
        require(rootFile in sourceFiles) {
            "Codynex C0 root was not discovered in project/src"
        }

        val modulePattern = Regex(
            "(?m)^\\s*module\\s+" +
                "([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)" +
                "\\s*;"
        )
        val usePattern = Regex(
            "(?m)^\\s*use\\s+" +
                "([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)" +
                "(?:\\s+as\\s+[A-Za-z_][A-Za-z0-9_]*)?\\s*;"
        )
        val byModule = linkedMapOf<String, CodynexC0Source>()
        var totalBytes = 0

        for (file in sourceFiles.sortedBy { it.path }) {
            require(file.length() <= 256L * 1024L) {
                "Codynex C0 source exceeds 256 KiB: " + file.name
            }
            val source = file.readText(Charsets.UTF_8)
            val bytes = source.toByteArray(Charsets.UTF_8).size
            require(bytes in 1..(256 * 1024)) {
                "Codynex C0 source UTF-8 size is out of bounds: " + file.name
            }
            totalBytes += bytes
            require(totalBytes <= 1024 * 1024) {
                "Codynex C0 source tree exceeds 1 MiB"
            }
            val module = modulePattern.find(source)?.groupValues?.get(1)
                ?: throw IllegalArgumentException(
                    "Codynex C0 source is missing module declaration: " +
                        file.name
                )
            require(!byModule.containsKey(module)) {
                "Duplicate Codynex C0 module identity: $module"
            }
            byModule[module] = CodynexC0Source(
                file = file,
                module = module,
                source = source,
                imports = usePattern.findAll(source)
                    .map { it.groupValues[1] }
                    .toList()
            )
        }

        val root = byModule.values.firstOrNull {
            it.file == rootFile
        } ?: throw IllegalStateException(
            "Codynex C0 root module was not indexed"
        )

        val selected = linkedMapOf<String, String>()
        val queue = root.imports.toMutableList()
        var queueIndex = 0
        val visited = mutableSetOf<String>()
        while (queueIndex < queue.size) {
            val name = queue[queueIndex++]
            if (!visited.add(name)) continue
            if (name == root.module) continue
            val dependency = byModule[name]
                ?: throw IllegalArgumentException(
                    "Codynex C0 import has no project source: $name"
                )
            selected[name] = dependency.source
            require(selected.size <= 63) {
                "Codynex C0 dependency graph exceeds 64 total modules"
            }
            queue.addAll(dependency.imports)
            require(queue.size <= 4096) {
                "Codynex C0 import traversal exceeded bound"
            }
        }

        val rootDisplay =
            projectDisplay.trimEnd('/') + "/" + cleanRoot
        return CodynexC0Project(
            projectDisplay = projectDisplay,
            rootDisplay = rootDisplay,
            rootSource = root.source,
            modules = selected,
            sourceFiles = sourceFiles.size,
            totalSourceBytes = totalBytes,
            projectRoot = projectRoot,
            rootFile = rootFile
        )
    }

    private fun loadCodynexC0Vm1(): ByteArray {
        val file = resolveFile(
            "/D:/Workspace/Codynex/native/m2/vm1/arm32/vm1_seed.hex"
        )
        require(file.isFile) {
            "Canonical Codynex ARM32 VM1 authority is missing"
        }
        val raw = file.readBytes()
        require(raw.size == 1624) {
            "Canonical Codynex VM1 hex byte count drift"
        }
        require(
            codynexC0Sha256(raw) ==
                "1f013e2592741895f511d1724ecd69ee156e24f771c289d848e1bab265d3655e"
        ) {
            "Canonical Codynex VM1 hex SHA-256 drift"
        }
        val hex = raw.toString(Charsets.UTF_8)
        require(hex.length == 1624 && hex.length % 2 == 0) {
            "Canonical Codynex VM1 hex encoding drift"
        }
        val vm1 = ByteArray(hex.length / 2)
        var source = 0
        var target = 0
        while (source < hex.length) {
            val high = hex[source].digitToIntOrNull(16)
                ?: throw IllegalStateException(
                    "Canonical Codynex VM1 contains non-hex byte"
                )
            val low = hex[source + 1].digitToIntOrNull(16)
                ?: throw IllegalStateException(
                    "Canonical Codynex VM1 contains non-hex byte"
                )
            vm1[target] = ((high shl 4) or low).toByte()
            source += 2
            target += 1
        }
        require(vm1.size == 812) {
            "Canonical Codynex VM1 decoded byte count drift"
        }
        require(
            codynexC0Sha256(vm1) ==
                "7d7b33d2796ab2ddbca1519e00f254c2e6c8417af3ee9317ab45929a593b7df5"
        ) {
            "Canonical Codynex VM1 decoded SHA-256 drift"
        }
        return vm1
    }

    private fun codynexC0Sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") {
                (it.toInt() and 0xff).toString(16).padStart(2, '0')
            }

    private fun atomicWriteCodynexC0(target: File, bytes: ByteArray) {
        target.parentFile?.let {
            require(it.mkdirs() || it.isDirectory) {
                "Could not create Codynex C0 artifact directory"
            }
        }
        val parent = target.parentFile
            ?: throw IllegalStateException("Codynex C0 artifact has no parent")
        val temp = File(
            parent,
            "." + target.name + ".c0-" + System.nanoTime()
        )
        val backup = File(
            parent,
            "." + target.name + ".backup-" + System.nanoTime()
        )
        temp.writeBytes(bytes)
        var backedUp = false
        try {
            if (target.exists()) {
                require(target.isFile) {
                    "Codynex C0 artifact target is not a file"
                }
                require(target.renameTo(backup)) {
                    "Could not stage previous Codynex C0 artifact"
                }
                backedUp = true
            }
            require(temp.renameTo(target)) {
                "Could not publish Codynex C0 artifact atomically"
            }
            if (backedUp) backup.delete()
        } catch (error: Throwable) {
            temp.delete()
            if (backedUp && !target.exists()) backup.renameTo(target)
            throw error
        }
    }


    private val appContext = context.applicationContext
    private val riftRoot = File(appContext.filesDir, "riftfs").apply { mkdirs() }.canonicalFile
    private val llm = RiftLlmDevClient(appContext)
    private val codynexC0Runtime = RiftHeadlessJsRuntime(appContext)

    fun chat(args: MutableList<String>, cwd: String): Result {
        val sub = args.removeFirstOrNull()?.lowercase() ?: "help"
        if (sub == "help") {
            require(args.isEmpty()) { "usage: chat help" }
            return Result(
                "RiftOS chat handoff bundles\n" +
                    "chat handoff <payload.json> [name]\nchat export <payload.json> [name]\nchat list\n" +
                    "chat inspect <bundle.riftchat>\nchat resume <bundle.riftchat>\nchat transcript <bundle.riftchat> [offset-chars] [max-chars]"
            )
        }
        val request = JSONObject()
        when (sub) {
            "list" -> { require(args.isEmpty()) { "usage: chat list" }; request.put("op", "list") }
            "handoff", "export" -> {
                require(args.size in 1..2) { "usage: chat $sub <payload.json> [name]" }
                val raw = args[0]
                val name = args.getOrNull(1).orEmpty()
                require(name.length <= 160) { "chat handoff name is too long" }
                request.put("op", "create").put("payloadPath", resolveDisplay(cwd, raw)).put("name", name)
            }
            "inspect", "resume" -> {
                require(args.size == 1) { "usage: chat $sub <bundle.riftchat>" }
                request.put("op", sub).put("path", resolveDisplay(cwd, args[0]))
            }
            "transcript" -> {
                require(args.size in 1..3) { "usage: chat transcript <bundle.riftchat> [offset-chars] [max-chars]" }
                val offset = if (args.size >= 2) args[1].toLongOrNull() ?: throw IllegalArgumentException("transcript offset must be an integer") else 0L
                require(offset >= 0L) { "transcript offset must be non-negative" }
                val maxChars = if (args.size >= 3) args[2].toIntOrNull() ?: throw IllegalArgumentException("transcript max-chars must be an integer") else 32768
                require(maxChars in 1..65536) { "transcript max-chars must be between 1 and 65536" }
                request.put("op", "transcript").put("path", resolveDisplay(cwd, args[0]))
                    .put("offsetChars", offset)
                    .put("maxChars", maxChars)
            }
            else -> throw IllegalArgumentException("unknown chat command: $sub")
        }
        val value = RiftChatHandoff.execute(riftRoot, request)
        return Result(value.toString(2), value)
    }

    fun devLab(args: MutableList<String>, cwd: String): Result {
        val action = args.removeFirstOrNull()?.lowercase() ?: "help"
        if (action == "help") return Result(
            "RiftOS Android-native Dev Lab\n" +
                "devlab status\ndevlab load <project-path>\ndevlab staged\ndevlab stage <project-path> <text>\n" +
                "devlab stage-file <project-path> <riftfs-source-file>\ndevlab delete <project-path>\ndevlab unstage <project-path>\n" +
                "devlab snapshot [note]\ndevlab snapshots [limit]\ndevlab load-snapshot [id|latest]\n" +
                "devlab preview [id|latest]\ndevlab publish [id|latest]\ndevlab reset\n" +
                "Web execution/HTML preview is owned by RiftBrowser."
        )
        val request = JSONObject().put("action", action).put("cwd", cwd)
        when (action) {
            "status", "staged", "reset" -> Unit
            "load", "delete", "unstage" -> request.put("path", args.removeFirstOrNull()
                ?: throw IllegalArgumentException("usage: devlab $action <project-path>"))
            "stage" -> {
                require(args.size >= 2) { "usage: devlab stage <project-path> <text>" }
                request.put("path", args.removeAt(0)).put("text", args.joinToString(" ")).put("reason", "native shell")
            }
            "stage-file" -> {
                require(args.size >= 2) { "usage: devlab stage-file <project-path> <riftfs-source-file>" }
                request.put("path", args.removeAt(0)).put("sourcePath", resolveDisplay(cwd, args.removeAt(0))).put("reason", "native shell")
            }
            "snapshot" -> request.put("note", args.joinToString(" "))
            "snapshots" -> request.put("limit", (args.firstOrNull()?.toIntOrNull() ?: 50).coerceIn(1, 200))
            "load-snapshot", "preview", "publish" -> request.put("snapshotId", args.firstOrNull() ?: "latest")
            else -> throw IllegalArgumentException(
                if (action in setOf("run","run-file","css","css-off","open"))
                    "Dev Lab web execution moved to RiftBrowser; use the native Dev Lab UI/browser runner."
                else "unknown devlab command: $action"
            )
        }
        val value = RiftNativeDevLab.execute(appContext, request)
        return Result(value.toString(2), value)
    }

    fun vortex(args: MutableList<String>, cwd: String): Result {
        val sub = args.removeFirstOrNull()?.lowercase() ?: "help"
        if (sub == "help") {
            require(args.isEmpty()) { "usage: vortex help" }
            return Result(
                "Vortex3D native bridge\nvortex status\nvortex catalog\nvortex api\nvortex snapshot\nvortex ui-tree [limit]\n" +
                    "vortex screenshot [name]\nvortex click <target>\nvortex touch <down|move|up|cancel|0..3> <x> <y>\n" +
                    "vortex test [all|system|case-id]\nvortex test-wait <system|case-id>\n" +
                    "vortex script <RiftFS-path> [--unsafe] [--live]\nvortex script-wait <RiftFS-path> [--unsafe] [--live]\n" +
                    "vortex job <id> [--image]\nvortex pull <artifact-id> [filename]\nvortex cleanup"
            )
        }
        val bridge = RiftMcpRuntime.vortexBridge(appContext)
        val request = JSONObject()
        val session: Boolean
        when (sub) {
            "status", "catalog", "api", "snapshot", "cleanup" -> { require(args.isEmpty()) { "usage: vortex $sub" }; request.put("op", sub); session = false }
            "ui-tree", "ui_tree" -> {
                require(args.size <= 1) { "usage: vortex ui-tree [limit]" }
                val limit = if (args.isEmpty()) 256 else args[0].toIntOrNull() ?: throw IllegalArgumentException("ui-tree limit must be an integer")
                require(limit in 1..1024) { "ui-tree limit must be between 1 and 1024" }
                request.put("op", "ui_tree").put("limit", limit); session = false
            }
            "screenshot" -> { require(args.size <= 1) { "usage: vortex screenshot [name]" }; val name=args.firstOrNull()?:"current"; require(name.length<=120){"vortex screenshot name is too long"}; request.put("op","screenshot").put("name",name).put("includeImage",true); session=false }
            "click" -> { require(args.isNotEmpty()){"usage: vortex click <target>"}; val target=args.joinToString(" "); require(target.length<=256){"vortex click target is too long"}; request.put("op","click").put("target",target); session=false }
            "touch" -> {
                require(args.size==3){"usage: vortex touch <action> <x> <y>"}
                val actions=mapOf("down" to 0,"up" to 1,"move" to 2,"cancel" to 3)
                val raw=args[0].lowercase(); val action=actions[raw]?:raw.toIntOrNull()
                require(action!=null&&action in 0..3){"touch action must be down/move/up/cancel or 0..3"}
                val x=args[1].toDoubleOrNull(); val y=args[2].toDoubleOrNull()
                require(x!=null&&y!=null){"touch coordinates must be numeric"}
                require(x.isFinite()&&y.isFinite()){"touch coordinates must be finite"}
                request.put("op","touch").put("action",action).put("x",x).put("y",y); session=false
            }
            "test", "validate" -> { require(args.size<=1){"usage: vortex test [all|system|case-id]"}; val target=args.firstOrNull()?:"all"; require(target.length<=160){"vortex validation target is too long"}; request.put("op","validate").put("target",target); session=false }
            "test-wait", "validate-wait" -> {
                require(args.size==1){"usage: vortex test-wait <target>"}
                require(args[0].length<=160){"vortex validation target is too long"}
                request.put("kind","validation").put("target",args[0]).put("includeImage",true); session=true
            }
            "script", "script-wait" -> {
                val unsafe=args.remove("--unsafe"); val live=args.remove("--live")
                require(args.size==1){"usage: vortex $sub <RiftFS-path> [--unsafe] [--live]"}
                val path=resolveDisplay(cwd,args[0])
                val source=readText(path)
                if(sub=="script"){
                    request.put("op","script").put("source",source).put("unsafe",unsafe).put("live",live).put("name",File(path).nameWithoutExtension); session=false
                }else{
                    request.put("kind","script").put("source",source).put("unsafe",unsafe).put("live",live).put("name",File(path).nameWithoutExtension).put("includeImage",true); session=true
                }
            }
            "job" -> {
                require(args.size in 1..2 && (args.size==1 || args[1]=="--image")){"usage: vortex job <id> [--image]"}
                require(args[0].length<=160){"vortex job id is too long"}
                request.put("op","job").put("id",args[0]).put("includeImage",args.size==2); session=false
            }
            "pull" -> {
                require(args.size in 1..2){"usage: vortex pull <artifact-id> [filename]"}
                require(args[0].length<=512){"vortex artifact id is too long"}
                require(args.getOrNull(1)?.length?.let { it<=120 } ?: true){"vortex artifact filename is too long"}
                request.put("op","pull_artifact").put("id",args[0]).put("name",args.getOrNull(1)?:""); session=false
            }
            else -> throw IllegalArgumentException("unknown vortex command: $sub")
        }
        val value=if(session) bridge.executeSession(request) else bridge.execute(request)
        val display=JSONObject(value.toString()).also { if(it.has("_riftImage")) it.put("_riftImage",JSONObject().put("attached",true)) }
        return Result(display.toString(2),value)
    }

    fun codynex(args: MutableList<String>, cwd: String): Result {
        val sub = args.removeFirstOrNull()?.lowercase() ?: "help"

        if (sub == "help") {
            require(args.isEmpty()) { "usage: codynex help" }
            return Result(
                "Codynex host\n" +
                    "codynex c0-status\n" +
                    "codynex c0-compile <project> [root-relative]\n" +
                    "codynex c0-run <project> [root-relative]\n" +
                    "codynex c0-run-host <project> [root-relative]\n" +
                    "codynex c0-run-host-call <project> [root-relative]\n" +
                    "Legacy LR0 bridge:\n" +
                    "codynex status\n" +
                    "codynex read-state <id>\n" +
                    "codynex call <function-id>\n" +
                    "codynex compile-activate <RiftFS-source-path>\n" +
                    "codynex activate\n" +
                    "codynex corrupt\n" +
                    "codynex recover\n" +
                    "codynex clear\n" +
                    "codynex cold-restart"
            )
        }

        if (sub.startsWith("c0-" ) || sub.startsWith("c0_")) {
            return executeCodynexC0(sub, args, cwd)
        }

        val request = JSONObject()

        when (sub) {
            "status" -> {
                require(args.isEmpty()) { "usage: codynex status" }
                request.put("op", "status")
            }

            "read-state", "read_state" -> {
                require(args.size == 1) {
                    "usage: codynex read-state <id>"
                }

                val id = args[0].toIntOrNull()
                    ?: throw IllegalArgumentException(
                        "state id must be an integer"
                    )

                require(id in 0..65535) {
                    "state id must be between 0 and 65535"
                }

                request
                    .put("op", "read_state")
                    .put("stateId", id)
            }

            "call" -> {
                require(args.size == 1) {
                    "usage: codynex call <function-id>"
                }

                val id = args[0].toIntOrNull()
                    ?: throw IllegalArgumentException(
                        "function id must be an integer"
                    )

                require(id in 0..65535) {
                    "function id must be between 0 and 65535"
                }

                request
                    .put("op", "call")
                    .put("functionId", id)
            }

            "compile-activate", "compile_activate" -> {
                require(args.size == 1) {
                    "usage: codynex compile-activate <RiftFS-source-path>"
                }

                val display = resolveDisplay(cwd, args[0])
                val file = resolveFile(display)

                require(file.isFile) {
                    "Codynex source file not found: $display"
                }

                require(file.length() <= 64L * 1024L) {
                    "Codynex LR0 source exceeds 64 KiB"
                }

                val source = file.readText(Charsets.UTF_8)

                require(
                    source.toByteArray(Charsets.UTF_8).size <=
                        64 * 1024
                ) {
                    "Codynex LR0 source exceeds 64 KiB UTF-8"
                }

                request
                    .put("op", "compile_activate")
                    .put("source", source)
            }

            "activate" -> {
                require(args.isEmpty()) { "usage: codynex activate" }
                request.put("op", "activate_candidate")
            }

            "corrupt" -> {
                require(args.isEmpty()) { "usage: codynex corrupt" }
                request.put("op", "corrupt_candidate")
            }

            "recover" -> {
                require(args.isEmpty()) { "usage: codynex recover" }
                request.put("op", "recover")
            }

            "clear" -> {
                require(args.isEmpty()) { "usage: codynex clear" }
                request.put("op", "clear")
            }

            "cold-restart", "cold_restart" -> {
                require(args.isEmpty()) {
                    "usage: codynex cold-restart"
                }
                request.put("op", "cold_restart")
            }

            else ->
                throw IllegalArgumentException(
                    "unknown codynex command: $sub"
                )
        }

        val value =
            RiftMcpRuntime
                .codynexBridge(appContext)
                .execute(request)

        return Result(
            value.toString(2),
            value
        )
    }

    fun vortexAgent(args: MutableList<String>): Result =
        localAgent("vortex-agent",args){ request -> RiftVortexLocalAgent.execute(appContext,request) }

    fun riftOsAgent(args: MutableList<String>, cwd: String): Result {
        if(args.firstOrNull()?.lowercase()=="devlab"){
            args.removeAt(0)
            return RiftLocalAgentExecutionGate.withAccess {
                devLab(args,cwd)
            }
        }
        val activity=RiftMcpRuntime.activeActivity()
        val context=activity?:appContext
        return localAgent("riftos-agent",args){ request -> RiftOsLocalAgent.execute(context,request) }
    }

    fun riftLlm(args: MutableList<String>, cwd: String): Result {
        val sub=args.removeFirstOrNull()?.lowercase()?:"help"
        if(sub=="help") return Result(
            "RiftLLM native Dev API bridge\nriftllm-agent status\nriftllm-agent unpair\n" +
                "riftllm-agent train-data-status\nriftllm-agent train-data-build\nriftllm-agent train-data-build-status\nriftllm-agent train-data-build-cancel\n" +
                "riftllm-agent train-data-upload\nriftllm-agent train-data-remote-status\nriftllm-agent train-canary-start\nriftllm-agent train-canary-status\n" +
                "riftllm-agent text-encoding-prime-b2\n" +
                "riftllm-agent riftpack-qualification-start\nriftllm-agent riftpack-qualification-status\n" +
                "riftllm-agent process-death-start\nriftllm-agent process-death-status\n" +
                "riftllm-agent train-v2-adversarial-start\nriftllm-agent train-v2-adversarial-status\n" +
                "riftllm-agent train-v2-builder-start\nriftllm-agent train-v2-builder-status\n" +
                "Pairing is entered only in native Settings. Legacy corpus-* helpers are unavailable unless explicitly reintroduced behind a bounded native/headless service."
        )
        if(sub=="pair") throw IllegalStateException("RiftLLM pairing token must be entered in native Settings; shell arguments are intentionally rejected.")
        val request=JSONObject()
        val value:Any=when(sub){
            "status","unpair" -> llm.execute(JSONObject().put("op",sub))
            "train-data-status" -> RiftTrainDataTaskRunner.execute(appContext,llm,JSONObject().put("op","status"))
            "train-data-build" -> RiftTrainDataTaskRunner.execute(appContext,llm,JSONObject().put("op","build"))
            "train-data-build-status" -> RiftTrainDataTaskRunner.execute(appContext,llm,JSONObject().put("op","build-status"))
            "train-data-build-cancel" -> RiftTrainDataTaskRunner.execute(appContext,llm,JSONObject().put("op","build-cancel"))
            "train-data-upload" -> RiftTrainDataTaskRunner.execute(appContext,llm,JSONObject().put("op","upload"))
            "train-data-remote-status" -> RiftTrainDataTaskRunner.execute(appContext,llm,JSONObject().put("op","remote-status"))
            "train-canary-start" -> RiftTrainDataTaskRunner.execute(appContext,llm,JSONObject().put("op","canary-start"))
            "train-canary-status" -> RiftTrainDataTaskRunner.execute(appContext,llm,JSONObject().put("op","canary-status"))
            "text-encoding-prime-b2" -> {
                require(args.isEmpty()) { "usage: riftllm-agent text-encoding-prime-b2" }
                primeFrozenB2Tokenizer()
            }
            "riftpack-qualification-start" -> {
                require(args.isEmpty()) { "usage: riftllm-agent riftpack-qualification-start" }
                llm.execute(JSONObject().put("op","riftpack_qualification_start"))
            }
            "riftpack-qualification-status" -> {
                require(args.isEmpty()) { "usage: riftllm-agent riftpack-qualification-status" }
                llm.execute(JSONObject().put("op","riftpack_qualification_status"))
            }
            "process-death-start" -> {
                require(args.isEmpty()) { "usage: riftllm-agent process-death-start" }
                llm.execute(JSONObject().put("op","rift_micro_process_death_start"))
            }
            "process-death-status" -> {
                require(args.isEmpty()) { "usage: riftllm-agent process-death-status" }
                llm.execute(JSONObject().put("op","rift_micro_process_death_status"))
            }
            "train-v2-adversarial-start" -> {
                require(args.isEmpty()) { "usage: riftllm-agent train-v2-adversarial-start" }
                llm.execute(JSONObject().put("op","train_v2_adversarial_start"))
            }
            "train-v2-adversarial-status" -> {
                require(args.isEmpty()) { "usage: riftllm-agent train-v2-adversarial-status" }
                llm.execute(JSONObject().put("op","train_v2_adversarial_status"))
            }
            "train-v2-builder-start" -> {
                require(args.isEmpty()) { "usage: riftllm-agent train-v2-builder-start" }
                llm.execute(JSONObject().put("op","train_v2_builder_start"))
            }
            "train-v2-builder-status" -> {
                require(args.isEmpty()) { "usage: riftllm-agent train-v2-builder-status" }
                llm.execute(JSONObject().put("op","train_v2_builder_status"))
            }
            else -> {
                if (sub == "preview" || sub == "publish") throw IllegalStateException(
                    "RiftLLM '$sub' requires the retired workspace patch preview/apply composite and is intentionally unavailable until a bounded native publisher is implemented."
                )
                val mapping=mapOf(
                    "staged" to "list_staged","reset" to "reset","snapshots" to "list_snapshots","benchmarks" to "list_benchmarks",
                    "text-encoding-status" to "text_encoding_status"
                )
                val op=mapping[sub]?:throw IllegalStateException(
                    "Legacy RiftLLM shell helper '$sub' is unavailable in the native shell; migrate it to a bounded native/headless service before re-enabling it."
                )
                request.put("op",op)
                val first=args.firstOrNull()
                if(first!=null) request.put("request",JSONObject().put("id",first))
                llm.execute(request)
            }
        }
        val text=when(value){is JSONObject->value.toString(2);is JSONArray->value.toString(2);else->value.toString()}
        return Result(text,value as? JSONObject)
    }

    private fun primeFrozenB2Tokenizer(): JSONObject {
        val expectedSha = "314e3a732d4cc4c31c40c9b0add3fffcec38c8a4b40e0d228bdc4eed1addbbd1"
        val project = File(riftRoot, "workspace/RiftLLM").canonicalFile
        require(project.isDirectory && project.path.startsWith(riftRoot.path + File.separator)) {
            "RiftLLM workspace project is missing"
        }
        val artifact = File(project, "tokenizer/output/rift-token-b-balanced-v2.riftbpe").canonicalFile
        require(artifact.path.startsWith(project.path + File.separator) && artifact.isFile) {
            "Frozen B2 tokenizer workspace artifact is missing"
        }
        require(artifact.length() in 1..(4L * 1024L * 1024L)) {
            "Frozen B2 tokenizer workspace artifact is out of bounds"
        }

        val digest = MessageDigest.getInstance("SHA-256")
        artifact.inputStream().buffered().use { input ->
            val hashBuffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(hashBuffer)
                if (read <= 0) break
                digest.update(hashBuffer, 0, read)
            }
        }
        val actualSha = digest.digest().joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        require(actualSha == expectedSha) {
            "Frozen B2 tokenizer workspace artifact SHA-256 mismatch"
        }

        val totalBytes = artifact.length()
        val begin = llm.execute(
            JSONObject()
                .put("op", "text_encoding_begin")
                .put(
                    "request",
                    JSONObject()
                        .put("slot", "artifact")
                        .put("totalBytes", totalBytes)
                        .put("sha256", expectedSha)
                )
        ) as? JSONObject ?: throw IllegalStateException("RiftLLM text encoding begin returned an unexpected payload")
        val maxChunkBytes = begin.optInt("maxChunkBytes", 0)
        require(maxChunkBytes == 192 * 1024) {
            "RiftLLM Text Encoding chunk contract mismatch"
        }

        var offset = 0L
        artifact.inputStream().buffered().use { input ->
            val buffer = ByteArray(maxChunkBytes)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                val appended = llm.execute(
                    JSONObject()
                        .put("op", "text_encoding_append")
                        .put(
                            "request",
                            JSONObject()
                                .put("slot", "artifact")
                                .put("offset", offset)
                                .put("dataBase64", Base64.encodeToString(chunk, Base64.NO_WRAP))
                        )
                ) as? JSONObject ?: throw IllegalStateException("RiftLLM text encoding append returned an unexpected payload")
                offset += read.toLong()
                require(appended.optLong("receivedBytes", -1L) == offset) {
                    "RiftLLM frozen B2 upload acknowledgement drifted at byte $offset"
                }
            }
        }
        require(offset == totalBytes) {
            "RiftLLM frozen B2 upload ended at $offset / $totalBytes bytes"
        }

        val committed = llm.execute(
            JSONObject()
                .put("op", "text_encoding_commit")
                .put("request", JSONObject().put("slot", "artifact"))
        ) as? JSONObject ?: throw IllegalStateException("RiftLLM text encoding commit returned an unexpected payload")
        require(committed.optBoolean("committed", false)) {
            "RiftLLM frozen B2 tokenizer commit was not acknowledged"
        }
        require(committed.optString("sha256").lowercase() == expectedSha) {
            "RiftLLM frozen B2 tokenizer commit SHA-256 mismatch"
        }

        return JSONObject()
            .put("schema", "riftllm-frozen-b2-prime-v1")
            .put("primed", true)
            .put("source", "workspace/RiftLLM/tokenizer/output/rift-token-b-balanced-v2.riftbpe")
            .put("target", "app-private://text-encoding/candidate.riftbpe")
            .put("bytes", totalBytes)
            .put("sha256", expectedSha)
            .put("maxChunkBytes", maxChunkBytes)
    }

    private fun localAgent(name:String,args:MutableList<String>,call:(JSONObject)->JSONObject):Result{
        val sub=args.removeFirstOrNull()?.lowercase()?:"help"
        if(sub=="help") {
            require(args.isEmpty()) { "usage: $name help" }
            return Result(
                "$name Android-native local agent\n$name status\n$name open\n$name tree [limit]\n$name click <target>\n" +
                    "$name tap <x> <y>\n$name swipe <x1> <y1> <x2> <y2> [ms]\n$name type <target> <text>\n$name back"
            )
        }
        val request=JSONObject().put("op",sub)
        when(sub){
            "status","open","back"->{require(args.isEmpty()){"usage: $name $sub"}}
            "tree"->{
                require(args.size<=1){"usage: $name tree [limit]"}
                val limit=if(args.isEmpty())256 else args[0].toIntOrNull()?:throw IllegalArgumentException("tree limit must be an integer")
                require(limit in 1..1024){"tree limit must be between 1 and 1024"}
                request.put("limit",limit)
            }
            "click"->{require(args.isNotEmpty()){"usage: $name click <target>"};request.put("target",args.joinToString(" "))}
            "tap"->{
                require(args.size==2){"usage: $name tap <x> <y>"}
                val x=args[0].toDoubleOrNull()?:throw IllegalArgumentException("tap x must be numeric")
                val y=args[1].toDoubleOrNull()?:throw IllegalArgumentException("tap y must be numeric")
                request.put("x",x).put("y",y)
            }
            "swipe"->{
                require(args.size in 4..5){"usage: $name swipe <x1> <y1> <x2> <y2> [ms]"}
                val x1=args[0].toDoubleOrNull()?:throw IllegalArgumentException("swipe x1 must be numeric")
                val y1=args[1].toDoubleOrNull()?:throw IllegalArgumentException("swipe y1 must be numeric")
                val x2=args[2].toDoubleOrNull()?:throw IllegalArgumentException("swipe x2 must be numeric")
                val y2=args[3].toDoubleOrNull()?:throw IllegalArgumentException("swipe y2 must be numeric")
                val duration=if(args.size==5) args[4].toLongOrNull()?:throw IllegalArgumentException("swipe duration must be an integer") else 350L
                request.put("x1",x1).put("y1",y1).put("x2",x2).put("y2",y2).put("durationMs",duration)
            }
            "type"->{require(args.size>=2){"usage: $name type <target> <text>"};request.put("target",args.removeAt(0)).put("text",args.joinToString(" "))}
            "type-focused"->{require(name=="riftos-agent"){"unknown $name command: $sub"};require(args.isNotEmpty()){"usage: $name type-focused <text>"};request.put("text",args.joinToString(" "))}
            else->throw IllegalArgumentException("unknown $name command: $sub")
        }
        val value=call(request)
        return Result(value.toString(2),value)
    }

    private fun readText(display:String):String{
        val file=resolveFile(display)
        require(file.isFile){"file not found: $display"}
        require(file.length()<=240L*1024L){"Vortex script source exceeds 240 KiB"}
        val text=file.readText(Charsets.UTF_8)
        require(text.toByteArray(Charsets.UTF_8).size<=240*1024){"Vortex script source exceeds 240 KiB UTF-8"}
        return text
    }

    private fun resolveDisplay(cwd:String,raw:String):String{
        var value=raw.trim().replace('\\','/')
        if(value=="~"||value.startsWith("~/")) value="/D:/Users/Default"+value.drop(1)
        if(Regex("^[A-Za-z]:($|/)").containsMatchIn(value)) value="/$value"
        if(!value.startsWith('/')) value=cwd.trimEnd('/')+"/"+value
        return RiftVolumePaths.normalizeDisplay(value)
    }

    private fun resolveFile(display:String):File{
        val normalized=RiftVolumePaths.normalizeDisplay(display)
        val relative=if(normalized.startsWith("/C:",true)||normalized.startsWith("/D:",true)) RiftVolumePaths.resolveRelative(normalized) else normalized.trimStart('/')
        val file=if(relative.isBlank())riftRoot else File(riftRoot,relative).canonicalFile
        require(file==riftRoot||file.path.startsWith(riftRoot.path+File.separator)){"Path escaped RiftFS"}
        return file
    }
}
