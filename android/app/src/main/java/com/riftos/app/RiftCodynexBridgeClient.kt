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

/** Explicit local Binder client for the Codynex LR0 lab bridge. */
class RiftCodynexBridgeClient(context: Context) {
    companion object {
        private const val CODYNEX_PACKAGE =
            "com.codynex.lr0lab"
        private const val CODYNEX_SERVICE =
            "com.codynex.lr0lab.CodynexBridgeService"
        private const val DESCRIPTOR =
            "com.codynex.lr0lab.bridge.v1"
        private const val TRANSACTION_EXECUTE =
            IBinder.FIRST_CALL_TRANSACTION
        private const val BIND_TIMEOUT_MS = 8_000L
        private const val TRANSACTION_TIMEOUT_MS = 12_000L
        private const val MAX_REQUEST_JSON_BYTES =
            96 * 1024
        private const val MAX_RESPONSE_JSON_BYTES =
            192 * 1024
    }

    private val appContext =
        context.applicationContext
    private val lock = Any()

    private val rpcExecutor =
        ThreadPoolExecutor(
            0,
            2,
            30L,
            TimeUnit.SECONDS,
            SynchronousQueue()
        )

    @Volatile
    private var remote: IBinder? = null

    @Volatile
    private var bound = false

    @Volatile
    private var bindLatch: CountDownLatch? = null

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                service: IBinder
            ) {
                remote = service
                bindLatch?.countDown()
            }

            override fun onServiceDisconnected(
                name: ComponentName
            ) {
                remote = null
            }

            override fun onBindingDied(
                name: ComponentName
            ) {
                remote = null
                bindLatch?.countDown()
            }

            override fun onNullBinding(
                name: ComponentName
            ) {
                remote = null
                bindLatch?.countDown()
            }
        }

    fun execute(
        request: JSONObject
    ): JSONObject {
        val copy = JSONObject(request.toString())

        require(
            copy.optString("op").isNotBlank()
        ) {
            "Codynex bridge op is required"
        }

        val bytes =
            copy.toString()
                .toByteArray(Charsets.UTF_8)
                .size

        require(bytes <= MAX_REQUEST_JSON_BYTES) {
            "Codynex bridge request exceeds " +
                "$MAX_REQUEST_JSON_BYTES UTF-8 bytes"
        }

        return transactWithReconnect(copy)
    }

    fun close() {
        synchronized(lock) {
            remote = null
            bindLatch = null

            if (bound) {
                runCatching {
                    appContext.unbindService(connection)
                }
            }

            bound = false
        }

        rpcExecutor.shutdownNow()
    }

    private fun ensureRemote(): IBinder {
        remote
            ?.takeIf { it.isBinderAlive }
            ?.let { return it }

        val latch: CountDownLatch

        synchronized(lock) {
            remote
                ?.takeIf { it.isBinderAlive }
                ?.let { return it }

            if (bound) {
                runCatching {
                    appContext.unbindService(connection)
                }
                bound = false
            }

            latch = CountDownLatch(1)
            bindLatch = latch

            val intent =
                Intent().setComponent(
                    ComponentName(
                        CODYNEX_PACKAGE,
                        CODYNEX_SERVICE
                    )
                )

            bound =
                appContext.bindService(
                    intent,
                    connection,
                    Context.BIND_AUTO_CREATE or
                        Context.BIND_IMPORTANT
                )

            if (!bound) {
                bindLatch = null
                throw IllegalStateException(
                    "Codynex LR0 bridge is unavailable. " +
                        "Install the bridge-enabled LR0 APK."
                )
            }
        }

        if (
            !latch.await(
                BIND_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )
        ) {
            synchronized(lock) {
                bindLatch = null

                if (bound) {
                    runCatching {
                        appContext.unbindService(connection)
                    }
                }

                bound = false
                remote = null
            }

            throw IllegalStateException(
                "Timed out binding to Codynex LR0 bridge"
            )
        }

        bindLatch = null

        return remote
            ?.takeIf { it.isBinderAlive }
            ?: throw IllegalStateException(
                "Codynex LR0 bridge returned no live Binder"
            )
    }

    private fun transactWithReconnect(
        request: JSONObject
    ): JSONObject =
        try {
            transactBounded(
                ensureRemote(),
                request
            )
        } catch (error: Throwable) {
            synchronized(lock) {
                remote = null

                if (bound) {
                    runCatching {
                        appContext.unbindService(connection)
                    }
                }

                bound = false
            }

            transactBounded(
                ensureRemote(),
                request
            )
        }

    private fun transactBounded(
        service: IBinder,
        request: JSONObject
    ): JSONObject {
        val future =
            try {
                rpcExecutor.submit<JSONObject> {
                    transact(service, request)
                }
            } catch (
                error:
                    java.util.concurrent
                        .RejectedExecutionException
            ) {
                throw IllegalStateException(
                    "Codynex bridge RPC workers are occupied",
                    error
                )
            }

        return try {
            future.get(
                TRANSACTION_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )
        } catch (error: TimeoutException) {
            future.cancel(true)

            throw IllegalStateException(
                "Codynex bridge transaction timed out after " +
                    "${TRANSACTION_TIMEOUT_MS}ms",
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

    private fun transact(
        service: IBinder,
        request: JSONObject
    ): JSONObject {
        val requestText = request.toString()

        require(
            requestText.toByteArray(Charsets.UTF_8).size <=
                MAX_REQUEST_JSON_BYTES
        ) {
            "Codynex bridge request exceeds " +
                "$MAX_REQUEST_JSON_BYTES UTF-8 bytes"
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
            ) {
                "Codynex bridge rejected Binder transaction"
            }

            reply.readException()

            val raw =
                reply.readString()
                    ?: throw IllegalStateException(
                        "Codynex bridge returned an empty response"
                    )

            require(
                raw.toByteArray(Charsets.UTF_8).size <=
                    MAX_RESPONSE_JSON_BYTES
            ) {
                "Codynex bridge response exceeds " +
                    "$MAX_RESPONSE_JSON_BYTES UTF-8 bytes"
            }

            return JSONObject(raw)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
