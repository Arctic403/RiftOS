package com.codynex.editorapp

import android.content.Context
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.evaluate
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

data class CodynexCompilerCandidate(
    val compiler: String,
    val bytes: ByteArray,
    val source: String
)

/**
 * Editor-owned compiler host.
 *
 * The Android layer provides only a bounded QuickJS execution container. Compiler
 * semantics live in the Codynex-owned compiler payload. A compiler pushed to the
 * editor workspace hot-swap path overrides the bundled bootstrap compiler without
 * requiring a RiftOS rebuild.
 */
class CodynexCompilerRuntime(context: Context) {
    companion object {
        const val HOT_COMPILER_WORKSPACE_PATH =
            ".codynex/toolchains/compiler.js"

        private const val BUNDLED_COMPILER_ASSET =
            "codynex_compiler.js"
        private const val MAX_COMPILER_SOURCE_BYTES =
            1024 * 1024
        private const val MAX_RESULT_BYTES =
            64 * 1024
        private const val EVALUATION_TIMEOUT_MS =
            120_000L

        private const val COMPILE_ENTRY = """
            (function() {
              const request = JSON.parse(__codynex_request());
              try {
                const compiler = globalThis.CodynexC0;
                if (!compiler) {
                  throw new Error('Codynex compiler payload did not expose CodynexC0');
                }
                if (typeof compiler.VERSION !== 'string' || !compiler.VERSION) {
                  throw new Error('Codynex compiler payload omitted VERSION');
                }

                const modules = request.modules || {};
                const hasModules = Object.keys(modules).length > 0;
                const vm2 = request.target === 'vm2';
                let compiled;

                if (vm2) {
                  if (typeof compiler.compileVM2 !== 'function' ||
                      typeof compiler.compileProjectVM2 !== 'function') {
                    throw new Error('Codynex compiler payload does not support VM2');
                  }
                  compiled = hasModules
                    ? compiler.compileProjectVM2(String(request.root || ''), modules)
                    : compiler.compileVM2(String(request.root || ''));
                } else {
                  if (typeof compiler.compile !== 'function' ||
                      typeof compiler.compileProject !== 'function') {
                    throw new Error('Codynex compiler payload does not support VM1');
                  }
                  compiled = hasModules
                    ? compiler.compileProject(String(request.root || ''), modules)
                    : compiler.compile(String(request.root || ''));
                }

                const artifact = vm2 ? compiled.vm2 : compiled.vm1;
                const raw = Array.from(
                  artifact && artifact.bytes ? artifact.bytes : [],
                  value => Number(value) & 255
                );
                let hex = '';
                for (const value of raw) {
                  hex += value.toString(16).padStart(2, '0');
                }

                __codynex_result(JSON.stringify({
                  ok: true,
                  compiler: compiler.VERSION,
                  bytes: raw.length,
                  hex: hex
                }));
              } catch (error) {
                __codynex_result(JSON.stringify({
                  ok: false,
                  code: error && error.code ? String(error.code) : '',
                  error: String(error && error.message || error)
                }));
              }
            })();
        """
    }

    private val appContext = context.applicationContext
    private val workspaceRoot =
        File(appContext.filesDir, "codynex-workspace")
            .apply { mkdirs() }
            .canonicalFile
    private val hotCompilerFile =
        File(workspaceRoot, HOT_COMPILER_WORKSPACE_PATH)
            .canonicalFile

    init {
        val prefix = workspaceRoot.path + File.separator
        require(hotCompilerFile.path.startsWith(prefix)) {
            "Codynex compiler hot-swap path escaped editor workspace"
        }
    }

    fun compile(
        rootSource: String,
        moduleSources: Map<String, String>,
        target: EditorVmTarget
    ): CodynexCompilerCandidate {
        val modules = JSONObject()
        moduleSources.toSortedMap().forEach { (name, source) ->
            modules.put(name, source)
        }

        val request =
            JSONObject()
                .put("root", rootSource)
                .put("modules", modules)
                .put(
                    "target",
                    if (target == EditorVmTarget.VM2) "vm2" else "vm1"
                )

        val compilerPayload = loadCompilerPayload()
        var resultJson: String? = null

        runBlocking {
            quickJs {
                evaluationTimeoutMillis = EVALUATION_TIMEOUT_MS

                function("__codynex_request") {
                    request.toString()
                }
                function("__codynex_result") { values ->
                    resultJson = values.firstOrNull()?.toString()
                    Unit
                }

                evaluate<Any?>(
                    "globalThis.CODYNEX_AUTORUN=false;",
                    filename = "codynex-compiler-config.js"
                )
                evaluate<Any?>(
                    compilerPayload.first,
                    filename = compilerPayload.second
                )
                evaluate<Any?>(
                    COMPILE_ENTRY,
                    filename = "codynex-editor-compile.js"
                )
            }
        }

        val payload =
            resultJson?.let(::JSONObject)
                ?: error("Codynex compiler returned no result")

        require(payload.optBoolean("ok", false)) {
            val code = payload.optString("code").trim()
            val message =
                payload.optString("error").trim()
                    .ifBlank { "Codynex compiler rejected source" }
            if (code.isBlank()) message else "$code: $message"
        }

        val compiler =
            payload.optString("compiler").trim()
        require(compiler.isNotEmpty() && compiler.length <= 128) {
            "Codynex compiler identity is invalid"
        }

        val hex = payload.optString("hex")
        require(
            hex.isNotEmpty() &&
                hex.length % 2 == 0 &&
                hex.length / 2 <= MAX_RESULT_BYTES
        ) {
            "Codynex compiler returned malformed or oversized output"
        }

        val bytes = ByteArray(hex.length / 2)
        var sourceIndex = 0
        var targetIndex = 0
        while (sourceIndex < hex.length) {
            val high =
                hex[sourceIndex].digitToIntOrNull(16)
                    ?: error("Codynex compiler output is non-canonical hex")
            val low =
                hex[sourceIndex + 1].digitToIntOrNull(16)
                    ?: error("Codynex compiler output is non-canonical hex")
            bytes[targetIndex] = ((high shl 4) or low).toByte()
            sourceIndex += 2
            targetIndex += 1
        }

        return CodynexCompilerCandidate(
            compiler = compiler,
            bytes = bytes,
            source = compilerPayload.second
        )
    }

    private fun loadCompilerPayload(): Pair<String, String> {
        if (hotCompilerFile.isFile) {
            require(hotCompilerFile.length() in 1..MAX_COMPILER_SOURCE_BYTES.toLong()) {
                "hot Codynex compiler payload is out of bounds"
            }
            return hotCompilerFile.readText(Charsets.UTF_8) to
                "workspace:$HOT_COMPILER_WORKSPACE_PATH"
        }

        val bytes =
            appContext.assets.open(BUNDLED_COMPILER_ASSET).use { input ->
                input.readBytes()
            }
        require(bytes.isNotEmpty() && bytes.size <= MAX_COMPILER_SOURCE_BYTES) {
            "bundled Codynex compiler payload is out of bounds"
        }
        return bytes.toString(Charsets.UTF_8) to
            "asset:$BUNDLED_COMPILER_ASSET"
    }
}
