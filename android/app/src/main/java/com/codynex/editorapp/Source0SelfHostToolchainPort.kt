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
import java.io.File
import java.security.MessageDigest

class Source0SelfHostToolchainPort(
    private val context: Context,
    private val artifacts: BootstrapArtifacts,
    candidateDirectory: File
) : CompilerPort, PreviewPort {
    companion object {
        private const val COMPILER_AUTHORITY =
            "com.riftos.app.codynexcompiler"
        private const val COMPILE_METHOD = "compile-c0"
        private const val MAX_SOURCE_BYTES = 256 * 1024
        private const val MAX_CANDIDATE_BYTES = 64 * 1024
        private const val PREVIEW_OUTPUT_BYTES = 64 * 1024
    }

    private val candidateRoot =
        candidateDirectory.apply { mkdirs() }.canonicalFile

    override fun compile(request: CompileRequest): CompileResult {
        val sourceBytes = request.sourceText.toByteArray(Charsets.UTF_8)

        if (sourceBytes.size > MAX_SOURCE_BYTES) {
            return failure(
                request.sourcePath,
                "source exceeds $MAX_SOURCE_BYTES bytes"
            )
        }

        val response =
            try {
                context.contentResolver.call(
                    Uri.parse("content://$COMPILER_AUTHORITY"),
                    COMPILE_METHOD,
                    null,
                    Bundle().apply {
                        putString("source", request.sourceText)
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
        val candidateFile =
            File(request.artifact.id).canonicalFile

        requireInsideCandidateRoot(candidateFile)

        if (!candidateFile.isFile) {
            return previewFailure(
                request.sourcePath,
                "candidate artifact is missing"
            )
        }

        if (candidateFile.length() > MAX_CANDIDATE_BYTES) {
            return previewFailure(
                request.sourcePath,
                "candidate exceeds $MAX_CANDIDATE_BYTES bytes"
            )
        }

        val candidate = candidateFile.readBytes()
        val output = ByteArray(PREVIEW_OUTPUT_BYTES)

        val run =
            try {
                Vm1Bridge.run(
                    vm = artifacts.vm1,
                    program = candidate,
                    source = ByteArray(0),
                    output = output,
                    stepBudget =
                        (candidate.size * 256 + 20_000)
                            .coerceIn(20_000, 5_000_000)
                )
            } catch (error: Throwable) {
                return previewFailure(
                    request.sourcePath,
                    "VM1 preview bridge failed: " +
                        (error.message ?: error.javaClass.simpleName)
                )
            }

        if (run.size < 2) {
            return previewFailure(
                request.sourcePath,
                "VM1 bridge returned an invalid preview result"
            )
        }

        val vmStatus = run[0]
        val programResult = run[1]

        if (vmStatus != 0) {
            return previewFailure(
                request.sourcePath,
                "VM1 preview failed with status $vmStatus"
            )
        }

        return PreviewResult(
            success = true,
            diagnostics = listOf(
                EditorDiagnostic(
                    severity = DiagnosticSeverity.INFO,
                    message =
                        "VM1 preview passed on empty input; " +
                            "program result $programResult",
                    file = request.sourcePath
                )
            ),
            summary = "Preview passed: VM1 result $programResult"
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

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
