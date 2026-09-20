package com.riftos.app

import android.content.Context
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.evaluate
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Headless trusted JavaScript service runtime.
 *
 * This is deliberately not a browser surface. It hosts the trusted Rift++ Core/RiftVM modules,
 * the Semnexis V0 bootstrap compiler, and a bounded read-only developer qjs surface inside QuickJS.
 * Each command family receives only its explicitly installed capabilities; generic qjs gets confined
 * RiftFS text reads and captured output, not file writes, process, network, Git, or Android authority.
 * No DOM, network, Android intents, arbitrary native calls, or ambient shell globals are exposed.
 */
class RiftHeadlessJsRuntime(context: Context) {
    companion object {
        private const val MAX_TEXT_BYTES = 8L * 1024L * 1024L
        private const val MAX_SEMNEXIS_SOURCE_BYTES = 512L * 1024L
        private const val MAX_STATE_BYTES = 64 * 1024
        private const val MAX_STATE_KEY_BYTES = 4 * 1024
        private const val MAX_STATE_FILES = 256
        private const val EVALUATION_TIMEOUT_MS = 120_000L
        private const val QJS_EVALUATION_TIMEOUT_MS = 30_000L
        private const val MAX_QJS_SOURCE_BYTES = 256 * 1024
        private const val MAX_QJS_TOTAL_BYTES = 8 * 1024 * 1024L
        private const val MAX_QJS_FILES = 64
        private const val MAX_QJS_OUTPUT_BYTES = 256 * 1024
    }

    data class CommandResult(val output: String, val result: JSONObject?)

    private val appContext = context.applicationContext
    private val riftRoot = File(appContext.filesDir, "riftfs").apply { mkdirs() }.canonicalFile
    private val stateRoot = File(riftRoot, "system/riftpp-state").apply { mkdirs() }.canonicalFile

    @Volatile private var vmSourceCache: String? = null
    @Volatile private var coreSourceCache: String? = null
    @Volatile private var semnexisSourceCache: String? = null


    private fun canonicalUtf8Bytes(value: String): ByteArray {
        val out = ByteArrayOutputStream(value.length.coerceAtLeast(16))
        var index = 0
        while (index < value.length) {
            val first = value[index].code
            val codePoint = when {
                first in 0xD800..0xDBFF -> {
                    val second = value.getOrNull(index + 1)?.code
                    if (second != null && second in 0xDC00..0xDFFF) {
                        index += 1
                        0x10000 + ((first - 0xD800) shl 10) + (second - 0xDC00)
                    } else {
                        0xFFFD
                    }
                }
                first in 0xDC00..0xDFFF -> 0xFFFD
                else -> first
            }
            when {
                codePoint <= 0x7F -> out.write(codePoint)
                codePoint <= 0x7FF -> {
                    out.write(0xC0 or (codePoint shr 6))
                    out.write(0x80 or (codePoint and 0x3F))
                }
                codePoint <= 0xFFFF -> {
                    out.write(0xE0 or (codePoint shr 12))
                    out.write(0x80 or ((codePoint shr 6) and 0x3F))
                    out.write(0x80 or (codePoint and 0x3F))
                }
                else -> {
                    out.write(0xF0 or (codePoint shr 18))
                    out.write(0x80 or ((codePoint shr 12) and 0x3F))
                    out.write(0x80 or ((codePoint shr 6) and 0x3F))
                    out.write(0x80 or (codePoint and 0x3F))
                }
            }
            index += 1
        }
        return out.toByteArray()
    }

    fun executeRiftpp(args: List<String>, cwd: String): CommandResult {
        val request = JSONObject()
            .put("args", org.json.JSONArray(args))
            .put("cwd", cwd)
        var resultJson: String? = null

        runBlocking {
            quickJs {
                evaluationTimeoutMillis = EVALUATION_TIMEOUT_MS

                function("__rift_request") { request.toString() }
                function("__rift_result") { values ->
                    resultJson = values.firstOrNull()?.toString()
                    Unit
                }
                function("__rift_utf8") { values ->
                    values.firstOrNull()?.toString().orEmpty().let { canonicalUtf8Bytes(it) }
                }
                function("__rift_sha256") { values ->
                    val value = values.firstOrNull()
                    val bytes = when (value) {
                        is ByteArray -> value
                        is List<*> -> ByteArray(value.size) { index -> (value[index] as Number).toByte() }
                        else -> throw IllegalArgumentException("SHA-256 input must be a byte array")
                    }
                    MessageDigest.getInstance("SHA-256").digest(bytes)
                }
                function("__rift_read_text") { values ->
                    val path = values.firstOrNull()?.toString().orEmpty()
                    val file = resolveFile(path, cwd)
                    require(file.isFile) { "file not found: $path" }
                    require(file.length() <= MAX_TEXT_BYTES) { "file exceeds headless runtime text limit: $path" }
                    file.readText(Charsets.UTF_8)
                }
                function("__rift_write_text") { values ->
                    val path = values.getOrNull(0)?.toString().orEmpty()
                    val text = values.getOrNull(1)?.toString().orEmpty()
                    val bytes = text.let { canonicalUtf8Bytes(it) }
                    require(bytes.size <= MAX_TEXT_BYTES) { "output exceeds headless runtime text limit" }
                    val file = resolveFile(path, cwd)
                    file.parentFile?.mkdirs()
                    atomicWrite(file, bytes)
                    true
                }
                function("__rift_state_load") { values ->
                    stateLoad(values.getOrNull(0)?.toString().orEmpty(), values.getOrNull(1)?.toString().orEmpty())
                }
                function("__rift_state_save") { values ->
                    stateSave(
                        values.getOrNull(0)?.toString().orEmpty(),
                        values.getOrNull(1)?.toString().orEmpty(),
                        values.getOrNull(2)?.toString().orEmpty()
                    )
                }
                function("__rift_state_remove") { values ->
                    stateRemove(values.getOrNull(0)?.toString().orEmpty(), values.getOrNull(1)?.toString().orEmpty())
                }

                evaluate<Any?>(Scripts.POLYFILLS, filename = "rift-headless-polyfills.js")
                evaluate<Any?>(preparedVmSource(), filename = "riftvm.headless.js")
                evaluate<Any?>(preparedCoreSource(), filename = "riftpp-core.headless.js")
                evaluate<Any?>(
                    Scripts.RIFTPP_COMMAND_ENTRY,
                    filename = "riftpp-command.headless.js"
                )
            }
        }

        val payload = resultJson?.let(::JSONObject)
            ?: throw IllegalStateException("Headless Rift++ runtime returned no result")
        return CommandResult(
            output = payload.optString("output"),
            result = payload.optJSONObject("result")
        )
    }


    fun executeSemnexis(args: List<String>, cwd: String): CommandResult {
        val request = JSONObject()
            .put("args", org.json.JSONArray(args))
            .put("cwd", cwd)
        var resultJson: String? = null

        runBlocking {
            quickJs {
                evaluationTimeoutMillis = EVALUATION_TIMEOUT_MS

                function("__rift_request") { request.toString() }
                function("__rift_result") { values ->
                    resultJson = values.firstOrNull()?.toString()
                    Unit
                }
                function("__rift_read_text") { values ->
                    val path = values.firstOrNull()?.toString().orEmpty()
                    val file = resolveFile(path, cwd)
                    require(file.isFile) { "file not found: $path" }
                    require(file.length() <= MAX_SEMNEXIS_SOURCE_BYTES) {
                        "Semnexis source exceeds compiler file budget: $path"
                    }
                    file.readText(Charsets.UTF_8)
                }
                function("__rift_write_semnexis_binary") { values ->
                    val requested = values.getOrNull(0)?.toString().orEmpty()
                    val allowedPaths = setOf(
                        "/documents/builds/Semnexis/semx-arm32-proof.elf",
                        "/documents/builds/Semnexis/semx-arm32-runtime.elf"
                    )
                    require(requested in allowedPaths) { "Semnexis binary output path is fixed" }
                    val bytes = when (val raw = values.getOrNull(1)) {
                        is ByteArray -> {
                            require(raw.isNotEmpty() && raw.size <= 1024 * 1024) {
                                "Semnexis binary payload exceeds fixed limit"
                            }
                            raw
                        }
                        is List<*> -> {
                            require(raw.isNotEmpty() && raw.size <= 1024 * 1024) {
                                "Semnexis binary payload exceeds fixed limit"
                            }
                            ByteArray(raw.size) { index ->
                                val number = raw[index] as? Number
                                    ?: throw IllegalArgumentException("Semnexis binary payload must contain bytes")
                                val numeric = number.toDouble()
                                require(numeric.isFinite() && numeric % 1.0 == 0.0 && numeric in 0.0..255.0) {
                                    "Semnexis binary payload byte is out of range"
                                }
                                numeric.toInt().toByte()
                            }
                        }
                        else -> throw IllegalArgumentException("Semnexis binary payload must be a byte array")
                    }
                    val target = resolveFile(requested, "/")
                    target.parentFile?.mkdirs()
                    atomicWrite(target, bytes)
                    true
                }

                evaluate<Any?>(Scripts.POLYFILLS, filename = "semnexis-polyfills.js")
                evaluate<Any?>(preparedSemnexisSource(), filename = "semnexis-bootstrap.headless.js")
                evaluate<Any?>(
                    Scripts.SEMNEXIS_COMMAND_ENTRY,
                    filename = "semnexis-command.headless.js"
                )
            }
        }

        val payload = resultJson?.let(::JSONObject)
            ?: throw IllegalStateException("Headless Semnexis runtime returned no result")
        return CommandResult(
            output = payload.optString("output"),
            result = payload.optJSONObject("result")
        )
    }

    fun executeQuickJs(args: List<String>, cwd: String): CommandResult {
        val subcommand = args.firstOrNull()?.trim()?.lowercase().orEmpty().ifBlank { "help" }
        if (subcommand == "help") {
            val value = JSONObject()
                .put("schema", "rift-qjs-shell/1")
                .put("backend", "headless-quickjs")
                .put("commands", org.json.JSONArray(listOf("qjs help", "qjs version", "qjs eval <javascript>", "qjs run <script.js> [script.js ...]")))
                .put("riftFsRead", true)
                .put("riftFsWrite", false)
                .put("processAuthority", false)
                .put("networkAuthority", false)
                .put("androidAuthority", false)
            return CommandResult(
                output = "Rift bounded QuickJS\nqjs help\nqjs version\nqjs eval <javascript>\nqjs run <script.js> [script.js ...]\nHost API: print(...), console.log(...), rift.readText(path), rift.cwd",
                result = value
            )
        }
        if (subcommand == "version") {
            require(args.size == 1) { "usage: qjs version" }
            val value = JSONObject()
                .put("schema", "rift-qjs-shell-version/1")
                .put("backend", "headless-quickjs")
                .put("binding", "quickjs-kt")
                .put("bindingVersion", "1.0.14")
                .put("evaluationTimeoutMs", QJS_EVALUATION_TIMEOUT_MS)
                .put("riftFsRead", true)
                .put("riftFsWrite", false)
                .put("processAuthority", false)
                .put("networkAuthority", false)
                .put("androidAuthority", false)
            return CommandResult(value.toString(2), value)
        }

        val sources = mutableListOf<Pair<String, String>>()
        val mode: String
        when (subcommand) {
            "eval", "-e", "--eval" -> {
                require(args.size >= 2) { "usage: qjs eval <javascript>" }
                val source = args.drop(1).joinToString(" ")
                val size = canonicalUtf8Bytes(source).size
                require(size <= MAX_QJS_SOURCE_BYTES) { "QuickJS eval source exceeds $MAX_QJS_SOURCE_BYTES UTF-8 bytes" }
                sources += "<qjs-eval>" to source
                mode = "eval"
            }
            "run" -> {
                require(args.size >= 2) { "usage: qjs run <script.js> [script.js ...]" }
                val requested = args.drop(1)
                require(requested.size <= MAX_QJS_FILES) { "qjs run accepts at most $MAX_QJS_FILES scripts" }
                var totalBytes = 0L
                for (rawPath in requested) {
                    require(rawPath.endsWith(".js", ignoreCase = true)) {
                        "bounded qjs run accepts classic .js scripts only: $rawPath"
                    }
                    val file = resolveFile(rawPath, cwd)
                    require(file.isFile) { "QuickJS script not found: $rawPath" }
                    require(file.length() <= MAX_TEXT_BYTES) { "QuickJS script exceeds per-file text limit: $rawPath" }
                    totalBytes += file.length()
                    require(totalBytes <= MAX_QJS_TOTAL_BYTES) { "QuickJS script set exceeds $MAX_QJS_TOTAL_BYTES bytes" }
                    sources += rawPath to file.readText(Charsets.UTF_8)
                }
                mode = "run"
            }
            else -> throw IllegalArgumentException("unsupported qjs command: $subcommand")
        }

        val output = mutableListOf<String>()
        var outputBytes = 0
        var lastValue: Any? = null
        fun emit(values: Array<out Any?>) {
            val line = values.joinToString(" ") { it?.toString() ?: "null" }
            val bytes = canonicalUtf8Bytes(line).size + 1
            require(outputBytes + bytes <= MAX_QJS_OUTPUT_BYTES) {
                "QuickJS output exceeds $MAX_QJS_OUTPUT_BYTES UTF-8 bytes"
            }
            outputBytes += bytes
            output += line
        }

        runBlocking {
            quickJs {
                evaluationTimeoutMillis = QJS_EVALUATION_TIMEOUT_MS

                function("__rift_qjs_print") { values ->
                    emit(values)
                    Unit
                }
                function("__rift_qjs_read_text") { values ->
                    val path = values.firstOrNull()?.toString().orEmpty()
                    val file = resolveFile(path, cwd)
                    require(file.isFile) { "file not found: $path" }
                    require(file.length() <= MAX_TEXT_BYTES) {
                        "file exceeds headless runtime text limit: $path"
                    }
                    file.readText(Charsets.UTF_8)
                }

                evaluate<Any?>(Scripts.QJS_PRELUDE.replace("__RIFT_QJS_CWD__", JSONObject.quote(cwd)), filename = "rift-qjs-prelude.js")
                for ((filename, source) in sources) {
                    lastValue = evaluate<Any?>(source, filename = filename)
                }
            }
        }

        if (mode == "eval" && lastValue is String) emit(arrayOf(lastValue))
        else if (mode == "eval" && lastValue is Number) emit(arrayOf(lastValue))
        else if (mode == "eval" && lastValue is Boolean) emit(arrayOf(lastValue))

        val value = JSONObject()
            .put("schema", "rift-qjs-shell-result/1")
            .put("backend", "headless-quickjs")
            .put("mode", mode)
            .put("scripts", sources.size)
            .put("outputBytes", outputBytes)
            .put("riftFsRead", true)
            .put("riftFsWrite", false)
            .put("processAuthority", false)
            .put("networkAuthority", false)
            .put("androidAuthority", false)
        return CommandResult(output.joinToString("\n"), value)
    }

    fun executeDeveloperTool(args: List<String>): CommandResult {
        val subcommand = args.firstOrNull()?.trim()?.lowercase().orEmpty()
        return when (subcommand) {
            "", "help" -> {
                val value = JSONObject()
                    .put("schema", "rift-developer-tool/1")
                    .put("commands", org.json.JSONArray(listOf("rift-tool gate0-verify", "rift-tool semantic-compat", "rift-tool text-model-benchmark")))
                    .put("genericJavaScript", false)
                    .put("processAuthority", false)
                    .put("networkAuthority", false)
                CommandResult(
                    output = "Rift developer tools\nrift-tool gate0-verify\nrift-tool semantic-compat\nrift-tool text-model-benchmark",
                    result = value
                )
            }
            "gate0-verify" -> executeGate0Verifier()
            "semantic-compat" -> executeSemanticCompatibilityVerifier()
            "text-model-benchmark" -> executeTextModelBenchmark()
            else -> throw IllegalArgumentException("unsupported fixed Rift developer tool: $subcommand")
        }
    }

    private fun executeGate0Verifier(): CommandResult {
        val bundle = gate0Bundle()
        val semanticVerifierSource = readGate0File("/workspace/rift++/tools/semantic-verifier-core.js")
        val referenceVerifierSource = readGate0File("/workspace/rift++/tools/reference-integrity-verifier-core.js")
        var resultJson: String? = null

        runBlocking {
            quickJs {
                evaluationTimeoutMillis = EVALUATION_TIMEOUT_MS

                function("__rift_gate0_bundle") { bundle.toString() }
                function("__rift_gate0_result") { values ->
                    resultJson = values.firstOrNull()?.toString()
                    Unit
                }
                function("__rift_utf8") { values ->
                    values.firstOrNull()?.toString().orEmpty().let { canonicalUtf8Bytes(it) }
                }
                function("__rift_sha256") { values ->
                    val value = values.firstOrNull()
                    val bytes = when (value) {
                        is ByteArray -> value
                        is List<*> -> ByteArray(value.size) { index -> (value[index] as Number).toByte() }
                        else -> throw IllegalArgumentException("SHA-256 input must be a byte array")
                    }
                    MessageDigest.getInstance("SHA-256").digest(bytes)
                }

                evaluate<Any?>(Scripts.POLYFILLS, filename = "rift-tool-polyfills.js")
                evaluate<Any?>(preparedVmSource(), filename = "riftvm.gate0.js")
                evaluate<Any?>(preparedCoreSource(), filename = "riftpp-core.gate0.js")
                evaluate<Any?>(semanticVerifierSource, filename = "semantic-verifier-core.js")
                evaluate<Any?>(referenceVerifierSource, filename = "reference-integrity-verifier-core.js")
                evaluate<Any?>(Scripts.GATE0_VERIFY_ENTRY, filename = "gate0-verify.js")
            }
        }

        val payload = resultJson?.let(::JSONObject)
            ?: throw IllegalStateException("Gate 0 verifier returned no result")
        require(payload.optString("status") == "PASS") { "Gate 0 verifier suite did not pass" }
        return CommandResult(payload.toString(2), payload)
    }

    private fun executeSemanticCompatibilityVerifier(): CommandResult {
        val bundle = gate0Bundle()
        val semanticVerifierSource = readGate0File("/workspace/rift++/tools/semantic-verifier-core.js")
        var resultJson: String? = null

        runBlocking {
            quickJs {
                evaluationTimeoutMillis = EVALUATION_TIMEOUT_MS

                function("__rift_gate0_bundle") { bundle.toString() }
                function("__rift_gate0_result") { values ->
                    resultJson = values.firstOrNull()?.toString()
                    Unit
                }
                function("__rift_utf8") { values ->
                    values.firstOrNull()?.toString().orEmpty().let { canonicalUtf8Bytes(it) }
                }
                function("__rift_sha256") { values ->
                    val value = values.firstOrNull()
                    val bytes = when (value) {
                        is ByteArray -> value
                        is List<*> -> ByteArray(value.size) { index -> (value[index] as Number).toByte() }
                        else -> throw IllegalArgumentException("SHA-256 input must be a byte array")
                    }
                    MessageDigest.getInstance("SHA-256").digest(bytes)
                }

                evaluate<Any?>(Scripts.POLYFILLS, filename = "rift-tool-polyfills.js")
                evaluate<Any?>(preparedVmSource(), filename = "riftvm.semantic-compat.js")
                evaluate<Any?>(preparedCoreSource(), filename = "riftpp-core.semantic-compat.js")
                evaluate<Any?>(semanticVerifierSource, filename = "semantic-verifier-core.js")
                evaluate<Any?>(Scripts.SEMANTIC_COMPAT_ENTRY, filename = "semantic-compat.js")
            }
        }

        val payload = resultJson?.let(::JSONObject)
            ?: throw IllegalStateException("Semantic compatibility verifier returned no result")
        require(payload.optString("status") == "PASS") { "Semantic compatibility verifier did not pass" }
        return CommandResult(payload.toString(2), payload)
    }


    private fun executeTextModelBenchmark(): CommandResult {
        var resultJson: String? = null

        runBlocking {
            quickJs {
                evaluationTimeoutMillis = EVALUATION_TIMEOUT_MS

                function("__rift_text_benchmark_result") { values ->
                    resultJson = values.firstOrNull()?.toString()
                    Unit
                }
                function("__rift_utf8") { values ->
                    values.firstOrNull()?.toString().orEmpty().let { canonicalUtf8Bytes(it) }
                }
                function("__rift_sha256") { values ->
                    val value = values.firstOrNull()
                    val bytes = when (value) {
                        is ByteArray -> value
                        is List<*> -> ByteArray(value.size) { index -> (value[index] as Number).toByte() }
                        else -> throw IllegalArgumentException("SHA-256 input must be a byte array")
                    }
                    MessageDigest.getInstance("SHA-256").digest(bytes)
                }

                evaluate<Any?>(Scripts.POLYFILLS, filename = "rift-tool-polyfills.js")
                evaluate<Any?>(Scripts.TEXT_MODEL_BENCHMARK_ENTRY, filename = "text-model-benchmark.js")
            }
        }

        val payload = resultJson?.let(::JSONObject)
            ?: throw IllegalStateException("Text-model benchmark returned no result")
        require(payload.optString("status") == "MEASURED") { "Text-model benchmark did not complete" }
        return CommandResult(payload.toString(2), payload)
    }

    private fun gate0Bundle(): JSONObject {
        val manifest = JSONObject(readGate0File("/workspace/rift++/tests/FIXTURE-MANIFEST.json"))
        val expectations = JSONObject(readGate0File("/workspace/rift++/tests/compat/EXPECTATIONS.json"))
        val reference = JSONObject(readGate0File("/workspace/rift++/tests/REFERENCE-INTEGRITY.json"))
        val bounds = JSONObject(readGate0File("/workspace/rift++/tests/BOUND-CLASSIFICATION.json"))
        require(manifest.optString("schema") == "riftpp-gate0-fixture-manifest/2") {
            "Gate 0 fixture manifest schema is not supported"
        }
        val rows = manifest.optJSONArray("files")
            ?: throw IllegalArgumentException("Gate 0 fixture manifest has no files array")
        require(rows.length() in 1..256) { "Gate 0 fixture manifest exceeds fixed file-count bounds" }

        val files = JSONObject()
        val hashes = JSONObject()
        fun addFile(logicalPath: String, physicalPath: String) {
            val text = readGate0File(physicalPath)
            val bytes = text.let { canonicalUtf8Bytes(it) }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            files.put(logicalPath, text)
            hashes.put(logicalPath, digest)
        }

        val fixturePath = Regex("^(compat|reference-bootstrap)/[A-Za-z0-9._+/-]+\\.riftpp$")
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index)
                ?: throw IllegalArgumentException("Gate 0 fixture manifest row $index is invalid")
            val relative = row.optString("path").trim()
            require(fixturePath.matches(relative) && relative.split('/').none { it == ".." }) {
                "Gate 0 fixture path is outside the allowlist: $relative"
            }
            addFile(relative, "/workspace/rift++/tests/$relative")
            val text = files.getString(relative)
            val bytes = text.let { canonicalUtf8Bytes(it) }
            require(row.optInt("bytes", -1) == bytes.size) {
                "Gate 0 fixture byte-length drift: $relative"
            }
            require(row.optString("sha256") == hashes.getString(relative)) {
                "Gate 0 fixture hash drift: $relative"
            }
        }

        listOf(
            "workspace/RiftLLM+/src/riftllm_plus/riftbrain.riftpp" to
                "/workspace/RiftLLM+/src/riftllm_plus/riftbrain.riftpp",
            "workspace/RiftLLM+/src/riftllm_plus/native_repair.riftpp" to
                "/workspace/RiftLLM+/src/riftllm_plus/native_repair.riftpp",
            "workspace/RiftOS-main/src/riftpp-core.js" to
                "/workspace/RiftOS-main/src/riftpp-core.js",
            "workspace/RiftOS-main/src/riftvm.js" to
                "/workspace/RiftOS-main/src/riftvm.js",
            "workspace/RiftOS-main/android/app/src/main/java/com/riftos/app/RiftHeadlessJsRuntime.kt" to
                "/workspace/RiftOS-main/android/app/src/main/java/com/riftos/app/RiftHeadlessJsRuntime.kt",
            "workspace/RiftLLM+/tests/gate6d3-persistence-cap96.riftpp" to
                "/workspace/RiftLLM+/tests/gate6d3-persistence-cap96.riftpp",
            "workspace/RiftLLM+/tests/gate6d3-persistence-cap144.riftpp" to
                "/workspace/RiftLLM+/tests/gate6d3-persistence-cap144.riftpp",
            "workspace/RiftLLM+/experiments/E09-0024-gate6d3-stateful-substrate-candidate.md" to
                "/workspace/RiftLLM+/experiments/E09-0024-gate6d3-stateful-substrate-candidate.md",
            "workspace/RiftLLM+/tests/gate6d3-stateful-substrate-candidate.json" to
                "/workspace/RiftLLM+/tests/gate6d3-stateful-substrate-candidate.json"
        ).forEach { (logical, physical) -> addFile(logical, physical) }

        val llmDirectory = resolveFile("/workspace/RiftLLM+/src/riftllm_plus", "/")
        require(llmDirectory.isDirectory) { "RiftLLM+ source directory is unavailable" }
        val llmMembers = llmDirectory.listFiles()
            ?.map { entry -> if (entry.isDirectory) entry.name + "/" else entry.name }
            ?.sorted()
            ?: throw IllegalStateException("Could not enumerate RiftLLM+ source directory")
        require(llmMembers.size <= 64) { "RiftLLM+ source membership exceeds verifier bounds" }

        val directories = JSONObject()
            .put("workspace/RiftLLM+/src/riftllm_plus", org.json.JSONArray(llmMembers))
        val bundle = JSONObject()
            .put("expectations", expectations)
            .put("manifest", manifest)
            .put("reference", reference)
            .put("bounds", bounds)
            .put("files", files)
            .put("pathHashes", hashes)
            .put("directories", directories)
            .put("installedSourceSha", BuildConfig.RIFT_SOURCE_SHA)
        require(bundle.toString().let { canonicalUtf8Bytes(it) }.size <= MAX_TEXT_BYTES) {
            "Gate 0 verifier bundle exceeds headless runtime text limit"
        }
        return bundle
    }

    private fun readGate0File(path: String): String {
        val exact = setOf(
            "/workspace/rift++/tools/semantic-verifier-core.js",
            "/workspace/rift++/tools/reference-integrity-verifier-core.js",
            "/workspace/rift++/tests/FIXTURE-MANIFEST.json",
            "/workspace/rift++/tests/compat/EXPECTATIONS.json",
            "/workspace/rift++/tests/REFERENCE-INTEGRITY.json",
            "/workspace/rift++/tests/BOUND-CLASSIFICATION.json",
            "/workspace/RiftLLM+/src/riftllm_plus/riftbrain.riftpp",
            "/workspace/RiftLLM+/src/riftllm_plus/native_repair.riftpp",
            "/workspace/RiftOS-main/src/riftpp-core.js",
            "/workspace/RiftOS-main/src/riftvm.js",
            "/workspace/RiftOS-main/android/app/src/main/java/com/riftos/app/RiftHeadlessJsRuntime.kt",
            "/workspace/RiftLLM+/tests/gate6d3-persistence-cap96.riftpp",
            "/workspace/RiftLLM+/tests/gate6d3-persistence-cap144.riftpp",
            "/workspace/RiftLLM+/experiments/E09-0024-gate6d3-stateful-substrate-candidate.md",
            "/workspace/RiftLLM+/tests/gate6d3-stateful-substrate-candidate.json"
        )
        val allowed = path in exact ||
            path.startsWith("/workspace/rift++/tests/compat/") ||
            path.startsWith("/workspace/rift++/tests/reference-bootstrap/")
        require(allowed && !path.contains("/../") && !path.contains("\\")) {
            "Gate 0 verifier path is outside the fixed allowlist"
        }
        val file = resolveFile(path, "/")
        require(file.isFile) { "Gate 0 verifier file not found: $path" }
        require(file.length() <= MAX_TEXT_BYTES) { "Gate 0 verifier file exceeds text limit: $path" }
        return file.readText(Charsets.UTF_8)
    }
    private fun preparedVmSource(): String {
        vmSourceCache?.let { return it }
        val source = readAsset("www/src/riftvm.js")
        val stripped = source.replace(Regex("(?m)^export\\s+"), "")
        return ("(function(){\n" + stripped + "\n" +
            "globalThis.RiftVMHeadless=Object.freeze({" +
            "RIFT_EXEC_FORMAT:RIFT_EXEC_FORMAT,RIFT_VM_ABI:RIFT_VM_ABI," +
            "prepareRiftExecutable:prepareRiftExecutable,executeRiftExecutable:executeRiftExecutable," +
            "inspectRiftExecutable:inspectRiftExecutable});\n})();").also { vmSourceCache = it }
    }

    private fun preparedCoreSource(): String {
        coreSourceCache?.let { return it }
        var source = readAsset("www/src/riftpp-core.js")
        source = source.replace(
            "import { RIFT_EXEC_FORMAT, RIFT_VM_ABI, prepareRiftExecutable } from './riftvm.js';",
            "const {RIFT_EXEC_FORMAT,RIFT_VM_ABI,prepareRiftExecutable}=globalThis.RiftVMHeadless;"
        )
        source = source.replace(Regex("(?m)^export\\s+"), "")
        return ("(function(){\n" + source + "\n})();").also { coreSourceCache = it }
    }


    private fun preparedSemnexisSource(): String {
        semnexisSourceCache?.let { return it }
        var source = readAsset("www/src/semnexis-bootstrap.js")
        source = source.replace(Regex("(?m)^export\\s+"), "")
        return ("(function(){\n" + source + "\n})();").also { semnexisSourceCache = it }
    }

    private fun readAsset(path: String): String =
        appContext.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun resolveFile(rawPath: String, cwd: String): File {
        var raw = rawPath.trim().replace('\\', '/')
        require(raw.isNotBlank()) { "RiftFS path is required" }
        if (!raw.startsWith("/") && !Regex("^[A-Za-z]:($|/)").containsMatchIn(raw)) {
            raw = cwd.trimEnd('/') + "/" + raw
        }
        val display = RiftVolumePaths.normalizeDisplay(raw)
        val relative = if (display.startsWith("/C:", true) || display.startsWith("/D:", true)) {
            RiftVolumePaths.resolveRelative(display)
        } else {
            display.trimStart('/')
        }
        val file = if (relative.isBlank()) riftRoot else File(riftRoot, relative).canonicalFile
        require(file == riftRoot || file.path.startsWith(riftRoot.path + File.separator)) { "Path escaped RiftFS" }
        return file
    }

    private fun stateNamespace(raw: String): String {
        val value = raw.trim()
        require(Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,95}$").matches(value)) { "Invalid Rift++ state namespace" }
        return value
    }

    private fun stateFile(namespace: String, key: String): File {
        val cleanNamespace = stateNamespace(namespace)
        val keyBytes = key.let { canonicalUtf8Bytes(it) }
        require(keyBytes.isNotEmpty() && keyBytes.size <= MAX_STATE_KEY_BYTES) { "Invalid Rift++ state key" }
        val directory = File(stateRoot, cleanNamespace).canonicalFile
        require(directory == stateRoot || directory.path.startsWith(stateRoot.path + File.separator)) { "State namespace escaped RiftFS" }
        directory.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256").digest(keyBytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val file = File(directory, "$digest.state").canonicalFile
        require(file.parentFile == directory) { "State key escaped namespace" }
        return file
    }

    private fun stateLoad(namespace: String, key: String): String? {
        val file = stateFile(namespace, key)
        if (!file.exists()) return null
        require(file.isFile && file.length() <= MAX_STATE_BYTES.toLong()) { "Invalid Rift++ state record" }
        return file.readText(Charsets.UTF_8)
    }

    private fun stateSave(namespace: String, key: String, value: String): Boolean {
        val bytes = value.let { canonicalUtf8Bytes(it) }
        require(bytes.size <= MAX_STATE_BYTES) { "Rift++ state record exceeds $MAX_STATE_BYTES UTF-8 bytes" }
        val file = stateFile(namespace, key)
        if (!file.exists()) {
            val count = file.parentFile?.listFiles()?.count { it.isFile } ?: 0
            require(count < MAX_STATE_FILES) { "Rift++ state namespace exceeds $MAX_STATE_FILES records" }
        }
        atomicWrite(file, bytes)
        return true
    }

    private fun stateRemove(namespace: String, key: String): Boolean {
        val file = stateFile(namespace, key)
        if (!file.exists()) return false
        require(file.isFile) { "Invalid Rift++ state record" }
        return file.delete()
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.headless-${System.nanoTime()}")
        temp.writeBytes(bytes)
        val backup = File(target.parentFile, ".${target.name}.backup-${System.nanoTime()}")
        var backedUp = false
        try {
            if (target.exists()) {
                require(target.isFile) { "Headless output target is not a file" }
                require(target.renameTo(backup)) { "Could not stage existing output for atomic replacement" }
                backedUp = true
            }
            require(temp.renameTo(target)) { "Atomic output publish failed" }
            if (backedUp) backup.delete()
        } catch (error: Throwable) {
            temp.delete()
            if (backedUp && !target.exists()) backup.renameTo(target)
            throw error
        }
    }

    private object Scripts {
        const val POLYFILLS = """
            globalThis.TextEncoder = class {
              encode(value) {
                const raw = __rift_utf8(String(value));
                const out = new Uint8Array(raw.length);
                for (let i = 0; i < raw.length; i++) out[i] = raw[i] & 255;
                return out;
              }
            };
            globalThis.crypto = Object.freeze({
              subtle: Object.freeze({
                digest: async function(name, data) {
                  if (String(name).toUpperCase() !== 'SHA-256') throw new Error('Only SHA-256 is available');
                  const view = data instanceof ArrayBuffer ? new Uint8Array(data) : data;
                  const input = Array.from(view, value => value & 255);
                  const raw = __rift_sha256(input);
                  const out = new Uint8Array(raw.length);
                  for (let i = 0; i < raw.length; i++) out[i] = raw[i] & 255;
                  return out.buffer;
                }
              })
            });
        """


        const val TEXT_MODEL_BENCHMARK_ENTRY = """
            (function() {
              const snippet = 'fn classify_value(input: string) { let total: u32 = 0 while total < 64 { total += 1 } let label: string = "Rift++ 😀 λ" return total } // comment 😀 λ\n';
              let text = '';
              while (text.length < 131072) text += snippet;
              const lexIterations = 16;
              const randomIterations = 8;
              const indexBuildIterations = 4;

              const isAlpha = c => (c >= 65 && c <= 90) || (c >= 97 && c <= 122) || c === 95;
              const isDigit = c => c >= 48 && c <= 57;
              const isAlphaNum = c => isAlpha(c) || isDigit(c);

              const lexUtf16 = value => {
                let tokens = 0, lines = 1, i = 0;
                while (i < value.length) {
                  const c = value.charCodeAt(i);
                  if (c === 10) { lines++; i++; continue; }
                  if (c === 32 || c === 9 || c === 13) { i++; continue; }
                  if (isAlpha(c)) {
                    i++;
                    while (i < value.length && isAlphaNum(value.charCodeAt(i))) i++;
                    tokens++;
                    continue;
                  }
                  if (isDigit(c)) {
                    i++;
                    while (i < value.length && isDigit(value.charCodeAt(i))) i++;
                    tokens++;
                    continue;
                  }
                  if (c === 47 && i + 1 < value.length && value.charCodeAt(i + 1) === 47) {
                    i += 2;
                    while (i < value.length && value.charCodeAt(i) !== 10) i++;
                    continue;
                  }
                  if (c === 34) {
                    i++;
                    while (i < value.length) {
                      const q = value.charCodeAt(i);
                      if (q === 92 && i + 1 < value.length) { i += 2; continue; }
                      i++;
                      if (q === 34) break;
                    }
                    tokens++;
                    continue;
                  }
                  i++;
                  tokens++;
                }
                return ((tokens * 2654435761) ^ lines) >>> 0;
              };

              const lexUtf8 = bytes => {
                let tokens = 0, lines = 1, i = 0;
                while (i < bytes.length) {
                  const c = bytes[i];
                  if (c === 10) { lines++; i++; continue; }
                  if (c === 32 || c === 9 || c === 13) { i++; continue; }
                  if (isAlpha(c)) {
                    i++;
                    while (i < bytes.length && isAlphaNum(bytes[i])) i++;
                    tokens++;
                    continue;
                  }
                  if (isDigit(c)) {
                    i++;
                    while (i < bytes.length && isDigit(bytes[i])) i++;
                    tokens++;
                    continue;
                  }
                  if (c === 47 && i + 1 < bytes.length && bytes[i + 1] === 47) {
                    i += 2;
                    while (i < bytes.length && bytes[i] !== 10) i++;
                    continue;
                  }
                  if (c === 34) {
                    i++;
                    while (i < bytes.length) {
                      const q = bytes[i];
                      if (q === 92 && i + 1 < bytes.length) { i += 2; continue; }
                      i++;
                      if (q === 34) break;
                    }
                    tokens++;
                    continue;
                  }
                  i++;
                  tokens++;
                }
                return ((tokens * 2654435761) ^ lines) >>> 0;
              };

              const decodeCodePoint = (bytes, offset) => {
                const b0 = bytes[offset];
                if (b0 < 128) return b0;
                if (b0 < 224) return ((b0 & 31) << 6) | (bytes[offset + 1] & 63);
                if (b0 < 240) return ((b0 & 15) << 12) | ((bytes[offset + 1] & 63) << 6) | (bytes[offset + 2] & 63);
                return ((b0 & 7) << 18) | ((bytes[offset + 1] & 63) << 12) | ((bytes[offset + 2] & 63) << 6) | (bytes[offset + 3] & 63);
              };
              const codePointWidth = b0 => b0 < 128 ? 1 : b0 < 224 ? 2 : b0 < 240 ? 3 : 4;

              const buildUtf8CodeUnitIndex = bytes => {
                const offsets = new Uint32Array(text.length);
                const kinds = new Uint8Array(text.length);
                let byteOffset = 0, unitOffset = 0;
                while (byteOffset < bytes.length) {
                  const cp = decodeCodePoint(bytes, byteOffset);
                  offsets[unitOffset] = byteOffset;
                  kinds[unitOffset] = 0;
                  unitOffset++;
                  if (cp > 65535) {
                    offsets[unitOffset] = byteOffset;
                    kinds[unitOffset] = 1;
                    unitOffset++;
                  }
                  byteOffset += codePointWidth(bytes[byteOffset]);
                }
                if (unitOffset !== text.length) throw new Error('UTF-8 code-unit index length mismatch');
                return { offsets: offsets, kinds: kinds };
              };

              const utf8CodeUnitAt = (bytes, index, unitOffset) => {
                const byteOffset = index.offsets[unitOffset];
                const cp = decodeCodePoint(bytes, byteOffset);
                if (cp <= 65535) return cp;
                const value = cp - 65536;
                return index.kinds[unitOffset] === 0 ? 55296 + (value >>> 10) : 56320 + (value & 1023);
              };

              const timed = fn => {
                const start = Date.now();
                const value = fn();
                return { ms: Math.max(0, Date.now() - start), value: value };
              };
              const ratio = (numerator, denominator) => denominator > 0 ? numerator / denominator : null;

              const bytes = new TextEncoder().encode(text);
              const utf8Index = buildUtf8CodeUnitIndex(bytes);
              const signature16 = lexUtf16(text);
              const signature8 = lexUtf8(bytes);
              if (signature16 !== signature8) throw new Error('UTF-16/UTF-8 lexer benchmark signatures diverged');

              const probes = new Uint32Array(16384);
              let state = 305419896;
              for (let i = 0; i < probes.length; i++) {
                state = (Math.imul(state, 1664525) + 1013904223) >>> 0;
                probes[i] = state % text.length;
              }

              for (let i = 0; i < 2; i++) { lexUtf16(text); lexUtf8(bytes); }

              const utf16Lex = timed(() => {
                let acc = 0;
                for (let n = 0; n < lexIterations; n++) acc = (acc + lexUtf16(text) + n) >>> 0;
                return acc;
              });
              const utf8Lex = timed(() => {
                let acc = 0;
                for (let n = 0; n < lexIterations; n++) acc = (acc + lexUtf8(bytes) + n) >>> 0;
                return acc;
              });
              const utf8LexEndToEnd = timed(() => {
                let acc = 0;
                for (let n = 0; n < lexIterations; n++) {
                  const prepared = new TextEncoder().encode(text);
                  acc = (acc + lexUtf8(prepared) + n) >>> 0;
                }
                return acc;
              });

              const utf16Random = timed(() => {
                let acc = 0;
                for (let n = 0; n < randomIterations; n++) {
                  for (let i = 0; i < probes.length; i++) acc = (acc + text.charCodeAt(probes[i])) >>> 0;
                }
                return acc;
              });
              const utf8IndexedRandom = timed(() => {
                let acc = 0;
                for (let n = 0; n < randomIterations; n++) {
                  for (let i = 0; i < probes.length; i++) acc = (acc + utf8CodeUnitAt(bytes, utf8Index, probes[i])) >>> 0;
                }
                return acc;
              });
              const utf8IndexBuild = timed(() => {
                let length = 0;
                for (let n = 0; n < indexBuildIterations; n++) length += buildUtf8CodeUnitIndex(bytes).offsets.length;
                return length;
              });

              __rift_text_benchmark_result(JSON.stringify({
                schema: 'riftpp-text-model-benchmark-v2',
                status: 'MEASURED',
                representationUnderTest: {
                  semanticUnit: 'utf16-code-unit',
                  hotCandidate: 'utf16-string',
                  alternateCandidate: 'utf8-bytes-plus-code-unit-index',
                  interchangeBoundary: 'utf8'
                },
                corpus: {
                  codeUnits: text.length,
                  utf8Bytes: bytes.length,
                  lexIterations: lexIterations,
                  randomAccessProbes: probes.length,
                  randomIterations: randomIterations,
                  indexBuildIterations: indexBuildIterations
                },
                lexerLike: {
                  utf16Ms: utf16Lex.ms,
                  utf8PreparedMs: utf8Lex.ms,
                  utf8PrepareAndScanMs: utf8LexEndToEnd.ms,
                  preparedUtf8OverUtf16: ratio(utf8Lex.ms, utf16Lex.ms),
                  endToEndUtf8OverUtf16: ratio(utf8LexEndToEnd.ms, utf16Lex.ms),
                  signature: signature16
                },
                codeUnitRandomAccess: {
                  utf16DirectMs: utf16Random.ms,
                  utf8IndexedMs: utf8IndexedRandom.ms,
                  utf8IndexedOverUtf16: ratio(utf8IndexedRandom.ms, utf16Random.ms),
                  utf8IndexBuildMs: utf8IndexBuild.ms,
                  utf8IndexBytes: utf8Index.offsets.byteLength + utf8Index.kinds.byteLength
                },
                checksums: {
                  utf16Lex: utf16Lex.value,
                  utf8Lex: utf8Lex.value,
                  utf8LexEndToEnd: utf8LexEndToEnd.value,
                  utf16Random: utf16Random.value,
                  utf8IndexedRandom: utf8IndexedRandom.value,
                  utf8IndexBuild: utf8IndexBuild.value
                },
                interpretation: 'Ratios above 1 mean the UTF-16 representation completed that comparable operation faster. Treat sequential traversal, indexed code-unit access, preparation cost, and memory overhead as separate measurements.'
              }));
            })();
        """

        const val SEMANTIC_COMPAT_ENTRY = """
            (async function() {
              const bundle = JSON.parse(__rift_gate0_bundle());
              const compiler = globalThis.RiftPlusPlusCore;
              const vm = globalThis.RiftVMHeadless;
              const semanticVerifier = globalThis.RiftSemanticVerifier;
              if (!compiler || !vm || !semanticVerifier) {
                throw new Error('Semantic compatibility verifier runtime is incomplete');
              }

              const compilerApi = Object.freeze({
                RIFTPP_CORE_VERSION: compiler.version,
                RIFTPP_LANGUAGE: compiler.language,
                compileRiftPlusPlusCoreV1: compiler.compile,
                compileRiftPlusPlusCoreProgramV1: compiler.compileProgram
              });
              const files = bundle.files || {};
              const hashes = bundle.pathHashes || {};
              const has = (object, key) => Object.prototype.hasOwnProperty.call(object, key);
              const loadText = async path => {
                const key = String(path || '');
                if (!has(files, key)) throw new Error('Semantic compatibility verifier denied file: ' + key);
                return String(files[key]);
              };
              const hashFixture = async path => {
                const key = String(path || '');
                if (!has(hashes, key)) throw new Error('Semantic compatibility verifier denied fixture hash: ' + key);
                return String(hashes[key]);
              };
              const semantic = await semanticVerifier.run({
                compiler: compilerApi,
                vm: vm,
                loadText: loadText,
                hashFixture: hashFixture,
                expectations: bundle.expectations,
                fixtureManifest: bundle.manifest
              });
              const result = Object.freeze({
                schema: 'riftpp-semantic-compat-device-suite/1',
                status: 'PASS',
                installedSourceSha: String(bundle.installedSourceSha || ''),
                compiler: String(compiler.version || ''),
                semantic: semantic
              });
              __rift_gate0_result(JSON.stringify(result));
            })();
        """

        const val GATE0_VERIFY_ENTRY = """
            (async function() {
              const bundle = JSON.parse(__rift_gate0_bundle());
              const compiler = globalThis.RiftPlusPlusCore;
              const vm = globalThis.RiftVMHeadless;
              const semanticVerifier = globalThis.RiftSemanticVerifier;
              const referenceVerifier = globalThis.RiftReferenceIntegrityVerifier;
              if (!compiler || !vm || !semanticVerifier || !referenceVerifier) {
                throw new Error('Gate 0 verifier runtime is incomplete');
              }

              const compilerApi = Object.freeze({
                RIFTPP_CORE_VERSION: compiler.version,
                RIFTPP_LANGUAGE: compiler.language,
                compileRiftPlusPlusCoreV1: compiler.compile,
                compileRiftPlusPlusCoreProgramV1: compiler.compileProgram
              });
              const files = bundle.files || {};
              const hashes = bundle.pathHashes || {};
              const directories = bundle.directories || {};
              const has = (object, key) => Object.prototype.hasOwnProperty.call(object, key);
              const loadText = async path => {
                const key = String(path || '');
                if (!has(files, key)) throw new Error('Gate 0 verifier denied file: ' + key);
                return String(files[key]);
              };
              const hashPath = async path => {
                const key = String(path || '');
                if (!has(hashes, key)) throw new Error('Gate 0 verifier denied path hash: ' + key);
                return String(hashes[key]);
              };
              const hashFixture = hashPath;
              const listDir = async path => {
                const key = String(path || '');
                if (!has(directories, key)) throw new Error('Gate 0 verifier denied directory: ' + key);
                return Array.from(directories[key] || []);
              };
              const hashText = async value => {
                const bytes = new TextEncoder().encode(String(value));
                const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
                return Array.from(digest).map(byte => byte.toString(16).padStart(2, '0')).join('');
              };
              const byteLength = value => new TextEncoder().encode(String(value)).byteLength;

              const semantic = await semanticVerifier.run({
                compiler: compilerApi,
                vm: vm,
                loadText: loadText,
                hashFixture: hashFixture,
                expectations: bundle.expectations,
                fixtureManifest: bundle.manifest
              });
              const reference = await referenceVerifier.run({
                compiler: compilerApi,
                vm: vm,
                loadText: loadText,
                hashText: hashText,
                hashPath: hashPath,
                listDir: listDir,
                byteLength: byteLength,
                reference: bundle.reference,
                fixtureManifest: bundle.manifest,
                bounds: bundle.bounds
              });
              const result = Object.freeze({
                schema: 'riftpp-gate0-device-verifier-suite/1',
                status: 'PASS',
                installedSourceSha: String(bundle.installedSourceSha || ''),
                semantic: semantic,
                reference: reference
              });
              __rift_gate0_result(JSON.stringify(result));
            })();
        """
        const val QJS_PRELUDE = """
            (function() {
              const emit = (...values) => __rift_qjs_print(...values);
              Object.defineProperty(globalThis, 'print', {
                value: emit, writable: false, configurable: false, enumerable: true
              });
              Object.defineProperty(globalThis, 'console', {
                value: Object.freeze({
                  log: (...values) => emit(...values),
                  info: (...values) => emit(...values),
                  warn: (...values) => emit(...values),
                  error: (...values) => emit(...values)
                }),
                writable: false, configurable: false, enumerable: true
              });
              Object.defineProperty(globalThis, 'rift', {
                value: Object.freeze({
                  cwd: __RIFT_QJS_CWD__,
                  readText: path => __rift_qjs_read_text(String(path))
                }),
                writable: false, configurable: false, enumerable: true
              });
            })();
        """

        const val SEMNEXIS_COMMAND_ENTRY = """
            (function() {
              const request = JSON.parse(__rift_request());
              const args = Array.from(request.args || []);
              const cwd = String(request.cwd || '/');
              const compiler = globalThis.SemnexisBootstrap;
              if (!compiler) throw new Error('Semnexis bootstrap compiler is unavailable');

              const output = [];
              let outputChars = 0;
              const MAX_SEMNEXIS_OUTPUT_CHARS = 256 * 1024;
              const emit = value => {
                const text = String(value == null ? '' : value);
                outputChars += text.length;
                if (outputChars > MAX_SEMNEXIS_OUTPUT_CHARS) throw new Error('Semnexis output exceeds command budget');
                output.push(text);
              };
              const finish = result => __rift_result(JSON.stringify({
                output: output.join('\n'),
                result: result || null
              }));
              const usage =
                'Semnexis bootstrap shell (headless QuickJS)\n' +
                'semx help\nsemx version\nsemx self-test\n' +
                'semx check <source.snx>\nsemx dump-graph <source.snx>\n' +
                'semx dump-plan <source.snx>\nsemx dump-ir <source.snx>\n' +
                'semx emit-arm32-proof <source.snx>\nsemx emit-arm32-runtime <source.snx>';

              const normalizePath = value => {
                const raw = String(value || '').replaceAll('\\\\','/');
                const absolute = raw.startsWith('/') || /^[A-Za-z]:($|\/)/.test(raw);
                const joined = absolute ? raw : String(cwd).replace(/\/$/,'') + '/' + raw;
                const parts = [];
                for (const part of joined.split('/')) {
                  if (!part || part === '.') continue;
                  if (part === '..') {
                    if (!parts.length) throw new Error('path escaped root');
                    parts.pop();
                    continue;
                  }
                  parts.push(part);
                }
                return '/' + parts.join('/');
              };
              const sourcePath = value => {
                if (!value) throw new Error('Semnexis source path is required');
                const path = normalizePath(value);
                if (!/\.snx$/i.test(path)) throw new Error('Semnexis source must end in .snx: ' + path);
                return path;
              };
              const read = path => __rift_read_text(sourcePath(path));
              const sub = String(args.shift() || 'help').toLowerCase();

              if (sub === 'help') {
                emit(usage);
                finish({backend:'headless-quickjs', compiler:compiler.version});
                return;
              }
              if (sub === 'version') {
                const value = {
                  language:compiler.language,
                  compiler:compiler.version,
                  graphSchema:compiler.graphSchema,
                  planSchema:compiler.planSchema,
                  irSchema:compiler.irSchema,
                  irBinaryFormat:compiler.irBinaryFormat,
                  irBinaryVersion:compiler.irBinaryVersion,
                  irBinaryLatestFormat:compiler.irBinaryLatestFormat,
                  irBinaryLatestVersion:compiler.irBinaryLatestVersion,
                  irBinaryCompatibility:compiler.irBinaryCompatibility,
                  irGraphNodeSemantics:compiler.irGraphNodeSemantics,
                  arm32ElfSchema:compiler.arm32ElfSchema,
                  arm32RuntimeElfSchema:compiler.arm32RuntimeElfSchema,
                  backend:'headless-quickjs'
                };
                emit(JSON.stringify(value, null, 2));
                finish(value);
                return;
              }
              if (sub === 'self-test' || sub === 'selftest') {
                if (args.length) throw new Error('usage: semx self-test');
                const result = compiler.compile('fn main() -> i32 {\n    return 40 + 2;\n}\n');
                if (result.graph.nodes.length !== 12 ||
                    result.graph.edges.length !== 18 ||
                    result.plan.steps.length !== 5 ||
                    result.ir.functions.length !== 1 ||
                    result.ir.functions[0].instructions.length !== 6) {
                  throw new Error('Semnexis bootstrap self-test shape mismatch');
                }
                const irBinary = compiler.encodeIR(result.ir);
                const decodedIR = compiler.decodeIR(irBinary);
                if (decodedIR.dump() !== result.irText) {
                  throw new Error('Semnexis Native IR binary round-trip mismatch');
                }
                const arm32 = compiler.emitArm32Proof(result.ir);
                if (arm32.constantResult !== 42 || arm32.byteLength !== 100 || !compiler.verifyArm32Proof(arm32)) {
                  throw new Error('Semnexis ARM32 ELF proof self-test mismatch');
                }
                const runtimeProgram = compiler.compile(
                  'fn add(a: i32, b: i32) -> i32 { return a + b; }\n' +
                  'fn main() -> i32 { return add(40, 2); }\n'
                );
                const arm32Runtime = compiler.emitArm32Runtime(runtimeProgram.ir);
                if (!compiler.verifyArm32Runtime(arm32Runtime, runtimeProgram.ir) ||
                    arm32Runtime.byteLength !== 192 ||
                    arm32Runtime.constantEvaluated !== false ||
                    arm32Runtime.runtimeLowered !== true ||
                    arm32Runtime.functions.length !== 2) {
                  throw new Error('Semnexis ARM32 runtime lowering self-test mismatch');
                }
                const readWord = (bytes, offset) => (
                  bytes[offset] |
                  (bytes[offset + 1] << 8) |
                  (bytes[offset + 2] << 16) |
                  (bytes[offset + 3] << 24)
                ) >>> 0;
                const addFn = arm32Runtime.functions.find(fn => fn.name === 'add');
                const mainFn = arm32Runtime.functions.find(fn => fn.name === 'main');
                if (!addFn || !mainFn) throw new Error('Semnexis ARM32 runtime function metadata mismatch');
                let runtimeAdds = false;
                for (let offset = addFn.fileOffset; offset < addFn.fileOffset + addFn.bytes; offset += 4) {
                  if ((readWord(arm32Runtime.bytes, offset) & 0x0FF00000) === 0x00900000) runtimeAdds = true;
                }
                let runtimeCall = false;
                for (let offset = mainFn.fileOffset; offset < mainFn.fileOffset + mainFn.bytes; offset += 4) {
                  if (((readWord(arm32Runtime.bytes, offset) & 0xFF000000) >>> 0) === 0xEB000000) runtimeCall = true;
                }
                if (!runtimeAdds || !runtimeCall || arm32Runtime.allocator !== 'linear-scan-r4-r7-v0' ||
                    arm32Runtime.functions.some(fn => fn.spillSlots !== 0)) {
                  throw new Error('Semnexis ARM32 runtime codegen/register-allocation proof is missing');
                }
                const arithmeticProgram = compiler.compile(
                  'fn arithmetic(a: i32, b: i32) -> i32 {\n' +
                  ' let sum = a + b;\n' +
                  ' let difference = a - b;\n' +
                  ' let product = sum * difference;\n' +
                  ' return product / b;\n' +
                  '}\n' +
                  'fn main() -> i32 { return arithmetic(84, 2); }\n'
                );
                const arm32Arithmetic = compiler.emitArm32Runtime(arithmeticProgram.ir);
                if (!compiler.verifyArm32Runtime(arm32Arithmetic, arithmeticProgram.ir) ||
                    arm32Arithmetic.byteLength !== 1016 ||
                    arm32Arithmetic.divisionHelperBytes !== 716 ||
                    arm32Arithmetic.checkedArithmetic.join(',') !== 'add,sub,mul,div') {
                  throw new Error('Semnexis ARM32 checked arithmetic proof mismatch');
                }
                const arithmeticFn = arm32Arithmetic.functions.find(fn => fn.name === 'arithmetic');
                if (!arithmeticFn || arithmeticFn.spillSlots !== 1 || arithmeticFn.frameBytes !== 8) {
                  throw new Error('Semnexis ARM32 arithmetic allocator proof mismatch');
                }
                let runtimeSmull = false;
                let runtimeDivCall = false;
                for (let offset = arithmeticFn.fileOffset; offset < arithmeticFn.fileOffset + arithmeticFn.bytes; offset += 4) {
                  const word = readWord(arm32Arithmetic.bytes, offset);
                  if (word === 0xE0C32190) runtimeSmull = true;
                  if (((word & 0xFF000000) >>> 0) === 0xEB000000) runtimeDivCall = true;
                }
                if (!runtimeSmull || !runtimeDivCall) {
                  throw new Error('Semnexis ARM32 multiply/divide instructions are missing');
                }
                const conditionalProgram = compiler.compile(
                  'fn choose(a: i32, b: i32) -> i32 {\n' +
                  ' return if a < b { a + 1 } else { b + 2 };\n' +
                  '}\n' +
                  'fn main() -> i32 { return choose(3, 5); }\n'
                );
                const conditionalBinary = compiler.encodeIR(conditionalProgram.ir);
                const decodedConditional = compiler.decodeIR(conditionalBinary);
                const arm32Conditional = compiler.emitArm32Runtime(conditionalProgram.ir);
                const chooseFn = arm32Conditional.functions.find(fn => fn.name === 'choose');
                if (conditionalBinary.length !== 674 ||
                    decodedConditional.dump() !== conditionalProgram.irText ||
                    !conditionalProgram.irText.includes('phi.i32') ||
                    !conditionalProgram.irText.includes('br.cmp.lt') ||
                    !compiler.verifyArm32Runtime(arm32Conditional, conditionalProgram.ir) ||
                    arm32Conditional.byteLength !== 340 ||
                    arm32Conditional.controlFlowLowered !== true ||
                    arm32Conditional.allocator !== 'mixed-v0' ||
                    !chooseFn || chooseFn.allocator !== 'cfg-spill-v0' || chooseFn.blockCount !== 4) {
                  throw new Error('Semnexis conditional CFG proof mismatch');
                }
                const loopProgram = compiler.compile(
                  'fn sum(n: i32) -> i32 {\n' +
                  ' return loop (i = 0, acc = 0) while i < n { next (i + 1, acc + i); } yield acc;\n' +
                  '}\n' +
                  'fn main() -> i32 { return sum(5); }\n'
                );
                const loopBinary = compiler.encodeIR(loopProgram.ir);
                const decodedLoop = compiler.decodeIR(loopBinary);
                const arm32Loop = compiler.emitArm32Runtime(loopProgram.ir);
                const sumFn = arm32Loop.functions.find(fn => fn.name === 'sum');
                if (loopBinary.length !== 756 ||
                    decodedLoop.dump() !== loopProgram.irText ||
                    !loopProgram.irText.includes('loop0.body:%v8') ||
                    !loopProgram.irText.includes('loop0.body:%v11') ||
                    !compiler.verifyArm32Runtime(arm32Loop, loopProgram.ir) ||
                    arm32Loop.byteLength !== 368 ||
                    arm32Loop.controlFlowLowered !== true ||
                    arm32Loop.allocator !== 'mixed-v0' ||
                    !sumFn || sumFn.allocator !== 'cfg-spill-v0' || sumFn.blockCount !== 4) {
                  throw new Error('Semnexis loop CFG proof mismatch');
                }
                const loopBlocks = Object.fromEntries(sumFn.blocks.map(block => [block.label, block]));
                let loopBackedge = false;
                const bodyBlock = loopBlocks['loop0.body'];
                const exitBlock = loopBlocks['loop0.exit'];
                const headerBlock = loopBlocks['loop0.header'];
                if (!bodyBlock || !exitBlock || !headerBlock) throw new Error('Semnexis loop block metadata mismatch');
                for (let offset = bodyBlock.fileOffset; offset < exitBlock.fileOffset; offset += 4) {
                  const word = readWord(arm32Loop.bytes, offset);
                  if (((word & 0xFF000000) >>> 0) !== 0xEA000000) continue;
                  let imm24 = word & 0x00FFFFFF;
                  if (imm24 & 0x00800000) imm24 |= 0xFF000000;
                  const from = sumFn.address + (offset - sumFn.fileOffset);
                  const target = (from + 8 + (imm24 | 0) * 4) >>> 0;
                  if (target === headerBlock.address && from > headerBlock.address) loopBackedge = true;
                }
                if (!loopBackedge) throw new Error('Semnexis ARM32 loop backedge proof mismatch');
                const forgedEffect = compiler.compile('fn main() -> i32 with time { return clock(); }\n').ir;
                forgedEffect.functions[0].effect = 'pure';
                forgedEffect.functions[0].requiresCapabilities = [];
                forgedEffect.functions[0].grantsCapabilities = [];
                let derivedEffectsRejectForgery = false;
                try { forgedEffect.verify(); } catch (_) { derivedEffectsRejectForgery = true; }
                if (!derivedEffectsRejectForgery) throw new Error('Semnexis derived-effect verifier accepted forged metadata');

                const tamperedRuntime = compiler.emitArm32Runtime(runtimeProgram.ir);
                tamperedRuntime.bytes[84] = tamperedRuntime.bytes[84] ^ 1;
                let canonicalMachineRejectsTamper = false;
                try { compiler.verifyArm32Runtime(tamperedRuntime, runtimeProgram.ir); } catch (_) { canonicalMachineRejectsTamper = true; }
                if (!canonicalMachineRejectsTamper) throw new Error('Semnexis canonical machine verifier accepted tampered code');

                let nestedExpr = '1';
                for (let i = 0; i < 300; i += 1) nestedExpr = 'if 0 < 1 { ' + nestedExpr + ' } else { 2 }';
                let expressionBudgetRejects = false;
                try { compiler.compile('fn main() -> i32 { return ' + nestedExpr + '; }\n'); } catch (_) { expressionBudgetRejects = true; }
                if (!expressionBudgetRejects) throw new Error('Semnexis expression budget did not reject pathological nesting');
                const sliceProgram = compiler.compile(
                  'fn first(source: Slice<u8>) -> u8 { return slice_get(source, 0); }\n' +
                  'fn main() -> i32 { return 0; }\n'
                );
                const sliceBinary = compiler.encodeIR(sliceProgram.ir);
                const sliceDecoded = compiler.decodeIR(sliceBinary);
                const sliceRuntime = compiler.emitArm32Runtime(sliceProgram.ir);
                const sliceMagic = String.fromCharCode(
                  sliceBinary[0], sliceBinary[1], sliceBinary[2],
                  sliceBinary[3], sliceBinary[4], sliceBinary[5]
                );
                let sliceV1Rejects = false;
                try { compiler.encodeIRV1(sliceProgram.ir); } catch (_) { sliceV1Rejects = true; }
                if (sliceMagic !== 'SNIRV2' ||
                    sliceBinary.length !== 273 ||
                    sliceDecoded.dump() !== sliceProgram.irText ||
                    !sliceV1Rejects ||
                    !compiler.verifyArm32Runtime(sliceRuntime, sliceProgram.ir) ||
                    sliceRuntime.byteLength !== 232) {
                  throw new Error('Semnexis Slice<u8>/SNIRV2 proof mismatch');
                }
                const sliceFn = sliceRuntime.functions.find(fn => fn.name === 'first');
                if (!sliceFn || sliceFn.bytes !== 100 || sliceFn.spillSlots !== 0) {
                  throw new Error('Semnexis Slice<u8> ARM32 function proof mismatch');
                }
                const recordProgram = compiler.compile(
                  'struct Token { kind: i32, start: i32, end: i32 }\n' +
                  'fn first_token(source: Slice<u8>) -> Token { return Token { kind: 1, start: 0, end: slice_len(source) }; }\n' +
                  'fn main() -> i32 { return 0; }\n'
                );
                const recordBinary = compiler.encodeIR(recordProgram.ir);
                const recordDecoded = compiler.decodeIR(recordBinary);
                const recordRuntime = compiler.emitArm32Runtime(recordProgram.ir);
                const recordMagic = String.fromCharCode(
                  recordBinary[0], recordBinary[1], recordBinary[2],
                  recordBinary[3], recordBinary[4], recordBinary[5]
                );
                let recordV2Rejects = false;
                try { compiler.encodeIRV2(recordProgram.ir); } catch (_) { recordV2Rejects = true; }
                if (recordMagic !== 'SNIRV3' ||
                    recordBinary.length !== 358 ||
                    recordDecoded.dump() !== recordProgram.irText ||
                    !recordV2Rejects ||
                    !compiler.verifyArm32Runtime(recordRuntime, recordProgram.ir) ||
                    recordRuntime.byteLength !== 248) {
                  throw new Error('Semnexis flat-record/SNIRV3 proof mismatch');
                }
                const recordFn = recordRuntime.functions.find(fn => fn.name === 'first_token');
                if (!recordFn ||
                    recordFn.bytes !== 116 ||
                    recordFn.frameBytes !== 16 ||
                    recordFn.slotCount !== 3 ||
                    recordFn.spillSlots !== 0) {
                  throw new Error('Semnexis flat-record ARM32 function proof mismatch');
                }
                const projectionProgram = compiler.compile(
                  'struct Token { kind: i32, start: i32, end: i32 }\n' +
                  'fn first_token(source: Slice<u8>) -> Token { return Token { kind: 1, start: 0, end: slice_len(source) }; }\n' +
                  'fn token_kind(source: Slice<u8>) -> i32 { let token = first_token(source); return token.kind; }\n' +
                  'fn token_end(source: Slice<u8>) -> i32 { let token = first_token(source); return token.end; }\n' +
                  'fn main() -> i32 { return 0; }\n'
                );
                const projectionBinary = compiler.encodeIR(projectionProgram.ir);
                const projectionDecoded = compiler.decodeIR(projectionBinary);
                const projectionRuntime = compiler.emitArm32Runtime(projectionProgram.ir);
                const projectionMagic = String.fromCharCode(
                  projectionBinary[0], projectionBinary[1], projectionBinary[2],
                  projectionBinary[3], projectionBinary[4], projectionBinary[5]
                );
                let projectionV3Rejects = false;
                try { compiler.encodeIRV3(projectionProgram.ir); } catch (_) { projectionV3Rejects = true; }
                if (projectionMagic !== 'SNIRV4' ||
                    projectionBinary.length !== 802 ||
                    projectionDecoded.dump() !== projectionProgram.irText ||
                    !projectionV3Rejects ||
                    !compiler.verifyArm32Runtime(projectionRuntime, projectionProgram.ir) ||
                    projectionRuntime.byteLength !== 440) {
                  throw new Error('Semnexis record-projection/SNIRV4 proof mismatch');
                }
                const projectionKindFn = projectionRuntime.functions.find(fn => fn.name === 'token_kind');
                const projectionEndFn = projectionRuntime.functions.find(fn => fn.name === 'token_end');
                for (const projectionFn of [projectionKindFn, projectionEndFn]) {
                  if (!projectionFn ||
                      projectionFn.bytes !== 96 ||
                      projectionFn.frameBytes !== 24 ||
                      projectionFn.slotCount !== 6 ||
                      projectionFn.spillSlots !== 0) {
                    throw new Error('Semnexis record-projection ARM32 function proof mismatch');
                  }
                }
                const recordConditionalProgram = compiler.compile(
                  'struct Token { kind: i32, start: i32, end: i32 }\n' +
                  'fn lex_first(source: Slice<u8>) -> Token {\n' +
                  ' let c = slice_get(source, 0);\n' +
                  ' return if c >= 48 { if c <= 57 { Token { kind: 2, start: 0, end: 1 } } else { Token { kind: 1, start: 0, end: 1 } } } else { Token { kind: 1, start: 0, end: 1 } };\n' +
                  '}\n' +
                  'fn main() -> i32 { return 0; }\n'
                );
                const recordConditionalBinary = compiler.encodeIR(recordConditionalProgram.ir);
                const recordConditionalDecoded = compiler.decodeIR(recordConditionalBinary);
                const recordConditionalRuntime = compiler.emitArm32Runtime(recordConditionalProgram.ir);
                const recordConditionalMagic = String.fromCharCode(
                  recordConditionalBinary[0], recordConditionalBinary[1], recordConditionalBinary[2],
                  recordConditionalBinary[3], recordConditionalBinary[4], recordConditionalBinary[5]
                );
                let recordConditionalV4Rejects = false;
                try { compiler.encodeIRV4(recordConditionalProgram.ir); } catch (_) { recordConditionalV4Rejects = true; }
                const recordConditionalFn = recordConditionalRuntime.functions.find(fn => fn.name === 'lex_first');
                const recordPhiCount = recordConditionalProgram.ir.functions
                  .find(fn => fn.name === 'lex_first').instructions.filter(inst => inst.op === 'phi.record').length;
                if (recordConditionalMagic !== 'SNIRV5' ||
                    recordConditionalBinary.length !== 1125 ||
                    recordConditionalDecoded.dump() !== recordConditionalProgram.irText ||
                    !recordConditionalV4Rejects ||
                    recordPhiCount !== 2 ||
                    !compiler.verifyArm32Runtime(recordConditionalRuntime, recordConditionalProgram.ir) ||
                    recordConditionalRuntime.byteLength !== 644 ||
                    !recordConditionalFn ||
                    recordConditionalFn.bytes !== 512 ||
                    recordConditionalFn.frameBytes !== 128 ||
                    recordConditionalFn.slotCount !== 32 ||
                    recordConditionalFn.spillSlots !== 17 ||
                    recordConditionalFn.blockCount !== 7 ||
                    recordConditionalFn.allocator !== 'cfg-spill-v0') {
                  throw new Error('Semnexis record-conditional/SNIRV5 proof mismatch');
                }
                const recordLoopProgram = compiler.compile(
                  'struct Token { kind: i32, start: i32, end: i32 }\n' +
                  'fn lex_at(source: Slice<u8>, start: i32) -> Token {\n' +
                  ' return if start < slice_len(source) { Token { kind: 1, start: start, end: start + 1 } } else { Token { kind: 0, start: start, end: start } };\n' +
                  '}\n' +
                  'fn count_tokens(source: Slice<u8>) -> i32 {\n' +
                  ' let first = lex_at(source, 0);\n' +
                  ' return loop(token = first, count = 0) while token.kind != 0 {\n' +
                  '  next(lex_at(source, token.end), count + 1);\n' +
                  ' } yield count;\n' +
                  '}\n' +
                  'fn main() -> i32 { return 0; }\n'
                );
                const recordLoopBinary = compiler.encodeIR(recordLoopProgram.ir);
                const recordLoopDecoded = compiler.decodeIR(recordLoopBinary);
                const recordLoopRuntime = compiler.emitArm32Runtime(recordLoopProgram.ir);
                const recordLoopMagic = String.fromCharCode(
                  recordLoopBinary[0], recordLoopBinary[1], recordLoopBinary[2],
                  recordLoopBinary[3], recordLoopBinary[4], recordLoopBinary[5]
                );
                let recordLoopV4Rejects = false;
                try { compiler.encodeIRV4(recordLoopProgram.ir); } catch (_) { recordLoopV4Rejects = true; }
                const countTokensIr = recordLoopProgram.ir.functions.find(fn => fn.name === 'count_tokens');
                const recordLoopPhiRecordCount = countTokensIr.instructions.filter(inst => inst.op === 'phi.record').length;
                const recordLoopPhiI32Count = countTokensIr.instructions.filter(inst => inst.op === 'phi.i32').length;
                const recordLoopFn = recordLoopRuntime.functions.find(fn => fn.name === 'count_tokens');
                if (recordLoopMagic !== 'SNIRV5' ||
                    recordLoopBinary.length !== 1554 ||
                    recordLoopDecoded.dump() !== recordLoopProgram.irText ||
                    !recordLoopV4Rejects ||
                    recordLoopPhiRecordCount !== 1 ||
                    recordLoopPhiI32Count !== 1 ||
                    !compiler.verifyArm32Runtime(recordLoopRuntime, recordLoopProgram.ir) ||
                    recordLoopRuntime.byteLength !== 816 ||
                    !recordLoopFn ||
                    recordLoopFn.bytes !== 368 ||
                    recordLoopFn.frameBytes !== 128 ||
                    recordLoopFn.slotCount !== 31 ||
                    recordLoopFn.spillSlots !== 13 ||
                    recordLoopFn.blockCount !== 4 ||
                    recordLoopFn.allocator !== 'cfg-spill-v0') {
                  throw new Error('Semnexis record-loop-state/SNIRV5 proof mismatch');
                }
                const decimalProgram = compiler.compile(
                  'fn digit_value(c: u8) -> i32 { return c - 48; }\n' +
                  'fn parse_decimal(source: Slice<u8>, start: i32, end: i32) -> i32 {\n' +
                  ' return loop(i = start, value = 0) while i < end {\n' +
                  '  next(i + 1, value * 10 + digit_value(slice_get(source, i)));\n' +
                  ' } yield value;\n' +
                  '}\n' +
                  'fn main() -> i32 { return 0; }\n'
                );
                const decimalBinary = compiler.encodeIR(decimalProgram.ir);
                const decimalDecoded = compiler.decodeIR(decimalBinary);
                const decimalRuntime = compiler.emitArm32Runtime(decimalProgram.ir);
                const decimalMagic = String.fromCharCode(
                  decimalBinary[0], decimalBinary[1], decimalBinary[2],
                  decimalBinary[3], decimalBinary[4], decimalBinary[5]
                );
                let decimalV5Rejects = false;
                try { compiler.encodeIRV5(decimalProgram.ir); } catch (_) { decimalV5Rejects = true; }
                const decimalZextCount = decimalProgram.ir.functions.reduce(
                  (total, fn) => total + fn.instructions.filter(inst => inst.op === 'zext.u8.i32').length,
                  0
                );
                const decimalParseFn = decimalRuntime.functions.find(fn => fn.name === 'parse_decimal');
                if (decimalMagic !== 'SNIRV6' ||
                    decimalBinary.length !== 1085 ||
                    decimalDecoded.dump() !== decimalProgram.irText ||
                    !decimalV5Rejects ||
                    decimalZextCount !== 1 ||
                    !compiler.verifyArm32Runtime(decimalRuntime, decimalProgram.ir) ||
                    decimalRuntime.byteLength !== 532 ||
                    !decimalParseFn ||
                    decimalParseFn.bytes !== 360 ||
                    decimalParseFn.frameBytes !== 88 ||
                    decimalParseFn.spillSlots !== 21 ||
                    decimalParseFn.blockCount !== 4 ||
                    decimalParseFn.allocator !== 'cfg-spill-v0') {
                  throw new Error('Semnexis decimal-widening/SNIRV6 proof mismatch');
                }
                const arenaReadProgram = compiler.compile(
                  'struct Expr { kind: i32, a: i32, b: i32, value: i32 }\n' +
                  'fn read_value(arena: Arena, index: i32) -> i32 {\n' +
                  ' let node = arena_load<Expr>(arena, index);\n' +
                  ' return node.value;\n' +
                  '}\n' +
                  'fn main() -> i32 { return 0; }\n'
                );
                const arenaReadBinary = compiler.encodeIR(arenaReadProgram.ir);
                const arenaReadDecoded = compiler.decodeIR(arenaReadBinary);
                const arenaReadRuntime = compiler.emitArm32Runtime(arenaReadProgram.ir);
                const arenaReadMagic = String.fromCharCode(
                  arenaReadBinary[0], arenaReadBinary[1], arenaReadBinary[2],
                  arenaReadBinary[3], arenaReadBinary[4], arenaReadBinary[5]
                );
                let arenaReadV6Rejects = false;
                try { compiler.encodeIRV6(arenaReadProgram.ir); } catch (_) { arenaReadV6Rejects = true; }
                const arenaLoadCount = arenaReadProgram.ir.functions.reduce(
                  (total, fn) => total + fn.instructions.filter(inst => inst.op === 'arena.load.record').length,
                  0
                );
                const arenaReadFn = arenaReadRuntime.functions.find(fn => fn.name === 'read_value');
                if (arenaReadMagic !== 'SNIRV7' ||
                    arenaReadBinary.length !== 392 ||
                    arenaReadDecoded.dump() !== arenaReadProgram.irText ||
                    !arenaReadV6Rejects ||
                    arenaLoadCount !== 1 ||
                    !compiler.verifyArm32Runtime(arenaReadRuntime, arenaReadProgram.ir) ||
                    arenaReadRuntime.byteLength !== 328 ||
                    !arenaReadFn ||
                    arenaReadFn.bytes !== 196 ||
                    arenaReadFn.frameBytes !== 32 ||
                    arenaReadFn.slotCount !== 8 ||
                    arenaReadFn.spillSlots !== 0 ||
                    arenaReadFn.blockCount !== 0 ||
                    arenaReadFn.allocator !== 'linear-scan-r4-r7-v0') {
                  throw new Error('Semnexis Arena-read/SNIRV7 proof mismatch');
                }
                const parserStateStackProgram = compiler.compile(
                  'struct Expr { kind: i32, a: i32, b: i32, value: i32 }\n' +
                  'struct ParserState { pos: i32, root: i32, slot: i32 }\n' +
                  'fn step(source: Slice<u8>, arena: Arena, state: ParserState) -> i32 {\n' +
                  ' let node = arena_load<Expr>(arena, state.root);\n' +
                  ' return state.pos + state.slot + node.value + slice_len(source);\n' +
                  '}\n' +
                  'fn call_step(source: Slice<u8>, arena: Arena) -> i32 {\n' +
                  ' let state = ParserState { pos: 2, root: 0, slot: 7 };\n' +
                  ' return step(source, arena, state);\n' +
                  '}\n' +
                  'fn main() -> i32 { return 0; }\n'
                );
                const parserStateStackBinary = compiler.encodeIR(parserStateStackProgram.ir);
                const parserStateStackDecoded = compiler.decodeIR(parserStateStackBinary);
                const parserStateStackRuntime = compiler.emitArm32Runtime(parserStateStackProgram.ir);
                const parserStateStackMagic = String.fromCharCode(
                  parserStateStackBinary[0], parserStateStackBinary[1], parserStateStackBinary[2],
                  parserStateStackBinary[3], parserStateStackBinary[4], parserStateStackBinary[5]
                );
                const parserStateStepFn = parserStateStackRuntime.functions.find(fn => fn.name === 'step');
                const parserStateCallFn = parserStateStackRuntime.functions.find(fn => fn.name === 'call_step');
                if (parserStateStackMagic !== 'SNIRV7' ||
                    parserStateStackBinary.length !== 953 ||
                    parserStateStackDecoded.dump() !== parserStateStackProgram.irText ||
                    !compiler.verifyArm32Runtime(parserStateStackRuntime, parserStateStackProgram.ir) ||
                    parserStateStackRuntime.byteLength !== 676 ||
                    !parserStateStepFn ||
                    parserStateStepFn.bytes !== 368 ||
                    parserStateStepFn.frameBytes !== 80 ||
                    parserStateStepFn.slotCount !== 20 ||
                    parserStateStepFn.spillSlots !== 0 ||
                    !parserStateCallFn ||
                    parserStateCallFn.bytes !== 176 ||
                    parserStateCallFn.frameBytes !== 32 ||
                    parserStateCallFn.slotCount !== 7 ||
                    parserStateCallFn.spillSlots !== 1) {
                  throw new Error('Semnexis parser-state stack-argument proof mismatch');
                }
                const recordLoopYieldProgram = compiler.compile(
                  'struct ParserState { pos: i32, root: i32, slot: i32 }\n' +
                  'fn advance(n: i32) -> ParserState {\n' +
                  ' return loop(state = ParserState { pos: 0, root: 0, slot: 0 }) while state.pos < n {\n' +
                  '  next(ParserState { pos: state.pos + 1, root: state.root, slot: state.slot + 1 });\n' +
                  ' } yield state;\n' +
                  '}\n' +
                  'fn run() -> i32 { let state = advance(5); return state.pos + state.slot; }\n' +
                  'fn main() -> i32 { return run(); }\n'
                );
                const recordLoopYieldBinary = compiler.encodeIR(recordLoopYieldProgram.ir);
                const recordLoopYieldDecoded = compiler.decodeIR(recordLoopYieldBinary);
                const recordLoopYieldRuntime = compiler.emitArm32Runtime(recordLoopYieldProgram.ir);
                const recordLoopYieldMagic = String.fromCharCode(
                  recordLoopYieldBinary[0], recordLoopYieldBinary[1], recordLoopYieldBinary[2],
                  recordLoopYieldBinary[3], recordLoopYieldBinary[4], recordLoopYieldBinary[5]
                );
                const recordLoopYieldFn = recordLoopYieldRuntime.functions.find(fn => fn.name === 'advance');
                if (recordLoopYieldMagic !== 'SNIRV5' ||
                    recordLoopYieldBinary.length !== 1150 ||
                    recordLoopYieldDecoded.dump() !== recordLoopYieldProgram.irText ||
                    !compiler.verifyArm32Runtime(recordLoopYieldRuntime, recordLoopYieldProgram.ir) ||
                    recordLoopYieldRuntime.byteLength !== 700 ||
                    !recordLoopYieldFn ||
                    recordLoopYieldFn.bytes !== 432 ||
                    recordLoopYieldFn.frameBytes !== 152 ||
                    recordLoopYieldFn.slotCount !== 37 ||
                    recordLoopYieldFn.spillSlots !== 13 ||
                    recordLoopYieldFn.blockCount !== 4 ||
                    recordLoopYieldFn.allocator !== 'cfg-spill-v0') {
                  throw new Error('Semnexis record-loop-yield proof mismatch');
                }

                const recursionProgram = compiler.compile(
                  'fn fact(n: i32) -> i32 { return if n <= 1 { 1 } else { n * fact(n - 1) }; }\n' +
                  'fn main() -> i32 { return fact(5); }\n'
                );
                const recursionRuntime = compiler.emitArm32Runtime(recursionProgram.ir);
                const recursionFactFn = recursionRuntime.functions.find(fn => fn.name === 'fact');
                const recursionMainFn = recursionRuntime.functions.find(fn => fn.name === 'main');
                if (!compiler.verifyArm32Runtime(recursionRuntime, recursionProgram.ir) ||
                    recursionRuntime.recursiveFunctions.join(',') !== 'fact' ||
                    recursionRuntime.maxRecursiveCallDepth !== 256 ||
                    !recursionFactFn || recursionFactFn.boundedRecursion !== true ||
                    !recursionMainFn || recursionMainFn.boundedRecursion !== false) {
                  throw new Error('Semnexis bounded-recursion proof mismatch');
                }
                const value = {
                  ok:true,
                  schema:'semnexis-bootstrap-self-test/17',
                  backend:'headless-quickjs',
                  compiler:compiler.version,
                  nodes:result.graph.nodes.length,
                  edges:result.graph.edges.length,
                  planSteps:result.plan.steps.length,
                  irFunctions:result.ir.functions.length,
                  irInstructions:result.ir.functions.reduce((total, fn) => total + fn.instructions.length, 0),
                  irBinaryFormat:compiler.irBinaryFormat,
                  irBinaryVersion:compiler.irBinaryVersion,
                  irBinaryLatestFormat:compiler.irBinaryLatestFormat,
                  irBinaryLatestVersion:compiler.irBinaryLatestVersion,
                  irBinaryCompatibility:compiler.irBinaryCompatibility,
                  irGraphNodeSemantics:compiler.irGraphNodeSemantics,
                  irBinaryBytes:irBinary.length,
                  arm32ElfSchema:compiler.arm32ElfSchema,
                  arm32Target:arm32.target,
                  arm32Bytes:arm32.byteLength,
                  arm32ConstantResult:arm32.constantResult,
                  arm32RuntimeElfSchema:compiler.arm32RuntimeElfSchema,
                  arm32RuntimeTarget:arm32Runtime.target,
                  arm32RuntimeBytes:arm32Runtime.byteLength,
                  arm32RuntimeFunctions:arm32Runtime.functions.length,
                  arm32RuntimeLowered:arm32Runtime.runtimeLowered,
                  arm32RuntimeConstantEvaluated:arm32Runtime.constantEvaluated,
                  arm32RuntimeHasCall:runtimeCall,
                  arm32RuntimeHasCheckedAdd:runtimeAdds,
                  arm32RuntimeAllocator:arm32Runtime.allocator,
                  arm32RuntimeRegisterOnlyFixture:arm32Runtime.functions.every(fn => fn.spillSlots === 0),
                  arm32ArithmeticBytes:arm32Arithmetic.byteLength,
                  arm32ArithmeticChecked:arm32Arithmetic.checkedArithmetic,
                  arm32DivisionHelperBytes:arm32Arithmetic.divisionHelperBytes,
                  arm32ArithmeticSpillSlots:arithmeticFn.spillSlots,
                  arm32RuntimeHasCheckedMultiply:runtimeSmull,
                  arm32RuntimeHasCheckedDivide:runtimeDivCall,
                  controlFlowIrBinaryBytes:conditionalBinary.length,
                  arm32ConditionalBytes:arm32Conditional.byteLength,
                  arm32ConditionalBlocks:chooseFn.blockCount,
                  arm32ConditionalAllocator:chooseFn.allocator,
                  loopIrBinaryBytes:loopBinary.length,
                  arm32LoopBytes:arm32Loop.byteLength,
                  arm32LoopBlocks:sumFn.blockCount,
                  arm32LoopAllocator:sumFn.allocator,
                  arm32ControlFlowLowered:arm32Conditional.controlFlowLowered && arm32Loop.controlFlowLowered,
                  arm32LoopBackedge:loopBackedge,
                  sliceIrBinaryFormat:sliceMagic,
                  sliceIrBinaryBytes:sliceBinary.length,
                  sliceV1Rejects:sliceV1Rejects,
                  arm32SliceBytes:sliceRuntime.byteLength,
                  arm32SliceFunctionBytes:sliceFn.bytes,
                  arm32SliceSpillSlots:sliceFn.spillSlots,
                  recordIrBinaryFormat:recordMagic,
                  recordIrBinaryBytes:recordBinary.length,
                  recordV2Rejects:recordV2Rejects,
                  arm32RecordBytes:recordRuntime.byteLength,
                  arm32RecordFunctionBytes:recordFn.bytes,
                  arm32RecordFrameBytes:recordFn.frameBytes,
                  arm32RecordSlotCount:recordFn.slotCount,
                  arm32RecordSpillSlots:recordFn.spillSlots,
                  projectionIrBinaryFormat:projectionMagic,
                  projectionIrBinaryBytes:projectionBinary.length,
                  projectionV3Rejects:projectionV3Rejects,
                  arm32ProjectionBytes:projectionRuntime.byteLength,
                  arm32ProjectionFunctionBytes:projectionKindFn.bytes,
                  arm32ProjectionFrameBytes:projectionKindFn.frameBytes,
                  arm32ProjectionSlotCount:projectionKindFn.slotCount,
                  arm32ProjectionSpillSlots:projectionKindFn.spillSlots,
                  recordConditionalIrBinaryFormat:recordConditionalMagic,
                  recordConditionalIrBinaryBytes:recordConditionalBinary.length,
                  recordConditionalV4Rejects:recordConditionalV4Rejects,
                  recordConditionalPhiCount:recordPhiCount,
                  arm32RecordConditionalBytes:recordConditionalRuntime.byteLength,
                  arm32RecordConditionalFunctionBytes:recordConditionalFn.bytes,
                  arm32RecordConditionalFrameBytes:recordConditionalFn.frameBytes,
                  arm32RecordConditionalSlotCount:recordConditionalFn.slotCount,
                  arm32RecordConditionalSpillSlots:recordConditionalFn.spillSlots,
                  arm32RecordConditionalBlocks:recordConditionalFn.blockCount,
                  arm32RecordConditionalAllocator:recordConditionalFn.allocator,
                  recordLoopIrBinaryFormat:recordLoopMagic,
                  recordLoopIrBinaryBytes:recordLoopBinary.length,
                  recordLoopV4Rejects:recordLoopV4Rejects,
                  recordLoopPhiRecordCount:recordLoopPhiRecordCount,
                  recordLoopPhiI32Count:recordLoopPhiI32Count,
                  arm32RecordLoopBytes:recordLoopRuntime.byteLength,
                  arm32RecordLoopFunctionBytes:recordLoopFn.bytes,
                  arm32RecordLoopFrameBytes:recordLoopFn.frameBytes,
                  arm32RecordLoopSlotCount:recordLoopFn.slotCount,
                  arm32RecordLoopSpillSlots:recordLoopFn.spillSlots,
                  arm32RecordLoopBlocks:recordLoopFn.blockCount,
                  arm32RecordLoopAllocator:recordLoopFn.allocator,
                  decimalIrBinaryFormat:decimalMagic,
                  decimalIrBinaryBytes:decimalBinary.length,
                  decimalV5Rejects:decimalV5Rejects,
                  decimalZextCount:decimalZextCount,
                  arm32DecimalBytes:decimalRuntime.byteLength,
                  arm32DecimalFunctionBytes:decimalParseFn.bytes,
                  arm32DecimalFrameBytes:decimalParseFn.frameBytes,
                  arm32DecimalSpillSlots:decimalParseFn.spillSlots,
                  arm32DecimalBlocks:decimalParseFn.blockCount,
                  arm32DecimalAllocator:decimalParseFn.allocator,
                  arenaReadIrBinaryFormat:arenaReadMagic,
                  arenaReadIrBinaryBytes:arenaReadBinary.length,
                  arenaReadV6Rejects:arenaReadV6Rejects,
                  arenaReadLoadCount:arenaLoadCount,
                  arm32ArenaReadBytes:arenaReadRuntime.byteLength,
                  arm32ArenaReadFunctionBytes:arenaReadFn.bytes,
                  arm32ArenaReadFrameBytes:arenaReadFn.frameBytes,
                  arm32ArenaReadSlotCount:arenaReadFn.slotCount,
                  arm32ArenaReadSpillSlots:arenaReadFn.spillSlots,
                  arm32ArenaReadAllocator:arenaReadFn.allocator,
                  parserStateStackIrBinaryFormat:parserStateStackMagic,
                  parserStateStackIrBinaryBytes:parserStateStackBinary.length,
                  arm32ParserStateStackBytes:parserStateStackRuntime.byteLength,
                  arm32ParserStateStepBytes:parserStateStepFn.bytes,
                  arm32ParserStateStepFrameBytes:parserStateStepFn.frameBytes,
                  arm32ParserStateStepSlotCount:parserStateStepFn.slotCount,
                  arm32ParserStateCallBytes:parserStateCallFn.bytes,
                  arm32ParserStateCallFrameBytes:parserStateCallFn.frameBytes,
                  arm32ParserStateCallSpillSlots:parserStateCallFn.spillSlots,
                  recordLoopYieldIrBinaryFormat:recordLoopYieldMagic,
                  recordLoopYieldIrBinaryBytes:recordLoopYieldBinary.length,
                  arm32RecordLoopYieldBytes:recordLoopYieldRuntime.byteLength,
                  arm32RecordLoopYieldFunctionBytes:recordLoopYieldFn.bytes,
                  arm32RecordLoopYieldFrameBytes:recordLoopYieldFn.frameBytes,
                  arm32RecordLoopYieldSlotCount:recordLoopYieldFn.slotCount,
                  arm32RecordLoopYieldSpillSlots:recordLoopYieldFn.spillSlots,
                  arm32RecordLoopYieldBlocks:recordLoopYieldFn.blockCount,
                  boundedRecursionFunctions:recursionRuntime.recursiveFunctions.length,
                  boundedRecursionMaxDepth:recursionRuntime.maxRecursiveCallDepth,
                  arm32BoundedRecursionBytes:recursionRuntime.byteLength,
                  arm32BoundedRecursionFunctionBytes:recursionFactFn.bytes,
                  hardeningDerivedEffects:derivedEffectsRejectForgery,
                  hardeningCanonicalMachineVerify:canonicalMachineRejectsTamper,
                  hardeningExpressionBudget:expressionBudgetRejects
                };
                emit(JSON.stringify(value, null, 2));
                finish(value);
                return;
              }

              if (args.length !== 1) {
                throw new Error('usage: semx ' + sub + ' <source.snx>');
              }
              const path = sourcePath(args[0]);
              const result = compiler.compile(read(path));

              if (sub === 'check') {
                const value = {
                  ok:true,
                  source:path,
                  backend:'headless-quickjs',
                  compiler:compiler.version,
                  nodes:result.graph.nodes.length,
                  edges:result.graph.edges.length,
                  planSteps:result.plan.steps.length
                };
                emit(
                  'Semnexis check OK: ' + value.nodes + ' nodes, ' +
                  value.edges + ' edges, ' + value.planSteps + ' plan steps'
                );
                finish(value);
                return;
              }
              if (sub === 'dump-graph') {
                emit(result.graph.dump(MAX_SEMNEXIS_OUTPUT_CHARS).replace(/\n$/,''));
                finish({
                  ok:true,
                  source:path,
                  backend:'headless-quickjs',
                  compiler:compiler.version,
                  format:compiler.graphSchema
                });
                return;
              }
              if (sub === 'dump-plan') {
                emit(result.plan.dump(MAX_SEMNEXIS_OUTPUT_CHARS).replace(/\n$/,''));
                finish({
                  ok:true,
                  source:path,
                  backend:'headless-quickjs',
                  compiler:compiler.version,
                  format:compiler.planSchema
                });
                return;
              }
              if (sub === 'dump-ir') {
                emit(result.ir.dump(MAX_SEMNEXIS_OUTPUT_CHARS).replace(/\n$/,''));
                finish({
                  ok:true,
                  source:path,
                  backend:'headless-quickjs',
                  compiler:compiler.version,
                  format:compiler.irSchema
                });
                return;
              }
              if (sub === 'emit-arm32-proof') {
                const artifact = compiler.emitArm32Proof(result.ir);
                const outputPath = '/documents/builds/Semnexis/semx-arm32-proof.elf';
                __rift_write_semnexis_binary(outputPath, Array.from(artifact.bytes));
                const value = {
                  ok:true,
                  source:path,
                  output:outputPath,
                  backend:'headless-quickjs',
                  compiler:compiler.version,
                  format:artifact.schema,
                  target:artifact.target,
                  bytes:artifact.byteLength,
                  constantResult:artifact.constantResult,
                  executionPolicy:artifact.executionPolicy
                };
                emit(JSON.stringify(value, null, 2));
                finish(value);
                return;
              }
              if (sub === 'emit-arm32-runtime') {
                const artifact = compiler.emitArm32Runtime(result.ir);
                const outputPath = '/documents/builds/Semnexis/semx-arm32-runtime.elf';
                __rift_write_semnexis_binary(outputPath, Array.from(artifact.bytes));
                const value = {
                  ok:true,
                  source:path,
                  output:outputPath,
                  backend:'headless-quickjs',
                  compiler:compiler.version,
                  format:artifact.schema,
                  target:artifact.target,
                  bytes:artifact.byteLength,
                  functions:artifact.functions.length,
                  runtimeLowered:artifact.runtimeLowered,
                  constantEvaluated:artifact.constantEvaluated,
                  trapExitCode:artifact.trapExitCode,
                  allocator:artifact.allocator,
                  controlFlowLowered:artifact.controlFlowLowered,
                  blocks:artifact.functions.reduce((total, fn) => total + fn.blockCount, 0),
                  spillSlots:artifact.functions.reduce((total, fn) => total + fn.spillSlots, 0),
                  registerValues:artifact.functions.reduce((total, fn) => total + fn.registerValues, 0),
                  divisionHelperBytes:artifact.divisionHelperBytes,
                  checkedArithmetic:artifact.checkedArithmetic,
                  executionPolicy:artifact.executionPolicy
                };
                emit(JSON.stringify(value, null, 2));
                finish(value);
                return;
              }
              throw new Error('unknown semx command: ' + sub + '\n' + usage);
            })();
        """

        const val RIFTPP_COMMAND_ENTRY = """
            (async function() {
              const request = JSON.parse(__rift_request());
              const args = Array.from(request.args || []);
              const cwd = String(request.cwd || '/');
              const compiler = globalThis.RiftPlusPlusCore;
              const vm = globalThis.RiftVMHeadless;
              if (!compiler || !vm) throw new Error('Rift++ headless modules are unavailable');

              const output = [];
              const emit = value => output.push(String(value == null ? '' : value));
              const finish = result => __rift_result(JSON.stringify({output: output.join('\n'), result: result || null}));
              const usage = 'Rift++ Core shell (headless QuickJS)\n' +
                'riftpp help\nriftpp version\nriftpp self-test\nriftpp check <source.riftpp>\n' +
                'riftpp compile <source.riftpp> [output.rxe]\nriftpp inspect <source.riftpp|program.rxe>\n' +
                'riftpp run <source.riftpp>\nriftpp exec <program.rxe>\n' +
                'riftpp run-stateful <source.riftpp> <namespace>\nriftpp exec-stateful <program.rxe> <namespace>';

              const normalizePath = value => {
                const raw = String(value || '').replaceAll('\\\\','/');
                const absolute = raw.startsWith('/') || /^[A-Za-z]:($|\/)/.test(raw);
                const joined = absolute ? raw : String(cwd).replace(/\/$/,'') + '/' + raw;
                const parts = [];
                for (const part of joined.split('/')) {
                  if (!part || part === '.') continue;
                  if (part === '..') { if (!parts.length) throw new Error('path escaped root'); parts.pop(); continue; }
                  parts.push(part);
                }
                return '/' + parts.join('/');
              };
              const read = path => __rift_read_text(normalizePath(path));
              const write = (path,text) => __rift_write_text(normalizePath(path), String(text));
              const sourcePath = value => {
                if (!value) throw new Error('Rift++ source path is required');
                const path = normalizePath(value);
                if (!/\.riftpp$/i.test(path)) throw new Error('Rift++ source must end in .riftpp: ' + path);
                return path;
              };
              const execPath = value => {
                if (!value) throw new Error('Rift executable path is required');
                const path = normalizePath(value);
                if (!/\.rxe$/i.test(path)) throw new Error('Rift executable must end in .rxe: ' + path);
                return path;
              };
              const stateNamespaceArg = value => {
                const namespace = String(value || '').trim();
                if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/.test(namespace)) throw new Error('Rift++ state namespace is invalid');
                return namespace;
              };

              const compileSource = (path, source) => {
                const ast = compiler.parse(source);
                if (!ast.uses || !ast.uses.length) return compiler.compile(source);
                const normalized = String(path).replaceAll('\\\\','/');
                const suffix = ast.module.replaceAll('.','/') + '.riftpp';
                if (!normalized.endsWith(suffix)) throw new Error("Rift++ imported source path must mirror module '" + ast.module + "' as " + suffix);
                const moduleRoot = normalized.slice(0, normalized.length - suffix.length);
                const modules = Object.create(null);
                const loaded = new Set();
                const loadModule = name => {
                  if (name === ast.module) throw new Error("Rift++ cyclic module import returns to root '" + name + "'");
                  if (loaded.has(name)) return;
                  if (loaded.size >= 63) throw new Error('Rift++ module graph exceeds 64 total modules');
                  const modulePath = moduleRoot + name.replaceAll('.','/') + '.riftpp';
                  const text = read(modulePath);
                  const depAst = compiler.parse(text);
                  if (depAst.module !== name) throw new Error('Rift++ module identity mismatch: expected ' + name + ', found ' + depAst.module + ' in ' + modulePath);
                  modules[name] = text;
                  loaded.add(name);
                  for (const use of depAst.uses || []) loadModule(use.module);
                };
                for (const use of ast.uses) loadModule(use.module);
                return compiler.compileProgram(source, modules);
              };

              const inspectCompiled = result => ({
                schema: result.schema,
                language: result.language,
                compiler: result.compiler,
                module: result.module,
                modules: Array.from(result.modules || []),
                structs: result.ast.structs.map(item => item.name),
                enums: result.ast.enums.map(item => item.name),
                functions: result.ast.functions.map(item => item.name),
                imports: Array.from(result.executable.imports || []),
                bytes: new TextEncoder().encode(result.executableText).byteLength,
                targetFormat: result.executable.format,
                targetAbi: result.executable.abi
              });

              const execute = async (raw, label, hostMode = 'none', stateNamespace = '') => {
                const info = vm.inspectRiftExecutable(raw);
                const allowedImports = hostMode === 'state' ? new Set(['state.load','state.save','state.remove']) : new Set();
                const deniedImports = info.imports.filter(method => !allowedImports.has(method));
                if (deniedImports.length) throw new Error('riftpp shell execution denies host imports: ' + deniedImports.join(', '));
                const lines = [];
                let bytes = 0;
                const host = {
                  write: value => {
                    const text = String(value);
                    bytes += new TextEncoder().encode(text).byteLength + 1;
                    if (lines.length >= 256 || bytes > 65536) throw new Error('riftpp shell output limit exceeded');
                    lines.push(text);
                  },
                  invoke: async (method, args) => {
                    if (hostMode !== 'state') throw new Error('riftpp shell host imports are disabled');
                    if (method === 'state.load') return __rift_state_load(stateNamespace, String(args[0] ?? ''));
                    if (method === 'state.save') return __rift_state_save(stateNamespace, String(args[0] ?? ''), String(args[1] ?? ''));
                    if (method === 'state.remove') return __rift_state_remove(stateNamespace, String(args[0] ?? ''));
                    throw new Error('riftpp stateful execution denied host import: ' + method);
                  }
                };
                const result = await vm.executeRiftExecutable(raw, host, {maxSteps:100000,maxStack:1024,maxCallDepth:32,yieldEvery:512});
                for (const line of lines) emit(line);
                const summary = {schema:'riftpp-shell-run/1',label:label,steps:result.steps,prints:result.prints,result:result.result};
                emit(JSON.stringify(summary,null,2));
                return {result:result,output:lines};
              };

              const sub = String(args.shift() || 'help').toLowerCase();
              if (sub === 'help') { emit(usage); finish({backend:'headless-quickjs'}); return; }
              if (sub === 'version') {
                const value = {language:compiler.language,compiler:compiler.version,targetFormat:compiler.targetFormat,targetAbi:compiler.targetAbi,backend:'headless-quickjs'};
                emit(JSON.stringify(value,null,2)); finish(value); return;
              }
              if (sub === 'self-test' || sub === 'selftest') {
                const source = 'riftpp 1\nmodule shell.selftest\nfn multiply(a: u32, b: u32) -> u32 { return a * b }\nfn main() { print("Rift++ shell self-test") print(multiply(6, 7)) print(multiply(6, 7) == 42) let max: u8 = 255 print(max) print(u8_to_u32(max)) let narrowed: Result<u8, string> = u8_from_u32(255) match narrowed { Result.Ok(value) => { print(value) } Result.Err(message) => { print(message) } } let rejected: Result<u8, string> = u8_from_u32(256) match rejected { Result.Ok(value) => { print(value) } Result.Err(message) => { print(message) } } var bytes: Buffer<u8, 8> = [65, 66] let pushed: Result<Buffer<u8, 8>, string> = bytes.push(67) match pushed { Result.Ok(next) => { bytes = next } Result.Err(message) => { print(message) return } } print(bytes.len()) match bytes.get(2) { Option.Some(value) => { print(u8_to_u32(value)) } Option.None => { print(999) } } let sliced: Result<Slice<u8>, string> = bytes.slice(1, 3) match sliced { Result.Ok(view) => { print(view.len()) match view.get(0) { Option.Some(value) => { print(u8_to_u32(value)) } Option.None => { print(999) } } } Result.Err(message) => { print(message) } } print(value_sha256(max) == value_sha256(max)) }\n';
                const compiled = compiler.compile(source);
                const executed = await execute(compiled.executable, 'embedded:self-test');
                const expected = ['Rift++ shell self-test','42','true','255','255','255','u32 value is out of u8 range','3','67','2','66','true'];
                if (JSON.stringify(executed.output) !== JSON.stringify(expected)) throw new Error('riftpp self-test output mismatch');
                let literalRejected = false;
                try { compiler.compile('riftpp 1\nmodule shell.selftest_bad_literal\nfn main() { let value: u8 = 256 print(value) }\n'); }
                catch (error) { literalRejected = String(error && error.message || error).includes('u8 literal is out of range'); }
                if (!literalRejected) throw new Error('riftpp self-test expected out-of-range u8 literal rejection');
                let overflowRejected = false;
                try {
                  const overflow = compiler.compile('riftpp 1\nmodule shell.selftest_u8_overflow\nfn main() { let a: u8 = 255 let b: u8 = 1 print(a + b) }\n');
                  await execute(overflow.executable, 'embedded:self-test-u8-overflow');
                } catch (error) { overflowRejected = String(error && error.message || error).includes('u8 overflow'); }
                if (!overflowRejected) throw new Error('riftpp self-test expected checked u8 overflow rejection');
                const value = {ok:true,schema:'riftpp-shell-self-test/3',backend:'headless-quickjs',compiler:compiler.version,format:compiled.executable.format,abi:compiled.executable.abi,steps:executed.result.steps,prints:executed.result.prints,u8:{literalRange:true,explicitConversions:true,bufferSlice:true,hash:true,checkedOverflow:true}};
                emit(JSON.stringify(value,null,2)); finish(value); return;
              }
              if (sub === 'check') {
                const path = sourcePath(args[0]), source = read(path), result = compileSource(path, source), info = inspectCompiled(result);
                const value = {ok:true,path:path,module:result.module,modules:Array.from(result.modules || []),functions:info.functions,bytes:info.bytes,targetFormat:info.targetFormat,targetAbi:info.targetAbi,backend:'headless-quickjs'};
                emit(JSON.stringify(value,null,2)); finish(value); return;
              }
              if (sub === 'compile') {
                const path = sourcePath(args[0]), source = read(path), result = compileSource(path, source);
                const out = args[1] ? normalizePath(args[1]) : path.replace(/\.riftpp$/i,'.rxe');
                if (!/\.rxe$/i.test(out)) throw new Error('Rift executable output must end in .rxe: ' + out);
                write(out, result.executableText);
                const value = {ok:true,source:path,output:out,module:result.module,modules:Array.from(result.modules || []),bytes:new TextEncoder().encode(result.executableText).byteLength,format:result.executable.format,abi:result.executable.abi,backend:'headless-quickjs'};
                emit(JSON.stringify(value,null,2)); finish(value); return;
              }
              if (sub === 'inspect') {
                if (!args[0]) throw new Error('usage: riftpp inspect <source.riftpp|program.rxe>');
                const path = normalizePath(args[0]), text = read(path);
                const value = /\.riftpp$/i.test(path) ? inspectCompiled(compileSource(path,text)) :
                  (/\.rxe$/i.test(path) ? vm.inspectRiftExecutable(text) : (()=>{throw new Error('riftpp inspect expects .riftpp or .rxe: ' + path)})());
                emit(JSON.stringify(value,null,2)); finish(value); return;
              }
              if (sub === 'run') {
                const path = sourcePath(args[0]), source = read(path), result = compileSource(path, source), executed = await execute(result.executable, path);
                finish({backend:'headless-quickjs',steps:executed.result.steps,prints:executed.result.prints}); return;
              }
              if (sub === 'exec') {
                const path = execPath(args[0]), executed = await execute(read(path), path);
                finish({backend:'headless-quickjs',steps:executed.result.steps,prints:executed.result.prints}); return;
              }
              if (sub === 'run-stateful') {
                const path = sourcePath(args[0]), namespace = stateNamespaceArg(args[1]), source = read(path), result = compileSource(path, source), executed = await execute(result.executable, path, 'state', namespace);
                finish({backend:'headless-quickjs',hostMode:'state',namespace:namespace,steps:executed.result.steps,prints:executed.result.prints}); return;
              }
              if (sub === 'exec-stateful') {
                const path = execPath(args[0]), namespace = stateNamespaceArg(args[1]), executed = await execute(read(path), path, 'state', namespace);
                finish({backend:'headless-quickjs',hostMode:'state',namespace:namespace,steps:executed.result.steps,prints:executed.result.prints}); return;
              }
              throw new Error('unknown riftpp command: ' + sub + '\n' + usage);
            })();
        """
    }
}
