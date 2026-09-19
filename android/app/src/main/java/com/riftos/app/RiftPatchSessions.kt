package com.riftos.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Process-local patch-session/provenance correlation for workspace mutations.
 *
 * Writers declare workspace paths before mutation and commit a short-lived claim afterwards.
 * Exact file claims are bound to resulting SHA-256 state. Directory claims are explicitly
 * lower-confidence scope claims. Workspace Records never invents an attributed writer.
 */
internal object RiftPatchSessions {
    const val VERSION = 1
    private const val CLAIM_TTL_MS = 15_000L
    private const val MAX_PATHS = 512
    private const val MAX_ACTIVE_CLAIMS = 4096
    private const val MAX_LABEL_CHARS = 120
    private const val MAX_INTENT_CHARS = 500

    data class FileState(
        val exists: Boolean,
        val kind: String,
        val size: Long,
        val modified: Long,
        val sha256: String?
    )

    data class Handle(
        val patchId: String,
        val origin: String,
        val operation: String,
        val intent: String?,
        val requestId: String?,
        val startedAt: Long,
        val paths: List<String>,
        val before: Map<String, FileState>
    )

    private data class Claim(
        val handle: Handle,
        val path: String,
        val recursive: Boolean,
        val before: FileState,
        val after: FileState,
        val committedAt: Long,
        val expiresAt: Long
    )

    private val lock = Any()
    private val claims = ArrayList<Claim>()

    fun begin(
        context: Context,
        origin: String,
        operation: String,
        intent: String?,
        requestId: String?,
        rawPaths: Collection<String>
    ): Handle? {
        val paths = rawPaths.mapNotNull(::normalizeWorkspacePath).distinct().sorted()
        if (paths.isEmpty()) return null
        require(paths.size <= MAX_PATHS) { "Patch provenance path set exceeds $MAX_PATHS entries" }

        val root = workspaceRoot(context)
        val before = LinkedHashMap<String, FileState>()
        for (path in paths) before[path] = fileState(root, path)

        val now = System.currentTimeMillis()
        return Handle(
            patchId = "patch-$now-${UUID.randomUUID().toString().take(12)}",
            origin = bounded(origin, MAX_LABEL_CHARS, "unknown"),
            operation = bounded(operation, MAX_LABEL_CHARS, "mutation"),
            intent = intent?.trim()?.take(MAX_INTENT_CHARS)?.takeIf { it.isNotBlank() },
            requestId = requestId?.trim()?.take(MAX_LABEL_CHARS)?.takeIf { it.isNotBlank() },
            startedAt = now,
            paths = paths,
            before = before
        )
    }

    fun commit(context: Context, handle: Handle): JSONObject {
        val root = workspaceRoot(context)
        val now = System.currentTimeMillis()
        val pending = ArrayList<Claim>(handle.paths.size)
        for (path in handle.paths) {
            val before = handle.before.getValue(path)
            val after = fileState(root, path)
            pending += Claim(
                handle = handle,
                path = path,
                recursive = before.kind == "directory" || after.kind == "directory",
                before = before,
                after = after,
                committedAt = now,
                expiresAt = now + CLAIM_TTL_MS
            )
        }
        synchronized(lock) {
            pruneLocked(now)
            claims += pending
            if (claims.size > MAX_ACTIVE_CLAIMS) {
                claims.subList(0, claims.size - MAX_ACTIVE_CLAIMS).clear()
            }
        }
        return JSONObject()
            .put("version", VERSION)
            .put("patchId", handle.patchId)
            .put("origin", handle.origin)
            .put("operation", handle.operation)
            .put("intent", handle.intent ?: JSONObject.NULL)
            .put("requestId", handle.requestId ?: JSONObject.NULL)
            .put("startedAt", handle.startedAt)
            .put("committedAt", now)
            .put("paths", handle.paths.size)
    }

    fun abort(handle: Handle) {
        synchronized(lock) {
            claims.removeAll { it.handle.patchId == handle.patchId }
        }
    }

    fun resolve(path: String, currentExists: Boolean, currentSha256: String?): JSONObject? {
        val clean = normalizeWorkspaceRelative(path) ?: return null
        val now = System.currentTimeMillis()
        synchronized(lock) {
            pruneLocked(now)
            for (index in claims.lastIndex downTo 0) {
                val claim = claims[index]
                if (claim.path != clean || !stateMatches(claim.after, currentExists, currentSha256)) continue
                claims.removeAt(index)
                return claimJson(claim, "state-bound")
            }
            for (index in claims.lastIndex downTo 0) {
                val claim = claims[index]
                if (!claim.recursive) continue
                val inScope = claim.path.isBlank() || clean.startsWith(claim.path + "/")
                if (inScope) return claimJson(claim, "scope-bound")
            }
        }
        return null
    }

    fun unattributed(recordId: String, path: String, source: String, at: Long): JSONObject =
        JSONObject()
            .put("version", VERSION)
            .put("patchId", "unattributed-${recordId.take(MAX_LABEL_CHARS)}")
            .put("origin", "unattributed-local")
            .put("operation", source.take(MAX_LABEL_CHARS))
            .put("intent", JSONObject.NULL)
            .put("requestId", JSONObject.NULL)
            .put("startedAt", at)
            .put("committedAt", at)
            .put("attributed", false)
            .put("confidence", "none")
            .put("scopePath", normalizeWorkspaceRelative(path) ?: path.take(500))
            .put("before", JSONObject.NULL)
            .put("after", JSONObject.NULL)

    private fun claimJson(claim: Claim, confidence: String): JSONObject =
        JSONObject()
            .put("version", VERSION)
            .put("patchId", claim.handle.patchId)
            .put("origin", claim.handle.origin)
            .put("operation", claim.handle.operation)
            .put("intent", claim.handle.intent ?: JSONObject.NULL)
            .put("requestId", claim.handle.requestId ?: JSONObject.NULL)
            .put("startedAt", claim.handle.startedAt)
            .put("committedAt", claim.committedAt)
            .put("attributed", true)
            .put("confidence", confidence)
            .put("scopePath", claim.path)
            .put("before", stateJson(claim.before))
            .put("after", stateJson(claim.after))

    private fun stateJson(state: FileState): JSONObject =
        JSONObject()
            .put("exists", state.exists)
            .put("kind", state.kind)
            .put("size", state.size)
            .put("modified", state.modified)
            .put("sha256", state.sha256 ?: JSONObject.NULL)

    private fun stateMatches(expected: FileState, exists: Boolean, sha256: String?): Boolean {
        if (expected.exists != exists) return false
        if (!exists) return true
        return expected.kind == "file" && expected.sha256 != null && expected.sha256 == sha256
    }

    private fun fileState(root: File, path: String): FileState {
        val target = workspaceFile(root, path)
        return when {
            !target.exists() -> FileState(false, "missing", 0L, 0L, null)
            target.isDirectory -> FileState(true, "directory", 0L, target.lastModified(), null)
            target.isFile -> FileState(true, "file", target.length(), target.lastModified(), digestFile(target))
            else -> FileState(true, "other", target.length(), target.lastModified(), null)
        }
    }

    private fun workspaceRoot(context: Context): File =
        File(context.applicationContext.filesDir, "riftfs/workspace").apply { mkdirs() }.canonicalFile

    private fun workspaceFile(root: File, path: String): File {
        val target = if (path.isBlank()) root else File(root, path).canonicalFile
        require(target == root || target.path.startsWith(root.path + File.separator)) {
            "Patch provenance path escaped workspace"
        }
        return target
    }

    private fun normalizeWorkspacePath(raw: String): String? {
        var value = raw.trim().replace('\\', '/')
        if (value.isBlank()) return null
        if (
            value.startsWith("/D:/Workspace", ignoreCase = true) ||
            value.startsWith("D:/Workspace", ignoreCase = true)
        ) {
            value = RiftVolumePaths.resolveRelative(value)
        } else {
            value = value.trimStart('/')
            if (!value.equals("workspace", ignoreCase = true) &&
                !value.startsWith("workspace/", ignoreCase = true)) return null
        }
        if (value.equals("workspace", ignoreCase = true)) return ""
        value = value.substringAfter('/')
        return normalizeWorkspaceRelative(value)
    }

    private fun normalizeWorkspaceRelative(raw: String): String? {
        val value = raw.trim().replace('\\', '/').trim('/')
        if (value.isBlank()) return ""
        if (value.startsWith("C:", ignoreCase = true) || value.startsWith("D:", ignoreCase = true)) return null
        val parts = value.split('/').filter { it.isNotBlank() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." || it.contains('\u0000') || it.contains(':') }) return null
        return parts.joinToString("/")
    }

    private fun pruneLocked(now: Long) {
        claims.removeAll { it.expiresAt < now }
    }

    private fun digestFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                RiftDeadline.check("patch provenance hash")
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun bounded(value: String, max: Int, fallback: String): String =
        value.trim().take(max).ifBlank { fallback }
}
