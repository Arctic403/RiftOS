package com.riftos.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Local, app-private ChatGPT development-session handoff bundle store. */
object RiftChatHandoff {
    private const val PAYLOAD_SCHEMA = "rift.chat-handoff.payload/1"
    private const val BUNDLE_SCHEMA = "rift.chat-handoff/1"
    private const val RESUME_SCHEMA = "rift.chat-resume/1"
    private const val BUNDLE_DIR = "workspace/chat-handoffs"
    private const val MAX_PAYLOAD_BYTES = 8L * 1024L * 1024L
    private const val MAX_HANDOFF_BYTES = 1L * 1024L * 1024L
    private const val MAX_STATE_BYTES = 2L * 1024L * 1024L
    private const val MAX_TRANSCRIPT_BYTES = 24L * 1024L * 1024L
    private const val MAX_MANIFEST_BYTES = 256L * 1024L
    private const val MAX_BUNDLE_BYTES = 32L * 1024L * 1024L
    private const val MAX_TRANSCRIPT_CHARS = 64 * 1024
    private const val MAX_BUNDLES_LISTED = 100
    private const val MAX_BUNDLES_SCANNED = 4096
    private const val MAX_PATH_CHARS = 1024
    private const val MAX_PATH_SEGMENT_CHARS = 255
    private const val MAX_JSON_DEPTH = 64
    private val allowedEntries = setOf("manifest.json", "handoff.md", "state.json", "transcript.jsonl")

    fun execute(riftRoot: File, args: JSONObject): JSONObject {
        val canonicalRoot = riftRoot.canonicalFile
        val bundleRoot = File(canonicalRoot, BUNDLE_DIR).apply { mkdirs() }.canonicalFile
        requireInside(canonicalRoot, bundleRoot)
        require(bundleRoot.isDirectory) { "Chat handoff bundle root is not a directory" }
        return when (args.optString("op", "list").trim().lowercase(Locale.US)) {
            "create" -> create(canonicalRoot, bundleRoot, args)
            "list" -> list(canonicalRoot, bundleRoot)
            "inspect" -> inspect(canonicalRoot, bundleRoot, args.getString("path"))
            "resume" -> resume(canonicalRoot, bundleRoot, args.getString("path"))
            "transcript" -> {
                val offset = args.optLong("offsetChars", 0L)
                val maxChars = args.optInt("maxChars", 32 * 1024)
                require(offset >= 0L) { "transcript offset must be non-negative" }
                require(maxChars in 1..MAX_TRANSCRIPT_CHARS) { "transcript maxChars must be between 1 and $MAX_TRANSCRIPT_CHARS" }
                transcript(canonicalRoot, bundleRoot, args.getString("path"), offset, maxChars)
            }
            else -> throw IllegalArgumentException("Unsupported chat handoff operation")
        }
    }

    private fun create(riftRoot: File, bundleRoot: File, args: JSONObject): JSONObject {
        val payloadFile = resolveRiftFile(riftRoot, args.getString("payloadPath"))
        require(payloadFile.isFile) { "Chat handoff payload not found" }
        val payloadBytes = readBounded(payloadFile, MAX_PAYLOAD_BYTES)
        val payload = JSONObject(decodeJsonUtf8(payloadBytes, "chat handoff payload"))
        if (payload.has("schema") && payload.opt("schema") != JSONObject.NULL) require(payload.opt("schema") is String) { "Chat handoff schema must be a string" }
        val schema = payload.optString("schema", PAYLOAD_SCHEMA)
        require(schema == PAYLOAD_SCHEMA) { "Unsupported chat handoff payload schema" }

        if (payload.has("title") && payload.opt("title") != JSONObject.NULL) require(payload.opt("title") is String) { "Chat handoff title must be a string" }
        if (payload.has("source") && payload.opt("source") != JSONObject.NULL) require(payload.opt("source") is String) { "Chat handoff source must be a string" }
        val title = payload.optString("title", "Chat Handoff").trim().ifBlank { "Chat Handoff" }
        require(title.length <= 160) { "Chat handoff title is too long" }
        val source = payload.optString("source", "chatgpt").trim().ifBlank { "chatgpt" }
        require(source.length <= 160) { "Chat handoff source label is too long" }
        val handoffValue = when {
            payload.has("handoff_markdown") -> payload.opt("handoff_markdown")
            payload.has("handoff") -> payload.opt("handoff")
            else -> null
        }
        require(handoffValue is String) { "handoff_markdown is required and must be a string" }
        val handoffText = handoffValue
        require(handoffText.isNotBlank()) { "handoff_markdown is required" }
        val handoff = handoffText.toByteArray(StandardCharsets.UTF_8)
        require(handoff.size <= MAX_HANDOFF_BYTES) { "handoff.md exceeds local handoff limit" }

        val stateValue = payload.opt("state")
        require(stateValue == null || stateValue == JSONObject.NULL || stateValue is JSONObject) { "state must be a JSON object" }
        val state = ((stateValue as? JSONObject) ?: JSONObject()).toString(2).toByteArray(StandardCharsets.UTF_8)
        require(state.size <= MAX_STATE_BYTES) { "state.json exceeds local handoff limit" }
        val transcript = transcriptBytes(riftRoot, payload)

        val entryMeta = JSONObject()
            .put("handoff.md", meta(handoff))
            .put("state.json", meta(state))
        if (transcript != null) entryMeta.put("transcript.jsonl", meta(transcript))

        val now = System.currentTimeMillis()
        val manifest = JSONObject()
            .put("schema", BUNDLE_SCHEMA)
            .put("format_version", 1)
            .put("title", title)
            .put("created_at_ms", now)
            .put("source", source)
            .put("payload_schema", schema)
            .put("transcript_included", transcript != null)
            .put("entries", entryMeta)
        val manifestBytes = manifest.toString(2).toByteArray(StandardCharsets.UTF_8)
        require(manifestBytes.size <= MAX_MANIFEST_BYTES) { "manifest exceeds local handoff limit" }

        val requested = args.optString("name").trim().ifBlank { title }
        require(requested.length <= 160) { "Chat handoff bundle name is too long" }
        val stem = slug(requested)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
        val destination = uniqueBundle(bundleRoot, "$stem-$stamp.riftchat")
        val temp = File(bundleRoot, ".${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(temp))).use { zip ->
                putEntry(zip, "manifest.json", manifestBytes)
                putEntry(zip, "handoff.md", handoff)
                putEntry(zip, "state.json", state)
                if (transcript != null) putEntry(zip, "transcript.jsonl", transcript)
            }
            require(temp.length() <= MAX_BUNDLE_BYTES) { ".riftchat bundle exceeds local bundle limit" }
            require(temp.renameTo(destination)) { "Could not publish chat handoff" }
        } finally {
            if (temp.exists()) temp.delete()
        }

        return JSONObject()
            .put("schema", BUNDLE_SCHEMA)
            .put("created", true)
            .put("path", displayPath(riftRoot, destination))
            .put("title", title)
            .put("bytes", destination.length())
            .put("sha256", sha256(destination))
            .put("transcript_included", transcript != null)
            .put("resume_command", "chat resume ${displayPath(riftRoot, destination)}")
    }

    private fun list(riftRoot: File, bundleRoot: File): JSONObject {
        val rows = JSONArray()
        val bundles = mutableListOf<File>()
        var scanned = 0
        Files.newDirectoryStream(bundleRoot.toPath()).use { stream ->
            for (path in stream) {
                scanned++
                require(scanned <= MAX_BUNDLES_SCANNED) { "Chat handoff directory exceeds scan limit" }
                val file = path.toFile()
                if (file.isFile && file.name.endsWith(".riftchat", true)) bundles += file
            }
        }
        bundles.sortedByDescending { it.lastModified() }
            .take(MAX_BUNDLES_LISTED)
            .forEach { file ->
                val manifest = runCatching { readManifest(file) }.getOrNull()
                rows.put(JSONObject()
                    .put("path", displayPath(riftRoot, file))
                    .put("title", manifest?.optString("title", file.name) ?: file.name)
                    .put("created_at_ms", manifest?.optLong("created_at_ms", file.lastModified()) ?: file.lastModified())
                    .put("bytes", file.length())
                    .put("transcript_included", manifest?.optBoolean("transcript_included", false) ?: false))
            }
        return JSONObject().put("schema", "rift.chat-handoff.list/1").put("bundles", rows).put("scanned", scanned)
    }

    private fun inspect(riftRoot: File, bundleRoot: File, rawPath: String): JSONObject {
        val bundle = resolveBundle(riftRoot, bundleRoot, rawPath)
        val manifest = readManifest(bundle)
        return JSONObject()
            .put("schema", "rift.chat-handoff.inspect/1")
            .put("path", displayPath(riftRoot, bundle))
            .put("bytes", bundle.length())
            .put("sha256", sha256(bundle))
            .put("manifest", manifest)
    }

    private fun resume(riftRoot: File, bundleRoot: File, rawPath: String): JSONObject {
        val bundle = resolveBundle(riftRoot, bundleRoot, rawPath)
        val manifest = readManifest(bundle)
        val handoff = readEntry(bundle, "handoff.md", MAX_HANDOFF_BYTES)
        val state = readEntry(bundle, "state.json", MAX_STATE_BYTES)
        verifyEntry(manifest, "handoff.md", handoff)
        verifyEntry(manifest, "state.json", state)
        val handoffMarkdown = decodeUtf8Text(handoff, "handoff.md")
        val stateJson = JSONObject(decodeJsonUtf8(state, "state.json"))
        return JSONObject()
            .put("schema", RESUME_SCHEMA)
            .put("path", displayPath(riftRoot, bundle))
            .put("bundle_sha256", sha256(bundle))
            .put("title", manifest.optString("title", "Chat Handoff"))
            .put("created_at_ms", manifest.optLong("created_at_ms", 0L))
            .put("handoff_markdown", handoffMarkdown)
            .put("state", stateJson)
            .put("transcript_included", manifest.optBoolean("transcript_included", false))
            .put("verified_entries", JSONArray().put("handoff.md").put("state.json"))
    }

    private fun transcript(riftRoot: File, bundleRoot: File, rawPath: String, offsetChars: Long, maxChars: Int): JSONObject {
        val bundle = resolveBundle(riftRoot, bundleRoot, rawPath)
        val manifest = readManifest(bundle)
        require(manifest.optBoolean("transcript_included", false)) { "Bundle has no transcript" }
        ZipFile(bundle).use { zip ->
            validateEntries(zip)
            val entry = zip.getEntry("transcript.jsonl") ?: throw IllegalArgumentException("Bundle transcript missing")
            require(entry.size < 0L || entry.size <= MAX_TRANSCRIPT_BYTES) { "Transcript exceeds local handoff limit" }
            verifyEntry(zip, manifest, "transcript.jsonl", MAX_TRANSCRIPT_BYTES)
            InputStreamReader(
                BufferedInputStream(zip.getInputStream(entry)),
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
            ).use { reader ->
                var skipped = 0L
                while (skipped < offsetChars) {
                    val amount = reader.skip(offsetChars - skipped)
                    if (amount <= 0L) {
                        if (reader.read() == -1) break
                        skipped++
                    } else skipped += amount
                }
                val buffer = CharArray(maxChars)
                var count = 0
                while (count < maxChars) {
                    val read = reader.read(buffer, count, maxChars - count)
                    if (read < 0) break
                    count += read
                }
                val text = String(buffer, 0, count)
                return JSONObject()
                    .put("schema", "rift.chat-handoff.transcript/1")
                    .put("path", displayPath(riftRoot, bundle))
                    .put("offset_chars", skipped)
                    .put("next_offset_chars", skipped + count)
                    .put("chars", count)
                    .put("maybe_more", count == maxChars)
                    .put("verified_entry", true)
                    .put("text", text)
            }
        }
    }

    private fun transcriptBytes(riftRoot: File, payload: JSONObject): ByteArray? {
        if (payload.has("transcript_path") && payload.opt("transcript_path") != JSONObject.NULL) require(payload.opt("transcript_path") is String) { "transcript_path must be a string" }
        val transcriptPath = payload.optString("transcript_path").trim()
        if (transcriptPath.isNotEmpty()) {
            val source = resolveRiftFile(riftRoot, transcriptPath)
            require(source.isFile) { "transcript_path not found" }
            return readBounded(source, MAX_TRANSCRIPT_BYTES)
        }
        val raw = payload.opt("transcript")
        if (raw == null || raw == JSONObject.NULL) return null
        val text = when (raw) {
            is JSONArray -> buildString {
                for (index in 0 until raw.length()) {
                    val value = raw.opt(index)
                    append(when (value) {
                        is JSONObject, is JSONArray -> value.toString()
                        else -> JSONObject().put("content", value).toString()
                    }).append('\n')
                }
            }
            is JSONObject -> raw.toString() + "\n"
            else -> raw.toString()
        }
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_TRANSCRIPT_BYTES) { "transcript exceeds local handoff limit" }
        return bytes
    }

    private fun readManifest(bundle: File): JSONObject {
        require(bundle.length() <= MAX_BUNDLE_BYTES) { ".riftchat bundle exceeds local bundle limit" }
        ZipFile(bundle).use { zip ->
            val seen = validateEntries(zip)
            val bytes = readEntry(zip, "manifest.json", MAX_MANIFEST_BYTES)
            val manifest = JSONObject(decodeJsonUtf8(bytes, "manifest.json"))
            validateManifest(manifest, seen)
            return manifest
        }
    }

    private fun validateEntries(zip: ZipFile): Set<String> {
        val seen = mutableSetOf<String>()
        val entries = zip.entries()
        var count = 0
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            count++
            require(count <= allowedEntries.size) { "Too many .riftchat entries" }
            require(!entry.isDirectory && entry.name in allowedEntries && seen.add(entry.name)) { "Invalid .riftchat entry" }
            val limit = entryLimit(entry.name)
            require(entry.size < 0L || entry.size <= limit) { ".riftchat entry exceeds limit" }
        }
        require("manifest.json" in seen && "handoff.md" in seen && "state.json" in seen) { "Incomplete .riftchat bundle" }
        return seen
    }

    private fun entryLimit(name: String): Long = when (name) {
        "manifest.json" -> MAX_MANIFEST_BYTES
        "handoff.md" -> MAX_HANDOFF_BYTES
        "state.json" -> MAX_STATE_BYTES
        "transcript.jsonl" -> MAX_TRANSCRIPT_BYTES
        else -> 0L
    }
    private fun validateManifest(manifest: JSONObject, seen: Set<String>) {
        fun requiredString(key: String): String {
            val value = manifest.opt(key)
            require(value is String) { "Manifest $key must be a string" }
            return value
        }
        fun requiredInteger(key: String): Long {
            val value = manifest.opt(key)
            require(value is Number) { "Manifest $key must be an integer" }
            val text = value.toString()
            require(Regex("-?[0-9]+").matches(text)) { "Manifest $key must be an integer" }
            return text.toLongOrNull() ?: throw IllegalArgumentException("Manifest $key is out of range")
        }

        require(requiredString("schema") == BUNDLE_SCHEMA) { "Unsupported .riftchat schema" }
        require(requiredInteger("format_version") == 1L) { "Unsupported .riftchat format version" }
        val title = requiredString("title").trim()
        require(title.isNotEmpty() && title.length <= 160) { "Invalid .riftchat title" }
        val source = requiredString("source").trim()
        require(source.isNotEmpty() && source.length <= 160) { "Invalid .riftchat source" }
        require(requiredString("payload_schema") == PAYLOAD_SCHEMA) { "Unsupported .riftchat payload schema" }
        require(requiredInteger("created_at_ms") >= 0L) { "Invalid .riftchat created_at_ms" }

        val transcriptValue = manifest.opt("transcript_included")
        require(transcriptValue is Boolean) { "Manifest transcript_included must be a boolean" }
        val transcriptPresent = "transcript.jsonl" in seen
        require(transcriptValue == transcriptPresent) { ".riftchat transcript flag mismatch" }

        val metadata = manifest.opt("entries")
        require(metadata is JSONObject) { "Manifest entries metadata missing" }
        val metadataNames = mutableSetOf<String>()
        val keys = metadata.keys()
        while (keys.hasNext()) metadataNames += keys.next()
        val expectedNames = seen.filterTo(mutableSetOf()) { it != "manifest.json" }
        require(metadataNames == expectedNames) { "Manifest entry metadata does not match bundle entries" }

        val hashPattern = Regex("^[A-Fa-f0-9]{64}$")
        for (name in expectedNames) {
            val rawItem = metadata.opt(name)
            require(rawItem is JSONObject) { "Manifest metadata missing for $name" }
            val rawSize = rawItem.opt("size")
            require(rawSize is Number && Regex("[0-9]+").matches(rawSize.toString())) { "Invalid $name size metadata" }
            val size = rawSize.toString().toLongOrNull() ?: throw IllegalArgumentException("Invalid $name size metadata")
            val limit = entryLimit(name)
            if (name == "handoff.md" || name == "state.json") require(size in 1L..limit) { "Invalid $name size metadata" }
            else require(size in 0L..limit) { "Invalid $name size metadata" }
            val rawHash = rawItem.opt("sha256")
            require(rawHash is String && hashPattern.matches(rawHash)) { "Invalid $name SHA-256 metadata" }
        }
    }

    private fun readEntry(bundle: File, name: String, limit: Long): ByteArray =
        ZipFile(bundle).use { zip -> validateEntries(zip); readEntry(zip, name, limit) }

    private fun readEntry(zip: ZipFile, name: String, limit: Long): ByteArray {
        val entry = zip.getEntry(name) ?: throw IllegalArgumentException("Missing $name")
        BufferedInputStream(zip.getInputStream(entry)).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            var total = 0L
            while (true) {
                RiftDeadline.check("chat handoff read")
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= limit) { "$name exceeds local handoff limit" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    private fun verifyEntry(manifest: JSONObject, name: String, bytes: ByteArray) {
        val expected = manifest.getJSONObject("entries").getJSONObject(name)
        require(expected.getLong("size") == bytes.size.toLong()) { "$name size mismatch" }
        require(expected.getString("sha256").equals(sha256(bytes), true)) { "$name hash mismatch" }
    }

    private fun verifyEntry(zip: ZipFile, manifest: JSONObject, name: String, limit: Long) {
        val expected = manifest.getJSONObject("entries").getJSONObject(name)
        val entry = zip.getEntry(name) ?: throw IllegalArgumentException("Missing $name")
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        BufferedInputStream(zip.getInputStream(entry)).use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                RiftDeadline.check("chat handoff verification")
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= limit) { "$name exceeds local handoff limit" }
                digest.update(buffer, 0, count)
            }
        }
        if (entry.size >= 0L) require(entry.size == total) { "$name ZIP size mismatch" }
        require(expected.getLong("size") == total) { "$name size mismatch" }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        require(expected.getString("sha256").equals(actualHash, true)) { "$name hash mismatch" }
    }

    private fun decodeUtf8Text(bytes: ByteArray, label: String): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: Throwable) {
        throw IllegalArgumentException("$label is not valid UTF-8", error)
    }

    private fun decodeJsonUtf8(bytes: ByteArray, label: String): String {
        requireJsonNesting(bytes, label)
        return decodeUtf8Text(bytes, label)
    }

    private fun requireJsonNesting(bytes: ByteArray, label: String) {
        val stack = CharArray(MAX_JSON_DEPTH)
        var depth = 0
        var inString = false
        var escaped = false
        for (raw in bytes) {
            val ch = (raw.toInt() and 0xff).toChar()
            if (inString) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') inString = false
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{', '[' -> {
                    require(depth < MAX_JSON_DEPTH) { "$label exceeds JSON nesting depth $MAX_JSON_DEPTH" }
                    stack[depth++] = ch
                }
                '}', ']' -> {
                    require(depth > 0) { "$label has invalid JSON nesting" }
                    val open = stack[--depth]
                    require((open == '{' && ch == '}') || (open == '[' && ch == ']')) { "$label has mismatched JSON nesting" }
                }
            }
        }
        require(!inString && depth == 0) { "$label has incomplete JSON structure" }
    }

    private fun meta(bytes: ByteArray): JSONObject = JSONObject()
        .put("size", bytes.size)
        .put("sha256", sha256(bytes))

    private fun putEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun readBounded(file: File, limit: Long): ByteArray {
        require(file.length() <= limit) { "Input exceeds local handoff limit" }
        FileInputStream(file).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            var total = 0L
            while (true) {
                RiftDeadline.check("chat handoff input")
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= limit) { "Input exceeds local handoff limit" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    private fun resolveRiftFile(riftRoot: File, rawPath: String): File {
        val normalized = rawPath.replace('\\', '/').trim()
        require(normalized.isNotBlank()) { "Path is required" }
        require(normalized.length <= MAX_PATH_CHARS) { "RiftFS path is too long" }
        val segments = normalized.trim('/').split('/').filter { it.isNotBlank() }
        require(segments.none { it == "." || it == ".." || it.indexOf('\u0000') >= 0 || it.length > MAX_PATH_SEGMENT_CHARS }) { "Invalid RiftFS path" }
        val file = segments.fold(riftRoot) { parent, segment -> File(parent, segment) }.canonicalFile
        requireInside(riftRoot, file)
        return file
    }

    private fun resolveBundle(riftRoot: File, bundleRoot: File, rawPath: String): File {
        val candidate = if (rawPath.contains('/') || rawPath.contains('\\')) {
            resolveRiftFile(riftRoot, rawPath)
        } else {
            File(bundleRoot, if (rawPath.endsWith(".riftchat", true)) rawPath else "$rawPath.riftchat").canonicalFile
        }
        requireInside(bundleRoot, candidate)
        require(candidate.isFile && candidate.name.endsWith(".riftchat", true)) { ".riftchat bundle not found" }
        require(candidate.length() <= MAX_BUNDLE_BYTES) { ".riftchat bundle exceeds local bundle limit" }
        return candidate
    }

    private fun requireInside(root: File, file: File) {
        val rootPath = root.canonicalFile.toPath()
        val filePath = file.canonicalFile.toPath()
        require(filePath == rootPath || filePath.startsWith(rootPath)) { "Path escapes RiftFS scope" }
    }

    private fun uniqueBundle(root: File, requestedName: String): File {
        val direct = File(root, requestedName)
        if (!direct.exists()) return direct
        val stem = requestedName.removeSuffix(".riftchat")
        for (index in 2..9999) {
            val candidate = File(root, "$stem-$index.riftchat")
            if (!candidate.exists()) return candidate
        }
        throw IllegalStateException("Could not allocate a duplicate-safe chat handoff name")
    }

    private fun slug(value: String): String {
        val normalized = value.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-', '.', '_')
            .take(64)
        return normalized.ifBlank { "chat-handoff" }
    }

    private fun displayPath(riftRoot: File, file: File): String {
        val relative = riftRoot.canonicalFile.toPath().relativize(file.canonicalFile.toPath()).toString().replace(File.separatorChar, '/')
        return "/$relative"
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                RiftDeadline.check("chat handoff hash")
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
