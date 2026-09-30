package com.riftpp.editor

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RiftppPipeline(private val context: Context) {
    companion object {
        private const val MAX_NATIVE_PROGRAM_BYTES = 256 * 1024
        private const val MAX_APP_ARTIFACT_BYTES = 1024 * 1024
        private const val MAX_PREVIEW_BYTES = 1024 * 1024
        private const val MAX_SOURCE_BYTES = 512
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
                "riftpp/frontend/frontend.app2.arm64.r4.hex"
            } else {
                "riftpp/frontend/frontend.app2.arm32.r4.hex"
            }
        )
    }

    private val runtimeProgram: ByteArray by lazy {
        compileRecordProgram(
            if (android.os.Process.is64Bit()) {
                "riftpp/runtime/runtime.app2.arm64.r4.hex"
            } else {
                "riftpp/runtime/runtime.app2.arm32.r4.hex"
            }
        )
    }

    init {
        require(frozenCompiler.isNotEmpty()) {
            "frozen S3 compiler missing"
        }
    }

    fun compile(source: String): ByteArray {
        require(source.all { it.code <= 0x7f }) {
            "App v2 source must be canonical ASCII"
        }

        val sourceBytes = source.toByteArray(Charsets.US_ASCII)
        require(sourceBytes.size <= MAX_SOURCE_BYTES) {
            "App v2 source exceeds $MAX_SOURCE_BYTES bytes"
        }

        return bridge.run(
            frontendProgram,
            sourceBytes,
            MAX_APP_ARTIFACT_BYTES
        ) ?: error("Rift++ App v2 frontend rejected source")
    }

    fun render(
        artifact: ByteArray,
        eventKind: Int = 0,
        controlId: Int = 0
    ): ByteArray {
        require(artifact.size in 1..MAX_APP_ARTIFACT_BYTES) {
            "artifact size is out of bounds"
        }
        require(eventKind in 0..1) {
            "unsupported App v2 event kind"
        }
        require(controlId >= 0) {
            "invalid App v2 control id"
        }

        val envelope =
            ByteBuffer
                .allocate(16 + artifact.size)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("RPE2".toByteArray(Charsets.US_ASCII))
                .putInt(artifact.size)
                .putInt(eventKind)
                .putInt(controlId)
                .put(artifact)
                .array()

        return bridge.run(
            runtimeProgram,
            envelope,
            MAX_PREVIEW_BYTES
        ) ?: error("Rift++ App v2 runtime rejected artifact/event")
    }

    fun runtimeProgramForPackaging(): ByteArray =
        runtimeProgram.copyOf()

    private fun compileRecordProgram(assetPath: String): ByteArray {
        val recordSource = HexAssets.readLineHex(context, assetPath)
        return bridge.run(
            frozenCompiler,
            recordSource,
            MAX_NATIVE_PROGRAM_BYTES
        ) ?: error("frozen S3 compiler rejected " + assetPath)
    }
}
