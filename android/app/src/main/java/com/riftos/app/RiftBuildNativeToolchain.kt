package com.riftos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Structured native C/C++ toolchain runner for RiftBuild.
 *
 * Toolchains may be local, bundled, or downloaded/provisioned separately. RiftBuild never turns
 * project/source text into a shell command: the compiler is launched directly with an argv vector.
 */
class RiftBuildNativeToolchain(
    context: Context,
    private val riftRoot: File,
    private val workspaceRoot: File
) {
    private data class Toolchain(
        val compiler: File,
        val sysroot: File,
        val args: List<String>,
        val version: String,
        val source: String
    )

    private data class ProjectSpec(
        val library: String,
        val sources: List<File>,
        val includeDirs: List<File>,
        val libraries: List<String>,
        val cxxStandard: String,
        val api: Int,
        val optimization: String
    )

    private data class AbiSpec(
        val target: String,
        val abi: String,
        val triple: String,
        val elfClass: Int,
        val machine: Int
    )

    companion object {
        private const val TOOLCHAIN_SCHEMA = "riftbuild-android-clang-toolchain/1"
        private const val PROJECT_SCHEMA = "riftbuild-native-project/1"
        private const val TOOLCHAIN_RELATIVE = "system/toolchains/android-clang-v1"
        private const val TOOLCHAIN_MANIFEST = "toolchain.json"
        private const val PROJECT_MANIFEST = "rift-native.json"
        private const val BUNDLED_TOOLCHAIN_ASSET = "riftbuild/android-clang-v1.zip"
        private const val MAX_BUNDLED_TOOLCHAIN_FILES = 20_000
        private const val MAX_BUNDLED_TOOLCHAIN_BYTES = 512L * 1024L * 1024L
        private const val MAX_BUNDLED_TOOLCHAIN_ENTRY_BYTES = 128L * 1024L * 1024L
        private const val MAX_MANIFEST_BYTES = 256L * 1024L
        private const val MAX_SOURCES = 256
        private const val MAX_INCLUDE_DIRS = 64
        private const val MAX_LIBRARIES = 64
        private const val MAX_TOOLCHAIN_ARGS = 128
        private const val MAX_ARG_CHARS = 4096
        private const val MAX_SOURCE_BYTES = 32L * 1024L * 1024L
        private const val MAX_OUTPUT_BYTES = 128L * 1024L * 1024L
        private const val MAX_COMPILER_OUTPUT_BYTES = 1024 * 1024
        private const val PROCESS_TIMEOUT_SECONDS = 180L
        private val SAFE_LIBRARY = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")
        private val SAFE_LINK_LIBRARY = Regex("^[A-Za-z0-9_+.-]{1,80}$")
        private val SOURCE_EXTENSIONS = setOf("c", "cc", "cpp", "cxx")
        private val CXX_STANDARDS = setOf("c++17", "c++20", "c++23")
        private val OPTIMIZATIONS = setOf("O0", "O1", "O2", "O3", "Os", "Oz")
    }

    private val appContext = context.applicationContext
    private val toolchainRoot = File(riftRoot, TOOLCHAIN_RELATIVE).apply { mkdirs() }.canonicalFile

    fun status(): JSONObject {
        val manifest = File(toolchainRoot, TOOLCHAIN_MANIFEST).canonicalFile
        val out = JSONObject()
            .put("schema", "riftbuild-native-toolchain-status-v1")
            .put("contract", TOOLCHAIN_SCHEMA)
            .put("manifest", "/C:/Toolchains/android-clang-v1/$TOOLCHAIN_MANIFEST")
            .put("downloadedToolchainsAllowed", true)
            .put("bundledToolchainAsset", BUNDLED_TOOLCHAIN_ASSET)
            .put("bundledToolchainAvailable", bundledAssetAvailable())
            .put("processMode", "structured-argv")
            .put("shell", false)

        if (!manifest.isFile) {
            return out
                .put("ready", false)
                .put("blockers", JSONArray().put("native toolchain manifest missing"))
        }

        return runCatching {
            val toolchain = readToolchain()
            val blockers = JSONArray()
            if (!toolchain.compiler.isFile) blockers.put("configured compiler is not a file")
            if (toolchain.compiler.isFile && !toolchain.compiler.canExecute()) blockers.put("configured compiler is not executable")
            if (!toolchain.sysroot.isDirectory) blockers.put("configured sysroot is not a directory")
            out
                .put("ready", blockers.length() == 0)
                .put("version", toolchain.version)
                .put("source", toolchain.source)
                .put("compiler", toolchain.compiler.absolutePath)
                .put("compilerExecutable", toolchain.compiler.isFile && toolchain.compiler.canExecute())
                .put("sysroot", toolchain.sysroot.absolutePath)
                .put("toolchainArgCount", toolchain.args.size)
                .put("blockers", blockers)
        }.getOrElse { error ->
            out
                .put("ready", false)
                .put("error", error.message ?: error.javaClass.simpleName)
                .put("blockers", JSONArray().put("native toolchain manifest invalid"))
        }
    }

    fun installBundled(): JSONObject {
        require(bundledAssetAvailable()) { "Bundled native toolchain asset is not present in this RiftOS build" }
        val staging = File(toolchainRoot.parentFile, toolchainRoot.name + ".installing").canonicalFile
        require(confinedTo(toolchainRoot.parentFile.canonicalFile, staging)) { "Bundled toolchain staging escaped toolchain parent" }
        if (staging.exists()) deleteTreeBounded(staging, MAX_BUNDLED_TOOLCHAIN_FILES + 512)
        require(staging.mkdirs() || staging.isDirectory) { "Could not create bundled toolchain staging directory" }

        var fileCount = 0
        var totalBytes = 0L
        appContext.assets.open(BUNDLED_TOOLCHAIN_ASSET).use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.replace('\\', '/')
                    require(name.isNotBlank() && !name.startsWith("/") && name.split('/').none { it == ".." }) {
                        "Bundled toolchain archive contains unsafe path"
                    }
                    val output = File(staging, name).canonicalFile
                    require(confinedTo(staging, output)) { "Bundled toolchain entry escaped staging root" }
                    if (entry.isDirectory) {
                        require(output.mkdirs() || output.isDirectory) { "Could not create bundled toolchain directory" }
                    } else {
                        fileCount += 1
                        require(fileCount <= MAX_BUNDLED_TOOLCHAIN_FILES) { "Bundled toolchain file-count limit exceeded" }
                        output.parentFile?.let { require(it.mkdirs() || it.isDirectory) { "Could not create bundled toolchain parent directory" } }
                        var entryBytes = 0L
                        FileOutputStream(output).use { sink ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                entryBytes += count
                                totalBytes += count
                                require(entryBytes <= MAX_BUNDLED_TOOLCHAIN_ENTRY_BYTES) { "Bundled toolchain entry exceeds byte limit" }
                                require(totalBytes <= MAX_BUNDLED_TOOLCHAIN_BYTES) { "Bundled toolchain exceeds total byte limit" }
                                sink.write(buffer, 0, count)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
        }

        val stagedManifest = File(staging, TOOLCHAIN_MANIFEST).canonicalFile
        require(confinedTo(staging, stagedManifest) && stagedManifest.isFile) { "Bundled toolchain manifest is missing" }
        val stagedJson = readJson(stagedManifest)
        require(stagedJson.optString("schema") == TOOLCHAIN_SCHEMA) { "Bundled toolchain manifest schema is invalid" }

        val backup = File(toolchainRoot.parentFile, toolchainRoot.name + ".backup").canonicalFile
        if (backup.exists()) deleteTreeBounded(backup, MAX_BUNDLED_TOOLCHAIN_FILES + 512)
        if (toolchainRoot.exists()) require(toolchainRoot.renameTo(backup)) { "Could not stage previous native toolchain for replacement" }
        var committed = false
        try {
            require(staging.renameTo(toolchainRoot)) { "Could not commit bundled native toolchain" }
            committed = true
        } finally {
            if (!committed && backup.exists() && !toolchainRoot.exists()) backup.renameTo(toolchainRoot)
        }
        if (backup.exists()) deleteTreeBounded(backup, MAX_BUNDLED_TOOLCHAIN_FILES + 512)

        return status()
            .put("schema", "riftbuild-native-toolchain-install-v1")
            .put("installedFrom", "bundled-asset")
            .put("files", fileCount)
            .put("bytes", totalBytes)
    }

    fun compile(projectRoot: File, target: String): JSONObject {
        val canonicalProject = projectRoot.canonicalFile
        require(confinedTo(workspaceRoot, canonicalProject)) { "Native compile project escaped D:/Workspace" }
        require(canonicalProject.isDirectory) { "Native compile project is not a directory" }
        require(target == "arm32" || target == "arm64" || target == "universal") {
            "Native compile target must be arm32, arm64 or universal"
        }

        val toolchain = readToolchain()
        require(toolchain.compiler.isFile) { "Configured native compiler is missing" }
        require(toolchain.compiler.canExecute()) { "Configured native compiler is not executable" }
        require(toolchain.sysroot.isDirectory) { "Configured native sysroot is missing" }

        val spec = readProjectSpec(canonicalProject)
        val abis = when (target) {
            "arm32" -> listOf(abiArm32())
            "arm64" -> listOf(abiArm64())
            else -> listOf(abiArm64(), abiArm32())
        }
        val outputs = JSONArray()
        for (abi in abis) outputs.put(compileAbi(canonicalProject, toolchain, spec, abi))

        return JSONObject()
            .put("schema", "riftbuild-native-compile-v1")
            .put("projectSchema", PROJECT_SCHEMA)
            .put("toolchainSchema", TOOLCHAIN_SCHEMA)
            .put("target", target)
            .put("library", spec.library)
            .put("sourceCount", spec.sources.size)
            .put("includeDirCount", spec.includeDirs.size)
            .put("libraries", JSONArray(spec.libraries))
            .put("cxxStandard", spec.cxxStandard)
            .put("api", spec.api)
            .put("optimization", spec.optimization)
            .put("processMode", "structured-argv")
            .put("shell", false)
            .put("outputs", outputs)
            .put("state", "compiled-native")
    }

    private fun compileAbi(
        projectRoot: File,
        toolchain: Toolchain,
        spec: ProjectSpec,
        abi: AbiSpec
    ): JSONObject {
        val prepared = File(projectRoot, "build/riftbuild/prepared").canonicalFile
        require(confinedTo(projectRoot, prepared)) { "Prepared output escaped project root" }
        val outputDir = File(prepared, "lib/${abi.abi}").canonicalFile
        require(confinedTo(prepared, outputDir)) { "Native ABI output escaped prepared package root" }
        require(outputDir.mkdirs() || outputDir.isDirectory) { "Could not create native ABI output directory" }

        val output = File(outputDir, "lib${spec.library}.so").canonicalFile
        require(confinedTo(outputDir, output)) { "Native library output escaped ABI directory" }
        if (output.exists()) require(output.delete()) { "Could not replace stale native library output" }

        val argv = ArrayList<String>()
        argv += toolchain.compiler.absolutePath
        argv += toolchain.args
        argv += "--target=${abi.triple}${spec.api}"
        argv += "--sysroot=${toolchain.sysroot.absolutePath}"
        argv += "-std=${spec.cxxStandard}"
        argv += "-fPIC"
        argv += "-shared"
        argv += "-${spec.optimization}"
        argv += "-fvisibility=hidden"
        argv += "-Wl,--build-id=none"
        argv += "-Wl,-soname,${output.name}"
        for (dir in spec.includeDirs) argv += "-I${dir.absolutePath}"
        argv += "-o"
        argv += output.absolutePath
        for (source in spec.sources) argv += source.absolutePath
        for (library in spec.libraries) argv += "-l" + library

        val tempDir = File(projectRoot, "build/riftbuild/tmp/${abi.abi}").canonicalFile
        require(confinedTo(projectRoot, tempDir)) { "Native compiler temp directory escaped project root" }
        require(tempDir.mkdirs() || tempDir.isDirectory) { "Could not create native compiler temp directory" }

        val process = try {
            ProcessBuilder(argv)
                .directory(projectRoot)
                .redirectErrorStream(true)
                .apply {
                    environment()["TMPDIR"] = tempDir.absolutePath
                    environment()["LD_LIBRARY_PATH"] = toolchain.compiler.parentFile?.absolutePath.orEmpty()
                }
                .start()
        } catch (error: Exception) {
            error("Native compiler launch failed for ${abi.abi}: ${error.message ?: error.javaClass.simpleName}")
        }

        val captured = ByteArrayOutputStream()
        var truncated = false
        val drain = Thread({
            val buffer = ByteArray(8192)
            process.inputStream.use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    val remaining = MAX_COMPILER_OUTPUT_BYTES - captured.size()
                    if (remaining > 0) captured.write(buffer, 0, minOf(count, remaining))
                    if (count > remaining) truncated = true
                }
            }
        }, "riftbuild-native-compiler-output-${abi.abi}")
        drain.isDaemon = true
        drain.start()

        val finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            drain.join(1000)
            error("Native compiler timed out for ${abi.abi}")
        }
        drain.join(1000)
        val exitCode = process.exitValue()
        val compilerOutput = captured.toString(Charsets.UTF_8.name())
        require(exitCode == 0) {
            "Native compiler failed for ${abi.abi} with exit $exitCode" +
                if (compilerOutput.isBlank()) "" else ": " + compilerOutput.take(4096)
        }

        require(output.isFile) { "Native compiler did not produce ${output.name}" }
        require(output.length() in 1L..MAX_OUTPUT_BYTES) { "Native compiler output size is invalid" }
        verifyElf(output, abi)

        return JSONObject()
            .put("abi", abi.abi)
            .put("targetTriple", abi.triple + spec.api)
            .put("path", "build/riftbuild/prepared/lib/${abi.abi}/${output.name}")
            .put("bytes", output.length())
            .put("sha256", sha256(output))
            .put("elfClass", if (abi.elfClass == 2) 64 else 32)
            .put("machine", abi.machine)
            .put("exitCode", exitCode)
            .put("compilerOutput", compilerOutput)
            .put("compilerOutputTruncated", truncated)
    }

    private fun readToolchain(): Toolchain {
        val manifestFile = File(toolchainRoot, TOOLCHAIN_MANIFEST).canonicalFile
        require(confinedTo(toolchainRoot, manifestFile) && manifestFile.isFile) {
            "Native toolchain manifest missing: /C:/Toolchains/android-clang-v1/$TOOLCHAIN_MANIFEST"
        }
        val manifest = readJson(manifestFile)
        require(manifest.optString("schema") == TOOLCHAIN_SCHEMA) { "Unsupported native toolchain schema" }
        val compilerRaw = manifest.optString("compiler").trim()
        val sysrootRaw = manifest.optString("sysroot").trim()
        require(compilerRaw.isNotBlank()) { "Native toolchain compiler is required" }
        require(sysrootRaw.isNotBlank()) { "Native toolchain sysroot is required" }
        val compiler = resolveToolPath(compilerRaw, allowNative = true)
        val sysroot = resolveToolPath(sysrootRaw, allowNative = false)
        val argsArray = manifest.optJSONArray("args") ?: JSONArray()
        require(argsArray.length() <= MAX_TOOLCHAIN_ARGS) { "Native toolchain arg count exceeds limit" }
        val toolArgs = ArrayList<String>(argsArray.length())
        for (i in 0 until argsArray.length()) {
            val value = argsArray.getString(i)
                .replace("%TOOLCHAIN%", toolchainRoot.absolutePath)
                .replace("%SYSROOT%", sysroot.absolutePath)
                .replace("%COMPILER_DIR%", compiler.parentFile?.absolutePath.orEmpty())
            require(value.length <= MAX_ARG_CHARS && !value.contains('\u0000')) { "Native toolchain arg is invalid" }
            toolArgs += value
        }
        return Toolchain(
            compiler = compiler,
            sysroot = sysroot,
            args = toolArgs,
            version = manifest.optString("version", "unknown").take(120),
            source = manifest.optString("source", "local").take(240)
        )
    }

    private fun readProjectSpec(projectRoot: File): ProjectSpec {
        val manifestFile = File(projectRoot, PROJECT_MANIFEST).canonicalFile
        require(confinedTo(projectRoot, manifestFile) && manifestFile.isFile) {
            "Native project manifest missing: $PROJECT_MANIFEST"
        }
        val manifest = readJson(manifestFile)
        require(manifest.optString("schema") == PROJECT_SCHEMA) { "Unsupported native project schema" }

        val library = manifest.optString("library").trim()
        require(SAFE_LIBRARY.matches(library)) { "Native project library name is invalid" }

        val sourceArray = manifest.optJSONArray("sources") ?: error("Native project sources are required")
        require(sourceArray.length() in 1..MAX_SOURCES) { "Native project source count is invalid" }
        val sources = ArrayList<File>()
        for (i in 0 until sourceArray.length()) {
            val source = resolveProjectPath(projectRoot, sourceArray.getString(i))
            require(source.isFile) { "Native source is missing: ${sourceArray.getString(i)}" }
            require(source.extension.lowercase() in SOURCE_EXTENSIONS) { "Unsupported native source extension: ${source.name}" }
            require(source.length() <= MAX_SOURCE_BYTES) { "Native source exceeds per-file limit: ${source.name}" }
            sources += source
        }

        val includeDirs = ArrayList<File>()
        val includeArray = manifest.optJSONArray("includeDirs") ?: JSONArray()
        require(includeArray.length() <= MAX_INCLUDE_DIRS) { "Native include-dir count is invalid" }
        for (i in 0 until includeArray.length()) {
            val dir = resolveProjectPath(projectRoot, includeArray.getString(i))
            require(dir.isDirectory) { "Native include directory is missing: ${includeArray.getString(i)}" }
            includeDirs += dir
        }

        val libraries = ArrayList<String>()
        val libraryArray = manifest.optJSONArray("libraries") ?: JSONArray()
        require(libraryArray.length() <= MAX_LIBRARIES) { "Native link-library count exceeds limit" }
        for (i in 0 until libraryArray.length()) {
            val name = libraryArray.getString(i).trim()
            require(SAFE_LINK_LIBRARY.matches(name)) { "Native link-library name is invalid" }
            libraries += name
        }

        val standard = manifest.optString("cxxStandard", "c++20")
        require(standard in CXX_STANDARDS) { "Native cxxStandard must be c++17, c++20 or c++23" }
        val api = manifest.optInt("api", 26)
        require(api in 21..100) { "Native Android API level is out of range" }
        val optimization = manifest.optString("optimization", "O2")
        require(optimization in OPTIMIZATIONS) { "Native optimization must be O0/O1/O2/O3/Os/Oz" }

        return ProjectSpec(library, sources, includeDirs, libraries, standard, api, optimization)
    }

    private fun resolveProjectPath(projectRoot: File, raw: String): File {
        require(raw.isNotBlank() && !raw.contains('\u0000')) { "Native project path is invalid" }
        val normalized = raw.replace('\\', '/')
        require(!normalized.startsWith("/") && !Regex("^[A-Za-z]:").containsMatchIn(normalized)) {
            "Native project paths must be relative"
        }
        require(normalized.split('/').none { it == ".." }) { "Native project path traversal is forbidden" }
        val file = File(projectRoot, normalized).canonicalFile
        require(confinedTo(projectRoot, file)) { "Native project path escaped project root" }
        return file
    }

    private fun resolveToolPath(raw: String, allowNative: Boolean): File {
        return when {
            allowNative && raw.startsWith("native:") -> {
                val name = raw.removePrefix("native:")
                require(name.matches(Regex("^[A-Za-z0-9._+-]{1,160}$"))) { "Native-library compiler name is invalid" }
                File(appContext.applicationInfo.nativeLibraryDir, name).canonicalFile
            }
            raw.startsWith("absolute:") -> {
                val value = raw.removePrefix("absolute:")
                require(value.startsWith("/")) { "Absolute toolchain path must begin with /" }
                File(value).canonicalFile
            }
            else -> {
                val file = File(toolchainRoot, raw).canonicalFile
                require(confinedTo(toolchainRoot, file)) { "Toolchain path escaped C:/Toolchains/android-clang-v1" }
                file
            }
        }
    }

    private fun readJson(file: File): JSONObject {
        require(file.length() in 1L..MAX_MANIFEST_BYTES) { "JSON manifest exceeds limit: ${file.name}" }
        return JSONObject(file.readText(Charsets.UTF_8))
    }

    private fun verifyElf(file: File, abi: AbiSpec) {
        val header = ByteArray(20)
        file.inputStream().use { input ->
            var read = 0
            while (read < header.size) {
                val count = input.read(header, read, header.size - read)
                require(count > 0) { "Native compiler output has a truncated ELF header" }
                read += count
            }
        }
        require(
            header[0] == 0x7f.toByte() &&
                header[1] == 'E'.code.toByte() &&
                header[2] == 'L'.code.toByte() &&
                header[3] == 'F'.code.toByte()
        ) { "Native compiler output is not ELF" }
        require((header[4].toInt() and 0xff) == abi.elfClass) { "Native compiler ELF class mismatch for ${abi.abi}" }
        require((header[5].toInt() and 0xff) == 1) { "Native compiler output must be little-endian ELF" }
        val type = (header[16].toInt() and 0xff) or ((header[17].toInt() and 0xff) shl 8)
        require(type == 3) { "Native compiler output must be ET_DYN shared object" }
        val machine = (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
        require(machine == abi.machine) { "Native compiler ELF machine mismatch for ${abi.abi}" }
    }

    private fun bundledAssetAvailable(): Boolean = runCatching {
        appContext.assets.open(BUNDLED_TOOLCHAIN_ASSET).use { input -> input.read() >= 0 }
    }.getOrDefault(false)

    private fun deleteTreeBounded(root: File, maxEntries: Int) {
        var count = 0
        root.walkBottomUp().forEach { entry ->
            count += 1
            require(count <= maxEntries) { "Native toolchain tree exceeds deletion bound" }
            require(entry.delete()) { "Could not delete native toolchain path: " + entry.name }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun confinedTo(root: File, child: File): Boolean {
        val rootPath = root.canonicalFile.path
        val childPath = child.canonicalFile.path
        return childPath == rootPath || childPath.startsWith(rootPath + File.separator)
    }

    private fun abiArm32() = AbiSpec("arm32", "armeabi-v7a", "armv7a-linux-androideabi", 1, 40)
    private fun abiArm64() = AbiSpec("arm64", "arm64-v8a", "aarch64-linux-android", 2, 183)
}
