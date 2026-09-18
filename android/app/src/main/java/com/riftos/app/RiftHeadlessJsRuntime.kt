package com.riftos.app

import android.content.Context
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.evaluate
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Headless trusted JavaScript service runtime.
 *
 * This is deliberately not a browser surface. It hosts the frozen Rift++ Core/RiftVM JavaScript
 * modules inside QuickJS with a tiny capability set: confined RiftFS text I/O, UTF-8 and SHA-256.
 * No DOM, network, Android intents, arbitrary native calls, or ambient shell globals are exposed.
 */
class RiftHeadlessJsRuntime(context: Context) {
    companion object {
        private const val MAX_TEXT_BYTES = 8L * 1024L * 1024L
        private const val MAX_STATE_BYTES = 64 * 1024
        private const val MAX_STATE_KEY_BYTES = 4 * 1024
        private const val MAX_STATE_FILES = 256
        private const val EVALUATION_TIMEOUT_MS = 120_000L
    }

    data class CommandResult(val output: String, val result: JSONObject?)

    private val appContext = context.applicationContext
    private val riftRoot = File(appContext.filesDir, "riftfs").apply { mkdirs() }.canonicalFile
    private val stateRoot = File(riftRoot, "system/riftpp-state").apply { mkdirs() }.canonicalFile

    @Volatile private var vmSourceCache: String? = null
    @Volatile private var coreSourceCache: String? = null

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
                    values.firstOrNull()?.toString().orEmpty().toByteArray(Charsets.UTF_8)
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
                    val bytes = text.toByteArray(Charsets.UTF_8)
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


    fun executeDeveloperTool(args: List<String>): CommandResult {
        val subcommand = args.firstOrNull()?.trim()?.lowercase().orEmpty()
        return when (subcommand) {
            "", "help" -> {
                val value = JSONObject()
                    .put("schema", "rift-developer-tool/1")
                    .put("commands", org.json.JSONArray(listOf("rift-tool gate0-verify", "rift-tool semantic-compat")))
                    .put("genericJavaScript", false)
                    .put("processAuthority", false)
                    .put("networkAuthority", false)
                CommandResult(
                    output = "Rift developer tools\nrift-tool gate0-verify\nrift-tool semantic-compat",
                    result = value
                )
            }
            "gate0-verify" -> executeGate0Verifier()
            "semantic-compat" -> executeSemanticCompatibilityVerifier()
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
                    values.firstOrNull()?.toString().orEmpty().toByteArray(Charsets.UTF_8)
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
                    values.firstOrNull()?.toString().orEmpty().toByteArray(Charsets.UTF_8)
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
            val bytes = text.toByteArray(Charsets.UTF_8)
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
            val bytes = text.toByteArray(Charsets.UTF_8)
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
        require(bundle.toString().toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES) {
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
        val keyBytes = key.toByteArray(Charsets.UTF_8)
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
        val bytes = value.toByteArray(Charsets.UTF_8)
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
              encode(value) { return __rift_utf8(String(value)); }
            };
            globalThis.crypto = Object.freeze({
              subtle: Object.freeze({
                digest: async function(name, data) {
                  if (String(name).toUpperCase() !== 'SHA-256') throw new Error('Only SHA-256 is available');
                  const view = data instanceof ArrayBuffer ? new Int8Array(data) : data;
                  const out = __rift_sha256(view);
                  return out.buffer.slice(out.byteOffset, out.byteOffset + out.byteLength);
                }
              })
            });
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
                const source = 'riftpp 1\nmodule shell.selftest\nfn multiply(a: u32, b: u32) -> u32 { return a * b }\nfn main() { print("Rift++ shell self-test") print(multiply(6, 7)) print(multiply(6, 7) == 42) }\n';
                const compiled = compiler.compile(source);
                const executed = await execute(compiled.executable, 'embedded:self-test');
                const expected = ['Rift++ shell self-test','42','true'];
                if (JSON.stringify(executed.output) !== JSON.stringify(expected)) throw new Error('riftpp self-test output mismatch');
                const value = {ok:true,schema:'riftpp-shell-self-test/2',backend:'headless-quickjs',compiler:compiler.version,format:compiled.executable.format,abi:compiled.executable.abi,steps:executed.result.steps,prints:executed.result.prints};
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
