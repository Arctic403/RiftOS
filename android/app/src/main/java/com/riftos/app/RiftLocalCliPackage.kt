package com.riftos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Replaceable RiftCLI intelligence package loader.
 *
 * The package is data/code under /workspace/.riftcli, not an APK authority surface.
 * It executes only inside the existing bounded headless QuickJS runtime and may
 * request native driver work, but it cannot grant itself authority.
 */
internal class RiftLocalCliPackage(
    context: Context,
    private val runtime: RiftHeadlessJsRuntime
) {
    companion object {
        private const val PACKAGE_RELATIVE = "workspace/.riftcli"
        private const val PACKAGE_DISPLAY = "/workspace/.riftcli"
        private const val MANIFEST_SCHEMA = "rift.cli-local-package/1"
        private const val REQUEST_SCHEMA = "rift.cli-local-request/1"
        private const val RESPONSE_SCHEMA = "rift.cli-local-response/1"
        private const val EXECUTION_SCHEMA = "rift.cli-local-execution/1"
        private const val API_VERSION = 1
        private const val RESULT_PREFIX = "RIFTCLI_RESULT:"
        private const val MAX_MANIFEST_BYTES = 64L * 1024L
        private const val MAX_ENTRY_BYTES = 512L * 1024L
        private const val MAX_RESPONSE_BYTES = 256 * 1024
        private const val MAX_NATIVE_ARGS = 128
        private const val MAX_NATIVE_ARG_BYTES = 128 * 1024
        private const val MAX_NATIVE_TOTAL_BYTES = 256 * 1024
    }

    private data class Manifest(
        val name: String,
        val version: String,
        val entryRelative: String,
        val entryFile: File
    )

    private val appContext = context.applicationContext
    private val riftRoot = File(appContext.filesDir, "riftfs").apply { mkdirs() }.canonicalFile
    private val packageRoot = File(riftRoot, PACKAGE_RELATIVE).canonicalFile

    fun status(): JSONObject {
        val base = JSONObject()
            .put("schema", "rift.cli-local-package-status/1")
            .put("path", PACKAGE_DISPLAY)
            .put("apiVersion", API_VERSION)
            .put("replaceable", true)
            .put("authority", "none-direct")
            .put("runtime", "bounded-headless-quickjs")
            .put("riftFsRead", true)
            .put("riftFsWrite", false)
            .put("processAuthority", false)
            .put("networkAuthority", false)
            .put("androidAuthority", false)
            .put("gitAuthority", false)
            .put("shellAuthority", false)
            .put("toolHostAuthority", false)

        if (!packageRoot.exists()) {
            return base
                .put("present", false)
                .put("valid", false)
                .put("reason", "package-missing")
        }

        return runCatching {
            val manifest = loadManifest()
            base
                .put("present", true)
                .put("valid", true)
                .put("name", manifest.name)
                .put("version", manifest.version)
                .put("entry", manifest.entryRelative)
        }.getOrElse { failure ->
            base
                .put("present", true)
                .put("valid", false)
                .put("reason", "package-invalid")
                .put("detail", failure.message?.take(512) ?: failure.javaClass.simpleName)
        }
    }

    fun execute(cwd: String, argv: List<String>): JSONObject {
        val manifest = loadManifest()
        val runtimeDir = packageFile(".runtime").apply { mkdirs() }
        require(runtimeDir.isDirectory) { "RiftCLI local package runtime directory is unavailable" }

        val request = JSONObject()
            .put("schema", REQUEST_SCHEMA)
            .put("apiVersion", API_VERSION)
            .put("cwd", cwd)
            .put("argv", JSONArray(argv))
            .put("packageRoot", PACKAGE_DISPLAY)
            .put("authority", JSONObject()
                .put("direct", false)
                .put("nativeDriverRequestOnly", true)
                .put("riftFsRead", true)
                .put("riftFsWrite", false)
                .put("process", false)
                .put("network", false)
                .put("android", false)
                .put("git", false)
                .put("shell", false)
                .put("toolHost", false))

        val invocationId = UUID.randomUUID().toString()
        val invocationName = "invoke-$invocationId.js"
        val entrySnapshotName = "entry-$invocationId.js"
        val invocation = packageFile(".runtime/$invocationName")
        val entrySnapshot = packageFile(".runtime/$entrySnapshotName")
        val bootstrap = buildString {
            append("globalThis.RIFTCLI_REQUEST = JSON.parse(")
            append(JSONObject.quote(request.toString()))
            append(");\n")
            append("globalThis.riftcliRespond = function(value) {\n")
            append("  print(")
            append(JSONObject.quote(RESULT_PREFIX))
            append(" + JSON.stringify(value));\n")
            append("};\n")
        }

        invocation.writeText(bootstrap, Charsets.UTF_8)
        entrySnapshot.writeText(manifest.entryFile.readText(Charsets.UTF_8), Charsets.UTF_8)
        try {
            val quickJs = runtime.executeQuickJs(
                listOf("run", ".runtime/$invocationName", ".runtime/$entrySnapshotName"),
                PACKAGE_DISPLAY
            )
            val lines = quickJs.output.lineSequence().toList()
            val responseLines = lines.filter { it.startsWith(RESULT_PREFIX) }
            require(responseLines.size == 1) {
                "RiftCLI local package must emit exactly one $RESULT_PREFIX response"
            }

            val responseText = responseLines.single().removePrefix(RESULT_PREFIX)
            require(responseText.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_BYTES) {
                "RiftCLI local package response exceeds $MAX_RESPONSE_BYTES UTF-8 bytes"
            }
            val response = JSONObject(responseText)
            require(response.optString("schema") == RESPONSE_SCHEMA) {
                "RiftCLI local package response schema must be $RESPONSE_SCHEMA"
            }

            val output = response.optString("output")
            val resultValue = response.opt("result")
            require(
                resultValue == null ||
                    resultValue == JSONObject.NULL ||
                    resultValue is JSONObject
            ) { "RiftCLI local package result must be an object or null" }

            val nativeArgv = parseNativeArgv(response.optJSONArray("nativeArgv"))
            if (nativeArgv != null) {
                require(response.optBoolean("ok", true)) {
                    "RiftCLI local package cannot request native work from a failed response"
                }
            }

            val diagnostics = lines
                .filterNot { it.startsWith(RESULT_PREFIX) }
                .joinToString("\n")
                .takeIf { it.isNotBlank() }

            return JSONObject()
                .put("schema", EXECUTION_SCHEMA)
                .put("ok", response.optBoolean("ok", true))
                .put("output", output)
                .put("result", if (resultValue is JSONObject) resultValue else JSONObject.NULL)
                .put("nativeArgv", nativeArgv?.let { JSONArray(it) } ?: JSONObject.NULL)
                .put("diagnostics", diagnostics ?: JSONObject.NULL)
                .put("package", JSONObject()
                    .put("path", PACKAGE_DISPLAY)
                    .put("name", manifest.name)
                    .put("version", manifest.version)
                    .put("entry", manifest.entryRelative)
                    .put("apiVersion", API_VERSION)
                    .put("replaceable", true)
                    .put("authority", "none-direct"))
        } finally {
            runCatching { invocation.delete() }
            runCatching { entrySnapshot.delete() }
        }
    }

    private fun loadManifest(): Manifest {
        require(packageRoot.isDirectory) {
            "RiftCLI local package is missing at $PACKAGE_DISPLAY"
        }
        val manifestFile = packageFile("manifest.json")
        require(manifestFile.isFile) {
            "RiftCLI local package manifest is missing"
        }
        require(manifestFile.length() <= MAX_MANIFEST_BYTES) {
            "RiftCLI local package manifest exceeds $MAX_MANIFEST_BYTES bytes"
        }

        val json = JSONObject(manifestFile.readText(Charsets.UTF_8))
        require(json.optString("schema") == MANIFEST_SCHEMA) {
            "RiftCLI local package manifest schema must be $MANIFEST_SCHEMA"
        }
        require(json.optInt("apiVersion", -1) == API_VERSION) {
            "RiftCLI local package apiVersion must be $API_VERSION"
        }

        val name = json.optString("name").trim()
        val version = json.optString("version").trim()
        val entryRelative = json.optString("entry").trim().replace('\\', '/')
        require(name.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,95}$"))) {
            "Invalid RiftCLI local package name"
        }
        require(version.isNotBlank() && version.length <= 96) {
            "Invalid RiftCLI local package version"
        }
        require(entryRelative.endsWith(".js", ignoreCase = true)) {
            "RiftCLI local package entry must be a classic .js file"
        }

        val entryFile = packageFile(entryRelative)
        require(entryFile.isFile) {
            "RiftCLI local package entry is missing: $entryRelative"
        }
        require(entryFile.length() <= MAX_ENTRY_BYTES) {
            "RiftCLI local package entry exceeds $MAX_ENTRY_BYTES bytes"
        }

        return Manifest(name, version, entryRelative, entryFile)
    }

    private fun parseNativeArgv(raw: JSONArray?): List<String>? {
        if (raw == null) return null
        require(raw.length() in 1..MAX_NATIVE_ARGS) {
            "RiftCLI local nativeArgv must contain 1..$MAX_NATIVE_ARGS arguments"
        }

        var total = 0
        val values = ArrayList<String>(raw.length())
        for (index in 0 until raw.length()) {
            val value = raw.opt(index)
            require(value is String) {
                "RiftCLI local nativeArgv[$index] must be a string"
            }
            val bytes = value.toByteArray(Charsets.UTF_8).size
            require(bytes <= MAX_NATIVE_ARG_BYTES) {
                "RiftCLI local nativeArgv[$index] exceeds $MAX_NATIVE_ARG_BYTES bytes"
            }
            total += bytes
            require(total <= MAX_NATIVE_TOTAL_BYTES) {
                "RiftCLI local nativeArgv exceeds $MAX_NATIVE_TOTAL_BYTES bytes"
            }
            values += value
        }

        require(values.first().trim().lowercase() == "driver") {
            "RiftCLI local package may request only the native driver protocol"
        }
        return values
    }

    private fun packageFile(relative: String): File {
        val normalized = relative.trim().replace('\\', '/')
        require(normalized.isNotBlank()) { "RiftCLI local package path is blank" }
        require(!normalized.startsWith("/") && !Regex("^[A-Za-z]:").containsMatchIn(normalized)) {
            "RiftCLI local package paths must be relative"
        }
        val parts = normalized.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." || it.contains('\u0000') }) {
            "Invalid RiftCLI local package path"
        }

        val file = File(packageRoot, parts.joinToString(File.separator)).canonicalFile
        require(file.path.startsWith(packageRoot.path + File.separator)) {
            "RiftCLI local package path escaped package root"
        }
        return file
    }
}
