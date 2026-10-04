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
import dalvik.system.DexClassLoader
import java.io.File
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Generic crash-contained JVM/DEX tool host.
 *
 * Payloads are exact-hash validated before load and copied into a hash-named code cache. The
 * payload classloader inherits framework classes only, not RiftOS app classes, so project-owned
 * compiler/tool payloads remain authoritative and independently swappable.
 *
 * Tool ABI:
 *   public static String run(String requestJson)
 */
class RiftManagedJvmToolService : Service() {
    companion object {
        internal const val DESCRIPTOR = "com.riftos.app.RiftManagedJvmToolService"
        internal const val TRANSACTION_PID = IBinder.FIRST_CALL_TRANSACTION
        internal const val TRANSACTION_RUN = IBinder.FIRST_CALL_TRANSACTION + 1

        private const val MAX_REQUEST_BYTES = 512 * 1024
        private const val MAX_RESPONSE_BYTES = 512 * 1024
        private const val MAX_PAYLOAD_BYTES = 128L * 1024L * 1024L
        private const val BIND_TIMEOUT_SECONDS = 3L
        private const val RUN_TIMEOUT_SECONDS = 10 * 60L

        fun run(
            context: Context,
            payloadFile: File,
            expectedSha256: String,
            entryClass: String,
            entryMethod: String,
            requestJson: String
        ): Bundle {
            require(payloadFile.isFile) { "Managed JVM tool payload is missing" }
            require(payloadFile.length() in 1..MAX_PAYLOAD_BYTES) {
                "Managed JVM tool payload size is out of bounds"
            }
            require(expectedSha256.matches(Regex("^[0-9a-f]{64}$"))) {
                "Managed JVM tool SHA-256 is invalid"
            }
            require(entryClass.matches(Regex("^[A-Za-z_$][A-Za-z0-9_$.]{0,199}$"))) {
                "Managed JVM tool entry class is invalid"
            }
            require(entryMethod.matches(Regex("^[A-Za-z_$][A-Za-z0-9_$]{0,79}$"))) {
                "Managed JVM tool entry method is invalid"
            }
            require(requestJson.toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_BYTES) {
                "Managed JVM tool request exceeds 512 KiB"
            }

            val app = context.applicationContext
            val connected = CompletableFuture<IBinder>()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    if (service != null) connected.complete(service)
                    else connected.completeExceptionally(
                        IllegalStateException("Managed JVM tool binder missing")
                    )
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    if (!connected.isDone) {
                        connected.completeExceptionally(
                            IllegalStateException("Managed JVM tool disconnected")
                        )
                    }
                }

                override fun onBindingDied(name: ComponentName?) {
                    if (!connected.isDone) {
                        connected.completeExceptionally(
                            IllegalStateException("Managed JVM tool binding died")
                        )
                    }
                }

                override fun onNullBinding(name: ComponentName?) {
                    if (!connected.isDone) {
                        connected.completeExceptionally(
                            IllegalStateException("Managed JVM tool null binding")
                        )
                    }
                }
            }

            require(
                app.bindService(
                    Intent(app, RiftManagedJvmToolService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE
                )
            ) { "Could not bind managed JVM tool service" }

            var remotePid = -1
            val executor = Executors.newSingleThreadExecutor()
            try {
                val binder = connected.get(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                remotePid = readRemotePid(binder)
                val future = executor.submit<Bundle> {
                    transactRun(
                        binder,
                        payloadFile.absolutePath,
                        expectedSha256,
                        entryClass,
                        entryMethod,
                        requestJson
                    )
                }
                return try {
                    future.get(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                } catch (interrupted: InterruptedException) {
                    future.cancel(true)
                    if (remotePid > 0) runCatching { Process.killProcess(remotePid) }
                    Thread.currentThread().interrupt()
                    Bundle().apply {
                        putString("status", "cancelled")
                        putString("detail", "Managed JVM tool was cancelled")
                    }
                } catch (timeout: TimeoutException) {
                    future.cancel(true)
                    if (remotePid > 0) runCatching { Process.killProcess(remotePid) }
                    Bundle().apply {
                        putString("status", "timeout")
                        putString("detail", "Managed JVM tool exceeded ${RUN_TIMEOUT_SECONDS}s")
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
                    "Managed JVM tool PID transaction failed"
                }
                reply.readException()
                reply.readInt()
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

        private fun transactRun(
            binder: IBinder,
            payloadPath: String,
            sha256: String,
            entryClass: String,
            entryMethod: String,
            requestJson: String
        ): Bundle {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeString(payloadPath)
                data.writeString(sha256)
                data.writeString(entryClass)
                data.writeString(entryMethod)
                data.writeString(requestJson)
                require(binder.transact(TRANSACTION_RUN, data, reply, 0)) {
                    "Managed JVM tool transaction failed"
                }
                reply.readException()
                reply.readBundle(RiftManagedJvmToolService::class.java.classLoader)
                    ?: Bundle().apply {
                        putString("status", "host-reject")
                        putString("detail", "Managed JVM tool returned no bundle")
                    }
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }

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

                TRANSACTION_RUN -> {
                    data.enforceInterface(DESCRIPTOR)
                    val result = execute(
                        data.readString().orEmpty(),
                        data.readString().orEmpty(),
                        data.readString().orEmpty(),
                        data.readString().orEmpty(),
                        data.readString().orEmpty()
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

    private fun execute(
        payloadPath: String,
        expectedSha256: String,
        entryClass: String,
        entryMethod: String,
        requestJson: String
    ): Bundle {
        return runCatching {
            require(expectedSha256.matches(Regex("^[0-9a-f]{64}$"))) {
                "Managed JVM tool SHA-256 is invalid"
            }
            require(entryClass.matches(Regex("^[A-Za-z_$][A-Za-z0-9_$.]{0,199}$"))) {
                "Managed JVM tool entry class is invalid"
            }
            require(entryMethod.matches(Regex("^[A-Za-z_$][A-Za-z0-9_$]{0,79}$"))) {
                "Managed JVM tool entry method is invalid"
            }
            require(requestJson.toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_BYTES) {
                "Managed JVM tool request exceeds 512 KiB"
            }

            val source = File(payloadPath).canonicalFile
            require(source.isFile && source.length() in 1..MAX_PAYLOAD_BYTES) {
                "Managed JVM tool payload is missing or oversized"
            }
            require(sha256(source) == expectedSha256) {
                "Managed JVM tool payload SHA-256 mismatch"
            }

            val cacheRoot = File(codeCacheDir, "rift-managed-jvm").apply { mkdirs() }.canonicalFile
            require(cacheRoot.isDirectory) { "Managed JVM tool cache is unavailable" }
            val cached = File(cacheRoot, "$expectedSha256.apk").canonicalFile
            require(cached.toPath().startsWith(cacheRoot.toPath())) {
                "Managed JVM tool cache path escaped"
            }
            if (!cached.isFile || cached.length() != source.length() || sha256(cached) != expectedSha256) {
                val temp = File(cacheRoot, ".$expectedSha256.tmp").canonicalFile
                source.inputStream().buffered().use { input ->
                    temp.outputStream().buffered().use { output ->
                        input.copyTo(output, 64 * 1024)
                    }
                }
                require(sha256(temp) == expectedSha256) {
                    "Managed JVM tool cached copy SHA-256 mismatch"
                }
                if (cached.exists()) require(cached.delete()) {
                    "Could not replace managed JVM tool cache"
                }
                require(temp.renameTo(cached)) {
                    "Could not commit managed JVM tool cache"
                }
            }

            val optimized = File(cacheRoot, "oat-$expectedSha256").apply { mkdirs() }
            val frameworkParent = applicationContext.classLoader.parent
                ?: ClassLoader.getSystemClassLoader()
            val loader = DexClassLoader(
                cached.absolutePath,
                optimized.absolutePath,
                null,
                frameworkParent
            )
            val clazz = loader.loadClass(entryClass)
            val method = clazz.getMethod(entryMethod, String::class.java)
            require(Modifier.isPublic(method.modifiers) && Modifier.isStatic(method.modifiers)) {
                "Managed JVM tool entrypoint must be public static"
            }
            val raw = method.invoke(null, requestJson)
            require(raw is String) { "Managed JVM tool entrypoint must return String" }
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_BYTES) {
                "Managed JVM tool response exceeds 512 KiB"
            }

            Bundle().apply {
                putString("status", "success")
                putString("responseJson", raw)
                putString("payloadSha256", expectedSha256)
                putString("entryClass", entryClass)
                putString("entryMethod", entryMethod)
            }
        }.getOrElse { error ->
            val cause = error.cause ?: error
            Bundle().apply {
                putString("status", "tool-error")
                putString("errorClass", cause.javaClass.name)
                putString("detail", cause.message ?: cause.javaClass.simpleName)
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
