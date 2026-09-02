package com.riftos.app

import android.Manifest
import android.app.Activity
import android.app.Notification
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
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

class RiftNativeDispatcher(
    private val activity: Activity,
    private val resultSink: (String, Boolean, Any?, String?) -> Unit,
    private val directoryPicker: (String) -> Unit
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val prefs = activity.getSharedPreferences("rift-native", Context.MODE_PRIVATE)
    private val riftRoot = File(activity.filesDir, "riftfs").apply {
        mkdirs()
        listOf("home", "apps", "system", "workspace", "downloads", "documents").forEach {
            File(this, it).mkdirs()
        }
    }

    fun handleAsync(raw: String) {
        val message = try {
            JSONObject(raw)
        } catch (error: Exception) {
            return
        }
        val id = message.optString("id")
        val method = message.optString("method")
        val args = message.optJSONObject("args") ?: JSONObject()
        if (id.isBlank() || method.isBlank()) return

        if (method == "files.pickDirectory") {
            directoryPicker(id)
            return
        }

        executor.execute {
            try {
                resultSink(id, true, dispatch(method, args), null)
            } catch (error: Throwable) {
                resultSink(id, false, null, error.message ?: error.javaClass.simpleName)
            }
        }
    }

    fun completeDirectoryPick(requestId: String, uri: Uri) {
        val mountId = "android-${UUID.randomUUID()}"
        val doc = DocumentFile.fromTreeUri(activity, uri)
        val name = doc?.name?.takeIf { it.isNotBlank() } ?: "Android Files"
        val record = JSONObject()
            .put("uri", uri.toString())
            .put("name", name)
            .put("persistent", true)
        prefs.edit().putString("mount:$mountId", record.toString()).apply()
        resultSink(
            requestId,
            true,
            JSONObject()
                .put("mountId", mountId)
                .put("name", name)
                .put("persistent", true)
                .put("system", false),
            null
        )
    }

    fun cancelDirectoryPick(requestId: String) {
        resultSink(requestId, false, null, "Directory selection cancelled")
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    private fun dispatch(method: String, args: JSONObject): Any? = when (method) {
        "files.mounts" -> mountedDirectories()
        "files.unmount" -> unmount(args.getString("mountId"))
        "fs.stat" -> stat(args.getString("mountId"), args.optString("path"))
        "fs.readText" -> readText(args.getString("mountId"), args.optString("path"))
        "fs.writeText" -> writeText(args.getString("mountId"), args.optString("path"), args.optString("text"))
        "fs.mkdir" -> mkdir(args.getString("mountId"), args.optString("path"))
        "fs.remove" -> remove(args.getString("mountId"), args.optString("path"))
        "fs.list" -> list(args.getString("mountId"), args.optString("path"), args.optBoolean("recursive", true))
        "settings.get" -> getSetting(args.getString("key"))
        "settings.set" -> setSetting(args.getString("key"), args.opt("value"))
        "system.storage" -> storageInfo()
        "system.info", "device.info" -> deviceInfo()
        "device.vibrate" -> vibrate(args.optLong("milliseconds", 40L))
        "clipboard.read" -> clipboardRead()
        "clipboard.write" -> clipboardWrite(args.optString("text"))
        "share.text" -> shareText(args.optString("text"), args.optString("title", "Share from RiftOS"))
        "intent.open" -> openIntent(args.getString("url"))
        "notifications.show" -> showNotification(args.optString("title", "RiftOS"), args.optString("body"))
        else -> throw IllegalArgumentException("Unsupported RiftNative method: $method")
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
        require(target == rootCanonical || target.path.startsWith(rootCanonical.path + File.separator)) {
            "Path escaped RiftFS"
        }
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
        for (segment in normalizeSegments(path)) {
            current = current.findFile(segment) ?: return null
        }
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
        if (found != null) {
            require(found.isFile) { "$path is not a file" }
            return found
        }
        require(create) { "File not found: $path" }
        return parent.createFile("application/octet-stream", name)
            ?: throw IllegalStateException("Could not create $path")
    }

    private fun mountedDirectories(): JSONArray {
        val out = JSONArray()
        prefs.all.keys.filter { it.startsWith("mount:") }.sorted().forEach { key ->
            val mountId = key.removePrefix("mount:")
            val record = runCatching { mountRecord(mountId) }.getOrNull() ?: return@forEach
            out.put(
                JSONObject()
                    .put("mountId", mountId)
                    .put("name", record.optString("name", "Android Files"))
                    .put("persistent", record.optBoolean("persistent", true))
                    .put("system", false)
            )
        }
        return out
    }

    private fun unmount(mountId: String): Boolean {
        val key = "mount:$mountId"
        val existed = prefs.contains(key)
        prefs.edit().remove(key).apply()
        return existed
    }

    private fun stat(mountId: String, path: String): JSONObject? {
        if (mountId == "__riftfs__") {
            val file = internalFile(path)
            if (!file.exists()) return null
            return JSONObject()
                .put("kind", if (file.isDirectory) "directory" else "file")
                .put("size", if (file.isFile) file.length() else 0L)
                .put("modified", file.lastModified())
                .put("name", file.name)
        }
        val doc = externalNode(mountId, path) ?: return null
        return JSONObject()
            .put("kind", if (doc.isDirectory) "directory" else "file")
            .put("size", if (doc.isFile) doc.length() else 0L)
            .put("modified", doc.lastModified())
            .put("name", doc.name ?: "")
    }

    private fun readText(mountId: String, path: String): String {
        if (mountId == "__riftfs__") {
            val file = internalFile(path)
            require(file.isFile) { "File not found: $path" }
            return file.readText(Charsets.UTF_8)
        }
        val doc = externalFile(mountId, path, false)
        return activity.contentResolver.openInputStream(doc.uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: throw IllegalStateException("Could not read $path")
    }

    private fun writeText(mountId: String, path: String, text: String): JSONObject {
        if (mountId == "__riftfs__") {
            val file = internalFile(path)
            file.parentFile?.mkdirs()
            file.writeText(text, Charsets.UTF_8)
            return stat(mountId, path)!!
        }
        val doc = externalFile(mountId, path, true)
        activity.contentResolver.openOutputStream(doc.uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) }
            ?: throw IllegalStateException("Could not write $path")
        return stat(mountId, path)!!
    }

    private fun mkdir(mountId: String, path: String): JSONObject {
        if (mountId == "__riftfs__") {
            val dir = internalFile(path)
            require(dir.exists() && dir.isDirectory || dir.mkdirs()) { "Could not create directory: $path" }
            return stat(mountId, path)!!
        }
        externalDirectory(mountId, path, true)
        return stat(mountId, path)!!
    }

    private fun remove(mountId: String, path: String): Boolean {
        require(normalizeSegments(path).isNotEmpty()) { "Cannot delete a filesystem root" }
        if (mountId == "__riftfs__") {
            val file = internalFile(path)
            if (!file.exists()) return true
            return if (file.isDirectory) file.deleteRecursively() else file.delete()
        }
        val doc = externalNode(mountId, path) ?: return true
        return doc.delete()
    }

    private fun list(mountId: String, path: String, recursive: Boolean): JSONArray {
        val out = JSONArray()
        if (mountId == "__riftfs__") {
            val base = internalFile(path)
            if (!base.exists()) return out
            require(base.isDirectory) { "$path is not a directory" }
            fun walk(dir: File, prefix: String) {
                dir.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { child ->
                    val relative = if (prefix.isBlank()) child.name else "$prefix/${child.name}"
                    out.put(
                        JSONObject()
                            .put("path", relative)
                            .put("kind", if (child.isDirectory) "directory" else "file")
                            .put("size", if (child.isFile) child.length() else 0L)
                            .put("modified", child.lastModified())
                    )
                    if (recursive && child.isDirectory) walk(child, relative)
                }
            }
            walk(base, "")
            return out
        }

        val base = externalDirectory(mountId, path, false)
        fun walk(dir: DocumentFile, prefix: String) {
            dir.listFiles().sortedBy { (it.name ?: "").lowercase() }.forEach { child ->
                val name = child.name ?: return@forEach
                val relative = if (prefix.isBlank()) name else "$prefix/$name"
                out.put(
                    JSONObject()
                        .put("path", relative)
                        .put("kind", if (child.isDirectory) "directory" else "file")
                        .put("size", if (child.isFile) child.length() else 0L)
                        .put("modified", child.lastModified())
                )
                if (recursive && child.isDirectory) walk(child, relative)
            }
        }
        walk(base, "")
        return out
    }

    private fun getSetting(key: String): JSONObject? {
        val raw = prefs.getString("setting:$key", null) ?: return null
        val stored = JSONObject(raw)
        return JSONObject()
            .put("key", key)
            .put("value", stored.opt("value"))
            .put("modified", stored.optLong("modified", 0L))
    }

    private fun setSetting(key: String, value: Any?): JSONObject {
        val modified = System.currentTimeMillis()
        val stored = JSONObject().put("value", value ?: JSONObject.NULL).put("modified", modified)
        prefs.edit().putString("setting:$key", stored.toString()).apply()
        return JSONObject().put("key", key).put("value", value ?: JSONObject.NULL).put("modified", modified)
    }

    private fun storageInfo(): JSONObject {
        val stats = StatFs(activity.filesDir.absolutePath)
        val quota = stats.totalBytes
        val free = stats.availableBytes
        return JSONObject()
            .put("usage", (quota - free).coerceAtLeast(0L))
            .put("quota", quota)
            .put("free", free)
            .put("backend", "Android internal storage")
    }

    private fun deviceInfo(): JSONObject = JSONObject()
        .put("platform", "android")
        .put("manufacturer", Build.MANUFACTURER)
        .put("model", Build.MODEL)
        .put("device", Build.DEVICE)
        .put("androidRelease", Build.VERSION.RELEASE)
        .put("sdk", Build.VERSION.SDK_INT)

    private fun vibrate(milliseconds: Long): Boolean {
        val duration = milliseconds.coerceIn(20L, 1000L)
        if (Build.VERSION.SDK_INT >= 31) {
            val manager = activity.getSystemService(VibratorManager::class.java)
            manager.defaultVibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            val vibrator = activity.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        }
        return true
    }

    private fun clipboardRead(): String {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return clipboard.primaryClip?.getItemAt(0)?.coerceToText(activity)?.toString().orEmpty()
    }

    private fun clipboardWrite(text: String): Boolean {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("RiftOS", text))
        return true
    }

    private fun shareText(text: String, title: String): Boolean {
        activity.runOnUiThread {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            activity.startActivity(Intent.createChooser(intent, title))
        }
        return true
    }

    private fun openIntent(rawUrl: String): Boolean {
        val uri = Uri.parse(rawUrl)
        require(uri.scheme in setOf("https", "http", "mailto", "tel", "geo")) { "Blocked intent scheme" }
        activity.runOnUiThread { activity.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        return true
    }

    private fun showNotification(title: String, body: String): JSONObject {
        if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return JSONObject().put("shown", false).put("reason", "notification-permission-required")
        }
        val manager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "riftos"
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(channelId, "RiftOS", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(activity, channelId) else Notification.Builder(activity)
        builder.setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
        manager.notify((System.currentTimeMillis() and 0x7fffffff).toInt(), builder.build())
        return JSONObject().put("shown", true)
    }
}
