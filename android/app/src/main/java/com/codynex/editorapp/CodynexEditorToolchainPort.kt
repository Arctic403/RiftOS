package com.codynex.editorapp

import android.content.Context
import com.codynex.editor.ArtifactRef
import com.codynex.editor.CompileRequest
import com.codynex.editor.CompileResult
import com.codynex.editor.CompilerPort
import com.codynex.editor.DiagnosticSeverity
import com.codynex.editor.EditorDiagnostic
import com.codynex.editor.PreviewPort
import com.codynex.editor.PreviewRequest
import com.codynex.editor.PreviewResult
import java.io.File
import java.security.MessageDigest

data class LivePreviewRun(
    val success: Boolean,
    val result: Int,
    val output: ByteArray,
    val error: String? = null
)

enum class EditorVmTarget {
    VM1,
    VM2
}

/** Android editor toolchain adapter. Compiler semantics come from editor-owned payloads. */
class CodynexEditorToolchainPort(
    private val context: Context,
    private val artifacts: BootstrapArtifacts,
    candidateDirectory: File,
    private val target: EditorVmTarget = EditorVmTarget.VM2
) : CompilerPort, PreviewPort {
    companion object {
        private val MODULE_ID = Regex(
            "^[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*$"
        )
        private const val MAX_SOURCE_BYTES = 256 * 1024
        private const val MAX_PROJECT_BYTES = 1024 * 1024
        private const val MAX_PROJECT_MODULES = 64
        private const val MAX_CANDIDATE_BYTES = 64 * 1024
        private const val PREVIEW_OUTPUT_BYTES = 64 * 1024
        private const val MAX_LIVE_INPUT_BYTES = 1024
        private const val NATIVE_PROOF_ASSET = "native_proof.hex"
        private const val NATIVE_PROOF_EXPECTED_ASSET =
            "native_proof_expected.hex"
        private const val NATIVE_PROOF_INPUT_ASSET =
            "native_proof_input.hex"
        private const val MAX_NATIVE_PROOF_BYTES = 4 * 1024
        private const val MAX_NATIVE_PROOF_INPUT_BYTES = 1024
    }

    private val candidateRoot =
        candidateDirectory.apply { mkdirs() }.canonicalFile

    @Volatile
    private var latestPreviewRun: LivePreviewRun? = null

    override fun compile(request: CompileRequest): CompileResult {
        val sourceBytes = request.sourceText.toByteArray(Charsets.UTF_8)

        if (sourceBytes.isEmpty() || sourceBytes.size > MAX_SOURCE_BYTES) {
            return failure(
                request.sourcePath,
                "source must be 1..$MAX_SOURCE_BYTES bytes"
            )
        }

        if (request.moduleSources.size > MAX_PROJECT_MODULES - 1) {
            return failure(
                request.sourcePath,
                "project exceeds $MAX_PROJECT_MODULES modules"
            )
        }

        var totalBytes = sourceBytes.size
        request.moduleSources.toSortedMap().forEach { (name, source) ->
            if (!MODULE_ID.matches(name)) {
                return failure(
                    request.sourcePath,
                    "invalid Codynex module identity: $name"
                )
            }
            val bytes = source.toByteArray(Charsets.UTF_8)
            if (bytes.isEmpty() || bytes.size > MAX_SOURCE_BYTES) {
                return failure(
                    request.sourcePath,
                    "module $name exceeds per-source bounds"
                )
            }
            totalBytes += bytes.size
            if (totalBytes > MAX_PROJECT_BYTES) {
                return failure(
                    request.sourcePath,
                    "project exceeds $MAX_PROJECT_BYTES bytes"
                )
            }
        }

        val targetKey =
            if (target == EditorVmTarget.VM2) "vm2" else "vm1"
        val targetLabel = target.name

        val compiled =
            try {
                CodynexCompilerRuntime(context.applicationContext).compile(
                    rootSource = request.sourceText,
                    moduleSources = request.moduleSources.toSortedMap(),
                    target = target
                )
            } catch (error: Throwable) {
                return failure(
                    request.sourcePath,
                    "Codynex editor compiler failed: " +
                        (error.message ?: error.javaClass.simpleName)
                )
            }

        val compiler = compiled.compiler
        val candidate = compiled.bytes

        if (
            candidate.isEmpty() ||
            candidate.size > MAX_CANDIDATE_BYTES
        ) {
            return failure(
                request.sourcePath,
                "compiler returned invalid $targetLabel length ${candidate.size}"
            )
        }

        val hash = sha256(candidate)
        val file =
            File(
                candidateRoot,
                "candidate-$hash.$targetKey"
            ).canonicalFile

        requireInsideCandidateRoot(file)
        atomicWrite(file, candidate)

        return CompileResult(
            success = true,
            artifact = ArtifactRef(
                id = file.absolutePath,
                kind = "$targetKey-program",
                displayName = file.name
            ),
            diagnostics = listOf(
                EditorDiagnostic(
                    severity = DiagnosticSeverity.INFO,
                    message =
                        "Compiled with $compiler (${compiled.source}): " +
                            "${candidate.size} $targetLabel bytes; SHA-256 $hash",
                    file = request.sourcePath
                )
            ),
            summary =
                "Compile succeeded: ${candidate.size}-byte $targetLabel candidate"
        )
    }

    override fun preview(request: PreviewRequest): PreviewResult {
        val targetLabel = target.name
        val run = executePreview(request.artifact, ByteArray(0))
        latestPreviewRun = run

        if (!run.success) {
            return previewFailure(
                request.sourcePath,
                run.error ?: "$targetLabel preview failed"
            )
        }

        return PreviewResult(
            success = true,
            diagnostics = listOf(
                EditorDiagnostic(
                    severity = DiagnosticSeverity.INFO,
                    message =
                        "$targetLabel preview passed on empty input; " +
                            "program result ${run.result}",
                    file = request.sourcePath
                )
            ),
            summary = "Preview passed: $targetLabel result ${run.result}"
        )
    }

    fun latestLivePreview(): LivePreviewRun? =
        latestPreviewRun?.let { run ->
            run.copy(output = run.output.copyOf())
        }

    fun replayLivePreview(
        artifact: ArtifactRef,
        input: ByteArray
    ): LivePreviewRun {
        if (input.size > MAX_LIVE_INPUT_BYTES) {
            return LivePreviewRun(
                success = false,
                result = 0,
                output = ByteArray(0),
                error = "live preview input exceeds $MAX_LIVE_INPUT_BYTES bytes"
            )
        }

        val run = executePreview(artifact, input)
        latestPreviewRun = run
        return run.copy(output = run.output.copyOf())
    }

    fun runNativeProof(): LivePreviewRun {
        val kernel =
            try {
                val raw =
                    context.assets.open(NATIVE_PROOF_ASSET)
                        .bufferedReader(Charsets.UTF_8)
                        .use { it.readText().trim() }
                decodeCanonicalHex(raw)
            } catch (error: Throwable) {
                return LivePreviewRun(
                    success = false,
                    result = 0,
                    output = ByteArray(0),
                    error =
                        "native proof asset load failed: " +
                            (error.message ?: error.javaClass.simpleName)
                )
            }

        if (
            kernel.isEmpty() ||
            kernel.size > MAX_NATIVE_PROOF_BYTES ||
            kernel.size % 4 != 0
        ) {
            return LivePreviewRun(
                success = false,
                result = 0,
                output = ByteArray(0),
                error =
                    "native proof kernel must be aligned and 4.." +
                        MAX_NATIVE_PROOF_BYTES +
                        " bytes"
            )
        }

        val expected =
            try {
                val raw =
                    context.assets.open(NATIVE_PROOF_EXPECTED_ASSET)
                        .bufferedReader(Charsets.UTF_8)
                        .use { it.readText().trim() }

                require(
                    raw.length == 8 &&
                        raw.all { value ->
                            value in '0'..'9' || value in 'a'..'f'
                        }
                ) {
                    "expected result must be exactly 8 lowercase hex digits"
                }

                raw.toLong(16).toInt()
            } catch (error: Throwable) {
                return LivePreviewRun(
                    success = false,
                    result = 0,
                    output = ByteArray(0),
                    error =
                        "native proof expected-result load failed: " +
                            (error.message ?: error.javaClass.simpleName)
                )
            }

        val input =
            try {
                val raw =
                    context.assets.open(NATIVE_PROOF_INPUT_ASSET)
                        .bufferedReader(Charsets.UTF_8)
                        .use { it.readText().trim() }

                require(
                    raw.isNotEmpty() &&
                        raw.length % 2 == 0 &&
                        raw.length <= MAX_NATIVE_PROOF_INPUT_BYTES * 2 &&
                        raw.all { value ->
                            value in '0'..'9' || value in 'a'..'f'
                        }
                ) {
                    "native proof input must be bounded canonical lowercase hex"
                }

                decodeCanonicalHex(raw)
            } catch (error: Throwable) {
                return LivePreviewRun(
                    success = false,
                    result = 0,
                    output = ByteArray(0),
                    error =
                        "native proof input load failed: " +
                            (error.message ?: error.javaClass.simpleName)
                )
            }

        val raw =
            try {
                CodynexRuntimeBridge.run(
                    vm = kernel,
                    program = byteArrayOf(0, 0, 0, 0),
                    source = input,
                    output = ByteArray(1),
                    stepBudget = 1
                )
            } catch (error: Throwable) {
                return LivePreviewRun(
                    success = false,
                    result = 0,
                    output = ByteArray(0),
                    error =
                        "native proof bridge failed: " +
                            (error.message ?: error.javaClass.simpleName)
                )
            }

        if (raw.size < 2) {
            return LivePreviewRun(
                success = false,
                result = 0,
                output = ByteArray(0),
                error = "native proof bridge returned an invalid result"
            )
        }

        if (raw[0] != 0) {
            return LivePreviewRun(
                success = false,
                result = raw[1],
                output = ByteArray(0),
                error =
                    "native proof kernel failed with status " +
                        raw[0].toString()
            )
        }

        if (raw[1] != expected) {
            return LivePreviewRun(
                success = false,
                result = raw[1],
                output = ByteArray(0),
                error =
                    "native proof expected 0x" +
                        expected.toUInt().toString(16).padStart(8, '0') +
                        " but got 0x" +
                        raw[1].toUInt().toString(16).padStart(8, '0')
            )
        }

        return LivePreviewRun(
            success = true,
            result = raw[1],
            output = ByteArray(0)
        )
    }

    private fun executePreview(
        artifact: ArtifactRef,
        input: ByteArray
    ): LivePreviewRun {
        val candidateFile = File(artifact.id).canonicalFile
        requireInsideCandidateRoot(candidateFile)

        if (!candidateFile.isFile) {
            return LivePreviewRun(
                success = false,
                result = 0,
                output = ByteArray(0),
                error = "candidate artifact is missing"
            )
        }

        if (candidateFile.length() > MAX_CANDIDATE_BYTES) {
            return LivePreviewRun(
                success = false,
                result = 0,
                output = ByteArray(0),
                error = "candidate exceeds $MAX_CANDIDATE_BYTES bytes"
            )
        }

        val candidate = candidateFile.readBytes()
        val output = ByteArray(PREVIEW_OUTPUT_BYTES)
        val runtime =
            if (target == EditorVmTarget.VM2) {
                artifacts.vm2
            } else {
                artifacts.vm1
            }
        val targetLabel = target.name
        val raw =
            try {
                CodynexRuntimeBridge.run(
                    vm = runtime,
                    program = candidate,
                    source = input,
                    output = output,
                    stepBudget =
                        (candidate.size * 256 + 20_000)
                            .coerceIn(20_000, 5_000_000)
                )
            } catch (error: Throwable) {
                return LivePreviewRun(
                    success = false,
                    result = 0,
                    output = ByteArray(0),
                    error =
                        "$targetLabel preview bridge failed: " +
                            (error.message ?: error.javaClass.simpleName)
                )
            }

        if (raw.size < 2) {
            return LivePreviewRun(
                success = false,
                result = 0,
                output = ByteArray(0),
                error = "$targetLabel bridge returned an invalid preview result"
            )
        }

        val vmStatus = raw[0]
        val programResult = raw[1]

        if (vmStatus != 0) {
            return LivePreviewRun(
                success = false,
                result = programResult,
                output = ByteArray(0),
                error = "$targetLabel preview failed with status $vmStatus"
            )
        }

        val emitted =
            if (programResult in 0..output.size) {
                output.copyOf(programResult)
            } else {
                ByteArray(0)
            }

        return LivePreviewRun(
            success = true,
            result = programResult,
            output = emitted
        )
    }

    private fun failure(
        path: String,
        message: String
    ): CompileResult =
        CompileResult(
            success = false,
            diagnostics = listOf(
                EditorDiagnostic(
                    severity = DiagnosticSeverity.ERROR,
                    message = message,
                    file = path
                )
            ),
            summary = "Compile failed"
        )

    private fun previewFailure(
        path: String,
        message: String
    ): PreviewResult =
        PreviewResult(
            success = false,
            diagnostics = listOf(
                EditorDiagnostic(
                    severity = DiagnosticSeverity.ERROR,
                    message = message,
                    file = path
                )
            ),
            summary = "Preview failed"
        )

    private fun requireInsideCandidateRoot(file: File) {
        val prefix = candidateRoot.path + File.separator
        require(
            file == candidateRoot ||
                file.path.startsWith(prefix)
        ) {
            "candidate path escapes editor artifact directory"
        }
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val temp =
            File(target.parentFile, ".${target.name}.tmp")

        temp.writeBytes(bytes)

        if (target.exists()) {
            require(target.delete()) {
                "could not replace candidate artifact"
            }
        }

        require(temp.renameTo(target)) {
            "could not publish candidate artifact"
        }
    }

    private fun decodeCanonicalHex(raw: String): ByteArray {
        require(raw.length % 2 == 0) { "hex artifact has odd length" }

        val output = ByteArray(raw.length / 2)
        var source = 0
        var target = 0

        while (source < raw.length) {
            val high = nibble(raw[source])
            val low = nibble(raw[source + 1])
            output[target] = ((high shl 4) or low).toByte()
            source += 2
            target += 1
        }

        return output
    }

    private fun nibble(value: Char): Int =
        when (value) {
            in '0'..'9' -> value.code - '0'.code
            in 'a'..'f' -> value.code - 'a'.code + 10
            else -> error("non-canonical hex character")
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
