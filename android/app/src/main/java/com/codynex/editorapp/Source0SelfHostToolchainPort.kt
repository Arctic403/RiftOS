package com.codynex.editorapp

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
    private val artifacts: BootstrapArtifacts,
    candidateDirectory: File
) : CompilerPort, PreviewPort {
    companion object {
        private const val MAX_SOURCE_BYTES = 1024 * 1024
        private const val MAX_CANDIDATE_BYTES = 64 * 1024
    }

    private val candidateRoot =
        candidateDirectory.apply { mkdirs() }.canonicalFile

    override fun compile(request: CompileRequest): CompileResult {
        val source = request.sourceText.toByteArray(Charsets.UTF_8)

        if (source.size > MAX_SOURCE_BYTES) {
            return failure(
                request.sourcePath,
                "source exceeds $MAX_SOURCE_BYTES bytes"
            )
        }

        val output = ByteArray(maxOf(1, (source.size + 1) / 2)) {
            0xA5.toByte()
        }

        val run = Vm1Bridge.run(
            vm = artifacts.vm1,
            program = artifacts.compilerA,
            source = source,
            output = output,
            stepBudget = stepBudget(source.size)
        )

        if (run.size < 2) {
            return failure(
                request.sourcePath,
                "VM bridge returned an invalid result"
            )
        }

        val vmStatus = run[0]
        val compilerResult = run[1]

        if (vmStatus != 0) {
            return failure(
                request.sourcePath,
                "VM1 rejected compiler execution with status $vmStatus"
            )
        }

        if (compilerResult <= 0) {
            return failure(
                request.sourcePath,
                "Source0 compiler rejected source with result $compilerResult"
            )
        }

        if (
            compilerResult > output.size ||
            compilerResult > MAX_CANDIDATE_BYTES
        ) {
            return failure(
                request.sourcePath,
                "compiler returned invalid output length $compilerResult"
            )
        }

        val candidate = output.copyOf(compilerResult)
        val hash = sha256(candidate)
        val file = File(candidateRoot, "candidate-$hash.vm1").canonicalFile

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
                        "Compiled $compilerResult VM1 bytes; SHA-256 $hash",
                    file = request.sourcePath
                )
            ),
            summary = "Compile succeeded: $compilerResult-byte candidate"
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
        val source = File(request.sourcePath)
            .takeIf { it.isFile }
            ?.readText(Charsets.UTF_8)
            ?.toByteArray(Charsets.UTF_8)
            ?: return previewFailure(
                request.sourcePath,
                "save the document before preview"
            )

        if (source.size > MAX_SOURCE_BYTES) {
            return previewFailure(
                request.sourcePath,
                "source exceeds $MAX_SOURCE_BYTES bytes"
            )
        }

        val output = ByteArray(maxOf(1, candidate.size)) {
            0xA5.toByte()
        }

        val run = Vm1Bridge.run(
            vm = artifacts.vm1,
            program = candidate,
            source = source,
            output = output,
            stepBudget = stepBudget(source.size)
        )

        if (run.size < 2) {
            return previewFailure(
                request.sourcePath,
                "VM bridge returned an invalid preview result"
            )
        }

        val vmStatus = run[0]
        val compilerResult = run[1]

        if (vmStatus != 0) {
            return previewFailure(
                request.sourcePath,
                "candidate VM execution failed with status $vmStatus"
            )
        }

        if (compilerResult != candidate.size) {
            return previewFailure(
                request.sourcePath,
                "candidate produced $compilerResult bytes; expected ${candidate.size}"
            )
        }

        val reproduced = output.copyOf(compilerResult)
        if (!reproduced.contentEquals(candidate)) {
            return previewFailure(
                request.sourcePath,
                "candidate executed but did not reproduce itself"
            )
        }

        return PreviewResult(
            success = true,
            diagnostics = listOf(
                EditorDiagnostic(
                    severity = DiagnosticSeverity.INFO,
                    message =
                        "Fixed-point preview passed: candidate reproduced " +
                            "${candidate.size} bytes exactly",
                    file = request.sourcePath
                )
            ),
            summary = "Preview passed: self-hosted candidate fixed point"
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

    private fun stepBudget(sourceBytes: Int): Int =
        (sourceBytes * 64 + 4096)
            .coerceIn(20_000, 5_000_000)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
