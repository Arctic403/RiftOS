package com.riftpp.editor

import android.content.Context

class RiftppPipeline(private val context: Context) {
    companion object {
        private const val MAX_NATIVE_PROGRAM_BYTES = 256 * 1024
        private const val MAX_APP_ARTIFACT_BYTES = 1024 * 1024
        private const val MAX_PREVIEW_BYTES = 1024 * 1024
    }

    private val bridge = RiftppNativeBridge()

    private val frozenCompiler: ByteArray by lazy {
        HexAssets.readContinuousHex(
            context,
            if (android.os.Process.is64Bit()) {
                "riftpp/s3/compiler.arm64.native.hex"
            } else {
                "riftpp/s3/compiler.arm32.native.hex"
            }
        )
    }

    private val frontendProgram: ByteArray by lazy {
        compileRecordProgram(
            if (android.os.Process.is64Bit()) {
                "riftpp/frontend/frontend.arm64.r4.hex"
            } else {
                "riftpp/frontend/frontend.arm32.r4.hex"
            }
        )
    }

    private val previewProgram: ByteArray by lazy {
        compileRecordProgram(
            if (android.os.Process.is64Bit()) {
                "riftpp/runtime/preview.arm64.r4.hex"
            } else {
                "riftpp/runtime/preview.arm32.r4.hex"
            }
        )
    }

    init {
        require(frozenCompiler.isNotEmpty()) { "frozen S3 compiler missing" }
    }

    fun compile(source: String): ByteArray {
        val sourceBytes = source.toByteArray(Charsets.US_ASCII)
        require(sourceBytes.size <= MAX_APP_ARTIFACT_BYTES) { "source too large" }
        return bridge.run(
            frontendProgram,
            sourceBytes,
            MAX_APP_ARTIFACT_BYTES
        ) ?: error("Rift++ frontend rejected source")
    }

    fun preview(artifact: ByteArray): ByteArray {
        require(artifact.size <= MAX_APP_ARTIFACT_BYTES) { "artifact too large" }
        return bridge.run(
            previewProgram,
            artifact,
            MAX_PREVIEW_BYTES
        ) ?: error("Rift++ preview runtime rejected artifact")
    }

    private fun compileRecordProgram(assetPath: String): ByteArray {
        val recordSource = HexAssets.readLineHex(context, assetPath)
        return bridge.run(
            frozenCompiler,
            recordSource,
            MAX_NATIVE_PROGRAM_BYTES
        ) ?: error("frozen S3 compiler rejected " + assetPath)
    }
}
