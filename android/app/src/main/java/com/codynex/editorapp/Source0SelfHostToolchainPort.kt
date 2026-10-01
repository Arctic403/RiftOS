package com.codynex.editorapp

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.codynex.editor.ArtifactRef
import com.codynex.editor.CompileRequest
import com.codynex.editor.CompileResult
import com.codynex.editor.CompilerPort
import com.codynex.editor.DiagnosticSeverity
import com.codynex.editor.EditorDiagnostic
import com.codynex.editor.PreviewPort
import com.codynex.editor.PreviewRequest
import com.codynex.editor.PreviewResult
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class LivePreviewRun(
    val success: Boolean,
    val result: Int,
    val output: ByteArray,
    val error: String? = null
)

/** TEMPORARY LIVE-PROOF toolchain transport; MUST be replaced by native Codynex/.cx. */
class Source0SelfHostToolchainPort(
    private val context: Context,
    private val artifacts: BootstrapArtifacts,
    candidateDirectory: File
) : CompilerPort, PreviewPort {
    companion object {
        private const val COMPILER_AUTHORITY =
            "com.riftos.app.codynexcompiler"
        private const val COMPILE_METHOD = "compile-c0"
        private const val COMPILE_PROJECT_METHOD = "compile-c0-project"
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
        val modulesJson = JSONObject()
        request.moduleSources.toSortedMap().forEach { (name, source) ->
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
            modulesJson.put(name, source)
        }

        val method =
            if (request.moduleSources.isEmpty()) {
                COMPILE_METHOD
            } else {
                COMPILE_PROJECT_METHOD
            }

        val response =
            try {
                context.contentResolver.call(
                    Uri.parse("content://$COMPILER_AUTHORITY"),
                    method,
                    null,
                    Bundle().apply {
                        putString("source", request.sourceText)
                        if (request.moduleSources.isNotEmpty()) {
                            putString("modulesJson", modulesJson.toString())
                        }
                    }
                )
            } catch (error: Throwable) {
                return failure(
                    request.sourcePath,
                    "RiftOS C0 compiler bridge unavailable: " +
                        (error.message ?: error.javaClass.simpleName)
                )
            }
                ?: return failure(
                    request.sourcePath,
                    "RiftOS C0 compiler bridge returned no result"
                )

        if (!response.getBoolean("success", false)) {
            return failure(
                request.sourcePath,
                response.getString("error")
                    ?: "C0 compiler rejected source"
            )
        }

        val compiler =
            response.getString("compiler")
                ?: return failure(
                    request.sourcePath,
                    "compiler bridge omitted compiler identity"
                )

        val candidate =
            response.getByteArray("vm1")
                ?: return failure(
                    request.sourcePath,
                    "compiler bridge omitted VM1 output"
                )

        if (
            candidate.isEmpty() ||
            candidate.size > MAX_CANDIDATE_BYTES
        ) {
            return failure(
                request.sourcePath,
                "compiler returned invalid VM1 length ${candidate.size}"
            )
        }

        val hash = sha256(candidate)
        val file =
            File(
                candidateRoot,
                "candidate-$hash.vm1"
            ).canonicalFile

        requireInsideCandidateRoot(file)
        atomicWrite(file, candidate)

        return CompileResult(
            success = true,
            artifact = ArtifactRef(
                id = file.absolutePath,
                kind = "vm1-program",
                displayName = file.name
            ),
            diagnostics = listOf(
                EditorDiagnostic(
                    severity = DiagnosticSeverity.INFO,
                    message =
                        "Compiled with $compiler: " +
                            "${candidate.size} VM1 bytes; SHA-256 $hash",
                    file = request.sourcePath
                )
            ),
            summary =
                "Compile succeeded: ${candidate.size}-byte VM1 candidate"
        )
    }

    override fun preview(request: PreviewRequest): PreviewResult {
        val run = executePreview(request.artifact, ByteArray(0))
        latestPreviewRun = run

        if (!run.success) {
            return previewFailure(
                request.sourcePath,
                run.error ?: "VM1 preview failed"
            )
        }

        return PreviewResult(
            success = true,
            diagnostics = listOf(
                EditorDiagnostic(
                    severity = DiagnosticSeverity.INFO,
                    message =
                        "VM1 preview passed on empty input; " +
                            "program result ${run.result}",
                    file = request.sourcePath
                )
            ),
            summary = "Preview passed: VM1 result ${run.result}"
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
                Vm1Bridge.run(
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
        val raw =
            try {
                Vm1Bridge.run(
                    vm = artifacts.vm1,
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
                        "VM1 preview bridge failed: " +
                            (error.message ?: error.javaClass.simpleName)
                )
            }

        if (raw.size < 2) {
            return LivePreviewRun(
                success = false,
                result = 0,
                output = ByteArray(0),
                error = "VM1 bridge returned an invalid preview result"
            )
        }

        val vmStatus = raw[0]
        val programResult = raw[1]

        if (vmStatus != 0) {
            return LivePreviewRun(
                success = false,
                result = programResult,
                output = ByteArray(0),
                error = "VM1 preview failed with status $vmStatus"
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
