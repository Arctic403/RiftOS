package com.riftpp.editor

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RiftppPipeline(
    private val context: Context
) {
    companion object {
        private const val MAX_NATIVE_PROGRAM_BYTES =
            1024 * 1024
        private const val MAX_NATIVE_INPUT_BYTES =
            4 * 1024 * 1024
        private const val MAX_NATIVE_OUTPUT_BYTES =
            4 * 1024 * 1024
        private const val MAX_S3_RECORD_SOURCE_BYTES =
            1024 * 1024
        private const val MAX_APP_ARTIFACT_BYTES =
            1024 * 1024
        private const val MAX_PREVIEW_BYTES =
            1024 * 1024
        private const val MAX_SOURCE_BYTES =
            512
        private const val MAX_PROJECT_SOURCE_BYTES =
            4096
    }

    private val bridge =
        RiftppNativeBridge()

    private val bootstrapCompiler: ByteArray by lazy {
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
        compileBundledRecordProgram(
            if (android.os.Process.is64Bit()) {
                "riftpp/frontend/frontend.app2.arm64.r4.hex"
            } else {
                "riftpp/frontend/frontend.app2.arm32.r4.hex"
            }
        )
    }

    private val projectFrontendProgram: ByteArray by lazy {
        compileBundledRecordProgram(
            if (android.os.Process.is64Bit()) {
                "riftpp/frontend/frontend.project1.arm64.r4.hex"
            } else {
                "riftpp/frontend/frontend.project1.arm32.r4.hex"
            }
        )
    }

    private val runtimeProgram: ByteArray by lazy {
        compileBundledRecordProgram(
            if (android.os.Process.is64Bit()) {
                "riftpp/runtime/runtime.app2.arm64.r4.hex"
            } else {
                "riftpp/runtime/runtime.app2.arm32.r4.hex"
            }
        )
    }

    init {
        require(
            bootstrapCompiler.isNotEmpty()
        ) {
            "S3 bootstrap compiler missing"
        }
    }

    fun compile(
        source: String
    ): ByteArray {
        require(
            source.all {
                it.code <= 0x7f
            }
        ) {
            "App v2 source must be canonical ASCII"
        }

        val sourceBytes =
            source.toByteArray(
                Charsets.US_ASCII
            )

        require(
            sourceBytes.size <=
                MAX_SOURCE_BYTES
        ) {
            "App v2 source exceeds $MAX_SOURCE_BYTES bytes"
        }

        return bridge.run(
            frontendProgram,
            sourceBytes,
            MAX_APP_ARTIFACT_BYTES
        ) ?: error(
            "Rift++ App v2 frontend rejected source"
        )
    }

    fun compileProject(
        sourceUnits: ByteArray
    ): ByteArray {
        require(
            sourceUnits.isNotEmpty()
        ) {
            "Rift++ project source is empty"
        }
        require(
            sourceUnits.size <=
                MAX_PROJECT_SOURCE_BYTES
        ) {
            "Rift++ project source exceeds $MAX_PROJECT_SOURCE_BYTES bytes"
        }
        require(
            sourceUnits.all {
                it.toInt() in 0..0x7f
            }
        ) {
            "Rift++ project source must be canonical ASCII"
        }

        return bridge.run(
            projectFrontendProgram,
            sourceUnits,
            MAX_APP_ARTIFACT_BYTES
        ) ?: error(
            "Rift++ project frontend rejected source units"
        )
    }

    fun render(
        artifact: ByteArray,
        eventKind: Int = 0,
        controlId: Int = 0
    ): ByteArray {
        require(
            artifact.size in
                1..MAX_APP_ARTIFACT_BYTES
        ) {
            "artifact size is out of bounds"
        }
        require(
            eventKind in 0..1
        ) {
            "unsupported App v2 event kind"
        }
        require(
            controlId >= 0
        ) {
            "invalid App v2 control id"
        }

        val envelope =
            ByteBuffer
                .allocate(
                    16 + artifact.size
                )
                .order(
                    ByteOrder.LITTLE_ENDIAN
                )
                .put(
                    "RPE2".toByteArray(
                        Charsets.US_ASCII
                    )
                )
                .putInt(
                    artifact.size
                )
                .putInt(
                    eventKind
                )
                .putInt(
                    controlId
                )
                .put(
                    artifact
                )
                .array()

        return bridge.run(
            runtimeProgram,
            envelope,
            MAX_PREVIEW_BYTES
        ) ?: error(
            "Rift++ App v2 runtime rejected artifact/event"
        )
    }

    fun runtimeProgramForPackaging(): ByteArray =
        runtimeProgram.copyOf()

    fun bootstrapCompilerForDevelopment(): ByteArray =
        bootstrapCompiler.copyOf()

    fun compileRecordHex(
        recordHex: String,
        compiler: ByteArray? = null
    ): ByteArray =
        compileRecordSource(
            HexAssets.decodeLineHex(
                recordHex
            ),
            compiler
        )

    fun compileRecordSource(
        recordSource: ByteArray,
        compiler: ByteArray? = null
    ): ByteArray {
        require(
            recordSource.isNotEmpty()
        ) {
            "S3 record source is empty"
        }
        require(
            recordSource.size <=
                MAX_S3_RECORD_SOURCE_BYTES
        ) {
            "S3 record source exceeds $MAX_S3_RECORD_SOURCE_BYTES bytes"
        }

        val authority =
            compiler
                ?: bootstrapCompiler

        require(
            authority.size in
                1..MAX_NATIVE_PROGRAM_BYTES
        ) {
            "S3 compiler program is out of bounds"
        }

        return bridge.run(
            authority,
            recordSource,
            MAX_NATIVE_PROGRAM_BYTES
        ) ?: error(
            "S3 compiler rejected record source"
        )
    }

    fun runNativeProgram(
        program: ByteArray,
        input: ByteArray,
        outputCapacity: Int
    ): ByteArray {
        require(
            program.size in
                1..MAX_NATIVE_PROGRAM_BYTES
        ) {
            "native program is out of bounds"
        }
        require(
            input.size <=
                MAX_NATIVE_INPUT_BYTES
        ) {
            "native input exceeds $MAX_NATIVE_INPUT_BYTES bytes"
        }
        require(
            outputCapacity in
                1..MAX_NATIVE_OUTPUT_BYTES
        ) {
            "native output capacity is out of bounds"
        }

        return bridge.run(
            program,
            input,
            outputCapacity
        ) ?: error(
            "native program rejected input"
        )
    }

    private fun compileBundledRecordProgram(
        assetPath: String
    ): ByteArray =
        compileRecordSource(
            HexAssets.readLineHex(
                context,
                assetPath
            )
        )
}
