package com.riftos.app

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Bounded native C++ bootstrap toolchain for Semnexis.
 *
 * Security contract:
 * - never invokes /system/bin/sh or any shell;
 * - executable code is accepted only from the APK-owned nativeLibraryDir;
 * - sources are fixed to the Semnexis bootstrap translation units;
 * - inputs must resolve beneath RiftFS workspace/Semnexis;
 * - outputs are build artifacts only and are never execve()'d from writable RiftFS;
 * - arbitrary compiler/linker flags are not accepted from RiftShell/MCP.
 */
class RiftNativeToolchain(context: Context) {
    companion object {
        private const val API_LEVEL = 26
        private const val MAX_OUTPUT_BYTES = 512 * 1024
        private const val BUILD_TIMEOUT_MS = 45_000L
        private const val TOOLCHAIN_ID = "rift-clang-v1"

        private val SEMNEXIS_SOURCES = listOf(
            "src/frontend.cpp",
            "src/graph.cpp",
            "src/main.cpp"
        )
    }

    data class Result(
        val output: String,
        val value: JSONObject
    )

    private val appContext = context.applicationContext
    private val riftRoot = File(appContext.filesDir, "riftfs").apply { mkdirs() }.canonicalFile
    private val nativeLibDir = File(appContext.applicationInfo.nativeLibraryDir).canonicalFile
    private val toolchainRoot = File(appContext.filesDir, "rift-toolchains/$TOOLCHAIN_ID").canonicalFile

    // Compiler/linker payloads are APK-owned. Modern Android forbids execve() from writable
    // app-home storage for target API 29+, so they must never be downloaded into RiftFS.
    private val clangDriver = File(nativeLibDir, "libriftclang.so")
    private val lldDriver = File(nativeLibDir, "libriftlld.so")
    private val sysroot = File(toolchainRoot, "sysroot")
    private val resourceDir = File(toolchainRoot, "resource")

    fun executeShell(args: MutableList<String>, cwd: String): Result {
        val command = args.removeFirstOrNull()?.lowercase() ?: "doctor"
        require(args.isEmpty()) {
            "riftclang does not accept arbitrary compiler arguments"
        }

        return when (command) {
            "doctor" -> doctor(cwd)
            "semnexis-build" -> buildSemnexis(cwd)
            else -> throw IllegalArgumentException(
                "usage: riftclang doctor|semnexis-build"
            )
        }
    }

    private fun doctor(cwd: String): Result {
        val target = androidTarget()
        val checks = JSONObject()
            .put("clang", fileCheck(clangDriver, executable = true))
            .put("lld", fileCheck(lldDriver, executable = true))
            .put("sysroot", fileCheck(sysroot, executable = false))
            .put("resourceDir", fileCheck(resourceDir, executable = false))
            .put("semnexis", fileCheck(semnexisRoot(), executable = false))

        val ready =
            clangDriver.isFile &&
            clangDriver.canExecute() &&
            lldDriver.isFile &&
            lldDriver.canExecute() &&
            sysroot.isDirectory &&
            resourceDir.isDirectory &&
            semnexisRoot().isDirectory

        val value = JSONObject()
            .put("toolchain", TOOLCHAIN_ID)
            .put("ready", ready)
            .put("target", target)
            .put("api", API_LEVEL)
            .put("hostAbi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
            .put("cwd", cwd)
            .put("checks", checks)
            .put("writableArtifactExecution", false)
            .put("shellEscape", false)

        val output = buildString {
            appendLine("Rift native Clang bootstrap")
            appendLine("toolchain: $TOOLCHAIN_ID")
            appendLine("target: $target")
            appendLine("ready: $ready")
            if (!ready) {
                appendLine("payload: missing/incomplete trusted APK toolchain payload")
            }
            append("generated RiftFS artifacts are compile/link outputs only; they are not executed from app-home storage")
        }

        return Result(output, value)
    }

    private fun buildSemnexis(cwd: String): Result {
        val diagnosis = doctor(cwd)
        require(diagnosis.value.optBoolean("ready")) {
            "Rift Clang payload is not ready; run 'riftclang doctor'"
        }

        val project = semnexisRoot()
        val include = confined(File(project, "include"), project)
        require(include.isDirectory) { "Semnexis include directory missing" }

        val sourceFiles = SEMNEXIS_SOURCES.map { relative ->
            confined(File(project, relative), project).also { file ->
                require(file.isFile) { "Semnexis source missing: $relative" }
            }
        }

        val buildRoot = File(riftRoot, "documents/builds/Semnexis").apply { mkdirs() }.canonicalFile
        require(buildRoot.path.startsWith(riftRoot.path + File.separator)) {
            "build output escaped RiftFS"
        }
        val output = File(buildRoot, "semx-android.elf").canonicalFile
        require(output.parentFile == buildRoot) { "invalid Semnexis output path" }

        val command = mutableListOf(
            clangDriver.absolutePath,
            "--driver-mode=g++",
            "--target=${androidTarget()}",
            "--sysroot=${sysroot.absolutePath}",
            "-resource-dir=${resourceDir.absolutePath}",
            "--ld-path=${lldDriver.absolutePath}",
            "-std=c++17",
            "-stdlib=libc++",
            "-O0",
            "-g0",
            "-fPIE",
            "-pie",
            "-Wall",
            "-Wextra",
            "-Wpedantic",
            "-Werror",
            "-I${include.absolutePath}"
        )
        sourceFiles.forEach { command += it.absolutePath }
        command += listOf(
            "-Wl,--build-id=sha1",
            "-o",
            output.absolutePath
        )

        if (output.exists()) {
            require(output.isFile) { "Semnexis build output is not a file" }
            require(output.delete()) { "could not replace previous Semnexis build artifact" }
        }

        val processResult = runTrustedProcess(command, project)
        require(processResult.exitCode == 0) {
            "clang++ failed with exit ${processResult.exitCode}: ${processResult.output}"
        }
        require(output.isFile && output.length() > 0L) {
            "clang++ reported success but produced no Semnexis ELF artifact"
        }

        val relativeArtifact = output.relativeTo(riftRoot).invariantSeparatorsPath
        val value = JSONObject()
            .put("toolchain", TOOLCHAIN_ID)
            .put("target", androidTarget())
            .put("artifact", relativeArtifact)
            .put("artifactBytes", output.length())
            .put("sourceCount", sourceFiles.size)
            .put("exitCode", processResult.exitCode)
            .put("compilerOutput", processResult.output)
            .put("executedArtifact", false)

        return Result(
            "Semnexis native bootstrap built: $relativeArtifact (${output.length()} bytes)",
            value
        )
    }

    private fun androidTarget(): String {
        val abis = Build.SUPPORTED_ABIS.toList()
        return when {
            abis.contains("arm64-v8a") -> "aarch64-linux-android$API_LEVEL"
            abis.contains("armeabi-v7a") -> "armv7a-linux-androideabi$API_LEVEL"
            else -> throw IllegalStateException(
                "Rift Clang bootstrap currently supports arm64-v8a/armeabi-v7a; device ABIs=${abis.joinToString()}"
            )
        }
    }

    private fun semnexisRoot(): File =
        confined(File(riftRoot, "workspace/Semnexis"), File(riftRoot, "workspace"))

    private fun confined(candidate: File, root: File): File {
        val canonicalRoot = root.canonicalFile
        val canonical = candidate.canonicalFile
        require(
            canonical == canonicalRoot ||
                canonical.path.startsWith(canonicalRoot.path + File.separator)
        ) {
            "path escaped allowed root: ${candidate.path}"
        }
        return canonical
    }

    private fun fileCheck(file: File, executable: Boolean): JSONObject =
        JSONObject()
            .put("path", file.absolutePath)
            .put("exists", file.exists())
            .put("file", file.isFile)
            .put("directory", file.isDirectory)
            .put("executable", if (executable) file.canExecute() else JSONObject.NULL)

    private data class ProcessResult(
        val exitCode: Int,
        val output: String
    )

    private fun runTrustedProcess(command: List<String>, cwd: File): ProcessResult {
        require(
            command.isNotEmpty() &&
                File(command.first()).canonicalFile == clangDriver.canonicalFile
        ) {
            "untrusted native executable"
        }
        require(cwd.canonicalFile == semnexisRoot()) {
            "native compiler cwd must be Semnexis project root"
        }

        val process = ProcessBuilder(command)
            .directory(cwd)
            .redirectErrorStream(true)
            .start()

        val reader = Executors.newSingleThreadExecutor()
        val outputFuture = reader.submit<String> {
            process.inputStream.use { input ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    val remaining = MAX_OUTPUT_BYTES - bytes.size()
                    if (remaining <= 0) continue
                    bytes.write(buffer, 0, minOf(count, remaining))
                }
                bytes.toString(Charsets.UTF_8.name())
            }
        }

        try {
            val finished = process.waitFor(BUILD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
                throw IllegalStateException(
                    "Rift Clang build timed out after $BUILD_TIMEOUT_MS ms"
                )
            }
            val output = runCatching {
                outputFuture.get(2, TimeUnit.SECONDS)
            }.getOrDefault("")
            return ProcessResult(process.exitValue(), output)
        } finally {
            reader.shutdownNow()
        }
    }
}
