package com.riftos.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Builds deterministic, paginated UTF-8 project snapshots for full AI audits. */
object RiftProjectExporter {
    private const val DEFAULT_PAGE_BYTES = 600 * 1024
    private const val MIN_PAGE_BYTES = 64 * 1024
    private const val MAX_PAGE_BYTES = 700 * 1024
    private const val MAX_CHUNK_BYTES = 96 * 1024
    private const val MAX_SOURCE_FILE_BYTES = 16L * 1024L * 1024L
    private const val MAX_SKIPPED_SAMPLE = 120

    private val ignoredDirectories = setOf(
        ".git", ".gradle", ".idea", ".next", ".cache", ".turbo", ".parcel-cache",
        "node_modules", "build", "dist", "out", "target", "vendor", "Pods",
        ".venv", "venv", "__pycache__", "coverage", ".pytest_cache", ".mypy_cache"
    )
    private val binaryExtensions = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "ico", "pdf", "zip", "gz", "tgz", "7z", "rar",
        "apk", "aab", "jar", "aar", "dex", "so", "dll", "exe", "bin", "class", "wasm",
        "woff", "woff2", "ttf", "otf", "mp3", "wav", "ogg", "mp4", "mov", "avi", "sqlite",
        "sqlite3", "db", "obj", "o", "a", "dylib", "blend", "fbx", "glb", "gltf"
    )
    private val secretNames = setOf(
        "local.properties", ".npmrc", ".pypirc", ".netrc", "credentials.json",
        "service-account.json", "google-services.json"
    )
    private val secretExtensions = setOf("keystore", "jks", "p12", "pfx", "pem", "key")

    private data class SourceFile(val file: File, val path: String, val size: Long, val sha256: String)
    private data class Cursor(val fileIndex: Int, val byteOffset: Int)

    fun export(
        root: File,
        rawCursor: String = "",
        requestedPageBytes: Int = DEFAULT_PAGE_BYTES,
        expectedSnapshot: String = ""
    ): JSONObject {
        val pageBytes = requestedPageBytes.coerceIn(MIN_PAGE_BYTES, MAX_PAGE_BYTES)
        val skipped = JSONArray()
        val skippedCounts = linkedMapOf<String, Int>()
        val files = scan(root, skipped, skippedCounts)

        val snapshotId = snapshotId(files)
        require(expectedSnapshot.isBlank() || expectedSnapshot == snapshotId) {
            "Project changed during export; expected $expectedSnapshot but found $snapshotId. Restart from cursor 0."
        }
        val cursor = parseCursor(rawCursor, files.size)
        val entries = JSONArray()
        val response = JSONObject()
            .put("ok", true)
            .put("format", "RIFT_PROJECT_EXPORT_V2")
            .put("snapshotId", snapshotId)
            .put("cursor", encodeCursor(cursor))
            .put("fileCount", files.size)
            .put("sourceBytes", files.sumOf { it.size })
            .put("pageBytes", pageBytes)
            .put("entries", entries)

        if (cursor.fileIndex == 0 && cursor.byteOffset == 0) {
            response.put("skippedCount", skippedCounts.values.sum())
            response.put("skippedByReason", JSONObject(skippedCounts))
            response.put("skipped", skipped)
        }

        var next = cursor
        var usedBytes = response.toString().toByteArray(Charsets.UTF_8).size + 1024
        while (next.fileIndex < files.size) {
            val source = files[next.fileIndex]
            val bytes = source.file.readBytes()
            require(next.byteOffset in 0..bytes.size) { "Invalid export cursor offset" }
            var end = utf8Boundary(bytes, next.byteOffset, (next.byteOffset + MAX_CHUNK_BYTES).coerceAtMost(bytes.size))
            var row: JSONObject
            var rowBytes: Int
            while (true) {
                row = exportRow(source, bytes, next.byteOffset, end)
                rowBytes = row.toString().toByteArray(Charsets.UTF_8).size + 1
                if (usedBytes + rowBytes <= pageBytes || entries.length() == 0 && end - next.byteOffset <= 1024) break
                val reduced = next.byteOffset + ((end - next.byteOffset) / 2).coerceAtLeast(1)
                end = utf8Boundary(bytes, next.byteOffset, reduced)
                if (end <= next.byteOffset) end = (next.byteOffset + 1).coerceAtMost(bytes.size)
            }
            if (usedBytes + rowBytes > pageBytes && entries.length() > 0) break

            entries.put(row)
            usedBytes += rowBytes
            next = if (end >= bytes.size) Cursor(next.fileIndex + 1, 0) else Cursor(next.fileIndex, end)
            if (usedBytes >= pageBytes - 2048) break
        }

        val done = next.fileIndex >= files.size
        response.put("done", done)
        response.put("nextCursor", if (done) JSONObject.NULL else encodeCursor(next))
        response.put("returnedEntries", entries.length())
        response.put("responseBytes", response.toString().toByteArray(Charsets.UTF_8).size)
        return response
    }

    fun snapshotId(root: File): String = snapshotId(scan(root))

    private fun scan(
        root: File,
        skipped: JSONArray? = null,
        skippedCounts: MutableMap<String, Int>? = null
    ): List<SourceFile> {
        val files = mutableListOf<SourceFile>()
        root.walkTopDown()
            .onEnter { directory -> directory == root || directory.name !in ignoredDirectories }
            .filter { it.isFile }
            .sortedBy { relativePath(root, it) }
            .forEach { file ->
                val path = relativePath(root, file)
                val reason = skipReason(root, file)
                if (reason != null) {
                    if (skippedCounts != null) skippedCounts[reason] = (skippedCounts[reason] ?: 0) + 1
                    if (skipped != null && skipped.length() < MAX_SKIPPED_SAMPLE) {
                        skipped.put(JSONObject().put("path", path).put("reason", reason).put("size", file.length()))
                    }
                } else {
                    files += SourceFile(file, path, file.length(), sha256(file))
                }
            }
        return files
    }

    private fun exportRow(source: SourceFile, bytes: ByteArray, start: Int, end: Int): JSONObject =
        JSONObject()
            .put("path", source.path)
            .put("sha256", source.sha256)
            .put("size", source.size)
            .put("byteStart", start)
            .put("byteEnd", end)
            .put("complete", end >= bytes.size)
            .put("content", String(bytes, start, end - start, Charsets.UTF_8))

    private fun parseCursor(raw: String, fileCount: Int): Cursor {
        if (raw.isBlank() || raw == "0") return Cursor(0, 0)
        val parts = raw.split(':', limit = 2)
        require(parts.size == 2) { "Invalid export cursor" }
        val fileIndex = parts[0].toIntOrNull() ?: throw IllegalArgumentException("Invalid export cursor")
        val byteOffset = parts[1].toIntOrNull() ?: throw IllegalArgumentException("Invalid export cursor")
        require(fileIndex in 0..fileCount && byteOffset >= 0) { "Invalid export cursor" }
        return Cursor(fileIndex, byteOffset)
    }

    private fun encodeCursor(cursor: Cursor): String = "${cursor.fileIndex}:${cursor.byteOffset}"

    private fun utf8Boundary(bytes: ByteArray, start: Int, requestedEnd: Int): Int {
        if (requestedEnd >= bytes.size) return bytes.size
        var end = requestedEnd
        while (end > start && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return end
    }

    private fun skipReason(root: File, file: File): String? {
        val relative = root.toPath().relativize(file.toPath())
        if (relative.any { it.toString() in ignoredDirectories }) return "ignored-directory"
        val name = file.name.lowercase()
        val extension = file.extension.lowercase()
        if (name.startsWith(".env") || name in secretNames || extension in secretExtensions) return "sensitive"
        if (extension in binaryExtensions) return "binary"
        if (file.length() > MAX_SOURCE_FILE_BYTES) return "oversized-text"
        if (looksBinary(file)) return "binary"
        return null
    }

    private fun looksBinary(file: File): Boolean {
        file.inputStream().buffered().use { input ->
            val sample = ByteArray(8192)
            val count = input.read(sample)
            for (index in 0 until count.coerceAtLeast(0)) if (sample[index].toInt() == 0) return true
        }
        return false
    }

    private fun relativePath(root: File, file: File): String =
        root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')

    private fun snapshotId(files: List<SourceFile>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.forEach { source ->
            digest.update(source.path.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(source.sha256.toByteArray(Charsets.US_ASCII))
            digest.update(0.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
