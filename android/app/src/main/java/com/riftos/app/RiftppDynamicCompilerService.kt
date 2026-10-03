package com.riftos.app

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Crash-contained execution lane for project-owned Rift++ compiler payloads.
 *
 * This is the sole Rift++ native compiler execution service. It has no hardcoded compiler identity; authority comes
 * from RiftBuildManagedToolchains manifest + exact SHA-256 validation before bytes reach this
 * process. The frozen legacy compiler service remains unchanged.
 */
class RiftppDynamicCompilerService : Service() {
    companion object {
        internal const val DESCRIPTOR = "com.riftos.app.RiftppDynamicCompilerService"
        internal const val TRANSACTION_PID = IBinder.FIRST_CALL_TRANSACTION
        internal const val TRANSACTION_COMPILE = IBinder.FIRST_CALL_TRANSACTION + 1

        private const val MAX_COMPILER_BYTES = 256 * 1024
        private const val MAX_SOURCE_BYTES = 512 * 1024
        private const val MAX_OUTPUT_BYTES = 512 * 1024
        private const val BIND_TIMEOUT_SECONDS = 2L
        private const val COMPILE_TIMEOUT_SECONDS = 3L

        fun compile(
            context: Context,
            compiler: ByteArray,
            source: ByteArray,
            outputCapacity: Int
        ): Bundle {
            require(compiler.isNotEmpty() && compiler.size <= MAX_COMPILER_BYTES) {
                "Dynamic Rift++ compiler bytes are out of bounds"
            }
            require(source.size <= MAX_SOURCE_BYTES) {
                "Dynamic Rift++ source bytes are out of bounds"
            }
            require(outputCapacity in 1..MAX_OUTPUT_BYTES) {
                "Dynamic Rift++ output capacity is out of bounds"
            }

            val app = context.applicationContext
            val connected = CompletableFuture<IBinder>()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    if (service != null) connected.complete(service)
                    else connected.completeExceptionally(IllegalStateException("Dynamic compiler binder missing"))
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    if (!connected.isDone) {
                        connected.completeExceptionally(IllegalStateException("Dynamic compiler disconnected"))
                    }
                }

                override fun onBindingDied(name: ComponentName?) {
                    if (!connected.isDone) {
                        connected.completeExceptionally(IllegalStateException("Dynamic compiler binding died"))
                    }
                }

                override fun onNullBinding(name: ComponentName?) {
                    if (!connected.isDone) {
                        connected.completeExceptionally(IllegalStateException("Dynamic compiler null binding"))
                    }
                }
            }

            val intent = Intent(app, RiftppDynamicCompilerService::class.java)
            require(app.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                "Could not bind dynamic Rift++ compiler service"
            }

            var remotePid = -1
            val executor = Executors.newSingleThreadExecutor()
            try {
                val binder = connected.get(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                remotePid = readRemotePid(binder)
                val future = executor.submit<Bundle> {
                    transactCompile(binder, compiler, source, outputCapacity)
                }
                return try {
                    future.get(COMPILE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                } catch (timeout: TimeoutException) {
                    future.cancel(true)
                    if (remotePid > 0) runCatching { Process.killProcess(remotePid) }
                    Bundle().apply {
                        putString("status", "timeout")
                        putInt("hostStatus", -200)
                        putLong("returnValue", 0L)
                    }
                }
            } finally {
                executor.shutdownNow()
                runCatching { app.unbindService(connection) }
            }
        }

        private fun readRemotePid(binder: IBinder): Int {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(DESCRIPTOR)
                require(binder.transact(TRANSACTION_PID, data, reply, 0)) {
                    "Dynamic compiler PID transaction failed"
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
            outputCapacity: Int
        ): Bundle {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeByteArray(compiler)
                data.writeByteArray(source)
                data.writeInt(outputCapacity)
                require(binder.transact(TRANSACTION_COMPILE, data, reply, 0)) {
                    "Dynamic compiler transaction failed"
                }
                reply.readException()
                reply.readBundle(RiftppDynamicCompilerService::class.java.classLoader)
                    ?: Bundle().apply {
                        putString("status", "host-reject")
                        putInt("hostStatus", -201)
                    }
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }

    private val nativeLoadFailure: Throwable? =
        runCatching { System.loadLibrary("riftpp_dynamic_compiler_host") }.exceptionOrNull()

    private external fun nativeCompileDynamic(
        compiler: ByteArray,
        source: ByteArray,
        output: ByteArray
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

                TRANSACTION_COMPILE -> {
                    data.enforceInterface(DESCRIPTOR)
                    val compiler = data.createByteArray() ?: ByteArray(0)
                    val source = data.createByteArray() ?: ByteArray(0)
                    val capacity = data.readInt()
                    val result = execute(compiler, source, capacity)
                    reply?.writeNoException()
                    reply?.writeBundle(result)
                    true
                }

                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun execute(
        compiler: ByteArray,
        source: ByteArray,
        outputCapacity: Int
    ): Bundle {
        nativeLoadFailure?.let { failure ->
            return Bundle().apply {
                putString("status", "host-reject")
                putInt("hostStatus", -210)
                putString("detail", failure.message ?: failure.javaClass.simpleName)
            }
        }
        if (
            compiler.isEmpty() || compiler.size > MAX_COMPILER_BYTES ||
            source.size > MAX_SOURCE_BYTES ||
            outputCapacity !in 1..MAX_OUTPUT_BYTES
        ) {
            return Bundle().apply {
                putString("status", "host-reject")
                putInt("hostStatus", -211)
            }
        }

        val output = ByteArray(outputCapacity)
        val native = nativeCompileDynamic(compiler, source, output)
        val hostStatus = native.getOrElse(0) { -212L }.toInt()
        val returnValue = native.getOrElse(1) { 0xffffffffL }
        val success = hostStatus == 0 && returnValue != 0xffffffffL

        return Bundle().apply {
            putString(
                "status",
                when {
                    hostStatus != 0 -> "host-reject"
                    returnValue == 0xffffffffL -> "compiler-reject"
                    else -> "success"
                }
            )
            putInt("hostStatus", hostStatus)
            putLong("returnValue", returnValue)
            putInt("compilerBytes", compiler.size)
            putInt("sourceBytes", source.size)
            putInt("outputCapacity", outputCapacity)
            if (success) {
                val length = returnValue.toInt()
                putByteArray("output", output.copyOf(length))
                putInt("outputBytes", length)
            }
        }
    }
}
