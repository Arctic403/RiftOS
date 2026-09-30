package com.riftos.app

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.RemoteException
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Private crash-containment process for the approved Rift++ machine-code compiler seed.
 *
 * This service is transport/execution plumbing only. It must never parse Rift++ source or emit
 * target instructions. Compiler authority remains in the exact machine-code artifact supplied by
 * the Rift++ workspace and approved here by ABI-specific size + SHA-256 identity.
 */
class RiftppCompilerService : Service() {
    companion object {
        internal const val DESCRIPTOR = "com.riftos.app.RiftppCompilerService"
        internal const val TRANSACTION_PID = IBinder.FIRST_CALL_TRANSACTION
        internal const val TRANSACTION_COMPILE = IBinder.FIRST_CALL_TRANSACTION + 1
        internal const val TRANSACTION_COMPILE_PROOF = IBinder.FIRST_CALL_TRANSACTION + 2
        internal const val TRANSACTION_STAGE1_SELF_HOST = IBinder.FIRST_CALL_TRANSACTION + 3
        internal const val TRANSACTION_S2_BOOTSTRAP = IBinder.FIRST_CALL_TRANSACTION + 4
        internal const val TRANSACTION_S2_VECTORS = IBinder.FIRST_CALL_TRANSACTION + 5
        internal const val TRANSACTION_S2_SELF_HOST = IBinder.FIRST_CALL_TRANSACTION + 6
        internal const val TRANSACTION_S3_SELF_HOST = IBinder.FIRST_CALL_TRANSACTION + 7
        internal const val TRANSACTION_S3_EMIT = IBinder.FIRST_CALL_TRANSACTION + 8

        private const val COMPILER_BYTES = 276
        private const val STAGE1_ARM64_SOURCE_BYTES = 2342
        private const val STAGE1_ARM32_SOURCE_BYTES = 2357
        private const val STAGE1_ARM64_IMAGE_BYTES = 336
        private const val STAGE1_ARM32_IMAGE_BYTES = 340
        private const val STAGE1_ARM64_SOURCE_SHA256 =
            "e3415740508a3db18f53744a2b8c900a1349b898eeb186e6159d21b38c9829f5"
        private const val STAGE1_ARM32_SOURCE_SHA256 =
            "ed2fb30fbd7819bd3adc3c427835ed70d7bd3167ae731357fe10697afdd3eb59"
        private const val STAGE1_ARM64_IMAGE_SHA256 =
            "1d5a87efb088e68ef1cec2b80c49c2a484d5e83d2c327d8131ef81e18ca9556b"
        private const val STAGE1_ARM32_IMAGE_SHA256 =
            "7b11fae1b5ad0314a6fcf1a310c57e2c10b90cb8b5b14b40c010e89aa3c3c431"
        private const val S2_GENA_ARM32_SOURCE_BYTES = 22809
        private const val S2_GENA_ARM64_SOURCE_BYTES = 20230
        private const val S2_GENA_ARM32_IMAGE_BYTES = 3128
        private const val S2_GENA_ARM64_IMAGE_BYTES = 2884
        private const val S2_PROOF_SOURCE_BYTES = 24
        private const val S2_PROOF_OUTPUT_BYTES = 96
        private const val S2_VECTOR_SOURCE_BYTES = 560
        private const val S2_VECTOR_OUTPUT_BYTES = 2240
        private const val S2_GENA_ARM32_SOURCE_SHA256 =
            "602ea5053ad483a3a27e6239812e26afc6f641dd1affcabf54d92999f17665b9"
        private const val S2_GENA_ARM64_SOURCE_SHA256 =
            "d96060c42ffa7b1eec2cd01efbc046368d1f738a43e95f36ef5813394f5f805d"
        private const val S2_GENA_ARM32_IMAGE_SHA256 =
            "d8a725107677188fdde1b6926139eb0b2da0719afe4c0f717d2c7a880237f49c"
        private const val S2_GENA_ARM64_IMAGE_SHA256 =
            "f0e3c871b4765bd94d69d71a26ffdfbcc3eabe3e114de492681f9999892cdfa5"
        private const val S2_PROOF_ARM32_SOURCE_SHA256 =
            "acc620528c81b818f94d98631f442f51c61ecaa7de568d0e34dc3c9ccd4cf7a4"
        private const val S2_PROOF_ARM64_SOURCE_SHA256 =
            "7955a9a7042ba8852515a7750bd152aea5f892ff870796296554d44d9e1d74b0"
        private const val S2_VECTOR_ARM32_SOURCE_SHA256 =
            "cf33c59d52c504e3464e6e23e527ea22dc0e64117b82d3f4b5dcd3a0541ea412"
        private const val S2_VECTOR_ARM64_SOURCE_SHA256 =
            "3075cb2d91a3bf1411d1a0b63c1c38dd2f3482ae3f3b2ea146d35699156aa287"
        private const val S2_CANONICAL_COMPILER_SOURCE_BYTES = 11072
        private const val S2_SELF_HOST_IMAGE_BYTES = 44288
        private const val S2_CANONICAL_ARM32_SOURCE_SHA256 =
            "476266f86506ffcf3d036c5f9ae381067a898998051ed2d7d60798f544bfd2f8"
        private const val S2_CANONICAL_ARM64_SOURCE_SHA256 =
            "d2644b4cb4597bf32871749c9ea140c9d986592fc32477beff13ea03c00e3ca8"
        private const val S2_DIAGNOSTIC_ARM32_SOURCE_SHA256 =
            "404cfa2316ace6ab27e4c6606e7de39b52463ab03ffca38e2c422b8ba242ffa9"
        private const val S2_DIAGNOSTIC_ARM64_SOURCE_SHA256 =
            "3b12909dd4fa31e8bc61eccee335070402c3866df1a18267ebe2618e57cb6adc"
        private const val S2_GENERATION_C_ARM32_SHA256 =
            "13c691dcb1214d7a66ac8d931907a25d96ac12a42b9f52ba2a9b8dfa0d344aa2"
        private const val S2_GENERATION_C_ARM64_SHA256 =
            "cf9de173f31cb745a2d7afd32959d798b2ee76e78b0f0cd2775fa0bc6a137600"
        private const val S2_PROOF_ARM32_OUTPUT_SHA256 =
            "1f9ffbb7a94afcc37821d0686d6cc1c23c76258ae54eec0cbd97f85ccd09631f"
        private const val S2_PROOF_ARM64_OUTPUT_SHA256 =
            "6b99c0501765629c7752f361617bfe56350fb974e7fa790b7d7c5992e139b5c4"
        private const val S3_CANDIDATE_SOURCE_BYTES = 8256
        private const val S3_BOOTSTRAP_IMAGE_BYTES = 33024
        private const val S3_SELF_HOST_IMAGE_BYTES = 16528
        private const val S3_PROOF_OUTPUT_BYTES = 64
        private const val S3_CANDIDATE_ARM32_SOURCE_SHA256 =
            "3d869139d47b3d406f60554637252f7e7f87fabf144eed8051129c23d61e98bf"
        private const val S3_CANDIDATE_ARM64_SOURCE_SHA256 =
            "baf1c342ee3cf7d070aebea299390ad25db16f2a6a3c74a9909645593678ef46"
        private const val S3_GENERATION_A_ARM32_SHA256 =
            "3b5dc52a20f2f419f9f5f7d46aa68c5d985aac8695e8cc0291ab38bfde47640a"
        private const val S3_GENERATION_A_ARM64_SHA256 =
            "827dca521292971e043cd1aa7d62e09c217a78236c55b58c272234680871e024"
        private const val S3_GENERATION_C_ARM32_SHA256 =
            "4a9599bb01e1525d99230aa490ff3b48c28434381f1aa7af361e9a530baa54ac"
        private const val S3_GENERATION_C_ARM64_SHA256 =
            "5991695723b494805312bdb7c1f258d27f5eedefadae393afc45420ae46dc43d"
        private const val S3_PROOF_ARM32_OUTPUT_SHA256 =
            "b10f7caead3598873c03be2336578d170d07fda0197aa88606cd06ffa2601c15"
        private const val S3_PROOF_ARM64_OUTPUT_SHA256 =
            "859865bc0057100027cbfbe71561b3c6648d010a118f5737005cd1858bd90073"
        private const val MAX_SOURCE_BYTES = 4096
        private const val MAX_OUTPUT_BYTES = 4096
        private const val ARM64_SHA256 =
            "b1f33b940d2ac199f5e38c1c621cd8b27ed15dd3a60fcb85daad7b7154b2ee0c"
        private const val ARM32_SHA256 =
            "1725b5341e87a09943737130a945d8ee500492370da8ce648f696b331e118653"

        private val nativeLoadFailure: Throwable? =
            runCatching { System.loadLibrary("riftpp_compiler_host") }.exceptionOrNull()
    }

    private external fun nativeCompile(
        compiler: ByteArray,
        source: ByteArray,
        output: ByteArray,
        proveGeneratedPayload: Boolean
    ): LongArray

    private external fun nativeStage1SelfHost(
        seedCompiler: ByteArray,
        arm32Source: ByteArray,
        arm64Source: ByteArray,
        bootstrapArm32: ByteArray,
        bootstrapArm64: ByteArray,
        selfArm32: ByteArray,
        selfArm64: ByteArray
    ): LongArray

    private external fun nativeS2Bootstrap(
        seedCompiler: ByteArray,
        stage1Arm32Source: ByteArray,
        stage1Arm64Source: ByteArray,
        genAArm32Source: ByteArray,
        genAArm64Source: ByteArray,
        proofArm32Source: ByteArray,
        proofArm64Source: ByteArray,
        genAArm32: ByteArray,
        genAArm64: ByteArray,
        proofArm32: ByteArray,
        proofArm64: ByteArray
    ): LongArray

    private external fun nativeS2SelfHost(
        genACompiler: ByteArray,
        compilerArm32Source: ByteArray,
        compilerArm64Source: ByteArray,
        diagnosticSource: ByteArray,
        proofArm32Source: ByteArray,
        proofArm64Source: ByteArray,
        generationBArm32: ByteArray,
        generationBArm64: ByteArray,
        proofArm32: ByteArray,
        proofArm64: ByteArray
    ): LongArray

    private external fun nativeS3SelfHost(
        s2Compiler: ByteArray,
        compilerArm32Source: ByteArray,
        compilerArm64Source: ByteArray,
        proofArm32Source: ByteArray,
        proofArm64Source: ByteArray,
        generationAArm32: ByteArray,
        generationAArm64: ByteArray,
        generationCArm32: ByteArray,
        generationCArm64: ByteArray,
        proofArm32: ByteArray,
        proofArm64: ByteArray
    ): LongArray

    private external fun nativeS2Vectors(
        genACompiler: ByteArray,
        arm32Source: ByteArray,
        arm64Source: ByteArray,
        arm32Output: ByteArray,
        arm64Output: ByteArray
    ): IntArray

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return when (code) {
                IBinder.INTERFACE_TRANSACTION -> {
                    reply?.writeString(DESCRIPTOR)
                    true
                }
                TRANSACTION_PID -> {
                    data.enforceInterface(DESCRIPTOR)
                    reply?.writeNoException()
                    reply?.writeInt(Process.myPid())
                    true
                }
                TRANSACTION_COMPILE, TRANSACTION_COMPILE_PROOF -> {
                    data.enforceInterface(DESCRIPTOR)
                    val compiler = data.createByteArray()
                    val source = data.createByteArray()
                    val capacity = data.readInt()
                    val result = executeApproved(
                        compiler,
                        source,
                        capacity,
                        code == TRANSACTION_COMPILE_PROOF
                    )
                    reply?.writeNoException()
                    reply?.writeBundle(result)
                    true
                }
                TRANSACTION_STAGE1_SELF_HOST -> {
                    data.enforceInterface(DESCRIPTOR)
                    val compiler = data.createByteArray()
                    val arm32Source = data.createByteArray()
                    val arm64Source = data.createByteArray()
                    val result = executeStage1SelfHost(
                        compiler,
                        arm32Source,
                        arm64Source
                    )
                    reply?.writeNoException()
                    reply?.writeBundle(result)
                    true
                }
                TRANSACTION_S2_BOOTSTRAP -> {
                    data.enforceInterface(DESCRIPTOR)
                    val compiler = data.createByteArray()
                    val stage1Arm32Source = data.createByteArray()
                    val stage1Arm64Source = data.createByteArray()
                    val genAArm32Source = data.createByteArray()
                    val genAArm64Source = data.createByteArray()
                    val proofArm32Source = data.createByteArray()
                    val proofArm64Source = data.createByteArray()
                    val result = executeS2Bootstrap(
                        compiler,
                        stage1Arm32Source,
                        stage1Arm64Source,
                        genAArm32Source,
                        genAArm64Source,
                        proofArm32Source,
                        proofArm64Source
                    )
                    reply?.writeNoException()
                    reply?.writeBundle(result)
                    true
                }
                TRANSACTION_S2_VECTORS -> {
                    data.enforceInterface(DESCRIPTOR)
                    val genACompiler = data.createByteArray()
                    val arm32Source = data.createByteArray()
                    val arm64Source = data.createByteArray()
                    val result = executeS2Vectors(genACompiler, arm32Source, arm64Source)
                    reply?.writeNoException()
                    reply?.writeBundle(result)
                    true
                }
                TRANSACTION_S2_SELF_HOST -> {
                    data.enforceInterface(DESCRIPTOR)
                    val genACompiler = data.createByteArray()
                    val compilerArm32Source = data.createByteArray()
                    val compilerArm64Source = data.createByteArray()
                    val diagnosticSource = data.createByteArray()
                    val proofArm32Source = data.createByteArray()
                    val proofArm64Source = data.createByteArray()
                    val result = executeS2SelfHost(
                        genACompiler,
                        compilerArm32Source,
                        compilerArm64Source,
                        diagnosticSource,
                        proofArm32Source,
                        proofArm64Source
                    )
                    reply?.writeNoException()
                    reply?.writeBundle(result)
                    true
                }
                TRANSACTION_S3_SELF_HOST -> {
                    data.enforceInterface(DESCRIPTOR)
                    val s2Compiler = data.createByteArray()
                    val compilerArm32Source = data.createByteArray()
                    val compilerArm64Source = data.createByteArray()
                    val proofArm32Source = data.createByteArray()
                    val proofArm64Source = data.createByteArray()
                    val result = executeS3SelfHost(
                        s2Compiler,
                        compilerArm32Source,
                        compilerArm64Source,
                        proofArm32Source,
                        proofArm64Source
                    )
                    reply?.writeNoException()
                    reply?.writeBundle(result)
                    true
                }
                TRANSACTION_S3_EMIT -> {
                    data.enforceInterface(DESCRIPTOR)
                    val s3Compiler = data.createByteArray()
                    val entrySource = data.createByteArray()
                    val emitterSource = data.createByteArray()
                    val result = executeS3Emit(
                        s3Compiler,
                        entrySource,
                        emitterSource
                    )
                    reply?.writeNoException()
                    reply?.writeBundle(result)
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun executeApproved(
        compiler: ByteArray?,
        source: ByteArray?,
        outputCapacity: Int,
        proveGeneratedPayload: Boolean
    ): Bundle {
        val hostAbi = if (Process.is64Bit()) "arm64-v8a" else "armeabi-v7a"
        val expectedSha = if (Process.is64Bit()) ARM64_SHA256 else ARM32_SHA256
        val compilerBytes = compiler ?: return rejected(hostAbi, "compiler-missing")
        val sourceBytes = source ?: return rejected(hostAbi, "source-missing")

        if (compilerBytes.size != COMPILER_BYTES) {
            return rejected(hostAbi, "compiler-size")
        }
        val compilerSha = sha256(compilerBytes)
        if (compilerSha != expectedSha) {
            return rejected(hostAbi, "compiler-identity", compilerSha)
        }
        if (sourceBytes.size > MAX_SOURCE_BYTES) {
            return rejected(hostAbi, "source-bounds", compilerSha)
        }
        if (outputCapacity !in 1..MAX_OUTPUT_BYTES) {
            return rejected(hostAbi, "output-bounds", compilerSha)
        }
        nativeLoadFailure?.let {
            return rejected(hostAbi, "native-library", compilerSha, it.message)
        }

        val output = ByteArray(outputCapacity)
        val nativeResult = try {
            nativeCompile(compilerBytes, sourceBytes, output, proveGeneratedPayload)
        } catch (failure: Throwable) {
            return rejected(hostAbi, "native-call", compilerSha, failure.message)
        }
        if (nativeResult.size != 4) {
            return rejected(hostAbi, "native-envelope", compilerSha)
        }

        val hostStatus = nativeResult[0].toInt()
        val returnValue = nativeResult[1] and 0xffff_ffffL
        val generatedPayloadProofStatus = nativeResult[2].toInt()
        val generatedPayloadReturnValue = nativeResult[3] and 0xffff_ffffL
        if (hostStatus != 0) {
            return Bundle().apply {
                putString("status", "host-reject")
                putString("reason", "native-host-$hostStatus")
                putString("hostAbi", hostAbi)
                putInt("pid", Process.myPid())
                putString("compilerSha256", compilerSha)
                putInt("compilerBytes", compilerBytes.size)
                putString("sourceSha256", sha256(sourceBytes))
                putInt("sourceBytes", sourceBytes.size)
                putLong("returnValue", returnValue)
            }
        }

        if (returnValue == 0xffff_ffffL) {
            return Bundle().apply {
                putString("status", "compiler-reject")
                putString("hostAbi", hostAbi)
                putInt("pid", Process.myPid())
                putString("compilerSha256", compilerSha)
                putInt("compilerBytes", compilerBytes.size)
                putString("sourceSha256", sha256(sourceBytes))
                putInt("sourceBytes", sourceBytes.size)
                putLong("returnValue", returnValue)
            }
        }

        if (returnValue > outputCapacity.toLong()) {
            return rejected(hostAbi, "result-bounds", compilerSha)
        }
        if (proveGeneratedPayload && generatedPayloadProofStatus != 0) {
            return rejected(
                hostAbi,
                "generated-payload-host-$generatedPayloadProofStatus",
                compilerSha
            )
        }

        val exactOutput = output.copyOf(returnValue.toInt())
        return Bundle().apply {
            putString("status", "success")
            putString("hostAbi", hostAbi)
            putInt("pid", Process.myPid())
            putString("compilerSha256", compilerSha)
            putInt("compilerBytes", compilerBytes.size)
            putString("sourceSha256", sha256(sourceBytes))
            putInt("sourceBytes", sourceBytes.size)
            putLong("returnValue", returnValue)
            putByteArray("output", exactOutput)
            putString("outputSha256", sha256(exactOutput))
            putInt("outputBytes", exactOutput.size)
            putBoolean("generatedPayloadProofRequested", proveGeneratedPayload)
            if (proveGeneratedPayload) {
                putInt("generatedPayloadProofStatus", generatedPayloadProofStatus)
                putLong("generatedPayloadReturnValue", generatedPayloadReturnValue)
            }
        }
    }


    private fun executeStage1SelfHost(
        seedCompiler: ByteArray?,
        arm32Source: ByteArray?,
        arm64Source: ByteArray?
    ): Bundle {
        val hostAbi = if (Process.is64Bit()) "arm64-v8a" else "armeabi-v7a"
        val expectedSeedSha = if (Process.is64Bit()) ARM64_SHA256 else ARM32_SHA256
        val seed = seedCompiler ?: return rejected(hostAbi, "stage1-seed-missing")
        val source32 = arm32Source ?: return rejected(hostAbi, "stage1-arm32-source-missing")
        val source64 = arm64Source ?: return rejected(hostAbi, "stage1-arm64-source-missing")

        if (seed.size != COMPILER_BYTES) {
            return rejected(hostAbi, "stage1-seed-size")
        }
        val seedSha = sha256(seed)
        if (seedSha != expectedSeedSha) {
            return rejected(hostAbi, "stage1-seed-identity", seedSha)
        }
        if (
            source32.size != STAGE1_ARM32_SOURCE_BYTES ||
            sha256(source32) != STAGE1_ARM32_SOURCE_SHA256
        ) {
            return rejected(hostAbi, "stage1-arm32-source-identity", seedSha)
        }
        if (
            source64.size != STAGE1_ARM64_SOURCE_BYTES ||
            sha256(source64) != STAGE1_ARM64_SOURCE_SHA256
        ) {
            return rejected(hostAbi, "stage1-arm64-source-identity", seedSha)
        }
        nativeLoadFailure?.let {
            return rejected(hostAbi, "native-library", seedSha, it.message)
        }

        val bootstrap32 = ByteArray(STAGE1_ARM32_IMAGE_BYTES)
        val bootstrap64 = ByteArray(STAGE1_ARM64_IMAGE_BYTES)
        val self32 = ByteArray(STAGE1_ARM32_IMAGE_BYTES)
        val self64 = ByteArray(STAGE1_ARM64_IMAGE_BYTES)

        val nativeResult = try {
            nativeStage1SelfHost(
                seed,
                source32,
                source64,
                bootstrap32,
                bootstrap64,
                self32,
                self64
            )
        } catch (failure: Throwable) {
            return rejected(hostAbi, "stage1-native-call", seedSha, failure.message)
        }
        if (nativeResult.size != 5) {
            return rejected(hostAbi, "stage1-native-envelope", seedSha)
        }

        val hostStatus = nativeResult[0].toInt()
        val bootstrap32Bytes = nativeResult[1].toInt()
        val bootstrap64Bytes = nativeResult[2].toInt()
        val self32Bytes = nativeResult[3].toInt()
        val self64Bytes = nativeResult[4].toInt()
        if (hostStatus != 0) {
            return Bundle().apply {
                putString("status", "host-reject")
                putString("reason", "stage1-native-$hostStatus")
                putString("hostAbi", hostAbi)
                putInt("pid", Process.myPid())
                putString("compilerSha256", seedSha)
                putInt("bootstrapArm32Bytes", bootstrap32Bytes)
                putInt("bootstrapArm64Bytes", bootstrap64Bytes)
                putInt("selfArm32Bytes", self32Bytes)
                putInt("selfArm64Bytes", self64Bytes)
            }
        }

        if (
            bootstrap32Bytes != STAGE1_ARM32_IMAGE_BYTES ||
            bootstrap64Bytes != STAGE1_ARM64_IMAGE_BYTES ||
            self32Bytes != STAGE1_ARM32_IMAGE_BYTES ||
            self64Bytes != STAGE1_ARM64_IMAGE_BYTES
        ) {
            return rejected(hostAbi, "stage1-result-length", seedSha)
        }

        val bootstrap32Sha = sha256(bootstrap32)
        val bootstrap64Sha = sha256(bootstrap64)
        val self32Sha = sha256(self32)
        val self64Sha = sha256(self64)
        if (bootstrap32Sha != STAGE1_ARM32_IMAGE_SHA256) {
            return rejected(hostAbi, "stage1-bootstrap-arm32-identity", seedSha)
        }
        if (bootstrap64Sha != STAGE1_ARM64_IMAGE_SHA256) {
            return rejected(hostAbi, "stage1-bootstrap-arm64-identity", seedSha)
        }
        if (self32Sha != STAGE1_ARM32_IMAGE_SHA256) {
            return rejected(hostAbi, "stage1-self-arm32-identity", seedSha)
        }
        if (self64Sha != STAGE1_ARM64_IMAGE_SHA256) {
            return rejected(hostAbi, "stage1-self-arm64-identity", seedSha)
        }
        if (!bootstrap32.contentEquals(self32)) {
            return rejected(hostAbi, "stage1-self-arm32-byte-drift", seedSha)
        }
        if (!bootstrap64.contentEquals(self64)) {
            return rejected(hostAbi, "stage1-self-arm64-byte-drift", seedSha)
        }

        return Bundle().apply {
            putString("status", "success")
            putString("hostAbi", hostAbi)
            putInt("pid", Process.myPid())
            putString("compilerSha256", seedSha)
            putString("stage1Arm32SourceSha256", STAGE1_ARM32_SOURCE_SHA256)
            putString("stage1Arm64SourceSha256", STAGE1_ARM64_SOURCE_SHA256)
            putString("bootstrapArm32Sha256", bootstrap32Sha)
            putString("bootstrapArm64Sha256", bootstrap64Sha)
            putString("selfArm32Sha256", self32Sha)
            putString("selfArm64Sha256", self64Sha)
            putInt("bootstrapArm32Bytes", bootstrap32Bytes)
            putInt("bootstrapArm64Bytes", bootstrap64Bytes)
            putInt("selfArm32Bytes", self32Bytes)
            putInt("selfArm64Bytes", self64Bytes)
            putBoolean("selfHostedCurrentAbi", true)
            putBoolean("crossTargetReproduced", true)
            putBoolean("hostParsesStage1Numbers", false)
            putBoolean("hostEmitsStage1Instructions", false)
        }
    }



    private fun executeS2SelfHost(
        genACompiler: ByteArray?,
        compilerArm32Source: ByteArray?,
        compilerArm64Source: ByteArray?,
        diagnosticSource: ByteArray?,
        proofArm32Source: ByteArray?,
        proofArm64Source: ByteArray?
    ): Bundle {
        val hostAbi = if (Process.is64Bit()) "arm64-v8a" else "armeabi-v7a"
        val expectedGenABytes =
            if (Process.is64Bit()) S2_GENA_ARM64_IMAGE_BYTES else S2_GENA_ARM32_IMAGE_BYTES
        val expectedGenASha =
            if (Process.is64Bit()) S2_GENA_ARM64_IMAGE_SHA256 else S2_GENA_ARM32_IMAGE_SHA256

        val genA = genACompiler ?: return rejected(hostAbi, "s2-selfhost-gena-missing")
        val source32 =
            compilerArm32Source ?: return rejected(hostAbi, "s2-selfhost-arm32-source-missing")
        val source64 =
            compilerArm64Source ?: return rejected(hostAbi, "s2-selfhost-arm64-source-missing")
        val diagnostic =
            diagnosticSource ?: return rejected(hostAbi, "s2-selfhost-diagnostic-source-missing")
        val proof32Source =
            proofArm32Source ?: return rejected(hostAbi, "s2-selfhost-proof-arm32-missing")
        val proof64Source =
            proofArm64Source ?: return rejected(hostAbi, "s2-selfhost-proof-arm64-missing")

        if (genA.size != expectedGenABytes) {
            return rejected(hostAbi, "s2-selfhost-gena-size")
        }
        val genASha = sha256(genA)
        if (genASha != expectedGenASha) {
            return rejected(hostAbi, "s2-selfhost-gena-identity", genASha)
        }
        if (
            source32.size != S2_CANONICAL_COMPILER_SOURCE_BYTES ||
            sha256(source32) != S2_CANONICAL_ARM32_SOURCE_SHA256
        ) {
            return rejected(hostAbi, "s2-selfhost-arm32-source-identity", genASha)
        }
        if (
            source64.size != S2_CANONICAL_COMPILER_SOURCE_BYTES ||
            sha256(source64) != S2_CANONICAL_ARM64_SOURCE_SHA256
        ) {
            return rejected(hostAbi, "s2-selfhost-arm64-source-identity", genASha)
        }
        val expectedDiagnosticSha =
            if (Process.is64Bit()) S2_DIAGNOSTIC_ARM64_SOURCE_SHA256
            else S2_DIAGNOSTIC_ARM32_SOURCE_SHA256
        if (
            diagnostic.size != S2_CANONICAL_COMPILER_SOURCE_BYTES ||
            sha256(diagnostic) != expectedDiagnosticSha
        ) {
            return rejected(hostAbi, "s2-selfhost-diagnostic-source-identity", genASha)
        }
        if (
            proof32Source.size != S2_PROOF_SOURCE_BYTES ||
            sha256(proof32Source) != S2_PROOF_ARM32_SOURCE_SHA256
        ) {
            return rejected(hostAbi, "s2-selfhost-proof-arm32-identity", genASha)
        }
        if (
            proof64Source.size != S2_PROOF_SOURCE_BYTES ||
            sha256(proof64Source) != S2_PROOF_ARM64_SOURCE_SHA256
        ) {
            return rejected(hostAbi, "s2-selfhost-proof-arm64-identity", genASha)
        }
        nativeLoadFailure?.let {
            return rejected(hostAbi, "native-library", genASha, it.message)
        }

        val generationC32 = ByteArray(S2_SELF_HOST_IMAGE_BYTES)
        val generationC64 = ByteArray(S2_SELF_HOST_IMAGE_BYTES)
        val proof32 = ByteArray(S2_PROOF_OUTPUT_BYTES)
        val proof64 = ByteArray(S2_PROOF_OUTPUT_BYTES)

        val nativeResult = try {
            nativeS2SelfHost(
                genA,
                source32,
                source64,
                diagnostic,
                proof32Source,
                proof64Source,
                generationC32,
                generationC64,
                proof32,
                proof64
            )
        } catch (failure: Throwable) {
            return rejected(hostAbi, "s2-selfhost-native-call", genASha, failure.message)
        }
        if (nativeResult.size != 12) {
            return rejected(hostAbi, "s2-selfhost-native-envelope", genASha)
        }

        val hostStatus = nativeResult[0].toInt()
        val b32Bytes = nativeResult[1].toInt()
        val b64Bytes = nativeResult[2].toInt()
        val c32Bytes = nativeResult[3].toInt()
        val c64Bytes = nativeResult[4].toInt()
        val arm32CdFixedPoint = nativeResult[5] == 1L
        val arm64CdFixedPoint = nativeResult[6] == 1L
        val proof32Bytes = nativeResult[7].toInt()
        val proof64Bytes = nativeResult[8].toInt()
        val proofExecutionStatus = nativeResult[9].toInt()
        val proofReturnValue = nativeResult[10] and 0xffff_ffffL
        val diagnosticReturnValue = nativeResult[11] and 0xffff_ffffL

        if (hostStatus != 0) {
            return Bundle().apply {
                putString("status", "host-reject")
                putString("reason", "s2-selfhost-native-$hostStatus")
                putString("hostAbi", hostAbi)
                putInt("pid", Process.myPid())
                putString("genASha256", genASha)
                putString("diagnosticSourceSha256", expectedDiagnosticSha)
                putInt("generationBArm32Bytes", b32Bytes)
                putInt("generationBArm64Bytes", b64Bytes)
                putInt("generationCArm32Bytes", c32Bytes)
                putInt("generationCArm64Bytes", c64Bytes)
                putBoolean("generationCArm32EqualsD", arm32CdFixedPoint)
                putBoolean("generationCArm64EqualsD", arm64CdFixedPoint)
                putInt("proofArm32Bytes", proof32Bytes)
                putInt("proofArm64Bytes", proof64Bytes)
                putInt("proofExecutionStatus", proofExecutionStatus)
                putLong("proofReturnValue", proofReturnValue)
                putLong("diagnosticReturnValue", diagnosticReturnValue)
            }
        }

        if (
            b32Bytes != S2_SELF_HOST_IMAGE_BYTES ||
            b64Bytes != S2_SELF_HOST_IMAGE_BYTES ||
            c32Bytes != S2_SELF_HOST_IMAGE_BYTES ||
            c64Bytes != S2_SELF_HOST_IMAGE_BYTES ||
            !arm32CdFixedPoint ||
            !arm64CdFixedPoint ||
            proof32Bytes != S2_PROOF_OUTPUT_BYTES ||
            proof64Bytes != S2_PROOF_OUTPUT_BYTES ||
            proofExecutionStatus != 0 ||
            proofReturnValue != 42L
        ) {
            return rejected(hostAbi, "s2-selfhost-result", genASha)
        }

        val c32Sha = sha256(generationC32)
        val c64Sha = sha256(generationC64)
        if (c32Sha != S2_GENERATION_C_ARM32_SHA256) {
            return rejected(hostAbi, "s2-selfhost-generation-c-arm32-identity", genASha)
        }
        if (c64Sha != S2_GENERATION_C_ARM64_SHA256) {
            return rejected(hostAbi, "s2-selfhost-generation-c-arm64-identity", genASha)
        }

        val proof32Sha = sha256(proof32)
        val proof64Sha = sha256(proof64)
        if (proof32Sha != S2_PROOF_ARM32_OUTPUT_SHA256) {
            return rejected(hostAbi, "s2-selfhost-proof-arm32-output", genASha)
        }
        if (proof64Sha != S2_PROOF_ARM64_OUTPUT_SHA256) {
            return rejected(hostAbi, "s2-selfhost-proof-arm64-output", genASha)
        }

        return Bundle().apply {
            putString("status", "success")
            putString("hostAbi", hostAbi)
            putInt("pid", Process.myPid())
            putString("genASha256", genASha)
            putString("canonicalArm32SourceSha256", S2_CANONICAL_ARM32_SOURCE_SHA256)
            putString("canonicalArm64SourceSha256", S2_CANONICAL_ARM64_SOURCE_SHA256)
            putString("diagnosticSourceSha256", expectedDiagnosticSha)
            putString("generationCArm32Sha256", c32Sha)
            putString("generationCArm64Sha256", c64Sha)
            putString("proofArm32OutputSha256", proof32Sha)
            putString("proofArm64OutputSha256", proof64Sha)
            putInt("generationBArm32Bytes", b32Bytes)
            putInt("generationBArm64Bytes", b64Bytes)
            putInt("generationCArm32Bytes", c32Bytes)
            putInt("generationCArm64Bytes", c64Bytes)
            putInt("proofArm32Bytes", proof32Bytes)
            putInt("proofArm64Bytes", proof64Bytes)
            putInt("proofExecutionStatus", proofExecutionStatus)
            putLong("proofReturnValue", proofReturnValue)
            putLong("diagnosticReturnValue", diagnosticReturnValue)
            putBoolean("generationACompiledCanonicalCompiler", true)
            putBoolean("generationBCurrentAbiExecuted", true)
            putBoolean("generationBCompiledBothTargets", true)
            putBoolean("generationCCurrentAbiExecuted", true)
            putBoolean("generationCArm32EqualsD", true)
            putBoolean("generationCArm64EqualsD", true)
            putBoolean("generationCCompiledBothTargets", true)
            putBoolean("hostParsesS2Opcodes", false)
            putBoolean("hostEmitsS2Instructions", false)
        }
    }

    private fun executeS2Bootstrap(
        seedCompiler: ByteArray?,
        stage1Arm32Source: ByteArray?,
        stage1Arm64Source: ByteArray?,
        genAArm32Source: ByteArray?,
        genAArm64Source: ByteArray?,
        proofArm32Source: ByteArray?,
        proofArm64Source: ByteArray?
    ): Bundle {
        val hostAbi = if (Process.is64Bit()) "arm64-v8a" else "armeabi-v7a"
        val expectedSeedSha = if (Process.is64Bit()) ARM64_SHA256 else ARM32_SHA256
        val seed = seedCompiler ?: return rejected(hostAbi, "s2-seed-missing")
        val stage1Source32 =
            stage1Arm32Source ?: return rejected(hostAbi, "s2-stage1-arm32-source-missing")
        val stage1Source64 =
            stage1Arm64Source ?: return rejected(hostAbi, "s2-stage1-arm64-source-missing")
        val genASource32 =
            genAArm32Source ?: return rejected(hostAbi, "s2-gena-arm32-source-missing")
        val genASource64 =
            genAArm64Source ?: return rejected(hostAbi, "s2-gena-arm64-source-missing")
        val proofSource32 =
            proofArm32Source ?: return rejected(hostAbi, "s2-proof-arm32-source-missing")
        val proofSource64 =
            proofArm64Source ?: return rejected(hostAbi, "s2-proof-arm64-source-missing")

        if (seed.size != COMPILER_BYTES) {
            return rejected(hostAbi, "s2-seed-size")
        }
        val seedSha = sha256(seed)
        if (seedSha != expectedSeedSha) {
            return rejected(hostAbi, "s2-seed-identity", seedSha)
        }
        if (
            stage1Source32.size != STAGE1_ARM32_SOURCE_BYTES ||
            sha256(stage1Source32) != STAGE1_ARM32_SOURCE_SHA256
        ) {
            return rejected(hostAbi, "s2-stage1-arm32-source-identity", seedSha)
        }
        if (
            stage1Source64.size != STAGE1_ARM64_SOURCE_BYTES ||
            sha256(stage1Source64) != STAGE1_ARM64_SOURCE_SHA256
        ) {
            return rejected(hostAbi, "s2-stage1-arm64-source-identity", seedSha)
        }
        if (
            genASource32.size != S2_GENA_ARM32_SOURCE_BYTES ||
            sha256(genASource32) != S2_GENA_ARM32_SOURCE_SHA256
        ) {
            return rejected(hostAbi, "s2-gena-arm32-source-identity", seedSha)
        }
        if (
            genASource64.size != S2_GENA_ARM64_SOURCE_BYTES ||
            sha256(genASource64) != S2_GENA_ARM64_SOURCE_SHA256
        ) {
            return rejected(hostAbi, "s2-gena-arm64-source-identity", seedSha)
        }
        if (
            proofSource32.size != S2_PROOF_SOURCE_BYTES ||
            sha256(proofSource32) != S2_PROOF_ARM32_SOURCE_SHA256
        ) {
            return rejected(hostAbi, "s2-proof-arm32-source-identity", seedSha)
        }
        if (
            proofSource64.size != S2_PROOF_SOURCE_BYTES ||
            sha256(proofSource64) != S2_PROOF_ARM64_SOURCE_SHA256
        ) {
            return rejected(hostAbi, "s2-proof-arm64-source-identity", seedSha)
        }
        nativeLoadFailure?.let {
            return rejected(hostAbi, "native-library", seedSha, it.message)
        }

        val genA32 = ByteArray(S2_GENA_ARM32_IMAGE_BYTES)
        val genA64 = ByteArray(S2_GENA_ARM64_IMAGE_BYTES)
        val proof32 = ByteArray(S2_PROOF_OUTPUT_BYTES)
        val proof64 = ByteArray(S2_PROOF_OUTPUT_BYTES)

        val nativeResult = try {
            nativeS2Bootstrap(
                seed,
                stage1Source32,
                stage1Source64,
                genASource32,
                genASource64,
                proofSource32,
                proofSource64,
                genA32,
                genA64,
                proof32,
                proof64
            )
        } catch (failure: Throwable) {
            return rejected(hostAbi, "s2-native-call", seedSha, failure.message)
        }
        if (nativeResult.size != 7) {
            return rejected(hostAbi, "s2-native-envelope", seedSha)
        }

        val hostStatus = nativeResult[0].toInt()
        val genA32Bytes = nativeResult[1].toInt()
        val genA64Bytes = nativeResult[2].toInt()
        val proof32Bytes = nativeResult[3].toInt()
        val proof64Bytes = nativeResult[4].toInt()
        val proofExecutionStatus = nativeResult[5].toInt()
        val proofReturnValue = nativeResult[6] and 0xffff_ffffL

        if (hostStatus != 0) {
            return Bundle().apply {
                putString("status", "host-reject")
                putString("reason", "s2-native-$hostStatus")
                putString("hostAbi", hostAbi)
                putInt("pid", Process.myPid())
                putString("compilerSha256", seedSha)
                putInt("genAArm32Bytes", genA32Bytes)
                putInt("genAArm64Bytes", genA64Bytes)
                putInt("proofArm32Bytes", proof32Bytes)
                putInt("proofArm64Bytes", proof64Bytes)
                putInt("proofExecutionStatus", proofExecutionStatus)
                putLong("proofReturnValue", proofReturnValue)
            }
        }

        if (
            genA32Bytes != S2_GENA_ARM32_IMAGE_BYTES ||
            genA64Bytes != S2_GENA_ARM64_IMAGE_BYTES ||
            proof32Bytes != S2_PROOF_OUTPUT_BYTES ||
            proof64Bytes != S2_PROOF_OUTPUT_BYTES
        ) {
            return rejected(hostAbi, "s2-result-length", seedSha)
        }

        val genA32Sha = sha256(genA32)
        val genA64Sha = sha256(genA64)
        if (genA32Sha != S2_GENA_ARM32_IMAGE_SHA256) {
            return rejected(hostAbi, "s2-gena-arm32-image-identity", seedSha)
        }
        if (genA64Sha != S2_GENA_ARM64_IMAGE_SHA256) {
            return rejected(hostAbi, "s2-gena-arm64-image-identity", seedSha)
        }
        if (proofExecutionStatus != 0 || proofReturnValue != 42L) {
            return rejected(hostAbi, "s2-proof-execution", seedSha)
        }

        return Bundle().apply {
            putString("status", "success")
            putString("hostAbi", hostAbi)
            putInt("pid", Process.myPid())
            putString("compilerSha256", seedSha)
            putString("stage1Arm32SourceSha256", STAGE1_ARM32_SOURCE_SHA256)
            putString("stage1Arm64SourceSha256", STAGE1_ARM64_SOURCE_SHA256)
            putString("genAArm32SourceSha256", S2_GENA_ARM32_SOURCE_SHA256)
            putString("genAArm64SourceSha256", S2_GENA_ARM64_SOURCE_SHA256)
            putString("genAArm32Sha256", genA32Sha)
            putString("genAArm64Sha256", genA64Sha)
            putString("proofArm32SourceSha256", S2_PROOF_ARM32_SOURCE_SHA256)
            putString("proofArm64SourceSha256", S2_PROOF_ARM64_SOURCE_SHA256)
            putString("proofArm32OutputSha256", sha256(proof32))
            putString("proofArm64OutputSha256", sha256(proof64))
            putInt("genAArm32Bytes", genA32Bytes)
            putInt("genAArm64Bytes", genA64Bytes)
            putInt("proofArm32Bytes", proof32Bytes)
            putInt("proofArm64Bytes", proof64Bytes)
            putInt("proofExecutionStatus", proofExecutionStatus)
            putLong("proofReturnValue", proofReturnValue)
            putBoolean("stage1ManufacturedGenerationA", true)
            putBoolean("generationACompiledBothTargets", true)
            putBoolean("hostParsesS2Opcodes", false)
            putBoolean("hostEmitsS2Instructions", false)
        }
    }


    private fun executeS3SelfHost(
        s2Compiler: ByteArray?,
        compilerArm32Source: ByteArray?,
        compilerArm64Source: ByteArray?,
        proofArm32Source: ByteArray?,
        proofArm64Source: ByteArray?
    ): Bundle {
        val hostAbi = if (Process.is64Bit()) "arm64-v8a" else "armeabi-v7a"
        val expectedS2Sha =
            if (Process.is64Bit()) S2_GENERATION_C_ARM64_SHA256 else S2_GENERATION_C_ARM32_SHA256

        val promotedS2 =
            s2Compiler ?: return rejected(hostAbi, "s3-selfhost-s2-compiler-missing")
        val source32 =
            compilerArm32Source ?: return rejected(hostAbi, "s3-selfhost-arm32-source-missing")
        val source64 =
            compilerArm64Source ?: return rejected(hostAbi, "s3-selfhost-arm64-source-missing")
        val proof32Source =
            proofArm32Source ?: return rejected(hostAbi, "s3-selfhost-proof-arm32-missing")
        val proof64Source =
            proofArm64Source ?: return rejected(hostAbi, "s3-selfhost-proof-arm64-missing")

        if (promotedS2.size != S2_SELF_HOST_IMAGE_BYTES) {
            return rejected(hostAbi, "s3-selfhost-s2-compiler-size")
        }
        val s2Sha = sha256(promotedS2)
        if (s2Sha != expectedS2Sha) {
            return rejected(hostAbi, "s3-selfhost-s2-compiler-identity", s2Sha)
        }
        if (
            source32.size != S3_CANDIDATE_SOURCE_BYTES ||
            sha256(source32) != S3_CANDIDATE_ARM32_SOURCE_SHA256
        ) {
            return rejected(hostAbi, "s3-selfhost-arm32-source-identity", s2Sha)
        }
        if (
            source64.size != S3_CANDIDATE_SOURCE_BYTES ||
            sha256(source64) != S3_CANDIDATE_ARM64_SOURCE_SHA256
        ) {
            return rejected(hostAbi, "s3-selfhost-arm64-source-identity", s2Sha)
        }
        if (
            proof32Source.size != S2_PROOF_SOURCE_BYTES ||
            sha256(proof32Source) != S2_PROOF_ARM32_SOURCE_SHA256
        ) {
            return rejected(hostAbi, "s3-selfhost-proof-arm32-source-identity", s2Sha)
        }
        if (
            proof64Source.size != S2_PROOF_SOURCE_BYTES ||
            sha256(proof64Source) != S2_PROOF_ARM64_SOURCE_SHA256
        ) {
            return rejected(hostAbi, "s3-selfhost-proof-arm64-source-identity", s2Sha)
        }
        nativeLoadFailure?.let {
            return rejected(hostAbi, "native-library", s2Sha, it.message)
        }

        val generationA32 = ByteArray(S3_BOOTSTRAP_IMAGE_BYTES)
        val generationA64 = ByteArray(S3_BOOTSTRAP_IMAGE_BYTES)
        val generationC32 = ByteArray(S3_SELF_HOST_IMAGE_BYTES)
        val generationC64 = ByteArray(S3_SELF_HOST_IMAGE_BYTES)
        val proof32 = ByteArray(S3_PROOF_OUTPUT_BYTES)
        val proof64 = ByteArray(S3_PROOF_OUTPUT_BYTES)

        val nativeResult = try {
            nativeS3SelfHost(
                promotedS2,
                source32,
                source64,
                proof32Source,
                proof64Source,
                generationA32,
                generationA64,
                generationC32,
                generationC64,
                proof32,
                proof64
            )
        } catch (failure: Throwable) {
            return rejected(hostAbi, "s3-selfhost-native-call", s2Sha, failure.message)
        }
        if (nativeResult.size < 13) {
            return rejected(hostAbi, "s3-selfhost-native-result", s2Sha)
        }

        val hostStatus = nativeResult[0].toInt()
        val generationA32Bytes = nativeResult[1].toInt()
        val generationA64Bytes = nativeResult[2].toInt()
        val generationB32Bytes = nativeResult[3].toInt()
        val generationB64Bytes = nativeResult[4].toInt()
        val generationC32Bytes = nativeResult[5].toInt()
        val generationC64Bytes = nativeResult[6].toInt()
        val arm32FixedPoint = nativeResult[7] == 1L
        val arm64FixedPoint = nativeResult[8] == 1L
        val proof32Bytes = nativeResult[9].toInt()
        val proof64Bytes = nativeResult[10].toInt()
        val proofExecutionStatus = nativeResult[11].toInt()
        val proofReturnValue = nativeResult[12].toInt()

        if (hostStatus != 0) {
            return Bundle().apply {
                putString("status", "host-reject")
                putString("reason", "s3-selfhost-native-$hostStatus")
                putString("hostAbi", hostAbi)
                putInt("pid", Process.myPid())
                putString("promotedS2CompilerSha256", s2Sha)
                putInt("generationAArm32Bytes", generationA32Bytes)
                putInt("generationAArm64Bytes", generationA64Bytes)
                putInt("generationBArm32Bytes", generationB32Bytes)
                putInt("generationBArm64Bytes", generationB64Bytes)
                putInt("generationCArm32Bytes", generationC32Bytes)
                putInt("generationCArm64Bytes", generationC64Bytes)
                putBoolean("generationBArm64EqualsC", arm64FixedPoint)
                putInt("proofArm32Bytes", proof32Bytes)
                putInt("proofArm64Bytes", proof64Bytes)
                putInt("proofExecutionStatus", proofExecutionStatus)
                putInt("proofReturnValue", proofReturnValue)
            }
        }

        if (
            generationA32Bytes != S3_BOOTSTRAP_IMAGE_BYTES ||
            generationA64Bytes != S3_BOOTSTRAP_IMAGE_BYTES ||
            generationB32Bytes != S3_SELF_HOST_IMAGE_BYTES ||
            generationB64Bytes != S3_SELF_HOST_IMAGE_BYTES ||
            generationC32Bytes != S3_SELF_HOST_IMAGE_BYTES ||
            generationC64Bytes != S3_SELF_HOST_IMAGE_BYTES ||
            proof32Bytes != S3_PROOF_OUTPUT_BYTES ||
            proof64Bytes != S3_PROOF_OUTPUT_BYTES
        ) {
            return rejected(hostAbi, "s3-selfhost-result-length", s2Sha)
        }
        if (!arm32FixedPoint || !arm64FixedPoint) {
            return rejected(hostAbi, "s3-selfhost-fixed-point", s2Sha)
        }

        val generationA32Sha = sha256(generationA32)
        val generationA64Sha = sha256(generationA64)
        val generationC32Sha = sha256(generationC32)
        val generationC64Sha = sha256(generationC64)
        val proof32Sha = sha256(proof32)
        val proof64Sha = sha256(proof64)

        if (
            generationA32Sha != S3_GENERATION_A_ARM32_SHA256 ||
            generationA64Sha != S3_GENERATION_A_ARM64_SHA256
        ) {
            return rejected(hostAbi, "s3-selfhost-generation-a-identity", s2Sha)
        }
        if (
            generationC32Sha != S3_GENERATION_C_ARM32_SHA256 ||
            generationC64Sha != S3_GENERATION_C_ARM64_SHA256
        ) {
            return rejected(hostAbi, "s3-selfhost-generation-c-identity", s2Sha)
        }
        if (
            proof32Sha != S3_PROOF_ARM32_OUTPUT_SHA256 ||
            proof64Sha != S3_PROOF_ARM64_OUTPUT_SHA256
        ) {
            return rejected(hostAbi, "s3-selfhost-proof-identity", s2Sha)
        }
        if (proofExecutionStatus != 0 || proofReturnValue != 42) {
            return rejected(hostAbi, "s3-selfhost-proof-execution", s2Sha)
        }

        return Bundle().apply {
            putString("status", "success")
            putString("hostAbi", hostAbi)
            putInt("pid", Process.myPid())
            putString("promotedS2CompilerSha256", s2Sha)
            putString("candidateArm32SourceSha256", S3_CANDIDATE_ARM32_SOURCE_SHA256)
            putString("candidateArm64SourceSha256", S3_CANDIDATE_ARM64_SOURCE_SHA256)
            putString("generationAArm32Sha256", generationA32Sha)
            putString("generationAArm64Sha256", generationA64Sha)
            putString("generationCArm32Sha256", generationC32Sha)
            putString("generationCArm64Sha256", generationC64Sha)
            putString("proofArm32OutputSha256", proof32Sha)
            putString("proofArm64OutputSha256", proof64Sha)
            putInt("generationAArm32Bytes", generationA32Bytes)
            putInt("generationAArm64Bytes", generationA64Bytes)
            putInt("generationBArm32Bytes", generationB32Bytes)
            putInt("generationBArm64Bytes", generationB64Bytes)
            putInt("generationCArm32Bytes", generationC32Bytes)
            putInt("generationCArm64Bytes", generationC64Bytes)
            putBoolean("generationBArm64EqualsC", true)
            putInt("proofArm32Bytes", proof32Bytes)
            putInt("proofArm64Bytes", proof64Bytes)
            putInt("proofExecutionStatus", proofExecutionStatus)
            putInt("proofReturnValue", proofReturnValue)
            putBoolean("generationACurrentAbiExecuted", true)
            putBoolean("generationACompiledBothTargets", true)
            putBoolean("generationBCurrentAbiExecuted", true)
            putBoolean("generationBCompiledBothTargets", true)
            putBoolean("hostParsesS3Opcodes", false)
            putBoolean("hostEmitsS3Instructions", false)
        }
    }

    private fun executeS2Vectors(
        genACompiler: ByteArray?,
        arm32Source: ByteArray?,
        arm64Source: ByteArray?
    ): Bundle {
        val hostAbi = if (Process.is64Bit()) "arm64-v8a" else "armeabi-v7a"
        val expectedCompilerBytes =
            if (Process.is64Bit()) S2_GENA_ARM64_IMAGE_BYTES else S2_GENA_ARM32_IMAGE_BYTES
        val expectedCompilerSha =
            if (Process.is64Bit()) S2_GENA_ARM64_IMAGE_SHA256 else S2_GENA_ARM32_IMAGE_SHA256
        val compiler = genACompiler ?: return rejected(hostAbi, "s2-vectors-compiler-missing")
        val source32 = arm32Source ?: return rejected(hostAbi, "s2-vectors-arm32-source-missing")
        val source64 = arm64Source ?: return rejected(hostAbi, "s2-vectors-arm64-source-missing")

        if (compiler.size != expectedCompilerBytes || sha256(compiler) != expectedCompilerSha) {
            return rejected(hostAbi, "s2-vectors-compiler-identity")
        }
        if (
            source32.size != S2_VECTOR_SOURCE_BYTES ||
            sha256(source32) != S2_VECTOR_ARM32_SOURCE_SHA256
        ) {
            return rejected(hostAbi, "s2-vectors-arm32-source-identity", expectedCompilerSha)
        }
        if (
            source64.size != S2_VECTOR_SOURCE_BYTES ||
            sha256(source64) != S2_VECTOR_ARM64_SOURCE_SHA256
        ) {
            return rejected(hostAbi, "s2-vectors-arm64-source-identity", expectedCompilerSha)
        }
        nativeLoadFailure?.let {
            return rejected(hostAbi, "native-library", expectedCompilerSha, it.message)
        }

        val output32 = ByteArray(S2_VECTOR_OUTPUT_BYTES)
        val output64 = ByteArray(S2_VECTOR_OUTPUT_BYTES)
        val nativeResult = try {
            nativeS2Vectors(compiler, source32, source64, output32, output64)
        } catch (failure: Throwable) {
            return rejected(hostAbi, "s2-vectors-native-call", expectedCompilerSha, failure.message)
        }
        if (nativeResult.size != 3) {
            return rejected(hostAbi, "s2-vectors-native-envelope", expectedCompilerSha)
        }
        if (
            nativeResult[0] != 0 ||
            nativeResult[1] != S2_VECTOR_OUTPUT_BYTES ||
            nativeResult[2] != S2_VECTOR_OUTPUT_BYTES
        ) {
            return rejected(
                hostAbi,
                "s2-vectors-native-${nativeResult[0]}-${nativeResult[1]}-${nativeResult[2]}",
                expectedCompilerSha
            )
        }

        return Bundle().apply {
            putString("status", "success")
            putString("hostAbi", hostAbi)
            putInt("pid", Process.myPid())
            putString("genACompilerSha256", expectedCompilerSha)
            putString("arm32SourceSha256", S2_VECTOR_ARM32_SOURCE_SHA256)
            putString("arm64SourceSha256", S2_VECTOR_ARM64_SOURCE_SHA256)
            putString("arm32OutputSha256", sha256(output32))
            putString("arm64OutputSha256", sha256(output64))
            putString("arm32OutputHex", output32.joinToString("") { "%02x".format(it) })
            putString("arm64OutputHex", output64.joinToString("") { "%02x".format(it) })
            putInt("arm32OutputBytes", output32.size)
            putInt("arm64OutputBytes", output64.size)
            putBoolean("hostParsesS2Opcodes", false)
            putBoolean("hostEmitsS2Instructions", false)
            putBoolean("outputsExecuted", false)
        }
    }

    private fun executeS3Emit(
        s3Compiler: ByteArray?,
        entrySource: ByteArray?,
        emitterSource: ByteArray?
    ): Bundle {
        val hostAbi =
            if (Process.is64Bit()) {
                "arm64-v8a"
            } else {
                "armeabi-v7a"
            }

        if (Process.is64Bit()) {
            return rejected(
                hostAbi,
                "s3-emit-r1-arm32-host-required"
            )
        }

        val compilerBytes =
            s3Compiler
                ?: return rejected(
                    hostAbi,
                    "s3-emit-compiler-missing"
                )
        val entryBytes =
            entrySource
                ?: return rejected(
                    hostAbi,
                    "s3-emit-entry-source-missing"
                )
        val emitterBytes =
            emitterSource
                ?: return rejected(
                    hostAbi,
                    "s3-emit-emitter-source-missing"
                )

        if (
            compilerBytes.size !=
                S3_SELF_HOST_IMAGE_BYTES
        ) {
            return rejected(
                hostAbi,
                "s3-emit-compiler-size"
            )
        }

        val compilerSha =
            sha256(compilerBytes)

        if (
            compilerSha !=
                S3_GENERATION_C_ARM32_SHA256
        ) {
            return rejected(
                hostAbi,
                "s3-emit-compiler-identity",
                compilerSha
            )
        }

        if (
            entryBytes.size != 976
        ) {
            return rejected(
                hostAbi,
                "s3-emit-entry-source-size",
                compilerSha
            )
        }

        if (
            emitterBytes.size != 1144
        ) {
            return rejected(
                hostAbi,
                "s3-emit-emitter-source-size",
                compilerSha
            )
        }

        nativeLoadFailure?.let {
            return rejected(
                hostAbi,
                "s3-emit-native-library",
                compilerSha,
                it.message
            )
        }

        fun run(
            compiler: ByteArray,
            source: ByteArray,
            output: ByteArray,
            expectedBytes: Int,
            stage: String
        ): String? {
            val nativeResult =
                try {
                    nativeCompile(
                        compiler,
                        source,
                        output,
                        false
                    )
                } catch (
                    failure: Throwable
                ) {
                    return "$stage-native-call:" +
                        (
                            failure.message
                                ?: failure.javaClass
                                    .simpleName
                            )
                }

            if (nativeResult.size != 4) {
                return "$stage-native-envelope"
            }

            val hostStatus =
                nativeResult[0].toInt()
            val returnValue =
                nativeResult[1] and
                    0xffff_ffffL

            if (hostStatus != 0) {
                return "$stage-native-$hostStatus"
            }

            if (
                returnValue !=
                    expectedBytes.toLong()
            ) {
                return "$stage-result-$returnValue"
            }

            return null
        }

        val entryEmitterOutput =
            ByteArray(1968)

        run(
            compilerBytes,
            entryBytes,
            entryEmitterOutput,
            1968,
            "entry-emitter"
        )?.let {
            return rejected(
                hostAbi,
                "s3-emit-$it",
                compilerSha
            )
        }

        val entryOutput =
            ByteArray(228)

        run(
            entryEmitterOutput,
            ByteArray(0),
            entryOutput,
            228,
            "entry"
        )?.let {
            return rejected(
                hostAbi,
                "s3-emit-$it",
                compilerSha
            )
        }

        val emitterOutput =
            ByteArray(2304)

        run(
            compilerBytes,
            emitterBytes,
            emitterOutput,
            2304,
            "elf-emitter"
        )?.let {
            return rejected(
                hostAbi,
                "s3-emit-$it",
                compilerSha
            )
        }

        val elfOutput =
            ByteArray(644)

        run(
            emitterOutput,
            entryOutput,
            elfOutput,
            644,
            "elf"
        )?.let {
            return rejected(
                hostAbi,
                "s3-emit-$it",
                compilerSha
            )
        }

        return Bundle().apply {
            putString(
                "status",
                "success"
            )
            putString(
                "schema",
                "rift.riftpp-s3-android-r1/1"
            )
            putString(
                "hostAbi",
                hostAbi
            )
            putInt(
                "pid",
                Process.myPid()
            )
            putString(
                "compilerSha256",
                compilerSha
            )
            putInt(
                "compilerBytes",
                compilerBytes.size
            )
            putString(
                "entrySourceSha256",
                sha256(entryBytes)
            )
            putString(
                "emitterSourceSha256",
                sha256(emitterBytes)
            )
            putString(
                "entryEmitterOutputSha256",
                sha256(entryEmitterOutput)
            )
            putString(
                "entryOutputSha256",
                sha256(entryOutput)
            )
            putString(
                "emitterOutputSha256",
                sha256(emitterOutput)
            )
            putString(
                "elfSha256",
                sha256(elfOutput)
            )
            putInt(
                "entryEmitterOutputBytes",
                entryEmitterOutput.size
            )
            putInt(
                "entryOutputBytes",
                entryOutput.size
            )
            putInt(
                "emitterOutputBytes",
                emitterOutput.size
            )
            putInt(
                "elfBytes",
                elfOutput.size
            )
            putByteArray(
                "elf",
                elfOutput
            )
            putBoolean(
                "hostParsesS3Opcodes",
                false
            )
            putBoolean(
                "hostEmitsS3Instructions",
                false
            )
            putBoolean(
                "hostParsesElf",
                false
            )
            putBoolean(
                "hostEmitsElf",
                false
            )
        }
    }


    private fun rejected(
        hostAbi: String,
        reason: String,
        compilerSha: String? = null,
        detail: String? = null
    ): Bundle = Bundle().apply {
        putString("status", "host-reject")
        putString("reason", reason)
        putString("hostAbi", hostAbi)
        putInt("pid", Process.myPid())
        compilerSha?.let { putString("compilerSha256", it) }
        detail?.take(512)?.let { putString("detail", it) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}

/**
 * Main-process client. The compiler executes synchronously inside :riftppCompiler, while this side
 * owns binding, timeout, worker-process kill-on-timeout, and crash classification.
 */
internal object RiftppCompilerClient {
    private const val BIND_TIMEOUT_MS = 2_000L
    private const val EXECUTION_TIMEOUT_MS = 3_000L
    private const val STAGE1_EXECUTION_TIMEOUT_MS = 15_000L
    private const val S2_BOOTSTRAP_TIMEOUT_MS = 15_000L
    private const val S2_SELF_HOST_TIMEOUT_MS = 30_000L

    fun execute(
        context: Context,
        compiler: ByteArray,
        source: ByteArray,
        outputCapacity: Int,
        proveGeneratedPayload: Boolean = false
    ): JSONObject {
        val appContext = context.applicationContext
        val binderReady = CompletableFuture<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null) {
                    binderReady.completeExceptionally(RemoteException("null compiler binder"))
                } else {
                    binderReady.complete(service)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (!binderReady.isDone) {
                    binderReady.completeExceptionally(DeadObjectException())
                }
            }

            override fun onBindingDied(name: ComponentName?) {
                if (!binderReady.isDone) {
                    binderReady.completeExceptionally(DeadObjectException())
                }
            }

            override fun onNullBinding(name: ComponentName?) {
                if (!binderReady.isDone) {
                    binderReady.completeExceptionally(RemoteException("null compiler binding"))
                }
            }
        }

        val intent = Intent(appContext, RiftppCompilerService::class.java)
        if (!appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            return failure("host-reject", "bind-failed")
        }

        val executor = Executors.newSingleThreadExecutor()
        var workerPid = -1
        try {
            val binder = binderReady.get(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            workerPid = queryPid(binder)
            val future = executor.submit<Bundle> {
                transactCompile(
                    binder,
                    compiler,
                    source,
                    outputCapacity,
                    proveGeneratedPayload
                )
            }

            val bundle = try {
                future.get(EXECUTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (timeout: TimeoutException) {
                if (workerPid > 0) Process.killProcess(workerPid)
                return failure("timeout", "compiler-timeout", workerPid)
            } catch (failure: ExecutionException) {
                val cause = failure.cause
                return if (cause is DeadObjectException || cause is RemoteException) {
                    failure("crash", "compiler-process-died", workerPid)
                } else {
                    failure("host-reject", "binder-execution", workerPid, cause?.message)
                }
            }

            return bundleToJson(bundle)
        } catch (timeout: TimeoutException) {
            if (workerPid > 0) Process.killProcess(workerPid)
            return failure("timeout", "bind-timeout", workerPid)
        } catch (failure: DeadObjectException) {
            return failure("crash", "compiler-process-died", workerPid)
        } catch (failure: Throwable) {
            return failure("host-reject", "binder-transport", workerPid, failure.message)
        } finally {
            runCatching { appContext.unbindService(connection) }
            executor.shutdownNow()
        }
    }


    fun executeS3Emit(
        context: Context,
        s3Compiler: ByteArray,
        entrySource: ByteArray,
        emitterSource: ByteArray
    ): JSONObject {
        val appContext =
            context.applicationContext
        val binderReady =
            CompletableFuture<IBinder>()

        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName?,
                    service: IBinder?
                ) {
                    if (service == null) {
                        binderReady
                            .completeExceptionally(
                                RemoteException(
                                    "null compiler binder"
                                )
                            )
                    } else {
                        binderReady
                            .complete(service)
                    }
                }

                override fun onServiceDisconnected(
                    name: ComponentName?
                ) {
                    if (!binderReady.isDone) {
                        binderReady
                            .completeExceptionally(
                                DeadObjectException()
                            )
                    }
                }

                override fun onBindingDied(
                    name: ComponentName?
                ) {
                    if (!binderReady.isDone) {
                        binderReady
                            .completeExceptionally(
                                DeadObjectException()
                            )
                    }
                }

                override fun onNullBinding(
                    name: ComponentName?
                ) {
                    if (!binderReady.isDone) {
                        binderReady
                            .completeExceptionally(
                                RemoteException(
                                    "null compiler binding"
                                )
                            )
                    }
                }
            }

        val intent =
            Intent(
                appContext,
                RiftppCompilerService::class.java
            )

        if (
            !appContext.bindService(
                intent,
                connection,
                Context.BIND_AUTO_CREATE
            )
        ) {
            return failure(
                "host-reject",
                "s3-emit-bind-failed"
            )
        }

        val executor =
            Executors
                .newSingleThreadExecutor()
        var workerPid = -1

        try {
            val binder =
                binderReady.get(
                    BIND_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS
                )
            workerPid =
                queryPid(binder)

            val future =
                executor.submit<Bundle> {
                    transactS3Emit(
                        binder,
                        s3Compiler,
                        entrySource,
                        emitterSource
                    )
                }

            val bundle =
                try {
                    future.get(
                        STAGE1_EXECUTION_TIMEOUT_MS,
                        TimeUnit.MILLISECONDS
                    )
                } catch (
                    timeout: TimeoutException
                ) {
                    if (workerPid > 0) {
                        Process.killProcess(
                            workerPid
                        )
                    }

                    return failure(
                        "timeout",
                        "s3-emit-timeout",
                        workerPid
                    )
                } catch (
                    failure: ExecutionException
                ) {
                    val cause =
                        failure.cause

                    return if (
                        cause is
                            DeadObjectException ||
                        cause is
                            RemoteException
                    ) {
                        failure(
                            "crash",
                            "s3-emit-process-died",
                            workerPid
                        )
                    } else {
                        failure(
                            "host-reject",
                            "s3-emit-binder-execution",
                            workerPid,
                            cause?.message
                        )
                    }
                }

            return s3EmitBundleToJson(
                bundle
            )
        } catch (
            timeout: TimeoutException
        ) {
            if (workerPid > 0) {
                Process.killProcess(
                    workerPid
                )
            }

            return failure(
                "timeout",
                "s3-emit-bind-timeout",
                workerPid
            )
        } catch (
            failure: DeadObjectException
        ) {
            return failure(
                "crash",
                "s3-emit-process-died",
                workerPid
            )
        } catch (
            failure: Throwable
        ) {
            return failure(
                "host-reject",
                "s3-emit-binder-transport",
                workerPid,
                failure.message
            )
        } finally {
            runCatching {
                appContext
                    .unbindService(
                        connection
                    )
            }
            executor.shutdownNow()
        }
    }


    fun executeStage1SelfHost(
        context: Context,
        compiler: ByteArray,
        arm32Source: ByteArray,
        arm64Source: ByteArray
    ): JSONObject {
        val appContext = context.applicationContext
        val binderReady = CompletableFuture<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null) {
                    binderReady.completeExceptionally(RemoteException("null compiler binder"))
                } else {
                    binderReady.complete(service)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (!binderReady.isDone) binderReady.completeExceptionally(DeadObjectException())
            }

            override fun onBindingDied(name: ComponentName?) {
                if (!binderReady.isDone) binderReady.completeExceptionally(DeadObjectException())
            }

            override fun onNullBinding(name: ComponentName?) {
                if (!binderReady.isDone) {
                    binderReady.completeExceptionally(RemoteException("null compiler binding"))
                }
            }
        }

        val intent = Intent(appContext, RiftppCompilerService::class.java)
        if (!appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            return failure("host-reject", "bind-failed")
        }

        val executor = Executors.newSingleThreadExecutor()
        var workerPid = -1
        try {
            val binder = binderReady.get(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            workerPid = queryPid(binder)
            val future = executor.submit<Bundle> {
                transactStage1SelfHost(
                    binder,
                    compiler,
                    arm32Source,
                    arm64Source
                )
            }

            val bundle = try {
                future.get(STAGE1_EXECUTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (timeout: TimeoutException) {
                if (workerPid > 0) Process.killProcess(workerPid)
                return failure("timeout", "stage1-selfhost-timeout", workerPid)
            } catch (failure: ExecutionException) {
                val cause = failure.cause
                return if (cause is DeadObjectException || cause is RemoteException) {
                    failure("crash", "compiler-process-died", workerPid)
                } else {
                    failure("host-reject", "stage1-binder-execution", workerPid, cause?.message)
                }
            }

            return stage1BundleToJson(bundle)
        } catch (timeout: TimeoutException) {
            if (workerPid > 0) Process.killProcess(workerPid)
            return failure("timeout", "bind-timeout", workerPid)
        } catch (failure: DeadObjectException) {
            return failure("crash", "compiler-process-died", workerPid)
        } catch (failure: Throwable) {
            return failure("host-reject", "binder-transport", workerPid, failure.message)
        } finally {
            runCatching { appContext.unbindService(connection) }
            executor.shutdownNow()
        }
    }



    fun executeS2SelfHost(
        context: Context,
        genACompiler: ByteArray,
        compilerArm32Source: ByteArray,
        compilerArm64Source: ByteArray,
        diagnosticSource: ByteArray,
        proofArm32Source: ByteArray,
        proofArm64Source: ByteArray
    ): JSONObject {
        val appContext = context.applicationContext
        val binderReady = CompletableFuture<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null) {
                    binderReady.completeExceptionally(RemoteException("null compiler binder"))
                } else {
                    binderReady.complete(service)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (!binderReady.isDone) binderReady.completeExceptionally(DeadObjectException())
            }

            override fun onBindingDied(name: ComponentName?) {
                if (!binderReady.isDone) binderReady.completeExceptionally(DeadObjectException())
            }

            override fun onNullBinding(name: ComponentName?) {
                if (!binderReady.isDone) {
                    binderReady.completeExceptionally(RemoteException("null compiler binding"))
                }
            }
        }

        val intent = Intent(appContext, RiftppCompilerService::class.java)
        if (!appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            return failure("host-reject", "bind-failed")
        }

        val executor = Executors.newSingleThreadExecutor()
        var workerPid = -1
        try {
            val binder = binderReady.get(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            workerPid = queryPid(binder)
            val future = executor.submit<Bundle> {
                transactS2SelfHost(
                    binder,
                    genACompiler,
                    compilerArm32Source,
                    compilerArm64Source,
                    diagnosticSource,
                    proofArm32Source,
                    proofArm64Source
                )
            }
            val bundle = try {
                future.get(S2_SELF_HOST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (timeout: TimeoutException) {
                if (workerPid > 0) Process.killProcess(workerPid)
                return failure("timeout", "s2-selfhost-timeout", workerPid)
            } catch (failure: ExecutionException) {
                val cause = failure.cause
                return if (cause is DeadObjectException || cause is RemoteException) {
                    failure("crash", "compiler-process-died", workerPid)
                } else {
                    failure("host-reject", "s2-selfhost-binder-execution", workerPid, cause?.message)
                }
            }
            return s2SelfHostBundleToJson(bundle)
        } catch (timeout: TimeoutException) {
            if (workerPid > 0) Process.killProcess(workerPid)
            return failure("timeout", "bind-timeout", workerPid)
        } catch (failure: DeadObjectException) {
            return failure("crash", "compiler-process-died", workerPid)
        } catch (failure: Throwable) {
            return failure("host-reject", "binder-transport", workerPid, failure.message)
        } finally {
            runCatching { appContext.unbindService(connection) }
            executor.shutdownNow()
        }
    }


    fun executeS3SelfHost(
        context: Context,
        s2Compiler: ByteArray,
        compilerArm32Source: ByteArray,
        compilerArm64Source: ByteArray,
        proofArm32Source: ByteArray,
        proofArm64Source: ByteArray
    ): JSONObject {
        val appContext = context.applicationContext
        val binderReady = CompletableFuture<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null) {
                    binderReady.completeExceptionally(RemoteException("null compiler binder"))
                } else {
                    binderReady.complete(service)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (!binderReady.isDone) binderReady.completeExceptionally(DeadObjectException())
            }

            override fun onBindingDied(name: ComponentName?) {
                if (!binderReady.isDone) binderReady.completeExceptionally(DeadObjectException())
            }

            override fun onNullBinding(name: ComponentName?) {
                if (!binderReady.isDone) {
                    binderReady.completeExceptionally(RemoteException("null compiler binding"))
                }
            }
        }

        val intent = Intent(appContext, RiftppCompilerService::class.java)
        if (!appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            return failure("host-reject", "bind-failed")
        }

        val executor = Executors.newSingleThreadExecutor()
        var workerPid = -1
        try {
            val binder = binderReady.get(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            workerPid = queryPid(binder)
            val future = executor.submit<Bundle> {
                transactS3SelfHost(
                    binder,
                    s2Compiler,
                    compilerArm32Source,
                    compilerArm64Source,
                    proofArm32Source,
                    proofArm64Source
                )
            }
            val bundle = try {
                future.get(S2_SELF_HOST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (timeout: TimeoutException) {
                if (workerPid > 0) Process.killProcess(workerPid)
                return failure("timeout", "s3-selfhost-timeout", workerPid)
            } catch (failure: ExecutionException) {
                val cause = failure.cause
                return if (cause is DeadObjectException || cause is RemoteException) {
                    failure("crash", "compiler-process-died", workerPid)
                } else {
                    failure("host-reject", "s3-selfhost-binder-execution", workerPid, cause?.message)
                }
            }
            return s3SelfHostBundleToJson(bundle)
        } catch (timeout: TimeoutException) {
            if (workerPid > 0) Process.killProcess(workerPid)
            return failure("timeout", "bind-timeout", workerPid)
        } catch (failure: DeadObjectException) {
            return failure("crash", "compiler-process-died", workerPid)
        } catch (failure: Throwable) {
            return failure("host-reject", "binder-transport", workerPid, failure.message)
        } finally {
            runCatching { appContext.unbindService(connection) }
            executor.shutdownNow()
        }
    }

    fun executeS2Bootstrap(
        context: Context,
        compiler: ByteArray,
        stage1Arm32Source: ByteArray,
        stage1Arm64Source: ByteArray,
        genAArm32Source: ByteArray,
        genAArm64Source: ByteArray,
        proofArm32Source: ByteArray,
        proofArm64Source: ByteArray
    ): JSONObject {
        val appContext = context.applicationContext
        val binderReady = CompletableFuture<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null) {
                    binderReady.completeExceptionally(RemoteException("null compiler binder"))
                } else {
                    binderReady.complete(service)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (!binderReady.isDone) binderReady.completeExceptionally(DeadObjectException())
            }

            override fun onBindingDied(name: ComponentName?) {
                if (!binderReady.isDone) binderReady.completeExceptionally(DeadObjectException())
            }

            override fun onNullBinding(name: ComponentName?) {
                if (!binderReady.isDone) {
                    binderReady.completeExceptionally(RemoteException("null compiler binding"))
                }
            }
        }

        val intent = Intent(appContext, RiftppCompilerService::class.java)
        if (!appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            return failure("host-reject", "bind-failed")
        }

        val executor = Executors.newSingleThreadExecutor()
        var workerPid = -1
        try {
            val binder = binderReady.get(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            workerPid = queryPid(binder)
            val future = executor.submit<Bundle> {
                transactS2Bootstrap(
                    binder,
                    compiler,
                    stage1Arm32Source,
                    stage1Arm64Source,
                    genAArm32Source,
                    genAArm64Source,
                    proofArm32Source,
                    proofArm64Source
                )
            }

            val bundle = try {
                future.get(S2_BOOTSTRAP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (timeout: TimeoutException) {
                if (workerPid > 0) Process.killProcess(workerPid)
                return failure("timeout", "s2-bootstrap-timeout", workerPid)
            } catch (failure: ExecutionException) {
                val cause = failure.cause
                return if (cause is DeadObjectException || cause is RemoteException) {
                    failure("crash", "compiler-process-died", workerPid)
                } else {
                    failure("host-reject", "s2-binder-execution", workerPid, cause?.message)
                }
            }

            return s2BundleToJson(bundle)
        } catch (timeout: TimeoutException) {
            if (workerPid > 0) Process.killProcess(workerPid)
            return failure("timeout", "bind-timeout", workerPid)
        } catch (failure: DeadObjectException) {
            return failure("crash", "compiler-process-died", workerPid)
        } catch (failure: Throwable) {
            return failure("host-reject", "binder-transport", workerPid, failure.message)
        } finally {
            runCatching { appContext.unbindService(connection) }
            executor.shutdownNow()
        }
    }

    fun executeS2Vectors(
        context: Context,
        genACompiler: ByteArray,
        arm32Source: ByteArray,
        arm64Source: ByteArray
    ): JSONObject {
        val appContext = context.applicationContext
        val binderReady = CompletableFuture<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null) {
                    binderReady.completeExceptionally(RemoteException("null compiler binder"))
                } else {
                    binderReady.complete(service)
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                if (!binderReady.isDone) binderReady.completeExceptionally(DeadObjectException())
            }
            override fun onBindingDied(name: ComponentName?) {
                if (!binderReady.isDone) binderReady.completeExceptionally(DeadObjectException())
            }
            override fun onNullBinding(name: ComponentName?) {
                if (!binderReady.isDone) {
                    binderReady.completeExceptionally(RemoteException("null compiler binding"))
                }
            }
        }

        val intent = Intent(appContext, RiftppCompilerService::class.java)
        if (!appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            return failure("host-reject", "bind-failed")
        }
        val executor = Executors.newSingleThreadExecutor()
        var workerPid = -1
        try {
            val binder = binderReady.get(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            workerPid = queryPid(binder)
            val future = executor.submit<Bundle> {
                transactS2Vectors(binder, genACompiler, arm32Source, arm64Source)
            }
            val bundle = try {
                future.get(S2_BOOTSTRAP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (timeout: TimeoutException) {
                if (workerPid > 0) Process.killProcess(workerPid)
                return failure("timeout", "s2-vectors-timeout", workerPid)
            } catch (failure: ExecutionException) {
                val cause = failure.cause
                return if (cause is DeadObjectException || cause is RemoteException) {
                    failure("crash", "compiler-process-died", workerPid)
                } else {
                    failure("host-reject", "s2-vectors-binder-execution", workerPid, cause?.message)
                }
            }
            return s2VectorBundleToJson(bundle)
        } catch (timeout: TimeoutException) {
            if (workerPid > 0) Process.killProcess(workerPid)
            return failure("timeout", "bind-timeout", workerPid)
        } catch (failure: DeadObjectException) {
            return failure("crash", "compiler-process-died", workerPid)
        } catch (failure: Throwable) {
            return failure("host-reject", "binder-transport", workerPid, failure.message)
        } finally {
            runCatching { appContext.unbindService(connection) }
            executor.shutdownNow()
        }
    }

    private fun queryPid(binder: IBinder): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(RiftppCompilerService.DESCRIPTOR)
            if (!binder.transact(RiftppCompilerService.TRANSACTION_PID, data, reply, 0)) {
                throw RemoteException("compiler pid transaction rejected")
            }
            reply.readException()
            reply.readInt()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun transactCompile(
        binder: IBinder,
        compiler: ByteArray,
        source: ByteArray,
        outputCapacity: Int,
        proveGeneratedPayload: Boolean
    ): Bundle {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(RiftppCompilerService.DESCRIPTOR)
            data.writeByteArray(compiler)
            data.writeByteArray(source)
            data.writeInt(outputCapacity)
            val transactionCode =
                if (proveGeneratedPayload) {
                    RiftppCompilerService.TRANSACTION_COMPILE_PROOF
                } else {
                    RiftppCompilerService.TRANSACTION_COMPILE
                }
            if (!binder.transact(transactionCode, data, reply, 0)) {
                throw RemoteException("compiler transaction rejected")
            }
            reply.readException()
            reply.readBundle(RiftppCompilerService::class.java.classLoader)
                ?: throw RemoteException("compiler result bundle missing")
        } finally {
            reply.recycle()
            data.recycle()
        }
    }


    private fun transactS3Emit(
        binder: IBinder,
        s3Compiler: ByteArray,
        entrySource: ByteArray,
        emitterSource: ByteArray
    ): Bundle {
        val data =
            Parcel.obtain()
        val reply =
            Parcel.obtain()

        return try {
            data.writeInterfaceToken(
                RiftppCompilerService
                    .DESCRIPTOR
            )
            data.writeByteArray(
                s3Compiler
            )
            data.writeByteArray(
                entrySource
            )
            data.writeByteArray(
                emitterSource
            )

            if (
                !binder.transact(
                    RiftppCompilerService
                        .TRANSACTION_S3_EMIT,
                    data,
                    reply,
                    0
                )
            ) {
                throw RemoteException(
                    "S3 emit transaction rejected"
                )
            }

            reply.readException()

            reply.readBundle(
                RiftppCompilerService::class
                    .java.classLoader
            )
                ?: throw RemoteException(
                    "S3 emit result bundle missing"
                )
        } finally {
            reply.recycle()
            data.recycle()
        }
    }


    private fun transactS2Vectors(
        binder: IBinder,
        genACompiler: ByteArray,
        arm32Source: ByteArray,
        arm64Source: ByteArray
    ): Bundle {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(RiftppCompilerService.DESCRIPTOR)
            data.writeByteArray(genACompiler)
            data.writeByteArray(arm32Source)
            data.writeByteArray(arm64Source)
            if (!binder.transact(RiftppCompilerService.TRANSACTION_S2_VECTORS, data, reply, 0)) {
                throw RemoteException("S2 vector transaction rejected")
            }
            reply.readException()
            reply.readBundle(RiftppCompilerService::class.java.classLoader)
                ?: throw RemoteException("S2 vector result bundle missing")
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun s2VectorBundleToJson(bundle: Bundle): JSONObject =
        JSONObject()
            .put("schema", "rift.riftpp-s2-vectors/1")
            .put("status", bundle.getString("status") ?: "host-reject")
            .put("reason", bundle.getString("reason") ?: JSONObject.NULL)
            .put("detail", bundle.getString("detail") ?: JSONObject.NULL)
            .put("hostAbi", bundle.getString("hostAbi") ?: JSONObject.NULL)
            .put("pid", bundle.getInt("pid", -1))
            .put("genACompilerSha256", bundle.getString("genACompilerSha256") ?: JSONObject.NULL)
            .put("arm32SourceSha256", bundle.getString("arm32SourceSha256") ?: JSONObject.NULL)
            .put("arm64SourceSha256", bundle.getString("arm64SourceSha256") ?: JSONObject.NULL)
            .put("arm32OutputSha256", bundle.getString("arm32OutputSha256") ?: JSONObject.NULL)
            .put("arm64OutputSha256", bundle.getString("arm64OutputSha256") ?: JSONObject.NULL)
            .put("arm32OutputHex", bundle.getString("arm32OutputHex") ?: JSONObject.NULL)
            .put("arm64OutputHex", bundle.getString("arm64OutputHex") ?: JSONObject.NULL)
            .put("arm32OutputBytes", bundle.getInt("arm32OutputBytes", 0))
            .put("arm64OutputBytes", bundle.getInt("arm64OutputBytes", 0))
            .put("hostParsesS2Opcodes", bundle.getBoolean("hostParsesS2Opcodes", false))
            .put("hostEmitsS2Instructions", bundle.getBoolean("hostEmitsS2Instructions", false))
            .put("outputsExecuted", bundle.getBoolean("outputsExecuted", false))

    private fun transactStage1SelfHost(
        binder: IBinder,
        compiler: ByteArray,
        arm32Source: ByteArray,
        arm64Source: ByteArray
    ): Bundle {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(RiftppCompilerService.DESCRIPTOR)
            data.writeByteArray(compiler)
            data.writeByteArray(arm32Source)
            data.writeByteArray(arm64Source)
            if (!binder.transact(
                    RiftppCompilerService.TRANSACTION_STAGE1_SELF_HOST,
                    data,
                    reply,
                    0
                )
            ) {
                throw RemoteException("stage1 self-host transaction rejected")
            }
            reply.readException()
            reply.readBundle(RiftppCompilerService::class.java.classLoader)
                ?: throw RemoteException("stage1 self-host result bundle missing")
        } finally {
            reply.recycle()
            data.recycle()
        }
    }




    private fun transactS3SelfHost(
        binder: IBinder,
        s2Compiler: ByteArray,
        compilerArm32Source: ByteArray,
        compilerArm64Source: ByteArray,
        proofArm32Source: ByteArray,
        proofArm64Source: ByteArray
    ): Bundle {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(RiftppCompilerService.DESCRIPTOR)
            data.writeByteArray(s2Compiler)
            data.writeByteArray(compilerArm32Source)
            data.writeByteArray(compilerArm64Source)
            data.writeByteArray(proofArm32Source)
            data.writeByteArray(proofArm64Source)
            if (!binder.transact(
                    RiftppCompilerService.TRANSACTION_S3_SELF_HOST,
                    data,
                    reply,
                    0
                )
            ) {
                throw RemoteException("S3 self-host transaction rejected")
            }
            reply.readException()
            reply.readBundle(RiftppCompilerService::class.java.classLoader)
                ?: throw RemoteException("S3 self-host result bundle missing")
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun transactS2SelfHost(
        binder: IBinder,
        genACompiler: ByteArray,
        compilerArm32Source: ByteArray,
        compilerArm64Source: ByteArray,
        diagnosticSource: ByteArray,
        proofArm32Source: ByteArray,
        proofArm64Source: ByteArray
    ): Bundle {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(RiftppCompilerService.DESCRIPTOR)
            data.writeByteArray(genACompiler)
            data.writeByteArray(compilerArm32Source)
            data.writeByteArray(compilerArm64Source)
            data.writeByteArray(diagnosticSource)
            data.writeByteArray(proofArm32Source)
            data.writeByteArray(proofArm64Source)
            if (!binder.transact(
                    RiftppCompilerService.TRANSACTION_S2_SELF_HOST,
                    data,
                    reply,
                    0
                )
            ) {
                throw RemoteException("S2 self-host transaction rejected")
            }
            reply.readException()
            reply.readBundle(RiftppCompilerService::class.java.classLoader)
                ?: throw RemoteException("S2 self-host result bundle missing")
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun transactS2Bootstrap(
        binder: IBinder,
        compiler: ByteArray,
        stage1Arm32Source: ByteArray,
        stage1Arm64Source: ByteArray,
        genAArm32Source: ByteArray,
        genAArm64Source: ByteArray,
        proofArm32Source: ByteArray,
        proofArm64Source: ByteArray
    ): Bundle {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(RiftppCompilerService.DESCRIPTOR)
            data.writeByteArray(compiler)
            data.writeByteArray(stage1Arm32Source)
            data.writeByteArray(stage1Arm64Source)
            data.writeByteArray(genAArm32Source)
            data.writeByteArray(genAArm64Source)
            data.writeByteArray(proofArm32Source)
            data.writeByteArray(proofArm64Source)
            if (!binder.transact(
                    RiftppCompilerService.TRANSACTION_S2_BOOTSTRAP,
                    data,
                    reply,
                    0
                )
            ) {
                throw RemoteException("S2 bootstrap transaction rejected")
            }
            reply.readException()
            reply.readBundle(RiftppCompilerService::class.java.classLoader)
                ?: throw RemoteException("S2 bootstrap result bundle missing")
        } finally {
            reply.recycle()
            data.recycle()
        }
    }



    private fun s3SelfHostBundleToJson(bundle: Bundle): JSONObject =
        JSONObject()
            .put("schema", "rift.riftpp-s3-selfhost/1")
            .put("status", bundle.getString("status") ?: "host-reject")
            .put("reason", bundle.getString("reason") ?: JSONObject.NULL)
            .put("detail", bundle.getString("detail") ?: JSONObject.NULL)
            .put("hostAbi", bundle.getString("hostAbi") ?: JSONObject.NULL)
            .put("pid", bundle.getInt("pid", -1))
            .put("promotedS2CompilerSha256", bundle.getString("promotedS2CompilerSha256") ?: JSONObject.NULL)
            .put("candidateArm32SourceSha256", bundle.getString("candidateArm32SourceSha256") ?: JSONObject.NULL)
            .put("candidateArm64SourceSha256", bundle.getString("candidateArm64SourceSha256") ?: JSONObject.NULL)
            .put("generationAArm32Sha256", bundle.getString("generationAArm32Sha256") ?: JSONObject.NULL)
            .put("generationAArm64Sha256", bundle.getString("generationAArm64Sha256") ?: JSONObject.NULL)
            .put("generationCArm32Sha256", bundle.getString("generationCArm32Sha256") ?: JSONObject.NULL)
            .put("generationCArm64Sha256", bundle.getString("generationCArm64Sha256") ?: JSONObject.NULL)
            .put("proofArm32OutputSha256", bundle.getString("proofArm32OutputSha256") ?: JSONObject.NULL)
            .put("proofArm64OutputSha256", bundle.getString("proofArm64OutputSha256") ?: JSONObject.NULL)
            .put("generationAArm32Bytes", bundle.getInt("generationAArm32Bytes", 0))
            .put("generationAArm64Bytes", bundle.getInt("generationAArm64Bytes", 0))
            .put("generationBArm32Bytes", bundle.getInt("generationBArm32Bytes", 0))
            .put("generationBArm64Bytes", bundle.getInt("generationBArm64Bytes", 0))
            .put("generationCArm32Bytes", bundle.getInt("generationCArm32Bytes", 0))
            .put("generationCArm64Bytes", bundle.getInt("generationCArm64Bytes", 0))
            .put("generationBArm64EqualsC", bundle.getBoolean("generationBArm64EqualsC", false))
            .put("proofArm32Bytes", bundle.getInt("proofArm32Bytes", 0))
            .put("proofArm64Bytes", bundle.getInt("proofArm64Bytes", 0))
            .put("proofExecutionStatus", bundle.getInt("proofExecutionStatus", -1))
            .put("proofReturnValue", bundle.getInt("proofReturnValue", 0))
            .put("generationACurrentAbiExecuted", bundle.getBoolean("generationACurrentAbiExecuted", false))
            .put("generationACompiledBothTargets", bundle.getBoolean("generationACompiledBothTargets", false))
            .put("generationBCurrentAbiExecuted", bundle.getBoolean("generationBCurrentAbiExecuted", false))
            .put("generationBCompiledBothTargets", bundle.getBoolean("generationBCompiledBothTargets", false))
            .put("hostParsesS3Opcodes", bundle.getBoolean("hostParsesS3Opcodes", false))
            .put("hostEmitsS3Instructions", bundle.getBoolean("hostEmitsS3Instructions", false))

    private fun s2SelfHostBundleToJson(bundle: Bundle): JSONObject =
        JSONObject()
            .put("schema", "rift.riftpp-s2-selfhost/1")
            .put("status", bundle.getString("status") ?: "host-reject")
            .put("reason", bundle.getString("reason") ?: JSONObject.NULL)
            .put("detail", bundle.getString("detail") ?: JSONObject.NULL)
            .put("hostAbi", bundle.getString("hostAbi") ?: JSONObject.NULL)
            .put("pid", bundle.getInt("pid", -1))
            .put("genASha256", bundle.getString("genASha256") ?: JSONObject.NULL)
            .put(
                "canonicalArm32SourceSha256",
                bundle.getString("canonicalArm32SourceSha256") ?: JSONObject.NULL
            )
            .put(
                "canonicalArm64SourceSha256",
                bundle.getString("canonicalArm64SourceSha256") ?: JSONObject.NULL
            )
            .put(
                "diagnosticSourceSha256",
                bundle.getString("diagnosticSourceSha256") ?: JSONObject.NULL
            )
            .put(
                "generationCArm32Sha256",
                bundle.getString("generationCArm32Sha256") ?: JSONObject.NULL
            )
            .put(
                "generationCArm64Sha256",
                bundle.getString("generationCArm64Sha256") ?: JSONObject.NULL
            )
            .put(
                "proofArm32OutputSha256",
                bundle.getString("proofArm32OutputSha256") ?: JSONObject.NULL
            )
            .put(
                "proofArm64OutputSha256",
                bundle.getString("proofArm64OutputSha256") ?: JSONObject.NULL
            )
            .put("generationBArm32Bytes", bundle.getInt("generationBArm32Bytes", 0))
            .put("generationBArm64Bytes", bundle.getInt("generationBArm64Bytes", 0))
            .put("generationCArm32Bytes", bundle.getInt("generationCArm32Bytes", 0))
            .put("generationCArm64Bytes", bundle.getInt("generationCArm64Bytes", 0))
            .put("proofArm32Bytes", bundle.getInt("proofArm32Bytes", 0))
            .put("proofArm64Bytes", bundle.getInt("proofArm64Bytes", 0))
            .put("proofExecutionStatus", bundle.getInt("proofExecutionStatus", -1))
            .put(
                "proofReturnValue",
                if (bundle.containsKey("proofReturnValue")) {
                    bundle.getLong("proofReturnValue")
                } else {
                    JSONObject.NULL
                }
            )
            .put(
                "diagnosticReturnValue",
                if (bundle.containsKey("diagnosticReturnValue")) {
                    bundle.getLong("diagnosticReturnValue")
                } else {
                    JSONObject.NULL
                }
            )
            .put(
                "generationACompiledCanonicalCompiler",
                bundle.getBoolean("generationACompiledCanonicalCompiler", false)
            )
            .put(
                "generationBCurrentAbiExecuted",
                bundle.getBoolean("generationBCurrentAbiExecuted", false)
            )
            .put(
                "generationBCompiledBothTargets",
                bundle.getBoolean("generationBCompiledBothTargets", false)
            )
            .put(
                "generationCCurrentAbiExecuted",
                bundle.getBoolean("generationCCurrentAbiExecuted", false)
            )
            .put(
                "generationCArm32EqualsD",
                bundle.getBoolean("generationCArm32EqualsD", false)
            )
            .put(
                "generationCArm64EqualsD",
                bundle.getBoolean("generationCArm64EqualsD", false)
            )
            .put(
                "generationCCompiledBothTargets",
                bundle.getBoolean("generationCCompiledBothTargets", false)
            )
            .put("hostParsesS2Opcodes", bundle.getBoolean("hostParsesS2Opcodes", false))
            .put("hostEmitsS2Instructions", bundle.getBoolean("hostEmitsS2Instructions", false))

    private fun s2BundleToJson(bundle: Bundle): JSONObject =
        JSONObject()
            .put("schema", "rift.riftpp-s2-bootstrap/1")
            .put("status", bundle.getString("status") ?: "host-reject")
            .put("reason", bundle.getString("reason") ?: JSONObject.NULL)
            .put("detail", bundle.getString("detail") ?: JSONObject.NULL)
            .put("hostAbi", bundle.getString("hostAbi") ?: JSONObject.NULL)
            .put("pid", bundle.getInt("pid", -1))
            .put("compilerSha256", bundle.getString("compilerSha256") ?: JSONObject.NULL)
            .put(
                "stage1Arm32SourceSha256",
                bundle.getString("stage1Arm32SourceSha256") ?: JSONObject.NULL
            )
            .put(
                "stage1Arm64SourceSha256",
                bundle.getString("stage1Arm64SourceSha256") ?: JSONObject.NULL
            )
            .put(
                "genAArm32SourceSha256",
                bundle.getString("genAArm32SourceSha256") ?: JSONObject.NULL
            )
            .put(
                "genAArm64SourceSha256",
                bundle.getString("genAArm64SourceSha256") ?: JSONObject.NULL
            )
            .put("genAArm32Sha256", bundle.getString("genAArm32Sha256") ?: JSONObject.NULL)
            .put("genAArm64Sha256", bundle.getString("genAArm64Sha256") ?: JSONObject.NULL)
            .put(
                "proofArm32SourceSha256",
                bundle.getString("proofArm32SourceSha256") ?: JSONObject.NULL
            )
            .put(
                "proofArm64SourceSha256",
                bundle.getString("proofArm64SourceSha256") ?: JSONObject.NULL
            )
            .put(
                "proofArm32OutputSha256",
                bundle.getString("proofArm32OutputSha256") ?: JSONObject.NULL
            )
            .put(
                "proofArm64OutputSha256",
                bundle.getString("proofArm64OutputSha256") ?: JSONObject.NULL
            )
            .put("genAArm32Bytes", bundle.getInt("genAArm32Bytes", 0))
            .put("genAArm64Bytes", bundle.getInt("genAArm64Bytes", 0))
            .put("proofArm32Bytes", bundle.getInt("proofArm32Bytes", 0))
            .put("proofArm64Bytes", bundle.getInt("proofArm64Bytes", 0))
            .put("proofExecutionStatus", bundle.getInt("proofExecutionStatus", -1))
            .put(
                "proofReturnValue",
                if (bundle.containsKey("proofReturnValue")) {
                    bundle.getLong("proofReturnValue")
                } else {
                    JSONObject.NULL
                }
            )
            .put(
                "stage1ManufacturedGenerationA",
                bundle.getBoolean("stage1ManufacturedGenerationA", false)
            )
            .put(
                "generationACompiledBothTargets",
                bundle.getBoolean("generationACompiledBothTargets", false)
            )
            .put("hostParsesS2Opcodes", bundle.getBoolean("hostParsesS2Opcodes", false))
            .put("hostEmitsS2Instructions", bundle.getBoolean("hostEmitsS2Instructions", false))

    private fun stage1BundleToJson(bundle: Bundle): JSONObject =
        JSONObject()
            .put("schema", "rift.riftpp-stage1-selfhost/1")
            .put("status", bundle.getString("status") ?: "host-reject")
            .put("reason", bundle.getString("reason") ?: JSONObject.NULL)
            .put("detail", bundle.getString("detail") ?: JSONObject.NULL)
            .put("hostAbi", bundle.getString("hostAbi") ?: JSONObject.NULL)
            .put("pid", bundle.getInt("pid", -1))
            .put("compilerSha256", bundle.getString("compilerSha256") ?: JSONObject.NULL)
            .put(
                "stage1Arm32SourceSha256",
                bundle.getString("stage1Arm32SourceSha256") ?: JSONObject.NULL
            )
            .put(
                "stage1Arm64SourceSha256",
                bundle.getString("stage1Arm64SourceSha256") ?: JSONObject.NULL
            )
            .put(
                "bootstrapArm32Sha256",
                bundle.getString("bootstrapArm32Sha256") ?: JSONObject.NULL
            )
            .put(
                "bootstrapArm64Sha256",
                bundle.getString("bootstrapArm64Sha256") ?: JSONObject.NULL
            )
            .put("selfArm32Sha256", bundle.getString("selfArm32Sha256") ?: JSONObject.NULL)
            .put("selfArm64Sha256", bundle.getString("selfArm64Sha256") ?: JSONObject.NULL)
            .put("bootstrapArm32Bytes", bundle.getInt("bootstrapArm32Bytes", 0))
            .put("bootstrapArm64Bytes", bundle.getInt("bootstrapArm64Bytes", 0))
            .put("selfArm32Bytes", bundle.getInt("selfArm32Bytes", 0))
            .put("selfArm64Bytes", bundle.getInt("selfArm64Bytes", 0))
            .put("selfHostedCurrentAbi", bundle.getBoolean("selfHostedCurrentAbi", false))
            .put("crossTargetReproduced", bundle.getBoolean("crossTargetReproduced", false))
            .put("hostParsesStage1Numbers", bundle.getBoolean("hostParsesStage1Numbers", false))
            .put(
                "hostEmitsStage1Instructions",
                bundle.getBoolean("hostEmitsStage1Instructions", false)
            )

    private fun s3EmitBundleToJson(
        bundle: Bundle
    ): JSONObject {
        val elf =
            bundle.getByteArray(
                "elf"
            )

        return JSONObject()
            .put(
                "schema",
                bundle.getString(
                    "schema"
                )
                    ?: "rift.riftpp-s3-android-r1/1"
            )
            .put(
                "status",
                bundle.getString(
                    "status"
                )
                    ?: "host-reject"
            )
            .put(
                "reason",
                bundle.getString(
                    "reason"
                )
                    ?: JSONObject.NULL
            )
            .put(
                "detail",
                bundle.getString(
                    "detail"
                )
                    ?: JSONObject.NULL
            )
            .put(
                "hostAbi",
                bundle.getString(
                    "hostAbi"
                )
                    ?: JSONObject.NULL
            )
            .put(
                "pid",
                bundle.getInt(
                    "pid",
                    -1
                )
            )
            .put(
                "compilerSha256",
                bundle.getString(
                    "compilerSha256"
                )
                    ?: JSONObject.NULL
            )
            .put(
                "compilerBytes",
                bundle.getInt(
                    "compilerBytes",
                    0
                )
            )
            .put(
                "entrySourceSha256",
                bundle.getString(
                    "entrySourceSha256"
                )
                    ?: JSONObject.NULL
            )
            .put(
                "emitterSourceSha256",
                bundle.getString(
                    "emitterSourceSha256"
                )
                    ?: JSONObject.NULL
            )
            .put(
                "entryEmitterOutputSha256",
                bundle.getString(
                    "entryEmitterOutputSha256"
                )
                    ?: JSONObject.NULL
            )
            .put(
                "entryOutputSha256",
                bundle.getString(
                    "entryOutputSha256"
                )
                    ?: JSONObject.NULL
            )
            .put(
                "emitterOutputSha256",
                bundle.getString(
                    "emitterOutputSha256"
                )
                    ?: JSONObject.NULL
            )
            .put(
                "elfSha256",
                bundle.getString(
                    "elfSha256"
                )
                    ?: JSONObject.NULL
            )
            .put(
                "entryEmitterOutputBytes",
                bundle.getInt(
                    "entryEmitterOutputBytes",
                    0
                )
            )
            .put(
                "entryOutputBytes",
                bundle.getInt(
                    "entryOutputBytes",
                    0
                )
            )
            .put(
                "emitterOutputBytes",
                bundle.getInt(
                    "emitterOutputBytes",
                    0
                )
            )
            .put(
                "elfBytes",
                bundle.getInt(
                    "elfBytes",
                    0
                )
            )
            .put(
                "elfHex",
                elf?.joinToString(
                    ""
                ) {
                    "%02x".format(it)
                }
                    ?: JSONObject.NULL
            )
            .put(
                "hostParsesS3Opcodes",
                bundle.getBoolean(
                    "hostParsesS3Opcodes",
                    false
                )
            )
            .put(
                "hostEmitsS3Instructions",
                bundle.getBoolean(
                    "hostEmitsS3Instructions",
                    false
                )
            )
            .put(
                "hostParsesElf",
                bundle.getBoolean(
                    "hostParsesElf",
                    false
                )
            )
            .put(
                "hostEmitsElf",
                bundle.getBoolean(
                    "hostEmitsElf",
                    false
                )
            )
    }


    private fun bundleToJson(bundle: Bundle): JSONObject {
        val output = bundle.getByteArray("output")
        return JSONObject()
            .put("schema", "rift.riftpp-compiler-host/1")
            .put("status", bundle.getString("status") ?: "host-reject")
            .put("reason", bundle.getString("reason") ?: JSONObject.NULL)
            .put("detail", bundle.getString("detail") ?: JSONObject.NULL)
            .put("hostAbi", bundle.getString("hostAbi") ?: JSONObject.NULL)
            .put("pid", bundle.getInt("pid", -1))
            .put("compilerSha256", bundle.getString("compilerSha256") ?: JSONObject.NULL)
            .put("compilerBytes", bundle.getInt("compilerBytes", 0))
            .put("sourceSha256", bundle.getString("sourceSha256") ?: JSONObject.NULL)
            .put("sourceBytes", bundle.getInt("sourceBytes", 0))
            .put("returnValue", if (bundle.containsKey("returnValue")) bundle.getLong("returnValue") else JSONObject.NULL)
            .put("outputSha256", bundle.getString("outputSha256") ?: JSONObject.NULL)
            .put("outputBytes", bundle.getInt("outputBytes", 0))
            .put("outputHex", output?.joinToString("") { "%02x".format(it) } ?: JSONObject.NULL)
            .put("generatedPayloadProofRequested", bundle.getBoolean("generatedPayloadProofRequested", false))
            .put(
                "generatedPayloadProofStatus",
                if (bundle.containsKey("generatedPayloadProofStatus")) bundle.getInt("generatedPayloadProofStatus") else JSONObject.NULL
            )
            .put(
                "generatedPayloadReturnValue",
                if (bundle.containsKey("generatedPayloadReturnValue")) bundle.getLong("generatedPayloadReturnValue") else JSONObject.NULL
            )
    }

    private fun failure(
        status: String,
        reason: String,
        pid: Int = -1,
        detail: String? = null
    ): JSONObject = JSONObject()
        .put("schema", "rift.riftpp-compiler-host/1")
        .put("status", status)
        .put("reason", reason)
        .put("pid", pid)
        .put("detail", detail?.take(512) ?: JSONObject.NULL)
}
