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
 * RiftOS transport client for the installed Codynex Editor bridge.
 *
 * RiftOS only transports bytes/requests. Compilation, preview, packing and signing
 * remain inside com.codynex.editor.
 */
class RiftCodynexEditorBridgeClient(context: Context) {
    companion object {
        private const val EDITOR_PACKAGE = "com.codynex.editor"
        private const val EDITOR_SERVICE =
            "com.codynex.editorapp.CodynexEditorBridgeService"
        private const val DESCRIPTOR = "com.codynex.editor.bridge.v1"

        private const val TRANSACTION_EXECUTE = IBinder.FIRST_CALL_TRANSACTION
        private const val TRANSACTION_OPEN_READ = IBinder.FIRST_CALL_TRANSACTION + 1
        private const val TRANSACTION_OPEN_WRITE = IBinder.FIRST_CALL_TRANSACTION + 2

        private const val BIND_TIMEOUT_MS = 8_000L
        private const val MAX_JSON_BYTES = 256 * 1024
        private const val MAX_FILE_BYTES = 16L * 1024L * 1024L
        private const val MAX_TREE_BYTES = 64L * 1024L * 1024L
        private const val MAX_TREE_ENTRIES = 4096
        private const val MAX_TREE_DEPTH = 32
    }

    private val appContext = context.applicationContext

    fun execute(request: JSONObject): JSONObject {
        val requestText = request.toString()
        require(
            requestText.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES
        ) {
            "Codynex editor bridge request exceeds $MAX_JSON_BYTES bytes"
        }

        return withRemote { remote ->
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeString(requestText)

                require(
                    remote.transact(
                        TRANSACTION_EXECUTE,
                        data,
                        reply,
                        0
                    )
                ) {
                    "Codynex editor bridge execute transaction rejected"
                }

                reply.readException()
                val responseText = reply.readString()
                    ?: error("Codynex editor bridge returned no response")
                require(
                    responseText.toByteArray(Charsets.UTF_8).size <=
                        MAX_JSON_BYTES
                ) {
                    "Codynex editor bridge response exceeds $MAX_JSON_BYTES bytes"
                }
                JSONObject(responseText)
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }

    fun readText(editorPath: String): String =
        readBytes(editorPath).toString(Charsets.UTF_8)

    fun writeText(editorPath: String, text: String): JSONObject =
        execute(
            JSONObject()
                .put("op", "write")
                .put("path", editorPath)
                .put("text", text)
        )

    fun push(source: File, editorPath: String): JSONObject {
        require(source.isFile) {
            "local source is not a file"
        }
        require(source.length() <= MAX_FILE_BYTES) {
            "Codynex editor push exceeds $MAX_FILE_BYTES bytes"
        }

        openDescriptor(
            TRANSACTION_OPEN_WRITE,
            editorPath
        ).use { descriptor ->
            FileOutputStream(descriptor.fileDescriptor).use { output ->
                source.inputStream().buffered().use { input ->
                    copyBounded(input, output, MAX_FILE_BYTES)
                }
            }
        }

        return execute(
            JSONObject()
                .put("op", "stat")
                .put("path", editorPath)
        )
    }

    fun pull(editorPath: String, destination: File): JSONObject {
        val parent = destination.parentFile
            ?: error("local destination has no parent")
        require(parent.mkdirs() || parent.isDirectory) {
            "could not create local destination parent"
        }

        val temp = File(
            parent,
            "." + destination.name + ".codynex-editor-pull.tmp"
        )

        openDescriptor(
            TRANSACTION_OPEN_READ,
            editorPath
        ).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { input ->
                FileOutputStream(temp).use { output ->
                    copyBounded(input, output, MAX_FILE_BYTES)
                }
            }
        }

        if (destination.exists()) {
            require(destination.delete()) {
                "could not replace local destination"
            }
        }
        require(temp.renameTo(destination)) {
            temp.delete()
            "could not publish pulled editor file"
        }

        return execute(
            JSONObject()
                .put("op", "stat")
                .put("path", editorPath)
        )
            .put("localBytes", destination.length())
    }

    fun pushDirectory(source: File, editorPath: String): JSONObject {
        require(source.isDirectory) {
            "local push source is not a directory"
        }
        val root = source.canonicalFile
        val queue = ArrayDeque<Pair<File, String>>()
        queue.add(root to normalizeEditorPath(editorPath))

        var entries = 0
        var files = 0
        var directories = 0
        var totalBytes = 0L

        while (queue.isNotEmpty()) {
            val (current, target) = queue.removeFirst()
            val relative = current.canonicalFile
                .relativeTo(root)
                .invariantSeparatorsPath
            val depth = if (relative.isBlank()) 0
            else relative.split('/').size
            require(depth <= MAX_TREE_DEPTH) {
                "Codynex editor folder push exceeds depth $MAX_TREE_DEPTH"
            }

            execute(
                JSONObject()
                    .put("op", "mkdir")
                    .put("path", target)
            )
            directories += 1

            val children = current.listFiles()
                ?.sortedWith(
                    compareByDescending<File> { it.isDirectory }
                        .thenBy { it.name.lowercase() }
                )
                .orEmpty()

            for (child in children) {
                entries += 1
                require(entries <= MAX_TREE_ENTRIES) {
                    "Codynex editor folder push exceeds $MAX_TREE_ENTRIES entries"
                }
                val canonicalChild = child.canonicalFile
                val rootPrefix = root.path + File.separator
                require(
                    canonicalChild.path.startsWith(rootPrefix)
                ) {
                    "Codynex editor folder push escaped selected source tree"
                }
                val childTarget =
                    target.trimEnd('/') + "/" + child.name
                if (child.isDirectory) {
                    queue.add(child to childTarget)
                } else {
                    require(child.isFile) {
                        "folder push encountered unsupported entry: ${child.path}"
                    }
                    require(child.length() <= MAX_FILE_BYTES) {
                        "folder push file exceeds $MAX_FILE_BYTES bytes: ${child.name}"
                    }
                    totalBytes += child.length()
                    require(totalBytes <= MAX_TREE_BYTES) {
                        "Codynex editor folder push exceeds $MAX_TREE_BYTES bytes"
                    }
                    push(child, childTarget)
                    files += 1
                }
            }
        }

        return JSONObject()
            .put("schema", "codynex-editor-folder-push/1")
            .put("editorPath", normalizeEditorPath(editorPath))
            .put("entries", entries)
            .put("files", files)
            .put("directories", directories)
            .put("bytes", totalBytes)
    }

    fun pullDirectory(editorPath: String, destination: File): JSONObject {
        val rootPath = normalizeEditorPath(editorPath)
        val rootStat = execute(
            JSONObject()
                .put("op", "stat")
                .put("path", rootPath)
        )
        require(rootStat.optBoolean("directory", false)) {
            "editor pull source is not a directory"
        }

        val root = destination.canonicalFile
        require(root.mkdirs() || root.isDirectory) {
            "could not create local folder destination"
        }

        val listed = execute(JSONObject().put("op", "list"))
            .getJSONArray("entries")

        var entries = 0
        var files = 0
        var directories = 0
        var totalBytes = 0L
        val prefix = rootPath.trimEnd('/') + "/"

        for (index in 0 until listed.length()) {
            val item = listed.getJSONObject(index)
            val path = normalizeEditorPath(item.getString("path"))
            if (!path.startsWith(prefix)) continue

            val suffix = path.removePrefix(prefix)
            if (suffix.isBlank()) continue
            val segments = suffix.split('/')
            require(
                segments.none { it.isBlank() || it == "." || it == ".." }
            ) {
                "Codynex editor returned unsafe folder entry"
            }
            require(segments.size <= MAX_TREE_DEPTH) {
                "Codynex editor folder pull exceeds depth $MAX_TREE_DEPTH"
            }

            entries += 1
            require(entries <= MAX_TREE_ENTRIES) {
                "Codynex editor folder pull exceeds $MAX_TREE_ENTRIES entries"
            }

            val local = File(root, suffix).canonicalFile
            val localPrefix = root.path + File.separator
            require(local.path.startsWith(localPrefix)) {
                "Codynex editor folder pull escaped destination"
            }

            if (item.getBoolean("directory")) {
                require(local.mkdirs() || local.isDirectory) {
                    "could not create pulled directory"
                }
                directories += 1
            } else {
                pull(path, local)
                totalBytes += local.length()
                require(totalBytes <= MAX_TREE_BYTES) {
                    "Codynex editor folder pull exceeds $MAX_TREE_BYTES bytes"
                }
                files += 1
            }
        }

        return JSONObject()
            .put("schema", "codynex-editor-folder-pull/1")
            .put("editorPath", rootPath)
            .put("entries", entries)
            .put("files", files)
            .put("directories", directories)
            .put("bytes", totalBytes)
    }

    private fun readBytes(editorPath: String): ByteArray {
        val output = ByteArrayOutputStream()
        openDescriptor(
            TRANSACTION_OPEN_READ,
            editorPath
        ).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { input ->
                copyBounded(input, output, MAX_FILE_BYTES)
            }
        }
        return output.toByteArray()
    }

    private fun openDescriptor(
        transaction: Int,
        editorPath: String
    ): ParcelFileDescriptor =
        withRemote { remote ->
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeString(normalizeEditorPath(editorPath))

                require(
                    remote.transact(
                        transaction,
                        data,
                        reply,
                        0
                    )
                ) {
                    "Codynex editor bridge file transaction rejected"
                }

                reply.readException()
                require(reply.readInt() == 1) {
                    "Codynex editor bridge returned no file descriptor"
                }
                ParcelFileDescriptor.CREATOR.createFromParcel(reply)
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

    private fun <T> withRemote(block: (IBinder) -> T): T {
        val latch = CountDownLatch(1)
        val remote = AtomicReference<IBinder?>(null)

        val connection = object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                service: IBinder
            ) {
                remote.set(service)
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                remote.set(null)
            }

            override fun onBindingDied(name: ComponentName) {
                remote.set(null)
                latch.countDown()
            }

            override fun onNullBinding(name: ComponentName) {
                remote.set(null)
                latch.countDown()
            }
        }

        val intent = Intent().setComponent(
            ComponentName(
                EDITOR_PACKAGE,
                EDITOR_SERVICE
            )
        )

        val bound = appContext.bindService(
            intent,
            connection,
            Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
        )
        require(bound) {
            "Codynex editor bridge is unavailable. Install the bridge-enabled editor."
        }

        try {
            require(
                latch.await(
                    BIND_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS
                )
            ) {
                "Codynex editor bridge bind timed out"
            }

            val binder = remote.get()
                ?.takeIf { it.isBinderAlive }
                ?: error("Codynex editor bridge binder is unavailable")
            return block(binder)
        } finally {
            runCatching {
                appContext.unbindService(connection)
            }
        }
    }

    private fun copyBounded(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        maxBytes: Long
    ): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count.toLong()
            require(total <= maxBytes) {
                "Codynex editor transfer exceeds $maxBytes bytes"
            }
            output.write(buffer, 0, count)
        }
        return total
    }

    private fun normalizeEditorPath(path: String): String {
        val clean = path.trim().replace('\\', '/').trim('/')
        require(clean.isNotBlank()) {
            "editor path must not be blank"
        }
        require(!Regex("^[A-Za-z]:").containsMatchIn(clean)) {
            "editor path must be workspace-relative"
        }
        val segments = clean.split('/')
        require(
            segments.none { it.isBlank() || it == "." || it == ".." }
        ) {
            "editor path is not canonical"
        }
        require(segments.size <= MAX_TREE_DEPTH) {
            "editor path exceeds depth $MAX_TREE_DEPTH"
        }
        return segments.joinToString("/")
    }
}
