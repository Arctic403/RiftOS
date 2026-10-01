package com.riftos.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Explicit Binder client for the bounded legacy Rift++ editor development bridge.
 *
 * This client only transports requests/bytes. Rift++ compilation, preview, packaging
 * and signing remain inside the installed Rift++ editor process.
 */
class RiftppEditorBridgeClient(context: Context) {
    companion object {
        private const val EDITOR_PACKAGE =
            "com.riftpp.editor"
        private const val EDITOR_SERVICE =
            "com.riftpp.editor.RiftppEditorBridgeService"
        private const val DESCRIPTOR =
            "com.riftpp.editor.bridge.v1"

        private const val TRANSACTION_EXECUTE =
            IBinder.FIRST_CALL_TRANSACTION
        private const val TRANSACTION_OPEN_READ =
            IBinder.FIRST_CALL_TRANSACTION + 1
        private const val TRANSACTION_OPEN_WRITE =
            IBinder.FIRST_CALL_TRANSACTION + 2

        private const val BIND_TIMEOUT_MS =
            8_000L
        private const val MAX_JSON_BYTES =
            256 * 1024
        private const val MAX_TRANSFER_BYTES =
            16L * 1024L * 1024L
    }

    private val appContext =
        context.applicationContext

    fun execute(
        request: JSONObject
    ): JSONObject {
        val requestText =
            request.toString()

        require(
            requestText
                .toByteArray(
                    Charsets.UTF_8
                ).size <=
                MAX_JSON_BYTES
        ) {
            "Rift++ editor bridge request exceeds $MAX_JSON_BYTES bytes"
        }

        return withRemote { remote ->
            val data =
                Parcel.obtain()
            val reply =
                Parcel.obtain()

            try {
                data.writeInterfaceToken(
                    DESCRIPTOR
                )
                data.writeString(
                    requestText
                )

                require(
                    remote.transact(
                        TRANSACTION_EXECUTE,
                        data,
                        reply,
                        0
                    )
                ) {
                    "Rift++ editor bridge execute transaction rejected"
                }

                reply.readException()

                val responseText =
                    reply.readString()
                        ?: error(
                            "Rift++ editor bridge returned no response"
                        )

                require(
                    responseText
                        .toByteArray(
                            Charsets.UTF_8
                        ).size <=
                        MAX_JSON_BYTES
                ) {
                    "Rift++ editor bridge response exceeds $MAX_JSON_BYTES bytes"
                }

                JSONObject(
                    responseText
                )
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }

    fun readText(
        editorPath: String
    ): String =
        readBytes(editorPath)
            .toString(
                Charsets.UTF_8
            )

    fun writeText(
        editorPath: String,
        text: String
    ): JSONObject =
        execute(
            JSONObject()
                .put("op", "write")
                .put(
                    "path",
                    editorPath
                )
                .put("text", text)
        )

    fun push(
        source: File,
        editorPath: String
    ): JSONObject {
        require(source.isFile) {
            "local source is not a file"
        }
        require(
            source.length() <=
                MAX_TRANSFER_BYTES
        ) {
            "Rift++ editor push exceeds $MAX_TRANSFER_BYTES bytes"
        }

        openDescriptor(
            TRANSACTION_OPEN_WRITE,
            editorPath
        ).use { descriptor ->
            FileOutputStream(
                descriptor.fileDescriptor
            ).use { output ->
                source.inputStream()
                    .buffered()
                    .use { input ->
                        copyBounded(
                            input = input,
                            output = output,
                            maxBytes =
                                MAX_TRANSFER_BYTES
                        )
                    }
            }
        }

        return execute(
            JSONObject()
                .put("op", "stat")
                .put(
                    "path",
                    editorPath
                )
        )
    }

    fun pull(
        editorPath: String,
        destination: File
    ): JSONObject {
        val parent =
            destination.parentFile
                ?: error(
                    "local destination has no parent"
                )

        require(
            parent.mkdirs() ||
                parent.isDirectory
        ) {
            "could not create local destination parent"
        }

        val temp =
            File(
                parent,
                "." +
                    destination.name +
                    ".riftpp-editor-pull.tmp"
            )

        openDescriptor(
            TRANSACTION_OPEN_READ,
            editorPath
        ).use { descriptor ->
            FileInputStream(
                descriptor.fileDescriptor
            ).use { input ->
                FileOutputStream(
                    temp
                ).use { output ->
                    copyBounded(
                        input = input,
                        output = output,
                        maxBytes =
                            MAX_TRANSFER_BYTES
                    )
                }
            }
        }

        if (destination.exists()) {
            require(
                destination.delete()
            ) {
                "could not replace local destination"
            }
        }

        require(
            temp.renameTo(
                destination
            )
        ) {
            temp.delete()
            "could not publish pulled editor file"
        }

        return execute(
            JSONObject()
                .put("op", "stat")
                .put(
                    "path",
                    editorPath
                )
        )
            .put(
                "localBytes",
                destination.length()
            )
    }

    private fun readBytes(
        editorPath: String
    ): ByteArray {
        val output =
            ByteArrayOutputStream()

        openDescriptor(
            TRANSACTION_OPEN_READ,
            editorPath
        ).use { descriptor ->
            FileInputStream(
                descriptor.fileDescriptor
            ).use { input ->
                copyBounded(
                    input = input,
                    output = output,
                    maxBytes =
                        MAX_TRANSFER_BYTES
                )
            }
        }

        return output.toByteArray()
    }

    private fun openDescriptor(
        transaction: Int,
        editorPath: String
    ): ParcelFileDescriptor =
        withRemote { remote ->
            val data =
                Parcel.obtain()
            val reply =
                Parcel.obtain()

            try {
                data.writeInterfaceToken(
                    DESCRIPTOR
                )
                data.writeString(
                    editorPath
                )

                require(
                    remote.transact(
                        transaction,
                        data,
                        reply,
                        0
                    )
                ) {
                    "Rift++ editor bridge file transaction rejected"
                }

                reply.readException()

                require(
                    reply.readInt() == 1
                ) {
                    "Rift++ editor bridge returned no file descriptor"
                }

                ParcelFileDescriptor
                    .CREATOR
                    .createFromParcel(
                        reply
                    )
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

    private fun <T> withRemote(
        block: (IBinder) -> T
    ): T {
        val latch =
            CountDownLatch(1)

        val remote =
            AtomicReference<IBinder?>(
                null
            )

        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName,
                    service: IBinder
                ) {
                    remote.set(
                        service
                    )
                    latch.countDown()
                }

                override fun onServiceDisconnected(
                    name: ComponentName
                ) {
                    remote.set(
                        null
                    )
                }

                override fun onBindingDied(
                    name: ComponentName
                ) {
                    remote.set(
                        null
                    )
                    latch.countDown()
                }

                override fun onNullBinding(
                    name: ComponentName
                ) {
                    remote.set(
                        null
                    )
                    latch.countDown()
                }
            }

        val intent =
            Intent()
                .setComponent(
                    ComponentName(
                        EDITOR_PACKAGE,
                        EDITOR_SERVICE
                    )
                )

        val bound =
            appContext.bindService(
                intent,
                connection,
                Context.BIND_AUTO_CREATE or
                    Context.BIND_IMPORTANT
            )

        require(bound) {
            "Rift++ editor bridge is unavailable. Install the bridge-enabled legacy editor."
        }

        try {
            require(
                latch.await(
                    BIND_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS
                )
            ) {
                "Rift++ editor bridge bind timed out"
            }

            val binder =
                remote.get()
                    ?.takeIf {
                        it.isBinderAlive
                    }
                    ?: error(
                        "Rift++ editor bridge binder is unavailable"
                    )

            return block(binder)
        } finally {
            runCatching {
                appContext.unbindService(
                    connection
                )
            }
        }
    }

    private fun copyBounded(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        maxBytes: Long
    ): Long {
        val buffer =
            ByteArray(
                64 * 1024
            )
        var total =
            0L

        while (true) {
            val count =
                input.read(
                    buffer
                )

            if (count < 0) {
                break
            }
            if (count == 0) {
                continue
            }

            total +=
                count.toLong()

            require(
                total <= maxBytes
            ) {
                "Rift++ editor transfer exceeds $maxBytes bytes"
            }

            output.write(
                buffer,
                0,
                count
            )
        }

        return total
    }
}
