package com.riftos.app

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLConnection
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream

class RiftNativeDispatcher(
    private val activity: Activity,
    private val resultSink: (String, Boolean, Any?, String?) -> Unit,
    private val progressSink: (JSONObject) -> Unit,
    private val directoryPicker: (String) -> Unit,
    private val notificationPermissionRequester: (String) -> Unit
) {
    private val executor = Executors.newSingleThreadExecutor()
    // Long-running file transfers use their own worker so normal FS/UI RPCs stay responsive.
    private val transferExecutor = Executors.newSingleThreadExecutor()
    private val transferService = RiftTransferRegistry.service
    private val prefs = activity.getSharedPreferences("rift-native", Context.MODE_PRIVATE)
    private val secrets = RiftSecretStore(activity)
    private val riftRoot = File(activity.filesDir, "riftfs").apply {
        mkdirs()
        listOf("home", "apps", "system", "workspace", "downloads", "documents").forEach { File(this, it).mkdirs() }
    }
    companion object {
        private const val MAX_BRIDGE_TEXT_BYTES = 4L * 1024L * 1024L
        private const val COPY_BUFFER_BYTES = 256 * 1024
    }

    private val protectedRiftRoots = setOf("home", "apps", "system", "workspace", "downloads", "documents")

    private data class TransferProgress(
        val id: String,
        val operation: String,
        var bytes: Long = 0L,
        var files: Int = 0,
        var directories: Int = 0,
        var totalFiles: Long = 0L,
        var totalDirectories: Long = 0L,
        var totalBytes: Long = 0L,
        var lastEmit: Long = 0L
    )

    private fun emitTransfer(progress: TransferProgress?, phase: String, currentPath: String, force: Boolean = false) {
        if (progress == null || progress.id.isBlank()) return
        val now = System.currentTimeMillis()
        if (!force && now - progress.lastEmit < 100L) return
        progress.lastEmit = now
        progressSink(JSONObject()
            .put("transferId", progress.id)
            .put("operation", progress.operation)
            .put("phase", phase)
            .put("currentPath", currentPath)
            .put("bytes", progress.bytes)
            .put("files", progress.files)
            .put("directories", progress.directories)
            .put("totalFiles", progress.totalFiles)
            .put("totalDirectories", progress.totalDirectories)
            .put("totalBytes", progress.totalBytes))
    }

    fun handleAsync(raw: String) {
        val message = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val id = message.optString("id")
        val method = message.optString("method")
        val args = message.optJSONObject("args") ?: JSONObject()
        if (id.isBlank() || method.isBlank()) return
        when (method) {
            "files.pickDirectory" -> { directoryPicker(id); return }
            "notifications.request" -> { notificationPermissionRequester(id); return }
        }
        val worker = if (method == "fs.copy" || method == "fs.move") transferExecutor else executor
        worker.execute {
            try { resultSink(id, true, dispatch(method, args), null) }
            catch (error: Throwable) { resultSink(id, false, null, error.message ?: error.javaClass.simpleName) }
        }
    }

    fun completeDirectoryPick(requestId: String, uri: Uri) {
        val mountId = "android-${UUID.randomUUID()}"
        val doc = DocumentFile.fromTreeUri(activity, uri)
        val name = doc?.name?.takeIf { it.isNotBlank() } ?: "Android Files"
        val record = JSONObject().put("uri", uri.toString()).put("name", name).put("persistent", true)
        prefs.edit().putString("mount:$mountId", record.toString()).apply()
        resultSink(requestId, true, JSONObject().put("mountId", mountId).put("name", name).put("persistent", true).put("system", false), null)
    }
    fun cancelDirectoryPick(requestId: String) { resultSink(requestId, false, null, "Directory selection cancelled") }
    fun completeNotificationPermission(requestId: String, granted: Boolean) {
        resultSink(requestId, true, JSONObject().put("granted", granted).put("sdk", Build.VERSION.SDK_INT), null)
    }
    fun shutdown() {
        executor.shutdownNow()
        transferExecutor.shutdownNow()
    }

    private fun dispatch(method: String, args: JSONObject): Any? = when (method) {
        "files.mounts" -> mountedDirectories()
        "files.unmount" -> unmount(args.getString("mountId"))
        "files.open" -> openMountedFile(args.getString("mountId"), args.optString("path"))
        "fs.stat" -> stat(args.getString("mountId"), args.optString("path"))
        "fs.readText" -> readText(args.getString("mountId"), args.optString("path"))
        "fs.writeText" -> writeText(args.getString("mountId"), args.optString("path"), args.optString("text"))
        "fs.mkdir" -> mkdir(args.getString("mountId"), args.optString("path"))
        "fs.remove" -> remove(args.getString("mountId"), args.optString("path"))
        "fs.zip" -> {
            val job = RiftTransferJob("zip", args.optString("from"), args.optString("to"))
            transferService.submit(job) {
                val progress = TransferProgress(job.id, "zip")
                emitTransfer(progress, "starting", args.optString("from"), true)
                zip(args.getString("mountId"), args.optString("from"), args.optString("to"), progress)
                emitTransfer(progress, "complete", args.optString("to"), true)
            }
            JSONObject().put("transferId", job.id).put("phase", job.phase)
        }
        "fs.unzip" -> {
            val job = RiftTransferJob("unzip", args.optString("from"), args.optString("to"))
            transferService.submit(job) {
                val progress = TransferProgress(job.id, "unzip")
                emitTransfer(progress, "starting", args.optString("from"), true)
                unzip(args.getString("mountId"), args.optString("from"), args.optString("to"), progress)
                emitTransfer(progress, "complete", args.optString("to"), true)
            }
            JSONObject().put("transferId", job.id).put("phase", job.phase)
        }
        "fs.copy" -> {
            val job = RiftTransferJob("copy", args.optString("from"), args.optString("to"))
            transferService.submit(job) {
                val progress = TransferProgress(job.id, "copy")
                val manifest = buildTransferManifest(args.getString("fromMountId"), args.optString("from"))
                progress.totalFiles = manifest.files
                progress.totalBytes = manifest.bytes
                progress.totalDirectories = manifest.directories
                emitTransfer(progress, "starting", args.optString("from"), true)
                copyNode(args.getString("fromMountId"), args.optString("from"), args.getString("toMountId"), args.optString("to"), args.optBoolean("overwrite", false), progress)
                emitTransfer(progress, "complete", args.optString("to"), true)
            }
            JSONObject().put("transferId", job.id).put("phase", job.phase)
        }
        "fs.move" -> {
            val job = RiftTransferJob("move", args.optString("from"), args.optString("to"))
            transferService.submit(job) {
                val progress = TransferProgress(job.id, "move")
                val manifest = buildTransferManifest(args.getString("fromMountId"), args.optString("from"))
                progress.totalFiles = manifest.files
                progress.totalBytes = manifest.bytes
                progress.totalDirectories = manifest.directories
                emitTransfer(progress, "starting", args.optString("from"), true)
                moveNode(args.getString("fromMountId"), args.optString("from"), args.getString("toMountId"), args.optString("to"), args.optBoolean("overwrite", false), progress)
                emitTransfer(progress, "complete", args.optString("to"), true)
            }
            JSONObject().put("transferId", job.id).put("phase", job.phase)
        }
        "fs.list" -> list(args.getString("mountId"), args.optString("path"), args.optBoolean("recursive", false))
        "settings.get" -> getSetting(args.getString("key"))
        "settings.set" -> setSetting(args.getString("key"), args.opt("value"))
        "secrets.get" -> JSONObject().put("value", secrets.get(args.getString("key")) ?: JSONObject.NULL)
        "secrets.set" -> secrets.set(args.getString("key"), args.optString("value"))
        "secrets.remove" -> secrets.remove(args.getString("key"))
        "system.storage" -> storageInfo()
        "system.info", "device.info" -> deviceInfo()
        "device.vibrate" -> vibrate(args.optLong("milliseconds", 40L))
        "clipboard.read", "clipboard.readText" -> clipboardRead()
        "clipboard.write", "clipboard.writeText" -> clipboardWrite(args.optString("text"))
        "share.text" -> shareText(args.optString("text"), args.optString("title", "Share from RiftOS"))
        "intent.open" -> openIntent(args.getString("url"))
        "browser.open" -> openBrowser(args.optString("url", "https://chatgpt.com"))
        "preview.open" -> openPreview(args.optString("root"), args.optString("entry", "index.html"))
        "notifications.show" -> showNotification(args.optString("title", "RiftOS"), args.optString("body"))
        else -> throw IllegalArgumentException("Unsupported RiftAndroid method: $method")
    }

    private fun normalizeSegments(path: String): List<String> {
        val normalized = path.replace('\\', '/').trim('/')
        if (normalized.isBlank()) return emptyList()
        return normalized.split('/').filter { it.isNotBlank() }.map {
            require(it != "." && it != ".." && !it.contains('\u0000')) { "Invalid path segment" }
            it
        }
    }
    private fun internalFile(path: String): File {
        var file = riftRoot
        for (segment in normalizeSegments(path)) file = File(file, segment)
        val rootCanonical = riftRoot.canonicalFile
        val target = file.canonicalFile
        require(target == rootCanonical || target.path.startsWith(rootCanonical.path + File.separator)) { "Path escaped RiftFS" }
        return target
    }
    private fun mountRecord(mountId: String): JSONObject {
        val raw = prefs.getString("mount:$mountId", null) ?: throw IllegalArgumentException("Unknown mount: $mountId")
        return JSONObject(raw)
    }
    private fun externalRoot(mountId: String): DocumentFile {
        val uri = Uri.parse(mountRecord(mountId).getString("uri"))
        return DocumentFile.fromTreeUri(activity, uri) ?: throw IllegalStateException("Mount is unavailable")
    }
    private fun externalNode(mountId: String, path: String): DocumentFile? {
        var current = externalRoot(mountId)
        for (segment in normalizeSegments(path)) current = current.findFile(segment) ?: return null
        return current
    }
    private fun externalDirectory(mountId: String, path: String, create: Boolean): DocumentFile {
        var current = externalRoot(mountId)
        for (segment in normalizeSegments(path)) {
            val found = current.findFile(segment)
            current = when {
                found?.isDirectory == true -> found
                found != null -> throw IllegalStateException("$segment is not a directory")
                create -> current.createDirectory(segment) ?: throw IllegalStateException("Could not create $segment")
                else -> throw IllegalStateException("Directory not found: $path")
            }
        }
        return current
    }
    private fun externalFile(mountId: String, path: String, create: Boolean): DocumentFile {
        val parts = normalizeSegments(path)
        require(parts.isNotEmpty()) { "Mount root is not a file" }
        val parent = externalDirectory(mountId, parts.dropLast(1).joinToString("/"), create)
        val name = parts.last()
        val found = parent.findFile(name)
        if (found != null) { require(found.isFile) { "$path is not a file" }; return found }
        require(create) { "File not found: $path" }
        return parent.createFile("application/octet-stream", name) ?: throw IllegalStateException("Could not create $path")
    }

    private fun mountedDirectories(): JSONArray {
        val out = JSONArray()
        prefs.all.keys.filter { it.startsWith("mount:") }.sorted().forEach { key ->
            val mountId = key.removePrefix("mount:")
            val record = runCatching { mountRecord(mountId) }.getOrNull() ?: return@forEach
            val uri = runCatching { Uri.parse(record.getString("uri")) }.getOrNull()
            val permissionStillPresent = uri != null && activity.contentResolver.persistedUriPermissions.any { it.uri == uri }
            if (!permissionStillPresent) return@forEach
            out.put(JSONObject().put("mountId", mountId).put("name", record.optString("name", "Android Files")).put("persistent", true).put("system", false))
        }
        return out
    }
    private fun unmount(mountId: String): Boolean {
        val key = "mount:$mountId"
        val record = runCatching { mountRecord(mountId) }.getOrNull()
        record?.optString("uri")?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching {
                activity.contentResolver.releasePersistableUriPermission(Uri.parse(raw), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
        val existed = prefs.contains(key); prefs.edit().remove(key).apply(); return existed
    }

    private fun stat(mountId: String, path: String): JSONObject? {
        if (mountId == "__riftfs__") {
            val file = internalFile(path); if (!file.exists()) return null
            return JSONObject()
                .put("kind", if (file.isDirectory) "directory" else "file")
                .put("size", if (file.isFile) file.length() else 0L)
                .put("modified", file.lastModified())
                .put("name", file.name)
                .put("mime", if (file.isFile) URLConnection.guessContentTypeFromName(file.name) ?: JSONObject.NULL else JSONObject.NULL)
        }
        val doc = externalNode(mountId, path) ?: return null
        return JSONObject()
            .put("kind", if (doc.isDirectory) "directory" else "file")
            .put("size", if (doc.isFile) doc.length() else 0L)
            .put("modified", doc.lastModified())
            .put("name", doc.name ?: "")
            .put("mime", doc.type ?: JSONObject.NULL)
    }
    private fun readText(mountId: String, path: String): String {
        if (mountId == "__riftfs__") {
            val file = internalFile(path)
            require(file.isFile) { "File not found: $path" }
            require(file.length() <= MAX_BRIDGE_TEXT_BYTES) { "File is too large to read through the Rift web bridge (${file.length()} bytes)" }
            return file.readText(Charsets.UTF_8)
        }
        val doc = externalFile(mountId, path, false)
        val size = doc.length()
        require(size <= MAX_BRIDGE_TEXT_BYTES) { "File is too large to read through the Rift web bridge ($size bytes)" }
        return activity.contentResolver.openInputStream(doc.uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: throw IllegalStateException("Could not read $path")
    }
    private fun writeText(mountId: String, path: String, text: String): JSONObject {
        if (mountId == "__riftfs__") { val file=internalFile(path); file.parentFile?.mkdirs(); file.writeText(text,Charsets.UTF_8); return stat(mountId,path)!! }
        val doc=externalFile(mountId,path,true)
        activity.contentResolver.openOutputStream(doc.uri,"wt")?.bufferedWriter(Charsets.UTF_8)?.use{it.write(text)} ?: throw IllegalStateException("Could not write $path")
        return stat(mountId,path)!!
    }
    private fun mkdir(mountId: String, path: String): JSONObject {
        if(mountId=="__riftfs__"){val dir=internalFile(path);require((dir.exists()&&dir.isDirectory)||dir.mkdirs()){ "Could not create directory: $path" };return stat(mountId,path)!!}
        externalDirectory(mountId,path,true);return stat(mountId,path)!!
    }
    private fun remove(mountId: String, path: String): Boolean {
        val segments = normalizeSegments(path)
        require(segments.isNotEmpty()){ "Cannot delete a filesystem root" }
        if (mountId == "__riftfs__" && segments.size == 1 && segments.first() in protectedRiftRoots) {
            throw IllegalArgumentException("Cannot delete a RiftFS system root")
        }
        if(mountId=="__riftfs__"){val file=internalFile(path);if(!file.exists())return true;return if(file.isDirectory)file.deleteRecursively()else file.delete()}
        return externalNode(mountId,path)?.delete() ?: true
    }

    private fun zip(mountId: String, from: String, to: String, progress: TransferProgress? = null): JSONObject {
        val destination = if (mountId == "__riftfs__") internalFile(to) else externalFile(mountId, to, true)
        destination.parentFile?.mkdirs()
        val output = if (mountId == "__riftfs__") destination.outputStream() else activity.contentResolver.openOutputStream(destination.uri, "wt")
            ?: throw IllegalStateException("Could not create archive")
        output.buffered().use { stream ->
            ZipOutputStream(stream).use { zip ->
                if (mountId == "__riftfs__") {
                    val source = internalFile(from)
                    source.walkTopDown().filter { it.isFile }.forEach { file ->
                        zip.putNextEntry(ZipEntry(if (source.isDirectory) file.relativeTo(source).path else file.name))
                        file.inputStream().use { input ->
                            val buffer = ByteArray(COPY_BUFFER_BYTES)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                zip.write(buffer, 0, count)
                                progress?.bytes = (progress?.bytes ?: 0L) + count
                                emitTransfer(progress, "compressing", file.path)
                            }
                        }
                        progress?.files = (progress?.files ?: 0) + 1
                        zip.closeEntry()
                    }
                } else {
                    val source = externalFile(mountId, from, false)
                    zip.putNextEntry(ZipEntry(source.name ?: leafName(from)))
                    activity.contentResolver.openInputStream(source.uri)?.use { input ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            zip.write(buffer, 0, count)
                            progress?.bytes = (progress?.bytes ?: 0L) + count
                            emitTransfer(progress, "compressing", from)
                        }
                    }
                    progress?.files = (progress?.files ?: 0) + 1
                    zip.closeEntry()
                }
            }
        }
        return stat(mountId, to)!!
    }

    private fun unzip(mountId: String, from: String, to: String, progress: TransferProgress? = null): JSONObject {
        val input = if (mountId == "__riftfs__") internalFile(from).inputStream() else {
            val source = externalFile(mountId, from, false)
            activity.contentResolver.openInputStream(source.uri) ?: throw IllegalStateException("Could not read archive")
        }
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val safe = normalizeSegments(entry.name).joinToString("/")
                if (safe.isNotBlank()) {
                    val targetPath = childPath(to, safe)
                    val target = if (mountId == "__riftfs__") internalFile(targetPath) else externalFile(mountId, targetPath, true)
                    if (entry.isDirectory) {
                        if (mountId == "__riftfs__") target.mkdirs()
                    } else {
                        val output = if (mountId == "__riftfs__") target.outputStream() else activity.contentResolver.openOutputStream(target.uri, "wt")
                            ?: throw IllegalStateException("Could not write extracted file")
                        output.use { out ->
                            val buffer = ByteArray(COPY_BUFFER_BYTES)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                out.write(buffer, 0, count)
                                progress?.bytes = (progress?.bytes ?: 0L) + count
                                emitTransfer(progress, "extracting", targetPath)
                            }
                        }
                        progress?.files = (progress?.files ?: 0) + 1
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return stat(mountId, to)!!
    }

    private fun normalizedRelative(path: String): String = normalizeSegments(path).joinToString("/")

    private fun childPath(parent: String, child: String): String =
        listOf(normalizedRelative(parent), normalizedRelative(child)).filter { it.isNotBlank() }.joinToString("/")

    private fun copyFileBytes(
        fromMountId: String, fromPath: String,
        toMountId: String, toPath: String,
        progress: TransferProgress?
    ) {
        val input = if (fromMountId == "__riftfs__") {
            val source = internalFile(fromPath)
            require(source.isFile) { "File not found: $fromPath" }
            source.inputStream()
        } else {
            val source = externalFile(fromMountId, fromPath, false)
            activity.contentResolver.openInputStream(source.uri)
                ?: throw IllegalStateException("Could not read $fromPath")
        }
        input.use { sourceStream ->
            fun streamTo(output: java.io.OutputStream) {
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val count = sourceStream.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    if (progress != null) progress.bytes += count
                    emitTransfer(progress, "transferring", fromPath)
                    // Do not monopolize the single native FS worker on large transfers.
                    // Yielding keeps the UI/event bridge responsive while large folders copy.
                    Thread.yield()
                }
            }
            if (toMountId == "__riftfs__") {
                val destination = internalFile(toPath)
                destination.parentFile?.mkdirs()
                destination.outputStream().buffered(COPY_BUFFER_BYTES).use(::streamTo)
            } else {
                val destination = externalFile(toMountId, toPath, true)
                activity.contentResolver.openOutputStream(destination.uri, "wt")?.buffered(COPY_BUFFER_BYTES)?.use(::streamTo)
                    ?: throw IllegalStateException("Could not write $toPath")
            }
        }
    }

    private fun parentRelative(path: String): String = normalizedRelative(path).substringBeforeLast('/', "")
    private fun leafName(path: String): String = normalizeSegments(path).lastOrNull() ?: ""

    private fun tryProviderCopy(fromMountId: String, fromPath: String, toMountId: String, toPath: String): JSONObject? {
        if (fromMountId == "__riftfs__" || fromMountId != toMountId) return null
        if (leafName(fromPath) != leafName(toPath)) return null
        return runCatching {
            val source = externalNode(fromMountId, fromPath) ?: return@runCatching null
            val targetParent = externalDirectory(toMountId, parentRelative(toPath), false)
            val copiedUri = DocumentsContract.copyDocument(activity.contentResolver, source.uri, targetParent.uri)
                ?: return@runCatching null
            val copied = DocumentFile.fromSingleUri(activity, copiedUri) ?: return@runCatching null
            if ((copied.name ?: "") != leafName(toPath)) return@runCatching null
            stat(toMountId, toPath)
        }.getOrNull()
    }

    private fun tryProviderMove(fromMountId: String, fromPath: String, toMountId: String, toPath: String): JSONObject? {
        if (fromMountId == "__riftfs__" || fromMountId != toMountId) return null
        val sourceName = leafName(fromPath)
        val destinationName = leafName(toPath)
        return runCatching {
            val source = externalNode(fromMountId, fromPath) ?: return@runCatching null
            val sourceParentPath = parentRelative(fromPath)
            val targetParentPath = parentRelative(toPath)
            if (sourceParentPath == targetParentPath) {
                if (sourceName == destinationName) return@runCatching null
                if (!source.renameTo(destinationName)) return@runCatching null
                return@runCatching stat(toMountId, toPath)
            }
            if (sourceName != destinationName) return@runCatching null
            val sourceParent = externalDirectory(fromMountId, sourceParentPath, false)
            val targetParent = externalDirectory(toMountId, targetParentPath, false)
            DocumentsContract.moveDocument(activity.contentResolver, source.uri, sourceParent.uri, targetParent.uri)
                ?: return@runCatching null
            stat(toMountId, toPath)
        }.getOrNull()
    }

    private fun buildTransferManifest(fromMountId: String, path: String): RiftTransferManifest {
        val manifest = RiftTransferManifest()
        fun walk(current: String) {
            val node = stat(fromMountId, current) ?: return
            if (node.optString("kind") == "file") {
                manifest.addFile(node.optLong("size", 0L))
                return
            }
            manifest.addDirectory()
            val children = list(fromMountId, current, false)
            for (index in 0 until children.length()) {
                val child = children.optJSONObject(index) ?: continue
                val childPath = child.optString("path")
                if (childPath.isNotBlank()) walk(childPath(current, childPath))
            }
        }
        walk(path)
        return manifest
    }

    private fun copyNode(
        fromMountId: String, fromPathRaw: String,
        toMountId: String, toPathRaw: String,
        overwrite: Boolean,
        progress: TransferProgress? = null,
        job: RiftTransferJob? = null
    ): JSONObject {
        val fromPath = normalizedRelative(fromPathRaw)
        val toPath = normalizedRelative(toPathRaw)
        require(fromPath.isNotBlank()) { "Cannot copy a filesystem root" }
        require(toPath.isNotBlank()) { "Destination cannot be a filesystem root" }
        if (fromMountId == toMountId) {
            require(fromPath != toPath) { "Copy source and destination are identical" }
            if (toPath.startsWith("$fromPath/")) throw IllegalArgumentException("Cannot copy a directory inside itself")
        }
        if (job?.isCancelled() == true) throw IllegalStateException("Transfer cancelled")
        val source = stat(fromMountId, fromPath) ?: throw IllegalArgumentException("Source not found: $fromPath")
        val existing = stat(toMountId, toPath)
        if (existing != null) {
            require(overwrite) { "Destination already exists: $toPath" }
            require(remove(toMountId, toPath)) { "Could not replace destination: $toPath" }
        }
        tryProviderCopy(fromMountId, fromPath, toMountId, toPath)?.let { return it }

        try {
            if (source.optString("kind") == "file") {
                copyFileBytes(fromMountId, fromPath, toMountId, toPath, progress)
                if (progress != null) progress.files++
                emitTransfer(progress, "transferring", fromPath, true)
            } else {
                mkdir(toMountId, toPath)
                if (progress != null) progress.directories++
                emitTransfer(progress, "transferring", fromPath)
                val children = list(fromMountId, fromPath, false)
                for (index in 0 until children.length()) {
                    val row = children.optJSONObject(index) ?: continue
                    val relative = row.optString("path")
                    if (relative.isBlank()) continue
                    copyNode(
                        fromMountId, childPath(fromPath, relative),
                        toMountId, childPath(toPath, relative),
                        overwrite = false,
                        progress = progress,
                        job = job
                    )
                    emitTransfer(progress, "queued", relative)
                    if (job?.isCancelled() == true) throw IllegalStateException("Transfer cancelled")
                    Thread.yield()
                }
            }
        } catch (error: Throwable) {
            runCatching { remove(toMountId, toPath) }
            throw error
        }
        return stat(toMountId, toPath) ?: throw IllegalStateException("Copied destination is missing: $toPath")
    }

    private fun moveNode(
        fromMountId: String, fromPathRaw: String,
        toMountId: String, toPathRaw: String,
        overwrite: Boolean,
        progress: TransferProgress? = null
    ): JSONObject {
        val fromPath = normalizedRelative(fromPathRaw)
        val toPath = normalizedRelative(toPathRaw)
        require(fromPath.isNotBlank()) { "Cannot move a filesystem root" }
        require(toPath.isNotBlank()) { "Destination cannot be a filesystem root" }
        if (fromMountId == "__riftfs__" && normalizeSegments(fromPath).size == 1 && normalizeSegments(fromPath).first() in protectedRiftRoots) {
            throw IllegalArgumentException("Cannot move a RiftFS system root")
        }
        require(fromMountId != toMountId || fromPath != toPath) { "Move source and destination are identical" }
        if (fromMountId == toMountId && toPath.startsWith("$fromPath/")) {
            throw IllegalArgumentException("Cannot move a directory inside itself")
        }

        val existingDestination = stat(toMountId, toPath)
        if (existingDestination != null) {
            require(overwrite) { "Destination already exists: $toPath" }
            require(remove(toMountId, toPath)) { "Could not replace destination: $toPath" }
        }
        tryProviderMove(fromMountId, fromPath, toMountId, toPath)?.let { return it }

        if (fromMountId == "__riftfs__" && toMountId == "__riftfs__") {
            val source = internalFile(fromPath)
            val destination = internalFile(toPath)
            require(source.exists()) { "Source not found: $fromPath" }
            destination.parentFile?.mkdirs()
            if (source.renameTo(destination)) return stat(toMountId, toPath)!!
        }

        copyNode(fromMountId, fromPath, toMountId, toPath, overwrite, progress, null)
        emitTransfer(progress, "source-removal", fromPath, true)
        if (!remove(fromMountId, fromPath)) {
            runCatching { remove(toMountId, toPath) }
            throw IllegalStateException("Copied $fromPath but could not remove the source; destination was rolled back")
        }
        return stat(toMountId, toPath) ?: throw IllegalStateException("Moved destination is missing: $toPath")
    }
    private fun list(mountId: String, path: String, recursive: Boolean): JSONArray {
        val out=JSONArray()
        if(mountId=="__riftfs__"){
            val base=internalFile(path);if(!base.exists())return out;require(base.isDirectory){"$path is not a directory"}
            fun walk(dir:File,prefix:String){dir.listFiles()?.sortedBy{it.name.lowercase()}?.forEach{child->val relative=if(prefix.isBlank())child.name else "$prefix/${child.name}";out.put(JSONObject().put("path",relative).put("kind",if(child.isDirectory)"directory" else "file").put("size",if(child.isFile)child.length()else 0L).put("modified",child.lastModified()).put("mime",if(child.isFile) URLConnection.guessContentTypeFromName(child.name) ?: JSONObject.NULL else JSONObject.NULL));if(recursive&&child.isDirectory)walk(child,relative)}}
            walk(base,"");return out
        }
        val base=externalDirectory(mountId,path,false)
        fun walk(dir:DocumentFile,prefix:String){dir.listFiles().sortedBy{(it.name?:"").lowercase()}.forEach{child->val name=child.name?:return@forEach;val relative=if(prefix.isBlank())name else "$prefix/$name";out.put(JSONObject().put("path",relative).put("kind",if(child.isDirectory)"directory" else "file").put("size",if(child.isFile)child.length()else 0L).put("modified",child.lastModified()).put("mime",child.type ?: JSONObject.NULL));if(recursive&&child.isDirectory)walk(child,relative)}}
        walk(base,"");return out
    }

    private fun openMountedFile(mountId: String, path: String): JSONObject {
        require(mountId != "__riftfs__") { "Native open is only available for Android-mounted files" }
        val doc = externalFile(mountId, path, false)
        val mime = doc.type ?: activity.contentResolver.getType(doc.uri) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(doc.uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        require(intent.resolveActivity(activity.packageManager) != null) { "No Android app can open this file type" }
        activity.runOnUiThread { activity.startActivity(intent) }
        return JSONObject()
            .put("opened", true)
            .put("name", doc.name ?: path.substringAfterLast('/'))
            .put("size", doc.length())
            .put("mime", mime)
    }

    private fun getSetting(key: String): JSONObject? {
        val raw=prefs.getString("setting:$key",null)?:return null;val stored=JSONObject(raw)
        return JSONObject().put("key",key).put("value",stored.opt("value")).put("modified",stored.optLong("modified",0L))
    }
    private fun setSetting(key:String,value:Any?):JSONObject{
        val modified=System.currentTimeMillis();val stored=JSONObject().put("value",value?:JSONObject.NULL).put("modified",modified);prefs.edit().putString("setting:$key",stored.toString()).apply()
        return JSONObject().put("key",key).put("value",value?:JSONObject.NULL).put("modified",modified)
    }
    private fun storageInfo():JSONObject{
        val stats=StatFs(activity.filesDir.absolutePath);val quota=stats.totalBytes;val free=stats.availableBytes
        return JSONObject().put("usage",(quota-free).coerceAtLeast(0L)).put("quota",quota).put("free",free).put("backend","Android internal storage")
    }
    private fun deviceInfo():JSONObject=JSONObject().put("platform","android").put("manufacturer",Build.MANUFACTURER).put("brand",Build.BRAND).put("model",Build.MODEL).put("device",Build.DEVICE).put("androidRelease",Build.VERSION.RELEASE).put("sdk",Build.VERSION.SDK_INT)
    private fun vibrate(milliseconds:Long):Boolean{
        val duration=milliseconds.coerceIn(20L,1000L)
        if(Build.VERSION.SDK_INT>=31){
            activity.getSystemService(VibratorManager::class.java).defaultVibrator.vibrate(VibrationEffect.createOneShot(duration,VibrationEffect.DEFAULT_AMPLITUDE))
        }else{
            @Suppress("DEPRECATION")
            val vibrator=activity.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator.vibrate(VibrationEffect.createOneShot(duration,VibrationEffect.DEFAULT_AMPLITUDE))
        }
        return true
    }
    private fun clipboardRead():String=(activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(activity)?.toString().orEmpty()
    private fun clipboardWrite(text:String):Boolean{(activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("RiftOS",text));return true}
    private fun shareText(text:String,title:String):Boolean{activity.runOnUiThread{activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,text)},title))};return true}
    private fun openIntent(rawUrl:String):Boolean{val uri=Uri.parse(rawUrl);require(uri.scheme in setOf("https","http","mailto","tel","geo")){"Blocked intent scheme"};activity.runOnUiThread{activity.startActivity(Intent(Intent.ACTION_VIEW,uri))};return true}
    private fun openBrowser(rawUrl:String):Boolean{
        var url=rawUrl.trim();if(url.isBlank())url="https://chatgpt.com";if(!url.startsWith("http://")&&!url.startsWith("https://"))url="https://$url"
        val finalUrl=url
        val main=activity as? MainActivity ?: throw IllegalStateException("RiftBrowser requires the RiftOS desktop host")
        main.openDesktopBrowser(finalUrl)
        return true
    }
    private fun openPreview(root:String,entry:String):Boolean{
        normalizeSegments(root);normalizeSegments(entry)
        activity.runOnUiThread{activity.startActivity(Intent(activity,RiftPreviewActivity::class.java).putExtra(RiftPreviewActivity.EXTRA_ROOT,root).putExtra(RiftPreviewActivity.EXTRA_ENTRY,entry))};return true
    }
    private fun showNotification(title:String,body:String):JSONObject{
        if(Build.VERSION.SDK_INT>=33&&activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return JSONObject().put("shown",false).put("reason","notification-permission-required")
        val manager=activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager;val channelId="riftos"
        if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(NotificationChannel(channelId,"RiftOS",NotificationManager.IMPORTANCE_DEFAULT))
        val notification=if(Build.VERSION.SDK_INT>=26){
            android.app.Notification.Builder(activity,channelId).setSmallIcon(android.R.drawable.stat_notify_more).setContentTitle(title).setContentText(body).setAutoCancel(true).build()
        }else{
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(activity).setSmallIcon(android.R.drawable.stat_notify_more).setContentTitle(title).setContentText(body).setAutoCancel(true).build()
        }
        manager.notify((System.currentTimeMillis() and 0x7fffffff).toInt(),notification);return JSONObject().put("shown",true)
    }
}
