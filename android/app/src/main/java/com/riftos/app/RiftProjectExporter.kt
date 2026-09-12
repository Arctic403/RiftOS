package com.riftos.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/** Creates a deterministic local workspace project snapshot for deep AI audits. */
object RiftProjectExporter {
    private val ignored = setOf(".git", ".gradle", "build", "node_modules", ".idea")

    fun export(root: File, destination: File): JSONObject {
        val files = JSONArray()
        var count = 0
        val manifest = JSONObject()
            .put("format", "RIFT_PROJECT_EXPORT_V1")
            .put("generatedAt", System.currentTimeMillis())

        root.walkTopDown()
            .filter { it.isFile && !isIgnored(root, it) }
            .forEach { file ->
                count++
                files.put(JSONObject()
                    .put("path", root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/'))
                    .put("sha256", sha256(file)))
            }

        val payload = manifest
            .put("fileCount", count)
            .put("files", files)

        destination.parentFile?.mkdirs()
        GZIPOutputStream(FileOutputStream(destination)).use { out ->
            out.write(payload.toString().toByteArray(StandardCharsets.UTF_8))
        }

        return JSONObject()
            .put("ok", true)
            .put("format", "RIFT_PROJECT_EXPORT_V1")
            .put("artifact", destination.name)
            .put("path", destination.absolutePath)
            .put("fileCount", count)
            .put("bytes", destination.length())
    }

    private fun isIgnored(root: File, file: File): Boolean =
        root.toPath().relativize(file.toPath()).any { it.toString() in ignored }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.joinToString("") { "%02x".format(it) }
    }
}
