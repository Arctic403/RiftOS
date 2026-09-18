package com.riftos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Native Dev Lab control plane.
 *
 * Owns staging, snapshots, provenance and atomic publication without WebView/JavaScript.
 * Browser-execution actions are intentionally rejected here and must be routed through RiftBrowser.
 */
object RiftNativeDevLab {
    private const val MAX_TEXT_BYTES = 2L * 1024L * 1024L
    private const val MAX_STAGED_FILES = 256
    private const val PROJECT_REL = "workspace/RiftOS-main"
    private val textExtensions = setOf(
        "txt","md","markdown","json","jsonl","js","mjs","cjs","ts","tsx","jsx","css","html","htm",
        "xml","svg","csv","tsv","yaml","yml","toml","ini","conf","cfg","log","kt","kts","java","py",
        "rs","go","c","cc","cpp","cxx","h","hpp","hh","cs","sh","bash","zsh","gradle","properties","riftpp"
    )

    fun execute(context: Context, request: JSONObject): JSONObject {
        val action = request.optString("action").trim().lowercase()
        require(action.isNotBlank()) { "Dev Lab action is required" }
        return when (action) {
            "status" -> status(context)
            "load" -> loadSource(context, request.optString("path"))
            "staged" -> listStaged(context)
            "stage" -> stage(context, request.optString("path"), request.optString("text"), request.optString("reason"))
            "stage-file" -> {
                val source = resolveRiftPath(context, request.optString("sourcePath"), request.optString("cwd", "/"))
                require(source.isFile) { "source file not found: ${request.optString("sourcePath")}" }
                require(source.length() <= MAX_TEXT_BYTES) { "source file exceeds Dev Lab limit" }
                stage(context, request.optString("path"), source.readText(Charsets.UTF_8), request.optString("reason"))
            }
            "delete" -> stageDelete(context, request.optString("path"), request.optString("reason"))
            "unstage" -> JSONObject().put("unstaged", unstage(context, request.optString("path")))
            "reset" -> JSONObject().put("reset", reset(context))
            "snapshot" -> createSnapshot(context, request.optString("note"))
            "snapshots" -> listSnapshots(context, request.optInt("limit", 50))
            "load-snapshot" -> loadSnapshot(context, resolveSnapshotId(context, request.optString("snapshotId")))
            "preview" -> previewSnapshot(context, resolveSnapshotId(context, request.optString("snapshotId")))
            "publish" -> publishSnapshot(context, resolveSnapshotId(context, request.optString("snapshotId")))
            "run", "run-file", "css", "css-off", "open" ->
                throw IllegalStateException("Dev Lab web execution moved to RiftBrowser; native browser runner is required for action: $action")
            "runs" -> JSONObject().put("runs", JSONArray()).put("count", 0)
            else -> throw IllegalArgumentException("Unsupported native Dev Lab action: $action")
        }
    }

    private fun roots(context: Context): Roots {
        val rift = File(context.applicationContext.filesDir, "riftfs").apply { mkdirs() }.canonicalFile
        val lab = File(rift, "system/devlab").apply { mkdirs() }.canonicalFile
        val stage = File(lab, "stage").apply { mkdirs() }
        val snapshots = File(lab, "snapshots").apply { mkdirs() }
        val publications = File(lab, "publications").apply { mkdirs() }
        val transactions = File(lab, "transactions").apply { mkdirs() }
        val project = File(rift, PROJECT_REL).canonicalFile
        return Roots(rift, lab, stage, snapshots, publications, transactions, File(lab, "state.json"), project)
    }

    private data class Roots(
        val rift: File,
        val lab: File,
        val stage: File,
        val snapshots: File,
        val publications: File,
        val transactions: File,
        val state: File,
        val project: File
    )

    private fun defaultState() = JSONObject()
        .put("format", "riftos-devlab-state-native")
        .put("version", 2)
        .put("project", "RiftOS-main")
        .put("baselineHeadSha", JSONObject.NULL)
        .put("staged", JSONObject())
        .put("latestSnapshot", JSONObject.NULL)
        .put("lastPublished", JSONObject.NULL)
        .put("updatedAt", System.currentTimeMillis())

    private fun loadState(r: Roots): JSONObject {
        val parsed = if (r.state.isFile) runCatching { JSONObject(r.state.readText(Charsets.UTF_8)) }.getOrNull() else null
        return parsed ?: defaultState().also { saveState(r, it) }
    }

    private fun saveState(r: Roots, state: JSONObject) {
        state.put("updatedAt", System.currentTimeMillis())
        atomicWrite(r.state, state.toString(2).toByteArray(Charsets.UTF_8))
    }

    private fun sourcePath(raw: String): String {
        var value = raw.trim().replace('\\', '/').trimStart('/')
        if (value.startsWith("RiftOS-main/")) value = value.removePrefix("RiftOS-main/")
        require(value.isNotBlank() && value != "." && value != "..") { "Dev Lab source path must be project-relative" }
        val parts = value.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." || it.contains('\u0000') }) { "Unsafe Dev Lab source path" }
        val name = parts.last().lowercase()
        val ext = name.substringAfterLast('.', "")
        require(ext in textExtensions || name in setOf("readme","license","makefile","dockerfile","cmakelists.txt")) {
            "Dev Lab only stages text/source files: $value"
        }
        return parts.joinToString("/")
    }

    private fun projectFile(r: Roots, relative: String): File = confined(r.project, sourcePath(relative))
    private fun stageFile(r: Roots, relative: String): File = confined(r.stage, sourcePath(relative))

    private fun confined(root: File, relative: String): File {
        val file = File(root, relative).canonicalFile
        require(file == root || file.path.startsWith(root.path + File.separator)) { "Path escaped Dev Lab root" }
        return file
    }

    private fun currentHead(r: Roots): String {
        val meta = File(r.project, ".riftgit.json")
        return if (meta.isFile) runCatching { JSONObject(meta.readText()).optString("headSha", "unknown") }.getOrDefault("unknown") else "unknown"
    }

    private fun baseline(r: Roots, path: String, staged: JSONObject): Pair<Boolean,String?> {
        val prior = staged.optJSONObject(path)
        if (prior != null) return prior.optBoolean("base_exists") to prior.optString("base_sha256").takeIf { it.isNotBlank() }
        val file = projectFile(r, path)
        if (!file.isFile) return false to null
        require(file.length() <= MAX_TEXT_BYTES) { "Baseline file exceeds Dev Lab limit: $path" }
        return true to sha256(file)
    }

    private fun stage(context: Context, rawPath: String, text: String, reason: String): JSONObject {
        val r = roots(context)
        val path = sourcePath(rawPath)
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_TEXT_BYTES) { "Dev Lab source editor limit is 2 MiB per file" }
        val state = loadState(r)
        val staged = state.optJSONObject("staged") ?: JSONObject().also { state.put("staged", it) }
        require(staged.length() < MAX_STAGED_FILES || staged.has(path)) { "Dev Lab staged-file limit is $MAX_STAGED_FILES" }
        val base = baseline(r, path, staged)
        if (staged.length() == 0) state.put("baselineHeadSha", currentHead(r))
        val target = stageFile(r, path)
        target.parentFile?.mkdirs()
        atomicWrite(target, bytes)
        val entry = JSONObject()
            .put("path", path).put("action", "write")
            .put("base_exists", base.first).put("base_sha256", base.second ?: JSONObject.NULL)
            .put("stage_sha256", sha256(target))
            .put("reason", reason.take(500)).put("stagedAt", System.currentTimeMillis())
        staged.put(path, entry)
        saveState(r, state)
        return entry
    }

    private fun stageDelete(context: Context, rawPath: String, reason: String): JSONObject {
        val r = roots(context)
        val path = sourcePath(rawPath)
        val state = loadState(r)
        val staged = state.optJSONObject("staged") ?: JSONObject().also { state.put("staged", it) }
        val base = baseline(r, path, staged)
        require(base.first) { "Cannot stage deletion of missing source: $path" }
        if (staged.length() == 0) state.put("baselineHeadSha", currentHead(r))
        stageFile(r, path).delete()
        val entry = JSONObject()
            .put("path", path).put("action", "delete")
            .put("base_exists", true).put("base_sha256", base.second ?: JSONObject.NULL)
            .put("stage_sha256", JSONObject.NULL)
            .put("reason", reason.take(500)).put("stagedAt", System.currentTimeMillis())
        staged.put(path, entry)
        saveState(r, state)
        return entry
    }

    private fun unstage(context: Context, rawPath: String): Boolean {
        val r = roots(context)
        val path = sourcePath(rawPath)
        val state = loadState(r)
        val staged = state.optJSONObject("staged") ?: JSONObject()
        staged.remove(path)
        stageFile(r, path).delete()
        if (staged.length() == 0) state.put("baselineHeadSha", JSONObject.NULL)
        saveState(r, state)
        return true
    }

    private fun reset(context: Context): Boolean {
        val r = roots(context)
        r.stage.deleteRecursively(); r.stage.mkdirs()
        val state = loadState(r)
        state.put("staged", JSONObject()).put("baselineHeadSha", JSONObject.NULL)
        saveState(r, state)
        return true
    }

    private fun loadSource(context: Context, rawPath: String): JSONObject {
        val r = roots(context)
        val path = sourcePath(rawPath)
        val state = loadState(r)
        val entry = state.optJSONObject("staged")?.optJSONObject(path)
        val file = if (entry?.optString("action") == "write") stageFile(r, path) else projectFile(r, path)
        val exists = file.isFile
        val content = if (exists) {
            require(file.length() <= MAX_TEXT_BYTES) { "Source exceeds Dev Lab limit" }
            file.readText(Charsets.UTF_8)
        } else ""
        return JSONObject()
            .put("path", path).put("content", content).put("exists", exists)
            .put("staged", entry ?: JSONObject.NULL)
            .put("workspaceHeadSha", currentHead(r))
            .put("labBaselineHeadSha", state.opt("baselineHeadSha") ?: JSONObject.NULL)
    }

    private fun listStaged(context: Context): JSONObject {
        val state = loadState(roots(context))
        val staged = state.optJSONObject("staged") ?: JSONObject()
        val out = JSONArray()
        staged.keys().asSequence().sorted().forEach { out.put(staged.getJSONObject(it)) }
        return JSONObject().put("entries", out).put("count", out.length())
    }

    private fun createSnapshot(context: Context, note: String): JSONObject {
        val r = roots(context)
        val state = loadState(r)
        val staged = state.optJSONObject("staged") ?: JSONObject()
        require(staged.length() > 0) { "Nothing is staged in RiftOS Dev Lab" }
        val entries = JSONArray()
        staged.keys().asSequence().sorted().forEach { path ->
            val entry = JSONObject(staged.getJSONObject(path).toString())
            if (entry.optString("action") == "write") entry.put("content", stageFile(r, path).readText(Charsets.UTF_8))
            entries.put(entry)
        }
        val id = "snapshot-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(12)}"
        val snapshot = JSONObject()
            .put("format", "riftos-devlab-snapshot-native").put("version", 2)
            .put("id", id).put("createdAt", System.currentTimeMillis()).put("note", note.take(1000))
            .put("baselineHeadSha", state.opt("baselineHeadSha") ?: currentHead(r))
            .put("capturedHeadSha", currentHead(r)).put("entries", entries)
        atomicWrite(File(r.snapshots, "$id.json"), snapshot.toString(2).toByteArray())
        state.put("latestSnapshot", id); saveState(r, state)
        return snapshot
    }

    private fun listSnapshots(context: Context, limit: Int): JSONObject {
        val r = roots(context)
        val items = r.snapshots.listFiles()?.filter { it.isFile && it.extension == "json" }
            ?.sortedByDescending { it.lastModified() }?.take(limit.coerceIn(1,200)).orEmpty()
        val out = JSONArray()
        for (file in items) runCatching { JSONObject(file.readText()) }.getOrNull()?.let(out::put)
        return JSONObject().put("snapshots", out).put("count", out.length())
    }

    private fun resolveSnapshotId(context: Context, raw: String): String {
        val r = roots(context)
        if (raw.isNotBlank() && raw != "latest") return safeId(raw)
        val state = loadState(r)
        val latest = state.optString("latestSnapshot")
        if (latest.isNotBlank()) return safeId(latest)
        val newest = r.snapshots.listFiles()?.filter { it.isFile && it.extension == "json" }?.maxByOrNull { it.lastModified() }
        return newest?.nameWithoutExtension ?: throw IllegalStateException("No Dev Lab snapshot is available")
    }

    private fun loadSnapshot(context: Context, id: String): JSONObject {
        val file = File(roots(context).snapshots, "${safeId(id)}.json")
        require(file.isFile) { "Dev Lab snapshot not found: $id" }
        return JSONObject(file.readText(Charsets.UTF_8))
    }

    private fun previewSnapshot(context: Context, id: String): JSONObject {
        val r = roots(context)
        val snapshot = loadSnapshot(context, id)
        val entries = snapshot.getJSONArray("entries")
        val conflicts = JSONArray()
        val baselineHead = snapshot.optString("baselineHeadSha").trim().takeIf { it.isNotBlank() && it != "unknown" && it != "null" }
        val currentHead = currentHead(r)
        val headConflict = baselineHead != null && currentHead != "unknown" && baselineHead != currentHead
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val path = entry.getString("path")
            val current = projectFile(r, path)
            val expectedExists = entry.optBoolean("base_exists")
            val currentExists = current.isFile
            val typeConflict = current.exists() && !current.isFile
            val expectedSha = entry.optString("base_sha256").takeIf { it.isNotBlank() }
            val actualSha = if (currentExists) sha256(current) else null
            if (typeConflict || expectedExists != currentExists || (expectedExists && expectedSha != actualSha)) {
                conflicts.put(JSONObject()
                    .put("path", path)
                    .put("expected", expectedSha ?: JSONObject.NULL)
                    .put("actual", if (typeConflict) "<non-file>" else actualSha ?: JSONObject.NULL))
            }
        }
        return JSONObject()
            .put("snapshotId", id).put("changes", entries.length())
            .put("conflicts", conflicts)
            .put("baselineHeadSha", baselineHead ?: JSONObject.NULL)
            .put("currentHeadSha", currentHead)
            .put("headConflict", headConflict)
            .put("safeToPublish", conflicts.length() == 0 && !headConflict)
    }

    private fun publishSnapshot(context: Context, id: String): JSONObject {
        val r = roots(context)
        val snapshot = loadSnapshot(context, id)
        val preview = previewSnapshot(context, id)
        require(preview.optBoolean("safeToPublish")) { "Dev Lab publish aborted: project changed since staging" }
        val entries = snapshot.getJSONArray("entries")
        val tx = File(r.transactions, "tx-${System.currentTimeMillis()}-${UUID.randomUUID()}").apply { mkdirs() }
        val applied = ArrayList<String>()
        try {
            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                val path = sourcePath(entry.getString("path"))
                val target = projectFile(r, path)
                val backup = confined(tx, path)
                if (target.isFile) {
                    backup.parentFile?.mkdirs()
                    target.copyTo(backup, overwrite = false)
                }
                when (entry.getString("action")) {
                    "write" -> {
                        target.parentFile?.mkdirs()
                        atomicWrite(target, entry.getString("content").toByteArray(Charsets.UTF_8))
                    }
                    "delete" -> if (target.exists() && !target.delete()) throw IllegalStateException("Could not delete $path")
                    else -> throw IllegalArgumentException("Unknown Dev Lab snapshot action")
                }
                applied += path
            }
        } catch (error: Throwable) {
            val rollbackFailures = ArrayList<String>()
            for (path in applied.asReversed()) {
                val target = projectFile(r, path)
                val backup = confined(tx, path)
                runCatching {
                    if (backup.isFile) {
                        target.parentFile?.mkdirs()
                        backup.copyTo(target, overwrite = true)
                    } else if (target.exists() && !target.delete()) {
                        throw IllegalStateException("Could not remove newly-created target")
                    }
                }.onFailure { rollbackFailures += "$path: ${it.message ?: it.javaClass.simpleName}" }
            }
            if (rollbackFailures.isNotEmpty()) {
                throw IllegalStateException(
                    "Dev Lab publish failed and rollback was incomplete; recovery data kept at ${tx.absolutePath}: ${rollbackFailures.joinToString("; ")}",
                    error
                )
            }
            tx.deleteRecursively()
            throw IllegalStateException("Dev Lab publish rolled back: ${error.message}", error)
        }

        val receiptId = "publish-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(12)}"
        val receipt = JSONObject()
            .put("format", "riftos-devlab-publication-native").put("version", 2)
            .put("id", receiptId).put("snapshotId", id).put("publishedAt", System.currentTimeMillis())
            .put("changes", entries.length()).put("preview", preview)
        atomicWrite(File(r.publications, "$receiptId.json"), receipt.toString(2).toByteArray())
        tx.deleteRecursively()

        val state = loadState(r)
        val staged = state.optJSONObject("staged") ?: JSONObject()
        for (i in 0 until entries.length()) {
            val path = entries.getJSONObject(i).getString("path")
            staged.remove(path); stageFile(r, path).delete()
        }
        if (staged.length() == 0) state.put("baselineHeadSha", JSONObject.NULL)
        state.put("lastPublished", JSONObject().put("snapshotId", id).put("receiptId", receiptId).put("publishedAt", System.currentTimeMillis()))
        saveState(r, state)
        return JSONObject().put("published", true).put("snapshotId", id).put("receiptId", receiptId).put("changes", entries.length())
    }

    private fun status(context: Context): JSONObject {
        val r = roots(context)
        val state = loadState(r)
        return JSONObject()
            .put("available", true).put("backend", "android-native-devlab").put("webViewRequired", false)
            .put("root", "/system/devlab").put("project", "RiftOS-main")
            .put("workspaceHeadSha", currentHead(r))
            .put("baselineHeadSha", state.opt("baselineHeadSha") ?: JSONObject.NULL)
            .put("staged", state.optJSONObject("staged")?.length() ?: 0)
            .put("snapshots", r.snapshots.listFiles()?.count { it.isFile && it.extension == "json" } ?: 0)
            .put("latestSnapshot", state.opt("latestSnapshot") ?: JSONObject.NULL)
            .put("lastPublished", state.opt("lastPublished") ?: JSONObject.NULL)
            .put("browserExecutionRequired", true)
    }

    private fun resolveRiftPath(context: Context, raw: String, cwd: String): File {
        val root = roots(context).rift
        var display = raw.trim().replace('\\','/')
        if (!display.startsWith("/") && !Regex("^[A-Za-z]:").containsMatchIn(display)) display = "${cwd.trimEnd('/')}/$display"
        val normalized = RiftVolumePaths.normalizeDisplay(display)
        val rel = if (normalized.startsWith("/C:", true) || normalized.startsWith("/D:", true)) RiftVolumePaths.resolveRelative(normalized) else normalized.trimStart('/')
        return confined(root, rel)
    }

    private fun safeId(raw: String): String {
        val id = raw.trim()
        require(id.matches(Regex("^[A-Za-z0-9._-]+$"))) { "Invalid Dev Lab snapshot id" }
        return id
    }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.tmp-${UUID.randomUUID()}")
        val backup = File(target.parentFile, ".${target.name}.backup-${UUID.randomUUID()}")
        tmp.writeBytes(bytes)
        var backedUp = false
        try {
            if (target.exists()) {
                require(target.renameTo(backup)) { "Could not stage existing ${target.name} for atomic replacement" }
                backedUp = true
            }
            require(tmp.renameTo(target)) { "Atomic write failed for ${target.name}" }
            if (backedUp) backup.delete()
        } catch (error: Throwable) {
            tmp.delete()
            if (backedUp && !target.exists()) backup.renameTo(target)
            throw error
        }
    }
}
