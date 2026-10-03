package com.riftos.app

import android.content.Context
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.OutputMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Bounded on-device Kotlin -> external managed compiler -> JVM class -> DEX pipeline.
 *
 * RiftOS owns only project validation, toolchain assets, compiler request normalization and D8.
 * The Kotlin compiler implementation itself is project/bundle owned and hot-swappable.
 */
class RiftBuildKotlinCompiler(
    context: Context,
    private val workspaceRoot: File,
    private val riftRoot: File
) {
    companion object {
        private const val SCHEMA = "riftbuild-kotlin-project/1"
        private const val MANIFEST = "rift-kotlin.json"
        private const val ASSET_ROOT = "riftbuild/kotlin-toolchain"
        private const val TOOLCHAIN_RELATIVE = "system/toolchains/rift-kotlin-v1"
        private const val DEFAULT_COMPILER = "kotlin-android"
        private const val MAX_SOURCES = 64
        private const val MAX_SOURCE_BYTES = 2L * 1024L * 1024L
        private const val MAX_TOTAL_SOURCE_BYTES = 8L * 1024L * 1024L
        private val DEX_ENTRY = Regex("^classes(?:[2-9]|[1-9][0-9]+)?\\.dex$")
    }

    private val appContext = context.applicationContext
    private val toolchainRoot = File(riftRoot, TOOLCHAIN_RELATIVE).apply { mkdirs() }.canonicalFile

    fun status(): JSONObject {
        val androidJar = materializeAsset("android.jar")
        val stdlibJar = materializeAsset("kotlin-stdlib.jar")
        return JSONObject()
            .put("schema", "riftbuild-kotlin-toolchain-status/2")
            .put("ready", androidJar.isFile && stdlibJar.isFile)
            .put("compilerAuthority", "managed-external")
            .put("defaultCompilerId", DEFAULT_COMPILER)
            .put("dexer", "D8")
            .put("androidJar", fileInfo(androidJar))
            .put("kotlinStdlib", fileInfo(stdlibJar))
    }

    fun compile(
        projectRoot: File,
        compilerInvoker: (String, JSONObject) -> JSONObject
    ): JSONObject {
        val project = checkedProject(projectRoot)
        val manifestFile = File(project, MANIFEST).canonicalFile
        require(confinedTo(project, manifestFile) && manifestFile.isFile) {
            "Kotlin build manifest is missing: $MANIFEST"
        }
        require(manifestFile.length() in 1..(256L * 1024L)) {
            "Kotlin build manifest is oversized"
        }

        val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
        require(manifest.optString("schema") == SCHEMA) {
            "Unsupported Kotlin build manifest schema"
        }

        val compilerId = manifest.optString("compiler", DEFAULT_COMPILER).trim()
        require(compilerId.matches(Regex("^[A-Za-z0-9._+-]{1,80}$"))) {
            "Kotlin compiler id is invalid"
        }

        val module = manifest.optString("module", "rift-kotlin").trim().ifBlank { "rift-kotlin" }
        require(module.matches(Regex("^[A-Za-z0-9._+-]{1,80}$"))) {
            "Kotlin module name is invalid"
        }

        val minSdk = manifest.optInt("minSdk", 26)
        require(minSdk in 26..36) { "Kotlin minSdk is out of bounds" }

        val jvmTarget = manifest.optString("jvmTarget", "1.8")
        require(jvmTarget in setOf("1.8", "11", "17")) { "Unsupported Kotlin JVM target" }

        val sourceArray = manifest.optJSONArray("sources") ?: error("Kotlin sources array is required")
        require(sourceArray.length() in 1..MAX_SOURCES) { "Kotlin source count is out of bounds" }

        val relativeSources = JSONArray()
        var totalSourceBytes = 0L
        for (index in 0 until sourceArray.length()) {
            val relative = sourceArray.getString(index).trim()
            require(relative.endsWith(".kt") && !relative.startsWith("/") && !relative.contains("\\")) {
                "Kotlin source path is invalid"
            }
            val file = File(project, relative).canonicalFile
            require(confinedTo(project, file) && file.isFile) {
                "Kotlin source is missing or escaped project: $relative"
            }
            require(file.length() in 1..MAX_SOURCE_BYTES) { "Kotlin source is oversized: $relative" }
            totalSourceBytes += file.length()
            require(totalSourceBytes <= MAX_TOTAL_SOURCE_BYTES) { "Kotlin total source bytes exceed limit" }
            relativeSources.put(relative)
        }

        val outputRelative = manifest.optString("outputDir", "build/riftbuild/hot-dex").trim()
        require(outputRelative.startsWith("build/riftbuild/") && !outputRelative.contains("\\")) {
            "Kotlin outputDir must stay under build/riftbuild"
        }
        val outputDir = File(project, outputRelative).canonicalFile
        require(confinedTo(project, outputDir)) { "Kotlin outputDir escaped project" }

        val classesRelative = "build/riftbuild/kotlin-classes"
        val classesDir = File(project, classesRelative).canonicalFile
        require(confinedTo(project, classesDir)) { "Kotlin classes directory escaped project" }
        resetDirectory(classesDir)
        resetDirectory(outputDir)

        val androidJar = materializeAsset("android.jar")
        val stdlibJar = materializeAsset("kotlin-stdlib.jar")

        val request = JSONObject()
            .put("schema", RiftBuildManagedToolchains.COMPILER_PROTOCOL)
            .put("language", "kotlin")
            .put("sources", relativeSources)
            .put("outputDir", classesRelative)
            .put(
                "classpath",
                JSONArray()
                    .put(androidJar.absolutePath)
                    .put(stdlibJar.absolutePath)
            )
            .put(
                "options",
                JSONObject()
                    .put("moduleName", module)
                    .put("jvmTarget", jvmTarget)
                    .put("minSdk", minSdk)
                    .put("noJdk", true)
                    .put("noStdlib", true)
                    .put("noReflect", true)
            )

        val compilerReceipt = compilerInvoker(compilerId, request)
        require(compilerReceipt.optString("state") == "success") {
            "Managed Kotlin compiler failed: " +
                compilerReceipt.optString("detail", compilerReceipt.toString())
        }

        val response = compilerReceipt.optJSONObject("response")
            ?: error("Managed Kotlin compiler returned no compiler response")
        require(response.optString("state") == "success") {
            "Managed Kotlin compiler rejected source: " +
                response.optString("messages", response.toString())
        }

        val classFiles = classesDir.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .toList()
        require(classFiles.isNotEmpty()) { "Managed Kotlin compiler produced no class files" }

        val d8 = D8Command.builder()
            .setMinApiLevel(minSdk)
            .setOutput(outputDir.toPath(), OutputMode.DexIndexed)
        for (file in classFiles) d8.addProgramFiles(file.toPath())
        d8.addProgramFiles(stdlibJar.toPath())
        d8.addLibraryFiles(androidJar.toPath())
        D8.run(d8.build())

        val dexFiles = outputDir.listFiles()
            ?.filter { it.isFile && DEX_ENTRY.matches(it.name) }
            ?.sortedBy { dexIndex(it.name) }
            .orEmpty()
        require(dexFiles.isNotEmpty() && dexFiles.first().name == "classes.dex") {
            "D8 produced no indexed DEX output"
        }

        val dex = JSONArray()
        for (file in dexFiles) {
            val header = file.inputStream().use { input -> ByteArray(8).also { input.read(it) } }
            require(
                header.size >= 8 &&
                    header[0] == 'd'.code.toByte() &&
                    header[1] == 'e'.code.toByte() &&
                    header[2] == 'x'.code.toByte() &&
                    header[3] == '\n'.code.toByte() &&
                    header[7] == 0.toByte()
            ) { "D8 output has invalid DEX magic: ${file.name}" }
            dex.put(fileInfo(file).put("name", file.name))
        }

        return JSONObject()
            .put("schema", "riftbuild-kotlin-compile/2")
            .put("state", "compiled-dex")
            .put("project", project.absolutePath)
            .put("compilerId", compilerId)
            .put("compilerReceipt", compilerReceipt)
            .put("module", module)
            .put("sourceCount", relativeSources.length())
            .put("sourceBytes", totalSourceBytes)
            .put("classFiles", classFiles.size)
            .put("minSdk", minSdk)
            .put("jvmTarget", jvmTarget)
            .put("outputDir", outputRelative)
            .put("dexFiles", dex)
            .put("messages", response.optString("messages", ""))
    }

    private fun materializeAsset(name: String): File {
        require(name in setOf("android.jar", "kotlin-stdlib.jar"))
        val target = File(toolchainRoot, name).canonicalFile
        require(confinedTo(toolchainRoot, target)) { "Kotlin toolchain asset escaped toolchain root" }

        val bytes = appContext.assets.open("$ASSET_ROOT/$name").use { it.readBytes() }
        require(bytes.isNotEmpty() && bytes.size <= 64 * 1024 * 1024) {
            "Kotlin toolchain asset is missing or oversized: $name"
        }
        if (!target.isFile || target.length() != bytes.size.toLong() || sha256(target) != sha256(bytes)) {
            val temp = File(toolchainRoot, ".$name.tmp").canonicalFile
            require(confinedTo(toolchainRoot, temp)) { "Kotlin toolchain temp escaped root" }
            temp.outputStream().use { it.write(bytes) }
            if (target.exists()) require(target.delete()) { "Could not replace Kotlin toolchain asset: $name" }
            require(temp.renameTo(target)) { "Could not commit Kotlin toolchain asset: $name" }
        }
        return target
    }

    private fun checkedProject(projectRoot: File): File {
        val project = projectRoot.canonicalFile
        require(confinedTo(workspaceRoot.canonicalFile, project)) { "Kotlin project escaped workspace" }
        require(project.isDirectory) { "Kotlin project is not a directory" }
        return project
    }

    private fun resetDirectory(dir: File) {
        if (dir.exists()) {
            dir.walkBottomUp().forEach { file ->
                if (file != dir) require(file.delete()) { "Could not clear Kotlin build output: ${file.name}" }
            }
        }
        require(dir.mkdirs() || dir.isDirectory) { "Could not create Kotlin build directory" }
    }

    private fun confinedTo(root: File, child: File): Boolean {
        val rootPath = root.canonicalFile.toPath()
        val childPath = child.canonicalFile.toPath()
        return childPath == rootPath || childPath.startsWith(rootPath)
    }

    private fun fileInfo(file: File): JSONObject =
        JSONObject()
            .put("path", file.absolutePath)
            .put("bytes", file.length())
            .put("sha256", sha256(file))

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun dexIndex(name: String): Int =
        if (name == "classes.dex") 1
        else name.removePrefix("classes").removeSuffix(".dex").toIntOrNull() ?: Int.MAX_VALUE
}
