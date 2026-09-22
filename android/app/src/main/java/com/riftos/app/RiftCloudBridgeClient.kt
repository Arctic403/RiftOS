package com.riftos.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Explicit Binder client for the RiftCloud account/Worker management bridge. */
class RiftCloudBridgeClient(context: Context) {
    companion object {
        private const val RIFTCLOUD_PACKAGE = "com.riftcloud.app"
        private const val RIFTCLOUD_SERVICE = "com.riftcloud.app.RiftCloudBridgeService"
        private const val DESCRIPTOR = "com.riftcloud.app.bridge.v1"
        private const val TRANSACTION_EXECUTE = IBinder.FIRST_CALL_TRANSACTION
        private const val BIND_TIMEOUT_MS = 8_000L
        private const val TRANSACTION_TIMEOUT_MS = 40_000L
        private const val MAX_REQUEST_JSON_BYTES = 256 * 1024
        private const val MAX_RESPONSE_JSON_BYTES = 384 * 1024
    }

    private val appContext = context.applicationContext
    private val lock = Any()
    private val rpcExecutor = ThreadPoolExecutor(
        0,
        2,
        30L,
        TimeUnit.SECONDS,
        SynchronousQueue()
    )

    @Volatile private var remote: IBinder? = null
    @Volatile private var bound = false
    @Volatile private var bindLatch: CountDownLatch? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            remote = service
            bindLatch?.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            remote = null
        }

        override fun onBindingDied(name: ComponentName) {
            remote = null
            bindLatch?.countDown()
        }

        override fun onNullBinding(name: ComponentName) {
            remote = null
            bindLatch?.countDown()
        }
    }

    fun execute(request: JSONObject): JSONObject {
        val copy = JSONObject(request.toString())
        require(copy.optString("op").isNotBlank()) { "RiftCloud bridge op is required" }
        require(copy.toString().toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_JSON_BYTES) {
            "RiftCloud bridge request exceeds $MAX_REQUEST_JSON_BYTES UTF-8 bytes"
        }
        return transactWithReconnect(copy)
    }

    fun close() {
        synchronized(lock) {
            remote = null
            bindLatch = null
            if (bound) runCatching { appContext.unbindService(connection) }
            bound = false
        }
        rpcExecutor.shutdownNow()
    }

    private fun ensureRemote(): IBinder {
        remote?.takeIf { it.isBinderAlive }?.let { return it }

        val latch: CountDownLatch
        synchronized(lock) {
            remote?.takeIf { it.isBinderAlive }?.let { return it }
            if (bound) {
                runCatching { appContext.unbindService(connection) }
                bound = false
            }

            latch = CountDownLatch(1)
            bindLatch = latch
            val intent = Intent().setComponent(
                ComponentName(RIFTCLOUD_PACKAGE, RIFTCLOUD_SERVICE)
            )
            bound = appContext.bindService(
                intent,
                connection,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
            )
            if (!bound) {
                bindLatch = null
                throw IllegalStateException(
                    "RiftCloud bridge is unavailable. Install/open the bridge-enabled RiftCloud APK."
                )
            }
        }

        if (!latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            synchronized(lock) {
                bindLatch = null
                if (bound) runCatching { appContext.unbindService(connection) }
                bound = false
                remote = null
            }
            throw IllegalStateException("Timed out binding to RiftCloud bridge")
        }

        bindLatch = null
        return remote?.takeIf { it.isBinderAlive }
            ?: throw IllegalStateException("RiftCloud bridge returned no live Binder")
    }

    private fun transactWithReconnect(request: JSONObject): JSONObject =
        try {
            transactBounded(ensureRemote(), request)
        } catch (error: Throwable) {
            synchronized(lock) {
                remote = null
                if (bound) runCatching { appContext.unbindService(connection) }
                bound = false
            }
            transactBounded(ensureRemote(), request)
        }

    private fun transactBounded(service: IBinder, request: JSONObject): JSONObject {
        val future = try {
            rpcExecutor.submit<JSONObject> { transact(service, request) }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            throw IllegalStateException(
                "RiftCloud bridge RPC workers are occupied by stalled Binder calls",
                error
            )
        }

        return try {
            future.get(TRANSACTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            future.cancel(true)
            throw IllegalStateException(
                "RiftCloud bridge transaction timed out after ${TRANSACTION_TIMEOUT_MS}ms",
                error
            )
        } catch (error: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw error
        } catch (error: ExecutionException) {
            throw (error.cause ?: error)
        }
    }

    private fun transact(service: IBinder, request: JSONObject): JSONObject {
        val requestText = request.toString()
        require(requestText.toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_JSON_BYTES) {
            "RiftCloud bridge request exceeds $MAX_REQUEST_JSON_BYTES UTF-8 bytes"
        }

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(requestText)
            require(
                service.transact(
                    TRANSACTION_EXECUTE,
                    data,
                    reply,
                    0
                )
            ) { "RiftCloud bridge rejected Binder transaction" }
            reply.readException()
            val raw = reply.readString()
                ?: throw IllegalStateException("RiftCloud bridge returned an empty response")
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_JSON_BYTES) {
                "RiftCloud bridge response exceeds $MAX_RESPONSE_JSON_BYTES UTF-8 bytes"
            }
            return JSONObject(raw)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
