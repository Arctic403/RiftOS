package com.riftbuild.tools.kotlinc

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

object KotlinCompilerTool {
    private const val REQUEST_SCHEMA = "riftbuild-compiler-json/1"
    private const val RESPONSE_SCHEMA = "riftbuild-compiler-response/1"
    private const val MAX_SOURCES = 64
    private const val MAX_MESSAGES = 64 * 1024

    @JvmStatic
    fun run(requestJson: String): String {
        return runCatching {
            val request = JSONObject(requestJson)
            require(request.optString("schema") == REQUEST_SCHEMA) {
                "Unsupported compiler request schema"
            }

            val project = File(request.getString("projectRoot")).canonicalFile
            require(project.isDirectory) { "Project root is unavailable" }

            val sources = request.getJSONArray("sources")
            require(sources.length() in 1..MAX_SOURCES) {
                "Kotlin source count is out of bounds"
            }

            val sourceFiles = ArrayList<File>(sources.length())
            for (index in 0 until sources.length()) {
                val relative = sources.getString(index).trim()
                require(
                    relative.endsWith(".kt") &&
                        !relative.startsWith("/") &&
                        !relative.contains("\\")
                ) {
                    "Kotlin source path is invalid"
                }
                val file = File(project, relative).canonicalFile
                require(confinedTo(project, file) && file.isFile) {
                    "Kotlin source escaped project or is missing"
                }
                sourceFiles += file
            }

            val outputRelative = request.getString("outputDir").trim()
            require(
                outputRelative.startsWith("build/riftbuild/") &&
                    !outputRelative.contains("\\")
            ) {
                "Kotlin compiler outputDir must stay under build/riftbuild"
            }
            val outputDir = File(project, outputRelative).canonicalFile
            require(confinedTo(project, outputDir)) {
                "Kotlin compiler outputDir escaped project"
            }
            require(outputDir.mkdirs() || outputDir.isDirectory) {
                "Could not create Kotlin compiler outputDir"
            }

            val classpath = request.optJSONArray("classpath") ?: JSONArray()
            val classpathFiles = ArrayList<File>(classpath.length())
            for (index in 0 until classpath.length()) {
                val file = File(classpath.getString(index)).canonicalFile
                require(file.isFile) { "Kotlin compiler classpath entry is missing" }
                classpathFiles += file
            }

            val options = request.optJSONObject("options") ?: JSONObject()
            val args = ArrayList<String>()
            if (options.optBoolean("noJdk", true)) args += "-no-jdk"
            if (options.optBoolean("noStdlib", true)) args += "-no-stdlib"
            if (options.optBoolean("noReflect", true)) args += "-no-reflect"

            args += "-jvm-target"
            args += options.optString("jvmTarget", "1.8")

            args += "-module-name"
            args += options.optString("moduleName", "rift-kotlin")

            if (classpathFiles.isNotEmpty()) {
                args += "-classpath"
                args += classpathFiles.joinToString(File.pathSeparator) { it.absolutePath }
            }

            args += "-d"
            args += outputDir.absolutePath
            sourceFiles.forEach { args += it.absolutePath }

            val diagnostics = ByteArrayOutputStream()
            val exit = PrintStream(diagnostics, true, Charsets.UTF_8.name()).use { stream ->
                K2JVMCompiler().exec(stream, *args.toTypedArray())
            }
            val messages = diagnostics.toString(Charsets.UTF_8.name()).take(MAX_MESSAGES)

            JSONObject()
                .put("schema", RESPONSE_SCHEMA)
                .put("state", if (exit == ExitCode.OK) "success" else "compiler-reject")
                .put("exitCode", exit.toString())
                .put("sourceCount", sourceFiles.size)
                .put("messages", messages)
                .toString()
        }.getOrElse { error ->
            JSONObject()
                .put("schema", RESPONSE_SCHEMA)
                .put("state", "tool-error")
                .put("errorClass", error.javaClass.name)
                .put("messages", error.message ?: error.javaClass.simpleName)
                .toString()
        }
    }

    private fun confinedTo(root: File, child: File): Boolean {
        val rootPath = root.canonicalFile.toPath()
        val childPath = child.canonicalFile.toPath()
        return childPath == rootPath || childPath.startsWith(rootPath)
    }
}
