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

        private const val COMPILER_BYTES = 276
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
