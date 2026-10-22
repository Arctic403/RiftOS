package com.riftos.app

import android.content.Context
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.OutputMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.JarEntry
import java.util.zip.ZipFile

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
        val metadata = Properties().apply {
            appContext.assets.open("$ASSET_ROOT/toolchain.properties").use { load(it) }
        }
        require(metadata.getProperty("androidJarSha256") == sha256(androidJar) &&
            metadata.getProperty("kotlinStdlibSha256") == sha256(stdlibJar)) {
            "Packaged JVM toolchain metadata identity mismatch"
        }
        val api = metadata.getProperty("androidApiLevel")?.toIntOrNull()
            ?: error("Packaged Android API level is missing")
        require(api in 26..36) { "Packaged Android API level is unsupported" }
        return JSONObject()
            .put("schema", "riftbuild-kotlin-toolchain-status/2")
            .put("ready", true)
            .put("compilerAuthority", "managed-external")
            .put("defaultCompilerId", DEFAULT_COMPILER)
            .put("dexer", "D8")
            .put("androidApiLevel", api)
            .put("kotlinStdlibVersion", jarVersion(stdlibJar))
            .put("jvmDependencyInputsVersion", 1)
            .put("javaResources", true)
            .put("androidJar", fileInfo(androidJar))
            .put("kotlinStdlib", fileInfo(stdlibJar))
    }

    private data class Inputs(
        val program: List<File>, val classpath: List<File>,
        val stdlib: File, val android: File, val status: JSONObject
    )

    private fun jarVersion(file: File): String =
        JarFile(file).use { it.manifest?.mainAttributes?.getValue("Implementation-Version") }
            ?.substringBefore('-') ?: error("Standard library version metadata is missing")

    private fun resolveInputs(project: File, request: JSONObject?, stage: File? = null): Inputs {
        val meta = status()
        val stdlibDefault = File(meta.getJSONObject("kotlinStdlib").getString("path")).canonicalFile
        val androidJar = File(meta.getJSONObject("androidJar").getString("path")).canonicalFile
        val spec = request ?: JSONObject()
        require(spec.keys().asSequence().all {
            it in setOf("programJars", "classpathJars", "stdlib", "minCompileSdk", "kotlinStdlibVersion")
        }) { "Unknown JVM dependency option" }
        if (spec.has("minCompileSdk")) {
            val api = spec.get("minCompileSdk")
            require(api is Number && api.toDouble() == api.toInt().toDouble() && api.toInt() in 26..36 &&
                api.toInt() <= meta.getInt("androidApiLevel")) {
                "Required Android compile SDK is unavailable"
            }
        }
        var bytes = 0L
        val seen = HashSet<String>()
        fun pinned(value: JSONObject): File {
            require(value.keys().asSequence().all { it == "source" || it == "sha256" }) {
                "Unknown pinned JVM JAR option"
            }
            val relative = value.getString("source")
            require(relative.endsWith(".jar") && RiftJvmJarSupport.safePath(relative)) {
                "Invalid project JVM JAR path"
            }
            val expected = value.getString("sha256")
            require(Regex("^[0-9a-f]{64}$").matches(expected)) { "JVM JAR SHA-256 is invalid" }
            val original = File(project, relative).canonicalFile
            require(confinedTo(project, original) && original.isFile &&
                original.length() in 1..(32L * 1024L * 1024L)) {
                "JVM JAR escaped project, is missing or exceeds 32 MiB"
            }
            require(seen.add(original.path)) { "Duplicate JVM JAR input" }
            bytes += original.length()
            require(bytes <= 128L * 1024L * 1024L) { "JVM input bytes exceed 128 MiB" }
            val file = if (stage == null) original else File(stage, "input-" + seen.size + ".jar")
            if (stage != null) {
                original.inputStream().use { input ->
                    file.outputStream().use { out ->
                        val buffer = ByteArray(65536)
                        var copied = 0L
                        while (true) {
                            RiftDeadline.check("JVM dependency snapshot")
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            require(copied <= 32L * 1024L * 1024L) { "JVM JAR grew during copy" }
                            out.write(buffer, 0, count)
                        }
                    }
                }
            }
            require(sha256(file) == expected) { "JVM JAR SHA-256 mismatch: $relative" }
            return file
        }
        fun jars(key: String): List<File> {
            if (!spec.has(key)) return emptyList()
            val array = spec.getJSONArray(key)
            require(array.length() <= 16) { "JVM JAR count exceeds limit" }
            return (0 until array.length()).map { pinned(array.getJSONObject(it)) }
        }
        val program = jars("programJars")
        val classpath = jars("classpathJars")
        require(program.size + classpath.size <= 16) { "JVM JAR count exceeds limit" }
        val stdlib = if (spec.has("stdlib")) pinned(spec.getJSONObject("stdlib")) else stdlibDefault
        if (spec.has("kotlinStdlibVersion")) {
            val required = spec.getString("kotlinStdlibVersion")
            require(Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$").matches(required) &&
                jarVersion(stdlib) == required) { "Required Kotlin standard library version is unavailable" }
        }
        return Inputs(program, classpath, stdlib, androidJar, meta)
    }

    fun validateCompilerInputs(projectRoot: File, request: JSONObject, classpath: JSONArray) {
        val selected = resolveInputs(checkedProject(projectRoot), request)
        val paths = (0 until classpath.length()).map {
            File(classpath.getString(it)).canonicalPath
        }
        // The selected SDK, one stdlib and explicitly pinned project JARs
        // are the *entire* JVM compile classpath. Reject undeclared extras:
        // accepting them here could compile against classes absent from D8.
        val declared = listOf(selected.android, selected.stdlib) +
            selected.program + selected.classpath
        RiftJvmClasspathPolicy.requireExact(paths, declared.map { it.path })
    }

    fun dexJvmClasses(
        projectRoot: File,
        classesRelative: String,
        outputRelative: String,
        minSdk: Int,
        jvmInputs: JSONObject? = null
    ): JSONObject {
        val project = checkedProject(projectRoot)
        require(minSdk in 26..36) { "JVM minSdk is out of bounds" }
        require(classesRelative.startsWith("build/riftbuild/") &&
            RiftJvmJarSupport.safePath(classesRelative)) { "Invalid JVM classes directory" }
        require(outputRelative.startsWith("build/riftbuild/") &&
            RiftJvmJarSupport.safePath(outputRelative)) { "Invalid DEX output directory" }
        val classesDir = File(project, classesRelative).canonicalFile
        val outputDir = File(project, outputRelative).canonicalFile
        require(confinedTo(project, classesDir) && classesDir.isDirectory &&
            confinedTo(project, outputDir) && !confinedTo(outputDir, classesDir) &&
            !confinedTo(classesDir, outputDir)) { "JVM class/output directories overlap or escaped project" }
        // Snapshot inputs in a new sibling; a failed D8 run cannot expose partial final DEX.
        val stage = File(outputDir.parentFile, "." + outputDir.name + "-" + UUID.randomUUID()).canonicalFile
        require(confinedTo(project, stage) && stage.mkdirs()) { "Could not create JVM staging directory" }
        try {
            val inputDir = File(stage, "inputs").apply { mkdirs() }
            val selected = resolveInputs(project, jvmInputs, inputDir)
            val classes = classesDir.walkTopDown().onEnter { directory ->
                RiftDeadline.check("JVM class enumeration")
                require(confinedTo(classesDir, directory.canonicalFile)) { "JVM class directory escaped project" }
                true
            }.filter { it.isFile && it.extension == "class" }.take(50001).toList()
            require(classes.isNotEmpty() && classes.size <= 50000) { "JVM class count is invalid" }
            var total = 0L
            val names = HashSet<String>()
            for (file in classes) {
                RiftDeadline.check("JVM app class verification")
                require(confinedTo(classesDir, file.canonicalFile)) { "JVM class escaped directory" }
                val name = file.relativeTo(classesDir).invariantSeparatorsPath
                require(!name.startsWith("META-INF/") && name != "module-info.class" &&
                    RiftJvmJarSupport.safePath(name) && names.add(name)) { "Invalid JVM app class path" }
                total += file.length()
                require(total <= 64L * 1024L * 1024L) { "JVM app classes exceed 64 MiB" }
            }
            val resources = linkedMapOf<String, ByteArray>()
            val program = ArrayList<File>()
            (selected.program + selected.stdlib).forEachIndexed { index, jar ->
                val normalized = File(inputDir, "program-$index.jar")
                RiftJvmJarSupport.prepare(jar, normalized, names, resources)
                program.add(normalized)
            }
            val classpath = selected.classpath.mapIndexed { index, jar ->
                File(inputDir, "classpath-$index.jar").also {
                    RiftJvmJarSupport.prepare(jar, it, HashSet(), null)
                }
            }
            val dexDir = File(stage, "dex").apply { mkdirs() }
            val command = D8Command.builder().setMinApiLevel(minSdk)
                .setOutput(dexDir.toPath(), OutputMode.DexIndexed)
            for (file in classes) command.addProgramFiles(file.toPath())
            for (file in program) command.addProgramFiles(file.toPath())
            for (file in classpath) command.addClasspathFiles(file.toPath())
            command.addLibraryFiles(selected.android.toPath())
            RiftDeadline.check("RAPP JVM D8 preparation")
            D8.run(command.build())
            RiftDeadline.check("RAPP JVM D8 completion")
            val dexFiles = dexDir.listFiles()?.filter { it.isFile && DEX_ENTRY.matches(it.name) }
                ?.sortedBy { dexIndex(it.name) }.orEmpty()
            require(dexFiles.isNotEmpty() && dexFiles.first().name == "classes.dex" &&
                dexFiles.size <= 99) { "D8 produced invalid indexed DEX output" }
            for (file in dexFiles) {
                val header = file.inputStream().use { input ->
                    ByteArray(8).also { require(input.read(it) == 8) { "D8 output header is truncated" } }
                }
                require(header[0] == 100.toByte() && header[1] == 101.toByte() &&
                    header[2] == 120.toByte() && header[3] == 10.toByte() && header[7] == 0.toByte()) {
                    "D8 output has invalid DEX magic"
                }
            }
            val resourceRows = JSONArray()
            for ((name, bytes) in resources.toSortedMap()) {
                RiftDeadline.check("JVM Java resource staging")
                val file = File(dexDir, "java-resources/$name").canonicalFile
                require(confinedTo(dexDir, file)) { "Java resource escaped DEX output" }
                require(file.parentFile?.mkdirs() == true || file.parentFile?.isDirectory == true)
                file.writeBytes(bytes)
                resourceRows.put(JSONObject().put("name", name).put("bytes", bytes.size)
                    .put("sha256", sha256(bytes)))
            }
            RiftDeadline.check("JVM output publication")
            // Preserve last good output until the validated replacement is published.
            // On a failed rename restore it; if restore itself fails keep its backup.
            RiftJvmDexPublication.publish(dexDir, outputDir)
            val dex = JSONArray()
            for (file in dexFiles) dex.put(fileInfo(File(outputDir, file.name)).put("name", file.name))
            return JSONObject().put("schema", "rift-jvm-dex/1").put("state", "dexed")
                .put("classesDir", classesRelative).put("outputDir", outputRelative)
                .put("minSdk", minSdk).put("classFiles", classes.size)
                .put("dexFiles", dex).put("javaResources", resourceRows)
                .put("jvmDependencyInputsVersion", 1)
        } finally {
            stage.deleteRecursively()
        }
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
                RiftDeadline.check("JVM artifact hashing")
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

/** Enforce a complete, non-duplicated compile classpath for explicit JVM inputs. */
internal object RiftJvmClasspathPolicy {
    fun requireExact(actual: List<String>, declared: List<String>) {
        require(declared.size == declared.toSet().size &&
            actual.size == declared.size && actual.toSet() == declared.toSet()) {
            "Compiler classpath contains missing, duplicate or undeclared JVM JARs"
        }
    }
}

/**
 * Publish only validated DEX outputs, restoring the previous good directory if
 * publication fails. Keep a failed-rollback backup for manual recovery rather
 * than deleting it during the caller's staging cleanup.
 */
internal object RiftJvmDexPublication {
    fun publish(
        prepared: File,
        output: File,
        move: (File, File) -> Boolean = { from, to -> from.renameTo(to) }
    ) {
        require(prepared.isDirectory) {
            "Prepared DEX directory is missing"
        }
        require(prepared.canonicalPath != output.canonicalPath) {
            "DEX staging and output paths must differ"
        }
        val parent = output.parentFile ?: error("DEX output has no parent")
        require(parent.isDirectory) { "DEX output parent is missing" }
        val backup = File(parent, ".${output.name}-previous-${UUID.randomUUID()}")
        require(!backup.exists()) { "DEX backup collision" }
        val hadPrevious = output.exists()
        if (hadPrevious) {
            require(output.isDirectory && move(output, backup)) {
                "Could not preserve previous DEX output"
            }
        }
        try {
            RiftDeadline.check("JVM DEX publication")
            require(move(prepared, output)) { "Could not publish verified DEX output" }
        } catch (failed: Throwable) {
            if (hadPrevious && !move(backup, output)) {
                failed.addSuppressed(IllegalStateException(
                    "Previous DEX preserved at backup: ${backup.absolutePath}"
                ))
            }
            throw failed
        }
        // Only a successful replacement retires the previous output.
        if (hadPrevious) {
            require(backup.deleteRecursively()) { "Could not retire previous DEX backup" }
        }
    }
}

/** Bounded class-only normalization; multi-release desktop classes never enter Android DEX. */
internal object RiftJvmJarSupport {
    fun safePath(name: String): Boolean = name.length in 1..512 &&
        !name.contains('\\') && !name.contains(':') &&
        name.split('/').all { it.isNotEmpty() && it != "." && it != ".." &&
            Regex("^[A-Za-z0-9_.$+@-]+$").matches(it) }

    private fun resourcePath(name: String): Boolean = safePath(name) &&
        name != "AndroidManifest.xml" && name != "resources.arsc" &&
        !Regex("^classes[0-9]*\\.dex$").matches(name) &&
        listOf("assets/", "lib/", "res/").none { name.startsWith(it) }

    fun prepare(input: File, output: File, classNames: MutableSet<String>,
        resources: MutableMap<String, ByteArray>?) {
        ZipFile(input).use { zip ->
            JarOutputStream(output.outputStream().buffered()).use { out ->
                val seen = HashSet<String>()
                var entries = 0
                var expanded = 0L
                val iterator = zip.entries()
                while (iterator.hasMoreElements()) {
                    RiftDeadline.check("JVM JAR normalization")
                    val entry = iterator.nextElement()
                    require(++entries <= 50000) { "JVM JAR entry count exceeded" }
                    val name = entry.name
                    require(safePath(name.trimEnd('/')) && seen.add(name)) { "Unsafe or duplicate JAR entry" }
                    if (entry.isDirectory || name.startsWith("META-INF/versions/") ||
                        name == "module-info.class") continue
                    if (name.endsWith(".class")) {
                        require(!name.startsWith("META-INF/") && classNames.add(name)) {
                            "Duplicate or invalid JVM program class: $name"
                        }
                        require(classNames.size <= 50000) { "JVM class count exceeded" }
                        out.putNextEntry(JarEntry(name))
                        zip.getInputStream(entry).use { source ->
                            val buffer = ByteArray(65536)
                            var countBytes = 0L
                            while (true) {
                                RiftDeadline.check("JVM class normalization")
                                val count = source.read(buffer)
                                if (count < 0) break
                                countBytes += count; expanded += count
                                require(countBytes <= 8L * 1024L * 1024L &&
                                    expanded <= 96L * 1024L * 1024L) { "JVM JAR expanded class bytes exceeded" }
                                out.write(buffer, 0, count)
                            }
                        }
                        out.closeEntry()
                    } else if (resources != null) {
                        // Build metadata, signatures and shrinker rules are not runtime resources.
                        if (name == "META-INF/MANIFEST.MF" || name == "META-INF/INDEX.LIST" ||
                            name.endsWith(".kotlin_module") || name.startsWith("META-INF/proguard/") ||
                            name.startsWith("META-INF/com.android.tools/") ||
                            Regex("^META-INF/[^/]+\\.(SF|RSA|DSA|EC)$", RegexOption.IGNORE_CASE).matches(name))
                            continue
                        require(resourcePath(name)) { "JAR resource conflicts with Android packaging: $name" }
                        val bytes = zip.getInputStream(entry).use { source ->
                            val outBytes = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                RiftDeadline.check("JVM resource normalization")
                                val count = source.read(buffer)
                                if (count < 0) break
                                require(outBytes.size() + count <= 1024 * 1024) { "Java resource exceeds 1 MiB" }
                                outBytes.write(buffer, 0, count)
                            }
                            outBytes.toByteArray()
                        }
                        val prior = resources[name]
                        resources[name] = if (name.startsWith("META-INF/services/")) {
                            val providers = ((prior?.toString(Charsets.UTF_8) ?: "") + "\n" +
                                bytes.toString(Charsets.UTF_8)).lineSequence()
                                .map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toSortedSet()
                            require(providers.all { Regex("^[A-Za-z_$][A-Za-z0-9_$.]*$").matches(it) }) {
                                "Invalid Java service provider"
                            }
                            (providers.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)
                        } else {
                            require(prior == null || prior.contentEquals(bytes)) { "Conflicting Java resource: $name" }
                            bytes
                        }
                        require(resources.size <= 96 && resources.values.sumOf { it.size.toLong() } <=
                            8L * 1024L * 1024L) { "Java resources exceed bound" }
                    }
                }
            }
        }
    }
}
