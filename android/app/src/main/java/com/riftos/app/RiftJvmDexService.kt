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
 * Generic bounded JVM class -> DEX platform service.
 *
 * This service owns Android/JVM toolchain materialization and D8 only. Language-specific compile
 * recipes live outside RiftOS. The status schema intentionally remains compatible with the
 * device-proven external RiftBuild Hosted provider.
 */
class RiftJvmDexService(
    context: Context,
    private val workspaceRoot: File,
    riftRoot: File
) {
    companion object {
        private const val ASSET_ROOT = "riftbuild/kotlin-toolchain"
        private const val TOOLCHAIN_RELATIVE = "system/toolchains/rift-kotlin-v1"
        private const val DEFAULT_COMPILER = "kotlin-android"
        private val DEX_ENTRY = Regex("^classes(?:[2-9]|[1-9][0-9]+)?\\.dex$")
    }

    private val appContext = context.applicationContext
    private val toolchainRoot =
        File(riftRoot, TOOLCHAIN_RELATIVE)
            .apply { mkdirs() }
            .canonicalFile

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

    fun dexJvmClasses(
        projectRoot: File,
        classesRelative: String,
        outputRelative: String,
        minSdk: Int
    ): JSONObject {
        val project = checkedProject(projectRoot)
        require(minSdk in 26..36) { "JVM minSdk is out of bounds" }
        require(
            classesRelative.startsWith("build/riftbuild/") &&
                !classesRelative.contains("\\")
        ) {
            "JVM classes directory must stay under build/riftbuild"
        }
        require(
            outputRelative.startsWith("build/riftbuild/") &&
                !outputRelative.contains("\\")
        ) {
            "DEX output directory must stay under build/riftbuild"
        }

        val classesDir = File(project, classesRelative).canonicalFile
        val outputDir = File(project, outputRelative).canonicalFile
        require(confinedTo(project, classesDir) && classesDir.isDirectory) {
            "JVM classes directory is missing"
        }
        require(confinedTo(project, outputDir)) {
            "DEX output directory escaped project"
        }
        resetDirectory(outputDir)

        val androidJar = materializeAsset("android.jar")
        val stdlibJar = materializeAsset("kotlin-stdlib.jar")
        val classFiles =
            classesDir.walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .toList()
        require(classFiles.isNotEmpty()) {
            "JVM classes directory produced no class files"
        }

        RiftDeadline.check("RAPP JVM D8 preparation")
        val d8 =
            D8Command.builder()
                .setMinApiLevel(minSdk)
                .setOutput(outputDir.toPath(), OutputMode.DexIndexed)
        for (file in classFiles) d8.addProgramFiles(file.toPath())
        d8.addProgramFiles(stdlibJar.toPath())
        d8.addLibraryFiles(androidJar.toPath())
        D8.run(d8.build())
        RiftDeadline.check("RAPP JVM D8 completion")

        val dexFiles =
            outputDir.listFiles()
                ?.filter { it.isFile && DEX_ENTRY.matches(it.name) }
                ?.sortedBy { dexIndex(it.name) }
                .orEmpty()
        require(dexFiles.isNotEmpty() && dexFiles.first().name == "classes.dex") {
            "D8 produced no indexed DEX output"
        }

        val dex = JSONArray()
        for (file in dexFiles) {
            val header =
                file.inputStream().use { input ->
                    ByteArray(8).also { bytes ->
                        val count = input.read(bytes)
                        require(count == 8) {
                            "D8 output header is truncated: ${file.name}"
                        }
                    }
                }
            require(
                header[0] == 'd'.code.toByte() &&
                    header[1] == 'e'.code.toByte() &&
                    header[2] == 'x'.code.toByte() &&
                    header[3] == '\n'.code.toByte() &&
                    header[7] == 0.toByte()
            ) {
                "D8 output has invalid DEX magic: ${file.name}"
            }
            dex.put(fileInfo(file).put("name", file.name))
        }

        return JSONObject()
            .put("schema", "rift-jvm-dex/1")
            .put("state", "dexed")
            .put("classesDir", classesRelative)
            .put("outputDir", outputRelative)
            .put("minSdk", minSdk)
            .put("classFiles", classFiles.size)
            .put("dexFiles", dex)
    }

    private fun materializeAsset(name: String): File {
        require(name in setOf("android.jar", "kotlin-stdlib.jar"))
        val target = File(toolchainRoot, name).canonicalFile
        require(confinedTo(toolchainRoot, target)) {
            "JVM toolchain asset escaped toolchain root"
        }

        val bytes =
            appContext.assets.open("$ASSET_ROOT/$name").use { it.readBytes() }
        require(bytes.isNotEmpty() && bytes.size <= 64 * 1024 * 1024) {
            "JVM toolchain asset is missing or oversized: $name"
        }
        if (
            !target.isFile ||
            target.length() != bytes.size.toLong() ||
            sha256(target) != sha256(bytes)
        ) {
            val temp = File(toolchainRoot, ".$name.tmp").canonicalFile
            require(confinedTo(toolchainRoot, temp)) {
                "JVM toolchain temp escaped root"
            }
            temp.outputStream().use { it.write(bytes) }
            if (target.exists()) {
                require(target.delete()) {
                    "Could not replace JVM toolchain asset: $name"
                }
            }
            require(temp.renameTo(target)) {
                "Could not commit JVM toolchain asset: $name"
            }
        }
        return target
    }

    private fun checkedProject(projectRoot: File): File {
        val project = projectRoot.canonicalFile
        require(confinedTo(workspaceRoot.canonicalFile, project)) {
            "JVM project escaped workspace"
        }
        require(project.isDirectory) {
            "JVM project is not a directory"
        }
        return project
    }

    private fun resetDirectory(dir: File) {
        if (dir.exists()) {
            dir.walkBottomUp().forEach { file ->
                if (file != dir) {
                    require(file.delete()) {
                        "Could not clear JVM build output: ${file.name}"
                    }
                }
            }
        }
        require(dir.mkdirs() || dir.isDirectory) {
            "Could not create JVM build directory"
        }
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
        return digest.digest().joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }

    private fun dexIndex(name: String): Int =
        if (name == "classes.dex") {
            1
        } else {
            name.removePrefix("classes")
                .removeSuffix(".dex")
                .toIntOrNull()
                ?: Int.MAX_VALUE
        }
}
